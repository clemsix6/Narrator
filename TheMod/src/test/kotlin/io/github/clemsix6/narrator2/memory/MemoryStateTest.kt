package io.github.clemsix6.narrator2.memory

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `memory_state` (M3 review, finding 22): the code's own keys, beside the memory, on an in-memory Journal. */
class MemoryStateTest {
    private val f = StoreFixture()
    private val state = f.state

    @AfterEach
    fun close() = f.close()

    @Test
    fun `the memory state is a small key value store, neither audited nor decided`() = runBlocking<Unit> {
        assertNull(state.state("world_play_s"))
        state.setState("world_play_s", "120")
        f.clock.advance(Duration.ofMinutes(5))
        state.setState("world_play_s", "180")
        assertEquals("180", state.state("world_play_s"))
        assertEquals(listOf(f.clock.instant()), f.rows("SELECT updated_at FROM memory_state WHERE key = 'world_play_s'") { instant(1) })
        assertEquals(0L, f.count("memory_log"), "not a write of the memory: no audit")
        assertEquals(emptyList(), f.journal.sink.decisions, "nor any decision")
    }

    @Test
    fun `a key done with is deleted`() = runBlocking<Unit> {
        f.sql("INSERT INTO memory_state VALUES ('dream:7:step', '3', ?), ('dream:pending', '7', ?)", f.clock.instant(), f.clock.instant())
        state.deleteState("dream:7:step")
        assertEquals(listOf("dream:pending"), f.rows("SELECT key FROM memory_state ORDER BY key") { getString(1) })
        state.deleteState("dream:7:step") // already gone: nothing happens
        assertNull(state.state("dream:7:step"))
    }
}
