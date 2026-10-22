package io.github.clemsix6.narrator2.guard

import io.github.clemsix6.narrator2.config.NarratorConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The caps by chaos level (M1_M2 §10.4, levels 3 to 5 loosened by M7-LOOSEN). */
class CapsTest {
    private val levels = (Caps.MIN_CHAOS..Caps.MAX_CHAOS).map(Caps::of)

    @Test
    fun `the table of the design, level by level`() {
        assertEquals(listOf(false, true, true, true, true, true), levels.map { it.hostileAllowed }, "hostile actions")
        assertEquals(listOf(0, 60, 120, 600, 900, 1_800), levels.map { it.harmfulSeconds }, "harmful seconds")
        assertEquals(listOf(0, 1, 1, 3, 4, 5), levels.map { it.harmfulLevel }, "harmful level")
        assertEquals(listOf(0, 2, 4, 10, 14, 20), levels.map { it.spawnsPerAction }, "spawns per action")
        assertEquals(listOf(0, 4, 8, 24, 32, 48), levels.map { it.livingMobs }, "living creatures")
        assertEquals(listOf(0.0, 1.0, 2.0, 4.0, 4.0, 4.0), levels.map { it.explosionPower }, "explosion power")
        assertEquals(listOf(0.0, 1.0, 2.0, 4.0, 5.0, 5.0), levels.map { it.lightningHearts }, "lightning hearts")
        assertEquals(listOf(0.0, 1.0, 1.5, 2.5, 3.0, 3.0), levels.map { it.launchStrength }, "launch strength")
        assertEquals(listOf(0, 50, 200, 3_000, 5_000, 10_000), levels.map { it.teleportBlocks }, "teleport blocks")
        assertEquals(listOf(0, 8, 16, 64, 128, 128), levels.map { it.confiscatedItems }, "confiscated items")
        assertEquals(listOf(0, 0, 0, 2, 3, 5), levels.map { it.narratorKillsPerHour }, "kills per played hour")
        assertEquals(listOf(0, 2, 3, 5, 5, 5), levels.map { it.maxSeverity }, "severity of a punishment (M4 §6.2)")
        assertEquals(listOf(0, 60, 45, 15, 10, 5), levels.map { it.categoryCooldownMinutes }, "rest of a category, minutes (M4 §6.2)")
        assertEquals(listOf(0, 4, 6, 8, 12, 20), levels.map { it.punishmentsPerHour }, "punishments of one player per hour")
    }

    @Test
    fun `chaos 0 allows nothing hostile`() {
        val calm = Caps.of(0)
        assertFalse(calm.hostileAllowed)
        assertEquals(Caps(false, 0, 0, 0, 0, 0.0, 0.0, 0.0, 0, 0, 0, 0, 0, 0), calm, "every cap is zero")
    }

    @Test
    fun `the severity grows and the rest of a category shortens with the chaos`() {
        levels.drop(1).zipWithNext().forEach { (lower, higher) ->
            assertTrue(lower.maxSeverity <= higher.maxSeverity, "$lower -> $higher")
            assertTrue(lower.categoryCooldownMinutes > higher.categoryCooldownMinutes, "$lower -> $higher")
        }
    }

    @Test
    fun `no cap shrinks when the chaos grows`() {
        levels.zipWithNext().forEach { (lower, higher) ->
            assertTrue(lower.harmfulSeconds <= higher.harmfulSeconds && lower.harmfulLevel <= higher.harmfulLevel, "$lower -> $higher")
            assertTrue(lower.spawnsPerAction <= higher.spawnsPerAction && lower.livingMobs <= higher.livingMobs, "$lower -> $higher")
            assertTrue(lower.explosionPower <= higher.explosionPower && lower.lightningHearts <= higher.lightningHearts, "$lower -> $higher")
            assertTrue(lower.launchStrength <= higher.launchStrength && lower.teleportBlocks <= higher.teleportBlocks, "$lower -> $higher")
            assertTrue(lower.confiscatedItems <= higher.confiscatedItems && lower.narratorKillsPerHour <= higher.narratorKillsPerHour, "$lower -> $higher")
            assertTrue(lower.punishmentsPerHour < higher.punishmentsPerHour, "$lower -> $higher")
        }
    }

    @Test
    fun `the default chaos is 3, two deaths per played hour`() {
        val caps = Caps.of(NarratorConfig().chaos)
        assertEquals(Caps.of(3), caps)
        assertEquals(2, caps.narratorKillsPerHour)
    }

    @Test
    fun `a level out of range is brought back into it`() {
        assertEquals(Caps.of(0), Caps.of(-2))
        assertEquals(Caps.of(5), Caps.of(9))
    }

    /** The tools' schemas bound what can be asked: a cap beyond them could never be reached. */
    @Test
    fun `the caps stay within what the tools take`() {
        levels.forEach {
            assertTrue(it.harmfulSeconds <= 1_800 && it.harmfulLevel <= Caps.BENEFICIAL_LEVEL, "effects at $it")
            assertTrue(it.spawnsPerAction <= 20 && it.explosionPower <= 4.0 && it.lightningHearts <= 5.0 && it.launchStrength <= 3.0, "at $it")
            assertTrue(it.confiscatedItems <= 128, "confiscations at $it")
        }
        assertEquals(900, Caps.BENEFICIAL_SECONDS)
        assertEquals(5, Caps.BENEFICIAL_LEVEL)
    }
}
