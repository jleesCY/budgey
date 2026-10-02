package com.jlees.budgey.ui.subscriptions

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.jlees.budgey.AppContainer
import com.jlees.budgey.data.db.PurchaseEntity
import com.jlees.budgey.data.db.PaymentMethodEntity
import com.jlees.budgey.data.db.displayName
import com.jlees.budgey.data.db.PurchaseSource
import com.jlees.budgey.data.db.SubscriptionEntity
import com.jlees.budgey.data.db.SubscriptionPeriodEntity
import com.jlees.budgey.data.db.SubscriptionStatus
import com.jlees.budgey.data.db.cycle
import com.jlees.budgey.domain.BillingCycle
import com.jlees.budgey.domain.CategoryTree
import com.jlees.budgey.icons.Brand
import com.jlees.budgey.domain.CycleUnit
import com.jlees.budgey.domain.Money
import com.jlees.budgey.domain.PriceField
import com.jlees.budgey.domain.PriceInputs
import com.jlees.budgey.domain.PriceMath
import com.jlees.budgey.ui.navigation.SubscriptionEditRoute
import com.jlees.budgey.reminders.RenewalReminders
import com.jlees.budgey.data.HistorySync
import com.jlees.budgey.data.db.isLive
import com.jlees.budgey.domain.Renewals
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import com.jlees.budgey.scan.ScanResult
import com.jlees.budgey.scan.ScanKind
import com.jlees.budgey.scan.ScanDraft

data class SubscriptionForm(
    val id: String? = null,
    val name: String = "",
    /** Base price + fees & taxes = total charged; editing any two fills in the third. */
    val price: PriceInputs = PriceInputs(),
    val cycleUnit: CycleUnit = CycleUnit.MONTH,
    val cycleCount: Int = 1,
    /** First (or any known) billing date; future dates are computed from it. */
    val anchorDate: LocalDate = LocalDate.now(),
    val nextDueDate: LocalDate = LocalDate.now(),
    /**
     * false (default) = next payment is calculated from the start date + billing cycle.
     * true = the user turned auto-calculation off and picks the date by hand.
     */
    val nextDueOverridden: Boolean = false,
    val categoryId: String? = null,
    val categoryTouched: Boolean = false,
    val suggestedCategoryId: String? = null,
    val brandKey: String? = null,
    val status: SubscriptionStatus = SubscriptionStatus.ACTIVE,
    val autoLog: Boolean = true,
    val trialEndDate: LocalDate? = null,
    val paymentMethod: String = "",
    val paymentMethodId: String? = null,
    val note: String = "",
    val receiptFile: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val fromScan: Boolean = false,
    val scanText: String? = null,
    /** null = app default, negative = off, else days before. */
    val reminderDays: Int? = null,
    /** Last billing day when paused/cancelled (null while live). */
    val endDate: LocalDate? = null,
    /** Earlier price / cycle periods (e.g. a student discount), oldest first. */
    val periods: List<SubscriptionPeriodEntity> = emptyList(),
) {
    /** What gets logged each billing date: the total charged. */
    val amountCents: Long? get() = price.totalCents
    val cycle: BillingCycle get() = BillingCycle(cycleUnit, cycleCount.coerceAtLeast(1))
    val canSave: Boolean get() = name.isNotBlank() && (amountCents ?: 0) > 0
}

data class PaymentHistory(val count: Int, val total: Long, val recent: List<PurchaseEntity>)

class SubscriptionEditViewModel(private val c: AppContainer, handle: SavedStateHandle) : ViewModel() {
    private val route = handle.toRoute<SubscriptionEditRoute>()
    val isNew = route.id == null

    /** The subscription as it was when the editor opened (null for a new one). Declared before init. */
    private var original: SubscriptionEntity? = null
    private var originalPeriods: List<SubscriptionPeriodEntity> = emptyList()
    /** The scan / switched-in draft this editor started from (kept so switching back is lossless). */
    private var draft: ScanDraft? = null

    private val _form = MutableStateFlow(SubscriptionForm())
    val form: StateFlow<SubscriptionForm> = _form.asStateFlow()
    val tree: StateFlow<CategoryTree> = c.repository.categoryTree.stateIn(viewModelScope, SharingStarted.Eagerly, CategoryTree.EMPTY)

    val history: StateFlow<PaymentHistory> = c.repository.purchases.map { all ->
        val mine = all.filter { it.subscriptionId != null && it.subscriptionId == route.id }
        PaymentHistory(mine.size, mine.sumOf { it.amountCents }, mine.take(6))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PaymentHistory(0, 0, emptyList()))

    init {
        viewModelScope.launch {
            val id = route.id
            if (id != null) {
                loadExisting(id)
            } else {
                if (route.fromScan) c.scanDrafts.take()?.let { d ->
                    draft = d
                    val r = d.result
                    val cycle = r.cycle ?: BillingCycle.MONTHLY
                    val today = LocalDate.now()
                    // Best anchor: the charge date on the receipt, else the next billing date.
                    val anchor = r.date ?: r.nextBillingDate ?: today
                    val next = r.nextBillingDate ?: cycle.nextOnOrAfter(anchor, today)
                    _form.update {
                        it.copy(
                            name = r.merchant ?: "",
                            // A scanned amount is what was actually charged → it goes in the total box.
                            price = PriceInputs.from(null, null, r.amountCents),
                            cycleUnit = cycle.unit, cycleCount = cycle.count,
                            anchorDate = anchor,
                            nextDueDate = next,
                            nextDueOverridden = r.nextBillingDate != null,
                            status = if (r.trialEndDate != null) SubscriptionStatus.TRIAL else SubscriptionStatus.ACTIVE,
                            trialEndDate = r.trialEndDate,
                            receiptFile = d.receiptFile,
                            fromScan = r.rawText.isNotBlank(),
                            scanText = r.rawText.ifBlank { null },
                            categoryId = d.categoryId ?: it.categoryId,
                            categoryTouched = d.categoryId != null,
                            paymentMethodId = d.paymentMethodId,
                            brandKey = d.brandKey,
                            note = d.note,
                        )
                    }
                    suggest()
                }
                recompute()
            }
        }
    }

    /** Hands what's typed so far to the purchase editor (Purchase ⇄ Subscription switch). */
    fun convertToPurchase() {
        val f = _form.value
        val base = draft?.result ?: ScanResult(
            kind = ScanKind.PURCHASE, merchant = null, brand = null, amountCents = null, date = null,
            cycle = null, nextBillingDate = null, trialEndDate = null,
            amountCandidates = emptyList(), subscriptionScore = 0, rawText = "",
        )
        c.scanDrafts.put(
            ScanDraft(
                result = base.copy(
                    kind = ScanKind.PURCHASE, merchant = f.name, amountCents = f.amountCents,
                    // The most recent charge, if the start date is in the past; otherwise the start date.
                    date = minOf(f.anchorDate, LocalDate.now()),
                    cycle = f.cycle, nextBillingDate = f.nextDueDate.takeIf { f.nextDueOverridden },
                    trialEndDate = f.trialEndDate, rawText = f.scanText ?: "",
                ),
                receiptFile = f.receiptFile,
                categoryId = f.categoryId.takeIf { f.categoryTouched },
                paymentMethodId = f.paymentMethodId,
                brandKey = f.brandKey,
                note = f.note,
                switched = true,
            )
        )
    }

    private fun recompute() = _form.update {
        if (it.nextDueOverridden) it else it.copy(nextDueDate = it.cycle.nextOnOrAfter(it.anchorDate, LocalDate.now()))
    }

    private var suggestJob: Job? = null
    private suspend fun suggest() {
        val f = _form.value
        val s = c.repository.suggestCategory(f.name, f.brandKey)
        _form.update { if (!it.categoryTouched) it.copy(categoryId = s, suggestedCategoryId = s) else it.copy(suggestedCategoryId = s) }
    }

    fun setName(v: String) {
        _form.update { it.copy(name = v) }
        suggestJob?.cancel()
        suggestJob = viewModelScope.launch { delay(350); suggest() }
    }

    /** Edit one of the three price boxes; the calculated one updates automatically. */
    fun setPrice(field: PriceField, text: String) = _form.update { it.copy(price = PriceMath.edit(it.price, field, text)) }
    fun setCycle(cycle: BillingCycle) { _form.update { it.copy(cycleUnit = cycle.unit, cycleCount = cycle.count) }; recompute() }
    fun setCycleUnit(u: CycleUnit) { _form.update { it.copy(cycleUnit = u) }; recompute() }
    fun setCycleCount(n: Int) { _form.update { it.copy(cycleCount = n.coerceIn(1, 365)) }; recompute() }
    fun setAnchor(d: LocalDate) { _form.update { it.copy(anchorDate = d) }; recompute() }
    fun setNextDue(d: LocalDate) = _form.update { it.copy(nextDueDate = d, nextDueOverridden = true) }

    /** Toggle automatic next-payment calculation (on by default). */
    fun setAutoNextDue(auto: Boolean) {
        _form.update { it.copy(nextDueOverridden = !auto) }
        recompute()
    }
    fun setCategory(id: String?) = _form.update { it.copy(categoryId = id, categoryTouched = true) }
    /** Icon picked in the icon sheet. Picking a logo also fills an empty name and the category. */
    fun setIcon(ref: String?, brand: Brand?) {
        _form.update { it.copy(brandKey = ref, name = if (brand != null && it.name.isBlank()) brand.name else it.name) }
        if (brand != null && !_form.value.categoryTouched) viewModelScope.launch {
            c.repository.categoryForBrand(brand)?.let { id -> _form.update { it.copy(categoryId = id, suggestedCategoryId = id) } }
        }
    }
    fun setStatus(s: SubscriptionStatus) = _form.update {
        val stopping = s == SubscriptionStatus.PAUSED || s == SubscriptionStatus.CANCELLED
        it.copy(
            status = s,
            trialEndDate = if (s == SubscriptionStatus.TRIAL) (it.trialEndDate ?: it.nextDueDate) else it.trialEndDate,
            // Stopping records when billing ended; nothing before that date changes.
            endDate = if (stopping) (it.endDate ?: LocalDate.now()) else null,
        )
    }

    fun setEndDate(d: LocalDate) = _form.update { it.copy(endDate = d) }

    /** True when switching this saved subscription back on should go through "Resume" (it has a stop date). */
    val needsResume: Boolean
        get() = original != null && _form.value.endDate != null &&
            (_form.value.status == SubscriptionStatus.PAUSED || _form.value.status == SubscriptionStatus.CANCELLED)

    /**
     * Resume a paused/cancelled subscription on [date] (e.g. Dec 15) without creating a new one and
     * without touching the past: the run that ended becomes a history period, and the current
     * period restarts on [date] with the price in the form (change it in the dialog if it's different now).
     */
    fun resume(date: LocalDate, totalText: String?) {
        _form.update { f ->
            val end = minOf(f.endDate ?: date.minusDays(1), date.minusDays(1))
            val closed = if (!f.anchorDate.isAfter(end)) {
                SubscriptionPeriodEntity(
                    subscriptionId = f.id ?: "",
                    startDate = f.anchorDate,
                    endDate = end,
                    amountCents = original?.amountCents ?: (f.amountCents ?: 0),
                    cycleUnit = f.cycleUnit,
                    cycleCount = f.cycleCount,
                    label = "",
                )
            } else null
            f.copy(
                periods = (f.periods + listOfNotNull(closed)).sortedBy { it.startDate },
                anchorDate = date,
                nextDueOverridden = false,
                status = SubscriptionStatus.ACTIVE,
                endDate = null,
                price = if (!totalText.isNullOrBlank()) PriceMath.edit(f.price, PriceField.TOTAL, totalText) else f.price,
            )
        }
        recompute()
    }

    /** Add or replace an earlier period in the price history. */
    fun upsertPeriod(p: SubscriptionPeriodEntity) = _form.update { f ->
        f.copy(periods = (f.periods.filter { it.id != p.id } + p).sortedBy { it.startDate })
    }

    fun removePeriod(id: String) = _form.update { f -> f.copy(periods = f.periods.filter { it.id != id }) }
    fun setAutoLog(v: Boolean) = _form.update { it.copy(autoLog = v) }
    fun setTrialEnd(d: LocalDate?) = _form.update { it.copy(trialEndDate = d) }
    val paymentMethods: StateFlow<List<PaymentMethodEntity>> =
        c.repository.paymentMethods.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun setPaymentMethodId(id: String?) = _form.update { it.copy(paymentMethodId = id, paymentMethod = if (id == null) "" else it.paymentMethod) }
    suspend fun createPaymentMethod(m: PaymentMethodEntity) = c.repository.savePaymentMethod(m)
    suspend fun importPaymentIcon(uri: Uri): String = c.paymentIcons.importImage(uri)
    fun setNote(v: String) = _form.update { it.copy(note = v) }
    fun setReminderDays(v: Int?) = _form.update { it.copy(reminderDays = v) }

    val defaultReminderDays: StateFlow<Int> = c.settings.settings.map { it.reminderDaysBefore }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1)
    fun attachReceipt(uri: Uri) = viewModelScope.launch {
        val name = c.receipts.importImage(uri)
        _form.update { it.copy(receiptFile = name) }
    }
    fun removeReceipt() = _form.update { it.copy(receiptFile = null) }
    fun receiptFile(name: String) = c.receipts.file(name)

    private fun toEntity(f: SubscriptionForm): SubscriptionEntity {
        val base = SubscriptionEntity(
            name = f.name.trim(), amountCents = f.amountCents ?: 0, cycleUnit = f.cycleUnit, cycleCount = f.cycleCount,
            anchorDate = f.anchorDate, nextDueDate = f.nextDueDate, categoryId = f.categoryId, brandKey = f.brandKey,
            status = f.status, autoLog = f.autoLog, trialEndDate = f.trialEndDate,
            paymentMethod = f.paymentMethodId?.let { id -> paymentMethods.value.firstOrNull { it.id == id }?.displayName } ?: f.paymentMethod.trim(),
            paymentMethodId = f.paymentMethodId,
            note = f.note.trim(), receiptFile = f.receiptFile, createdAt = f.createdAt, reminderDays = f.reminderDays,
            endDate = if (f.status == SubscriptionStatus.PAUSED || f.status == SubscriptionStatus.CANCELLED) f.endDate else null,
            listPriceCents = f.price.baseCents,
            feesCents = f.price.feesCents,
            taxRatePercent = null,
        )
        return if (f.id != null) base.copy(id = f.id) else base
    }

    /** Shown when saving an edit would change already-logged payments; the user must confirm. */
    data class SyncPrompt(
        val entity: SubscriptionEntity,
        val periods: List<SubscriptionPeriodEntity>,
        val plan: HistorySync,
        val changes: List<String>,
        val onDone: () -> Unit,
    )

    private val _syncPrompt = MutableStateFlow<SyncPrompt?>(null)
    val syncPrompt: StateFlow<SyncPrompt?> = _syncPrompt.asStateFlow()

    /** How many past payments would be logged for the current form (for the hint under the start date). */
    suspend fun pastPaymentCount(): Int {
        val f = _form.value
        if (!f.autoLog) return 0
        val e = toEntity(f)
        return Renewals.paidDates(e, f.periods.map { it.copy(subscriptionId = e.id) }, LocalDate.now()).size
    }

    private suspend fun loadExisting(id: String) {
        c.repository.subscription(id)?.let { s ->
            original = s
            val periods = c.repository.periodsFor(s.id)
            originalPeriods = periods
            _form.value = SubscriptionForm(
                endDate = s.endDate,
                periods = periods,
                id = s.id, name = s.name,
                price = PriceInputs.from(s.listPriceCents, s.feesCents ?: s.listPriceCents?.let { s.amountCents - it }, s.amountCents),
                cycleUnit = s.cycleUnit,
                cycleCount = s.cycleCount, anchorDate = s.anchorDate, nextDueDate = s.nextDueDate,
                // Auto stays on unless the saved date is off the start-date rhythm (i.e. was set by hand).
                nextDueOverridden = s.nextDueDate != s.cycle.nextOnOrAfter(s.anchorDate, s.nextDueDate),
                categoryId = s.categoryId, categoryTouched = true, brandKey = s.brandKey, status = s.status,
                autoLog = s.autoLog, trialEndDate = s.trialEndDate, paymentMethod = s.paymentMethod, paymentMethodId = s.paymentMethodId, note = s.note,
                receiptFile = s.receiptFile, createdAt = s.createdAt, reminderDays = s.reminderDays,
            )
        }
    }

    /** Leave edit mode without saving (or refresh after saving): show what's stored. */
    fun reload() = viewModelScope.launch { route.id?.let { loadExisting(it) } }

    fun save(onDone: () -> Unit) = viewModelScope.launch {
        val f = _form.value
        if (!f.canSave) return@launch
        val entity = toEntity(f)
        val periods = f.periods.map { it.copy(subscriptionId = entity.id) }
        val logEnabled = true
        val plan = c.repository.planHistorySync(entity, periods, logEnabled)
        val before = original
        if (before == null || plan.isEmpty) {
            // New subscription: backfill its past payments straight away (that's what the start date means).
            persist(entity, periods, plan, onDone)
        } else {
            _syncPrompt.value = SyncPrompt(entity, periods, plan, describeChanges(before, entity, plan), onDone)
        }
    }

    /** User answered the "update past payments?" prompt. */
    fun answerSync(applyToPast: Boolean) = viewModelScope.launch {
        val p = _syncPrompt.value ?: return@launch
        _syncPrompt.value = null
        if (applyToPast) {
            persist(p.entity, p.periods, p.plan, p.onDone)
        } else {
            // "Only future": if the price or schedule changed, keep the old terms as a history period
            // up to the next unbilled date, and start the new terms from there. Past payments untouched.
            val (entity, periods) = splitForFuture(p.entity, p.periods)
            persist(entity, periods, null, p.onDone)
        }
    }

    /** Dismissed the prompt — nothing is saved. */
    fun cancelSync() {
        _syncPrompt.value = null
    }

    private fun splitForFuture(
        entity: SubscriptionEntity,
        periods: List<SubscriptionPeriodEntity>,
    ): Pair<SubscriptionEntity, List<SubscriptionPeriodEntity>> {
        val before = original ?: return entity to periods
        val termsChanged = before.amountCents != entity.amountCents || before.cycleUnit != entity.cycleUnit ||
            before.cycleCount != entity.cycleCount
        if (!termsChanged || !before.isLive) return entity to periods
        // New terms start at the first payment that hasn't happened yet (or a later start date the user set).
        val newStart = maxOf(before.nextDueDate, entity.anchorDate)
        val oldEnd = newStart.minusDays(1)
        if (before.anchorDate.isAfter(oldEnd)) return entity to periods
        val history = SubscriptionPeriodEntity(
            subscriptionId = entity.id, startDate = before.anchorDate, endDate = oldEnd,
            amountCents = before.amountCents, cycleUnit = before.cycleUnit, cycleCount = before.cycleCount,
        )
        val moved = entity.copy(
            anchorDate = newStart,
            nextDueDate = if (_form.value.nextDueOverridden) entity.nextDueDate else entity.cycle.nextOnOrAfter(newStart, LocalDate.now()),
        )
        return moved to (periods + history).sortedBy { it.startDate }
    }

    private suspend fun persist(
        entity: SubscriptionEntity,
        periods: List<SubscriptionPeriodEntity>,
        plan: HistorySync?,
        onDone: () -> Unit,
    ) {
        c.repository.saveSubscription(entity)
        c.repository.replacePeriods(entity.id, periods)
        if (plan != null) c.repository.applyHistorySync(plan)
        // Log anything due from here on and move the next payment date forward.
        c.repository.processDueSubscriptions()
        // Re-check reminders right away (e.g. a subscription that renews tomorrow).
        RenewalReminders.checkNow(c.context)
        onDone()
    }

    private fun describeChanges(old: SubscriptionEntity, new: SubscriptionEntity, plan: HistorySync): List<String> {
        val out = mutableListOf<String>()
        if (old.amountCents != new.amountCents) out += "Price: ${Money.format(old.amountCents)} → ${Money.format(new.amountCents)}"
        if (_form.value.periods.map { it.copy(subscriptionId = "") } != originalPeriods.map { it.copy(subscriptionId = "") }) {
            out += "Price history changed"
        }
        if (old.name != new.name) out += "Name: ${old.name} → ${new.name}"
        if (old.categoryId != new.categoryId) out += "Category changed"
        if (old.paymentMethod != new.paymentMethod || old.paymentMethodId != new.paymentMethodId) out += "Payment method changed"
        if (old.brandKey != new.brandKey) out += "Icon changed"
        if (old.anchorDate != new.anchorDate || old.cycleUnit != new.cycleUnit || old.cycleCount != new.cycleCount) {
            out += "Billing schedule changed"
        }
        if (old.autoLog != new.autoLog || old.status != new.status || old.trialEndDate != new.trialEndDate) out += "Status / logging changed"
        if (plan.toUpdate.isNotEmpty()) out += "• ${plan.toUpdate.size} logged payment${s(plan.toUpdate.size)} will be updated"
        if (plan.toAdd.isNotEmpty()) out += "• ${plan.toAdd.size} past payment${s(plan.toAdd.size)} will be added"
        if (plan.toRemove.isNotEmpty()) out += "• ${plan.toRemove.size} payment${s(plan.toRemove.size)} on dates that are no longer billing dates will be removed"
        return out
    }

    private fun s(n: Int) = if (n == 1) "" else "s"

    /** Manually record a payment today (e.g. auto-log is off, or a one-off extra charge). */
    fun logPaymentNow() = viewModelScope.launch {
        val f = _form.value
        val id = f.id ?: return@launch
        c.repository.savePurchase(
            PurchaseEntity(
                merchant = f.name, amountCents = f.amountCents ?: return@launch, date = LocalDate.now(),
                categoryId = f.categoryId, brandKey = f.brandKey, paymentMethod = f.paymentMethod, paymentMethodId = f.paymentMethodId,
                // MANUAL (not SUBSCRIPTION) so later schedule/price syncs never touch it.
                source = PurchaseSource.MANUAL, subscriptionId = id, note = "Logged manually",
            )
        )
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        val id = _form.value.id ?: return@launch
        c.repository.subscription(id)?.let { c.repository.deleteSubscription(it) }
        onDone()
    }
}
