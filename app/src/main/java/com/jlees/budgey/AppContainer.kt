package com.jlees.budgey

import android.content.Context
import com.jlees.budgey.data.BudgetRepository
import com.jlees.budgey.data.ReceiptStore
import com.jlees.budgey.data.SettingsRepository
import com.jlees.budgey.data.backup.BackupManager
import com.jlees.budgey.data.db.AppDatabase
import com.jlees.budgey.icons.BrandCatalog
import com.jlees.budgey.scan.OcrEngine
import com.jlees.budgey.scan.ReceiptParser
import com.jlees.budgey.scan.ScanDraftHolder
import com.jlees.budgey.scan.SmartScanner
import com.jlees.budgey.scan.NanoScanner

/**
 * Manual dependency container. The app is small enough that Hilt would add more build
 * complexity than it saves; swapping later is mechanical.
 */
class AppContainer(val context: Context) {
    val database: AppDatabase = AppDatabase.build(context)
    val brands: BrandCatalog = BrandCatalog(context)
    val receipts: ReceiptStore = ReceiptStore(context)
    /** Custom pictures chosen as icons (payment methods, merchants, subscriptions). */
    val paymentIcons: ReceiptStore = ReceiptStore(context, folder = "icons", maxDimension = 256, quality = 85)
    val settings: SettingsRepository = SettingsRepository(context)
    val repository: BudgetRepository = BudgetRepository(database, receipts, brands, paymentIcons)
    val backup: BackupManager = BackupManager(context, repository, settings)
    val ocr: OcrEngine = OcrEngine(context)
    // Lazy: building it reads the brand catalog, which shouldn't happen on the main thread at launch.
    val parser: ReceiptParser by lazy { ReceiptParser(brands.matcher) }
    /** Optional on-device AI models for scans (Settings → Scanner). */
    val smartScanner: SmartScanner = SmartScanner(context)
    /** Gemini Nano, on phones that have it built in. */
    val nanoScanner: NanoScanner = NanoScanner()
    val scanDrafts: ScanDraftHolder = ScanDraftHolder()
    /** Unfinished purchase / subscription adds ("Resume"). */
    val pendingAdds: com.jlees.budgey.data.PendingAdds = com.jlees.budgey.data.PendingAdds(context, receipts)
    val itemScanner: com.jlees.budgey.scan.ItemScanner = com.jlees.budgey.scan.ItemScanner(ocr, smartScanner, nanoScanner, settings)
    val fx: com.jlees.budgey.data.FxRepository = com.jlees.budgey.data.FxRepository(context)
    /** For short clean-up work that must finish after a screen has closed (its scope is gone by then). */
    val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    fun receiptSession() = com.jlees.budgey.data.ReceiptSession(context, receipts, repository)

    init {
        // Auto-logging used to be one global switch; it's per subscription now. If it was off,
        // turn it off on every subscription before anything gets auto-logged.
        repository.beforeAutoLog = {
            if (settings.consumeLegacyAutoLogOff()) repository.setAutoLogForAll(false)
        }
    }
}
