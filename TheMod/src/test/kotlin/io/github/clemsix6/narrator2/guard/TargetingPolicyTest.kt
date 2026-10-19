package io.github.clemsix6.narrator2.guard

import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.mechanics.PunishmentId
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
import kotlin.test.assertTrue

/**
 * No piling on (M1_M2 §10.3) and the categories' rest (M4 §6.1), with a clock that only moves when the test says so: a recent death, a
 * punishment of another turn, and fairness over the last hour. The test clock's zone is UTC, and it
 * starts at 19:30:00.
 */
class TargetingPolicyTest {
    private val clock = TestClock()
    private val players = PlayerStates()
    private val episodes = Episodes(clock)
    private var chaos = 3
    private val policy = TargetingPolicy(players, clock) { Caps.of(chaos) }
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
    /** An act of the engine: a rule's firing [id] (`Caller.act` = -[id]). */
    private fun firing(id: Long) = Caller(Role.DIRECTOR, "mechanics:C-60", turnId = null, taskId = null, firingId = id)

    /** A hostile action of [caller] of [category] against [target] lasting [seconds]: checked, then its episode opened as the guards do. */
    private fun punish(target: PlayerRef, caller: Caller, seconds: Long = 60, category: PunishmentCategory? = null): Verdict {
        val verdict = policy.check(target, caller, episodes, category = category)
        if (verdict == Verdict.Allowed) episodes.open(target.uuid, caller.act, now() + seconds * 1_000, category)
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
    fun `a refusal names the player through the common escape, and waits are rounded up`() {
        val engine = PlayerRef(UUID(0, 3), "<engine>")
        online(engine, lastDeathAt = now() - 1_500)
        assertEquals(
            "‹engine› died 2s ago; hostile actions against them are refused until 19:34:58 (in 4m59s).",
            denied(policy.check(engine, turn(1), episodes), Rules.NO_PILE_ON).message,
        )
    }

    /** M4 review, finding 9: the engine's firing 812 is not the Director's turn 812 (`Caller.act` = -812). */
    @Test
    fun `a turn and an engine firing of the same number are two acts`() {
        online(bartok)
        assertEquals(Verdict.Allowed, punish(bartok, turn(812)))
        denied(punish(bartok, firing(812)), Rules.ALREADY_PUNISHED)

        assertEquals(Verdict.Allowed, punish(mimosa, firing(5)), "a firing opens its own punishment")
        assertEquals(Verdict.Allowed, punish(mimosa, firing(5), seconds = 120), "and extends it, like a turn")
        assertEquals(now() + 120_000, episodes.active(mimosa.uuid)?.until)
        assertEquals(-5L, episodes.active(mimosa.uuid)?.act)
        denied(punish(mimosa, turn(5)), Rules.ALREADY_PUNISHED)
        denied(punish(mimosa, firing(6)), Rules.ALREADY_PUNISHED)
    }

    /** M4 §6.1: another act may not strike a player in a category they were struck in less than its rest ago (30 min at chaos 3). */
    @Test
    fun `the same kind of punishment waits for its rest, but never within one act`() {
        online(bartok)
        assertEquals(Verdict.Allowed, punish(bartok, turn(1), seconds = 120, category = PunishmentCategory.SIZE))
        assertEquals(Verdict.Allowed, punish(bartok, turn(1), category = PunishmentCategory.SIZE), "one act may strike twice in a category")
        assertEquals(Verdict.Allowed, punish(bartok, turn(1), category = PunishmentCategory.MOVEMENT), "a composed punishment")

        advance(12)
        assertNull(episodes.active(bartok.uuid), "the punishment is over")
        val refused = denied(punish(bartok, turn(2), category = PunishmentCategory.SIZE), Rules.SAME_PUNISHMENT)
        assertEquals(
            "Bartok was already made smaller or bigger 12m ago (size); another size punishment against them is allowed at 20:00:00 (in 18m). " +
                "Vary the punishment.",
            refused.message,
        )
        denied(punish(bartok, firing(2), category = PunishmentCategory.SIZE), Rules.SAME_PUNISHMENT)
        assertEquals(Verdict.Allowed, punish(mimosa, turn(2), category = PunishmentCategory.SIZE), "another player is not concerned")

        assertEquals(Verdict.Allowed, punish(bartok, turn(2), category = PunishmentCategory.GRAVITY), "another category")
        denied(punish(bartok, turn(2), category = PunishmentCategory.SIZE), Rules.SAME_PUNISHMENT)
        denied(punish(bartok, turn(2), category = PunishmentCategory.MOVEMENT), Rules.SAME_PUNISHMENT)
        assertEquals(Verdict.Allowed, punish(bartok, turn(2)), "an action without a category")

        advance(17, 59)
        denied(punish(bartok, turn(3), category = PunishmentCategory.SIZE), Rules.SAME_PUNISHMENT)
        advance(0, 1)
        assertEquals(Verdict.Allowed, punish(bartok, turn(3), category = PunishmentCategory.SIZE), "30 minutes after the last strike")
        assertEquals(Verdict.Allowed, punish(bartok, turn(3), category = PunishmentCategory.MOVEMENT), "the slowness was struck at 19:30 too")
    }

    @Test
    fun `the other category never rests`() {
        online(bartok)
        assertEquals(Verdict.Allowed, punish(bartok, turn(1), category = PunishmentCategory.OTHER))
        advance(2)
        assertEquals(Verdict.Allowed, punish(bartok, turn(2), category = PunishmentCategory.OTHER))
        assertNull(episodes.lastStrike(bartok.uuid, PunishmentCategory.OTHER), "not even recorded")
    }

    @Test
    fun `the rest of a category shortens as the chaos grows`() {
        online(bartok)
        assertEquals(Verdict.Allowed, punish(bartok, turn(1), category = PunishmentCategory.LIGHTNING))
        advance(15)
        val allowed = (1..5).associateWith { level ->
            chaos = level
            policy.check(bartok, turn(2), episodes, category = PunishmentCategory.LIGHTNING) == Verdict.Allowed
        }
        assertEquals(mapOf(1 to false, 2 to false, 3 to false, 4 to false, 5 to true), allowed, "15 minutes after, by chaos level (rests of 60, 45, 30, 20, 10 min)")
        chaos = 1
        assertEquals(
            "Bartok was already struck by lightning 15m ago (lightning); another lightning punishment against them is allowed at 20:30:00 (in 45m). " +
                "Vary the punishment.",
            denied(policy.check(bartok, turn(2), episodes, category = PunishmentCategory.LIGHTNING), Rules.SAME_PUNISHMENT).message,
        )
    }

    @Test
    fun `a caller without an act is a new act at each action, for the categories too`() {
        online(bartok)
        assertEquals(Verdict.Allowed, punish(bartok, turn(null), seconds = 1, category = PunishmentCategory.FOOD))
        advance(1)
        denied(punish(bartok, turn(null), category = PunishmentCategory.FOOD), Rules.SAME_PUNISHMENT)
    }

    /** M4 review, finding 16: one act composes one punishment of the catalogue per player, never a pile of them. */
    @Test
    fun `an act uses punish once per player`() {
        online(bartok)
        fun punishWith(caller: Caller, id: PunishmentId, category: PunishmentCategory): Verdict {
            val verdict = policy.check(bartok, caller, episodes, category = category, punishment = id)
            if (verdict == Verdict.Allowed) episodes.open(bartok.uuid, caller.act, now() + 60_000, category, id)
            return verdict
        }
        assertEquals(Verdict.Allowed, punishWith(turn(1), PunishmentId.HUNGER, PunishmentCategory.FOOD))
        val refused = denied(punishWith(turn(1), PunishmentId.SMITE, PunishmentCategory.LIGHTNING), Rules.ALREADY_PUNISHED)
        assertEquals("this act already punished Bartok (hunger); one punish per act and player: compose it with the other tools, or let it run.", refused.message)
        assertEquals(Verdict.Allowed, punish(bartok, turn(1), category = PunishmentCategory.MOVEMENT), "the act's other tools still compose the punishment")

        advance(2)
        assertNull(episodes.active(bartok.uuid), "the punishment is over")
        denied(punishWith(turn(1), PunishmentId.TINY, PunishmentCategory.SIZE), Rules.ALREADY_PUNISHED)
        assertEquals(Verdict.Allowed, punishWith(turn(2), PunishmentId.TINY, PunishmentCategory.SIZE), "another act may")
        assertEquals(Verdict.Allowed, policy.check(mimosa, turn(1), episodes, punishment = PunishmentId.HUNGER), "another player is not concerned")
    }

    /** M4 review, finding 39: a punish that did nothing at all is withdrawn, as if never asked; the act may punish again. */
    @Test
    fun `a withdrawn punish counts nowhere`() {
        online(bartok)
        assertEquals(Verdict.Allowed, punish(bartok, turn(1), category = PunishmentCategory.FOOD))
        advance(2)
        val before = episodes.recentPunishments(bartok.uuid)
        assertEquals(Verdict.Allowed, policy.check(bartok, turn(2), episodes, category = PunishmentCategory.LIGHTNING, punishment = PunishmentId.SMITE))
        episodes.open(bartok.uuid, turn(2).act, now() + 30_000, PunishmentCategory.LIGHTNING, PunishmentId.SMITE)
        episodes.open(bartok.uuid, turn(2).act, now() + 30_000, PunishmentCategory.HARM)
        assertEquals(2, episodes.recentPunishments(bartok.uuid).size)

        assertTrue(episodes.withdraw(bartok.uuid, turn(2).act))
        assertNull(episodes.active(bartok.uuid), "no episode")
        assertEquals(before, episodes.recentPunishments(bartok.uuid), "not counted")
        assertNull(episodes.lastStrike(bartok.uuid, PunishmentCategory.LIGHTNING), "lightning does not rest")
        assertNull(episodes.lastStrike(bartok.uuid, PunishmentCategory.HARM), "nor what its steps struck")
        assertEquals(PunishmentCategory.FOOD, episodes.resting(bartok.uuid, 30 * 60_000L).keys.single(), "the earlier act's strike stays")
        assertEquals(Verdict.Allowed, policy.check(bartok, turn(2), episodes, category = PunishmentCategory.LIGHTNING, punishment = PunishmentId.SMITE), "the act may punish again")
        assertEquals(false, episodes.withdraw(bartok.uuid, turn(2).act), "nothing left to withdraw")
    }

    /** M4 review, finding 18: a player saved from a death (a rescue, a duel's fatal blow stopped) is left alone, by every act. */
    @Test
    fun `a shielded player is not a target`() {
        online(bartok)
        assertEquals(Verdict.Allowed, punish(bartok, turn(1)))
        val shield = Shield(now(), now() + TargetingPolicy.NO_PILE_ON_MS, "duel C-52: fatal blow stopped at 1 hp")
        advance(1)
        val refused = denied(policy.check(bartok, turn(1), episodes, shield = shield), Rules.NO_PILE_ON)
        assertEquals(
            "Bartok was saved from death 1m ago (duel C-52: fatal blow stopped at 1 hp); hostile actions against them are refused until 19:35:00 (in 4m).",
            refused.message,
        )
        denied(policy.check(bartok, firing(9), episodes, shield = shield), Rules.NO_PILE_ON)
        advance(4)
        assertEquals(Verdict.Allowed, policy.check(bartok, turn(2), episodes, shield = shield), "once the shield is over")
    }

    @Test
    fun `one punishment at a time and fairness come before the categories`() {
        online(bartok)
        assertEquals(Verdict.Allowed, punish(bartok, turn(1), category = PunishmentCategory.SIZE))
        denied(punish(bartok, turn(2), category = PunishmentCategory.SIZE), Rules.ALREADY_PUNISHED)
        listOf(PunishmentCategory.AIR, PunishmentCategory.FOOD, PunishmentCategory.HARM).forEachIndexed { index, category ->
            advance(2)
            assertEquals(Verdict.Allowed, punish(bartok, turn(3L + index), category = category), "punishment ${index + 2}")
        }
        advance(2)
        val refused = denied(punish(bartok, turn(9), category = PunishmentCategory.SIZE), TargetingPolicy.FAIR_SHARE)
        assertTrue("allowed at 20:30:00" in refused.message, refused.message)
    }
}
