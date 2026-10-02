package com.jlees.budgey

import com.jlees.budgey.domain.BillingCycle
import com.jlees.budgey.icons.Brand
import com.jlees.budgey.icons.BrandMatcher
import com.jlees.budgey.scan.ScanKind
import com.jlees.budgey.scan.ScanResult
import com.jlees.budgey.scan.SmartScanParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SmartScanParserTest {
    private val today = LocalDate.of(2026, 10, 1)

    @Test fun parsesFencedJsonWithChatter() {
        val reply = """Sure! Here it is:
```json
{"merchant": "Shell", "total": "45.67", "date": "2026-09-30", "kind": "purchase", "billing_cycle": null,
 "next_billing_date": null, "trial_end_date": null, "other_amounts": [3.459, "$13.20"]}
```"""
        val f = SmartScanParser.parse(reply, today)!!
        assertEquals("Shell", f.merchant)
        assertEquals(4567L, f.totalCents)
        assertEquals(LocalDate.of(2026, 9, 30), f.date)
        assertEquals(ScanKind.PURCHASE, f.kind)
        assertEquals(listOf(346L, 1320L), f.otherAmounts)
    }

    @Test fun subscriptionFields() {
        val f = SmartScanParser.parse("""{"merchant":"Netflix","total":22.99,"kind":"subscription","billing_cycle":"monthly","next_billing_date":"2026-10-12","date":null}""", today)!!
        assertEquals(ScanKind.SUBSCRIPTION, f.kind)
        assertEquals(BillingCycle.MONTHLY, f.cycle)
        assertEquals(LocalDate.of(2026, 10, 12), f.nextBillingDate)
        assertNull(f.date)
    }

    @Test fun rejectsGarbageAndFutureDates() {
        assertNull(SmartScanParser.parse("I can't read this image.", today))
        assertNull(SmartScanParser.parse("""{"merchant": null, "total": null}""", today))
        assertNull(SmartScanParser.parse("""{"merchant":"X","total":5,"date":"2027-05-01"}""", today)!!.date)
    }

    @Test fun mergePrefersModelButKeepsOcrExtras() {
        val ocr = ScanResult(ScanKind.PURCHASE, "Chase", null, 234567, null, null, null, null, listOf(234567, 645), 0, "raw")
        val matcher = BrandMatcher(listOf(Brand("starbucks", "Starbucks", 0, "coffee", false, emptyList(), null)))
        val smart = SmartScanParser.parse("""{"merchant":"STARBUCKS STORE 123","total":6.45,"date":"2026-09-28","kind":"purchase"}""", today)
        val r = SmartScanParser.merge(ocr, smart, matcher)
        assertEquals("Starbucks", r.merchant)
        assertEquals(645L, r.amountCents)
        assertEquals(listOf(645L, 234567L), r.amountCandidates)
        assertEquals(LocalDate.of(2026, 9, 28), r.date)
        assertEquals("raw", r.rawText)
    }

    @Test fun ignoresThinkingBlock() {
        val f = SmartScanParser.parse("<think>The total {maybe} is…</think>\n{\"merchant\":\"Kroger\",\"total\":12.5}", today)!!
        assertEquals("Kroger", f.merchant)
        assertEquals(1250L, f.totalCents)
    }

    @Test fun engineCatalog() {
        assertEquals(com.jlees.budgey.scan.ScanEngine.VISION_AI, com.jlees.budgey.scan.ScanEngine.forFile("gemma-4-E2B-it.litertlm"))
        assertTrue(com.jlees.budgey.scan.ScanEngine.entries.filter { it.downloadable }.all { it.url!!.startsWith("https://huggingface.co/litert-community/") })
    }
}
