package com.jlees.budgey.ui.scan

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.jlees.budgey.AppContainer
import com.jlees.budgey.scan.ScanDraft
import com.jlees.budgey.data.toScanResult
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
import com.jlees.budgey.data.PendingAdd
import com.jlees.budgey.data.PendingScan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import com.jlees.budgey.scan.ModelState
import com.jlees.budgey.scan.ModelCrashedException

sealed interface ScanState {
    data object Waiting : ScanState
    data object Working : ScanState
    data class Done(
        val result: ScanResult,
        val receiptFile: String,
        val kind: ScanKind,
        /** The scanner the first scan used; rescan options only appear after a Standard scan. */
        val firstEngine: ScanEngine = ScanEngine.STANDARD,
        /** The scanner behind the result shown now. */
        val readWith: ScanEngine = firstEngine,
        /** The AI model's raw answer, when one was used (shown under "View text"). */
        val aiReply: String? = null,
    ) : ScanState
    /**
     * [message] is friendly; [detail] the technical reason, shown small. [modelCrashed]: the AI model
     * failed (Budgey is fine) — offer to read the photo with Standard.
     */
    data class Failed(val message: String, val modelCrashed: Boolean = false, val detail: String? = null) : ScanState
}

class ScanViewModel(private val c: AppContainer, handle: SavedStateHandle) : ViewModel() {
    val route = handle.toRoute<ScanRoute>()
    private val _state = MutableStateFlow<ScanState>(ScanState.Waiting)
    val state: StateFlow<ScanState> = _state.asStateFlow()

    /** What the "working" screen says (changes when the AI model takes over). */
    val status = MutableStateFlow("Reading text on your device…")

    init {
        // "Resume": put the review screen back exactly as it was left.
        if (route.mode == "resume") {
            val kind = if (route.target == "subscription") ScanKind.SUBSCRIPTION else ScanKind.PURCHASE
            c.pendingAdds.get(kind)?.scan?.let { _state.value = it.toDone() }
        }
    }

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

    /** The scan (or rescan) that's running, so Cancel can stop it. */
    private var scanJob: kotlinx.coroutines.Job? = null

    /** Scans [uri] with the chosen scanner, or with [engine] when given (e.g. Standard after a model crash). */
    fun process(uri: Uri, engine: ScanEngine? = null) {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            // A different picture replaces the one being reviewed (its saved copy is no longer needed).
            if (_state.value is ScanState.Done) dropDone()
            if (lastUri != uri) com.jlees.budgey.data.TempFiles.releaseCamera(c.context, lastUri)
            lastUri = uri
            _state.value = ScanState.Working
            status.value = "Reading text on your device…"
            try {
                val done = scan(uri, engine ?: c.settings.current().scanEngine, previous = null)
                _state.value = done
                keep(done)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // cancelled: cancelScan() already set the screen
            } catch (e: ModelCrashedException) {
                noteCrash()
                _state.value = ScanState.Failed(e.message ?: "", modelCrashed = true)
            } catch (e: Exception) {
                _state.value = ScanState.Failed(
                    "Couldn't read that picture. A sharper, closer, well-lit photo usually works.",
                    detail = e.message,
                )
            }
        }
    }

    /** Stop a scan that's running. A rescan goes back to the earlier results; a first scan to the start. */
    fun cancelScan() {
        scanJob?.cancel()
        scanJob = null
        val back = rescanBase
        rescanBase = null
        _state.value = back ?: ScanState.Waiting
    }

    /** "Try again" after a failure: the same photo again if it's still there (null = pick a new one). */
    fun retrySamePhoto(): Boolean {
        val uri = lastUri ?: return false
        val readable = runCatching { c.context.contentResolver.openInputStream(uri)?.use { true } ?: false }.getOrDefault(false)
        if (!readable) return false
        process(uri)
        return true
    }

    /**
     * Saves the review for "resume" as soon as it's on screen — not only when leaving it, which
     * Android skips if it closes Budgey in the background (e.g. while you check your banking app).
     */
    private fun keep(done: ScanState.Done) {
        if (!committed) c.pendingAdds.put(done.kind, PendingAdd(scan = done.toPending()))
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
            var newFile: String? = null
            try {
                // Vision models don't need Google's text, so they start right away, in parallel.
                val visionReply = if (useLocalModel && engine.vision && modelImage != null) {
                    async { safely { c.smartScanner.read(engine, modelImage, today) } }
                } else null

                // Google's reader: as-is + cleaned-up copies, on the full-resolution original.
                val scan = try { c.ocr.scan(uri) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
                val file = previous?.receiptFile ?: c.receipts.importImage(uri).also { newFile = it }
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
                    aiReply = reply,
                ).also { newFile = null } // the review owns the photo now
            } finally {
                // Always freed — also when the scan fails or you leave mid-scan.
                modelImage?.delete()
                newFile?.let { c.receipts.delete(it) }
            }
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

    private fun rescanWith(engine: ScanEngine) {
        scanJob?.cancel()
        scanJob = viewModelScope.launch { rescanNow(engine) }
    }

    private suspend fun rescanNow(engine: ScanEngine) {
        val before = _state.value as? ScanState.Done ?: return
        rescanBase = before
        _state.value = ScanState.Working
        status.value = if (engine == ScanEngine.STANDARD) "Reading the photo again…" else "Rescanning with ${engine.title}…"
        // The original photo is best; if it's gone (e.g. a temporary camera file), use the saved copy.
        val source = withContext(Dispatchers.IO) {
            lastUri?.takeIf { u -> runCatching { c.context.contentResolver.openInputStream(u)?.use { true } ?: false }.getOrDefault(false) }
        } ?: Uri.fromFile(c.receipts.file(before.receiptFile))
        try {
            val done = scan(source, engine, previous = before)
            _state.value = done
            keep(done)
            messages.tryEmit("Rescanned with ${done.readWith.title}")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // cancelScan() put the earlier results back
        } catch (e: ModelCrashedException) {
            // Keep what was already found rather than losing the scan.
            _state.value = before
            noteCrash()
            errorDialog.value = (e.message ?: "") + "\n\nYou're back on your earlier results."
        } catch (e: Exception) {
            _state.value = before
            messages.tryEmit("Rescan didn't work: ${e.message ?: "unknown error"}")
        }
        rescanBase = null
    }

    /** The results a rescan started from (so leaving mid-rescan still keeps them). */
    private var rescanBase: ScanState.Done? = null

    private suspend fun safely(block: suspend () -> String?): String? =
        try { block() }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        // A crashed model ends the scan (with an explanation) instead of silently giving a weaker result.
        catch (e: ModelCrashedException) { throw e }
        catch (e: Throwable) { null }

    /** The user picked one of the other amounts found. */
    fun pickAmount(cents: Long) {
        _state.update { s ->
            if (s !is ScanState.Done) s
            else s.copy(result = s.result.copy(amountCents = cents, amountCandidates = (listOf(cents) + s.result.amountCandidates).distinct().take(6)))
        }
        (_state.value as? ScanState.Done)?.let(::keep)
    }

    fun setKind(kind: ScanKind) {
        _state.update { s -> if (s is ScanState.Done) s.copy(kind = kind) else s }
        (_state.value as? ScanState.Done)?.let(::keep)
    }

    /** True once the review was handed to an editor (it takes over the "resume" from there). */
    private var committed = false

    /** Stores the draft for the editor and returns which editor to open. */
    fun commit(): ScanKind? {
        val s = _state.value as? ScanState.Done ?: return null
        // Make sure the saved copy is current: the editor falls back to it if Android closes Budgey
        // before the editor has saved its own "resume" (which then replaces this one).
        keep(s)
        c.scanDrafts.put(ScanDraft(s.result.copy(kind = s.kind), s.receiptFile))
        committed = true
        return s.kind
    }

    /** Leaving the review without continuing keeps it, so the ⟲ Resume button can bring it back. */
    override fun onCleared() {
        // Our own camera capture: the review keeps its own copy, so the original can go.
        com.jlees.budgey.data.TempFiles.releaseCamera(c.context, lastUri)
        val s = _state.value as? ScanState.Done ?: rescanBase ?: return
        if (!committed) c.pendingAdds.put(s.kind, PendingAdd(scan = s.toPending()))
    }

    /** Throw this scan away (photo included). */
    fun discard() = reset()

    private fun ScanState.Done.toPending() = PendingScan(
        kind = kind.name,
        merchant = result.merchant,
        brandId = result.brand?.id,
        amountCents = result.amountCents,
        date = result.date?.toString(),
        cycleUnit = result.cycle?.unit?.name,
        cycleCount = result.cycle?.count,
        nextBillingDate = result.nextBillingDate?.toString(),
        trialEndDate = result.trialEndDate?.toString(),
        amountCandidates = result.amountCandidates,
        subscriptionScore = result.subscriptionScore,
        rawText = result.rawText,
        receiptFile = receiptFile,
        firstEngine = firstEngine.name,
        readWith = readWith.name,
        aiReply = aiReply,
        isRefund = result.isRefund,
    )

    private fun PendingScan.toDone(): ScanState.Done {
        fun engine(n: String) = ScanEngine.entries.firstOrNull { it.name == n } ?: ScanEngine.STANDARD
        val r = toScanResult(c.brands::byId)
        return ScanState.Done(
            result = r,
            receiptFile = receiptFile,
            kind = r.kind,
            firstEngine = engine(firstEngine),
            readWith = engine(readWith),
            aiReply = aiReply,
        )
    }

    fun receiptFile(name: String) = c.receipts.file(name)

    fun reset() {
        status.value = "Reading text on your device…"
        dropDone()
        _state.value = ScanState.Waiting
    }

    /** Throws away the scan being reviewed: its photo, and its "resume" if it was one. */
    private fun dropDone() {
        (_state.value as? ScanState.Done)?.let { d ->
            if (committed) return // an editor owns the photo now
            ScanKind.entries.forEach { k -> if (c.pendingAdds.get(k)?.scan?.receiptFile == d.receiptFile) c.pendingAdds.clear(k, deleteReceipt = false) }
            c.receipts.delete(d.receiptFile)
        }
    }
}
