package com.jlees.budgey.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Exchange rates against one [base] currency (1 base = rates[code] of that currency), as published
 * by the European Central Bank and other central banks via the free Frankfurter service.
 * [date] is the newest rate date in the table (ISO yyyy-mm-dd).
 */
data class FxTable(val base: String, val date: String, val rates: Map<String, Double>) {
    val currencies: List<String> get() = (rates.keys + base).distinct().sorted()

    /** How many [to] one [from] buys, or null if either currency is unknown. */
    fun rate(from: String, to: String): Double? {
        if (from == to) return 1.0
        val f = if (from == base) 1.0 else rates[from] ?: return null
        val t = if (to == base) 1.0 else rates[to] ?: return null
        return t / f
    }

    /** Converts [amount] (in major units, e.g. 12.5 dollars) from one currency to another. */
    fun convert(amount: BigDecimal, from: String, to: String): BigDecimal? {
        val r = rate(from, to) ?: return null
        return amount.multiply(BigDecimal(r), MathContext.DECIMAL64)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Reads either API shape:
         *  • v2 `/v2/rates`: [{"date":"…","base":"EUR","quote":"USD","rate":1.13}, …]
         *  • v1 `/v1/latest`: {"base":"EUR","date":"…","rates":{"USD":1.13, …}}
         */
        fun parse(text: String): FxTable? = runCatching {
            when (val root = json.parseToJsonElement(text)) {
                is JsonArray -> {
                    var base: String? = null
                    var date = ""
                    val rates = HashMap<String, Double>()
                    root.forEach { el ->
                        val o = el as? JsonObject ?: return@forEach
                        val b = (o["base"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
                        val q = (o["quote"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
                        val r = (o["rate"] as? JsonPrimitive)?.doubleOrNull ?: return@forEach
                        if (base == null) base = b
                        if (b != base || r <= 0.0) return@forEach
                        rates[q] = r
                        val d = (o["date"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                        if (d > date) date = d
                    }
                    base?.let { FxTable(it, date, rates) }
                }
                is JsonObject -> {
                    val base = (root["base"] as? JsonPrimitive)?.contentOrNull ?: return null
                    val date = (root["date"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    val rates = (root["rates"] as? JsonObject)?.mapNotNull { (k, v) ->
                        (v as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 }?.let { k to it }
                    }?.toMap().orEmpty()
                    FxTable(base, date, rates)
                }
                else -> null
            }
        }.getOrNull()?.takeIf { it.rates.isNotEmpty() }

        /** Formats a converted amount with sensible precision (more decimals for tiny rates). */
        fun plain(x: BigDecimal, decimals: Int = 2): String = x.setScale(decimals, RoundingMode.HALF_UP).toPlainString()
    }
}

/** Tip calculator math (cents). Tip amounts round UP to the next cent. */
object TipMath {
    /** What the tip is based on: the bill before tax, or (when [afterTax]) bill + tax. */
    fun base(billCents: Long, taxCents: Long, afterTax: Boolean): Long =
        if (afterTax) billCents + taxCents.coerceAtLeast(0) else billCents

    /** [percent] of [baseCents], rounded up to the cent. */
    fun tipFromPercent(baseCents: Long, percent: Double): Long =
        BigDecimal(baseCents.coerceAtLeast(0)).multiply(BigDecimal(percent)).divide(BigDecimal(100), 0, RoundingMode.CEILING).toLong()

    /** What percentage [tipCents] is of [baseCents] (0 when there's no bill yet). */
    fun percentFromTip(baseCents: Long, tipCents: Long): Double =
        if (baseCents <= 0) 0.0 else BigDecimal(tipCents).multiply(BigDecimal(100)).divide(BigDecimal(baseCents), 4, RoundingMode.HALF_UP).toDouble()

    /** "20", "18.5", "12.75" — no trailing zeros. */
    fun formatPercent(p: Double): String =
        BigDecimal(p).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}
