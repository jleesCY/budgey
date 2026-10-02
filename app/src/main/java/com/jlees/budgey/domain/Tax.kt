package com.jlees.budgey.domain

import java.math.BigDecimal
import java.math.RoundingMode

/** Sales-tax math for "advertised price + tax = what you're actually charged". Pure, unit-tested. */
object Tax {
    /** Tax in cents on [listCents] at [percent] (e.g. 7.25), rounded half-up like most card statements. */
    fun taxCents(listCents: Long, percent: Double): Long =
        BigDecimal.valueOf(listCents)
            .multiply(BigDecimal.valueOf(percent))
            .divide(BigDecimal(100), 0, RoundingMode.HALF_UP)
            .toLong()

    fun totalCents(listCents: Long, percent: Double): Long = listCents + taxCents(listCents, percent)

    /** "7", "7.25", "8.875%", "7,5" → percent. Null if blank/invalid/out of range (0–30%). */
    fun parsePercent(text: String): Double? {
        val t = text.trim().removeSuffix("%").trim().replace(',', '.')
        if (t.isEmpty()) return null
        val v = t.toBigDecimalOrNull() ?: return null
        if (v < BigDecimal.ZERO || v > BigDecimal(30)) return null
        return v.toDouble()
    }

    /** 7.0 → "7", 8.875 → "8.875". */
    fun formatPercent(percent: Double): String =
        BigDecimal.valueOf(percent).stripTrailingZeros().toPlainString()
}
