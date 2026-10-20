package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.forge.api.Acts
import io.github.clemsix6.narrator2.forge.api.Args
import io.github.clemsix6.narrator2.forge.api.Creatures
import io.github.clemsix6.narrator2.forge.api.Events
import io.github.clemsix6.narrator2.forge.api.Game
import io.github.clemsix6.narrator2.forge.api.Ground
import io.github.clemsix6.narrator2.forge.api.ModuleContext
import io.github.clemsix6.narrator2.forge.api.Pending
import io.github.clemsix6.narrator2.forge.api.Place
import io.github.clemsix6.narrator2.forge.api.Scheduled
import io.github.clemsix6.narrator2.forge.api.StopReason
import io.github.clemsix6.narrator2.forge.api.Storage
import io.github.clemsix6.narrator2.forge.api.Timers
import io.github.clemsix6.narrator2.forge.api.World
import io.github.clemsix6.narrator2.forge.host.Fault
import io.github.clemsix6.narrator2.forge.host.LostThreads
import io.github.clemsix6.narrator2.forge.host.ModuleHost
import io.github.clemsix6.narrator2.forge.host.ModuleThreads
import io.github.clemsix6.narrator2.forge.sandbox.BytecodeGuard
import io.github.clemsix6.narrator2.forge.sandbox.Instrumenter
import io.github.clemsix6.narrator2.forge.sdk.FactQueue
import io.github.clemsix6.narrator2.forge.sdk.StorageMap
import io.github.clemsix6.narrator2.forge.sdk.WorldView
import io.github.clemsix6.narrator2.gametest.ForgeContractsGameTests.Companion.FORGE_ENVIRONMENT
import io.github.clemsix6.narrator2.testing.BusyMachine
import io.github.clemsix6.narrator2.testing.ForgeSamples
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.block.Blocks
import java.lang.reflect.Proxy
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * In-game tests of the loader and the host (task M5-05, M5 §16.3), in the Forge's batch: a module's code runs on a
 * module thread while the server thread waits for it and runs its SDK calls (so the world is read on the server thread,
 * where `getChunkNow` answers); and while modules attack it — an endless loop, the hash of a structure shared ten
 * thousand times (now bounded by the guard, M5-18), an `indexOf` of O(n·m) in a loop, a list that grows, an allocation
 * loop — the server's tick never waits past a call's budget and its margin.
 */
class ModuleHostGameTests {

    @GameTest(environment = FORGE_ENVIRONMENT, maxTicks = ASYNC_MAX_TICKS)
    fun aModuleRunsOnItsThreadAndReadsTheWorldOnTheServerThread(helper: GameTestHelper) {
        val runtime = helper.narrator()
        val server = helper.level.server
        val gold = helper.absolutePos(BlockPos(1, 2, 1))
        helper.setBlock(BlockPos(1, 2, 1), Blocks.GOLD_BLOCK)
        val dimension = helper.level.dimension().identifier().toString()
        val built = AtomicReference<Pair<Map<String, ByteArray>, Map<String, ByteArray>>>()
        val failure = AtomicReference<Throwable>()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                built.set(build("Probe", PROBE) to Instrumenter.rewrite(ForgeSamples.CLASS, ForgeSamples.classes))
            } catch (e: Throwable) {
                failure.set(e)
            }
        }
        val threads = ModuleThreads("gametest-module")
        helper.startSequence()
            .thenAwait { helper.assertTrue(failure.get() == null && built.get() != null, "building the modules: ${failure.get()}") }
            .thenExecute {
                try {
                    val (probe, witness) = built.get()
                    val world = WorldView(server, runtime.players) { false }
                    val arguments = mapOf("dimension" to dimension, "x" to gold.x, "y" to gold.y, "z" to gold.z)
                    val context = GameContext(world, arguments)
                    // A busy server's patience (FLAKY-4): what is checked here is where the code runs, never its time.
                    val host = ModuleHost("Probe", probe, threads, "gametest Probe", timeScale = BusyMachine.TIME_SCALE)
                    host.start(context)
                    helper.assertTrue(host.disabled == null, "the probe started: ${host.disabled}")
                    val surface = context.logs.firstOrNull { it.startsWith("surface ") }?.removePrefix("surface ")?.toIntOrNull()
                    helper.assertTrue("block gold_block" in context.logs, "the world read on the server thread, though the module runs on its own: ${context.logs}")
                    helper.assertTrue(surface != null && surface > gold.y, "the surface: ${context.logs}")
                    val ran = AtomicReference<Thread>()
                    host.call("where") { ran.set(Thread.currentThread()) }
                    helper.assertTrue(ran.get() != Thread.currentThread() && ran.get().name.startsWith("gametest-module-"), "module code on a module thread: ${ran.get()}")
                    host.unload()

                    // The witness of ForgeSamples, loaded by its filtering loader and started from the server thread.
                    val tag = GameContext(world, mapOf("players" to listOf("Bartok", "Mimosa"), "minutes" to 5, "mood" to "taquin"))
                    val witness0 = ModuleHost(ForgeSamples.CLASS, witness, threads, "gametest ${ForgeSamples.NAME}", timeScale = BusyMachine.TIME_SCALE)
                    witness0.start(tag)
                    helper.assertTrue(witness0.disabled == null, "the witness started: ${witness0.disabled}")
                    repeat(3) { witness0.call("timer") { tag.tasks[0].run() } }
                    witness0.stop(StopReason.EXPIRED)
                    helper.assertTrue(witness0.faults == 0 && tag.logs.any { "the round ran out" in it }, "the witness ran and stopped: ${tag.logs}")
                    witness0.unload()
                } finally {
                    threads.close()
                }
            }
            .thenSucceed()
    }

    @GameTest(environment = FORGE_ENVIRONMENT, maxTicks = ASYNC_MAX_TICKS)
    fun theTickNeverWaitsPastABudgetWhileModulesAttackIt(helper: GameTestHelper) {
        val server = helper.level.server
        val built = AtomicReference<Map<String, ByteArray>>()
        val failure = AtomicReference<Throwable>()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                built.set(build("Attack", ATTACK))
            } catch (e: Throwable) {
                failure.set(e)
            }
        }
        val lost = LostThreads()
        val threads = ModuleThreads("gametest-attack", lost)
        val measures = CopyOnWriteArrayList<Measure>()
        var sequence = helper.startSequence()
            .thenAwait { helper.assertTrue(failure.get() == null && built.get() != null, "building the module: ${failure.get()}") }
            .thenIdle(5)
        // Before the attacks: the tick as the batch makes it.
        val before = mutableListOf<Int>()
        sequence = sequence.thenExecuteFor(5) { before += server.tickCount }
        ATTACKS.forEachIndexed { index, attack ->
            var host: ModuleHost? = null
            var context: GameContext? = null
            var tick = 0
            var called: ModuleHost.Called? = null
            val faults = CopyOnWriteArrayList<Fault>()
            sequence = sequence
                .thenExecute {
                    val made = GameContext(WorldView(server, helper.narrator().players) { false }, emptyMap())
                    // Its `start` (the structures of the attacks) with a busy server's patience; the attack with the
                    // game's margin, and the game's budget when time is what stops it (FLAKY-4).
                    val attacker = ModuleHost(
                        "Attack", built.get(), threads, "gametest attack $attack",
                        timeScale = BusyMachine.TIME_SCALE, abandonMarginNanos = ModuleHost.ABANDON_MARGIN_NANOS,
                    ) { faults += it }
                    attacker.start(made)
                    helper.assertTrue(attacker.disabled == null, "$attack: the module started: ${attacker.disabled}")
                    host = attacker
                    context = made
                }
                .thenIdle(1)
                .thenExecute {
                    tick = server.tickCount
                    val budget = if (attack in GUARDED) ForgeLimits.CALL_NANOS * BusyMachine.TIME_SCALE else ForgeLimits.CALL_NANOS
                    var ran = host?.call("timer", budget) { context!!.tasks[index].run() }
                    // A call the module thread could not even start in time (a saturated machine) is skipped, nobody's
                    // fault (M5-LOAD): asked again.
                    repeat(10) { if (ran?.skipped == true) ran = host?.call("timer", budget) { context!!.tasks[index].run() } }
                    called = ran
                    helper.assertTrue(called != null && !called!!.skipped, "$attack: the module ran: ${host?.disabled}")
                }
                // A call given up at the server's deadline is judged once its thread computed past its budget, or ended.
                .thenAwait { helper.assertTrue(host?.busy() != true, "$attack: the call given up is settled") }
                .thenExecute {
                    val ran = called
                    if (ran == null) helper.assertTrue(false, "$attack never ran") else measures += Measure(attack, ran, server.tickTimesNanos[tick % 100], ran.fault ?: faults.lastOrNull())
                }
        }
        sequence
            .thenAwait { helper.assertTrue(lost.alive() == 0, "the lost threads end once their JDK call returns") }
            .thenExecute {
                threads.close()
                val baseline = before.maxOf { server.tickTimesNanos[it % 100] } / 1e6
                for (measure in measures) {
                    Log.forge.info(
                        "[host] in the server, ${measure.attack}: the module held the tick %.1f ms, the tick took %.1f ms (%.1f ms at most before), %s"
                            .format(measure.called.nanos / 1e6, measure.tickNanos / 1e6, baseline, measure.fault?.text),
                    )
                    // The wall clock of a busy server may add to both (FLAKY-4): without the abandon, the indexOf loop alone
                    // holds its caller about 1 s (measured), until its meter reads the clock between two indexOf.
                    val held = measure.called.nanos / 1e6
                    helper.assertTrue(held < BUDGET_MS + MARGIN_MS + BusyMachine.SLACK_MILLIS, "${measure.attack}: the module held the tick $held ms")
                    helper.assertTrue(measure.tickNanos / 1e6 < BUDGET_MS + TICK_MARGIN_MS + baseline + BusyMachine.SLACK_MILLIS, "${measure.attack}: the tick took ${measure.tickNanos / 1e6} ms")
                    helper.assertTrue(measure.fault != null, "${measure.attack}: stopped, and its fault counted")
                }
                val kinds = measures.associate { it.attack to it.fault?.kind }
                helper.assertTrue(kinds["endless loop"] == Fault.Kind.LIMIT && kinds["indexOf loop"] == Fault.Kind.LIMIT && kinds["allocation loop"] == Fault.Kind.LIMIT, "$kinds")
                // One indexOf (75 ms) outlasts the budget: the server gave the call up, never waited for its meter to stop
                // it; its thread computing on past its budget, the call was abandoned (M5-LOAD: judged then).
                val indexOf = measures.first { it.attack == "indexOf loop" }
                helper.assertTrue(indexOf.called.fault == null || "and was abandoned" in indexOf.called.fault!!.cause, "given up at the deadline: ${indexOf.called.fault}")
                helper.assertTrue("and was abandoned" in indexOf.fault!!.cause && indexOf.fault.disables, "the indexOf loop was abandoned: ${indexOf.fault}")
                // The shared-structure hash is now bounded by the guard (M5-18), and the growing list too: each a clean
                // ForgeLimit of the guard, an exception, given time enough that the clock never stops it first — no abandon,
                // no lost thread.
                helper.assertTrue(kinds["shared hash"] == Fault.Kind.EXCEPTION && kinds["growing list"] == Fault.Kind.EXCEPTION, "$kinds")
                val shared = measures.first { it.attack == "shared hash" }
                helper.assertTrue(shared.fault!!.cause.startsWith("ForgeLimit: "), "the guard stopped the shared hash: ${shared.fault}")
                Log.forge.info("[host] in the server, the guard stopped the shared hash in %.1f ms".format(shared.called.nanos / 1e6))
            }
            .thenSucceed()
    }

    /** An attack's call, the tick it was in, and its fault: the call's own, or the one its host counted once it settled the call it gave up. */
    private class Measure(val attack: String, val called: ModuleHost.Called, val tickNanos: Long, val fault: Fault?)

    /** A deployment's context with the game's real `World`, its storage and facts, and acts, creatures and ground that do nothing. */
    private class GameContext(private val world: World, private val arguments: Map<String, Any>) : ModuleContext {
        val logs = CopyOnWriteArrayList<String>()
        val tasks = CopyOnWriteArrayList<Runnable>()
        private val storage = StorageMap()
        private val events = FactQueue()
        private val random = Random(7)
        private val started = System.currentTimeMillis()

        override fun id() = "C-70"

        override fun restarted() = false

        override fun now() = System.currentTimeMillis()

        override fun endsAt() = started + 600_000

        override fun random() = random

        override fun args(): Args = object : Args {
            override fun player(name: String?) = arguments[name] as String?

            @Suppress("UNCHECKED_CAST")
            override fun players(name: String?) = arguments[name] as List<String>

            override fun integer(name: String?) = arguments[name] as Int

            override fun number(name: String?) = (arguments[name] as Number).toDouble()

            override fun text(name: String?) = arguments[name] as String?

            override fun bool(name: String?) = arguments[name] as Boolean

            override fun place(name: String?) = arguments[name] as Place?
        }

        override fun events(): Events = events

        override fun timers(): Timers = object : Timers {
            override fun after(ticks: Int, task: Runnable?): Scheduled = keep(task)

            override fun every(ticks: Int, task: Runnable?): Scheduled = keep(task)

            private fun keep(task: Runnable?): Scheduled {
                tasks += requireNotNull(task)
                return object : Scheduled {
                    override fun cancel() {}

                    override fun active() = true
                }
            }
        }

        override fun world(): World = world

        override fun acts(): Acts = nothing(Acts::class.java) { nothing(Pending::class.java) { null } }

        override fun creatures(): Creatures = nothing(Creatures::class.java) { null }

        override fun ground(): Ground = nothing(Ground::class.java) { null }

        override fun storage(): Storage = storage

        override fun game(): Game? = null

        override fun log(line: String?) {
            logs += line.orEmpty()
        }

        override fun tellDirector(note: String?) {
            logs += "director: $note"
        }

        override fun end(why: String?) {
            logs += "end: $why"
        }

        private fun <T : Any> nothing(type: Class<T>, answer: () -> Any?): T =
            type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, _, _ -> answer() })
    }

    private companion object {
        /** A call's budget and the margin before the caller abandons it. */
        val BUDGET_MS = ForgeLimits.CALL_MILLIS + ModuleHost.ABANDON_MARGIN_NANOS / 1_000_000

        /** What the abandon itself and a busy test server may add to the module's hold of the tick. */
        const val MARGIN_MS = 10

        /**
         * What the rest of an attacked tick may add, besides the tick's usual work: the abandon itself, and the lost
         * thread still computing during that tick (a safepoint, a collection, waits for it: M5 §3.8). Measured: 17 ms.
         * The bound, about one tick's length (50 ms) past the usual tick, against 75 ms to minutes without the host.
         */
        const val TICK_MARGIN_MS = 25

        /** The attacks, in the order of the module's timers. */
        val ATTACKS = listOf("endless loop", "shared hash", "indexOf loop", "growing list", "allocation loop")

        /** The attacks the guard stops, not the clock: their calls have a busy server's patience. */
        val GUARDED = setOf("shared hash", "growing list")

        /** Reads a block and the surface of the cell its parameters name, and logs them. */
        val PROBE = """
            import io.github.clemsix6.narrator2.forge.api.*;
            public final class Probe implements ForgeModule {
                public void start(ModuleContext m) {
                    Cell cell = new Cell(m.args().text("dimension"), m.args().integer("x"), m.args().integer("y"), m.args().integer("z"));
                    BlockInfo block = m.world().block(cell);
                    m.log("block " + (block == null ? "unloaded" : block.block()));
                    m.log("surface " + m.world().surface(cell.dimension(), cell.x(), cell.z()));
                }
            }
        """.trimIndent()

        /** Five attacks on the server's time, one a timer (finding 1 of M5-04 among them: L1 = 10,000 × L0, hashed). */
        val ATTACK = """
            import io.github.clemsix6.narrator2.forge.api.*;
            import java.util.*;
            public final class Attack implements ForgeModule {
                List<List<Integer>> l1;
                static long sink;
                public void start(ModuleContext m) {
                    List<Integer> l0 = new ArrayList<>(); for (int i = 0; i < 10_000; i++) l0.add(i);
                    l1 = new ArrayList<>(); for (int i = 0; i < 10_000; i++) l1.add(l0);
                    String text = "a".repeat(65_535) + "b";
                    String pattern = "a".repeat(32_767) + "b";
                    m.timers().every(1, () -> { while (true) { } });
                    m.timers().every(1, () -> m.log("" + l1.hashCode()));
                    m.timers().every(1, () -> { while (true) { if (text.indexOf(pattern) < 0) m.log("never"); } });
                    m.timers().every(1, () -> { List<Object> l = new ArrayList<>(); while (true) l.add(new Object()); });
                    m.timers().every(1, () -> { for (;;) { long[] a = new long[65_000]; sink += a.length; } });
                }
            }
        """.trimIndent()

        /** [source] compiled against the SDK, clean to the guard, rewritten: what the Forge builds. Off the server thread. */
        fun build(name: String, source: String): Map<String, ByteArray> {
            val compiled = ForgeSamples.compile(name, source)
            check(compiled.ok) { "$name: ${compiled.problems}" }
            val violations = BytecodeGuard().check(compiled.classes)
            check(violations.isEmpty()) { "$name: $violations" }
            return Instrumenter.rewrite(name, compiled.classes)
        }
    }
}
