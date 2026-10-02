package com.jlees.budgey

import com.jlees.budgey.domain.BillingCycle
import com.jlees.budgey.domain.BudgetPeriod
import com.jlees.budgey.domain.CycleUnit
import com.jlees.budgey.domain.Money
import com.jlees.budgey.icons.Brand
import com.jlees.budgey.icons.BrandMatcher
import com.jlees.budgey.scan.ReceiptParser
import com.jlees.budgey.scan.ScanKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ParserAndDomainTest {
    private fun b(id: String, name: String, sub: Boolean = false, vararg aliases: String) =
        Brand(id, name, 0xFF000000, null, sub, aliases.toList(), id)

    private val matcher = BrandMatcher(
        listOf(
            b("amazon", "Amazon", false, "amzn", "amazon mktplace"),
            b("amazonprime", "Amazon Prime", true, "prime video"),
            b("netflix", "Netflix", true),
            b("starbucks", "Starbucks", false, "sbux"),
            b("7eleven", "7-Eleven", false, "seven eleven"),
            b("traderjoes", "Trader Joe's", false, "trader joe"),
            b("att", "AT&T", true, "att"),
            b("max", "Max", true, "hbo max"),
        )
    )
    private val today = LocalDate.of(2026, 10, 1)
    private val parser = ReceiptParser(matcher)

    // ---------- Money ----------
    @Test fun moneyParse() {
        assertEquals(123456L, Money.parse("$1,234.56"))
        assertEquals(1250L, Money.parse("12,50"))
        assertEquals(1200L, Money.parse("12"))
        assertEquals(-500L, Money.parse("(5.00)"))
        assertNull(Money.parse("abc"))
    }

    // ---------- Periods ----------
    @Test fun billingCycleKeepsMonthEnd() {
        val c = BillingCycle(CycleUnit.MONTH, 1)
        val anchor = LocalDate.of(2026, 1, 31)
        assertEquals(LocalDate.of(2026, 2, 28), c.nextOnOrAfter(anchor, LocalDate.of(2026, 2, 1)))
        assertEquals(LocalDate.of(2026, 3, 31), c.nextOnOrAfter(anchor, LocalDate.of(2026, 3, 1)))
        assertEquals(anchor, c.nextOnOrAfter(anchor, LocalDate.of(2026, 1, 1)))
    }

    @Test fun billingCycleOldAnchorIsFast() {
        val c = BillingCycle(CycleUnit.WEEK, 2)
        val next = c.nextOnOrAfter(LocalDate.of(2001, 1, 1), today)
        assertTrue(!next.isBefore(today) && next.isBefore(today.plusDays(14)))
    }

    @Test fun monthlyCost() {
        assertEquals(1000L, BillingCycle.YEARLY.monthlyCost(12000))
        assertEquals(999L, BillingCycle.MONTHLY.monthlyCost(999))
    }

    @Test fun budgetPeriods() {
        val r = BudgetPeriod.QUARTERLY.rangeContaining(LocalDate.of(2026, 8, 15))
        assertEquals(LocalDate.of(2026, 7, 1), r.start)
        assertEquals(LocalDate.of(2026, 9, 30), r.end)
    }

    // ---------- Brands ----------
    @Test fun brandMatching() {
        assertEquals("amazon", matcher.match("AMZN Mktp US*2K4")?.id)
        assertEquals("amazonprime", matcher.match("Amazon Prime")?.id)
        assertEquals("starbucks", matcher.match("SQ *STARBUCKS #1234")?.id)
        assertEquals("7eleven", matcher.match("7-ELEVEN 3341")?.id)
        assertEquals("traderjoes", matcher.match("TRADER JOE'S #552")?.id)
        assertEquals("att", matcher.match("AT&T")?.id)
        assertEquals("max", matcher.match("max")?.id)
        assertNull(matcher.match("Joe's Diner"))
        assertNull(matcher.match("Maxine's Bakery"))
    }

    // ---------- Receipt parsing ----------
    @Test fun restaurantReceipt() {
        val r = parser.parse(
            listOf(
                "THE BLUE DOOR CAFE",
                "123 Main St",
                "Server: Kim   Table 4",
                "09/14/2026  7:42 PM",
                "Burger  14.50",
                "Fries  4.00",
                "Subtotal  18.50",
                "Tax  1.48",
                "Tip  3.70",
                "TOTAL  23.68",
                "VISA  23.68",
            ), today
        )
        assertEquals(ScanKind.PURCHASE, r.kind)
        assertEquals("The Blue Door Cafe", r.merchant)
        assertEquals(2368L, r.amountCents)
        assertEquals(LocalDate.of(2026, 9, 14), r.date)
    }

    @Test fun groceryWithCashAndChange() {
        val r = parser.parse(
            listOf(
                "TRADER JOE'S",
                "Store #552",
                "BANANAS 1.29",
                "MILK 3.49",
                "SUBTOTAL 4.78",
                "TOTAL 4.78",
                "CASH 20.00",
                "CHANGE 15.22",
                "2026-09-30 10:15",
            ), today
        )
        assertEquals("Trader Joe's", r.merchant)
        assertEquals(478L, r.amountCents)
        assertEquals(LocalDate.of(2026, 9, 30), r.date)
    }

    @Test fun subscriptionEmail() {
        val r = parser.parse(
            listOf(
                "From: Netflix <info@account.netflix.com>",
                "Your membership has been renewed",
                "Premium plan  $22.99/month",
                "Billed on September 12, 2026",
                "Next billing date: October 12, 2026",
                "Manage your subscription in Account settings",
            ), today
        )
        assertEquals(ScanKind.SUBSCRIPTION, r.kind)
        assertEquals("Netflix", r.merchant)
        assertEquals(2299L, r.amountCents)
        assertEquals(BillingCycle.MONTHLY, r.cycle)
        assertEquals(LocalDate.of(2026, 10, 12), r.nextBillingDate)
        assertEquals(LocalDate.of(2026, 9, 12), r.date)
    }

    @Test fun yearlySubscriptionScreenshot() {
        val r = parser.parse(
            listOf(
                "Subscriptions",
                "Cloud Storage Pro",
                "Annual plan",
                "$99.99/year",
                "Renews Mar 3",
                "Cancel subscription",
            ), today
        )
        assertEquals(ScanKind.SUBSCRIPTION, r.kind)
        assertEquals(9999L, r.amountCents)
        assertEquals(BillingCycle.YEARLY, r.cycle)
        assertEquals(LocalDate.of(2027, 3, 3), r.nextBillingDate)
    }

    @Test fun mobileOrderScreenshot() {
        val r = parser.parse(
            listOf(
                "Order details",
                "Starbucks",
                "Placed on Sep 28, 2026",
                "Caramel Macchiato  5.95",
                "Subtotal  5.95",
                "Tax  0.42",
                "Total",
                "$6.37",
            ), today
        )
        assertEquals(ScanKind.PURCHASE, r.kind)
        assertEquals("Starbucks", r.merchant)
        assertEquals(637L, r.amountCents)
        assertEquals(LocalDate.of(2026, 9, 28), r.date)
    }
}
