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

    /**
     * The range as words that fit in a sentence ("spent ___", "No spending ___"): "this month",
     * "all time", or for a custom range "from Mar 3 to Apr 2".
     */
    fun phrase(custom: DateRange?, today: LocalDate): String {
        if (this != CUSTOM) return label.lowercase()
        val r = custom ?: return "in this range"
        fun d(x: LocalDate) = x.format(
            java.time.format.DateTimeFormatter.ofPattern(if (x.year == today.year) "MMM d" else "MMM d, yyyy")
        )
        return if (r.start == r.end) "on ${d(r.start)}" else "from ${d(r.start)} to ${d(r.end)}"
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
) : java.io.Serializable {
    /** Number of filters beyond the date range — shown as a badge. */
    val activeCount: Int
        get() = listOf(
            query.isNotBlank(), categoryIds.isNotEmpty(), uncategorizedOnly, minCents != null || maxCents != null,
            amountKind != AmountKind.ALL, sources.isNotEmpty(), paymentMethodIds.isNotEmpty(), brandIds.isNotEmpty(),
            hasReceipt != null,
        ).count { it }

    /**
     * "Clear all": back to [base] — the category / payment method a filtered screen was opened
     * for — keeping the date range and sort you picked.
     */
    fun clearedTo(base: PurchaseFilter) = base.copy(datePreset = datePreset, customRange = customRange, sort = sort)

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
    ): List<BudgetStatus> {
        // One pass over the purchases per budget period (week / month / quarter / year), adding up
        // spending per category; each budget then just sums its folder. Re-filtering every purchase
        // for every budget got slow with years of data.
        val byRange = HashMap<DateRange, Map<String?, Long>>()
        fun totals(range: DateRange) = byRange.getOrPut(range) {
            val out = HashMap<String?, Long>()
            for (p in purchases) if (p.date in range) out[p.categoryId] = (out[p.categoryId] ?: 0L) + p.amountCents
            out
        }
        return tree.all.mapNotNull { c ->
            val budget = c.budgetCents ?: return@mapNotNull null
            val range = c.budgetPeriod.rangeContaining(today, firstDayOfWeek)
            val sums = totals(range)
            val spent = tree.subtreeIds(c.id).sumOf { sums[it] ?: 0L }
            BudgetStatus(c, budget, spent, range, today)
        }
    }
}
