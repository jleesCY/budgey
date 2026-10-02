package com.jlees.budgey.scan

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Runs the Vision AI model in its OWN process (":ai", see AndroidManifest.xml).
 *
 * The model is native C++/GPU code. If it fails hard — runs out of memory, hits a GPU driver bug —
 * it takes down the whole process it lives in, and no try/catch can stop that. Keeping it here
 * means only this helper process dies; Budgey itself keeps running, notices the loss
 * ([SmartScanner] watches for it), ends the scan and shows what happened.
 *
 * Unloading is simple too: when Budgey disconnects, this service shuts down and its process exits,
 * so all of the model's memory (CPU and GPU) goes back to the phone.
 *
 * Protocol (Messenger): the app sends [MSG_WARM_UP] or [MSG_READ] with a Bundle; the reply to a read
 * is [MSG_REPLY] with the same arg1 and either [KEY_TEXT] or [KEY_ERROR].
 */
class ModelService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private var engine: Engine? = null
    private var enginePath: String? = null

    private val messenger by lazy {
        Messenger(Handler(Looper.getMainLooper()) { msg ->
            // The Message is recycled after this returns, so copy what we need.
            val data = Bundle(msg.data)
            val replyTo = msg.replyTo
            val id = msg.arg1
            when (msg.what) {
                MSG_WARM_UP -> scope.launch { runCatching { mutex.withLock { load(data) } } }
                MSG_READ -> scope.launch {
                    val reply = Message.obtain(null, MSG_REPLY, id, 0)
                    reply.data = try {
                        Bundle().apply { putString(KEY_TEXT, mutex.withLock { read(data) }) }
                    } catch (t: Throwable) {
                        Bundle().apply { putString(KEY_ERROR, t.message ?: t.javaClass.simpleName) }
                    }
                    runCatching { replyTo?.send(reply) }
                }
            }
            true
        })
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onDestroy() {
        scope.cancel()
        runCatching { engine?.close() }
        engine = null
        super.onDestroy()
        // Nothing else lives in this process: end it so every byte of model memory is returned.
        Process.killProcess(Process.myPid())
    }

    private fun load(data: Bundle): Engine {
        val path = requireNotNull(data.getString(KEY_MODEL)) { "No model" }
        engine?.let { if (enginePath == path) return it else it.close() }
        val cache = File(requireNotNull(data.getString(KEY_CACHE))).apply { mkdirs() }
        val e = Engine(
            EngineConfig(
                modelPath = path,
                // Phones with lots of memory run the language part on the GPU too (several times faster).
                backend = if (data.getBoolean(KEY_GPU)) Backend.GPU() else Backend.CPU(),
                // The image encoder must run on the GPU (CPU vision crashes in current LiteRT-LM).
                visionBackend = Backend.GPU(),
                maxNumTokens = 2048,
                cacheDir = cache.path,
            )
        )
        try {
            e.initialize() // a few seconds; longer the very first time
        } catch (t: Throwable) {
            // A stale or corrupt cache can break loading; clear it so the next try starts fresh.
            runCatching { e.close() }
            cache.deleteRecursively()
            throw t
        }
        engine = e
        enginePath = path
        return e
    }

    private fun read(data: Bundle): String {
        val e = load(data)
        val jpeg = File(requireNotNull(data.getString(KEY_IMAGE))).readBytes()
        val contents = Contents.of(Content.ImageBytes(jpeg), Content.Text(requireNotNull(data.getString(KEY_PROMPT))))
        return e.createConversation(
            ConversationConfig(samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.1))
        ).use { conversation ->
            // This library version has no reply-length limit; the prompt asks for one short JSON object.
            conversation.sendMessage(contents).toString()
        }
    }

    companion object {
        const val MSG_WARM_UP = 1
        const val MSG_READ = 2
        const val MSG_REPLY = 3
        const val KEY_MODEL = "model"
        const val KEY_CACHE = "cache"
        const val KEY_GPU = "gpu"
        const val KEY_IMAGE = "image"
        const val KEY_PROMPT = "prompt"
        const val KEY_TEXT = "text"
        const val KEY_ERROR = "error"
    }
}
