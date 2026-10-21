package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.forge.api.Cell
import io.github.clemsix6.narrator2.forge.api.ForgeRefusal
import io.github.clemsix6.narrator2.forge.api.Outcome
import io.github.clemsix6.narrator2.forge.api.Pending
import io.github.clemsix6.narrator2.forge.api.Place
import io.github.clemsix6.narrator2.forge.api.Spawn
import io.github.clemsix6.narrator2.forge.sdk.ActQueue
import io.github.clemsix6.narrator2.forge.sdk.CreatureControl
import io.github.clemsix6.narrator2.forge.sdk.EngineActRunner
import io.github.clemsix6.narrator2.forge.sdk.GroundView
import io.github.clemsix6.narrator2.forge.sdk.ModuleActs
import io.github.clemsix6.narrator2.forge.sdk.ModuleCreatures
import io.github.clemsix6.narrator2.gametest.ForgeContractsGameTests.Companion.FORGE_ENVIRONMENT
import io.github.clemsix6.narrator2.gametest.GuardGameTests.Companion.count
import io.github.clemsix6.narrator2.gametest.GuardPolicyGameTests.Companion.floor
import io.github.clemsix6.narrator2.guard.AttributeKey
import io.github.clemsix6.narrator2.guard.BlockRestorer
import io.github.clemsix6.narrator2.guard.CreatureShield
import io.github.clemsix6.narrator2.guard.GuardStates
import io.github.clemsix6.narrator2.guard.GuardsImpl
import io.github.clemsix6.narrator2.guard.NarratorMob
import io.github.clemsix6.narrator2.guard.Peril
import io.github.clemsix6.narrator2.guard.DeathGuard
import io.github.clemsix6.narrator2.guard.Verdict
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.tool.Caller
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import io.github.clemsix6.narrator2.mechanics.EngineActions
import io.github.clemsix6.narrator2.mechanics.module.moduleOnlyTools
import io.github.clemsix6.narrator2.mechanics.rule.MarkOwner
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.tool.action.playerActionTools
import io.github.clemsix6.narrator2.tool.action.punishTools
import io.github.clemsix6.narrator2.tool.action.worldActionTools
import io.github.clemsix6.narrator2.tool.speech.speechTools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.chat.Component
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntitySelector
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import net.minecraft.world.scores.Team
import java.util.UUID

/**
 * In-game tests of the SDK's acts, creatures and blocks (task M5-07, M5 §4.4, §4.5, §16.3), in the batch of the Forge:
 * a module's acts go through the real tools and guards, one act of the engine per call; its creatures are its own and
 * marked, push nobody (design review, finding 3 (a)), aim at their target only, never kill a bystander (finding 3 (b))
 * nor anyone (code review, D1); it claims no boss nor anyone's creature (finding 4) and gives back what it claimed where
 * it was; its blocks go in thin air only.
 *
 * Each test runs a deployment of its own ([Rig]) on the server's runtime, its acts on the server thread (an unconfined
 * queue: each act is done before its call returns), and gives back everything at the end.
 */
class ModuleActsGameTests {
    /** A deployment of a module, without its module: its acts, creatures and blocks on the server's runtime. */
    private class Rig(private val helper: GameTestHelper, deployment: Long) : AutoCloseable {
        val runtime = helper.narrator()
        val guards = runtime.guards as GuardsImpl
        private val server = helper.level.server
        val actions = EngineActions(
            runtime,
            speechTools(runtime) + playerActionTools(runtime) + worldActionTools(runtime) + punishTools(runtime) + moduleOnlyTools(),
        )
        val queue = ActQueue(Ref(RefKind.CANON, deployment), EngineActRunner(actions), CoroutineScope(Dispatchers.Unconfined), runtime.clock, { _, body -> body() }, server)
        val creatures = CreatureControl(runtime, deployment, queue)
        val acts = ModuleActs(queue, creatures, server)
        val ground = GroundView(queue)
        val tag = MarkOwner.module(deployment).tag

        /** One call of the module that asks [ask]: its outcome, settled (a summoning's creatures its own), as its callback gets it. */
        fun outcome(ask: () -> Pending): Outcome {
            var got: Outcome? = null
            queue.invocation("test") { ask().then { got = it } }
            queue.deliver()
            return got ?: error("no outcome")
        }

        /** The creature [uuid] in the test's level. */
        fun mob(uuid: String): Mob = helper.level.getEntity(UUID.fromString(uuid)) as Mob

        override fun close() {
            queue.close()
            creatures.releaseAll()
        }
    }

    /**
     * A module's acts go through the real tools and their guards (M5 §4.4): a protected player is refused, a second
     * call (another act of the engine) may not punish a player the first one punishes, and what a law may not do a
     * module may not either; an explosion of power 100, a horde, a boss are refused.
     */
    @GameTest(environment = FORGE_ENVIRONMENT)
    fun aModulesActsGoThroughTheRealToolsAndTheirGuards(helper: GameTestHelper) = withTestPlayers(helper, 1) { (bob) ->
        bob.makeVulnerable()
        Rig(helper, 990_701).use { rig ->
            rig.guards.setProtected(bob.uuid, bob.name, on = true)
            try {
                val refused = rig.outcome { rig.acts.punish(bob.name, "tiny", 1, "a bougé quand le Narrateur regardait") }
                helper.assertTrue(refused.text().startsWith("DENIED (protected): "), refused.text())
            } finally {
                rig.guards.setProtected(bob.uuid, bob.name, on = false)
            }
            val tiny = rig.outcome { rig.acts.punish(bob.name, "tiny", 1, "a bougé quand le Narrateur regardait") }
            helper.assertTrue(tiny.ok(), tiny.text())
            val episode = GuardStates.read(bob.live).episode
            helper.assertTrue((episode?.act ?: 0L) < 0L, "the punishment is an act of the engine's, never a turn's: $episode")

            val again = rig.outcome { rig.acts.punish(bob.name, "slow_hands", 1, "a encore bougé") }
            helper.assertTrue(again.text().startsWith("DENIED (already_punished): "), "another call of the module is another act: ${again.text()}")
            val wither = rig.outcome { rig.acts.effect(bob.name, "wither", 10, 1) }
            helper.assertTrue(wither.text().startsWith("DENIED (forbidden_effect): a module never gives wither"), wither.text())
            val blast = rig.outcome { rig.acts.explosion(CreatureControl.placeOf(bob.live), 100.0, true) }
            helper.assertTrue(blast.text().startsWith("Invalid arguments: power must be between 0.5 and 4"), blast.text())
            val horde = rig.outcome { rig.acts.spawn("zombie", bob.name, Spawn.of(1_000)) }
            helper.assertTrue(horde.text().startsWith("DENIED (module_creatures): "), horde.text())
            val warden = rig.outcome { rig.acts.spawn("warden", bob.name, null) }
            helper.assertTrue(warden.text().startsWith("DENIED (forbidden_creature): minecraft:warden is not summoned"), warden.text())
            val creeper = rig.outcome { rig.acts.spawn("creeper", bob.name, null) }
            helper.assertTrue(creeper.text().startsWith("DENIED (forbidden_creature): minecraft:creeper is never a module's"), creeper.text())

            val first = rig.outcome { rig.acts.hud(bob.name, "10 s") }
            val second = rig.outcome { rig.acts.hud(bob.name, "9 s") }
            helper.assertTrue(first.ok(), first.text())
            helper.assertTrue(second.text().startsWith("DENIED (module_acts): one line above the hotbar per player and second"), second.text())

            val grace = rig.guards.grace(bob.live, rig.runtime.clock.millis() + 60_000, Caller(Role.DIRECTOR, "gametest", null, null))
            helper.assertEquals(Verdict.Allowed, grace, "a grace")
            val graced = rig.outcome { rig.acts.launch(bob.name, "up", 1.0) }
            helper.assertTrue(graced.text().startsWith("DENIED (grace): "), "nothing hostile against a player in grace: ${graced.text()}")
        }
    }

    /** A creature a module claimed is the Narrator's: it never picks up an item, where the same zombie of the wild does. */
    @GameTest(environment = FORGE_ENVIRONMENT, maxTicks = 400)
    fun aClaimedCreatureNeverPicksUpAnItem(helper: GameTestHelper) {
        floor(helper)
        val bob = TestPlayer(helper)
        val rig = Rig(helper, 990_707)
        bob.standAt(BlockPos(3, 1, 6))
        fun zombie(at: BlockPos): Mob {
            GuardPolicyGameTests.pen(helper, at)
            val zombie = EntityTypes.ZOMBIE.create(helper.level, EntitySpawnReason.COMMAND)!!
            zombie.snapTo(helper.absoluteVec(Vec3(at.x + 0.5, 1.0, at.z + 0.5)))
            zombie.setPersistenceRequired()
            helper.assertTrue(helper.level.addFreshEntity(zombie), "a zombie")
            zombie.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY)
            zombie.setCanPickUpLoot(true)
            return zombie
        }
        val claimed = zombie(BlockPos(1, 1, 1))
        val ordinary = zombie(BlockPos(5, 1, 1))
        helper.assertTrue(rig.creatures.claim(claimed.stringUUID) != null, "claimed")
        val sword = helper.spawnItem(Items.IRON_SWORD, BlockPos(1, 1, 1)).apply { setNoPickUpDelay() }
        helper.spawnItem(Items.IRON_SWORD, BlockPos(5, 1, 1)).setNoPickUpDelay()
        helper.startSequence()
            .thenWaitUntil { helper.assertTrue(ordinary.mainHandItem.`is`(Items.IRON_SWORD), "the wild zombie picks up its sword") }
            .thenIdle(20)
            .thenExecute {
                helper.assertTrue(claimed.mainHandItem.isEmpty, "the module's zombie holds nothing: ${claimed.mainHandItem}")
                helper.assertFalse(sword.isRemoved, "its sword is still on the ground")
                rig.close()
                listOf(claimed, ordinary).forEach { it.discard() }
                sword.discard()
                bob.close()
            }
            .thenSucceed()
    }

    /**
     * A summoning's creatures are the module's own, marked (the Narrator's, the deployment's, no fall damage), steered at
     * once within their bounds, and they push nobody (design review, finding 3 (a)): on the team whose collision rule is
     * `never`, the game finds nobody for them to push, and a player next to one is not moved, where the same creature
     * of the wild pushes them. They go with the deployment.
     */
    @GameTest(environment = FORGE_ENVIRONMENT, maxTicks = 200)
    fun aSummoningsCreaturesAreTheModulesOwnAndPushNobody(helper: GameTestHelper) {
        floor(helper)
        val ann = TestPlayer(helper)
        val bob = TestPlayer(helper)
        val rig = Rig(helper, 990_702)
        try {
            ann.standAt(BlockPos(1, 1, 1))
            bob.standAt(BlockPos(5, 1, 5))
            val summoned = rig.outcome { rig.acts.spawn("chicken", bob.name, Spawn.of(2).withDistance(3)) }
            helper.assertTrue(summoned.ok() && summoned.creatures().size == 2, summoned.toString())
            val chickens = summoned.creatures().map(rig::mob)
            for (chicken in chickens) {
                helper.assertTrue(chicken.entityTags().containsAll(listOf("narrator2", rig.tag)), "marked: ${chicken.entityTags()}")
                helper.assertEquals(ModuleCreatures.TEAM, chicken.team?.name, "on the team that pushes nobody")
                helper.assertEquals(Team.CollisionRule.NEVER, chicken.team?.collisionRule, "its collision rule")
                helper.assertEquals(0.0, chicken.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER), "no fall damage")
            }
            helper.assertEquals(summoned.creatures(), rig.creatures.own().map { it.uuid() }, "its own, in order")
            val handle = rig.creatures.get(summoned.creatures().first())!!
            helper.assertTrue(handle.info()!!.own(), "its own")

            handle.velocity(0.5, 0.2, 0.0)
            helper.assertEquals(Vec3(0.5, 0.2, 0.0), chickens.first().deltaMovement, "its motion")
            val tooFast = runCatching { handle.velocity(3.0, 0.0, 0.0) }.exceptionOrNull()
            helper.assertTrue(tooFast is ForgeRefusal, "a motion within ±2 blocks a tick: $tooFast")
            handle.name("Poulette")
            handle.glow(true)
            handle.gravity(false)
            helper.assertTrue(
                chickens.first().customName?.string == "Poulette" && chickens.first().hasGlowingTag() && chickens.first().isNoGravity,
                "named, glowing, floating",
            )
            val peck = rig.outcome { handle.attack(ann.name) }
            helper.assertTrue(peck.text().startsWith("Invalid arguments: a chicken never attacks"), peck.text())

            // What `LivingEntity.pushEntities` asks the game (javap): nobody for a module's creature, and it for nobody.
            val wild = EntityTypes.CHICKEN.create(helper.level, EntitySpawnReason.COMMAND)!!
            wild.snapTo(helper.absoluteVec(Vec3(1.8, 1.0, 1.5)))
            helper.assertTrue(helper.level.addFreshEntity(wild), "a wild chicken")
            val module = chickens.last()
            module.snapTo(bob.live.x + 0.3, bob.live.y, bob.live.z)
            helper.assertTrue(!EntitySelector.pushableBy(module).test(bob.live), "a module's chicken pushes no player")
            helper.assertTrue(!EntitySelector.pushableBy(bob.live).test(module), "nor is it pushed")
            helper.assertTrue(EntitySelector.pushableBy(wild).test(ann.live), "a wild chicken pushes a player")
            for (chicken in listOf(wild, module)) chicken.isNoAi = true
            ann.live.deltaMovement = Vec3.ZERO
            bob.live.deltaMovement = Vec3.ZERO
            helper.startSequence()
                .thenIdle(5)
                .thenExecute {
                    helper.assertTrue(ann.live.deltaMovement.horizontalDistance() > 0.0, "the wild chicken pushed Ann: ${ann.live.deltaMovement}")
                    helper.assertEquals(Vec3.ZERO, bob.live.deltaMovement, "the module's chicken did not push Bob")

                    chickens.first().kill(helper.level)
                    val gone = rig.creatures.sweep()
                    helper.assertEquals(listOf(summoned.creatures().first() to "died"), gone.map { it.creature().uuid() to it.why() }, "the fact of a creature gone")
                    helper.assertEquals(1, rig.creatures.own().size, "one left")
                    handle.velocity(0.1, 0.0, 0.0) // gone: nothing happens

                    rig.close()
                    helper.assertTrue(chickens.last().isRemoved, "a summoned creature goes with the deployment")
                    val after = runCatching { handle.velocity(0.0, 0.0, 0.0) }.exceptionOrNull()
                    helper.assertTrue(after is ForgeRefusal, "not its own any more: $after")
                    wild.discard()
                    ann.close()
                    bob.close()
                }
                .thenSucceed()
        } catch (e: Throwable) {
            rig.close()
            ann.close()
            bob.close()
            throw e
        }
    }

    /**
     * A module claims only a wild hostile creature near a player (M5 §4.5): never a tamed wolf, a named zombie, a
     * villager, a warden (a boss, design review finding 4) nor a creeper. What it claims is its own while it holds it, and
     * when it lets it go it gets back to normal, back where it was claimed.
     */
    @GameTest(environment = FORGE_ENVIRONMENT)
    fun aModuleClaimsOnlyWhatItMayAndGivesItBackWhereItWas(helper: GameTestHelper) = withTestPlayers(helper, 1) { (bob) ->
        floor(helper)
        bob.standAt(BlockPos(1, 1, 1))
        Rig(helper, 990_703).use { rig ->
            val zombie = creature(helper, EntityTypes.ZOMBIE, Vec3(4.5, 1.0, 4.5))
            val named = creature(helper, EntityTypes.ZOMBIE, Vec3(2.5, 1.0, 5.5)).apply { customName = Component.literal("Gérard") }
            val wolf = creature(helper, EntityTypes.WOLF, Vec3(5.5, 1.0, 2.5)).also { it.tame(bob.live) }
            val villager = creature(helper, EntityTypes.VILLAGER, Vec3(6.5, 1.0, 6.5))
            val warden = creature(helper, EntityTypes.WARDEN, Vec3(1.5, 1.0, 6.5))
            val creeper = creature(helper, EntityTypes.CREEPER, Vec3(6.5, 1.0, 1.5))
            try {
                for (refused in listOf(named, wolf, villager, warden, creeper)) {
                    helper.assertEquals(null, rig.creatures.claim(refused.stringUUID), "${EntityType.getKey(refused.type)} is never claimed")
                    helper.assertTrue(rig.tag !in refused.entityTags(), "left unmarked")
                }

                val claimed = rig.creatures.claim(zombie.stringUUID)!!
                helper.assertTrue(zombie.entityTags().containsAll(listOf("narrator2", rig.tag)), "marked: ${zombie.entityTags()}")
                helper.assertEquals(ModuleCreatures.TEAM, zombie.team?.name, "on the team that pushes nobody")
                val origin = zombie.position()
                zombie.teleportTo(origin.x + 2.0, origin.y, origin.z + 1.0)
                claimed.name("Zombie de Bob")
                claimed.glow(true)
                claimed.gravity(false)
                claimed.effect("slowness", 30, 1)
                claimed.attribute("movement_speed", 2.0)
                helper.assertTrue(zombie.hasEffect(MobEffects.SLOWNESS) && zombie.getAttributeValue(Attributes.MOVEMENT_SPEED) > 0.3, "changed while its own")
                val levitation = runCatching { claimed.effect("levitation", 10, 1) }.exceptionOrNull()
                helper.assertTrue(levitation is ForgeRefusal, "never an effect that makes it fall: $levitation")

                claimed.release()
                helper.assertTrue(!zombie.isRemoved, "a claimed creature stays in the world")
                helper.assertTrue(zombie.position().distanceTo(origin) < 0.01, "back where it was claimed: ${zombie.position()} for $origin")
                helper.assertTrue(zombie.entityTags().none { it.startsWith("narrator2") }, "its marks taken off: ${zombie.entityTags()}")
                helper.assertEquals(null, zombie.team, "off the team")
                helper.assertTrue(zombie.customName == null && !zombie.hasGlowingTag() && !zombie.isNoGravity, "its name, glow and gravity as before")
                helper.assertTrue(!zombie.hasEffect(MobEffects.SLOWNESS), "the effect its module put on it lifted")
                helper.assertEquals(null, zombie.getAttribute(Attributes.MOVEMENT_SPEED)!!.getModifier(MarkOwner.module(990_703).modifier("movement_speed")), "its speed as before")
                helper.assertEquals(1.0, zombie.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER), "fall damage as before")
                val after = runCatching { claimed.velocity(0.1, 0.0, 0.0) }.exceptionOrNull()
                helper.assertTrue(after is ForgeRefusal, "released: not its own any more: $after")
            } finally {
                listOf(zombie, named, wolf, villager, warden, creeper).forEach { it.discard() }
            }
        }
    }

    /**
     * A creature of a deployment loads (its chunk, a restart): its own again while the deployment runs; given back,
     * at the next task of the server, once it ended (`ModuleCreatures.onMobLoad`, for the engine and its guardian).
     */
    @GameTest(environment = FORGE_ENVIRONMENT, maxTicks = 100)
    fun aCreatureLoadedAfterItsDeploymentEndedIsGivenBack(helper: GameTestHelper) {
        floor(helper)
        val bob = TestPlayer(helper)
        val rig = Rig(helper, 990_706)
        bob.standAt(BlockPos(1, 1, 1))
        val zombie = creature(helper, EntityTypes.ZOMBIE, Vec3(4.5, 1.0, 4.5))
        val origin = zombie.position()
        rig.creatures.claim(zombie.stringUUID)!!
        zombie.teleportTo(origin.x + 2.0, origin.y, origin.z)
        val now = rig.runtime.clock.millis()
        ModuleCreatures.onMobLoad(zombie, { id -> rig.creatures.takeIf { id == 990_706L } }, rig.guards, now)
        helper.assertEquals(listOf(zombie.stringUUID), rig.creatures.own().map { it.uuid() }, "its own again while the deployment runs")

        ModuleCreatures.onMobLoad(zombie, { null }, rig.guards, now)
        helper.assertTrue(rig.tag in zombie.entityTags(), "never while it loads")
        helper.startSequence()
            .thenAwait { helper.assertTrue(zombie.entityTags().none { it.startsWith("narrator2") }, "given back: ${zombie.entityTags()}") }
            .thenExecute {
                helper.assertTrue(zombie.position().distanceTo(origin) < 0.01, "back where it was claimed")
                helper.assertEquals(null, zombie.team, "off the team")
                zombie.discard()
                rig.queue.close()
                bob.close()
            }
            .thenSucceed()
    }

    /**
     * A module's creature aims at the player its attack (an act the guards accepted) sent it to, nobody else; its blows
     * reach nobody else; and a bystander who dies near it is rescued, never counted (design review, finding 3 (b)), while
     * its target's death of another cause is the death guard's, which the attack armed (its fatal blow is a rescue:
     * [aModulesCreatureIsBoundToNobodyAndNeverKills]). Another player's blow is never the Narrator's.
     */
    @GameTest(environment = FORGE_ENVIRONMENT)
    fun aModulesCreatureAimsAtItsTargetOnlyAndNeverKillsABystander(helper: GameTestHelper) = withTestPlayers(helper, 2) { (ann, bob) ->
        floor(helper)
        ann.standAt(BlockPos(2, 1, 2))
        bob.standAt(BlockPos(6, 1, 6))
        ann.makeVulnerable()
        bob.makeVulnerable()
        Rig(helper, 990_704).use { rig ->
            val zombie = creature(helper, EntityTypes.ZOMBIE, Vec3(4.5, 1.0, 4.5))
            try {
                val claimed = rig.creatures.claim(zombie.stringUUID)!!
                rig.guards.setProtected(bob.uuid, bob.name, on = true)
                val protectedOne = rig.outcome { claimed.attack(bob.name) }
                rig.guards.setProtected(bob.uuid, bob.name, on = false)
                helper.assertTrue(protectedOne.text().startsWith("DENIED (protected): "), protectedOne.text())

                val attack = rig.outcome { claimed.attack(ann.name) }
                helper.assertTrue(attack.ok(), attack.text())
                helper.assertTrue(zombie.target === ann.live, "it goes for Ann: ${zombie.target}")
                helper.assertTrue(rig.guards.death.isArmed(ann.live), "the death guard watches over Ann")
                zombie.target = bob.live
                helper.assertTrue(zombie.target !== bob.live, "never for Bob, a bystander")

                val level = helper.level
                helper.assertTrue(!bob.live.hurtServer(level, level.damageSources().mobAttack(zombie), 4f), "its blow never reaches Bob")
                helper.assertEquals(20f, bob.live.health, "Bob's health")
                helper.assertTrue(ann.live.hurtServer(level, level.damageSources().mobAttack(zombie), 4f), "it reaches Ann")

                helper.assertTrue(ModuleCreatures.allowDeath(ann.live, level.damageSources().lava(), rig.guards), "its target's death of another cause is the death guard's")
                helper.assertTrue(ModuleCreatures.allowDeath(bob.live, level.damageSources().playerAttack(ann.live), rig.guards), "another player's blow is never the Narrator's")
                val killsBefore = GuardStates.read(bob.live).narratorKills.size
                bob.live.health = 0.5f
                helper.assertTrue(!ModuleCreatures.allowDeath(bob.live, level.damageSources().lava(), rig.guards), "a bystander's death near it is refused")
                helper.assertEquals(DeathGuard.RESCUE_HEALTH, bob.live.health, "Bob rescued")
                helper.assertTrue(GuardStates.read(bob.live).shield != null, "and shielded a while")
                helper.assertEquals(killsBefore, GuardStates.read(bob.live).narratorKills.size, "never counted")
            } finally {
                zombie.discard()
            }
        }
    }

    /**
     * A module's creature never kills (M5 review, D1: a creature the Narrator summons never kills, a module's included),
     * and is its module's, not a player's (M4-LEFTOVERS' test, moved here). A zombie `spawn` marked as a punishment of
     * Ann is hers alone until the module makes it its own: then it is bound to nobody, and `creature_attack` sends it at
     * Bob, at 1 health. Its blow lands; the fatal one is refused, by the server's own hooks, and turned into a rescue
     * where Bob stands, nothing lost, nothing counted. The zombie stays its module's, and stays when Ann leaves.
     */
    @GameTest(environment = FORGE_ENVIRONMENT)
    fun aModulesCreatureIsBoundToNobodyAndNeverKills(helper: GameTestHelper) = withTestPlayers(helper, 2) { (ann, bob) ->
        floor(helper)
        ann.standAt(BlockPos(2, 1, 2))
        bob.standAt(BlockPos(6, 1, 6))
        ann.makeVulnerable()
        bob.makeVulnerable()
        Rig(helper, 990_708).use { rig ->
            // What `spawn` does with a zombie sent at Ann, then the summoning's outcome settled (`Act.settle`): the module's.
            val zombie = EntityTypes.ZOMBIE.create(helper.level, EntitySpawnReason.COMMAND)!!
            zombie.snapTo(helper.absoluteVec(Vec3(4.5, 1.0, 4.5)))
            zombie.isNoAi = true // the test strikes for it
            rig.guards.mobs.mark(zombie, rig.runtime.clock.millis() + 120_000, ann.uuid, harmful = true, punishment = NarratorMob.SPAWN)
            helper.assertTrue(helper.level.addFreshEntity(zombie), "a zombie")
            try {
                helper.assertEquals(ann.uuid, CreatureShield.boundTo(zombie)?.player, "summoned, the zombie is Ann's")
                helper.assertTrue(rig.creatures.reserve(1), "room for it")
                rig.creatures.adopt(listOf(zombie.uuid), 1, ann.name, targeting = true)
                helper.assertEquals(listOf(zombie.stringUUID), rig.creatures.own().map { it.uuid() }, "the module's own")
                helper.assertEquals(null, CreatureShield.boundTo(zombie), "bound to nobody: its module aims it")

                val attack = rig.outcome { rig.creatures.get(zombie.stringUUID)!!.attack(bob.name) }
                helper.assertTrue(attack.ok(), attack.text())
                helper.assertTrue(zombie.target === bob.live, "sent at Bob, not the player it was summoned for: ${zombie.target}")

                bob.live.inventory.add(ItemStack(Items.DIAMOND, 5))
                bob.live.damageCooldownTime = 0
                helper.assertTrue(zombie.doHurtTarget(helper.level, bob.live), "its blow lands")
                helper.assertTrue(bob.live.health < bob.live.maxHealth && !bob.live.isDeadOrDying, "a blow that does not kill hurts: ${bob.live.health}")

                val kills = GuardStates.read(bob.live).narratorKills
                val where = bob.live.position()
                bob.live.health = 1f
                bob.live.damageCooldownTime = 0
                zombie.doHurtTarget(helper.level, bob.live)
                helper.assertFalse(bob.live.isDeadOrDying, "the fatal blow is refused")
                helper.assertEquals(DeathGuard.RESCUE_HEALTH, bob.live.health, "rescued, with three hearts")
                helper.assertTrue(bob.live.position().distanceTo(where) < 0.01, "where he stood: ${bob.live.position()} (was $where)")
                helper.assertEquals(5, count(bob.live, Items.DIAMOND), "nothing lost")
                val state = GuardStates.read(bob.live)
                helper.assertTrue(state.narratorKills == kills && state.lastDeathAt == null && !state.protectedDeath, "no death, none counted: $state")
                helper.assertTrue((state.shield?.until ?: 0L) > System.currentTimeMillis(), "shielded from the Narrator a while: ${state.shield}")
                helper.assertTrue(zombie.isAlive && rig.creatures.own().any { it.uuid() == zombie.stringUUID }, "the zombie stays its module's")

                ann.close()
                helper.assertFalse(zombie.isRemoved, "nor goes with Ann")
            } finally {
                zombie.discard()
            }
        }
    }

    /**
     * A module's blocks go in thin air only (decision Q4), through the `BlockRestorer`: never on stone, never another
     * block than the SDK's; away from every player they are nobody's trap; near a player they are a trap of that
     * player, refused like any punishment (protected), within the chaos level's length; and whoever stands on one when
     * it goes falls without fall damage, the death guard covering a fall.
     */
    @GameTest(environment = FORGE_ENVIRONMENT, maxTicks = ASYNC_MAX_TICKS)
    fun aModulesBlocksGoInThinAirOnlyAndAreAPunishmentNearAPlayer(helper: GameTestHelper) {
        floor(helper)
        val bob = TestPlayer(helper)
        val rig = Rig(helper, 990_705)
        val dimension = "overworld"
        fun cell(x: Int, y: Int, z: Int): Cell = helper.absolutePos(BlockPos(x, y, z)).let { Cell(dimension, it.x, it.y, it.z) }
        helper.closingOnFailure(AutoCloseable { rig.close(); bob.close() }, ASYNC_MAX_TICKS) { _ ->
            bob.standAt(BlockPos(1, 1, 1))
            bob.makeVulnerable()
            val stone = rig.outcome { rig.ground.place(listOf(cell(6, 0, 6)), "glass", 10) }
            helper.assertTrue(stone.text().startsWith("FAILED: ") && stone.text().endsWith("is not air (minecraft:stone)"), stone.text())
            val wrong = rig.outcome { rig.ground.place(listOf(cell(6, 2, 6)), "stone", 10) }
            helper.assertTrue(wrong.text().startsWith("Invalid arguments: block is glass, a stained glass"), wrong.text())

            val far = rig.outcome { rig.ground.place(listOf(cell(6, 3, 6), cell(6, 3, 5)), "red_stained_glass", 10) }
            helper.assertTrue(far.ok() && "trap" !in far.text(), far.text())
            helper.assertBlockPresent(Blocks.STAINED_GLASS.red(), BlockPos(6, 3, 6))
            helper.assertEquals(null, GuardStates.read(bob.live).episode, "away from every player: no punishment")

            rig.guards.setProtected(bob.uuid, bob.name, on = true)
            val shielded = rig.outcome { rig.ground.place(listOf(cell(2, 1, 1)), "cobweb", 10) }
            rig.guards.setProtected(bob.uuid, bob.name, on = false)
            helper.assertTrue(shielded.text().startsWith("DENIED (protected): "), shielded.text())
            helper.assertBlockPresent(Blocks.AIR, BlockPos(2, 1, 1))
            val long = rig.outcome { rig.ground.place(listOf(cell(2, 1, 1)), "cobweb", 100) }
            helper.assertTrue(long.text().startsWith("Invalid arguments: near a player, blocks stay at most "), long.text())
            val web = rig.outcome { rig.ground.place(listOf(cell(2, 1, 1)), "cobweb", 10) }
            helper.assertTrue(web.ok() && "; a trap of " in web.text(), web.text())
            helper.assertTrue(GuardStates.read(bob.live).episode != null && rig.guards.death.isArmed(bob.live), "a punishment of Bob, under the death guard")

            // A floor of a second under Bob, high up: it goes, and Bob falls without fall damage.
            val floorCell = cell(4, 3, 4)
            val platform = rig.outcome { rig.ground.place(listOf(floorCell), "glass", 1) }
            helper.assertTrue(platform.ok(), platform.text())
            bob.live.teleportTo(floorCell.x() + 0.5, floorCell.y() + 1.0, floorCell.z() + 0.5)
        }
        helper.startSequence()
            .thenAwait { helper.assertBlockPresent(Blocks.AIR, BlockPos(4, 3, 4)) }
            .thenExecute {
                val cushioned = rig.guards.attributes.on(bob.live).any { it.attribute == AttributeKey.FALL_DAMAGE_MULTIPLIER.id && it.amount <= -1.0 }
                helper.assertTrue(cushioned, "no fall damage for Bob: ${rig.guards.attributes.on(bob.live)}")
                helper.assertTrue(rig.guards.death.armedFor(bob.live, Peril.FALL) > DeathGuard.playTime(bob.live), "the death guard covers his fall")
                rig.close()
                bob.close()
            }
            .thenSucceed()
    }

    private companion object {
        /** A creature of [type] in the wild at [at] (relative to the test), without AI: nobody's. */
        fun <T : Mob> creature(helper: GameTestHelper, type: EntityType<T>, at: Vec3): T {
            val mob = type.create(helper.level, EntitySpawnReason.COMMAND)!!
            mob.snapTo(helper.absoluteVec(at))
            mob.isNoAi = true
            helper.assertTrue(helper.level.addFreshEntity(mob), "${BuiltInRegistries.ENTITY_TYPE.getKey(type)} added")
            return mob
        }
    }
}
