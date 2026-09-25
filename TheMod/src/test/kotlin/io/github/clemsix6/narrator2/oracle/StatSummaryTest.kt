package io.github.clemsix6.narrator2.oracle

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Stat names as the model writes them, and the summary of `player_stats`. */
class StatSummaryTest {
    @Test
    fun `the model's stat names become the game's full names`() {
        assertEquals("minecraft.custom:minecraft.deaths", StatSummary.fullName("deaths"))
        assertEquals("minecraft.custom:minecraft.deaths", StatSummary.fullName("custom:deaths"))
        assertEquals("minecraft.mined:minecraft.diamond_ore", StatSummary.fullName("Mined:Diamond_Ore"))
        assertEquals("minecraft.mined:minecraft.stone", StatSummary.fullName("minecraft.mined:minecraft.stone"))
        assertEquals("minecraft.killed_by:minecraft.player", StatSummary.fullName("killed_by:player"))
        assertEquals("minecraft.used:minecraft.ender_pearl", StatSummary.fullName("used:minecraft:ender_pearl"))
        assertEquals("create.custom:create.wrenched", StatSummary.fullName("create.custom:create.wrenched"), "another mod's stat type")
        assertNull(StatSummary.fullName("walked:stone"), "not a stat type")
        assertNull(StatSummary.fullName("mined:"))
    }

    @Test
    fun `full names are shown without the minecraft namespace`() {
        assertEquals("mined:stone", StatSummary.short("minecraft.mined:minecraft.stone"))
        assertEquals("custom:play_time", StatSummary.short("minecraft.custom:minecraft.play_time"))
        assertEquals("create.custom:create.wrenched", StatSummary.short("create.custom:create.wrenched"))
    }

    @Test
    fun `the summary covers time, deaths, kills, mining, building, travels, damage and feats`() {
        val stats = mapOf(
            "minecraft.custom:minecraft.play_time" to 72_000L * 41 + 14_400, // 41 h 12 min in ticks: 1d17h
            "minecraft.custom:minecraft.deaths" to 17L,
            "minecraft.custom:minecraft.time_since_death" to 72_000L * 2, // 2 hours
            "minecraft.custom:minecraft.mob_kills" to 1_204L,
            "minecraft.custom:minecraft.player_kills" to 3L,
            "minecraft.killed_by:minecraft.player" to 2L,
            "minecraft.mined:minecraft.stone" to 40_000L,
            "minecraft.mined:minecraft.diamond_ore" to 12L,
            "minecraft.mined:minecraft.deepslate_diamond_ore" to 200L,
            "minecraft.mined:minecraft.ancient_debris" to 31L,
            "minecraft.used:minecraft.cobblestone" to 18_000L,
            "minecraft.used:minecraft.glass" to 400L,
            "minecraft.used:minecraft.ender_pearl" to 55L,
            "minecraft.custom:minecraft.walk_one_cm" to 20_000_000L,
            "minecraft.custom:minecraft.sprint_one_cm" to 8_000_000L,
            "minecraft.custom:minecraft.aviate_one_cm" to 4_000_000L,
            "minecraft.custom:minecraft.fall_one_cm" to 9_000_000L,
            "minecraft.custom:minecraft.damage_dealt" to 34_120L,
            "minecraft.custom:minecraft.damage_taken" to 16_080L,
            "minecraft.custom:minecraft.traded_with_villager" to 54L,
            "minecraft.custom:minecraft.raid_win" to 1L,
        )
        val blocks = setOf("minecraft:cobblestone", "minecraft:glass")

        assertEquals(
            listOf(
                "play time 1d17h · deaths 17 (last 2h00m of play ago)",
                "kills: 1204 mobs, 3 players · killed by players 2 times",
                "mined 40243 blocks (diamond ore 212, ancient debris 31)",
                "placed about 18400 blocks",
                "travelled 320.0 km (walk 200.0 km, sprint 80.0 km, elytra 40.0 km)",
                "damage dealt 1706 hearts, taken 804 hearts",
                "trades 54 · raids won 1",
            ),
            StatSummary.summary(stats) { it in blocks },
        )
    }

    @Test
    fun `a player without stats gets a summary of zeros`() {
        assertEquals(
            listOf(
                "play time 0s · never died",
                "kills: 0 mobs, 0 players · killed by players 0 times",
                "mined 0 blocks (diamond ore 0, ancient debris 0)",
                "placed about 0 blocks",
                "damage dealt 0 hearts, taken 0 hearts",
            ),
            StatSummary.summary(emptyMap()) { false },
        )
    }
}
