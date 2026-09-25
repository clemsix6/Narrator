package io.github.clemsix6.narrator2.guard

import io.github.clemsix6.narrator2.guard.DeathGuard.Companion.HOUR_TICKS
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The quota of deaths the Narrator may cause (M1_M2 §10.3): counted in the player's own play time (the
 * `PLAY_TIME` stat, in ticks), never in wall-clock time.
 */
class DeathQuotaTest {
    private val start = 500_000L

    @Test
    fun `a kill counts for one played hour, then no more`() {
        val kills = listOf(start)
        assertEquals(listOf(start), DeathGuard.recentKills(kills, start))
        assertEquals(listOf(start), DeathGuard.recentKills(kills, start + HOUR_TICKS - 1), "one tick before the hour is over")
        assertEquals(emptyList(), DeathGuard.recentKills(kills, start + HOUR_TICKS), "a full played hour later")
    }

    @Test
    fun `time away does not count, only time played`() {
        // A kill, then the player logs off for a day: their play time has not moved, so the kill still counts.
        val kills = listOf(start)
        assertEquals(0, DeathGuard.quotaLeft(kills, start + 20 * 60 * 20, cap = 1), "20 minutes played later")
        assertEquals(1, DeathGuard.quotaLeft(kills, start + HOUR_TICKS, cap = 1), "an hour played later")
    }

    @Test
    fun `only the kills of the last played hour use the quota`() {
        val kills = listOf(start - 2 * HOUR_TICKS, start - HOUR_TICKS / 2, start - 10)
        assertEquals(listOf(start - HOUR_TICKS / 2, start - 10), DeathGuard.recentKills(kills, start))
        assertEquals(0, DeathGuard.quotaLeft(kills, start, cap = 2))
        assertEquals(1, DeathGuard.quotaLeft(kills, start + HOUR_TICKS / 2, cap = 2), "the older of the two has left the hour")
    }

    @Test
    fun `a kill recorded after the current play time counts as a kill now`() {
        // The stats file was reset: the kill still expires one played hour from now, not in years.
        val kills = listOf(start)
        val reset = 1_000L
        assertEquals(listOf(reset), DeathGuard.recentKills(kills, reset))
        assertEquals(emptyList(), DeathGuard.recentKills(kills.map { minOf(it, reset) }, reset + HOUR_TICKS))
    }

    @Test
    fun `the quota follows the chaos level`() {
        val quotas = (0..5).map { chaos -> DeathGuard.quotaLeft(emptyList(), start, Caps.of(chaos).narratorKillsPerHour) }
        assertEquals(listOf(0, 0, 0, 1, 1, 2), quotas)
        assertEquals(1, DeathGuard.quotaLeft(listOf(start - 100), start, Caps.of(5).narratorKillsPerHour), "chaos 5: a second death in the hour")
        assertEquals(0, DeathGuard.quotaLeft(listOf(start - 100), start, Caps.of(3).narratorKillsPerHour), "chaos 3: one per hour")
    }

    @Test
    fun `the quota is never negative`() {
        assertEquals(0, DeathGuard.quotaLeft(listOf(start - 3, start - 2, start - 1), start, cap = 1))
    }
}
