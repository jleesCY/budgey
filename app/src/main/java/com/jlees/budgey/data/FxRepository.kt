package com.jlees.budgey.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jlees.budgey.BudgeyApp
import com.jlees.budgey.domain.FxTable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Exchange rates for the currency converter.
 *
 *  • Built in: a snapshot of ~160 currencies ships inside the app (assets/fx_rates.json, refresh it
 *    with `./gradlew :app:fetchFxRates`). By default that's all the converter uses — it never goes
 *    online for rates.
 *  • Optional "daily updates" pack (Settings → Tools): downloads today's rates (~10 KB) and keeps
 *    them up to date once a day whenever the phone is online. Delete it to go back to the built-in
 *    rates.
 *
 * Rates come from Frankfurter (frankfurter.dev): free, no account or key, data from the European
 * Central Bank and other central banks. The request carries no personal data.
 */
class FxRepository(private val context: Context) {
    private val pack get() = File(context.filesDir, "fx/rates.json")

    enum class Source { BUILT_IN, PACK }

    data class Rates(val table: FxTable, val source: Source, val fetchedAt: Long?, val bytes: Long)

    @Volatile private var builtInCache: FxTable? = null

    /** The rates that ship with the app. */
    suspend fun builtIn(): FxTable = withContext(Dispatchers.IO) {
        builtInCache ?: (runCatching {
            context.assets.open("fx_rates.json").bufferedReader().use { FxTable.parse(it.readText()) }
        }.getOrNull() ?: FxTable("EUR", "", mapOf("USD" to 1.13))).also { builtInCache = it }
    }

    fun hasPack(): Boolean = pack.isFile

    /** What the converter uses: the update pack if it's installed and readable, otherwise the built-in rates. */
    suspend fun current(): Rates = withContext(Dispatchers.IO) {
        if (pack.isFile) {
            runCatching { FxTable.parse(pack.readText()) }.getOrNull()?.let {
                return@withContext Rates(it, Source.PACK, pack.lastModified(), pack.length())
            }
        }
        Rates(builtIn(), Source.BUILT_IN, null, 0)
    }

    /** Installs or updates the daily-updates pack. Throws with a readable message on failure. */
    suspend fun downloadPack(): Rates = withContext(Dispatchers.IO) {
        // v2 covers ~160 currencies; v1 (ECB's ~30) is the fallback.
        val text = runCatching { get("https://api.frankfurter.dev/v2/rates") }
            .recoverCatching { get("https://api.frankfurter.dev/v1/latest") }
            .getOrElse { throw IllegalStateException("Couldn't download rates — check your connection") }
        val table = FxTable.parse(text) ?: throw IllegalStateException("The rate service sent something unexpected")
        pack.parentFile?.mkdirs()
        val tmp = File(pack.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(pack)) {
            pack.delete()
            tmp.renameTo(pack)
        }
        schedule(context, true)
        Rates(table, Source.PACK, pack.lastModified(), pack.length())
    }

    /** Removes the pack and stops the daily updates; the built-in rates are used again. */
    suspend fun deletePack() = withContext(Dispatchers.IO) {
        schedule(context, false)
        pack.delete()
        File(pack.path + ".tmp").delete()
        // Leftover from an earlier version that kept a temporary copy.
        File(context.cacheDir, "fx").deleteRecursively()
        Unit
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "Budgey (Android)")
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val DAILY_WORK = "fx-rates-daily"

        /**
         * Daily refresh while the pack is installed. No "needs network" constraint on purpose:
         * that requires the network-state permission, which Budgey doesn't have — and asking
         * for it is what crashed the earlier version. Offline runs just retry later.
         */
        fun schedule(context: Context, on: Boolean) {
            runCatching {
                val wm = WorkManager.getInstance(context.applicationContext)
                if (!on) {
                    wm.cancelUniqueWork(DAILY_WORK)
                    return
                }
                val request = PeriodicWorkRequestBuilder<FxRatesWorker>(24, TimeUnit.HOURS)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                    .build()
                wm.enqueueUniquePeriodicWork(DAILY_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
            }
        }
    }
}

/** Refreshes the daily-updates pack once a day (if it's installed). */
class FxRatesWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val fx = (applicationContext as BudgeyApp).container.fx
        if (!fx.hasPack()) return Result.success()
        return if (runCatching { fx.downloadPack() }.isSuccess) Result.success()
        else if (runAttemptCount < 5) Result.retry() else Result.success()
    }
}
