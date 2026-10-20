package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.core.onServerThread
import io.github.clemsix6.narrator2.director.DirectorControl
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.director.MechanicSignal
import io.github.clemsix6.narrator2.forge.Arsenal
import io.github.clemsix6.narrator2.forge.ArsenalEntry
import io.github.clemsix6.narrator2.forge.Built
import io.github.clemsix6.narrator2.forge.Forge
import io.github.clemsix6.narrator2.forge.ForgeRejected
import io.github.clemsix6.narrator2.forge.ForgeStatus
import io.github.clemsix6.narrator2.forge.ModuleStatus
import io.github.clemsix6.narrator2.forge.TestReport
import io.github.clemsix6.narrator2.forge.arsenal.Manifest
import io.github.clemsix6.narrator2.forge.compile.EcjCompiler
import io.github.clemsix6.narrator2.forge.sandbox.BytecodeGuard
import io.github.clemsix6.narrator2.forge.sandbox.Instrumenter
import io.github.clemsix6.narrator2.forge.sdk.EngineActRunner
import io.github.clemsix6.narrator2.forge.sdk.ModuleCreatures
import io.github.clemsix6.narrator2.forge.host.ModuleThreads
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
import net.minecraft.network.chat.ChatType
import net.minecraft.network.chat.PlayerChatMessage
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.Mob
import net.minecraft.world.phys.AABB
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-game tests of the modules' engine (task M5-11, M5 §16.3), in the Forge's batch. Each test deploys its modules on an
 * engine of its own ([Rig]: its state in memory, the server's runtime, a Forge assembled from the real components — ECJ,
 * the guard, the manifest, the rewriting — and the real host, SDK, tools and guards), from `deploy_module` to its end:
 * a module that ends itself on a fact of the real bus; a faulty module disabled, its creatures gone; a restart with its
 * state re-encoded (`restarted`, its storage back, its creature found again, a version that no longer builds); and the
 * other ends, each with its canon, its decision and its line to the Director.
 */
class ModuleEngineGameTests {
    /** A module that summons two chickens around its player, and ends when its player says « la fin » (M5 §6.6, fulfilled). */
    @GameTest(environment = FORGE_ENVIRONMENT, maxTicks = ASYNC_MAX_TICKS)
    fun aModuleRunsFromItsDeploymentToItsOwnEnd(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper) { rig, bob ->
            rig.forge.add("flock", FLOCK)
            val view = rig.deploy("flock", """{"player":"${bob.name}"}""", RuleKind.PLAN)
            val canon = rig.runtime.memory.store.canon(view.ref)
            check(canon?.status == CanonStatus.ACTIVE && canon.players.map { it.uuid } == listOf(bob.uuid)) { "its canon row: $canon" }
            check(rig.decisions(ModuleEngine.STARTED).single().subject == view.ref.toString()) { "its decision started" }

            rig.await("its two chickens, its own") { rig.creatures(view.ref).size == 2 }
            val chickens = rig.onServer { rig.creatures(view.ref) }
            check(rig.decisions("act").isNotEmpty()) { "its summoning, an act of the engine" }

            rig.onServer { say(helper.level.server, bob, "c'est la fin") }
            val ended = rig.awaitEnd(view.ref, CanonStatus.FULFILLED)
            check(ended.resolution == "La basse-cour est rentrée.") { "the canon resolved with its words: ${ended.resolution}" }
            check(rig.endings() == listOf("fulfilled")) { rig.endings().toString() }
            check(rig.moduleLines().single().startsWith("${view.ref} flock ended by itself: « La basse-cour est rentrée. »")) { rig.moduleLines().toString() }
            rig.await("its chickens gone with it") { chickens.none { it.isAlive && !it.isRemoved } }
        }
    }

    /** A module that summons two chickens, then spins in a timer: disabled at its second fault, its chickens gone (M5 §3.7). */
    @GameTest(environment = FORGE_ENVIRONMENT, maxTicks = ASYNC_MAX_TICKS)
    fun aFaultyModuleIsDisabledAndItsCreaturesGo(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper) { rig, bob ->
            rig.forge.add("spinner", SPINNER)
            val view = rig.deploy("spinner", """{"player":"${bob.name}"}""", RuleKind.PLAN)
            rig.await("its two chickens") { rig.creatures(view.ref).size == 2 || rig.store.get().modules.isEmpty() }
            val chickens = rig.onServer { rig.creatures(view.ref) }

            val ended = rig.awaitEnd(view.ref, CanonStatus.CANCELLED)
            // Its spin is stopped by its meter at 20 ms, and the second such fault disables it. On a busy server, the
            // meter's unwinding may take longer than the host's 5 ms margin: that call is then abandoned, which disables
            // it at once (FLAKY-4). Either way a limit in its timer, at most two faults, and only the last one disables.
            val resolution = ended.resolution!!
            val faults = rig.decisions(ModuleEngine.FAULT)
            val abandoned = "and was abandoned" in resolution
            check(resolution.startsWith(if (abandoned) "disabled: limit in timer: time budget: one call ran past " else "disabled: limit in timer: time budget at Spinner.java:")) { resolution }
            check(faults.size == 2 || (abandoned && faults.size == 1)) { "two faults, or one abandon: ${faults.map { it.reason }}" }
            check(rig.endings() == listOf("disabled")) { rig.endings().toString() }
            check(rig.moduleLines().single().let { it.startsWith("${view.ref} spinner disabled: limit in timer: ") && it.endsWith("; ask the forger to fix it") }) {
                rig.moduleLines().toString()
            }
            check(chickens.size == 2) { "it had its chickens: $chickens" }
            rig.await("its chickens gone") { chickens.none { it.isAlive && !it.isRemoved } }
        }
    }

    /**
     * A restart (M5 §6.7): the engine stops as the server does (`stop(SERVER_STOPPING)`, its chicken left in the world),
     * its state re-encoded, a new engine on it: the module starts again with `restarted()`, its storage back and its
     * chicken its own again; a version whose source changed meanwhile ends `restart_failed`.
     */
    @GameTest(environment = FORGE_ENVIRONMENT, maxTicks = ASYNC_MAX_TICKS)
    fun aModuleLivesThroughARestart(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper) { rig, bob ->
            rig.forge.add("keeper", KEEPER)
            rig.forge.add("fickle", FICKLE)
            val kept = rig.deploy("keeper", """{"player":"${bob.name}"}""", RuleKind.LAW, minutes = 60)
            val fickle = rig.deploy("fickle", "{}", RuleKind.PLAN, minutes = 60)
            rig.await("its chicken") { rig.creatures(kept.ref).size == 1 }
            val chicken = rig.onServer { rig.creatures(kept.ref).single() }

            val saved = rig.onServer {
                rig.second()
                rig.stop()
                MechanicsState.decode(MechanicsState.encode(rig.store.get()))
            }
            check(saved.module(kept.ref.id)?.storage == mapOf("word" to "poule", "stopped" to "SERVER_STOPPING")) { "saved with the world: ${saved.modules}" }
            check(chicken.isAlive && ModuleCreatures.ownerOf(chicken) == kept.ref.id) { "its chicken stays in the world, marked" }
            rig.forge.edit("fickle", FICKLE.replace("Rien.", "Autre chose."))

            val again = Rig(helper, saved, rig.forge, rig.clock, rig.director, rig.sink)
            try {
                again.onServer { again.start() }
                again.await("the keeper started again") { again.decisions(ModuleEngine.RESTARTED).isNotEmpty() }
                again.onServer { again.second() }
                val back = again.store.get().module(kept.ref.id)?.storage
                check(back?.get("back") == "1 poule") { "restarted(), its storage back and its chicken its own: $back" }
                check(again.creatures(kept.ref) == listOf(chicken)) { "its chicken found again" }

                val failed = again.awaitEnd(fickle.ref, CanonStatus.CANCELLED)
                check(failed.resolution == "could not restart: the source of fickle v1 no longer matches the one deployed") { "${failed.resolution}" }
                check("${fickle.ref} fickle could not restart: the source of fickle v1 no longer matches the one deployed" in again.moduleLines()) { again.moduleLines().toString() }

                again.book.stop(kept.ref, "Le test est fini.", Author(Source.OPERATOR, operator = "gametest"))
                again.awaitEnd(kept.ref, CanonStatus.RETIRED)
                check(again.endings().toSet() == setOf("restart_failed", "removed")) { again.endings().toString() }
                again.await("its chicken gone") { !chicken.isAlive || chicken.isRemoved }
            } finally {
                again.cleanUp()
            }
        }
    }

    /** The other ends of M5 §6.6, each with its canon, its decision and its line: expired, stopped by the Director, the switch. */
    @GameTest(environment = FORGE_ENVIRONMENT, maxTicks = ASYNC_MAX_TICKS)
    fun eachEndHasItsCanonItsDecisionAndItsLine(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        withRig(helper) { rig, _ ->
            rig.forge.add("idler", IDLER)
            val brief = rig.deploy("idler", "{}", RuleKind.PLAN, minutes = 5)
            val removed = rig.deploy("idler", "{}", RuleKind.LAW, minutes = 60)

            rig.book.stop(removed.ref, "Le Narrateur a renvoyé l'oisif.", Author(Source.DIRECTOR, turnId = 7, conversationId = "director-gametest"))
            check(rig.awaitEnd(removed.ref, CanonStatus.RETIRED).resolution == "Le Narrateur a renvoyé l'oisif.")
            check(rig.moduleLines().isEmpty()) { "the Director's own stop is not told to him: ${rig.moduleLines()}" }

            rig.onServer {
                rig.clock.advance(Duration.ofMinutes(5))
                rig.second()
            }
            check(rig.awaitEnd(brief.ref, CanonStatus.EXPIRED).resolution == ModuleEngine.EXPIRED_NOTE)
            check(rig.moduleLines() == listOf("${brief.ref} idler ended: its time is up (0 acts, 0 faults)")) { rig.moduleLines().toString() }

            val switched = rig.deploy("idler", "{}", RuleKind.LAW, minutes = 60)
            val stopped = rig.book.halt("fin du test", "gametest")
            check(stopped.map { it.ref } == listOf(switched.ref) && rig.store.get().forgeHalt?.by == "gametest") { "the switch: $stopped" }
            check(rig.awaitEnd(switched.ref, CanonStatus.RETIRED).resolution == ModuleEngine.FORGE_OFF_NOTE)
            check(rig.moduleLines().last() == "the Forge was switched off by an operator: ${switched.ref} stopped") { rig.moduleLines().toString() }
            check(rig.decisions(ModuleBook.HALTED, DecisionRecord.Kind.FORGE).size == 1)
            rig.book.resume("gametest")
            check(rig.endings() == listOf("removed", "expired", "forge_off")) { rig.endings().toString() }
        }
    }

    /**
     * The modules' engine of one test on the server's runtime: its state in memory, its own clock and module threads, its
     * Director and Forge for the test, the real tools and guards. Its creatures are those in the test's area.
     */
    private class Rig(
        val helper: GameTestHelper,
        initial: MechanicsState = MechanicsState.EMPTY,
        val forge: TestForge = TestForge(helper.narrator().clock),
        val clock: TestClock = TestClock(Instant.ofEpochMilli(helper.narrator().clock.millis()), helper.narrator().clock.zone),
        val director: FakeDirector = FakeDirector(clock),
        val sink: KeptDecisions = KeptDecisions(helper.narrator().trace),
    ) {
        val runtime = helper.narrator()
        val services: NarratorServices = object : NarratorServices by runtime {
            override val trace: TraceSink get() = sink
            override val director: DirectorControl get() = this@Rig.director
            override val forge: Forge get() = this@Rig.forge
        }
        val store = StateStore.inMemory(initial)
        private val actions = EngineActions(services, speechTools(services) + playerActionTools(services) + worldActionTools(services) + punishTools(services) + moduleOnlyTools())
        private val threads = ModuleThreads("gametest-engine")
        private val area: AABB = helper.bounds.inflate(16.0)

        /**
         * The engine looks after the creatures of its own deployments only (and the wild ones of the test's area): the
         * tests of the batch run side by side, and a creature another test's deployment holds must never be given back by
         * this one as it starts.
         */
        val modules = ModuleEngine(
            services, store, threads, EngineActRunner(actions), runtime.mechanics.challenges, clock = clock,
            area = { entity -> area.contains(entity.position()) && ModuleCreatures.ownerOf(entity).let { it == null || store.snapshot.module(it) != null } },
        )
        val book = ModuleBook(services, store, modules, clock)
        private val engine = Engine(services, store, listOf(modules), clock)

        /** Starts the engine (the creatures already loaded come back to their deployments). Server thread. */
        fun start() = engine.start(helper.level.server)

        /** Stops the engine as the server stops. Server thread. */
        fun stop() = engine.stop()

        /** A tick of the engine. Server thread. */
        fun tick() = engine.tick(helper.level.server)

        /** A second of the engine. Server thread. */
        fun second() {
            clock.advance(Duration.ofMillis(1_001))
            tick()
        }

        suspend fun <T> onServer(block: () -> T): T = helper.level.server.onServerThread(10_000, block)

        /** Ticks the engine until [condition] holds (the acts' coroutines run their tools meanwhile). */
        suspend fun await(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) = awaitCondition(what, timeoutMs) { onServer { tick(); condition() } }

        suspend fun deploy(module: String, params: String, kind: RuleKind, minutes: Int = 10): ModuleView = book.deploy(
            DeploySpec(
                module = module, version = null, params = Json.parseToJsonElement(params) as JsonObject, kind = kind,
                statement = "Le Narrateur a lancé le module $module.", quote = null, minutes = minutes, challenge = null,
            ),
            DIRECTOR,
        )

        /** The creatures of deployment [ref] in the test's area. Server thread. */
        fun creatures(ref: Ref): List<Mob> = helper.level.getEntitiesOfClass(Mob::class.java, area) { ModuleCreatures.ownerOf(it) == ref.id }

        /** [ref] ended: its canon row closed [status] by the engine, its decision written. */
        suspend fun awaitEnd(ref: Ref, status: CanonStatus): CanonEntry {
            await("$ref closed $status") { store.snapshot.module(ref.id) == null }
            awaitCondition("$ref closed $status") { runtime.memory.store.canon(ref)?.status == status && sink.kept.any { it.decision == ModuleEngine.ENDED && it.subject == "$ref" } }
            return runtime.memory.store.canon(ref)!!
        }

        fun decisions(name: String, kind: DecisionRecord.Kind = DecisionRecord.Kind.MECHANIC): List<DecisionRecord> =
            sink.kept.filter { it.kind == kind && it.decision == name }

        /** How its deployments ended, in order. */
        fun endings(): List<String> = decisions(ModuleEngine.ENDED).map { it.detail!!.getValue(ModuleEngine.ENDING).jsonPrimitive.content }

        fun moduleLines(): List<String> = director.signals.filterIsInstance<DirectorSignal.Mechanic>().filter { it.kind == MechanicSignal.MODULE }.map { it.line }

        /** The modules left running stopped, the engine stopped, its threads and compiler closed. */
        suspend fun cleanUp() {
            for (view in book.list()) runCatching { book.stop(view.ref, "Le test est fini.", Author(Source.OPERATOR, operator = "gametest")) }
            onServer { if (modules.running) stop() }
            threads.close()
        }
    }

    /** A Forge assembled from the real components: ECJ, the guard, the manifest, the rewriting. Each module is version 1, ready. */
    private class TestForge(private val clock: Clock) : Forge {
        private val compiler = EcjCompiler()
        private val sources = ConcurrentHashMap<String, String>()
        private val entries = ConcurrentHashMap<String, ArsenalEntry>()

        override val available = true
        override val unavailableReason: String? = null

        override val arsenal: Arsenal = object : Arsenal {
            override fun list(): List<ArsenalEntry> = entries.values.toList()

            override fun entry(name: String, version: Int?): ArsenalEntry? = entries[name]?.takeIf { version == null || it.version == version }

            override suspend fun source(name: String, version: Int): String? = sources[name]

            override fun file(name: String, version: Int): Path? = null
        }

        /** Module [name] in the arsenal, its manifest read from its compiled classes. */
        suspend fun add(name: String, source: String) {
            entries[name] = Manifest.read(name, compile(name, source)).entry(1, ModuleStatus.READY, false, clock.instant(), "gametest", null, Manifest.hash(source))
            sources[name] = source
        }

        /** The source of [name] changed under its manifest: it no longer matches the version deployed. */
        fun edit(name: String, source: String) {
            sources[name] = source
        }

        override suspend fun build(name: String, version: Int?): Built {
            val entry = arsenal.entry(name, version) ?: throw ForgeRejected(null, "no module $name in the arsenal")
            val source = sources.getValue(name)
            val main = Manifest.mainClass(name)
            return Built(entry.copy(hash = Manifest.hash(source)), main, Instrumenter.rewrite(main, compile(name, source)))
        }

        private suspend fun compile(name: String, source: String): Map<String, ByteArray> {
            val compiled = compiler.compile(Manifest.mainClass(name), source, name)
            check(compiled.ok) { "$name: ${compiled.problems}" }
            val violations = BytecodeGuard().check(compiled.classes)
            check(violations.isEmpty()) { "$name: $violations" }
            return compiled.classes
        }

        override suspend fun test(name: String, source: String): TestReport = throw ForgeRejected(null, "not in these tests")

        override suspend fun save(name: String, source: String, note: String, by: String): ArsenalEntry = throw ForgeRejected(null, "not in these tests")

        override suspend fun review(name: String, version: Int?, approve: Boolean, operator: String): ArsenalEntry = throw ForgeRejected(null, "not in these tests")

        override fun status(): ForgeStatus = ForgeStatus(ecj = null, compiler = "idle")

        override fun stop() = compiler.stop()
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
        val DIRECTOR = Caller(Role.DIRECTOR, "director-gametest", turnId = 4242, taskId = null)

        /** [player] says [text] in the public chat, as the client would. Server thread. */
        fun say(server: MinecraftServer, player: TestPlayer, text: String) {
            val live = player.live
            server.playerList.broadcastChatMessage(PlayerChatMessage.unsigned(live.uuid, text), live, ChatType.bind(ChatType.CHAT, live))
        }

        /** Runs [test] with a [Rig] started on the server thread and a player standing on the test's floor; all closed however it ends. */
        suspend fun CoroutineScope.withRig(helper: GameTestHelper, test: suspend (Rig, TestPlayer) -> Unit) {
            val server = helper.level.server
            val bob = server.onServerThread {
                floor(helper)
                TestPlayer(helper, "me_").also { it.standAt(BlockPos(3, 1, 3)) }
            }
            val rig = Rig(helper)
            try {
                rig.onServer { rig.start() }
                test(rig, bob)
            } finally {
                rig.cleanUp()
                rig.forge.stop()
                server.onServerThread { bob.close() }
            }
        }

        /** Two chickens around its player; « fin » in the chat ends it. */
        val FLOCK = """
            import io.github.clemsix6.narrator2.forge.api.*;
            @ModuleInfo(name = "flock", summary = "Two chickens follow a player until they say the word.")
            @Param(name = "player", type = ParamType.PLAYER, doc = "Whose chickens.")
            public final class Flock implements ForgeModule {
                public void start(ModuleContext m) {
                    m.acts().spawn("chicken", m.args().player("player"), Spawn.of(2).withDistance(3));
                    m.events().on(GameFact.Chat.class, c -> { if (Text.words(c.text()).contains("fin")) m.end("La basse-cour est rentrée."); });
                }
            }
        """.trimIndent()

        /** Two chickens, then a timer that spins for ever once they are there. */
        val SPINNER = """
            import io.github.clemsix6.narrator2.forge.api.*;
            @ModuleInfo(name = "spinner", summary = "Two chickens, then it spins.")
            @Param(name = "player", type = ParamType.PLAYER, doc = "Whose chickens.")
            public final class Spinner implements ForgeModule {
                public void start(ModuleContext m) {
                    m.acts().spawn("chicken", m.args().player("player"), Spawn.of(2).withDistance(3));
                    m.timers().every(1, () -> { if (m.creatures().own().size() >= 2) { while (true) { } } });
                }
            }
        """.trimIndent()

        /** A chicken and a word, kept across a restart. */
        val KEEPER = """
            import io.github.clemsix6.narrator2.forge.api.*;
            @ModuleInfo(name = "keeper", summary = "Keeps a chicken and a word across restarts.")
            @Param(name = "player", type = ParamType.PLAYER, doc = "Whose chicken.")
            public final class Keeper implements ForgeModule {
                public void start(ModuleContext m) {
                    if (m.restarted()) {
                        m.storage().put("back", m.creatures().own().size() + " " + m.storage().get("word"));
                    } else {
                        m.storage().put("word", "poule");
                        m.acts().spawn("chicken", m.args().player("player"), Spawn.of(1).withDistance(3).withLifetimeMinutes(10));
                    }
                }
                public void stop(ModuleContext m, StopReason why) { m.storage().put("stopped", why.name()); }
            }
        """.trimIndent()

        /** Nothing; its source changes while the server is down. */
        val FICKLE = """
            import io.github.clemsix6.narrator2.forge.api.*;
            @ModuleInfo(name = "fickle", summary = "Rien.")
            public final class Fickle implements ForgeModule {
                public void start(ModuleContext m) { }
            }
        """.trimIndent()

        /** Nothing at all: it waits for its end. */
        val IDLER = """
            import io.github.clemsix6.narrator2.forge.api.*;
            @ModuleInfo(name = "idler", summary = "Waits for its end.")
            public final class Idler implements ForgeModule {
                public void start(ModuleContext m) { }
            }
        """.trimIndent()
    }
}
