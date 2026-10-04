package com.jlees.budgey.data

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * Short-lived files in the app's cache. Each is deleted by whoever made it as soon as it's done;
 * [startupCleanup] sweeps up anything left behind by a crash or the app being killed mid-way.
 *
 * - cacheDir/camera — photos the camera app wrote for us (copied into app storage right away)
 * - cacheDir/scan   — the picture handed to an AI model during a scan
 * - cacheDir/import — a backup being restored
 */
object TempFiles {
    fun cameraDir(context: Context) = File(context.cacheDir, "camera").apply { mkdirs() }
    fun importDir(context: Context) = File(context.cacheDir, "import")
    private fun scanDir(context: Context) = File(context.cacheDir, "scan")

    /** True when [uri] is one of our own camera captures (safe to delete once copied). */
    fun isCameraFile(context: Context, uri: Uri?): Boolean {
        val path = uri?.takeIf { it.scheme == "file" }?.path ?: return false
        return runCatching { File(path).canonicalFile.parentFile == cameraDir(context).canonicalFile }.getOrDefault(false)
    }

    /** Deletes [uri] if it's our camera capture (pictures from Photos/Files are never touched). */
    fun releaseCamera(context: Context, uri: Uri?) {
        if (isCameraFile(context, uri)) runCatching { File(uri!!.path!!).delete() }
    }

    /** Deletes camera captures older than [maxAgeMs] (a capture in progress is always newer). */
    fun pruneCamera(context: Context, maxAgeMs: Long = 3600_000L) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        cameraDir(context).listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    /** Runs at app start: deletes leftovers from before [launchedAt] (newer files are in use). */
    fun startupCleanup(context: Context, launchedAt: Long) {
        fun sweep(dir: File) = dir.walkBottomUp().filter { it != dir && it.lastModified() < launchedAt }.forEach { it.delete() }
        runCatching { sweep(importDir(context)) }
        runCatching { sweep(scanDir(context)) }
        runCatching { pruneCamera(context) }
    }
}
