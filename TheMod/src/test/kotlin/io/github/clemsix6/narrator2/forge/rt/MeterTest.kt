package io.github.clemsix6.narrator2.forge.rt

import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.forge.api.Limits
import io.github.clemsix6.narrator2.testing.BusyMachine
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The meter's contract (M5 §3.4, M5-01 handoff), the meter alone: its owner here is a loader without classes, so no
 * frame on the stack is the module's (the rewritten modules are in `RewriteBatteryTest`).
 */
class MeterTest {
    private val meter = Meter(object : ClassLoader(null) {})

    private fun ticksUntilStopped(step: () -> Unit): ForgeLimit {
        var ticks = 0L
        return assertFailsWith<ForgeLimit> {
            while (ticks++ < 1_000_000_000L) step()
        }
    }

    @Test
    fun `no module code runs outside an armed call`() {
        assertEquals("module code ran outside a metered call", assertFailsWith<ForgeLimit> { meter.enter() }.message)
        assertFailsWith<ForgeLimit> { meter.tick() }
        meter.exit()
        meter.arm(BusyMachine.PATIENT_NANOS, ForgeLimits.CALL_ALLOCATION_BYTES)
        meter.enter()
        meter.tick()
        meter.exit()
        meter.disarm()
        assertFailsWith<ForgeLimit> { meter.enter() }
        assertFailsWith<ForgeLimit> { meter.tick() }
        assertNull(meter.tripped(), "a refusal outside a call trips no call")
    }

    @Test
    fun `the clock is read every 16 ticks, enter counting as one`() {
        meter.arm(-1, Long.MAX_VALUE)
        repeat(ForgeLimits.METER_CHECK_EVERY - 2) { meter.tick() }
        meter.enter()
        assertEquals("time budget", assertFailsWith<ForgeLimit> { meter.enter() }.message)
        assertEquals("time budget", meter.tripped())
    }

    @Test
    fun `a call that only enters methods is stopped by the clock`() {
        meter.arm(2_000_000, Long.MAX_VALUE)
        val stopped = ticksUntilStopped {
            meter.enter()
            meter.exit()
        }
        assertEquals("time budget", stopped.message)
    }

    @Test
    fun `tripping is sticky until the next arm`() {
        meter.arm(-1, Long.MAX_VALUE)
        ticksUntilStopped { meter.tick() }
        assertEquals("time budget", assertFailsWith<ForgeLimit> { meter.tick() }.message)
        assertEquals("time budget", assertFailsWith<ForgeLimit> { meter.enter() }.message)
        meter.exit()
        meter.disarm()
        assertEquals("time budget", meter.tripped(), "kept after the call, even if the module swallowed the error")
        meter.arm(ForgeLimits.CALL_NANOS, ForgeLimits.CALL_ALLOCATION_BYTES)
        assertNull(meter.tripped())
        meter.tick()
    }

    @Test
    fun `allocating past the allowance trips the meter`() {
        meter.arm(60_000_000_000L, 1L shl 20)
        assumeTrue(meter.allocatedSinceArm() >= 0, "the JVM measures allocation")
        var kept: ByteArray? = null
        val stopped = ticksUntilStopped {
            kept = ByteArray(64 * 1024)
            meter.tick()
        }
        assertEquals("allocation budget", stopped.message)
        assertTrue(meter.allocatedSinceArm() >= 1L shl 20 && kept != null)
    }

    /** Computes, without a tick of the module (one long step of the JDK), until this thread's CPU clock moved [nanos]. */
    private fun burn(nanos: Long) {
        val cpu = ManagementFactory.getThreadMXBean()
        val start = cpu.currentThreadCpuTime
        var x = 0L
        while (cpu.currentThreadCpuTime - start < nanos) repeat(10_000) { x += it.toLong() xor (x ushr 3) }
        check(x != 42L)
    }

    @Test
    fun `disarm trips a call that overran inside one call of the JDK`() {
        // Its thread's CPU time shows it: 40 ms at least, more than the clock's steps (15.6 ms under Windows) can add.
        meter.arm(1_000_000, Long.MAX_VALUE)
        burn(Meter.MIN_CPU_EVIDENCE_NANOS)
        meter.disarm()
        assertEquals("time budget", meter.tripped())
        assertTrue(meter.cpu() >= Meter.MIN_CPU_EVIDENCE_NANOS, "its thread's CPU time: ${meter.cpu()}")

        meter.arm(60_000_000_000L, 1L shl 20)
        assumeTrue(meter.allocatedSinceArm() >= 0, "the JVM measures allocation")
        val big = ByteArray(4 shl 20)
        meter.disarm()
        assertEquals("allocation budget", meter.tripped())
        assertTrue(meter.allocatedSinceArm() >= big.size, "the allocation as it was at disarm")
    }

    @Test
    fun `a call the machine holds back is not past its time, however long the wall clock says`() {
        // A sleep stands for the machine holding the thread back (another thread on its core, a pause of the GC): no
        // work of the module, no CPU (M5-LOAD; FLAKY-4 saw a call of a few µs take 4 ms, one of 75 ms of work 2 s).
        meter.arm(2_000_000, Long.MAX_VALUE)
        Thread.sleep(30)
        repeat(4 * ForgeLimits.METER_CHECK_EVERY) { meter.tick() }
        Thread.sleep(30)
        meter.disarm()
        assertNull(meter.tripped(), "60 ms of wall clock, but no work past its 2 ms")
        assertTrue(meter.worked() < 2_000_000, "${meter.worked()} ns of its own work")
    }

    @Test
    fun `the module's own work is counted, and only that, until its budget`() {
        meter.arm(20_000_000, Long.MAX_VALUE)
        val start = System.nanoTime()
        // 10 ms of the module's own code (ticks), held back 30 ms, then its code again until its meter stops it.
        while (System.nanoTime() - start < 10_000_000) meter.tick()
        Thread.sleep(30)
        val stopped = ticksUntilStopped { meter.tick() }
        meter.disarm()
        assertEquals("time budget", stopped.message)
        val wall = (System.nanoTime() - start) / 1e6
        assertTrue(wall >= 50, "stopped at 20 ms of its own work, not of the wall clock: $wall ms")
        assertTrue(meter.worked() in 20_000_000..21_000_000, "${meter.worked()} ns of its own work")
    }

    @Test
    fun `a sound call is not tripped`() {
        // A busy machine's patience: a sound call is no slower than its work, however long the machine holds it back.
        meter.arm(BusyMachine.PATIENT_NANOS, ForgeLimits.CALL_ALLOCATION_BYTES)
        repeat(1_000) {
            meter.enter()
            meter.tick()
            meter.exit()
        }
        meter.disarm()
        assertNull(meter.tripped())
        val allocated = meter.allocatedSinceArm()
        assertEquals(allocated, meter.allocatedSinceArm(), "frozen at disarm")
    }

    @Test
    fun `a counter that drifted past the depth is recounted on the real frames`() {
        meter.arm(60_000_000_000L, Long.MAX_VALUE)
        repeat(10 * Limits.MAX_DEPTH) { meter.enter() }
        assertNull(meter.tripped(), "none of these frames is the module's: an exception skipped their exit")
        meter.disarm()
    }
}
