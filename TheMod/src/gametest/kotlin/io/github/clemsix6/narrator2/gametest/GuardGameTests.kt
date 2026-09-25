package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.guard.AttributeKey
import io.github.clemsix6.narrator2.guard.AttributeLedger
import io.github.clemsix6.narrator2.guard.Confiscation
import io.github.clemsix6.narrator2.guard.DeathGuard
import io.github.clemsix6.narrator2.guard.GuardState
import io.github.clemsix6.narrator2.guard.GuardStates
import io.github.clemsix6.narrator2.guard.GuardsImpl
import io.github.clemsix6.narrator2.guard.LedgerEffect
import io.github.clemsix6.narrator2.guard.LedgerModifier
import io.github.clemsix6.narrator2.guard.OpenEpisode
import io.github.clemsix6.narrator2.guard.Rules
import io.github.clemsix6.narrator2.guard.Verdict
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.testing.testSettings
import io.github.clemsix6.narrator2.tool.Caller
import io.netty.channel.embedded.EmbeddedChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.Registries
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.network.Connection
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket
import net.minecraft.server.level.ClientInformation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.stats.Stats
import net.minecraft.util.ProblemReporter
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.level.GameType
import net.minecraft.world.level.storage.TagValueInput
import net.minecraft.world.phys.Vec3
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * In-game tests of the guards (M1_M2 §17.3): the death guard (a protected death, the quota, the mark
 * that survives a disconnection), the attribute ledger (reapplied after a death and a reconnection,
 * removed when due), confiscations (written with the removal, given back when due, after a crash, into
 * a full inventory, or never when unreadable) and the Narrator's creatures.
 *
 * Each test has its own mock player and removes it. Time is moved by passing `now` to the ledgers; the
 * death guard counts the player's `PLAY_TIME`, which the tests set. "Restarts" disconnect the player
 * (the game saves them) and load them back from their file into a new player object.
 */
class GuardGameTests {

    @GameTest
    fun aNarratorDeathKeepsTheInventoryAndTheExperience(helper: GameTestHelper) = withPlayer(helper) { player ->
        val guards = guards(helper)
        equip(player)
        guards.armDeathGuard(player.uuid, 10)
        helper.assertTrue(guards.death.isArmed(player), "the guard is armed")

        kill(helper, player)
        helper.assertTrue(player.isDeadOrDying, "the player died")
        helper.assertTrue(DeathGuard.keepsInventory(player), "the dying player is marked")
        helper.assertValueEqual(5, count(player, Items.DIAMOND), "nothing dropped")
        helper.assertValueEqual(listOf(PLAY_TIME), GuardStates.read(player).narratorKills, "the death counts, in played ticks")
        val attribution = guards.deathAttribution(player, helper.level.damageSources().magic())
        helper.assertTrue(attribution.narrator && attribution.keptInventory, "attribution for the Observer: $attribution")

        val respawned = respawn(helper, player)
        helper.assertValueEqual(5, count(respawned, Items.DIAMOND), "the inventory comes back")
        helper.assertValueEqual(7, respawned.experienceLevel, "the experience comes back")
        helper.assertFalse(DeathGuard.keepsInventory(respawned), "the mark is cleared at respawn")
        helper.assertValueEqual(listOf(PLAY_TIME), GuardStates.read(respawned).narratorKills, "the quota follows the player")
    }

    @GameTest
    fun aDeathBeyondTheQuotaIsPrevented(helper: GameTestHelper) = withPlayer(helper) { player ->
        val guards = guards(helper)
        equip(player)
        // Chaos 3: one death per played hour, and one happened 1 000 ticks ago.
        guards.states.update(player) { it.copy(narratorKills = listOf(PLAY_TIME - 1_000)) }
        guards.armDeathGuard(player.uuid, 10)

        kill(helper, player)
        helper.assertFalse(player.isDeadOrDying, "the death is prevented")
        helper.assertValueEqual(1f, player.health, "kept at 1 health")
        helper.assertValueEqual(5, count(player, Items.DIAMOND), "the inventory is untouched")
        helper.assertFalse(DeathGuard.keepsInventory(player), "no mark")
        helper.assertValueEqual(listOf(PLAY_TIME - 1_000), GuardStates.read(player).narratorKills, "nothing more is counted")
        helper.assertTrue(
            GuardStates.read(player).armedUntil >= PLAY_TIME + DeathGuard.PREVENTED_REARM_SECONDS * DeathGuard.TICKS_PER_SECOND,
            "armed a little longer: a lasting hazard cannot finish the job",
        )

        // An hour of play later, the quota is back.
        setPlayTime(player, PLAY_TIME + DeathGuard.HOUR_TICKS)
        guards.armDeathGuard(player.uuid, 10)
        kill(helper, player)
        helper.assertTrue(player.isDeadOrDying && DeathGuard.keepsInventory(player), "a protected death again: ${GuardStates.read(player)}")
    }

    @GameTest
    fun anOrdinaryDeathDropsTheInventory(helper: GameTestHelper) = withPlayer(helper) { player ->
        val guards = guards(helper)
        equip(player)
        kill(helper, player)
        helper.assertTrue(player.isDeadOrDying, "the player died")
        helper.assertFalse(DeathGuard.keepsInventory(player), "not the Narrator's death")
        helper.assertValueEqual(0, count(player, Items.DIAMOND), "the inventory is dropped as usual")
        helper.assertFalse(guards.deathAttribution(player, helper.level.damageSources().magic()).narrator, "attribution")
        helper.assertValueEqual(emptyList<Long>(), GuardStates.read(player).narratorKills, "nothing counted")
    }

    /** Armed or not, a blow from another player is that player's doing: the guard never shields from PvP. An operator's `/kill` neither. */
    @GameTest
    fun aKillByAPlayerOrAnOperatorIsNeverTheNarrators(helper: GameTestHelper) = withPlayer(helper) { player ->
        val guards = guards(helper)
        @Suppress("DEPRECATION")
        val other = helper.makeMockServerPlayerInLevel()
        try {
            guards.armDeathGuard(player.uuid, 10)
            val sources = helper.level.damageSources()
            helper.assertTrue(guards.death.attributed(player, sources.magic()), "armed: a magic death is the Narrator's")
            helper.assertFalse(guards.death.attributed(player, sources.playerAttack(other)), "not a blow from another player")
            helper.assertFalse(guards.death.attributed(player, sources.genericKill()), "nor an operator's /kill")
        } finally {
            helper.level.server.playerList.remove(other)
        }
    }

    /**
     * The Narrator's creatures: marked and counted when they load, a kill by one of them is the
     * Narrator's without any arming, and they disappear when due.
     */
    @GameTest
    fun theNarratorsCreaturesAreCountedAttributedAndRemovedWhenDue(helper: GameTestHelper) = withPlayer(helper) { player ->
        val guards = guards(helper)
        equip(player)
        val now = System.currentTimeMillis()
        val zombie = EntityTypes.ZOMBIE.create(helper.level, EntitySpawnReason.COMMAND)!!
        zombie.snapTo(helper.absoluteVec(Vec3(2.5, 1.0, 2.5)))
        guards.mobs.mark(zombie, now + 60_000, player.uuid, harmful = true)
        helper.assertTrue(helper.level.addFreshEntity(zombie), "the zombie is added")
        helper.assertTrue(zombie.isPersistenceRequired, "it never despawns on its own")
        helper.assertTrue(guards.activeOn(player.uuid).any { it.kind == "mob" && it.what == "1 minecraft:zombie" }, "counted: ${guards.activeOn(player.uuid)}")

        kill(helper, player, helper.level.damageSources().mobAttack(zombie))
        helper.assertTrue(player.isDeadOrDying && DeathGuard.keepsInventory(player), "a kill by the Narrator's creature is protected")
        helper.assertValueEqual(5, count(respawn(helper, player), Items.DIAMOND), "and the inventory comes back")

        guards.mobs.expire(now + 61_000)
        helper.assertTrue(zombie.isRemoved, "removed when due")
        helper.assertTrue(guards.activeOn(player.uuid).none { it.kind == "mob" }, "no longer counted")
    }

    /** Disconnected (or the server restarted) between a protected death and the respawn: the mark and the inventory are in the file. */
    @GameTest
    fun aProtectedDeathHoldsAcrossADisconnection(helper: GameTestHelper) = withPlayer(helper) { player ->
        val guards = guards(helper)
        equip(player)
        guards.armDeathGuard(player.uuid, 10)
        kill(helper, player)
        helper.assertTrue(DeathGuard.keepsInventory(player), "marked")

        val back = reconnect(helper, player)
        helper.assertTrue(back.isDeadOrDying, "back on the death screen")
        helper.assertTrue(DeathGuard.keepsInventory(back), "the mark came back from the player's file")
        helper.assertValueEqual(5, count(back, Items.DIAMOND), "so did the inventory")

        val respawned = respawn(helper, back)
        helper.assertValueEqual(5, count(respawned, Items.DIAMOND), "carried over at respawn")
        helper.assertValueEqual(7, respawned.experienceLevel, "experience too")
        helper.assertFalse(DeathGuard.keepsInventory(respawned), "the mark is cleared")
    }

    /**
     * A restart during a harmful effect: the game keeps the effect, the ledger still tells it is the
     * Narrator's, and the death guard, counted in played ticks, still covers it.
     */
    @GameTest
    fun aRestartDuringAnEffectKeepsItsGuard(helper: GameTestHelper) = withPlayer(helper) { player ->
        val guards = guards(helper)
        equip(player)
        guards.effects.apply(player, MobEffects.WITHER, level = 2, seconds = 60, particles = true, turnId = 9)
        guards.armDeathGuard(player.uuid, 65)
        helper.assertTrue(guards.activeOn(player.uuid).any { it.kind == "effect" && it.what == "wither II" && it.harmful }, "listed: ${guards.activeOn(player.uuid)}")

        val back = reconnect(helper, player)
        vulnerable(back)
        helper.assertTrue(back.hasEffect(MobEffects.WITHER), "the game kept the effect")
        helper.assertValueEqual(listOf("minecraft:wither"), guards.effects.on(back).map { it.effect }, "the ledger knows it is the Narrator's")
        helper.assertTrue(guards.death.isArmed(back), "still armed: the guard counts played ticks")

        kill(helper, back, helper.level.damageSources().wither())
        helper.assertTrue(back.isDeadOrDying && DeathGuard.keepsInventory(back), "a death by the effect is protected")
        helper.assertValueEqual(5, count(respawn(helper, back), Items.DIAMOND), "and the inventory comes back")
    }

    @GameTest
    fun theNarratorsEffectsAreToldApartFromTheOthers(helper: GameTestHelper) = withPlayer(helper) { player ->
        val guards = guards(helper)
        player.addEffect(MobEffectInstance(MobEffects.SPEED, 1_200, 0))
        guards.effects.apply(player, MobEffects.SLOWNESS, level = 1, seconds = 30, particles = false, turnId = null)
        helper.assertValueEqual(listOf("minecraft:slowness"), guards.effects.on(player).map { it.effect }, "only the Narrator's")

        guards.effects.apply(player, MobEffects.SLOWNESS, level = 3, seconds = 10, particles = false, turnId = null)
        helper.assertValueEqual(2, player.getEffect(MobEffects.SLOWNESS)!!.amplifier, "replaced, even by a weaker, shorter one")

        val removed = guards.effects.removeAll(player)
        helper.assertValueEqual(listOf("minecraft:slowness"), removed.map { it.effect }, "all_narrator")
        helper.assertFalse(player.hasEffect(MobEffects.SLOWNESS), "gone")
        helper.assertTrue(player.hasEffect(MobEffects.SPEED), "the player's own effect stays")

        guards.effects.apply(player, MobEffects.GLOWING, level = 1, seconds = 30, particles = false, turnId = null)
        player.removeEffect(MobEffects.GLOWING)
        guards.effects.prune(player)
        helper.assertValueEqual(emptyList<LedgerEffect>(), guards.effects.on(player), "an effect gone (milk) leaves the ledger")
    }

    @GameTest
    fun anAttributeIsReappliedAfterADeathAndRemovedWhenDue(helper: GameTestHelper) = withPlayer(helper) { player ->
        val guards = guards(helper)
        val now = System.currentTimeMillis()
        val scale = AttributeKey.SCALE
        helper.assertValueEqual(0.5, guards.attributes.valueWith(player, scale, -0.5, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL), "preview")
        helper.assertValueEqual(1.0, player.getAttributeValue(Attributes.SCALE), "the preview changes nothing")

        val value = guards.attributes.apply(player, scale, -0.5, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, now + 60_000, harmful = true, turnId = 7)
        helper.assertValueEqual(0.5, value, "applied")
        helper.assertValueEqual(0.25, guards.attributes.valueWith(player, scale, -0.75, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL), "a preview replaces the Narrator's modifier")
        helper.assertTrue(guards.activeOn(player.uuid).any { it.kind == "attribute" && it.what == "scale x0.5" && it.harmful }, "listed: ${guards.activeOn(player.uuid)}")

        kill(helper, player)
        val respawned = respawn(helper, player)
        helper.assertValueEqual(0.5, respawned.getAttributeValue(Attributes.SCALE), "reapplied to the new player object")

        guards.attributes.expire(respawned, now + 59_000)
        helper.assertValueEqual(0.5, respawned.getAttributeValue(Attributes.SCALE), "not due yet")
        guards.attributes.expire(respawned, now + 60_000)
        helper.assertValueEqual(1.0, respawned.getAttributeValue(Attributes.SCALE), "removed when due")
        helper.assertValueEqual(emptyList<LedgerModifier>(), guards.attributes.on(respawned), "and gone from the ledger")
    }

    /** A reconnection (or a restart) reapplies what is still running, drops what ran out meanwhile, and removes stray modifiers. */
    @GameTest
    fun attributesComeBackAfterAReconnection(helper: GameTestHelper) = withPlayer(helper) { player ->
        val guards = guards(helper)
        val now = System.currentTimeMillis()
        guards.attributes.apply(player, AttributeKey.SCALE, -0.5, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, now + 60_000, harmful = true, turnId = null)
        val ranOut = LedgerModifier("step_height", 1.0, "add_value", now - 1_000, harmful = false, turnId = null)
        guards.states.update(player) { it.copy(modifiers = it.modifiers + ranOut) }

        val back = reconnect(helper, player)
        helper.assertValueEqual(0.5, back.getAttributeValue(Attributes.SCALE), "the running modifier is reapplied")
        helper.assertValueEqual(0.6, back.getAttributeValue(Attributes.STEP_HEIGHT), "the one that ran out is not")
        helper.assertValueEqual(listOf("scale"), guards.attributes.on(back).map { it.attribute }, "and it left the ledger")

        val stray = AttributeModifier(AttributeLedger.modifierId(AttributeKey.JUMP_STRENGTH), 1.0, AttributeModifier.Operation.ADD_VALUE)
        back.getAttribute(Attributes.JUMP_STRENGTH)!!.addTransientModifier(stray)
        guards.attributes.reapply(back, now)
        helper.assertFalse(back.getAttribute(Attributes.JUMP_STRENGTH)!!.hasModifier(stray.id()), "a Narrator modifier missing from the ledger is removed")
        helper.assertValueEqual(0.5, back.getAttributeValue(Attributes.SCALE), "the ledger's own stays")
    }

    /** The confiscation is in the ledger in the same tick as the items leave, and they come back when due, components and all. */
    @GameTest
    fun aConfiscationIsWrittenWithTheRemovalAndGivenBackWhenDue(helper: GameTestHelper) = withPlayer(helper) { player ->
        val guards = guards(helper)
        val now = System.currentTimeMillis()
        equip(player)
        val sword = namedSword(helper)
        player.inventory.setItem(2, sword.copy())

        val taken = guards.items.confiscate(player, { it.`is`(Items.DIAMOND) }, 3, now + 60_000, "greed", turnId = 5)!!
        helper.assertValueEqual(2, count(player, Items.DIAMOND), "taken from the inventory")
        helper.assertValueEqual(listOf(taken), GuardStates.read(player).confiscations, "written in the same tick")
        helper.assertValueEqual("3x minecraft:diamond", taken.what, "described")
        guards.items.confiscate(player, { it.`is`(Items.IRON_SWORD) }, 1, now + 30_000, null, turnId = 5)
        helper.assertValueEqual(0, count(player, Items.IRON_SWORD), "the sword too")
        helper.assertEquals(null, guards.items.confiscate(player, { it.`is`(Items.EMERALD) }, 1, now, null, null), "nothing matches")

        guards.items.giveBack(player, now + 29_000)
        helper.assertValueEqual(2, count(player, Items.DIAMOND), "nothing is due yet")
        guards.items.giveBack(player, now + 30_000)
        helper.assertTrue(ItemStack.matches(sword, find(player, Items.IRON_SWORD)), "the sword comes back as it was: ${find(player, Items.IRON_SWORD)}")
        helper.assertValueEqual(2, count(player, Items.DIAMOND), "the diamonds are not due yet")
        guards.items.giveBack(player, now + 60_000)
        helper.assertValueEqual(5, count(player, Items.DIAMOND), "the diamonds come back")
        helper.assertValueEqual(emptyList<Confiscation>(), guards.items.held(player), "the ledger is empty")
    }

    /** A confiscation and its return are decisions in the Journal, linked by the confiscation's id. */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun aConfiscationAndItsReturnAreTraced(helper: GameTestHelper) {
        val runtime = helper.narrator()
        val guards = guards(helper)
        val now = System.currentTimeMillis()
        @Suppress("DEPRECATION")
        val player = helper.makeMockServerPlayerInLevel()
        val taken = try {
            player.inventory.setItem(0, ItemStack(Items.DIAMOND, 4))
            val confiscation = guards.items.confiscate(player, { it.`is`(Items.DIAMOND) }, 4, now, "a test", turnId = null)!!
            guards.items.giveBack(player, now)
            confiscation
        } finally {
            helper.level.server.playerList.remove(player)
        }
        val decisions = AtomicReference<Any>()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runtime.journal.barrier()
                decisions.set(
                    runtime.journal.read(5_000) { connection ->
                        connection.prepareStatement(
                            "SELECT decision FROM engine_decisions WHERE kind = 'ledger' AND (id = ? OR json_extract_string(detail, '\$.confiscation') = ?) ORDER BY id",
                        ).use { statement ->
                            statement.setLong(1, taken.id)
                            statement.setString(2, taken.id.toString())
                            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
                        }
                    },
                )
            } catch (e: Exception) {
                decisions.set("read failed: $e")
            }
        }
        helper.awaitAsync { helper.assertValueEqual(listOf("confiscated", "returned"), decisions.get(), "decisions of confiscation ${taken.id}") }
    }

    /** A crash after the confiscation was saved and before the return: the player's file has the ledger without the items. */
    @GameTest
    fun aConfiscationSurvivesACrashBeforeItsReturn(helper: GameTestHelper) = withPlayer(helper) { player ->
        val guards = guards(helper)
        val now = System.currentTimeMillis()
        val sword = namedSword(helper)
        player.inventory.setItem(0, sword.copy())
        guards.items.confiscate(player, { it.`is`(Items.IRON_SWORD) }, 1, now + 60_000, null, turnId = null)

        val back = reconnect(helper, player)
        helper.assertValueEqual(0, count(back, Items.IRON_SWORD), "not in the inventory")
        helper.assertValueEqual(1, guards.items.held(back).size, "still in the ledger")
        guards.items.giveBack(back, now + 60_000)
        helper.assertTrue(ItemStack.matches(sword, find(back, Items.IRON_SWORD)), "given back whole: ${find(back, Items.IRON_SWORD)}")
        helper.assertValueEqual(emptyList<Confiscation>(), guards.items.held(back), "and gone from the ledger")
    }

    /** What does not fit stays in the ledger (never on the ground) and comes back once there is room. */
    @GameTest
    fun whatDoesNotFitWaitsForRoom(helper: GameTestHelper) = withPlayer(helper) { player ->
        val guards = guards(helper)
        val now = System.currentTimeMillis()
        player.inventory.setItem(0, ItemStack(Items.DIAMOND, 10))
        guards.items.confiscate(player, { it.`is`(Items.DIAMOND) }, 6, now, null, turnId = null)
        // Room for 2 diamonds only: slot 0 holds 62, every other slot is full of dirt.
        player.inventory.setItem(0, ItemStack(Items.DIAMOND, 62))
        for (slot in 1 until 36) player.inventory.setItem(slot, ItemStack(Items.DIRT, 64))

        guards.items.giveBack(player, now)
        helper.assertValueEqual(64, count(player, Items.DIAMOND), "what fits comes back")
        helper.assertValueEqual(listOf("4x minecraft:diamond"), guards.items.held(player).map { it.what }, "the rest stays in the ledger")

        player.inventory.setItem(5, ItemStack.EMPTY)
        guards.items.giveBack(player, now)
        helper.assertValueEqual(68, count(player, Items.DIAMOND), "the rest comes back once there is room")
        helper.assertValueEqual(emptyList<Confiscation>(), guards.items.held(player), "the ledger is empty")
    }

    /** A creative player's full inventory does not swallow what comes back (`Inventory.add` would). */
    @GameTest
    fun aCreativeInventoryDoesNotSwallowWhatComesBack(helper: GameTestHelper) = withPlayer(helper) { player ->
        val guards = guards(helper)
        val now = System.currentTimeMillis()
        player.setGameMode(GameType.CREATIVE)
        player.inventory.setItem(0, ItemStack(Items.DIAMOND, 3))
        guards.items.confiscate(player, { it.`is`(Items.DIAMOND) }, 3, now, null, turnId = null)
        for (slot in 0 until 36) player.inventory.setItem(slot, ItemStack(Items.DIRT, 64))
        guards.items.giveBack(player, now)
        helper.assertValueEqual(listOf("3x minecraft:diamond"), guards.items.held(player).map { it.what }, "kept in the ledger")
    }

    /** A stack whose item left the game stays in the ledger: never erased silently. */
    @GameTest
    fun anUnreadableStackStaysInTheLedger(helper: GameTestHelper) = withPlayer(helper) { player ->
        val guards = guards(helper)
        val now = System.currentTimeMillis()
        val gone = Confiscation(-1, listOf("""{id:"narrator2:gone",count:1}"""), "1x narrator2:gone", now, now, null, null)
        guards.states.update(player) { it.copy(confiscations = listOf(gone)) }
        guards.items.giveBack(player, now + 1_000)
        helper.assertValueEqual(listOf(gone), guards.items.held(player), "still held")
    }

    @GameTest
    fun theGuardStateSurvivesItsCodec(helper: GameTestHelper) {
        helper.assertTrue(GuardState.TYPE.isPersistent && GuardState.TYPE.copyOnDeath(), "saved with the player, copied on death")
        val state = GuardState(
            modifiers = listOf(LedgerModifier("scale", -0.5, "add_multiplied_total", 1_000, harmful = true, turnId = 42)),
            effects = listOf(LedgerEffect("minecraft:slowness", 2, 2_000, harmful = true, turnId = null)),
            confiscations = listOf(Confiscation(7, listOf("""{id:"minecraft:diamond",count:3}"""), "3x minecraft:diamond", 10, 20, "greed", 42)),
            episode = OpenEpisode(42, 3_000),
            armedUntil = 123_456,
            protectedDeath = true,
            narratorKills = listOf(100_000, 150_000),
        )
        val saved = GuardState.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow()
        helper.assertValueEqual(state, GuardState.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow(), "round trip")
        helper.assertValueEqual(GuardState.EMPTY, GuardState.CODEC.parse(NbtOps.INSTANCE, CompoundTag()).getOrThrow(), "every field is optional")
        helper.succeed()
    }

    @GameTest
    fun protectionAndChaosZeroComeBeforeAnyHostileAction(helper: GameTestHelper) = withPlayer(helper) { player ->
        val runtime = helper.narrator()
        val guards = guards(helper)
        val caller = Caller(Role.DIRECTOR, "guard-test", turnId = 1, taskId = null)
        val until = System.currentTimeMillis() + 60_000

        guards.setProtected(player.uuid, player.plainTextName, on = true)
        helper.assertTrue(guards.isProtected(player.uuid), "protected")
        val refused = guards.hostile(player, caller, until)
        helper.assertTrue(refused is Verdict.Denied && refused.rule == Rules.PROTECTED, "refused: $refused")
        guards.setProtected(player.uuid, player.plainTextName, on = false)
        helper.assertFalse(guards.isProtected(player.uuid), "no longer protected")

        // Guards of their own at chaos 0, with the runtime's player states.
        val services = TestServices(minecraft = helper.level.server, settings = testSettings(NarratorConfig(chaos = 0)), players = runtime.players)
        val calm = GuardsImpl(services)
        val chaos0 = calm.hostile(player, caller, until)
        helper.assertTrue(chaos0 is Verdict.Denied && chaos0.rule == Rules.CHAOS_0, "refused at chaos 0: $chaos0")
        helper.assertTrue(services.traces.decisions.any { it.decision == "denied" && it.subject == player.plainTextName }, "the refusal is a decision")
        helper.assertEquals(null, GuardStates.read(player).episode, "no punishment opened")

        // No death at chaos 0: the Narrator's kill is prevented.
        calm.death.arm(player, 10)
        helper.assertFalse(calm.death.allowDeath(player, helper.level.damageSources().magic()), "prevented at chaos 0")
        helper.assertValueEqual(1f, player.health, "kept at 1 health")
    }

    private companion object {
        /** The mock players' play time, in ticks: long enough for a played hour to have passed before. */
        const val PLAY_TIME = 200_000L

        fun guards(helper: GameTestHelper): GuardsImpl = helper.narrator().guards as GuardsImpl

        /**
         * Runs [body] with a mock player who can take damage, then removes it (the current object, after
         * a respawn or a reconnection) and succeeds.
         */
        fun withPlayer(helper: GameTestHelper, body: (ServerPlayer) -> Unit) {
            @Suppress("DEPRECATION")
            val player = helper.makeMockServerPlayerInLevel()
            val uuid: UUID = player.uuid
            try {
                val at = helper.absolutePos(BlockPos(1, 1, 1))
                player.teleportTo(at.x + 0.5, at.y.toDouble(), at.z + 0.5)
                vulnerable(player)
                setPlayTime(player, PLAY_TIME)
                body(player)
            } finally {
                val players = helper.level.server.playerList
                players.getPlayer(uuid)?.let { players.remove(it) }
            }
            helper.succeed()
        }

        /** A survival player whose client has loaded: the only kind that takes damage (`handoff/T05.md`). */
        fun vulnerable(player: ServerPlayer) {
            player.setGameMode(GameType.SURVIVAL)
            player.connection.handleAcceptPlayerLoad(ServerboundPlayerLoadedPacket())
        }

        fun setPlayTime(player: ServerPlayer, ticks: Long) {
            player.stats.setValue(player, Stats.CUSTOM.get(Stats.PLAY_TIME), ticks.toInt())
        }

        /** 5 diamonds and 7 levels of experience. */
        fun equip(player: ServerPlayer) {
            player.inventory.setItem(0, ItemStack(Items.DIAMOND, 5))
            player.experienceLevel = 7
        }

        /** An iron sword with a name and an enchantment, to check that components survive. */
        fun namedSword(helper: GameTestHelper): ItemStack {
            val sword = ItemStack(Items.IRON_SWORD)
            sword.set(DataComponents.CUSTOM_NAME, Component.literal("Oathkeeper"))
            val sharpness = helper.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS)
            sword.enchant(sharpness, 3)
            return sword
        }

        /** A lethal hit, even right after another one (the damage cooldown is reset). */
        fun kill(helper: GameTestHelper, player: ServerPlayer, source: DamageSource = helper.level.damageSources().magic()): Boolean {
            player.damageCooldownTime = 0
            return player.hurtServer(helper.level, source, 1_000f)
        }

        fun respawn(helper: GameTestHelper, player: ServerPlayer): ServerPlayer =
            helper.level.server.playerList.respawn(player, false, Entity.RemovalReason.KILLED)

        /**
         * Disconnects [player] (the game saves them), then connects them again from their saved file into
         * a new player object: what a restart does, with nothing carried over in memory.
         */
        fun reconnect(helper: GameTestHelper, player: ServerPlayer): ServerPlayer {
            val server = helper.level.server
            val players = server.playerList
            val profile = player.gameProfile
            val id = player.nameAndId()
            players.remove(player)
            val saved = players.loadPlayerData(id).orElseThrow { AssertionError("${player.plainTextName} was not saved") }
            val back = ServerPlayer(server, helper.level, profile, ClientInformation.createDefault())
            back.load(TagValueInput.create(ProblemReporter.DISCARDING, server.registryAccess(), saved))
            val connection = Connection(PacketFlow.SERVERBOUND)
            EmbeddedChannel(connection)
            players.placeNewPlayer(connection, back, CommonListenerCookie.createInitial(profile, false))
            return back
        }

        fun count(player: ServerPlayer, item: Item): Int =
            (0 until player.inventory.containerSize).sumOf { slot -> player.inventory.getItem(slot).let { if (it.`is`(item)) it.count else 0 } }

        fun find(player: ServerPlayer, item: Item): ItemStack =
            (0 until player.inventory.containerSize).map { player.inventory.getItem(it) }.firstOrNull { it.`is`(item) } ?: ItemStack.EMPTY
    }
}
