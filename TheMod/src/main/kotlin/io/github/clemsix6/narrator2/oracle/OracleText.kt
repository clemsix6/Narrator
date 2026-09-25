package io.github.clemsix6.narrator2.oracle

import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.tool.ToolText
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * How the Oracle writes what it reads, for the model: English, compact, ids without `minecraft:`
 * (M1_M2 §12.6). Durations, levels and dimensions are written as everywhere else (`tool/ToolText`). Pure:
 * no game object, usable from any thread.
 */
object OracleText {
    private const val VANILLA = "minecraft:"

    /** An id without the `minecraft:` namespace: `minecraft:diamond_ore` → `diamond_ore`; other namespaces stay. */
    fun bare(id: String): String = id.removePrefix(VANILLA)

    /** `x y z`. */
    fun coords(x: Int, y: Int, z: Int): String = "$x $y $z"

    /**
     * The 8-point compass direction from a point to another (north is -z, east is +x), e.g. `NE`;
     * `here` when both horizontal offsets are 0.
     */
    fun compass(dx: Int, dz: Int): String {
        if (dx == 0 && dz == 0) return "here"
        // Angle from north, clockwise: east is 90°.
        val degrees = Math.toDegrees(atan2(dx.toDouble(), -dz.toDouble()))
        val index = (((degrees + 360 + 22.5) % 360) / 45).toInt()
        return COMPASS[index]
    }

    /**
     * Straight-line distance, rounded to the metre (a block is a metre). Computed in `Double`: the squares
     * of two offsets beyond about 46,000 blocks overflow an `Int` (and gave `sqrt` a negative number).
     */
    fun distance(dx: Int, dy: Int, dz: Int): Int =
        sqrt(dx.toDouble() * dx + dy.toDouble() * dy + dz.toDouble() * dz).roundToInt()

    /**
     * Where a point is seen from another: `12 m NE`, `12 m NE, 5 below`, `here`, `3 above`.
     * The height difference is given when it is 3 blocks or more.
     */
    fun relative(dx: Int, dy: Int, dz: Int): String {
        val flat = compass(dx, dz)
        val vertical = when {
            dy >= VERTICAL_NOTE -> "${dy} above"
            dy <= -VERTICAL_NOTE -> "${-dy} below"
            else -> null
        }
        if (flat == "here") return vertical ?: "here"
        val head = "${distance(dx, dy, dz)} m $flat"
        return if (vertical == null) head else "$head, $vertical"
    }

    /** How long ago, as `[-12m]` in the event lists: `[-40s]`, `[-3h05m]` ([ToolText.duration]). */
    fun ago(ms: Long): String = "[-${ToolText.duration(ms)}]"

    /** Health or another float: `14`, `13.5`. */
    fun number(value: Float): String {
        val rounded = (value * 10).roundToInt() / 10.0
        return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
    }

    /** A share, `42%`. */
    fun percent(part: Long, whole: Long): String = if (whole <= 0) "0%" else "${(part * 100.0 / whole).roundToInt()}%"

    /** A length in blocks (centimetre stats), as km with one decimal above 1 km: `312.4 km`, `640 m`. */
    fun metres(centimetres: Long): String {
        val metres = centimetres / 100
        return if (metres >= 1000) "%.1f km".format(Locale.ROOT, metres / 1000.0) else "$metres m"
    }

    /** A string cut to [max] characters, with `…` when cut. */
    fun cut(text: String, max: Int): String = if (text.length <= max) text else text.take(max - 1) + "…"

    /**
     * Quoted text from a player (an item's or a mob's custom name, a book, a sign), through the common
     * escape of player text ([ModelText.quote]): `"…"`, `\` and `"` escaped, one line, `<` `>` as `‹` `›`,
     * at most [max] characters.
     */
    fun quote(text: String, max: Int = 60): String = ModelText.quote(text, max)

    private const val VERTICAL_NOTE = 3
    private val COMPASS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
}
