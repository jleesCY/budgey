package com.jlees.budgey

import com.jlees.budgey.domain.BudgetPeriod
import com.jlees.budgey.data.db.CategoryEntity
import com.jlees.budgey.data.db.PurchaseEntity
import com.jlees.budgey.domain.BillingCycle
import com.jlees.budgey.domain.Budgets
import com.jlees.budgey.domain.CategoryTree
import com.jlees.budgey.domain.CycleUnit
import com.jlees.budgey.domain.Money
import com.jlees.budgey.domain.TipMath
import com.jlees.budgey.domain.TipSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/** Money parsing, rounding and data-shape fixes from the October 2026 audit. */
class AuditFixesTest {

    @Test fun moneyKeepsOldBehaviour() {
        assertEquals(123456L, Money.parse("$1,234.56"))
        assertEquals(1250L, Money.parse("12,50"))
        assertEquals(1200L, Money.parse("12"))
        assertEquals(-500L, Money.parse("(5.00)"))
        assertEquals(1250L, Money.parse("12.5"))
        assertEquals(1200L, Money.parse("12."))
        assertNull(Money.parse("abc"))
        assertNull(Money.parse(""))
        assertNull(Money.parse("."))
    }

    @Test fun moneyDecimalCommasAndGrouping() {
        assertEquals(450L, Money.parse("4,5"))          // was $45.00
        assertEquals(123456L, Money.parse("1.234,56"))  // was $1.23
        assertEquals(123456L, Money.parse("1 234,56"))
        assertEquals(123456L, Money.parse("1 234,56"))
        assertEquals(123456L, Money.parse("1'234.56"))
        assertEquals(123400L, Money.parse("1,234"))     // a thousands comma, not a decimal
        assertEquals(123456700L, Money.parse("1,234,567"))
        assertEquals(123456700L, Money.parse("1.234.567"))
        assertEquals(123456789L, Money.parse("1,234,567.89"))
    }

    @Test fun moneyNegatives() {
        assertEquals(-500L, Money.parse("-5"))
        assertEquals(-500L, Money.parse("−5"))
        assertEquals(-500L, Money.parse("$-5"))
        assertEquals(-1200L, Money.parse("12.00-"))
        assertEquals(-500L, Money.parse("($5.00)"))
    }

    @Test fun moneyOverflowIsRejected() {
        assertNull(Money.parse("123456789012345678901234567890"))
    }

    @Test fun compactRoundsInsteadOfTruncating() {
        assertEquals("$1,235", Money.formatCompact(123_499))
        assertEquals("$1,234", Money.formatCompact(123_449))
        assertEquals("$12.5k", Money.formatCompact(1_249_999))
        assertEquals("-$1,235", Money.formatCompact(-123_499))
        assertEquals("$5.25", Money.formatCompact(525))
    }

    @Test fun yearlyCostRoundsOnce() {
        assertEquals(9_999L, BillingCycle.YEARLY.yearlyCost(9_999))
        assertEquals(11_988L, BillingCycle.MONTHLY.yearlyCost(999))
        // $10/week: 52.1775 weeks a year.
        assertEquals(52_178L, BillingCycle(CycleUnit.WEEK, 1).yearlyCost(1_000))
    }

    @Test fun tipsAreNotACentHigh() {
        // BigDecimal(15.3) is 15.300000000000000710…, which used to round $10.00 × 15.3% up to $1.54.
        assertEquals(153L, TipSpec(percent = 15.3).cents(1_000))
        assertEquals(1_230L, TipMath.tipFromPercent(10_000, 12.3))
    }

    @Test fun categoryLoopsStayVisible() {
        val a = CategoryEntity(id = "a", name = "A", parentId = "b")
        val b = CategoryEntity(id = "b", name = "B", parentId = "a")
        val c = CategoryEntity(id = "c", name = "C", parentId = "a")
        val tree = CategoryTree(listOf(a, b, c))
        val shown = tree.flattened().map { it.first.id }.toSet()
        assertEquals(setOf("a", "b", "c"), shown)
        assertEquals(listOf("a", "c"), tree.path("c").map { it.id })
        assertTrue(tree.roots.map { it.id }.containsAll(listOf("a", "b")))
    }

    @Test fun budgetsAddUpTheirFolders() {
        val food = CategoryEntity(id = "food", name = "Food", budgetCents = 50_000)
        val coffee = CategoryEntity(id = "coffee", name = "Coffee", parentId = "food", budgetCents = 5_000)
        val fun_ = CategoryEntity(id = "fun", name = "Fun", budgetCents = 10_000, budgetPeriod = BudgetPeriod.WEEKLY)
        val today = LocalDate.of(2026, 10, 14)
        val ps = listOf(
            PurchaseEntity(merchant = "Cafe", amountCents = 500, date = today, categoryId = "coffee"),
            PurchaseEntity(merchant = "Grocer", amountCents = 4_000, date = today.minusDays(3), categoryId = "food"),
            PurchaseEntity(merchant = "Old", amountCents = 9_999, date = today.minusMonths(1), categoryId = "food"),
            PurchaseEntity(merchant = "Movie", amountCents = 1_500, date = today, categoryId = "fun"),
            PurchaseEntity(merchant = "Game", amountCents = 2_000, date = today.minusDays(10), categoryId = "fun"),
        )
        val s = Budgets.statuses(CategoryTree(listOf(food, coffee, fun_)), ps, today, DayOfWeek.SUNDAY).associateBy { it.category.id }
        assertEquals(4_500L, s["food"]!!.spentCents)
        assertEquals(500L, s["coffee"]!!.spentCents)
        assertEquals(1_500L, s["fun"]!!.spentCents) // last week's game doesn't count this week
    }

    @Test fun customRangeReadsAsWords() {
        val today = LocalDate.of(2026, 10, 14)
        val r = com.jlees.budgey.domain.DateRange(LocalDate.of(2026, 3, 3), LocalDate.of(2026, 4, 2))
        assertEquals("from Mar 3 to Apr 2", com.jlees.budgey.domain.DatePreset.CUSTOM.phrase(r, today))
        assertEquals("this month", com.jlees.budgey.domain.DatePreset.THIS_MONTH.phrase(null, today))
        val old = com.jlees.budgey.domain.DateRange(LocalDate.of(2025, 12, 1), LocalDate.of(2025, 12, 1))
        assertEquals("on Dec 1, 2025", com.jlees.budgey.domain.DatePreset.CUSTOM.phrase(old, today))
    }

    @Test fun clearAllKeepsTheScreensScope() {
        val base = com.jlees.budgey.domain.PurchaseFilter(categoryIds = setOf("food"), datePreset = com.jlees.budgey.domain.DatePreset.ALL)
        val busy = base.copy(query = "x", minCents = 100, datePreset = com.jlees.budgey.domain.DatePreset.LAST_30)
        val cleared = busy.clearedTo(base)
        assertEquals(setOf("food"), cleared.categoryIds)
        assertEquals("", cleared.query)
        assertNull(cleared.minCents)
        assertEquals(com.jlees.budgey.domain.DatePreset.LAST_30, cleared.datePreset)
    }
}
