package com.jlees.budgey.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jlees.budgey.BudgeyApp
import com.jlees.budgey.data.AppSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.jlees.budgey.data.db.SubscriptionEntity
import com.jlees.budgey.data.db.isLive
import com.jlees.budgey.domain.Reminder
import com.jlees.budgey.domain.ReminderKind
import com.jlees.budgey.reminders.RenewalReminders
import java.time.LocalDate
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import com.jlees.budgey.data.backup.ImportPlan
import com.jlees.budgey.scan.ModelState
import com.jlees.budgey.scan.NanoStatus
import com.jlees.budgey.scan.ScanEngine
import kotlinx.coroutines.withContext
import java.time.temporal.ChronoUnit


data class DataCounts(val categories: Int = 0, val purchases: Int = 0, val subscriptions: Int = 0)

/** One row in Settings → Scanner. */
data class EngineRow(
    val engine: ScanEngine,
    /** Enough memory / Android version for it. */
    val fits: Boolean,
    /** Downloadable models: where the download stands. */
    val model: ModelState = ModelState.NotDownloaded,
    /** Gemini Nano only. */
    val nano: NanoStatus? = null,
    /** 0–1 while a model file is being imported from storage. */
    val importProgress: Float? = null,
) {
    val ready: Boolean
        get() = when (engine) {
            ScanEngine.STANDARD -> true
            // Selectable once the phone supports it; if Android still has to fetch it, selecting starts that.
            ScanEngine.GEMINI_NANO -> nano != null && nano != NanoStatus.UNSUPPORTED && nano != NanoStatus.UNKNOWN
            else -> fits && model is ModelState.Ready
        }
}

/** The automatic copy taken right before "Erase all data". */
data class SafetyBackupInfo(val label: String, val daysLeft: Int)

class SettingsViewModel(private val app: BudgeyApp) : ViewModel() {
    private val c = app.container

    val settings: StateFlow<AppSettings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    // A Channel keeps messages until the screen reads them — e.g. "Exported 120 records" arriving
    // while you're on another tab is shown when you come back instead of being dropped.
    private val messageQueue = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.BUFFERED)
    val messages: kotlinx.coroutines.flow.Flow<String> = messageQueue.receiveAsFlow()

    val counts: StateFlow<DataCounts> = combine(c.repository.categories, c.repository.purchases, c.repository.subscriptions) { a, b, s ->
        DataCounts(a.size, b.size, s.size)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DataCounts())

    val brandStats: Pair<Int, Int> = c.brands.all.let { all -> all.size to all.count { c.brands.hasGlyph(it) } }

    private val safetyFile = com.jlees.budgey.data.SafetyCopy.file(app)
    private val _safety = MutableStateFlow<SafetyBackupInfo?>(null)
    val safetyBackup: StateFlow<SafetyBackupInfo?> = _safety

    private val _engines = MutableStateFlow(ScanEngine.entries.map { EngineRow(it, fits = it == ScanEngine.STANDARD) })
    val engines: StateFlow<List<EngineRow>> = _engines
    val ramGb: Float get() = c.smartScanner.totalRamBytes() / 1_000_000_000f
    @Volatile private var importing: Pair<ScanEngine?, Float>? = null
    val nanoMessage = MutableStateFlow<String?>(null)
    /** Null until checked. */
    val nanoSupported = MutableStateFlow<Boolean?>(null)

    private suspend fun refreshEngines() {
        val smart = c.smartScanner
        val checked = c.nanoScanner.status()
        // Couldn't tell this time: keep showing what we knew.
        val nano = if (checked == NanoStatus.UNKNOWN) {
            _engines.value.firstOrNull { it.engine == ScanEngine.GEMINI_NANO }?.nano
                ?: if (nanoSupported.value == false) NanoStatus.UNSUPPORTED else NanoStatus.UNKNOWN
        } else checked
        // Lost support for sure (e.g. restored to another phone): fall back to Standard.
        if (nano == NanoStatus.UNSUPPORTED && settings.value.scanEngine == ScanEngine.GEMINI_NANO) {
            c.settings.update { it.copy(scanEngine = ScanEngine.STANDARD) }
        }
        if (nano != NanoStatus.UNKNOWN) nanoSupported.value = nano != NanoStatus.UNSUPPORTED
        _engines.value = ScanEngine.entries.mapNotNull { e ->
            when (e) {
                ScanEngine.STANDARD -> EngineRow(e, fits = true)
                // Detected automatically: only listed on phones that support it.
                ScanEngine.GEMINI_NANO -> if (nano == NanoStatus.UNSUPPORTED || (nano == NanoStatus.UNKNOWN && nanoSupported.value != true)) null
                    else EngineRow(e, fits = true, nano = nano)
                else -> EngineRow(
                    e, fits = smart.fits(e),
                    model = withContext(Dispatchers.IO) { smart.state(e) },
                    importProgress = importing?.takeIf { it.first == null || it.first == e }?.second,
                )
            }
        }
    }

    init {
        viewModelScope.launch {
            val freed = c.smartScanner.cleanupStorage()
            if (freed > 50_000_000) messageQueue.send("Cleaned up ${freed / 1_000_000} MB of leftover model files")
        }
        viewModelScope.launch { refreshEngines() }
    }

    /**
     * Keeps the scanner rows fresh (download progress) — the screen runs this only while the
     * Scanner page is showing, so nothing polls in the background.
     */
    suspend fun watchEngines() {
        while (true) {
            refreshEngines()
            val busy = _engines.value.any { it.model is ModelState.Downloading || it.nano == NanoStatus.DOWNLOADING } || importing != null
            kotlinx.coroutines.delay(if (busy) 1_000 else 4_000)
        }
    }

    /** One refresh, e.g. when a page that shows downloaded models opens. */
    fun refreshEnginesNow() = viewModelScope.launch { refreshEngines() }

    fun selectEngine(e: ScanEngine) {
        update { it.copy(scanEngine = e, smartScanCrashed = false) }
        // Gemini Nano supported but not on the phone yet: ask Android to fetch it now.
        if (e == ScanEngine.GEMINI_NANO && _engines.value.any { it.engine == e && it.nano == NanoStatus.NEEDS_DOWNLOAD }) downloadNano()
        // Switching away from the downloaded model frees its memory now rather than in 3 minutes.
        if (!e.downloadable) viewModelScope.launch { c.smartScanner.release() }
    }

    fun download(e: ScanEngine) = viewModelScope.launch {
        val error = c.smartScanner.startDownload(e, settings.value.modelsWifiOnly)
        if (error != null) messageQueue.send(error)
        else messageQueue.send(if (settings.value.modelsWifiOnly) "Downloading ${e.title} (Wi-Fi only) — you can leave this screen" else "Downloading ${e.title} — you can leave this screen")
        refreshEngines()
    }

    fun cancelDownload(e: ScanEngine) = viewModelScope.launch {
        withContext(Dispatchers.IO) { c.smartScanner.cancelDownload(e) }
        refreshEngines()
    }

    fun deleteModel(e: ScanEngine) = viewModelScope.launch {
        c.smartScanner.delete(e)
        if (settings.value.scanEngine == e) c.settings.update { it.copy(scanEngine = ScanEngine.STANDARD) }
        refreshEngines()
        messageQueue.send("${e.title} deleted")
    }

    /** Asks the phone (AICore) to fetch Gemini Nano. */
    // Runs in the app's scope so leaving Settings doesn't stop the download.
    fun downloadNano() {
        // One request at a time: tapping again while Android is fetching it doesn't start another.
        if (nanoJob?.isActive == true) return
        nanoJob = app.appScope.launch {
            runCatching {
                c.nanoScanner.download().collect { nanoMessage.value = it }
            }.onFailure { if (it !is kotlinx.coroutines.CancellationException) nanoMessage.value = "Couldn't start: ${it.message}" }
            refreshEngines()
        }
    }

    private companion object {
        /** Shared by every Settings screen instance, since the download outlives them. */
        @Volatile var nanoJob: kotlinx.coroutines.Job? = null
    }

    fun importModel(uri: Uri) = viewModelScope.launch {
        val name = withContext(Dispatchers.IO) {
            app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) cur.getString(0) else null
            }
        } ?: ""
        importing = ScanEngine.forFile(name) to 0f
        runCatching { c.smartScanner.importModel(uri, name) { p -> importing = importing?.first to p } }
            .onSuccess { messageQueue.send("${it.title} installed") }
            .onFailure { messageQueue.send("Couldn't import: ${it.message}") }
        importing = null
        refreshEngines()
    }

    private val _storage = MutableStateFlow(0L)
    val storage: StateFlow<Long> = _storage

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _safety.value = safetyInfo()
            _storage.value = c.receipts.sizeBytes() + c.paymentIcons.sizeBytes()
        }
    }

    private fun safetyInfo(): SafetyBackupInfo? = com.jlees.budgey.data.SafetyCopy.info(app)?.let { (made, left) ->
        SafetyBackupInfo(made.format(java.time.format.DateTimeFormatter.ofPattern("MMM d")), left)
    }

    /** Settings → Your data → "Delete safety copy now": you're sure about the erase. */
    fun deleteSafetyBackup() = viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) { com.jlees.budgey.data.SafetyCopy.delete(app) }
        _safety.value = withContext(Dispatchers.IO) { safetyInfo() }
        messageQueue.send(if (ok) "Safety copy deleted" else "Couldn't delete the safety copy")
    }

    fun update(transform: (AppSettings) -> AppSettings) = viewModelScope.launch { c.settings.update(transform) }

    fun exportFileName() = c.backup.suggestedFileName()

    fun export(uri: Uri) = viewModelScope.launch {
        runCatching { c.backup.export(uri) }
            .onSuccess { messageQueue.send("Exported $it records") }
            .onFailure { messageQueue.send("Export failed: ${it.message}") }
    }

    /** Posts a sample reminder so you can see what they look like (uses your next renewal if any). */
    fun sendTestReminder() = viewModelScope.launch {
        val today = LocalDate.now()
        val sub = c.repository.allSubscriptions().filter { it.isLive }.minByOrNull { it.nextDueDate }
            ?: SubscriptionEntity(name = "Example Streaming", amountCents = 1599, anchorDate = today, nextDueDate = today.plusDays(1))
        val reminder = Reminder(sub, ReminderKind.RENEWAL, sub.nextDueDate, ChronoUnit.DAYS.between(today, sub.nextDueDate).coerceAtLeast(0))
        if (RenewalReminders.canNotify(app)) {
            RenewalReminders.post(app, reminder)
            messageQueue.send("Test reminder sent")
        } else {
            messageQueue.send("Notifications are blocked for this app")
        }
    }

    fun checkRemindersNow() {
        RenewalReminders.checkNow(app)
    }

    // ---------------------------------------------------------------- tools: currency rates

    /** The rates the converter uses (built in, or the daily-updates pack). */
    val fxInfo = MutableStateFlow<com.jlees.budgey.data.FxRepository.Rates?>(null)
    val fxBusy = MutableStateFlow(false)

    fun loadFxInfo() = viewModelScope.launch { fxInfo.value = runCatching { c.fx.current() }.getOrNull() }

    /** Download (or update) the daily-updates pack. */
    fun downloadFxPack() = viewModelScope.launch {
        if (fxBusy.value) return@launch
        fxBusy.value = true
        runCatching { c.fx.downloadPack() }
            .onSuccess {
                fxInfo.value = it
                messageQueue.send("Daily currency updates on — rates from ${it.table.date}")
            }
            .onFailure { messageQueue.send(it.message ?: "Couldn't download rates") }
        fxBusy.value = false
    }

    fun deleteFxPack() = viewModelScope.launch {
        runCatching { c.fx.deletePack() }
        fxInfo.value = runCatching { c.fx.current() }.getOrNull()
        messageQueue.send("Update pack deleted — using the built-in rates")
    }

    fun recompressImages() = viewModelScope.launch {
        messageQueue.send("Shrinking pictures…")
        val (b1, a1) = c.receipts.recompressAll()
        val (b2, a2) = c.paymentIcons.recompressAll()
        val saved = (b1 + b2) - (a1 + a2)
        _storage.value = a1 + a2
        messageQueue.send(if (saved > 0) "Saved ${saved / 1024} KB" else "Pictures are already as small as they get")
    }

    fun restoreSafetyBackup() = viewModelScope.launch {
        runCatching {
            val loaded = c.backup.load(Uri.fromFile(safetyFile))
            try {
                // Exactly as it was: original ids, icons and colors, no merging with the new defaults.
                c.backup.restoreExact(loaded)
            } finally {
                loaded.release()
            }
        }.onSuccess {
            safetyFile.delete()
            _safety.value = null
            messageQueue.send("Restored ${it.purchasesAdded} purchases and ${it.subscriptionsAdded} subscriptions")
        }.onFailure { messageQueue.send("Couldn't restore: ${it.message}") }
    }

    fun eraseAll() = viewModelScope.launch {
        // Safety net: keep a full copy on the phone for a week, so a mistaken erase can be undone.
        runCatching { c.backup.export(Uri.fromFile(safetyFile)) }
            .onFailure { messageQueue.send("Couldn't make a safety copy, so nothing was erased. ${it.message ?: ""}"); return@launch }
        _safety.value = withContext(Dispatchers.IO) { safetyInfo() }
        c.repository.eraseEverything()
        // Their photos were just erased too, so unfinished adds can't be resumed any more.
        com.jlees.budgey.scan.ScanKind.entries.forEach { c.pendingAdds.clear(it, deleteReceipt = false) }
        c.settings.update { it.copy(defaultsSeeded = false) }
        app.startupTasks() // re-seed default categories
        messageQueue.send("All data erased")
    }
}
