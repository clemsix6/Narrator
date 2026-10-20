package io.github.clemsix6.narrator2.forge.host

import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.forge.api.ModuleContext
import io.github.clemsix6.narrator2.forge.api.StopReason
import io.github.clemsix6.narrator2.forge.sandbox.FakeContext
import io.github.clemsix6.narrator2.testing.BusyMachine
import io.github.clemsix6.narrator2.testing.ForgeSamples
import io.github.clemsix6.narrator2.testing.TestClock
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.lang.ref.WeakReference
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The host (M5 §3.5, §3.7): a module loaded by its own loader, made and started under its meter on the module thread,
 * its faults counted (2 limits, 3 exceptions in ten minutes; at once in its constructor or `start`), its slowness, its
 * allocation, its retained memory; nothing but the text of an exception kept; unloaded and collected.
 *
 * The modules run with [BusyMachine.TIME_SCALE] (a call 10 s, the margin 2.5 s): a busy machine never stops them before
 * the limit a test is about. A test of the meter's time gives its calls the game's budget ([ForgeLimits.CALL_NANOS]).
 */
class ModuleHostTest {
    private val threads = ModuleThreads("test-module")

    @AfterEach
    fun close() {
        threads.close()
    }

    @Test
    fun `the witness module is made and started on its module thread, its SDK calls on the caller's`() {
        val fake = FakeContext()
        val caller = Thread.currentThread()
        val seen = mutableListOf<Thread>()
        val recording = record(fake.context) { seen += Thread.currentThread() }
        val host = ModuleHost(ForgeSamples.CLASS, Hosts.build(ForgeSamples.CLASS, ForgeSamples.SOURCE), threads, "C-70 tag_game", timeScale = BusyMachine.TIME_SCALE)
        assertTrue(host.started(recording), "started: ${host.disabled}")
        assertNull(host.disabled)
        assertEquals(2, fake.handlers.size, "it subscribed to two facts")
        assertEquals(2, fake.timers.size, "and set two timers")
        assertTrue(seen.isNotEmpty() && seen.all { it == caller }, "every SDK call ran on the caller's thread: ${seen.toSet()}")
        repeat(25) { assertNull(host.call("timer") { fake.timers[0].run() }?.fault) }
        assertNull(host.stop(StopReason.EXPIRED)?.fault)
        assertTrue(fake.logs.any { "the round ran out" in it }, "${fake.logs}")
        assertEquals(0, host.faults)
    }

    @Test
    fun `a call made from the module thread nests in the running one, metered on its own within what that call has left`() {
        val fake = FakeContext()
        val faults = mutableListOf<Fault>()
        val source = Hosts.source(
            "Nester",
            "public static long n; long sink;",
            "m.timers().every(1, () -> { while (true) { } }); m.timers().every(1, () -> m.log(\"quick\"));" +
                "m.timers().every(1, () -> { long x = 0; for (long i = 0; i < n; i++) x += i ^ (x >>> 3); sink = x; });",
        )
        val host = Hosts.host("Nester", source, threads, faults = faults, timeScale = BusyMachine.TIME_SCALE)
        assertTrue(host.started(fake.context))
        host.main.getField("n").setLong(null, Hosts.rounds(100))
        val outer = mutableListOf<Thread>()
        val inner = mutableListOf<Thread>()
        val nested = mutableListOf<ModuleHost.Called?>()
        // The bench's shape: a scenario's call (a long budget) whose code runs the module's handlers; the loop with the
        // game's 20 ms, the quick ones with time to spare.
        val scenario = host.call("scenario", 10 * SECOND) {
            outer += Thread.currentThread()
            nested += host.call("timer", BusyMachine.PATIENT_NANOS) { inner += Thread.currentThread(); fake.timers[1].run() }
            nested += host.call("timer", ForgeLimits.CALL_NANOS) { fake.timers[0].run() }
            nested += host.call("timer", BusyMachine.PATIENT_NANOS) { fake.timers[1].run() }
        }!!
        assertNull(scenario.fault, "the scenario's own call goes on after a nested fault: $faults")
        assertEquals(outer, inner, "no new hand-over: the same module thread")
        assertNull(nested[0]!!.fault)
        val looped = nested[1]!!
        assertEquals(Fault.Kind.LIMIT, looped.fault?.kind)
        // Its own work, which a busy machine does not stretch (M5-LOAD): the meter stops it at 20 ms of it (the CPU clock's
        // two steps of 15.6 ms may read a little more).
        val ownBudget = ForgeLimits.CALL_MILLIS.toDouble()..(ForgeLimits.CALL_MILLIS + 32).toDouble()
        assertTrue(looped.work / 1e6 in ownBudget, "the nested loop stopped at its own 20 ms, not the scenario's 10 s: ${looped.work / 1e6} ms")
        assertNull(nested[2]!!.fault, "the running call resumed, its meter as it was")
        assertEquals(listOf("quick", "quick"), fake.logs.toList())

        // A nested call never outlives the call it nests in: here 600 ms of work in all, about 100 of them spent before it
        // (a nested call of 100 ms of work, measured on this machine), so it has about 500 ms left of the 2 s it asks for.
        host.call("scenario", 600 * MILLI) {
            nested += host.call("timer", SECOND) { fake.timers[2].run() }
            nested += host.call("timer", 2 * SECOND) { fake.timers[0].run() }
        }!!
        val first = nested[nested.size - 2]!!
        val last = nested.last()!!
        assertNull(first.fault)
        assertEquals(Fault.Kind.LIMIT, last.fault?.kind)
        val left = 600 - first.work / 1e6
        // The CPU clock's steps of 15.6 ms may read a little more than the meter counted, for either call.
        assertTrue(last.work / 1e6 in left - 5..left + 64, "it had only what was left ($left ms), not its 2 s: ${last.work / 1e6} ms of work")
    }

    @Test
    fun `a call nested in a call given up is refused, so its thread unwinds`() {
        val fake = FakeContext()
        val host = Hosts.host("Quick", Hosts.source("Quick", body = "m.timers().every(1, () -> m.log(\"ran\"));"), threads, timeScale = BusyMachine.TIME_SCALE)
        assertTrue(host.started(fake.context))
        val refused = AtomicReference<Any>()
        val given = CountDownLatch(1)
        val done = CountDownLatch(1)
        // A call with the game's budget whose thread waits (no work: the machine's doing) until its caller gave it up.
        val outer = host.call("scenario", ForgeLimits.CALL_NANOS) {
            given.await(60, TimeUnit.SECONDS)
            try {
                refused.set(host.call("timer") { fake.timers[0].run() } ?: "nothing ran")
            } catch (thrown: Throwable) {
                refused.set(thrown)
            }
            done.countDown()
        }!!
        given.countDown()
        assertNull(outer.fault, "given up without computing: nobody's fault (M5-LOAD)")
        assertTrue(done.await(60, TimeUnit.SECONDS))
        assertTrue(refused.get() is io.github.clemsix6.narrator2.forge.rt.ForgeLimit, "the module threads refuse it: ${refused.get()}")
        assertEquals(emptyList(), fake.logs.toList(), "the module's code never ran on the thread given up")
        assertTrue(Hosts.settled(host))
        assertEquals(0, host.faults)
        assertEquals(1, host.starved)
        assertNull(host.call("timer") { fake.timers[0].run() }!!.fault, "the module goes on")
        assertEquals(listOf("ran"), fake.logs.toList())
    }

    @Test
    fun `the bench's members - its class not initialized, a line, a measure of what it retains`() {
        val fake = FakeContext()
        val source = Hosts.source("Benched", "static final List<String> kept = new ArrayList<>(); static { kept.add(\"x\".repeat(1_000)); }", "throw new IllegalStateException(\"no\");")
        val host = Hosts.host("Benched", source, threads, timeScale = BusyMachine.TIME_SCALE)
        assertEquals("Benched", host.main.name)
        val measured = assertNotNull(host.measure(emptyList()), "a measure, even before the module was made")
        assertTrue(measured.bytes >= 2_000, "its static field, initialized in the measure's call: $measured")
        host.start(fake.context)
        assertTrue(host.disabled!!.line > 0)
        assertEquals(0, host.line(IllegalStateException("not the module's")))
    }

    @Test
    fun `two limits in ten minutes disable it, one ten minutes old does not count`() {
        val clock = TestClock()
        val faults = mutableListOf<Fault>()
        val fake = FakeContext()
        val host = Hosts.host("Looper", Hosts.source("Looper", body = "m.timers().every(1, () -> { while (true) { } });"), threads, clock, faults, timeScale = BusyMachine.TIME_SCALE)
        assertTrue(host.started(fake.context))
        // The game's budget: the meter stops each loop at 20 ms, and the margin (2.5 s here) lets it unwind unabandoned.
        val loop = { host.call("timer", ForgeLimits.CALL_NANOS) { fake.timers[0].run() } }
        val first = loop()?.fault
        assertEquals(Fault.Kind.LIMIT, first?.kind)
        assertTrue(first!!.cause.startsWith("time budget at Looper.java:"), first.cause)
        assertTrue(first.line > 0)
        assertFalse(first.disables)
        clock.advance(Duration.ofMinutes(11))
        assertFalse(loop()!!.fault!!.disables, "the first one is out of the window")
        clock.advance(Duration.ofMinutes(1))
        val third = loop()?.fault
        assertTrue(third!!.disables)
        assertEquals(third, host.disabled)
        assertEquals("limit in timer: ${third.cause}", third.text)
        assertEquals(3, host.faults)
        assertEquals(faults, listOf(first, faults[1], third))
        assertNull(host.call("timer") { fake.timers[0].run() }, "nothing runs once disabled")
    }

    @Test
    fun `three exceptions in ten minutes disable it, and only their text is kept`() {
        val clock = TestClock()
        val fake = FakeContext()
        val members = "static final class Oops extends IllegalStateException { Oops(String s) { super(s); } }"
        val host = Hosts.host("Thrower", Hosts.source("Thrower", members, "m.timers().every(1, () -> { throw new Oops(\"boom\"); });"), threads, clock, timeScale = BusyMachine.TIME_SCALE)
        assertTrue(host.started(fake.context))
        repeat(2) {
            val fault = host.call("timer") { fake.timers[0].run() }!!.fault!!
            assertEquals(Fault.Kind.EXCEPTION, fault.kind)
            assertTrue(fault.cause.startsWith("Thrower\$Oops: boom at Thrower.java:"), fault.cause)
            assertFalse(fault.disables)
            clock.advance(Duration.ofMinutes(4))
        }
        assertTrue(host.call("timer") { fake.timers[0].run() }!!.fault!!.disables)
        assertEquals(Fault.Kind.EXCEPTION, host.disabled?.kind)
    }

    @Test
    fun `a limit of Guarded is an exception, with the module's line`() {
        val fake = FakeContext()
        val host = Hosts.host("Grower", Hosts.source("Grower", body = "m.timers().every(1, () -> { List<Integer> l = new ArrayList<>(); while (true) l.add(1); });"), threads, timeScale = BusyMachine.TIME_SCALE)
        assertTrue(host.started(fake.context))
        val fault = host.call("timer") { fake.timers[0].run() }!!.fault!!
        assertEquals(Fault.Kind.EXCEPTION, fault.kind)
        assertTrue(fault.cause.startsWith("ForgeLimit: a collection over 10000 elements at Grower.java:"), fault.cause)
        assertTrue(fault.line > 0)
    }

    @Test
    fun `a fault in the constructor or in start disables it at once`() {
        val fake = FakeContext()
        val faults = mutableListOf<Fault>()
        val made = Hosts.host("BadMaker", Hosts.source("BadMaker", "public BadMaker() { throw new IllegalStateException(\"no\"); }"), threads, faults = faults, timeScale = BusyMachine.TIME_SCALE)
        assertFalse(made.started(fake.context))
        assertEquals(Fault.Kind.EXCEPTION, made.disabled?.kind)
        assertEquals(ModuleHost.CONSTRUCTOR, made.disabled?.call)
        assertTrue(made.disabled!!.cause.startsWith("IllegalStateException: no at BadMaker.java:"), made.disabled!!.cause)

        val init = Hosts.host("BadInit", Hosts.source("BadInit", "static final int X = Integer.parseInt(\"x\");"), threads, timeScale = BusyMachine.TIME_SCALE)
        assertFalse(init.started(fake.context), "its static initializer runs, metered, in the constructor's call")
        assertTrue(init.disabled!!.cause.startsWith("NumberFormatException"), init.disabled!!.cause)

        // Its loop runs to the end of `start`'s budget: 500 ms here (ten times the game's), which its constructor never
        // reaches on a busy machine; stopped by its meter or abandoned, the fault is the same.
        val started = Hosts.host("BadStart", Hosts.source("BadStart", body = "while (true) { }"), threads, timeScale = 10)
        assertFalse(started.started(fake.context))
        assertEquals(Fault.Kind.LIMIT, started.disabled?.kind)
        assertEquals(ModuleHost.START, started.disabled?.call)
    }

    @Test
    fun `over 50 ms of its code a second, five seconds in a row, disables it`() {
        val fake = FakeContext()
        // Calls of 10 s: 18 ms of the module's own work each (rounds of a loop, measured on this machine) is never
        // stopped, and four of them are 72 ms a second, however long a busy machine makes them on the wall clock.
        val host = Hosts.host("Rounds", Hosts.source("Rounds", Hosts.ROUNDS, Hosts.ROUNDS_BODY), threads, timeScale = BusyMachine.TIME_SCALE)
        assertTrue(host.started(fake.context))
        host.main.getField("n").setLong(null, Hosts.rounds(18))
        host.second()
        repeat(ForgeLimits.SLOW_SECONDS) { second ->
            repeat(4) { assertNull(host.call("timer") { fake.timers[0].run() }!!.fault, "18 ms is within a call's budget") }
            val fault = host.second()
            if (second < ForgeLimits.SLOW_SECONDS - 1) assertNull(fault) else assertEquals(Fault.Kind.SLOWNESS, fault?.kind)
        }
        assertEquals(Fault.Kind.SLOWNESS, host.disabled?.kind)
        assertTrue(host.disabled!!.cause.startsWith("over 50 ms of its code a second, 5 seconds in a row ("), host.disabled!!.cause)
    }

    @Test
    fun `a second whose calls a busy machine stretched, but that did little work, is not slow`() {
        val fake = FakeContext()
        val host = Hosts.host("Light", Hosts.source("Light", "int n;", "m.timers().every(1, () -> n++);"), threads, timeScale = BusyMachine.TIME_SCALE)
        assertTrue(host.started(fake.context))
        host.second()
        repeat(ForgeLimits.SLOW_SECONDS + 1) {
            // Each call held back 20 ms by the machine (a sleep on the module thread: no work, no CPU), as a saturated
            // machine stretched the orbit of M5-15's chickens to 82 ms a second.
            repeat(4) { assertNull(host.call("timer") { Thread.sleep(20); fake.timers[0].run() }!!.fault) }
            assertNull(host.second())
            assertTrue(host.msPerSecond >= 80, "the wall clock says ${host.msPerSecond} ms")
        }
        assertNull(host.disabled)
    }

    @Test
    fun `allocating over 256 MiB in a minute disables it`() {
        val fake = FakeContext()
        val churn = "m.timers().every(1, () -> { long sum = 0; for (int i = 0; i < 24; i++) { long[] a = new long[60_000]; sum += a.length; } m.log(\"\" + sum); });"
        val host = Hosts.host("Churner", Hosts.source("Churner", body = churn), threads, timeScale = BusyMachine.TIME_SCALE)
        assertTrue(host.started(fake.context))
        val first = host.call("timer") { fake.timers[0].run() }!!
        assumeTrue(first.allocated >= 0, "the JVM measures allocation")
        assertTrue(first.allocated in (10L shl 20)..(16L shl 20), "about 11 MiB a call: ${first.allocated}")
        var calls = 1
        while (host.disabled == null && calls < 40) {
            host.call("timer") { fake.timers[0].run() }
            calls++
        }
        assertEquals(Fault.Kind.MEMORY, host.disabled?.kind)
        assertTrue(host.disabled!!.cause.startsWith("allocates too much"), host.disabled!!.cause)
        assertTrue(calls in 22..26, "disabled past 256 MiB: after $calls calls")
    }

    @Test
    fun `the gauge measures what it keeps, and disables it past 32 MiB or past its ceiling`() {
        val fake = FakeContext()
        val keeper = Hosts.host(
            "Keeper",
            Hosts.source("Keeper", "static final List<int[]> kept = new ArrayList<>();", "m.timers().every(1, () -> { for (int i = 0; i < 50; i++) kept.add(new int[65_536]); });"),
            threads,
            timeScale = BusyMachine.TIME_SCALE,
        )
        assertTrue(keeper.started(fake.context))
        assertNull(keeper.gauge())
        assertTrue(keeper.retained!!.bytes < 1L shl 20, "it keeps little at first: ${keeper.retained}")
        repeat(3) { keeper.call("timer") { fake.timers[0].run() } }
        val fault = keeper.gauge()
        assertEquals(Fault.Kind.MEMORY, fault?.kind)
        assertEquals("retains over 32 MiB (at most 32)", fault!!.cause, "37 MiB kept: the walk stops once past the ceiling, the verdict known")

        val wide = Hosts.host(
            "Wide",
            Hosts.source(
                "Wide", "final List<List<Object>> all = new ArrayList<>();",
                "m.timers().every(1, () -> { for (int i = 0; i < 1_000; i++) { List<Object> l = new ArrayList<>(); for (int j = 0; j < 25; j++) l.add(new Object()); all.add(l); } });",
            ),
            threads,
            timeScale = BusyMachine.TIME_SCALE,
        )
        assertTrue(wide.started(fake.context))
        repeat(9) { assertNull(wide.call("timer") { fake.timers.last().run() }?.fault) }
        // 234,000 objects: past the gauge's ceiling of 200,000, in one measure, however slow or busy the machine (M5-LOAD:
        // its ceilings are work, not time).
        val tooWide = wide.gauge()
        assertFalse(wide.retained!!.complete, "the measure stops at its ceiling: ${wide.retained}")
        assertTrue(wide.retained!!.objects in ForgeLimits.GAUGE_MAX_OBJECTS + 1..ForgeLimits.GAUGE_MAX_OBJECTS + 100, "${wide.retained}")
        assertEquals(Fault.Kind.MEMORY, tooWide?.kind)
        assertEquals("retains too much to measure (over ${ForgeLimits.GAUGE_MAX_OBJECTS} objects)", tooWide!!.cause)
    }

    @Test
    fun `the gauge walks a cycle and a structure shared ten thousand times once`() {
        val fake = FakeContext()
        val members = "static final class Node { Node next; } Node ring; List<List<Integer>> shared;"
        val body = """
            Node first = new Node(); Node at = first;
            for (int i = 0; i < 1_000; i++) { at.next = new Node(); at = at.next; }
            at.next = first; ring = first;
            List<Integer> l0 = new ArrayList<>(); for (int i = 0; i < 10_000; i++) l0.add(i);
            shared = new ArrayList<>(); for (int i = 0; i < 10_000; i++) shared.add(l0);
        """.trimIndent()
        // Walked once each, the 10,000 lists are 11,000 to 22,000 objects; walked as often as they are held, a hundred
        // million (past the ceiling of 200,000 objects).
        val host = Hosts.host("Graph", Hosts.source("Graph", members, body), threads, timeScale = BusyMachine.TIME_SCALE)
        assertTrue(host.started(fake.context))
        assertNull(host.gauge(), "${host.retained}")
        val measure = assertNotNull(host.retained)
        assertTrue(measure.complete, "$measure")
        assertTrue(measure.objects in 11_000..22_000, "each object once: ${measure.objects}")
        assertNull(host.disabled)
        println("[host] the gauge walked a ring and a structure shared ten thousand times: ${measure.objects} objects in %.2f ms".format(measure.nanos / 1e6))
    }

    @Test
    fun `twenty unloaded loaders out of twenty are collected, an exception and a context kept by the module included`() {
        val references = mutableListOf<WeakReference<ClassLoader>>()
        val keeper = Hosts.build(
            "Clinger",
            Hosts.source(
                "Clinger",
                "static ModuleContext kept; static Runnable task; static final class Oops extends RuntimeException { Oops() { super(\"kept\"); } }",
                "kept = m; task = () -> m.log(\"tick\"); m.timers().every(1, task); m.events().on(GameFact.Chat.class, c -> m.log(c.text())); throw new Oops();",
            ),
        )
        val witness = Hosts.build(ForgeSamples.CLASS, ForgeSamples.SOURCE)
        for (i in 0 until 20) {
            references += if (i % 2 == 0) runAndUnload(ForgeSamples.CLASS, witness, "C-$i") else runAndUnload("Clinger", keeper, "C-$i")
        }
        assertTrue(Hosts.collected(references), "${references.count { it.get() != null }} of 20 loaders are still alive")
    }

    /**
     * A deployment from its start to its unloading, in a frame of its own: its context (which keeps the module's
     * handlers and timers, as the SDK does until its end) must not stay in a slot of the test's frame.
     */
    private fun runAndUnload(main: String, classes: Map<String, ByteArray>, label: String): WeakReference<ClassLoader> {
        val fake = FakeContext()
        val host = ModuleHost(main, classes, threads, label, timeScale = BusyMachine.TIME_SCALE)
        host.start(fake.context)
        fake.timers.forEach { timer -> host.call("timer") { timer.run() } }
        host.stop(StopReason.EXPIRED)
        return host.unload()
    }

    @Test
    fun `unloading ends the module thread its code ran on, and the last exception the JVM keeps there`() {
        val ran = AtomicReference<Thread>()
        val loader = throwAndUnload(ran)
        ran.get().join(30_000)
        assertFalse(ran.get().isAlive, "the module thread ended at the unloading: ${ran.get()}")
        assertTrue(Hosts.collected(listOf(loader)), "the module's loader is collected")
    }

    /** A module whose timer throws, run once and unloaded, in a frame of its own (as [runAndUnload]); the thread its code ran on in [ran]. */
    private fun throwAndUnload(ran: AtomicReference<Thread>): WeakReference<ClassLoader> {
        val fake = FakeContext()
        val members = "static final class Oops extends IllegalStateException { Oops() { super(\"kept\"); } }"
        val host = Hosts.host("Leaver", Hosts.source("Leaver", members, "m.timers().every(1, () -> { throw new Oops(); });"), threads, timeScale = BusyMachine.TIME_SCALE)
        assertTrue(host.started(fake.context))
        assertEquals(Fault.Kind.EXCEPTION, host.call("timer") { ran.set(Thread.currentThread()); fake.timers[0].run() }?.fault?.kind)
        return host.unload()
    }

    @Test
    fun `the leak watch tells a loader still alive two minutes and two collections later, once`() {
        val clock = TestClock()
        var collections = 0L
        val watch = LeakWatch(clock) { collections }
        val kept = ClassLoader.getPlatformClassLoader()
        watch.watch("C-70 kept", WeakReference(kept))
        watch.watch("C-71 gone", WeakReference(null))
        assertEquals(1, watch.watching())
        clock.advance(Duration.ofMinutes(3))
        assertEquals(emptyList(), watch.check(), "two minutes, but no collection yet")
        collections = 2
        assertEquals(listOf("C-70 kept"), watch.check())
        assertEquals(emptyList(), watch.check(), "told once")
        assertEquals(1, watch.leaks)
    }

    /** [context] behind a recorder that notes each SDK call (any interface it hands out included). */
    private fun record(context: ModuleContext, note: () -> Unit): ModuleContext = recorder(ModuleContext::class.java, context, note)

    private fun <T : Any> recorder(type: Class<T>, target: T, note: () -> Unit): T =
        type.cast(
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, arguments ->
                note()
                val result = method.invoke(target, *(arguments ?: arrayOf()))
                if (result != null && method.returnType.isInterface && method.returnType.packageName == type.packageName) {
                    @Suppress("UNCHECKED_CAST")
                    recorder(method.returnType as Class<Any>, result, note)
                } else {
                    result
                }
            },
        )

    private companion object {
        const val MILLI = 1_000_000L
        const val SECOND = 1_000 * MILLI
    }
}
