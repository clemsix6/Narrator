package io.github.clemsix6.narrator2.tool.speech

import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.guard.Verdict
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.tool.Caller
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The anti-spam limits of the speech tools (M1_M2 §9.1), on a clock that only moves when told. */
class SpeechLimiterTest {
    private val clock = TestClock()
    private val limiter = SpeechLimiter(clock)
    private val bob = PlayerRef(UUID.randomUUID(), "Bob")
    private val alice = PlayerRef(UUID.randomUUID(), "Alice")

    private fun turn(id: Long) = Caller(Role.DIRECTOR, "director-1", turnId = id, taskId = null)

    private fun denied(verdict: Verdict): String {
        assertIs<Verdict.Denied>(verdict)
        assertEquals(SpeechLimiter.RULE, verdict.rule)
        return verdict.message
    }

    @Test
    fun `a turn says at most 4 lines, and the next turn starts afresh`() {
        repeat(4) { assertEquals(Verdict.Allowed, limiter.line(turn(1))) }

        assertContains(denied(limiter.line(turn(1))), "4 lines already this turn")
        assertEquals(Verdict.Allowed, limiter.line(turn(2)), "a new turn")
    }

    @Test
    fun `at most 8 lines in any minute, whatever the turns`() {
        repeat(4) { assertEquals(Verdict.Allowed, limiter.line(turn(1))) }
        clock.advance(Duration.ofSeconds(20))
        repeat(4) { assertEquals(Verdict.Allowed, limiter.line(turn(2))) }

        assertContains(denied(limiter.line(turn(3))), "8 lines already in the last minute; the next is possible in 40s")
        clock.advance(Duration.ofSeconds(39))
        assertContains(denied(limiter.line(turn(3))), "the next is possible in 1s")
        clock.advance(Duration.ofSeconds(1))
        repeat(4) { assertEquals(Verdict.Allowed, limiter.line(turn(3)), "the first four lines are a minute old") }
        denied(limiter.line(turn(4)))
    }

    @Test
    fun `a refusal counts nothing`() {
        repeat(4) { limiter.line(turn(1)) }
        repeat(10) { denied(limiter.line(turn(1))) }

        assertEquals(Verdict.Allowed, limiter.line(turn(2)))
        repeat(3) { assertEquals(Verdict.Allowed, limiter.line(turn(2))) }
        denied(limiter.line(turn(3)))
    }

    @Test
    fun `the same turn number in another conversation is another turn`() {
        repeat(4) { limiter.line(turn(1)) }

        assertEquals(Verdict.Allowed, limiter.line(Caller(Role.DIRECTOR, "director-2", turnId = 1, taskId = null)))
    }

    @Test
    fun `at most 10 sounds in any minute`() {
        repeat(10) {
            assertEquals(Verdict.Allowed, limiter.sound())
            clock.advance(Duration.ofSeconds(1))
        }

        assertContains(denied(limiter.sound()), "10 sounds already in the last minute; the next is possible in 50s")
        clock.advance(Duration.ofSeconds(50))
        assertEquals(Verdict.Allowed, limiter.sound())
    }

    @Test
    fun `one title per player every 30 seconds`() {
        assertEquals(SpeechLimiter.Split(listOf(bob), emptyList()), limiter.titles(listOf(bob)))
        clock.advance(Duration.ofSeconds(12))

        val split = limiter.titles(listOf(bob, alice))
        assertEquals(listOf(alice), split.allowed, "another player is not held back")
        assertEquals(listOf(SpeechLimiter.Waiting(bob, agoMs = 12_000, leftMs = 18_000)), split.waiting)
        assertEquals("Bob got one 12s ago (next in 18s)", split.waiting.single().describe())

        clock.advance(Duration.ofSeconds(18))
        assertEquals(listOf(bob), limiter.titles(listOf(bob)).allowed, "30 s later")
        assertEquals(listOf(alice), limiter.titles(listOf(alice)).waiting.map { it.player }, "Alice's title was 18 s ago")
    }

    @Test
    fun `a player named twice is counted once`() {
        val split = limiter.actionbars(listOf(bob, bob))

        assertEquals(listOf(bob), split.allowed)
        assertEquals(emptyList(), split.waiting)
    }

    @Test
    fun `one line above the hotbar per player every 5 seconds`() {
        limiter.actionbars(listOf(bob))
        clock.advance(Duration.ofMillis(4_999))
        assertEquals(listOf(bob), limiter.actionbars(listOf(bob)).waiting.map { it.player })
        clock.advance(Duration.ofMillis(1))
        assertEquals(listOf(bob), limiter.actionbars(listOf(bob)).allowed)
    }

    @Test
    fun `one book per player every 5 minutes, apart from titles`() {
        limiter.books(listOf(bob))
        assertEquals(listOf(bob), limiter.titles(listOf(bob)).allowed, "each limit counts apart")
        clock.advance(Duration.ofSeconds(130))

        val waiting = limiter.books(listOf(bob)).waiting.single()
        assertEquals("Bob got one 2m10s ago (next in 2m50s)", waiting.describe())
        clock.advance(Duration.ofSeconds(170))
        assertEquals(listOf(bob), limiter.books(listOf(bob)).allowed)
    }

    @Test
    fun `a wait is rounded up to the second, and a name is escaped`() {
        val engine = PlayerRef(UUID.randomUUID(), "<engine>")
        assertEquals("‹engine› got one 12s ago (next in 18s)", SpeechLimiter.Waiting(engine, agoMs = 12_400, leftMs = 17_001).describe())
        assertEquals("Bob got one 0s ago (next in 1s)", SpeechLimiter.Waiting(bob, agoMs = 1, leftMs = 1).describe())
    }
}
