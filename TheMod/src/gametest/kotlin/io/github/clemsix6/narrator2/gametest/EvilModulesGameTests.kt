package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.forge.api.Acts
import io.github.clemsix6.narrator2.forge.api.Args
import io.github.clemsix6.narrator2.forge.api.Creatures
import io.github.clemsix6.narrator2.forge.api.Events
import io.github.clemsix6.narrator2.forge.api.Game
import io.github.clemsix6.narrator2.forge.api.Ground
import io.github.clemsix6.narrator2.forge.api.ModuleContext
import io.github.clemsix6.narrator2.forge.api.Place
import io.github.clemsix6.narrator2.forge.api.Storage
import io.github.clemsix6.narrator2.forge.api.Timers
import io.github.clemsix6.narrator2.forge.api.World
import io.github.clemsix6.narrator2.forge.host.ModuleHost
import io.github.clemsix6.narrator2.forge.host.ModuleThreads
import io.github.clemsix6.narrator2.forge.sandbox.BytecodeGuard
import io.github.clemsix6.narrator2.forge.sandbox.Instrumenter
import io.github.clemsix6.narrator2.gametest.ForgeContractsGameTests.Companion.FORGE_ENVIRONMENT
import io.github.clemsix6.narrator2.testing.BusyMachine
import io.github.clemsix6.narrator2.testing.ForgeSamples
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import java.lang.reflect.Proxy
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * The runtime side of the attack battery (M5 §16.2, §16.3, task M5-18), on the server thread with the real sandbox:
 * the deep hash and equality of a shared structure — the explicit `hashCode`, the implicit key of a map, a record that
 * wraps it, `Arrays.deepHashCode` — are now bounded by the guard (M5-18's fix for M5-05's open finding): a clean
 * ForgeLimit in a few ms, with **no lost thread**, so the server's tick is never held past a call's budget. The
 * documented residual (`Stream.distinct`, a factory the guard does not wrap) is still bounded, but by the host's
 * abandon: the server goes on at the budget and the lost thread ends on its own.
 *
 * The §17 attacks through the real tools and guards (a protected player, acharnement, forbidden effects and creatures,
 * a trap on solid ground, a bystander's death) are in `ModuleActsGameTests` and `ActQueueTest`.
 */
class EvilModulesGameTests {
    @GameTest(environment = FORGE_ENVIRONMENT, maxTicks = ASYNC_MAX_TICKS)
    fun theDeepHashAttacksAreStoppedByTheGuardWithNoLostThread(helper: GameTestHelper) {
        val built = AtomicReference<Map<String, Map<String, ByteArray>>>()
        val failure = AtomicReference<Throwable>()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                built.set(GUARDED.associateWith { build(it) })
            } catch (e: Throwable) {
                failure.set(e)
            }
        }
        val threads = ModuleThreads("gametest-evil")
        helper.startSequence()
            .thenAwait { helper.assertTrue(failure.get() == null && built.get() != null, "building the attacks: ${failure.get()}") }
            .thenExecute {
                try {
                    val budgetMs = (ModuleHost.STARTUP_CALL_NANOS + ModuleHost.ABANDON_MARGIN_NANOS) / 1_000_000
                    for (name in GUARDED) {
                        val context = EvilContext()
                        val host = ModuleHost(name, built.get().getValue(name), threads, "evil $name")
                        val called = host.start(context.context)
                        val cause = host.disabled?.cause ?: "<not disabled>"
                        // Stopped before the JDK does the work: the call-site guard (« too large to hash ») warm, or
                        // the meter at the time budget (« time budget ») cold, never the host's abandon.
                        helper.assertTrue(host.disabled != null, "$name ran to the end: $cause")
                        helper.assertTrue("abandoned" !in cause, "$name was abandoned, not bounded by the guard: $cause")
                        helper.assertTrue("too large to hash" in cause || "time budget" in cause, "$name: $cause")
                        val held = (called?.nanos ?: 0) / 1e6
                        helper.assertTrue(held < budgetMs, "$name held the tick $held ms (bounded, not abandoned)")
                        Log.forge.info("[evil] $name stopped in %.1f ms (%s), no lost thread".format(held, cause))
                        host.unload()
                    }
                    helper.assertTrue(threads.lost.alive() == 0, "no call was abandoned, no thread lost: the guard or the meter stopped each one")
                } finally {
                    threads.close()
                }
            }
            .thenSucceed()
    }

    @GameTest(environment = FORGE_ENVIRONMENT, maxTicks = ASYNC_MAX_TICKS)
    fun theResidualStreamDistinctIsStoppedByTheHost(helper: GameTestHelper) {
        val built = AtomicReference<Map<String, ByteArray>>()
        val failure = AtomicReference<Throwable>()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                built.set(build("DistinctShared"))
            } catch (e: Throwable) {
                failure.set(e)
            }
        }
        val threads = ModuleThreads("gametest-evil-residual")
        helper.startSequence()
            .thenAwait { helper.assertTrue(failure.get() == null && built.get() != null, "building: ${failure.get()}") }
            .thenExecute {
                val context = EvilContext()
                val host = ModuleHost("DistinctShared", built.get(), threads, "evil DistinctShared")
                val called = host.start(context.context)
                val disabled = host.disabled
                helper.assertTrue(disabled != null && "abandoned" in disabled.cause, "the host abandoned it: $disabled")
                val held = (called?.nanos ?: 0) / 1e6
                // A start is waited for while its thread has not computed (M5-LOAD), given up once its CPU time reached its
                // budget (50 ms: four steps of the CPU clock under Windows, read each millisecond), as a busy machine allows.
                val abandonBudgetMs = (ModuleHost.STARTUP_CALL_NANOS + ModuleHost.ABANDON_MARGIN_NANOS) / 1_000_000 + 16
                helper.assertTrue(held < abandonBudgetMs + BusyMachine.SLACK_MILLIS, "the server went on at the budget: $held ms")
                Log.forge.info("[evil] DistinctShared abandoned by the host after %.1f ms (a lost thread, bounded to two)".format(held))
                host.unload()
            }
            .thenAwait { helper.assertTrue(threads.lost.alive() == 0, "the lost thread ended on its own") }
            .thenExecute { threads.close() }
            .thenSucceed()
    }

    /** A context that only records what a module logs; a deep-hash attack faults before it reaches anything else. */
    private class EvilContext {
        val logs = CopyOnWriteArrayList<String>()
        private val random = Random(7)
        val context: ModuleContext = Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(ModuleContext::class.java),
        ) { _, method, args ->
            when (method.name) {
                "log", "tellDirector", "end" -> logs.add("${args?.get(0)}").let { null }
                "random" -> random
                "id" -> "C-70"
                "now", "endsAt" -> 0L
                "restarted" -> false
                "args" -> stub(Args::class.java)
                "events" -> stub(Events::class.java)
                "timers" -> stub(Timers::class.java)
                "world" -> stub(World::class.java)
                "acts" -> stub(Acts::class.java)
                "creatures" -> stub(Creatures::class.java)
                "ground" -> stub(Ground::class.java)
                "storage" -> stub(Storage::class.java)
                "game" -> null
                else -> null
            }
        } as ModuleContext

        private fun <T : Any> stub(type: Class<T>): T =
            type.cast(Proxy.newProxyInstance(javaClass.classLoader, arrayOf(type)) { _, _, _ -> null })
    }

    private companion object {
        /** The deep-hash attacks the guard now bounds (explicit, implicit key, a record, nested arrays). */
        val GUARDED = listOf("SharedHash", "SharedKey", "RecordHash", "ArraysDeepHash")

        /** The source of an evil module, read from the battery's resources. */
        fun source(name: String): String =
            EvilModulesGameTests::class.java.getResourceAsStream("/forge/evil/$name.java").use { stream ->
                String(requireNotNull(stream) { "no forge/evil/$name.java" }.readAllBytes(), Charsets.UTF_8)
            }

        /** [name] compiled against the SDK, checked clean by the guard, rewritten: what the Forge builds. Off the server thread. */
        fun build(name: String): Map<String, ByteArray> {
            val compiled = ForgeSamples.compile(name, source(name))
            check(compiled.ok) { "$name: ${compiled.problems}" }
            val violations = BytecodeGuard().check(compiled.classes)
            check(violations.isEmpty()) { "$name is refused by the guard, not reached at runtime: $violations" }
            return Instrumenter.rewrite(name, compiled.classes)
        }
    }
}
