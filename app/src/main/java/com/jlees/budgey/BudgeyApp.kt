package com.jlees.budgey

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.jlees.budgey.reminders.RenewalReminders

class BudgeyApp : Application() {
    /** Give the AI model's memory back when Android asks (app in background or memory is tight). */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && ::container.isInitialized) {
            // Hidden / in the background: let the model go unless a scan is using it or just warmed it
            // up. Android about to kill the app: let it go anyway (unless it's mid-read).
            val critical = level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
            appScope.launch { container.smartScanner.release(ignoreHold = critical) }
            container.brands.trimMemory()
        }
    }

    lateinit var container: AppContainer
        private set

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** When this run of the app started: start-up clean-up only touches files older than this. */
    private var launchedAt = 0L

    override fun onCreate() {
        super.onCreate()
        // The AI model runs in its own ":ai" process (so a model crash can't take the app down).
        // That process only hosts the model — skip all of the app's own start-up work there.
        if (Application.getProcessName() != packageName) return
        launchedAt = System.currentTimeMillis()
        container = AppContainer(this)
        RenewalReminders.ensureChannel(this)
        RenewalReminders.schedule(this)
        appScope.launch {
            // Read the ~1 MB brand catalog in the background before a screen needs it.
            runCatching { container.brands.preload() }
            startupTasks()
            cleanupStorage() // only at launch: nothing is using temporary files yet
        }
    }

    /** Runs on every launch (and after "Erase everything"): first-run seeding, subscription auto-logging. */
    suspend fun startupTasks() {
        val settings = container.settings.current()
        if (!settings.defaultsSeeded) {
            container.repository.seedDefaults()
            container.settings.update { it.copy(defaultsSeeded = true) }
        }
        // Gemini Nano picked but this phone no longer supports it (e.g. restored from another phone).
        if (settings.scanEngine == com.jlees.budgey.scan.ScanEngine.GEMINI_NANO &&
            container.nanoScanner.status() == com.jlees.budgey.scan.NanoStatus.UNSUPPORTED
        ) container.settings.update { it.copy(scanEngine = com.jlees.budgey.scan.ScanEngine.STANDARD) }
        com.jlees.budgey.data.FxRepository.schedule(this, container.fx.hasPack())
        // Reminders, then auto-logging (so a "renews today" reminder isn't skipped when the app is
        // opened before the 9 AM check).
        RenewalReminders.runCheck(this)
    }

    /**
     * Sweeps up files nothing needs any more. Each file is normally deleted as soon as it's done
     * with; this catches what a crash, a force-stop or a dead battery left behind.
     */
    private suspend fun cleanupStorage() = kotlinx.coroutines.withContext(Dispatchers.IO) {
        runCatching { container.smartScanner.cleanupStorage() }
        // Receipt photos no purchase/subscription uses (unfinished "resume" adds keep theirs).
        runCatching { container.repository.cleanupReceipts(keep = container.pendingAdds.receiptFiles(), before = launchedAt) }
        // Custom icon pictures nothing uses (replaced, or chosen and then not saved).
        runCatching { container.repository.cleanupIcons(keepKeys = container.pendingAdds.iconKeys(), before = launchedAt) }
        // Check splitter photos the saved split doesn't use.
        runCatching { com.jlees.budgey.ui.tools.SplitStore(this@BudgeyApp).cleanupPhotos(before = launchedAt) }
        // The post-erase safety copy, once its week is up (not only when Settings is opened).
        runCatching { com.jlees.budgey.data.SafetyCopy.prune(this@BudgeyApp) }
        // Camera captures, AI model inputs, unpacked backups.
        com.jlees.budgey.data.TempFiles.startupCleanup(this@BudgeyApp, launchedAt)
    }
}
