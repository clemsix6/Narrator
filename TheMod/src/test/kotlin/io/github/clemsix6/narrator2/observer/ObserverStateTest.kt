package io.github.clemsix6.narrator2.observer

import io.github.clemsix6.narrator2.testing.LogCapture
import io.github.clemsix6.narrator2.testing.SampleEvents
import io.github.clemsix6.narrator2.testing.TestServices
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ObserverStateTest {
    private val services = TestServices()
    private val state = ObserverState(services)

    @Test
    fun `a hook that throws never breaks the game, and is reported once a minute`() {
        val logs = LogCapture()
        logs.use {
            repeat(3) { state.hook("block_break") { error("boom $it") } }
        }
        assertEquals(1, Regex("hook block_break failed").findAll(logs.text).count(), logs.text)
        assertContains(logs.text, "boom 0")
        assertTrue(state.hookNanos > 0, "the time in hooks is counted")
    }

    @Test
    fun `hooks publish on the bus of the server`() {
        val seen = CopyOnWriteArrayList<Long>()
        services.bus.subscribeSync("test", "test") { seen += it.id }
        state.hook("chat") { state.publish(SampleEvents.all.first()) }
        assertEquals(listOf(1L), seen)
    }

    @Test
    fun `every flushable is flushed at the stop, even after one fails`() {
        val flushed = CopyOnWriteArrayList<String>()
        state.onStop { flushed += "damage" }
        state.onStop { error("broken") }
        state.onStop { flushed += "pickups" }

        LogCapture().use { logs ->
            state.flushAll()
            assertContains(logs.text, "flush failed at the stop")
        }
        assertEquals(listOf("damage", "pickups"), flushed)
    }
}
