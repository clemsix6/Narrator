package io.github.clemsix6.narrator2.gametest

import com.mojang.authlib.GameProfile
import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.config.UiTexts
import io.github.clemsix6.narrator2.core.onServerThread
import io.github.clemsix6.narrator2.director.DirectorControl
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.director.MechanicSignal
import io.github.clemsix6.narrator2.guard.ItemLedger
import io.github.clemsix6.narrator2.guard.LedgerPurpose
import io.github.clemsix6.narrator2.guard.PunishmentCategory
import io.github.clemsix6.narrator2.guard.Rules
import io.github.clemsix6.narrator2.guard.Verdict
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.mechanics.Bossbars
import io.github.clemsix6.narrator2.mechanics.ChallengeControl
import io.github.clemsix6.narrator2.mechanics.ChallengeOutcome
import io.github.clemsix6.narrator2.mechanics.ChallengeSpec
import io.github.clemsix6.narrator2.mechanics.ChallengeStatus
import io.github.clemsix6.narrator2.mechanics.ChallengeView
import io.github.clemsix6.narrator2.mechanics.DisabledMechanics
import io.github.clemsix6.narrator2.mechanics.Engine
import io.github.clemsix6.narrator2.mechanics.EngineActions
import io.github.clemsix6.narrator2.mechanics.MechanicPart
import io.github.clemsix6.narrator2.mechanics.Mechanics
import io.github.clemsix6.narrator2.mechanics.MechanicsState
import io.github.clemsix6.narrator2.mechanics.Participant
import io.github.clemsix6.narrator2.mechanics.StateStore
import io.github.clemsix6.narrator2.mechanics.challenge.Challenges
import io.github.clemsix6.narrator2.mechanics.challenge.Stakes
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.memory.Interaction
import io.github.clemsix6.narrator2.memory.InteractionKind
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.testing.FakeDirector
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.ToolCatalog
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolRunner
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.tool.action.FavorTool
import io.github.clemsix6.narrator2.tool.mechanics.EndChallengeTool
import io.github.clemsix6.narrator2.tool.mechanics.StartChallengeTool
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.TraceSink
import io.netty.channel.embedded.EmbeddedChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.Connection
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.game.ClientboundBossEventPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ClientInformation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-game tests of the challenges' tools and stakes (M4-17, M4 §5.3, §12, §21.2), from the tool to the payment:
 * `start_challenge` as the model writes it, the canon and the bars, the win, then the stakes paid by the engine
 * through the real tools — `give_item` with its rationing, `favor` and its `favor_limit`, `punish` and its guards —
 * one act per player, to the players online only, each said in the line and kept in the player's history; a lost
 * challenge's forfeit for those enrolled at the start, never a latecomer; `end_challenge` of a judged challenge; and
 * the stakes refused when the challenge starts.
 *
 * Each test builds its own engine (the challenges' part, its bars and the stakes) on a store in memory, with a clock
 * it moves; the guards, the tools' limits, the memory and the Journal are the running Narrator's (the players are
 * the test's own, with unique names). The tools reach the test's engine, not the server's, through the services it
 * gives them.
 */
class ChallengeStakesGameTests {

    /** Won by two: the first gets the diamond, the second is past her precious points of the hour (refused, said); the loser gets the forfeit. */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun aChallengeStartedByTheToolPaysItsStakesThroughTheRealTools(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 3) { rig, (bob, ann, cid) ->
            rig.services.toolLimits.gifts.take(ann.ref, 4, "4x diamond") // Ann's precious points of the hour are spent
            val started = rig.call(
                rig.start,
                """{"title": "Un œuf", "statement": "Le Narrateur a défié trois joueurs de tenir un œuf de tortue.", "goal": "obtain",
                   "item": "turtle_egg", "players": ["${bob.name}", "${ann.name}", "${cid.name}"], "minutes": 5, "winners": 2,
                   "reward": {"kind": "item", "item": "diamond"}, "penalty": {"punishment": "tiny", "severity": 1}}""",
            )
            val view = rig.challenges.list().single()
            check(started.status == ToolStatus.OK && started.text.startsWith("ok: challenge ${view.ref} started: obtain 1 turtle_egg « Un œuf » · ${bob.name}, ")) {
                "the start: ${started.text}"
            }
            check(started.text.endsWith(" · reward 1x diamond · penalty tiny (1); boss bars shown")) { "its stakes: ${started.text}" }
            check(rig.canon(view.ref).stakes == "reward 1x diamond · penalty tiny (1)") { "the canon's stakes: ${rig.canon(view.ref)}" }

            rig.onServer {
                rig.second()
                check(bars(bob).size == 1 && bars(cid).size == 1) { "the bars: ${bars(bob)} ${bars(cid)}" }
                bob.live.inventory.add(ItemStack(Items.TURTLE_EGG))
                rig.second()
                ann.live.inventory.add(ItemStack(Items.TURTLE_EGG))
                rig.second()
                check(rig.store.get().challenges.isEmpty() && bars(cid).isEmpty()) { "won by two, bars down" }
            }
            val line = rig.awaitEnd(view.ref)
            val (head, reward, penalty) = line.split(" · ")
            check(Regex("^${view.ref} won by ${bob.name} in \\d+s, ${ann.name} in \\d+s \\(turtle_egg 1/1\\)$").matches(head)) { "the line: $line" }
            check(reward.startsWith("reward: ${bob.name} got 1x diamond, ${ann.name} refused (precious_limit: ${ann.name} got 4 of the 4 precious points")) {
                "the reward, refused to Ann by the rationing: $reward"
            }
            check(Regex("^penalty: ${cid.name} tiny \\(1\\) until \\d\\d:\\d\\d$").matches(penalty)) { "the forfeit: $penalty" }

            rig.onServer {
                check(count(bob, Items.DIAMOND) == 1 && count(ann, Items.DIAMOND) == 0) { "the real give_item: one diamond for Bob, none for Ann" }
                check(near(cid.live.getAttributeValue(Attributes.SCALE), 0.6)) { "the real punish: Cid is smaller (${cid.live.getAttributeValue(Attributes.SCALE)})" }
            }
            rig.checkStakes(view.ref, listOf(bob to InteractionKind.GIFT, cid to InteractionKind.PUNISHMENT), listOf(ann))
            rig.checkActs(view.ref, listOf(bob, ann, cid))
        }
    }

    /**
     * Nobody wins: the forfeit for each participant enrolled at the start and online — one refused by the guards (another
     * act punishes him), one paid, one gone offline — and never for the latecomer.
     */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun aLostChallengesForfeitSparesTheLatecomerAndTheOffline(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 4) { rig, (bob, ann, cid, dan) ->
            val started = rig.call(
                rig.start,
                """{"title": "Une émeraude", "statement": "Le Narrateur a défié trois joueurs de trouver une émeraude.", "goal": "obtain",
                   "item": "emerald", "players": ["${bob.name}", "${ann.name}", "${cid.name}"], "minutes": 3,
                   "penalty": {"punishment": "slow_hands", "severity": 1}}""",
            )
            check(started.status == ToolStatus.OK) { "the start: ${started.text}" }
            val view = rig.challenges.list().single()
            rig.onServer {
                // Dan joined late, as the engine enrolls a latecomer of a challenge open to all: he may win, never lose.
                rig.store.update { state ->
                    state.copy(challenges = state.challenges.map { it.copy(participants = it.participants + Participant(dan.uuid.toString(), dan.name, late = true)) })
                }
                // Bob is under another act's punishment: the forfeit may not pile on.
                val other = Caller(Role.DIRECTOR, "stakes-gametest", turnId = 777, taskId = null)
                val verdict = rig.services.guards.hostile(bob.live, other, rig.services.clock.millis() + 600_000, PunishmentCategory.HARM)
                check(verdict == Verdict.Allowed) { "Bob's other punishment: $verdict" }
                rig.second()
            }
            rig.onServer { cid.close() }
            awaitCondition("Cid offline") { rig.services.players.get(cid.uuid) == null }
            rig.onServer {
                rig.at(view.endsAt)
                check(rig.store.get().challenges.isEmpty()) { "lost at its end" }
            }
            val line = rig.awaitEnd(view.ref)
            val (head, penalty) = line.split(" · ")
            check(head == "${view.ref} lost: nobody held 1 emerald in 3m") { "the line: $line" }
            check(Regex("^penalty: ${bob.name} refused \\(${Rules.ALREADY_PUNISHED}: .+\\), ${ann.name} slow_hands \\(1\\) until \\d\\d:\\d\\d, ${cid.name} offline, not paid$").matches(penalty)) {
                "the forfeit: refused, paid, offline; nothing for the latecomer: $penalty"
            }
            rig.onServer {
                check(near(ann.live.getAttributeValue(Attributes.BLOCK_BREAK_SPEED), 0.6)) { "Ann digs slower: ${ann.live.getAttributeValue(Attributes.BLOCK_BREAK_SPEED)}" }
                check(near(dan.live.getAttributeValue(Attributes.BLOCK_BREAK_SPEED), 1.0) && near(bob.live.getAttributeValue(Attributes.BLOCK_BREAK_SPEED), 1.0)) {
                    "neither the latecomer nor the refused one"
                }
            }
            rig.checkStakes(view.ref, listOf(ann to InteractionKind.PUNISHMENT), listOf(bob, cid, dan))
            rig.checkActs(view.ref, listOf(bob, ann))
            // M4 review, finding 27: the latecomer may win, never lose, not even in his history.
            awaitCondition("the decision of ${view.ref}, written after its results") { rig.decisions("lost").any { it.subject == "${view.ref}" } }
            for (player in listOf(bob, ann, cid)) check(rig.results(player, view.ref).map { it.valence } == listOf(0)) { "${player.name} lost: ${rig.results(player, view.ref)}" }
            check(rig.results(dan, view.ref).isEmpty()) { "no « lost the challenge » for the latecomer: ${rig.results(dan, view.ref)}" }
        }
    }

    /**
     * A judged challenge ended by `end_challenge`: the winners named by the Director, the joker refused to the one who
     * got his of the day (`favor_limit`, said in the answer), given to the other; the forfeit for the loser; no event.
     */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun endChallengeJudgesAndAJokerPastItsLimitIsSaid(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 3) { rig, (bob, ann, cid) ->
            val first = rig.call(FavorTool(), """{"player": "${bob.name}", "favor": "joker"}""")
            check(first.text.startsWith("ok: ${bob.name} got a joker")) { "Bob's joker of the day: ${first.text}" }
            rig.call(
                rig.start,
                """{"title": "La statue", "statement": "Le Narrateur a demandé à trois joueurs de bâtir une statue.", "goal": "judged",
                   "players": ["${bob.name}", "${ann.name}", "${cid.name}"], "minutes": 5,
                   "reward": {"kind": "joker"}, "penalty": {"punishment": "hunger", "severity": 1}}""",
            )
            val view = rig.challenges.list().single()
            rig.onServer { rig.at(view.endsAt) }
            check(rig.challenges.list().single().status == ChallengeStatus.JUDGING) { "judging" }
            check(rig.director.signals.filterIsInstance<DirectorSignal.Mechanic>().single().kind == MechanicSignal.JUDGE) { "a JUDGE event" }

            val stranger = rig.call(rig.end, """{"id": "${view.ref}", "outcome": "won", "winners": ["Personne"], "note": "Personne n'a gagné."}""")
            check(stranger.text == "Invalid arguments: won needs winners among the participants: ${bob.name}, ${ann.name}, ${cid.name}") { "a stranger: ${stranger.text}" }
            val ended = rig.call(
                rig.end,
                """{"id": "${view.ref}", "outcome": "won", "winners": ["${bob.name}", "${ann.name.uppercase()}"], "note": "Deux statues ont plu au Narrateur."}""",
            )
            val expected = Regex(
                Regex.escape("ok: ${view.ref} won by ${bob.name}, ${ann.name} (ended by the Director); note: « Deux statues ont plu au Narrateur. » · ") +
                    Regex.escape("reward: ${bob.name} refused (favor_limit: ${bob.name} got a joker ") + "\\d+s" +
                    Regex.escape(" ago, and a player gets 1 a day at most; ") + ".*" + Regex.escape("), ${ann.name} got a joker · penalty: ${cid.name} hunger (1) until ") +
                    "\\d\\d:\\d\\d",
            )
            check(ended.status == ToolStatus.OK && expected.matches(ended.text)) { "the end: ${ended.text}" }

            rig.onServer {
                check(jokers(bob) == 1 && jokers(ann) == 1) { "one joker each: Bob's of the day, Ann's of the challenge" }
                check(cid.live.hasEffect(MobEffects.HUNGER)) { "Cid is hungry" }
            }
            check(rig.director.signals.size == 1) { "no event for the Director's own end: ${rig.director.signals}" }
            rig.checkStakes(view.ref, listOf(ann to InteractionKind.BLESSING, cid to InteractionKind.PUNISHMENT), listOf(bob))
            check(rig.canon(view.ref).resolution == "Deux statues ont plu au Narrateur.") { "the note is the canon's resolution" }
        }
    }

    /** The stakes pass their tools' dry checks when the challenge starts: nothing starts with a stake that could never be paid. */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun theStakesAreCheckedWhenTheChallengeStarts(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 1) { rig, (bob) ->
            fun start(stakes: String) = """{"title": "Refusé", "statement": "Le Narrateur a lancé un défi.", "goal": "judged",
                "players": ["${bob.name}"], "minutes": 5, $stakes}"""
            val refusals = listOf(
                """"reward": {"kind": "item", "item": "command_block"}""" to
                    "DENIED (forbidden_item): reward: give_item refuses 1x command_block: command_block is never given: ",
                """"reward": {"kind": "item", "item": "diamond", "count": 5}""" to
                    "DENIED (precious_limit): reward: give_item refuses 5x diamond: 5x diamond is worth 5 precious points, more than the 4",
                """"reward": {"kind": "effect", "effect": "poison"}""" to
                    "Invalid arguments: reward: apply_effect refuses poison I for 2m: poison is not a beneficial effect",
                """"reward": {"kind": "effect", "effect": "speedd"}""" to "Invalid arguments: reward: apply_effect refuses speedd I for 2m: unknown effect \"speedd\"",
                """"reward": {"kind": "effect", "effect": "resistance", "level": 4}""" to
                    "Invalid arguments: reward: apply_effect refuses resistance IV for 2m: resistance goes up to level 3",
                """"penalty": {"punishment": "smite", "severity": 3}""" to "Invalid arguments: penalty.severity must be between 1 and 2 (got 3)",
            )
            for ((stakes, refusal) in refusals) {
                val result = rig.call(rig.start, start(stakes))
                check(result.text.startsWith(refusal)) { "$stakes: ${result.text}" }
            }
            check(rig.challenges.list().isEmpty() && rig.decisions("started").isEmpty()) { "nothing started" }
            val accepted = rig.call(rig.start, start(""""reward": {"kind": "effect", "effect": "speed", "level": 2}, "penalty": {"punishment": "web", "severity": 2}"""))
            check(accepted.status == ToolStatus.OK && accepted.text.endsWith(" · reward speed II for 2m · penalty web (2); boss bars shown")) { "accepted: ${accepted.text}" }
        }
    }

    /**
     * M4 review, finding 36: the winners of an `obtain` often have a full inventory, and `give_item` dropped their reward
     * at their feet, where it burns or vanishes in five minutes. It waits in their ledger now, and comes once there is room.
     */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun anItemRewardForAFullInventoryWaitsInTheLedgerNeverOnTheGround(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 1) { rig, (bob) ->
            val started = rig.call(
                rig.start,
                """{"title": "Les mains pleines", "statement": "Le Narrateur a défié un joueur de tenir une pomme.", "goal": "obtain",
                   "item": "apple", "players": ["${bob.name}"], "minutes": 5, "reward": {"kind": "item", "item": "golden_apple"}}""",
            )
            check(started.status == ToolStatus.OK) { "the start: ${started.text}" }
            val view = rig.challenges.list().single()
            rig.onServer {
                val inventory = bob.live.inventory
                inventory.add(ItemStack(Items.APPLE))
                for (slot in 0 until MAIN_SLOTS) if (inventory.getItem(slot).isEmpty) inventory.setItem(slot, ItemStack(Items.COBBLESTONE, 64))
                rig.second()
                check(rig.store.get().challenges.isEmpty()) { "won" }
            }
            val line = rig.awaitEnd(view.ref)
            check(line.endsWith(" · reward: ${bob.name} got 1x golden_apple")) { "the line: $line" }
            awaitCondition("the reward in the ledger") { rig.onServer { rig.runtime.guards.items.held(bob.live).isNotEmpty() } }
            rig.onServer {
                check(count(bob, Items.GOLDEN_APPLE) == 0) { "no room for it" }
                val ground = bob.live.level().getEntitiesOfClass(ItemEntity::class.java, bob.live.boundingBox.inflate(16.0)) { it.item.`is`(Items.GOLDEN_APPLE) }
                check(ground.isEmpty()) { "nothing on the ground: $ground" }
                check(rig.runtime.guards.items.held(bob.live).single().purpose == LedgerPurpose.OWED) { "it waits in his ledger: ${rig.runtime.guards.items.held(bob.live)}" }
                bob.live.inventory.setItem(1, ItemStack.EMPTY)
            }
            awaitCondition("the golden apple, once there is room") { rig.onServer { count(bob, Items.GOLDEN_APPLE) } == 1 }
            rig.checkStakes(view.ref, listOf(bob to InteractionKind.GIFT), emptyList())
            rig.checkActs(view.ref, listOf(bob))
        }
    }

    /**
     * M4 review, finding 13: a challenge whose end passed while the server was down was cancelled, and who had made it lost
     * what they had won. It is won by them now, without penalty for the others, each reward paid once the winner is back.
     */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun aChallengeMadeBeforeAStopIsWonAfterItAndTheRewardPaidOnceBack(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper, 3) { rig, (bob, ann, cid) ->
            val started = rig.call(
                rig.start,
                """{"title": "Avant l'arrêt", "statement": "Le Narrateur a défié trois joueurs de tenir une pomme.", "goal": "obtain",
                   "item": "apple", "players": ["${bob.name}", "${ann.name}", "${cid.name}"], "minutes": 5, "winners": 0,
                   "reward": {"kind": "item", "item": "diamond"}, "penalty": {"punishment": "tiny", "severity": 1}}""",
            )
            check(started.status == ToolStatus.OK) { "the start: ${started.text}" }
            val view = rig.challenges.list().single()
            val saved = rig.onServer {
                bob.live.inventory.add(ItemStack(Items.APPLE))
                rig.second()
                check(rig.challenge(view.ref)!!.participants.map { it.doneAt != null } == listOf(true, false, false)) { "Bob made it" }
                rig.engine.stop()
                bob.close() // he is away when the server comes back
                MechanicsState.decode(MechanicsState.encode(rig.store.get()))
            }

            val again = Rig(helper, StateStore.inMemory(saved), rig.clock, rig.director, rig.sink)
            var back: ServerPlayer? = null
            try {
                rig.onServer {
                    rig.clock.now = Instant.ofEpochMilli(view.endsAt + 10 * 60_000L)
                    again.engine.start(helper.level.server)
                    check(again.challenge(view.ref) != null) { "kept by the startup" }
                    again.tick()
                    check(again.store.get().challenges.isEmpty()) { "won at the first tick" }
                }
                val line = again.awaitEnd(view.ref)
                val expected = "^${view.ref} won by ${bob.name} in \\d+s \\(apple 1/1\\); the server was down when it ended: no penalty · reward: ${bob.name} paid once back online$"
                check(Regex(expected).matches(line)) { "the line: $line" }
                val canon = rig.canon(view.ref)
                check(canon.status == CanonStatus.WON && canon.winners.map { it.uuid } == listOf(bob.uuid)) { "the canon: ${canon.status} ${canon.winners}" }
                rig.onServer {
                    check(near(ann.live.getAttributeValue(Attributes.SCALE), 1.0) && near(cid.live.getAttributeValue(Attributes.SCALE), 1.0)) { "no penalty for the others" }
                }

                back = rig.onServer { rejoin(helper, bob) }
                awaitCondition("Bob's diamond, once back") { rig.onServer { again.second(); count(back!!, Items.DIAMOND) } == 1 }
                // The gift enters his history once given, in the payment's coroutine.
                awaitCondition("Bob's gift in his history") { rig.runtime.memory.store.interactions(bob.uuid, 20).any { it.canon == view.ref && it.kind == InteractionKind.GIFT } }
                again.checkStakes(view.ref, listOf(bob to InteractionKind.GIFT), listOf(ann, cid))
            } finally {
                rig.onServer {
                    again.engine.stop()
                    back?.let { helper.level.server.playerList.remove(it) }
                }
            }
        }
    }

    // ── The rig ──

    /**
     * An engine of the test's own: the challenges' part with the real stakes ([Stakes] through [EngineActions] and every
     * tool of the catalogue), and the bars, on a store in memory; a fake Director, the decisions kept, a clock the test
     * moves. The guards, the memory, the Journal and the tools' limits are the running Narrator's.
     */
    private class Rig(
        val helper: GameTestHelper,
        val store: StateStore = StateStore.inMemory(),
        val clock: TestClock = helper.narrator().clock.let { TestClock(Instant.ofEpochMilli(it.millis()), it.zone) },
        val director: FakeDirector = FakeDirector(clock),
        val sink: KeptDecisions = KeptDecisions(helper.narrator().trace),
    ) {
        val runtime: NarratorRuntime = helper.narrator()
        val bars = Bossbars()
        val services: NarratorServices = object : NarratorServices by runtime {
            override val trace: TraceSink get() = sink
            override val director: DirectorControl get() = this@Rig.director
            override val mechanics: Mechanics get() = this@Rig.mechanics
        }
        val actions = EngineActions(services, ToolCatalog.allTools(services))
        val challenges = Challenges(services, store, bars, Stakes(services, actions), clock = clock)
        val mechanics: Mechanics = RigMechanics(challenges)
        val engine = Engine(services, store, listOf(challenges, DrawnBars(bars, store, runtime.settings.get().ui)), clock)
        val start = StartChallengeTool()
        val end = EndChallengeTool()

        /** [tool] called by the Director's turn with [json], as the model writes it. Off the server thread. */
        suspend fun call(tool: NarratorTool<*>, json: String): ToolResult = ToolRunner(services).invoke(tool, DIRECTOR, json)

        fun decisions(name: String): List<DecisionRecord> = sink.kept.filter { it.kind == DecisionRecord.Kind.MECHANIC && it.decision == name }

        suspend fun canon(ref: Ref) = runtime.memory.store.canon(ref) ?: error("no canon row $ref")

        /** A second of the engine. Server thread. */
        fun second() {
            clock.advance(Duration.ofMillis(1_001))
            engine.tick(helper.level.server)
        }

        /** A second of the engine at [epochMs]. Server thread. */
        fun at(epochMs: Long) {
            clock.now = Instant.ofEpochMilli(epochMs)
            engine.tick(helper.level.server)
        }

        suspend fun <T> onServer(block: () -> T): T = helper.level.server.onServerThread(10_000, block)

        /** Waits for the `CHALLENGE` event of [ref]: its line. */
        suspend fun awaitEnd(ref: Ref): String {
            fun ended() = director.signals.filterIsInstance<DirectorSignal.Mechanic>().lastOrNull { it.kind == MechanicSignal.CHALLENGE && it.line.startsWith("$ref ") }
            awaitCondition("the end of $ref") { ended() != null }
            return ended()!!.line
        }

        /**
         * The stakes of [ref] in the players' history: one interaction of the right kind for each of [paid] (valence 0,
         * canon = the challenge, by the engine, its summary the tool's first line), none for [unpaid].
         */
        suspend fun checkStakes(ref: Ref, paid: List<Pair<TestPlayer, InteractionKind>>, unpaid: List<TestPlayer>) {
            val kinds = setOf(InteractionKind.GIFT, InteractionKind.BLESSING, InteractionKind.PUNISHMENT)
            suspend fun stakesOf(player: TestPlayer): List<Interaction> = runtime.memory.store.interactions(player.uuid, 20).filter { it.canon == ref && it.kind in kinds }
            for ((player, kind) in paid) {
                val stake = stakesOf(player)
                check(stake.size == 1 && stake.single().kind == kind && stake.single().valence == 0 && stake.single().source == Source.ENGINE) {
                    "${player.name}'s ${kind.id}: $stake"
                }
                val stakeWord = if (kind == InteractionKind.PUNISHMENT) "penalty" else "reward"
                check(stake.single().summary.startsWith("challenge $stakeWord: ")) { "its summary: ${stake.single().summary}" }
            }
            for (player in unpaid) check(stakesOf(player).isEmpty()) { "nothing in ${player.name}'s history: ${stakesOf(player)}" }
        }

        /** [player]'s `challenge_result` interactions of [ref]. */
        suspend fun results(player: TestPlayer, ref: Ref): List<Interaction> =
            runtime.memory.store.interactions(player.uuid, 20).filter { it.canon == ref && it.kind == InteractionKind.CHALLENGE_RESULT }

        fun challenge(ref: Ref) = store.get().challenge(ref.id)

        /** A tick of the engine at the clock's time. Server thread. */
        fun tick() = engine.tick(helper.level.server)

        /** One act of the engine per player paid ([players], in order): its `stake` decision, the tool's `action` as that act. */
        fun checkActs(ref: Ref, players: List<TestPlayer>) {
            val stakes = decisions("stake").filter { it.conversationId == "mechanics:$ref" }
            check(stakes.map { it.subject } == players.map { it.name }) { "one act per player: ${stakes.map { it.subject }}" }
            val actions = decisions("action").filter { it.conversationId == "mechanics:$ref" }
            check(actions.map { it.detail!!.getValue("act").jsonPrimitive.long } == stakes.map { -it.id }) { "each tool call is its act's: $actions" }
        }
    }

    /** The engine the tools reach: the rig's challenges, available; nothing else. */
    private class RigMechanics(private val part: Challenges) : Mechanics by DisabledMechanics("the rig has only challenges") {
        override val available = true
        override val unavailableReason: String? = null
        override val challenges: ChallengeControl = object : ChallengeControl by DisabledMechanics("the rig has only challenges").challenges {
            override suspend fun start(spec: ChallengeSpec, caller: Caller): ChallengeView = part.start(spec, caller)

            override suspend fun end(ref: Ref, outcome: ChallengeOutcome, winners: List<UUID>, note: String, author: Author): ChallengeView =
                part.end(ref, outcome, winners, note, author)

            override fun list(): List<ChallengeView> = part.list()
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

    private companion object {
        val DIRECTOR = Caller(Role.DIRECTOR, "director-stakes-gametest", turnId = 4243, taskId = null)

        /** Runs [test] with a [Rig] started on the server thread and [count] players who record their boss bars; all closed however it ends. */
        suspend fun CoroutineScope.withRig(helper: GameTestHelper, count: Int, test: suspend (Rig, List<TestPlayer>) -> Unit) {
            val server = helper.level.server
            val players = server.onServerThread { List(count) { TestPlayer(helper, "sk_", keep = { it is ClientboundBossEventPacket }) } }
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

        /** The boss bars [player]'s client shows: the ids added and not removed since. */
        fun bars(player: TestPlayer): Set<UUID> {
            val shown = LinkedHashSet<UUID>()
            player.packets.filterIsInstance<ClientboundBossEventPacket>().forEach { packet ->
                packet.dispatch(object : ClientboundBossEventPacket.Handler {
                    override fun add(
                        id: UUID, name: net.minecraft.network.chat.Component, progress: Float, color: net.minecraft.world.BossEvent.BossBarColor,
                        overlay: net.minecraft.world.BossEvent.BossBarOverlay, darkenScreen: Boolean, playMusic: Boolean, createWorldFog: Boolean,
                    ) {
                        shown += id
                    }

                    override fun remove(id: UUID) {
                        shown -= id
                    }
                })
            }
            return shown
        }

        fun near(value: Double, expected: Double): Boolean = kotlin.math.abs(value - expected) < 1e-6

        fun count(player: TestPlayer, item: Item): Int = count(player.live, item)

        fun count(player: ServerPlayer, item: Item): Int =
            (0 until player.inventory.containerSize).sumOf { slot -> player.inventory.getItem(slot).let { if (it.`is`(item)) it.count else 0 } }

        /** The inventory's main slots (hot bar included), those `Inventory.add` fills. */
        const val MAIN_SLOTS = 36

        /** [player], who left, joins again as a new player object, as after a restart. Server thread. */
        fun rejoin(helper: GameTestHelper, player: TestPlayer): ServerPlayer {
            val server = helper.level.server
            val profile = GameProfile(player.uuid, player.name)
            val back = ServerPlayer(server, helper.level, profile, ClientInformation.createDefault())
            val connection = Connection(PacketFlow.SERVERBOUND)
            EmbeddedChannel(connection)
            server.playerList.placeNewPlayer(connection, back, CommonListenerCookie.createInitial(profile, false))
            return back
        }

        fun jokers(player: TestPlayer): Int =
            (0 until player.live.inventory.containerSize).sumOf { slot -> player.live.inventory.getItem(slot).let { if (ItemLedger.isJoker(it)) it.count else 0 } }
    }
}
