package io.github.clemsix6.narrator2.oracle

import java.util.Locale
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * How the Oracle writes what it reads, for the model: English, compact, ids without `minecraft:`,
 * dimensions `overworld`, `nether`, `end` (M1_M2 §12.6). Pure: no game object, usable from any thread.
 */
object OracleText {
    private const val VANILLA = "minecraft:"

    /** An id without the `minecraft:` namespace: `minecraft:diamond_ore` → `diamond_ore`; other namespaces stay. */
    fun bare(id: String): String = id.removePrefix(VANILLA)

    /** A dimension's short name: `overworld`, `nether`, `end`, or the bare id of another dimension. */
    fun dimension(id: String): String = when (id) {
        "minecraft:the_nether" -> "nether"
        "minecraft:the_end" -> "end"
        else -> bare(id)
    }

    /** The full id of a dimension written by the model: `nether`, `the_nether`, `minecraft:the_nether`… */
    fun dimensionId(name: String): String = when (val lower = name.trim().lowercase()) {
        "overworld", "nether", "end" -> if (lower == "overworld") "minecraft:overworld" else "minecraft:the_$lower"
        else -> if (':' in lower) lower else VANILLA + lower
    }

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

    /** Straight-line distance, rounded to the metre (a block is a metre). */
    fun distance(dx: Int, dy: Int, dz: Int): Int = sqrt((dx * dx + dy * dy + dz * dz).toDouble()).roundToInt()

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

    /** A duration as the situation writes it: `40s`, `25m`, `1h12`, `3d4h`. */
    fun duration(ms: Long): String {
        val seconds = (ms / 1000).coerceAtLeast(0)
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m"
            seconds < 86_400 -> "${seconds / 3600}h%02d".format((seconds % 3600) / 60)
            else -> "${seconds / 86_400}d${(seconds % 86_400) / 3600}h"
        }
    }

    /** How long ago, as `[-12m]` in the event lists: `[-40s]`, `[-3h05]`. */
    fun ago(ms: Long): String = "[-${duration(ms)}]"

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

    /** Roman numerals for effect and enchantment levels 1-10; beyond, digits. */
    fun level(level: Int): String = if (level in 1..ROMAN.size) ROMAN[level - 1] else level.toString()

    /** A string cut to [max] characters, with `…` when cut. */
    fun cut(text: String, max: Int): String = if (text.length <= max) text else text.take(max - 1) + "…"

    /** Quoted text from a player or an item name: `"…"`, `"` escaped, one line. */
    fun quote(text: String, max: Int = 60): String = "\"" + cut(text.replace('\n', ' ').replace("\"", "\\\""), max) + "\""

    private const val VERTICAL_NOTE = 3
    private val COMPASS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    private val ROMAN = listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X")
}
