package com.jlees.budgey.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jlees.budgey.AppContainer
import com.jlees.budgey.data.db.PaymentMethodEntity
import com.jlees.budgey.data.db.PurchaseEntity
import com.jlees.budgey.data.db.PurchaseSource
import com.jlees.budgey.data.db.SubscriptionEntity
import com.jlees.budgey.data.db.SubscriptionPeriodEntity
import com.jlees.budgey.domain.CategoryTree
import com.jlees.budgey.domain.DateRange
import com.jlees.budgey.domain.Renewals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** Everything the calendar needs, indexed by date so each month renders instantly. */
data class CalendarData(
    val loading: Boolean = true,
    val today: LocalDate = LocalDate.now(),
    val tree: CategoryTree = CategoryTree.EMPTY,
    val firstDayOfWeek: DayOfWeek = DayOfWeek.SUNDAY,
    val purchasesByDate: Map<LocalDate, List<PurchaseEntity>> = emptyMap(),
    val totalsByDate: Map<LocalDate, Long> = emptyMap(),
    /** Live subscriptions (for projecting future renewals). */
    val subscriptions: List<SubscriptionEntity> = emptyList(),
    /** Earlier price/cycle periods of each subscription. */
    val periods: List<SubscriptionPeriodEntity> = emptyList(),
    /** For the day list's purchase rows (same as the Purchases tab). */
    val paymentMethods: Map<String, PaymentMethodEntity> = emptyMap(),
) {
    /**
     * Renewal markers for a month: upcoming charges, logged past payments, and past billing
     * dates since each subscription's start date (shown even when no payment was logged).
     */
    fun renewalsIn(month: YearMonth): Map<LocalDate, List<RenewalMark>> =
        renewalCache[month] ?: computeRenewals(month).also { renewalCache[month] = it }

    /** Already worked out for [month]? (so a page can show it at once instead of computing on the UI thread) */
    fun cachedRenewals(month: YearMonth): Map<LocalDate, List<RenewalMark>>? = renewalCache[month]

    // Per data snapshot: a new snapshot (anything changed) starts with an empty cache.
    private val renewalCache = java.util.concurrent.ConcurrentHashMap<YearMonth, Map<LocalDate, List<RenewalMark>>>()

    private fun computeRenewals(month: YearMonth): Map<LocalDate, List<RenewalMark>> {
        val range = DateRange(month.atDay(1), month.atEndOfMonth())
        val out = HashMap<LocalDate, MutableList<RenewalMark>>()
        Renewals.byDate(subscriptions, range, today).forEach { (d, subs) ->
            subs.forEach { s -> out.getOrPut(d) { mutableListOf() } += RenewalMark(s.id, s.name, s.brandKey, s.amountCents, s.categoryId, projected = true) }
        }
        // Past billing dates (from the start date) — logged ones are replaced by the real purchase below.
        subscriptions.forEach { s ->
            val pastEnd = minOf(range.end, today.minusDays(1))
            if (!pastEnd.isBefore(range.start)) Renewals.paidDates(s, periods, pastEnd).forEach { (d, amount) ->
                if (d !in range) return@forEach
                val loggedThatDay = purchasesByDate[d]?.any { it.subscriptionId == s.id } == true
                if (!loggedThatDay) {
                    out.getOrPut(d) { mutableListOf() } += RenewalMark(s.id, s.name, s.brandKey, amount, s.categoryId, projected = false, logged = false)
                }
            }
        }
        var d = range.start
        while (!d.isAfter(range.end)) {
            purchasesByDate[d]?.filter { it.source == PurchaseSource.SUBSCRIPTION || it.subscriptionId != null }?.forEach { p ->
                val list = out.getOrPut(d) { mutableListOf() }
                if (list.none { it.subscriptionId == p.subscriptionId && p.subscriptionId != null }) {
                    list += RenewalMark(p.subscriptionId, p.merchant, p.brandKey, p.amountCents, p.categoryId, projected = false)
                }
            }
            d = d.plusDays(1)
        }
        return out
    }

    fun monthTotal(month: YearMonth): Long {
        var sum = 0L
        var d = month.atDay(1)
        val end = month.atEndOfMonth()
        while (!d.isAfter(end)) {
            sum += totalsByDate[d] ?: 0L
            d = d.plusDays(1)
        }
        return sum
    }
}

data class RenewalMark(
    val subscriptionId: String?,
    val name: String,
    val brandKey: String?,
    val amountCents: Long,
    val categoryId: String?,
    /** true = upcoming charge, false = a past billing date. */
    val projected: Boolean,
    /** For past dates: whether a payment purchase was recorded that day. */
    val logged: Boolean = true,
)

class CalendarViewModel(c: AppContainer) : ViewModel() {
    val selected = MutableStateFlow(LocalDate.now())

    val data: StateFlow<CalendarData> = combine(
        c.repository.purchases, c.repository.subscriptions, c.repository.categoryTree, c.settings.settings,
        c.repository.subscriptionPeriods,
    ) { purchases, subs, tree, settings, periods ->
        val byDate = purchases.groupBy { it.date }
        CalendarData(
            loading = false,
            today = LocalDate.now(),
            tree = tree,
            firstDayOfWeek = settings.firstDayOfWeek,
            purchasesByDate = byDate,
            totalsByDate = byDate.mapValues { (_, l) -> l.sumOf { it.amountCents } },
            subscriptions = subs,
            periods = periods,
        )
    }.combine(c.repository.paymentMethods) { d, methods -> d.copy(paymentMethods = methods.associateBy { it.id }) }
        .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarData())

    fun select(date: LocalDate) {
        selected.value = date
    }
}
