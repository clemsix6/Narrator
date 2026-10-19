package io.github.clemsix6.narrator2.memory.stats

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.journal.Journal
import io.github.clemsix6.narrator2.journal.JournalUnavailableException
import io.github.clemsix6.narrator2.memory.query
import io.github.clemsix6.narrator2.memory.uuid
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Computes the sheets' statistics again (M3 §6.1, §6.3, §6.7): the pairs first (the style's social share
 * reads them), then the server's rates (luck and style compare each player with the others), then each
 * player's stats. Everything is read on the Journal's SQL pool; each result is written in short
 * `Journal.write` jobs of its own (the pairs, [PairStats.CHUNK] a job, then one per player), so the writer is
 * held for milliseconds.
 *
 * Called by the memory's jobs (M3-12): every `memory.statsMinutes` for the players online, at each
 * departure, and before a dream for the players active since the last one ([activeSince]). Never on the
 * server thread. A step that fails is logged and skipped; the next refresh does it again (everything is
 * recomputed from the whole history, so running late or twice is harmless).
 */
class StatsRefresher(private val journal: Journal, private val clock: Clock) {
    private val log = Log.memory
    private val decisions = Decisions(journal, clock)

    /** What a refresh did: players refreshed, players that failed, pairs written (null when the pairs failed). */
    data class Outcome(val players: Int, val failed: Int, val pairs: Int?, val elapsedMs: Long)

    /**
     * Recomputes the pairs, then the stats of [players], as of now. [why] says what asked for it (`timer`,
     * `left`, `dream`…), for the decision. Fails only with [JournalUnavailableException] when the Journal
     * cannot be used at all, and with cancellation.
     */
    suspend fun refresh(players: Collection<UUID>, why: String): Outcome {
        val started = System.nanoTime()
        journal.barrier()
        val now = clock.instant()
        val pairs = step("pairs") {
            val computed = journal.read(READ_TIMEOUT_MS) { PairStats.compute(it, now) }
            PairStats.store(journal, computed, WRITE_TIMEOUT_MS)
            computed.size
        }
        val rates = step("rates") { journal.read(READ_TIMEOUT_MS) { Rates.read(it, now) } }.orEmpty()
        var refreshed = 0
        var failed = 0
        for (player in players.distinct()) {
            val done = step("stats of $player") {
                val stats = journal.read(READ_TIMEOUT_MS) { SheetStats.compute(it, player, now, rates) } ?: return@step false
                journal.write(WRITE_TIMEOUT_MS) { SheetStats.write(it, player, stats, now) }
                true
            }
            when (done) {
                true -> refreshed++
                false -> Unit // never seen: nothing to compute
                null -> failed++
            }
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        decisions.record(
            Log.memory, DecisionRecord.Kind.MEMORY, "stats",
            subject = why,
            reason = "$refreshed players, ${pairs ?: "no"} pairs${if (failed > 0) ", $failed failed" else ""}, $elapsedMs ms",
            detail = buildJsonObject {
                put("players", JsonPrimitive(refreshed))
                put("failed", JsonPrimitive(failed))
                put("pairs", JsonPrimitive(pairs))
                put("elapsed_ms", JsonPrimitive(elapsedMs))
            },
            level = if (failed > 0 || pairs == null) Log.Level.WARN else Log.Level.DEBUG,
        )
        return Outcome(refreshed, failed, pairs, elapsedMs)
    }

    /** The players online at some point since [since] (still online, or left after it): those a dream reflects on. */
    suspend fun activeSince(since: Instant): List<UUID> = journal.read(READ_TIMEOUT_MS) { connection: Connection ->
        connection.query(
            "SELECT DISTINCT player_uuid FROM sessions WHERE left_at IS NULL OR left_at >= ? ORDER BY player_uuid",
            since,
            timeoutSeconds = SheetStats.QUERY_TIMEOUT_SECONDS,
        ) { uuid(1) }
    }

    /** Runs [block]; its result, or null when it failed (logged). The Journal's unavailability and cancellation go through. */
    private suspend fun <T> step(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: JournalUnavailableException) {
        throw e
    } catch (e: Exception) {
        log.warn("stats: $what failed: ${e.message}; the next refresh does it again", e)
        null
    }

    companion object {
        /** A stats query takes up to a second on 500 hours of play; [SheetStats.QUERY_TIMEOUT_SECONDS] stops it. */
        const val READ_TIMEOUT_MS = (SheetStats.QUERY_TIMEOUT_SECONDS + 5) * 1_000L

        /** A write job only upserts a few rows; this bounds the wait behind the Journal's batches. */
        const val WRITE_TIMEOUT_MS = 10_000L
    }
}
