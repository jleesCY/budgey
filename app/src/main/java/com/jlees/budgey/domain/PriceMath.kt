package com.jlees.budgey.domain

/** The three linked price boxes on a subscription: base + fees & taxes = total charged. */
enum class PriceField { BASE, FEES, TOTAL }

/**
 * Text of the three boxes. Base price is never calculated — it only changes when you type in it.
 *  - Edit base  → fees stay, total = base + fees
 *  - Edit fees  → base stays, total = base + fees
 *  - Edit total → base stays, fees = total − base
 */
data class PriceInputs(
    val base: String = "",
    val fees: String = "",
    val total: String = "",
    val lastEdited: PriceField? = null,
) {
    /** The box that was just calculated (shown as "Calculated"). */
    val derived: PriceField
        get() = if (lastEdited == PriceField.TOTAL) PriceField.FEES else PriceField.TOTAL

    fun text(f: PriceField) = when (f) {
        PriceField.BASE -> base
        PriceField.FEES -> fees
        PriceField.TOTAL -> total
    }

    val baseCents: Long? get() = Money.parse(base)
    val feesCents: Long? get() = Money.parse(fees)
    val totalCents: Long? get() = Money.parse(total)

    companion object {
        fun from(baseCents: Long?, feesCents: Long?, totalCents: Long?) = PriceInputs(
            base = baseCents?.let(Money::toInput) ?: "",
            fees = feesCents?.let(Money::toInput) ?: "",
            total = totalCents?.let(Money::toInput) ?: "",
        )
    }
}

object PriceMath {
    fun edit(state: PriceInputs, field: PriceField, text: String): PriceInputs = when (field) {
        PriceField.BASE -> withTotal(state.copy(base = text, lastEdited = field))
        PriceField.FEES -> withTotal(state.copy(fees = text, lastEdited = field))
        PriceField.TOTAL -> {
            val s = state.copy(total = text, lastEdited = field)
            val base = s.baseCents
            val total = s.totalCents
            when {
                // No base price entered: the total stands on its own.
                base == null -> s
                total == null -> s.copy(fees = "")
                else -> s.copy(fees = Money.toInput(total - base))
            }
        }
    }

    /** total = base + fees (either may be blank; both blank clears the total). */
    private fun withTotal(s: PriceInputs): PriceInputs {
        val base = s.baseCents
        val fees = s.feesCents
        if (base == null && fees == null) return s.copy(total = "")
        return s.copy(total = Money.toInput((base ?: 0) + (fees ?: 0)))
    }
}
