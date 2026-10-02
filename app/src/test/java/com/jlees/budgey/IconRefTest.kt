package com.jlees.budgey

import com.jlees.budgey.icons.IconRef
import org.junit.Assert.assertEquals
import org.junit.Test

class IconRefTest {
    @Test fun parsesEveryKind() {
        assertEquals(IconRef.Auto, IconRef.parse(null))
        assertEquals(IconRef.None, IconRef.parse(""))
        assertEquals(IconRef.None, IconRef.parse("none"))
        assertEquals(IconRef.BrandRef("netflix"), IconRef.parse("netflix")) // older bare id
        assertEquals(IconRef.BrandRef("nfl"), IconRef.parse("brand:nfl"))
        assertEquals(IconRef.Image("a.webp"), IconRef.parse("image:a.webp"))
        assertEquals(IconRef.Generic("cash"), IconRef.parse("generic:cash"))
    }

    @Test fun textRoundTripsAndCapsAtThree() {
        val key = IconRef.text("ABCD", 0xFF1E88E5)
        assertEquals("text:#1E88E5:ABC", key)
        assertEquals(IconRef.Text("ABC", 0xFF1E88E5), IconRef.parse(key))
    }
}
