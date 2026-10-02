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
            appScope.launch { container.smartScanner.release() }
        }
    }

    lateinit var container: AppContainer
        private set

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // The AI model runs in its own ":ai" process (so a model crash can't take the app down).
        // That process only hosts the model — skip all of the app's own start-up work there.
        if (Application.getProcessName() != packageName) return
        container = AppContainer(this)
        RenewalReminders.ensureChannel(this)
        RenewalReminders.schedule(this)
        appScope.launch { startupTasks() }
    }

    /** Runs on every launch: first-run seeding, subscription auto-logging, receipt cleanup. */
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
        container.repository.processDueSubscriptions()
        container.smartScanner.cleanupStorage()
        container.repository.cleanupReceipts()
    }
}
