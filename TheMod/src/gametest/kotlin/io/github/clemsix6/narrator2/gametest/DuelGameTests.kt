package io.github.clemsix6.narrator2.gametest

import com.mojang.brigadier.CommandDispatcher
import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.command.DuelCommand
import io.github.clemsix6.narrator2.config.UiTexts
import io.github.clemsix6.narrator2.core.onServerThread
import io.github.clemsix6.narrator2.director.DirectorControl
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.director.MechanicSignal
import io.github.clemsix6.narrator2.gametest.GuardGameTests.Companion.basin
import io.github.clemsix6.narrator2.gametest.GuardGameTests.Companion.count
import io.github.clemsix6.narrator2.gametest.GuardGameTests.Companion.homeAt
import io.github.clemsix6.narrator2.gametest.GuardGameTests.Companion.isAt
import io.github.clemsix6.narrator2.guard.GuardStates
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.mechanics.ArenaSpec
import io.github.clemsix6.narrator2.mechanics.Bossbars
import io.github.clemsix6.narrator2.mechanics.ChallengeControl
import io.github.clemsix6.narrator2.mechanics.ChallengeStatus
import io.github.clemsix6.narrator2.mechanics.ChallengeView
import io.github.clemsix6.narrator2.mechanics.DuelSpec
import io.github.clemsix6.narrator2.mechanics.Engine
import io.github.clemsix6.narrator2.mechanics.MechanicPart
import io.github.clemsix6.narrator2.mechanics.MechanicsState
import io.github.clemsix6.narrator2.mechanics.StateStore
import io.github.clemsix6.narrator2.mechanics.challenge.Challenges
import io.github.clemsix6.narrator2.mechanics.challenge.Duels
import io.github.clemsix6.narrator2.memory.CanonEntry
import io.github.clemsix6.narrator2.memory.CanonKind
import io.github.clemsix6.narrator2.memory.CanonMechanic
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.memory.InteractionKind
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.testing.FakeChallenges
import io.github.clemsix6.narrator2.testing.FakeDirector
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.TraceSink
import kotlinx.coroutines.CoroutineScope
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.commands.CommandSourceStack
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundBossEventPacket
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.BossEvent.BossBarColor
import net.minecraft.world.BossEvent.BossBarOverlay
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * In-game tests of the duels (M4-19, M4 §5.5, §21.2). Nobody is ever made to fight: a duel starts only once both
 * players accepted it by `/duel accept` (the invitation's buttons), and an invitation declined, run out or accepted by
 * one player only never starts. Nobody dies in a duel, whatever would kill them — one test per way to die: the
 * opponent's fatal blow (stopped at 1 health, put out, the fight's harmful effects lifted), lava, the void, fire, a
 * fall, a creature (each a rescue, out of the danger), and what is left once the duel is over (a fire, a wither, a
 * last blow). Each time the duelist is alive ticks later, with what they carried, and nothing is counted against the
 * Narrator's quota. An arena takes the duelists face to face and puts them back afterwards, even across a restart.
 *
 * Each test builds its own engine (the challenges' part, the duels' part and the bars) on a store in memory, driven
 * by a clock of its own, with a fake Director; the canon, the interactions and the guards are the running
 * Narrator's. `/duel` runs on a dispatcher of the test's own, wired to its engine. The server's `ALLOW_DEATH` reaches
 * the tests' engines through [DeathHook], in the engine's own phase (before the guards'), as `MechanicsHooks` does
 * for the server's engine.
 */
class DuelGameTests {

    // ── Invitations: nobody is made to fight ──

    /**
     * The invitation's buttons, `/duel accept` suggested and typed by each player: a stranger is not invited, the
     * first acceptance is only acknowledged (nobody is woken), the second starts the duel (a title, the bars, a
     * `DUEL` event without priority). At its deadline, a draw: both told, the Director woken.
     */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun aDuelStartsOnlyOnceBothAcceptByCommandAndIsADrawAtItsDeadline(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 3) { rig, (bob, ann, cid) ->
            val view = rig.propose(spec(bob, ann, "Au soleil"))
            check(view.status == ChallengeStatus.INVITED && view.participants.map { it.uuid } == listOf(bob, ann).map { it.uuid.toString() }) { "invited: $view" }
            val canon = canon(rig, view.ref)
            check(canon.kind == CanonKind.CHALLENGE && canon.mechanic == CanonMechanic.DUEL && canon.status == CanonStatus.ACTIVE) { "the canon row: $canon" }
            check(canon.players.map { it.uuid } == listOf(bob.uuid, ann.uuid) && canon.dueAt == null) { "its players: $canon" }
            check(rig.decisions(Duels.INVITED).single().reason!!.startsWith("${view.ref} invited: ${bob.name} and ${ann.name} « Au soleil », where they stand")) {
                "the invitation: ${rig.decisions(Duels.INVITED)}"
            }
            val ui = rig.ui
            for ((player, opponent) in listOf(bob to ann, ann to bob)) {
                val invite = player.chat().map { it.content() }.single { "duel" in it.string }
                check(ui.format("duel.invite", "opponent" to opponent.name, "title" to "Au soleil", "seconds" to 120, "where" to ui["duel.where.here"]) in invite.string) {
                    "${player.name}'s invitation: ${invite.string}"
                }
                check(commands(invite) == listOf("/duel accept ${view.ref}", "/duel decline ${view.ref}")) { "its buttons: ${commands(invite)}" }
            }

            rig.onServer {
                check(rig.suggestions(bob, "duel accept ") == listOf("${view.ref}")) { "Bob's own invitation is suggested" }
                check(rig.suggestions(cid, "duel accept ").isEmpty()) { "nothing for Cid" }
                rig.command(cid, "duel accept ${view.ref}")
                check(cid.read(line(ui, ui["duel.not_invited"]))) { "Cid is not invited: ${cid.lines()}" }
                rig.command(bob, "duel accept nope")
                check(bob.read(line(ui, ui.format("command.unknown_challenge", "id" to "nope")))) { "an unknown id: ${bob.lines()}" }
                rig.command(bob, "duel accept ${view.ref}")
                check(bob.read(line(ui, ui.format("duel.accepted", "player" to bob.name)))) { "acknowledged: ${bob.lines()}" }
                check(ann.read(line(ui, ui.format("duel.accepted", "player" to bob.name)))) { "Ann learns it: ${ann.lines()}" }
                val waiting = rig.duel(view.ref)!!
                check(waiting.status == ChallengeStatus.INVITED && waiting.participants.map { it.accepted } == listOf(true, false)) { "one acceptance: $waiting" }
                rig.second()
                check(bars(bob).isEmpty()) { "an invitation has no bar" }
                rig.command(ann, "duel accept ${view.ref}")
                check(rig.running(view.ref)) { "both accepted: it runs" }
                for ((player, opponent) in listOf(bob to ann, ann to bob)) {
                    check(player.titles() == listOf(ui.format("duel.start", "opponent" to opponent.name))) { "${player.name}'s title: ${player.titles()}" }
                }
                rig.second()
                check(bars(bob).single().let { it.name.startsWith("Au soleil · ") && it.color == BossBarColor.RED }) { "Bob's bar: ${bars(bob)}" }
                check(bars(cid).isEmpty()) { "Cid takes no part" }
            }
            val start = rig.mechanic().single()
            check(start.kind == MechanicSignal.DUEL && !start.priority && start.line.startsWith("${view.ref} started: ${bob.name} and ${ann.name} accepted; they fight until ")) {
                "the start wakes nobody: $start"
            }
            check(rig.decisions(Duels.ACCEPTED).size == 2 && rig.decisions(Challenges.STARTED).single().reason == start.line) { "the decisions of the start" }

            rig.onServer {
                rig.at(rig.duel(view.ref)!!.endsAt)
                check(rig.store.get().challenges.isEmpty() && bars(bob).isEmpty()) { "a draw at its deadline, bars down" }
                check(bob.read(line(ui, ui["duel.draw"])) && ann.read(line(ui, ui["duel.draw"]))) { "both told" }
            }
            val end = rig.awaitEnd(view.ref)
            check(end == "${view.ref} lost: a draw, nobody won within 5m") { "the end: $end" }
            check(rig.mechanic().last().priority) { "an end wakes the Director" }
            rig.checkClosed(view.ref, CanonStatus.LOST, emptyList(), listOf(bob, ann), end)
        }
    }

    /** Nobody accepts: the invitation runs out, the duel is cancelled without having started; nobody is woken before their time. */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun aDuelNobodyAcceptsExpires(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 2) { rig, (bob, ann) ->
            val view = rig.propose(spec(bob, ann, "Personne"))
            rig.onServer {
                rig.at(view.endsAt - 1_000)
                check(rig.duel(view.ref)?.status == ChallengeStatus.INVITED) { "still invited a second before the end" }
                rig.at(view.endsAt)
                check(rig.store.get().challenges.isEmpty()) { "expired" }
                for (player in listOf(bob, ann)) check(player.read(line(rig.ui, rig.ui["duel.expired"]))) { "${player.name} is told" }
                check(bars(bob).isEmpty() && bob.titles().isEmpty()) { "it never started" }
            }
            val end = rig.awaitEnd(view.ref)
            check(end == "${view.ref} cancelled: nobody accepted within 2m") { "the end: $end" }
            check(rig.mechanic().single().let { it.kind == MechanicSignal.DUEL && !it.priority }) { "no priority for a duel that never started" }
            check(rig.decisions(Challenges.STARTED).isEmpty()) { "never started" }
            rig.checkClosed(view.ref, CanonStatus.CANCELLED, emptyList(), emptyList(), end, interactions = false)
        }
    }

    /** One accepts, the other never does: it never starts, nobody moves, and it is cancelled when the invitation runs out. */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun aDuelAcceptedByOnePlayerOnlyNeverStarts(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 2) { rig, (bob, ann) ->
            val view = rig.propose(spec(bob, ann, "Seul contre personne"))
            rig.onServer {
                val where = bob.live.position()
                rig.command(bob, "duel accept ${view.ref}")
                repeat(3) { rig.second() }
                check(rig.duel(view.ref)?.status == ChallengeStatus.INVITED) { "one acceptance does not start it" }
                rig.at(view.endsAt)
                check(rig.store.get().challenges.isEmpty()) { "cancelled at the end of the invitation" }
                check(bob.live.position() == where && bob.titles().isEmpty() && bars(bob).isEmpty()) { "Bob never fought" }
                check(bob.read(line(rig.ui, rig.ui["duel.expired"]))) { "Bob is told: ${bob.lines()}" }
            }
            val end = rig.awaitEnd(view.ref)
            check(end == "${view.ref} cancelled: ${ann.name} did not accept within 2m (${bob.name} did)") { "the end: $end" }
            check(rig.decisions(Challenges.STARTED).isEmpty() && rig.decisions(Duels.ACCEPTED).size == 1) { "never started" }
            rig.checkClosed(view.ref, CanonStatus.CANCELLED, emptyList(), emptyList(), end, interactions = false)
        }
    }

    /** One declines: the duel is cancelled at once, both told; it is gone for the other's acceptance. */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun aDeclinedDuelIsCancelledAtOnce(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 2) { rig, (bob, ann) ->
            val view = rig.propose(spec(bob, ann, "Non merci"))
            rig.onServer {
                rig.command(bob, "duel accept ${view.ref}")
                rig.command(ann, "duel decline ${view.ref}")
                check(rig.store.get().challenges.isEmpty()) { "cancelled at once" }
                val declined = line(rig.ui, rig.ui.format("duel.declined", "player" to ann.name))
                check(ann.read(declined) && bob.read(declined)) { "both told: ${ann.lines()} / ${bob.lines()}" }
                rig.command(bob, "duel accept ${view.ref}")
                check(bob.read(line(rig.ui, rig.ui.format("command.unknown_challenge", "id" to "${view.ref}")))) { "gone: ${bob.lines()}" }
            }
            val end = rig.awaitEnd(view.ref)
            check(end == "${view.ref} cancelled: ${ann.name} declined") { "the end: $end" }
            check(!rig.mechanic().single().priority) { "no priority" }
            rig.checkClosed(view.ref, CanonStatus.CANCELLED, emptyList(), emptyList(), end, interactions = false)
        }
    }

    // ── Nobody dies in a duel: one test per way to die ──

    /**
     * The opponent's fatal blow (M4 §5.5): stopped at 1 health where Bob stands, his fire out, the wither and the
     * poison of the fight lifted, resistance V a few seconds; Ann wins, Bob is not rescued (nothing to rescue him
     * from), and lives on with his diamonds.
     */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun theOpponentsFatalBlowIsStoppedAtOneHealth(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 2) { rig, (bob, ann) ->
            val view = rig.fight(bob, ann, "Le coup fatal")
            rig.onServer {
                val player = bob.live
                val where = player.position()
                // A sword of fire and a withering arrow went before the blow.
                player.igniteForSeconds(8f)
                player.addEffect(MobEffectInstance(MobEffects.WITHER, 200, 1))
                player.addEffect(MobEffectInstance(MobEffects.POISON, 200, 0))
                player.hurtServer(helper.level, helper.level.damageSources().playerAttack(ann.live), 1_000f)
                check(!player.isDeadOrDying && player.health == 1f) { "stopped at 1 health: ${player.health}" }
                check(!player.isOnFire && !player.hasEffect(MobEffects.WITHER) && !player.hasEffect(MobEffects.POISON)) { "put out, the fight's effects lifted" }
                check(player.getEffect(MobEffects.RESISTANCE)?.amplifier == 4) { "resistance V: ${player.activeEffects}" }
                check(player.position() == where) { "left where he stands" }
                check(rig.store.get().challenges.isEmpty()) { "the duel is over" }
                check(bob.read(line(rig.ui, rig.ui["duel.lost"])) && ann.read(line(rig.ui, rig.ui["duel.won"]))) { "both told" }
                check(GuardStates.read(player).rescues.isEmpty()) { "no rescue: nothing to take him out of" }
            }
            val end = rig.awaitEnd(view.ref)
            check(Regex("^${view.ref} won by ${ann.name} in \\d+s: ${ann.name}'s fatal blow on ${bob.name} was stopped at 1 hp$").matches(end)) { "the end: $end" }
            check(rig.mechanic().last().priority) { "the end wakes the Director" }
            rig.checkClosed(view.ref, CanonStatus.WON, listOf(ann), listOf(bob), end)
            rig.onServer { rig.livesOn(bob, diamonds = 3) }
        }
    }

    /** Lava: Bob is rescued at its first blow, out of it, at his respawn point; Ann wins. */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun lavaNeverKillsADuelist(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 2) { rig, (bob, ann) ->
            val view = rig.fight(bob, ann, "La lave")
            rig.onServer {
                val home = homeAt(helper, bob.live)
                basin(helper, Blocks.LAVA)
                bob.standAt(BlockPos(1, 1, 1))
                val player = bob.live
                player.health = 1f
                repeat(40) { if (rig.running(view.ref)) player.doTick() }
                rig.checkRescued(bob, home)
                basin(helper, Blocks.STONE)
            }
            rig.checkWonByRescue(view.ref, winner = ann, loser = bob, cause = "lava")
            rig.onServer { rig.livesOn(bob, diamonds = 3) }
        }
    }

    /** The void: Bob falls out of the world; he is rescued at its first blow, back on the ground at his respawn point. */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun theVoidNeverKillsADuelist(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 2) { rig, (bob, ann) ->
            val view = rig.fight(bob, ann, "Le vide")
            rig.onServer {
                val home = homeAt(helper, bob.live)
                val player = bob.live
                player.health = 1f
                player.teleportTo(player.x, (helper.level.minY - VOID_DEPTH).toDouble(), player.z)
                repeat(40) { if (rig.running(view.ref)) player.doTick() }
                rig.checkRescued(bob, home)
            }
            rig.checkWonByRescue(view.ref, winner = ann, loser = bob, cause = "out_of_world")
            rig.onServer { rig.livesOn(bob, diamonds = 3) }
        }
    }

    /** Fire: Bob burns at 1 health; the burn that would kill him is a rescue, and he burns no more. */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun fireNeverKillsADuelist(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 2) { rig, (bob, ann) ->
            val view = rig.fight(bob, ann, "Le feu")
            rig.onServer {
                val home = homeAt(helper, bob.live)
                val player = bob.live
                player.igniteForSeconds(10f)
                player.health = 1f
                repeat(60) { if (rig.running(view.ref)) player.doTick() }
                rig.checkRescued(bob, home)
            }
            rig.checkWonByRescue(view.ref, winner = ann, loser = bob, cause = "on_fire")
            rig.onServer { rig.livesOn(bob, diamonds = 3) }
        }
    }

    /** A fall (after a knockback, say) from 60 blocks: a rescue, and no fall damage for a while. */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun aFallNeverKillsADuelist(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 2) { rig, (bob, ann) ->
            val view = rig.fight(bob, ann, "La chute")
            rig.onServer {
                val home = homeAt(helper, bob.live)
                val player = bob.live
                player.causeFallDamage(60.0, 1f, helper.level.damageSources().fall())
                rig.checkRescued(bob, home)
                player.causeFallDamage(60.0, 1f, helper.level.damageSources().fall())
                check(!player.isDeadOrDying && player.health > 1f) { "a fall right after hurts nothing: ${player.health}" }
            }
            rig.checkWonByRescue(view.ref, winner = ann, loser = bob, cause = "fall")
            rig.onServer { rig.livesOn(bob, diamonds = 3) }
        }
    }

    /** A creature's blow (a zombie's): a rescue, out of its reach; Ann wins all the same. */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun aCreatureNeverKillsADuelist(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 2) { rig, (bob, ann) ->
            val view = rig.fight(bob, ann, "La créature")
            rig.onServer {
                val home = homeAt(helper, bob.live)
                bob.standAt(BlockPos(1, 1, 1))
                val zombie = helper.spawnWithNoFreeWill(EntityTypes.ZOMBIE, BlockPos(2, 1, 1))
                try {
                    bob.live.hurtServer(helper.level, helper.level.damageSources().mobAttack(zombie), 1_000f)
                    rig.checkRescued(bob, home)
                } finally {
                    zombie.discard()
                }
            }
            rig.checkWonByRescue(view.ref, winner = ann, loser = bob, cause = "mob_attack by zombie")
            rig.onServer { rig.livesOn(bob, diamonds = 3) }
        }
    }

    /**
     * After the end: Bob burns at 1 health with a wither when the duel reaches its deadline (a draw). The end puts
     * him out and lifts the wither; a last blow of Ann right after the end is stopped at 1 health too. Then, the
     * aftercare over, he lives on.
     */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun nothingKillsADuelistOnceTheDuelIsOver(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 2) { rig, (bob, ann) ->
            val view = rig.fight(bob, ann, "Après la fin", minutes = 1)
            rig.onServer {
                val player = bob.live
                player.igniteForSeconds(30f)
                player.addEffect(MobEffectInstance(MobEffects.WITHER, 600, 1))
                player.health = 1f
                rig.at(rig.duel(view.ref)!!.endsAt)
                check(rig.store.get().challenges.isEmpty()) { "a draw at its deadline" }
                check(!player.isOnFire && !player.hasEffect(MobEffects.WITHER)) { "the end puts him out and lifts the wither" }
                player.hurtServer(helper.level, helper.level.damageSources().playerAttack(ann.live), 1_000f)
                check(!player.isDeadOrDying && player.health == 1f && player.getEffect(MobEffects.RESISTANCE)?.amplifier == 4) {
                    "a last blow after the end is stopped too: ${player.health}"
                }
                repeat(40) { player.doTick() }
                check(!player.isDeadOrDying && !player.isRemoved) { "alive" }
            }
            val end = rig.awaitEnd(view.ref)
            check(end == "${view.ref} lost: a draw, nobody won within 1m") { "the end: $end" }
            rig.checkClosed(view.ref, CanonStatus.LOST, emptyList(), listOf(bob, ann), end)
            rig.onServer { rig.livesOn(bob, diamonds = 3) }
        }
    }

    // ── The arena ──

    /**
     * An arena in their dimension: once both accepted, each is taken face to face 5 blocks from its point; at the
     * end, each is put back where they stood, looking the same way.
     */
    @GameTest(structure = FIELD, skyAccess = true, maxTicks = ASYNC_MAX_TICKS)
    fun anArenaTakesTheDuelistsFaceToFaceAndPutsThemBack(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 2) { rig, (bob, ann) ->
            val (point, places) = rig.onServer { field(helper, bob, ann) }
            val view = rig.propose(spec(bob, ann, "L'arène", arena = ArenaSpec(point.x, point.y, point.z)))
            check(view.duel!!.arena!!.dimension == helper.level.dimension().identifier().toString()) { "the arena is in their dimension: ${view.duel}" }
            rig.onServer {
                rig.command(bob, "duel accept ${view.ref}")
                check(isAt(bob.live, places.first)) { "nobody moves before both accepted" }
                rig.command(ann, "duel accept ${view.ref}")
                check(rig.running(view.ref)) { "it runs" }
                check(isAt(bob.live, point.offset(-5, 0, 0)) && abs(bob.live.yRot - FACING_EAST) < 0.1f) { "Bob, west, looks east: ${bob.live.position()} ${bob.live.yRot}" }
                check(isAt(ann.live, point.offset(5, 0, 0)) && abs(ann.live.yRot - FACING_WEST) < 0.1f) { "Ann, east, looks west: ${ann.live.position()} ${ann.live.yRot}" }
                check(rig.duel(view.ref)!!.arena.map { it.uuid } == listOf(bob, ann).map { it.uuid.toString() }) { "where they stood is kept" }
                rig.at(rig.duel(view.ref)!!.endsAt)
                check(rig.store.get().challenges.isEmpty()) { "a draw at its deadline" }
                check(isAt(bob.live, places.first) && isAt(ann.live, places.second)) { "put back in the same second: ${bob.live.position()} ${ann.live.position()}" }
                check(abs(bob.live.yRot - BOB_YAW) < 0.1f) { "looking the same way: ${bob.live.yRot}" }
                check(rig.store.get().returns.isEmpty()) { "nobody left to put back" }
            }
            val end = rig.awaitEnd(view.ref)
            check(end == "${view.ref} lost: a draw, nobody won within 5m; both put back where they were") { "the end: $end" }
            rig.checkClosed(view.ref, CanonStatus.LOST, emptyList(), listOf(bob, ann), end)
        }
    }

    /**
     * A restart in the middle of a duel in an arena: the engine stops, the state goes through the world's codec, a
     * new engine starts on it. The duel goes on: an arrow of Ann that would kill Bob is stopped at 1 health, Ann
     * wins, and both are put back where they stood before the restart.
     */
    @GameTest(structure = FIELD, skyAccess = true, maxTicks = ASYNC_MAX_TICKS)
    fun aDuelGoesOnAfterARestart(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 2) { rig, (bob, ann) ->
            val (point, places) = rig.onServer { field(helper, bob, ann) }
            val view = rig.propose(spec(bob, ann, "Avant et après", arena = ArenaSpec(point.x, point.y, point.z)))
            val saved = rig.onServer {
                bob.makeVulnerable()
                bob.live.inventory.add(ItemStack(Items.DIAMOND, 3))
                rig.command(bob, "duel accept ${view.ref}")
                rig.command(ann, "duel accept ${view.ref}")
                check(rig.running(view.ref)) { "it runs" }
                rig.engine.stop()
                MechanicsState.decode(MechanicsState.encode(rig.store.get()))
            }
            check(saved.challenges.single().let { it.status == ChallengeStatus.RUNNING && it.arena.size == 2 }) { "the duel is saved with the world: $saved" }

            val again = Rig(helper, rig.director, rig.sink, StateStore.inMemory(saved), rig.clock)
            DeathHook.engines -= rig.engine
            DeathHook.engines += again.engine
            try {
                rig.onServer {
                    rig.clock.advance(Duration.ofMinutes(1))
                    again.engine.start(helper.level.server)
                    val player = bob.live
                    val arrow = EntityTypes.ARROW.create(helper.level, EntitySpawnReason.COMMAND)!!
                    arrow.setOwner(ann.live)
                    player.hurtServer(helper.level, helper.level.damageSources().arrow(arrow, ann.live), 1_000f)
                    check(!player.isDeadOrDying && player.health == 1f) { "the arrow's fatal blow is stopped after the restart: ${player.health}" }
                    check(again.store.get().challenges.isEmpty()) { "Ann won" }
                    again.second()
                    check(isAt(bob.live, places.first) && isAt(ann.live, places.second)) { "put back where they stood before the restart" }
                }
                val end = again.awaitEnd(view.ref)
                check(Regex("^${view.ref} won by ${ann.name} in 1m(\\d\\ds)?: ${ann.name}'s fatal blow on ${bob.name} was stopped at 1 hp; both put back where they were$").matches(end)) {
                    "the end: $end"
                }
                again.checkClosed(view.ref, CanonStatus.WON, listOf(ann), listOf(bob), end)
                rig.onServer { again.livesOn(bob, diamonds = 3) }
            } finally {
                rig.onServer { again.engine.stop() }
                DeathHook.engines -= again.engine
            }
        }
    }

    // ── The rig ──

    /**
     * An engine of the test's own: the challenges' part, the duels' part and the bars on a store in memory, a fake
     * Director, the decisions kept, and [clock] that the test moves; `/duel` on a dispatcher wired to it. The rest is
     * the running Narrator's (memory, Journal, guards).
     */
    private class Rig(
        val helper: GameTestHelper,
        val director: FakeDirector,
        val sink: KeptDecisions,
        val store: StateStore = StateStore.inMemory(),
        val clock: TestClock,
    ) {
        val runtime: NarratorRuntime = helper.narrator()
        val services: NarratorServices = object : NarratorServices by runtime {
            override val trace: TraceSink get() = sink
            override val director: DirectorControl get() = this@Rig.director
        }
        val ui: UiTexts get() = runtime.settings.get().ui
        val bars = Bossbars()
        val challenges = Challenges(services, store, bars, clock = clock)
        val duels = Duels(services, store, challenges, clock = clock)
        val engine = Engine(services, store, listOf(challenges, duels, DrawnBars(bars, store, runtime.settings.get().ui)), clock)

        /** What `/duel` answers to: this engine's duels. */
        private val control: ChallengeControl = object : ChallengeControl by FakeChallenges(clock, AtomicLong(1)) {
            override fun answerDuel(player: ServerPlayer, ref: Ref, accept: Boolean): Component = duels.answer(player, ref, accept)

            override fun list(): List<ChallengeView> = challenges.list()
        }
        private val dispatcher = CommandDispatcher<CommandSourceStack>().also { DuelCommand.register(it) { control } }

        suspend fun propose(spec: DuelSpec): ChallengeView = duels.propose(spec, DIRECTOR)

        /** A duel of [bob] and [ann], both accepted, both able to take damage, Bob with 3 diamonds: running. */
        suspend fun fight(bob: TestPlayer, ann: TestPlayer, title: String, minutes: Int = 5): ChallengeView {
            val view = propose(spec(bob, ann, title, minutes = minutes))
            onServer {
                bob.makeVulnerable()
                ann.makeVulnerable()
                bob.live.inventory.add(ItemStack(Items.DIAMOND, 3))
                command(bob, "duel accept ${view.ref}")
                command(ann, "duel accept ${view.ref}")
                check(running(view.ref)) { "both accepted: ${store.get()}" }
            }
            return view
        }

        fun duel(ref: Ref) = store.get().challenge(ref.id)

        fun running(ref: Ref): Boolean = duel(ref)?.status == ChallengeStatus.RUNNING

        fun decisions(name: String): List<DecisionRecord> = sink.kept.filter { it.kind == DecisionRecord.Kind.MECHANIC && it.decision == name }

        fun mechanic(): List<DirectorSignal.Mechanic> = director.signals.filterIsInstance<DirectorSignal.Mechanic>()

        /** [player] types `/`[text] on the test's dispatcher. Server thread. */
        fun command(player: TestPlayer, text: String) {
            dispatcher.execute(text, player.live.createCommandSourceStack())
        }

        /** What `/`[typed] suggests to [player] next. Server thread. */
        fun suggestions(player: TestPlayer, typed: String): List<String> =
            dispatcher.getCompletionSuggestions(dispatcher.parse(typed, player.live.createCommandSourceStack())).get().list.map { it.text }

        /** A tick of the engine at the clock's time. Server thread. */
        fun tick() = engine.tick(helper.level.server)

        /** A second of the engine. Server thread. */
        fun second() {
            clock.advance(Duration.ofMillis(1_001))
            tick()
        }

        /** A second of the engine at [epochMs]. Server thread. */
        fun at(epochMs: Long) {
            clock.now = Instant.ofEpochMilli(epochMs)
            tick()
        }

        suspend fun <T> onServer(block: () -> T): T = helper.level.server.onServerThread(10_000, block)

        /** Waits for the end of [ref] as the Director hears it: its `DUEL` line (not the start's). */
        suspend fun awaitEnd(ref: Ref): String {
            fun ends() = mechanic().filter { it.kind == MechanicSignal.DUEL && it.line.startsWith("$ref ") && !it.line.startsWith("$ref started") }
            awaitCondition("the end of $ref") { ends().isNotEmpty() }
            return ends().last().line
        }

        /** [player] was rescued (M4-04): alive, at [home], put out, some health, one rescue; nothing counted. Server thread. */
        fun checkRescued(player: TestPlayer, home: BlockPos) {
            val live = player.live
            check(!live.isDeadOrDying && live.health > 0f) { "${player.name} lives: ${live.health}" }
            check(isAt(live, home)) { "taken out of the danger, to the respawn point: ${live.position()}" }
            check(!live.isOnFire) { "put out" }
            check(GuardStates.read(live).rescues.size == 1 && GuardStates.read(live).narratorKills.isEmpty()) { "one rescue, nothing counted: ${GuardStates.read(live)}" }
            check(store.get().challenges.isEmpty()) { "the duel is over" }
        }

        /** The duel [ref] ended won by [winner], [loser] rescued from [cause]: its line, canon and results. */
        suspend fun checkWonByRescue(ref: Ref, winner: TestPlayer, loser: TestPlayer, cause: String) {
            val end = awaitEnd(ref)
            check(Regex("^$ref won by ${winner.name} in \\d+s: ${loser.name} would have died \\(${Regex.escape(cause)}\\) and was rescued instead$").matches(end)) { "the end: $end" }
            check(mechanic().last().priority) { "the end wakes the Director" }
            check(loser.read(line(ui, ui["duel.lost"])) && winner.read(line(ui, ui["duel.won"]))) { "both told" }
            checkClosed(ref, CanonStatus.WON, listOf(winner), listOf(loser), end)
        }

        /**
         * [player] lives on: once the duel's aftercare is over, [LATER_TICKS] ticks of their own later (longer than the
         * resistance, and than the 20 ticks a player refused a death without health back would take to leave), still in
         * the game with health, with their [diamonds], and no death counted against the Narrator. Server thread.
         */
        fun livesOn(player: TestPlayer, diamonds: Int) {
            clock.advance(Duration.ofSeconds(10))
            tick()
            val live = player.live
            repeat(LATER_TICKS) { live.doTick() }
            check(!live.isDeadOrDying && !live.isRemoved && live.health > 0f) { "${player.name} lives on: ${live.health}" }
            check(helper.level.server.playerList.getPlayer(player.uuid) === live) { "still in the game" }
            check(count(live, Items.DIAMOND) == diamonds) { "nothing dropped: ${count(live, Items.DIAMOND)} diamonds" }
            check(GuardStates.read(live).narratorKills.isEmpty()) { "no death counted" }
        }

        /**
         * [ref] ended cleanly: its canon resolved by the engine [status] with [winners], its decision with [line], a
         * `challenge_result` per duelist (+1 for [winners], 0 for [losers]) when [interactions].
         */
        suspend fun checkClosed(ref: Ref, status: CanonStatus, winners: List<TestPlayer>, losers: List<TestPlayer>, line: String, interactions: Boolean = true) {
            awaitCondition("the decision of $ref's end") { sink.kept.any { it.kind == DecisionRecord.Kind.MECHANIC && it.subject == "$ref" && it.decision == status.id } }
            val decision = sink.kept.last { it.kind == DecisionRecord.Kind.MECHANIC && it.subject == "$ref" && it.decision == status.id }
            check(decision.reason == line && decision.conversationId == "mechanics:$ref") { "the decision: $decision" }
            val row = canon(this, ref)
            check(row.status == status && row.winners.map { it.uuid } == winners.map { it.uuid }) { "the canon: ${row.status} ${row.winners}" }
            check(line.removePrefix("$ref ").startsWith(row.resolution.orEmpty()) && !row.resolution.isNullOrEmpty()) { "the resolution says how it ended: ${row.resolution}" }
            if (!interactions) return
            for ((players, valence) in listOf(winners to 1, losers to 0)) {
                for (player in players) {
                    val result = runtime.memory.store.interactions(player.uuid, 10).filter { it.kind == InteractionKind.CHALLENGE_RESULT && it.canon == ref }
                    check(result.size == 1 && result.single().valence == valence && result.single().source == Source.ENGINE) { "${player.name}'s result: $result" }
                }
            }
        }

        companion object {
            operator fun invoke(helper: GameTestHelper): Rig {
                val clock = TestClock(Instant.ofEpochMilli(helper.narrator().clock.millis()), helper.narrator().clock.zone)
                return Rig(helper, FakeDirector(clock), KeptDecisions(helper.narrator().trace), clock = clock)
            }
        }
    }

    /**
     * The server's `ALLOW_DEATH`, for the tests' engines: in the engine's own phase, before the guards', like
     * `MechanicsHooks` for the server's engine (whose `DisabledMechanics` lets every death through until M4-23).
     * Registered once, at its first use; a test adds its engine while it runs.
     */
    private object DeathHook {
        val engines = CopyOnWriteArrayList<Engine>()

        init {
            ServerLivingEntityEvents.ALLOW_DEATH.register(Identifier.fromNamespaceAndPath("narrator2", "mechanics_before_guards")) { entity, source, _ ->
                entity !is ServerPlayer || engines.all { it.allowDeath(entity, source) }
            }
        }
    }

    /** The bars of the rig, drawn from its state when it starts and each second, as the engine's own part does (M4-09). */
    private class DrawnBars(private val bars: Bossbars, private val store: StateStore, private val ui: UiTexts) : MechanicPart {
        override fun started(server: MinecraftServer, now: Long) = bars.render(server, store.get(), ui, now)

        override fun everySecond(server: MinecraftServer, now: Long) = bars.render(server, store.get(), ui, now)

        override fun stopping() = bars.hideEverything()
    }

    /** A trace sink that keeps the decisions it passes on to the Journal. */
    private class KeptDecisions(private val inner: TraceSink) : TraceSink by inner {
        val kept = CopyOnWriteArrayList<DecisionRecord>()

        override fun decision(r: DecisionRecord) {
            kept += r
            inner.decision(r)
        }
    }

    /** A boss bar as the client shows it. */
    private data class ShownBar(val name: String, val color: BossBarColor)

    private companion object {
        const val FIELD = "narrator2-gametest:oracle_field"

        /** Ticks a duelist lives on after the end, in [Rig.livesOn]. */
        const val LATER_TICKS = 140

        /** How far below the bottom of the world the void test puts Bob: where the void hurts. */
        const val VOID_DEPTH = 70

        const val FACING_EAST = -90f
        const val FACING_WEST = 90f

        /** Where Bob looks before an arena duel. */
        const val BOB_YAW = 30f

        val DIRECTOR = Caller(Role.DIRECTOR, "director-gametest", turnId = 4343, taskId = null)

        /** Runs [test] with a [Rig] started on the server thread, hooked to `ALLOW_DEATH`, and [count] players; all closed however it ends. */
        suspend fun CoroutineScope.withRig(helper: GameTestHelper, count: Int, test: suspend (Rig, List<TestPlayer>) -> Unit) {
            val server = helper.level.server
            val players = server.onServerThread { List(count) { TestPlayer(helper, "du_", keep = ::isOfInterest) } }
            val rig = Rig(helper)
            try {
                rig.onServer { rig.engine.start(server) }
                DeathHook.engines += rig.engine
                test(rig, players)
            } finally {
                DeathHook.engines -= rig.engine
                server.onServerThread {
                    rig.engine.stop()
                    players.forEach { it.close() }
                }
            }
        }

        fun isOfInterest(packet: Packet<*>): Boolean =
            packet is ClientboundSystemChatPacket || packet is ClientboundBossEventPacket || packet is ClientboundSetTitleTextPacket

        /** A duel of [a] and [b], the rest as given. */
        fun spec(a: TestPlayer, b: TestPlayer, title: String, minutes: Int = 5, arena: ArenaSpec? = null) = DuelSpec(
            players = listOf(a.name, b.name),
            title = title,
            statement = "Le Narrateur a proposé un duel d'essai à deux joueurs.",
            minutes = minutes,
            arena = arena,
        )

        suspend fun canon(rig: Rig, ref: Ref): CanonEntry = rig.runtime.memory.store.canon(ref) ?: error("no canon row $ref")

        /**
         * A flat stone field (21 × 21 blocks) in the test's structure, [bob] and [ann] standing in two corners of it
         * (Bob looking [BOB_YAW]); returns the arena's point at its center, and where each stands. Server thread.
         */
        fun field(helper: GameTestHelper, bob: TestPlayer, ann: TestPlayer): Pair<BlockPos, Pair<BlockPos, BlockPos>> {
            for (x in 0..20) for (z in 0..20) helper.setBlock(x, 0, z, Blocks.STONE)
            bob.standAt(BlockPos(2, 1, 3))
            bob.live.yRot = BOB_YAW
            ann.standAt(BlockPos(18, 1, 17))
            return helper.absolutePos(BlockPos(10, 1, 10)) to (helper.absolutePos(BlockPos(2, 1, 3)) to helper.absolutePos(BlockPos(18, 1, 17)))
        }

        /** The commands of [component]'s buttons, in order. */
        fun commands(component: Component): List<String> =
            listOfNotNull((component.style.clickEvent as? ClickEvent.RunCommand)?.command()) + component.siblings.flatMap(::commands)

        /** A private line of the Narrator, as the player reads it. */
        fun line(ui: UiTexts, text: String): String = ui["narrator.whisper_prefix"] + " " + text

        /** Whether [this] read [text] in the chat. */
        fun TestPlayer.read(text: String): Boolean = chat().any { it.content().string == text }

        /** The chat lines [this] read. */
        fun TestPlayer.lines(): List<String> = chat().map { it.content().string }

        /** The titles [this] was shown. */
        fun TestPlayer.titles(): List<String> = packets.filterIsInstance<ClientboundSetTitleTextPacket>().map { it.text().string }

        /** The bars [player]'s client shows: the boss bar packets played back in order. */
        fun bars(player: TestPlayer): List<ShownBar> {
            val shown = LinkedHashMap<UUID, ShownBar>()
            player.packets.filterIsInstance<ClientboundBossEventPacket>().forEach { packet ->
                packet.dispatch(object : ClientboundBossEventPacket.Handler {
                    override fun add(
                        id: UUID, name: Component, progress: Float, color: BossBarColor, overlay: BossBarOverlay,
                        darkenScreen: Boolean, playMusic: Boolean, createWorldFog: Boolean,
                    ) {
                        shown[id] = ShownBar(name.string, color)
                    }

                    override fun remove(id: UUID) {
                        shown.remove(id)
                    }

                    override fun updateProgress(id: UUID, progress: Float) {}

                    override fun updateName(id: UUID, name: Component) {
                        shown.computeIfPresent(id) { _, bar -> bar.copy(name = name.string) }
                    }

                    override fun updateStyle(id: UUID, color: BossBarColor, overlay: BossBarOverlay) {
                        shown.computeIfPresent(id) { _, bar -> bar.copy(color = color) }
                    }

                    override fun updateProperties(id: UUID, darkenScreen: Boolean, playMusic: Boolean, createWorldFog: Boolean) {}
                })
            }
            return shown.values.toList()
        }
    }
}
