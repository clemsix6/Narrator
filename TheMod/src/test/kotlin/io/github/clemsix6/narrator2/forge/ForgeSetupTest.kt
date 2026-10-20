package io.github.clemsix6.narrator2.forge

import io.github.clemsix6.narrator2.config.ForgeConfig
import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.forge.arsenal.ArsenalStore
import io.github.clemsix6.narrator2.mechanics.ForgeHalt
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.runtime.Phase
import io.github.clemsix6.narrator2.testing.LogCapture
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.testing.testSettings
import io.github.clemsix6.narrator2.trace.DecisionRecord
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The server's Forge as `createForge` makes it (task M5-21, M5 §11): a [DisabledForge] with its reason when it cannot
 * run, else the real [ForgeService], started with its built-in modules; the operators online told when it falls; and
 * the Forge in the `ready` line of the start ([forgeState]).
 */
class ForgeSetupTest {
    private val clock = TestClock()

    @Test
    fun `the Forge stays off with its reason when the configuration says so, when ECJ is missing, or when it cannot start`() {
        val off = TestServices(clock = clock, settings = testSettings(NarratorConfig(forge = ForgeConfig(enabled = false))))
        val cases: List<Triple<TestServices, (NarratorServices) -> ForgeService, String>> = listOf(
            Triple(off, { error("never built when it is off") }, "forge.enabled is false"),
            Triple(
                TestServices(clock = clock),
                { throw NoClassDefFoundError("org/eclipse/jdt/internal/compiler/env/INameEnvironment") },
                "ECJ is missing: org/eclipse/jdt/internal/compiler/env/INameEnvironment",
            ),
            Triple(TestServices(clock = clock), { throw IllegalStateException("the arsenal's folder is a file") }, "it could not start: the arsenal's folder is a file"),
        )
        for ((services, make, reason) in cases) {
            LogCapture().use { logs ->
                val forge = createForge(services, make = make)
                assertIs<DisabledForge>(forge, reason)
                assertFalse(forge.available)
                assertEquals(reason, forge.unavailableReason)
                assertContains(logs.text, "the Forge is off")
                assertEquals("off ($reason)", forgeState(forge, null), "the ready line")
                assertEquals("off ($reason), switched off by Clement", forgeState(forge, ForgeHalt("trop de poules", "Clement", 0)))
            }
        }
    }

    @Test
    fun `the real Forge starts with its built-in modules, and the operators online are told once when it falls`() = runBlocking<Unit> {
        val services = TestServices(clock = clock)
        val told = CopyOnWriteArrayList<String>()
        val forge = createForge(services, told::add)
        val hold = CountDownLatch(1)
        try {
            assertIs<ForgeService>(forge)
            forge.start().join()
            val builtIn = forge.arsenal.list().filter { it.builtIn && it.version == 0 && it.status == ModuleStatus.READY }.map { it.name }
            assertEquals(ArsenalStore.BUILT_IN.toSet(), builtIn.toSet(), "the built-in modules, built as the Forge started")
            assertEquals("on", forgeState(forge, null), "the ready line")
            assertEquals("switched off by Clement", forgeState(forge, ForgeHalt("trop de poules", "Clement", clock.millis())))

            // Three module threads lost and still running: past the ceiling of two, the Forge is stuck until the restart.
            val lost = List(3) { Thread { hold.await() }.apply { isDaemon = true; start() } }
            lost.forEach(forge.lostThreads::add)
            assertFalse(forge.available)
            awaitTrue("the operators told") { told.isNotEmpty() }
            Thread.sleep(WATCH_LOOKS_MS) // the watch looks again: still told once
            assertEquals(listOf("the Forge is unavailable until the server restarts: ${forge.unavailableReason}"), told.toList())
            assertContains(told.single(), "too many module threads were lost since ")
            assertTrue(services.traces.decisions.any { it.kind == DecisionRecord.Kind.FORGE && it.decision == ForgeService.STUCK }, "the decision forge stuck")
        } finally {
            hold.countDown()
            forge.stop()
        }
    }

    @Test
    fun `a Forge that stops with the server is not told to the operators`() {
        val services = TestServices(clock = clock)
        val told = CopyOnWriteArrayList<String>()
        val forge = createForge(services, told::add)
        services.phase = Phase.STOPPING
        forge.stop()
        assertEquals("the server is stopping", forge.unavailableReason)
        Thread.sleep(WATCH_LOOKS_MS)
        assertEquals(emptyList(), told.toList())
    }

    private fun awaitTrue(what: String, condition: () -> Boolean) {
        val until = System.nanoTime() + 10_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < until) { "timed out waiting for: $what" }
            Thread.sleep(20)
        }
    }

    private companion object {
        /** Longer than the watch's look, once a second. */
        const val WATCH_LOOKS_MS = 1_500L
    }
}
