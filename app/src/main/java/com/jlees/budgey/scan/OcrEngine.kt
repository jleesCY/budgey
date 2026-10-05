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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
            ?: return OcrScan(listOf(read(InputImage.fromFilePath(context, uri), "original")))
        val pages = ArrayList<OcrPage>()
        // Bitmaps are freed as soon as they're no longer needed — and on errors/cancellation too.
        var big: Bitmap? = original
        var small: Bitmap? = null
        fun free(b: Bitmap?) { if (b != null && !b.isRecycled) b.recycle() }
        try {
            pages += read(InputImage.fromBitmap(original, 0), "original")

            // Cleaned-up copies at a smaller size (faster, and plenty for display digits).
            val sm = scaleDown(original, 1600)
            small = sm
            if (sm !== original) { free(original); big = null } // the full-size photo isn't needed any more
            val w = sm.width
            val h = sm.height
            val gray = withContext(Dispatchers.Default) {
                val px = IntArray(w * h)
                sm.getPixels(px, 0, w, 0, 0, w, h)
                ImageEnhance.toGray(px)
            }
            free(sm); small = null; big = null
            for (invert in listOf(false, true)) {
                val bmp = withContext(Dispatchers.Default) {
                    val out = ImageEnhance.toArgb(ImageEnhance.enhance(gray, w, h, invert))
                    Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
                }
                try {
                    pages += read(InputImage.fromBitmap(bmp, 0), if (invert) "inverted" else "enhanced")
                } catch (e: CancellationException) {
                    throw e // leaving the screen stops the scan (it used to carry on regardless)
                } catch (e: Exception) {
                    // One cleaned-up pass failing isn't fatal: the others still vote.
                } finally {
                    free(bmp)
                }
            }
        } finally {
            free(small)
            free(big)
        }
        return OcrScan(pages)
    }

    /**
     * The photo exactly as taken (just turned upright and sized to ~1280 px, high-quality JPEG)
     * for the AI models. They get this one untouched picture — the black & white / inverted copies
     * are only for Google's text reader.
     */
    suspend fun prepareModelImage(uri: Uri): File? = withContext(Dispatchers.IO) {
        val bmp = decodeUpright(uri, maxDim = 1280) ?: return@withContext null
        // A unique name, so two scans at once (e.g. the check splitter and a purchase) can't clash.
        // The caller deletes it when done; anything left over is cleared at the next start.
        val out = File(File(context.cacheDir, "scan").apply { mkdirs() }, "model-input-${System.nanoTime()}.jpg")
        try {
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        } catch (t: Throwable) {
            out.delete()
            throw t
        } finally {
            bmp.recycle()
        }
        out
    }

    private suspend fun read(image: InputImage, variant: String): OcrPage {
        // ML Kit keeps reading the bitmap until its task finishes, even if we stop waiting. So the wait
        // isn't cancellable: the caller recycles the bitmap only once ML Kit is done with it (a pass
        // takes well under a second), and cancellation is honoured right after.
        val text = withContext(NonCancellable) {
            suspendCancellableCoroutine<Text> { cont ->
                recognizer.process(image)
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
        }
        currentCoroutineContext().ensureActive()
        return OcrPage(toRows(text), variant)
    }

    private fun scaleDown(src: Bitmap, maxDim: Int): Bitmap {
        val scale = maxDim.toFloat() / maxOf(src.width, src.height)
        if (scale >= 1f) return src
        return Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true)
    }

    /** Decodes with EXIF rotation applied, so box positions match the photo as you see it. */
    private fun decodeUpright(uri: Uri, maxDim: Int): Bitmap? = runCatching {
        com.jlees.budgey.data.ImageDecode.withLocalFile(context, uri) { com.jlees.budgey.data.ImageDecode.decodeUpright(it, maxDim) }
    }.getOrNull()

    private data class Piece(val text: String, val left: Int, val centerY: Float, val height: Int)

    /**
     * ML Kit groups text into blocks, which on receipts often splits the label column
     * ("TOTAL") from the value column ("23.45"). Re-assemble rows by vertical position.
     * On a tilted photo a row slopes, so positions are first straightened by the text's typical
     * angle — otherwise "TOTAL" on the left could pair with the tax amount on the right.
     */
    private fun toRows(text: Text): List<OcrLine> {
        val lines = text.textBlocks.flatMap { it.lines }.filter { it.boundingBox != null }
        if (lines.isEmpty()) return text.text.lines().map { OcrLine(it) }
        val angles = lines.map { it.angle }.filter { !it.isNaN() && abs(it) < 45f }.sorted()
        val tilt = if (angles.isEmpty()) 0f else angles[angles.size / 2]
        val slope = if (abs(tilt) < 0.5f) 0f else kotlin.math.tan(Math.toRadians(tilt.toDouble())).toFloat()
        val pieces = lines.map { line ->
            val box = line.boundingBox!!
            // Straighten: a point further right sits lower on a clockwise-tilted photo.
            Piece(line.text, box.left, box.exactCenterY() - box.exactCenterX() * slope, box.height())
        }
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
