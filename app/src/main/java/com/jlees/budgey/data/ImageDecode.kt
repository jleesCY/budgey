package com.jlees.budgey.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File

/**
 * Reading pictures the user picked or shared. A picture from another app is copied to a private
 * temporary file ONCE and read from there: some apps only let a shared picture be opened a single
 * time, and decoding needs it three times (size, pixels, orientation).
 */
object ImageDecode {
    /** Pictures larger than this that can't be decoded aren't copied as-is (they're not a photo). */
    const val MAX_RAW_COPY_BYTES = 20L * 1024 * 1024

    /** Runs [block] with a local file for [uri] (a temporary copy unless it's already a file). */
    fun <T> withLocalFile(context: Context, uri: Uri, block: (File) -> T): T {
        val direct = uri.takeIf { it.scheme == "file" }?.path?.let(::File)?.takeIf { it.isFile }
        if (direct != null) return block(direct)
        val dir = File(context.cacheDir, "scan").apply { mkdirs() } // swept at start-up if anything's left
        val tmp = File(dir, "source-${System.nanoTime()}")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: error("Couldn't open that picture")
            input.use { i -> tmp.outputStream().use { i.copyTo(it) } }
            return block(tmp)
        } finally {
            tmp.delete()
        }
    }

    /**
     * Decodes [file] at most [maxDim] px on its longest side, turned upright from its EXIF
     * orientation — rotations and mirror images (front cameras, some scanners) alike.
     */
    fun decodeUpright(file: File, maxDim: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        val raw = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val exif = runCatching { ExifInterface(file) }.getOrNull()
        val rotation = exif?.rotationDegrees?.toFloat() ?: 0f
        val flipped = exif?.isFlipped == true
        val scale = minOf(1f, maxDim.toFloat() / maxOf(raw.width, raw.height))
        if (scale >= 1f && rotation == 0f && !flipped) return raw
        val m = Matrix().apply {
            postScale(if (flipped) -scale else scale, scale) // mirror first, then turn
            postRotate(rotation)
        }
        // createBitmap can hand back the same bitmap; only recycle the original if it's a copy.
        Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also { if (it !== raw) raw.recycle() }
    }.getOrNull()
}
