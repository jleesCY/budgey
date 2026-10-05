package com.jlees.budgey.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import java.time.LocalDate

/** Gemini Nano availability on this phone. */
/** [UNKNOWN]: the phone's AI service didn't answer this time (busy, updating) — not the same as unsupported. */
enum class NanoStatus { UNSUPPORTED, NEEDS_DOWNLOAD, DOWNLOADING, READY, UNKNOWN }

/**
 * Gemini Nano through ML Kit's GenAI Prompt API. The model belongs to the phone (Android's
 * AICore service), so Budgey doesn't download or store anything; on supported phones that
 * haven't fetched it yet, AICore downloads it itself.
 */
class NanoScanner {
    private val model by lazy { Generation.getClient() }

    suspend fun status(): NanoStatus = runCatching {
        when (model.checkStatus()) {
            FeatureStatus.AVAILABLE -> NanoStatus.READY
            FeatureStatus.DOWNLOADABLE -> NanoStatus.NEEDS_DOWNLOAD
            FeatureStatus.DOWNLOADING -> NanoStatus.DOWNLOADING
            FeatureStatus.UNAVAILABLE -> NanoStatus.UNSUPPORTED
            else -> NanoStatus.UNKNOWN
        }
    }.getOrElse { e ->
        if (e is kotlinx.coroutines.CancellationException) throw e
        // A hiccup in AICore isn't proof the phone can't run it: don't switch anyone's scanner over it.
        NanoStatus.UNKNOWN
    }

    /** Asks the phone to fetch Gemini Nano. Emits progress as a fraction-less byte count, then null when done. */
    fun download(): Flow<String> = model.download().map { s ->
        when (s) {
            is DownloadStatus.DownloadStarted -> "Starting…"
            is DownloadStatus.DownloadProgress -> "${s.totalBytesDownloaded / 1_000_000} MB downloaded"
            DownloadStatus.DownloadCompleted -> "done"
            is DownloadStatus.DownloadFailed -> "failed: ${s.e.message}"
        }
    }

    suspend fun warmUp() {
        runCatching { model.warmup() }
    }

    /**
     * Reads the picture (vision only). If this phone's Gemini Nano can't take images, it returns
     * null and the scan simply uses the Standard reader's result.
     */
    suspend fun read(image: File, today: LocalDate, prompt: String? = null, maxTokens: Int = 256): String? {
        val bitmap = decode(image) ?: return null
        var finished = false
        val viaImage = try {
            // A stuck answer shouldn't leave the scan spinning forever: after 30 s, fall back to Standard.
            kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                model.generateContent(
                    generateContentRequest(ImagePart(bitmap), TextPart(prompt ?: SmartScanParser.prompt(today))) {
                        temperature = 0.1f
                        topK = 1
                        maxOutputTokens = maxTokens
                    }
                ).candidates.firstOrNull()?.text
            }.also { finished = it != null }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            finished = true
            null
        } finally {
            // Freed once Gemini Nano is done with it. After a timeout or cancel it may still be
            // reading the picture, so it's left to the garbage collector instead.
            if (finished) bitmap.recycle()
        }
        return viaImage?.takeIf { it.isNotBlank() }
    }

    private fun decode(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1024) sample *= 2
        return runCatching { BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) }.getOrNull()
    }
}
