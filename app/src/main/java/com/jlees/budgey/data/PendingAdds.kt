package com.jlees.budgey.data

import android.content.Context
import com.jlees.budgey.scan.ScanKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/*
 * "Resume": a purchase or subscription you started adding but left before saving — either on the
 * scan review screen or in the editor. One per kind, kept in the app's private storage so it
 * survives leaving the screen or the app. A ⟲ Resume half on the + button brings it back.
 */

/** The scan review screen, as you left it. Dates are ISO strings. */
@Serializable
data class PendingScan(
    val kind: String,
    val merchant: String? = null,
    val brandId: String? = null,
    val amountCents: Long? = null,
    val date: String? = null,
    val cycleUnit: String? = null,
    val cycleCount: Int? = null,
    val nextBillingDate: String? = null,
    val trialEndDate: String? = null,
    val amountCandidates: List<Long> = emptyList(),
    val subscriptionScore: Int = 0,
    val rawText: String = "",
    val receiptFile: String,
    val firstEngine: String = "STANDARD",
    val readWith: String = "STANDARD",
    val aiReply: String? = null,
    val isRefund: Boolean = false,
)

/** A new purchase's editor, as you left it. */
@Serializable
data class PendingPurchase(
    val merchant: String = "",
    val amountText: String = "",
    val isRefund: Boolean = false,
    val date: String,
    val categoryId: String? = null,
    val categoryTouched: Boolean = false,
    val note: String = "",
    val paymentMethod: String = "",
    val paymentMethodId: String? = null,
    val paymentTouched: Boolean = false,
    val brandKey: String? = null,
    val receiptFile: String? = null,
    val source: String = "MANUAL",
    val amountCandidates: List<Long> = emptyList(),
    val scanText: String? = null,
)

@Serializable
data class PendingPeriod(
    val startDate: String,
    val endDate: String,
    val amountCents: Long,
    val cycleUnit: String,
    val cycleCount: Int,
    val label: String = "",
)

/** A new subscription's editor, as you left it. */
@Serializable
data class PendingSubscription(
    val name: String = "",
    val base: String = "",
    val fees: String = "",
    val total: String = "",
    val lastEdited: String? = null,
    val cycleUnit: String = "MONTH",
    val cycleCount: Int = 1,
    val anchorDate: String,
    val nextDueDate: String,
    val nextDueOverridden: Boolean = false,
    val categoryId: String? = null,
    val categoryTouched: Boolean = false,
    val brandKey: String? = null,
    val status: String = "ACTIVE",
    val autoLog: Boolean = true,
    val trialEndDate: String? = null,
    val paymentMethod: String = "",
    val paymentMethodId: String? = null,
    val note: String = "",
    val receiptFile: String? = null,
    val fromScan: Boolean = false,
    val scanText: String? = null,
    val reminderDays: Int? = null,
    val periods: List<PendingPeriod> = emptyList(),
)

/** Exactly one of [scan], [purchase], [subscription] is set. */
@Serializable
data class PendingAdd(
    val savedAt: Long = System.currentTimeMillis(),
    val scan: PendingScan? = null,
    val purchase: PendingPurchase? = null,
    val subscription: PendingSubscription? = null,
) {
    val receiptFile: String? get() = scan?.receiptFile ?: purchase?.receiptFile ?: subscription?.receiptFile
}

class PendingAdds(context: Context, private val receipts: ReceiptStore) {
    private val file = File(context.filesDir, "pending/adds.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class Saved(val purchase: PendingAdd? = null, val subscription: PendingAdd? = null)

    private val _state = MutableStateFlow(load())
    /** Unfinished adds by kind (a scan review is filed under the kind chosen on that screen). */
    val state: StateFlow<Map<ScanKind, PendingAdd>> = _state.asStateFlow()

    fun get(kind: ScanKind): PendingAdd? = _state.value[kind]

    /** Remember [add] as the unfinished [kind]. A replaced one's photo is deleted (unless still in use). */
    @Synchronized
    fun put(kind: ScanKind, add: PendingAdd) {
        val old = _state.value[kind]
        val others = _state.value.filterKeys { it != kind }
        // The same scan can't be pending under both kinds (e.g. you flipped Purchase ⇄ Subscription).
        val next = others.filterValues { it.receiptFile == null || it.receiptFile != add.receiptFile } + (kind to add)
        _state.value = next
        old?.receiptFile?.let { f -> if (f != add.receiptFile && next.values.none { it.receiptFile == f }) receipts.delete(f) }
        persist()
    }

    /**
     * Forget the unfinished [kind]. [deleteReceipt] = also delete its photo (true when you discard it;
     * false when it was just saved and the purchase now owns the photo).
     */
    @Synchronized
    fun clear(kind: ScanKind, deleteReceipt: Boolean) {
        val old = _state.value[kind] ?: return
        _state.value = _state.value - kind
        if (deleteReceipt) old.receiptFile?.let { f -> if (_state.value.values.none { it.receiptFile == f }) receipts.delete(f) }
        persist()
    }

    /** Photos that belong to unfinished adds (so startup clean-up keeps them). */
    fun receiptFiles(): Set<String> = _state.value.values.mapNotNull { it.receiptFile }.toSet()

    /** Icon keys chosen in unfinished adds (so startup clean-up keeps custom pictures). */
    fun iconKeys(): Set<String> = _state.value.values.mapNotNull { it.purchase?.brandKey ?: it.subscription?.brandKey }.toSet()

    private fun load(): Map<ScanKind, PendingAdd> = runCatching {
        val s = json.decodeFromString(Saved.serializer(), file.readText())
        buildMap {
            s.purchase?.let { put(ScanKind.PURCHASE, it) }
            s.subscription?.let { put(ScanKind.SUBSCRIPTION, it) }
        }
    }.getOrDefault(emptyMap())

    private fun persist() {
        runCatching {
            val m = _state.value
            if (m.isEmpty()) {
                file.delete()
                return
            }
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(Saved.serializer(), Saved(m[ScanKind.PURCHASE], m[ScanKind.SUBSCRIPTION])))
            tmp.renameTo(file)
        }
    }
}

/**
 * A scan review saved for "resume" ([PendingScan]) back as a [com.jlees.budgey.scan.ScanResult] —
 * for the review screen, and for an editor whose in-memory hand-off was lost (Android closed
 * Budgey in the background).
 */
fun PendingScan.toScanResult(brandById: (String) -> com.jlees.budgey.icons.Brand?): com.jlees.budgey.scan.ScanResult {
    fun date(s: String?) = s?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
    val k = com.jlees.budgey.scan.ScanKind.entries.firstOrNull { it.name == kind } ?: com.jlees.budgey.scan.ScanKind.PURCHASE
    val unit = com.jlees.budgey.domain.CycleUnit.entries.firstOrNull { it.name == cycleUnit }
    return com.jlees.budgey.scan.ScanResult(
        kind = k,
        merchant = merchant,
        brand = brandId?.let(brandById),
        amountCents = amountCents,
        date = date(date),
        cycle = if (unit != null) com.jlees.budgey.domain.BillingCycle(unit, cycleCount ?: 1) else null,
        nextBillingDate = date(nextBillingDate),
        trialEndDate = date(trialEndDate),
        amountCandidates = amountCandidates,
        subscriptionScore = subscriptionScore,
        rawText = rawText,
        isRefund = isRefund,
    )
}
