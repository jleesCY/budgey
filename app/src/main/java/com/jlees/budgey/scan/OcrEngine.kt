package com.jlees.budgey.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import java.io.File

/**
 * On-device OCR using ML Kit's *bundled* Latin model — the model ships inside the APK,
 * so recognition works in airplane mode and nothing leaves the phone.
 *
 * Each picture is read three times: as-is, as a cleaned-up black & white copy with thicker
 * strokes, and as an inverted copy (for light digits on a dark display). The parser then lets
 * the passes vote — see [ReceiptParser.combine].
 */
class OcrEngine(private val context: Context) {
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    suspend fun scan(uri: Uri): OcrScan {
        val original = withContext(Dispatchers.IO) { decodeUpright(uri, maxDim = 2400) }
            ?: return OcrScan(listOf(read(InputImage.fromFilePath(context, uri), 1, 1, "original").copy(boxes = emptyList())), 1f)
        val aspect = original.width.toFloat() / original.height
        val pages = ArrayList<OcrPage>()
        pages += read(InputImage.fromBitmap(original, 0), original.width, original.height, "original")

        // Cleaned-up copies at a smaller size (faster, and plenty for display digits).
        val small = scaleDown(original, 1600)
        val w = small.width
        val h = small.height
        val gray = withContext(Dispatchers.Default) {
            val px = IntArray(w * h)
            small.getPixels(px, 0, w, 0, 0, w, h)
            ImageEnhance.toGray(px)
        }
        for (invert in listOf(false, true)) {
            val bmp = withContext(Dispatchers.Default) {
                val out = ImageEnhance.toArgb(ImageEnhance.enhance(gray, w, h, invert))
                Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
            }
            runCatching { read(InputImage.fromBitmap(bmp, 0), w, h, if (invert) "inverted" else "enhanced") }
                .onSuccess { pages += it }
            bmp.recycle()
        }
        if (small !== original) small.recycle()
        original.recycle()
        return OcrScan(pages, aspect)
    }

    /**
     * The photo exactly as taken (just turned upright and sized to ~1280 px, high-quality JPEG)
     * for the AI models. They get this one untouched picture — the black & white / inverted copies
     * are only for Google's text reader.
     */
    suspend fun prepareModelImage(uri: Uri): File? = withContext(Dispatchers.IO) {
        val bmp = decodeUpright(uri, maxDim = 1280) ?: return@withContext null
        val out = File(File(context.cacheDir, "scan").apply { mkdirs() }, "model-input.jpg")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bmp.recycle()
        out
    }

    /** Older entry point: rows from the plain image only. */
    suspend fun readRows(uri: Uri): List<OcrLine> = scan(uri).pages.first().lines

    private suspend fun read(image: InputImage, w: Int, h: Int, variant: String): OcrPage {
        val text = suspendCancellableCoroutine<Text> { cont ->
            recognizer.process(image)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
        val fw = w.toFloat().coerceAtLeast(1f)
        val fh = h.toFloat().coerceAtLeast(1f)
        val boxes = ArrayList<TextBox>()
        for (block in text.textBlocks) for (line in block.lines) {
            line.boundingBox?.let { b -> boxes += TextBox(line.text, b.left / fw, b.top / fh, b.right / fw, b.bottom / fh) }
            for (el in line.elements) {
                val b = el.boundingBox ?: continue
                boxes += TextBox(el.text, b.left / fw, b.top / fh, b.right / fw, b.bottom / fh)
            }
        }
        return OcrPage(toRows(text), boxes, variant)
    }

    private fun scaleDown(src: Bitmap, maxDim: Int): Bitmap {
        val scale = maxDim.toFloat() / maxOf(src.width, src.height)
        if (scale >= 1f) return src
        return Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true)
    }

    /** Decodes with EXIF rotation applied, so box positions match the photo as you see it. */
    private fun decodeUpright(uri: Uri, maxDim: Int): Bitmap? = runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) sample *= 2
        val raw = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rotation = resolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        val scale = minOf(1f, maxDim.toFloat() / maxOf(raw.width, raw.height))
        if (rotation == 0f && scale >= 1f) return raw
        val m = Matrix().apply { postScale(scale, scale); postRotate(rotation) }
        Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also { if (it != raw) raw.recycle() }
    }.getOrNull()

    private data class Piece(val text: String, val left: Int, val centerY: Float, val height: Int)

    /**
     * ML Kit groups text into blocks, which on receipts often splits the label column
     * ("TOTAL") from the value column ("23.45"). Re-assemble rows by vertical position.
     */
    private fun toRows(text: Text): List<OcrLine> {
        val pieces = text.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            Piece(line.text, box.left, box.exactCenterY(), box.height())
        }
        if (pieces.isEmpty()) return text.text.lines().map { OcrLine(it) }
        val sorted = pieces.sortedBy { it.centerY }
        val rows = mutableListOf<MutableList<Piece>>()
        for (p in sorted) {
            val row = rows.lastOrNull()
            val rowY = row?.map { it.centerY }?.average()?.toFloat()
            val tolerance = (row?.maxOf { it.height } ?: p.height) * 0.5f
            if (row != null && rowY != null && abs(p.centerY - rowY) <= tolerance) row += p else rows += mutableListOf(p)
        }
        // Text height relative to the typical row: headline amounts / merchant names stand out.
        val heights = rows.map { r -> r.maxOf { it.height } }.sorted()
        val median = heights[heights.size / 2].coerceAtLeast(1).toFloat()
        return rows.map { r ->
            OcrLine(r.sortedBy { it.left }.joinToString("  ") { it.text }, r.maxOf { it.height } / median)
        }
    }
}
