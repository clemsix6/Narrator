package io.github.clemsix6.narrator2.tool

import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.llm.Role
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The one way the engine writes durations, levels, numbers, dimensions and names for the model (M2 review, finding 16). */
class ToolTextTest {
    @Test
    fun `a duration has one form, from seconds to days`() {
        assertEquals(
            listOf("0s", "36s", "59s", "40s", "1m", "1m30s", "4m", "4m05s", "9m59s", "10m", "12m", "25m", "59m", "1h00m", "1h05m", "1h12m", "3h05m", "1d2h", "2d4h"),
            listOf(
                0L, 36_000L, 59_400L, 40_400L, 60_000L, 90_000L, 240_000L, 245_000L, 599_000L, 600_000L, 12 * 60_000L + 20_000,
                25 * 60_000L + 59_000, 59 * 60_000L + 29_000, 3_600_000L, 3_900_000L, 72 * 60_000L, 185 * 60_000L, 96_000_000L,
                (52 * 60 + 30) * 60_000L,
            ).map(ToolText::duration),
        )
        assertEquals("0s", ToolText.duration(-5_000), "a time in the future is now")
    }

    @Test
    fun `a wait has the same form, rounded up so that it is never over too soon`() {
        assertEquals(
            listOf("1s", "18s", "45s", "1m", "3m", "4m30s", "3m10s", "5m", "1h05m"),
            listOf(1L, 17_001L, 45_000L, 60_000L, 180_000L, 270_000L, 189_500L, 300_000L, 3_900_000L).map(ToolText::wait),
        )
        assertEquals("0s", ToolText.wait(-5), "nothing to wait for")
        assertEquals("1s", ToolText.wait(1), "while ToolText.duration(1) is ${ToolText.duration(1)}")
    }

    @Test
    fun `numbers and levels`() {
        assertEquals(listOf("3", "0.5", "12.25", "-1.5", "0.33"), listOf(3.0, 0.5, 12.25, -1.5, 1.0 / 3).map(ToolText::number))
        assertEquals(listOf("I", "II", "X", "11", "255"), listOf(1, 2, 10, 11, 255).map(ToolText::roman))
    }

    @Test
    fun `a dimension is read in one form and written in any`() {
        assertEquals(
            listOf("overworld", "the_nether", "the_end", "twilight:forest"),
            listOf("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end", "twilight:forest").map(ToolText::dimension),
        )
        val written = mapOf(
            "overworld" to "minecraft:overworld",
            "the_nether" to "minecraft:the_nether",
            "nether" to "minecraft:the_nether",
            " Nether " to "minecraft:the_nether",
            "minecraft:nether" to "minecraft:the_nether",
            "end" to "minecraft:the_end",
            "the_end" to "minecraft:the_end",
            "minecraft:the_end" to "minecraft:the_end",
            "twilight:forest" to "twilight:forest",
        )
        written.forEach { (name, id) -> assertEquals(id, ToolText.dimensionId(name), name) }
        written.values.forEach { id -> assertEquals(id, ToolText.dimensionId(ToolText.dimension(id)), "what the model reads, written back, is $id") }
    }

    @Test
    fun `player names are escaped`() {
        val engine = PlayerRef(UUID.randomUUID(), "<engine>")
        val bob = PlayerRef(UUID.randomUUID(), "Bob")
        assertEquals("‹engine›", engine.shownName)
        assertEquals("‹engine›, Bob", ToolText.names(listOf(engine, bob)))
        assertEquals("nobody", ToolText.names(emptyList()))
    }

    @Test
    fun `a turn counter counts each turn apart, whoever calls in between`() {
        val counter = TurnCounter(2)
        val director = Caller(Role.DIRECTOR, "director-1", turnId = 1, taskId = null)
        val other = Caller(Role.ANALYST, "analyst-1", turnId = null, taskId = 3)
        assertTrue(counter.take(director))
        assertTrue(counter.take(other), "another conversation has its own count")
        assertTrue(counter.take(director))
        assertTrue(counter.full(director))
        assertFalse(counter.take(director), "the third of the turn")
        assertTrue(counter.take(other), "the other conversation's turn did not reset the first one's, nor was it counted by it")
        assertFalse(counter.take(director), "still the third")
        assertTrue(counter.take(director.copy(turnId = 2)), "the next turn starts afresh")
    }
}
