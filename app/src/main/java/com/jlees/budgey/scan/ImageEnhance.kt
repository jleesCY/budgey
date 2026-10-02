package com.jlees.budgey.scan

/**
 * Pure-Kotlin image clean-up used before OCR (no Android types, so it's unit-tested on the JVM).
 * Works on grayscale pixels (0 = black, 255 = white), row by row.
 *
 * Why: gas-pump / kiosk displays use segmented digits with gaps between the bars, often behind
 * glare. The text reader is trained on solid printed type, so it misreads them. A high-contrast
 * black & white copy with slightly thickened strokes turns "8" made of 7 bars into a solid "8".
 */
object ImageEnhance {

    fun toGray(argb: IntArray): IntArray = IntArray(argb.size) { i ->
        val p = argb[i]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        (r * 299 + g * 587 + b * 114) / 1000
    }

    fun invert(gray: IntArray): IntArray = IntArray(gray.size) { 255 - gray[it] }

    /**
     * Adaptive threshold (Bradley): a pixel is "ink" when it's clearly darker than the average of
     * its neighbourhood. Handles glare and uneven lighting far better than one global cut-off.
     * Returns 0 for ink, 255 for background.
     */
    fun adaptiveThreshold(gray: IntArray, w: Int, h: Int, window: Int = maxOf(15, w / 12), percent: Int = 12): IntArray {
        val integral = LongArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0L
            for (x in 0 until w) {
                rowSum += gray[y * w + x]
                integral[(y + 1) * (w + 1) + (x + 1)] = integral[y * (w + 1) + (x + 1)] + rowSum
            }
        }
        val half = window / 2
        val out = IntArray(gray.size)
        for (y in 0 until h) {
            val y1 = maxOf(0, y - half)
            val y2 = minOf(h - 1, y + half)
            for (x in 0 until w) {
                val x1 = maxOf(0, x - half)
                val x2 = minOf(w - 1, x + half)
                val count = (x2 - x1 + 1) * (y2 - y1 + 1)
                val sum = integral[(y2 + 1) * (w + 1) + (x2 + 1)] - integral[y1 * (w + 1) + (x2 + 1)] -
                    integral[(y2 + 1) * (w + 1) + x1] + integral[y1 * (w + 1) + x1]
                out[y * w + x] = if (gray[y * w + x].toLong() * count * 100 <= sum * (100 - percent)) 0 else 255
            }
        }
        return out
    }

    /** Grows dark strokes by [radius] px in every direction (closes gaps between display segments). */
    fun thickenDark(bin: IntArray, w: Int, h: Int, radius: Int): IntArray {
        if (radius <= 0) return bin
        // Separable min filter: horizontal pass, then vertical pass.
        val tmp = IntArray(bin.size)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var m = 255
                val a = maxOf(0, x - radius)
                val b = minOf(w - 1, x + radius)
                for (k in a..b) if (bin[row + k] < m) { m = bin[row + k]; if (m == 0) break }
                tmp[row + x] = m
            }
        }
        val out = IntArray(bin.size)
        for (x in 0 until w) {
            for (y in 0 until h) {
                var m = 255
                val a = maxOf(0, y - radius)
                val b = minOf(h - 1, y + radius)
                for (k in a..b) if (tmp[k * w + x] < m) { m = tmp[k * w + x]; if (m == 0) break }
                out[y * w + x] = m
            }
        }
        return out
    }

    /** Stroke thickening scaled to the picture: ~1 px per 500 px of width, at least 1. */
    fun strokeRadius(w: Int): Int = (w / 500).coerceIn(1, 4)

    /** Full clean-up: (optionally inverted) gray → adaptive black & white → thicker strokes. */
    fun enhance(gray: IntArray, w: Int, h: Int, invert: Boolean): IntArray {
        val src = if (invert) invert(gray) else gray
        return thickenDark(adaptiveThreshold(src, w, h), w, h, strokeRadius(w))
    }

    fun toArgb(gray: IntArray): IntArray = IntArray(gray.size) { i ->
        val v = gray[i]
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
}
