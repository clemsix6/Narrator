package io.github.clemsix6.narrator2.mechanics

import io.github.clemsix6.narrator2.testing.MechanicsSamples
import io.github.clemsix6.narrator2.testing.MechanicsSamples.bartok
import io.github.clemsix6.narrator2.testing.MechanicsSamples.evening
import io.github.clemsix6.narrator2.testing.MechanicsSamples.josiane
import io.github.clemsix6.narrator2.testing.MechanicsSamples.mimosa
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * What the engine's guardian releases while the mechanics are off (M4 review, finding 9): every duel, its duelists to
 * put back and its bets given back; the bets of a challenge past its end. The rest waits for the engine. The guardian
 * itself runs in game (`MechanicsAssemblyGameTests`).
 */
class EngineGuardianTest {
    private val arena = listOf(
        SavedPosition(bartok.uuid.toString(), "minecraft:overworld", 10.5, 64.0, -3.5, 90f, 0f, savedAt = evening),
        SavedPosition(mimosa.uuid.toString(), "minecraft:overworld", 20.5, 70.0, 8.5, -90f, 0f, savedAt = evening),
    )
    private val duel = MechanicsSamples.invitedDuel.copy(
        status = ChallengeStatus.RUNNING,
        startedAt = evening + 5 * MINUTE,
        endsAt = evening + 10 * MINUTE,
        arena = arena,
        bets = listOf(Bet(josiane.uuid.toString(), "Josiane", mimosa.uuid.toString(), stake = 901, what = "3x diamond")),
        betsCloseAt = evening + 6 * MINUTE,
    )
    private val challenge = MechanicsSamples.runningChallenge // C-50, ends at 20:15, Josiane's stake 812 on it

    @Test
    fun `every duel is over for its players, put back, its bets given back, its end now, and stays for the engine to close`() {
        val now = evening + 7 * MINUTE
        val state = MechanicsState(challenges = listOf(challenge, duel), rules = listOf(MechanicsSamples.griefLaw))

        val released = EngineGuardian.release(state, now)

        assertEquals(listOf(challenge, duel.copy(arena = emptyList(), bets = emptyList(), endsAt = now)), released.challenges, "C-50 runs on: its end is to come")
        assertEquals(arena, released.returns)
        assertEquals(listOf(Settlement(josiane.uuid.toString(), 901)), released.settlements)
        assertEquals(state.rules, released.rules, "the rules wait for the engine")
        assertSame(released, EngineGuardian.release(released, now + 1_000), "nothing more to release")
    }

    @Test
    fun `a challenge's bets go back at its end, not a week later, and never twice`() {
        val end = challenge.endsAt
        val state = MechanicsState(challenges = listOf(challenge), settlements = listOf(Settlement(josiane.uuid.toString(), 700, at = evening)))

        assertSame(state, EngineGuardian.release(state, end - 1), "before its end")
        val released = EngineGuardian.release(state, end)

        assertEquals(listOf(challenge.copy(bets = emptyList())), released.challenges, "kept: the engine closes it, its canon with it")
        assertEquals(listOf(Settlement(josiane.uuid.toString(), 700, at = evening), Settlement(josiane.uuid.toString(), 812)), released.settlements)
        assertSame(released, EngineGuardian.release(released, end + 1_000))

        val judging = MechanicsState(challenges = listOf(challenge.copy(status = ChallengeStatus.JUDGING, judgeUntil = end + 5 * MINUTE)))
        assertSame(judging, EngineGuardian.release(judging, end + MINUTE), "a verdict awaited: its bets wait for the end of the judging")
        assertEquals(emptyList(), EngineGuardian.release(judging, end + 5 * MINUTE).challenges.single().bets)
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}
