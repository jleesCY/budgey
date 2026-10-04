package com.jlees.budgey.scan

import com.jlees.budgey.domain.BillingCycle
import com.jlees.budgey.domain.CycleUnit
import com.jlees.budgey.domain.Money
import com.jlees.budgey.icons.BrandMatcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalDate

/** What the on-device AI model read off the picture. Any field may be missing. */
data class SmartFields(
    val kind: ScanKind? = null,
    val merchant: String? = null,
    val totalCents: Long? = null,
    val date: LocalDate? = null,
    val cycle: BillingCycle? = null,
    val nextBillingDate: LocalDate? = null,
    val trialEndDate: LocalDate? = null,
    val otherAmounts: List<Long> = emptyList(),
)

/**
 * Pure helpers for the AI ("smart") scan: the prompt we send with the picture, turning the
 * model's JSON reply into [SmartFields], and merging that with the classic OCR result.
 */
object SmartScanParser {

    fun prompt(today: LocalDate): String = """
You read photos and screenshots of purchases for a personal budget app. Today is $today.
The picture may be a paper receipt, a gas pump display, a kiosk or card-terminal screen, a banking or
credit-card app screenshot, an order confirmation, an email, an app-store or subscription page, or an invoice.

Find:
- merchant: the business that was paid (not the bank, card network, or payment app; for "SQ *JOE'S CAFE" answer "Joe's Cafe").
- total: the final amount actually charged, as a number. Not a subtotal, tax, tip line, balance, credit limit,
  rewards, price per gallon, or gallons. On a gas pump it is the SALE / $ amount.
  It is the amount AFTER discounts and coupons. If everything was discounted (e.g. a 100% off coupon or a
  comped item) and the receipt's total is 0.00, the total is 0. Never answer an item's price instead.
  Seven-segment digits often lose the decimal point: a pump showing 4567 means 45.67.
- date: the purchase or charge date (YYYY-MM-DD), or null.
- kind: "subscription" if it is a recurring plan or membership, otherwise "purchase".
- billing_cycle: for subscriptions one of "weekly","monthly","quarterly","yearly", else null.
- next_billing_date and trial_end_date (YYYY-MM-DD) if shown, else null.
- other_amounts: up to 5 other money amounts you see, most likely first.

Reply with ONLY one JSON object, no other text:
{"merchant": "<business name>", "total": <number>, "date": null, "kind": "purchase", "billing_cycle": null, "next_billing_date": null, "trial_end_date": null, "other_amounts": []}
Use null for anything you can't find.
""".trim()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Parses the model's reply. Tolerates code fences, chatter around the JSON, and strings for numbers. */
    fun parse(reply: String, today: LocalDate = LocalDate.now()): SmartFields? {
        // Some models "think" first (<think>…</think>); only look at what comes after.
        val body = reply.substringAfterLast("</think>")
        val start = body.indexOf('{')
        val end = body.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = runCatching { json.parseToJsonElement(body.substring(start, end + 1)) as? JsonObject }.getOrNull() ?: return null

        fun str(key: String): String? = (obj[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("null", true) && !it.equals("unknown", true) }

        /** [allowZero]: the total may really be 0 (fully discounted); other amounts never are. */
        fun money(e: JsonElement?, allowZero: Boolean = false): Long? {
            val p = e as? JsonPrimitive ?: return null
            if (p is JsonNull) return null
            p.doubleOrNull?.let { d -> return Math.round(kotlin.math.abs(d) * 100).takeIf { it > 0 || (allowZero && it == 0L) } }
            return p.contentOrNull?.let { Money.parse(it.replace("$", "").replace("−", "-")) }?.let { kotlin.math.abs(it) }
                ?.takeIf { it > 0 || (allowZero && it == 0L) }
        }

        fun date(key: String): LocalDate? = str(key)?.let { s ->
            runCatching { LocalDate.parse(s.take(10)) }.getOrNull()?.takeIf { it.year in 2000..(today.year + 5) }
        }

        val cycle = when (str("billing_cycle")?.lowercase()) {
            "weekly" -> BillingCycle(CycleUnit.WEEK, 1)
            "monthly" -> BillingCycle(CycleUnit.MONTH, 1)
            "quarterly" -> BillingCycle(CycleUnit.MONTH, 3)
            "yearly", "annual", "annually" -> BillingCycle(CycleUnit.YEAR, 1)
            else -> null
        }
        val others = (obj["other_amounts"] as? JsonArray)?.mapNotNull { money(it) }.orEmpty()
        val fields = SmartFields(
            kind = when (str("kind")?.lowercase()) {
                "subscription" -> ScanKind.SUBSCRIPTION
                "purchase" -> ScanKind.PURCHASE
                else -> null
            },
            merchant = str("merchant")?.takeUnless { it.startsWith("<") || it == "..." },
            totalCents = money(obj["total"], allowZero = true),
            date = date("date")?.takeIf { !it.isAfter(today.plusDays(1)) },
            cycle = cycle,
            nextBillingDate = date("next_billing_date"),
            trialEndDate = date("trial_end_date"),
            otherAmounts = others.distinct().take(5),
        )
        return fields.takeIf { it.merchant != null || it.totalCents != null }
    }

    /**
     * The AI's reading wins where it gave an answer; the classic OCR result fills the gaps and
     * keeps its number boxes (tap-to-pick) and alternative amounts.
     */
    fun merge(ocr: ScanResult, smart: SmartFields?, brands: BrandMatcher?): ScanResult {
        if (smart == null) return ocr
        val brand = smart.merchant?.let { m -> brands?.match(m)?.takeIf { !it.payment } }
        // The text reader only reports 0 when a Total line printed 0.00 on a discounted receipt, which is
        // strong evidence. Small AI models tend to answer the item's price there instead, so 0 wins.
        val total = if (ocr.amountCents == 0L) 0L else smart.totalCents
        val amounts = (listOfNotNull(total) + ocr.amountCandidates + smart.otherAmounts).distinct().take(6)
        val kind = smart.kind ?: ocr.kind
        return ocr.copy(
            kind = kind,
            merchant = brand?.name ?: smart.merchant ?: ocr.merchant,
            brand = brand ?: if (smart.merchant == null) ocr.brand else null,
            amountCents = total ?: ocr.amountCents,
            amountCandidates = amounts,
            date = smart.date ?: ocr.date,
            cycle = if (kind == ScanKind.SUBSCRIPTION) smart.cycle ?: ocr.cycle ?: BillingCycle.MONTHLY else ocr.cycle,
            nextBillingDate = smart.nextBillingDate ?: ocr.nextBillingDate,
            trialEndDate = smart.trialEndDate ?: ocr.trialEndDate,
            subscriptionScore = if (smart.kind == ScanKind.SUBSCRIPTION) maxOf(ocr.subscriptionScore, 4) else ocr.subscriptionScore,
        )
    }
}
