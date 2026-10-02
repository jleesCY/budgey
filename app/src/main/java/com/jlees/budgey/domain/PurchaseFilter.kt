package com.jlees.budgey.domain

import com.jlees.budgey.data.db.CategoryEntity
import com.jlees.budgey.data.db.PurchaseEntity
import com.jlees.budgey.data.db.PurchaseSource
import java.time.DayOfWeek
import java.time.LocalDate

enum class DatePreset(val label: String) {
    THIS_WEEK("This week"),
    THIS_MONTH("This month"),
    LAST_MONTH("Last month"),
    LAST_30("Last 30 days"),
    LAST_90("Last 90 days"),
    THIS_YEAR("This year"),
    ALL("All time"),
    CUSTOM("Custom range");

    fun range(today: LocalDate, firstDayOfWeek: DayOfWeek, custom: DateRange?): DateRange? = when (this) {
        THIS_WEEK -> BudgetPeriod.WEEKLY.rangeContaining(today, firstDayOfWeek)
        THIS_MONTH -> BudgetPeriod.MONTHLY.rangeContaining(today)
        LAST_MONTH -> BudgetPeriod.MONTHLY.rangeContaining(today.minusMonths(1))
        LAST_30 -> DateRange(today.minusDays(29), today)
        LAST_90 -> DateRange(today.minusDays(89), today)
        THIS_YEAR -> BudgetPeriod.YEARLY.rangeContaining(today)
        ALL -> null
        CUSTOM -> custom
    }
}

enum class SortOrder(val label: String) {
    DATE_DESC("Newest first"), DATE_ASC("Oldest first"), AMOUNT_DESC("Highest amount"),
    AMOUNT_ASC("Lowest amount"), MERCHANT("Merchant A–Z"),
}

enum class AmountKind(val label: String) { ALL("All"), EXPENSES("Expenses"), REFUNDS("Refunds") }

/** Every filter the purchases list supports. Empty sets mean "don't filter on this". */
data class PurchaseFilter(
    val query: String = "",
    val datePreset: DatePreset = DatePreset.THIS_MONTH,
    val customRange: DateRange? = null,
    val categoryIds: Set<String> = emptySet(),
    val includeSubcategories: Boolean = true,
    val uncategorizedOnly: Boolean = false,
    val minCents: Long? = null,
    val maxCents: Long? = null,
    val amountKind: AmountKind = AmountKind.ALL,
    val sources: Set<PurchaseSource> = emptySet(),
    /** Saved payment method ids. */
    val paymentMethodIds: Set<String> = emptySet(),
    val brandIds: Set<String> = emptySet(),
    val hasReceipt: Boolean? = null,
    val sort: SortOrder = SortOrder.DATE_DESC,
) {
    /** Number of filters beyond the date range — shown as a badge. */
    val activeCount: Int
        get() = listOf(
            query.isNotBlank(), categoryIds.isNotEmpty(), uncategorizedOnly, minCents != null || maxCents != null,
            amountKind != AmountKind.ALL, sources.isNotEmpty(), paymentMethodIds.isNotEmpty(), brandIds.isNotEmpty(),
            hasReceipt != null,
        ).count { it }

    fun cleared() = PurchaseFilter(datePreset = datePreset, customRange = customRange, sort = sort)

    fun apply(
        purchases: List<PurchaseEntity>,
        tree: CategoryTree,
        today: LocalDate,
        firstDayOfWeek: DayOfWeek,
        brandOf: (PurchaseEntity) -> String?,
    ): List<PurchaseEntity> {
        val range = datePreset.range(today, firstDayOfWeek, customRange)
        val cats: Set<String> = if (includeSubcategories) categoryIds.flatMap { tree.subtreeIds(it) }.toSet() else categoryIds
        val q = query.trim().lowercase()
        val filtered = purchases.filter { p ->
            (range == null || p.date in range) &&
                (!uncategorizedOnly || p.categoryId == null || p.categoryId !in tree.byId) &&
                (cats.isEmpty() || p.categoryId in cats) &&
                (minCents == null || kotlin.math.abs(p.amountCents) >= minCents) &&
                (maxCents == null || kotlin.math.abs(p.amountCents) <= maxCents) &&
                (amountKind == AmountKind.ALL || (amountKind == AmountKind.EXPENSES) == (p.amountCents >= 0)) &&
                (sources.isEmpty() || p.source in sources) &&
                (paymentMethodIds.isEmpty() || p.paymentMethodId in paymentMethodIds) &&
                (hasReceipt == null || (p.receiptFile != null) == hasReceipt) &&
                (brandIds.isEmpty() || brandOf(p) in brandIds) &&
                (q.isEmpty() || p.merchant.lowercase().contains(q) || p.note.lowercase().contains(q) ||
                    p.paymentMethod.lowercase().contains(q) ||
                    (p.categoryId?.let { tree.byId[it]?.name?.lowercase()?.contains(q) } == true) ||
                    Money.toInput(kotlin.math.abs(p.amountCents)).startsWith(q))
        }
        return when (sort) {
            SortOrder.DATE_DESC -> filtered.sortedWith(compareByDescending<PurchaseEntity> { it.date }.thenByDescending { it.createdAt })
            SortOrder.DATE_ASC -> filtered.sortedWith(compareBy<PurchaseEntity> { it.date }.thenBy { it.createdAt })
            SortOrder.AMOUNT_DESC -> filtered.sortedByDescending { it.amountCents }
            SortOrder.AMOUNT_ASC -> filtered.sortedBy { it.amountCents }
            SortOrder.MERCHANT -> filtered.sortedBy { it.merchant.lowercase() }
        }
    }
}

/** Spending per category including all descendants, for budgets and charts. */
object Spending {
    /** Direct totals keyed by categoryId (null = uncategorized). */
    fun direct(purchases: List<PurchaseEntity>): Map<String?, Long> =
        purchases.groupBy { it.categoryId }.mapValues { (_, l) -> l.sumOf { it.amountCents } }

    /** Totals that roll children up into every ancestor. */
    fun rolledUp(purchases: List<PurchaseEntity>, tree: CategoryTree): Map<String, Long> {
        val out = HashMap<String, Long>()
        for (p in purchases) {
            val id = p.categoryId ?: continue
            tree.path(id).forEach { c -> out[c.id] = (out[c.id] ?: 0L) + p.amountCents }
        }
        return out
    }

    fun inRange(purchases: List<PurchaseEntity>, range: DateRange) = purchases.filter { it.date in range }
}

data class BudgetStatus(
    val category: CategoryEntity,
    val budgetCents: Long,
    val spentCents: Long,
    val range: DateRange,
    val today: LocalDate,
) {
    val remaining: Long get() = budgetCents - spentCents
    val fraction: Float get() = if (budgetCents <= 0) 0f else spentCents.toFloat() / budgetCents
    val over: Boolean get() = spentCents > budgetCents

    /** Fraction of the period that has elapsed — used to show "on pace" vs "ahead of pace". */
    val elapsed: Float
        get() {
            val d = java.time.temporal.ChronoUnit.DAYS.between(range.start, today) + 1
            return (d.toFloat() / range.days).coerceIn(0f, 1f)
        }

    val aheadOfPace: Boolean get() = !over && fraction > elapsed + 0.1f
}

object Budgets {
    fun statuses(
        tree: CategoryTree,
        purchases: List<PurchaseEntity>,
        today: LocalDate,
        firstDayOfWeek: DayOfWeek,
    ): List<BudgetStatus> = tree.all.mapNotNull { c ->
        val budget = c.budgetCents ?: return@mapNotNull null
        val range = c.budgetPeriod.rangeContaining(today, firstDayOfWeek)
        val ids = tree.subtreeIds(c.id)
        val spent = purchases.filter { it.date in range && it.categoryId in ids }.sumOf { it.amountCents }
        BudgetStatus(c, budget, spent, range, today)
    }
}
