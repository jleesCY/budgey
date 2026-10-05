package com.jlees.budgey.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
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
        val name = "${UUID.randomUUID()}.webp"
        val target = File(dir, name)
        try {
            ImageDecode.withLocalFile(context, uri) { src ->
                val bitmap = ImageDecode.decodeUpright(src, maxDimension)
                if (bitmap != null) {
                    try { encode(bitmap, target) } finally { bitmap.recycle() }
                } else {
                    // Not something we can decode: keep it as-is, unless it's far too big to be a picture.
                    if (src.length() > ImageDecode.MAX_RAW_COPY_BYTES) error("That file is too large to use as a picture")
                    src.copyTo(target, overwrite = true)
                }
            }
        } catch (t: Throwable) {
            target.delete() // never leave a half-written picture behind
            throw t
        }
        name
    }

    private fun encode(bitmap: Bitmap, target: File) {
        target.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, out)
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
            val bmp = ImageDecode.decodeUpright(f, maxDimension)
            if (bmp == null) { after += old; return@forEach }
            val tmp = File(dir, f.name + ".tmp")
            try { runCatching { encode(bmp, tmp) } } finally { bmp.recycle() }
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

    /**
     * Deletes every picture not in [referenced]. Only files from before [before] are touched, so a
     * photo you're adding right now (not saved anywhere yet) is never swept away.
     * (Also removes leftover ".tmp" files from an interrupted re-compress.)
     */
    fun cleanupOrphans(referenced: Set<String>, before: Long = Long.MAX_VALUE) {
        dir.listFiles()?.forEach { if (it.name !in referenced && it.lastModified() < before) it.delete() }
    }
}
