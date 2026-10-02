package com.jlees.budgey.icons

/**
 * What icon an item uses, stored as a short string (purchase/subscription `brandKey`,
 * payment method `icon`):
 *
 *   null              → Auto: detect the brand from the name (merchants only)
 *   "" / "none"       → No brand: show the category icon
 *   "brand:<id>"      → A brand from the catalog (a bare "<id>" is the older form)
 *   "text:#RRGGBB:ABC"→ Custom 1–3 letters on a color
 *   "image:<file>"    → Your own picture (app storage, folder "icons")
 *   "generic:<key>"   → A plain symbol (cash, card, bank…)
 */
sealed interface IconRef {
    data object Auto : IconRef
    data object None : IconRef
    data class BrandRef(val id: String) : IconRef
    data class Text(val text: String, val color: Long) : IconRef
    data class Image(val file: String) : IconRef
    data class Generic(val key: String) : IconRef

    companion object {
        const val MAX_TEXT = 3

        fun parse(key: String?): IconRef = when {
            key == null -> Auto
            key.isEmpty() || key == "none" -> None
            key.startsWith("brand:") -> BrandRef(key.removePrefix("brand:"))
            key.startsWith("image:") -> Image(key.removePrefix("image:"))
            key.startsWith("generic:") -> Generic(key.removePrefix("generic:"))
            key.startsWith("text:") -> {
                val rest = key.removePrefix("text:")
                val color = rest.substringBefore(':', "")
                val text = rest.substringAfter(':', rest)
                Text(text.take(MAX_TEXT), parseColor(color) ?: 0xFF607D8B)
            }
            else -> BrandRef(key) // legacy: bare brand id
        }

        fun text(text: String, color: Long): String =
            "text:#%06X:%s".format(color and 0xFFFFFF, text.trim().take(MAX_TEXT))

        fun brand(id: String) = "brand:$id"

        private fun parseColor(hex: String): Long? =
            hex.removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16)?.let { 0xFF000000 or it }
    }
}
