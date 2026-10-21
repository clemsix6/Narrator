package io.github.clemsix6.narrator2.forge

import io.github.clemsix6.narrator2.testing.LogCapture
import io.github.clemsix6.narrator2.testing.TestServices
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.logging.log4j.Level
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * A Forge stopped while it builds its built-in modules (M6-FIX-6): a `/narrator restart` soon after the start stops it
 * so, and that is no error.
 */
class ForgeStopTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `a Forge stopped while it builds its built-in modules logs no error and is not ready`() = runBlocking<Unit> {
        val forge = ForgeService(TestServices(dataDir = dir))
        LogCapture().use { logs ->
            val startup = forge.start()
            // Stopped once the first is built: the compiler is most likely on the next one, which the stop cuts short.
            val end = System.nanoTime() + 60_000_000_000L
            while (forge.arsenal.list().none { it.builtIn }) {
                check(System.nanoTime() < end && startup.isActive) { "no built-in module was built: ${logs.text}" }
                Thread.sleep(5)
            }
            forge.stop()
            withTimeout(30_000) { startup.join() }

            assertEquals(emptyList(), logs.at(Level.ERROR) + logs.at(Level.WARN), "no error nor warning for a Forge that was stopped")
            assertFalse(logs.messages.any { it.startsWith("the Forge is ready") }, "a stopped Forge is never said ready: ${logs.text}")
            assertEquals("the server is stopping", forge.unavailableReason)
        }
    }
}
