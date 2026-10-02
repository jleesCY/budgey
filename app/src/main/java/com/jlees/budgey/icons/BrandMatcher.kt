package com.jlees.budgey.icons

/** A known merchant/service. Loaded from assets/brands.json — edit that file to add brands. */
data class Brand(
    val id: String,
    val name: String,
    /** ARGB color. */
    val color: Long,
    /** Default category template key (see DefaultCategories), e.g. "streaming". */
    val category: String?,
    /** Usually billed as a subscription. */
    val subscription: Boolean,
    val aliases: List<String>,
    /** Simple Icons slug, if a glyph might exist. */
    val slug: String?,
    /** Can be used as a payment method icon (cards, banks, wallets). */
    val payment: Boolean = false,
    /**
     * The name is an everyday word ("Current", "Ring", "Target", "Zip"), so a single-word name or
     * alias only matches when it's the whole merchant name — "Zip's Car Wash" isn't Zip.
     */
    val strict: Boolean = false,
)

/**
 * Pure-Kotlin matcher (no Android deps, unit-tested) that maps a free-form merchant name
 * — typed by the user, read off a receipt, or copied from a bank statement — to a Brand.
 */
class BrandMatcher(val brands: List<Brand>) {

    private data class Term(val text: String, val brand: Brand, val isName: Boolean) {
        val exactOnly: Boolean = brand.strict && ' ' !in text
    }

    private val terms: List<Term> = brands.flatMap { b ->
        (listOf(b.name) + b.aliases)
            .map(::normalize)
            .filter { it.isNotBlank() }
            .distinct()
            .mapIndexed { i, t -> Term(t, b, i == 0) }
    }

    val byId: Map<String, Brand> = brands.associateBy { it.id }

    /** Best brand for a merchant name, or null. */
    fun match(merchant: String): Brand? {
        val candidates = listOf(cleanMerchant(merchant), normalize(merchant)).filter { it.isNotBlank() }.distinct()
        if (candidates.isEmpty()) return null
        var best: Term? = null
        var bestScore = 0
        for (m in candidates) for (t in terms) {
            val score = score(m, t.text) ?: continue
            if (t.exactOnly && score < 1000) continue
            if (score > bestScore) {
                bestScore = score
                best = t
            }
        }
        return best?.brand
    }

    /**
     * Finds a brand mentioned anywhere in OCR'd text. Earlier lines (receipt headers,
     * email senders) win ties. Short terms (< 4 chars) only count when they are the
     * whole line, to avoid matching random words.
     */
    fun findInText(lines: List<String>): Brand? {
        var best: Brand? = null
        var bestScore = 0
        lines.take(60).forEachIndexed { index, raw ->
            val line = normalize(raw)
            if (line.isBlank()) return@forEachIndexed
            for (t in terms) {
                if ((t.text.length < 4 || t.exactOnly) && line != t.text) continue
                val s = score(line, t.text) ?: continue
                val weighted = s * 10 - index
                if (weighted > bestScore) {
                    bestScore = weighted
                    best = t.brand
                }
            }
        }
        return best
    }

    /** Scores how well [term] matches inside [merchant] (both normalized). Null = no match. */
    private fun score(merchant: String, term: String): Int? {
        if (merchant == term) return 1000 + term.length
        if (term.length < 3) return null
        val padded = " $merchant "
        if (!padded.contains(" $term ")) return null
        return if (merchant.startsWith("$term ")) 100 + term.length else term.length
    }

    companion object {
        private val NON_ALNUM = Regex("[^a-z0-9]+")
        private val NOISE_PREFIXES = listOf("sq ", "tst ", "sp ", "pp ", "paypal ", "dd ", "pos ", "ach ", "debit ", "purchase ", "checkcard ", "google ", "apple pay ")

        fun normalize(s: String): String =
            s.lowercase()
                .replace("+", " plus ")
                .replace("&", " ")
                .replace("'", "")
                .replace("’", "")
                .replace(NON_ALNUM, " ")
                .trim()

        /** Removes bank-statement noise like "SQ *", store numbers, and trailing city/state codes. */
        fun cleanMerchant(s: String): String {
            var m = normalize(s)
            var changed = true
            while (changed) {
                changed = false
                for (p in NOISE_PREFIXES) {
                    if (m.startsWith(p) && m.length > p.length + 2) {
                        m = m.removePrefix(p); changed = true
                    }
                }
            }
            // Drop pure-number tokens (store numbers, dates, card digits).
            return m.split(' ').filter { tok -> tok.isNotEmpty() && !tok.all(Char::isDigit) }.joinToString(" ")
        }
    }
}
