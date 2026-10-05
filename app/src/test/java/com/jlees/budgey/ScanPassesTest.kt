package com.jlees.budgey

import com.jlees.budgey.scan.ImageEnhance
import com.jlees.budgey.scan.ReceiptParser
import com.jlees.budgey.scan.ScanKind
import com.jlees.budgey.scan.ScanResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Multi-pass OCR voting and the display-digit clean-up. */
class ScanPassesTest {
    private val parser = ReceiptParser(null)

    private fun result(merchant: String?, vararg amounts: Long) = ScanResult(
        kind = ScanKind.PURCHASE, merchant = merchant, brand = null, amountCents = amounts.firstOrNull(), date = null,
        cycle = null, nextBillingDate = null, trialEndDate = null, amountCandidates = amounts.toList(),
        subscriptionScore = 0, rawText = merchant ?: "",
    )

    @Test fun passesVoteOnTheAmount() {
        // Plain image misread the sale as 45.07; both cleaned-up passes read 45.67.
        val r = parser.combine(listOf(result("Shell", 4507, 4567), result(null, 4567), result(null, 4567, 1320)))
        assertEquals(4567L, r.amountCents)
        assertEquals("Shell", r.merchant)
        assertTrue(4507L in r.amountCandidates)
    }

    @Test fun singlePassIsUnchanged() {
        val one = result("Target", 1299)
        assertEquals(one, parser.combine(listOf(one)))
    }

    @Test fun cleanupClosesGapsBetweenSegments() {
        // 9×5 white image with two dark bars separated by a 1-px gap (like a display segment gap).
        val w = 9; val h = 5
        val gray = IntArray(w * h) { 255 }
        for (y in 1..3) { gray[y * w + 2] = 0; gray[y * w + 3] = 0; gray[y * w + 5] = 0; gray[y * w + 6] = 0 }
        val thick = ImageEnhance.thickenDark(gray, w, h, 1)
        assertEquals(0, thick[2 * w + 4]) // the gap is filled
        val bin = ImageEnhance.adaptiveThreshold(gray, w, h, window = 9)
        assertEquals(0, bin[2 * w + 2])
        assertEquals(255, bin[0])
        assertEquals(255, ImageEnhance.invert(IntArray(1))[0])
    }
}
