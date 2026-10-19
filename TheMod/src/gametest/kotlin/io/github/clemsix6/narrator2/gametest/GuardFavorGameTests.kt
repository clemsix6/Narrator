package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.gametest.GuardGameTests.Companion.PLAY_TIME
import io.github.clemsix6.narrator2.gametest.GuardGameTests.Companion.basin
import io.github.clemsix6.narrator2.gametest.GuardGameTests.Companion.count
import io.github.clemsix6.narrator2.gametest.GuardGameTests.Companion.deathGuardDecisions
import io.github.clemsix6.narrator2.gametest.GuardGameTests.Companion.equip
import io.github.clemsix6.narrator2.gametest.GuardGameTests.Companion.homeAt
import io.github.clemsix6.narrator2.gametest.GuardGameTests.Companion.isAt
import io.github.clemsix6.narrator2.gametest.GuardGameTests.Companion.reconnect
import io.github.clemsix6.narrator2.gametest.GuardGameTests.Companion.setPlayTime
import io.github.clemsix6.narrator2.gametest.GuardGameTests.Companion.withPlayer
import io.github.clemsix6.narrator2.gametest.GuardGapGameTests.Companion.hit
import io.github.clemsix6.narrator2.gametest.GuardGapGameTests.Companion.narratorMob
import io.github.clemsix6.narrator2.gametest.GuardPolicyGameTests.Companion.mockPlayer
import io.github.clemsix6.narrator2.gametest.GuardPolicyGameTests.Companion.removePlayers
import io.github.clemsix6.narrator2.guard.AttributeKey
import io.github.clemsix6.narrator2.guard.Confiscation
import io.github.clemsix6.narrator2.guard.DeathGuard
import io.github.clemsix6.narrator2.guard.ExplosionGuard
import io.github.clemsix6.narrator2.guard.GuardStates
import io.github.clemsix6.narrator2.guard.Guards
import io.github.clemsix6.narrator2.guard.GuardsImpl
import io.github.clemsix6.narrator2.guard.LedgerPurpose
import io.github.clemsix6.narrator2.guard.Peril
import io.github.clemsix6.narrator2.guard.PunishmentCategory
import io.github.clemsix6.narrator2.guard.Rules
import io.github.clemsix6.narrator2.guard.Verdict
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.testing.testSettings
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * In-game tests of the guards of M4 (M4-04, `design/M4.md` §6.1, §7.1, §13): the categories' rest between two
 * acts, the grace (hostile actions refused, the Narrator's creatures and explosions sparing the player, no new
 * grace within the hour), the pardon (only what the Narrator inflicted), `isPunished`, the pace, the rescue the
 * duels use (out of the lava, nothing counted), and the engine's acts in the explanation of a death.
 *
 * Each test has its own mock players and removes them. The tests that move time build guards of their own on
 * the runtime's player states, with a test clock that starts now: the server's guards would also expire what
 * the tests running alongside need.
 */
class GuardFavorGameTests {

    /** `same_punishment` (M4 §6.1) through the guards: another turn may not strike the same category before its rest. */
    @GameTest
    fun theSameKindOfPunishmentRestsBetweenTwoTurns(helper: GameTestHelper) = withPlayer(helper) { player ->
        val (guards, clock, services) = ownGuards(helper)
        helper.assertEquals(Verdict.Allowed, guards.hostile(player, turn(1), clock.millis() + 60_000, PunishmentCategory.SIZE), "turn 1 makes him smaller")
        helper.assertEquals(Verdict.Allowed, guards.hostile(player, turn(1), clock.millis() + 60_000, PunishmentCategory.MOVEMENT), "and slows him down")

        clock.advance(Duration.ofMinutes(12))
        val refused = guards.hostile(player, turn(2), clock.millis() + 60_000, PunishmentCategory.SIZE)
        helper.assertTrue(
            refused is Verdict.Denied && refused.rule == Rules.SAME_PUNISHMENT && refused.message.contains(" 12m ago (size); ") &&
                refused.message.endsWith("(in 18m). Vary the punishment."),
            "another turn, the same category: $refused",
        )
        val engine = Caller(Role.DIRECTOR, "mechanics:C-60", turnId = null, taskId = null, firingId = 1)
        val firing = guards.hostile(player, engine, clock.millis() + 60_000, PunishmentCategory.MOVEMENT)
        helper.assertTrue(firing is Verdict.Denied && firing.rule == Rules.SAME_PUNISHMENT, "an engine's act is another act too: $firing")
        helper.assertEquals(Verdict.Allowed, guards.hostile(player, turn(2), clock.millis() + 60_000, PunishmentCategory.AIR), "another category")

        val offering = Confiscation(-1, listOf("""{id:"minecraft:diamond",count:10}"""), "10x minecraft:diamond", clock.millis(), clock.millis() + 600_000, "an offering", null, LedgerPurpose.OFFERING)
        guards.states.update(player) { it.copy(confiscations = it.confiscations + offering) }
        val pacing = guards.pacing(listOf(player.uuid), clock.millis()).single()
        helper.assertEquals(listOf(offering), pacing.offerings, "the offerings waiting")
        helper.assertEquals(null, pacing.graceUntil, "no grace")
        helper.assertEquals(2, pacing.punishedThisHour, "two punishments this hour")
        helper.assertEquals(listOf(PunishmentCategory.SIZE, PunishmentCategory.MOVEMENT, PunishmentCategory.AIR), pacing.resting.keys.toList(), "resting, soonest first")
        helper.assertEquals(clock.millis() + Duration.ofMinutes(30).toMillis(), pacing.resting[PunishmentCategory.AIR], "air rests 30 minutes at chaos 3")
        helper.assertTrue(
            services.traces.decisions.any { it.decision == "denied" && it.reason.orEmpty().startsWith("same_punishment: ") },
            "the refusal is a decision",
        )

        clock.advance(Duration.ofMinutes(18))
        helper.assertEquals(Verdict.Allowed, guards.hostile(player, turn(3), clock.millis() + 60_000, PunishmentCategory.SIZE), "30 minutes after the first strike")
    }

    /**
     * The grace (M4 §7.1): until it ends, the guards refuse every hostile action against the player, even of
     * the act already punishing them, and the Narrator's creatures and explosions spare them; no new grace
     * while it runs nor within the hour after; saved with the player; the pace shows it.
     */
    @GameTest
    fun theGraceKeepsTheNarratorAndItsCreaturesAway(helper: GameTestHelper) {
        val (guards, clock, services) = ownGuards(helper)
        val graced = mockPlayer(helper, BlockPos(4, 1, 2))
        val exposed = mockPlayer(helper, BlockPos(4, 1, 0))
        try {
            helper.assertEquals(Verdict.Allowed, guards.hostile(graced, turn(7), clock.millis() + 600_000), "a punishment runs")
            val until = clock.millis() + Duration.ofMinutes(15).toMillis()
            helper.assertEquals(Verdict.Allowed, guards.grace(graced, until, turn(8)), "graced")
            helper.assertEquals(until, GuardStates.read(graced).graceUntil, "saved with the player")
            helper.assertEquals(until, guards.pacing(listOf(graced.uuid), clock.millis()).single().graceUntil, "the pace shows it")

            val refused = guards.hostile(graced, turn(7), clock.millis() + 60_000, PunishmentCategory.HARM)
            helper.assertTrue(
                refused is Verdict.Denied && refused.rule == Rules.GRACE && refused.message.endsWith("(in 15m); nothing hostile against them until then."),
                "even the act already punishing them: $refused",
            )
            val again = guards.grace(graced, until + 60_000, turn(9))
            helper.assertTrue(again is Verdict.Denied && again.rule == Rules.FAVOR_LIMIT && "is under your grace until" in again.message, "no second grace: $again")
            helper.assertEquals(until, GuardStates.read(graced).graceUntil, "the grace is unchanged")

            // The Narrator's creatures and explosions: the server's guards read the grace saved with the player.
            val zombie = narratorMob(helper, EntityTypes.ZOMBIE, Vec3(1.5, 1.0, 6.5), null, System.currentTimeMillis() + 600_000)
            val sources = helper.level.damageSources()
            helper.assertFalse(hit(helper, graced, sources.mobAttack(zombie)), "the Narrator's zombie spares the graced player")
            helper.assertTrue(hit(helper, exposed, sources.mobAttack(zombie)), "and hurts the other one")
            val healthBefore = exposed.health
            val center = helper.absoluteVec(Vec3(4.5, 1.5, 4.5))
            ExplosionGuard.narrators { helper.level.explode(null, center.x, center.y, center.z, 3f, false, Level.ExplosionInteraction.NONE) }
            helper.assertValueEqual(20f, graced.health, "the Narrator's explosion spares the graced player")
            helper.assertTrue(exposed.health < healthBefore, "and hurts the other one: ${exposed.health}")

            clock.advance(Duration.ofMinutes(16))
            helper.assertEquals(Verdict.Allowed, guards.hostile(graced, turn(10), clock.millis() + 60_000, PunishmentCategory.HARM), "fair game once it ended")
            val soon = guards.grace(graced, clock.millis() + 60_000, turn(11))
            helper.assertTrue(soon is Verdict.Denied && soon.rule == Rules.FAVOR_LIMIT && soon.message.contains("last grace ended 1m ago"), "not within the hour: $soon")
            clock.advance(Duration.ofMinutes(59))
            helper.assertEquals(Verdict.Allowed, guards.grace(graced, clock.millis() + Duration.ofHours(3).toMillis(), turn(12)), "an hour after its end")
            helper.assertEquals(clock.millis() + Duration.ofHours(1).toMillis(), GuardStates.read(graced).graceUntil, "an hour at most")
            helper.assertTrue(
                services.traces.decisions.count { it.kind == DecisionRecord.Kind.GUARD && it.decision == "graced" } == 2,
                "each grace is a decision",
            )
        } finally {
            removePlayers(helper, graced, exposed)
            helper.killAllEntities()
        }
        helper.succeed()
    }

    /**
     * The pardon (M4 §7.1) lifts what the Narrator inflicted, and only that: its harmful effects and modifiers,
     * its confiscations (given back now), its harmful creatures summoned for the player, and the punishment;
     * not a favor, not a stake, not another player's creature. `isPunished` before and after.
     */
    @GameTest
    fun aPardonLiftsWhatTheNarratorInflictedAndNothingElse(helper: GameTestHelper) = withPlayer(helper) { player ->
        val guards = serverGuards(helper)
        val now = System.currentTimeMillis()
        equip(player)
        helper.assertFalse(guards.isPunished(player), "nothing yet")
        val stake = Confiscation(-4041, listOf("""{id:"minecraft:emerald",count:2}"""), "2x minecraft:emerald", now, now + 86_400_000, "a bet", null, LedgerPurpose.STAKE, canon = 50)
        guards.states.update(player) { it.copy(confiscations = it.confiscations + stake) }
        helper.assertFalse(guards.isPunished(player), "a stake is no punishment")
        helper.assertTrue(guards.activeOn(player.uuid).any { it.kind == "stake" && !it.harmful }, "shown apart: ${guards.activeOn(player.uuid)}")

        helper.assertEquals(Verdict.Allowed, guards.hostile(player, turn(4041), now + 600_000, PunishmentCategory.MOVEMENT), "the punishment opens")
        guards.effects.apply(player, MobEffects.SLOWNESS, 2, 600, particles = false, turnId = 4041)
        guards.effects.apply(player, MobEffects.SPEED, 1, 600, particles = false, turnId = 4041)
        guards.attributes.apply(player, AttributeKey.SCALE, -0.5, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, now + 600_000, harmful = true, turnId = 4041)
        guards.attributes.apply(player, AttributeKey.STEP_HEIGHT, 0.5, AttributeModifier.Operation.ADD_VALUE, now + 600_000, harmful = false, turnId = 4041)
        guards.items.confiscate(player, { it.`is`(Items.DIAMOND) }, 3, now + 600_000, "greed", turnId = 4041)
        val zombie = narratorMob(helper, EntityTypes.ZOMBIE, Vec3(5.5, 1.0, 5.5), player.uuid, now + 600_000)
        val friend = EntityTypes.WOLF.create(helper.level, EntitySpawnReason.COMMAND)!!.also {
            it.snapTo(helper.absoluteVec(Vec3(6.5, 1.0, 5.5)))
            it.isNoAi = true
            guards.mobs.mark(it, now + 600_000, player.uuid, harmful = false)
            helper.level.addFreshEntity(it)
        }
        val elsewhere = narratorMob(helper, EntityTypes.ZOMBIE, Vec3(6.5, 1.0, 6.5), UUID.randomUUID(), now + 600_000)
        helper.assertTrue(guards.isPunished(player), "punished")

        val lifted = guards.pardon(player, turn(4042))
        helper.assertEquals(
            listOf("slowness II lifted", "scale reset", "3x minecraft:diamond returned", "1 minecraft:zombie sent away"),
            lifted, "what was lifted",
        )
        helper.assertFalse(player.hasEffect(MobEffects.SLOWNESS), "its slowness is lifted")
        helper.assertTrue(player.hasEffect(MobEffects.SPEED), "its favor stays")
        helper.assertValueEqual(1.0, player.getAttributeValue(Attributes.SCALE), "the scale is back")
        helper.assertValueEqual(1.1, player.getAttributeValue(Attributes.STEP_HEIGHT), "its kind change stays")
        helper.assertValueEqual(5, count(player, Items.DIAMOND), "the diamonds are back now")
        helper.assertEquals(listOf(stake), guards.items.held(player), "the stake stays in escrow")
        helper.assertTrue(zombie.isRemoved, "its zombie is sent away")
        helper.assertFalse(friend.isRemoved || elsewhere.isRemoved, "a creature that does no harm, and another player's, stay")
        helper.assertEquals(null, GuardStates.read(player).episode, "the punishment is over")
        helper.assertFalse(guards.isPunished(player), "nothing left")
        helper.assertEquals(emptyList<String>(), guards.pardon(player, turn(4043)), "nothing to pardon")
        helper.assertEquals(Verdict.Allowed, guards.hostile(player, turn(4044), now + 60_000, PunishmentCategory.SIZE), "a new punishment may begin")
        friend.discard()
        elsewhere.discard()
    }

    /**
     * A pardon of a punishment that is only its episode (a teleport, a shuffle) still ends it; `isPunished` does not
     * count the episode alone, which nothing lifts: a joker is not played for it (M4 review, finding 35).
     */
    @GameTest
    fun aPardonEndsAPunishmentWithNothingLeftToLift(helper: GameTestHelper) = withPlayer(helper) { player ->
        val guards = serverGuards(helper)
        guards.hostile(player, turn(5051), System.currentTimeMillis() + 600_000, PunishmentCategory.TELEPORT)
        helper.assertFalse(guards.isPunished(player), "an episode alone: nothing to lift")
        helper.assertEquals(listOf("punishment ended"), guards.pardon(player, turn(5052)), "what was lifted")
        helper.assertEquals(null, GuardStates.read(player).episode, "ended")
    }

    /**
     * A pardon that lifts the Narrator's levitation leaves the player high in the air: the fall that follows is
     * cushioned and on the Narrator (M2 review, finding 1), as at the levitation's own end.
     */
    @GameTest
    fun aPardonedLevitationEndsInACushionedFall(helper: GameTestHelper) = withPlayer(helper) { player ->
        val guards = serverGuards(helper)
        guards.hostile(player, turn(6061), System.currentTimeMillis() + 60_000, PunishmentCategory.AIR)
        guards.effects.apply(player, MobEffects.LEVITATION, 2, 60, particles = false, turnId = 6061)
        val up = helper.absolutePos(BlockPos(1, 30, 1))
        player.teleportTo(up.x + 0.5, up.y.toDouble(), up.z + 0.5)

        helper.assertEquals(listOf("levitation II lifted"), guards.pardon(player, turn(6062)), "what was lifted")
        helper.assertFalse(player.hasEffect(MobEffects.LEVITATION), "no longer levitating")
        helper.assertValueEqual(0.0, player.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER), "no fall damage")
        helper.assertTrue(guards.death.armedFor(player, Peril.FALL) > DeathGuard.playTime(player), "a fatal fall would be the Narrator's")
    }

    /**
     * The rescue the duels use (M4 §5.5): out of the lava, put out, some health, at the respawn point, without
     * counting anything against the quota nor arming anything, and alive ticks later; a `rescued` decision.
     */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun theEnginesRescueTakesAPlayerOutOfTheLavaWithoutCounting(helper: GameTestHelper) {
        val runtime = helper.narrator()
        val guards = serverGuards(helper)
        val bob = TestPlayer(helper)
        helper.closingOnFailure(bob, ASYNC_MAX_TICKS) {
            basin(helper, Blocks.LAVA)
            bob.standAt(BlockPos(1, 1, 1))
            bob.makeVulnerable()
            val player = bob.live
            setPlayTime(player, PLAY_TIME)
            equip(player)
            val home = homeAt(helper, player)
            repeat(5) { player.doTick() }
            helper.assertTrue(player.isInLava && player.isOnFire, "in the lava, burning")
            player.health = 1f
            val deathsLeft = guards.deathsLeft(player.uuid)
            val armed = GuardStates.read(player).let { it.armedUntil to it.perils }

            guards.rescue(player, "duel C-52: fell into the lava")
            helper.assertTrue(isAt(player, home), "at the respawn point: ${player.position()}")
            helper.assertFalse(player.isOnFire, "put out")
            helper.assertValueEqual(DeathGuard.RESCUE_HEALTH, player.health, "with some health")
            helper.assertEquals(emptyList<Long>(), GuardStates.read(player).narratorKills, "nothing counted")
            helper.assertEquals(deathsLeft, guards.deathsLeft(player.uuid), "the quota is untouched")
            helper.assertEquals(armed, GuardStates.read(player).let { it.armedUntil to it.perils }, "nothing armed")
            helper.assertFalse(DeathGuard.keepsInventory(player), "no mark")
            // M4 review, finding 18: saved from a death, they are left alone 5 minutes, as after one.
            val after = guards.hostile(player, turn(5252), System.currentTimeMillis() + 60_000, PunishmentCategory.LIGHTNING)
            helper.assertTrue(
                after is Verdict.Denied && after.rule == Rules.NO_PILE_ON && after.message.contains(" was saved from death ") &&
                    after.message.contains(" ago (duel C-52: fell into the lava); hostile actions against them are refused until "),
                "nothing hostile against a player just rescued: $after",
            )
            helper.assertTrue(guards.spares(player), "the Narrator's creatures and explosions spare them")

            var decisions: AtomicReference<Any>? = null
            helper.startSequence()
                .thenIdle(2)
                .thenExecute {
                    repeat(100) { player.doTick() }
                    helper.assertTrue(!player.isDeadOrDying && isAt(player, home), "alive at home ticks later")
                    helper.assertValueEqual(5, count(player, Items.DIAMOND), "the inventory is untouched")
                    decisions = deathGuardDecisions(runtime, bob.name)
                }
                .thenAwait { helper.assertEquals(listOf<Pair<String, String?>>("rescued" to null), decisions?.get(), "decisions of ${bob.name}") }
                .thenExecute {
                    basin(helper, Blocks.STONE)
                    bob.close()
                }
                .thenSucceed()
        }
    }

    /**
     * The guards' shield (M4 review, finding 18, the contract a duel's spared loser uses): until it ends, every act
     * is refused, even the one already punishing the player, and the Narrator's creatures and explosions spare them;
     * a shorter shield never cuts a longer one; a `guard shielded` decision.
     */
    @GameTest
    fun aShieldKeepsTheNarratorAwayUntilItEnds(helper: GameTestHelper) = withPlayer(helper) { player ->
        val (guards, clock, services) = ownGuards(helper)
        helper.assertEquals(Verdict.Allowed, guards.hostile(player, turn(31), clock.millis() + 600_000, PunishmentCategory.SIZE), "a punishment runs")
        guards.shield(player, clock.millis() + Guards.RESCUE_SHIELD_MS, "duel C-52: fatal blow stopped at 1 hp")
        guards.shield(player, clock.millis() + 60_000, "shorter")
        clock.advance(Duration.ofMinutes(2))
        for (caller in listOf(turn(31), turn(32))) {
            val refused = guards.hostile(player, caller, clock.millis() + 60_000, PunishmentCategory.HARM)
            helper.assertTrue(
                refused is Verdict.Denied && refused.rule == Rules.NO_PILE_ON &&
                    refused.message.contains(" was saved from death 2m ago (duel C-52: fatal blow stopped at 1 hp); hostile actions against them are refused until ") &&
                    refused.message.endsWith(" (in 3m)."),
                "refused for act ${caller.act}: $refused",
            )
        }
        helper.assertTrue(guards.spares(player), "spared by the Narrator's creatures and explosions")
        helper.assertTrue(services.traces.decisions.any { it.decision == "shielded" && it.subject == player.plainTextName }, "a shielded decision")
        clock.advance(Duration.ofMinutes(3))
        helper.assertFalse(guards.spares(player), "the shield is over")
        helper.assertEquals(Verdict.Allowed, guards.hostile(player, turn(31), clock.millis() + 60_000, PunishmentCategory.SIZE), "fair game again")
    }

    /**
     * A shield is saved with the player (M4 review, finding 18, made to last by M4-FIX-H): after a restart (the player
     * read back from their file, the guards new and empty), the Narrator still leaves them alone until it ends.
     */
    @GameTest
    fun aShieldHoldsAcrossARestart(helper: GameTestHelper) = withPlayer(helper) { player ->
        val (before, clock, _) = ownGuards(helper)
        before.shield(player, clock.millis() + Guards.RESCUE_SHIELD_MS, "duel C-52: fatal blow stopped at 1 hp")

        val back = reconnect(helper, player)
        val after = ownGuards(helper, clock).guards // the guards of the next start: nothing in memory
        clock.advance(Duration.ofMinutes(1))
        val refused = after.hostile(back, turn(51), clock.millis() + 60_000, PunishmentCategory.HARM)
        helper.assertTrue(
            refused is Verdict.Denied && refused.rule == Rules.NO_PILE_ON && refused.message.contains("(duel C-52: fatal blow stopped at 1 hp)"),
            "refused after the restart: $refused",
        )
        helper.assertTrue(after.spares(back), "spared by the Narrator's creatures and explosions")
        helper.assertEquals("duel C-52: fatal blow stopped at 1 hp", GuardStates.read(back).shield?.reason, "in the player's file")
    }

    /**
     * A duelist is out of the Narrator's reach while their duel runs (M4 review, finding 19): `hostile` refuses them,
     * whatever the act, and its creatures and explosions spare them; the mechanics engine says who duels.
     */
    @GameTest
    fun aDuelistIsOutOfReachWhileTheDuelRuns(helper: GameTestHelper) = withPlayer(helper) { player ->
        val (guards, clock, _) = ownGuards(helper)
        helper.assertEquals(Verdict.Allowed, guards.vet(player, turn(41), PunishmentCategory.HARM), "no duel: fair game")
        var dueling = true
        guards.isDueling = { it == player.uuid && dueling }
        val refused = guards.hostile(player, turn(41), clock.millis() + 60_000, PunishmentCategory.HARM)
        helper.assertEquals(
            Verdict.Denied(Rules.DUEL, "${player.plainTextName} is fighting a duel; nothing hostile against a duelist while the duel runs."),
            refused, "a duelist",
        )
        helper.assertTrue(guards.spares(player), "spared by the Narrator's creatures and explosions")
        helper.assertEquals(null, GuardStates.read(player).episode, "nothing opened")
        dueling = false
        helper.assertEquals(Verdict.Allowed, guards.hostile(player, turn(41), clock.millis() + 60_000, PunishmentCategory.HARM), "the duel is over")
    }

    /**
     * A death 20 s after one of the engine's acts (M4 §4.3): the act wrote no `tool_calls` row, only its
     * `mechanic action` decision, and the `death_guard died` decision names it all the same.
     */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun aDeathIsTiedToTheEnginesActJustBefore(helper: GameTestHelper) {
        val runtime = helper.narrator()
        val victim = TestPlayer(helper, "de_")
        val name = victim.name
        try {
            victim.makeVulnerable()
            Decisions(runtime.trace, Clock.offset(runtime.clock, Duration.ofSeconds(-20))).record(
                Log.mechanics, DecisionRecord.Kind.MECHANIC, "action", name, reason = "explosion ok",
                conversationId = "mechanics:C-60",
                detail = buildJsonObject {
                    put("tool", "explosion")
                    put("status", "ok")
                    put("result", "ok: explosion of power 2 at $name")
                },
            )
            victim.live.hurtServer(helper.level, helper.level.damageSources().fall(), 1_000f)
            helper.assertTrue(victim.live.isDeadOrDying, "the player died")
        } finally {
            victim.close()
        }

        val reason = AtomicReference<String?>()
        CoroutineScope(Dispatchers.IO).launch {
            val deadline = System.currentTimeMillis() + 15_000
            while (reason.get() == null && System.currentTimeMillis() < deadline) {
                delay(200)
                runtime.journal.barrier()
                reason.set(
                    runtime.journal.read(5_000) { connection ->
                        connection.prepareStatement("SELECT reason FROM engine_decisions WHERE kind = 'death_guard' AND decision = 'died' AND subject = ?").use { statement ->
                            statement.setString(1, name)
                            statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
                        }
                    },
                )
            }
        }
        helper.awaitAsync {
            val sentence = reason.get()
            helper.assertTrue(sentence != null, "the death_guard died decision of $name")
            helper.assertTrue(
                sentence!!.endsWith("the Narrator acted on $name in the last 1m: explosion 20s before (engine, mechanics:C-60)"),
                "the engine's act: $sentence",
            )
        }
    }

    private companion object {
        fun turn(id: Long) = Caller(Role.DIRECTOR, "guard-favor-test", turnId = id, taskId = null)

        fun serverGuards(helper: GameTestHelper): GuardsImpl = helper.narrator().guards as GuardsImpl

        /** Guards of the test's own, their clock (it starts now and moves when told) and their services (their decisions). */
        data class Own(val guards: GuardsImpl, val clock: TestClock, val services: TestServices)

        /** [Own] guards, on the runtime's player states, on [clock] (a new one by default). */
        fun ownGuards(helper: GameTestHelper, clock: TestClock = TestClock(Instant.now())): Own {
            val services = TestServices(minecraft = helper.level.server, clock = clock, settings = testSettings(NarratorConfig()), players = helper.narrator().players)
            return Own(GuardsImpl(services), clock, services)
        }
    }
}
