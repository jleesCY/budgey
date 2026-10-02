package com.jlees.budgey.scan

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.math.BigDecimal
import java.math.RoundingMode

/** One line on a receipt. [priceCents] is the line total (all units). */
data class ReceiptItem(val name: String, val priceCents: Long, val qty: Int = 1)

/** Items plus the summary lines found on a receipt (null = not found). */
data class ItemizedReceipt(
    val items: List<ReceiptItem> = emptyList(),
    val subtotalCents: Long? = null,
    val taxCents: Long? = null,
    val feesCents: Long? = null,
    val tipCents: Long? = null,
    val totalCents: Long? = null,
) {
    val isEmpty: Boolean get() = items.isEmpty() && subtotalCents == null && totalCents == null
}

/**
 * Pulls line items out of a receipt for the check splitter: from the text rows Google's reader
 * found ([fromRows]), or from an AI model's JSON reply ([fromAiReply], with [AI_PROMPT]).
 */
object ReceiptItems {

    // A price at the end of a row, optionally followed by a tax flag letter (T, F, N, X, A, B…).
    private val trailingPrice = Regex("""(?<![\d.,])([-−–]?)\s?\$?\s?(\d{1,3}(?:,\d{3})+|\d+)[.,](\d{2})\s*([-−–]?)\s*(?:[A-Z]{1,2})?\s*$""")
    private val leadingQty = Regex("""^(\d{1,2})\s*(?:x|X|×|@)?\s+(?=\D)""")
    private val unitPrice = Regex("""\s*(?:@|x|×)\s*\$?\d+[.,]\d{2}\s*(?:ea\.?|each)?\s*$""", RegexOption.IGNORE_CASE)

    private val subtotalWords = Regex("""\b(sub\s?-?\s?total|subtotal|sub tot|food total|merchandise|net total)\b""", RegexOption.IGNORE_CASE)
    private val taxWords = Regex("""\b(tax|hst|gst|pst|qst|vat|sales tax)\b""", RegexOption.IGNORE_CASE)
    private val tipWords = Regex("""\b(tip|gratuity|grat)\b""", RegexOption.IGNORE_CASE)
    private val feeWords = Regex("""\b(service charge|service fee|delivery fee|bag fee|surcharge|fee)\b""", RegexOption.IGNORE_CASE)
    private val totalWords = Regex("""\b(total|amount due|balance due|grand total|amount|due)\b""", RegexOption.IGNORE_CASE)
    private val discountWords = Regex("""\b(discount|coupon|promo|savings|off)\b""", RegexOption.IGNORE_CASE)
    // Payment / change / card lines and other things that are never items.
    private val ignoreWords = Regex(
        """\b(change|cash|tender|visa|mastercard|master card|amex|american express|discover|debit|credit|card|payment|paid|auth|approval|approved|""" +
            """balance|account|acct|ref|trans|invoice|order\s?#|table|server|guest|check\s?#|chk|tel|phone|www|thank|survey|reward|points|loyalty|""" +
            """you saved|items sold|item count|qty|date|time)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val hasLetters = Regex("""[A-Za-z]{2,}""")

    private fun cents(whole: String, frac: String, negative: Boolean): Long {
        val v = whole.replace(",", "").toLong() * 100 + frac.toLong()
        return if (negative) -v else v
    }

    /** Rows of text, top to bottom, as Google's reader returns them. */
    fun fromRows(rows: List<String>): ItemizedReceipt {
        val items = mutableListOf<ReceiptItem>()
        var subtotal: Long? = null
        var tax: Long? = null
        var fees: Long? = null
        var tip: Long? = null
        var total: Long? = null
        var pendingName: String? = null // a name on one row with its price alone on the next
        var summaryStarted = false

        for (raw in rows) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val m = trailingPrice.find(line)
            if (m == null) {
                // Remember a likely item name in case its price is on the next row.
                pendingName = if (!summaryStarted && hasLetters.containsMatchIn(line) && !ignoreWords.containsMatchIn(line)) line else null
                continue
            }
            val negative = m.groupValues[1].isNotEmpty() || m.groupValues[4].isNotEmpty()
            val amount = cents(m.groupValues[2], m.groupValues[3], negative)
            var label = line.substring(0, m.range.first).trim().trimEnd(':', '.', '-', ' ')
            if (!hasLetters.containsMatchIn(label)) {
                // Price alone on its row: belongs to the name on the row above.
                label = pendingName ?: continue
            }
            pendingName = null
            when {
                subtotalWords.containsMatchIn(label) -> { subtotal = amount; summaryStarted = true }
                tipWords.containsMatchIn(label) -> { tip = amount; summaryStarted = true }
                taxWords.containsMatchIn(label) -> { tax = (tax ?: 0L) + amount; summaryStarted = true }
                // Fees before the summary (e.g. "Bag fee" in the list) are still fees, not items.
                feeWords.containsMatchIn(label) -> fees = (fees ?: 0L) + amount
                totalWords.containsMatchIn(label) && !discountWords.containsMatchIn(label) -> {
                    // The first "total" after the items is the bill; later ones are usually payments.
                    if (total == null) total = amount
                    summaryStarted = true
                }
                summaryStarted -> Unit // anything after the totals is payment / change
                ignoreWords.containsMatchIn(label) -> Unit
                else -> {
                    var name = label
                    var qty = 1
                    leadingQty.find(name)?.let { q ->
                        qty = q.groupValues[1].toInt().coerceAtLeast(1)
                        name = name.substring(q.range.last + 1)
                    }
                    name = name.replace(unitPrice, "").trim()
                    if (discountWords.containsMatchIn(name) && amount > 0) {
                        items += ReceiptItem(name, -amount, 1) // discounts lower the bill
                    } else if (name.isNotEmpty()) {
                        items += ReceiptItem(name.take(60), amount, qty)
                    }
                }
            }
        }
        return ItemizedReceipt(items, subtotal, tax, fees, tip, total)
    }

    /** Asks an AI model for the items as JSON. */
    val AI_PROMPT: String = """
You read photos of restaurant checks, store receipts and kiosk screens so a group can split the bill.
List every purchased line item exactly as printed. Reply with ONE JSON object and nothing else:
{"items":[{"name":"Cheeseburger","qty":1,"price":12.50}],"subtotal":0.00,"tax":0.00,"fees":0.00,"tip":0.00,"total":0.00}
Rules:
- price is the line total for that row (all units), as a number without currency symbols.
- Discounts or coupons are items with a negative price.
- Do not list subtotal, tax, tip, fees, total, payments, change or card lines as items.
- Use null for any summary value that isn't printed.
""".trim()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun money(e: kotlinx.serialization.json.JsonElement?): Long? {
        val p = e as? JsonPrimitive ?: return null
        val d = p.doubleOrNull ?: p.contentOrNull?.replace("$", "")?.replace(",", "")?.trim()?.toDoubleOrNull() ?: return null
        return BigDecimal(d.toString()).multiply(BigDecimal(100)).setScale(0, RoundingMode.HALF_UP).toLong()
    }

    /** Parses the model's reply (tolerates code fences, thinking text, extra prose). */
    fun fromAiReply(reply: String): ItemizedReceipt? {
        val text = reply.substringAfter("</think>")
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = runCatching { json.parseToJsonElement(text.substring(start, end + 1)) as? JsonObject }.getOrNull() ?: return null
        val items = (obj["items"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = (o["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val price = money(o["price"]) ?: return@mapNotNull null
            if (name.isEmpty()) return@mapNotNull null
            ReceiptItem(name.take(60), price, ((o["qty"] as? JsonPrimitive)?.intOrNull ?: 1).coerceAtLeast(1))
        }
        val r = ItemizedReceipt(items, money(obj["subtotal"]), money(obj["tax"]), money(obj["fees"]), money(obj["tip"]), money(obj["total"]))
        return r.takeUnless { it.isEmpty }
    }
}
