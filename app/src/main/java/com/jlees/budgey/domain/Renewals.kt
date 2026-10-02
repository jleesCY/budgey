package com.jlees.budgey.domain

import com.jlees.budgey.data.db.SubscriptionEntity
import com.jlees.budgey.data.db.SubscriptionPeriodEntity
import com.jlees.budgey.data.db.SubscriptionStatus
import com.jlees.budgey.data.db.cycle
import com.jlees.budgey.data.db.isLive
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** One stretch of billing at a fixed price and cycle. end = null means ongoing. */
data class BillingSegment(
    val start: LocalDate,
    val end: LocalDate?,
    val amountCents: Long,
    val cycle: BillingCycle,
    val label: String,
)

/** Pure logic for renewal dates and reminders (unit-tested, no Android). */
object Renewals {

    /**
     * Billing dates of [sub] that fall inside [range], from today onward only (past charges
     * are represented by the purchases that were actually logged).
     */
    fun upcomingIn(sub: SubscriptionEntity, range: DateRange, today: LocalDate): List<LocalDate> {
        if (!sub.isLive) return emptyList()
        val from = maxOf(range.start, today, sub.nextDueDate)
        if (from.isAfter(range.end)) return emptyList()
        val out = ArrayList<LocalDate>()
        var d = sub.cycle.nextOnOrAfter(sub.anchorDate, from)
        // nextDueDate may have been set by hand off the anchor's rhythm — always include it.
        if (sub.nextDueDate in range && !sub.nextDueDate.isBefore(today)) out += sub.nextDueDate
        var guard = 0
        while (!d.isAfter(range.end) && guard++ < 400) {
            if (d !in out) out += d
            d = sub.cycle.nextOnOrAfter(sub.anchorDate, d.plusDays(1))
        }
        return out.sorted()
    }

    /** Dates billed during a free trial (no charge). */
    fun isFreeTrialDate(sub: SubscriptionEntity, date: LocalDate): Boolean {
        val end = sub.trialEndDate
        return if (end != null) date.isBefore(end) else sub.status == SubscriptionStatus.TRIAL
    }

    /**
     * Every paid billing date from the start (anchor) date through [end], inclusive, oldest first.
     * Used to backfill payment history when a subscription's start date is in the past.
     */
    fun billingDatesThrough(sub: SubscriptionEntity, end: LocalDate, max: Int = 3000): List<LocalDate> {
        val out = ArrayList<LocalDate>()
        var n = 0L
        while (out.size < max && n < max * 2) {
            val d = sub.cycle.nth(sub.anchorDate, n++)
            if (d.isAfter(end)) break
            if (!isFreeTrialDate(sub, d)) out += d
        }
        return out
    }

    /**
     * The stretches of a subscription's life that were billed: every saved history period plus the
     * current period (from anchorDate; open-ended while live, ending at endDate when paused/cancelled).
     * A paused/cancelled subscription without an endDate (older data) has no known current stretch.
     */
    fun segments(sub: SubscriptionEntity, periods: List<SubscriptionPeriodEntity>): List<BillingSegment> {
        val past = periods.filter { it.subscriptionId == sub.id }
            .map { BillingSegment(it.startDate, it.endDate, it.amountCents, it.cycle, it.label) }
        val current = when {
            sub.isLive -> BillingSegment(sub.anchorDate, null, sub.amountCents, sub.cycle, "")
            sub.endDate != null -> BillingSegment(sub.anchorDate, sub.endDate, sub.amountCents, sub.cycle, "")
            else -> null
        }
        return (past + listOfNotNull(current)).sortedBy { it.start }
    }

    /**
     * Every paid billing date through [through] across all segments, with the amount charged that day.
     * If segments overlap, the later-starting one wins. Free-trial dates are skipped.
     */
    fun paidDates(
        sub: SubscriptionEntity,
        periods: List<SubscriptionPeriodEntity>,
        through: LocalDate,
        max: Int = 3000,
    ): java.util.SortedMap<LocalDate, Long> {
        val out = java.util.TreeMap<LocalDate, Long>()
        for (seg in segments(sub, periods)) {
            var n = 0L
            while (out.size < max && n < max * 2) {
                val d = seg.cycle.nth(seg.start, n++)
                if (d.isAfter(through) || (seg.end != null && d.isAfter(seg.end))) break
                if (!isFreeTrialDate(sub, d)) out[d] = seg.amountCents
            }
        }
        return out
    }

    /** Past billing dates (before today) inside [range], across the subscription's whole history. */
    fun pastIn(
        sub: SubscriptionEntity,
        periods: List<SubscriptionPeriodEntity>,
        range: DateRange,
        today: LocalDate,
    ): List<LocalDate> {
        val end = minOf(range.end, today.minusDays(1))
        if (end.isBefore(range.start)) return emptyList()
        return paidDates(sub, periods, end).keys.filter { it in range }
    }

    /** All upcoming renewals in [range] keyed by date. */
    fun byDate(subs: List<SubscriptionEntity>, range: DateRange, today: LocalDate): Map<LocalDate, List<SubscriptionEntity>> {
        val out = HashMap<LocalDate, MutableList<SubscriptionEntity>>()
        for (s in subs) for (d in upcomingIn(s, range, today)) out.getOrPut(d) { mutableListOf() } += s
        return out
    }
}

enum class ReminderKind { RENEWAL, TRIAL_END }

data class Reminder(
    val subscription: SubscriptionEntity,
    val kind: ReminderKind,
    val date: LocalDate,
    val daysUntil: Long,
) {
    /** Stable key so the same reminder is never sent twice. */
    val key: String get() = "${kind.name}:${subscription.id}:$date"
}

object Reminders {
    /**
     * Which reminders should fire today.
     * @param defaultDays reminder lead time from settings (0 = on the day).
     * Each subscription can override it via reminderDays (null = default, negative = off).
     */
    fun due(
        subs: List<SubscriptionEntity>,
        today: LocalDate,
        defaultDays: Int,
        alreadySent: Set<String>,
    ): List<Reminder> {
        val out = ArrayList<Reminder>()
        for (s in subs) {
            if (!s.isLive) continue
            val lead = s.reminderDays ?: defaultDays
            if (lead < 0) continue
            val until = ChronoUnit.DAYS.between(today, s.nextDueDate)
            val isTrialCharge = s.status == SubscriptionStatus.TRIAL && s.trialEndDate != null && s.nextDueDate.isBefore(s.trialEndDate)
            if (until in 0..lead.toLong() && !isTrialCharge) {
                out += Reminder(s, ReminderKind.RENEWAL, s.nextDueDate, until)
            }
            val trialEnd = s.trialEndDate
            if (s.status == SubscriptionStatus.TRIAL && trialEnd != null) {
                // Trials get at least 2 days' warning so there's time to cancel.
                val tUntil = ChronoUnit.DAYS.between(today, trialEnd)
                if (tUntil in 0..maxOf(lead, 2).toLong()) out += Reminder(s, ReminderKind.TRIAL_END, trialEnd, tUntil)
            }
        }
        return out.filter { it.key !in alreadySent }
    }
}
