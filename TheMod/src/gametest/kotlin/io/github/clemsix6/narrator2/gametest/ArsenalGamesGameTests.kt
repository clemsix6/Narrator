package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.core.onServerThread
import io.github.clemsix6.narrator2.director.DirectorControl
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.director.MechanicSignal
import io.github.clemsix6.narrator2.forge.Forge
import io.github.clemsix6.narrator2.forge.ForgeService
import io.github.clemsix6.narrator2.forge.ModuleStatus
import io.github.clemsix6.narrator2.forge.arsenal.ArsenalStore
import io.github.clemsix6.narrator2.forge.host.ModuleThreads
import io.github.clemsix6.narrator2.forge.sdk.EngineActRunner
import io.github.clemsix6.narrator2.forge.sdk.ModuleCreatures
import io.github.clemsix6.narrator2.gametest.ForgeContractsGameTests.Companion.FORGE_ENVIRONMENT
import io.github.clemsix6.narrator2.gametest.GuardPolicyGameTests.Companion.floor
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.mechanics.Bossbars
import io.github.clemsix6.narrator2.mechanics.ChallengeControl
import io.github.clemsix6.narrator2.mechanics.ChallengeOutcome
import io.github.clemsix6.narrator2.mechanics.ChallengeSpec
import io.github.clemsix6.narrator2.mechanics.ChallengeStatus
import io.github.clemsix6.narrator2.mechanics.ChallengeView
import io.github.clemsix6.narrator2.mechanics.DeploySpec
import io.github.clemsix6.narrator2.mechanics.DuelSpec
import io.github.clemsix6.narrator2.mechanics.Engine
import io.github.clemsix6.narrator2.mechanics.EngineActions
import io.github.clemsix6.narrator2.mechanics.GoalKind
import io.github.clemsix6.narrator2.mechanics.ModuleView
import io.github.clemsix6.narrator2.mechanics.RuleKind
import io.github.clemsix6.narrator2.mechanics.StateStore
import io.github.clemsix6.narrator2.mechanics.challenge.Challenges
import io.github.clemsix6.narrator2.mechanics.module.ModuleBook
import io.github.clemsix6.narrator2.mechanics.module.ModuleEngine
import io.github.clemsix6.narrator2.mechanics.module.moduleOnlyTools
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.CanonEntry
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.testing.FakeDirector
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.action.worldActionTools
import io.github.clemsix6.narrator2.tool.speech.speechTools
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.TraceSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.GameType
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-game tests of the arsenal's games (task M5-16, M5 §10, §16.3): `tag` and `simon_says`, built from the mod's own
 * sources by the Forge's real pipeline when it starts (version 0, built-in), deployed by the modules' book on an engine
 * of the test's own (its state in memory, its clock, a challenges' part of its own, the real tools), played by two test
 * players who read their action bars, each refereeing a judged challenge that it settles itself: the winner it names
 * wins the challenge, both canon rows are resolved, and the Director hears both ends. `tag` gives its verdict once the
 * challenge is past its own end (`JUDGING`): `Game.running()` holds until the verdict.
 */
class ArsenalGamesGameTests {
    /**
     * `tag`: the wolf, read on the action bars, has particles above it; the prey comes within reach and, once the wolf has
     * counted to three, is the wolf. The challenge's minute runs out (it waits for a verdict), then the game's two
     * minutes: the first wolf, the wolf for a few seconds only, wins the challenge.
     */
    @GameTest(environment = FORGE_ENVIRONMENT, maxTicks = GAMES_MAX_TICKS)
    fun tagPassesTheRoleOnAndItsLeastWolfWinsTheJudgedChallenge(helper: GameTestHelper) = helper.runScript(GAMES_MAX_TICKS) {
        withRig(helper) { rig, bob, ann ->
            val challenge = rig.challenges.start(judged("Le loup", 1, bob, ann), DIRECTOR)
            val view = rig.deploy("tag", """{"players": ["${bob.name}", "${ann.name}"], "minutes": 2}""", challenge.ref)

            rig.await("a line above each hotbar") { lines(bob).isNotEmpty() && lines(ann).isNotEmpty() }
            val (wolf, prey) = if (lines(bob).last().startsWith("Tu es le loup")) bob to ann else ann to bob
            check(lines(wolf).last() == "Tu es le loup ! Compte jusqu'à trois…") { "the wolf's line: ${lines(wolf)}" }
            check(lines(prey).last().startsWith("Loup : ${wolf.name} · toi : 0s · fin dans ")) { "the prey's line: ${lines(prey)}" }
            rig.await("the particles above the wolf") { rig.actions(view.ref, "particles").isNotEmpty() }
            val particles = rig.actions(view.ref, "particles").first().detail!!
            check(particles.getValue("status").jsonPrimitive.content == "ok" && particles.getValue("args").jsonObject.getValue("particle").jsonPrimitive.content == "angry_villager") {
                "the particles: $particles"
            }

            rig.onServer { prey.live.teleportTo(wolf.live.x + 1.0, wolf.live.y, wolf.live.z) }
            rig.await("the prey turned wolf, once the wolf has counted to three", COUNT_TICKS) {
                lines(prey).last().startsWith("Tu es le loup") && lines(wolf).last().startsWith("Loup : ${prey.name} · ")
            }
            rig.onServer { wolf.live.teleportTo(wolf.live.x - 6.0, wolf.live.y, wolf.live.z) }

            rig.onServer {
                rig.clock.advance(Duration.ofSeconds(70))
                rig.tick()
            }
            rig.await("the challenge past its minute, waiting for its verdict") { rig.challenge(challenge.ref)?.status == ChallengeStatus.JUDGING }
            rig.onServer {
                rig.clock.advance(Duration.ofSeconds(60))
                rig.tick()
            }
            val verdict = "${wolf.name} a été le moins longtemps loup ("
            val ended = rig.awaitEnd(view.ref)
            check(ended.status == CanonStatus.RETIRED && ended.resolution!!.startsWith(verdict)) { "the law's canon: ${ended.status} ${ended.resolution}" }
            val won = rig.awaitChallenge(challenge.ref)
            check(won.status == CanonStatus.WON && won.winners.map { it.uuid } == listOf(wolf.uuid)) { "the challenge's canon: ${won.status} ${won.winners}" }
            check(won.resolution!!.startsWith(verdict)) { "the challenge's verdict: ${won.resolution}" }
            check(rig.lines(MechanicSignal.CHALLENGE).any { it.startsWith("${challenge.ref} won by ${wolf.name}") }) { rig.director.signals.toString() }
            val told = rig.lines(MechanicSignal.MODULE).single()
            check(told.startsWith("${view.ref} tag ended by itself: « $verdict") && told.endsWith(" acts, 0 faults)")) { rig.lines(MechanicSignal.MODULE).toString() }
            rig.report("tag", told)
        }
    }

    /**
     * `simon_says`: the first order shows on both action bars; Ann plays it right, Bob wrong: Bob is out, Ann is the last
     * one in and wins the challenge, and the game ends with its verdict.
     */
    @GameTest(environment = FORGE_ENVIRONMENT, maxTicks = GAMES_MAX_TICKS)
    fun simonSaysPutsAWrongMoveOutAndTheLastOneInWinsTheJudgedChallenge(helper: GameTestHelper) = helper.runScript(GAMES_MAX_TICKS) {
        withRig(helper) { rig, bob, ann ->
            val challenge = rig.challenges.start(judged("Jacques a dit", 10, bob, ann), DIRECTOR)
            val view = rig.deploy("simon_says", """{"players": ["${bob.name}", "${ann.name}"], "rounds": 3}""", challenge.ref)

            rig.await("the rest before the first order") { lines(ann).any { it.startsWith("Prêts ? · ordre suivant dans ") } }
            rig.await("the first order above both hotbars", FIRST_ORDER_TICKS, 30_000) { order(bob) != null && order(ann) != null }
            val order = order(ann)!!
            check(order == order(bob)) { "the same order for both: $order, ${order(bob)}" }
            println("[M5-16] simon_says in the server: the first order is « $order »")
            val moving = rig.onServer { listOf(ann to true, bob to false).filter { (player, right) -> answer(player, order, right) }.map { it.first } }
            var step = 0
            val ended = rig.awaitEnd(view.ref) {
                val dx = if (step++ % 2 == 0) 2.0 else -2.0
                for (player in moving) player.live.teleportTo(player.live.x + dx, player.live.y, player.live.z)
            }
            val verdict = "${ann.name} a tenu jusqu'au bout de Jacques a dit"
            check(ended.status == CanonStatus.RETIRED && ended.resolution == verdict) { "the law's canon after « $order »: ${ended.status} ${ended.resolution}" }
            val won = rig.awaitChallenge(challenge.ref)
            check(won.status == CanonStatus.WON && won.winners.map { it.uuid } == listOf(ann.uuid) && won.resolution == verdict) {
                "the challenge's canon: ${won.status} ${won.winners} ${won.resolution}"
            }
            check(rig.lines(MechanicSignal.CHALLENGE).any { it.startsWith("${challenge.ref} won by ${ann.name}") }) { rig.director.signals.toString() }
            val told = rig.lines(MechanicSignal.MODULE).single()
            check(told.startsWith("${view.ref} simon_says ended by itself: « $verdict » (") && told.endsWith(" acts, 0 faults)")) { rig.lines(MechanicSignal.MODULE).toString() }
            rig.report("simon_says", told)
        }
    }

    /**
     * The test's own engine on the server's runtime: its state in memory, its clock (50 ms a tick), its Director, a
     * challenges' part and a Forge of its own (the real one, with the two games as its only built-in modules), the real
     * tools of the modules' acts. Its creatures are those of the test's area.
     */
    private class Rig(val helper: GameTestHelper, val forge: ForgeService) {
        val runtime = helper.narrator()
        val clock = TestClock(Instant.ofEpochMilli(runtime.clock.millis()), runtime.clock.zone)
        val director = FakeDirector(clock)
        private val sink = KeptDecisions(runtime.trace)
        private val services: NarratorServices = object : NarratorServices by runtime {
            override val trace: TraceSink get() = sink
            override val director: DirectorControl get() = this@Rig.director
            override val forge: Forge get() = this@Rig.forge
        }
        val store = StateStore.inMemory()
        val challenges = Challenges(services, store, Bossbars(), clock = clock)

        /** The challenges' part as the modules see it: start, end and list; no duel nor bet here. */
        private val control = object : ChallengeControl {
            override suspend fun start(spec: ChallengeSpec, caller: Caller): ChallengeView = challenges.start(spec, caller)

            override suspend fun proposeDuel(spec: DuelSpec, caller: Caller): ChallengeView = error("no duel in these tests")

            override suspend fun end(ref: Ref, outcome: ChallengeOutcome, winners: List<UUID>, note: String, author: Author): ChallengeView =
                challenges.end(ref, outcome, winners, note, author)

            override fun answerDuel(player: ServerPlayer, ref: Ref, accept: Boolean): Component = error("no duel in these tests")

            override fun bet(player: ServerPlayer, ref: Ref, on: String): Component = error("no bet in these tests")

            override fun list(): List<ChallengeView> = challenges.list()
        }
        private val actions = EngineActions(services, speechTools(services) + worldActionTools(services) + moduleOnlyTools())
        private val threads = ModuleThreads("gametest-games")
        private val area = helper.bounds.inflate(16.0)
        private val modules = ModuleEngine(
            services, store, threads, EngineActRunner(actions), control, clock = clock,
            area = { entity -> area.contains(entity.position()) && ModuleCreatures.ownerOf(entity).let { it == null || store.snapshot.module(it) != null } },
        )
        private val book = ModuleBook(services, store, modules, clock)
        private val engine = Engine(services, store, listOf(challenges, modules), clock)

        /** What the calls of the module took in the last second, in ms, at each tick (`ModuleView.msPerSecond`). */
        private val seconds = CopyOnWriteArrayList<Double>()

        fun start() = engine.start(helper.level.server)

        /** The engine's ticks so far ([tick]; its seconds jumped by the tests count one each). Server thread; read from any. */
        @Volatile
        var ticks = 0
            private set

        /** A tick of the engine, 50 ms later. Server thread. */
        fun tick() {
            clock.advance(Duration.ofMillis(TICK_MS))
            engine.tick(helper.level.server)
            ticks++
            for (view in book.list()) seconds += view.msPerSecond
        }

        suspend fun <T> onServer(block: () -> T): T = helper.level.server.onServerThread(10_000, block)

        /**
         * Ticks the engine until [condition] holds, [meanwhile] before each check (the acts' coroutines run their tools between
         * the ticks): one tick a check, [ticks] of them and [timeoutMs] of the wall clock spent before it fails ([awaitTicks]).
         * A busy batch makes each check slower, never the games' ticks: `simon_says` gives its first order 200 ticks in, which
         * took 17 s of a budget of 30 s under load (FLAKY-5).
         */
        suspend fun await(what: String, ticks: Int = SLACK_TICKS, timeoutMs: Long = 15_000, meanwhile: () -> Unit = {}, condition: () -> Boolean) =
            awaitTicks(what, ticks, { this.ticks }, timeoutMs) {
                onServer {
                    meanwhile()
                    tick()
                    condition()
                }
            }

        suspend fun deploy(module: String, params: String, challenge: Ref): ModuleView {
            check(forge.arsenal.entry(module, 0)?.let { it.builtIn && it.status == ModuleStatus.READY } == true) { "$module built when the Forge started" }
            val spec = DeploySpec(
                module = module, version = null, params = Json.parseToJsonElement(params) as JsonObject, kind = RuleKind.LAW,
                statement = "Le Narrateur a lancé le jeu $module.", quote = null, minutes = 10, challenge = challenge,
            )
            return book.deploy(spec, DIRECTOR)
        }

        fun challenge(ref: Ref) = store.get().challenge(ref.id)

        /** The `mechanic action` decisions of the acts of deployment [ref] through [tool]. */
        fun actions(ref: Ref, tool: String): List<DecisionRecord> = sink.kept.filter {
            it.kind == DecisionRecord.Kind.MECHANIC && it.decision == EngineActions.ACTION && it.conversationId == "${EngineActions.CONVERSATION_PREFIX}$ref" &&
                it.detail?.get("tool")?.jsonPrimitive?.content == tool
        }

        /** Deployment [ref] ended, by the engine: its canon row, resolved. [meanwhile] runs before each tick until then. */
        suspend fun awaitEnd(ref: Ref, meanwhile: () -> Unit = {}): CanonEntry {
            await("$ref ended", END_TICKS, 30_000, meanwhile) { store.snapshot.module(ref.id) == null }
            return awaitResolved(ref) { sink.kept.any { it.decision == ModuleEngine.ENDED && it.subject == "$ref" } }
        }

        /** Challenge [ref] settled: its canon row, resolved. */
        suspend fun awaitChallenge(ref: Ref): CanonEntry {
            await("$ref settled") { challenge(ref) == null }
            return awaitResolved(ref) { true }
        }

        private suspend fun awaitResolved(ref: Ref, also: () -> Boolean): CanonEntry {
            awaitCondition("the canon row of $ref resolved") { runtime.memory.store.canon(ref)?.resolution != null && also() }
            return runtime.memory.store.canon(ref)!!
        }

        /** The lines of [kind] the Director heard. */
        fun lines(kind: MechanicSignal): List<String> =
            director.signals.filterIsInstance<DirectorSignal.Mechanic>().filter { it.kind == kind }.map { it.line }

        /** The game's numbers in the log of the GameTests (M5-16's handoff quotes them): the time of its calls a second, and how it ended. */
        fun report(module: String, ended: String) {
            val sorted = seconds.sorted()
            val ms = { value: Double -> "%.2f".format(Locale.ROOT, value) }
            println("[M5-16] $module in the server: its calls took ${ms(sorted[sorted.size / 2])} ms a second (median), ${ms(sorted.last())} ms at most; $ended")
        }

        /** What a test leaves running stopped, the engine stopped, its threads closed. */
        suspend fun cleanUp() {
            for (view in book.list()) runCatching { book.stop(view.ref, "Le test est fini.", Author(Source.OPERATOR, operator = "gametest")) }
            onServer { if (modules.running) engine.stop() }
            threads.close()
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
        /** At least a minute of wall-clock time ([runScript]): `simon_says` gives its first order after 10 s of ticks at about real time. */
        const val GAMES_MAX_TICKS = 60_000

        /** A tick of the rig's clock, as on a server at 20 ticks a second. */
        const val TICK_MS = 50L

        /** Engine ticks for what follows an act or a step of a game within a tick or two: a line, particles, a verdict. */
        const val SLACK_TICKS = 100

        /** Engine ticks for the wolf to count to three (3 s, 60 ticks) once the prey is within reach. */
        const val COUNT_TICKS = 60 + SLACK_TICKS

        /** Engine ticks for `simon_says`'s first order: 10 s (200 ticks) after its start, twice that allowed. */
        const val FIRST_ORDER_TICKS = 400

        /** Engine ticks for a game to end once its verdict is due: `simon_says` watches 4 s (80 ticks), then judges. */
        const val END_TICKS = 400

        val DIRECTOR = Caller(Role.DIRECTOR, "director-gametest", turnId = 4242, taskId = null)

        /** A judged challenge of [minutes] for [players]. */
        fun judged(title: String, minutes: Int, vararg players: TestPlayer) = ChallengeSpec(
            title = title, statement = "Le Narrateur a lancé un défi jugé aux joueurs.", goal = GoalKind.JUDGED, players = players.map { it.name },
            minutes = minutes,
        )

        /** The lines above [player]'s hotbar so far, oldest first. */
        fun lines(player: TestPlayer): List<String> =
            player.packets.filterIsInstance<ClientboundSystemChatPacket>().filter { it.overlay() }.map { it.content().string }

        /** The order above [player]'s hotbar (a line without « · »); null while there is none. */
        fun order(player: TestPlayer): String? = lines(player).lastOrNull()?.takeIf { "·" !in it }

        /**
         * [player] answers [order]: obeys Jacques and nobody else when [right], the reverse otherwise; true when that
         * answer is to move during the watch (not freezing). Server thread.
         */
        fun answer(player: TestPlayer, order: String, right: Boolean): Boolean {
            val doIt = order.startsWith("Jacques a dit") == right
            val live = player.live
            val words = order.lowercase()
            when {
                "accroupis" in words -> live.isShiftKeyDown = doIt
                "saute" in words -> if (doIt) {
                    live.teleportTo(live.x, live.y + 1.5, live.z)
                    live.setOnGround(false)
                }
                "ciel" in words -> live.xRot = if (doIt) -80f else 0f
                else -> return !doIt
            }
            return false
        }

        /** Runs [test] with a [Rig] started on the server thread, its Forge started, and two players standing on the test's floor; all closed however it ends. */
        suspend fun CoroutineScope.withRig(helper: GameTestHelper, test: suspend (Rig, TestPlayer, TestPlayer) -> Unit) {
            val server = helper.level.server
            val runtime = helper.narrator()
            val dir = Files.createTempDirectory("narrator2-gametest-games")
            val forge = ForgeService(runtime, ArsenalStore(dir.resolve(ForgeService.ARSENAL_DIR), runtime.clock, builtInNames = listOf("tag", "simon_says")))
            val (bob, ann) = server.onServerThread {
                floor(helper)
                listOf(BlockPos(1, 1, 3), BlockPos(6, 1, 3)).map { at ->
                    TestPlayer(helper, "ag_").also {
                        it.live.setGameMode(GameType.SURVIVAL)
                        it.standAt(at)
                        it.live.setOnGround(true)
                        it.forget()
                    }
                }
            }
            val rig = Rig(helper, forge)
            try {
                forge.start().join()
                rig.onServer { rig.start() }
                test(rig, bob, ann)
            } finally {
                rig.cleanUp()
                forge.stop()
                server.onServerThread {
                    bob.close()
                    ann.close()
                }
            }
        }
    }
}
