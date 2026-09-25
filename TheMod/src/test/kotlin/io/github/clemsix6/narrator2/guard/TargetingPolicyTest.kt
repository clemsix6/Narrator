package io.github.clemsix6.narrator2.guard

import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.observer.LivePlayer
import io.github.clemsix6.narrator2.observer.PlayerStates
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.tool.Caller
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * No piling on (M1_M2 §10.3), with a clock that only moves when the test says so: a recent death, a
 * punishment of another turn, and fairness over the last hour. The test clock's zone is UTC, and it
 * starts at 19:30:00.
 */
class TargetingPolicyTest {
    private val clock = TestClock()
    private val players = PlayerStates()
    private val episodes = Episodes(clock)
    private val policy = TargetingPolicy(players, clock)
    private val bartok = PlayerRef(UUID(0, 1), "Bartok")
    private val mimosa = PlayerRef(UUID(0, 2), "Mimosa")

    private fun now() = clock.millis()

    private fun turn(id: Long?) = Caller(Role.DIRECTOR, "director-1", turnId = id, taskId = null)

    private fun online(ref: PlayerRef, health: Float = 20f, lastDeathAt: Long? = null) = players.put(
        LivePlayer(
            ref, sessionId = 1, joinedAt = now(), isOp = false, gameMode = "survival", pos = Pos("minecraft:overworld", 0, 64, 0),
            biome = null, structure = null, underground = false, health = health, maxHealth = 20f, food = 20, vehicle = null,
            lastDeathAt = lastDeathAt, lastMoveAt = now(), idle = false,
        ),
    )

    private fun advance(minutes: Long, seconds: Long = 0) = clock.advance(Duration.ofMinutes(minutes).plusSeconds(seconds))

    /** A hostile action of [caller] against [target] lasting [seconds]: checked, then its episode opened as the guards do. */
    private fun punish(target: PlayerRef, caller: Caller, seconds: Long = 60): Verdict {
        val verdict = policy.check(target, caller, episodes)
        if (verdict == Verdict.Allowed) episodes.open(target.uuid, caller.turnId, now() + seconds * 1_000)
        return verdict
    }

    private fun denied(verdict: Verdict, rule: String): Verdict.Denied {
        assertIs<Verdict.Denied>(verdict, "refused")
        assertEquals(rule, verdict.rule, verdict.message)
        return verdict
    }

    @Test
    fun `a player who died less than 5 minutes ago is not a target`() {
        online(bartok, lastDeathAt = now())
        advance(2)
        val refused = denied(policy.check(bartok, turn(1), episodes), Rules.NO_PILE_ON)
        assertEquals("Bartok died 2m ago; hostile actions against them are refused until 19:35:00 (in 3m).", refused.message)

        advance(2, 59)
        denied(policy.check(bartok, turn(1), episodes), Rules.NO_PILE_ON)
        advance(0, 1)
        assertEquals(Verdict.Allowed, policy.check(bartok, turn(1), episodes), "5 minutes after the death")
    }

    @Test
    fun `a dead player is not a target, however long ago they died`() {
        online(bartok, health = 0f, lastDeathAt = now())
        advance(30)
        val refused = denied(policy.check(bartok, turn(1), episodes), Rules.NO_PILE_ON)
        assertEquals("Bartok is dead; no hostile action against them until 5 minutes after their death.", refused.message)
    }

    @Test
    fun `a player the policy knows nothing about can be targeted`() {
        assertEquals(Verdict.Allowed, policy.check(bartok, turn(1), episodes))
    }

    @Test
    fun `the actions of one turn form one punishment, another turn waits for its end`() {
        online(bartok)
        assertEquals(Verdict.Allowed, punish(bartok, turn(7), seconds = 120), "the punishment opens")
        assertEquals(Verdict.Allowed, punish(bartok, turn(7), seconds = 300), "the same turn adds to it, and extends it")
        assertEquals(now() + 300_000, episodes.active(bartok.uuid)?.until)

        advance(1)
        val refused = denied(punish(bartok, turn(8)), Rules.ALREADY_PUNISHED)
        assertEquals(
            "Bartok is already being punished (an earlier punishment runs until 19:35:00, in 4m); one punishment at a time.",
            refused.message,
        )
        assertEquals(Verdict.Allowed, punish(mimosa, turn(8)), "another player is not concerned")

        advance(4)
        assertNull(episodes.active(bartok.uuid), "over once its last effect is over")
        assertEquals(Verdict.Allowed, punish(bartok, turn(8)), "a new punishment may begin")
    }

    @Test
    fun `a caller without a turn never extends a punishment`() {
        online(bartok)
        assertEquals(Verdict.Allowed, punish(bartok, turn(null)))
        denied(punish(bartok, turn(null)), Rules.ALREADY_PUNISHED)
        denied(punish(bartok, turn(3)), Rules.ALREADY_PUNISHED)
    }

    @Test
    fun `a death during a punishment keeps the player out of reach, even for the same turn`() {
        online(bartok)
        assertEquals(Verdict.Allowed, punish(bartok, turn(7)))
        online(bartok, lastDeathAt = now())
        denied(punish(bartok, turn(7)), Rules.NO_PILE_ON)
    }

    @Test
    fun `no more than 4 punishments of the same player in an hour`() {
        online(bartok)
        repeat(TargetingPolicy.MAX_PUNISHMENTS_PER_HOUR) { index ->
            assertEquals(Verdict.Allowed, punish(bartok, turn(index.toLong()), seconds = 60), "punishment ${index + 1}")
            // Extending it does not count again.
            assertEquals(Verdict.Allowed, punish(bartok, turn(index.toLong()), seconds = 90))
            advance(10)
        }
        assertEquals(4, episodes.recentPunishments(bartok.uuid).size)

        // 40 minutes after the first one: the fifth waits until the first leaves the hour.
        val refused = denied(punish(bartok, turn(10)), TargetingPolicy.FAIR_SHARE)
        assertEquals(
            "Bartok was punished 4 times in the last hour; the next punishment against them is allowed at 20:30:00 (in 20m). " +
                "Mind the other players, or reward this one.",
            refused.message,
        )
        assertEquals(Verdict.Allowed, punish(mimosa, turn(10)), "the others are fair game")

        advance(20)
        assertEquals(Verdict.Allowed, punish(bartok, turn(11)), "an hour after the first one")
        denied(punish(bartok, turn(12)), Rules.ALREADY_PUNISHED)
    }

    @Test
    fun `a punishment rebuilt after a restart is running again`() {
        online(bartok)
        // What the guards do when Bartok joins with a punishment of turn 7 saved in his state.
        episodes.open(bartok.uuid, 7, now() + 120_000)
        denied(policy.check(bartok, turn(8), episodes), Rules.ALREADY_PUNISHED)
        assertEquals(Verdict.Allowed, policy.check(bartok, turn(7), episodes), "turn 7 may go on")
    }

    @Test
    fun `durations for the model`() {
        assertEquals("1s", TargetingPolicy.span(1))
        assertEquals("45s", TargetingPolicy.span(45_000))
        assertEquals("3m", TargetingPolicy.span(180_000))
        assertEquals("4m30s", TargetingPolicy.span(270_000))
        assertEquals("1h05m", TargetingPolicy.span(3_900_000))
        assertEquals("0s", TargetingPolicy.span(-5))
    }
}
