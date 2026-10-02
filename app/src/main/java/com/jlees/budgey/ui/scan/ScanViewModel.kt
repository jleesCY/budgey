package com.jlees.budgey.ui.scan

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.jlees.budgey.AppContainer
import com.jlees.budgey.scan.ScanDraft
import com.jlees.budgey.scan.ScanKind
import com.jlees.budgey.scan.ScanResult
import com.jlees.budgey.scan.ReceiptParser
import com.jlees.budgey.scan.SmartScanParser
import com.jlees.budgey.scan.ScanEngine
import com.jlees.budgey.scan.NanoStatus
import com.jlees.budgey.ui.navigation.ScanRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import com.jlees.budgey.scan.ModelState
import com.jlees.budgey.scan.ModelCrashedException

sealed interface ScanState {
    data object Waiting : ScanState
    data object Working : ScanState
    /** [aspect] = width / height of the upright photo, so number boxes can be drawn over it. */
    data class Done(
        val result: ScanResult,
        val receiptFile: String,
        val kind: ScanKind,
        val aspect: Float = 0f,
        /** The scanner the first scan used; rescan options only appear after a Standard scan. */
        val firstEngine: ScanEngine = ScanEngine.STANDARD,
        /** The scanner behind the result shown now. */
        val readWith: ScanEngine = firstEngine,
    ) : ScanState
    /** [modelCrashed]: the AI model failed (Budgey is fine) — offer to read the photo with Standard. */
    data class Failed(val message: String, val modelCrashed: Boolean = false) : ScanState
}

class ScanViewModel(private val c: AppContainer, handle: SavedStateHandle) : ViewModel() {
    val route = handle.toRoute<ScanRoute>()
    private val _state = MutableStateFlow<ScanState>(ScanState.Waiting)
    val state: StateFlow<ScanState> = _state.asStateFlow()

    /** What the "working" screen says (changes when the AI model takes over). */
    val status = MutableStateFlow("Reading text on your device…")

    init {
        // Load the AI model while you're still taking the photo, so the scan itself is quicker.
        viewModelScope.launch {
            when (val engine = c.settings.current().scanEngine) {
                ScanEngine.STANDARD -> Unit
                ScanEngine.GEMINI_NANO -> c.nanoScanner.warmUp()
                else -> if (c.smartScanner.fits(engine)) c.smartScanner.warmUp(engine)
            }
        }
    }

    /** The photo as picked (full quality); rescans read this again when it's still reachable. */
    private var lastUri: Uri? = null

    /** Scans [uri] with the chosen scanner, or with [engine] when given (e.g. Standard after a model crash). */
    fun process(uri: Uri, engine: ScanEngine? = null) = viewModelScope.launch {
        lastUri = uri
        _state.value = ScanState.Working
        status.value = "Reading text on your device…"
        runCatching { scan(uri, engine ?: c.settings.current().scanEngine, previous = null) }
            .onSuccess { _state.value = it }
            .onFailure {
                _state.value = if (it is ModelCrashedException) {
                    noteCrash()
                    ScanState.Failed(it.message ?: "", modelCrashed = true)
                } else ScanState.Failed(it.message ?: "Couldn't read that image")
            }
    }

    /** After a model crash: read the same photo with Standard instead. */
    fun retryWithStandard() {
        lastUri?.let { process(it, ScanEngine.STANDARD) } ?: reset()
    }

    /** Shown as a dialog over the results (e.g. a rescan's model crashed). */
    val errorDialog = MutableStateFlow<String?>(null)

    /** Remember it so Settings → Scanner & AI models can explain it too. */
    private fun noteCrash() = viewModelScope.launch { c.settings.update { it.copy(smartScanCrashed = true) } }

    /**
     * Reads [uri] with [engine]. For a rescan, [previous] supplies the already-saved receipt image
     * (so nothing is saved twice), the purchase/subscription choice, and the amounts found before.
     */
    private suspend fun scan(uri: Uri, engine: ScanEngine, previous: ScanState.Done?): ScanState.Done {
        val today = java.time.LocalDate.now()
        val nanoReady = engine == ScanEngine.GEMINI_NANO && c.nanoScanner.status() == NanoStatus.READY
        if (engine != ScanEngine.STANDARD && (engine != ScanEngine.GEMINI_NANO || nanoReady)) status.value = "Reading with ${engine.title}…"
        val useLocalModel = engine != ScanEngine.STANDARD && engine != ScanEngine.GEMINI_NANO &&
            c.smartScanner.fits(engine) && c.smartScanner.modelFile(engine) != null

        return coroutineScope {
            // AI models get ONE picture: the untouched photo (upright, high quality). The cleaned-up
            // black & white / inverted copies below are only for Google's text reader.
            val modelImage = if (engine.vision) c.ocr.prepareModelImage(uri) else null
            // Vision models don't need Google's text, so they start right away, in parallel.
            val visionReply = if (useLocalModel && engine.vision && modelImage != null) {
                async { safely { c.smartScanner.read(engine, modelImage, today) } }
            } else null

            // Google's reader: as-is + cleaned-up copies, on the full-resolution original.
            val scan = try { c.ocr.scan(uri) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
            val file = previous?.receiptFile ?: c.receipts.importImage(uri)
            val ocr = scan ?: c.ocr.scan(Uri.fromFile(c.receipts.file(file)))
            var result = c.parser.combine(ocr.pages.map { c.parser.parseRows(it.lines) })

            val reply: String? = when {
                engine == ScanEngine.STANDARD -> null
                visionReply != null -> visionReply.await()
                engine == ScanEngine.GEMINI_NANO -> safely {
                    if (nanoReady) c.nanoScanner.read(modelImage ?: c.receipts.file(file), today) else null
                }
                else -> null
            }
            modelImage?.delete()
            // The AI's answer wins where it gave one; Google's reading fills gaps and adds alternatives.
            result = SmartScanParser.merge(result, reply?.let { SmartScanParser.parse(it, today) }, c.brands.matcher)
            if (previous != null) {
                // Keep the amounts found earlier as extra choices.
                result = result.copy(
                    amountCandidates = (result.amountCandidates + previous.result.amountCandidates).distinct().take(8)
                )
            }
            val kind = previous?.kind ?: when (route.target) {
                "purchase" -> ScanKind.PURCHASE
                "subscription" -> ScanKind.SUBSCRIPTION
                else -> result.kind
            }
            ScanState.Done(
                result, file, kind,
                firstEngine = previous?.firstEngine ?: engine,
                readWith = if (engine == ScanEngine.GEMINI_NANO && !nanoReady) ScanEngine.STANDARD else engine,
            )
        }
    }

    // ---------------------------------------------------------------- rescans

    /** What the rescan buttons can do right now (refreshed when the screen comes back into view). */
    data class RescanInfo(
        val nanoReady: Boolean = false,
        /** Enough memory for Vision AI; if not, there's no advanced rescan. */
        val visionFits: Boolean = false,
        val vision: ModelState = ModelState.NotDownloaded,
    )

    private val _rescan = MutableStateFlow(RescanInfo())
    val rescan: StateFlow<RescanInfo> = _rescan.asStateFlow()
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    suspend fun refreshRescan() {
        val v = ScanEngine.VISION_AI
        _rescan.value = RescanInfo(
            nanoReady = c.nanoScanner.status() == NanoStatus.READY,
            visionFits = c.smartScanner.fits(v),
            vision = withContext(Dispatchers.IO) { c.smartScanner.state(v) },
        )
    }

    /** Reads the same photo again: with Gemini Nano if this phone has it, otherwise Standard. */
    fun rescan() = rescanWith(if (_rescan.value.nanoReady) ScanEngine.GEMINI_NANO else ScanEngine.STANDARD)

    /** Reads the same photo again with Vision AI (must be downloaded). */
    fun advancedRescan() = rescanWith(ScanEngine.VISION_AI)

    private fun rescanWith(engine: ScanEngine) = viewModelScope.launch {
        val before = _state.value as? ScanState.Done ?: return@launch
        _state.value = ScanState.Working
        status.value = if (engine == ScanEngine.STANDARD) "Reading the photo again…" else "Rescanning with ${engine.title}…"
        // The original photo is best; if it's gone (e.g. a temporary camera file), use the saved copy.
        val source = withContext(Dispatchers.IO) {
            lastUri?.takeIf { u -> runCatching { c.context.contentResolver.openInputStream(u)?.use { true } ?: false }.getOrDefault(false) }
        } ?: Uri.fromFile(c.receipts.file(before.receiptFile))
        runCatching { scan(source, engine, previous = before) }
            .onSuccess { _state.value = it; messages.tryEmit("Rescanned with ${it.readWith.title}") }
            .onFailure {
                // Keep what was already found rather than losing the scan.
                _state.value = before
                if (it is ModelCrashedException) {
                    noteCrash()
                    errorDialog.value = (it.message ?: "") + "\n\nYou're back on your earlier results."
                } else messages.tryEmit("Rescan didn't work: ${it.message ?: "unknown error"}")
            }
    }

    private suspend fun safely(block: suspend () -> String?): String? =
        try { block() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        // A crashed model ends the scan (with an explanation) instead of silently giving a weaker result.
        catch (e: ModelCrashedException) { throw e }
        catch (e: Throwable) { null }

    /** The user picked one of the other amounts found. */
    fun pickAmount(cents: Long) = _state.update { s ->
        if (s !is ScanState.Done) s
        else s.copy(result = s.result.copy(amountCents = cents, amountCandidates = (listOf(cents) + s.result.amountCandidates).distinct().take(6)))
    }

    fun setKind(kind: ScanKind) = _state.update { s -> if (s is ScanState.Done) s.copy(kind = kind) else s }

    /** Stores the draft for the editor and returns which editor to open. */
    fun commit(): ScanKind? {
        val s = _state.value as? ScanState.Done ?: return null
        c.scanDrafts.put(ScanDraft(s.result.copy(kind = s.kind), s.receiptFile))
        return s.kind
    }

    fun receiptFile(name: String) = c.receipts.file(name)

    fun reset() {
        status.value = "Reading text on your device…"
        (_state.value as? ScanState.Done)?.let { c.receipts.delete(it.receiptFile) }
        _state.value = ScanState.Waiting
    }
}
