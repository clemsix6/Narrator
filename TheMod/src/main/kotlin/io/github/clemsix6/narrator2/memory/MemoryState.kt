package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.journal.Journal
import java.time.Clock

/**
 * The code's own state in `memory_state`, as `key → value` text: the world play clock and its save
 * ([DuckPlayClock]), the scheduler's pause, pending dream, dream steps, last dream end and first start
 * ([MemoryJobs]), the Dreamer's reflections on players, the day of the last anniversary ([Reminiscence]).
 *
 * These are not writes of the memory: no rule, no audit, no decision. Each change is one short
 * `Journal.write` job. [DuckMemoryState] in production; a fake in tests.
 */
interface MemoryState {
    /** The value of [key]; null when unset. */
    suspend fun state(key: String): String?

    suspend fun setState(key: String, value: String)

    /** Removes [key], done with (the step of a dream run once the dream is over). */
    suspend fun deleteState(key: String)
}

/** [MemoryState] on the Journal: reads on its read pool, each write its own job, stamped by [clock]. */
class DuckMemoryState(private val journal: Journal, private val clock: Clock) : MemoryState {
    override suspend fun state(key: String): String? = journal.read(READ_TIMEOUT_MS) { connection ->
        connection.query("SELECT value FROM memory_state WHERE key = ?", key) { getString(1) }.singleOrNull()
    }

    override suspend fun setState(key: String, value: String) {
        journal.write(WRITE_TIMEOUT_MS) { connection ->
            connection.update("INSERT OR REPLACE INTO memory_state VALUES (?, ?, ?)", key, value, clock.instant())
        }
    }

    override suspend fun deleteState(key: String) {
        journal.write(WRITE_TIMEOUT_MS) { connection -> connection.update("DELETE FROM memory_state WHERE key = ?", key) }
    }

    private companion object {
        const val READ_TIMEOUT_MS = 5_000L
        const val WRITE_TIMEOUT_MS = 10_000L
    }
}
