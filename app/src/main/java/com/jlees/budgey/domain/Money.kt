package com.jlees.budgey.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * All money is stored as Long minor units (cents) to avoid floating point drift.
 * Currency is fixed to USD for now, but every formatter goes through here so
 * multi-currency can be added later in one place.
 */
object Money {
    var currencyCode: String = "USD"

    // NumberFormat is slow to create and not thread-safe: one per thread, rebuilt if the currency changes.
    private val formatters = ThreadLocal<Pair<String, NumberFormat>>()

    private fun formatter(): NumberFormat {
        formatters.get()?.let { (code, f) -> if (code == currencyCode) return f }
        val f = NumberFormat.getCurrencyInstance(Locale.US).apply { currency = Currency.getInstance(currencyCode) }
        formatters.set(currencyCode to f)
        return f
    }

    fun format(cents: Long): String = formatter().format(BigDecimal.valueOf(cents, 2))

    /** "$1.2k" style for chart labels and tight spaces. Rounds to the nearest dollar (or 0.1k / 0.1M). */
    fun formatCompact(cents: Long): String {
        val abs = BigDecimal.valueOf(kotlin.math.abs(cents), 2)
        val sign = if (cents < 0) "-" else ""
        return when {
            abs >= BigDecimal(1_000_000) -> "$sign$" + trim(abs.divide(BigDecimal(1_000_000))) + "M"
            abs >= BigDecimal(10_000) -> "$sign$" + trim(abs.divide(BigDecimal(1_000))) + "k"
            abs >= BigDecimal(100) -> format(abs.setScale(0, RoundingMode.HALF_UP).movePointRight(2).toLong() * if (cents < 0) -1 else 1).substringBefore(".")
            else -> format(cents)
        }
    }

    private fun trim(v: BigDecimal): String = v.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    /** Plain editable text for an amount field: 1234.5 -> "1234.50". */
    fun toInput(cents: Long): String = BigDecimal.valueOf(cents, 2).toPlainString()

    private val minusSigns = "-−–"

    /**
     * Parses typed or scanned text into cents. Understands:
     * - "$1,234.56", "1234", "12.5", "12,50" / "4,5" (decimal comma), "1.234,56" and "1 234,56"
     *   (European grouping), "1'234.56" (Swiss): when both "." and "," appear, the last one is the
     *   decimal point; a lone "," followed by 1–2 digits is a decimal comma, by 3 digits a thousands
     *   separator ("1,234" = 1234);
     * - negatives: "-5", "−5", "$-5", "(5.00)", and a trailing minus as on receipts ("5.00-").
     * Returns null if no number can be read (or it's absurdly large).
     */
    fun parse(text: String): Long? {
        var t = text.trim()
        if (t.isEmpty()) return null
        // Currency symbols / codes don't matter here; signs and brackets do.
        val core = t.replace(Regex("""(?i)usd|us\$|[$€£¥\s\u00A0]"""), "")
        val negative = (core.isNotEmpty() && core.first() in minusSigns) ||
            (core.startsWith("(") && core.endsWith(")")) ||
            (core.length > 1 && core.last() in minusSigns && core[core.length - 2].isDigit())
        // Grouping spaces / apostrophes between digits ("1 234,56", "1'234.56").
        t = t.replace(Regex("""(?<=\d)[\s\u00A0\u202F'’](?=\d{3}\b)"""), "")
        t = t.replace(Regex("[^0-9.,]"), "")
        if (t.isEmpty() || t.none { it.isDigit() }) return null
        val lastDot = t.lastIndexOf('.')
        val lastComma = t.lastIndexOf(',')
        val decimalAt = when {
            lastDot >= 0 && lastComma >= 0 -> maxOf(lastDot, lastComma)
            lastComma >= 0 -> {
                val after = t.length - lastComma - 1
                if (after in 1..2) lastComma else -1 // "4,5" / "12,50" vs "1,234"
            }
            lastDot >= 0 -> {
                val after = t.length - lastDot - 1
                // Several dots with three digits at the end ("1.234.567") are grouping, not decimals.
                if (t.count { it == '.' } > 1 && after == 3) -1 else lastDot
            }
            else -> -1
        }
        val whole = (if (decimalAt >= 0) t.substring(0, decimalAt) else t).filter { it.isDigit() }
        val frac = if (decimalAt >= 0) t.substring(decimalAt + 1).filter { it.isDigit() } else ""
        if (whole.length > 13) return null // more than a trillion dollars: a misread, not money
        val bd = "${whole.ifEmpty { "0" }}.${frac.ifEmpty { "0" }}".toBigDecimalOrNull() ?: return null
        val cents = runCatching { bd.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact() }.getOrNull() ?: return null
        return if (negative) -cents else cents
    }
}
