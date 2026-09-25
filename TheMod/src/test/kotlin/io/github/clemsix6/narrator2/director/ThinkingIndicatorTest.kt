package io.github.clemsix6.narrator2.director

import io.github.clemsix6.narrator2.event.EventBus
import io.github.clemsix6.narrator2.event.NarratorChannel
import io.github.clemsix6.narrator2.event.NarratorSpoke
import io.github.clemsix6.narrator2.testing.SampleEvents.alex
import io.github.clemsix6.narrator2.testing.SampleEvents.steve
import io.github.clemsix6.narrator2.testing.TestClock
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThinkingIndicatorTest {
    private val game = DirectorHarness.FakeGame()
    private val text = "Le Narrateur réfléchit…"

    @Test
    fun `shown at once, renewed every 2 s, cleared by an answer or at the end of the turn`() = runTest {
        val indicator = ThinkingIndicator(game) { text }
        indicator.show(listOf(steve.uuid, alex.uuid), backgroundScope)
        assertEquals(listOf(listOf(steve.uuid, alex.uuid) to text), game.shown)

        testScheduler.advanceTimeBy(2_001)
        assertEquals(2, game.shown.size)
        indicator.spoke(listOf(steve.uuid))
        assertEquals(listOf(listOf(steve.uuid)), game.cleared)
        assertEquals(setOf(alex.uuid), indicator.showing)

        testScheduler.advanceTimeBy(2_000)
        assertEquals(listOf(alex.uuid) to text, game.shown.last())
        indicator.stop()
        assertEquals(listOf(alex.uuid), game.cleared.last())
        assertTrue(indicator.showing.isEmpty())

        val shown = game.shown.size
        testScheduler.advanceTimeBy(10_000)
        assertEquals(shown, game.shown.size, "nothing is renewed after the turn")
    }

    @Test
    fun `a player shown twice is shown once`() = runTest {
        val indicator = ThinkingIndicator(game) { text }
        indicator.show(listOf(steve.uuid), backgroundScope)
        indicator.show(listOf(steve.uuid), backgroundScope)
        assertEquals(1, game.shown.size)
        indicator.stop()
    }

    @Test
    fun `switched off, nothing is shown`() = runTest {
        val indicator = ThinkingIndicator(game) { null }
        indicator.show(listOf(steve.uuid), backgroundScope)
        testScheduler.advanceTimeBy(5_000)
        assertTrue(game.shown.isEmpty())
        indicator.stop()
        assertTrue(game.cleared.isEmpty())
    }

    @Test
    fun `the Narrator's lines are followed on the bus, and a line to everyone clears everyone`() = runTest {
        val bus = EventBus(TestClock(), { 0L }, 1)
        val indicator = ThinkingIndicator(game) { text }
        indicator.subscribe(bus)
        indicator.show(listOf(steve.uuid, alex.uuid), backgroundScope)
        bus.publish(NarratorSpoke(NarratorChannel.NARRATOR, "Toi.", listOf(alex), 1, null))
        assertEquals(setOf(steve.uuid), indicator.showing)
        bus.publish(NarratorSpoke(NarratorChannel.NARRATOR, "Tous.", null, 1, null))
        assertTrue(indicator.showing.isEmpty())
        assertEquals(listOf(listOf(alex.uuid), listOf(steve.uuid)), game.cleared)
        indicator.stop()
    }
}
