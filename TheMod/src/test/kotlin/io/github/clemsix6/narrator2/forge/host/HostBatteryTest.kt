package io.github.clemsix6.narrator2.forge.host

import io.github.clemsix6.narrator2.forge.sandbox.FakeContext
import io.github.clemsix6.narrator2.forge.sandbox.MeteredCall
import io.github.clemsix6.narrator2.forge.sandbox.RewriteBatteryTest
import org.junit.jupiter.api.AfterEach

/**
 * The runtime battery of M5-04 (`RewriteBatteryTest`, every case) through the **real** loader ([ModuleLoader][io.github.clemsix6.narrator2.forge.sandbox.ModuleLoader],
 * which resolves only the allow-list) and the **real** host ([ModuleHost]: the module thread, the meter armed there,
 * the faults kept as text): each attack is stopped the same way.
 */
class HostBatteryTest : RewriteBatteryTest() {
    private val threads = ModuleThreads("battery-module")

    @AfterEach
    fun close() {
        threads.close()
    }

    override fun run(name: String, classes: Map<String, ByteArray>): MeteredCall {
        val fake = FakeContext()
        val host = ModuleHost(name, classes, threads, "battery $name")
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
