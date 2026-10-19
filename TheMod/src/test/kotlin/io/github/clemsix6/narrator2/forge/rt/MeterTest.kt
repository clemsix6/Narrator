package io.github.clemsix6.narrator2.forge.rt

import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.forge.api.Limits
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
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
        meter.arm(ForgeLimits.CALL_NANOS, ForgeLimits.CALL_ALLOCATION_BYTES)
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

    @Test
    fun `disarm trips a call that overran inside one call of the JDK`() {
        meter.arm(1_000_000, Long.MAX_VALUE)
        Thread.sleep(5)
        meter.disarm()
        assertEquals("time budget", meter.tripped())

        meter.arm(60_000_000_000L, 1L shl 20)
        assumeTrue(meter.allocatedSinceArm() >= 0, "the JVM measures allocation")
        val big = ByteArray(4 shl 20)
        meter.disarm()
        assertEquals("allocation budget", meter.tripped())
        assertTrue(meter.allocatedSinceArm() >= big.size, "the allocation as it was at disarm")
    }

    @Test
    fun `a sound call is not tripped`() {
        meter.arm(ForgeLimits.CALL_NANOS, ForgeLimits.CALL_ALLOCATION_BYTES)
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
