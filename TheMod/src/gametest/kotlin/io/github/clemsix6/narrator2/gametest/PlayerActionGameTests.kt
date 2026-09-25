package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.guard.AttributeKey
import io.github.clemsix6.narrator2.guard.GuardStates
import io.github.clemsix6.narrator2.guard.GuardsImpl
import io.github.clemsix6.narrator2.guard.Rules
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.tool.action.ApplyEffectArgs
import io.github.clemsix6.narrator2.tool.action.ApplyEffectTool
import io.github.clemsix6.narrator2.tool.action.EnchantmentSpec
import io.github.clemsix6.narrator2.tool.action.GiftLimiter
import io.github.clemsix6.narrator2.tool.action.GiveItemArgs
import io.github.clemsix6.narrator2.tool.action.GiveItemTool
import io.github.clemsix6.narrator2.tool.action.ItemSpecs
import io.github.clemsix6.narrator2.tool.action.LaunchArgs
import io.github.clemsix6.narrator2.tool.action.LaunchDirection
import io.github.clemsix6.narrator2.tool.action.LaunchTool
import io.github.clemsix6.narrator2.tool.action.RemoveEffectArgs
import io.github.clemsix6.narrator2.tool.action.RemoveEffectTool
import io.github.clemsix6.narrator2.tool.action.ResetAttributeArgs
import io.github.clemsix6.narrator2.tool.action.ResetAttributeTool
import io.github.clemsix6.narrator2.tool.action.SetAttributeArgs
import io.github.clemsix6.narrator2.tool.action.SetAttributeTool
import io.github.clemsix6.narrator2.tool.action.TakeItemArgs
import io.github.clemsix6.narrator2.tool.action.TakeItemTool
import io.github.clemsix6.narrator2.tool.action.TeleportArgs
import io.github.clemsix6.narrator2.tool.action.TeleportTool
import io.github.clemsix6.narrator2.tool.action.forbiddenEffectIds
import io.github.clemsix6.narrator2.tool.speech.Speech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.chat.Style
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.block.Blocks
import java.time.Clock
import java.util.concurrent.atomic.AtomicReference

/**
 * In-game tests of the actions on players (M1_M2 §9.3, §17.3): effects applied, replaced, capped and
 * refused; attributes kept within their bounds and put back; a teleport onto safe ground; a launch and
 * its fall protection; items given with their components, and confiscated then given back.
 *
 * Each test has its own [TestPlayer]s (unique names: the tools address players by name) and removes
 * them. Punishments are per player, so tests running side by side never meet; the caps are read from the
 * guards, whatever the chaos level of the test server.
 */
class PlayerActionGameTests {

    // ── Effects ─────────────────────────────────────────────────────────────────────────────────

    @GameTest
    fun anEffectIsAppliedThenReplacedByAWeakerOne(helper: GameTestHelper) = withPlayer(helper) { bob ->
        val tool = ApplyEffectTool()
        val first = run(helper, tool, ApplyEffectArgs(listOf(bob.name.uppercase()), "slowness", seconds = 60, level = 2))
        helper.assertTrue(first.text.startsWith("ok: ${bob.name} gets slowness II for 60 s (until "), first.text)
        val slowness = bob.live.getEffect(MobEffects.SLOWNESS)!!
        helper.assertEquals(1 to 1_200, slowness.amplifier to slowness.duration, "level II for 60 s")
        val entry = GuardStates.read(bob.live).effects.single()
        helper.assertEquals("minecraft:slowness" to true, entry.effect to entry.harmful, "in the ledger, as a punishment")
        helper.assertEquals(CALLER.turnId, GuardStates.read(bob.live).episode?.turnId, "the turn's punishment is open")
        helper.assertTrue(guards(helper).death.isArmed(bob.live), "a harmful effect arms the death guard")

        // Weaker and shorter: addEffect alone would keep the stronger one.
        val second = run(helper, tool, ApplyEffectArgs(listOf(bob.name), "minecraft:slowness", seconds = 30))
        helper.assertEquals(ToolStatus.OK, second.status, second.text)
        val replaced = bob.live.getEffect(MobEffects.SLOWNESS)!!
        helper.assertEquals(0 to 600, replaced.amplifier to replaced.duration, "replaced by level I for 30 s")

        // A beneficial effect is not capped by the chaos level.
        val gift = run(helper, tool, ApplyEffectArgs(listOf(bob.name), "speed", seconds = 900, level = 5, particles = false))
        helper.assertEquals(ToolStatus.OK, gift.status, gift.text)
        helper.assertEquals(4, bob.live.getEffect(MobEffects.SPEED)?.amplifier, "speed V")
        helper.assertFalse(GuardStates.read(bob.live).effects.first { it.effect == "minecraft:speed" }.harmful, "a gift")
    }

    @GameTest
    fun aHarmfulEffectBeyondTheCapsIsRefused(helper: GameTestHelper) = withPlayer(helper) { bob ->
        val caps = guards(helper).caps()
        val tool = ApplyEffectTool()

        val tooStrong = run(helper, tool, ApplyEffectArgs(listOf(bob.name), "poison", seconds = 10, level = caps.harmfulLevel + 1))
        helper.assertEquals(ToolStatus.INVALID, tooStrong.status, tooStrong.text)
        helper.assertTrue(tooStrong.text.contains("poison is harmful: at chaos ") && tooStrong.text.contains("up to level ${caps.harmfulLevel}"), tooStrong.text)
        val tooLong = run(helper, tool, ApplyEffectArgs(listOf(bob.name), "poison", seconds = caps.harmfulSeconds + 1))
        helper.assertTrue(tooLong.text.contains("lasts at most ${caps.harmfulSeconds} s"), tooLong.text)

        helper.assertFalse(bob.live.hasEffect(MobEffects.POISON), "nothing applied")
        helper.assertEquals(null, GuardStates.read(bob.live).episode, "no punishment opened")
    }

    /** The effects that act on the world (omens, silverfish, slimes, cobwebs, wind bursts) are never given; unknown ids get suggestions. */
    @GameTest
    fun effectsThatActOnTheWorldAreNeverGiven(helper: GameTestHelper) = withPlayer(helper) { bob ->
        val tool = ApplyEffectTool()
        for (id in forbiddenEffectIds) {
            helper.assertTrue(BuiltInRegistries.MOB_EFFECT.containsKey(Identifier.parse(id)), "$id exists in 26.3")
            val result = run(helper, tool, ApplyEffectArgs(listOf(bob.name), id, seconds = 10))
            helper.assertEquals(ToolStatus.DENIED, result.status, result.text)
            helper.assertTrue(result.text.startsWith("DENIED (forbidden_effect): ${id.removePrefix("minecraft:")} is never given: "), result.text)
        }
        helper.assertTrue(bob.live.activeEffects.isEmpty(), "nothing applied: ${bob.live.activeEffects}")

        val unknown = run(helper, tool, ApplyEffectArgs(listOf(bob.name), "slownes", seconds = 10))
        helper.assertEquals(ToolStatus.INVALID, unknown.status, unknown.text)
        helper.assertTrue(unknown.text.startsWith("Invalid arguments: unknown effect \"slownes\"; closest ids: slowness"), unknown.text)
        val absent = run(helper, tool, ApplyEffectArgs(listOf("nobody_here"), "speed", seconds = 10))
        helper.assertTrue(absent.text.startsWith("Invalid arguments: nobody_here is not online; online: "), absent.text)
    }

    /** No piling on a player who just died: a harmful effect is refused, a gift is not. */
    @GameTest
    fun aPlayerWhoJustDiedIsNotPunished(helper: GameTestHelper) = withPlayer(helper) { bob ->
        bob.makeVulnerable()
        bob.live.hurtServer(helper.level, helper.level.damageSources().magic(), 1_000f)
        helper.assertTrue(bob.live.isDeadOrDying, "Bob died")
        helper.level.server.playerList.respawn(bob.live, false, Entity.RemovalReason.KILLED)

        val punished = run(helper, ApplyEffectTool(), ApplyEffectArgs(listOf(bob.name), "slowness", seconds = 30))
        helper.assertEquals(ToolStatus.DENIED, punished.status, punished.text)
        helper.assertTrue(punished.text.startsWith("DENIED (${Rules.NO_PILE_ON}): ${bob.name} "), punished.text)
        helper.assertFalse(bob.live.hasEffect(MobEffects.SLOWNESS), "no slowness")

        val gift = run(helper, ApplyEffectTool(), ApplyEffectArgs(listOf(bob.name), "regeneration", seconds = 30))
        helper.assertEquals(ToolStatus.OK, gift.status, gift.text)
    }

    /** With several players, a refusal for one does not stop the others, and the answer names it. */
    @GameTest
    fun aRefusalForOnePlayerSparesTheOthers(helper: GameTestHelper) = withTestPlayers(helper, 2) { (bob, alice) ->
        val guards = guards(helper)
        guards.setProtected(alice.uuid, alice.name, true)
        try {
            val result = run(helper, ApplyEffectTool(), ApplyEffectArgs(listOf(bob.name, alice.name), "weakness", seconds = 20))

            helper.assertEquals(ToolStatus.OK, result.status, result.text)
            helper.assertTrue(result.text.startsWith("ok: ${bob.name} gets weakness I for 20 s (until "), result.text)
            helper.assertTrue(result.text.contains("; not for the others: DENIED (${Rules.PROTECTED}): ${alice.name} is protected"), result.text)
            helper.assertTrue(bob.live.hasEffect(MobEffects.WEAKNESS), "Bob is weakened")
            helper.assertFalse(alice.live.hasEffect(MobEffects.WEAKNESS), "Alice is spared")
        } finally {
            guards.setProtected(alice.uuid, alice.name, false)
        }
    }

    /** An instant effect is applied once, whatever the seconds asked: instant_damage every tick for a minute would kill. */
    @GameTest(maxTicks = 100)
    fun anInstantEffectHitsOnce(helper: GameTestHelper) {
        val bob = TestPlayer(helper)
        helper.closingOnFailure(bob, 100) {
            bob.standAt(BlockPos(1, 1, 1))
            bob.makeVulnerable()
            val result = run(helper, ApplyEffectTool(), ApplyEffectArgs(listOf(bob.name), "instant_damage", seconds = 60))
            helper.assertEquals("ok: instant_damage I on ${bob.name} (health 14/20)", result.text, "result")
            helper.assertTrue(bob.live.activeEffects.isEmpty(), "nothing lasts")
            helper.startSequence()
                .thenIdle(20)
                .thenExecute {
                    // Natural regeneration may have healed a little since; a second hit would show.
                    helper.assertTrue(bob.live.health >= 14f && !bob.live.isDeadOrDying, "hit once: health ${bob.live.health}")
                    bob.close()
                }
                .thenSucceed()
        }
    }

    /** `all_narrator` lifts the Narrator's effects only; taking a beneficial effect away is a punishment like any other. */
    @GameTest
    fun removeEffectLiftsTheNarratorsAndTakingAGiftIsAPunishment(helper: GameTestHelper) = withPlayer(helper) { bob ->
        bob.live.addEffect(MobEffectInstance(MobEffects.FIRE_RESISTANCE, 1_200, 0))
        run(helper, ApplyEffectTool(), ApplyEffectArgs(listOf(bob.name), "slowness", seconds = 60))
        val tool = RemoveEffectTool()

        val lifted = run(helper, tool, RemoveEffectArgs(listOf(bob.name), "all_narrator"))
        helper.assertEquals("ok: removed slowness I from ${bob.name}", lifted.text, "result")
        helper.assertFalse(bob.live.hasEffect(MobEffects.SLOWNESS), "the Narrator's slowness is gone")
        helper.assertTrue(bob.live.hasEffect(MobEffects.FIRE_RESISTANCE), "the player's own potion stays")

        // Another turn may not take the fire resistance away: this turn's punishment still runs.
        val otherTurn = run(helper, tool, RemoveEffectArgs(listOf(bob.name), "fire_resistance"), OTHER_TURN)
        helper.assertEquals(ToolStatus.DENIED, otherTurn.status, otherTurn.text)
        helper.assertTrue(otherTurn.text.startsWith("DENIED (${Rules.ALREADY_PUNISHED}): "), otherTurn.text)
        helper.assertTrue(bob.live.hasEffect(MobEffects.FIRE_RESISTANCE), "still resistant")

        val sameTurn = run(helper, tool, RemoveEffectArgs(listOf(bob.name), "fire_resistance"))
        helper.assertEquals("ok: removed fire_resistance I from ${bob.name}", sameTurn.text, "result")
        helper.assertTrue(guards(helper).death.isArmed(bob.live), "armed: in lava, this would kill")

        val none = run(helper, tool, RemoveEffectArgs(listOf(bob.name), "glowing"))
        helper.assertEquals("Invalid arguments: ${bob.name} does not have glowing; ${bob.name} has no effect", none.text, "nothing to remove")
    }

    // ── Attributes ──────────────────────────────────────────────────────────────────────────────

    @GameTest
    fun anAttributeStaysWithinItsBoundsAndIsPutBack(helper: GameTestHelper) = withPlayer(helper) { bob ->
        val caps = guards(helper).caps()
        val set = SetAttributeTool()

        val giant = run(helper, set, SetAttributeArgs(bob.name, AttributeKey.SCALE, multiply = 10.0, seconds = 60))
        helper.assertEquals("Invalid arguments: scale must stay between 0.25 and 3 (would be 10)", giant.text, "out of bounds")

        val tiny = run(helper, set, SetAttributeArgs(bob.name, AttributeKey.SCALE, multiply = 0.5, seconds = 60))
        helper.assertTrue(tiny.text.startsWith("ok: ${bob.name}'s scale 1 → 0.5 for 60 s (until "), tiny.text)
        helper.assertEquals(0.5, bob.live.getAttributeValue(Attributes.SCALE), "half size")
        helper.assertTrue(GuardStates.read(bob.live).modifiers.single().harmful, "a punishment")
        helper.assertEquals(CALLER.turnId, GuardStates.read(bob.live).episode?.turnId, "the turn's punishment is open")

        // In the player's favor: not a punishment, not capped by the chaos level.
        val fast = run(helper, set, SetAttributeArgs(bob.name, AttributeKey.MOVEMENT_SPEED, multiply = 1.5, seconds = 900))
        helper.assertTrue(fast.text.startsWith("ok: ${bob.name}'s movement_speed 0.1 → 0.15 for 900 s"), fast.text)
        helper.assertFalse(GuardStates.read(bob.live).modifiers.first { it.attribute == "movement_speed" }.harmful, "a gift")
        val heavy = run(helper, set, SetAttributeArgs(bob.name, AttributeKey.JUMP_STRENGTH, multiply = 0.5, seconds = caps.harmfulSeconds + 10))
        helper.assertEquals(ToolStatus.INVALID, heavy.status, heavy.text)
        helper.assertTrue(heavy.text.contains("in ${bob.name}'s disfavor: at chaos ") && heavy.text.contains("at most ${caps.harmfulSeconds} s"), heavy.text)

        val reset = ResetAttributeTool()
        val back = run(helper, reset, ResetAttributeArgs(bob.name, AttributeKey.SCALE))
        helper.assertEquals("ok: put back ${bob.name}'s scale 0.5 → 1", back.text, "result")
        helper.assertEquals(1.0, bob.live.getAttributeValue(Attributes.SCALE), "full size")

        // Taking the speed back is a punishment: another turn may not, while this turn's runs.
        val otherTurn = run(helper, reset, ResetAttributeArgs(bob.name), OTHER_TURN)
        helper.assertTrue(otherTurn.text.startsWith("DENIED (${Rules.ALREADY_PUNISHED}): "), otherTurn.text)
        val all = run(helper, reset, ResetAttributeArgs(bob.name))
        helper.assertEquals("ok: put back ${bob.name}'s movement_speed 0.15 → 0.1", all.text, "result")
        helper.assertTrue(GuardStates.read(bob.live).modifiers.isEmpty(), "the ledger is empty")
        val nothing = run(helper, reset, ResetAttributeArgs(bob.name, AttributeKey.GRAVITY))
        helper.assertEquals("Invalid arguments: the Narrator did not change ${bob.name}'s gravity; its changes on ${bob.name}: none", nothing.text, "nothing")
    }

    // ── Teleport ────────────────────────────────────────────────────────────────────────────────

    /**
     * A column with a magma block on a roof, and a room under the roof: the magma is dangerous, the roof
     * leaves no room to stand, so the player lands in the room. Searched from just below the top of the
     * test's area: above it, the test framework has blocks of its own. Then another player is brought next
     * to the first one, in the room.
     */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun aTeleportLandsOnSafeGround(helper: GameTestHelper) {
        helper.setBlock(5, 1, 5, Blocks.STONE)
        helper.setBlock(5, 4, 5, Blocks.STONE)
        helper.setBlock(5, 5, 5, Blocks.MAGMA_BLOCK)
        val column = helper.absolutePos(BlockPos(5, 2, 5))
        val bob = TestPlayer(helper)
        val alice = TestPlayer(helper)
        helper.closingOnFailure(bob, ASYNC_MAX_TICKS) {
            helper.closingOnFailure(alice, ASYNC_MAX_TICKS) {
                bob.standAt(BlockPos(1, 2, 1))
                alice.standAt(BlockPos(2, 2, 1))
                val intoTheRoom = runAsync(helper, TeleportTool(), TeleportArgs(bob.name, x = column.x, z = column.z, y = column.y + 5))
                val nextToBob = AtomicReference<AtomicReference<ToolResult?>>(AtomicReference())
                helper.startSequence()
                    .thenAwait { helper.assertTrue(intoTheRoom.get() != null, "the teleport answers") }
                    .thenExecute {
                        val result = intoTheRoom.get()!!
                        helper.assertTrue(result.text.startsWith("ok: ${bob.name} teleported, "), result.text)
                        assertStandsAt(helper, bob.live, column)
                        helper.assertEquals(null, GuardStates.read(bob.live).episode, "a few blocks: no punishment")
                        nextToBob.set(runAsync(helper, TeleportTool(), TeleportArgs(alice.name, toPlayer = bob.name)))
                    }
                    .thenAwait { helper.assertTrue(nextToBob.get().get() != null, "the second teleport answers") }
                    .thenExecute {
                        val result = nextToBob.get().get()!!
                        helper.assertTrue(result.text.startsWith("ok: ${alice.name} teleported next to ${bob.name}, "), result.text)
                        assertStandsAt(helper, alice.live, column)
                        alice.close()
                        bob.close()
                    }
                    .thenSucceed()
            }
        }
    }

    /** More than 32 blocks is a punishment: allowed and opened for one player, refused for a protected one, capped by the chaos level. */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun aFarTeleportIsAPunishment(helper: GameTestHelper) {
        helper.setBlock(5, 40, 5, Blocks.STONE)
        val high = helper.absolutePos(BlockPos(5, 41, 5))
        val bob = TestPlayer(helper)
        val alice = TestPlayer(helper)
        val guards = guards(helper)
        guards.setProtected(alice.uuid, alice.name, true)
        helper.runAfterDelay((ASYNC_MAX_TICKS - 1).toLong()) { guards.setProtected(alice.uuid, alice.name, false) }
        helper.closingOnFailure(bob, ASYNC_MAX_TICKS) {
            helper.closingOnFailure(alice, ASYNC_MAX_TICKS) {
                bob.standAt(BlockPos(1, 1, 1))
                alice.standAt(BlockPos(2, 1, 1))
                val start = alice.live.position()
                val tooFar = runAsync(helper, TeleportTool(), TeleportArgs(bob.name, x = high.x + guards.caps().teleportBlocks + 100, z = high.z))
                val up = runAsync(helper, TeleportTool(), TeleportArgs(bob.name, x = high.x, z = high.z, y = high.y))
                val protected = runAsync(helper, TeleportTool(), TeleportArgs(alice.name, x = high.x, z = high.z, y = high.y))
                helper.startSequence()
                    .thenAwait { helper.assertTrue(listOf(tooFar, up, protected).all { it.get() != null }, "the teleports answer") }
                    .thenExecute {
                        helper.assertEquals(ToolStatus.INVALID, tooFar.get()!!.status, tooFar.get()!!.text)
                        helper.assertTrue(tooFar.get()!!.text.contains("a teleport goes at most ${guards.caps().teleportBlocks} blocks"), tooFar.get()!!.text)

                        helper.assertEquals(ToolStatus.OK, up.get()!!.status, up.get()!!.text)
                        assertStandsAt(helper, bob.live, high)
                        helper.assertEquals(CALLER.turnId, GuardStates.read(bob.live).episode?.turnId, "a punishment is open")

                        helper.assertTrue(protected.get()!!.text.startsWith("DENIED (${Rules.PROTECTED}): "), protected.get()!!.text)
                        helper.assertEquals(start, alice.live.position(), "Alice did not move")
                        guards.setProtected(alice.uuid, alice.name, false)
                        alice.close()
                        bob.close()
                    }
                    .thenSucceed()
            }
        }
    }

    // ── Launch ──────────────────────────────────────────────────────────────────────────────────

    @GameTest(maxTicks = 100)
    fun aLaunchThrowsThePlayerAndCushionsTheFall(helper: GameTestHelper) {
        val bob = TestPlayer(helper)
        helper.closingOnFailure(bob, 100) {
            bob.standAt(BlockPos(1, 1, 1))
            val caps = guards(helper).caps()
            val tooStrong = run(helper, LaunchTool(), LaunchArgs(bob.name, LaunchDirection.UP, caps.launchStrength + 0.5))
            helper.assertEquals(ToolStatus.INVALID, tooStrong.status, tooStrong.text)

            val result = run(helper, LaunchTool(), LaunchArgs(bob.name, LaunchDirection.UP, 1.5))
            helper.assertTrue(result.text.startsWith("ok: ${bob.name} launched up at strength 1.5; no fall damage until "), result.text)
            helper.assertEquals(1.5, bob.live.deltaMovement.y, "upwards")
            helper.assertEquals(0.0, bob.live.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER), "no fall damage")
            val cushion = GuardStates.read(bob.live).modifiers.single()
            helper.assertEquals("fall_damage_multiplier" to false, cushion.attribute to cushion.harmful, "the cushion is in the ledger, as a favor")
            helper.assertTrue(guards(helper).death.isArmed(bob.live), "armed")
            helper.assertEquals(CALLER.turnId, GuardStates.read(bob.live).episode?.turnId, "a punishment")
            helper.startSequence()
                .thenWaitUntil {
                    val motion = bob.packets.filterIsInstance<ClientboundSetEntityMotionPacket>().firstOrNull { it.id() == bob.live.id }
                    helper.assertTrue(motion != null && motion.movement().y > 1.0, "the player's own client is told to move")
                }
                .thenExecute { bob.close() }
                .thenSucceed()
        }
    }

    // ── Items ───────────────────────────────────────────────────────────────────────────────────

    @GameTest
    fun giveItemBuildsANamedEnchantedItem(helper: GameTestHelper) = withPlayer(helper) { bob ->
        val tool = GiveItemTool(GiftLimiter(Clock.systemUTC()))
        val result = run(
            helper, tool,
            GiveItemArgs(
                bob.name, "diamond_sword", name = "§6Excalibur", lore = listOf("Forgée par le Narrateur"),
                enchantments = listOf(EnchantmentSpec("sharpness", 7)), unbreakable = true,
            ),
        )
        helper.assertEquals(
            "ok: gave 1x diamond_sword \"Excalibur\" (sharpness 7, 1 line of lore, unbreakable) to ${bob.name}",
            result.text,
            "result",
        )
        val sword = find(bob.live, Items.DIAMOND_SWORD)
        val name = sword.get(DataComponents.CUSTOM_NAME)!!
        helper.assertEquals("Excalibur", name.string, "name, without the formatting code")
        helper.assertFalse(name.style.applyTo(Style.EMPTY.withItalic(true)).isItalic, "the name is not italic")
        helper.assertEquals(listOf("Forgée par le Narrateur"), sword.get(DataComponents.LORE)!!.lines().map { it.string }, "lore")
        val sharpness = helper.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS)
        helper.assertEquals(7, sword.enchantments.getLevel(sharpness), "sharpness VII")
        helper.assertTrue(sword.has(DataComponents.UNBREAKABLE), "unbreakable")
        val mark = sword.get(DataComponents.CUSTOM_DATA)!!.copyTag().getCompoundOrEmpty(Speech.CUSTOM_DATA_KEY)
        helper.assertEquals("item" to CALLER.turnId, mark.getStringOr("kind", "") to mark.getLongOr("turn", -1), "marked as the Narrator's")

        run(helper, tool, GiveItemArgs(bob.name, "enchanted_book", enchantments = listOf(EnchantmentSpec("mending"))))
        val book = find(bob.live, Items.ENCHANTED_BOOK)
        helper.assertEquals(1, book.get(DataComponents.STORED_ENCHANTMENTS)?.size(), "a book stores its enchantment")
        helper.assertTrue(book.enchantments.isEmpty, "and does not wear it")

        run(helper, tool, GiveItemArgs(bob.name, "diamond", count = 3))
        helper.assertFalse(find(bob.live, Items.DIAMOND).has(DataComponents.CUSTOM_DATA), "a plain gift stays plain, to stack with the player's own")
    }

    @GameTest
    fun giveItemRefusesWhatItMustNotGive(helper: GameTestHelper) = withPlayer(helper) { bob ->
        val tool = GiveItemTool(GiftLimiter(Clock.systemUTC()))
        for (id in ItemSpecs.forbiddenIds + "zombie_spawn_egg") {
            helper.assertTrue(BuiltInRegistries.ITEM.containsKey(Identifier.parse(id)), "$id exists in 26.3")
            val result = run(helper, tool, GiveItemArgs(bob.name, id))
            helper.assertTrue(result.text.startsWith("DENIED (${ItemSpecs.FORBIDDEN_ITEM}): "), "$id: ${result.text}")
        }
        helper.assertEquals(
            "Invalid arguments: diamond_sword stacks to 1: count must be at most 1 (got 2)",
            run(helper, tool, GiveItemArgs(bob.name, "diamond_sword", count = 2)).text,
            "stack size",
        )
        val enchantment = run(helper, tool, GiveItemArgs(bob.name, "stick", enchantments = listOf(EnchantmentSpec("sharpnes"))))
        helper.assertTrue(enchantment.text.startsWith("Invalid arguments: unknown enchantment \"sharpnes\"; closest ids: sharpness"), enchantment.text)
        val item = run(helper, tool, GiveItemArgs(bob.name, "diamant"))
        helper.assertTrue(item.text.startsWith("Invalid arguments: unknown item \"diamant\"; closest ids: "), item.text)
        helper.assertTrue((0 until bob.live.inventory.containerSize).all { bob.live.inventory.getItem(it).isEmpty }, "nothing given")
    }

    @GameTest
    fun giftsAreLimitedPerPlayerAndHour(helper: GameTestHelper) = withPlayer(helper) { bob ->
        val tool = GiveItemTool(GiftLimiter(Clock.systemUTC()))
        repeat(GiftLimiter.GIFTS_PER_HOUR) { helper.assertEquals(ToolStatus.OK, run(helper, tool, GiveItemArgs(bob.name, "cookie")).status, "gift ${it + 1}") }
        val more = run(helper, tool, GiveItemArgs(bob.name, "cookie"))
        helper.assertTrue(more.text.startsWith("DENIED (${GiftLimiter.RULE}): ${bob.name} got ${GiftLimiter.GIFTS_PER_HOUR} gifts in the last hour"), more.text)
        helper.assertEquals(GiftLimiter.GIFTS_PER_HOUR, count(bob.live, Items.COOKIE), "cookies")
    }

    @GameTest
    fun takeItemConfiscatesUntilDue(helper: GameTestHelper) = withPlayer(helper) { bob ->
        val caps = guards(helper).caps()
        bob.live.inventory.setItem(0, ItemStack(Items.DIAMOND, 10))
        bob.live.inventory.setItem(1, ItemStack(Items.OAK_LOG, 1))
        bob.live.inventory.setItem(2, ItemStack(Items.BIRCH_LOG, 2))
        val tool = TakeItemTool()

        helper.assertEquals("Invalid arguments: ${bob.name} has no emerald", run(helper, tool, TakeItemArgs(bob.name, "emerald", 1, 1)).text, "none")
        val tooMany = run(helper, tool, TakeItemArgs(bob.name, "diamond", caps.confiscatedItems + 1, 1))
        helper.assertTrue(tooMany.text.contains("takes at most ${caps.confiscatedItems} items"), tooMany.text)
        val unknownTag = run(helper, tool, TakeItemArgs(bob.name, "#nope_nothing", 1, 1))
        helper.assertTrue(unknownTag.text.startsWith("Invalid arguments: unknown item tag \"#nope_nothing\""), unknownTag.text)

        val diamonds = run(helper, tool, TakeItemArgs(bob.name, "diamond", 6, 1, reason = "trop brillant"))
        helper.assertTrue(diamonds.text.startsWith("ok: took 6x minecraft:diamond from ${bob.name} until "), diamonds.text)
        helper.assertEquals(4, count(bob.live, Items.DIAMOND), "4 left")
        val logs = run(helper, tool, TakeItemArgs(bob.name, "#logs", 5, 2))
        helper.assertTrue(logs.text.startsWith("ok: took 1x minecraft:oak_log, 2x minecraft:birch_log (all they had) from ${bob.name}"), logs.text)
        val held = GuardStates.read(bob.live).confiscations
        helper.assertEquals(2, held.size, "two confiscations in the ledger")
        helper.assertEquals(CALLER.turnId, GuardStates.read(bob.live).episode?.turnId, "a punishment")
        helper.assertTrue(guards(helper).death.isArmed(bob.live), "armed")

        guards(helper).items.giveBack(bob.live, held.maxOf { it.returnAt })
        helper.assertEquals(10, count(bob.live, Items.DIAMOND), "the diamonds are back")
        helper.assertEquals(1 to 2, count(bob.live, Items.OAK_LOG) to count(bob.live, Items.BIRCH_LOG), "the logs are back")
        helper.assertTrue(GuardStates.read(bob.live).confiscations.isEmpty(), "nothing held")
    }

    private companion object {
        val CALLER = Caller(Role.DIRECTOR, "player-action-gametest", turnId = 7_001, taskId = null)

        /** A later turn of the same conversation: it may not pile on the punishment of [CALLER]'s turn. */
        val OTHER_TURN = CALLER.copy(turnId = 7_002)

        fun guards(helper: GameTestHelper): GuardsImpl = helper.narrator().guards as GuardsImpl

        /** Runs [tool] on the server thread, where its world access runs inline. */
        fun <A : Any> run(helper: GameTestHelper, tool: NarratorTool<A>, args: A, caller: Caller = CALLER): ToolResult {
            val runtime = helper.narrator()
            return runBlocking { tool.run(ToolContext(runtime, caller, runtime.clock, tool.timeoutMs), args) }
        }

        /** Runs [tool] off the server thread, as the Director does: for a tool that waits for the server (a teleport's loading). */
        fun <A : Any> runAsync(helper: GameTestHelper, tool: NarratorTool<A>, args: A): AtomicReference<ToolResult?> {
            val runtime = helper.narrator()
            val result = AtomicReference<ToolResult?>()
            CoroutineScope(Dispatchers.IO).launch {
                result.set(
                    try {
                        tool.run(ToolContext(runtime, CALLER, runtime.clock, tool.timeoutMs), args)
                    } catch (e: Exception) {
                        ToolResult.failed("the tool threw $e")
                    },
                )
            }
            return result
        }

        /** Runs [body] with one new [TestPlayer] standing in the test's area; removed however it ends. */
        fun withPlayer(helper: GameTestHelper, body: (TestPlayer) -> Unit) = withTestPlayers(helper, 1) { (player) ->
            player.standAt(BlockPos(1, 1, 1))
            body(player)
        }

        /** [player] stands at the bottom center of [block] (absolute). */
        fun assertStandsAt(helper: GameTestHelper, player: ServerPlayer, block: BlockPos) =
            helper.assertEquals(Triple(block.x + 0.5, block.y.toDouble(), block.z + 0.5), Triple(player.x, player.y, player.z), "where ${player.plainTextName} stands")

        fun count(player: ServerPlayer, item: Item): Int =
            (0 until player.inventory.containerSize).sumOf { slot -> player.inventory.getItem(slot).let { if (it.`is`(item)) it.count else 0 } }

        fun find(player: ServerPlayer, item: Item): ItemStack =
            (0 until player.inventory.containerSize).map { player.inventory.getItem(it) }.firstOrNull { it.`is`(item) } ?: ItemStack.EMPTY
    }
}
