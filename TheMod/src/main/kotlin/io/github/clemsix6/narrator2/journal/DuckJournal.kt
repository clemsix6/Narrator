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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The Journal on DuckDB (M1_M2 §5): facts from the bus and traces of the engine, written in batches by
 * [JournalWriter] on its own thread; reads on a small pool of read connections (`narrator2-sql-N`).
 * Nothing here blocks the caller: records are queued, reads run on the pool.
 *
 * Ids ([nextId]) start past every id found in the database, including ids cited by other rows, so an
 * id is never handed out twice across restarts (unless its rows were lost in a crash before reaching
 * the disk).
 */
internal class DuckJournal private constructor(
    private val db: DuckDatabase,
    private val clock: Clock,
    private val queueCapacity: Int,
    seeds: Map<IdKind, Long>,
    private val hookNanos: () -> Long?,
    flushIntervalMs: Long,
    tuning: JournalTuning,
) : Journal {
    private val log = Log.journal
    private val ids: Map<IdKind, AtomicLong> = IdKind.entries.associateWith { AtomicLong(seeds.getValue(it)) }
    private val writer = JournalWriter(db, clock, flushIntervalMs, queueCapacity, tuning, ::reportHealth, ::closeResources)
    private val readers = LinkedBlockingQueue(db.readers)
    private val readThreads: ExecutorService = Executors.newFixedThreadPool(db.readers.size.coerceAtLeast(1)) { task ->
        Thread(task, "narrator2-sql-${READ_THREADS.incrementAndGet()}").apply { isDaemon = true }
    }
    private val resourcesClosed = AtomicBoolean()

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

    override fun nextIdSeed(kind: IdKind): Long = ids.getValue(kind).get()

    override fun nextId(kind: IdKind): Long = ids.getValue(kind).getAndIncrement()

    override fun attach(bus: EventBus) {
        check(subscription == null) { "the Journal is already attached to a bus" }
        val subscription = bus.subscribeAsync(SUBSCRIBER, OWNER, queueCapacity)
        this.bus = bus
        this.subscription = subscription
        writer.events = subscription.events
    }

    override suspend fun barrier() = writer.barrier()

    override suspend fun <T> read(timeoutMs: Long, query: (Connection) -> T): T {
        if (!available) throw JournalUnavailableException(health().lastError ?: "closed")
        val result = CompletableFuture<T>()
        try {
            readThreads.execute {
                if (result.isDone) return@execute // timed out or cancelled while waiting for a thread
                val connection = readers.take()
                try {
                    result.complete(query(connection))
                } catch (e: Throwable) {
                    result.completeExceptionally(e)
                } finally {
                    readers.put(connection)
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
     * Stops taking facts, writes what is queued, closes the open sessions (`server_stop`), checkpoints
     * and closes the database, within [timeout]. The last quarter of [timeout] (2 s at most) is kept
     * for the ending; what is still queued then is lost and counted in the log.
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

    /** The periodic report (M1_M2 §5.6): one log line and one `journal_health` decision. Writer thread. */
    private fun reportHealth() {
        val health = health()
        val now = System.nanoTime()
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

    /** Ends the reads, then closes the database. Once; on the writer thread, or on the closing one if the writer died. */
    private fun closeResources() {
        if (!resourcesClosed.compareAndSet(false, true)) return
        closing = true
        readThreads.shutdown()
        if (!readThreads.awaitTermination(READS_GRACE_MS, TimeUnit.MILLISECONDS)) {
            log.warn("closing: reads still running after $READS_GRACE_MS ms")
        }
        db.close()
    }

    companion object {
        /** Name and owner of the Journal's bus subscription. */
        const val SUBSCRIBER = "journal"
        const val OWNER = "journal"

        private val CLOSE_RESERVE_NANOS = TimeUnit.SECONDS.toNanos(2)
        private const val READS_GRACE_MS = 1_000L
        private val READ_THREADS = AtomicInteger()
        private val log = Log.journal

        /**
         * Where each id sequence starts: past the largest id of its table, and past the ids other rows
         * cite (a decision citing an event, a chat line citing its turn), in case the row itself was lost.
         */
        private val SEED_COLUMNS: Map<IdKind, List<String>> = mapOf(
            IdKind.EVENT to listOf("events.id", "chat.id", "private_messages.id", "chat_signals.event_id", "engine_decisions.event_id"),
            IdKind.SESSION to listOf("sessions.session_id", "events.session_id", "positions.session_id"),
            IdKind.TURN to listOf(
                "turns.turn_id", "llm_calls.turn_id", "tool_calls.turn_id", "engine_decisions.turn_id", "chat.turn_id",
                "tasks.requested_by_turn",
            ),
            IdKind.TASK to listOf("tasks.task_id", "conversations.task_id"),
            IdKind.LLM_CALL to listOf("llm_calls.id", "tool_calls.llm_call_id"),
            IdKind.TOOL_CALL to listOf("tool_calls.id"),
            IdKind.DECISION to listOf("engine_decisions.id"),
        )

        /**
         * Opens the Journal on [db], which it owns from now on: repairs what a crash left open, seeds the
         * ids, and starts the writer. A failed repair is logged and does not stop the Journal.
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
            val journal = DuckJournal(db, clock, config.queueCapacity, seeds(db.writer), hookNanos, config.flushIntervalMs, tuning)
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
