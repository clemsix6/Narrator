package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.core.onServerThread
import io.github.clemsix6.narrator2.director.DirectorControl
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.director.MechanicSignal
import io.github.clemsix6.narrator2.event.ExplosionHappened
import io.github.clemsix6.narrator2.guard.NarratorMobs
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.mechanics.Engine
import io.github.clemsix6.narrator2.mechanics.EngineActions
import io.github.clemsix6.narrator2.mechanics.MechanicsState
import io.github.clemsix6.narrator2.mechanics.RuleSpec
import io.github.clemsix6.narrator2.mechanics.RuleState
import io.github.clemsix6.narrator2.mechanics.RuleView
import io.github.clemsix6.narrator2.mechanics.StateStore
import io.github.clemsix6.narrator2.mechanics.rule.RuleBook
import io.github.clemsix6.narrator2.mechanics.rule.RuleEngine
import io.github.clemsix6.narrator2.mechanics.rule.RuleMarks
import io.github.clemsix6.narrator2.mechanics.rule.RuleValidator
import io.github.clemsix6.narrator2.mechanics.rule.ServerRuleIds
import io.github.clemsix6.narrator2.mechanics.rule.ruleOnlyTools
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.CanonEntry
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.memory.InteractionKind
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.observer.PlacedBlocks
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.testing.FakeDirector
import io.github.clemsix6.narrator2.testing.MechanicsSamples
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolArgs
import io.github.clemsix6.narrator2.tool.ToolCatalog
import io.github.clemsix6.narrator2.tool.action.MobAttributeKey
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.TraceSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.jsonPrimitive
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.chat.ChatType
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.PlayerChatMessage
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.monster.zombie.ZombifiedPiglin
import net.minecraft.world.entity.projectile.arrow.Arrow
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.portal.TeleportTransition
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.abs

/**
 * In-game tests of the rules at work (M4-18, M4 §8.6, §21.2): each of the seven rules of the specification (§8.8)
 * from its creation to its end — created by the real rule book (canon and state), set off by a real fact of the
 * world (an arrow, a hit, a creature that loads, a block broken, a dimension changed), its effect seen in the world
 * through the real tools and guards, then ended cleanly (canon closed, marks taken off the creatures). Then the
 * limits, a rule disabled after five failures, a rule that lives through a restart (a `mob_spawn` law applied to the
 * creatures already loaded when the server starts, another past its time ending at the first second), and the
 * rising edge of `position`.
 *
 * Each test builds its own engine (the rules' part in the engine's host) on a state in memory and a clock of its own,
 * which it drives; the canon, the tools, the guards and the Journal are the server's. The engine looks after the
 * creatures of its own test area only, and the creatures' load hook reaches it through [Loads]. The tests set off
 * explosions: they run in their own batch ([RULES_ENVIRONMENT]).
 */
class RuleEngineGameTests {

    /** §8.8: a skeleton's arrow that hits a player explodes where it hit; a second one, while the first act's punishment runs, plays harmless. */
    @GameTest(environment = RULES_ENVIRONMENT, maxTicks = ASYNC_MAX_TICKS)
    fun aSkeletonsArrowExplodesOnThePlayerItHits(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 1) { rig, (bob) ->
            val law = rig.create(example("skeleton_arrows"))
            val skeleton = rig.onServer {
                floor(helper)
                bob.standAt(BlockPos(3, 1, 3))
                bob.makeVulnerable()
                bob.live.addEffect(MobEffectInstance(MobEffects.RESISTANCE, 2_400, 3)) // a blast at his feet hurts, never kills
                helper.spawnWithNoFreeWill(EntityTypes.SKELETON, BlockPos(6, 1, 6))
            }
            try {
                rig.onServer { shoot(helper, skeleton, BlockPos(3, 1, 3)) }
                rig.await("the blast where the arrow hit") { rig.blasts(BlockPos(3, 1, 3)).isNotEmpty() }
                rig.await("the firing counted") { rig.rule(law)?.fired == 1 }
                val first = rig.decisions("action").single()
                check(first.reason!!.startsWith("explosion ok: ok: explosion of power 2, at x ") && "hurt: ${bob.name} " in first.reason!!) { first.reason!! }
                check(rig.decisions("fired").single().reason == "${law.ref} a skeleton's arrow hit ${bob.name} at ${pos(helper, BlockPos(3, 1, 3))}") {
                    rig.decisions("fired").single().reason!!
                }
                check(rig.onServer { bob.live.health < 20f && bob.live.isAlive }) { "hurt, alive" }

                rig.clock.advance(Duration.ofSeconds(4))
                rig.onServer { shoot(helper, skeleton, BlockPos(3, 1, 3)) }
                rig.await("the second firing counted") { rig.rule(law)?.fired == 2 }
                val second = rig.decisions("action").drop(1).map { it.detail!!.getValue("status").jsonPrimitive.content to it.reason!! }
                check(second.map { it.first } == listOf("denied", "ok") && "harmless" in second[1].second) { "refused, then played harmless: $second" }

                rig.clock.advance(Duration.ofMinutes(120))
                rig.onServer { rig.second() }
                rig.awaitEnd(law, CanonStatus.RETIRED, "its time ran out")
                check(rig.ruleLines().single() == "${law.ref} law ended: its time is up (2 fired)") { rig.ruleLines().toString() }
            } finally {
                rig.onServer { skeleton.discard() }
            }
        }
    }

    /** §8.8: hitting a silverfish sends the player somewhere at random, 150 to 300 blocks away. */
    @GameTest(environment = RULES_ENVIRONMENT, maxTicks = ASYNC_MAX_TICKS)
    fun hittingASilverfishSendsThePlayerFarAway(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 1) { rig, (bob) ->
            val law = rig.create(example("silverfish"))
            val (fish, start) = rig.onServer {
                floor(helper)
                bob.standAt(BlockPos(3, 1, 3))
                bob.makeVulnerable()
                val fish = helper.spawnWithNoFreeWill(EntityTypes.SILVERFISH, BlockPos(4, 1, 4))
                fish.hurtServer(helper.level, helper.level.damageSources().playerAttack(bob.live), 1f)
                fish to bob.live.position()
            }
            try {
                rig.await("the player teleported", timeoutMs = 30_000) { horizontal(bob.live.position(), start) > 100.0 }
                val away = rig.onServer { horizontal(bob.live.position(), start) }
                check(away in 140.0..320.0) { "half the radius to the radius: $away" }
                rig.await("the firing counted") { rig.rule(law)?.fired == 1 }
                val teleport = rig.decisions("action").single().reason!!
                check(teleport.startsWith("teleport ok: ok: ${bob.name} teleported at random, ")) { teleport }
                rig.remove(law)
                rig.awaitEnd(law, CanonStatus.RETIRED, NOTE)
            } finally {
                rig.onServer { fish.discard() }
            }
        }
    }

    /** §8.8: a cow hit levitates, then explodes harmlessly (and maybe in fireworks) where it is 3 s later; its marks go with the law. */
    @GameTest(environment = RULES_ENVIRONMENT, maxTicks = ASYNC_MAX_TICKS)
    fun aCowHitFliesThenBurstsWhereItIs(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 1) { rig, (bob) ->
            val law = rig.create(example("flying_cows"))
            val cow = rig.onServer {
                floor(helper)
                bob.standAt(BlockPos(1, 1, 1))
                bob.makeVulnerable()
                helper.spawn(EntityTypes.COW, BlockPos(5, 1, 5))
            }
            try {
                rig.onServer { cow.hurtServer(helper.level, helper.level.damageSources().playerAttack(bob.live), 1f) }
                rig.await("the cow levitates") { cow.hasEffect(MobEffects.LEVITATION) }
                check(rig.onServer { cow.entityTags().containsAll(listOf(NarratorMobs.TAG, RuleMarks.tag(law.ref.id))) }) { "marked as the law's: ${cow.entityTags()}" }
                check(rig.blastsNear(cow.blockPosition()).isEmpty()) { "nothing before its 3 s" }

                rig.clock.advance(Duration.ofSeconds(3))
                rig.await("the blast where the cow is") { rig.blastsNear(cow.blockPosition()).isNotEmpty() }
                rig.await("the firing counted") { rig.rule(law)?.fired == 1 }
                val actions = rig.decisions("action").associate { it.detail!!.getValue("tool").jsonPrimitive.content to it.reason!! }
                check(actions.getValue("mob_effect").startsWith("mob_effect ok: ok: minecraft:cow gets levitation II for 3s")) { actions.toString() }
                check(actions.getValue("explosion").startsWith("explosion ok: ok: explosion of power 2, ") && "harmless" in actions.getValue("explosion")) { actions.toString() }
                // Fireworks at a point want a pad 6 blocks from every player: near the player who hit, they often fail (M4 §8.8).
                check(actions.getValue("fireworks").startsWith("fireworks ok:") || actions.getValue("fireworks").startsWith("fireworks failed:")) { actions.toString() }

                rig.remove(law)
                rig.onServer { rig.second() }
                rig.await("the cow's marks taken off") { cow.entityTags().none { it == NarratorMobs.TAG || it.startsWith(RuleMarks.TAG_PREFIX) } }
                rig.awaitEnd(law, CanonStatus.RETIRED, NOTE)
            } finally {
                rig.onServer { cow.discard() }
            }
        }
    }

    /**
     * §8.8: the creepers under caffeine — the one already loaded when the law is made, then one that appears (the
     * creatures' load hook); never a creeper a player named. The law ends: their modifiers and marks come off; and a
     * creeper saved with them that loads again loses them at once.
     */
    @GameTest(environment = RULES_ENVIRONMENT, maxTicks = ASYNC_MAX_TICKS)
    fun creepersUnderCaffeineAndBackToNormal(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 0) { rig, _ ->
            val (before, named) = rig.onServer {
                floor(helper)
                val named = helper.spawnWithNoFreeWill(EntityTypes.CREEPER, BlockPos(6, 1, 2))
                named.customName = Component.literal("Gérard")
                helper.spawnWithNoFreeWill(EntityTypes.CREEPER, BlockPos(2, 1, 2)) to named
            }
            val creatures = mutableListOf<Mob>(before, named)
            try {
                val law = rig.create(example("caffeinated_creepers"))
                rig.onServer { rig.second() }
                rig.await("the creeper already loaded is caffeinated") { speed(before, law) == 2.0 }
                check(speed(named, law) == null) { "a creeper a player named is left alone" }
                check(rig.decisions("fired").single().reason == "${law.ref} changes the 1 creatures already loaded: 1 creeper") { rig.decisions("fired").toString() }

                val after = rig.onServer { helper.spawnWithNoFreeWill(EntityTypes.CREEPER, BlockPos(4, 1, 6)) }
                creatures += after
                rig.await("the creeper that appeared is caffeinated") { speed(after, law) == 2.0 }
                check(rig.onServer { after.entityTags().containsAll(listOf(NarratorMobs.TAG, RuleMarks.tag(law.ref.id))) }) { after.entityTags().toString() }
                rig.await("both counted") { rig.rule(law)?.fired == 2 }

                rig.remove(law)
                rig.onServer { rig.second() }
                rig.await("back to normal") { listOf(before, after).all { speed(it, law) == null && it.entityTags().none { tag -> tag == NarratorMobs.TAG || tag.startsWith(RuleMarks.TAG_PREFIX) } } }
                rig.awaitEnd(law, CanonStatus.RETIRED, NOTE)

                val reloaded = rig.onServer {
                    val creeper = EntityTypes.CREEPER.create(helper.level, EntitySpawnReason.LOAD)!!
                    creeper.setPos(helper.absoluteVec(Vec3(6.5, 1.0, 6.5)))
                    creeper.isNoAi = true
                    RuleMarks.mark(creeper, law.ref.id)
                    creeper.getAttribute(Attributes.MOVEMENT_SPEED)!!.addOrReplacePermanentModifier(
                        AttributeModifier(RuleMarks.modifier(law.ref.id, MobAttributeKey.MOVEMENT_SPEED), 2.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL),
                    )
                    helper.level.addFreshEntity(creeper)
                    creeper
                }
                creatures += reloaded
                check(rig.onServer { speed(reloaded, law) == null && reloaded.entityTags().none { it == NarratorMobs.TAG || it.startsWith(RuleMarks.TAG_PREFIX) } }) {
                    "a creeper loaded with the marks of an ended law loses them as it loads: ${reloaded.entityTags()}"
                }
            } finally {
                rig.onServer { creatures.forEach { it.discard() } }
            }
        }
    }

    /** §8.8: a diamond ore mined explodes where it was, no block touched; a second one within 3 s by the same player is held back by the limits. */
    @GameTest(environment = RULES_ENVIRONMENT, maxTicks = ASYNC_MAX_TICKS)
    fun aDiamondOreMinedExplodesAndTheLimitsHoldTheNextOneBack(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 1) { rig, (bob) ->
            val law = rig.create(example("explosive_diamonds"))
            rig.onServer {
                floor(helper)
                helper.setBlock(BlockPos(1, 1, 1), Blocks.DIAMOND_ORE)
                helper.setBlock(BlockPos(1, 1, 6), Blocks.DEEPSLATE_DIAMOND_ORE)
                helper.setBlock(BlockPos(1, 2, 1), Blocks.OAK_PLANKS)
                bob.standAt(BlockPos(6, 1, 1))
                bob.makeVulnerable()
                check(bob.live.gameMode.destroyBlock(helper.absolutePos(BlockPos(1, 1, 1)))) { "the first ore mined" }
                check(bob.live.gameMode.destroyBlock(helper.absolutePos(BlockPos(1, 1, 6)))) { "the second ore mined" }
            }
            rig.await("the blast where the ore was") { rig.blasts(BlockPos(1, 1, 1)).isNotEmpty() }
            rig.await("one firing, one held back") {
                rig.second()
                rig.rule(law)?.let { it.fired == 1 && it.skipped == 1 } == true
            }
            check(rig.decisions("fired").size == 1) { rig.decisions("fired").toString() }
            val throttled = rig.decisions("throttled").single().reason!!
            check(throttled.startsWith("${law.ref}: 1 firings held back by the limits since ")) { throttled }
            check(rig.blasts(BlockPos(1, 1, 6)).isEmpty()) { "no blast for the ore held back" }
            rig.onServer {
                check(helper.getBlockState(BlockPos(1, 2, 1)).`is`(Blocks.OAK_PLANKS) && helper.getBlockState(BlockPos(1, 0, 1)).`is`(Blocks.STONE)) { "no block touched" }
                check(bob.live.health < 20f && bob.live.isAlive) { "hurt, alive: ${bob.live.health}" }
            }
            rig.clock.advance(Duration.ofMinutes(120))
            rig.onServer { rig.second() }
            rig.awaitEnd(law, CanonStatus.RETIRED, "its time ran out")
        }
    }

    /** §8.8: who breaks another player's build is made tiny and told the law is the law; their own block is no grief. */
    @GameTest(environment = RULES_ENVIRONMENT, maxTicks = ASYNC_MAX_TICKS)
    fun whoBreaksAnotherPlayersBuildIsMadeTiny(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 2) { rig, (builder, breaker) ->
            val law = rig.create(example("grief_law"))
            rig.onServer {
                floor(helper)
                placeAs(helper, BlockPos(2, 1, 2), builder.uuid)
                placeAs(helper, BlockPos(4, 1, 4), breaker.uuid)
                breaker.standAt(BlockPos(3, 1, 3))
                breaker.makeVulnerable()
                breaker.forget()
                check(breaker.live.gameMode.destroyBlock(helper.absolutePos(BlockPos(2, 1, 2)))) { "the builder's planks broken" }
            }
            rig.await("the breaker made tiny") { breaker.live.getAttributeValue(Attributes.SCALE) < 1.0 }
            rig.await("the firing counted") { rig.rule(law)?.fired == 1 }
            check(rig.onServer { breaker.live.getAttributeValue(Attributes.SCALE) } == 0.5) { "tiny, severity 2" }
            rig.await("the law told in the action bar") {
                breaker.packets.filterIsInstance<ClientboundSystemChatPacket>().any { it.overlay() && it.content().string == "La loi est la loi, ${breaker.name}." }
            }
            awaitCondition("the punishment in the breaker's history") {
                rig.runtime.memory.store.interactions(breaker.uuid, 10).any { it.kind == InteractionKind.PUNISHMENT && it.canon == law.ref && it.source == Source.ENGINE }
            }

            rig.clock.advance(Duration.ofSeconds(4))
            rig.onServer {
                check(breaker.live.gameMode.destroyBlock(helper.absolutePos(BlockPos(4, 1, 4)))) { "his own block" }
                rig.tick()
                rig.second()
            }
            check(rig.decisions("fired").size == 1) { "his own block is no grief: ${rig.decisions("fired")}" }
            rig.remove(law)
            rig.awaitEnd(law, CanonStatus.RETIRED, NOTE)
        }
    }

    /** §8.8: the plan of a welcome committee — three named zombified piglins when the player enters the Nether, a `RULE` to the Director, and done. */
    @GameTest(environment = RULES_ENVIRONMENT, maxTicks = ASYNC_MAX_TICKS)
    fun aWelcomeCommitteeAwaitsThePlayerInTheNetherOnce(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 1) { rig, (bob) ->
            rig.runtime.journal.barrier() // the player's row, which the plan names
            val plan = rig.create(example("welcome_plan").replace("\"Bartok\"", "\"${bob.name}\"").replace("de Bartok", "de ${bob.name}"))
            val server = helper.level.server
            val nether = server.getLevel(Level.NETHER)!!
            val room = rig.onServer {
                val at = helper.absolutePos(BlockPos(4, 0, 4))
                room(nether, BlockPos(at.x, ROOM_Y, at.z))
            }
            var committee = emptyList<ZombifiedPiglin>()
            try {
                rig.onServer { bob.live.teleport(TeleportTransition(nether, Vec3.atBottomCenterOf(room), Vec3.ZERO, 0f, 0f, TeleportTransition.DO_NOTHING)) }
                rig.await("the spawn's answer") { rig.decisions("action").any { it.detail!!.getValue("tool").jsonPrimitive.content == "spawn" } }
                val spawned = rig.decisions("action").first { it.detail!!.getValue("tool").jsonPrimitive.content == "spawn" }.reason!!
                check(spawned.startsWith("spawn ok: ok: 3 minecraft:zombified_piglin ")) { spawned }
                rig.await("the welcome committee") {
                    committee = nether.getEntitiesOfClass(ZombifiedPiglin::class.java, AABB(room).inflate(12.0)) { it.customName?.string == "Comité d'accueil" }
                    committee.size == 3
                }
                rig.await("the Director told") { rig.director.signals.any { it is DirectorSignal.Mechanic && it.kind == MechanicSignal.RULE } }
                val told = rig.director.signals.filterIsInstance<DirectorSignal.Mechanic>().single()
                check(told.line == "${plan.ref} fired: ${bob.name} entered the_nether (${room.x} ${room.y} ${room.z}); your note: « le comité d'accueil de ${bob.name} est arrivé »") {
                    told.line
                }
                check(!told.priority) { "no priority" }
                rig.await("the plan fulfilled") { rig.store.get().rules.isEmpty() }
                rig.awaitEnd(plan, CanonStatus.FULFILLED, "fired once: ${bob.name} entered the_nether (${room.x} ${room.y} ${room.z})")

                rig.onServer {
                    bob.live.teleport(TeleportTransition(helper.level, helper.absoluteVec(Vec3(3.5, 1.0, 3.5)), Vec3.ZERO, 0f, 0f, TeleportTransition.DO_NOTHING))
                    bob.live.teleport(TeleportTransition(nether, Vec3.atBottomCenterOf(room), Vec3.ZERO, 0f, 0f, TeleportTransition.DO_NOTHING))
                    rig.tick()
                    rig.second()
                }
                check(rig.decisions("fired").size == 1) { "once: ${rig.decisions("fired")}" }
            } finally {
                rig.onServer {
                    committee.forEach { it.discard() }
                    bob.live.teleport(TeleportTransition(helper.level, helper.absoluteVec(Vec3(3.5, 1.0, 3.5)), Vec3.ZERO, 0f, 0f, TeleportTransition.DO_NOTHING))
                }
            }
        }
    }

    /** A rule whose effect fails five times in a row is disabled: its canon retired with the failure, the Director told, out of the state. */
    @GameTest(environment = RULES_ENVIRONMENT, maxTicks = ASYNC_MAX_TICKS)
    fun aRuleThatFailsFiveTimesInARowIsDisabled(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 1) { rig, (bob) ->
            rig.runtime.journal.barrier()
            val law = rig.create(chatLaw(bob, "coucou", """{"tool":"teleport","at":"actor","args":{"to_player":"Personne"}}"""))
            repeat(5) { i ->
                rig.onServer { say(helper.level.server, bob, "coucou") }
                rig.await("failure ${i + 1}") { (rig.rule(law)?.failures ?: 5) >= i + 1 }
                rig.clock.advance(Duration.ofSeconds(3))
            }
            rig.await("out of the state") { rig.store.get().rules.isEmpty() }
            val failure = "teleport: Invalid arguments: Personne is not online"
            val canon = rig.awaitEnd(law, CanonStatus.RETIRED)
            check(canon.resolution!!.startsWith("disabled after 5 failures in a row: $failure")) { canon.resolution!! }
            check(rig.ruleLines().single().startsWith("${law.ref} disabled after 5 failures: $failure")) { rig.ruleLines().toString() }
            check(rig.decisions("failed").size == 5 && rig.decisions("disabled").size == 1) { "decisions: ${rig.decisions("failed").size} failed" }
        }
    }

    /**
     * A rule lives through a restart (M4 §4.6): the state saved with the world, a new engine on it; the `mob_spawn` law
     * changes the creeper that loaded while the server was down as it starts; the law whose time ran out meanwhile
     * ends at the first second; the greeting law still answers.
     */
    @GameTest(environment = RULES_ENVIRONMENT, maxTicks = ASYNC_MAX_TICKS)
    fun aRuleLivesThroughARestart(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 1) { rig, (bob) ->
            rig.runtime.journal.barrier()
            val creepers = rig.create(example("caffeinated_creepers"))
            val greeting = rig.create(chatLaw(bob, "bonjour", """{"tool":"say","at":"actor","args":{"text":"{actor}, le Narrateur se souvient."}}"""))
            val brief = rig.create(chatLaw(bob, "adieu", """{"tool":"say","at":"actor","args":{"text":"Adieu, {actor}."}}""", minutes = 5))
            val first = rig.onServer {
                floor(helper)
                helper.spawnWithNoFreeWill(EntityTypes.CREEPER, BlockPos(2, 1, 2))
            }
            val creatures = mutableListOf<Mob>(first)
            try {
                rig.onServer { rig.second() }
                rig.await("the first creeper caffeinated") { speed(first, creepers) == 2.0 }
                val saved = rig.onServer {
                    rig.stop()
                    MechanicsState.decode(MechanicsState.encode(rig.store.get()))
                }
                rig.store.update { MechanicsState.EMPTY } // the server's state is in its save now
                check(saved.rules.map { it.id } == listOf(creepers, greeting, brief).map { it.ref.id }) { "saved with the world: ${saved.rules}" }

                // While the server is down, a creeper loads and the brief law's time runs out.
                val second = rig.onServer { helper.spawnWithNoFreeWill(EntityTypes.CREEPER, BlockPos(6, 1, 6)) }
                creatures += second
                check(speed(second, creepers) == null) { "no engine runs" }
                rig.clock.advance(Duration.ofMinutes(10))

                val again = Rig(helper, saved, rig.clock, rig.director, rig.sink)
                try {
                    again.onServer { again.start() }
                    again.await("the creeper loaded while the server was down, changed as it starts") { speed(second, creepers) == 2.0 }
                    check(again.decisions("fired").last().reason == "${creepers.ref} changes the 1 creatures already loaded: 1 creeper") { again.decisions("fired").toString() }
                    again.onServer { again.second() }
                    again.awaitEnd(brief, CanonStatus.RETIRED, "its time ran out")
                    check(again.store.get().rules.map { it.id } == listOf(creepers, greeting).map { it.ref.id }) { again.store.get().rules.toString() }

                    bob.forget()
                    again.onServer { say(helper.level.server, bob, "bonjour") }
                    again.await("the greeting after the restart") { bob.chat().any { "${bob.name}, le Narrateur se souvient." in it.content().string } }

                    again.remove(creepers)
                    again.remove(greeting)
                    again.onServer { again.second() }
                    again.await("the creepers back to normal") { listOf(first, second).all { speed(it, creepers) == null } }
                } finally {
                    again.onServer { again.stop() }
                    again.cleanUp()
                }
            } finally {
                rig.onServer { creatures.forEach { it.discard() } }
            }
        }
    }

    /** `position` fires when the player comes where the rule watches, never while they stay there, and again once they left and came back. */
    @GameTest(environment = RULES_ENVIRONMENT, maxTicks = ASYNC_MAX_TICKS)
    fun positionFiresOnItsRisingEdge(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 1) { rig, (bob) ->
            rig.runtime.journal.barrier()
            val high = helper.absolutePos(BlockPos(0, 4, 0)).y
            val law = rig.create(
                """{"kind":"law","statement":"Le Narrateur a décrété une loi d'essai.","trigger":"position","when":{"players":["${bob.name}"],"y_above":$high},""" +
                    """"effects":[{"tool":"notify","at":"actor","args":{"note":"il prend de la hauteur"}}],"minutes":60}""",
            )
            rig.onServer {
                floor(helper)
                bob.standAt(BlockPos(1, 5, 1))
                rig.second()
                rig.second()
            }
            check(rig.rule(law)?.fired == 0 && rig.decisions("fired").isEmpty()) { "already there when it began: nothing" }
            rig.onServer {
                bob.standAt(BlockPos(1, 1, 1))
                rig.second()
                bob.standAt(BlockPos(1, 5, 1))
                rig.second()
            }
            rig.await("fired as he came up") { rig.rule(law)?.fired == 1 }
            check(rig.ruleLines() == listOf("${law.ref} fired: ${bob.name} came at ${pos(helper, BlockPos(1, 5, 1))}; your note: « il prend de la hauteur »")) {
                rig.ruleLines().toString()
            }
            rig.onServer { repeat(4) { rig.second() } }
            check(rig.rule(law)?.fired == 1) { "staying up fires nothing" }
            rig.onServer {
                bob.standAt(BlockPos(1, 1, 1))
                rig.second()
                bob.standAt(BlockPos(1, 5, 1))
                rig.second()
            }
            rig.await("fired again") { rig.rule(law)?.fired == 2 }
            rig.remove(law)
            rig.awaitEnd(law, CanonStatus.RETIRED, NOTE)
        }
    }

    // ── The rig ──

    /**
     * An engine of these tests: the rules' part in the engine's host, on a state in memory ([initial]) and a clock of
     * its own ([clock]), with the server's tools, ids, guards and canon, a fake Director, and the decisions kept. Its
     * engine looks after the creatures of its test's area only.
     */
    private class Rig(
        val helper: GameTestHelper,
        initial: MechanicsState = MechanicsState.EMPTY,
        val clock: TestClock = TestClock(Instant.ofEpochMilli(helper.narrator().clock.millis()), helper.narrator().clock.zone),
        val director: FakeDirector = FakeDirector(clock),
        val sink: KeptDecisions = KeptDecisions(helper.narrator().trace),
    ) {
        val runtime: NarratorRuntime = helper.narrator()
        val services: NarratorServices = object : NarratorServices by runtime {
            override val trace: TraceSink get() = sink
            override val director: DirectorControl get() = this@Rig.director
        }
        val store = StateStore.inMemory(initial)
        val actions = EngineActions(services, ToolCatalog.allTools(services) + ruleOnlyTools())
        val book = RuleBook(services, store, RuleValidator(services, actions, ServerRuleIds(runtime.server)))
        private val area: AABB = helper.bounds.inflate(0.0, 16.0, 0.0)
        val rules = RuleEngine(services, store, book, actions, clock = clock, area = { area.contains(it.position()) })
        val engine = Engine(services, store, listOf(rules), clock)

        /** The explosions of the server, kept to look for this test's own. */
        private val explosions = CopyOnWriteArrayList<ExplosionHappened>()
        private val owner = "rule-engine-test-${UUID.randomUUID()}"

        /** Starts the engine and hears the creatures that load. Server thread. */
        fun start() {
            runtime.bus.subscribeSync("explosions", owner, { it is ExplosionHappened }) { explosions += it.event as ExplosionHappened }
            engine.start(helper.level.server)
            Loads.engines += engine
        }

        /** Stops the engine. Server thread. */
        fun stop() {
            Loads.engines -= engine
            engine.stop()
            runtime.bus.unsubscribeOwner(owner)
        }

        /** A tick of the engine at the clock's time. Server thread. */
        fun tick() = engine.tick(helper.level.server)

        /** A second of the engine. Server thread. */
        fun second() {
            clock.advance(Duration.ofMillis(1_001))
            tick()
        }

        suspend fun <T> onServer(block: () -> T): T = helper.level.server.onServerThread(10_000, block)

        /** Ticks the engine until [condition] holds (the firings' coroutines run their tools meanwhile). */
        suspend fun await(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) =
            awaitCondition(what, timeoutMs) { onServer { tick(); condition() } }

        suspend fun create(json: String): RuleView = book.create(spec(json), DIRECTOR)

        suspend fun remove(rule: RuleView) {
            book.remove(rule.ref, NOTE, Author(Source.OPERATOR, operator = "gametest"))
        }

        fun rule(view: RuleView): RuleState? = store.get().rule(view.ref.id)

        fun decisions(name: String): List<DecisionRecord> = sink.kept.filter { it.kind == DecisionRecord.Kind.MECHANIC && it.decision == name }

        /** The lines of the `RULE` events the Director got. */
        fun ruleLines(): List<String> = director.signals.filterIsInstance<DirectorSignal.Mechanic>().filter { it.kind == MechanicSignal.RULE }.map { it.line }

        /** The Narrator's explosions at the block [rel] of the test, give or take a block. */
        fun blasts(rel: BlockPos): List<ExplosionHappened> = blastsNear(helper.absolutePos(rel), 1)

        /** The Narrator's explosions within [blocks] blocks of [at] (absolute), a little more upward. */
        fun blastsNear(at: BlockPos, blocks: Int = 2): List<ExplosionHappened> =
            explosions.filter { it.narrator && abs(it.pos.x - at.x) <= blocks && abs(it.pos.y - at.y) <= blocks + 1 && abs(it.pos.z - at.z) <= blocks }

        /** [rule] ended: its canon row closed [status] by the engine (with [resolution] when given). */
        suspend fun awaitEnd(rule: RuleView, status: CanonStatus, resolution: String? = null): CanonEntry {
            awaitCondition("${rule.ref} closed $status") { runtime.memory.store.canon(rule.ref)?.status == status }
            val canon = runtime.memory.store.canon(rule.ref)!!
            check(resolution == null || canon.resolution == resolution) { "the resolution of ${rule.ref}: ${canon.resolution}" }
            check(store.get().rule(rule.ref.id) == null) { "${rule.ref} out of the state" }
            return canon
        }

        /** The rules left at the end of a test removed, their canon rows closed. */
        suspend fun cleanUp() {
            for (rule in store.snapshot.rules) runCatching { book.remove(rule.ref, NOTE, Author(Source.OPERATOR, operator = "gametest")) }
        }
    }

    /** The creatures' load hook (`ENTITY_LOAD`) for the engines of these tests: the server's own hook reaches the server's engine only. */
    private object Loads {
        val engines = CopyOnWriteArraySet<Engine>()

        init {
            ServerEntityEvents.ENTITY_LOAD.register { entity, level -> if (entity is Mob) engines.forEach { it.onMobLoad(entity, level) } }
        }
    }

    /** A trace sink that keeps the decisions it passes on to the Journal. */
    private class KeptDecisions(private val inner: TraceSink) : TraceSink by inner {
        val kept = CopyOnWriteArrayList<DecisionRecord>()

        override fun decision(r: DecisionRecord) {
            kept += r
            inner.decision(r)
        }
    }

    private companion object {
        /** The batch of these tests (`test_environment/rules.json`): they set off explosions. */
        const val RULES_ENVIRONMENT = "narrator2-gametest:rules"

        val DIRECTOR = Caller(Role.DIRECTOR, "director-gametest", turnId = 4242, taskId = null)

        /** The note of a rule removed by a test. */
        const val NOTE = "Le test est fini."

        /** The height of the room in the Nether. */
        const val ROOM_Y = 100

        /** Runs [test] with a [Rig] started on the server thread and [count] players; all closed however it ends. */
        suspend fun CoroutineScope.withRig(helper: GameTestHelper, count: Int, test: suspend (Rig, List<TestPlayer>) -> Unit) {
            val server = helper.level.server
            val players = server.onServerThread { List(count) { TestPlayer(helper, "re_") } }
            Loads.engines.size // the hook is registered before the first creature of the test
            val rig = Rig(helper)
            try {
                rig.onServer { rig.start() }
                test(rig, players)
            } finally {
                rig.onServer { rig.stop() }
                rig.cleanUp()
                server.onServerThread { players.forEach { it.close() } }
            }
        }

        /** A rule of the specification, as the model writes it (M4 §8.8). */
        fun example(name: String): String = MechanicsSamples.ruleExamples.getValue(name)

        /** A law set off when [player] says [word] in the public chat, doing [effect], for [minutes]. */
        fun chatLaw(player: TestPlayer, word: String, effect: String, minutes: Int = 60): String =
            """{"kind":"law","statement":"Le Narrateur a décrété une loi d'essai.","trigger":"chat","when":{"players":["${player.name}"],"words":["$word"]},""" +
                """"effects":[$effect],"minutes":$minutes}"""

        fun spec(json: String): RuleSpec = (ToolArgs.decode(RuleSpec.serializer(), json) as ToolArgs.Decoded.Ok).args

        fun floor(helper: GameTestHelper) {
            for (x in 0..7) for (z in 0..7) helper.setBlock(x, 0, z, Blocks.STONE)
        }

        /** `x y z` of the block [rel] of the test, as a fact says it. */
        fun pos(helper: GameTestHelper, rel: BlockPos): String = helper.absolutePos(rel).let { "${it.x} ${it.y} ${it.z}" }

        /** [skeleton] shoots an arrow straight down onto the block [rel]. Server thread. */
        fun shoot(helper: GameTestHelper, skeleton: Mob, rel: BlockPos) {
            val at = helper.absoluteVec(Vec3(rel.x + 0.5, rel.y + 3.2, rel.z + 0.5))
            val arrow = Arrow(helper.level, skeleton, ItemStack(Items.ARROW), ItemStack(Items.BOW))
            arrow.setPos(at)
            arrow.deltaMovement = Vec3(0.0, -1.5, 0.0)
            helper.level.addFreshEntity(arrow)
        }

        /** Planks at [rel], as if [placer] had placed them. Server thread. */
        fun placeAs(helper: GameTestHelper, rel: BlockPos, placer: UUID) {
            val planks = Blocks.OAK_PLANKS.defaultBlockState()
            helper.setBlock(rel, planks)
            PlacedBlocks.mark(helper.level, helper.absolutePos(rel), planks, placer)
        }

        /** [player] says [text] in the public chat, as the client would. Server thread. */
        fun say(server: MinecraftServer, player: TestPlayer, text: String) {
            val live = player.live
            server.playerList.broadcastChatMessage(PlayerChatMessage.unsigned(live.uuid, text), live, ChatType.bind(ChatType.CHAT, live))
        }

        /** The factor of [rule]'s modifier on [creature]'s speed; null without it. */
        fun speed(creature: Mob, rule: RuleView): Double? =
            creature.getAttribute(Attributes.MOVEMENT_SPEED)?.getModifier(RuleMarks.modifier(rule.ref.id, MobAttributeKey.MOVEMENT_SPEED))?.amount()

        fun horizontal(a: Vec3, b: Vec3): Double = Math.hypot(a.x - b.x, a.z - b.z)

        /**
         * A closed room of netherrack in [nether], 19 × 19 × 4 blocks of air around [center] (its floor's first air
         * block): `spawn` puts its creatures 4 to 8 blocks from the player. Server thread.
         */
        fun room(nether: ServerLevel, center: BlockPos): BlockPos {
            for (dx in -10..10) for (dy in -1..4) for (dz in -10..10) {
                val shell = dx == -10 || dx == 10 || dz == -10 || dz == 10 || dy == -1 || dy == 4
                nether.setBlockAndUpdate(center.offset(dx, dy, dz), if (shell) Blocks.NETHERRACK.defaultBlockState() else Blocks.AIR.defaultBlockState())
            }
            return center
        }
    }
}
