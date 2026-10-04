package com.jlees.budgey.ui.theme

import android.os.Build
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.jlees.budgey.data.AppFont
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import com.jlees.budgey.data.AppSettings
import com.jlees.budgey.data.ThemeMode

/** Expressive leans on bigger, softer corners. */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

private val base = Typography()

/** Default type scale with heavier display/headline weights for the expressive feel. */
val AppTypography = base.copy(
    displayLarge = base.displayLarge.copy(fontWeight = FontWeight.SemiBold),
    displayMedium = base.displayMedium.copy(fontWeight = FontWeight.SemiBold),
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

/** The same see-through 3-button navigation bar backgrounds Android's edge-to-edge default uses. */
private val LightScrim = android.graphics.Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DarkScrim = android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b)

@Composable
fun BudgeyTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    // Status / navigation bar icons follow Budgey's theme, not the phone's: with the phone in dark
    // mode and Budgey set to Light, the clock and battery icons would otherwise be white on white.
    val activity = remember(context) {
        var c: android.content.Context? = context
        while (c is android.content.ContextWrapper && c !is androidx.activity.ComponentActivity) c = c.baseContext
        c as? androidx.activity.ComponentActivity
    }
    androidx.compose.runtime.DisposableEffect(activity, dark) {
        activity?.enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
            navigationBarStyle = androidx.activity.SystemBarStyle.auto(LightScrim, DarkScrim) { dark },
        )
        onDispose { }
    }
    var scheme = if (settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        seedScheme(Color(settings.seedColor), dark)
    }
    if (dark && settings.amoled) scheme = scheme.amoled()

    val typography = remember(settings.font, settings.roundedFont) {
        AppTypography.withFontFamily(
            if (settings.font == AppFont.GOOGLE_SANS_FLEX) AppFonts.googleSansFlex(context, rounded = settings.roundedFont) else null
        )
    }

    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        shapes = AppShapes,
        typography = typography,
    ) {
        // Scale every sp-based size by the in-app text size (multiplies the phone's own font scale).
        val density = LocalDensity.current
        val scaled = remember(density, settings.textSize) {
            Density(density.density, density.fontScale * settings.textSize.scale)
        }
        CompositionLocalProvider(LocalDensity provides scaled, content = content)
    }
}

private fun ColorScheme.amoled() = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF080808),
    surfaceContainer = Color(0xFF101010),
    surfaceContainerHigh = Color(0xFF181818),
    surfaceContainerHighest = Color(0xFF202020),
    surfaceDim = Color.Black,
)

/** Budgey's own color: the green of a wild budgie. */
const val BUDGIE_GREEN = 0xFF3F9B3A

/**
 * Swatches offered in Settings when Material You is off — named after real budgie color
 * varieties (green, sky blue, lutino yellow, violet, cobalt, cinnamon, teal pied…).
 */
val SeedPresets = listOf(
    BUDGIE_GREEN, 0xFF2F7FCF, 0xFFE0A800, 0xFF7B52C2, 0xFF2C4FA3, 0xFF9A6B3C, 0xFF00897B, 0xFFD0507A, 0xFF2E5E4E, 0xFF5D6B7A,
).map { Color(it) }

/**
 * Accent hue offset. A budgie is green with a sunny yellow face, so the accent sits ~65° below
 * the main hue (green → yellow, sky blue → green, violet → blue).
 */
private const val TERTIARY_SHIFT = -65f

/**
 * Lightweight tonal scheme from one seed color using HSL tones. Not a full HCT
 * implementation, but produces coherent, accessible light/dark palettes.
 */
fun seedScheme(seed: Color, dark: Boolean): ColorScheme {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(seed.toArgb(), hsl)
    val h = hsl[0]
    val s = hsl[1].coerceIn(0.35f, 0.85f)
    fun t(hue: Float, sat: Float, light: Float) =
        Color(ColorUtils.HSLToColor(floatArrayOf((hue + 360f) % 360f, sat.coerceIn(0f, 1f), light)))

    val nS = 0.06f // neutral saturation
    val nvS = 0.14f // neutral-variant saturation
    return if (!dark) lightColorScheme(
        primary = t(h, s, 0.38f), onPrimary = Color.White,
        primaryContainer = t(h, s, 0.88f), onPrimaryContainer = t(h, s, 0.12f),
        inversePrimary = t(h, s, 0.78f),
        secondary = t(h, s * 0.35f, 0.40f), onSecondary = Color.White,
        secondaryContainer = t(h, s * 0.4f, 0.89f), onSecondaryContainer = t(h, s * 0.4f, 0.12f),
        tertiary = t(h + TERTIARY_SHIFT, s * 0.7f, 0.38f), onTertiary = Color.White,
        tertiaryContainer = t(h + TERTIARY_SHIFT, s * 0.85f, 0.86f), onTertiaryContainer = t(h + TERTIARY_SHIFT, s * 0.6f, 0.14f),
        background = t(h, nS, 0.985f), onBackground = t(h, nS, 0.10f),
        surface = t(h, nS, 0.985f), onSurface = t(h, nS, 0.10f),
        surfaceVariant = t(h, nvS, 0.90f), onSurfaceVariant = t(h, nvS, 0.30f),
        surfaceTint = t(h, s, 0.38f),
        inverseSurface = t(h, nS, 0.19f), inverseOnSurface = t(h, nS, 0.95f),
        outline = t(h, nvS, 0.50f), outlineVariant = t(h, nvS, 0.80f),
        surfaceBright = t(h, nS, 0.985f), surfaceDim = t(h, nS, 0.87f),
        surfaceContainerLowest = Color.White, surfaceContainerLow = t(h, nS, 0.96f),
        surfaceContainer = t(h, nS, 0.94f), surfaceContainerHigh = t(h, nS, 0.92f),
        surfaceContainerHighest = t(h, nS, 0.90f),
    ) else darkColorScheme(
        primary = t(h, s, 0.78f), onPrimary = t(h, s, 0.18f),
        primaryContainer = t(h, s, 0.28f), onPrimaryContainer = t(h, s, 0.90f),
        inversePrimary = t(h, s, 0.38f),
        secondary = t(h, s * 0.35f, 0.78f), onSecondary = t(h, s * 0.35f, 0.18f),
        secondaryContainer = t(h, s * 0.4f, 0.27f), onSecondaryContainer = t(h, s * 0.4f, 0.90f),
        tertiary = t(h + TERTIARY_SHIFT, s * 0.7f, 0.76f), onTertiary = t(h + TERTIARY_SHIFT, s * 0.55f, 0.18f),
        tertiaryContainer = t(h + TERTIARY_SHIFT, s * 0.6f, 0.28f), onTertiaryContainer = t(h + TERTIARY_SHIFT, s * 0.6f, 0.90f),
        background = t(h, nS, 0.07f), onBackground = t(h, nS, 0.90f),
        surface = t(h, nS, 0.07f), onSurface = t(h, nS, 0.90f),
        surfaceVariant = t(h, nvS, 0.28f), onSurfaceVariant = t(h, nvS, 0.80f),
        surfaceTint = t(h, s, 0.78f),
        inverseSurface = t(h, nS, 0.90f), inverseOnSurface = t(h, nS, 0.19f),
        outline = t(h, nvS, 0.58f), outlineVariant = t(h, nvS, 0.30f),
        surfaceBright = t(h, nS, 0.24f), surfaceDim = t(h, nS, 0.07f),
        surfaceContainerLowest = t(h, nS, 0.04f), surfaceContainerLow = t(h, nS, 0.10f),
        surfaceContainer = t(h, nS, 0.12f), surfaceContainerHigh = t(h, nS, 0.165f),
        surfaceContainerHighest = t(h, nS, 0.21f),
    )
}
