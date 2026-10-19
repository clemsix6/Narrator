package io.github.clemsix6.narrator2.gametest

import com.mojang.authlib.GameProfile
import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.command.BetCommand
import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.config.UiTexts
import io.github.clemsix6.narrator2.core.onServerThread
import io.github.clemsix6.narrator2.director.DirectorControl
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.director.MechanicSignal
import io.github.clemsix6.narrator2.gametest.GuardGameTests.Companion.count
import io.github.clemsix6.narrator2.gametest.GuardGameTests.Companion.reconnect
import io.github.clemsix6.narrator2.guard.Escrow
import io.github.clemsix6.narrator2.guard.GuardStates
import io.github.clemsix6.narrator2.guard.ItemLedger
import io.github.clemsix6.narrator2.guard.LedgerPurpose
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.mechanics.Bossbars
import io.github.clemsix6.narrator2.mechanics.ChallengeControl
import io.github.clemsix6.narrator2.mechanics.ChallengeOutcome
import io.github.clemsix6.narrator2.mechanics.ChallengeSpec
import io.github.clemsix6.narrator2.mechanics.ChallengeStatus
import io.github.clemsix6.narrator2.mechanics.Engine
import io.github.clemsix6.narrator2.mechanics.GoalKind
import io.github.clemsix6.narrator2.mechanics.Mechanics
import io.github.clemsix6.narrator2.mechanics.MechanicsState
import io.github.clemsix6.narrator2.mechanics.Settlement
import io.github.clemsix6.narrator2.mechanics.StateStore
import io.github.clemsix6.narrator2.mechanics.challenge.Bets
import io.github.clemsix6.narrator2.mechanics.challenge.Challenges
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.testing.FakeDirector
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import io.github.clemsix6.narrator2.trace.TraceSink
import io.netty.channel.embedded.EmbeddedChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.component.DataComponents
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.Connection
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.server.level.ClientInformation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.server.players.NameAndId
import net.minecraft.util.ProblemReporter
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.level.storage.TagValueInput
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-game tests of the bets (M4-20, M4 §5.6, §21.2): `/bet` stakes the stack in hand into the ledger, the end of a
 * challenge settles the bets by instructions saved with it, and the stakes move: given back, or handed over to the
 * winning bettors once both players are online. Nothing ever goes to the Narrator, and no item is ever lost: a won
 * bet and lost ones, a challenge cancelled or expired (every stake back), bettors away at the settlement (served at
 * their join; the winnings handed over once both are online; an instruction replayed gives nothing more), a restart
 * with stakes in play, a challenge that ended while the server was down, and the refusals: someone outside the
 * challenge, a participant already through, bets closed, an unknown challenge, an empty hand and a joker.
 *
 * Each test builds its own engine on a store in memory: the challenges' part (M4-12) with the bets' part, a ledger
 * of its own (its decisions kept; it reads the same player attachments as the server's guards, so the moves that
 * matter happen within one server-thread block), a fake Director and a clock the test moves. The canon is the real
 * memory's.
 */
class BetGameTests {

    /** A bet on the winner gets its stake back with the losing stakes; the losing bettors lose theirs to it; the line says it all. */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun aWinningBetIsPaidWithTheLosingStakesAndTheNarratorKeepsNothing(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 4) { rig, (bob, ann, cid, dan) ->
            val view = rig.challenges.start(spec(listOf(bob, ann), "Une pomme"), DIRECTOR)
            rig.onServer {
                cid.hold(ItemStack(Items.IRON_INGOT, 12))
                val placed = rig.bet(cid, view.ref, bob.name.uppercase()).string
                val expected = "${rig.ui["narrator.whisper_prefix"]} " +
                    rig.ui.format("bet.placed", "items" to "12× ${nameOf(Items.IRON_INGOT)}", "on" to bob.name, "id" to "${view.ref}")
                check(placed == expected) { "what the bettor reads: $placed" }
                check(cid.live.mainHandItem.isEmpty) { "the stake leaves the hand" }
                dan.hold(ItemStack(Items.GOLD_INGOT, 5))
                check(rig.ui["bet.on_nobody"] in rig.bet(dan, view.ref, "nobody").string) { "a bet on nobody" }
                ann.hold(ItemStack(Items.EMERALD, 3))
                rig.bet(ann, view.ref, ann.name) // a participant may bet, even on herself
                ann.hold(ItemStack(Items.EMERALD, 1))
                check(rig.bet(ann, view.ref, bob.name).string == rig.ui["bet.already"]) { "one bet per player and challenge" }
                check(count(ann.live, Items.EMERALD) == 1) { "a refusal takes nothing" }
                ann.hold(ItemStack.EMPTY)

                val bets = rig.challenge(view.ref)!!.bets
                check(bets.map { it.name to it.what } == listOf(cid.name to "12x iron_ingot", dan.name to "5x gold_ingot", ann.name to "3x emerald")) { "the bets: $bets" }
                check(bets.map { it.on } == listOf(bob.uuid.toString(), null, ann.uuid.toString())) { "on whom: $bets" }
                val stake = rig.ledger.held(cid.live).single()
                check(stake.id == bets[0].stake && stake.purpose == LedgerPurpose.STAKE && stake.canon == view.ref.id && stake.returnAt == Escrow.stakeDeadline(view.endsAt)) {
                    "in escrow, with its fallback deadline: $stake"
                }
                check(rig.decisions("bet").map { it.subject } == listOf("${view.ref}", "${view.ref}", "${view.ref}")) { "the bets traced" }

                bob.live.inventory.add(ItemStack(Items.APPLE))
                rig.second()
                check(rig.store.get().challenges.isEmpty() && rig.store.get().settlements.isEmpty()) { "won, and settled in the same second: ${rig.store.get()}" }
                check(count(cid.live, Items.IRON_INGOT) == 12 && count(cid.live, Items.GOLD_INGOT) == 5 && count(cid.live, Items.EMERALD) == 3) { "the winning bettor gets it all" }
                check(count(dan.live, Items.GOLD_INGOT) == 0 && count(ann.live, Items.EMERALD) == 0) { "the losing stakes are gone to him" }
                check(listOf(bob, ann, cid, dan).all { rig.ledger.held(it.live).isEmpty() }) { "every ledger is empty" }
            }
            val line = rig.awaitEnd(view.ref)
            val bets = " · bets: ${cid.name} bet 12x iron_ingot on ${bob.name}, got it back with ${dan.name}'s 5x gold_ingot and ${ann.name}'s 3x emerald; " +
                "${dan.name} bet 5x gold_ingot on nobody, lost it to ${cid.name}; ${ann.name} bet 3x emerald on ${ann.name}, lost it to ${cid.name}"
            check(line.startsWith("${view.ref} won by ${bob.name} ") && line.endsWith(bets)) { "the line: $line" }
            check(cid.told("bet.returned") && cid.told("bet.won")) { "the winning bettor is told: ${cid.lines()}" }
            check(dan.told("bet.lost") && ann.told("bet.lost")) { "the losers are told: ${dan.lines()} ${ann.lines()}" }
            check(rig.decisions("settled").size == 3 && rig.decisions("settled").all { it.applied }) { "each instruction applied once: ${rig.decisions("settled")}" }
        }
    }

    /** Cancelled, or a judged challenge left unjudged: every stake goes back to its owner, even the bets that would have won. */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun everyStakeGoesBackWhenAChallengeIsCancelledOrExpires(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 4) { rig, (bob, ann, cid, dan) ->
            val first = rig.challenges.start(spec(listOf(bob, ann), "Annulé"), DIRECTOR)
            rig.onServer {
                cid.hold(ItemStack(Items.IRON_INGOT, 12))
                rig.bet(cid, first.ref, bob.name)
                dan.hold(ItemStack(Items.GOLD_INGOT, 5))
                rig.bet(dan, first.ref, "nobody")
            }
            val cancelled = rig.challenges.end(first.ref, ChallengeOutcome.CANCELLED, emptyList(), "Le défi a été annulé par un opérateur.", Author(Source.OPERATOR, operator = "gametest"))
            check(cancelled.line.endsWith(" · bets: every stake given back (cancelled): ${cid.name} 12x iron_ingot on ${bob.name}; ${dan.name} 5x gold_ingot on nobody")) {
                "the line: ${cancelled.line}"
            }
            rig.onServer {
                check(rig.store.get().settlements.size == 2) { "the instructions are saved with the end: ${rig.store.get().settlements}" }
                rig.tick()
                check(rig.store.get().settlements.isEmpty()) { "applied at the next tick" }
                check(count(cid.live, Items.IRON_INGOT) == 12 && count(dan.live, Items.GOLD_INGOT) == 5) { "every stake back" }
                check(rig.ledger.held(cid.live).isEmpty() && rig.ledger.held(dan.live).isEmpty()) { "the ledgers are empty" }
                cid.live.inventory.clearContent() // the stake came back to the hand's slot: make room for the next one
                rig.at(rig.clock.millis() + 10 * MINUTE) // the pace
            }
            check(cid.told("bet.returned") && dan.told("bet.returned")) { "they are told: ${cid.lines()} ${dan.lines()}" }

            val judged = rig.challenges.start(spec(listOf(bob, ann), "La statue", GoalKind.JUDGED, minutes = 6), DIRECTOR)
            rig.onServer {
                cid.hold(ItemStack(Items.IRON_INGOT, 7))
                rig.bet(cid, judged.ref, ann.name)
                check(rig.ledger.held(cid.live).single().returnAt == Escrow.stakeDeadline(judged.endsAt + 15 * MINUTE)) { "a judged challenge's stake waits for the judging too" }
                rig.at(judged.endsAt)
                check(rig.challenge(judged.ref)!!.status == ChallengeStatus.JUDGING) { "judging" }
                dan.hold(ItemStack(Items.GOLD_INGOT, 1))
                check(rig.bet(dan, judged.ref, ann.name).string == rig.ui["bet.closed"]) { "no bet while it is judged" }
                rig.at(judged.endsAt + 15 * MINUTE)
                check(rig.store.get().challenges.isEmpty() && rig.store.get().settlements.isEmpty()) { "expired and settled in the same second" }
                check(count(cid.live, Items.IRON_INGOT) == 7) { "the stake is back" }
            }
            val line = rig.awaitEnd(judged.ref)
            check(line == "${judged.ref} expired: not judged within 15m; no stakes · bets: every stake given back (expired): ${cid.name} 7x iron_ingot on ${ann.name}") { "the line: $line" }
        }
    }

    /**
     * Two bettors away when the challenge ends: their instructions wait in the state. The loser comes back first: his
     * stake is ceded to the winner, still away, and waits in his ledger. The winner comes back: his stake comes back at
     * his join, and the loser's is handed over once both are online. An instruction replayed gives nothing more.
     */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun bettorsAwayAtTheSettlementAreServedOnceBackAndAReplayGivesNothingMore(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 4) { rig, (bob, ann, cid, dan) ->
            val view = rig.challenges.start(spec(listOf(bob, ann), "Les absents"), DIRECTOR)
            rig.onServer {
                cid.hold(ItemStack(Items.IRON_INGOT, 12))
                rig.bet(cid, view.ref, bob.name)
                dan.hold(ItemStack(Items.GOLD_INGOT, 5))
                rig.bet(dan, view.ref, ann.name)
                val (cidStake, danStake) = rig.challenge(view.ref)!!.bets.map { it.stake }
                val cidSaved = cid.live.nameAndId()
                val danSaved = dan.live.nameAndId()
                cid.close() // both leave: the game saves them, their stakes in their files
                dan.close()

                bob.live.inventory.add(ItemStack(Items.APPLE))
                rig.second()
                rig.second()
                val waiting = listOf(Settlement(cid.uuid.toString(), cidStake), Settlement(dan.uuid.toString(), danStake, cid.uuid.toString()))
                check(rig.store.get().challenges.isEmpty() && rig.store.get().settlements == waiting) { "the instructions wait for their owners: ${rig.store.get().settlements}" }

                val danBack = comeBack(helper, danSaved)
                rig.engine.onJoin(danBack) // the engine's JOIN, which the server runs before the guards'
                check(rig.store.get().settlements == waiting.take(1)) { "the loser's instruction applied at his join" }
                check(rig.ledger.held(danBack).single().let { it.id == danStake && it.owedTo == cid.uuid }) { "ceded to the winner, away: it waits in the loser's ledger" }
                check(count(danBack, Items.GOLD_INGOT) == 0) { "not his any more" }

                val cidBack = comeBack(helper, cidSaved)
                rig.engine.onJoin(cidBack)
                check(rig.store.get().settlements.isEmpty()) { "the winner's instruction applied at his join" }
                check(count(cidBack, Items.IRON_INGOT) == 12) { "his stake is back" }
                rig.ledger.giveBack(danBack, rig.clock.millis()) // the guards' second
                check(count(cidBack, Items.GOLD_INGOT) == 5 && count(danBack, Items.GOLD_INGOT) == 0) { "handed over once both are online" }
                check(rig.ledger.held(cidBack).isEmpty() && rig.ledger.held(danBack).isEmpty()) { "both ledgers are empty" }

                // A crash before the world saved the state: the same instructions, replayed.
                rig.store.update { it.copy(settlements = waiting) }
                rig.tick()
                check(rig.store.get().settlements.isEmpty()) { "replayed, then gone" }
                check(count(cidBack, Items.GOLD_INGOT) == 5 && count(cidBack, Items.IRON_INGOT) == 12 && count(danBack, Items.GOLD_INGOT) == 0) { "nothing more" }
                val settled = rig.decisions("settled")
                check(settled.map { it.applied } == listOf(true, true, false, false)) { "the replays changed nothing: ${settled.map { it.reason }}" }
            }
        }
    }

    /** A restart with bets in play: the stakes in the players' files, the bets in the world's state; settled after it. */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun theBetsAndTheirStakesSurviveARestart(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 4) { rig, (bob, ann, cid, dan) ->
            val view = rig.challenges.start(spec(listOf(bob, ann), "Avant et après", minutes = 10), DIRECTOR)
            val saved = rig.onServer {
                cid.hold(ItemStack(Items.IRON_INGOT, 12))
                rig.bet(cid, view.ref, ann.name)
                dan.hold(ItemStack(Items.GOLD_INGOT, 5))
                rig.bet(dan, view.ref, bob.name)
                rig.engine.stop()
                MechanicsState.decode(MechanicsState.encode(rig.store.get()))
            }
            val stakes = saved.challenges.single().bets.map { it.stake }
            check(saved.challenges.single().bets.map { it.name } == listOf(cid.name, dan.name)) { "the bets are saved with the world: $saved" }

            val again = Rig(helper, rig.director, rig.sink, rig.clock, StateStore.inMemory(saved))
            try {
                rig.onServer {
                    val cidBack = reconnect(helper, cid.live)
                    val danBack = reconnect(helper, dan.live)
                    check(again.ledger.held(cidBack).map { it.id } == stakes.take(1) && again.ledger.held(danBack).map { it.id } == stakes.drop(1)) {
                        "the stakes come back with the players' files"
                    }
                    rig.clock.advance(Duration.ofMinutes(1))
                    again.engine.start(helper.level.server)
                    ann.live.inventory.add(ItemStack(Items.APPLE))
                    again.second()
                    check(again.store.get().challenges.isEmpty() && again.store.get().settlements.isEmpty()) { "won after the restart, and settled" }
                    check(count(cidBack, Items.IRON_INGOT) == 12 && count(cidBack, Items.GOLD_INGOT) == 5 && count(danBack, Items.GOLD_INGOT) == 0) { "paid" }
                    check(again.ledger.held(cidBack).isEmpty() && again.ledger.held(danBack).isEmpty()) { "the ledgers are empty" }
                }
                val line = again.awaitEnd(view.ref)
                check(line.endsWith(" · bets: ${cid.name} bet 12x iron_ingot on ${ann.name}, got it back with ${dan.name}'s 5x gold_ingot; ${dan.name} bet 5x gold_ingot on ${bob.name}, lost it to ${cid.name}")) {
                    "the line: $line"
                }
            } finally {
                rig.onServer { again.engine.stop() }
            }
        }
    }

    /** A challenge whose end passed while the server was down is taken out as the engine starts: no verdict, every stake back. */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun theStakesOfAChallengeThatEndedWhileTheServerWasDownGoBack(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 3) { rig, (bob, ann, cid) ->
            val view = rig.challenges.start(spec(listOf(bob, ann), "Pendant l'arrêt"), DIRECTOR)
            val saved = rig.onServer {
                cid.hold(ItemStack(Items.IRON_INGOT, 12))
                rig.bet(cid, view.ref, bob.name)
                rig.engine.stop()
                MechanicsState.decode(MechanicsState.encode(rig.store.get()))
            }
            val stake = saved.challenges.single().bets.single().stake
            val again = Rig(helper, rig.director, rig.sink, rig.clock, StateStore.inMemory(saved))
            try {
                rig.onServer {
                    rig.clock.now = Instant.ofEpochMilli(view.endsAt + MINUTE)
                    again.engine.start(helper.level.server)
                    check(again.store.get().challenges.isEmpty() && again.store.get().settlements == listOf(Settlement(cid.uuid.toString(), stake))) {
                        "taken out at the start, its stake to give back: ${again.store.get()}"
                    }
                    again.tick()
                    check(again.store.get().settlements.isEmpty() && count(cid.live, Items.IRON_INGOT) == 12) { "back at the first tick" }
                    check(again.ledger.held(cid.live).isEmpty()) { "the ledger is empty" }
                }
                check(rig.sink.kept.any { it.kind == DecisionRecord.Kind.MECHANIC && it.decision == "cancelled" && it.subject == "${view.ref}" && "1 bet given back" in it.reason.orEmpty() }) {
                    "the start says it"
                }
            } finally {
                rig.onServer { again.engine.stop() }
                // What the reconciliation of the world's store would do; a store in memory never reconciles.
                rig.runtime.memory.store.resolveCanon(view.ref, CanonStatus.CANCELLED, "the server was down when it ended", emptyList(), Author(Source.ENGINE))
            }
        }
    }

    /** Someone outside the challenge, a participant already through, bets closed, an unknown challenge: refused, nothing taken. */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun aBetOnSomeoneOutsideTheChallengeIsRefusedAndTakesNothing(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 3) { rig, (bob, ann, eve) ->
            val view = rig.challenges.start(spec(listOf(bob, ann), "Deux pommes", winners = 0, minutes = 6), DIRECTOR)
            rig.onServer {
                eve.hold(ItemStack(Items.IRON_INGOT, 12))
                check(rig.bet(eve, view.ref, eve.name).string == rig.ui.format("bet.unknown_option", "options" to "${bob.name}, ${ann.name}, nobody")) { "not a participant" }
                check(rig.bet(eve, Ref(RefKind.CANON, 999_999_999), "nobody").string == rig.ui.format("command.unknown_challenge", "id" to "C-999999999")) { "no such challenge" }
                bob.live.inventory.add(ItemStack(Items.APPLE))
                rig.second()
                check(rig.challenge(view.ref)!!.participants.first().doneAt != null) { "Bob is through" }
                check(rig.bet(eve, view.ref, bob.name).string == rig.ui.format("bet.unknown_option", "options" to "${ann.name}, nobody")) { "a sure win is no bet" }
                // The command reads the id as typed: `50` is C-50, anything else names no challenge.
                check(BetCommand.bet(rig.commandServices, eve.live, "nonsense", "nobody").string == rig.ui.format("command.unknown_challenge", "id" to "nonsense")) { "an id that is no ref" }
                check(BetCommand.bet(rig.commandServices, eve.live, "${view.ref.id}", eve.name).string == rig.ui.format("bet.unknown_option", "options" to "${ann.name}, nobody")) {
                    "the bare number"
                }
                rig.at(rig.challenge(view.ref)!!.betsCloseAt!!)
                check(rig.bet(eve, view.ref, ann.name).string == rig.ui["bet.closed"]) { "closed after its first minutes" }
                check(eve.live.mainHandItem.count == 12 && rig.ledger.held(eve.live).isEmpty()) { "nothing taken" }
                check(rig.challenge(view.ref)!!.bets.isEmpty() && rig.decisions("bet").isEmpty()) { "no bet" }
            }
            rig.cancel(view.ref)
        }
    }

    /** An empty hand and a joker stake nothing: the joker stays in hand. */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun aJokerIsNeverStaked(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 3) { rig, (bob, ann, cid) ->
            val view = rig.challenges.start(spec(listOf(bob, ann), "Le joker"), DIRECTOR)
            rig.onServer {
                check(rig.bet(cid, view.ref, bob.name).string == rig.ui["bet.no_item"]) { "an empty hand" }
                cid.hold(joker())
                check(rig.bet(cid, view.ref, bob.name).string == rig.ui["bet.no_item"]) { "a joker" }
                check(ItemLedger.isJoker(cid.live.mainHandItem)) { "the joker stays in hand" }
                check(rig.ledger.held(cid.live).isEmpty() && rig.challenge(view.ref)!!.bets.isEmpty() && rig.decisions("bet").isEmpty()) { "no bet" }
                cid.hold(ItemStack(Items.IRON_INGOT, 2))
                check(rig.ui["bet.no_item"] != rig.bet(cid, view.ref, bob.name).string && rig.challenge(view.ref)!!.bets.size == 1) { "then a real stake" }
            }
            rig.cancel(view.ref)
            rig.onServer {
                rig.tick()
                check(count(cid.live, Items.IRON_INGOT) == 2) { "given back with the cancel" }
            }
        }
    }

    /** `/bet` is a player command: the dispatcher runs it with the player's own permission. */
    @GameTest
    fun betIsACommandForEveryPlayer(helper: GameTestHelper) {
        val runtime = helper.narrator()
        val player = TestPlayer(helper, "bt_")
        try {
            val output = CommandOutput()
            helper.level.server.commands.performPrefixedCommand(player.player.createCommandSourceStack().withSource(output), "bet C-999999999 nobody")
            val ui = runtime.settings.get().ui
            val expected = if (runtime.mechanics.available) ui.format("command.unknown_challenge", "id" to "C-999999999") else ui["mechanics.unavailable"]
            helper.assertEquals(listOf(expected), output.lines.toList(), "what the player reads")
        } finally {
            player.close()
        }
        helper.succeed()
    }

    // ── The rig ──

    /**
     * An engine of the test's own: the challenges' part with the bets' part on a store in memory, a ledger of its own,
     * a fake Director, the decisions kept, and [clock] that the test moves. The rest is the running Narrator's.
     */
    private class Rig(
        val helper: GameTestHelper,
        val director: FakeDirector,
        val sink: KeptDecisions,
        val clock: TestClock,
        val store: StateStore = StateStore.inMemory(),
    ) {
        val runtime: NarratorRuntime = helper.narrator()
        val ui: UiTexts get() = runtime.settings.get().ui
        val services: NarratorServices = object : NarratorServices by runtime {
            override val trace: TraceSink get() = sink
            override val director: DirectorControl get() = this@Rig.director
        }
        val ledger = ItemLedger(GuardStates(), Decisions(sink, clock), clock, tell = { _, _ -> }) { runtime.settings.get().ui }
        val bets = Bets(services, store, ledger, clock)
        val challenges = Challenges(services, store, Bossbars(), bets = bets, clock = clock)
        val engine = Engine(services, store, listOf(challenges, bets), clock)

        /** Services whose engine is this rig's (available), for `/bet` as the command runs it. */
        val commandServices: NarratorServices = object : NarratorServices by services {
            override val mechanics: Mechanics = object : Mechanics by runtime.mechanics {
                override val available = true
                override val challenges: ChallengeControl = object : ChallengeControl by runtime.mechanics.challenges {
                    override fun bet(player: ServerPlayer, ref: Ref, on: String): Component = bets.bet(player, ref, on)
                }
            }
        }

        fun challenge(ref: Ref) = store.get().challenge(ref.id)

        fun bet(player: TestPlayer, ref: Ref, on: String): Component = bets.bet(player.live, ref, on)

        fun decisions(name: String): List<DecisionRecord> = sink.kept.filter { it.kind == DecisionRecord.Kind.MECHANIC && it.decision == name }

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

        /** Cancels [ref] as an operator would: its canon closed, its bets given back. */
        suspend fun cancel(ref: Ref) {
            challenges.end(ref, ChallengeOutcome.CANCELLED, emptyList(), "Le défi a été annulé par un opérateur.", Author(Source.OPERATOR, operator = "gametest"))
        }

        /** Waits for the end of [ref] as the Director hears it: its line. */
        suspend fun awaitEnd(ref: Ref): String {
            awaitCondition("the end of $ref") { director.signals.any { it is DirectorSignal.Mechanic && it.kind == MechanicSignal.CHALLENGE && it.line.startsWith("$ref ") } }
            return director.signals.filterIsInstance<DirectorSignal.Mechanic>().last { it.kind == MechanicSignal.CHALLENGE && it.line.startsWith("$ref ") }.line
        }

        companion object {
            operator fun invoke(helper: GameTestHelper): Rig {
                val clock = TestClock(Instant.ofEpochMilli(helper.narrator().clock.millis()), helper.narrator().clock.zone)
                return Rig(helper, FakeDirector(clock), KeptDecisions(helper.narrator().trace), clock)
            }
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
        const val MINUTE = 60_000L

        val DIRECTOR = Caller(Role.DIRECTOR, "director-gametest", turnId = 4242, taskId = null)

        /** Runs [test] with a [Rig] started on the server thread and [count] players; all closed however it ends (those who came back too). */
        suspend fun CoroutineScope.withRig(helper: GameTestHelper, count: Int, test: suspend (Rig, List<TestPlayer>) -> Unit) {
            val server = helper.level.server
            val players = server.onServerThread { List(count) { TestPlayer(helper, "bt_") }.onEach { it.forget() } }
            val rig = Rig(helper)
            try {
                rig.onServer { rig.engine.start(server) }
                test(rig, players)
            } finally {
                server.onServerThread {
                    rig.engine.stop()
                    players.forEach { it.close() }
                }
            }
        }

        /** A challenge open to bets: hold an apple ([GoalKind.OBTAIN]) or [goal], for [players]. */
        fun spec(players: List<TestPlayer>, title: String, goal: GoalKind = GoalKind.OBTAIN, minutes: Int = 5, winners: Int = 1) = ChallengeSpec(
            title = title,
            statement = "Le Narrateur a lancé un défi d'essai aux joueurs.",
            goal = goal,
            item = if (goal == GoalKind.OBTAIN) "apple" else null,
            players = players.map { it.name },
            minutes = minutes,
            winners = winners,
            bets = true,
        )

        /** The name of [item] as the server writes it. */
        fun nameOf(item: Item): String = ItemStack(item).hoverName.string

        /** One of the Narrator's jokers (M4 §7.1): paper marked `{narrator2: {joker: 1b}}`. */
        fun joker(): ItemStack = ItemStack(Items.PAPER).also { stack ->
            val mark = CompoundTag()
            mark.putBoolean(ItemLedger.JOKER_KEY, true)
            val data = CompoundTag()
            data.put("narrator2", mark)
            CustomData.set(DataComponents.CUSTOM_DATA, stack, data)
        }

        /** The player saved as [saved] joins again, loaded from their file into a new player object. */
        fun comeBack(helper: GameTestHelper, saved: NameAndId): ServerPlayer {
            val server = helper.level.server
            val players = server.playerList
            val data = players.loadPlayerData(saved).orElseThrow { AssertionError("${saved.name()} was not saved") }
            val profile = GameProfile(saved.id(), saved.name())
            val back = ServerPlayer(server, helper.level, profile, ClientInformation.createDefault())
            back.load(TagValueInput.create(ProblemReporter.DISCARDING, server.registryAccess(), data))
            val connection = Connection(PacketFlow.SERVERBOUND)
            EmbeddedChannel(connection)
            players.placeNewPlayer(connection, back, CommonListenerCookie.createInitial(profile, false))
            return back
        }

        /** Whether a `mechanic settled` decision moved a stake. */
        val DecisionRecord.applied: Boolean get() = detail?.get("applied")?.jsonPrimitive?.booleanOrNull == true
    }
}

/** [this] player holds [stack] in their main hand. */
private fun TestPlayer.hold(stack: ItemStack) = live.setItemInHand(InteractionHand.MAIN_HAND, stack)

/** The chat lines [this] player received, as text. */
private fun TestPlayer.lines(): List<String> = chat().map { it.content().string }

/** Whether [this] player read the UI text [key] (its part before `{items}`). */
private fun TestPlayer.told(key: String): Boolean {
    val text = UiTexts.load(NarratorConfig().language)[key].substringBefore("{items}")
    return lines().any { text in it }
}
