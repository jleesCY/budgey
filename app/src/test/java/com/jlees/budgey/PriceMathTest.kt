package com.jlees.budgey

import com.jlees.budgey.domain.PriceField
import com.jlees.budgey.domain.PriceInputs
import com.jlees.budgey.domain.PriceMath
import org.junit.Assert.assertEquals
import org.junit.Test

class PriceMathTest {
    private fun PriceInputs.type(f: PriceField, t: String) = PriceMath.edit(this, f, t)
    private val saved = PriceInputs.from(1999, 140, 2139)

    @Test fun editingBaseKeepsFeesAndChangesTotal() {
        val s = saved.type(PriceField.BASE, "24.99")
        assertEquals("1.40", s.fees); assertEquals("26.39", s.total); assertEquals(PriceField.TOTAL, s.derived)
    }

    @Test fun editingFeesKeepsBaseAndChangesTotal() {
        val s = saved.type(PriceField.FEES, "2.00")
        assertEquals("19.99", s.base); assertEquals("21.99", s.total); assertEquals(PriceField.TOTAL, s.derived)
    }

    @Test fun editingTotalKeepsBaseAndChangesFees() {
        val s = saved.type(PriceField.TOTAL, "22.49")
        assertEquals("19.99", s.base); assertEquals("2.50", s.fees); assertEquals(PriceField.FEES, s.derived)
    }

    @Test fun rulesHoldInAnyOrder() {
        val s = PriceInputs().type(PriceField.BASE, "10.00").type(PriceField.TOTAL, "11.00").type(PriceField.BASE, "12.00")
        assertEquals("1.00", s.fees); assertEquals("13.00", s.total)
    }

    @Test fun baseAloneFillsTotal() {
        assertEquals("7.99", PriceInputs().type(PriceField.BASE, "7.99").total)
    }

    @Test fun totalWithoutBaseLeavesOthersAlone() {
        val s = PriceInputs().type(PriceField.TOTAL, "8.99")
        assertEquals("", s.base); assertEquals("", s.fees)
    }
}
