package com.jlees.budgey

import com.jlees.budgey.data.db.SubscriptionEntity
import com.jlees.budgey.data.db.SubscriptionStatus
import com.jlees.budgey.domain.BillingCycle
import com.jlees.budgey.domain.CycleUnit
import com.jlees.budgey.domain.DateRange
import com.jlees.budgey.domain.ReminderKind
import com.jlees.budgey.domain.Reminders
import com.jlees.budgey.domain.Renewals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RenewalsTest {
    private val today = LocalDate.of(2026, 10, 1)

    private fun sub(
        anchor: LocalDate,
        next: LocalDate,
        unit: CycleUnit = CycleUnit.MONTH,
        status: SubscriptionStatus = SubscriptionStatus.ACTIVE,
        reminderDays: Int? = null,
        trialEnd: LocalDate? = null,
    ) = SubscriptionEntity(
        id = "s", name = "Netflix", amountCents = 1549, cycleUnit = unit, anchorDate = anchor,
        nextDueDate = next, status = status, reminderDays = reminderDays, trialEndDate = trialEnd,
    )

    @Test fun autoNextPaymentFromStartDate() {
        // Started Feb 20 2026, monthly, today Oct 1 2026 -> next payment Oct 20 2026.
        assertEquals(LocalDate.of(2026, 10, 20), BillingCycle.MONTHLY.nextOnOrAfter(LocalDate.of(2026, 2, 20), today))
        // Yearly started Mar 3 2025 -> Mar 3 2027.
        assertEquals(LocalDate.of(2027, 3, 3), BillingCycle.YEARLY.nextOnOrAfter(LocalDate.of(2025, 3, 3), today))
    }

    @Test fun upcomingOccurrencesInMonth() {
        val s = sub(LocalDate.of(2026, 9, 29), LocalDate.of(2026, 10, 20), CycleUnit.WEEK)
        val oct = DateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))
        val dates = Renewals.upcomingIn(s, oct, today)
        assertTrue(dates.first() == LocalDate.of(2026, 10, 20))
        assertEquals(listOf(20, 27), dates.map { it.dayOfMonth }) // weekly from the 20th
        // Past months show nothing projected.
        assertEquals(0, Renewals.upcomingIn(s, DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)), today).size)
        // Paused subscriptions never project.
        assertEquals(0, Renewals.upcomingIn(s.copy(status = SubscriptionStatus.PAUSED), oct, today).size)
    }

    @Test fun reminderWindowAndDedup() {
        val s = sub(LocalDate.of(2026, 2, 2), LocalDate.of(2026, 10, 2))
        val due = Reminders.due(listOf(s), today, defaultDays = 1, alreadySent = emptySet())
        assertEquals(1, due.size)
        assertEquals(ReminderKind.RENEWAL, due.single().kind)
        assertEquals(1L, due.single().daysUntil)
        // Already sent -> nothing.
        assertEquals(0, Reminders.due(listOf(s), today, 1, setOf(due.single().key)).size)
        // Too far out with default 1 day; per-subscription 3-day override catches it.
        val later = s.copy(nextDueDate = LocalDate.of(2026, 10, 4))
        assertEquals(0, Reminders.due(listOf(later), today, 1, emptySet()).size)
        assertEquals(1, Reminders.due(listOf(later.copy(reminderDays = 3)), today, 1, emptySet()).size)
        // Turned off for this subscription.
        assertEquals(0, Reminders.due(listOf(s.copy(reminderDays = -1)), today, 1, emptySet()).size)
    }

    @Test fun trialEndingGetsTwoDaysWarning() {
        val t = sub(today, LocalDate.of(2026, 10, 3), status = SubscriptionStatus.TRIAL, trialEnd = LocalDate.of(2026, 10, 3))
        val due = Reminders.due(listOf(t), today, defaultDays = 0, alreadySent = emptySet())
        assertEquals(listOf(ReminderKind.TRIAL_END), due.map { it.kind })
    }

    @Test fun backfillEveryPastBillingDate() {
        // Started Feb 20 2026, monthly; today Oct 1 -> Feb 20 … Sep 20 = 8 payments.
        val s = sub(LocalDate.of(2026, 2, 20), LocalDate.of(2026, 10, 20))
        val past = Renewals.billingDatesThrough(s, today)
        assertEquals(8, past.size)
        assertEquals(LocalDate.of(2026, 2, 20), past.first())
        assertEquals(LocalDate.of(2026, 9, 20), past.last())
        // Calendar: September shows the 20th as a past billing date.
        val sep = DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))
        assertEquals(listOf(LocalDate.of(2026, 9, 20)), Renewals.pastIn(s, emptyList(), sep, today))
    }

    @Test fun freeTrialDatesAreNotBackfilled() {
        // 1-month free trial ending Mar 20: Feb 20 is free, Mar 20 onward is paid.
        val s = sub(LocalDate.of(2026, 2, 20), LocalDate.of(2026, 10, 20), trialEnd = LocalDate.of(2026, 3, 20))
        val past = Renewals.billingDatesThrough(s, today)
        assertEquals(LocalDate.of(2026, 3, 20), past.first())
        assertEquals(7, past.size)
    }

    @Test fun historyPeriodsUseTheirOwnPrice() {
        // Student price $7.49 Feb 20 - Jun 19, then full price $14.99 from Jun 20.
        val current = sub(LocalDate.of(2026, 6, 20), LocalDate.of(2026, 10, 20)).copy(amountCents = 1499)
        val student = com.jlees.budgey.data.db.SubscriptionPeriodEntity(
            subscriptionId = current.id, startDate = LocalDate.of(2026, 2, 20), endDate = LocalDate.of(2026, 6, 19), amountCents = 749,
        )
        val paid = Renewals.paidDates(current, listOf(student), today)
        assertEquals(8, paid.size)
        assertEquals(749L, paid[LocalDate.of(2026, 5, 20)])
        assertEquals(1499L, paid[LocalDate.of(2026, 9, 20)])
    }

    @Test fun cancelledThenResumedLeavesAGap() {
        // Ran Feb 20 - Oct 1 (cancelled), resumes Dec 15: old run is a period, current starts Dec 15.
        val old = com.jlees.budgey.data.db.SubscriptionPeriodEntity(
            subscriptionId = "s", startDate = LocalDate.of(2026, 2, 20), endDate = LocalDate.of(2026, 10, 1), amountCents = 1549,
        )
        val resumed = sub(LocalDate.of(2026, 12, 15), LocalDate.of(2026, 12, 15))
        val through = LocalDate.of(2027, 1, 31)
        val dates = Renewals.paidDates(resumed, listOf(old), through).keys.toList()
        assertTrue(LocalDate.of(2026, 9, 20) in dates)
        assertTrue(LocalDate.of(2026, 10, 20) !in dates && LocalDate.of(2026, 11, 20) !in dates)
        assertEquals(listOf(LocalDate.of(2026, 12, 15), LocalDate.of(2027, 1, 15)), dates.filter { it.isAfter(LocalDate.of(2026, 10, 1)) })
        // While cancelled with an end date, billing stops there.
        val cancelled = sub(LocalDate.of(2026, 2, 20), LocalDate.of(2026, 10, 20), status = SubscriptionStatus.CANCELLED)
            .copy(endDate = LocalDate.of(2026, 7, 31))
        assertEquals(LocalDate.of(2026, 7, 20), Renewals.paidDates(cancelled, emptyList(), today).lastKey())
    }
}
