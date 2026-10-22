package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.onServerThread
import io.github.clemsix6.narrator2.director.DirectorControl
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.director.MechanicSignal
import io.github.clemsix6.narrator2.forge.Forge
import io.github.clemsix6.narrator2.forge.ForgeService
import io.github.clemsix6.narrator2.forge.ModuleStatus
import io.github.clemsix6.narrator2.forge.api.Limits
import io.github.clemsix6.narrator2.forge.arsenal.ArsenalStore
import io.github.clemsix6.narrator2.forge.host.ModuleThreads
import io.github.clemsix6.narrator2.forge.sdk.EngineActRunner
import io.github.clemsix6.narrator2.gametest.ForgeContractsGameTests.Companion.FORGE_ENVIRONMENT
import io.github.clemsix6.narrator2.gametest.GuardPolicyGameTests.Companion.floor
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.mechanics.DeploySpec
import io.github.clemsix6.narrator2.mechanics.Engine
import io.github.clemsix6.narrator2.mechanics.EngineActions
import io.github.clemsix6.narrator2.mechanics.MechanicsState
import io.github.clemsix6.narrator2.mechanics.ModuleView
import io.github.clemsix6.narrator2.mechanics.RuleKind
import io.github.clemsix6.narrator2.mechanics.StateStore
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
import io.github.clemsix6.narrator2.tool.ToolLimits
import io.github.clemsix6.narrator2.tool.action.playerActionTools
import io.github.clemsix6.narrator2.tool.action.punishTools
import io.github.clemsix6.narrator2.tool.action.worldActionTools
import io.github.clemsix6.narrator2.tool.speech.speechTools
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.TraceSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.level.levelgen.Heightmap
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.hypot

/**
 * In-game tests of the built-in modules of the world (task M5-17, M5 §10, §16.3), in the Forge's batch: `hot_cold`,
 * `floor_is_lava` and `gravity_storm`, each built from the mod's resources as a Forge of the test's own starts (the real
 * pipeline: ECJ, the guard, the manifest, the rewriting), deployed by the book of `deploy_module` on an engine of the
 * test's own ([Rig]: its state in memory; its clock and its ticks driven by the test, so that minutes of play pass in a
 * few seconds), on real test players, through the real tools and guards. A refusal of the guards is handled in each of
 * the two modules that act on players; `hot_cold` finds its spot again after a restart. The times go to the log
 * (`M5-17 in the server: …`).
 */
class ArsenalWorldGameTests {
    /**
     * `hot_cold`: its spot is hidden on the surface near its player and kept in the storage, its action bar says how warm
     * it is; the engine stops as the server does, its state re-encoded, and on a new engine the module starts again with
     * the same spot: the player who stands on it wins, fireworks, and the module ends itself (M5 §6.7).
     */
    @GameTest(environment = FORGE_ENVIRONMENT, maxTicks = MAX_TICKS)
    fun hotColdKeepsItsSpotThroughARestartAndWhoReachesItWins(helper: GameTestHelper) = helper.runScript(MAX_TICKS) {
        withRig(helper, HOT_COLD, 1) { rig, players ->
            val bob = players.single()
            val view = rig.deploy(HOT_COLD, """{"players": ["${bob.name}"], "radius": 20}""")
            rig.playUntil("its spot kept in the storage") { "x" in rig.storage(view.ref) }
            val stored = rig.storage(view.ref)
            val spot = BlockPos(stored.getValue("x").toInt(), stored.getValue("y").toInt(), stored.getValue("z").toInt())
            val feet = rig.onServer { bob.live.position() }
            val surface = rig.onServer { helper.level.getHeight(Heightmap.Types.MOTION_BLOCKING, spot.x, spot.z) }
            check(stored["dimension"] == "overworld" && spot.y == surface) { "on the surface (World.surface): $stored, the height there $surface" }
            val away = hypot(spot.x + 0.5 - feet.x, spot.z + 0.5 - feet.z)
            check(away in 9.0..22.0) { "hidden 10 to 20 blocks from its player: $away" }
            rig.playUntil("a hint in its action bar") { bob.overlays().any { it.startsWith("Tu ") } }

            val saved = rig.onServer {
                rig.second()
                rig.stop()
                MechanicsState.decode(MechanicsState.encode(rig.store.get()))
            }
            check(saved.module(view.ref.id)?.storage == stored) { "its spot saved with the world: ${saved.modules}" }

            val again = Rig(helper, rig.forge, saved, rig.clock, rig.director, rig.sink)
            try {
                again.onServer { again.start() }
                again.playUntil("hot_cold started again") { again.decisions(ModuleEngine.RESTARTED).isNotEmpty() }
                again.playUntil("its line after the restart") { bob.chatLines().any { "n'a pas bougé" in it } }
                check(again.storage(view.ref) == stored) { "the same spot after the restart: ${again.storage(view.ref)}" }

                again.onServer { bob.live.teleportTo(spot.x + 0.5, spot.y.toDouble(), spot.z + 0.5) }
                val ended = again.awaitEnd(view.ref)
                check(ended.resolution == "${bob.name} a trouvé le trésor caché.") { "${ended.resolution}" }
                check(again.actions(view.ref).any { it.startsWith("fireworks ") }) { "fireworks over the spot (a test area has no open ground for them): ${again.actions(view.ref)}" }
                check(again.moduleLines().single().startsWith("${view.ref} hot_cold ended by itself: « ${bob.name} a trouvé")) { again.moduleLines().toString() }
                Log.forge.info("M5-17 in the server: hot_cold ${rig.measures} before the restart, ${again.measures} after it")
            } finally {
                again.cleanUp()
            }
        }
    }

    /**
     * `floor_is_lava`: the stone its players stand on is lava; Bob stands still on it and burns (sparks, burns kept in the
     * storage), Ann jumps and never burns. Bob is protected: at the end, the guards refuse his forfeit (`DENIED
     * (protected)`), and the module says so and ends without a second hostile act.
     */
    @GameTest(environment = FORGE_ENVIRONMENT, maxTicks = MAX_TICKS)
    fun floorIsLavaBurnsWhoStandsOnItAndLetsARefusedForfeitGo(helper: GameTestHelper) = helper.runScript(MAX_TICKS) {
        withRig(helper, FLOOR_IS_LAVA, 2) { rig, (bob, ann) ->
            rig.onServer {
                bob.live.setOnGround(true)
                ann.live.setOnGround(false)
            }
            val scale = rig.onServer { bob.live.getAttributeValue(Attributes.SCALE) }
            rig.runtime.guards.setProtected(bob.uuid, bob.name, on = true)
            try {
                val view = rig.deploy(FLOOR_IS_LAVA, """{"players": ["${bob.name}", "${ann.name}"], "minutes": 2}""")
                rig.playUntil("the stone is lava") { rig.actions(view.ref).any { it.startsWith("show_title ok") } }
                rig.playUntil("Bob burns") { (rig.storage(view.ref)["burns.${bob.name}"]?.toInt() ?: 0) >= 1 }
                check("burns.${ann.name}" !in rig.storage(view.ref)) { "Ann, in the air, never burns: ${rig.storage(view.ref)}" }
                rig.playUntil("sparks under Bob") { rig.actions(view.ref).any { it.startsWith("particles ok") } }

                rig.play(2 * 60)
                val ended = rig.awaitEnd(view.ref)
                check(ended.resolution!!.startsWith("${bob.name} a été le plus brûlé (") && ended.resolution!!.endsWith("son gage a été refusé.")) { "${ended.resolution}" }
                val punish = rig.actions(view.ref).filter { it.startsWith("punish") }
                check(punish.size == 1 && punish.single().startsWith("punish denied: DENIED (protected)")) { "one forfeit, refused by the guards: $punish" }
                check(bob.chatLines().any { "tombe à l'eau" in it }) { "the refusal said in the chat: ${bob.chatLines()}" }
                check(rig.onServer { bob.live.getAttributeValue(Attributes.SCALE) } == scale) { "Bob kept his size" }
                Log.forge.info("M5-17 in the server: floor_is_lava ${rig.measures}")
            } finally {
                rig.runtime.guards.setProtected(bob.uuid, bob.name, on = false)
            }
        }
    }

    /**
     * `gravity_storm` over a protected Bob and Ann: the first wave lifts Ann (the guards spare Bob); the next waves find
     * Bob protected and Ann's gravity resting (`same_punishment`), so they pass: the Director is told, and after the
     * last one the module ends itself.
     */
    @GameTest(environment = FORGE_ENVIRONMENT, maxTicks = MAX_TICKS)
    fun gravityStormLiftsWhomTheGuardsLetThroughAndOtherwiseWaits(helper: GameTestHelper) = helper.runScript(MAX_TICKS) {
        withRig(helper, GRAVITY_STORM, 2) { rig, (bob, ann) ->
            rig.onServer { ann.makeVulnerable() }
            val gravity = rig.onServer { ann.live.getAttributeBaseValue(Attributes.GRAVITY) }
            rig.runtime.guards.setProtected(bob.uuid, bob.name, on = true)
            try {
                val view = rig.deploy(GRAVITY_STORM, """{"players": ["${bob.name}", "${ann.name}"], "waves": 3}""")
                rig.playUntil("Ann floats at the first wave") { rig.onServer { ann.live.getAttributeValue(Attributes.GRAVITY) } < gravity }

                rig.playUntil("the second wave passes, told to the Director", maxSeconds = 60) { rig.moduleLines().any { "says: « La vague 2" in it } }
                val told = rig.moduleLines().single { "says:" in it }
                check("les garde-fous ont épargné" in told && bob.name in told && ann.name in told) { told }

                val ended = rig.awaitEnd(view.ref, maxSeconds = 60)
                check(ended.resolution == "La tempête de gravité est passée : ${ann.name} a flotté.") { "${ended.resolution}" }
                val punish = rig.actions(view.ref).filter { it.startsWith("punish") }
                check(punish.count { it.startsWith("punish ok") } == 1) { "Ann lifted once: $punish" }
                check(punish.any { it.startsWith("punish denied: DENIED (protected)") }) { "Bob spared, protected: $punish" }
                check(punish.any { it.startsWith("punish denied: DENIED (same_punishment)") }) { "Ann spared, her gravity resting: $punish" }
                Log.forge.info("M5-17 in the server: gravity_storm ${rig.measures}")
            } finally {
                rig.runtime.guards.setProtected(bob.uuid, bob.name, on = false)
            }
        }
    }

    /**
     * The modules' engine of one test, on the server's runtime: its state in memory, its own clock, module threads,
     * speech limits and Director, the test's Forge, the real tools and guards. These modules have no creature: the
     * engine looks after none, so it never touches another test's.
     */
    private class Rig(
        val helper: GameTestHelper,
        val forge: Forge,
        initial: MechanicsState = MechanicsState.EMPTY,
        val clock: TestClock = TestClock(Instant.ofEpochMilli(helper.narrator().clock.millis()), helper.narrator().clock.zone),
        val director: FakeDirector = FakeDirector(clock),
        val sink: KeptDecisions = KeptDecisions(helper.narrator().trace),
    ) {
        val runtime = helper.narrator()

        /** Speech limits of its own, on its clock: minutes pass here in seconds, and the batch's other tests keep theirs. */
        private val limits = ToolLimits(clock)

        val services: NarratorServices = object : NarratorServices by runtime {
            override val trace: TraceSink get() = sink
            override val director: DirectorControl get() = this@Rig.director
            override val forge: Forge get() = this@Rig.forge
            override val toolLimits: ToolLimits get() = limits
        }
        val store = StateStore.inMemory(initial)
        private val actions = EngineActions(services, speechTools(services) + playerActionTools(services) + worldActionTools(services) + punishTools(services) + moduleOnlyTools())
        private val threads = ModuleThreads("gametest-arsenal")
        val modules = ModuleEngine(services, store, threads, EngineActRunner(actions), runtime.mechanics.challenges, clock = clock, area = { false })
        val book = ModuleBook(services, store, modules, clock)
        private val engine = Engine(services, store, listOf(modules), clock)

        private var longestTickNanos = 0L
        private var mostMsPerSecond = 0.0
        private var deployMs = 0L

        /** What was measured: the deployment, the longest tick of the engine, the most time of module calls in an engine second. */
        val measures: String
            get() = "deployed in $deployMs ms, longest engine tick %.2f ms, at most %.2f ms of its calls in an engine second".format(longestTickNanos / 1e6, mostMsPerSecond)

        /** Starts the engine. Server thread. */
        fun start() = engine.start(helper.level.server)

        /** Stops the engine as the server stops. Server thread. */
        fun stop() = engine.stop()

        /** The engine's ticks so far. Server thread; read from any. */
        @Volatile
        var ticks = 0
            private set

        /** A tick of the engine, timed. Server thread. */
        fun tick() {
            val began = System.nanoTime()
            engine.tick(helper.level.server)
            ticks++
            longestTickNanos = maxOf(longestTickNanos, System.nanoTime() - began)
        }

        /** A second of the engine at once. Server thread. */
        fun second() {
            clock.advance(Duration.ofMillis(1_001))
            tick()
        }

        suspend fun <T> onServer(block: () -> T): T = helper.level.server.onServerThread(10_000, block)

        /** [seconds] seconds of play: a tick of the engine each 50 ms of its clock, a second of ticks at a time on the server thread. */
        suspend fun play(seconds: Int) = repeat(seconds) {
            onServer {
                repeat(Limits.TICKS_PER_SECOND) {
                    clock.advance(Duration.ofMillis(TICK_MS))
                    tick()
                }
                book.list().forEach { mostMsPerSecond = maxOf(mostMsPerSecond, it.msPerSecond) }
            }
        }

        /**
         * Plays second after second until [condition] holds, [maxSeconds] of play at most; then only ticks (the acts'
         * coroutines run their tools meanwhile). It fails once the [maxSeconds] were played and [AFTER_PLAY_TICKS] ticked
         * after them, and 15 s of the wall clock passed ([awaitTicks]): a busy batch makes each second of play slower, never
         * shorter, and `gravity_storm`'s waves 40 s of play apart took a quarter of a budget of 15 s under load (FLAKY-5).
         */
        suspend fun playUntil(what: String, maxSeconds: Int = 20, condition: suspend () -> Boolean) {
            var played = 0
            awaitTicks(what, maxSeconds * Limits.TICKS_PER_SECOND + AFTER_PLAY_TICKS, { ticks }) {
                if (played++ < maxSeconds) play(1) else onServer { tick() }
                condition()
            }
        }

        suspend fun deploy(module: String, params: String): ModuleView {
            val began = System.nanoTime()
            val view = book.deploy(
                DeploySpec(
                    module = module, version = null, params = Json.parseToJsonElement(params) as JsonObject, kind = RuleKind.LAW,
                    statement = "Le Narrateur a lancé le module $module.", quote = null, minutes = 10, challenge = null,
                ),
                DIRECTOR,
            )
            deployMs = (System.nanoTime() - began) / 1_000_000
            return view
        }

        /** The storage of deployment [ref] as the engine last saved it in its state (once a second); empty once it ended. */
        fun storage(ref: Ref): Map<String, String> = store.snapshot.module(ref.id)?.storage.orEmpty()

        /**
         * [ref], a law, ended by itself (`ctx.end`): its decision `module_ended` says `fulfilled`, and the engine retired its
         * canon row with the module's words (M5 §6.6).
         */
        suspend fun awaitEnd(ref: Ref, maxSeconds: Int = 20): CanonEntry {
            playUntil("$ref ended", maxSeconds) { store.snapshot.module(ref.id) == null }
            awaitCondition("$ref retired by the engine") { runtime.memory.store.canon(ref)?.status == CanonStatus.RETIRED && sink.kept.any { it.decision == ModuleEngine.ENDED && it.subject == "$ref" } }
            val ending = decisions(ModuleEngine.ENDED).single { it.subject == "$ref" }.detail!!.getValue(ModuleEngine.ENDING).jsonPrimitive.content
            check(ending == "fulfilled") { "$ref ended by itself: $ending" }
            return runtime.memory.store.canon(ref)!!
        }

        fun decisions(name: String): List<DecisionRecord> = sink.kept.filter { it.kind == DecisionRecord.Kind.MECHANIC && it.decision == name }

        /** The tool calls of deployment [ref]'s acts, as their decisions say them: `punish denied: DENIED (protected): …`. */
        fun actions(ref: Ref): List<String> =
            decisions(EngineActions.ACTION).filter { it.detail?.get("ref")?.jsonPrimitive?.content == ref.toString() }.mapNotNull { it.reason }

        fun moduleLines(): List<String> = director.signals.filterIsInstance<DirectorSignal.Mechanic>().filter { it.kind == MechanicSignal.MODULE }.map { it.line }

        /** The modules left running stopped, the engine stopped, its threads closed. */
        suspend fun cleanUp() {
            for (view in book.list()) runCatching { book.stop(view.ref, "Le test est fini.", Author(Source.OPERATOR, operator = "gametest")) }
            onServer { if (modules.running) stop() }
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
        const val HOT_COLD = "hot_cold"
        const val FLOOR_IS_LAVA = "floor_is_lava"
        const val GRAVITY_STORM = "gravity_storm"

        /** At least 40 s of wall-clock time (see [ASYNC_MAX_TICKS]): two minutes of play, and a restart. */
        const val MAX_TICKS = 4 * ASYNC_MAX_TICKS

        const val TICK_MS = 1_000L / Limits.TICKS_PER_SECOND

        /** Ticks of the engine, one a check, left to the acts' tools once a wait's seconds of play are over ([Rig.playUntil]). */
        const val AFTER_PLAY_TICKS = 100

        val DIRECTOR = Caller(Role.DIRECTOR, "director-gametest", turnId = 4243, taskId = null)

        /** The lines this player's action bar showed. */
        fun TestPlayer.overlays(): List<String> =
            packets.filterIsInstance<ClientboundSystemChatPacket>().filter { it.overlay() }.map { it.content().string }

        fun TestPlayer.chatLines(): List<String> = chat().map { it.content().string }

        /**
         * Runs [test] with a Forge of its own that builds the built-in module [module] from the mod's resources as it
         * starts, a [Rig] started on the server thread, and [count] test players standing on the test's stone floor; all
         * closed however it ends.
         */
        suspend fun CoroutineScope.withRig(helper: GameTestHelper, module: String, count: Int, test: suspend (Rig, List<TestPlayer>) -> Unit) {
            val runtime = helper.narrator()
            val server = helper.level.server
            val forge = ForgeService(runtime, ArsenalStore(Files.createTempDirectory("narrator2-gametest-arsenal"), runtime.clock, builtInNames = listOf(module)))
            val players = server.onServerThread {
                floor(helper)
                List(count) { i -> TestPlayer(helper, "w${i}_").also { it.standAt(BlockPos(3 + 2 * i, 1, 3 + 2 * i)) } }
            }
            try {
                val began = System.nanoTime()
                forge.start().join()
                val built = forge.arsenal.entry(module, 0)
                check(built != null && built.builtIn && built.status == ModuleStatus.READY) { "$module built as the Forge starts: $built" }
                Log.forge.info("M5-17 in the server: $module built as the Forge started in ${(System.nanoTime() - began) / 1_000_000} ms")
                val rig = Rig(helper, forge)
                try {
                    rig.onServer { rig.start() }
                    test(rig, players)
                } finally {
                    rig.cleanUp()
                }
            } finally {
                forge.stop()
                server.onServerThread { players.forEach { it.close() } }
            }
        }
    }
}
