package com.jlees.budgey

import com.jlees.budgey.domain.Tax
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaxTest {
    @Test fun addsTaxRoundedToCents() {
        assertEquals(140L, Tax.taxCents(1999, 7.0))      // 1.3993 -> 1.40
        assertEquals(2139L, Tax.totalCents(1999, 7.0))   // $19.99 + $1.40
        assertEquals(71L, Tax.taxCents(799, 8.875))      // 0.709 -> 0.71
        assertEquals(799L, Tax.totalCents(799, 0.0))
    }

    @Test fun parsesPercentText() {
        assertEquals(7.25, Tax.parsePercent("7.25")!!, 0.0)
        assertEquals(8.875, Tax.parsePercent("8.875%")!!, 0.0)
        assertEquals(7.5, Tax.parsePercent("7,5")!!, 0.0)
        assertNull(Tax.parsePercent("abc"))
        assertNull(Tax.parsePercent("45"))
        assertEquals("7", Tax.formatPercent(7.0))
        assertEquals("8.875", Tax.formatPercent(8.875))
    }
}
