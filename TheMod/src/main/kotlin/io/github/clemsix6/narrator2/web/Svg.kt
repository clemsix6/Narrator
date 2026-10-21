package io.github.clemsix6.narrator2.web

import java.util.Locale

/*
 * The dashboard's charts (M6 §8.6): inline SVG written through [Html], so that every label is escaped. The CSP's
 * `style-src 'self'` forbids `style="…"`: sizes are SVG attributes (`width`, `height`, `points`), colors come from the
 * stylesheet through classes (`bar`, `line`, `axis`), which follows the light or dark theme.
 */

/** One bar of [bars]: its [label], its [value] (zero or more) and the text written after it ([shown]: `$1.92 · 340 calls`). */
data class Bar(val label: String, val value: Double, val shown: String)

/** Horizontal bars, one per entry of [bars], scaled to the largest value; nothing for an empty list. [title] names the chart. */
fun Html.bars(title: String, bars: List<Bar>) {
    if (bars.isEmpty()) return
    val max = bars.maxOf { it.value }.takeIf { it > 0 } ?: 1.0
    val height = bars.size * ROW + 2 * MARGIN
    element("svg", "class" to "chart", "viewBox" to "0 0 $WIDTH $height", "role" to "img", "aria-label" to title) {
        element("title") { text(title) }
        bars.forEachIndexed { i, bar ->
            val top = MARGIN + i * ROW
            val width = if (bar.value > 0) (bar.value / max * BAR_AREA).coerceAtLeast(1.0) else 0.0
            element("text", "x" to LABEL_WIDTH - 8, "y" to top + TEXT_BASELINE, "text-anchor" to "end", "class" to "label") { text(bar.label) }
            element("rect", "x" to LABEL_WIDTH, "y" to top + (ROW - BAR_HEIGHT) / 2, "width" to number(width), "height" to BAR_HEIGHT, "class" to "bar")
            element("text", "x" to number(LABEL_WIDTH + width + 6), "y" to top + TEXT_BASELINE, "class" to "value") { text(bar.shown) }
        }
    }
}

/**
 * A curve of [values] (zero or more) at equal steps, the oldest first, scaled to the largest; [from] and [to] label the
 * two ends of the time axis, [top] the largest value. Nothing for fewer than two values.
 */
fun Html.curve(title: String, values: List<Double>, from: String, to: String, top: String) {
    if (values.size < 2) return
    val max = values.max().takeIf { it > 0 } ?: 1.0
    val left = 8.0
    val right = WIDTH - 8.0
    val bottom = CURVE_HEIGHT - 20.0
    val step = (right - left) / (values.size - 1)
    val points = values.mapIndexed { i, value -> "${number(left + i * step)},${number(bottom - value / max * (bottom - CURVE_TOP))}" }
    element("svg", "class" to "chart", "viewBox" to "0 0 $WIDTH $CURVE_HEIGHT", "role" to "img", "aria-label" to title) {
        element("title") { text(title) }
        element("line", "x1" to number(left), "y1" to number(bottom), "x2" to number(right), "y2" to number(bottom), "class" to "axis")
        element("polyline", "points" to points.joinToString(" "), "class" to "line")
        element("text", "x" to number(left), "y" to CURVE_TOP - 2, "class" to "label") { text(top) }
        element("text", "x" to number(left), "y" to CURVE_HEIGHT - 4, "class" to "label") { text(from) }
        element("text", "x" to number(right), "y" to CURVE_HEIGHT - 4, "text-anchor" to "end", "class" to "label") { text(to) }
    }
}

/** A coordinate with one decimal, whatever the locale. */
private fun number(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

private const val WIDTH = 720
private const val MARGIN = 4
private const val ROW = 24
private const val BAR_HEIGHT = 16
private const val TEXT_BASELINE = 16
private const val LABEL_WIDTH = 150
private const val BAR_AREA = 400.0
private const val CURVE_HEIGHT = 160
private const val CURVE_TOP = 16.0
