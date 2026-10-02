package com.jlees.budgey.ui.theme

import android.content.Context
import android.graphics.Typeface
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

/**
 * Google Sans Flex is bundled in the APK (assets/fonts) so it works offline — it is NOT a
 * downloadable font. Fetch it once with `./gradlew :app:fetchFonts` (or drop the .ttf in by hand).
 * If the file isn't there, the app quietly falls back to the system font.
 */
object AppFonts {
    const val GOOGLE_SANS_FLEX_ASSET = "fonts/GoogleSansFlex.ttf"

    /** Google Sans Flex's roundness axis: 0 = regular corners, 100 = fully rounded terminals. */
    private const val ROUNDNESS_AXIS = "ROND"

    @Volatile private var available: Boolean? = null
    private val families = HashMap<Boolean, FontFamily>()

    fun isGoogleSansFlexAvailable(context: Context): Boolean {
        available?.let { return it }
        // Validate the file up front so a missing or corrupt font can never crash text layout.
        val ok = runCatching {
            Typeface.createFromAsset(context.applicationContext.assets, GOOGLE_SANS_FLEX_ASSET) != null
        }.getOrDefault(false)
        available = ok
        return ok
    }

    /**
     * The variable font at every weight the app uses (regular or rounded),
     * or null if it isn't bundled / can't load.
     */
    fun googleSansFlex(context: Context, rounded: Boolean = false): FontFamily? {
        if (!isGoogleSansFlexAvailable(context)) return null
        synchronized(families) {
            return families.getOrPut(rounded) {
                val assets = context.applicationContext.assets
                FontFamily(
                    listOf(300, 400, 500, 600, 700, 800).map { w ->
                        // Variable font: one file; weight and roundness axes are set per entry.
                        Font(
                            GOOGLE_SANS_FLEX_ASSET,
                            assets,
                            weight = FontWeight(w),
                            variationSettings = FontVariation.Settings(
                                FontVariation.weight(w),
                                FontVariation.Setting(ROUNDNESS_AXIS, if (rounded) 100f else 0f),
                            ),
                        )
                    }
                )
            }
        }
    }
}

/** Applies [family] to every style in the type scale (null = keep the system font). */
fun Typography.withFontFamily(family: FontFamily?): Typography {
    if (family == null) return this
    fun TextStyle.f() = copy(fontFamily = family)
    return copy(
        displayLarge = displayLarge.f(), displayMedium = displayMedium.f(), displaySmall = displaySmall.f(),
        headlineLarge = headlineLarge.f(), headlineMedium = headlineMedium.f(), headlineSmall = headlineSmall.f(),
        titleLarge = titleLarge.f(), titleMedium = titleMedium.f(), titleSmall = titleSmall.f(),
        bodyLarge = bodyLarge.f(), bodyMedium = bodyMedium.f(), bodySmall = bodySmall.f(),
        labelLarge = labelLarge.f(), labelMedium = labelMedium.f(), labelSmall = labelSmall.f(),
    )
}
