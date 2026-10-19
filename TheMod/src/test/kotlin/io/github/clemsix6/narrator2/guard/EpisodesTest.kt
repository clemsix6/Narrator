package io.github.clemsix6.narrator2.guard

import io.github.clemsix6.narrator2.testing.TestClock
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The punishments of each player by act (M4 §4.3, §6.1): episodes opened, extended and closed, the hour's
 * punishments, and the categories struck, with a clock that only moves when the test says so (UTC, from
 * 19:30:00). The rules built on them are in `TargetingPolicyTest`.
 */
class EpisodesTest {
    private val clock = TestClock()
    private val episodes = Episodes(clock)
    private val bartok = UUID(0, 1)

    private fun now() = clock.millis()

    private fun advance(minutes: Long) = clock.advance(Duration.ofMinutes(minutes))

    @Test
    fun `an act extends its own episode, another act opens a new one`() {
        episodes.open(bartok, act = 42, until = now() + 60_000)
        episodes.open(bartok, act = 42, until = now() + 120_000)
        assertEquals(Episode(bartok, 42, now() + 120_000), episodes.active(bartok))
        assertEquals(1, episodes.recentPunishments(bartok).size, "extending is not a new punishment")

        // The engine's firing 42 is act -42: not the turn 42.
        episodes.open(bartok, act = -42, until = now() + 30_000)
        assertEquals(Episode(bartok, -42, now() + 120_000), episodes.active(bartok), "the later end wins")
        assertEquals(2, episodes.recentPunishments(bartok).size, "a new punishment")
    }

    @Test
    fun `a caller without an act never extends an episode`() {
        episodes.open(bartok, act = null, until = now() + 60_000)
        episodes.open(bartok, act = null, until = now() + 60_000)
        assertEquals(2, episodes.recentPunishments(bartok).size)
    }

    @Test
    fun `an episode ends with its last effect, or when it is closed`() {
        episodes.open(bartok, act = 7, until = now() + 60_000)
        advance(1)
        assertNull(episodes.active(bartok), "over")
        assertNull(episodes.close(bartok), "nothing left to close")

        episodes.open(bartok, act = 8, until = now() + 60_000)
        assertEquals(Episode(bartok, 8, now() + 60_000), episodes.close(bartok), "a pardon closes it")
        assertNull(episodes.active(bartok))
        assertEquals(2, episodes.recentPunishments(bartok).size, "the hour's punishments are history: they stay")
    }

    @Test
    fun `the hour's punishments leave after an hour`() {
        episodes.open(bartok, act = 1, until = now() + 1_000)
        advance(30)
        episodes.open(bartok, act = 2, until = now() + 1_000)
        assertEquals(2, episodes.recentPunishments(bartok).size)
        assertEquals(1, episodes.recentPunishments(bartok, now() + Duration.ofMinutes(30).toMillis()).size, "at a given time")
        advance(30)
        assertEquals(listOf(now() - Duration.ofMinutes(30).toMillis()), episodes.recentPunishments(bartok))
    }

    @Test
    fun `each category keeps its last strike, the other category none`() {
        episodes.open(bartok, act = 1, until = now() + 60_000, PunishmentCategory.SIZE)
        episodes.open(bartok, act = 1, until = now() + 60_000, PunishmentCategory.OTHER)
        episodes.open(bartok, act = 1, until = now() + 60_000)
        assertEquals(Strike(1, now()), episodes.lastStrike(bartok, PunishmentCategory.SIZE))
        assertNull(episodes.lastStrike(bartok, PunishmentCategory.OTHER), "other never rests")
        assertNull(episodes.lastStrike(bartok, PunishmentCategory.AIR))

        advance(5)
        episodes.open(bartok, act = -9, until = now() + 60_000, PunishmentCategory.SIZE)
        assertEquals(Strike(-9, now()), episodes.lastStrike(bartok, PunishmentCategory.SIZE), "the last one counts")
    }

    @Test
    fun `the resting categories say until when, soonest first`() {
        val rest = Duration.ofMinutes(30).toMillis()
        episodes.open(bartok, act = 1, until = now() + 60_000, PunishmentCategory.SIZE)
        advance(10)
        episodes.open(bartok, act = 2, until = now() + 60_000, PunishmentCategory.AIR)
        val start = now() - Duration.ofMinutes(10).toMillis()
        assertEquals(
            listOf(PunishmentCategory.SIZE to start + rest, PunishmentCategory.AIR to now() + rest),
            episodes.resting(bartok, rest).toList(),
        )
        advance(20)
        assertEquals(mapOf(PunishmentCategory.AIR to now() + Duration.ofMinutes(10).toMillis()), episodes.resting(bartok, rest), "size rests no more")
        assertEquals(emptyMap(), episodes.resting(UUID(0, 2), rest), "a player never struck")
    }

    @Test
    fun `a strike older than the longest rest is forgotten`() {
        episodes.open(bartok, act = 1, until = now() + 60_000, PunishmentCategory.SIZE)
        clock.advance(Duration.ofMillis(Episodes.STRIKES_KEPT_MS))
        episodes.open(bartok, act = 2, until = now() + 60_000, PunishmentCategory.AIR)
        assertNull(episodes.lastStrike(bartok, PunishmentCategory.SIZE))
        assertEquals(60 * 60_000L, Episodes.STRIKES_KEPT_MS, "the rest at chaos 1")
    }
}
