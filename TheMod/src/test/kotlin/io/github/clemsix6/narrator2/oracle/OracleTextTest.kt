package io.github.clemsix6.narrator2.oracle

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** How the Oracle writes ids, places, directions and durations for the model (M1_M2 §12.6). */
class OracleTextTest {
    @Test
    fun `ids lose the minecraft namespace, dimensions get their short names`() {
        assertEquals("diamond_ore", OracleText.bare("minecraft:diamond_ore"))
        assertEquals("create:brass_ore", OracleText.bare("create:brass_ore"))
        assertEquals("overworld", OracleText.dimension("minecraft:overworld"))
        assertEquals("nether", OracleText.dimension("minecraft:the_nether"))
        assertEquals("end", OracleText.dimension("minecraft:the_end"))
        assertEquals("twilight:forest", OracleText.dimension("twilight:forest"))
    }

    @Test
    fun `a dimension written by the model becomes its full id`() {
        assertEquals("minecraft:overworld", OracleText.dimensionId("overworld"))
        assertEquals("minecraft:the_nether", OracleText.dimensionId("Nether"))
        assertEquals("minecraft:the_end", OracleText.dimensionId("end"))
        assertEquals("minecraft:the_nether", OracleText.dimensionId("the_nether"))
        assertEquals("minecraft:the_end", OracleText.dimensionId("minecraft:the_end"))
    }

    @Test
    fun `directions follow the compass, north being negative z`() {
        assertEquals("N", OracleText.compass(0, -10))
        assertEquals("E", OracleText.compass(10, 0))
        assertEquals("S", OracleText.compass(0, 10))
        assertEquals("W", OracleText.compass(-10, 0))
        assertEquals("NE", OracleText.compass(7, -7))
        assertEquals("SW", OracleText.compass(-7, 7))
        assertEquals("N", OracleText.compass(2, -10), "mostly north")
        assertEquals("here", OracleText.compass(0, 0))
    }

    @Test
    fun `a place is seen with its distance, direction and height`() {
        assertEquals("10 m N", OracleText.relative(0, 0, -10))
        assertEquals("12 m SE, 5 below", OracleText.relative(8, -5, 8))
        assertEquals("10 m E, 3 above", OracleText.relative(10, 3, 0))
        assertEquals("3 m W", OracleText.relative(-2, 2, 0), "a small height difference is not told")
        assertEquals("12 below", OracleText.relative(0, -12, 0))
        assertEquals("here", OracleText.relative(0, 1, 0))
    }

    @Test
    fun `durations are compact`() {
        assertEquals("0s", OracleText.duration(-5))
        assertEquals("40s", OracleText.duration(40_999))
        assertEquals("25m", OracleText.duration(25 * 60_000L + 59_000))
        assertEquals("1h12", OracleText.duration(72 * 60_000L))
        assertEquals("3h05", OracleText.duration(185 * 60_000L))
        assertEquals("2d4h", OracleText.duration((52 * 60 + 30) * 60_000L))
        assertEquals("[-3m]", OracleText.ago(180_000))
    }

    @Test
    fun `numbers, levels, lengths and quotes`() {
        assertEquals("14", OracleText.number(14f))
        assertEquals("13.5", OracleText.number(13.5f))
        assertEquals("0.3", OracleText.number(0.33f))
        assertEquals("II", OracleText.level(2))
        assertEquals("X", OracleText.level(10))
        assertEquals("255", OracleText.level(255))
        assertEquals("640 m", OracleText.metres(64_000))
        assertEquals("312.4 km", OracleText.metres(31_240_000))
        assertEquals("42%", OracleText.percent(42, 100))
        assertEquals("0%", OracleText.percent(3, 0))
        assertEquals("\"say \\\"hi\\\" now\"", OracleText.quote("say \"hi\"\nnow"))
        assertEquals("\"abcd…\"", OracleText.quote("abcdefgh", 5))
    }
}
