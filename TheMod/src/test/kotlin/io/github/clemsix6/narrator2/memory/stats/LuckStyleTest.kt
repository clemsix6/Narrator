package io.github.clemsix6.narrator2.memory.stats

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The two stats that compare a player with the server (M3 §6.1): the luck index and the play style. */
class LuckStyleTest {
    private fun rates(
        hours: Double, mined: Long = 0, rare: Long = 0, absurd: Long = 0, placed: Long = 0, kills: Long = 0, farmed: Long = 0,
    ) = Rates(
        player = UUID.randomUUID(), playedSeconds = (hours * 3_600).toLong(), mined = mined, rare = rare, dug = 0, farmed = farmed,
        absurd = absurd, placed = placed, kills = kills, damage = 0.0, discoveries = 0, caught = 0, treasure = 0, messages = 0,
        metres = 0.0, won = 0, decided = 0, together = 0.0,
    )

    @Test
    fun `quantiles interpolate like DuckDB's quantile_cont`() {
        assertEquals(3.0, quantile(listOf(1.0, 2.0, 3.0, 4.0, 10.0), 0.5))
        assertEquals(2.0, quantile(listOf(4.0, 1.0, 3.0, 2.0, 10.0), 0.25))
        assertEquals(2.5, quantile(listOf(1.0, 2.0, 3.0, 4.0), 0.5))
        assertNull(quantile(emptyList(), 0.5))
    }

    @Test
    fun `luck is 50 plus 12 times the z sum, absurd deaths counting against`() {
        // Rare ores per 1000 blocks 1, 2, 3, 4, 10 (median 3, IQR 2); absurd deaths per hour 0, 0.1, 0.2, 0.3, 0.4 (median 0.2, IQR 0.2).
        val players = listOf(1L, 2L, 3L, 4L, 10L).mapIndexed { i, rare -> rates(10.0, mined = 1_000, rare = rare, absurd = i.toLong()) }
        val luck = players.map { Luck.of(it, players).first }
        // The lucky one: z_rare = (10 - 3) / 2 = 3.5, z_absurd = (0.4 - 0.2) / 0.2 = 1 → 50 + 12 × 2.5 = 80.
        // The first: z_rare = -1, z_absurd = -1 → 50; the median player: 50.
        assertEquals(listOf(50.0, 50.0, 50.0, 50.0, 80.0), luck)
        val unlucky = rates(10.0, mined = 1_000, rare = 0, absurd = 40)
        assertEquals(0.0, Luck.of(unlucky, players + unlucky).first) // clamped
        val detail = Luck.of(players.last(), players).second
        assertEquals("""{"rare_per_1k_mined":10.0,"treasure_fish":null,"challenges_ratio":null,"absurd_deaths_per_h":0.4}""", detail.toString())
    }

    @Test
    fun `no luck is made up under five hours or with fewer than three players`() {
        val players = listOf(1L, 2L, 10L).map { rates(10.0, mined = 1_000, rare = it) }
        assertEquals(50.0 + 12 * (10.0 - 2.0) / 4.5, Luck.of(players.last(), players).first, 0.5) // three players: counted
        assertEquals(50.0, Luck.of(players.last(), players.take(1) + players.last()).first) // two: no z
        val newcomer = rates(4.0, mined = 1_000, rare = 50)
        assertEquals(50.0, Luck.of(newcomer, players + newcomer).first)
    }

    @Test
    fun `style divides each activity by the server's median, the mean when it is 0`() {
        // Per hour: placed 100, 50, 50 (median 50); kills 0, 50, 50 (median 50); farmed 0, 0, 30 (median 0, mean 10).
        val a = rates(2.0, placed = 200)
        val b = rates(2.0, placed = 100, kills = 100)
        val c = rates(2.0, placed = 100, kills = 100, farmed = 60)
        val all = listOf(a, b, c)
        fun shares(player: Rates) = Style.of(player, all)!!.mapValues { it.value.jsonPrimitive.int }
        assertEquals(mapOf("explorer" to 0, "fighter" to 0, "builder" to 100, "miner" to 0, "farmer" to 0, "social" to 0), shares(a))
        assertEquals(50, shares(b)["fighter"])
        assertEquals(50, shares(b)["builder"])
        assertEquals(mapOf("explorer" to 0, "fighter" to 20, "builder" to 20, "miner" to 0, "farmer" to 60, "social" to 0), shares(c))
        assertNull(Style.of(rates(0.5, placed = 100), all)) // under an hour
        assertNull(Style.of(rates(2.0), all)) // did nothing
    }
}
