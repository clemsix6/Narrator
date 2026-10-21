package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.onServerThread
import io.github.clemsix6.narrator2.director.DirectorControl
import io.github.clemsix6.narrator2.forge.Forge
import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.forge.ForgeService
import io.github.clemsix6.narrator2.forge.ModuleStatus
import io.github.clemsix6.narrator2.forge.arsenal.ArsenalStore
import io.github.clemsix6.narrator2.forge.host.LeakWatch
import io.github.clemsix6.narrator2.forge.host.ModuleThreads
import io.github.clemsix6.narrator2.forge.sdk.EngineActRunner
import io.github.clemsix6.narrator2.forge.sdk.ModuleCreatures
import io.github.clemsix6.narrator2.gametest.GuardPolicyGameTests.Companion.floor
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.mechanics.DeploySpec
import io.github.clemsix6.narrator2.mechanics.Engine
import io.github.clemsix6.narrator2.mechanics.EngineActions
import io.github.clemsix6.narrator2.mechanics.RuleKind
import io.github.clemsix6.narrator2.mechanics.StateStore
import io.github.clemsix6.narrator2.mechanics.module.ModuleBook
import io.github.clemsix6.narrator2.mechanics.module.ModuleEngine
import io.github.clemsix6.narrator2.mechanics.module.moduleOnlyTools
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.testing.FakeDirector
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.action.playerActionTools
import io.github.clemsix6.narrator2.tool.action.punishTools
import io.github.clemsix6.narrator2.tool.action.worldActionTools
import io.github.clemsix6.narrator2.tool.speech.speechTools
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.phys.AABB
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.time.Duration
import java.time.Instant

/**
 * In-game test of the leak watch (M5-FIX-3, M5 §3.7) on the real `orbiting_chickens`: built by a Forge of its own from
 * the mod's jar, deployed on a modules' engine of its own (the real host, SDK, tools, guards and creatures), ended by
 * itself, its loader let go and watched by the watch the game builds ([LeakWatch], its own clock).
 *
 * M5-22 saw the watch tell a leak 120 s after such an end. The loader survives the young collections of G1, the
 * server's collector — the data of every class loader is a root there —, which the watch counted, and goes at the first
 * collection that unloads classes (measured by this test's probe: alive after three young collections, told a leak by
 * the old watch, collected by the first full collection). Now the watch waits for collections that unload classes.
 *
 * In a batch of its own (`forge_leak`): it makes the server collect, which would stretch the ticks of a test beside it.
 */
class LeakWatchGameTests {
    @GameTest(environment = LEAK_ENVIRONMENT, maxTicks = ASYNC_MAX_TICKS)
    fun orbitingChickensEndedWellIsNoLeak(helper: GameTestHelper) {
        floor(helper)
        val bob = TestPlayer(helper, "lw_").also { it.standAt(BlockPos(3, 1, 3)) }
        val runtime = helper.narrator()
        val clock = TestClock(Instant.ofEpochMilli(runtime.clock.millis()), runtime.clock.zone)
        val leakClock = TestClock()
        val leakWatch = LeakWatch(leakClock)
        val director = FakeDirector(clock)
        val forge = ForgeService(runtime, ArsenalStore(Files.createTempDirectory("narrator2-gametest-arsenal").resolve(ForgeService.ARSENAL_DIR), runtime.clock, builtInNames = listOf(MODULE)))
        val services: NarratorServices = object : NarratorServices by runtime {
            override val director: DirectorControl get() = director
            override val forge: Forge get() = forge
        }
        val store = StateStore.inMemory()
        val actions = EngineActions(services, speechTools(services) + playerActionTools(services) + worldActionTools(services) + punishTools(services) + moduleOnlyTools())
        val threads = ModuleThreads("gametest-leak", forge.lostThreads)
        val area: AABB = helper.bounds.inflate(16.0)
        val modules = ModuleEngine(
            services, store, threads, EngineActRunner(actions), runtime.mechanics.challenges, clock = clock, leakWatch = leakWatch,
            area = { entity -> area.contains(entity.position()) && ModuleCreatures.ownerOf(entity).let { it == null || store.snapshot.module(it) != null } },
        )
        val book = ModuleBook(services, store, modules, clock)
        val engine = Engine(services, store, listOf(modules), clock)
        var ticking = false
        helper.onEachTick {
            if (ticking) {
                clock.advance(TICK)
                engine.tick(helper.level.server)
            }
        }
        helper.runScript(ASYNC_MAX_TICKS) {
            val server = helper.level.server
            try {
                forge.start().join()
                check(forge.arsenal.entry(MODULE, 0)?.status == ModuleStatus.READY) { "$MODULE built from the mod's jar: ${forge.arsenal.entry(MODULE, 0)}" }
                server.onServerThread(10_000) {
                    engine.start(server)
                    ticking = true
                }
                val params = Json.parseToJsonElement("""{"player": "${bob.name}", "chickens": 3, "seconds": 10}""") as JsonObject
                val spec = DeploySpec(
                    module = MODULE, version = null, params = params, kind = RuleKind.PLAN,
                    statement = "Le Narrateur a lancé le module $MODULE.", quote = null, minutes = 10, challenge = null,
                )
                val view = book.deploy(spec, DIRECTOR)
                awaitCondition("${view.ref} ended", 60_000) { server.onServerThread(10_000) { store.snapshot.module(view.ref.id) == null } }
                awaitCondition("${view.ref} resolved") { runtime.memory.store.canon(view.ref)?.status != CanonStatus.ACTIVE }
                val canon = runtime.memory.store.canon(view.ref)!!
                check(canon.status == CanonStatus.FULFILLED && canon.resolution == LANDED) { "it ended by itself, landed: ${canon.status} « ${canon.resolution} »" }
                awaitCondition("its loader watched") { leakWatch.watching() == 1 }

                // Young collections only (the server's G1), then the delay: what the old watch told a leak.
                val before = collections()
                young(ForgeLimits.LEAK_GC_CYCLES + 1)
                val young = collections()
                val unloading = (young.keys - YOUNG).sumOf { young.getValue(it) - (before[it] ?: 0) }
                if (unloading == 0L) check(leakWatch.watching() == 1) { "a young collection does not unload a module's classes" }
                leakClock.advance(Duration.ofMillis(ForgeLimits.LEAK_CHECK_MS))
                check(leakWatch.check().isEmpty() && leakWatch.leaks == 0) { "no leak told after young collections only (${delta(before, young)})" }

                // The first collection that unloads classes takes it: no leak, ever, and the watch forgets it.
                var gcs = 0
                awaitCondition("its loader collected", 30_000) {
                    System.gc()
                    gcs++
                    leakWatch.check()
                    leakWatch.watching() == 0
                }
                check(leakWatch.leaks == 0) { "never told a leak: ${leakWatch.leaks}" }
                Log.forge.info(
                    "M5-FIX-3 in the server: ${view.ref} $MODULE ended well; its loader alive after young collections (${delta(before, young)}), " +
                        "no leak told ${ForgeLimits.LEAK_CHECK_MS / 1_000} s later; collected after $gcs System.gc() (${delta(young, collections())})",
                )
            } finally {
                server.onServerThread(10_000) {
                    ticking = false
                    if (modules.running) engine.stop()
                }
                threads.close()
                forge.stop()
                server.onServerThread(10_000) { bob.close() }
            }
        }
    }

    private companion object {
        const val LEAK_ENVIRONMENT = "narrator2-gametest:forge_leak"
        const val MODULE = "orbiting_chickens"
        const val LANDED = "Les poules sont redescendues sur terre."
        val TICK: Duration = Duration.ofMillis(50)
        val DIRECTOR = Caller(Role.DIRECTOR, "director-gametest", turnId = 4343, taskId = null)

        /** G1's young collector: the server's collector by default (a server-class machine). */
        const val G1_YOUNG = "G1 Young Generation"
        val YOUNG = setOf(G1_YOUNG)

        /** Short-lived garbage, out of reach of the JIT's escape analysis. */
        @Volatile
        var sink: Any? = null

        fun collections(): Map<String, Long> = ManagementFactory.getGarbageCollectorMXBeans().associate { it.name to it.collectionCount }

        fun delta(from: Map<String, Long>, to: Map<String, Long>): String = to.entries.joinToString { (name, count) -> "$name +${count - (from[name] ?: 0)}" }

        /** Allocates short-lived garbage, in arrays far below G1's humongous size, until its young collector ran [count] more times. */
        fun young(count: Int) {
            val young = ManagementFactory.getGarbageCollectorMXBeans().firstOrNull { it.name == G1_YOUNG }
            checkNotNull(young) { "the server runs G1: ${collections().keys}" }
            val target = young.collectionCount + count
            while (young.collectionCount < target) sink = ByteArray(256 * 1024)
            sink = null
        }
    }
}
