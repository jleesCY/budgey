package com.jlees.budgey.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** Inclusive date range. */
data class DateRange(val start: LocalDate, val end: LocalDate) : java.io.Serializable {
    operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)
    val days: Long get() = ChronoUnit.DAYS.between(start, end) + 1
}

/** How often a category budget resets. */
enum class BudgetPeriod(val label: String) {
    WEEKLY("Weekly"),
    MONTHLY("Monthly"),
    QUARTERLY("Quarterly"),
    YEARLY("Yearly");

    /** The period that contains [today]. */
    fun rangeContaining(today: LocalDate, firstDayOfWeek: DayOfWeek = DayOfWeek.SUNDAY): DateRange = when (this) {
        WEEKLY -> {
            val start = today.with(TemporalAdjusters.previousOrSame(firstDayOfWeek))
            DateRange(start, start.plusDays(6))
        }
        MONTHLY -> DateRange(today.withDayOfMonth(1), today.with(TemporalAdjusters.lastDayOfMonth()))
        QUARTERLY -> {
            val firstMonth = ((today.monthValue - 1) / 3) * 3 + 1
            val start = LocalDate.of(today.year, firstMonth, 1)
            DateRange(start, start.plusMonths(3).minusDays(1))
        }
        YEARLY -> DateRange(today.withDayOfYear(1), today.with(TemporalAdjusters.lastDayOfYear()))
    }

    /** Approximate number of these periods per month, used to normalize budgets. */
    val perMonth: Double
        get() = when (this) {
            WEEKLY -> 52.0 / 12.0
            MONTHLY -> 1.0
            QUARTERLY -> 1.0 / 3.0
            YEARLY -> 1.0 / 12.0
        }
}

enum class CycleUnit(val singular: String, val plural: String, /** Longest sensible "every N". */ val maxCount: Int) {
    DAY("day", "days", 365), WEEK("week", "weeks", 104), MONTH("month", "months", 60), YEAR("year", "years", 10);

    fun clamp(count: Int): Int = count.coerceIn(1, maxCount)

    fun add(date: LocalDate, count: Int): LocalDate = when (this) {
        DAY -> date.plusDays(count.toLong())
        WEEK -> date.plusWeeks(count.toLong())
        MONTH -> date.plusMonths(count.toLong())
        YEAR -> date.plusYears(count.toLong())
    }
}

/** A billing cycle such as "every 1 month" or "every 3 months". */
data class BillingCycle(val unit: CycleUnit, val count: Int = 1) : java.io.Serializable {
    init {
        require(count >= 1) { "count must be >= 1" }
    }

    fun next(from: LocalDate): LocalDate = unit.add(from, count)

    /**
     * Next billing date on or after [today], walking forward from [anchor]
     * (the start / last known billing date). Month-end anchors are preserved by
     * always adding from the anchor rather than chaining (Jan 31 -> Feb 28 -> Mar 31).
     */
    fun nextOnOrAfter(anchor: LocalDate, today: LocalDate): LocalDate {
        if (!anchor.isBefore(today)) return anchor
        var n = 1L
        // Jump close to today first so very old anchors are cheap.
        val approxDays = when (unit) {
            CycleUnit.DAY -> 1.0; CycleUnit.WEEK -> 7.0; CycleUnit.MONTH -> 30.44; CycleUnit.YEAR -> 365.25
        } * count
        val skip = (ChronoUnit.DAYS.between(anchor, today) / approxDays).toLong() - 1
        if (skip > 1) n = skip
        while (true) {
            val candidate = nth(anchor, n)
            if (!candidate.isBefore(today)) return candidate
            n++
        }
    }

    /** The [n]th occurrence after [anchor] (n = 0 is the anchor itself). */
    fun nth(anchor: LocalDate, n: Long): LocalDate = when (unit) {
        CycleUnit.DAY -> anchor.plusDays(n * count)
        CycleUnit.WEEK -> anchor.plusWeeks(n * count)
        CycleUnit.MONTH -> anchor.plusMonths(n * count)
        CycleUnit.YEAR -> anchor.plusYears(n * count)
    }

    /** How many times this cycle bills in an average year. */
    val perYear: Double
        get() = when (unit) {
            CycleUnit.DAY -> 365.25 / count
            CycleUnit.WEEK -> 52.1775 / count
            CycleUnit.MONTH -> 12.0 / count
            CycleUnit.YEAR -> 1.0 / count
        }

    /** Cost of [amountCents] per cycle expressed per month. */
    fun monthlyCost(amountCents: Long): Long = Math.round(amountCents * perYear / 12.0)

    /** Cost per year, rounded once (monthly × 12 would be a few cents off, e.g. $99.99/yr → $99.96). */
    fun yearlyCost(amountCents: Long): Long = Math.round(amountCents * perYear)

    val label: String
        get() = when {
            count == 1 && unit == CycleUnit.DAY -> "Daily"
            count == 1 && unit == CycleUnit.WEEK -> "Weekly"
            count == 2 && unit == CycleUnit.WEEK -> "Every 2 weeks"
            count == 1 && unit == CycleUnit.MONTH -> "Monthly"
            count == 3 && unit == CycleUnit.MONTH -> "Quarterly"
            count == 6 && unit == CycleUnit.MONTH -> "Every 6 months"
            count == 1 && unit == CycleUnit.YEAR -> "Yearly"
            else -> "Every $count ${if (count == 1) unit.singular else unit.plural}"
        }

    /** "per month", "per year", "every 2 weeks". */
    val perLabel: String
        get() = if (count == 1) "per ${unit.singular}" else "every $count ${unit.plural}"

    /** Short suffix for prices: "/mo", "/yr", "/2 wk". */
    val shortSuffix: String
        get() {
            val u = when (unit) { CycleUnit.DAY -> "day"; CycleUnit.WEEK -> "wk"; CycleUnit.MONTH -> "mo"; CycleUnit.YEAR -> "yr" }
            return if (count == 1) "/$u" else "/$count $u"
        }

    companion object {
        val MONTHLY = BillingCycle(CycleUnit.MONTH, 1)
        val YEARLY = BillingCycle(CycleUnit.YEAR, 1)
        val WEEKLY = BillingCycle(CycleUnit.WEEK, 1)
        val presets = listOf(
            BillingCycle(CycleUnit.WEEK, 1),
            BillingCycle(CycleUnit.WEEK, 2),
            BillingCycle(CycleUnit.MONTH, 1),
            BillingCycle(CycleUnit.MONTH, 3),
            BillingCycle(CycleUnit.MONTH, 6),
            BillingCycle(CycleUnit.YEAR, 1),
        )
    }
}
