package com.jlees.budgey

import com.jlees.budgey.domain.Check
import com.jlees.budgey.domain.CheckSplitter
import com.jlees.budgey.domain.CustomKind
import com.jlees.budgey.domain.FxTable
import com.jlees.budgey.domain.SplitItem
import com.jlees.budgey.domain.SplitMode
import com.jlees.budgey.domain.SplitPerson
import com.jlees.budgey.domain.TipMath
import com.jlees.budgey.domain.TipSpec
import com.jlees.budgey.scan.ReceiptItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class ToolsMathTest {
    private val a = SplitPerson("a", "Ann")
    private val b = SplitPerson("b", "Ben")
    private val c = SplitPerson("c", "Cat")

    @Test fun evenSplitRoundsUpAndTipOnlyForTippers() {
        // $100 subtotal + $10 tax, 20% tip ($20) — Cat doesn't share the tip.
        val check = Check(subtotalOverride = 10_000, taxCents = 1_000, tip = TipSpec(20.0), people = listOf(a, b, c.copy(sharesTip = false)))
        val r = CheckSplitter.split(check)
        // 110 / 3 = 36.666… → 36.67; tip 20 / 2 = 10.
        assertEquals(listOf(4_667L, 4_667L, 3_667L), r.shares.map { it.totalCents })
        assertEquals(13_000L, r.billCents)
        assertEquals(1L, r.roundingExtraCents)
    }

    @Test fun nobodyTippingMeansEveryoneTips() {
        val check = Check(subtotalOverride = 3_000, tip = TipSpec(amountCents = 600), people = listOf(a.copy(sharesTip = false), b.copy(sharesTip = false)))
        val r = CheckSplitter.split(check)
        assertEquals(listOf(1_800L, 1_800L), r.shares.map { it.totalCents })
        assertTrue(r.notes.isNotEmpty())
    }

    @Test fun byItemSharedItemsSplitEvenlyAndTaxFollowsOrders() {
        val items = listOf(
            SplitItem("1", "Burger", 1_200, people = setOf("a")),
            SplitItem("2", "Salad", 900, people = setOf("b")),
            SplitItem("3", "Nachos", 1_000, people = setOf("a", "b", "c")), // 3.333… each
        )
        val check = Check(items = items, taxCents = 310, tip = TipSpec(amountCents = 0), people = listOf(a, b, c), mode = SplitMode.ITEMS)
        val r = CheckSplitter.split(check)
        val ann = r.shares.first { it.person.id == "a" }
        // Food 12 + 3.3333 = 15.3333; tax 3.10 × 15.3333/31 = 1.5333 → 16.8667 → rounds up to 16.87.
        assertEquals(1_687L, ann.totalCents)
        val cat = r.shares.first { it.person.id == "c" }
        assertEquals(367L, cat.totalCents) // 3.3333 + 0.3333 = 3.6667 → 3.67
        assertTrue(r.collectedCents >= r.billCents)
    }

    @Test fun byItemUnassignedSharedByEveryone() {
        val items = listOf(SplitItem("1", "Fries", 600))
        val r = CheckSplitter.split(Check(items = items, tip = TipSpec(amountCents = 0), people = listOf(a, b), mode = SplitMode.ITEMS))
        assertEquals(listOf(300L, 300L), r.shares.map { it.totalCents })
        assertTrue(r.notes.any { "assigned" in it })
    }

    @Test fun customPercentAndAmount() {
        val people = listOf(a.copy(custom = "60"), b.copy(custom = "40%"))
        val r = CheckSplitter.split(Check(subtotalOverride = 5_000, tip = TipSpec(amountCents = 1_000), people = people, mode = SplitMode.CUSTOM, customKind = CustomKind.PERCENT))
        assertEquals(listOf(3_500L, 2_500L), r.shares.map { it.totalCents })
        val amounts = listOf(a.copy(custom = "20"), b.copy(custom = "$10.00"))
        val r2 = CheckSplitter.split(Check(subtotalOverride = 5_000, tip = TipSpec(amountCents = 0), people = amounts, mode = SplitMode.CUSTOM))
        assertEquals(2_000L, r2.unassignedCents)
    }

    @Test fun extractsItemsAndTotals() {
        val rows = listOf(
            "JOE'S DINER", "123 Main St", "Table 4  Server: Amy",
            "2 x Cheeseburger  25.98", "Fries 4.50 T", "Coke", "2.99", "Coupon -1.00",
            "Subtotal  32.47", "Sales Tax  2.84", "Total  35.31", "VISA  35.31", "Change 0.00",
        )
        val r = ReceiptItems.fromRows(rows)
        assertEquals(listOf("Cheeseburger", "Fries", "Coke", "Coupon"), r.items.map { it.name })
        assertEquals(2, r.items[0].qty)
        assertEquals(2_598L, r.items[0].priceCents)
        assertEquals(-100L, r.items[3].priceCents)
        assertEquals(3_247L, r.subtotalCents)
        assertEquals(284L, r.taxCents)
        assertEquals(3_531L, r.totalCents)
    }

    @Test fun parsesAiItems() {
        val reply = "```json\n{\"items\":[{\"name\":\"Pad Thai\",\"qty\":1,\"price\":14.5},{\"name\":\"Tea\",\"price\":\"3.25\"}],\"subtotal\":17.75,\"tax\":null,\"tip\":3.0,\"total\":20.75}\n```"
        val r = ReceiptItems.fromAiReply(reply)
        assertNotNull(r)
        assertEquals(listOf(1_450L, 325L), r!!.items.map { it.priceCents })
        assertEquals(300L, r.tipCents)
        assertEquals(null, r.taxCents)
    }

    @Test fun fxParsesBothShapesAndCrossRates() {
        val v1 = FxTable.parse("""{"amount":1.0,"base":"EUR","date":"2026-10-01","rates":{"USD":1.25,"GBP":0.8}}""")!!
        assertEquals(1.5625, v1.rate("GBP", "USD")!!, 1e-9)
        assertEquals(BigDecimal("12.50"), v1.convert(BigDecimal("10"), "EUR", "USD")!!.setScale(2))
        val v2 = FxTable.parse("""[{"date":"2026-09-30","base":"EUR","quote":"USD","rate":1.1298},{"date":"2026-10-01","base":"EUR","quote":"JPY","rate":178.49}]""")!!
        assertEquals("2026-10-01", v2.date)
        assertEquals(listOf("EUR", "JPY", "USD"), v2.currencies)
    }

    @Test fun builtInRatesParse() {
        // Unit tests run from the module folder.
        val f = listOf("src/main/assets/fx_rates.json", "app/src/main/assets/fx_rates.json").map { java.io.File(it) }.first { it.isFile }
        val t = FxTable.parse(f.readText())!!
        assertEquals("EUR", t.base)
        assertTrue(t.currencies.size > 100)
        assertTrue(t.rate("USD", "JPY")!! > 50)
    }

    @Test fun tipMath() {
        // Bill $100 before tax, $8.25 tax.
        assertEquals(10_000L, TipMath.base(10_000, 825, afterTax = false))
        assertEquals(10_825L, TipMath.base(10_000, 825, afterTax = true))
        assertEquals(2_000L, TipMath.tipFromPercent(10_000, 20.0))
        // 18% of $33.33 = $5.9994 → rounds UP to $6.00.
        assertEquals(600L, TipMath.tipFromPercent(3_333, 18.0))
        assertEquals(15.0, TipMath.percentFromTip(10_000, 1_500), 1e-9)
        assertEquals("18.5", TipMath.formatPercent(18.5))
        assertEquals("20", TipMath.formatPercent(20.0))
    }
}
