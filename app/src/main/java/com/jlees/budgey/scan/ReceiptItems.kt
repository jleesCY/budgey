package com.jlees.budgey.scan

import com.jlees.budgey.domain.Money
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

    /**
     * Rows that stand for several of the same thing ("5 Burgers 50.00") become one row per unit
     * (five "Burgers" at 10.00), so each one can be given to a different person. The leftover cent
     * of an uneven split goes to the first units. Very large counts (over [maxUnits]) stay as one row.
     */
    fun splitUnits(maxUnits: Int = 20): ItemizedReceipt = copy(items = items.flatMap { item ->
        if (item.qty !in 2..maxUnits || item.priceCents <= 0) listOf(item)
        else {
            val each = item.priceCents / item.qty
            val extra = item.priceCents % item.qty
            List(item.qty) { i -> ReceiptItem(item.name, each + if (i < extra) 1 else 0, 1) }
        }
    })
}

/**
 * Pulls line items out of a receipt for the check splitter: from the text rows Google's reader
 * found ([fromRows]), or from an AI model's JSON reply ([fromAiReply], with [AI_PROMPT]).
 */
object ReceiptItems {

    // A price at the end of a row, optionally followed by a tax flag letter (T, F, N, X, A, B…).
    // A minus counts only when it touches the number ("-2.00", "$-2.00", "2.00-", "2.00 CR"):
    // in "Burger - 12.99" the dash is just a separator. Grouped thousands work either way round.
    private val trailingPrice = Regex(
        """(?<![\d.,])(?:([-−–])(?=\$|\d))?\$?\s?(?:([-−–])(?=\d))?""" +
            """(\d{1,3}(?:,\d{3})+\.\d{2}|\d{1,3}(?:\.\d{3})+,\d{2}|\d{1,7}[.,]\d{2})""" +
            """(?:(-)|\s?(CR)\b)?\s*(?:[A-Z]{1,2})?\s*$""",
    )
    // A whole-dollar price at the end of a row: "5 Burgers ...... $50".
    private val trailingDollars = Regex("""(?<![\d.,])\$\s?(\d{1,5})\s*$""")

    // How many of an item one row stands for. "5 Burgers", "5x Burgers", "5 X Burgers"…
    private val leadingQty = Regex("""^(\d{1,2})\s*(?:x|×)?\s+(?=[A-Za-z])""", RegexOption.IGNORE_CASE)
    // …but "12 oz Latte", "6 pc Nuggets", "2 lb Wings" are sizes, not counts.
    private val sizeWord = Regex("""^\d{1,2}\s*(?:oz|fl\.? oz|pc|pcs|piece|pieces|ct|count|lb|lbs|in|inch|ml|l|ltr|g|kg|pk|pack|cl)\b""", RegexOption.IGNORE_CASE)
    // …"Burgers x5", "Burgers ×5", "Burgers 5x", "Burgers (5)", "Burgers qty 5"…
    private val trailingQty = Regex("""\s+(?:(?:x|×)\s?(\d{1,2})|(\d{1,2})\s?(?:x|×)|\((\d{1,2})\)|qty\.?:?\s*(\d{1,2}))$""", RegexOption.IGNORE_CASE)
    // …and "Burgers 5 @ 10.00" / "Burgers 5 x $10.00 ea" (count and unit price).
    private val unitPrice = Regex("""\s*(?:(\d{1,2})\s*)?(?:@|x|×)\s*\$?(\d+)[.,](\d{2})\s*(?:ea\.?|each)?\s*$""", RegexOption.IGNORE_CASE)
    // A count column between the name and a unit-price column: "Burgers 5 10.00 50.00".
    private val countThenUnit = Regex("""\s+(\d{1,2})\s+\$?(\d+)[.,](\d{2})\s*$""")

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

    private fun cents(amount: String, negative: Boolean): Long? {
        val v = Money.parse(amount)?.let { kotlin.math.abs(it) } ?: return null
        return if (negative) -v else v
    }

    /**
     * "18% tip 8.10", "Suggested gratuity: 15% $6.75 18% $8.10 20% $9.00", "a 20% tip would be 9.00":
     * printed suggestions, not a tip that was actually added.
     */
    private val tipSuggestion = Regex("""%|suggest|would be|guide|calculat|recommend""", RegexOption.IGNORE_CASE)
    private val anyPrice = Regex("""\d+[.,]\d{2}""")

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
            val dollars = if (m == null) trailingDollars.find(line) else null
            if (m == null && dollars == null) {
                // Remember a likely item name in case its price is on the next row.
                pendingName = if (!summaryStarted && hasLetters.containsMatchIn(line) && !ignoreWords.containsMatchIn(line)) line else null
                continue
            }
            val amount = if (m != null) {
                val negative = m.groupValues[1].isNotEmpty() || m.groupValues[2].isNotEmpty() ||
                    m.groupValues[4].isNotEmpty() || m.groupValues[5].isNotEmpty()
                cents(m.groupValues[3], negative) ?: continue
            } else dollars!!.groupValues[1].toLong() * 100
            val priceStart = m?.range?.first ?: dollars!!.range.first
            // Dot / dash / underscore leaders between the name and the price ("Burgers ...... $50").
            var label = line.substring(0, priceStart).trim().trimEnd(':', '.', '-', '_', '·', '…', ' ')
            if (!hasLetters.containsMatchIn(label)) {
                // Price alone on its row: belongs to the name on the row above.
                label = pendingName ?: continue
            }
            pendingName = null
            when {
                subtotalWords.containsMatchIn(label) -> { subtotal = amount; summaryStarted = true }
                tipWords.containsMatchIn(label) -> {
                    // Only a tip that was really added: not a printed suggestion (a % or several
                    // amounts on the row), and not one that appears after the bill's total.
                    val suggestion = tipSuggestion.containsMatchIn(line) || anyPrice.findAll(line).count() > 1
                    if (!suggestion && total == null) tip = amount
                    summaryStarted = true
                }
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
                    val (qty, name) = quantityOf(label, amount)
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

    /**
     * How many units [label] stands for, and the item's name without the count. [amount] (the row
     * total) is used to double-check counts that come with a unit price.
     */
    internal fun quantityOf(label: String, amount: Long): Pair<Int, String> {
        var name = label.trim()
        var qty = 1
        fun plausible(n: Int) = n in 2..99

        // "5 @ 10.00" / "x 10.00 ea": the unit price must multiply up to the row total.
        unitPrice.find(name)?.let { u ->
            val unit = u.groupValues[2].toLong() * 100 + u.groupValues[3].toLong()
            val n = u.groupValues[1].toIntOrNull() ?: if (unit > 0 && amount % unit == 0L) (amount / unit).toInt() else 1
            if (plausible(n) && unit > 0 && kotlin.math.abs(unit * n - kotlin.math.abs(amount)) <= n) qty = n
            name = name.substring(0, u.range.first)
        }
        // "5 10.00" before the row total: a count column, then a unit-price column.
        if (qty == 1) countThenUnit.find(name)?.let { u ->
            val n = u.groupValues[1].toInt()
            val unit = u.groupValues[2].toLong() * 100 + u.groupValues[3].toLong()
            if (plausible(n) && kotlin.math.abs(unit * n - kotlin.math.abs(amount)) <= n) {
                qty = n
                name = name.substring(0, u.range.first)
            }
        }
        if (!sizeWord.containsMatchIn(name)) leadingQty.find(name)?.let { q ->
            val n = q.groupValues[1].toInt()
            // "2% Milk" never matches (needs a space then a letter); a lone "1 Burger" is just 1.
            if (qty == 1 && plausible(n)) qty = n
            if (n >= 1) name = name.substring(q.range.last + 1)
        }
        if (qty == 1) trailingQty.find(name)?.let { q ->
            val n = q.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toIntOrNull() ?: 1
            if (plausible(n)) {
                qty = n
                name = name.substring(0, q.range.first)
            }
        }
        name = name.trim().trimEnd(':', '.', '-', '_', '·', '…', ' ').trim()
        return qty to name
    }

    /** Asks an AI model for the items as JSON. */
    val AI_PROMPT: String = """
You read photos of restaurant checks, store receipts and kiosk screens so a group can split the bill.
List every purchased line item. Reply with ONE JSON object and nothing else, in this shape:
{"items":[{"name":"<item name>","qty":<count>,"price":<row total>}],"subtotal":<number or null>,"tax":<number or null>,"fees":<number or null>,"tip":<number or null>,"total":<number or null>}
Rules:
- One entry per printed row. price is the row total for ALL units on that row, as a plain number.
- Watch for rows that stand for several of the same item and put the count in qty, for example:
  "5 Burgers ...... 50.00", "5x Burger 50.00", "Burger x5 50.00", "Burger (5) 50.00",
  "Burger 5 @ 10.00 50.00", "2 Margarita 24.00", or a QTY column. Those are qty 5 / 5 / 5 / 5 / 5 / 2.
  If a row only shows the price of one unit and a count, price is count × unit price.
- qty is 1 when no count is shown. Don't merge different rows into one.
- Discounts or coupons are items with a negative price.
- Do not list subtotal, tax, tip, fees, total, payments, change or card lines as items.
- Use null for any summary value that isn't printed. Never copy the placeholders above.
""".trim()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun money(e: kotlinx.serialization.json.JsonElement?): Long? {
        val p = e as? JsonPrimitive ?: return null
        val d = p.doubleOrNull ?: p.contentOrNull?.replace("$", "")?.replace(",", "")?.trim()?.toDoubleOrNull() ?: return null
        return BigDecimal(d.toString()).multiply(BigDecimal(100)).setScale(0, RoundingMode.HALF_UP).toLong()
    }

    private val itemObject = Regex("""\{[^{}]*"name"\s*:[^{}]*\}""")

    /**
     * Rescues the complete items from an answer that was cut off (the model ran out of room on a
     * long check): every whole {"name":…} object is kept; the unfinished last one and the summary
     * values, which come after the items, are lost.
     */
    private fun salvage(partial: String): ItemizedReceipt? {
        val items = itemObject.findAll(partial).mapNotNull { m ->
            runCatching { json.parseToJsonElement(m.value) as? JsonObject }.getOrNull()
        }.toList()
        if (items.isEmpty()) return null
        return fromAiReply("""{"items":[${items.joinToString(",")}]}""")
    }

    /** Parses the model's reply (tolerates code fences, thinking text, extra prose). */
    fun fromAiReply(reply: String): ItemizedReceipt? {
        val text = reply.substringAfter("</think>")
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = runCatching { json.parseToJsonElement(text.substring(start, end + 1)) as? JsonObject }.getOrNull()
            ?: return salvage(text.substring(start)) // a long check's answer cut off mid-way
        var items = (obj["items"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val rawName = (o["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (rawName.isEmpty() || rawName.startsWith("<") || rawName.trim('.', '…').isEmpty()) return@mapNotNull null // an echoed placeholder
            val unit = money(o["unit_price"])
            var qty = ((o["qty"] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.trim()?.toDoubleOrNull()?.toInt() } ?: 1)
                .coerceIn(1, 99)
            // The model sometimes leaves the count in the name ("5 Burgers") with qty 1.
            val (countInName, name) = quantityOf(rawName, 0)
            if (qty == 1 && countInName > 1) qty = countInName
            val price = money(o["price"]) ?: unit?.times(qty) ?: return@mapNotNull null
            ReceiptItem(name.ifEmpty { rawName }.take(60), price, qty)
        }
        val subtotal = money(obj["subtotal"])
        // Some models give the price of ONE unit for a multi-unit row. If treating those prices as unit
        // prices makes the items add up to the printed subtotal (and as-is they don't), fix them.
        if (subtotal != null && items.any { it.qty > 1 }) {
            val asIs = items.sumOf { it.priceCents }
            val asUnits = items.sumOf { if (it.qty > 1) it.priceCents * it.qty else it.priceCents }
            if (kotlin.math.abs(asUnits - subtotal) <= 2 && kotlin.math.abs(asIs - subtotal) > 2) {
                items = items.map { if (it.qty > 1) it.copy(priceCents = it.priceCents * it.qty) else it }
            }
        }
        val r = ItemizedReceipt(items, subtotal, money(obj["tax"]), money(obj["fees"]), money(obj["tip"]), money(obj["total"]))
        return r.takeUnless { it.isEmpty }
    }
}
