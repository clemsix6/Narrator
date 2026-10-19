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
import io.github.clemsix6.narrator2.guard.ItemLedger
import io.github.clemsix6.narrator2.guard.NarratorMobs
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.mechanics.ArenaSpec
import io.github.clemsix6.narrator2.mechanics.Bossbars
import io.github.clemsix6.narrator2.mechanics.ChallengeControl
import io.github.clemsix6.narrator2.mechanics.ChallengeOutcome
import io.github.clemsix6.narrator2.mechanics.ChallengeStatus
import io.github.clemsix6.narrator2.mechanics.ChallengeView
import io.github.clemsix6.narrator2.mechanics.DuelSpec
import io.github.clemsix6.narrator2.mechanics.Engine
import io.github.clemsix6.narrator2.mechanics.EngineActions
import io.github.clemsix6.narrator2.mechanics.MechanicPart
import io.github.clemsix6.narrator2.mechanics.MechanicRejected
import io.github.clemsix6.narrator2.mechanics.MechanicsState
import io.github.clemsix6.narrator2.mechanics.RewardKind
import io.github.clemsix6.narrator2.mechanics.RewardSpec
import io.github.clemsix6.narrator2.mechanics.SavedPosition
import io.github.clemsix6.narrator2.mechanics.StateStore
import io.github.clemsix6.narrator2.mechanics.challenge.Bets
import io.github.clemsix6.narrator2.mechanics.challenge.Challenges
import io.github.clemsix6.narrator2.mechanics.challenge.DuelReturns
import io.github.clemsix6.narrator2.mechanics.challenge.Duels
import io.github.clemsix6.narrator2.mechanics.challenge.Stakes
import io.github.clemsix6.narrator2.memory.Added
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.CanonDraft
import io.github.clemsix6.narrator2.memory.CanonEntry
import io.github.clemsix6.narrator2.memory.CanonFilter
import io.github.clemsix6.narrator2.memory.CanonKind
import io.github.clemsix6.narrator2.memory.CanonMechanic
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.memory.InteractionKind
import io.github.clemsix6.narrator2.memory.Memory
import io.github.clemsix6.narrator2.memory.MemoryStore
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.testing.FakeChallenges
import io.github.clemsix6.narrator2.testing.FakeDirector
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolCatalog
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import io.github.clemsix6.narrator2.trace.TraceSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.commands.CommandSourceStack
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
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
import net.minecraft.world.InteractionHand
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * In-game tests of the duels (M4-19, M4 §5.5, §21.2). Nobody is ever made to fight: a duel starts only once both
 * players accepted it by `/duel accept` (the invitation's buttons), and an invitation declined, run out or accepted by
 * one player only never starts. Nobody dies in a duel, whatever would kill them — one test per way to die: the
 * opponent's fatal blow (stopped at 1 health, put out, the fight's harmful effects lifted), lava, the void, fire, a
 * fall, a creature (each a rescue, out of the danger), and what is left once the duel is over (a fire, a wither, a
 * last blow). Each time the duelist is alive ticks later, with what they carried, and nothing is counted against the
 * Narrator's quota. Four tests let the server run 20 seconds after the blow, so that what the bench alone never
 * inflicts does hurt (M4 review, finding 3): a sword's fire, lava, the void. A death decided from outside the duel
 * (another player, the Narrator, its creature) cancels it and gives the bets back. An arena takes the duelists face to
 * face and puts them back afterwards, even across a restart, never over the void nor far from where they stood, and
 * not after a day. The bets open only once the duel starts, and a won duel pays them. Then the details: a proposal cut
 * off once written, a duelist who cannot get hurt, a player who turned a duel down, a duel that cannot start, an
 * invitation ended won, a duelist not back after a restart.
 *
 * Each test builds its own engine (the challenges', duels' and bets' parts, a ledger, the bars) on a store in memory, driven
 * by a clock of its own, with a fake Director; the canon, the interactions and the guards are the running
 * Narrator's. `/duel` runs on a dispatcher of the test's own, wired to its engine. The server's `ALLOW_DEATH` reaches
 * the tests' engines through [DeathHook], in the engine's own phase (before the guards'), as `MechanicsHooks` does
 * for the server's engine. The players join able to get hurt (survival mode, their client loaded).
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
                val text = ui.format("duel.invite", "opponent" to opponent.name, "title" to "Au soleil", "seconds" to 120, "where" to ui["duel.where.here"])
                val invites = player.chat().map { it.content() }.filter { text in it.string }
                check(invites.size == 1) { "${player.name}'s invitation, once: ${player.lines()}" }
                check(commands(invites.single()) == listOf("/duel accept ${view.ref}", "/duel decline ${view.ref}")) { "its buttons: ${commands(invites.single())}" }
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
                check(!rig.duels.isDueling(bob.uuid)) { "an invitation is no fight" }
                rig.command(ann, "duel accept ${view.ref}")
                check(rig.running(view.ref)) { "both accepted: it runs" }
                check(rig.duels.isDueling(bob.uuid) && rig.duels.isDueling(ann.uuid) && !rig.duels.isDueling(cid.uuid)) { "Bob and Ann fight a duel, Cid does not" }
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
                check(!rig.duels.isDueling(bob.uuid) && !rig.duels.isDueling(ann.uuid)) { "the duel is over" }
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

    /** A reward its tool refuses is refused with the proposal (the stakes' dry checks), before anything is written. */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun aRewardItsToolRefusesIsRefusedBeforeAnythingIsWritten(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 2) { rig, (bob, ann) ->
            val forbidden = spec(bob, ann, "Interdit").copy(reward = RewardSpec(RewardKind.ITEM, item = "command_block"))
            val refused = runCatching { rig.propose(forbidden) }.exceptionOrNull()
            check(refused is MechanicRejected && refused.rule == "forbidden_item" && refused.message!!.startsWith("reward: give_item refuses 1x command_block")) {
                "refused: $refused"
            }
            check(rig.store.snapshot.challenges.isEmpty() && rig.decisions(Duels.INVITED).isEmpty()) { "no state, no decision" }
            val rows = rig.runtime.memory.store.canon(CanonFilter(open = null, player = bob.uuid)).filter { row -> row.players.any { it.uuid == bob.uuid } }
            check(rows.isEmpty()) { "no canon row names Bob: $rows" }
            val invitation = rig.ui["duel.invite"].substringBefore("{")
            check(bob.lines().none { invitation in it } && ann.lines().none { invitation in it }) { "no invitation: ${bob.lines()}" }
        }
    }

    // ── Nobody dies in a duel: one test per way to die ──

    /**
     * The opponent's fatal blow (M4 §5.5): stopped at 1 health where Bob stands, his fire out, the wither and the
     * poison of the fight lifted, resistance V a few seconds; Ann wins and gets the duel's reward (the real `give_item`),
     * Bob is not rescued (nothing to rescue him from), and lives on with his diamonds.
     */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun theOpponentsFatalBlowIsStoppedAtOneHealth(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 2) { rig, (bob, ann) ->
            val view = rig.fight(bob, ann, "Le coup fatal", reward = RewardSpec(RewardKind.ITEM, item = "golden_apple"))
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
            check(Regex("^${view.ref} won by ${ann.name} in \\d+s: ${ann.name}'s fatal blow on ${bob.name} was stopped at 1 hp · reward: ${ann.name} got 1x golden_apple$").matches(end)) {
                "the end: $end"
            }
            check(rig.mechanic().last().priority) { "the end wakes the Director" }
            rig.checkClosed(view.ref, CanonStatus.WON, listOf(ann), listOf(bob), end)
            val gift = rig.runtime.memory.store.interactions(ann.uuid, 20).filter { it.canon == view.ref && it.kind == InteractionKind.GIFT }
            check(gift.size == 1 && gift.single().summary.startsWith("duel reward: ")) { "the reward in Ann's history: $gift" }
            rig.onServer {
                check(count(ann.live, Items.GOLDEN_APPLE) == 1) { "Ann got her reward" }
                rig.livesOn(bob, diamonds = 3)
            }
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

    // ── Nobody dies once the server runs (M4 review, finding 3) ──

    /**
     * A sword with Fire Aspect II: Ann's real attack is Bob's fatal blow, stopped at 1 health, and the sword sets him
     * alight right after, for 8 seconds. Hungry (no healing), he lives through the 20 seconds the server then runs,
     * the duel's protection included: put out at once, never burnt to death, his diamonds with him.
     */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun aFireAspectSwordKillsNobodyOnceTheServerRuns(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 2) { rig, (bob, ann) ->
            val view = rig.fight(bob, ann, "L'épée de feu")
            rig.onServer {
                homeAt(helper, bob.live)
                bob.standAt(BlockPos(2, 1, 2))
                ann.standAt(BlockPos(3, 1, 2))
                val fireAspect = helper.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.FIRE_ASPECT)
                ann.hold(ItemStack(Items.IRON_SWORD).also { it.enchant(fireAspect, 2) })
                repeat(CHARGE_TICKS) { ann.live.doTick() } // the sword in hand, her attack charged
                val player = bob.live
                player.foodData.foodLevel = HUNGRY
                player.health = 1f
                ann.live.attack(player)
                check(!player.isDeadOrDying && player.health == 1f) { "stopped at 1 health: ${player.health}" }
                check(rig.store.get().challenges.isEmpty()) { "Ann won" }
                check(player.isOnFire) { "the sword's fire caught him after the blow" }
            }
            rig.letRun(listOf(bob, ann), RUN_TICKS)
            rig.onServer {
                check(!bob.live.isOnFire) { "put out" }
                rig.livesOn(bob, diamonds = 3)
            }
            val end = rig.awaitEnd(view.ref)
            check(Regex("^${view.ref} won by ${ann.name} in \\d+s: ${ann.name}'s fatal blow on ${bob.name} was stopped at 1 hp$").matches(end)) { "the end: $end" }
        }
    }

    /**
     * Ann's fatal blow while Bob stands in lava: he is not left there at 1 health, but taken out of it at once, to his
     * respawn point; he lives through the 20 seconds the server then runs.
     */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun aLoserSparedInLavaIsTakenOutOfIt(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 2) { rig, (bob, ann) ->
            val view = rig.fight(bob, ann, "Le coup dans la lave")
            val home = rig.onServer {
                val home = homeAt(helper, bob.live)
                basin(helper, Blocks.LAVA)
                bob.standAt(BlockPos(1, 1, 1))
                ann.standAt(BlockPos(4, 1, 1))
                bob.live.foodData.foodLevel = HUNGRY
                bob.live.hurtServer(helper.level, helper.level.damageSources().playerAttack(ann.live), 1_000f)
                check(!bob.live.isDeadOrDying && rig.store.get().challenges.isEmpty()) { "Ann won, Bob lives" }
                home
            }
            try {
                rig.letRun(listOf(bob, ann), RUN_TICKS)
                rig.onServer {
                    check(isAt(bob.live, home) && !bob.live.isOnFire) { "out of the lava, at his respawn point, put out: ${bob.live.position()}" }
                    rig.livesOn(bob, diamonds = 3)
                }
            } finally {
                rig.onServer { basin(helper, Blocks.STONE) }
            }
            rig.checkWonByRescue(view.ref, winner = ann, loser = bob, cause = "player_attack by ${ann.name}")
        }
    }

    /**
     * Ann's fatal blow leaves Bob at 1 health on safe ground, and its knockback throws him into lava. The duel's
     * protection takes him out of it before it ends: he lives through the 20 seconds the server then runs.
     */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun aLoserKnockedIntoLavaAfterTheBlowIsTakenOutOfIt(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 2) { rig, (bob, ann) ->
            val view = rig.fight(bob, ann, "Le recul")
            val home = rig.onServer {
                val home = homeAt(helper, bob.live)
                basin(helper, Blocks.LAVA)
                bob.standAt(BlockPos(4, 1, 1))
                ann.standAt(BlockPos(5, 1, 1))
                val player = bob.live
                player.foodData.foodLevel = HUNGRY
                player.hurtServer(helper.level, helper.level.damageSources().playerAttack(ann.live), 1_000f)
                check(!player.isDeadOrDying && player.health == 1f && rig.store.get().challenges.isEmpty()) { "spared where he stands: ${player.health}" }
                bob.standAt(BlockPos(1, 1, 1)) // the knockback
                home
            }
            try {
                rig.letRun(listOf(bob, ann), RUN_TICKS)
                rig.onServer {
                    check(isAt(bob.live, home) && !bob.live.isOnFire) { "out of the lava, at his respawn point, put out: ${bob.live.position()}" }
                    rig.livesOn(bob, diamonds = 3)
                }
            } finally {
                rig.onServer { basin(helper, Blocks.STONE) }
            }
            val end = rig.awaitEnd(view.ref)
            check(Regex("^${view.ref} won by ${ann.name} in \\d+s: ${ann.name}'s fatal blow on ${bob.name} was stopped at 1 hp$").matches(end)) { "the end: $end" }
        }
    }

    /**
     * Ann's fatal blow while Bob falls through the void below the world, slowly (as under slow falling): he is not left
     * to fall at 1 health, but taken out of the void at once, to his respawn point; he lives through the 20 seconds the
     * server then runs, during which a client still in the void would fall on (half a block a tick).
     */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun aLoserSparedAboveTheVoidIsTakenOutOfIt(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 2) { rig, (bob, ann) ->
            val view = rig.fight(bob, ann, "Au bord du vide")
            val home = rig.onServer {
                val home = homeAt(helper, bob.live)
                ann.standAt(BlockPos(3, 1, 2))
                bob.standAt(BlockPos(2, 1, 2))
                val player = bob.live
                player.foodData.foodLevel = HUNGRY
                player.teleportTo(player.x, helper.level.minY - 2.0, player.z)
                player.hurtServer(helper.level, helper.level.damageSources().playerAttack(ann.live), 1_000f)
                check(!player.isDeadOrDying && rig.store.get().challenges.isEmpty()) { "Ann won, Bob lives" }
                home
            }
            rig.letRun(listOf(bob, ann), RUN_TICKS) {
                val player = bob.live
                if (player.y < helper.level.minY) player.teleportTo(player.x, player.y - SLOW_FALL, player.z)
            }
            rig.onServer {
                check(isAt(bob.live, home)) { "out of the void, at his respawn point: ${bob.live.position()}" }
                rig.livesOn(bob, diamonds = 3)
            }
            rig.checkWonByRescue(view.ref, winner = ann, loser = bob, cause = "player_attack by ${ann.name}")
        }
    }

    // ── A duel decided from outside it is cancelled (M4 review, finding 19) ──

    /**
     * Cid bet on Ann, then deals Bob the fatal blow himself, to win his bet: Bob is rescued, and the duel is cancelled,
     * not won. Nobody gets a reward; every stake goes back, Cid's and Dan's (who bet on Bob).
     */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun aThirdPlayersFatalBlowCancelsTheDuelAndGivesTheBetsBack(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 4) { rig, (bob, ann, cid, dan) ->
            val view = rig.propose(spec(bob, ann, "Le parieur").copy(bets = true, reward = RewardSpec(RewardKind.ITEM, item = "golden_apple")))
            rig.onServer {
                bob.live.inventory.add(ItemStack(Items.DIAMOND, 3))
                rig.command(bob, "duel accept ${view.ref}")
                rig.command(ann, "duel accept ${view.ref}")
                cid.hold(ItemStack(Items.IRON_INGOT, 12))
                rig.bets.bet(cid.live, view.ref, ann.name)
                dan.hold(ItemStack(Items.GOLD_INGOT, 5))
                rig.bets.bet(dan.live, view.ref, bob.name)
                check(rig.duel(view.ref)!!.bets.size == 2) { "two bets" }
                val home = homeAt(helper, bob.live)
                bob.live.hurtServer(helper.level, helper.level.damageSources().playerAttack(cid.live), 1_000f)
                rig.checkRescued(bob, home)
                rig.tick()
                check(count(cid.live, Items.IRON_INGOT) == 12 && count(dan.live, Items.GOLD_INGOT) == 5) { "every stake given back" }
                check(rig.ledger.held(cid.live).isEmpty() && rig.ledger.held(dan.live).isEmpty()) { "nothing left in escrow" }
                for (player in listOf(bob, ann)) check(player.read(line(rig.ui, rig.ui["duel.cancelled"]))) { "${player.name} is told: ${player.lines()}" }
            }
            val end = rig.awaitEnd(view.ref)
            val how = "${view.ref} cancelled: ${cid.name} interfered; ${bob.name} would have died (player_attack by ${cid.name}) and was rescued instead"
            check(end.startsWith(how) && end.contains("bets: every stake given back (cancelled)") && "reward" !in end) { "the end: $end" }
            rig.checkClosed(view.ref, CanonStatus.CANCELLED, emptyList(), emptyList(), end, interactions = false)
            rig.onServer {
                check(count(ann.live, Items.GOLDEN_APPLE) == 0) { "no reward for Ann" }
                rig.livesOn(bob, diamonds = 3)
            }
        }
    }

    /** One of the Narrator's creatures deals Bob the fatal blow: he is rescued, and the duel is cancelled. */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun aNarratorsCreaturesFatalBlowCancelsTheDuel(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 2) { rig, (bob, ann) ->
            val view = rig.fight(bob, ann, "La créature du Narrateur")
            rig.onServer {
                val home = homeAt(helper, bob.live)
                bob.standAt(BlockPos(1, 1, 1))
                val zombie = helper.spawnWithNoFreeWill(EntityTypes.ZOMBIE, BlockPos(2, 1, 1))
                try {
                    zombie.addTag(NarratorMobs.TAG)
                    bob.live.hurtServer(helper.level, helper.level.damageSources().mobAttack(zombie), 1_000f)
                    rig.checkRescued(bob, home)
                } finally {
                    zombie.discard()
                }
            }
            val end = rig.awaitEnd(view.ref)
            check(end == "${view.ref} cancelled: the Narrator's zombie interfered; ${bob.name} would have died (mob_attack by zombie) and was rescued instead") { "the end: $end" }
            rig.checkClosed(view.ref, CanonStatus.CANCELLED, emptyList(), emptyList(), end, interactions = false)
            rig.onServer { rig.livesOn(bob, diamonds = 3) }
        }
    }

    /**
     * The Narrator's act, from before the duel, is what kills Bob (its death guard is armed for him): he is rescued, and
     * the duel is cancelled. Nothing counts against the Narrator's quota.
     */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun aDeathTheNarratorCausedCancelsTheDuel(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 2) { rig, (bob, ann) ->
            val view = rig.fight(bob, ann, "Le doigt du Narrateur")
            rig.onServer {
                val home = homeAt(helper, bob.live)
                val player = bob.live
                rig.runtime.guards.armDeathGuard(bob.uuid, ARMED_SECONDS)
                player.igniteForSeconds(10f)
                player.health = 1f
                repeat(60) { if (rig.running(view.ref)) player.doTick() }
                rig.checkRescued(bob, home)
            }
            val end = rig.awaitEnd(view.ref)
            check(end == "${view.ref} cancelled: the Narrator interfered; ${bob.name} would have died (on_fire) and was rescued instead") { "the end: $end" }
            rig.checkClosed(view.ref, CanonStatus.CANCELLED, emptyList(), emptyList(), end, interactions = false)
            rig.onServer { rig.livesOn(bob, diamonds = 3) }
        }
    }

    // ── Back from the arena (M4 review, finding 21) ──

    /**
     * Four returns from an arena, each to where its duelist stood before the duel: Ann's, two blocks above the ground,
     * lands her on it. Bob stood on a bridge since broken, nineteen blocks up; Cid where lava now is, its only ground
     * nineteen blocks under; Dan below the world. None of the three is set down there, over the void or far under it:
     * each goes to their respawn point.
     */
    @GameTest(structure = FIELD, skyAccess = true, maxTicks = ASYNC_MAX_TICKS)
    fun aReturnLandsNearWhereTheDuelistStoodOrAtTheirRespawnPoint(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 4) { rig, (ann, bob, cid, dan) ->
            rig.onServer {
                for (x in 0..20) for (z in 0..20) helper.setBlock(x, 0, z, Blocks.STONE)
                val homes = listOf(ann, bob, cid, dan).associateWith { homeAt(helper, it.live, BlockPos(3, 1, 3)) }
                for (player in homes.keys) player.standAt(BlockPos(3, 1, 3))
                // Where Cid stood: lava now, in a stone cup nineteen blocks above the ground.
                helper.setBlock(15, 19, 15, Blocks.STONE)
                for ((dx, dz) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) helper.setBlock(15 + dx, 20, 15 + dz, Blocks.STONE)
                helper.setBlock(15, 20, 15, Blocks.LAVA)
                try {
                    val now = rig.clock.millis()
                    fun saved(player: TestPlayer, x: Int, y: Double, z: Int): SavedPosition {
                        val at = helper.absolutePos(BlockPos(x, 0, z))
                        return SavedPosition(player.uuid.toString(), helper.level.dimension().identifier().toString(), at.x + 0.5, y, at.z + 0.5, 0f, 0f, savedAt = now)
                    }
                    val ground = helper.absolutePos(BlockPos(0, 1, 0)).y.toDouble()
                    rig.store.update {
                        it.copy(
                            returns = listOf(
                                saved(ann, 10, ground + 2, 10),
                                saved(bob, 5, ground + 19, 15),
                                saved(cid, 15, ground + 19, 15),
                                saved(dan, 10, helper.level.minY - 20.0, 5),
                            ),
                        )
                    }
                    rig.second()
                    check(isAt(ann.live, helper.absolutePos(BlockPos(10, 1, 10)))) { "Ann on the ground, two blocks under where she stood: ${ann.live.position()}" }
                    for (player in listOf(bob, cid, dan)) check(isAt(player.live, homes.getValue(player))) { "${player.name} at their respawn point: ${player.live.position()}" }
                    check(rig.store.get().returns.isEmpty()) { "nobody left to put back" }
                } finally {
                    helper.setBlock(15, 20, 15, Blocks.AIR)
                }
            }
        }
    }

    /** A return saved more than a day ago is dropped, with its decision: Bob is left where he is. */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun aReturnOlderThanADayIsDropped(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 1) { rig, (bob) ->
            rig.onServer {
                homeAt(helper, bob.live)
                bob.standAt(BlockPos(2, 1, 2))
                val where = bob.live.position()
                val then = rig.clock.millis() - Duration.ofHours(25).toMillis()
                val dimension = helper.level.dimension().identifier().toString()
                rig.store.update { it.copy(returns = listOf(SavedPosition(bob.uuid.toString(), dimension, where.x + 3, where.y, where.z, 0f, 0f, savedAt = then))) }
                rig.second()
                check(bob.live.position() == where) { "left where he is: ${bob.live.position()}" }
                check(rig.store.get().returns.isEmpty()) { "the return is dropped" }
            }
            val dropped = rig.decisions(DuelReturns.RETURN_DROPPED)
            check(dropped.size == 1 && dropped.single().subject == bob.name) { "one decision: $dropped" }
            check(dropped.single().reason!!.endsWith(": that was 1d1h ago, more than 1d0h; left where they are")) { "why: ${dropped.single().reason}" }
        }
    }

    // ── The duel's details (M4 review, findings 25, 26, 34) ──

    /**
     * A proposal cut off right after its canon row is written (the tool's time out, the server stopping): the duel
     * goes in all the same, both invited, and its row is never left open without it.
     */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun aProposalCutOffOnceItsCanonIsWrittenStillInvitesBoth(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 2) { rig, (bob, ann) ->
            rig.cutAfterCanon = true
            val proposing = coroutineScope { launch { rig.propose(spec(bob, ann, "Coupé net")) } }
            check(proposing.isCancelled) { "the proposal was cut off" }
            val rows = rig.runtime.memory.store.canon(CanonFilter(open = true, player = bob.uuid))
                .filter { row -> row.mechanic == CanonMechanic.DUEL && row.players.any { it.uuid == bob.uuid } }
            check(rows.size == 1) { "one open row: $rows" }
            rig.onServer {
                check(rig.duel(rows.single().ref)?.status == ChallengeStatus.INVITED) { "its duel is in the state: ${rig.store.get()}" }
                val ui = rig.ui
                for ((player, opponent) in listOf(bob to ann, ann to bob)) {
                    val text = ui.format("duel.invite", "opponent" to opponent.name, "title" to "Coupé net", "seconds" to 120, "where" to ui["duel.where.here"])
                    check(player.lines().any { text in it }) { "${player.name} is invited: ${player.lines()}" }
                }
            }
        }
    }

    /**
     * A duelist must be able to get hurt: a proposal with Bob in creative mode is refused; a duel whose duelist turns
     * spectator before both accepted never starts; a duel whose duelist an operator puts in creative mode is
     * cancelled within the second.
     */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun aDuelistMustBeAbleToGetHurt(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 2) { rig, (bob, ann) ->
            rig.onServer { bob.live.setGameMode(GameType.CREATIVE) }
            val refused = runCatching { rig.propose(spec(bob, ann, "L'invulnérable")) }.exceptionOrNull()
            check(refused is MechanicRejected && refused.rule == null && refused.message!!.startsWith("${bob.name} is in creative mode")) { "refused: $refused" }
            check(rig.store.snapshot.challenges.isEmpty()) { "nothing proposed" }

            rig.onServer { bob.makeVulnerable() }
            val spectator = rig.propose(spec(bob, ann, "Le spectateur"))
            rig.onServer {
                rig.command(bob, "duel accept ${spectator.ref}")
                ann.live.setGameMode(GameType.SPECTATOR)
                rig.command(ann, "duel accept ${spectator.ref}")
                check(rig.store.get().challenges.isEmpty()) { "it never started" }
                check(bob.read(line(rig.ui, rig.ui["duel.cancelled"]))) { "Bob is told: ${bob.lines()}" }
                ann.makeVulnerable()
            }
            check(rig.awaitEnd(spectator.ref) == "${spectator.ref} cancelled: ${ann.name} is in spectator mode") { "the end: ${rig.awaitEnd(spectator.ref)}" }

            val view = rig.fight(bob, ann, "Le créatif")
            rig.onServer {
                ann.live.setGameMode(GameType.CREATIVE)
                rig.second()
                check(rig.store.get().challenges.isEmpty()) { "cancelled within the second" }
            }
            check(rig.awaitEnd(view.ref) == "${view.ref} cancelled: ${ann.name} is in creative mode") { "the end: ${rig.awaitEnd(view.ref)}" }
        }
    }

    /**
     * Nobody is pressed to fight: once Bob declined a duel, or Ann let an invitation run out, no duel is proposed to
     * them for ten minutes (`duel_declined`); then it is again.
     */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun aPlayerWhoTurnedADuelDownIsNotAskedAgainForTenMinutes(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 3) { rig, (bob, ann, cid) ->
            val declined = rig.propose(spec(bob, cid, "Non merci"))
            rig.onServer { rig.command(bob, "duel decline ${declined.ref}") }
            val refused = runCatching { rig.propose(spec(bob, cid, "Et maintenant ?")) }.exceptionOrNull()
            check(refused is MechanicRejected && refused.rule == Duels.DUEL_DECLINED && refused.message!!.startsWith("${bob.name} turned a duel down 0s ago")) {
                "Bob rests: $refused"
            }

            val ignored = rig.propose(spec(ann, cid, "Silence"))
            rig.onServer {
                rig.command(cid, "duel accept ${ignored.ref}")
                rig.at(ignored.endsAt)
                check(rig.store.get().challenges.isEmpty()) { "run out" }
            }
            val again = runCatching { rig.propose(spec(ann, cid, "Toujours pas ?")) }.exceptionOrNull()
            check(again is MechanicRejected && again.rule == Duels.DUEL_DECLINED && again.message!!.startsWith("${ann.name} turned a duel down")) { "Ann rests: $again" }

            rig.clock.advance(Duration.ofMinutes(10))
            check(rig.propose(spec(bob, ann, "Dix minutes plus tard")).status == ChallengeStatus.INVITED) { "asked again ten minutes later" }
        }
    }

    /**
     * Both accepted, but its arena, far away, does not load: the duel is cancelled half a minute after its invitation's
     * end, rather than kept an invitation for ever.
     */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun aDuelThatCannotStartIsCancelled(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 2) { rig, (bob, ann) ->
            val view = rig.propose(spec(bob, ann, "Nulle part"))
            rig.onServer {
                val far = ArenaSpec(bob.live.blockX + FAR, bob.live.blockY, bob.live.blockZ, helper.level.dimension().identifier().toString())
                rig.store.update { state -> state.copy(challenges = state.challenges.map { it.copy(duel = it.duel!!.copy(arena = far)) }) }
                rig.command(bob, "duel accept ${view.ref}")
                rig.command(ann, "duel accept ${view.ref}")
                check(rig.duel(view.ref)?.status == ChallengeStatus.INVITED) { "waiting for its arena" }
                rig.at(view.endsAt + Duration.ofSeconds(30).toMillis())
                check(rig.store.get().challenges.isEmpty()) { "cancelled" }
                for (player in listOf(bob, ann)) check(player.read(line(rig.ui, rig.ui["duel.cancelled"]))) { "${player.name} is told: ${player.lines()}" }
            }
            val end = rig.awaitEnd(view.ref)
            check(end == "${view.ref} cancelled: both accepted, but it could not start within 30s of the invitation's end") { "the end: $end" }
            rig.checkClosed(view.ref, CanonStatus.CANCELLED, emptyList(), emptyList(), end, interactions = false)
        }
    }

    /** A duel nobody fought, still an invitation, cannot end won or lost: only cancelled. */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun anInvitationCanOnlyBeCancelled(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 2) { rig, (bob, ann) ->
            val view = rig.propose(spec(bob, ann, "Jamais commencé"))
            val operator = Author(Source.OPERATOR, operator = "gametest")
            for ((outcome, winners) in listOf(ChallengeOutcome.WON to listOf(ann.uuid), ChallengeOutcome.LOST to emptyList())) {
                val refused = runCatching { rig.duels.end(view.ref, outcome, winners, "Terminé.", operator) }.exceptionOrNull()
                check(refused is MechanicRejected && refused.message!!.startsWith("${view.ref} is only an invitation")) { "${outcome.id} refused: $refused" }
            }
            check(rig.store.snapshot.challenge(view.ref.id)?.status == ChallengeStatus.INVITED) { "still an invitation" }
            rig.duels.end(view.ref, ChallengeOutcome.CANCELLED, emptyList(), "Annulé par un opérateur.", operator)
            awaitCondition("the end of ${view.ref}") { canon(rig, view.ref).status == CanonStatus.CANCELLED }
            val results = rig.runtime.memory.store.interactions(ann.uuid, 10).filter { it.canon == view.ref }
            check(results.isEmpty()) { "nothing in Ann's history: $results" }
        }
    }

    /**
     * A restart in the middle of a duel, and Bob never comes back: a minute after the engine started again he has left
     * the server, and Ann wins; half a minute after, it still ran.
     */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun aDuelistNotBackAfterARestartHasLeft(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 2) { rig, (bob, ann) ->
            val view = rig.fight(bob, ann, "Le déserteur")
            val saved = rig.onServer {
                rig.engine.stop()
                bob.close() // gone while the server was down
                MechanicsState.decode(MechanicsState.encode(rig.store.get()))
            }
            val again = Rig(helper, rig.director, rig.sink, StateStore.inMemory(saved), rig.clock)
            try {
                rig.onServer {
                    again.engine.start(helper.level.server)
                    rig.clock.advance(Duration.ofSeconds(30))
                    again.tick()
                    check(again.running(view.ref)) { "still running half a minute after the start" }
                    rig.clock.advance(Duration.ofSeconds(31))
                    again.tick()
                    check(again.store.get().challenges.isEmpty()) { "Bob has left" }
                    check(ann.read(line(again.ui, again.ui["duel.won"]))) { "Ann is told: ${ann.lines()}" }
                }
                val end = again.awaitEnd(view.ref)
                check(end == "${view.ref} won by ${ann.name} in 1m01s: ${bob.name} left the server") { "the end: $end" }
            } finally {
                rig.onServer { again.engine.stop() }
            }
        }
    }

    // ── Bets ──

    /**
     * A duel with bets (M4 §5.6): no bet while it is only an invitation (`bet.closed`, nothing taken); once both
     * accepted, the bets open. Ann's fatal blow wins it: Cid, who bet on her, gets his stake back with Dan's, who bet
     * on Bob; nothing goes to the Narrator.
     */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun aBetOnAWonDuelIsPaidWithTheLosingStake(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 4) { rig, (bob, ann, cid, dan) ->
            val view = rig.propose(spec(bob, ann, "Les paris sont ouverts").copy(bets = true))
            rig.onServer {
                cid.hold(ItemStack(Items.IRON_INGOT, 12))
                check(rig.bets.bet(cid.live, view.ref, ann.name).string == rig.ui["bet.closed"]) { "no bet on an invitation" }
                check(count(cid.live, Items.IRON_INGOT) == 12) { "a refusal takes nothing" }
                rig.command(bob, "duel accept ${view.ref}")
                rig.command(ann, "duel accept ${view.ref}")
                val running = rig.duel(view.ref)!!
                check(running.status == ChallengeStatus.RUNNING && running.betsCloseAt == running.startedAt!! + 100_000) {
                    "the bets open when it starts, for a third of its 5 minutes: $running"
                }
                rig.bets.bet(cid.live, view.ref, ann.name)
                dan.hold(ItemStack(Items.GOLD_INGOT, 5))
                rig.bets.bet(dan.live, view.ref, bob.name)
                check(rig.duel(view.ref)!!.bets.map { it.name to it.what } == listOf(cid.name to "12x iron_ingot", dan.name to "5x gold_ingot")) { "two bets" }

                bob.live.hurtServer(helper.level, helper.level.damageSources().playerAttack(ann.live), 1_000f)
                check(!bob.live.isDeadOrDying && rig.store.get().challenges.isEmpty()) { "Ann won, Bob lives" }
                rig.tick()
                check(rig.store.get().settlements.isEmpty()) { "settled at the next tick" }
                check(count(cid.live, Items.IRON_INGOT) == 12 && count(cid.live, Items.GOLD_INGOT) == 5) { "Cid gets his stake back with Dan's" }
                check(count(dan.live, Items.GOLD_INGOT) == 0) { "Dan's stake went to Cid" }
                check(rig.ledger.held(cid.live).isEmpty() && rig.ledger.held(dan.live).isEmpty()) { "nothing left in escrow" }
            }
            val end = rig.awaitEnd(view.ref)
            val bets = " · bets: ${cid.name} bet 12x iron_ingot on ${ann.name}, got it back with ${dan.name}'s 5x gold_ingot; " +
                "${dan.name} bet 5x gold_ingot on ${bob.name}, lost it to ${cid.name}"
            check(end.startsWith("${view.ref} won by ${ann.name} in ") && end.endsWith(bets)) { "the end: $end" }
            rig.checkClosed(view.ref, CanonStatus.WON, listOf(ann), listOf(bob), end)
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

        /** Set by a test: the next canon row written cancels, right after, the coroutine that wrote it (a tool's time out). */
        @Volatile
        var cutAfterCanon = false

        /** The running Narrator's memory, but for [cutAfterCanon]. */
        private val memory: Memory = object : Memory by runtime.memory {
            override val store: MemoryStore = object : MemoryStore by runtime.memory.store {
                override suspend fun addCanon(draft: CanonDraft, author: Author): Added<CanonEntry> {
                    val added = runtime.memory.store.addCanon(draft, author)
                    if (cutAfterCanon) {
                        cutAfterCanon = false
                        currentCoroutineContext().cancel()
                    }
                    return added
                }
            }
        }

        val services: NarratorServices = object : NarratorServices by runtime {
            override val trace: TraceSink get() = sink
            override val director: DirectorControl get() = this@Rig.director
            override val memory: Memory get() = this@Rig.memory
        }
        val ui: UiTexts get() = runtime.settings.get().ui
        val bars = Bossbars()

        /** A ledger of the rig's own: it reads the same player attachments as the server's guards. */
        val ledger = ItemLedger(GuardStates(), Decisions(sink, clock), clock, tell = { _, _ -> }) { runtime.settings.get().ui }
        val bets = Bets(services, store, ledger, clock)

        /** The stakes, paid by the real tools: the same for the challenges and the duels, as the assembly does. */
        val stakes = Stakes(services, EngineActions(services, ToolCatalog.allTools(services)))
        val challenges = Challenges(services, store, bars, stakes, bets, clock)
        val duels = Duels(services, store, challenges, stakes, clock)
        val engine = Engine(services, store, listOf(challenges, duels, bets, DrawnBars(bars, store, runtime.settings.get().ui)), clock)

        /** What `/duel` answers to: this engine's duels. */
        private val control: ChallengeControl = object : ChallengeControl by FakeChallenges(clock, AtomicLong(1)) {
            override fun answerDuel(player: ServerPlayer, ref: Ref, accept: Boolean): Component = duels.answer(player, ref, accept)

            override fun list(): List<ChallengeView> = challenges.list()
        }
        private val dispatcher = CommandDispatcher<CommandSourceStack>().also { DuelCommand.register(it) { control } }

        suspend fun propose(spec: DuelSpec): ChallengeView = duels.propose(spec, DIRECTOR)

        /** A duel of [bob] and [ann], both accepted, Bob with 3 diamonds: running. */
        suspend fun fight(bob: TestPlayer, ann: TestPlayer, title: String, minutes: Int = 5, reward: RewardSpec? = null): ChallengeView {
            val view = propose(spec(bob, ann, title, minutes = minutes).copy(reward = reward))
            onServer {
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

        /**
         * Lets the server run [ticks] ticks of its own ([ServerTicks]): in each, the engine's clock moves on by a tick,
         * [eachTick] runs (what a client would do, say), [players] tick as their client's connection makes them tick,
         * then the engine. Off the server thread.
         */
        suspend fun letRun(players: List<TestPlayer>, ticks: Int, eachTick: () -> Unit = {}) = ServerTicks.run(this, players, ticks, eachTick)

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
     * `MechanicsHooks` for the server's engine (which knows nothing of the tests' duels).
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

    /**
     * The server's own ticks, for the tests that let it run (M4 review, finding 3): the bench's players are not ticked
     * by a client's connection, and a loop of `doTick` within one server tick never lets their damage cooldown run
     * down (the level does that), so a fire hurts them once. Here each server tick, after the level ticked them, moves
     * the run's clock on by [TICK_MS], runs its own step, ticks its players (`doTick`, as their connection would:
     * fire, lava, the void) and then its engine. Registered once, at its first use; a failure ends the run, never the
     * server's tick.
     */
    private object ServerTicks {
        private val runs = CopyOnWriteArrayList<Run>()

        private class Run(val rig: Rig, val players: List<TestPlayer>, val eachTick: () -> Unit) {
            val ticks = AtomicInteger()

            @Volatile
            var failure: Throwable? = null

            fun tick() {
                if (failure != null) return
                try {
                    rig.clock.advance(Duration.ofMillis(TICK_MS))
                    eachTick()
                    players.map { it.live }.filterNot { it.isRemoved }.forEach { it.doTick() }
                    rig.tick()
                    ticks.incrementAndGet()
                } catch (e: Throwable) {
                    failure = e
                }
            }
        }

        init {
            ServerTickEvents.END_SERVER_TICK.register { _ -> runs.forEach { it.tick() } }
        }

        suspend fun run(rig: Rig, players: List<TestPlayer>, ticks: Int, eachTick: () -> Unit) {
            val run = Run(rig, players, eachTick)
            runs += run
            try {
                awaitCondition("$ticks ticks of the server", timeoutMs = 60_000) { run.failure != null || run.ticks.get() >= ticks }
            } finally {
                runs -= run
            }
            run.failure?.let { throw IllegalStateException("a tick of the server failed after ${run.ticks.get()} ticks", it) }
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

        /**
         * A server tick on the engine's clock, in the tests that let the server run: a busy server's, a little slower
         * than the 50 ms of an idle one. What lasts a number of ticks (an effect, a fire) then lasts longer on the
         * clock than its seconds, as it does on a real server under load.
         */
        const val TICK_MS = 60L

        /** Ticks the server runs after a duel, in those tests: 24 seconds, longer than any fire and than the duel's protection. */
        const val RUN_TICKS = 400

        /** The food level of a hungry player: too low to heal by itself. */
        const val HUNGRY = 6

        /** Ticks of her own that Ann needs to hold a sword and charge her attack. */
        const val CHARGE_TICKS = 20

        /** How far a slow fall takes a client each tick, in blocks. */
        const val SLOW_FALL = 0.5

        /** How long the Narrator's death guard stays armed for Bob, in seconds of his play time. */
        const val ARMED_SECONDS = 60

        /** How far east an arena that does not load is, in blocks: no test loads it. */
        const val FAR = 100_000

        const val FACING_EAST = -90f
        const val FACING_WEST = 90f

        /** Where Bob looks before an arena duel. */
        const val BOB_YAW = 30f

        val DIRECTOR = Caller(Role.DIRECTOR, "director-gametest", turnId = 4343, taskId = null)

        /**
         * Runs [test] with a [Rig] started on the server thread, hooked to `ALLOW_DEATH`, and [count] players able to get
         * hurt (a duelist must be); all closed however it ends.
         */
        suspend fun CoroutineScope.withRig(helper: GameTestHelper, count: Int, test: suspend (Rig, List<TestPlayer>) -> Unit) {
            val server = helper.level.server
            val players = server.onServerThread { List(count) { TestPlayer(helper, "du_", keep = ::isOfInterest).also { it.makeVulnerable() } } }
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

        /** [this] holds [stack] in the main hand. */
        fun TestPlayer.hold(stack: ItemStack) = live.setItemInHand(InteractionHand.MAIN_HAND, stack)

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
