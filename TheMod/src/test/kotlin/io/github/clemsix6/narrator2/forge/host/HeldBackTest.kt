package io.github.clemsix6.narrator2.forge.host

import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.forge.api.ModuleContext
import io.github.clemsix6.narrator2.forge.sandbox.FakeContext
import io.github.clemsix6.narrator2.testing.BusyMachine
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a busy machine does is not the module's doing (M5-LOAD): a fault is the module's only when it computed — its own
 * code's work, its thread's CPU time —, never for the wall clock alone. The game's budgets throughout (20 ms a call, 5
 * more before the caller gives up, 50 for a start); the machine holding a thread back is a sleep, on the module thread
 * (no work, no CPU) or on the caller while it serves an SDK request: deterministic, whatever the machine does besides.
 */
class HeldBackTest {
    private val threads = ModuleThreads("held-module")
    private val faults = mutableListOf<Fault>()

    @AfterEach
    fun close() {
        threads.close()
    }

    @Test
    fun `a call held back past the caller's deadline is given up without a fault, runs to its end alone, and the module goes on`() {
        val fake = FakeContext()
        val host = Hosts.host("Counter", Hosts.source("Counter", "public static int n;", "m.timers().every(1, () -> n++);"), threads, faults = faults)
        assertTrue(host.started(fake.context), "${host.disabled}")

        // Held back 500 ms: far more than a busy machine delays the caller after its deadline (FLAKY-4: 137 ms at most).
        val called = host.call("timer") { Thread.sleep(500); fake.timers[0].run() }!!
        assertNull(called.fault)
        assertTrue(called.nanos / 1e6 < BUDGET_MS + BusyMachine.SLACK_MILLIS, "the caller went on at its deadline: ${called.nanos / 1e6} ms")
        assertTrue(host.busy())
        val meanwhile = host.call("timer") { fake.timers[0].run() }!!
        assertTrue(meanwhile.skipped, "while it runs alone, the module's calls are skipped: never two calls of a module at once")
        assertEquals(0L, meanwhile.nanos, "skipped at once, never handed over to a module thread")
        assertTrue(Hosts.settled(host))

        assertEquals(emptyList(), faults, "nobody's fault")
        assertEquals(1, host.starved)
        assertEquals(1, host.skipped)
        assertNull(host.call("timer") { fake.timers[0].run() }!!.fault, "the module goes on")
        assertEquals(2, host.main.getField("n").getInt(null), "the call given up ran to its end, alone; the skipped one did not run")
        assertEquals(0, threads.lost.alive(), "no thread lost")
    }

    @Test
    fun `the meter counts the module's work, so a call held back past its budget is not stopped`() {
        val fake = FakeContext()
        // A patient caller (the margin of a busy machine), the meter at the game's 20 ms.
        val host = Hosts.host("Counter", Hosts.source("Counter", "public static int n;", "m.timers().every(1, () -> { for (int i = 0; i < 1_000; i++) n++; });"), threads, faults = faults, abandonMarginNanos = BusyMachine.SLACK_NANOS)
        assertTrue(host.started(fake.context))
        val called = host.call("timer", ForgeLimits.CALL_NANOS) { Thread.sleep(40); fake.timers[0].run(); Thread.sleep(40) }!!
        assertNull(called.fault, "80 ms of wall clock, a few microseconds of work")
        assertTrue(called.nanos >= 80_000_000)
        assertTrue(called.work < ForgeLimits.CALL_NANOS, "its own work: ${called.work / 1e6} ms")
        assertEquals(1_000, host.main.getField("n").getInt(null))
    }

    @Test
    fun `a loop its meter stopped, given up while it unwinds, is one limit fault, not an abandon`() {
        val fake = FakeContext()
        val host = Hosts.host("Looper", Hosts.source("Looper", body = "m.timers().every(1, () -> { while (true) { } });"), threads, faults = faults)
        assertTrue(host.started(fake.context))
        // The meter stops the loop at 20 ms of its work; the machine then holds its thread back 40 ms as it unwinds,
        // past the caller's 25 ms (FLAKY-4: 1 in 100 such loops under load; before M5-LOAD, disabled at once).
        val called = host.call("timer") {
            try {
                fake.timers[0].run()
            } finally {
                Thread.sleep(40)
            }
        }!!
        assertNull(called.fault, "given up: judged once its thread ended")
        assertTrue(Hosts.settled(host))
        val fault = faults.single()
        assertEquals(Fault.Kind.LIMIT, fault.kind)
        assertTrue(fault.cause.startsWith("time budget at Looper.java:"), fault.cause)
        assertFalse(fault.disables, "the first limit fault, as a loop its meter stopped in time")
        assertNull(host.disabled)
        assertEquals(0, threads.lost.alive(), "its thread ended: none lost")
    }

    @Test
    fun `a start held back past its 55 ms is waited for, and starts`() {
        val fake = FakeContext()
        // The caller serves the start's first SDK call held back 120 ms (the machine, on the server thread).
        val held = proxy(fake.context) { Thread.sleep(120) }
        val host = Hosts.host("Starter", Hosts.source("Starter", body = "m.timers().every(1, () -> m.log(\"tick\"));"), threads, faults = faults)
        val started = host.start(held)!!
        assertNull(started.fault)
        assertNull(host.disabled, "a start is waited for while its thread has not computed (before M5-LOAD: « ran past 55 ms », disabled)")
        assertNull(host.stalled)
        assertTrue(started.nanos > (ModuleHost.STARTUP_CALL_NANOS + ModuleHost.ABANDON_MARGIN_NANOS), "${started.nanos / 1e6} ms")
        assertEquals(1, fake.timers.size)
        assertNull(host.call("timer") { fake.timers[0].run() }!!.fault)
        assertEquals(listOf("tick"), fake.logs.toList())
    }

    @Test
    fun `a patient caller, the bench, waits for a call held back, and gives up one that computes`() {
        val fake = FakeContext()
        val source = Hosts.source(
            "Patient", "public static int n;",
            "m.timers().every(1, () -> n++); String text = \"a\".repeat(65_535) + \"b\"; String pattern = \"a\".repeat(32_767) + \"b\"; m.timers().every(1, () -> { while (true) { if (text.indexOf(pattern) < 0) m.log(\"never\"); } });",
        )
        val host = Hosts.host("Patient", source, threads, faults = faults, patienceNanos = BusyMachine.PATIENT_NANOS)
        assertTrue(host.started(fake.context))
        val held = host.call("timer") { Thread.sleep(80); fake.timers[0].run() }!!
        assertNull(held.fault, "waited for: no work, no fault")
        assertFalse(held.skipped || host.busy(), "never given up")
        assertTrue(held.nanos >= 80_000_000)
        assertEquals(1, host.main.getField("n").getInt(null))

        // One indexOf of 75 ms at a time, in a loop: given up once its thread's CPU time shows its budget, 40 ms at least.
        val computing = host.call("timer") { fake.timers[1].run() }!!
        val fault = checkNotNull(computing.fault) { "given up while computing: a fault" }
        assertEquals(Fault.Kind.LIMIT, fault.kind)
        assertTrue(fault.cause.startsWith("time budget: one call ran past 25 ms and was abandoned"), fault.cause)
        assertTrue(fault.disables)
    }

    @Test
    fun `a second of calls a busy machine stretched is not slow, its work is`() {
        val fake = FakeContext()
        val host = Hosts.host("Counter", Hosts.source("Counter", "public static int n;", "m.timers().every(1, () -> n++);"), threads, faults = faults, abandonMarginNanos = BusyMachine.SLACK_NANOS)
        assertTrue(host.started(fake.context))
        host.second()
        repeat(ForgeLimits.SLOW_SECONDS + 2) {
            // 20 calls a second, each held back 4 ms: 80 ms of wall clock a second (M5-15 measured 82 under load).
            repeat(20) { assertNull(host.call("timer") { Thread.sleep(4); fake.timers[0].run() }!!.fault) }
            assertNull(host.second())
            assertTrue(host.msPerSecond >= ForgeLimits.DEPLOYMENT_MS_PER_SECOND, "${host.msPerSecond} ms of wall clock")
        }
        assertNull(host.disabled)
    }

    /** [context] behind a proxy whose first SDK call runs [hold] first (on the caller, which serves the module's SDK calls). */
    private fun proxy(context: ModuleContext, hold: () -> Unit): ModuleContext {
        var first = true
        return ModuleContext::class.java.cast(
            Proxy.newProxyInstance(ModuleContext::class.java.classLoader, arrayOf(ModuleContext::class.java)) { _, method, arguments ->
                if (first) {
                    first = false
                    hold()
                }
                method.invoke(context, *(arguments ?: arrayOf()))
            },
        )
    }

    private companion object {
        val BUDGET_MS = ForgeLimits.CALL_MILLIS + ModuleHost.ABANDON_MARGIN_NANOS / 1_000_000
    }
}
