package com.jlees.budgey.ui.purchases

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.jlees.budgey.AppContainer
import com.jlees.budgey.data.AppSettings
import com.jlees.budgey.data.ChartType
import com.jlees.budgey.data.db.PurchaseEntity
import com.jlees.budgey.data.db.PaymentMethodEntity
import com.jlees.budgey.data.db.SubscriptionEntity
import com.jlees.budgey.data.db.SubscriptionStatus
import com.jlees.budgey.data.db.isLive
import com.jlees.budgey.data.db.monthlyCents
import com.jlees.budgey.domain.BudgetPeriod
import com.jlees.budgey.domain.BudgetStatus
import com.jlees.budgey.domain.Budgets
import com.jlees.budgey.domain.Renewals
import java.time.temporal.ChronoUnit
import com.jlees.budgey.domain.CategoryTree
import com.jlees.budgey.domain.DatePreset
import com.jlees.budgey.domain.DateRange
import com.jlees.budgey.domain.PurchaseFilter
import com.jlees.budgey.ui.components.ChartSlice
import com.jlees.budgey.ui.navigation.PurchasesRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class DayGroup(val date: LocalDate, val total: Long, val items: List<PurchaseEntity>)

data class UpcomingRenewal(val subscription: SubscriptionEntity, val date: LocalDate)

/** The "dashboard" bits shown above the list on the main Purchases tab (formerly the Home tab). */
data class Overview(
    val monthSpent: Long = 0,
    /** Spending last month up to the same day of the month, for a fair comparison. */
    val lastMonthToDate: Long = 0,
    val monthlyBudget: Long = 0,
    val budgetedSpent: Long = 0,
    val daysLeftInMonth: Int = 0,
    val budgets: List<BudgetStatus> = emptyList(),
    val budgetCount: Int = 0,
    val upcoming: List<UpcomingRenewal> = emptyList(),
    val upcomingTotal: Long = 0,
    val subscriptionsMonthly: Long = 0,
    val trialsEndingSoon: List<SubscriptionEntity> = emptyList(),
    val week: List<Pair<LocalDate, Long>> = emptyList(),
) {
    val budgetRemaining: Long get() = monthlyBudget - budgetedSpent
    val perDayLeft: Long get() = if (daysLeftInMonth > 0) budgetRemaining.coerceAtLeast(0) / daysLeftInMonth else 0
    /** Percent change vs last month to date, or null when there's nothing to compare. */
    val deltaPercent: Int?
        get() = if (lastMonthToDate <= 0) null else (((monthSpent - lastMonthToDate) * 100) / lastMonthToDate).toInt()
}

data class PurchasesUiState(
    val loading: Boolean = true,
    val tree: CategoryTree = CategoryTree.EMPTY,
    val settings: AppSettings = AppSettings(),
    val filter: PurchaseFilter = PurchaseFilter(),
    val groups: List<DayGroup> = emptyList(),
    val total: Long = 0,
    val count: Int = 0,
    val slices: List<ChartSlice> = emptyList(),
    val daily: List<Pair<LocalDate, Long>> = emptyList(),
    val uncategorizedCount: Int = 0,
    val paymentMethods: List<PaymentMethodEntity> = emptyList(),
    /** [paymentMethods] by id, so each row doesn't search the list. */
    val methodsById: Map<String, PaymentMethodEntity> = emptyMap(),
    /** Ids of the purchases currently listed (bulk actions only ever act on these). */
    val visibleIds: Set<String> = emptySet(),
    /** Is anything filtered beyond what this screen was opened for? (shows "Clear all") */
    val canClear: Boolean = false,
    val rangeLabel: String = "",
    /** The category the chart is currently drilled into (when exactly one is filtered). */
    val chartParent: String? = null,
    val overview: Overview = Overview(),
    val today: LocalDate = LocalDate.now(),
)

class PurchasesViewModel(private val c: AppContainer, handle: SavedStateHandle) : ViewModel() {
    private val route = handle.toRoute<PurchasesRoute>()
    val isScoped = route.categoryId != null || route.uncategorized || route.paymentMethodId != null

    /** What this screen was opened for: "Clear all" and "Reset" come back here, not to everything. */
    val baseFilter = PurchaseFilter(
        categoryIds = setOfNotNull(route.categoryId),
        uncategorizedOnly = route.uncategorized,
        paymentMethodIds = setOfNotNull(route.paymentMethodId),
        datePreset = if (isScoped) DatePreset.ALL else DatePreset.THIS_MONTH,
    )
    private val filter = MutableStateFlow(baseFilter)

    /**
     * The search box text. It updates on every keystroke; the list follows a moment after you stop
     * typing, so typing stays smooth with thousands of purchases.
     */
    val query = MutableStateFlow("")

    init {
        viewModelScope.launch { searchFollowsTyping() }
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private suspend fun searchFollowsTyping() =
        query.debounce(250).collect { q -> filter.update { if (it.query == q) it else it.copy(query = q) } }

    fun setQuery(q: String) { query.value = q }

    /** Everything you've picked, including purchases a filter has since hidden. */
    private val picked = MutableStateFlow<Set<String>>(emptySet())

    val state: StateFlow<PurchasesUiState> = combine(
        c.repository.purchases,
        c.repository.categoryTree,
        c.settings.settings,
        filter,
        combine(c.repository.paymentMethods, c.repository.subscriptions, ::Pair),
    ) { purchases, tree, settings, f, (methods, subs) ->
        build(purchases, tree, settings, f, methods, subs)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PurchasesUiState())

    /**
     * The selection as far as it's on screen: purchases hidden by a search or filter (or deleted
     * elsewhere) aren't counted, moved or deleted.
     */
    val selection: StateFlow<Set<String>> = combine(picked, state) { s, st ->
        if (s.isEmpty()) s else s.intersect(st.visibleIds)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private fun build(
        purchases: List<PurchaseEntity>,
        tree: CategoryTree,
        settings: AppSettings,
        f: PurchaseFilter,
        methods: List<PaymentMethodEntity>,
        subs: List<SubscriptionEntity>,
    ): PurchasesUiState {
        val today = LocalDate.now()
        val list = f.apply(purchases, tree, today, settings.firstDayOfWeek) { p -> c.brands.resolve(p.brandKey, p.merchant)?.id }
        val groups = if (f.sort == com.jlees.budgey.domain.SortOrder.DATE_DESC || f.sort == com.jlees.budgey.domain.SortOrder.DATE_ASC) {
            list.groupBy { it.date }.map { (d, items) -> DayGroup(d, items.sumOf { it.amountCents }, items) }
        } else {
            listOf(DayGroup(LocalDate.MIN, list.sumOf { it.amountCents }, list))
        }

        // ---- chart ----
        val parent = f.categoryIds.singleOrNull()?.takeIf { f.includeSubcategories }
        // Top-level categories first; tapping one splits it into its sub-categories, and so on.
        val bucketed = list.groupBy { p -> com.jlees.budgey.domain.ChartBuckets.bucket(tree, p.categoryId, parent) }
        val uncategorizedColor = Color(0xFF9E9E9E)
        val slices = bucketed.map { (key, items) ->
            val cat = key?.let { tree.byId[it] }
            ChartSlice(
                key = key,
                label = when {
                    cat == null -> "Uncategorized"
                    key == parent -> "${cat.name} (direct)"
                    else -> cat.name
                },
                value = items.sumOf { it.amountCents },
                color = cat?.let { Color(it.color) } ?: uncategorizedColor,
            )
        }.sortedByDescending { it.value }

        val range = f.datePreset.range(today, settings.firstDayOfWeek, f.customRange)
            ?: list.minOfOrNull { it.date }?.let { DateRange(it, today) }
        val daily = range?.let { r ->
            val start = if (r.days > 400) r.end.minusDays(399) else r.start
            val end = if (r.end.isAfter(today)) today else r.end
            val byDay = list.groupBy { it.date }.mapValues { (_, l) -> l.sumOf { it.amountCents } }
            generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }
                .map { it to (byDay[it] ?: 0L) }.toList()
        } ?: emptyList()

        return PurchasesUiState(
            loading = false,
            tree = tree,
            settings = settings,
            filter = f,
            groups = groups,
            total = list.sumOf { it.amountCents },
            count = list.size,
            slices = slices,
            daily = daily,
            uncategorizedCount = purchases.count { it.categoryId == null || it.categoryId !in tree.byId },
            paymentMethods = methods,
            methodsById = methods.associateBy { it.id },
            visibleIds = list.mapTo(HashSet(list.size)) { it.id },
            canClear = f.clearedTo(baseFilter) != f,
            rangeLabel = f.datePreset.phrase(f.customRange, today),
            chartParent = parent,
            overview = if (isScoped) Overview() else overview(purchases, subs, tree, settings, today),
            today = today,
        )
    }

    private fun overview(
        purchases: List<PurchaseEntity>,
        subs: List<SubscriptionEntity>,
        tree: CategoryTree,
        settings: AppSettings,
        today: LocalDate,
    ): Overview {
        val month = BudgetPeriod.MONTHLY.rangeContaining(today)
        val lastMonthStart = month.start.minusMonths(1)
        val lastMonthToDate = DateRange(lastMonthStart, minOf(lastMonthStart.plusDays((today.dayOfMonth - 1).toLong()), month.start.minusDays(1)))
        val inMonth = purchases.filter { it.date in month }
        // Overall monthly budget = top-level budgets only (a child's budget is part of its parent's).
        val statuses = Budgets.statuses(tree, purchases, today, settings.firstDayOfWeek)
        val topBudgets = statuses.filter { s -> tree.path(s.category.id).dropLast(1).none { it.budgetCents != null } }
        val budgetedIds = topBudgets.flatMap { tree.subtreeIds(it.category.id) }.toSet()
        val byDay = purchases.filter { !it.date.isBefore(today.minusDays(6)) && !it.date.isAfter(today) }
            .groupBy { it.date }.mapValues { (_, l) -> l.sumOf { it.amountCents } }
        val upcoming = Renewals.byDate(subs, DateRange(today, today.plusDays(14)), today)
            .flatMap { (d, list) -> list.map { UpcomingRenewal(it, d) } }.sortedBy { it.date }
        return Overview(
            monthSpent = inMonth.sumOf { it.amountCents },
            lastMonthToDate = purchases.filter { it.date in lastMonthToDate }.sumOf { it.amountCents },
            monthlyBudget = topBudgets.sumOf { Math.round(it.budgetCents * it.category.budgetPeriod.perMonth) },
            budgetedSpent = inMonth.filter { it.categoryId in budgetedIds }.sumOf { it.amountCents },
            daysLeftInMonth = (ChronoUnit.DAYS.between(today, month.end) + 1).toInt(),
            budgets = statuses.sortedByDescending { it.fraction }.take(3),
            budgetCount = statuses.size,
            upcoming = upcoming.take(5),
            upcomingTotal = upcoming.sumOf { it.subscription.amountCents },
            subscriptionsMonthly = subs.filter { it.isLive }.sumOf { it.monthlyCents },
            // Trials ending within a week, plus ended ones still marked as trials (being charged now).
            // Trials that ended over a month ago aren't news any more.
            trialsEndingSoon = subs.filter {
                it.status == SubscriptionStatus.TRIAL && it.trialEndDate != null &&
                    !it.trialEndDate.isAfter(today.plusDays(7)) && !it.trialEndDate.isBefore(today.minusDays(30))
            },
            week = (6 downTo 0).map { today.minusDays(it.toLong()) }.map { it to (byDay[it] ?: 0L) },
        )
    }

    fun setFilter(f: PurchaseFilter) {
        filter.update { f }
        query.value = f.query
    }

    fun updateFilter(transform: (PurchaseFilter) -> PurchaseFilter) {
        // Apply to the latest search text, which may not have reached the filter yet.
        filter.update { transform(it.copy(query = query.value)) }
        query.value = filter.value.query
    }

    /** "Clear all" / "Clear filters": back to what this screen was opened for. */
    fun clearFilters(allTime: Boolean = false) {
        val cleared = filter.value.clearedTo(baseFilter)
        setFilter(if (allTime) cleared.copy(datePreset = DatePreset.ALL, customRange = null) else cleared)
    }

    fun setChartType(t: ChartType) = viewModelScope.launch { c.settings.update { it.copy(chartType = t) } }

    /** Tapping a chart slice drills into that category (or uncategorized). */
    fun drillInto(slice: ChartSlice) = filter.update { f ->
        if (slice.key == null) f.copy(categoryIds = emptySet(), uncategorizedOnly = true)
        else if (slice.key == f.categoryIds.singleOrNull()) f // "direct" slice: already there
        else f.copy(categoryIds = setOf(slice.key), uncategorizedOnly = false, includeSubcategories = true)
    }

    /** Back out one level of chart drill-down. */
    fun drillUp() = filter.update { f ->
        val current = f.categoryIds.singleOrNull()
        val parent = current?.let { state.value.tree.byId[it]?.parentId }
        f.copy(categoryIds = setOfNotNull(parent), uncategorizedOnly = false)
    }

    // ---- selection / bulk actions ----
    fun toggleSelect(id: String) = picked.update { s ->
        // Start from what's visible, so a hidden pick doesn't come back when a filter changes.
        val visible = selection.value
        if (id in visible) visible - id else visible + id
    }
    fun clearSelection() = picked.update { emptySet() }

    fun categorizeSelected(categoryId: String?) = viewModelScope.launch {
        c.repository.setCategory(selection.value.toList(), categoryId)
        clearSelection()
    }

    /** Deleted batches whose Undo is still on screen (each snackbar undoes its own batch). */
    private val undoable = java.util.Collections.synchronizedList(mutableListOf<List<PurchaseEntity>>())

    /** Deletes the selected purchases. Returns the batch, for [undoDelete] / [forgetDeleted]. */
    fun deleteSelected(all: List<PurchaseEntity>): List<PurchaseEntity> {
        val ids = selection.value
        val batch = all.filter { it.id in ids }
        undoable += batch
        // One transaction for the whole batch; Undo waits for it, so a restored purchase can't be
        // deleted again by a delete that was still running.
        deleteJobs[batch] = viewModelScope.launch { c.repository.deletePurchases(batch) }
        clearSelection()
        return batch
    }

    private val deleteJobs = java.util.concurrent.ConcurrentHashMap<List<PurchaseEntity>, kotlinx.coroutines.Job>()

    fun undoDelete(batch: List<PurchaseEntity>) = viewModelScope.launch {
        undoable.remove(batch)
        deleteJobs.remove(batch)?.join()
        c.repository.restorePurchases(batch)
    }

    /**
     * The undo is gone: delete the receipt photos of the deleted purchases (unless something else
     * still uses them). Runs in the app's scope so it finishes even if this screen is closing.
     */
    fun forgetDeleted(batch: List<PurchaseEntity>) {
        deleteJobs.remove(batch)
        if (!undoable.remove(batch)) return
        val photos = batch.mapNotNull { it.receiptFile }
        if (photos.isNotEmpty()) c.appScope.launch { photos.forEach { c.repository.deleteReceiptIfUnused(it) } }
    }

    override fun onCleared() {
        synchronized(undoable) { undoable.toList() }.forEach(::forgetDeleted)
    }
}
