package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.difficulty.Difficulty
import io.github.clemsix6.narrator2.guard.BlockRestorer
import io.github.clemsix6.narrator2.guard.DeathGuard
import io.github.clemsix6.narrator2.guard.GuardStates
import io.github.clemsix6.narrator2.guard.GuardsImpl
import io.github.clemsix6.narrator2.guard.OpenEpisode
import io.github.clemsix6.narrator2.guard.Peril
import io.github.clemsix6.narrator2.guard.PunishmentCategory
import io.github.clemsix6.narrator2.guard.Rules
import io.github.clemsix6.narrator2.guard.Verdict
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.mechanics.MechanicsLimits
import io.github.clemsix6.narrator2.mechanics.PunishmentId
import io.github.clemsix6.narrator2.mechanics.punish.Primitives
import io.github.clemsix6.narrator2.mechanics.punish.PunishmentCatalog
import io.github.clemsix6.narrator2.mechanics.punish.PunishmentPlan
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.testing.testSettings
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.tool.action.PunishArgs
import io.github.clemsix6.narrator2.tool.action.PunishTool
import kotlinx.coroutines.runBlocking
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.monster.cubemob.Slime
import net.minecraft.world.entity.monster.zombie.Zombie
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.phys.Vec3
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/**
 * In-game tests of the catalogue of punishments (M4 §6.3, §21.2, M4-13): each punishment played on a test player.
 * Each test checks three things:
 * - it passes the guards: `punish` opens the episode with its category, every category it strikes rests, another
 *   act may not pile on, and the death guard covers what it can kill with (a death then counts in the Narrator's
 *   quota);
 * - it ends and clears at its deadline;
 * - a pardon lifts it.
 * Plus: the severity capped by the chaos level, `butterfingers` without a loss, `tree_top` refused while another
 * act punishes, `slime_rain` that never splits, and a trap that cannot be set opening no episode.
 *
 * Each test has its own [TestPlayer] and its own guards ([GuardsImpl] on the runtime's player states, with a test
 * clock that starts now and moves when told: the server's guards would also expire what the tests running
 * alongside need). The creatures `spawn` summons are registered by the server's load event with the server's
 * guards; the tests hand them to their own ([Bench.adopt]) and remove them however they end.
 */
class PunishGameTests {

    @GameTest
    fun tinyMakesThePlayerSmallerUntilItsEnd(helper: GameTestHelper) = bench(helper) { b ->
        b.lifecycle(
            PunishmentId.TINY, 2, listOf(Peril.ANY),
            applied = { result ->
                helper.assertTrue("scale ×0.5, poof particles, until " in result.text, result.text)
                helper.assertEquals(0.5, b.player.getAttributeValue(Attributes.SCALE), "half size")
            },
            ended = { helper.assertEquals(1.0, b.player.getAttributeValue(Attributes.SCALE), "full size again") },
        )
    }

    @GameTest
    fun moonGravityLightensThePlayerUntilItsEnd(helper: GameTestHelper) = bench(helper) { b ->
        val normal = b.player.getAttributeValue(Attributes.GRAVITY)
        b.lifecycle(
            PunishmentId.MOON_GRAVITY, 2, listOf(Peril.FALL),
            applied = { result ->
                helper.assertTrue("gravity ×0.4, end_rod particles, until " in result.text, result.text)
                helper.assertTrue(abs(b.player.getAttributeValue(Attributes.GRAVITY) - normal * 0.4) < 1e-9, "gravity ×0.4: ${b.player.getAttributeValue(Attributes.GRAVITY)}")
                helper.assertFalse(b.guards.death.attributed(b.player, b.sources.drown()), "a fall, not drowning")
            },
            ended = { helper.assertEquals(normal, b.player.getAttributeValue(Attributes.GRAVITY), "the gravity is back") },
        )
    }

    @GameTest
    fun slowHandsSlowsTheDiggingUntilItsEnd(helper: GameTestHelper) = bench(helper) { b ->
        b.lifecycle(
            PunishmentId.SLOW_HANDS, 3, listOf(Peril.ANY),
            applied = { result ->
                helper.assertTrue("block_break_speed ×0.3, mining_fatigue I, until " in result.text, result.text)
                helper.assertTrue(abs(b.player.getAttributeValue(Attributes.BLOCK_BREAK_SPEED) - 0.3) < 1e-9, "digging ×0.3")
                helper.assertEquals(0 to 180 * 20, b.player.getEffect(MobEffects.MINING_FATIGUE)?.let { it.amplifier to it.duration }, "mining fatigue I for 3 minutes")
            },
            runOut = { b.tickWhile { b.player.hasEffect(MobEffects.MINING_FATIGUE) } },
            ended = {
                helper.assertEquals(1.0, b.player.getAttributeValue(Attributes.BLOCK_BREAK_SPEED), "digging speed back")
                helper.assertFalse(b.player.hasEffect(MobEffects.MINING_FATIGUE), "no fatigue")
            },
        )
    }

    /** `heavy_feet` from severity 2 halves the jump too: its jump step strikes `gravity`, which rests as well. */
    @GameTest
    fun heavyFeetSlowsAndGroundsThePlayerUntilItsEnd(helper: GameTestHelper) = bench(helper) { b ->
        val jump = b.player.getAttributeValue(Attributes.JUMP_STRENGTH)
        b.lifecycle(
            PunishmentId.HEAVY_FEET, 2, listOf(Peril.ANY, Peril.FALL),
            applied = { result ->
                helper.assertTrue("slowness I, jump_strength ×0.5, until " in result.text, result.text)
                helper.assertEquals(0 to 90 * 20, b.player.getEffect(MobEffects.SLOWNESS)?.let { it.amplifier to it.duration }, "slowness I for 90 s")
                helper.assertTrue(abs(b.player.getAttributeValue(Attributes.JUMP_STRENGTH) - jump * 0.5) < 1e-9, "half the jump")
            },
            runOut = { b.tickWhile { b.player.hasEffect(MobEffects.SLOWNESS) } },
            ended = {
                helper.assertFalse(b.player.hasEffect(MobEffects.SLOWNESS), "no slowness")
                helper.assertEquals(jump, b.player.getAttributeValue(Attributes.JUMP_STRENGTH), "the jump is back")
            },
        )
        helper.assertTrue(PunishmentCategory.GRAVITY in b.plan(PunishmentId.HEAVY_FEET, 2).strikes, "the jump strikes gravity")
    }

    /** Played by the engine (a firing, a negative act), as a challenge's forfeit or a rule's effect would be. */
    @GameTest
    fun hungerStarvesThePlayerUntilItsEndForTheEngineToo(helper: GameTestHelper) = bench(helper) { b ->
        b.engine = true
        b.lifecycle(
            PunishmentId.HUNGER, 2, listOf(Peril.HUNGER),
            applied = { result ->
                helper.assertTrue("hunger II, until " in result.text, result.text)
                helper.assertEquals(1 to 60 * 20, b.player.getEffect(MobEffects.HUNGER)?.let { it.amplifier to it.duration }, "hunger II for a minute")
            },
            runOut = { b.tickWhile { b.player.hasEffect(MobEffects.HUNGER) } },
            ended = { helper.assertFalse(b.player.hasEffect(MobEffects.HUNGER), "no hunger") },
        )
    }

    /** From severity 3 the hotbar is shuffled with the inventory, under the death guard: nothing is lost, ever. */
    @GameTest
    fun butterfingersShufflesEverythingAndLosesNothing(helper: GameTestHelper) = bench(helper) { b ->
        var placed = emptyList<Item>()
        b.lifecycle(
            PunishmentId.BUTTERFINGERS, 3, listOf(Peril.ANY),
            ready = { placed = b.fillInventory() },
            applied = { result ->
                helper.assertTrue(" inventory and hotbar shuffled, " in result.text && result.text.contains("nothing lost"), result.text)
                val now = b.slots()
                helper.assertEquals(counts(placed), counts(now), "the same items, each once: nothing made, nothing lost")
                helper.assertTrue((0 until 9).any { now[it] != placed[it] }, "the hotbar shuffled too")
            },
            ended = { helper.assertEquals(counts(placed), counts(b.slots()), "still nothing lost") },
        )
    }

    /** Below severity 3 only the 27 slots of the inventory move: the hotbar the player holds stays, and nothing can kill. */
    @GameTest
    fun lightButterfingersLeaveTheHotbarAlone(helper: GameTestHelper) = bench(helper) { b ->
        val placed = b.fillInventory()
        val result = b.punish(PunishmentId.BUTTERFINGERS, 1, act = 1)
        helper.assertTrue(result.text.startsWith("ok: ${b.name} punished, butterfingers (severity 1) for « $REASON »: inventory shuffled, "), result.text)
        val now = b.slots()
        helper.assertEquals(placed.take(9), now.take(9), "the hotbar stays")
        helper.assertTrue((9 until 36).any { now[it] != placed[it] }, "the inventory moved")
        helper.assertEquals(counts(placed), counts(now), "nothing lost")
        helper.assertFalse(b.guards.death.isArmed(b.player), "a shuffle of the inventory alone kills nobody: nothing armed")
        helper.assertEquals(OpenEpisode(1, b.clock.millis() + PunishmentCatalog.SHUFFLE_SECONDS * 1_000L), GuardStates.read(b.player).episode, "a punishment all the same")
    }

    @GameTest
    fun guestsComeInCostumeAndLeaveAtTheirEnd(helper: GameTestHelper) = bench(helper) { b ->
        b.bob.makeVulnerable() // a creature never targets a player it cannot hurt (Mob.asValidTarget)
        b.lifecycle(
            PunishmentId.GUESTS, 2, listOf(Peril.ANY), name = "Comité d'accueil",
            applied = { result ->
                helper.assertTrue("3 baby zombies named « Comité d'accueil » going for them, until " in result.text, result.text)
                val guests = b.adopt()
                helper.assertEquals(3, guests.size, "three guests")
                for (guest in guests) {
                    helper.assertTrue(guest is Zombie && guest.isBaby, "a baby zombie: $guest")
                    helper.assertEquals("Comité d'accueil", guest.customName?.string, "named")
                    helper.assertTrue(guest.getItemBySlot(EquipmentSlot.HEAD).`is`(Items.CARVED_PUMPKIN), "a pumpkin on the head, against the sun")
                    helper.assertTrue(guest.target === b.player, "going for the player")
                }
            },
            ended = { helper.assertEquals(emptyList<Mob>(), b.summoned(), "the guests are gone") },
        )
    }

    @GameTest
    fun slimeRainFallsThenGoesAway(helper: GameTestHelper) = bench(helper) { b ->
        b.lifecycle(
            PunishmentId.SLIME_RAIN, 2, listOf(Peril.ANY),
            applied = { result ->
                helper.assertTrue("6 tiny slimes falling around them, until " in result.text, result.text)
                val slimes = b.adopt()
                helper.assertEquals(6, slimes.size, "six slimes")
                helper.assertTrue(slimes.all { it is Slime && it.size == 1 }, "all of size 1: ${slimes.map { (it as? Slime)?.size }}")
                // The difficulty layer raises it like any hostile creature's (M7-BASE).
                val health = (Difficulty.config?.takeIf { it.enabled }?.mobHealth ?: 1.0).toFloat()
                helper.assertTrue(slimes.all { it.health == health && it.maxHealth == health }, "with the health of their size, raised by the difficulty layer")
            },
            ended = { helper.assertEquals(emptyList<Mob>(), b.summoned(), "the slimes are gone") },
        )
    }

    /** A slime of size 1 never splits: none escapes the cap or the expiry (M4 review, finding 26). */
    @GameTest
    fun slimeRainNeverSplits(helper: GameTestHelper) = bench(helper) { b ->
        val result = b.punish(PunishmentId.SLIME_RAIN, 1, act = 1)
        helper.assertEquals(ToolStatus.OK, result.status, result.text)
        val slimes = b.adopt()
        helper.assertEquals(4, slimes.size, "2 + 2 slimes at severity 1")
        val victim = slimes.first() as Slime
        victim.hurtServer(helper.level, b.sources.generic(), 1_000f)
        helper.assertTrue(victim.isDeadOrDying, "the slime dies")
        victim.remove(Entity.RemovalReason.KILLED) // where a slime splits, after its death
        val left = b.summoned()
        helper.assertEquals(3, left.size, "no smaller slime came out: ${left.map { (it as? Slime)?.size }}")
        helper.assertEquals(0, helper.level.getEntitiesOfClass(Slime::class.java, victim.boundingBox.inflate(1.0)) { !it.isRemoved && it !in left }.size, "nor any other")
    }

    @GameTest
    fun skywardLaunchesThePlayerWithACushion(helper: GameTestHelper) = bench(helper) { b ->
        b.lifecycle(
            PunishmentId.SKYWARD, 2, listOf(Peril.FALL),
            applied = { result ->
                helper.assertTrue("launched up at strength 1.2 with no fall damage, until " in result.text, result.text)
                helper.assertEquals(1.2, b.player.deltaMovement.y, "thrown up")
                helper.assertEquals(0.0, b.player.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER), "no fall damage")
            },
            ended = { helper.assertEquals(1.0, b.player.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER), "the cushion ran out with the punishment") },
            // The cushion is a favor: a pardon leaves it to its own end.
            pardoned = { helper.assertEquals(0.0, b.player.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER), "the cushion stays") },
        )
    }

    /**
     * On top of the tree, to climb down by themselves. Any death up there is the Narrator's (M4 review, finding 10:
     * a skeleton or phantoms, not only the fall), so the player keeps their inventory; a fall stays covered longer,
     * by the height of the climb. Nothing to undo at the end.
     */
    @GameTest
    fun treeTopPutsThePlayerOnTheLeaves(helper: GameTestHelper) = bench(helper) { b ->
        val top = tree(helper)
        try {
            b.lifecycle(
                PunishmentId.TREE_TOP, 2, listOf(Peril.ANY, Peril.FALL),
                ready = { b.bob.standAt(BlockPos(1, 1, 1)) },
                applied = { result ->
                    helper.assertTrue("put on top of oak_leaves at x ${top.x}, y ${top.y + 1}, z ${top.z}, ${TREE_TOP} blocks above where they stood" in result.text, result.text)
                    helper.assertEquals(Vec3.atBottomCenterOf(top.above()), b.player.position(), "on the leaves")
                    helper.assertTrue(b.guards.death.attributed(b.player, b.sources.generic()), "any death on the tree top is the Narrator's")
                    val climb = PunishmentCatalog.TREE_TOP_SECONDS + TREE_TOP * PunishmentCatalog.TREE_TOP_FALL_SECONDS_PER_BLOCK
                    helper.assertTrue(b.guards.death.armedFor(b.player, Peril.FALL) >= DeathGuard.playTime(b.player) + climb * 20L, "a fall is covered for the climb down")
                    helper.assertFalse(b.guards.isPunished(b.player), "nothing to lift on a tree top: a joker is kept")
                },
                ended = { helper.assertEquals(Vec3.atBottomCenterOf(top.above()), b.player.position(), "still up there: they climb down by themselves") },
            )
        } finally {
            fell(helper)
        }
    }

    /**
     * `tree_top` opens its own episode (a short teleport is not hostile for `teleport`): refused while the teleport another
     * act struck rests; on another act's punishment of another kind, it stacks (M7-LOOSEN).
     */
    @GameTest
    fun treeTopIsRefusedWhileItsKindRests(helper: GameTestHelper) = bench(helper) { b ->
        tree(helper)
        try {
            val start = b.player.position()
            helper.assertEquals(Verdict.Allowed, b.guards.hostile(b.player, b.caller(7), b.clock.millis() + 60_000, PunishmentCategory.TELEPORT), "another act teleports")
            val result = b.punish(PunishmentId.TREE_TOP, 1, act = 8)
            helper.assertTrue(result.text.startsWith("DENIED (${Rules.SAME_PUNISHMENT}): ${b.name} was already teleported"), result.text)
            helper.assertEquals(start, b.player.position(), "not moved")
            helper.assertEquals(1, b.guards.pacing(listOf(b.bob.uuid), b.clock.millis()).single().punishedThisHour, "not counted")
        } finally {
            fell(helper)
        }
    }

    @GameTest
    fun smiteStrikesThePlayerOnce(helper: GameTestHelper) = bench(helper) { b ->
        b.bob.makeVulnerable()
        var before = 0f
        b.lifecycle(
            PunishmentId.SMITE, 2, listOf(Peril.ANY),
            // A new bolt, not a blow within the cooldown of the last one.
            ready = {
                b.player.damageCooldownTime = 0
                before = b.player.health
            },
            applied = { result ->
                helper.assertTrue("lightning struck ${b.name} for 2 hearts (health " in result.text, result.text)
                helper.assertEquals(before - 4f, b.player.health, "two hearts")
                // M4 review, finding 35: the bolt is done, only its episode is left; a joker would lift nothing.
                helper.assertFalse(b.guards.isPunished(b.player), "nothing to lift after a bolt")
            },
            ended = { helper.assertEquals(before - 4f, b.player.health, "the wound stays, the punishment is over") },
        )
    }

    /** The glass cage (M4 §9.2): set in the air for 10 s per point of severity, taken away by itself or by a pardon. */
    @GameTest(maxTicks = TRAP_MAX_TICKS)
    fun aCageHoldsThePlayerThenGoesAway(helper: GameTestHelper) = trapLifecycle(helper, PunishmentId.CAGE, Blocks.GLASS, "a glass cage of 25 blocks (taken away by itself)")

    @GameTest(maxTicks = TRAP_MAX_TICKS)
    fun cobwebsHoldThePlayerThenGoAway(helper: GameTestHelper) = trapLifecycle(helper, PunishmentId.WEB, Blocks.COBWEB, "6 cobwebs (taken away by themselves)")

    /** A trap the terrain refuses (the player in the air) changes nothing: no episode, no punishment counted. */
    @GameTest
    fun aTrapThatCannotBeSetOpensNoEpisode(helper: GameTestHelper) = bench(helper) { b ->
        b.player.setOnGround(false)
        val cage = b.punish(PunishmentId.CAGE, 2, act = 1)
        helper.assertEquals("FAILED: cannot trap ${b.name} in a glass cage: not on the ground; nothing was done", cage.text, "cage")
        val web = b.punish(PunishmentId.WEB, 2, act = 2)
        helper.assertEquals("FAILED: cannot trap ${b.name} in cobwebs: not on the ground; nothing was done", web.text, "web")
        helper.assertEquals(null, GuardStates.read(b.player).episode, "no episode")
        val pacing = b.guards.pacing(listOf(b.bob.uuid), b.clock.millis()).single()
        helper.assertEquals(0 to emptySet<PunishmentCategory>(), pacing.punishedThisHour to pacing.resting.keys, "nothing counted, nothing resting")
        helper.assertEquals(emptyList<Any>(), BlockRestorer.activeOn(b.bob.uuid), "no block set")
        helper.assertFalse(b.guards.death.isArmed(b.player), "nothing armed")
    }

    /**
     * One punish per act and player (M4 review, finding 16): the act that starved Bartok may not also strike him, nor
     * pile a third punishment on; another player is not concerned.
     */
    @GameTest
    fun anActPunishesAPlayerOnce(helper: GameTestHelper) = bench(helper) { b ->
        b.bob.makeVulnerable()
        val first = b.punish(PunishmentId.HUNGER, 1, act = 1)
        helper.assertEquals(ToolStatus.OK, first.status, first.text)
        b.player.damageCooldownTime = 0
        val health = b.player.health
        val smite = b.punish(PunishmentId.SMITE, 2, act = 1)
        helper.assertEquals(
            "DENIED (${Rules.ALREADY_PUNISHED}): this act already punished ${b.name} (hunger); one punish per act and player: compose it with the other tools, " +
                "or punish again in another turn.",
            smite.text, "a second punish of the same act",
        )
        helper.assertEquals(health, b.player.health, "not struck")
        helper.assertTrue(b.punish(PunishmentId.TINY, 1, act = 1).text.startsWith("DENIED (${Rules.ALREADY_PUNISHED}): this act already punished "), "nor a third")
        helper.assertEquals(1.0, b.player.getAttributeValue(Attributes.SCALE), "not made smaller")
        val pacing = b.guards.pacing(listOf(b.bob.uuid), b.clock.millis()).single()
        helper.assertEquals(1 to setOf(PunishmentCategory.FOOD), pacing.punishedThisHour to pacing.resting.keys, "one punishment, one category struck")
    }

    /**
     * A punish that does nothing at all (here a bolt that finds only lightning rods above the player, whom it would
     * change) counts nowhere (M4 review, finding 39): no episode, not among the hour's punishments, its category not
     * resting; the same act may punish again.
     */
    @GameTest
    fun aPunishThatDidNothingCountsNowhere(helper: GameTestHelper) = bench(helper) { b ->
        val rod = Blocks.LIGHTNING_ROD.asList().first()
        try {
            for (y in 1..5) helper.setBlock(BlockPos(3, y, 3), rod)
            b.stand()
            val result = b.punish(PunishmentId.SMITE, 2, act = 1)
            helper.assertEquals(ToolStatus.FAILED, result.status, result.text)
            helper.assertTrue(result.text.startsWith("${b.name}'s smite failed: FAILED: copper or a lightning rod everywhere above"), result.text)
            helper.assertEquals(null, GuardStates.read(b.player).episode, "no episode")
            val pacing = b.guards.pacing(listOf(b.bob.uuid), b.clock.millis()).single()
            helper.assertEquals(0 to emptySet<PunishmentCategory>(), pacing.punishedThisHour to pacing.resting.keys, "nothing counted, nothing resting")
            helper.assertTrue(b.decisions().any { it.decision == "withdrawn" && it.subject == b.name }, "a withdrawn decision")
        } finally {
            for (y in 1..5) helper.setBlock(BlockPos(3, y, 3), Blocks.AIR)
        }
        b.stand()
        helper.assertEquals(ToolStatus.OK, b.punish(PunishmentId.HUNGER, 1, act = 1).status, "the same act may punish once more")
    }

    /** The severity is capped by the chaos level (M4 §6.2): beyond, an error; within, the caps still bound the values, and say so. */
    @GameTest
    fun theSeverityIsCappedByTheChaosLevel(helper: GameTestHelper) = bench(helper, chaos = 1) { b ->
        val tooMuch = b.punish(PunishmentId.TINY, 3, act = 1)
        helper.assertEquals("Invalid arguments: at chaos 1 a punishment has a severity of at most 2 (asked 3)", tooMuch.text, "severity 3 at chaos 1")
        helper.assertEquals(null, GuardStates.read(b.player).episode, "nothing opened")
        val capped = b.punish(PunishmentId.TINY, 2, act = 1)
        helper.assertTrue(capped.text.endsWith(", until ${clock(b, 60)} (in 1m); duration capped at 60s by the chaos level"), capped.text)
        helper.assertEquals(0.5, b.player.getAttributeValue(Attributes.SCALE), "the severity's value")
        helper.assertEquals(b.clock.millis() + 60_000, GuardStates.read(b.player).modifiers.single().expiresAt, "for 60 s, the chaos level's longest")
        // One punish per act and player: the guests come with another act, once the first punishment is over.
        b.advance(Duration.ofSeconds(61))
        val guests = b.punish(PunishmentId.GUESTS, 2, act = 2)
        helper.assertTrue(guests.text.startsWith("ok: ") && guests.text.endsWith("; zombies capped at 2 by the chaos level"), guests.text)
        helper.assertEquals(2, b.adopt().size, "two guests at chaos 1")
    }

    // ── Shared ──────────────────────────────────────────────────────────────────────────────────

    /**
     * A trap's life, on the wall clock of the `BlockRestorer`: set around the player standing in the middle of the
     * area, under the guards; pardoned (taken away at once); its category resting; set again at severity 1 and taken
     * away by itself 10 s later.
     */
    private fun trapLifecycle(helper: GameTestHelper, id: PunishmentId, block: net.minecraft.world.level.block.Block, what: String) {
        floor(helper)
        val b = Bench(helper, 3)
        helper.closingOnFailure(b, TRAP_MAX_TICKS) {
            b.stand()
            val first = b.punish(id, 2, act = 1)
            helper.assertTrue(first.text.startsWith("ok: ${b.name} punished, ${id.id} (severity 2) for « $REASON »: $what, until ${clock(b, 20)} (in 20s)"), first.text)
            val held = blocks(helper, block)
            helper.assertTrue(held.isNotEmpty() && BlockRestorer.activeOn(b.bob.uuid).isNotEmpty(), "the trap holds the player")
            b.guarded(b.plan(id, 2), act = 1, perils = listOf(Peril.ANY))

            val lifted = b.pardon(5)
            helper.assertEquals(listOf("${held.size} trap blocks taken away"), lifted, "what the pardon lifted")
            helper.assertTrue(blocks(helper, block).isEmpty(), "taken away at once")
            b.ended("pardoned")
            helper.assertTrue(b.punish(id, 2, act = 3).text.startsWith("DENIED (${Rules.SAME_PUNISHMENT}): "), "the trap category rests")

            b.advance(Duration.ofMinutes(31))
            b.stand()
            val second = b.punish(id, 1, act = 4)
            helper.assertEquals(ToolStatus.OK, second.status, second.text)
            helper.assertTrue(blocks(helper, block).isNotEmpty(), "set again")
            helper.startSequence()
                .thenAwait { helper.assertTrue(blocks(helper, block).isEmpty() && BlockRestorer.activeOn(b.bob.uuid).isEmpty(), "taken away by itself") }
                .thenExecute {
                    b.advance(Duration.ofSeconds(MechanicsLimits.TRAP_SECONDS_PER_SEVERITY + 1L))
                    b.ended("at its deadline")
                    b.close()
                }
                .thenSucceed()
        }
    }

    /** One test's player, guards of its own on a clock it moves, and `punish` with the caller of a turn or of the engine. */
    private class Bench(val helper: GameTestHelper, chaos: Int) : AutoCloseable {
        val clock = TestClock(Instant.now())
        private val settings = testSettings(NarratorConfig(chaos = chaos))
        private val base = TestServices(minecraft = helper.level.server, clock = clock, settings = settings, players = helper.narrator().players)
        val guards = GuardsImpl(base, clock)
        val services = TestServices(minecraft = helper.level.server, clock = clock, settings = settings, players = helper.narrator().players, guards = guards, trace = base.trace)
        val bob = TestPlayer(helper)
        val player: ServerPlayer get() = bob.live
        val name: String get() = bob.name
        val sources get() = helper.level.damageSources()
        private val tool = PunishTool()

        /** Whether the acts are the engine's (firings) rather than the Director's turns. */
        var engine = false

        fun caller(act: Long): Caller =
            if (engine) Caller(Role.DIRECTOR, "mechanics:C-60", turnId = null, taskId = null, firingId = act) else Caller(Role.DIRECTOR, "punish-gametest", turnId = act, taskId = null)

        /** The act of [caller] for the guards: the turn, or minus the firing. */
        fun act(id: Long): Long = caller(id).act!!

        /** `punish` as the act [act], on the server thread (its steps run inline). */
        fun punish(id: PunishmentId, severity: Int, act: Long, name: String? = null): ToolResult = runBlocking {
            tool.run(ToolContext(services, caller(act), clock, tool.timeoutMs), PunishArgs(bob.name, id, severity, REASON, name))
        }

        fun plan(id: PunishmentId, severity: Int): PunishmentPlan = PunishmentCatalog.plan(id, severity, guards.caps(), bob.name)

        /** The decisions of these guards, in order. */
        fun decisions() = base.traces.decisions

        /** Moves the clock, then the guards undo what is due, as their check of each second does. */
        fun advance(duration: Duration) {
            clock.advance(duration)
            guards.check(listOf(player), clock.millis())
        }

        fun pardon(act: Long): List<String> = guards.pardon(player, caller(act))

        /** Standing on the floor in the middle of the area. */
        fun stand() {
            bob.standAt(BlockPos(3, 1, 3))
            player.setOnGround(true)
        }

        /** Ticks the player (a test player's connection does not) while [running], as the game runs effects out. */
        fun tickWhile(running: () -> Boolean) {
            repeat(MAX_EFFECT_TICKS) { if (running()) player.doTick() }
            helper.assertFalse(running(), "ran out")
        }

        /** The creatures `spawn` summoned for the player, loaded. */
        fun summoned(): List<Mob> = Primitives.summonedFor(player).values.filter { !it.isRemoved }

        /** Hands the player's creatures to these guards (the server's load event gave them to the server's): they expire and go with a pardon. */
        fun adopt(): List<Mob> = summoned().onEach { guards.mobs.onLoad(it) }

        /** 36 different items in the 36 slots of the inventory, hotbar first; what is where. */
        fun fillInventory(): List<Item> {
            val items = BuiltInRegistries.ITEM.asSequence().filter { it != Items.AIR }.take(36).toList()
            items.forEachIndexed { slot, item -> player.inventory.setItem(slot, ItemStack(item)) }
            return items
        }

        fun slots(): List<Item> = (0 until 36).map { player.inventory.getItem(it).item }

        /**
         * The first `punish` of a lifecycle: its episode opened with its category until its end, every category it
         * strikes resting, the death guard armed for [perils] (a death by one of them counts in the Narrator's quota),
         * and another act refused its kind while it rests, though not another kind (punishments stack, M7-LOOSEN).
         */
        fun guarded(plan: PunishmentPlan, act: Long, perils: List<Peril>) {
            val now = clock.millis()
            helper.assertEquals(OpenEpisode(act(act), now + plan.seconds * 1_000L), GuardStates.read(player).episode, "the episode punish opened, until its end")
            val pacing = guards.pacing(listOf(bob.uuid), now).single()
            helper.assertEquals(1, pacing.punishedThisHour, "one punishment this hour")
            helper.assertEquals(plan.strikes, pacing.resting.keys, "the categories it struck rest")
            plan.peril?.let { helper.assertTrue(guards.death.armedFor(player, it) >= DeathGuard.playTime(player) + plan.seconds * 20L, "the death guard covers ${it.id} as long as it runs") }
            for (peril in perils) helper.assertTrue(guards.death.attributed(player, sourceOf(peril)), "a death by ${peril.id} would be the Narrator's, in its quota")
            val other = guards.vet(player, caller(act + 1), plan.category)
            helper.assertTrue(other is Verdict.Denied && other.rule == Rules.SAME_PUNISHMENT, "another act may not strike its kind again so soon: $other")
            helper.assertEquals(Verdict.Allowed, guards.vet(player, caller(act + 1), null), "another act may punish them otherwise")
        }

        /** Nothing of the punishment left. */
        fun ended(how: String) {
            helper.assertFalse(guards.isPunished(player), "nothing inflicted any more ($how)")
            helper.assertEquals(null, GuardStates.read(player).episode, "the episode is over ($how)")
        }

        /**
         * The life of [id] at [severity]: applied ([applied]) under the guards ([guarded]); at its deadline, what
         * the game runs out by itself ([runOut]) and what the guards undo, then [ended]; its category resting
         * against another act; applied again later ([ready] first, each time) and pardoned, then [pardoned].
         */
        fun lifecycle(
            id: PunishmentId,
            severity: Int,
            perils: List<Peril>,
            name: String? = null,
            ready: () -> Unit = {},
            applied: (ToolResult) -> Unit,
            runOut: () -> Unit = {},
            ended: () -> Unit,
            pardoned: () -> Unit = ended,
        ) {
            val plan = plan(id, severity)
            ready()
            val first = punish(id, severity, act = 1, name = name)
            helper.assertEquals(ToolStatus.OK, first.status, first.text)
            helper.assertTrue(first.text.startsWith("ok: ${bob.name} punished, ${id.id} (severity $severity) for « $REASON »: "), first.text)
            helper.assertEquals(listOf(bob.ref), first.targets, "the player, for their history")
            applied(first)
            guarded(plan, act = 1, perils = perils)

            runOut()
            advance(Duration.ofSeconds(plan.seconds + 1L))
            ended()
            ended("at its deadline")
            ready()
            val again = punish(id, severity, act = 3, name = name)
            helper.assertTrue(again.text.startsWith("DENIED (${Rules.SAME_PUNISHMENT}): "), "its category rests: ${again.text}")

            advance(Duration.ofMinutes(31))
            ready()
            val second = punish(id, severity, act = 4, name = name)
            helper.assertEquals(ToolStatus.OK, second.status, second.text)
            applied(second)
            val lifted = pardon(5)
            helper.assertTrue(lifted.isNotEmpty(), "the pardon lifts it")
            pardoned()
            ended("pardoned")
        }

        override fun close() {
            summoned().forEach { it.discard() }
            bob.close()
        }

        private fun sourceOf(peril: Peril): DamageSource = when (peril) {
            Peril.FALL -> sources.fall()
            Peril.HUNGER -> sources.starve()
            Peril.MAGIC -> sources.magic()
            Peril.DROWNING -> sources.drown()
            Peril.FIRE -> sources.onFire()
            Peril.ANY -> sources.generic()
        }
    }

    private companion object {
        const val REASON = "a traité le Narrateur de dieu de pacotille"

        /** Ticks of the trap tests: a trap of 10 s on the wall clock, waiting at least 1 ms a tick. */
        const val TRAP_MAX_TICKS = 30_000

        /** The longest effect of these tests (3 minutes) in ticks, and some. */
        const val MAX_EFFECT_TICKS = 180 * 20 + 20

        /** A test with its [Bench], standing on a floor; the bench closed however it ends. */
        fun bench(helper: GameTestHelper, chaos: Int = 3, body: (Bench) -> Unit) {
            floor(helper)
            val b = Bench(helper, chaos)
            try {
                b.stand()
                body(b)
            } finally {
                b.close()
            }
            helper.succeed()
        }

        fun floor(helper: GameTestHelper) {
            for (x in 0..7) for (z in 0..7) helper.setBlock(x, 0, z, Blocks.STONE)
        }

        /**
         * The top of a tree, 10 blocks above the floor: above the barriers that roof the test's area (8 blocks up),
         * which a tree top never stands on (unbreakable). Its leaves, absolute.
         */
        fun tree(helper: GameTestHelper): BlockPos {
            helper.setBlock(BlockPos(6, TREE_TOP, 6), Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true))
            return helper.absolutePos(BlockPos(6, TREE_TOP, 6))
        }

        fun fell(helper: GameTestHelper) = helper.setBlock(6, TREE_TOP, 6, Blocks.AIR)

        const val TREE_TOP = 10

        /** How many of each item [items] holds. */
        fun counts(items: List<Item>): Map<Item, Int> = items.groupingBy { it }.eachCount()

        /** The cells of the area (and the layer above it) that hold [block]. */
        fun blocks(helper: GameTestHelper, block: net.minecraft.world.level.block.Block): List<BlockPos> =
            BlockPos.betweenClosed(helper.absolutePos(BlockPos(0, 0, 0)), helper.absolutePos(BlockPos(7, 8, 7)))
                .filter { helper.level.getBlockState(it).`is`(block) }.map { it.immutable() }

        /** The time of day [seconds] after [b]'s clock, as the tools write it. */
        fun clock(b: Bench, seconds: Long): String =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").format(Instant.ofEpochMilli(b.clock.millis() + seconds * 1_000).atZone(b.clock.zone))
    }
}
