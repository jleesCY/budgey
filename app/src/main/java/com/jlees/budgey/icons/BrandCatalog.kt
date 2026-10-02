package com.jlees.budgey.icons

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.caverock.androidsvg.PreserveAspectRatio
import com.caverock.androidsvg.RenderOptions
import com.caverock.androidsvg.SVG
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.Optional

/** A brand's logo: a single-path monochrome glyph, or an SVG (full color, or mono to be tinted). */
sealed interface BrandIcon {
    class Glyph(val vector: ImageVector) : BrandIcon
    class Svg(val svg: String, val mono: Boolean) : BrandIcon
}

/**
 * Offline brand catalog: names, aliases, colors and default categories from assets/brands.json,
 * logos from assets/brand_icons.json (filled by `./gradlew :app:fetchBrandIcons`). The older
 * assets/brand_glyphs.json (keyed by Simple Icons slug) is still read if present.
 */
class BrandCatalog(context: Context) {
    val matcher: BrandMatcher
    /** brand id → raw icon entry (path or svg). */
    private val iconEntries: Map<String, Pair<String, Boolean?>>
    private val glyphPaths: Map<String, String>
    private val icons = ConcurrentHashMap<String, Optional<BrandIcon>>()
    private val bitmaps = ConcurrentHashMap<String, ImageBitmap>()

    init {
        val assets = context.assets
        val brandsJson = JSONObject(assets.open("brands.json").bufferedReader().use { it.readText() })
        val arr = brandsJson.getJSONArray("brands")
        val brands = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val aliases = o.optJSONArray("aliases")
            Brand(
                id = o.getString("id"),
                name = o.getString("name"),
                color = parseColor(o.optString("color", "#757575")),
                category = o.optString("category").ifBlank { null },
                subscription = o.optBoolean("subscription", false),
                aliases = if (aliases == null) emptyList() else (0 until aliases.length()).map { aliases.getString(it) },
                slug = o.optString("slug").ifBlank { null },
                payment = o.optBoolean("payment", false),
                strict = o.optBoolean("strict", false),
            )
        }
        matcher = BrandMatcher(brands)
        glyphPaths = runCatching {
            val g = JSONObject(assets.open("brand_glyphs.json").bufferedReader().use { it.readText() })
            g.keys().asSequence().associateWith { g.getString(it) }
        }.getOrDefault(emptyMap())
        // id → ("p" + path) or ("s" + svg, mono)
        iconEntries = runCatching {
            val root = JSONObject(assets.open("brand_icons.json").bufferedReader().use { it.readText() })
            val obj = root.getJSONObject("icons")
            obj.keys().asSequence().mapNotNull { id ->
                val e = obj.getJSONObject(id)
                when {
                    e.has("path") -> id to ("p" + e.getString("path") to null)
                    e.has("svg") -> id to ("s" + e.getString("svg") to e.optBoolean("mono", false))
                    else -> null
                }
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    val all: List<Brand> get() = matcher.brands

    /** Only brands that have a real logo — what the icon picker offers (no letter placeholders). */
    val withIcons: List<Brand> by lazy { matcher.brands.filter(::hasIcon).sortedBy { it.name.lowercase() } }

    /** Cards, banks and wallets offered as payment-method icons. */
    val paymentBrands: List<Brand> by lazy { withIcons.filter { it.payment } }

    /** Best payment brand for a typed name ("chase sapphire" → Chase), payment brands only. */
    fun matchPayment(name: String): Brand? = match(name)?.takeIf { it.payment }

    fun byId(id: String): Brand? = matcher.byId[id]

    fun match(name: String): Brand? =
        matchCache.getOrPut(name) { Optional.ofNullable(matcher.match(name)) }.orElse(null)

    /** Resolves the brand behind an item's icon setting (see [IconRef]); null for text/picture/none. */
    fun resolve(brandKey: String?, name: String): Brand? = when (val ref = IconRef.parse(brandKey)) {
        IconRef.Auto -> match(name)
        is IconRef.BrandRef -> matcher.byId[ref.id] ?: match(name)
        else -> null
    }

    // Merchant names repeat a lot (every Starbucks visit), so cache the result per name.
    private val matchCache = ConcurrentHashMap<String, Optional<Brand>>()

    fun hasIcon(brand: Brand): Boolean =
        iconEntries.containsKey(brand.id) || (brand.slug != null && glyphPaths.containsKey(brand.slug))

    /** Kept for the Settings "about" count. */
    fun hasGlyph(brand: Brand): Boolean = hasIcon(brand)

    fun icon(brand: Brand): BrandIcon? = icons.getOrPut(brand.id) {
        val entry = iconEntries[brand.id]
        val icon: BrandIcon? = when {
            entry != null && entry.first.startsWith("p") -> BrandIcon.Glyph(vector(brand.id, entry.first.substring(1)))
            entry != null -> BrandIcon.Svg(entry.first.substring(1), entry.second == true)
            else -> brand.slug?.let { glyphPaths[it] }?.let { BrandIcon.Glyph(vector(brand.id, it)) }
        }
        Optional.ofNullable(icon)
    }.orElse(null)

    /**
     * Outline-style logos (e.g. Arcticons) use hairline strokes that look faint at avatar size;
     * thicken them. Also drops fixed width/height so the logo scales to fill its circle.
     */
    private fun prepareSvg(svg: String): String {
        var out = svg.replace(Regex("""(<svg[^>]*?)\s(width|height)="[^"]*"""")) { it.groupValues[1] }
            .replace(Regex("""(<svg[^>]*?)\s(width|height)="[^"]*"""")) { it.groupValues[1] }
        val outline = out.contains("fill=\"none\"") && out.contains("stroke=")
        if (outline && !out.contains("stroke-width")) {
            val open = out.indexOf('>') + 1
            out = out.substring(0, open) + "<g stroke-width=\"2.6\">" + out.substring(open).replace("</svg>", "</g></svg>")
        }
        return out
    }

    private fun vector(name: String, d: String): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = PathParser().parsePathString(d).toNodes(),
        fill = SolidColor(Color.Black),
    ).build()

    /** Renders an SVG logo to a bitmap once (per brand & size) and caches it. */
    fun svgBitmap(brand: Brand, svg: String, px: Int): ImageBitmap? {
        val size = px.coerceIn(24, 256)
        val key = brand.id + "@" + size
        bitmaps[key]?.let { return it }
        val bmp = runCatching {
            val doc = SVG.getFromString(prepareSvg(svg))
            // Without this, AndroidSVG draws at the file's own width/height (often 16–48 px) in the
            // top-left corner instead of scaling the artwork to fill the bitmap.
            doc.setDocumentWidth("100%")
            doc.setDocumentHeight("100%")
            doc.documentPreserveAspectRatio = PreserveAspectRatio.LETTERBOX
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            doc.renderToCanvas(Canvas(bitmap), RenderOptions.create().viewPort(0f, 0f, size.toFloat(), size.toFloat()))
            bitmap.asImageBitmap()
        }.getOrNull() ?: return null
        bitmaps[key] = bmp
        return bmp
    }

    private fun parseColor(hex: String): Long =
        runCatching { 0xFF000000 or hex.removePrefix("#").toLong(16) }.getOrDefault(0xFF757575)
}
