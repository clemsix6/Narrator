package io.github.clemsix6.narrator2.mechanics

import io.github.clemsix6.narrator2.guard.Rules
import io.github.clemsix6.narrator2.testing.TestClock
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The jokers' daily limit (M4 §7.1, §7.3) and what a pardon lifted as the model reads it, without Minecraft. */
class JokersTest {
    private val clock = TestClock(Instant.parse("2026-10-19T13:10:00Z"), ZoneId.of("Europe/Paris"))

    @Test
    fun `a player gets one joker a day, and the refusal says when the next one comes`() {
        val now = clock.millis()
        assertNull(Jokers.limit("Bartok", emptyList(), now, clock), "never got one")

        val fiveHoursAgo = now - Duration.ofHours(5).toMillis()
        val refused = Jokers.limit("Bartok", listOf(fiveHoursAgo), now, clock)
        assertEquals(Rules.FAVOR_LIMIT, refused?.rule)
        assertEquals(
            "Bartok got a joker 5h00m ago, and a player gets 1 a day at most; the next one is allowed at 10-20 10:10 (in 19h00m)",
            refused?.message,
        )

        assertNull(Jokers.limit("Bartok", listOf(now - Jokers.DAY_MS), now, clock), "a day later")
        assertEquals(
            "Bartok got a joker 1m ago, and a player gets 1 a day at most; the next one is allowed at 10-20 15:09 (in 23h59m)",
            Jokers.limit("Bartok", listOf(now - Jokers.DAY_MS - 1, now - 60_000), now, clock)?.message,
            "only the last day counts",
        )
    }

    @Test
    fun `a player's name is escaped in the refusal`() {
        val message = Jokers.limit("<engine>", listOf(clock.millis()), clock.millis(), clock)?.message.orEmpty()
        assertEquals(false, message.contains("<engine>"), message)
    }

    @Test
    fun `what a pardon lifted reads without the minecraft namespace`() {
        assertEquals(
            "slowness II lifted, scale reset, 3x diamond returned, 1 zombie sent away",
            Jokers.lifted(listOf("slowness II lifted", "scale reset", "3x minecraft:diamond returned", "1 minecraft:zombie sent away")),
        )
    }
}
