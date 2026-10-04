package com.jlees.budgey

import com.jlees.budgey.scan.ReceiptItems
import com.jlees.budgey.scan.ReceiptParser
import com.jlees.budgey.scan.ScanKind
import com.jlees.budgey.scan.ScanResult
import com.jlees.budgey.scan.SmartScanParser
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/** Fully discounted receipts ($0 totals) and rows that stand for several of the same item. */
class DiscountAndQuantityTest {
    private val parser = ReceiptParser(null)
    private val today = LocalDate.of(2026, 10, 3)

    // ---------------------------------------------------------------- $0 totals

    @Test fun fullyDiscountedReceiptCostsNothing() {
        val r = parser.parse(
            listOf("JOE'S CAFE", "Latte 5.25", "100% OFF COUPON -5.25", "SUBTOTAL 0.00", "TAX 0.00", "TOTAL 0.00", "VISA 0.00"),
            today,
        )
        assertEquals(0L, r.amountCents)
    }

    @Test fun compedItemTotalIsZero() {
        val r = parser.parse(listOf("Burger Barn", "Cheeseburger 12.99", "Manager comp (12.99)", "Total 0.00"), today)
        assertEquals(0L, r.amountCents)
    }

    @Test fun balanceZeroAfterPaymentIsNotTheTotal() {
        // No discount anywhere: "Balance 0.00" / "Change 0.00" after paying never wins.
        val r = parser.parse(listOf("Burger Barn", "Cheeseburger 12.99", "Total 12.99", "Cash 20.00", "Change 7.01", "Balance 0.00"), today)
        assertEquals(1299L, r.amountCents)
    }

    @Test fun discountedButNotFreeStillUsesTheTotal() {
        val r = parser.parse(listOf("Shop", "Shirt 20.00", "Discount -5.00", "Total 15.00", "Balance due 0.00"), today)
        assertEquals(1500L, r.amountCents)
    }

    @Test fun zeroFromTheTextReaderBeatsTheAiItemPrice() {
        val ocr = ScanResult(
            kind = ScanKind.PURCHASE, merchant = "Cafe", brand = null, amountCents = 0, date = null, cycle = null,
            nextBillingDate = null, trialEndDate = null, amountCandidates = listOf(0L, 525L), subscriptionScore = 0, rawText = "",
        )
        val ai = SmartScanParser.parse("""{"merchant":"Cafe","total":5.25}""", today)
        assertEquals(0L, SmartScanParser.merge(ocr, ai, null).amountCents)
    }

    @Test fun aiCanAnswerZero() {
        assertEquals(0L, SmartScanParser.parse("""{"merchant":"Cafe","total":0.00}""", today)?.totalCents)
        assertEquals(null, SmartScanParser.parse("""{"merchant":"<business name>","total":null}""", today))
    }

    // ---------------------------------------------------------------- quantities

    private fun only(rows: List<String>) = ReceiptItems.fromRows(rows).items

    @Test fun leadingCountWithWholeDollarPrice() {
        val items = only(listOf("5 burgers ...... $50", "Total $50"))
        assertEquals(1, items.size)
        assertEquals(5, items[0].qty)
        assertEquals(5000L, items[0].priceCents)
        assertEquals("burgers", items[0].name)
    }

    @Test fun manyCountFormats() {
        val rows = listOf(
            "5x Burger 50.00",
            "Fries x3 9.00",
            "Soda (4) 10.00",
            "Wings 2 @ 7.50 15.00",
            "Taco 3 4.00 12.00",
            "2 Margarita 24.00",
            "Salad 8.50",
            "12 oz Latte 4.75",
            "Subtotal 129.25",
        )
        val q = only(rows).associate { it.name to it.qty }
        assertEquals(5, q["Burger"])
        assertEquals(3, q["Fries"])
        assertEquals(4, q["Soda"])
        assertEquals(2, q["Wings"])
        assertEquals(3, q["Taco"])
        assertEquals(2, q["Margarita"])
        assertEquals(1, q["Salad"])
        assertEquals(1, q["12 oz Latte"]) // a size, not a count
    }

    @Test fun multiUnitRowsSplitIntoOnePerPerson() {
        val r = ReceiptItems.fromRows(listOf("5 Burgers 50.00", "3 Beers 20.00", "Salad 8.50")).splitUnits()
        assertEquals(9, r.items.size)
        assertEquals(List(5) { 1000L }, r.items.filter { it.name == "Burgers" }.map { it.priceCents })
        // 20.00 / 3: the extra cent goes to the first unit, and the total is unchanged.
        assertEquals(listOf(667L, 667L, 666L), r.items.filter { it.name == "Beers" }.map { it.priceCents })
        assertEquals(7850L, r.items.sumOf { it.priceCents })
    }

    @Test fun aiCountLeftInTheName() {
        val r = ReceiptItems.fromAiReply("""{"items":[{"name":"5 Burgers","qty":1,"price":50.00}],"subtotal":50.00}""")!!
        assertEquals(5, r.items[0].qty)
        assertEquals("Burgers", r.items[0].name)
    }

    @Test fun aiUnitPriceIsFixedWhenTheSubtotalSaysSo() {
        val r = ReceiptItems.fromAiReply(
            """{"items":[{"name":"Burger","qty":5,"price":10.00},{"name":"Salad","qty":1,"price":8.50}],"subtotal":58.50}""",
        )!!
        assertEquals(5000L, r.items[0].priceCents)
        assertEquals(850L, r.items[1].priceCents)
    }

    @Test fun aiPlaceholdersAreIgnored() {
        val r = ReceiptItems.fromAiReply("""{"items":[{"name":"<item name>","qty":1,"price":1}],"subtotal":null,"total":12.00}""")!!
        assertEquals(0, r.items.size)
    }
}
