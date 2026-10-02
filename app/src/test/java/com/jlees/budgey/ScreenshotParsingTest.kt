package com.jlees.budgey

import com.jlees.budgey.icons.Brand
import com.jlees.budgey.icons.BrandMatcher
import com.jlees.budgey.scan.OcrLine
import com.jlees.budgey.scan.ReceiptParser
import com.jlees.budgey.scan.ScanKind
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** Banking-app screenshots, fuel pumps and kiosk screens — the cases plain receipt rules got wrong. */
class ScreenshotParsingTest {
    private fun b(id: String, name: String, payment: Boolean = false, vararg aliases: String) =
        Brand(id, name, 0xFF000000, null, false, aliases.toList(), id, payment)

    private val matcher = BrandMatcher(
        listOf(
            b("starbucks", "Starbucks", false, "sbux"),
            b("chase", "Chase", true, "jpmorgan chase"),
            b("capitalone", "Capital One", true, "capital one bank"),
            b("visa", "Visa", true),
            b("shell", "Shell"),
        )
    )
    private val parser = ReceiptParser(matcher)
    private val today = LocalDate.of(2026, 10, 1)

    @Test fun chaseAppTransactionDetails() {
        val r = parser.parseRows(
            listOf(
                OcrLine("9:41", 0.9f),
                OcrLine("Chase", 1.2f),
                OcrLine("Transaction details", 1.1f),
                OcrLine("STARBUCKS STORE 12345", 1.6f),
                OcrLine("-$6.45", 2.6f),
                OcrLine("Pending"),
                OcrLine("Sep 28, 2026"),
                OcrLine("Card ending in 1234"),
                OcrLine("Category"),
                OcrLine("Food & Drink"),
                OcrLine("Available balance"),
                OcrLine("$2,345.67"),
            ), today
        )
        assertEquals(ScanKind.PURCHASE, r.kind)
        assertEquals("Starbucks", r.merchant)
        assertEquals(645L, r.amountCents)
        assertEquals(LocalDate.of(2026, 9, 28), r.date)
    }

    @Test fun creditCardAppWithUnknownMerchant() {
        val r = parser.parseRows(
            listOf(
                OcrLine("Capital One", 1.1f),
                OcrLine("Transaction", 1.1f),
                OcrLine("BLUE DOOR CAFE", 1.5f),
                OcrLine("$23.68", 2.2f),
                OcrLine("Posted · Sep 27, 2026"),
                OcrLine("Visa Credit Card ...1234"),
                OcrLine("Rewards earned  $0.47"),
                OcrLine("Current balance  $1,204.11"),
            ), today
        )
        assertEquals("Blue Door Cafe", r.merchant)
        assertEquals(2368L, r.amountCents)
        assertEquals(LocalDate.of(2026, 9, 27), r.date)
    }

    @Test fun merchantLabelRow() {
        val r = parser.parseRows(
            listOf(
                OcrLine("Activity"),
                OcrLine("$58.20", 2f),
                OcrLine("Merchant"),
                OcrLine("SQ *GREEN LEAF MARKET 0042 OH"),
                OcrLine("Date  09/29/2026"),
                OcrLine("Status  Posted"),
            ), today
        )
        assertEquals("Green Leaf Market", r.merchant)
        assertEquals(5820L, r.amountCents)
    }

    @Test fun gasPumpWithoutDecimalPoint() {
        val r = parser.parseRows(
            listOf(
                OcrLine("Shell"),
                OcrLine("REGULAR"),
                OcrLine("SALE  $", 1f),
                OcrLine("4567", 2.4f),
                OcrLine("GALLONS"),
                OcrLine("13.204", 2.4f),
                OcrLine("PRICE/GAL  3.459"),
            ), today
        )
        assertEquals("Shell", r.merchant)
        assertEquals(4567L, r.amountCents)
    }

    @Test fun gasPumpDecimalAmount() {
        val r = parser.parseRows(
            listOf(
                OcrLine("TOTAL SALE  $ 38.91"),
                OcrLine("GALLONS  11.250"),
                OcrLine("PRICE PER GALLON  3.459"),
            ), today
        )
        assertEquals(3891L, r.amountCents)
    }

    @Test fun kioskOrderSummary() {
        val r = parser.parseRows(
            listOf(
                OcrLine("Order Summary", 1.4f),
                OcrLine("Burrito Bowl  $10.75"),
                OcrLine("Chips & Guac  $4.95"),
                OcrLine("Subtotal  $15.70"),
                OcrLine("Tax  $1.26"),
                OcrLine("Order Total  $16.96", 1.3f),
                OcrLine("Tap, insert or swipe card"),
            ), today
        )
        assertEquals(1696L, r.amountCents)
    }

    @Test fun cleansBankStatementNames() {
        assertEquals("Blue Bottle Coffee", parser.cleanMerchantName("SQ *BLUE BOTTLE COFFEE 1234 CA"))
        assertEquals("Joe's Pizza", parser.cleanMerchantName("TST* JOE'S PIZZA #12"))
    }
}
