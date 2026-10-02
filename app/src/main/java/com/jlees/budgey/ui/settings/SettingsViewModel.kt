package com.jlees.budgey.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jlees.budgey.BudgeyApp
import com.jlees.budgey.data.AppSettings
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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

private const val SAFETY_DAYS = 7

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
            ScanEngine.GEMINI_NANO -> nano != null && nano != NanoStatus.UNSUPPORTED
            else -> fits && model is ModelState.Ready
        }
}

/** The automatic copy taken right before "Erase all data". */
data class SafetyBackupInfo(val label: String, val daysLeft: Int)

class SettingsViewModel(private val app: BudgeyApp) : ViewModel() {
    private val c = app.container

    val settings: StateFlow<AppSettings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    val counts: StateFlow<DataCounts> = combine(c.repository.categories, c.repository.purchases, c.repository.subscriptions) { a, b, s ->
        DataCounts(a.size, b.size, s.size)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DataCounts())

    val brandStats: Pair<Int, Int> = c.brands.all.let { all -> all.size to all.count { c.brands.hasGlyph(it) } }

    private val safetyFile = File(File(app.filesDir, "safety").apply { mkdirs() }, "before-erase.zip")
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
        val nano = c.nanoScanner.status()
        // Lost support (e.g. after a system update or restore to another phone): fall back to Standard.
        if (nano == NanoStatus.UNSUPPORTED && settings.value.scanEngine == ScanEngine.GEMINI_NANO) {
            c.settings.update { it.copy(scanEngine = ScanEngine.STANDARD) }
        }
        nanoSupported.value = nano != NanoStatus.UNSUPPORTED
        _engines.value = ScanEngine.entries.mapNotNull { e ->
            when (e) {
                ScanEngine.STANDARD -> EngineRow(e, fits = true)
                // Detected automatically: only listed on phones that support it.
                ScanEngine.GEMINI_NANO -> if (nano == NanoStatus.UNSUPPORTED) null else EngineRow(e, fits = true, nano = nano)
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
            if (freed > 50_000_000) _messages.emit("Cleaned up ${freed / 1_000_000} MB of leftover model files")
        }
        // Keep download progress fresh while Settings is open.
        viewModelScope.launch {
            while (true) {
                refreshEngines()
                val busy = _engines.value.any { it.model is ModelState.Downloading || it.nano == NanoStatus.DOWNLOADING } || importing != null
                kotlinx.coroutines.delay(if (busy) 1_000 else 4_000)
            }
        }
    }

    fun selectEngine(e: ScanEngine) {
        update { it.copy(scanEngine = e, smartScanCrashed = false) }
        // Gemini Nano supported but not on the phone yet: ask Android to fetch it now.
        if (e == ScanEngine.GEMINI_NANO && _engines.value.any { it.engine == e && it.nano == NanoStatus.NEEDS_DOWNLOAD }) downloadNano()
        // Switching away from the downloaded model frees its memory now rather than in 3 minutes.
        if (!e.downloadable) viewModelScope.launch { c.smartScanner.release() }
    }

    fun download(e: ScanEngine) = viewModelScope.launch {
        val error = c.smartScanner.startDownload(e, settings.value.modelsWifiOnly)
        if (error != null) _messages.emit(error)
        else _messages.emit(if (settings.value.modelsWifiOnly) "Downloading ${e.title} (Wi-Fi only) — you can leave this screen" else "Downloading ${e.title} — you can leave this screen")
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
        _messages.emit("${e.title} deleted")
    }

    /** Asks the phone (AICore) to fetch Gemini Nano. */
    // Runs in the app's scope so leaving Settings doesn't stop the download.
    fun downloadNano() = app.appScope.launch {
        runCatching {
            c.nanoScanner.download().collect { nanoMessage.value = it }
        }.onFailure { nanoMessage.value = "Couldn't start: ${it.message}" }
        refreshEngines()
    }

    fun importModel(uri: Uri) = viewModelScope.launch {
        val name = withContext(Dispatchers.IO) {
            app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) cur.getString(0) else null
            }
        } ?: ""
        importing = ScanEngine.forFile(name) to 0f
        runCatching { c.smartScanner.importModel(uri, name) { p -> importing = importing?.first to p } }
            .onSuccess { _messages.emit("${it.title} installed") }
            .onFailure { _messages.emit("Couldn't import: ${it.message}") }
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

    private fun safetyInfo(): SafetyBackupInfo? {
        if (!safetyFile.exists()) return null
        val ageDays = ChronoUnit.DAYS.between(
            Instant.ofEpochMilli(safetyFile.lastModified()).atZone(ZoneId.systemDefault()).toLocalDate(), LocalDate.now(),
        ).toInt()
        if (ageDays >= SAFETY_DAYS) {
            safetyFile.delete()
            return null
        }
        val label = Instant.ofEpochMilli(safetyFile.lastModified()).atZone(ZoneId.systemDefault()).toLocalDate()
            .format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))
        return SafetyBackupInfo(label, SAFETY_DAYS - ageDays)
    }

    fun update(transform: (AppSettings) -> AppSettings) = viewModelScope.launch { c.settings.update(transform) }

    fun exportFileName() = c.backup.suggestedFileName()

    fun export(uri: Uri) = viewModelScope.launch {
        runCatching { c.backup.export(uri) }
            .onSuccess { _messages.emit("Exported $it records") }
            .onFailure { _messages.emit("Export failed: ${it.message}") }
    }

    /** Posts a sample reminder so you can see what they look like (uses your next renewal if any). */
    fun sendTestReminder() = viewModelScope.launch {
        val today = LocalDate.now()
        val sub = c.repository.allSubscriptions().filter { it.isLive }.minByOrNull { it.nextDueDate }
            ?: SubscriptionEntity(name = "Example Streaming", amountCents = 1599, anchorDate = today, nextDueDate = today.plusDays(1))
        val reminder = Reminder(sub, ReminderKind.RENEWAL, sub.nextDueDate, ChronoUnit.DAYS.between(today, sub.nextDueDate).coerceAtLeast(0))
        if (RenewalReminders.canNotify(app)) {
            RenewalReminders.post(app, reminder)
            _messages.emit("Test reminder sent")
        } else {
            _messages.emit("Notifications are blocked for this app")
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
                _messages.emit("Daily currency updates on — rates from ${it.table.date}")
            }
            .onFailure { _messages.emit(it.message ?: "Couldn't download rates") }
        fxBusy.value = false
    }

    fun deleteFxPack() = viewModelScope.launch {
        runCatching { c.fx.deletePack() }
        fxInfo.value = runCatching { c.fx.current() }.getOrNull()
        _messages.emit("Update pack deleted — using the built-in rates")
    }

    fun recompressImages() = viewModelScope.launch {
        _messages.emit("Shrinking pictures…")
        val (b1, a1) = c.receipts.recompressAll()
        val (b2, a2) = c.paymentIcons.recompressAll()
        val saved = (b1 + b2) - (a1 + a2)
        _storage.value = a1 + a2
        _messages.emit(if (saved > 0) "Saved ${saved / 1024} KB" else "Pictures are already as small as they get")
    }

    fun restoreSafetyBackup() = viewModelScope.launch {
        runCatching {
            val loaded = c.backup.load(Uri.fromFile(safetyFile))
            c.backup.import(
                loaded,
                ImportPlan(
                    categoryIds = loaded.file.categories.map { it.id }.toSet(),
                    includeUncategorizedPurchases = true,
                    includeUncategorizedSubscriptions = true,
                    includeSettings = false,
                ),
            )
        }.onSuccess {
            safetyFile.delete()
            _safety.value = null
            _messages.emit("Restored ${it.purchasesAdded} purchases and ${it.subscriptionsAdded} subscriptions")
        }.onFailure { _messages.emit("Couldn't restore: ${it.message}") }
    }

    fun eraseAll() = viewModelScope.launch {
        // Safety net: keep a full copy on the phone for a week, so a mistaken erase can be undone.
        runCatching { c.backup.export(Uri.fromFile(safetyFile)) }
            .onFailure { _messages.emit("Couldn't make a safety copy, so nothing was erased. ${it.message ?: ""}"); return@launch }
        _safety.value = withContext(Dispatchers.IO) { safetyInfo() }
        c.repository.eraseEverything()
        c.settings.update { it.copy(defaultsSeeded = false) }
        app.startupTasks() // re-seed default categories
        _messages.emit("All data erased")
    }
}
