package io.github.clemsix6.narrator2.forge.host

import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.forge.api.StopReason
import io.github.clemsix6.narrator2.forge.sandbox.FakeContext
import io.github.clemsix6.narrator2.testing.BusyMachine
import io.github.clemsix6.narrator2.testing.TestClock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.lang.management.GarbageCollectorMXBean
import java.lang.management.ManagementFactory
import java.lang.ref.WeakReference
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The leak watch on real modules and the JVM's own collections (M5-FIX-3, M5 §3.7). A module that ended well is never
 * told a leak: the young collections, which never unload classes, no longer count (they did, and M5-22 saw a leak told
 * after each module's end); its loader is forgotten once a collection that unloads classes takes it. A module that left
 * a callback hanging in a structure of the server is still told, once.
 */
class LeakWatchTest {
    private val threads = ModuleThreads("test-leak")

    @AfterEach
    fun close() {
        threads.close()
    }

    @Test
    fun `young collections keep a module's classes, so counting them told a false leak, and the watch now waits for one that unloads classes`() {
        val young = g1Young()
        val clock = TestClock()
        val canaries = UnloadingCollections()
        val watch = LeakWatch(clock, canaries::count)
        val counted = LeakWatch(clock) { ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionCount } } // before M5-FIX-3
        val loader = runAndUnload("Ended", hanging = null)
        watch.watch("C-70 Ended", loader)
        counted.watch("C-70 Ended", loader)
        val unloadings = canaries.count()

        collectYoung(young, ForgeLimits.LEAK_GC_CYCLES + 1)
        assumeTrue(canaries.count() == unloadings, "a collection that unloads classes came meanwhile")
        assertNotNull(loader.get(), "a young collection keeps the data of every class loader as a root")
        clock.advance(Duration.ofMillis(ForgeLimits.LEAK_CHECK_MS))
        assertEquals(listOf("C-70 Ended"), counted.check(), "every collection counted: a false leak, as M5-22 saw")
        assertEquals(emptyList(), watch.check(), "no collection that unloads classes yet: not judged")
        assertEquals(1, watch.watching())

        assertTrue(Hosts.collected(listOf(loader)), "the first collection that unloads classes takes it")
        assertTrue(canaries.count() > unloadings, "the canary saw it")
        assertEquals(emptyList(), watch.check())
        assertEquals(0, watch.watching(), "forgotten")
        assertEquals(0, watch.leaks)
    }

    @Test
    fun `a module that leaves a callback hanging in a structure of the server is told a leak once, after the delay and two collections that unload classes`() {
        val clock = TestClock()
        val canaries = UnloadingCollections()
        val watch = LeakWatch(clock, canaries::count)
        val structure = mutableListOf<Runnable>() // the server's, outliving the module
        val loader = runAndUnload("Clinger", hanging = structure)
        watch.watch("C-71 Clinger", loader)
        val unloadings = canaries.count()

        collectUntil("${ForgeLimits.LEAK_GC_CYCLES} collections that unload classes") { canaries.count() >= unloadings + ForgeLimits.LEAK_GC_CYCLES }
        assertNotNull(loader.get(), "held by its callback")
        assertEquals(emptyList(), watch.check(), "${ForgeLimits.LEAK_CHECK_MS / 1_000} s not past yet")
        clock.advance(Duration.ofMillis(ForgeLimits.LEAK_CHECK_MS))
        assertEquals(listOf("C-71 Clinger"), watch.check())
        assertEquals(emptyList(), watch.check(), "told once")
        assertEquals(1, watch.leaks)

        structure.clear()
        assertTrue(Hosts.collected(listOf(loader)), "let go by the structure, it goes")
    }

    @Test
    fun `the watch the game builds tells a hanging callback and forgets a module that ended well`() {
        val clock = TestClock()
        val watch = LeakWatch(clock)
        val structure = mutableListOf<Runnable>()
        val ended = runAndUnload("Ended", hanging = null)
        val clinging = runAndUnload("Clinger", hanging = structure)
        watch.watch("C-72 Ended", ended)
        watch.watch("C-73 Clinger", clinging)
        clock.advance(Duration.ofMillis(ForgeLimits.LEAK_CHECK_MS))

        val told = mutableListOf<String>()
        collectUntil("the hanging callback told") {
            told += watch.check()
            told.isNotEmpty()
        }
        assertEquals(listOf("C-73 Clinger"), told)
        assertEquals(null, ended.get(), "collected, never told")
        assertEquals(0, watch.watching())
        assertEquals(1, watch.leaks)
        structure.clear()
    }

    /**
     * A module started, its timer run once, stopped and unloaded, in a frame of its own; its timer's callback, a lambda
     * of the module, left in [hanging] when given, as a structure of the server that missed its end would keep it.
     */
    private fun runAndUnload(name: String, hanging: MutableList<Runnable>?): WeakReference<ClassLoader> {
        val fake = FakeContext()
        val host = Hosts.host(name, Hosts.source(name, "int ticks;", "m.timers().every(1, () -> ticks++);"), threads, timeScale = BusyMachine.TIME_SCALE)
        assertTrue(host.started(fake.context), "${host.disabled}")
        assertEquals(null, host.call("timer") { fake.timers[0].run() }?.fault)
        hanging?.addAll(fake.timers)
        host.stop(StopReason.EXPIRED)
        return host.unload()
    }

    private companion object {
        /** Short-lived garbage, out of reach of the JIT's escape analysis. */
        @Volatile
        var sink: Any? = null

        /** G1's young collector; the test is skipped under another collector. */
        fun g1Young(): GarbageCollectorMXBean {
            val young = ManagementFactory.getGarbageCollectorMXBeans().firstOrNull { it.name == "G1 Young Generation" }
            assumeTrue(young != null, "G1 runs the tests")
            return young!!
        }

        /** Allocates short-lived garbage, in arrays far below G1's humongous size, until [young] ran [count] more times. */
        fun collectYoung(young: GarbageCollectorMXBean, count: Int) {
            val target = young.collectionCount + count
            while (young.collectionCount < target) sink = ByteArray(64 * 1024)
            sink = null
        }

        /** Collects until [condition] holds, 30 s at most. */
        fun collectUntil(what: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + 30_000_000_000L
            while (!condition()) {
                check(System.nanoTime() - deadline < 0) { "timed out waiting for $what" }
                System.gc()
                Thread.sleep(20)
            }
        }
    }
}
