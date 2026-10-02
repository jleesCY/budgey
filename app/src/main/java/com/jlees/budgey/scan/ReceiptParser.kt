package com.jlees.budgey.scan

import com.jlees.budgey.domain.BillingCycle
import com.jlees.budgey.domain.CycleUnit
import com.jlees.budgey.domain.Money
import com.jlees.budgey.icons.Brand
import com.jlees.budgey.icons.BrandMatcher
import java.time.LocalDate

enum class ScanKind { PURCHASE, SUBSCRIPTION }

data class ScanResult(
    val kind: ScanKind,
    val merchant: String?,
    val brand: Brand?,
    val amountCents: Long?,
    val date: LocalDate?,
    val cycle: BillingCycle?,
    val nextBillingDate: LocalDate?,
    val trialEndDate: LocalDate?,
    /** Other amounts seen, best first, so the user can tap a different one. */
    val amountCandidates: List<Long>,
    val subscriptionScore: Int,
    val rawText: String,
    /** Every amount found on the picture and where it is, for tap-to-pick on the photo. */
    val numberBoxes: List<NumberBox> = emptyList(),
)

/** An amount found on the photo; position as fractions (0–1) of the upright image. */
data class NumberBox(val cents: Long, val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * Heuristic, fully offline extraction of purchase / subscription details from OCR text.
 * Input should be visual rows (left-to-right text that sits on the same line), which
 * keeps "TOTAL ........ 23.45" together even when OCR splits it into columns.
 *
 * Everything here is a best guess — the UI always shows an editable review screen.
 */
/**
 * One visual row of OCR text. [size] is the row's text height relative to the median row
 * (1.0 = normal, 2.5 = a big headline amount in a banking app). Plain strings get size 1.
 */
data class OcrLine(val text: String, val size: Float = 1f)

class ReceiptParser(private val brands: BrandMatcher?) {

    /** Plain text rows (tests, pasted text). */
    fun parse(rows: List<String>, today: LocalDate = LocalDate.now()): ScanResult =
        parseRows(rows.map { OcrLine(it) }, today)

    fun parseRows(rows: List<OcrLine>, today: LocalDate = LocalDate.now()): ScanResult {
        val ocr = rows.map { it.copy(text = it.text.trim()) }.filter { it.text.isNotEmpty() }
        val lines = ocr.map { it.text }
        val sizes = ocr.map { it.size }
        val lower = lines.map { it.lowercase() }
        val all = lower.joinToString("\n")

        val doc = docType(all)
        val subScore = subscriptionScore(all)
        val amounts = rankAmounts(lines, sizes, if (subScore >= 4) ScanKind.SUBSCRIPTION else ScanKind.PURCHASE, doc)
        val merchantPick = pickMerchant(lines, sizes, amounts.firstOrNull()?.line, doc)
        val brand = merchantPick?.brand
        val merchant = merchantPick?.name

        val finalSubScore = subScore + if (brand?.subscription == true) 2 else 0
        val kind = if (finalSubScore >= 4) ScanKind.SUBSCRIPTION else ScanKind.PURCHASE
        val dates = findDates(lines, today)

        val nextBilling = dates.firstOrNull { it.tag == DateTag.NEXT_BILLING }?.date
        val trialEnd = dates.firstOrNull { it.tag == DateTag.TRIAL_END }?.date
        val purchaseDate = dates
            .filter { it.tag == DateTag.PURCHASE || it.tag == DateTag.NONE }
            .sortedByDescending { if (it.tag == DateTag.PURCHASE) 1 else 0 }
            .firstOrNull { !it.date.isAfter(today.plusDays(1)) }?.date

        val values = amounts.map { it.cents }.distinct().take(6)
        return ScanResult(
            kind = kind,
            merchant = merchant,
            brand = brand,
            amountCents = values.firstOrNull(),
            date = purchaseDate,
            cycle = if (kind == ScanKind.SUBSCRIPTION) detectCycle(all) else null,
            nextBillingDate = nextBilling,
            trialEndDate = trialEnd,
            amountCandidates = values,
            subscriptionScore = finalSubScore,
            rawText = lines.joinToString("\n"),
        )
    }

    // ---------------- Several OCR passes → one answer ----------------

    /**
     * Merges the results of reading the same picture several ways (as-is, cleaned up, inverted).
     * Amounts vote: each pass gives its best guess 4 points, runner-up 3, then 2, then 1 — so a
     * number that several passes agree on beats one lucky misread. Merchant, dates and kind come
     * from the first pass that found them (the plain image first).
     */
    fun combine(results: List<ScanResult>): ScanResult {
        require(results.isNotEmpty())
        if (results.size == 1) return results.first()
        val votes = LinkedHashMap<Long, Int>()
        results.forEach { r ->
            r.amountCandidates.forEachIndexed { i, cents -> votes[cents] = (votes[cents] ?: 0) + (4 - i).coerceAtLeast(1) }
        }
        val ranked = votes.entries.sortedByDescending { it.value }.map { it.key }
        val base = results.first()
        val withMerchant = results.firstOrNull { it.merchant != null } ?: base
        val texts = results.map { it.rawText }.filter { it.isNotBlank() }.distinct()
        return base.copy(
            merchant = withMerchant.merchant,
            brand = withMerchant.brand,
            amountCents = ranked.firstOrNull(),
            amountCandidates = ranked.take(6),
            date = results.firstNotNullOfOrNull { it.date },
            nextBillingDate = results.firstNotNullOfOrNull { it.nextBillingDate },
            trialEndDate = results.firstNotNullOfOrNull { it.trialEndDate },
            cycle = base.cycle ?: results.firstNotNullOfOrNull { it.cycle },
            rawText = texts.joinToString("\n\n— cleaned-up copy —\n"),
        )
    }

    /**
     * Turns OCR word/line boxes into tappable amounts. Digits with no decimal point ("4567") are
     * offered as $45.67 only on fuel pumps, where the display's dot often goes missing.
     * Overlapping boxes for the same amount (from different passes) are merged.
     */
    fun numberBoxes(boxes: List<TextBox>, fuel: Boolean): List<NumberBox> {
        val bare = Regex("""^\$?(\d{3,5})$""")
        val found = boxes.mapNotNull { b ->
            val t = b.text.trim()
            val amounts = signedAmountsIn(t).map { it.first }.filter { it > 0 }.ifEmpty {
                if (fuel) bare.find(t)?.groupValues?.get(1)?.toLongOrNull()?.let { listOf(it) }.orEmpty() else emptyList()
            }
            // A line box holding several amounts can't point at one of them — rely on its word boxes.
            if (amounts.size != 1) null else NumberBox(amounts.first(), b.left, b.top, b.right, b.bottom)
        }.sortedBy { area(it) } // tight word boxes first, whole-line boxes last
        val out = ArrayList<NumberBox>()
        for (nb in found) {
            if (out.none { it.cents == nb.cents && (overlap(it, nb) > 0.3f || contains(nb, it)) }) out += nb
        }
        return out
    }

    private fun area(a: NumberBox) = ((a.right - a.left) * (a.bottom - a.top)).coerceAtLeast(0f)

    private fun overlap(a: NumberBox, b: NumberBox): Float {
        val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (w <= 0f || h <= 0f) return 0f
        val inter = w * h
        return inter / (area(a) + area(b) - inter).coerceAtLeast(1e-6f)
    }

    private fun contains(outer: NumberBox, inner: NumberBox) =
        outer.left <= inner.left && outer.top <= inner.top && outer.right >= inner.right && outer.bottom >= inner.bottom &&
            area(outer) > area(inner)

    // ---------------- What kind of picture is this? ----------------

    enum class DocType { RECEIPT, BANK_APP, FUEL_PUMP }

    private val bankSignals = listOf(
        "pending", "posted", "transaction details", "transaction detail", "card ending", "account ending", "ending in",
        "available balance", "current balance", "dispute", "report a problem", "split this", "merchant", "card used",
        "transaction date", "authorized", "authorization", "debit card", "credit card", "statement", "category",
        "rewards earned", "cash back", "view receipt", "payment method", "transaction id", "reference",
    )
    private val fuelSignals = listOf("gallon", "gallons", "price/gal", "per gal", "/gal", "price per", "unleaded", "diesel", "regular", "premium", "pump", "fuel", "litre", "liter")

    internal fun docType(allLower: String): DocType {
        val fuel = fuelSignals.count { allLower.contains(it) }
        if (fuel >= 2) return DocType.FUEL_PUMP
        val bank = bankSignals.count { allLower.contains(it) }
        val receipt = receiptSignals.sumOf { (w, s) -> if (allLower.contains(w)) s else 0 }
        return if (bank >= 2 && bank * 2 > receipt) DocType.BANK_APP else DocType.RECEIPT
    }

    // ---------------- Amounts ----------------

    // "$12.34", "-$12.34", "$-12.34", "−12.34", "(12.34)", "USD 12.34", "12,34"
    private val moneyRegex = Regex("""(?<![\d.,])([-−–(])?\s?(?:usd|us\$|\$)?\s?([-−–])?\s?(\d{1,3}(?:,\d{3})+|\d+)[.,](\d{2})(?![\d])""", RegexOption.IGNORE_CASE)
    private val dollarWholeRegex = Regex("""\$\s?(\d{1,6})(?![\d.,])""")
    /** Fuel pumps and LCD screens often lose the decimal point: "SALE $ 4567" = $45.67. */
    private val bareDigitsRegex = Regex("""(?<![\d.,])(\d{3,5})(?![\d.,])""")

    internal data class AmountHit(val cents: Long, val score: Int, val line: Int)

    /** Amounts on a line as positive cents, each flagged if it was written as negative. */
    internal fun signedAmountsIn(line: String): List<Pair<Long, Boolean>> {
        val out = ArrayList<Pair<Long, Boolean>>()
        moneyRegex.findAll(line).forEach { m ->
            // Skip things that look like dates (12.25.2026) or times (10:45)
            val after = line.substring(m.range.last + 1).take(2)
            if (after.length == 2 && after[0] in "./:" && after[1].isDigit()) return@forEach
            val v = Money.parse(m.groupValues[3] + "." + m.groupValues[4]) ?: return@forEach
            val negative = m.groupValues[1].isNotEmpty() || m.groupValues[2].isNotEmpty()
            out += v to negative
        }
        if (out.isEmpty()) dollarWholeRegex.findAll(line).forEach { m ->
            m.groupValues[1].toLongOrNull()?.let { out += it * 100 to false }
        }
        return out
    }

    internal fun amountsIn(line: String): List<Long> = signedAmountsIn(line).map { (v, neg) -> if (neg) -v else v }

    private val strongTotal = listOf(
        "grand total" to 100, "amount due" to 95, "balance due" to 95, "order total" to 95, "total due" to 95,
        "total sale" to 95, "fuel sale" to 95, "purchase amount" to 92, "transaction amount" to 92,
        "total charged" to 92, "amount charged" to 92, "you paid" to 92, "total paid" to 90, "payment total" to 90,
        "you spent" to 85, "charged to" to 85, "amount paid" to 90, "total" to 80, "sale" to 70, "amount" to 45,
        "charged" to 50, "charge" to 45, "paid" to 45, "price" to 35, "billed" to 50, "payment" to 40, "debit" to 25,
    )
    private val penalties = listOf(
        "subtotal" to 70, "sub total" to 70, "sub-total" to 70, "tax" to 40, "tip" to 35, "gratuity" to 35,
        "discount" to 60, "savings" to 60, "you saved" to 60, "change" to 80, "cash back" to 70, "cash" to 50, "tender" to 60,
        "points" to 60, "balance remaining" to 50, "previous" to 40, "item" to 25, "qty" to 25, "each" to 25,
        "@" to 25, "deposit" to 30, "fee" to 20, "gift card balance" to 60,
        // Banking apps show lots of numbers that aren't the purchase:
        "available" to 90, "balance" to 70, "credit limit" to 90, "limit" to 50, "rewards" to 60, "earned" to 50,
        "minimum" to 60, "apr" to 60, "interest" to 40,
        // Fuel pumps:
        "gallon" to 90, "gal" to 70, "per " to 40, "/l" to 40, "litre" to 70, "liter" to 70,
    )
    private val perPeriod = Regex("""(/\s?(mo|month|yr|year|wk|week)\b|per (month|year|week)|a (month|year)|monthly|annually|yearly)""")

    internal fun rankAmounts(lines: List<String>, kind: ScanKind): List<Long> =
        rankAmounts(lines, lines.map { 1f }, kind, DocType.RECEIPT).map { it.cents }.distinct().take(6)

    internal fun rankAmounts(lines: List<String>, sizes: List<Float>, kind: ScanKind, doc: DocType): List<AmountHit> {
        val hits = ArrayList<AmountHit>()
        val maxSize = sizes.maxOrNull() ?: 1f
        lines.forEachIndexed { i, raw ->
            val line = raw.lowercase()
            var found = signedAmountsIn(raw)
            // Gas pumps: digits without a decimal point next to SALE / $.
            if (found.isEmpty() && doc == DocType.FUEL_PUMP && (line.contains("sale") || line.contains("$") || line.contains("amount"))) {
                bareDigitsRegex.find(line)?.let { m -> m.value.toLongOrNull()?.let { found = listOf(it to false) } }
            }
            if (found.isEmpty()) return@forEachIndexed
            var labelLine = line
            // Label on one row, value alone on the next (common in app screenshots).
            if (line.replace(Regex("""[\d$.,\s()\-−–+]|usd"""), "").isEmpty() && i > 0) {
                labelLine = lines[i - 1].lowercase() + " " + line
            }
            var score = 10
            strongTotal.firstOrNull { labelLine.contains(it.first) }?.let { score += it.second }
            penalties.forEach { (word, p) -> if (labelLine.contains(word)) score -= p }
            if (kind == ScanKind.SUBSCRIPTION && perPeriod.containsMatchIn(line)) score += 70
            // Big text is usually the headline number (banking apps, kiosk screens, pump displays).
            val size = sizes.getOrElse(i) { 1f }
            if (size >= 1.3f) score += ((size - 1f) * 45f).toInt().coerceAtMost(90)
            if (size == maxSize && maxSize >= 1.5f) score += 25
            when (doc) {
                // Receipts put the total near the bottom; banking apps put the amount near the top.
                DocType.RECEIPT -> score += (i * 10 / lines.size.coerceAtLeast(1))
                DocType.BANK_APP -> score += ((lines.size - i) * 10 / lines.size.coerceAtLeast(1))
                DocType.FUEL_PUMP -> Unit
            }
            found = found.filter { it.first != 0L }
            found.forEachIndexed { k, (cents, negative) ->
                // On receipts a negative line is a discount; in banking apps "-$12.34" is the charge.
                val neg = if (negative && doc != DocType.BANK_APP) -40 else 0
                // With "Total 3 items 23.45" take the last number on the row.
                hits += AmountHit(cents, score + neg + if (k == found.lastIndex) 2 else 0, i)
            }
        }
        // Fuel: gallons × price per gallon is a reliable cross-check.
        if (doc == DocType.FUEL_PUMP) fuelEstimate(lines)?.let { est ->
            val close = hits.filter { kotlin.math.abs(it.cents - est) <= 5 }
            if (close.isNotEmpty()) close.forEach { h -> hits += h.copy(score = h.score + 60) }
            else hits += AmountHit(est, 40, -1)
        }
        if (hits.isEmpty()) return emptyList()
        // "Biggest number wins ties" only makes sense on receipts (totals ≥ items).
        val maxAmount = hits.maxOf { it.cents }
        return hits
            .sortedWith(
                compareByDescending<AmountHit> { it.score + if (doc == DocType.RECEIPT && it.cents == maxAmount) 8 else 0 }
                    .thenByDescending { it.cents }
            )
            .filter { it.cents > 0 }
            .distinctBy { it.cents }
    }

    /** Gallons × price/gal from a pump photo, in cents (null if either is missing). */
    internal fun fuelEstimate(lines: List<String>): Long? {
        var gallons: Double? = null
        var price: Double? = null
        lines.forEachIndexed { i, raw ->
            val l = raw.lowercase()
            val nums = Regex("""(\d{1,3})[.,](\d{2,3})(?![\d])""").findAll(raw).map { (it.groupValues[1] + "." + it.groupValues[2]).toDouble() }.toList()
            if (nums.isEmpty()) return@forEachIndexed
            if (price == null && (l.contains("price") || l.contains("/gal") || l.contains("per gal"))) price = nums.firstOrNull { it in 1.0..15.0 }
            else if (gallons == null && (l.contains("gallon") || l.contains("gal") || l.contains("volume") || l.contains("litre") || l.contains("liter"))) gallons = nums.firstOrNull()
            else if (nums.size == 1 && i > 0) {
                // value on the line below its label
                val prev = lines[i - 1].lowercase()
                if (price == null && (prev.contains("price") || prev.contains("/gal"))) price = nums.first().takeIf { it in 1.0..15.0 }
                else if (gallons == null && (prev.contains("gallon") || prev.contains("gal"))) gallons = nums.first()
            }
        }
        val g = gallons ?: return null
        val p = price ?: return null
        if (g <= 0.0 || g > 60.0) return null
        return Math.round(g * p * 100)
    }

    // ---------------- Dates ----------------

    enum class DateTag { NONE, PURCHASE, NEXT_BILLING, TRIAL_END }
    data class DateHit(val date: LocalDate, val tag: DateTag, val line: Int)

    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val monthAlt = "(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?"
    private val isoDate = Regex("""\b(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})\b""")
    private val usDate = Regex("""\b(\d{1,2})[/\-.](\d{1,2})[/\-.](\d{2}|\d{4})\b""")
    private val monthFirst = Regex("""\b$monthAlt\s+(\d{1,2})(?:st|nd|rd|th)?,?\s+(\d{4})\b""", RegexOption.IGNORE_CASE)
    private val dayFirst = Regex("""\b(\d{1,2})(?:st|nd|rd|th)?\s+$monthAlt,?\s+(\d{4})\b""", RegexOption.IGNORE_CASE)
    private val monthNoYear = Regex("""\b$monthAlt\s+(\d{1,2})(?:st|nd|rd|th)?\b(?!,?\s*\d{4})""", RegexOption.IGNORE_CASE)

    private fun month(name: String): Int = months.indexOf(name.lowercase().take(3)) + 1

    private fun safeDate(y: Int, m: Int, d: Int): LocalDate? =
        if (y in 2000..2100 && m in 1..12 && d in 1..31) runCatching { LocalDate.of(y, m, d) }.getOrNull() else null

    internal fun datesIn(line: String, today: LocalDate): List<LocalDate> {
        val out = ArrayList<LocalDate>()
        isoDate.findAll(line).forEach { m ->
            safeDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())?.let(out::add)
        }
        usDate.findAll(line).forEach { m ->
            if (isoDate.containsMatchIn(m.value)) return@forEach
            var y = m.groupValues[3].toInt()
            if (y < 100) y += 2000
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            // US order first (MM/DD); fall back to DD/MM when MM is impossible.
            (safeDate(y, a, b) ?: safeDate(y, b, a))?.let(out::add)
        }
        monthFirst.findAll(line).forEach { m ->
            safeDate(m.groupValues[3].toInt(), month(m.groupValues[1]), m.groupValues[2].toInt())?.let(out::add)
        }
        dayFirst.findAll(line).forEach { m ->
            safeDate(m.groupValues[3].toInt(), month(m.groupValues[2]), m.groupValues[1].toInt())?.let(out::add)
        }
        if (out.isEmpty()) monthNoYear.findAll(line).forEach { m ->
            val mo = month(m.groupValues[1])
            val d = m.groupValues[2].toInt()
            safeDate(today.year, mo, d)?.let { date ->
                // "Renews Jan 5" in December means next year; a past-looking receipt date means this year.
                out += date
            }
        }
        return out
    }

    private val nextBillingWords = listOf("next billing", "next payment", "next charge", "renews", "renewal", "will be charged", "next bill", "billing date", "auto-renew", "until")
    private val trialWords = listOf("trial ends", "trial end", "free until", "trial expires", "free trial")
    private val purchaseWords = listOf("order date", "date:", "purchased", "placed on", "transaction date", "invoice date", "paid on", "date ")

    internal fun findDates(lines: List<String>, today: LocalDate): List<DateHit> {
        val out = ArrayList<DateHit>()
        lines.forEachIndexed { i, raw ->
            val l = raw.lowercase()
            val ds = datesIn(raw, today)
            if (ds.isEmpty()) return@forEachIndexed
            val context = l + " " + (if (i > 0) lines[i - 1].lowercase() else "")
            val tag = when {
                trialWords.any { l.contains(it) } -> DateTag.TRIAL_END
                nextBillingWords.any { l.contains(it) } -> DateTag.NEXT_BILLING
                purchaseWords.any { context.contains(it) } -> DateTag.PURCHASE
                else -> DateTag.NONE
            }
            ds.forEach { d ->
                // A "next billing" date without a year that lands in the past is really next year.
                val fixed = if (tag == DateTag.NEXT_BILLING && d.isBefore(today) && d.year == today.year && !raw.contains(today.year.toString())) d.plusYears(1) else d
                out += DateHit(fixed, tag, i)
            }
        }
        return out
    }

    // ---------------- Subscriptions ----------------

    private val subSignals = listOf(
        "subscription" to 3, "subscribed" to 3, "membership" to 2, "renew" to 3, "recurring" to 3,
        "next billing" to 4, "next payment" to 4, "next charge" to 3, "billing period" to 3, "billing cycle" to 3,
        "per month" to 2, "/month" to 2, "/mo" to 2, "monthly" to 2, "annual plan" to 2, "yearly" to 2,
        "per year" to 2, "/yr" to 2, "/year" to 2, "free trial" to 2, "cancel anytime" to 2,
        "manage subscription" to 3, "manage your subscription" to 3, "your plan" to 2, "premium" to 1, "plan" to 1,
    )
    private val receiptSignals = listOf(
        "subtotal" to 2, "tip" to 1, "server" to 2, "table" to 1, "change due" to 2, "cash" to 1, "qty" to 1,
        "item count" to 2, "store #" to 2, "cashier" to 2, "register" to 1, "guests" to 2,
    )

    internal fun subscriptionScore(allLower: String): Int =
        subSignals.sumOf { (w, s) -> if (allLower.contains(w)) s else 0 } -
            receiptSignals.sumOf { (w, s) -> if (allLower.contains(w)) s else 0 }

    internal fun detectCycle(allLower: String): BillingCycle = when {
        Regex("""weekly|/\s?wk\b|per week|/\s?week|every week""").containsMatchIn(allLower) -> BillingCycle(CycleUnit.WEEK, 1)
        Regex("""quarterly|every 3 months|/\s?quarter""").containsMatchIn(allLower) -> BillingCycle(CycleUnit.MONTH, 3)
        Regex("""annual|yearly|/\s?yr\b|per year|/\s?year|12 months|every year""").containsMatchIn(allLower) -> BillingCycle(CycleUnit.YEAR, 1)
        else -> BillingCycle(CycleUnit.MONTH, 1)
    }

    // ---------------- Merchant ----------------

    internal data class MerchantPick(val name: String, val brand: Brand?)

    private val uiChrome = listOf(
        "receipt", "welcome", "thank", "invoice", "order", "date", "time", "tel", "phone", "www", "http", "store #",
        "customer", "copy", "transaction", "subject", "to:", "your ", "hello", "hi ", "confirmation", "payment",
        "total", "subtotal", "amount", "pending", "posted", "details", "detail", "status", "category", "back", "done",
        "edit", "share", "help", "search", "home", "today", "yesterday", "card ending", "account", "balance",
        "available", "merchant", "description", "dispute", "report", "split", "statement", "debit", "credit",
        "purchase", "authorized", "approved", "card ", "visa", "mastercard", "amex", "discover", "chip", "contactless",
        "sale", "gallons", "price", "pump", "fuel", "self serve", "please", "see ", "cashier", "server", "table",
        "reference", "method", "activity", "transactions", "spending", "recurring", "rewards", "points", "notes", "add a note",
    )
    private val labelWords = listOf("merchant", "description", "payee", "paid to", "to", "where", "store", "vendor", "business", "location", "sold by")
    private val streetRegex = Regex("""^\d+\s+\w+.*\b(st|ave|rd|blvd|dr|street|road|avenue|lane|ln|hwy|pkwy|way|ct|pl)\b""", RegexOption.IGNORE_CASE)
    private val phoneRegex = Regex("""\(?\d{3}\)?[\s.-]\d{3}[\s.-]\d{4}""")
    private val timeRegex = Regex("""^\d{1,2}:\d{2}""")
    private val noisePrefix = Regex("""^(sq\s?\*|tst\s?\*|sp\s?\*|pp\s?\*|paypal\s?\*|pos\s+|ach\s+|debit card purchase\s*-?\s*|checkcard\s+\d*\s*|purchase authorized on \S+\s+|recurring payment\s*-?\s*)""", RegexOption.IGNORE_CASE)
    private val trailingNoise = Regex("""(\s+(#?\d[\d-]*|[A-Z]{2}|x+\d+|\*+\d+))+$""")

    /** "SQ *BLUE BOTTLE COFFEE 1234 CA" → "Blue Bottle Coffee". */
    internal fun cleanMerchantName(raw: String): String {
        var s = raw.trim().trim('"', '\'', '•', '·', '-', ':')
        s = s.replace(noisePrefix, "")
        if (s.split(' ').size > 2) s = s.replace(trailingNoise, "")
        return titleCase(s.trim())
    }

    private fun isPaymentBrand(line: String): Boolean {
        val b = brands?.match(line) ?: return false
        return b.payment && BrandMatcher.normalize(line).length <= BrandMatcher.normalize(b.name).length + 12
    }

    internal fun guessMerchant(lines: List<String>): String? = pickMerchant(lines, lines.map { 1f }, null, DocType.RECEIPT)?.name

    internal fun pickMerchant(lines: List<String>, sizes: List<Float>, amountLine: Int?, doc: DocType): MerchantPick? {
        // Email screenshots: "From: Name <x@y.com>"
        lines.firstOrNull { it.lowercase().startsWith("from:") }?.let { from ->
            val name = from.substringAfter(":").substringBefore("<").trim().trim('"')
            if (name.length >= 2) return MerchantPick(brands?.match(name)?.name ?: name, brands?.match(name))
        }
        data class Cand(val text: String, val score: Int, val brand: Brand?)
        val cands = ArrayList<Cand>()
        val limit = lines.size.coerceAtMost(30)
        for (i in 0 until limit) {
            val raw = lines[i]
            val l = raw.lowercase().trim()
            // "Merchant: Blue Bottle" on one row, or "Merchant" then the name on the next row.
            val label = labelWords.firstOrNull { w -> l == w || l.startsWith("$w:") || l.startsWith("$w  ") }
            if (label != null) {
                val rest = raw.substring(label.length).trim().trimStart(':').trim()
                val value = rest.ifEmpty { lines.getOrNull(i + 1) ?: "" }
                if (value.count { it.isLetter() } >= 2) {
                    val brand = brands?.match(value)?.takeIf { !it.payment }
                    cands += Cand(value, 220, brand)
                }
                continue
            }
            val letters = raw.count { it.isLetter() }
            if (letters < 3 || letters < raw.length / 2) continue
            if (uiChrome.any { l.startsWith(it) || l == it.trim() }) continue
            if (streetRegex.containsMatchIn(raw) || phoneRegex.containsMatchIn(raw) || timeRegex.containsMatchIn(l)) continue
            if (signedAmountsIn(raw).isNotEmpty() && letters < 6) continue
            if (isPaymentBrand(raw)) continue // the bank's / card's own name in a banking app header
            var score = 40
            val brand = brands?.match(raw)?.takeIf { !it.payment }
            if (brand != null) score += 70
            val size = sizes.getOrElse(i) { 1f }
            score += ((size - 1f) * 50f).toInt().coerceIn(-20, 100)
            when (doc) {
                DocType.RECEIPT, DocType.FUEL_PUMP -> score += (12 - i * 3).coerceAtLeast(-30) // store name is at the top
                DocType.BANK_APP -> {
                    score -= i // mild
                    if (amountLine != null && kotlin.math.abs(i - amountLine) <= 2) score += 45
                }
            }
            // Mostly-digits or very long lines are rarely the merchant.
            if (raw.length > 40) score -= 30
            if (raw.any { it.isDigit() }) score -= 10
            cands += Cand(raw, score, brand)
        }
        if (cands.isEmpty()) {
            val b = brands?.findInText(lines.filterNot(::isPaymentBrand)) ?: return null
            return MerchantPick(b.name, b)
        }
        val best = cands.maxBy { it.score }
        val brand = best.brand ?: brands?.match(cleanMerchantName(best.text))?.takeIf { !it.payment }
        return MerchantPick(brand?.name ?: cleanMerchantName(best.text), brand)
    }

    private fun titleCase(s: String): String =
        if (s == s.uppercase() && s.length > 3) s.lowercase().split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } } else s
}
