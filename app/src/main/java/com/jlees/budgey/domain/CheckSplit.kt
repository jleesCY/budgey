package com.jlees.budgey.domain

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/*
 * Check splitting math (pure Kotlin, unit-tested).
 *
 * Money is in cents. Shares are worked out exactly (fractions of a cent kept) and each person's
 * final total is rounded UP to the next cent, so the group never comes up short; the few cents
 * this can add are reported as [SplitResult.roundingExtraCents].
 */

data class SplitPerson(
    val id: String,
    val name: String,
    /** Whether this person chips in for the tip. */
    val sharesTip: Boolean = true,
    /** Custom split only: their amount (e.g. "12.50") or percentage (e.g. "40"), as typed. */
    val custom: String = "",
)

data class SplitItem(
    val id: String,
    val name: String,
    /** Line total (all units). */
    val priceCents: Long,
    val qty: Int = 1,
    /** Who had it; several people share it evenly. Empty = not assigned yet (shared by everyone). */
    val people: Set<String> = emptySet(),
)

enum class SplitMode(val label: String) { EVEN("Evenly"), CUSTOM("Custom"), ITEMS("By item") }
enum class CustomKind { AMOUNT, PERCENT }

/** Tip as a percentage of the subtotal (before tax), or a fixed amount. */
data class TipSpec(val percent: Double? = 18.0, val amountCents: Long? = null) {
    fun cents(subtotalCents: Long): Long = amountCents
        ?: percent?.let { BigDecimal(subtotalCents).multiply(BigDecimal(it)).divide(BigDecimal(100), 0, RoundingMode.CEILING).toLong() }
        ?: 0L
}

data class Check(
    val items: List<SplitItem> = emptyList(),
    /** Typed subtotal; when null the items' sum is used. */
    val subtotalOverride: Long? = null,
    val taxCents: Long = 0,
    /** Service charges, delivery fees, etc. — split like tax. */
    val feesCents: Long = 0,
    val tip: TipSpec = TipSpec(),
    val people: List<SplitPerson> = emptyList(),
    val mode: SplitMode = SplitMode.EVEN,
    val customKind: CustomKind = CustomKind.AMOUNT,
    /** By item: tip split by how much each tipper ordered (true) or evenly between tippers (false). */
    val tipByOrder: Boolean = true,
) {
    val itemsCents: Long get() = items.sumOf { it.priceCents }
    val subtotalCents: Long get() = if (mode == SplitMode.ITEMS) itemsCents else subtotalOverride ?: itemsCents
    val tipCents: Long get() = tip.cents(subtotalCents)
    /** Everything except the tip. */
    val beforeTipCents: Long get() = subtotalCents + taxCents + feesCents
    val totalCents: Long get() = beforeTipCents + tipCents
}

data class PersonShare(
    val person: SplitPerson,
    /** Their part of the food / items (exact, may include fractions of a cent). */
    val items: BigDecimal,
    /** Their part of tax + fees. */
    val taxAndFees: BigDecimal,
    val tip: BigDecimal,
    /** What they pay: everything above, rounded up to the cent. */
    val totalCents: Long,
    /** By item: the items they had and their part of each. */
    val lines: List<Pair<SplitItem, BigDecimal>> = emptyList(),
)

data class SplitResult(
    val shares: List<PersonShare>,
    val billCents: Long,
    val collectedCents: Long,
    /** Custom split: money not yet given to anyone (negative = over-assigned). */
    val unassignedCents: Long = 0,
    val notes: List<String> = emptyList(),
) {
    /** Extra cents collected because every share is rounded up. */
    val roundingExtraCents: Long get() = collectedCents - (billCents - unassignedCents)
}

object CheckSplitter {
    private val MC = MathContext(20)
    private val ZERO = BigDecimal.ZERO
    private fun dec(cents: Long) = BigDecimal(cents)
    private fun ceil(x: BigDecimal): Long = x.setScale(0, RoundingMode.CEILING).toLong()

    /** Parses "12.50", "$12.50", "12,50" or "40%" → a plain number, or null. */
    fun parseNumber(text: String): BigDecimal? {
        val t = text.trim().removePrefix("$").removeSuffix("%").trim().replace(',', '.')
        if (t.isEmpty()) return null
        return t.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }
    }

    fun split(check: Check): SplitResult {
        val people = check.people
        if (people.isEmpty()) return SplitResult(emptyList(), check.totalCents, 0, notes = listOf("Add at least one person."))
        val notes = mutableListOf<String>()

        // Tip goes to the people with tip sharing on (everyone, if nobody has it on).
        var tippers = people.filter { it.sharesTip }
        if (tippers.isEmpty()) {
            tippers = people
            if (check.tipCents > 0) notes += "Nobody has tip sharing on, so the tip is split between everyone."
        }
        val tipTotal = dec(check.tipCents)

        return when (check.mode) {
            SplitMode.EVEN -> {
                val each = dec(check.beforeTipCents).divide(BigDecimal(people.size), MC)
                val tipEach = tipTotal.divide(BigDecimal(tippers.size), MC)
                val sub = dec(check.subtotalCents).divide(BigDecimal(people.size), MC)
                val shares = people.map { p ->
                    val tip = if (p in tippers) tipEach else ZERO
                    PersonShare(p, sub, each - sub, tip, ceil(each + tip))
                }
                SplitResult(shares, check.totalCents, shares.sumOf { it.totalCents }, notes = notes)
            }

            SplitMode.CUSTOM -> {
                val base = dec(check.beforeTipCents)
                val parts = people.associateWith { p ->
                    val n = parseNumber(p.custom) ?: ZERO
                    if (check.customKind == CustomKind.PERCENT) base.multiply(n).divide(BigDecimal(100), MC)
                    else n.multiply(BigDecimal(100)) // dollars → cents
                }
                val assigned = parts.values.fold(ZERO, BigDecimal::add)
                val unassigned = (base - assigned).setScale(0, RoundingMode.HALF_UP).toLong()
                if (unassigned > 0) notes += "${Money.format(unassigned)} isn't assigned to anyone yet."
                if (unassigned < 0) notes += "Shares add up to ${Money.format(-unassigned)} more than the bill."
                val tipEach = tipTotal.divide(BigDecimal(tippers.size), MC)
                val sub = dec(check.subtotalCents)
                val shares = people.map { p ->
                    val part = parts.getValue(p)
                    // Show their share split into food vs tax & fees in the bill's own proportions.
                    val food = if (base.signum() > 0) part.multiply(sub).divide(base, MC) else ZERO
                    val tip = if (p in tippers) tipEach else ZERO
                    PersonShare(p, food, part - food, tip, ceil(part + tip))
                }
                SplitResult(shares, check.totalCents, shares.sumOf { it.totalCents }, unassignedCents = unassigned, notes = notes)
            }

            SplitMode.ITEMS -> {
                val ids = people.map { it.id }.toSet()
                val itemShare = HashMap<String, BigDecimal>()
                val lines = HashMap<String, MutableList<Pair<SplitItem, BigDecimal>>>()
                val unassigned = check.items.filter { it.people.none { id -> id in ids } }
                if (unassigned.isNotEmpty()) {
                    notes += "${unassigned.size} item${if (unassigned.size == 1) " isn't" else "s aren't"} assigned, so " +
                        "${if (unassigned.size == 1) "it's" else "they're"} shared by everyone."
                }
                check.items.forEach { item ->
                    val who = item.people.filter { it in ids }.ifEmpty { ids.toList() }
                    val part = dec(item.priceCents).divide(BigDecimal(who.size), MC)
                    who.forEach { id ->
                        itemShare[id] = (itemShare[id] ?: ZERO) + part
                        lines.getOrPut(id) { mutableListOf() } += item to part
                    }
                }
                val itemsTotal = dec(check.itemsCents)
                val extras = dec(check.taxCents + check.feesCents)
                // Tip: by what each tipper ordered, or evenly between tippers.
                val tipperFood = tippers.fold(ZERO) { acc, p -> acc + (itemShare[p.id] ?: ZERO) }
                val shares = people.map { p ->
                    val food = itemShare[p.id] ?: ZERO
                    // Tax & fees follow what you ordered.
                    val tax = if (itemsTotal.signum() > 0) extras.multiply(food).divide(itemsTotal, MC) else extras.divide(BigDecimal(people.size), MC)
                    val tip = when {
                        p !in tippers -> ZERO
                        check.tipByOrder && tipperFood.signum() > 0 -> tipTotal.multiply(food).divide(tipperFood, MC)
                        else -> tipTotal.divide(BigDecimal(tippers.size), MC)
                    }
                    PersonShare(p, food, tax, tip, ceil(food + tax + tip), lines[p.id].orEmpty())
                }
                SplitResult(shares, check.totalCents, shares.sumOf { it.totalCents }, notes = notes)
            }
        }
    }

    /** Cents (possibly fractional) → "$12.34", rounded to the nearest cent for display. */
    fun format(x: BigDecimal): String = Money.format(x.setScale(0, RoundingMode.HALF_UP).toLong())
}
