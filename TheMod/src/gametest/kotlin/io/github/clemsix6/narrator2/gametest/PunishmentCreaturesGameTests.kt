package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.gametest.GuardGameTests.Companion.count
import io.github.clemsix6.narrator2.guard.DeathGuard
import io.github.clemsix6.narrator2.guard.GuardStates
import io.github.clemsix6.narrator2.guard.GuardsImpl
import io.github.clemsix6.narrator2.guard.NarratorMob
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.mechanics.PunishmentId
import io.github.clemsix6.narrator2.mechanics.punish.Primitives
import io.github.clemsix6.narrator2.mechanics.punish.PunishmentCatalog
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.testing.testSettings
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.tool.action.PunishArgs
import io.github.clemsix6.narrator2.tool.action.PunishTool
import io.github.clemsix6.narrator2.tool.action.SpawnArgs
import io.github.clemsix6.narrator2.tool.action.SpawnTool
import kotlinx.coroutines.runBlocking
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.Mob
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import java.time.Duration
import java.time.Instant

/**
 * The creatures of a punishment of the catalogue (`guests`, `slime_rain`) are their player's alone, and never kill
 * (M4-POST, M4-24's finding 1: in the acceptance, `punish guests` on Pistache killed him, then Opaline and Bartok, who
 * stood still 16 to 22 blocks away):
 * - they go for their player only, and hurt nobody else, not even by a blow meant for that player;
 * - a blow that would kill their player is refused and turned into a rescue where he stands: nothing lost, nothing
 *   counted against the Narrator's quota of deaths;
 * - they go at the end of the punishment, at their player's death and at his departure.
 *
 * So are the hostile creatures the Director summons with `spawn` (M4-LEFTOVERS). A module's creatures are its module's,
 * not their player's, and never kill either: `ModuleActsGameTests` (M5 review, D1).
 *
 * Each test has its own guards on a test clock ([Rig], as `PunishGameTests`); what the game's hooks do (targeting,
 * damage, deaths, departures) goes through the server's guards, which read the creatures' own mark.
 */
class PunishmentCreaturesGameTests {

    /**
     * The acceptance's scene (M4-24, pass 3): Pistache punished with guests at severity 2, Opaline and Bartok standing
     * still about 20 blocks away, for 30 seconds of game. Nobody dies: no guest ever goes for a passer-by, no passer-by
     * takes a blow, and Pistache, whom they reach, is rescued rather than killed.
     */
    @GameTest(structure = FIELD, skyAccess = true, maxTicks = SCENE_TICKS + 100)
    fun theGuestsKillNobodyStandingTwentyBlocksAway(helper: GameTestHelper) {
        val rig = Rig(helper)
        try {
            rig.floor(31)
            val pistache = rig.player(BlockPos(10, 1, 16))
            val passersBy = listOf(rig.player(BlockPos(30, 1, 12)), rig.player(BlockPos(30, 1, 20)))
            val result = rig.punish(pistache, PunishmentId.GUESTS, 2, act = 1)
            helper.assertEquals(ToolStatus.OK, result.status, result.text)
            helper.assertEquals(3, rig.creaturesOf(pistache).size, "three guests: ${result.text}")

            val strangers = mutableSetOf<String>()
            val hurt = mutableSetOf<String>()
            var lowest = pistache.live.health
            helper.onEachTick {
                rig.players.forEach { it.live.doTick() } // what a client's ticks do: the cooldown between two blows, healing
                for (guest in rig.creaturesOf(pistache)) {
                    guest.target?.takeIf { it !== pistache.live }?.let { strangers += it.plainTextName }
                }
                passersBy.filter { it.live.health < it.live.maxHealth }.forEach { hurt += it.name }
                lowest = minOf(lowest, pistache.live.health)
            }
            helper.runAfterDelay(SCENE_TICKS.toLong()) {
                try {
                    helper.assertEquals(emptySet<String>(), strangers, "no guest ever went for a passer-by")
                    helper.assertEquals(emptySet<String>(), hurt, "no passer-by took a blow")
                    for (passerBy in passersBy) helper.assertFalse(passerBy.live.isDeadOrDying, "${passerBy.name} alive")
                    helper.assertTrue(lowest < pistache.live.maxHealth, "the guests came for their player: lowest health $lowest")
                    helper.assertFalse(pistache.live.isDeadOrDying, "Pistache alive")
                    val state = GuardStates.read(pistache.live)
                    helper.assertTrue(state.narratorKills.isEmpty() && state.lastDeathAt == null, "no death of Pistache, none counted: $state")
                } finally {
                    rig.close()
                }
                helper.succeed()
            }
        } catch (e: Throwable) {
            rig.close()
            throw e
        }
    }

    /**
     * A guest's blows, one by one: one meant for a passer-by lands nowhere and he is no target; the one that would kill
     * their player is a rescue where he stands, with his diamonds, a shield, nothing counted, and the guests sent away.
     */
    @GameTest
    fun aGuestsFatalBlowIsARescueAndAPasserByIsNeverItsTarget(helper: GameTestHelper) = rig(helper) { rig ->
        rig.floor(7)
        val pistache = rig.player(BlockPos(2, 1, 2))
        val bartok = rig.player(BlockPos(5, 1, 5))
        pistache.live.inventory.add(ItemStack(Items.DIAMOND, 5))
        val result = rig.punish(pistache, PunishmentId.GUESTS, 1, act = 1)
        helper.assertEquals(ToolStatus.OK, result.status, result.text)
        val guests = rig.creaturesOf(pistache)
        helper.assertTrue(guests.isNotEmpty() && guests.all { it.getAttached(NarratorMob.TYPE)?.punishment == PunishmentId.GUESTS.id }, "the guests bear their punishment")
        val guest = guests.first()

        guest.target = bartok.live
        helper.assertTrue(guest.target !== bartok.live, "a passer-by is no target: ${guest.target}")
        bartok.live.damageCooldownTime = 0
        guest.doHurtTarget(helper.level, bartok.live)
        bartok.live.damageCooldownTime = 0
        helper.assertFalse(bartok.live.hurtServer(helper.level, helper.level.damageSources().mobAttack(guest), 4f), "nor a blow meant for another")
        helper.assertEquals(bartok.live.maxHealth, bartok.live.health, "Bartok untouched")

        val where = pistache.live.position()
        pistache.live.health = 1f
        pistache.live.damageCooldownTime = 0
        guest.doHurtTarget(helper.level, pistache.live)
        helper.assertFalse(pistache.live.isDeadOrDying, "the fatal blow is refused")
        helper.assertEquals(DeathGuard.RESCUE_HEALTH, pistache.live.health, "rescued, with three hearts")
        helper.assertTrue(pistache.live.position().distanceTo(where) < 0.01, "where he stood: ${pistache.live.position()} (was $where)")
        helper.assertEquals(5, count(pistache.live, Items.DIAMOND), "nothing lost")
        val state = GuardStates.read(pistache.live)
        helper.assertTrue(state.narratorKills.isEmpty() && state.lastDeathAt == null && !state.protectedDeath, "no death, none counted: $state")
        helper.assertTrue((state.shield?.until ?: 0L) > System.currentTimeMillis(), "shielded from the Narrator a while: ${state.shield}")
        helper.assertTrue(guests.all { it.isRemoved }, "the guests are sent away")
    }

    /**
     * A zombie the Director sends at Pistache with `spawn`, outside any punishment of the catalogue, is his alone too
     * (M4-LEFTOVERS): Bartok, next to him, is no target and takes no blow; a blow that does not kill Pistache still hurts
     * him; the one that would kill him is a rescue where he stands, nothing lost, nothing counted, the zombie sent away.
     */
    @GameTest
    fun aZombieOfSpawnHurtsItsPlayerButNeverKills(helper: GameTestHelper) = rig(helper) { rig ->
        rig.floor(7)
        val pistache = rig.player(BlockPos(2, 1, 2))
        val bartok = rig.player(BlockPos(5, 1, 5))
        pistache.live.inventory.add(ItemStack(Items.DIAMOND, 5))
        val result = rig.spawn(SpawnArgs(entity = "zombie", near = pistache.name, distance = 2, target = true, lifetimeMinutes = 1), act = 1)
        helper.assertEquals(ToolStatus.OK, result.status, result.text)
        val zombie = rig.creaturesOf(pistache).single()
        helper.assertEquals(NarratorMob.SPAWN, zombie.getAttached(NarratorMob.TYPE)?.punishment, "the zombie is bound to Pistache")

        zombie.target = bartok.live
        helper.assertTrue(zombie.target !== bartok.live, "a passer-by is no target: ${zombie.target}")
        bartok.live.damageCooldownTime = 0
        helper.assertFalse(bartok.live.hurtServer(helper.level, helper.level.damageSources().mobAttack(zombie), 4f), "nor takes its blow")
        helper.assertEquals(bartok.live.maxHealth, bartok.live.health, "Bartok untouched")

        pistache.live.damageCooldownTime = 0
        helper.assertTrue(zombie.doHurtTarget(helper.level, pistache.live), "the zombie's blow lands")
        val hurt = pistache.live.health
        helper.assertTrue(hurt < pistache.live.maxHealth && !pistache.live.isDeadOrDying, "a blow that does not kill still hurts: $hurt")
        helper.assertFalse(zombie.isRemoved, "and the zombie stays")

        val where = pistache.live.position()
        pistache.live.health = 1f
        pistache.live.damageCooldownTime = 0
        zombie.doHurtTarget(helper.level, pistache.live)
        helper.assertFalse(pistache.live.isDeadOrDying, "the fatal blow is refused")
        helper.assertEquals(DeathGuard.RESCUE_HEALTH, pistache.live.health, "rescued, with three hearts")
        helper.assertTrue(pistache.live.position().distanceTo(where) < 0.01, "where he stood: ${pistache.live.position()} (was $where)")
        helper.assertEquals(5, count(pistache.live, Items.DIAMOND), "nothing lost")
        val state = GuardStates.read(pistache.live)
        helper.assertTrue(state.narratorKills.isEmpty() && state.lastDeathAt == null && !state.protectedDeath, "no death, none counted: $state")
        helper.assertTrue((state.shield?.until ?: 0L) > System.currentTimeMillis(), "shielded from the Narrator a while: ${state.shield}")
        helper.assertTrue(zombie.isRemoved, "the zombie is sent away")
    }

    /** The guests go at the end of the punishment, at their player's death and at his departure. */
    @GameTest
    fun theGuestsGoWithThePunishmentAndWithTheirPlayer(helper: GameTestHelper) = rig(helper) { rig ->
        rig.floor(7)
        val ending = rig.player(BlockPos(1, 1, 1))
        val dying = rig.player(BlockPos(6, 1, 1))
        val leaving = rig.player(BlockPos(3, 1, 6))
        val guests = listOf(ending, dying, leaving).mapIndexed { act, player ->
            val result = rig.punish(player, PunishmentId.GUESTS, 1, act = act + 1L)
            helper.assertEquals(ToolStatus.OK, result.status, result.text)
            rig.creaturesOf(player).also { helper.assertTrue(it.isNotEmpty(), "guests for ${player.name}") }
        }

        // These guards' clock runs the punishment out (the server's would, two minutes later).
        guests[0].forEach { rig.guards.mobs.onLoad(it) }
        rig.clock.advance(Duration.ofSeconds(PunishmentCatalog.GUESTS_MINUTES * 60L + 1))
        rig.guards.check(listOf(ending.live), rig.clock.millis())
        helper.assertTrue(guests[0].all { it.isRemoved }, "gone at the end of the punishment")
        helper.assertTrue(guests[1].none { it.isRemoved } && guests[2].none { it.isRemoved }, "the others stay")

        dying.live.damageCooldownTime = 0
        dying.live.hurtServer(helper.level, helper.level.damageSources().genericKill(), 1_000f)
        helper.assertTrue(dying.live.isDeadOrDying, "an operator's /kill")
        helper.assertTrue(guests[1].all { it.isRemoved }, "gone with their player's death")

        leaving.close()
        helper.assertTrue(guests[2].all { it.isRemoved }, "gone with their player's departure")
    }

    /**
     * Each test's guards on a test clock that starts now, with the server's players; the creatures `spawn` summons are
     * registered by the server's load event with the server's guards, which the game's hooks use.
     */
    private class Rig(val helper: GameTestHelper) : AutoCloseable {
        val clock = TestClock(Instant.now())
        private val settings = testSettings(NarratorConfig(chaos = 3))
        private val base = TestServices(minecraft = helper.level.server, clock = clock, settings = settings, players = helper.narrator().players)
        val guards = GuardsImpl(base, clock)
        private val services =
            TestServices(minecraft = helper.level.server, clock = clock, settings = settings, players = helper.narrator().players, guards = guards, trace = base.trace)
        private val tool = PunishTool()
        private val spawnTool = SpawnTool()
        val players = mutableListOf<TestPlayer>()

        /** A vulnerable player standing at [at] (relative), on the ground. */
        fun player(at: BlockPos): TestPlayer = TestPlayer(helper).also {
            players += it
            it.makeVulnerable() // a creature never targets a player it cannot hurt (Mob.asValidTarget)
            it.standAt(at)
            it.live.setOnGround(true)
        }

        fun floor(size: Int) {
            for (x in 0..size) for (z in 0..size) helper.setBlock(x, 0, z, Blocks.STONE)
        }

        /** `punish` of [player] as the Director's turn [act], on the server thread (its steps run inline). */
        fun punish(player: TestPlayer, id: PunishmentId, severity: Int, act: Long) = runBlocking {
            tool.run(ToolContext(services, turn(act), clock, tool.timeoutMs), PunishArgs(player.name, id, severity, REASON, "Le Mouton Candidat"))
        }

        /** The Director's own `spawn` ([args]) in its turn [act], on the server thread. */
        fun spawn(args: SpawnArgs, act: Long) = runBlocking { spawnTool.run(ToolContext(services, turn(act), clock, spawnTool.timeoutMs), args) }

        private fun turn(act: Long) = Caller(Role.DIRECTOR, "creatures-gametest", turnId = act, taskId = null)

        /** The creatures summoned for [player], loaded. */
        fun creaturesOf(player: TestPlayer): List<Mob> = Primitives.summonedFor(player.live).values.filter { !it.isRemoved }

        override fun close() {
            players.forEach { player -> runCatching { creaturesOf(player).forEach { it.discard() } } }
            players.forEach { it.close() }
        }
    }

    private companion object {
        const val FIELD = "narrator2-gametest:oracle_field"
        const val REASON = "a traité le Narrateur de dieu de pacotille"

        /** 30 seconds of game: in the acceptance, the guests killed three players in 29 s. */
        const val SCENE_TICKS = 600

        /** A test with its [Rig], closed however it ends. */
        fun rig(helper: GameTestHelper, body: (Rig) -> Unit) {
            val rig = Rig(helper)
            try {
                body(rig)
            } finally {
                rig.close()
            }
            helper.succeed()
        }
    }
}
