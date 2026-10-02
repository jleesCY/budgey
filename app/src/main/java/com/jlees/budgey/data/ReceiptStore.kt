package com.jlees.budgey.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Receipt / screenshot images live in app-private storage (filesDir/receipts) so they are
 * never visible to other apps and get included in exports.
 *
 * Every image is downscaled and re-encoded on the way in. A 12 MP phone photo (3–5 MB) ends up
 * around 100–250 KB — still sharp enough to read a receipt. OCR always runs on the original
 * before this happens, so compression never hurts detection.
 */
class ReceiptStore(
    private val context: Context,
    folder: String = "receipts",
    /** Longest side after downscaling (receipts stay readable; icons are tiny). */
    private val maxDimension: Int = 1600,
    /** 0–100. WebP at 60 is visually close to JPEG at 85 but about a third of the size. */
    private val quality: Int = 60,
) {
    val dir: File = File(context.filesDir, folder).apply { mkdirs() }

    fun file(name: String): File = File(dir, name)

    /** Copies an image into private storage, downscaled and compressed. Returns the file name. */
    suspend fun importImage(uri: Uri): String = withContext(Dispatchers.IO) {
        val name = "${UUID.randomUUID()}.${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "webp" else "jpg"}"
        val target = File(dir, name)
        val bitmap = decodeScaled(uri, maxDim = maxDimension)
        if (bitmap != null) {
            encode(bitmap, target)
            bitmap.recycle()
        } else {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }
        name
    }

    private fun encode(bitmap: Bitmap, target: File) {
        target.outputStream().use { out ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, out)
            else bitmap.compress(Bitmap.CompressFormat.JPEG, quality + 10, out)
        }
    }

    /** Total bytes used by this folder. */
    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    /**
     * Re-compresses images saved by older versions (which kept them much larger). Keeps each file
     * name, skips pictures already saved the new way, and only replaces a file when that saves
     * at least 15% — so running it again never keeps degrading quality.
     * Returns bytes before → after.
     */
    suspend fun recompressAll(): Pair<Long, Long> = withContext(Dispatchers.IO) {
        var before = 0L
        var after = 0L
        dir.listFiles()?.forEach { f ->
            val old = f.length()
            before += old
            if (f.name.endsWith(".webp") || f.name.endsWith(".tmp")) { after += old; return@forEach }
            val bmp = decodeScaled(Uri.fromFile(f), maxDim = maxDimension)
            if (bmp == null) { after += old; return@forEach }
            val tmp = File(dir, f.name + ".tmp")
            runCatching { encode(bmp, tmp) }
            bmp.recycle()
            if (tmp.exists() && tmp.length() in 1 until (old * 85 / 100)) {
                tmp.renameTo(f)
                after += f.length()
            } else {
                tmp.delete()
                after += old
            }
        }
        before to after
    }

    fun delete(name: String) {
        file(name).delete()
    }

    /** Deletes image files no longer referenced by any purchase or subscription. */
    fun cleanupOrphans(referenced: Set<String>) {
        dir.listFiles()?.forEach { if (it.name !in referenced) it.delete() }
    }

    private fun decodeScaled(uri: Uri, maxDim: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > maxDim * 2 || bounds.outHeight / sample > maxDim * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return null
        val rotation = context.contentResolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        val scale = minOf(1f, maxDim.toFloat() / maxOf(raw.width, raw.height))
        if (scale >= 1f && rotation == 0f) return raw
        val m = Matrix().apply { postScale(scale, scale); postRotate(rotation) }
        Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also { if (it != raw) raw.recycle() }
    }.getOrNull()
}
