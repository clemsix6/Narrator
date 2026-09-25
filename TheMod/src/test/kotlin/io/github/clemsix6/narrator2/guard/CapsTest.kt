package io.github.clemsix6.narrator2.guard

import io.github.clemsix6.narrator2.config.NarratorConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The caps by chaos level (M1_M2 §10.4). */
class CapsTest {
    private val levels = (Caps.MIN_CHAOS..Caps.MAX_CHAOS).map(Caps::of)

    @Test
    fun `the table of the design, level by level`() {
        assertEquals(listOf(false, true, true, true, true, true), levels.map { it.hostileAllowed }, "hostile actions")
        assertEquals(listOf(0, 60, 120, 300, 600, 900), levels.map { it.harmfulSeconds }, "harmful seconds")
        assertEquals(listOf(0, 1, 1, 2, 3, 4), levels.map { it.harmfulLevel }, "harmful level")
        assertEquals(listOf(0, 2, 4, 6, 10, 12), levels.map { it.spawnsPerAction }, "spawns per action")
        assertEquals(listOf(0, 4, 8, 16, 24, 32), levels.map { it.livingMobs }, "living creatures")
        assertEquals(listOf(0.0, 1.0, 2.0, 3.0, 4.0, 4.0), levels.map { it.explosionPower }, "explosion power")
        assertEquals(listOf(0.0, 1.0, 2.0, 3.0, 4.0, 5.0), levels.map { it.lightningHearts }, "lightning hearts")
        assertEquals(listOf(0.0, 1.0, 1.5, 2.0, 2.5, 3.0), levels.map { it.launchStrength }, "launch strength")
        assertEquals(listOf(0, 50, 200, 1_000, 3_000, 5_000), levels.map { it.teleportBlocks }, "teleport blocks")
        assertEquals(listOf(0, 8, 16, 32, 64, 128), levels.map { it.confiscatedItems }, "confiscated items")
        assertEquals(listOf(0, 0, 0, 1, 1, 2), levels.map { it.narratorKillsPerHour }, "kills per played hour")
    }

    @Test
    fun `chaos 0 allows nothing hostile`() {
        val calm = Caps.of(0)
        assertFalse(calm.hostileAllowed)
        assertEquals(Caps(false, 0, 0, 0, 0, 0.0, 0.0, 0.0, 0, 0, 0), calm, "every cap is zero")
    }

    @Test
    fun `no cap shrinks when the chaos grows`() {
        levels.zipWithNext().forEach { (lower, higher) ->
            assertTrue(lower.harmfulSeconds <= higher.harmfulSeconds && lower.harmfulLevel <= higher.harmfulLevel, "$lower -> $higher")
            assertTrue(lower.spawnsPerAction <= higher.spawnsPerAction && lower.livingMobs <= higher.livingMobs, "$lower -> $higher")
            assertTrue(lower.explosionPower <= higher.explosionPower && lower.lightningHearts <= higher.lightningHearts, "$lower -> $higher")
            assertTrue(lower.launchStrength <= higher.launchStrength && lower.teleportBlocks <= higher.teleportBlocks, "$lower -> $higher")
            assertTrue(lower.confiscatedItems <= higher.confiscatedItems && lower.narratorKillsPerHour <= higher.narratorKillsPerHour, "$lower -> $higher")
        }
    }

    @Test
    fun `the default chaos is 3, one death per played hour`() {
        val caps = Caps.of(NarratorConfig().chaos)
        assertEquals(Caps.of(3), caps)
        assertEquals(1, caps.narratorKillsPerHour)
    }

    @Test
    fun `a level out of range is brought back into it`() {
        assertEquals(Caps.of(0), Caps.of(-2))
        assertEquals(Caps.of(5), Caps.of(9))
    }

    @Test
    fun `harmful caps never exceed what beneficial actions get`() {
        levels.forEach {
            assertTrue(it.harmfulSeconds <= Caps.BENEFICIAL_SECONDS, "seconds at $it")
            assertTrue(it.harmfulLevel <= Caps.BENEFICIAL_LEVEL, "level at $it")
        }
        assertEquals(900, Caps.BENEFICIAL_SECONDS)
        assertEquals(5, Caps.BENEFICIAL_LEVEL)
    }
}
