package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.event.ExplosionHappened
import io.github.clemsix6.narrator2.gametest.GuardGameTests.Companion.PLAY_TIME
import io.github.clemsix6.narrator2.gametest.GuardGameTests.Companion.count
import io.github.clemsix6.narrator2.gametest.GuardGameTests.Companion.kill
import io.github.clemsix6.narrator2.gametest.GuardGameTests.Companion.reconnect
import io.github.clemsix6.narrator2.gametest.GuardGameTests.Companion.respawn
import io.github.clemsix6.narrator2.gametest.GuardGameTests.Companion.vulnerable
import io.github.clemsix6.narrator2.gametest.GuardGameTests.Companion.withPlayer
import io.github.clemsix6.narrator2.gametest.GuardPolicyGameTests.Companion.mockPlayer
import io.github.clemsix6.narrator2.gametest.GuardPolicyGameTests.Companion.removePlayers
import io.github.clemsix6.narrator2.guard.Confiscation
import io.github.clemsix6.narrator2.guard.ExplosionGuard
import io.github.clemsix6.narrator2.guard.GuardState
import io.github.clemsix6.narrator2.guard.GuardStates
import io.github.clemsix6.narrator2.guard.GuardsImpl
import io.github.clemsix6.narrator2.guard.NarratorMob
import io.github.clemsix6.narrator2.guard.NarratorMobs
import io.github.clemsix6.narrator2.guard.Rules
import io.github.clemsix6.narrator2.guard.TargetingPolicy
import io.github.clemsix6.narrator2.guard.Verdict
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.testing.testSettings
import io.github.clemsix6.narrator2.tool.Caller
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.NbtOps
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.ConversionParams
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.targeting.TargetingConditions
import net.minecraft.world.entity.monster.cubemob.Slime
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-game tests of the holes T09c closed in the guards (`docs/design/handoff/T09c.md`): a recent death
 * that survives a reconnection, the Narrator's creatures that spare protected and recently dead players,
 * conversions and splits that keep the Narrator's mark, what players entrusted to a creature coming
 * back when it expires, the Observer's flag on the explosion tool's blasts, and the deaths left.
 *
 * The Narrator's creatures are marked with a long expiry, and expired through a registry of the test's
 * own: the server's would also remove the creatures of the tests running alongside.
 */
class GuardGapGameTests {

    /** A player who died, left and came back within 5 minutes is still spared: the death is saved with them. */
    @GameTest
    fun aRecentDeathHoldsAcrossAReconnection(helper: GameTestHelper) = withPlayer(helper) { player ->
        val runtime = helper.narrator()
        val guards = guards(helper)
        kill(helper, player)
        helper.assertTrue(player.isDeadOrDying, "an ordinary death")
        helper.assertTrue(GuardStates.read(player).lastDeathAt != null, "saved with the player")

        val back = respawn(helper, reconnect(helper, player))
        helper.assertTrue(back.isAlive && runtime.players.get(back.uuid)!!.health > 0f, "back, alive, after a reconnection")
        val verdict = guards.hostile(back, CALLER, System.currentTimeMillis() + 60_000)
        helper.assertTrue(
            verdict is Verdict.Denied && verdict.rule == Rules.NO_PILE_ON && " died " in verdict.message,
            "no piling on after a reconnection: $verdict",
        )
        helper.assertTrue(runtime.players.get(back.uuid)?.lastDeathAt != null, "the situation report sees the death too")
        helper.assertTrue(guards.spares(back), "and the Narrator's creatures spare them")

        // Five minutes later, the rule no longer holds and the saved death is forgotten. Guards of their own,
        // five minutes ahead: the server's check would also expire the creatures of the other tests.
        val later = GuardsImpl(
            TestServices(minecraft = helper.level.server, settings = testSettings(NarratorConfig()), players = runtime.players),
            Clock.offset(Clock.systemUTC(), Duration.ofMillis(TargetingPolicy.NO_PILE_ON_MS)),
        )
        helper.assertFalse(later.spares(back), "fair game again after 5 minutes")
        later.check(listOf(back), System.currentTimeMillis() + TargetingPolicy.NO_PILE_ON_MS)
        helper.assertEquals(null, GuardStates.read(back).lastDeathAt, "forgotten after 5 minutes")
    }

    /**
     * The Narrator's creatures never hurt a protected player nor one who died less than 5 minutes ago:
     * not by a blow, an arrow, a potion or its effect, and they do not target them. The rule is the
     * Narrator's alone: an ordinary zombie still hurts the protected player, and the Narrator's creatures
     * hurt the others.
     */
    @GameTest
    fun theNarratorsCreaturesSpareProtectedAndRecentlyDeadPlayers(helper: GameTestHelper) {
        val guards = guards(helper)
        val level = helper.level
        val shielded = realPlayer(helper, BlockPos(1, 1, 1))
        val mourned = realPlayer(helper, BlockPos(3, 1, 1))
        val exposed = realPlayer(helper, BlockPos(5, 1, 1))
        try {
            guards.setProtected(shielded.uuid, shielded.plainTextName, on = true)
            guards.states.update(mourned) { it.copy(lastDeathAt = System.currentTimeMillis() - 60_000) }
            val expiresAt = System.currentTimeMillis() + 600_000
            val zombie = narratorMob(helper, EntityTypes.ZOMBIE, Vec3(2.5, 1.0, 4.5), null, expiresAt)
            val skeleton = narratorMob(helper, EntityTypes.SKELETON, Vec3(4.5, 1.0, 4.5), null, expiresAt)
            val witch = narratorMob(helper, EntityTypes.WITCH, Vec3(6.5, 1.0, 4.5), null, expiresAt)
            // Neither the arrow nor the potion carries the tag: their owners do.
            val arrow = EntityTypes.ARROW.create(level, EntitySpawnReason.COMMAND)!!.apply { setOwner(skeleton) }
            val potion = EntityTypes.SPLASH_POTION.create(level, EntitySpawnReason.COMMAND)!!.apply { setOwner(witch) }
            val ordinary = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND)!!
            val sources = level.damageSources()
            val combat = TargetingConditions.forCombat().ignoreLineOfSight()

            for (player in listOf(shielded, mourned)) {
                val who = if (player === shielded) "the protected player" else "the player who died a minute ago"
                helper.assertFalse(hit(helper, player, sources.mobAttack(zombie)), "the Narrator's zombie's blow spares $who")
                helper.assertFalse(hit(helper, player, sources.arrow(arrow, skeleton)), "its skeleton's arrow spares $who")
                helper.assertFalse(hit(helper, player, sources.indirectMagic(potion, witch)), "its witch's harming potion spares $who")
                helper.assertFalse(player.addEffect(MobEffectInstance(MobEffects.POISON, 200), potion), "its witch's poison spares $who")
                helper.assertFalse(player.hasEffect(MobEffects.POISON), "$who is not poisoned")
                helper.assertTrue(player.addEffect(MobEffectInstance(MobEffects.REGENERATION, 100), witch), "a beneficial effect still reaches $who")
                helper.assertValueEqual(20f, player.health, "health of $who")
                helper.assertFalse(combat.test(level, zombie, player), "$who is no target for the Narrator's zombie")
                zombie.target = player
                helper.assertEquals(null, zombie.target, "a target set on $who is dropped")
            }

            helper.assertTrue(hit(helper, shielded, sources.mobAttack(ordinary)), "an ordinary zombie's blow lands on the protected player")
            helper.assertTrue(hit(helper, exposed, sources.mobAttack(zombie)), "the Narrator's zombie hurts a player it may act against")
            helper.assertTrue(exposed.addEffect(MobEffectInstance(MobEffects.POISON, 200), potion), "and its witch poisons them")
            helper.assertTrue(combat.test(level, zombie, exposed), "a valid target")
            zombie.target = exposed
            helper.assertTrue(zombie.target === exposed, "kept as a target")

            guards.states.update(mourned) { it.copy(lastDeathAt = System.currentTimeMillis() - TargetingPolicy.NO_PILE_ON_MS) }
            helper.assertTrue(hit(helper, mourned, sources.mobAttack(zombie)), "5 minutes after the death, fair game again")
        } finally {
            guards.setProtected(shielded.uuid, shielded.plainTextName, on = false)
            removePlayers(helper, shielded, mourned, exposed)
            helper.killAllEntities()
        }
        helper.succeed()
    }

    /**
     * A creature of the Narrator's that converts (a zombie drowned) or splits (a slime killed) hands its
     * mark, its expiry, the player it was summoned for and its target down: registered at once, and gone
     * at the Narrator's due time.
     */
    @GameTest
    fun aConvertedOrSplitCreatureKeepsTheNarratorsMark(helper: GameTestHelper) {
        val guards = guards(helper)
        val target = realPlayer(helper, BlockPos(7, 1, 7))
        val summonedFor = UUID.randomUUID()
        val expiresAt = System.currentTimeMillis() + 600_000
        try {
            val zombie = narratorMob(helper, EntityTypes.ZOMBIE, Vec3(2.5, 1.0, 2.5), summonedFor, expiresAt)
            zombie.target = target
            val drowned = zombie.convertTo(EntityTypes.DROWNED, ConversionParams.single(zombie, true, true)) {}!!
            helper.assertTrue(zombie.isRemoved, "the zombie is gone")
            assertMarked(helper, drowned, summonedFor, expiresAt)
            helper.assertTrue(drowned.target === target, "the drowned keeps the zombie's target: ${drowned.target}")

            val slime = narratorMob(helper, EntityTypes.SLIME, Vec3(5.5, 1.0, 2.5), summonedFor, expiresAt) { it.setSize(4, true) }
            slime.target = target
            slime.health = 0f
            slime.remove(Entity.RemovalReason.KILLED)
            val children = helper.level.getEntitiesOfClass(Slime::class.java, AABB.ofSize(slime.position(), 4.0, 4.0, 4.0)) { it !== slime && !it.isRemoved }
            helper.assertTrue(children.isNotEmpty(), "the slime split")
            children.forEach {
                assertMarked(helper, it, summonedFor, expiresAt)
                helper.assertTrue(it.target === target, "a small slime keeps the target: ${it.target}")
            }
            val counted = guards.activeOn(summonedFor).map { it.what }
            helper.assertTrue("1 minecraft:drowned" in counted && "${children.size} minecraft:slime" in counted, "registered by the server's guards: $counted")

            val mobs = NarratorMobs()
            (children + drowned).forEach(mobs::onLoad)
            helper.assertValueEqual(children.size + 1, mobs.expire(expiresAt), "all due at the Narrator's time")
            helper.assertTrue((children + drowned).all { it.isRemoved }, "and removed then")
        } finally {
            removePlayers(helper, target)
            helper.killAllEntities()
        }
        helper.succeed()
    }

    /**
     * When the Narrator's creatures expire, nothing a player entrusted to them is lost: the owner of a
     * tamed horse and donkey gets back the saddle, the armor, the chest, what it held and the lead through
     * the ledger; the saddle of a pig nobody owns, and a helmet a dispenser put on a zombie, fall where they
     * stood. The zombie's own gear (a sword, and a nautilus shell the game keeps for good) leaves with it.
     */
    @GameTest
    fun whatPlayersEntrustedToACreatureOutlivesIt(helper: GameTestHelper) = withPlayer(helper) { player ->
        val guards = guards(helper)
        val expiresAt = System.currentTimeMillis() + 600_000
        val horse = narratorMob(helper, EntityTypes.HORSE, Vec3(2.5, 1.0, 2.5), player.uuid, expiresAt)
        horse.tameWithName(player)
        give(helper, player, horse, ItemStack(Items.SADDLE))
        give(helper, player, horse, ItemStack(Items.IRON_HORSE_ARMOR))
        helper.assertTrue(!horse.getItemBySlot(EquipmentSlot.SADDLE).isEmpty && !horse.getItemBySlot(EquipmentSlot.BODY).isEmpty, "the horse is saddled and armored")

        val donkey = narratorMob(helper, EntityTypes.DONKEY, Vec3(5.5, 1.0, 2.5), player.uuid, expiresAt)
        donkey.tameWithName(player)
        give(helper, player, donkey, ItemStack(Items.CHEST))
        helper.assertTrue(donkey.hasChest(), "the donkey carries a chest")
        helper.assertTrue(donkey.getSlot(500)!!.set(ItemStack(Items.DIAMOND, 3)), "3 diamonds in the chest")
        donkey.setLeashedTo(player, true)

        val pig = narratorMob(helper, EntityTypes.PIG, Vec3(2.5, 1.0, 5.5), null, expiresAt)
        give(helper, player, pig, ItemStack(Items.SADDLE))
        helper.assertFalse(pig.getItemBySlot(EquipmentSlot.SADDLE).isEmpty, "the pig is saddled")

        val zombie = narratorMob(helper, EntityTypes.ZOMBIE, Vec3(5.5, 1.0, 5.5), null, expiresAt) {
            it.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.IRON_SWORD))
            it.setItemSlot(EquipmentSlot.OFFHAND, ItemStack(Items.NAUTILUS_SHELL))
            it.setGuaranteedDrop(EquipmentSlot.OFFHAND)
        }
        // What a dispenser does to a mob in front of it (EquipmentDispenseItemBehavior).
        zombie.setItemSlot(EquipmentSlot.HEAD, ItemStack(Items.IRON_HELMET))
        zombie.setGuaranteedDrop(EquipmentSlot.HEAD)

        val creatures = listOf(horse, donkey, pig, zombie)
        val mobs = NarratorMobs(guards.items)
        creatures.forEach(mobs::onLoad)
        helper.assertValueEqual(4, mobs.expire(expiresAt), "all due")
        helper.assertTrue(creatures.all { it.isRemoved }, "all gone")

        for ((item, expected) in listOf(Items.SADDLE to 1, Items.IRON_HORSE_ARMOR to 1, Items.CHEST to 1, Items.DIAMOND to 3, Items.LEAD to 1)) {
            helper.assertValueEqual(expected, count(player, item), "$item back in the owner's inventory")
        }
        helper.assertValueEqual(emptyList<Confiscation>(), guards.items.held(player), "nothing left waiting in the ledger")
        val dropped = helper.getEntities(EntityTypes.ITEM)
        val onTheGround = dropped.map { it.item }
        helper.assertTrue(onTheGround.any { it.`is`(Items.SADDLE) }, "the pig's saddle, owner unknown, lies where it stood: $onTheGround")
        helper.assertTrue(onTheGround.any { it.`is`(Items.IRON_HELMET) }, "so does the zombie's helmet: $onTheGround")
        helper.assertTrue(onTheGround.none { it.`is`(Items.IRON_SWORD) || it.`is`(Items.NAUTILUS_SHELL) }, "the zombie's own gear left with it: $onTheGround")
        dropped.forEach { it.discard() }
    }

    /** The explosion tool's blast, which has no source entity, is the Narrator's for the Observer too. */
    @GameTest(maxTicks = 100)
    fun theObserverFlagsTheExplosionToolsBlast(helper: GameTestHelper) {
        val runtime = helper.narrator()
        val owner = "guard-gap-test-${UUID.randomUUID()}"
        val recorded = CopyOnWriteArrayList<ExplosionHappened>()
        runtime.bus.subscribeSync("recorder", owner) { envelope -> (envelope.event as? ExplosionHappened)?.let(recorded::add) }
        val player = mockPlayer(helper, BlockPos(1, 1, 1))
        fun close() {
            runtime.bus.unsubscribeOwner(owner)
            removePlayers(helper, player)
        }
        helper.runAfterDelay(99) { close() }
        val at = helper.absoluteVec(Vec3(4.5, 1.5, 4.5))
        ExplosionGuard.narrators { helper.level.explode(null, at.x, at.y, at.z, 1f, Level.ExplosionInteraction.NONE) }
        fun mine() = recorded.filter { e -> e.nearby.any { it.uuid == player.uuid } }
        helper.startSequence()
            .thenWaitUntil { helper.assertTrue(mine().isNotEmpty(), "published at the end of the tick") }
            .thenExecute {
                helper.assertTrue(mine().single().narrator, "the explosion tool's blast is the Narrator's: ${mine()}")
                close()
            }
            .thenSucceed()
    }

    /** `deathsLeft` follows the death guard's quota, and says 0 for a protected player. */
    @GameTest
    fun theDeathsLeftFollowTheQuota(helper: GameTestHelper) = withPlayer(helper) { player ->
        val guards = guards(helper)
        val cap = guards.caps().narratorKillsPerHour
        helper.assertValueEqual(cap, guards.deathsLeft(player.uuid), "the whole quota")
        guards.states.update(player) { it.copy(narratorKills = List(cap) { PLAY_TIME - 10 }) }
        helper.assertValueEqual(0, guards.deathsLeft(player.uuid), "spent")
        guards.states.update(player) { it.copy(narratorKills = emptyList()) }
        guards.setProtected(player.uuid, player.plainTextName, on = true)
        try {
            helper.assertValueEqual(0, guards.deathsLeft(player.uuid), "none for a protected player")
        } finally {
            guards.setProtected(player.uuid, player.plainTextName, on = false)
        }
    }

    @GameTest
    fun theNewFieldsSurviveTheirCodecs(helper: GameTestHelper) {
        val state = GuardState(lastDeathAt = 1_234)
        helper.assertValueEqual(state, GuardState.CODEC.parse(NbtOps.INSTANCE, GuardState.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow()).getOrThrow(), "guard state")
        val mob = NarratorMob(5_000, UUID.randomUUID(), harmful = true, ownSlots = listOf("offhand"))
        helper.assertValueEqual(mob, NarratorMob.CODEC.parse(NbtOps.INSTANCE, NarratorMob.CODEC.encodeStart(NbtOps.INSTANCE, mob).getOrThrow()).getOrThrow(), "creature")
        helper.succeed()
    }

    private companion object {
        val CALLER = Caller(Role.DIRECTOR, "guard-gap-test", turnId = 909, taskId = null)

        fun guards(helper: GameTestHelper): GuardsImpl = helper.narrator().guards as GuardsImpl

        /**
         * A survival player at [at] whom mobs can target: GameTest's mock players answer `gameMode()`
         * CREATIVE whatever their game mode (javap: `GameTestHelper$3`), so `Mob.asValidTarget` always turns
         * them away. The mock is reconnected from its file into a plain `ServerPlayer`.
         */
        fun realPlayer(helper: GameTestHelper, at: BlockPos): ServerPlayer =
            reconnect(helper, mockPlayer(helper, at)).also { vulnerable(it) }

        /** One of the Narrator's creatures, without AI, prepared by [setup] and marked before it enters the level, as the spawn tool does. */
        fun <T : Mob> narratorMob(helper: GameTestHelper, type: EntityType<T>, at: Vec3, player: UUID?, expiresAt: Long, setup: (T) -> Unit = {}): T {
            val mob = type.create(helper.level, EntitySpawnReason.COMMAND)!!
            mob.snapTo(helper.absoluteVec(at))
            mob.isNoAi = true
            setup(mob)
            guards(helper).mobs.mark(mob, expiresAt, player, harmful = true)
            helper.assertTrue(helper.level.addFreshEntity(mob), "${type.toShortString()} added")
            return mob
        }

        fun assertMarked(helper: GameTestHelper, mob: Mob, player: UUID, expiresAt: Long) {
            val what = "${mob.type.toShortString()} ${mob.uuid}"
            helper.assertTrue(NarratorMobs.isNarrators(mob) && mob.isPersistenceRequired, "$what is tagged and persistent")
            val mark = mob.getAttached(NarratorMob.TYPE)
            helper.assertTrue(mark?.expiresAt == expiresAt && mark.player == player, "$what has the Narrator's expiry and player: $mark")
        }

        /** A blow of 2 health points, even right after another one (the damage cooldown is reset). */
        fun hit(helper: GameTestHelper, player: ServerPlayer, source: DamageSource): Boolean {
            player.damageCooldownTime = 0
            return player.hurtServer(helper.level, source, 2f)
        }

        /** [player] hands [stack] to [creature] with a right click. */
        fun give(helper: GameTestHelper, player: ServerPlayer, creature: Entity, stack: ItemStack) {
            player.setItemInHand(InteractionHand.MAIN_HAND, stack)
            val result = player.interactOn(creature, InteractionHand.MAIN_HAND, Vec3.ZERO)
            helper.assertTrue(result.consumesAction(), "${stack.item} given to ${creature.type.toShortString()}: $result")
        }
    }
}
