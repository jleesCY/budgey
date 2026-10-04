package com.jlees.budgey.ui.components

import androidx.compose.animation.core.Animatable
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.asin
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.DonutLarge
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.Surface
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.ui.composed
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jlees.budgey.data.ChartType
import com.jlees.budgey.domain.Money
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sqrt

/** One wedge / bar. [key] is a category id (null = uncategorized). */
data class ChartSlice(val key: String?, val label: String, val value: Long, val color: Color)

private val shortDate = DateTimeFormatter.ofPattern("MMM d")

/**
 * The big, centered overview chart at the top of the Purchases tab. The total is shown by the
 * screen right underneath, so the chart itself focuses on the breakdown.
 * Tapping a slice/bar reports it so the screen can drill into that category.
 */
@Composable
fun SpendingChart(
    type: ChartType,
    slices: List<ChartSlice>,
    daily: List<Pair<LocalDate, Long>>,
    caption: String,
    onSliceClick: (ChartSlice) -> Unit,
    modifier: Modifier = Modifier,
) {
    val positive = remember(slices) { slices.filter { it.value > 0 } }
    val progress = remember { Animatable(0f) }
    val animations = LocalAnimations.current
    LaunchedEffect(positive, type, animations) {
        if (!animations) {
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        progress.snapTo(0f)
        // Material 3 "emphasized decelerate": quick start, long gentle settle.
        progress.animateTo(1f, tween(700, easing = EmphasizedDecelerate))
    }
    // Touch & hold a slice / ring to preview its category; keep holding and slide around to preview
    // others. Letting go ends the preview (a quick tap still opens the category).
    var preview by remember(positive, type) { mutableStateOf<ChartSlice?>(null) }
    val view = LocalView.current
    val picker = SlicePreview(
        onStart = { s -> preview = s; view.chartHaptic(start = true) },
        onChange = { s -> preview = s; view.chartHaptic(start = false) },
        onEnd = { preview = null },
    )
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        when (type) {
            ChartType.DONUT, ChartType.PIE -> {
                val donut = type == ChartType.DONUT
                Box(Modifier.fillMaxWidth(0.72f).widthIn(max = 280.dp).aspectRatio(1f), contentAlignment = Alignment.Center) {
                    PieCanvas(positive, donut, progress = { progress.value }, onSliceClick = onSliceClick, selected = preview, picker = picker)
                    val p = preview
                    when {
                        donut && p != null -> PreviewText(p, positive, Modifier.fillMaxWidth(0.56f))
                        donut -> CenterCaption(positive, caption)
                        p != null -> PreviewCard(p, positive)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Legend(positive, Modifier.fillMaxWidth(), vertical = false, onSliceClick = onSliceClick, selected = preview)
            }
            ChartType.RADIAL -> {
                val rings = positive.take(5)
                Box(Modifier.fillMaxWidth(0.72f).widthIn(max = 280.dp).aspectRatio(1f), contentAlignment = Alignment.Center) {
                    RingsCanvas(rings, { progress.value }, onSliceClick = onSliceClick, selected = preview, picker = picker)
                    preview?.let { PreviewCard(it, rings) }
                }
                Spacer(Modifier.height(16.dp))
                Legend(rings, Modifier.fillMaxWidth(), vertical = false, onSliceClick = onSliceClick, selected = preview)
            }
            ChartType.BARS -> Bars(positive.take(7), { progress.value }, onSliceClick)
            ChartType.TREND -> Column(Modifier.fillMaxWidth()) {
                TrendCanvas(daily, { progress.value }, Modifier.fillMaxWidth().height(200.dp))
                if (daily.size >= 2) Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(daily.first().first.format(shortDate), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text(daily.last().first.format(shortDate), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Picks the chart style. The choice is saved app-wide, so every chart follows it and it survives restarts. */
@Composable
fun ChartTypeButton(current: ChartType, onType: (ChartType) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        FilledTonalIconButton(onClick = { open = true }) { Icon(chartIcon(current), "Chart style: ${current.label}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ChartType.entries.forEach { t ->
                DropdownMenuItem(
                    text = { Text(t.label) },
                    leadingIcon = { Icon(chartIcon(t), null) },
                    trailingIcon = { if (t == current) Icon(Icons.Rounded.Check, null) },
                    onClick = { open = false; onType(t) },
                )
            }
        }
    }
}

fun chartIcon(t: ChartType): ImageVector = when (t) {
    ChartType.DONUT -> Icons.Rounded.DonutLarge
    ChartType.PIE -> Icons.Rounded.PieChart
    ChartType.BARS -> Icons.Rounded.BarChart
    ChartType.TREND -> Icons.Rounded.ShowChart
    ChartType.RADIAL -> Icons.Rounded.DataUsage
}

@Composable
private fun CenterCaption(slices: List<ChartSlice>, caption: String) {
    // Constrained to the donut hole so long captions ellipsize instead of spilling onto the ring.
    Column(Modifier.fillMaxWidth(0.56f), horizontalAlignment = Alignment.CenterHorizontally) {
        val top = slices.firstOrNull()
        val total = slices.sumOf { it.value }
        if (top != null && total > 0) {
            Text("${top.value * 100 / total}%", style = MaterialTheme.typography.displaySmall, color = top.color, maxLines = 1)
            Text(top.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Text("biggest share", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        } else {
            Text(caption, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

/** Material 3 emphasized-decelerate easing. */
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/** Callbacks for touch-and-hold previews on the round charts. */
private class SlicePreview(
    val onStart: (ChartSlice) -> Unit,
    val onChange: (ChartSlice) -> Unit,
    val onEnd: () -> Unit,
)

/**
 * Short, crisp haptics for the chart preview: a firm tap when a category is picked up, then a
 * light tick each time the finger slides onto a different one. (Android skips them when touch
 * feedback is off in system settings.)
 */
private fun View.chartHaptic(start: Boolean) {
    val type = when {
        start -> HapticFeedbackConstants.VIRTUAL_KEY
        Build.VERSION.SDK_INT >= 34 -> HapticFeedbackConstants.SEGMENT_TICK
        else -> HapticFeedbackConstants.CLOCK_TICK
    }
    performHapticFeedback(type)
}

/**
 * Tap = [onTap] the slice under the finger. Touch & hold = preview it, then keep holding and drag
 * to preview whichever slice is under the finger ([pick] with start = false may be more forgiving,
 * e.g. by angle only). While previewing, the gesture is consumed so the list doesn't scroll; a drag
 * that starts before the hold kicks in is left alone, so the screen still scrolls normally.
 */
private fun Modifier.slicePicker(
    key: Any?,
    pick: (pos: Offset, size: IntSize, start: Boolean) -> ChartSlice?,
    onTap: (ChartSlice) -> Unit,
    picker: SlicePreview,
): Modifier = composed {
    val currentPick by rememberUpdatedState(pick)
    val currentTap by rememberUpdatedState(onTap)
    val currentPicker by rememberUpdatedState(picker)
    pointerInput(key) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // 0 = still holding, 1 = lifted (tap), 2 = moved away / cancelled.
            var outcome = 0
            var last = down.position
            val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (outcome == 0) {
                    val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                    when {
                        c == null -> outcome = 2
                        !c.pressed -> { last = c.position; outcome = if (c.isConsumed) 2 else 1 }
                        (c.position - down.position).getDistance() > viewConfiguration.touchSlop -> outcome = 2
                    }
                }
            } == null
            if (outcome == 1) {
                currentPick(last, size, true)?.let(currentTap)
                return@awaitEachGesture
            }
            if (!held) return@awaitEachGesture
            var current = currentPick(down.position, size, true) ?: return@awaitEachGesture
            currentPicker.onStart(current)
            try {
                while (true) {
                    val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    c.consume()
                    if (!c.pressed) break
                    val s = currentPick(c.position, size, false)
                    if (s != null && s != current) {
                        current = s
                        currentPicker.onChange(s)
                    }
                }
            } finally {
                currentPicker.onEnd()
            }
        }
    }
}

/** Degrees clockwise from 12 o'clock to [pos], 0–360. */
private fun angleAt(pos: Offset, size: IntSize): Float {
    val dx = pos.x - size.width / 2f
    val dy = pos.y - size.height / 2f
    var angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat() + 90f
    if (angle < 0) angle += 360f
    return angle
}

/** The slice at [angle] degrees from 12 o'clock. */
private fun sliceAtAngle(angle: Float, slices: List<ChartSlice>, total: Float): ChartSlice? {
    if (total <= 0f) return null
    var acc = 0f
    for (s in slices) {
        acc += s.value / total * 360f
        if (angle <= acc) return s
    }
    return slices.lastOrNull()
}

/** The category being previewed: its share, name and amount (inside the donut's hole). */
@Composable
private fun PreviewText(s: ChartSlice, slices: List<ChartSlice>, modifier: Modifier = Modifier) {
    val total = slices.sumOf { it.value }.coerceAtLeast(1)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("${s.value * 100 / total}%", style = MaterialTheme.typography.displaySmall, color = s.color, maxLines = 1)
        Text(s.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text(Money.format(s.value), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** The same preview as a floating card, for the pie and rings (no hole to put it in). */
@Composable
private fun PreviewCard(s: ChartSlice, slices: List<ChartSlice>) {
    val total = slices.sumOf { it.value }.coerceAtLeast(1)
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 6.dp,
        modifier = Modifier.widthIn(max = 200.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(s.color))
                Spacer(Modifier.width(6.dp))
                Text(s.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(Money.format(s.value), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text("${s.value * 100 / total}% of spending", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** Which slice (if any) sits at [pos], by angle from 12 o'clock. */
private fun sliceAt(pos: Offset, size: IntSize, slices: List<ChartSlice>, total: Float, hole: Float): ChartSlice? {
    if (total <= 0f) return null
    val c = Offset(size.width / 2f, size.height / 2f)
    val dx = pos.x - c.x
    val dy = pos.y - c.y
    val r = min(size.width, size.height) / 2f
    val dist = sqrt(dx * dx + dy * dy)
    if (dist > r || dist < r * hole) return null
    var angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat() + 90f
    if (angle < 0) angle += 360f
    var acc = 0f
    for (s in slices) {
        acc += s.value / total * 360f
        if (angle <= acc) return s
    }
    return slices.lastOrNull()
}

private fun pointOn(center: Offset, radius: Float, degrees: Float): Offset {
    val rad = Math.toRadians(degrees.toDouble())
    return Offset(center.x + radius * cos(rad).toFloat(), center.y + radius * sin(rad).toFloat())
}

/**
 * Donut / pie in the Material 3 Expressive style:
 *  • donut: a thick ring of separate, fully rounded segments with clear gaps (like Material's
 *    segmented progress indicators); shares too small for a segment become dots so they're never lost;
 *  • pie: wedges pulled slightly apart, each with softly rounded corners.
 */
@Composable
private fun PieCanvas(
    slices: List<ChartSlice>,
    donut: Boolean,
    progress: () -> Float,
    onSliceClick: (ChartSlice) -> Unit,
    selected: ChartSlice?,
    picker: SlicePreview,
) {
    val total = slices.sumOf { it.value }.toFloat()
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    val density = LocalDensity.current
    val gapPx = with(density) { 4.dp.toPx() }
    val cornerPx = with(density) { 10.dp.toPx() }
    val popPx = with(density) { 6.dp.toPx() }
    // While previewing, the other slices fade back so the picked one stands out.
    fun colorOf(s: ChartSlice) = if (selected != null && s != selected) s.color.copy(alpha = 0.3f) else s.color
    Canvas(
        Modifier
            .fillMaxSize()
            .slicePicker(
                key = slices to donut,
                pick = { pos, size, start ->
                    if (start) sliceAt(pos, size, slices, total, hole = if (donut) 0.6f else 0f)
                    else {
                        // Sliding around: only the angle matters, wherever the finger is — except
                        // right at the middle, where the angle jumps around.
                        val c = Offset(size.width / 2f, size.height / 2f)
                        if ((pos - c).getDistance() < min(size.width, size.height) * 0.08f) null
                        else sliceAtAngle(angleAt(pos, size), slices, total)
                    }
                },
                onTap = onSliceClick,
                picker = picker,
            )
    ) {
        val d = min(size.width, size.height)
        val center = Offset(size.width / 2f, size.height / 2f)
        val p = progress()
        if (donut) {
            val stroke = d * 0.13f
            val radius = (d - stroke) / 2f
            val arcTopLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2, radius * 2)
            // No track behind the segments — only an empty ring when there's nothing to show.
            if (total <= 0f) {
                drawArc(empty, 0f, 360f, false, arcTopLeft, arcSize, style = Stroke(stroke))
                return@Canvas
            }
            // Each rounded cap pokes stroke/2 past the arc's end: leave room for both caps plus a gap.
            val capDeg = Math.toDegrees((stroke / 2f / radius).toDouble()).toFloat()
            val gapDeg = Math.toDegrees((gapPx / radius).toDouble()).toFloat()
            val trim = if (slices.size > 1) capDeg * 2 + gapDeg else 0f
            var start = -90f
            slices.forEach { s ->
                val sweep = s.value / total * 360f * p
                val visible = sweep - trim
                if (visible > 0.5f || (trim == 0f && sweep > 0f)) {
                    drawArc(colorOf(s), start + trim / 2, visible.coerceAtLeast(0.1f), false, arcTopLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                } else if (sweep > 0f) {
                    // Too thin for a segment: a dot keeps the category visible.
                    val dot = (stroke / 2f) * (sweep / trim).coerceIn(0.35f, 0.8f) * if (s == selected) 1.4f else 1f
                    drawCircle(colorOf(s), radius = dot, center = pointOn(center, radius, start + sweep / 2))
                }
                start += sweep
            }
        } else {
            val radius = d / 2f
            if (total <= 0f) {
                drawCircle(empty, radius, center)
                return@Canvas
            }
            var start = -90f
            slices.forEach { s ->
                val sweep = s.value / total * 360f * p
                if (sweep > 0f) {
                    val wedge = if (slices.size == 1 || sweep >= 359.9f) {
                        Path().apply { addOval(Rect(center, radius)) }
                    } else roundedWedge(center, radius, start, sweep, gapPx, cornerPx)
                    if (wedge != null) {
                        if (s == selected && slices.size > 1) {
                            // The previewed wedge pops out a little.
                            val out = pointOn(Offset.Zero, popPx, start + sweep / 2f)
                            translate(out.x, out.y) { drawPath(wedge, colorOf(s)) }
                        } else drawPath(wedge, colorOf(s))
                    }
                }
                start += sweep
            }
        }
    }
}

/**
 * One pie wedge with a constant-width gap to its neighbours and every corner rounded (the tip at
 * the center and both outer corners), Material 3 Expressive style. Returns null when the wedge is
 * too thin to draw.
 */
private fun roundedWedge(center: Offset, radius: Float, startDeg: Float, sweepDeg: Float, gap: Float, corner: Float): Path? {
    val half = gap / 2f
    // Trim the outer arc so the gap is the same width all the way out.
    val trim = Math.toDegrees(asin((half / radius).coerceAtMost(1f).toDouble())).toFloat()
    val a0 = startDeg + trim
    val a1 = startDeg + sweepDeg - trim
    if (a1 <= a0) return null
    // Tip: where the two edges (each moved half a gap inward) meet. Wide wedges keep the center.
    val tipDistance = if (sweepDeg < 180f) (half / sin(Math.toRadians(sweepDeg / 2.0)).toFloat()).coerceAtMost(radius * 0.5f) else 0f
    val tip = pointOn(center, tipDistance, startDeg + sweepDeg / 2f)
    val p1 = pointOn(center, radius, a0)
    val p2 = pointOn(center, radius, a1)
    val edge = (p1 - tip).getDistance()
    if (edge <= 0f) return null
    val d1 = (p1 - tip) / edge
    val d2 = (p2 - tip) / (p2 - tip).getDistance()
    val arcLength = Math.toRadians((a1 - a0).toDouble()).toFloat() * radius
    val r = minOf(corner, edge * 0.35f, arcLength * 0.35f)
    val rTip = minOf(corner * 0.6f, edge * 0.3f)
    val rDeg = Math.toDegrees((r / radius).toDouble()).toFloat()
    val tipOut = tip + d1 * rTip
    val tipIn = tip + d2 * rTip
    val bounds = Rect(center, radius)
    return Path().apply {
        moveTo(tipOut.x, tipOut.y)
        val beforeP1 = p1 - d1 * r
        lineTo(beforeP1.x, beforeP1.y)
        val afterP1 = pointOn(center, radius, a0 + rDeg)
        quadraticTo(p1.x, p1.y, afterP1.x, afterP1.y)
        arcTo(bounds, a0 + rDeg, (a1 - a0) - rDeg * 2, forceMoveTo = false)
        val afterP2 = p2 - d2 * r
        quadraticTo(p2.x, p2.y, afterP2.x, afterP2.y)
        lineTo(tipIn.x, tipIn.y)
        quadraticTo(tip.x, tip.y, tipOut.x, tipOut.y)
        close()
    }
}

/**
 * Concentric rings, one per top category, styled like Material 3 Expressive circular progress
 * indicators: a rounded indicator showing the category's share, a gap, then a tonal track in
 * the ring's own color.
 */
@Composable
private fun RingsCanvas(
    slices: List<ChartSlice>,
    progress: () -> Float,
    onSliceClick: (ChartSlice) -> Unit,
    selected: ChartSlice?,
    picker: SlicePreview,
) {
    val sum = slices.sumOf { it.value }.toFloat()
    val gapPx = with(LocalDensity.current) { 4.dp.toPx() }
    Canvas(
        Modifier
            .fillMaxSize()
            .slicePicker(
                key = slices,
                pick = { pos, size, start ->
                    // Each ring is a band of the radius (same layout as the drawing below).
                    val d = min(size.width, size.height).toFloat()
                    val n = slices.size.coerceAtLeast(1)
                    val band = d / 2f * 0.8f / n
                    val dist = (pos - Offset(size.width / 2f, size.height / 2f)).getDistance()
                    val i = ((d / 2f - dist) / band).toInt()
                    when {
                        dist <= d / 2f && i in slices.indices -> slices[i]
                        // Sliding past the outer or inner ring keeps the nearest one.
                        !start && dist > d / 2f -> slices.firstOrNull()
                        !start && i >= slices.size -> slices.lastOrNull()
                        else -> null
                    }
                },
                onTap = onSliceClick,
                picker = picker,
            )
    ) {
        val d = min(size.width, size.height)
        val center = Offset(size.width / 2f, size.height / 2f)
        val p = progress()
        val n = slices.size.coerceAtLeast(1)
        // Rings fill the outer ~80% of the radius with space between them; the middle stays open.
        val band = d / 2f * 0.8f / n
        val stroke = band * 0.7f
        slices.forEachIndexed { i, s ->
            val radius = d / 2f - stroke / 2f - i * band
            val tl = Offset(center.x - radius, center.y - radius)
            val sz = Size(radius * 2, radius * 2)
            val sweep = if (sum > 0f) 360f * (s.value / sum) * p else 0f
            val capDeg = Math.toDegrees((stroke / 2f / radius).toDouble()).toFloat()
            val gapDeg = Math.toDegrees((gapPx / radius).toDouble()).toFloat()
            // Visible extents (caps included): indicator from 12 o'clock to the share's angle, then a
            // gap, then the track around to just before 12 o'clock again.
            // While previewing, the other rings fade back so the picked one stands out.
            val dim = selected != null && s != selected
            val color = if (dim) s.color.copy(alpha = 0.3f) else s.color
            if (sweep < 360f - 0.5f) {
                val trackStart = -90f + sweep + gapDeg + capDeg
                val trackSweep = 360f - sweep - gapDeg * 2 - capDeg * 2
                if (trackSweep > 0f) drawArc(s.color.copy(alpha = if (dim) 0.1f else 0.24f), trackStart, trackSweep, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            if (sweep > 0f) {
                drawArc(color, -90f + capDeg, (sweep - capDeg * 2).coerceAtLeast(0.1f), false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
    }
}

@Composable
private fun Bars(slices: List<ChartSlice>, progress: () -> Float, onSliceClick: (ChartSlice) -> Unit) {
    val max = slices.maxOfOrNull { it.value }?.toFloat() ?: 1f
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        slices.forEach { s ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onSliceClick(s) }) {
                Row {
                    Text(s.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(Money.format(s.value), style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().height(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                    Box(
                        Modifier
                            .fillMaxWidth((s.value / max * progress()).coerceIn(0.02f, 1f))
                            .height(12.dp)
                            .clip(CircleShape)
                            .background(s.color)
                    )
                }
            }
        }
        if (slices.isEmpty()) Text("No spending in this period", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Cumulative spending across the period. */
@Composable
private fun TrendCanvas(daily: List<Pair<LocalDate, Long>>, progress: () -> Float, modifier: Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val cumulative = remember(daily) {
        var acc = 0L
        daily.map { acc += it.second; acc }
    }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        for (i in 0..3) {
            val y = h * i / 3f
            drawLine(grid, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        if (cumulative.size < 2) return@Canvas
        val p = progress()
        val max = (cumulative.maxOrNull() ?: 0L).coerceAtLeast(1L).toFloat()
        val points = cumulative.mapIndexed { i, v ->
            Offset(w * i / (cumulative.size - 1), h - (v / max * h * p))
        }
        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        val fill = Path().apply {
            addPath(path)
            lineTo(points.last().x, h)
            lineTo(points.first().x, h)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(line.copy(alpha = 0.35f), line.copy(alpha = 0f))))
        drawPath(path, line, style = Stroke(width = 6f, cap = StrokeCap.Round))
        drawCircle(line, 9f, points.last())
    }
}

/** Legend as small tonal pills (color dot, name, share), tappable to drill in. */
@Composable
fun Legend(
    slices: List<ChartSlice>,
    modifier: Modifier = Modifier,
    vertical: Boolean,
    onSliceClick: (ChartSlice) -> Unit,
    /** The category being previewed on the chart (its chip is highlighted). */
    selected: ChartSlice? = null,
) {
    val total = slices.sumOf { it.value }.coerceAtLeast(1)
    val items: @Composable () -> Unit = {
        slices.take(if (vertical) 6 else 12).forEach { s ->
            Row(
                Modifier
                    .padding(vertical = 3.dp)
                    .clip(CircleShape)
                    .background(if (s == selected) s.color.copy(alpha = 0.28f) else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onSliceClick(s) }
                    .padding(start = 8.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(s.color))
                Spacer(Modifier.width(6.dp))
                Text(s.label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = if (vertical) Modifier.weight(1f, fill = false) else Modifier.widthIn(max = 140.dp))
                Spacer(Modifier.width(6.dp))
                Text("${s.value * 100 / total}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (slices.size > 6 && vertical) Text("+${slices.size - 6} more", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
    }
    if (vertical) Column(modifier) { items() }
    else FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) { items() }
}
