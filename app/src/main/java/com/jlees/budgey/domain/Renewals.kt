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
     * The day paid billing starts, given a start date and a free trial. A trial that ends after the
     * start date moves the first charge to the trial's end, and the billing rhythm counts from there
     * (start Sep 1 with a 3-month trial ending Dec 1 → charged Dec 1, Jan 1, …; nothing in Oct/Nov).
     * null = nothing will be charged yet (on a free trial with no end date).
     *
     * A trial end date only counts while the subscription is still marked as a trial, or once that
     * date has passed (the trial happened). A future trial end left on an Active subscription is stale.
     */
    fun paidFrom(anchor: LocalDate, trialEnd: LocalDate?, status: SubscriptionStatus, today: LocalDate): LocalDate? {
        if (status == SubscriptionStatus.TRIAL && trialEnd == null) return null
        val trialCounts = trialEnd != null && (status == SubscriptionStatus.TRIAL || !trialEnd.isAfter(today))
        return if (trialCounts && trialEnd!!.isAfter(anchor)) trialEnd else anchor
    }

    fun paidFrom(sub: SubscriptionEntity, today: LocalDate): LocalDate? = paidFrom(sub.anchorDate, sub.trialEndDate, sub.status, today)

    /** The next charge on or after [today] (what "Next payment" shows). Null while an open-ended trial runs. */
    fun nextPaidDate(anchor: LocalDate, cycle: BillingCycle, trialEnd: LocalDate?, status: SubscriptionStatus, today: LocalDate): LocalDate? {
        val start = paidFrom(anchor, trialEnd, status, today) ?: return null
        return cycle.nextOnOrAfter(start, today)
    }

    /** Is [sub] a free trial that has reached its end date but is still marked as a trial (needs a look)? */
    fun trialEnded(sub: SubscriptionEntity, today: LocalDate): Boolean =
        sub.status == SubscriptionStatus.TRIAL && sub.trialEndDate != null && !sub.trialEndDate.isAfter(today)

    /**
     * Billing dates of [sub] that fall inside [range], from today onward only (past charges
     * are represented by the purchases that were actually logged).
     */
    fun upcomingIn(sub: SubscriptionEntity, range: DateRange, today: LocalDate): List<LocalDate> {
        if (!sub.isLive) return emptyList()
        // Free-trial days are never renewals: billing starts when the trial ends.
        val start = paidFrom(sub, today) ?: return emptyList()
        val from = maxOf(range.start, today, sub.nextDueDate, start)
        if (from.isAfter(range.end)) return emptyList()
        val out = ArrayList<LocalDate>()
        var d = sub.cycle.nextOnOrAfter(start, from)
        // nextDueDate may have been set by hand off the rhythm — include it (unless it's inside the trial).
        if (sub.nextDueDate in range && !sub.nextDueDate.isBefore(today) && !sub.nextDueDate.isBefore(start)) out += sub.nextDueDate
        var guard = 0
        while (!d.isAfter(range.end) && guard++ < 400) {
            if (d !in out) out += d
            d = sub.cycle.nextOnOrAfter(start, d.plusDays(1))
        }
        return out.sorted()
    }

    /** Dates during the current run's free trial (no charge). Earlier price-history periods never count. */
    fun isFreeTrialDate(sub: SubscriptionEntity, date: LocalDate, today: LocalDate = LocalDate.now()): Boolean {
        if (date.isBefore(sub.anchorDate)) return false
        val start = paidFrom(sub, today) ?: return true
        return date.isBefore(start)
    }

    /**
     * Every paid billing date from the start (anchor) date through [end], inclusive, oldest first.
     * Used to backfill payment history when a subscription's start date is in the past.
     */
    fun billingDatesThrough(sub: SubscriptionEntity, end: LocalDate, max: Int = 3000, today: LocalDate = end): List<LocalDate> {
        val start = paidFrom(sub, today) ?: return emptyList()
        val out = ArrayList<LocalDate>()
        var n = 0L
        while (out.size < max && n < max * 2) {
            val d = sub.cycle.nth(start, n++)
            if (d.isAfter(end)) break
            out += d
        }
        return out
    }

    /**
     * The stretches of a subscription's life that were billed: every saved history period plus the
     * current period (from anchorDate; open-ended while live, ending at endDate when paused/cancelled).
     * A paused/cancelled subscription without an endDate (older data) has no known current stretch.
     */
    fun segments(sub: SubscriptionEntity, periods: List<SubscriptionPeriodEntity>, today: LocalDate = LocalDate.now()): List<BillingSegment> {
        val past = periods.filter { it.subscriptionId == sub.id }
            .map { BillingSegment(it.startDate, it.endDate, it.amountCents, it.cycle, it.label) }
        // The current run is billed from the end of its free trial, if it had one.
        val start = paidFrom(sub, today)
        val current = when {
            start == null -> null
            sub.isLive -> BillingSegment(start, null, sub.amountCents, sub.cycle, "")
            sub.endDate != null && !sub.endDate.isBefore(start) -> BillingSegment(start, sub.endDate, sub.amountCents, sub.cycle, "")
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
                out[d] = seg.amountCents
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

enum class ReminderKind { RENEWAL, TRIAL_END, TRIAL_ENDED }

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
            // While it's still marked as a trial, the trial reminders below cover the first charge.
            val isTrialCharge = s.status == SubscriptionStatus.TRIAL && (s.trialEndDate == null || !s.nextDueDate.isAfter(s.trialEndDate))
            if (until in 0..lead.toLong() && !isTrialCharge) {
                out += Reminder(s, ReminderKind.RENEWAL, s.nextDueDate, until)
            }
            val trialEnd = s.trialEndDate
            if (s.status == SubscriptionStatus.TRIAL && trialEnd != null) {
                // Trials get at least 2 days' warning so there's time to cancel.
                val tUntil = ChronoUnit.DAYS.between(today, trialEnd)
                if (tUntil in 1..maxOf(lead, 2).toLong()) out += Reminder(s, ReminderKind.TRIAL_END, trialEnd, tUntil)
                // The trial is over (ends today or already ended) and it's still marked as a trial: you're
                // being charged now. Sent once; trials that ended over a month ago are left alone.
                if (tUntil in -30L..0L) out += Reminder(s, ReminderKind.TRIAL_ENDED, trialEnd, tUntil)
            }
        }
        return out.filter { it.key !in alreadySent }
    }
}
