package io.github.clemsix6.narrator2.journal

import io.github.clemsix6.narrator2.config.JournalConfig
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.event.AsyncSubscription
import io.github.clemsix6.narrator2.event.EventBus
import io.github.clemsix6.narrator2.event.SubscriberStats
import io.github.clemsix6.narrator2.trace.ChatSignalsRecord
import io.github.clemsix6.narrator2.trace.ConversationRecord
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import io.github.clemsix6.narrator2.trace.LlmCallRecord
import io.github.clemsix6.narrator2.trace.TaskRecord
import io.github.clemsix6.narrator2.trace.ToolCallRecord
import io.github.clemsix6.narrator2.trace.TurnRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.duckdb.DuckDBConnection
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Journal on DuckDB (M1_M2 §5): facts from the bus and traces of the engine, written in batches by
 * [JournalWriter] on its own thread; reads on a small pool of read connections (`narrator2-sql-N`).
 * Nothing here blocks the caller: records are queued, reads run on the pool.
 *
 * Ids ([nextId]) start past every id found in the database, including ids cited by other rows, and past
 * the ids reserved on disk before they were handed out ([IdReservations]): an id is never handed out
 * twice, across restarts and crashes.
 *
 * When the database stops answering (disk full, file locked…), the Journal turns unavailable: records
 * are counted as lost, reads fail, and the operators online are told once ([tellOperators]). The writer
 * then tries to open the database again every minute ([reopen]). Once it opens, reads and writes go on,
 * a `journal_health` decision `reopened` tells how long it lasted and what was lost, and the operators
 * are told again once the reopened database took its first batch.
 *
 * The memory writes through [write]: jobs run by the same writer, between its batches (M3 §4.1). If the
 * writer thread itself dies (a bug), the Journal turns unavailable for good and the operators are told.
 */
internal class DuckJournal private constructor(
    db: DuckDatabase,
    private val clock: Clock,
    private val queueCapacity: Int,
    private val ids: IdReservations,
    private val hookNanos: () -> Long?,
    flushIntervalMs: Long,
    private val tuning: JournalTuning,
    private val reopenDatabase: (() -> DuckDatabase)?,
) : Journal, JournalWriter.Recovery {
    private val log = Log.journal

    /** The database; replaced when it opens again after an outage. */
    @Volatile
    private var db: DuckDatabase = db

    /** True while [db] is closed after a failure, until it opens again. */
    @Volatile
    private var released = false

    private val writer = JournalWriter(db, clock, flushIntervalMs, queueCapacity, tuning, ::reportHealth, ::closeResources, ::endReads, this)
    private val readers = LinkedBlockingQueue(db.readers)
    private val readThreads: ExecutorService = Executors.newFixedThreadPool(db.readers.size.coerceAtLeast(1)) { task ->
        Thread(task, "narrator2-sql-${READ_THREADS.incrementAndGet()}").apply { isDaemon = true }
    }
    private val resourcesClosed = AtomicBoolean()

    /**
     * Tells the operators online, in the chat: set by the runtime once the server's players can be
     * reached. Called on the writer thread; must not block.
     */
    @Volatile
    var tellOperators: (String) -> Unit = {}

    /** Since when the database is down, and the loss counters then. Writer thread. */
    private class Outage(val error: String, val since: Instant, val sinceNanos: Long, val lost: Long, val facts: Long, val traces: Long) {
        var attempts = 0
    }

    /** The outage in progress, until a batch is committed on the reopened database. Writer thread. */
    private var outage: Outage? = null

    @Volatile
    private var closing = false

    @Volatile
    private var bus: EventBus? = null

    @Volatile
    private var subscription: AsyncSubscription? = null

    @Volatile
    private var lastBusStats: SubscriberStats? = null

    /** Baseline of the previous health report. Writer thread. */
    private class Report(val lost: Long, val hookNanos: Long?, val at: Long)

    private var lastReport = Report(0, null, System.nanoTime())

    override val available: Boolean get() = writer.available && !writer.finished && !closing

    override fun nextIdSeed(kind: IdKind): Long = ids.seed(kind)

    override fun nextId(kind: IdKind): Long = ids.next(kind)

    /**
     * Drains [bus] on the writer thread. The Journal must be the first to subscribe: its synchronous
     * [ID_SUBSCRIBER] reserves each event id on disk before any other subscriber sees the event.
     */
    override fun attach(bus: EventBus) {
        check(subscription == null) { "the Journal is already attached to a bus" }
        bus.subscribeSync(ID_SUBSCRIBER, OWNER) { envelope -> ids.handedOut(IdKind.EVENT, envelope.id) }
        val subscription = bus.subscribeAsync(SUBSCRIBER, OWNER, queueCapacity)
        this.bus = bus
        this.subscription = subscription
        writer.events = subscription.events
    }

    override suspend fun barrier() = writer.barrier()

    override suspend fun <T> read(timeoutMs: Long, query: (Connection) -> T): T {
        if (!available) throw JournalUnavailableException(unavailableReason())
        val result = CompletableFuture<T>()
        try {
            readThreads.execute {
                if (result.isDone) return@execute // timed out or cancelled while waiting for a thread
                // None while the database is down after a failure.
                val connection = readers.poll(timeoutMs, TimeUnit.MILLISECONDS)
                if (connection == null) {
                    result.completeExceptionally(JournalTimeoutException(timeoutMs))
                    return@execute
                }
                try {
                    result.complete(query(connection))
                } catch (e: Throwable) {
                    result.completeExceptionally(e)
                } finally {
                    endQuery(connection)
                    giveBack(connection)
                }
            }
        } catch (_: RejectedExecutionException) {
            throw JournalUnavailableException("the Journal is closed")
        }
        return try {
            withTimeout(timeoutMs) { result.await() }
        } catch (_: TimeoutCancellationException) {
            result.cancel(false)
            throw JournalTimeoutException(timeoutMs)
        }
    }

    /**
     * Ends the last query of [connection] before it goes back to the pool. Results are streamed, and
     * DuckDB keeps a query whose result was not read to the end, with its read transaction, open on its
     * connection until that connection runs another statement: closing the `ResultSet` and its statement
     * is not enough. Most reads stop early (`if (rows.next())`). While such a transaction lives, every
     * later write that updated or deleted rows (a session that closes, a player's counters, a turn
     * written again) keeps its checkpoint lock, and `CHECKPOINT` fails with "other write transactions
     * active" until the connection serves again (T07). A trivial query read to the end ends it, in about
     * 0.1 ms.
     */
    private fun endQuery(connection: Connection) {
        try {
            connection.createStatement().use { statement -> statement.executeQuery("SELECT 1").use { rows -> while (rows.next()) Unit } }
        } catch (e: SQLException) {
            log.debug { "could not end the last query of a read connection: ${e.message}" }
        }
    }

    /** Back to the pool; closed instead when its database was closed since, after a failure. */
    private fun giveBack(connection: DuckDBConnection) {
        if (!released && db.readers.any { it === connection }) {
            readers.put(connection)
        } else {
            try {
                connection.close()
            } catch (_: SQLException) {
                // already closed with its database
            }
        }
    }

    /**
     * Queues [block] on the writer thread (M3 §4.1) and waits for its outcome. Jobs run in the order they
     * come, each in a transaction of its own, always on the current write connection (the reopened one
     * after an outage). When [timeoutMs] runs out, a job that has not started is dropped; one already
     * running still commits. Jobs queued before [close] still run while the writer drains its queues;
     * those left at the close deadline fail. [block] must never wait on the Journal itself (`barrier`,
     * `read`, `write`): it runs on the writer thread, which would wait for itself until the timeout.
     */
    override suspend fun <T> write(timeoutMs: Long, block: (Connection) -> T): T {
        if (!available) throw JournalUnavailableException(unavailableReason())
        val result = writer.submitWrite(block)
        return try {
            withTimeout(timeoutMs) { result.await() }
        } catch (_: TimeoutCancellationException) {
            result.cancel()
            throw JournalTimeoutException(timeoutMs, "write")
        } catch (e: CancellationException) {
            result.cancel()
            throw e
        }
    }

    private fun unavailableReason(): String = if (closing) "the Journal is closed" else writer.lastError ?: "the Journal is closed"

    override fun checkpointAsync() = writer.requestCheckpoint()

    override fun health(): JournalHealth {
        val busStats = busStats()
        return JournalHealth(
            available = available,
            queued = writer.queued + (busStats?.queued ?: 0),
            droppedEvents = writer.lost + (busStats?.dropped ?: 0),
            rowsPerMinute = writer.rowsPerMinute,
            lastFlushMs = writer.lastFlushMs,
            lastFlushRows = writer.lastFlushRows,
            fileBytes = db.fileBytes(),
            walBytes = db.walBytes(),
            lastError = writer.lastError ?: if (closing) "the Journal is closed" else null,
        )
    }

    /**
     * Stops taking facts, writes what is queued, closes the open sessions (`server_stop`), ends the
     * reads, checkpoints and closes the database, within [timeout]. The last quarter of [timeout] (2 s
     * at most) is kept for the ending; what is still queued then is lost and counted in the log.
     */
    override fun close(timeout: Duration) {
        val deadline = System.nanoTime() + timeout.toNanos()
        if (!closing) {
            busStats() // the subscription's counters, before it goes
            closing = true
            subscription?.close()
            writer.requestClose(deadline, minOf(timeout.toNanos() / 4, CLOSE_RESERVE_NANOS))
        }
        if (writer.awaitStop(TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))) {
            closeResources()
        } else {
            log.error("the writer did not stop within ${timeout.toMillis()} ms: the database stays open until the server exits")
        }
    }

    override fun llmCall(r: LlmCallRecord) = writer.submit(r)

    override fun toolCall(r: ToolCallRecord) = writer.submit(r)

    override fun decision(r: DecisionRecord) = writer.submit(r)

    override fun turn(r: TurnRecord) = writer.submit(r)

    override fun conversation(r: ConversationRecord) = writer.submit(r)

    override fun task(r: TaskRecord) = writer.submit(r)

    override fun chatSignals(r: ChatSignalsRecord) = writer.submit(r)

    private fun start(repair: RepairReport?) {
        lastReport = Report(0, currentHookNanos(), System.nanoTime())
        writer.start()
        if (repair != null && repair.total > 0) {
            Decisions(this, clock).record(
                log, DecisionRecord.Kind.JOURNAL_HEALTH, "repaired",
                reason = "closed what the previous run left open (it did not stop normally): $repair",
                detail = buildJsonObject {
                    put("sessions", repair.sessions)
                    put("turns", repair.turns)
                    put("conversations", repair.conversations)
                    put("tasks", repair.tasks)
                },
                level = Log.Level.WARN,
            )
        }
    }

    /** The subscription's counters on the bus; the last ones seen once it is closed. */
    private fun busStats(): SubscriberStats? {
        val current = bus?.stats()?.firstOrNull { it.name == SUBSCRIBER && it.owner == OWNER }
        if (current != null) lastBusStats = current
        return current ?: lastBusStats
    }

    // ── Outages (writer thread) ─────────────────────────────────────────────────────────────────────

    override fun failed(error: String) {
        if (outage == null) {
            val health = health()
            outage = Outage(error, clock.instant(), System.nanoTime(), health.droppedEvents, writer.factsLostWhileDown, writer.tracesLostWhileDown)
            tell(
                "The Journal failed and records nothing: $error. Facts and traces are lost, sql and the briefing are off " +
                    "until it opens its database again; it tries every ${tuning.reopenInterval}. See the server log.",
            )
        }
        release()
    }

    /** Closes the failed database: its file stays locked while it is open. Reads in flight fail. */
    private fun release() {
        released = true
        readers.clear()
        db.close()
    }

    override fun reopen(): DuckDBConnection {
        outage?.let { it.attempts++ }
        val open = reopenDatabase ?: throw IllegalStateException("a database in memory cannot be opened again")
        val fresh = open()
        db = fresh
        readers.clear()
        readers.addAll(fresh.readers)
        released = false
        return fresh.writer
    }

    override fun reopened() {
        val outage = outage ?: return
        val lost = lostSince(outage)
        Decisions(this, clock).record(
            log, DecisionRecord.Kind.JOURNAL_HEALTH, "reopened",
            reason = "the database opened again after ${took(outage)} (${outage.attempts} attempts; it failed with: ${outage.error}); " +
                "${lost.total} records lost during the outage: ${lost.facts} facts and ${lost.traces} trace records dropped while it was down, " +
                "${lost.total - lost.facts - lost.traces} more in the batch that failed or a full queue",
            detail = buildJsonObject {
                put("error", outage.error)
                put("since", outage.since.toString())
                put("outage_ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - outage.sinceNanos))
                put("attempts", outage.attempts)
                put("lost", lost.total)
                put("lost_facts", lost.facts)
                put("lost_traces", lost.traces)
            },
            level = Log.Level.WARN,
        )
    }

    override fun recovered() {
        val outage = outage ?: return
        this.outage = null
        val lost = lostSince(outage)
        log.info("recording again, after ${took(outage)} down: ${lost.total} records lost during the outage")
        tell("The Journal records again, after ${took(outage)}: ${lost.total} records were lost during the outage (see the server log).")
    }

    override fun died(error: String) {
        tell(
            "The Journal stopped recording: its writer failed ($error). Facts, traces and memory writes are lost, sql and the " +
                "briefing are off until the server restarts. See the server log.",
        )
    }

    private class Lost(val total: Long, val facts: Long, val traces: Long)

    private fun lostSince(outage: Outage) = Lost(
        health().droppedEvents - outage.lost,
        writer.factsLostWhileDown - outage.facts,
        writer.tracesLostWhileDown - outage.traces,
    )

    private fun took(outage: Outage): String {
        val seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - outage.sinceNanos)
        return if (seconds < 60) "$seconds s" else "${seconds / 60} min ${seconds % 60} s"
    }

    private fun tell(text: String) {
        try {
            tellOperators(text)
        } catch (e: Exception) {
            log.error("could not tell the operators: $text", e)
        }
    }

    /** The periodic report (M1_M2 §5.6): one log line and one `journal_health` decision. Writer thread. */
    private fun reportHealth() {
        val health = health()
        val now = System.nanoTime()
        val outage = outage
        if (outage != null && !health.available) {
            // No decision: it could not be written. The next report counts these losses as new.
            log.warn("still unavailable after ${took(outage)} (${outage.attempts} attempts to open the database): ${lostSince(outage).total} records lost since")
            return
        }
        val hooks = currentHookNanos()
        val previous = lastReport
        lastReport = Report(health.droppedEvents, hooks, now)
        val newLost = health.droppedEvents - previous.lost
        val minutes = (now - previous.at) / 60e9
        // The Journal opens before the Observer, whose time then counts from zero: a missing baseline is 0.
        val hookMsPerMinute = if (hooks != null && minutes > 0) (hooks - (previous.hookNanos ?: 0)) / 1e6 / minutes else null
        val reason = buildString {
            append("${health.rowsPerMinute} rows/min · ${health.queued} queued")
            append(" · last flush ${health.lastFlushMs} ms (${health.lastFlushRows} rows)")
            append(" · ${health.droppedEvents} lost ($newLost new)")
            append(" · file ${megabytes(health.fileBytes)}, WAL ${megabytes(health.walBytes)}")
            hookMsPerMinute?.let { append(" · Observer hooks %.1f ms/min".format(Locale.ROOT, it)) }
        }
        Decisions(this, clock).record(
            log, DecisionRecord.Kind.JOURNAL_HEALTH, if (newLost > 0) "lost" else "healthy",
            reason = reason,
            detail = buildJsonObject {
                put("rows_per_minute", health.rowsPerMinute)
                put("queued", health.queued)
                put("last_flush_ms", health.lastFlushMs)
                put("last_flush_rows", health.lastFlushRows)
                put("lost", health.droppedEvents)
                put("new_lost", newLost)
                put("file_bytes", health.fileBytes)
                put("wal_bytes", health.walBytes)
                hookMsPerMinute?.let { put("hook_ms_per_minute", Math.round(it * 10) / 10.0) }
            },
            level = if (newLost > 0) Log.Level.WARN else Log.Level.INFO,
        )
    }

    private fun currentHookNanos(): Long? = try {
        hookNanos()
    } catch (_: Exception) {
        null
    }

    /**
     * Ends the reads: no new one starts, those running get [READS_GRACE_MS], then the read connections
     * close, which interrupts a read still running and ends every query left open. The writer calls it
     * before its last `CHECKPOINT`, which a query left open on a read connection would otherwise hold
     * back (see [endQuery]). Idempotent.
     */
    private fun endReads() {
        closing = true
        readThreads.shutdown()
        if (!readThreads.awaitTermination(READS_GRACE_MS, TimeUnit.MILLISECONDS)) {
            log.warn("closing: reads still running after $READS_GRACE_MS ms are interrupted")
        }
        db.readers.forEach { connection ->
            try {
                connection.close()
            } catch (e: SQLException) {
                log.warn("closing a read connection failed: ${e.message}")
            }
        }
    }

    /**
     * Ends the reads, closes the database, then keeps on disk exactly the ids handed out. Once; on the
     * writer thread, or on the closing one if the writer died.
     */
    private fun closeResources() {
        if (!resourcesClosed.compareAndSet(false, true)) return
        endReads()
        if (!released) db.close()
        ids.close()
    }

    companion object {
        /** Name and owner of the Journal's bus subscription. */
        const val SUBSCRIBER = "journal"
        const val OWNER = "journal"

        /** Name of the Journal's synchronous subscription, which reserves the event ids. */
        const val ID_SUBSCRIBER = "journal_ids"

        private val CLOSE_RESERVE_NANOS = TimeUnit.SECONDS.toNanos(2)
        private const val READS_GRACE_MS = 1_000L
        private val READ_THREADS = AtomicInteger()
        private val log = Log.journal

        /**
         * Where each id sequence starts: past the largest id of its table, and past the ids other rows
         * cite (a decision citing an event, a chat line citing its turn, a memory citing its turn), in case
         * the row itself was lost. Id lists (`evidence`, `refs`, `covers`) are left out: the memory checks
         * them before writing, so they only cite rows that exist.
         */
        private val SEED_COLUMNS: Map<IdKind, List<String>> = mapOf(
            IdKind.EVENT to listOf(
                "events.id", "chat.id", "private_messages.id", "chat_signals.event_id", "engine_decisions.event_id", "recalls.event_id",
            ),
            IdKind.SESSION to listOf("sessions.session_id", "events.session_id", "positions.session_id"),
            IdKind.TURN to listOf(
                "turns.turn_id", "llm_calls.turn_id", "tool_calls.turn_id", "engine_decisions.turn_id", "chat.turn_id",
                "tasks.requested_by_turn", "memories.turn_id", "canon.turn_id", "interactions.turn_id", "recalls.turn_id",
                "memory_log.turn_id",
            ),
            IdKind.TASK to listOf("tasks.task_id", "conversations.task_id"),
            IdKind.LLM_CALL to listOf("llm_calls.id", "tool_calls.llm_call_id"),
            IdKind.TOOL_CALL to listOf("tool_calls.id"),
            IdKind.DECISION to listOf("engine_decisions.id"),
            IdKind.MEMORY to listOf(
                "memories.id", "canon.id", "interactions.id", "chronicle.id", "notes.id", "memories.merged_into", "canon.replaces",
                "interactions.canon_id", "interactions.memory_id", "chronicle.superseded_by", "recalls.ref_id", "search_docs.ref_id",
            ),
            IdKind.MEMORY_RUN to listOf(
                "memory_runs.run_id", "memories.run_id", "canon.run_id", "interactions.run_id", "player_traits.run_id",
                "player_skills.run_id", "player_relations.run_id", "memory_docs.run_id", "chronicle.run_id", "notes.run_id",
                "memory_log.run_id",
            ),
        )

        /**
         * Opens the Journal on [db], which it owns from now on: repairs what a crash left open, seeds the
         * ids (past the database and past `narrator.ids` next to it), and starts the writer. A failed
         * repair is logged and does not stop the Journal. After a failure, the writer opens the
         * database file again with the same settings; a database in memory is never opened again.
         */
        internal fun open(
            db: DuckDatabase,
            config: JournalConfig,
            clock: Clock,
            hookNanos: () -> Long? = { null },
            tuning: JournalTuning = JournalTuning(),
        ): DuckJournal {
            require(db.readers.isNotEmpty()) { "the Journal needs at least one read connection" }
            val repair = try {
                Repair.run(db.writer)
            } catch (e: Exception) {
                log.error("could not close what the previous run left open; recording anyway", e)
                null
            }
            val file = db.file
            val ids = IdReservations.open(file?.resolveSibling(IdReservations.FILE_NAME), seeds(db.writer), tuning.idBlock, tuning.eventIdBlock)
            val reopen = file?.let { path ->
                { DuckDatabase.open(path, DuckDatabase.Options.of(config).copy(timeZone = db.timeZone, readers = db.readers.size)) }
            }
            val journal = DuckJournal(db, clock, config.queueCapacity, ids, hookNanos, config.flushIntervalMs, tuning, reopen)
            journal.start(repair)
            return journal
        }

        /** The first free id of each kind. */
        internal fun seeds(connection: Connection): Map<IdKind, Long> = SEED_COLUMNS.mapValues { (_, columns) ->
            val maxima = columns.joinToString(" UNION ALL ") { column ->
                val (table, name) = column.split('.')
                "SELECT max($name) AS m FROM $table"
            }
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT coalesce(max(m), 0) + 1 FROM ($maxima)").use { rows ->
                    rows.next()
                    rows.getLong(1)
                }
            }
        }

        private fun megabytes(bytes: Long): String = "%.1f MB".format(Locale.ROOT, bytes / 1e6)
    }
}
