package io.github.clemsix6.narrator2.forge.host

import io.github.clemsix6.narrator2.forge.sandbox.FakeContext
import io.github.clemsix6.narrator2.forge.sandbox.MeteredCall
import io.github.clemsix6.narrator2.forge.sandbox.RewriteBatteryTest
import io.github.clemsix6.narrator2.testing.BusyMachine
import org.junit.jupiter.api.AfterEach

/**
 * The runtime battery of M5-04 (`RewriteBatteryTest`, every case) through the **real** loader ([ModuleLoader][io.github.clemsix6.narrator2.forge.sandbox.ModuleLoader],
 * which resolves only the allow-list) and the **real** host ([ModuleHost]: the module thread, the meter armed there,
 * the faults kept as text): each attack is stopped the same way.
 *
 * A case about the meter's time keeps the game's budgets, with a margin of [BusyMachine.SLACK_NANOS] before an abandon:
 * the meter, never the abandon, stops it (the game's 5 ms is less than a busy machine may hold the module thread back
 * while it unwinds). Any other case runs with [BusyMachine.TIME_SCALE].
 */
class HostBatteryTest : RewriteBatteryTest() {
    private val threads = ModuleThreads("battery-module")

    @AfterEach
    fun close() {
        threads.close()
    }

    override fun run(name: String, classes: Map<String, ByteArray>, timed: Boolean): MeteredCall {
        val fake = FakeContext()
        val host = if (timed) {
            ModuleHost(name, classes, threads, "battery $name", abandonMarginNanos = BusyMachine.SLACK_NANOS)
        } else {
            ModuleHost(name, classes, threads, "battery $name", timeScale = BusyMachine.TIME_SCALE)
        }
        val called = host.start(fake.context)
        val fault = host.disabled
        return MeteredCall(
            error = null,
            tripped = fault?.takeIf { it.kind == Fault.Kind.LIMIT }?.cause,
            nanos = called?.nanos ?: 0,
            allocated = called?.allocated ?: -1,
            logs = fake.logs.toList(),
            fault = fault?.let { if (it.kind == Fault.Kind.LIMIT) "ForgeLimit: ${it.cause}" else it.cause },
        )
    }
}
