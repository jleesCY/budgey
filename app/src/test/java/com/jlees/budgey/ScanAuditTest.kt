package com.jlees.budgey

import com.jlees.budgey.scan.ReceiptItems
import com.jlees.budgey.scan.ReceiptParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/** Receipt-reading fixes from the October 2026 audit. */
class ScanAuditTest {
    private val us = ReceiptParser(null, dayFirst = false)
    private val uk = ReceiptParser(null, dayFirst = true)
    private val today = LocalDate.of(2026, 10, 14)

    @Test fun wordsStartingWithAMonthAreNotDates() {
        val r = us.parse(listOf("WHOLE FOODS MARKET 10", "Decaf 2 3.50", "Mayo 2 1.99", "Date: 10/03/2026", "TOTAL 5.49"), today)
        assertEquals(LocalDate.of(2026, 10, 3), r.date)
    }

    @Test fun realMonthNamesStillWork() {
        assertEquals(LocalDate.of(2026, 3, 10), us.parse(listOf("Cafe", "Mar 10, 2026", "Total 4.00"), today).date)
        assertEquals(LocalDate.of(2026, 9, 5), us.parse(listOf("Cafe", "September 5 2026", "Total 4.00"), today).date)
        assertEquals(LocalDate.of(2026, 9, 5), us.parse(listOf("Cafe", "Sept 5, 2026", "Total 4.00"), today).date)
    }

    @Test fun dayMonthOrderFollowsThePhone() {
        val rows = listOf("Cafe", "03/10/2026", "Total 4.00")
        assertEquals(LocalDate.of(2026, 3, 10), us.parse(rows, today).date)
        assertEquals(LocalDate.of(2026, 10, 3), uk.parse(rows, today).date)
        // Only one reading is a real date: that one, whatever the phone says.
        assertEquals(LocalDate.of(2026, 9, 25), uk.parse(listOf("Cafe", "09/25/2026", "Total 4.00"), today).date)
        // Both are real, but the usual one is in the future: the other one.
        assertEquals(LocalDate.of(2026, 10, 12), us.parse(listOf("Cafe", "12/10/2026", "Total 4.00"), today).date)
    }

    @Test fun localeDetection() {
        assertFalse(ReceiptParser.localeIsDayFirst(Locale.US))
        assertTrue(ReceiptParser.localeIsDayFirst(Locale.UK))
        assertTrue(ReceiptParser.localeIsDayFirst(Locale.GERMANY))
    }

    @Test fun yearlessDateAcrossNewYear() {
        val jan2 = LocalDate.of(2027, 1, 2)
        assertEquals(LocalDate.of(2026, 12, 28), us.parse(listOf("Cafe", "Dec 28", "Total 4.00"), jan2).date)
    }

    @Test fun dashSeparatorsAreNotMinusSigns() {
        val r = us.parse(listOf("Cafe", "Latte 4.50", "TOTAL – 23.45"), today)
        assertEquals(2345L, r.amountCents)
        assertFalse(r.isRefund)
    }

    @Test fun groupedThousands() {
        assertEquals(123456L, us.parse(listOf("Shop", "TOTAL 1.234,56"), today).amountCents)
        assertEquals(123456L, us.parse(listOf("Shop", "TOTAL 1 234,56"), today).amountCents)
        assertEquals(123456L, us.parse(listOf("Shop", "TOTAL $1,234.56"), today).amountCents)
        // A run of digits far too long to be money isn't read as one.
        assertNull(us.parse(listOf("Shop", "Card 123456789012345678.90"), today).amountCents)
    }

    @Test fun keywordsMatchWholeWords() {
        // "Regular coffee" and "Premium roast" don't make a café receipt a gas pump.
        assertEquals(ReceiptParser.DocType.RECEIPT, us.docType("regular coffee 3.00\npremium roast 4.00\ntotal 7.00"))
        assertEquals(ReceiptParser.DocType.FUEL_PUMP, us.docType("unleaded\n10.2 gallons\nprice/gal 3.459\nsale 35.28"))
        assertTrue(ReceiptParser.hasWord("apr 12.9%", "apr"))
        assertFalse(ReceiptParser.hasWord("april 5", "apr"))
        assertFalse(ReceiptParser.hasWord("legal notice", "gal"))
        assertTrue(ReceiptParser.hasWord("3.459/gal", "/gal"))
    }

    @Test fun balanceDueIsATotal() {
        assertEquals(4200L, us.parse(listOf("Clinic", "Visit 60.00", "Insurance -18.00", "Balance due 42.00"), today).amountCents)
    }

    @Test fun refundsAreRecognised() {
        assertTrue(us.parse(listOf("Store", "REFUND", "Jacket -49.99", "TOTAL REFUND 49.99"), today).isRefund)
        assertTrue(us.parse(listOf("Store", "Returned item", "Total 12.34-"), today).isRefund)
        // A returns policy at the bottom of a normal receipt isn't a refund.
        assertFalse(us.parse(listOf("Store", "Shirt 20.00", "Total 20.00", "Returns accepted within 30 days"), today).isRefund)
    }

    // ---------------------------------------------------------------- check splitter

    @Test fun suggestedTipsAreNotTheTip() {
        val r = ReceiptItems.fromRows(
            listOf("Burger 12.00", "Fries 4.00", "Subtotal 16.00", "Tax 1.40", "Total 17.40", "18% tip 3.13", "20% tip 3.48"),
        )
        assertNull(r.tipCents)
        assertEquals(1740L, r.totalCents)
        val r2 = ReceiptItems.fromRows(listOf("Pizza 20.00", "Suggested Gratuity: 15% $3.00 18% $3.60 20% $4.00", "Total 20.00"))
        assertNull(r2.tipCents)
    }

    @Test fun aRealTipIsKept() {
        val r = ReceiptItems.fromRows(listOf("Burger 12.00", "Subtotal 12.00", "Tip 2.40", "Total 14.40"))
        assertEquals(240L, r.tipCents)
    }

    @Test fun dashesInItemRowsAreNotDiscounts() {
        val r = ReceiptItems.fromRows(listOf("Burger - 12.99", "Coupon -2.00", "Fries 3.00-", "Total 13.99"))
        assertEquals(listOf(1299L, -200L, -300L), r.items.map { it.priceCents })
    }

    @Test fun itemsWithGroupedThousands() {
        val r = ReceiptItems.fromRows(listOf("Wine 1,250.00", "Total 1,250.00"))
        assertEquals(125000L, r.items.single().priceCents)
    }

    @Test fun cutOffAiAnswerKeepsTheWholeItems() {
        val reply = """{"items":[{"name":"Burger","qty":2,"price":24.00},{"name":"Fries","qty":1,"price":4.50},{"name":"Sha"""
        val r = ReceiptItems.fromAiReply(reply)!!
        assertEquals(listOf("Burger", "Fries"), r.items.map { it.name })
        assertEquals(2, r.items[0].qty)
    }
}
