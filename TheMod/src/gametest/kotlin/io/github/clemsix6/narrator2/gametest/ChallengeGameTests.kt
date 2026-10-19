package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.config.UiTexts
import io.github.clemsix6.narrator2.core.onServerThread
import io.github.clemsix6.narrator2.director.DirectorControl
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.director.MechanicSignal
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.mechanics.Bet
import io.github.clemsix6.narrator2.mechanics.Bossbars
import io.github.clemsix6.narrator2.mechanics.ChallengeOutcome
import io.github.clemsix6.narrator2.mechanics.ChallengeSpec
import io.github.clemsix6.narrator2.mechanics.ChallengeStatus
import io.github.clemsix6.narrator2.mechanics.Engine
import io.github.clemsix6.narrator2.mechanics.GoalKind
import io.github.clemsix6.narrator2.mechanics.MechanicPart
import io.github.clemsix6.narrator2.mechanics.MechanicRejected
import io.github.clemsix6.narrator2.mechanics.MechanicsState
import io.github.clemsix6.narrator2.mechanics.OutReason
import io.github.clemsix6.narrator2.mechanics.PlaceSpec
import io.github.clemsix6.narrator2.mechanics.RewardKind
import io.github.clemsix6.narrator2.mechanics.RewardSpec
import io.github.clemsix6.narrator2.mechanics.Settlement
import io.github.clemsix6.narrator2.mechanics.StateStore
import io.github.clemsix6.narrator2.mechanics.challenge.Challenges
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.CanonEntry
import io.github.clemsix6.narrator2.memory.CanonKind
import io.github.clemsix6.narrator2.memory.CanonMechanic
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.memory.InteractionKind
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.testing.FakeDirector
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.TraceSink
import kotlinx.coroutines.CoroutineScope
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.chat.ChatType
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.PlayerChatMessage
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundBossEventPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.world.BossEvent.BossBarColor
import net.minecraft.world.BossEvent.BossBarOverlay
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-game tests of the challenges (M4-12, M4 §5.2, §21.2): each goal from its start to its end with test
 * players — `obtain` (items given, counted slot by slot), `reach` (moved within the radius), `kill` (real
 * kills), `survive` (a death, armor worn), `answer` (the public chat, a prayer, never a whisper), `judged` (its
 * end, a `JUDGE` to a fake Director, then judged or expired) — and a clean end each time: bars taken down, canon
 * resolved by the engine, `challenge_result` interactions, the `mechanic` decision and the Director's event.
 * Then a challenge that goes on after a restart, and an operator's cancel with its bets given back.
 *
 * Each test builds its own engine on a store in memory, with the challenges' part and the bars, driven by a
 * clock of its own (minutes pass in a tick); the canon and the interactions are the real memory's. Scripts run
 * off the server thread (`runScript`), the world's work on it. Only the players named in each challenge take
 * part: the tests of a batch share the server.
 */
class ChallengeGameTests {

    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun obtainCountsEverySlotAndTheFirstToHoldTheCountWins(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 2) { rig, (bob, ann) ->
            val spec = spec(GoalKind.OBTAIN, listOf(bob, ann), "Trois œufs", item = "turtle_egg", count = 3, reward = RewardSpec(RewardKind.ITEM, item = "golden_apple"))
            val view = rig.challenges.start(spec, DIRECTOR)
            val canon = canon(rig, view.ref)
            check(canon.kind == CanonKind.CHALLENGE && canon.mechanic == CanonMechanic.CHALLENGE && canon.status == CanonStatus.ACTIVE) { "the canon row: $canon" }
            check(canon.players.map { it.uuid } == listOf(bob.uuid, ann.uuid) && canon.stakes == "reward 1x golden_apple" && canon.dueAt == null) { "its players and stakes: $canon" }
            check(rig.decisions("started").single().reason!!.startsWith("${view.ref} started: obtain 3 turtle_egg « Trois œufs » · ")) { "the start: ${rig.decisions("started")}" }

            rig.onServer {
                rig.second()
                check(bars(bob).single().let { "Trois œufs · 0/3 · " in it.name && it.color == BossBarColor.GREEN }) { "Bob's bar: ${bars(bob)}" }
                check(bars(ann).size == 1) { "Ann's bar: ${bars(ann)}" }
                bob.live.inventory.add(ItemStack(Items.TURTLE_EGG, 1))
                bob.live.setItemSlot(EquipmentSlot.OFFHAND, ItemStack(Items.TURTLE_EGG, 1))
                ann.live.inventory.add(ItemStack(Items.EGG, 16))
                rig.second()
                val bobs = rig.challenge(view.ref)!!.participants.first()
                check(bobs.progress == 2 && bobs.doneAt == null) { "the inventory and the off hand: $bobs" }
                check("2/3" in bars(bob).single().name) { "his bar follows: ${bars(bob)}" }
                bob.live.setItemSlot(EquipmentSlot.HEAD, ItemStack(Items.TURTLE_EGG, 1))
                rig.second()
                check(rig.store.get().challenges.isEmpty()) { "won: out of the state at once" }
                check(bars(bob).isEmpty() && bars(ann).isEmpty()) { "the bars are taken down: ${bars(bob)} ${bars(ann)}" }
            }
            val line = rig.awaitEnd(view.ref, MechanicSignal.CHALLENGE)
            check(Regex("^${view.ref} won by ${bob.name} in \\d+s \\(turtle_egg 3/3\\)$").matches(line)) { "the line: $line" }
            rig.checkClosed(view.ref, CanonStatus.WON, listOf(bob), listOf(ann), line)
        }
    }

    /** Nobody holds the item by the deadline: lost, everyone enrolled gets a result of 0, nobody is a winner. */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun aChallengeNobodyWinsIsLostAtItsDeadline(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 2) { rig, (bob, ann) ->
            val view = rig.challenges.start(spec(GoalKind.OBTAIN, listOf(bob, ann), "Une émeraude", item = "emerald", minutes = 4), DIRECTOR)
            rig.onServer {
                rig.second()
                bob.live.inventory.add(ItemStack(Items.DIAMOND, 1))
                rig.at(view.endsAt - 1_000)
                check(rig.store.get().challenges.size == 1 && bars(bob).size == 1) { "still running a second before its end" }
                rig.at(view.endsAt)
                check(rig.store.get().challenges.isEmpty() && bars(bob).isEmpty() && bars(ann).isEmpty()) { "lost at its end, bars down" }
            }
            val line = rig.awaitEnd(view.ref, MechanicSignal.CHALLENGE)
            check(line == "${view.ref} lost: nobody held 1 emerald in 4m") { "the line: $line" }
            check(rig.director.signals.filterIsInstance<DirectorSignal.Mechanic>().single().priority) { "a result wakes the Director even past his budget" }
            rig.checkClosed(view.ref, CanonStatus.LOST, emptyList(), listOf(bob, ann), line)
        }
    }

    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun reachIsWonAtTheDeadlineByEveryoneWhoGotThere(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 2) { rig, (bob, ann) ->
            rig.onServer {
                for (x in 0..7) for (z in 0..7) helper.setBlock(x, 0, z, Blocks.STONE)
                bob.standAt(BlockPos(0, 1, 0))
                ann.standAt(BlockPos(0, 1, 0))
            }
            val point = helper.absolutePos(BlockPos(7, 1, 7))
            val dimension = helper.level.dimension().identifier().toString()
            val place = PlaceSpec(dimension = dimension, x = point.x, y = point.y, z = point.z, radius = 2)
            val view = rig.challenges.start(spec(GoalKind.REACH, listOf(bob, ann), "Le coin", place = place, winners = 0, minutes = 3), DIRECTOR)
            rig.onServer {
                rig.second()
                check(rig.challenge(view.ref)!!.participants.none { it.doneAt != null }) { "nobody there yet" }
                check(bars(bob).single().color == BossBarColor.BLUE) { "reach is blue: ${bars(bob)}" }
                bob.standAt(BlockPos(6, 1, 7))
                rig.second()
                val state = rig.challenge(view.ref)!!
                check(state.participants.map { it.progress } == listOf(1, 0) && state.participants[0].doneAt != null) { "Bob got there: ${state.participants}" }
                check(bars(bob).single().name.contains("réussi")) { "his bar says so: ${bars(bob)}" }
                rig.at(view.endsAt - 1_000)
                check(rig.store.get().challenges.size == 1) { "winners 0: it goes on to its end" }
                rig.at(view.endsAt)
                check(rig.store.get().challenges.isEmpty() && bars(bob).isEmpty() && bars(ann).isEmpty()) { "ended, bars down" }
            }
            val line = rig.awaitEnd(view.ref, MechanicSignal.CHALLENGE)
            check(line.startsWith("${view.ref} won by ${bob.name} in ") && line.endsWith("(reached x ${point.x} y ${point.y} z ${point.z} (within 2) in ${ToolText.dimension(dimension)})")) { "the line: $line" }
            rig.checkClosed(view.ref, CanonStatus.WON, listOf(bob), listOf(ann), line)
        }
    }

    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun killCountsOnlyTheParticipantsKillsOfTheGoalsCreatures(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 3) { rig, (bob, ann, eve) ->
            val view = rig.challenges.start(spec(GoalKind.KILL, listOf(bob, ann), "Deux zombies", entity = "zombie", count = 2), DIRECTOR)
            rig.onServer {
                for (x in 0..7) for (z in 0..7) helper.setBlock(x, 0, z, Blocks.STONE)
                kill(helper, EntityTypes.ZOMBIE, bob)
                kill(helper, EntityTypes.CHICKEN, bob)
                kill(helper, EntityTypes.ZOMBIE, eve) // not a participant
                rig.tick()
                val bobs = rig.challenge(view.ref)!!.participants.first()
                check(bobs.progress == 1 && bobs.doneAt == null) { "one zombie counted at the next tick, the chicken and Eve's zombie not: $bobs" }
                rig.second()
                check("1/2" in bars(bob).single().name && bars(bob).single().color == BossBarColor.RED) { "Bob's bar: ${bars(bob)}" }
                check(bars(eve).isEmpty()) { "Eve takes no part" }
                kill(helper, EntityTypes.ZOMBIE, bob)
                rig.tick()
                check(rig.store.get().challenges.isEmpty() && bars(bob).isEmpty()) { "the second zombie wins at once" }
            }
            val line = rig.awaitEnd(view.ref, MechanicSignal.CHALLENGE)
            check(Regex("^${view.ref} won by ${bob.name} in \\d+s \\(zombie 2/2\\)$").matches(line)) { "the line: $line" }
            rig.checkClosed(view.ref, CanonStatus.WON, listOf(bob), listOf(ann), line)
        }
    }

    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun surviveEliminatesTheDeadAndTheArmoredAndCrownsTheRestAtTheDeadline(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 3) { rig, (bob, ann, cid) ->
            val view = rig.challenges.start(spec(GoalKind.SURVIVE, listOf(bob, ann, cid), "Nus", noArmor = true, minutes = 2), DIRECTOR)
            rig.onServer {
                rig.second()
                check(bars(cid).single().let { "en lice" in it.name && it.color == BossBarColor.PURPLE }) { "Cid's bar: ${bars(cid)}" }
                bob.makeVulnerable()
                bob.live.hurtServer(helper.level, helper.level.damageSources().magic(), 1_000f)
                check(bob.live.isDeadOrDying) { "Bob died" }
                ann.live.setItemSlot(EquipmentSlot.FEET, ItemStack(Items.LEATHER_BOOTS))
                rig.tick()
                check(rig.challenge(view.ref)!!.participants.map { it.out } == listOf(OutReason.DIED, null, null)) { "out at the death" }
                rig.second()
                check(rig.challenge(view.ref)!!.participants.map { it.out } == listOf(OutReason.DIED, OutReason.ARMOR, null)) { "out for the boots" }
                check("éliminé" in bars(ann).single().name) { "Ann's bar: ${bars(ann)}" }
                rig.at(view.endsAt)
                check(rig.store.get().challenges.isEmpty() && bars(cid).isEmpty() && bars(ann).isEmpty()) { "ended, bars down" }
            }
            val line = rig.awaitEnd(view.ref, MechanicSignal.CHALLENGE)
            check(line == "${view.ref} won by ${cid.name}: still in after 2m; out: ${bob.name} (died), ${ann.name} (armor)") { "the line: $line" }
            rig.checkClosed(view.ref, CanonStatus.WON, listOf(cid), listOf(bob, ann), line)
        }
    }

    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun answerTakesThePublicChatAndPrayersButNeverAWhisper(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 3) { rig, (bob, ann, cid) ->
            val spec = spec(GoalKind.ANSWER, listOf(bob, ann, cid), "Énigme", answers = listOf("une enclume", "enclume"), winners = 2)
            val view = rig.challenges.start(spec, DIRECTOR)
            rig.onServer {
                rig.second()
                check(bars(bob).single().color == BossBarColor.YELLOW) { "answer is yellow: ${bars(bob)}" }
                command(helper.level.server, cid, "msg ${ann.name} c'est une enclume")
                say(helper.level.server, bob, "Un marteau ?")
                say(helper.level.server, ann, "C'est une ENCLUME, évidemment !")
                rig.tick()
                check(rig.challenge(view.ref)!!.participants.map { it.doneAt != null } == listOf(false, true, false)) { "Ann answered, not the whisper nor the hammer" }
                command(helper.level.server, bob, "pray Je dirais... l'enclume")
                rig.tick()
                check(rig.store.get().challenges.isEmpty() && bars(bob).isEmpty()) { "two answered: it ends" }
            }
            val line = rig.awaitEnd(view.ref, MechanicSignal.CHALLENGE)
            check(Regex("^${view.ref} won by ${ann.name} in \\d+s, ${bob.name} in \\d+s \\(answered\\)$").matches(line)) { "the line: $line" }
            rig.checkClosed(view.ref, CanonStatus.WON, listOf(ann, bob), listOf(cid), line)
        }
    }

    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun aJudgedChallengeWaitsForTheDirectorsVerdictThenExpires(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 2) { rig, (bob, ann) ->
            val first = rig.challenges.start(spec(GoalKind.JUDGED, listOf(bob, ann), "La statue", minutes = 5), DIRECTOR)
            rig.onServer {
                rig.at(first.endsAt)
                val judging = rig.challenge(first.ref)!!
                check(judging.status == ChallengeStatus.JUDGING && judging.judgeUntil == first.endsAt + 15 * MINUTE) { "judging: $judging" }
                check(bars(bob).single().name.endsWith("le Narrateur juge")) { "the bar says it is judged: ${bars(bob)}" }
            }
            val judge = rig.director.signals.filterIsInstance<DirectorSignal.Mechanic>().single()
            check(judge.kind == MechanicSignal.JUDGE && judge.priority) { "a JUDGE, with priority: $judge" }
            check(judge.line == "${first.ref} « La statue » ended: judge it now with end_challenge ${first.ref} (winners); participants: ${bob.name}, ${ann.name}; you have 15m") {
                "the JUDGE line: ${judge.line}"
            }
            check(rig.decisions("judging").single().let { it.subject == "${first.ref}" && it.reason == judge.line }) { "the judging decision: ${rig.decisions("judging")}" }

            // The Director names Ann: a priority signal would wake him for nothing, the tool answers him.
            val judged = rig.challenges.end(first.ref, ChallengeOutcome.WON, listOf(ann.uuid), "Ann a sculpté la plus belle statue.", Author(Source.DIRECTOR, turnId = 4242))
            check(judged.line.startsWith("${first.ref} won by ${ann.name} (ended by the Director); note: « Ann a sculpté la plus belle statue. »")) { "the end's line: ${judged.line}" }
            check(rig.director.signals.size == 1) { "no event for the Director's own end: ${rig.director.signals}" }
            rig.checkClosed(first.ref, CanonStatus.WON, listOf(ann), listOf(bob), judged.line, resolution = "Ann a sculpté la plus belle statue.")
            val refusal = runCatching { rig.challenges.end(first.ref, ChallengeOutcome.LOST, emptyList(), "Rien.", Author(Source.DIRECTOR)) }.exceptionOrNull()
            check(refusal is MechanicRejected && refusal.message == "${first.ref} is not one of your running challenges; running: none") { "ended once: $refusal" }

            // A second one, ten minutes later, that nobody judges.
            rig.onServer { rig.at(rig.clock.millis() + 10 * MINUTE) }
            val second = rig.challenges.start(spec(GoalKind.JUDGED, listOf(bob, ann), "La tour", minutes = 5), DIRECTOR)
            rig.onServer {
                rig.at(second.endsAt)
                rig.at(second.endsAt + 15 * MINUTE)
                check(rig.store.get().challenges.isEmpty() && bars(bob).isEmpty()) { "expired, bars down" }
            }
            val line = rig.awaitEnd(second.ref, MechanicSignal.CHALLENGE)
            check(line == "${second.ref} expired: not judged within 15m; no stakes") { "the expiry: $line" }
            rig.checkClosed(second.ref, CanonStatus.EXPIRED, emptyList(), emptyList(), line, interactions = false)
        }
    }

    /**
     * A restart in the middle of a challenge: the engine stops (bars down), the state goes through the world's
     * codec, a new engine starts on it, shows the bars again and goes on counting to the win.
     */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun aChallengeGoesOnAfterARestart(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 2) { rig, (bob, ann) ->
            val view = rig.challenges.start(spec(GoalKind.KILL, listOf(bob, ann), "Avant et après", entity = "zombie", count = 2, minutes = 10), DIRECTOR)
            val saved = rig.onServer {
                for (x in 0..7) for (z in 0..7) helper.setBlock(x, 0, z, Blocks.STONE)
                kill(helper, EntityTypes.ZOMBIE, bob)
                rig.second()
                check("1/2" in bars(bob).single().name) { "one before: ${bars(bob)}" }
                rig.engine.stop()
                check(bars(bob).isEmpty()) { "the bars go with the server" }
                MechanicsState.decode(MechanicsState.encode(rig.store.get()))
            }
            check(saved.challenges.single().participants.first().progress == 1) { "the progress is saved with the world: $saved" }

            val again = Rig(helper, rig.director, rig.sink, StateStore.inMemory(saved), rig.clock)
            try {
                rig.onServer {
                    rig.clock.advance(Duration.ofMinutes(1))
                    again.engine.start(helper.level.server)
                    check("1/2" in bars(bob).single().name) { "the bars come back from the state: ${bars(bob)}" }
                    kill(helper, EntityTypes.ZOMBIE, bob)
                    again.tick()
                    check(again.store.get().challenges.isEmpty() && bars(bob).isEmpty()) { "the second zombie, after the restart, wins" }
                }
                val line = again.awaitEnd(view.ref, MechanicSignal.CHALLENGE)
                check(Regex("^${view.ref} won by ${bob.name} in 1m\\d\\ds \\(zombie 2/2\\)$").matches(line)) { "the line: $line" }
                again.checkClosed(view.ref, CanonStatus.WON, listOf(bob), listOf(ann), line)
            } finally {
                rig.onServer { again.engine.stop() }
            }
        }
    }

    /**
     * The pace refuses a second challenge at once; an operator cancels the first: no stakes, no result, its
     * bets given back by instructions saved with the end, and the Director told (with priority).
     */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun anOperatorsCancelGivesTheBetsBackAndThePaceRefusesTooSoon(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 2) { rig, (bob, ann) ->
            val view = rig.challenges.start(spec(GoalKind.OBTAIN, listOf(bob), "Une pomme", item = "apple"), DIRECTOR)
            val refused = runCatching { rig.challenges.start(spec(GoalKind.OBTAIN, listOf(ann), "Trop tôt", item = "apple"), DIRECTOR) }.exceptionOrNull()
            check(refused is MechanicRejected && refused.rule == Challenges.CHALLENGE_PACE && refused.message!!.startsWith("the last challenge started 0s ago; the next may start at ")) {
                "the pace: $refused"
            }
            check(rig.challenges.list().map { it.ref } == listOf(view.ref)) { "one challenge: ${rig.challenges.list()}" }
            rig.onServer {
                // Ann's bet, as the bets' part (M4-20) would have placed it.
                rig.store.update { state ->
                    state.copy(challenges = state.challenges.map { it.copy(bets = listOf(Bet(ann.uuid.toString(), ann.name, bob.uuid.toString(), stake = 9_001, what = "3x apple"))) })
                }
                rig.second()
            }
            val ended = rig.challenges.end(view.ref, ChallengeOutcome.CANCELLED, emptyList(), "Le défi a été annulé par un opérateur.", Author(Source.OPERATOR, operator = "gametest"))
            check(ended.line == "${view.ref} cancelled (ended by the operator gametest); note: « Le défi a été annulé par un opérateur. » · bets: 1 stake given back") {
                "the line: ${ended.line}"
            }
            rig.onServer {
                check(rig.store.get().settlements == listOf(Settlement(owner = ann.uuid.toString(), stake = 9_001))) { "the stake goes back: ${rig.store.get()}" }
                check(bars(bob).isEmpty()) { "the bar is down" }
            }
            val signal = rig.director.signals.filterIsInstance<DirectorSignal.Mechanic>().single()
            check(signal.kind == MechanicSignal.CHALLENGE && signal.priority && signal.line == ended.line) { "the operator's cancel is told: $signal" }
            rig.checkClosed(view.ref, CanonStatus.CANCELLED, emptyList(), emptyList(), ended.line, interactions = false, resolution = "Le défi a été annulé par un opérateur.")
        }
    }

    // ── The rig ──

    /**
     * An engine of the test's own: the challenges' part and the bars on a store in memory, a fake Director, the
     * decisions kept, and [clock] that the test moves. The rest is the running Narrator's (memory, Journal, bus).
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
        val bars = Bossbars()
        val challenges = Challenges(services, store, bars, clock = clock)
        val engine = Engine(services, store, listOf(challenges, DrawnBars(bars, store, runtime.settings.get().ui)), clock)

        fun challenge(ref: Ref) = store.get().challenge(ref.id)

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

        /** Waits for the end of [ref] as the Director hears it ([kind]): its line. */
        suspend fun awaitEnd(ref: Ref, kind: MechanicSignal): String {
            awaitCondition("the $kind event of $ref") { director.signals.any { it is DirectorSignal.Mechanic && it.kind == kind && it.line.startsWith("$ref ") } }
            return director.signals.filterIsInstance<DirectorSignal.Mechanic>().last { it.line.startsWith("$ref ") }.line
        }

        /**
         * [ref] ended cleanly: its canon resolved by the engine [status] with [winners], its decision with [line],
         * a `challenge_result` per participant (+1 for [winners], 0 for [losers]) when [interactions].
         */
        suspend fun checkClosed(
            ref: Ref,
            status: CanonStatus,
            winners: List<TestPlayer>,
            losers: List<TestPlayer>,
            line: String,
            interactions: Boolean = true,
            resolution: String? = null,
        ) {
            awaitCondition("the decision of $ref's end") { sink.kept.any { it.kind == DecisionRecord.Kind.MECHANIC && it.subject == "$ref" && it.decision == status.id } }
            val decision = sink.kept.last { it.kind == DecisionRecord.Kind.MECHANIC && it.subject == "$ref" && it.decision == status.id }
            check(decision.reason == line) { "the decision says the line: ${decision.reason}" }
            check(decision.conversationId == "mechanics:$ref") { "the decision's conversation: ${decision.conversationId}" }
            val row = canon(this, ref)
            check(row.status == status && row.winners.map { it.uuid } == winners.map { it.uuid }) { "the canon: ${row.status} ${row.winners}" }
            check(resolution == null || row.resolution == resolution) { "the resolution: ${row.resolution}" }
            check(row.resolution != null && line.removePrefix("$ref ").startsWith(row.resolution!!.substringBefore(';')) || resolution != null) { "the resolution says how it ended: ${row.resolution}" }
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
        const val MINUTE = 60_000L

        val DIRECTOR = Caller(Role.DIRECTOR, "director-gametest", turnId = 4242, taskId = null)

        /** Runs [test] with a [Rig] started on the server thread and [count] players who record their boss bars; all closed however it ends. */
        suspend fun CoroutineScope.withRig(helper: GameTestHelper, count: Int, test: suspend (Rig, List<TestPlayer>) -> Unit) {
            val server = helper.level.server
            val players = server.onServerThread { List(count) { TestPlayer(helper, "ch_", keep = ::isBossBar) } }
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

        fun isBossBar(packet: Packet<*>): Boolean = packet is ClientboundBossEventPacket

        suspend fun canon(rig: Rig, ref: Ref): CanonEntry = rig.runtime.memory.store.canon(ref) ?: error("no canon row $ref")

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

        /** A challenge of [goal] for [players], the rest as given. */
        fun spec(
            goal: GoalKind,
            players: List<TestPlayer>,
            title: String,
            item: String? = null,
            count: Int = 1,
            entity: String? = null,
            place: PlaceSpec? = null,
            answers: List<String>? = null,
            noArmor: Boolean = false,
            minutes: Int = 5,
            winners: Int = 1,
            reward: RewardSpec? = null,
        ) = ChallengeSpec(
            title = title,
            statement = "Le Narrateur a lancé un défi d'essai aux joueurs.",
            goal = goal,
            item = item,
            count = count,
            entity = entity,
            place = place,
            answers = answers,
            noArmor = noArmor,
            players = players.map { it.name },
            minutes = minutes,
            winners = winners,
            reward = reward,
        )

        /** [killer] kills a new creature of [type] in the test's area. Server thread. */
        fun <E : LivingEntity> kill(helper: GameTestHelper, type: EntityType<E>, killer: TestPlayer) {
            val victim = helper.spawn(type, BlockPos(3, 1, 3))
            victim.hurtServer(helper.level, helper.level.damageSources().playerAttack(killer.live), 1_000f)
            check(victim.isDeadOrDying) { "the ${type.description.string} died" }
        }

        /** [player] says [text] in the public chat, as the client would. Server thread. */
        fun say(server: MinecraftServer, player: TestPlayer, text: String) {
            val live = player.live
            server.playerList.broadcastChatMessage(PlayerChatMessage.unsigned(live.uuid, text), live, ChatType.bind(ChatType.CHAT, live))
        }

        /** [player] types `/`[command]. Server thread. */
        fun command(server: MinecraftServer, player: TestPlayer, command: String) {
            server.commands.performPrefixedCommand(player.live.createCommandSourceStack(), command)
        }
    }
}
