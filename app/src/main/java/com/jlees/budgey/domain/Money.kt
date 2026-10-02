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

    private fun formatter(): NumberFormat =
        NumberFormat.getCurrencyInstance(Locale.US).apply {
            currency = Currency.getInstance(currencyCode)
        }

    fun format(cents: Long): String = formatter().format(BigDecimal.valueOf(cents, 2))

    /** "$1.2k" style for chart labels and tight spaces. */
    fun formatCompact(cents: Long): String {
        val dollars = cents / 100.0
        val abs = kotlin.math.abs(dollars)
        val sign = if (dollars < 0) "-" else ""
        return when {
            abs >= 1_000_000 -> "$sign$" + trim(abs / 1_000_000) + "M"
            abs >= 10_000 -> "$sign$" + trim(abs / 1_000) + "k"
            else -> format(cents).substringBefore(".").let { if (abs < 100) format(cents) else it }
        }
    }

    private fun trim(v: Double): String =
        BigDecimal(v).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    /** Plain editable text for an amount field: 1234.5 -> "1234.50". */
    fun toInput(cents: Long): String = BigDecimal.valueOf(cents, 2).toPlainString()

    /**
     * Parses user/OCR text like "$1,234.56", "1234", "12.5", "(12.00)" into cents.
     * Returns null if no number can be read.
     */
    fun parse(text: String): Long? {
        var t = text.trim()
        if (t.isEmpty()) return null
        val negative = t.startsWith("-") || (t.startsWith("(") && t.endsWith(")"))
        t = t.replace(Regex("[^0-9.,]"), "")
        if (t.isEmpty()) return null
        // Treat a trailing ",dd" as a decimal comma (e.g. "12,50") when there is no dot.
        t = if (!t.contains('.') && Regex(",\\d{2}$").containsMatchIn(t)) {
            t.substring(0, t.length - 3).replace(",", "") + "." + t.takeLast(2)
        } else {
            t.replace(",", "")
        }
        if (t.count { it == '.' } > 1) {
            val last = t.lastIndexOf('.')
            t = t.substring(0, last).replace(".", "") + t.substring(last)
        }
        val bd = t.toBigDecimalOrNull() ?: return null
        val cents = bd.setScale(2, RoundingMode.HALF_UP).movePointRight(2).toLong()
        return if (negative) -cents else cents
    }
}
