package com.jlees.budgey.scan

import android.app.ActivityManager
import android.app.DownloadManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/** Where a downloadable model stands on this phone. */
sealed interface ModelState {
    data object NotDownloaded : ModelState
    data class Downloading(val fraction: Float, val bytesDone: Long, val paused: Boolean) : ModelState
    data class Ready(val file: File) : ModelState
    data class Failed(val reason: String) : ModelState
}

/**
 * Manages the downloadable vision model (Vision AI) and talks to it. The model itself runs with
 * Google's LiteRT-LM in a separate process ([ModelService]) so a crash there can't take Budgey down.
 *
 * Models are downloaded on request with Android's own download manager (works in the background,
 * survives the app closing, shows a notification, resumes after network drops) into the app's
 * private storage. They can also be put there with `./gradlew :app:pushScanModel` or imported
 * from a file. The internet is only ever used for these downloads.
 */
class SmartScanner(private val context: Context) {
    private val mutex = Mutex()
    private var releaseJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs = context.getSharedPreferences("smart_scan", Context.MODE_PRIVATE)
    private val downloads = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    /** Downloads and `adb push` land here (app-specific storage, removed with the app). */
    val modelDir: File get() = (context.getExternalFilesDir("models") ?: File(context.filesDir, "models")).apply { mkdirs() }
    /** Imported copies. */
    private val importDir: File get() = File(context.filesDir, "models").apply { mkdirs() }

    // ---------------------------------------------------------------- device

    fun totalRamBytes(): Long {
        val info = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        return info.totalMem
    }

    /** True if this phone has enough memory for [engine] (nominal 8 GB phones report ~7.3 GB). */
    fun fits(engine: ScanEngine): Boolean =
        Build.VERSION.SDK_INT >= 31 && totalRamBytes() >= engine.minRamGb * 900_000_000L

    fun freeSpaceBytes(): Long = runCatching { StatFs(modelDir.path).availableBytes }.getOrDefault(0L)

    // ---------------------------------------------------------------- files & downloads

    fun modelFile(engine: ScanEngine): File? {
        val name = engine.file ?: return null
        return listOf(modelDir, importDir).map { File(it, name) }
            .firstOrNull { it.isFile && it.length() >= engine.bytes * 95 / 100 }
    }

    fun state(engine: ScanEngine): ModelState {
        if (!engine.downloadable) return ModelState.NotDownloaded
        val id = prefs.getLong(dlKey(engine), -1L)
        if (id >= 0) {
            downloads.query(DownloadManager.Query().setFilterById(id))?.use { c ->
                if (c.moveToFirst()) {
                    val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).takeIf { it > 0 } ?: engine.bytes
                    when (status) {
                        DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING ->
                            return ModelState.Downloading(done.toFloat() / total, done, paused = false)
                        DownloadManager.STATUS_PAUSED ->
                            return ModelState.Downloading(done.toFloat() / total, done, paused = true)
                        DownloadManager.STATUS_FAILED -> {
                            val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                            downloads.remove(id)
                            prefs.edit().remove(dlKey(engine)).putString(failKey(engine), failReason(reason)).apply()
                        }
                        else -> prefs.edit().remove(dlKey(engine)).apply() // finished
                    }
                } else prefs.edit().remove(dlKey(engine)).apply()
            }
        }
        modelFile(engine)?.let { return ModelState.Ready(it) }
        // Keep showing why the last download failed until it's retried.
        return prefs.getString(failKey(engine), null)?.let { ModelState.Failed(it) } ?: ModelState.NotDownloaded
    }

    /**
     * Starts downloading [engine]'s model. [wifiOnly] keeps big downloads off mobile data.
     * Returns an error message, or null if the download started.
     */
    fun startDownload(engine: ScanEngine, wifiOnly: Boolean): String? {
        val url = engine.url ?: return "Nothing to download"
        if (freeSpaceBytes() < engine.bytes + 300_000_000L) {
            return "Not enough free space: needs ${engine.bytes / 1_000_000} MB, ${freeSpaceBytes() / 1_000_000} MB free"
        }
        File(modelDir, engine.file!!).delete()
        prefs.edit().remove(failKey(engine)).apply()
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Budgey: ${engine.title}")
            .setDescription("AI model for smarter scanning")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, "models", engine.file)
            .setAllowedOverMetered(!wifiOnly)
            .setAllowedOverRoaming(false)
        prefs.edit().putLong(dlKey(engine), downloads.enqueue(request)).apply()
        return null
    }

    fun cancelDownload(engine: ScanEngine) {
        val id = prefs.getLong(dlKey(engine), -1L)
        if (id >= 0) downloads.remove(id)
        prefs.edit().remove(dlKey(engine)).apply()
        engine.file?.let { File(modelDir, it).delete() }
    }

    /** The runtime's own cache (compiled GPU programs, weight caches) for one model. */
    private fun cacheFor(engine: ScanEngine): File = File(context.cacheDir, "litertlm/${engine.name}").apply { mkdirs() }

    /**
     * Unloads the model right away (you switched scanners, deleted it, or Android needs memory).
     * Unless [force], it waits for a scan that's running rather than cutting it off.
     */
    suspend fun release(force: Boolean = false) = withContext(Dispatchers.Main) {
        if (force || pending.isEmpty()) disconnect()
    }

    /**
     * Housekeeping, run at startup and when Settings opens:
     *  • deletes leftovers — half-finished imports (.part), files of models Budgey no longer offers,
     *    and partial downloads that aren't downloading any more;
     *  • clears the runtime cache of any model that isn't on the phone.
     * Returns the bytes freed.
     */
    suspend fun cleanupStorage(): Long = withContext(Dispatchers.IO) {
        var freed = 0L
        val known = ScanEngine.entries.filter { it.downloadable }
        // Stop downloads of models Budgey no longer offers, and forget their bookkeeping.
        prefs.all.keys.filter { it.startsWith("download_") || it.startsWith("failed_") }.forEach { key ->
            val name = key.substringAfter('_')
            if (known.none { it.name == name }) {
                if (key.startsWith("download_")) runCatching { downloads.remove(prefs.getLong(key, -1L)) }
                prefs.edit().remove(key).apply()
            }
        }
        // Refreshing each model's state also clears download IDs Android has forgotten.
        val active = known.filter { state(it) is ModelState.Downloading }.mapNotNull { it.file }.toSet()
        val recent = System.currentTimeMillis() - 10 * 60_000L
        for (dir in listOf(modelDir, importDir)) {
            dir.listFiles()?.forEach { f ->
                val engine = known.firstOrNull { it.file == f.name }
                val junk = when {
                    f.name in active -> false
                    f.name.endsWith(".part") && f.lastModified() > recent -> false // an import may be running
                    engine == null -> true // .part files, removed models
                    else -> f.length() < engine.bytes * 95 / 100 // incomplete, not downloading
                }
                if (junk) { freed += f.length(); f.delete() }
            }
        }
        known.filter { modelFile(it) == null }.forEach { e ->
            val dir = File(context.cacheDir, "litertlm/${e.name}")
            if (dir.exists()) { freed += dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }; dir.deleteRecursively() }
        }
        // Caches of models that are no longer offered at all.
        File(context.cacheDir, "litertlm").listFiles()?.filter { d -> known.none { it.name == d.name } }?.forEach { d ->
            freed += d.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            d.deleteRecursively()
        }
        freed
    }

    /** Deletes [engine]'s model from the phone. */
    suspend fun delete(engine: ScanEngine) = mutex.withLock {
        val name = engine.file ?: return@withLock
        // Unload first so the file isn't in use while it's deleted.
        release(force = true)
        cancelDownload(engine)
        prefs.edit().remove(failKey(engine)).apply()
        File(importDir, name).delete()
        File(modelDir, name).delete()
        cacheFor(engine).deleteRecursively()
        Unit
    }

    /** Copies a picked model file (e.g. gemma-4-E2B-it.litertlm) into app storage. */
    suspend fun importModel(uri: Uri, displayName: String, onProgress: (Float) -> Unit): ScanEngine = withContext(Dispatchers.IO) {
        val engine = ScanEngine.forFile(displayName)
            ?: error("Unknown model file \"$displayName\". Supported: ${ScanEngine.entries.mapNotNull { it.file }.joinToString()}")
        val resolver = context.contentResolver
        val total = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }?.takeIf { it > 0 } ?: engine.bytes
        val tmp = File(importDir, "${engine.file}.part")
        try { resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Couldn't open that file" }
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 20)
                var copied = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    copied += n
                    onProgress(copied.toFloat() / total)
                }
            }
        }
        } catch (e: Exception) { tmp.delete(); throw e }
        tmp.renameTo(File(importDir, engine.file!!))
        engine
    }

    private fun dlKey(engine: ScanEngine) = "download_${engine.name}"
    private fun failKey(engine: ScanEngine) = "failed_${engine.name}"

    private fun failReason(code: Int) = when (code) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Not enough storage space"
        DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_CANNOT_RESUME -> "The connection dropped — try again"
        DownloadManager.ERROR_FILE_ERROR -> "Couldn't save the file"
        else -> "Download failed (code $code) — try again"
    }

    // ---------------------------------------------------------------- running a model

    /*
     * The model runs in a separate process (ModelService, ":ai"). If it crashes, only that process
     * dies: we notice through the binder, fail the scan with [ModelCrashedException], and drop the
     * connection (which also stops Android restarting it). All of this runs on the main thread, so
     * the connection state needs no locking.
     */
    private var service: Messenger? = null
    private var binder: IBinder? = null
    private var connection: ServiceConnection? = null
    private val connecting = mutableListOf<CompletableDeferred<Messenger>>()
    private val pending = mutableMapOf<Int, CompletableDeferred<String?>>()
    private var nextId = 1

    private val replies = Messenger(Handler(Looper.getMainLooper()) { msg ->
        if (msg.what == ModelService.MSG_REPLY) {
            val error = msg.data.getString(ModelService.KEY_ERROR)
            val text = msg.data.getString(ModelService.KEY_TEXT)
            pending.remove(msg.arg1)?.let { d ->
                if (error != null) d.completeExceptionally(IllegalStateException(error)) else d.complete(text)
            }
        }
        true
    })

    private val death = IBinder.DeathRecipient {
        Handler(Looper.getMainLooper()).post { lost(crashed = true) }
    }

    /** The model's process is gone (crashed) or we let it go: fail anything waiting and unbind. */
    private fun lost(crashed: Boolean) {
        val error = if (crashed) ModelCrashedException() else java.util.concurrent.CancellationException("Model unloaded")
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
        connecting.forEach { it.completeExceptionally(error) }
        connecting.clear()
        runCatching { binder?.unlinkToDeath(death, 0) }
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        service = null
        binder = null
    }

    private fun disconnect() {
        releaseJob?.cancel()
        if (connection != null) lost(crashed = false)
    }

    /** Connects to the model process, starting it if needed. Main thread. */
    private suspend fun connect(): Messenger {
        service?.let { return it }
        val d = CompletableDeferred<Messenger>()
        connecting += d
        if (connection == null) {
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, b: IBinder) {
                    binder = b
                    runCatching { b.linkToDeath(death, 0) }.onFailure { lost(crashed = true); return }
                    val m = Messenger(b)
                    service = m
                    connecting.forEach { it.complete(m) }
                    connecting.clear()
                }
                // Called when the model process dies.
                override fun onServiceDisconnected(name: ComponentName?) = lost(crashed = true)
                override fun onBindingDied(name: ComponentName?) = lost(crashed = true)
                override fun onNullBinding(name: ComponentName?) = lost(crashed = true)
            }
            connection = conn
            val ok = context.bindService(Intent(context, ModelService::class.java), conn, Context.BIND_AUTO_CREATE)
            if (!ok) { lost(crashed = false); throw IllegalStateException("Couldn't start the AI model") }
        }
        return d.await()
    }

    private fun request(model: File, which: ScanEngine): Bundle = Bundle().apply {
        putString(ModelService.KEY_MODEL, model.path)
        putString(ModelService.KEY_CACHE, cacheFor(which).path)
        // Phones with lots of memory run the language part on the GPU too (several times faster);
        // on smaller phones that can run out of memory, so they use the CPU.
        putBoolean(ModelService.KEY_GPU, totalRamBytes() >= 11_000_000_000L)
    }

    /** Loads [engine] in the background (e.g. when the scan screen opens) so the scan itself is quicker. */
    fun warmUp(engine: ScanEngine) {
        val model = modelFile(engine) ?: return
        if (!engine.vision) return
        scope.launch(Dispatchers.Main) {
            runCatching {
                connect().send(Message.obtain(null, ModelService.MSG_WARM_UP).apply { data = request(model, engine) })
            }
            scheduleRelease()
        }
    }

    /**
     * Asks [engine] about the picture (the raw photo only). Returns the model's raw reply
     * (JSON, see [SmartScanParser]).
     * @throws ModelCrashedException if the model's process died (Budgey itself keeps running).
     */
    suspend fun read(engine: ScanEngine, image: File, today: java.time.LocalDate, prompt: String? = null): String? {
        if (!engine.vision) return null
        val model = modelFile(engine) ?: return null
        // The model process reads the picture from a file (app storage is shared between our processes).
        val jpeg = withContext(Dispatchers.Default) { jpegForModel(image, engine.squareInput) } ?: return null
        val input = File(context.cacheDir, "scan/model-${System.nanoTime()}.jpg").apply { parentFile?.mkdirs(); writeBytes(jpeg) }
        return try {
            withContext(Dispatchers.Main) {
                releaseJob?.cancel()
                val id = nextId++
                val reply = CompletableDeferred<String?>()
                pending[id] = reply
                try {
                    val m = connect()
                    m.send(Message.obtain(null, ModelService.MSG_READ, id, 0).apply {
                        replyTo = replies
                        data = request(model, engine).apply {
                            putString(ModelService.KEY_IMAGE, input.path)
                            putString(ModelService.KEY_PROMPT, prompt ?: SmartScanParser.prompt(today))
                        }
                    })
                    // First load can take a while; past this it's stuck, so treat it like a crash.
                    val answer = withTimeoutOrNull(150_000L) { listOf(reply.await()) } ?: run {
                        lost(crashed = true)
                        throw ModelCrashedException("${engine.title} stopped responding, so Budgey unloaded it. Nothing was lost.")
                    }
                    answer.first()
                } catch (e: android.os.RemoteException) {
                    lost(crashed = true)
                    throw ModelCrashedException()
                } finally {
                    pending.remove(id)
                    scheduleRelease()
                }
            }
        } finally {
            input.delete()
        }
    }

    private fun scheduleRelease() {
        releaseJob?.cancel()
        releaseJob = scope.launch(Dispatchers.Main) {
            delay(3 * 60_000L)
            if (pending.isEmpty()) disconnect()
        }
    }

    /**
     * ≤896 px JPEG — enough detail for the model's image encoder, and quicker than full size.
     * [square] pads it onto a white square for models that stretch input to a fixed square.
     */
    private fun jpegForModel(file: File, square: Boolean = false): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 896) sample *= 2
        val raw = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val scale = minOf(1f, 896f / maxOf(raw.width, raw.height))
        val scaled = if (scale < 1f) Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt(), (raw.height * scale).toInt(), true) else raw
        val bmp = if (square && scaled.width != scaled.height) {
            val side = maxOf(scaled.width, scaled.height)
            Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).also { sq ->
                val canvas = android.graphics.Canvas(sq)
                canvas.drawColor(android.graphics.Color.WHITE)
                canvas.drawBitmap(scaled, ((side - scaled.width) / 2).toFloat(), ((side - scaled.height) / 2).toFloat(), null)
            }.also { if (scaled !== raw) scaled.recycle() }
        } else scaled
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        if (bmp !== raw) bmp.recycle()
        raw.recycle()
        return out.toByteArray()
    }

}

/** The AI model's process crashed or hung. Budgey itself is fine; the model has been unloaded. */
class ModelCrashedException(
    message: String = "Vision AI stopped unexpectedly — usually because the phone ran low on memory. " +
        "Budgey unloaded the model; nothing was lost.",
) : RuntimeException(message)
