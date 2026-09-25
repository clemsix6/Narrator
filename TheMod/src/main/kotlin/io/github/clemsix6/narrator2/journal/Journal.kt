package io.github.clemsix6.narrator2.journal

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.event.EventBus
import io.github.clemsix6.narrator2.event.EventTable
import io.github.clemsix6.narrator2.trace.ChatSignalsRecord
import io.github.clemsix6.narrator2.trace.ConversationRecord
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.LlmCallRecord
import io.github.clemsix6.narrator2.trace.TaskRecord
import io.github.clemsix6.narrator2.trace.ToolCallRecord
import io.github.clemsix6.narrator2.trace.TraceSink
import io.github.clemsix6.narrator2.trace.TurnRecord
import java.sql.Connection
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Id sequences of the Journal. Each is seeded at `max(id) + 1` of its table when the Journal opens.
 *
 * [EVENT] ids are handed out by the [EventBus] (seeded once with [Journal.nextIdSeed]) and shared by
 * `events`, `chat` and `private_messages`.
 */
enum class IdKind { EVENT, SESSION, TURN, TASK, LLM_CALL, TOOL_CALL, DECISION }

/**
 * The Journal's state, for `/narrator status` and the periodic health report.
 *
 * @property queued records waiting for the writer.
 * @property droppedEvents records lost since the start (full queue, write error, degraded Journal).
 * @property lastFlushMs duration of the last write batch.
 * @property lastError the last write error, or why the Journal is unavailable.
 */
data class JournalHealth(
    val available: Boolean,
    val queued: Int,
    val droppedEvents: Long,
    val rowsPerMinute: Long,
    val lastFlushMs: Long,
    val lastFlushRows: Int,
    val fileBytes: Long,
    val walBytes: Long,
    val lastError: String?,
)

/** The Journal cannot serve a read: it failed to open, or went degraded after a write error. */
class JournalUnavailableException(reason: String) : IllegalStateException("the Journal is unavailable: $reason")

/** A read did not get a connection, or did not finish, within its time limit. */
class JournalTimeoutException(timeoutMs: Long) : RuntimeException("the Journal read did not finish within $timeoutMs ms")

/**
 * The history of the world (`world/narrator2/narrator.duckdb`): every game fact from the bus and every
 * trace of the engine. Writes are queued and batched by a single writer thread; reads run on a small
 * pool of read connections. Nothing here ever blocks the server thread.
 *
 * Privacy: the tables of [HIDDEN_FROM_MODEL] (private messages between players) are journaled but
 * must never reach the model. The `sql` tool rejects any query that names one of them, or that runs
 * SQL given as a string ([SQL_FROM_STRING_FUNCTIONS]); views read by the model never include them.
 */
interface Journal : TraceSink, AutoCloseable {
    /** False when the Journal could not open, or went degraded: reads fail, writes are counted as lost. */
    val available: Boolean

    /** The first id of [kind] not yet used; the bus takes it once for [IdKind.EVENT]. */
    fun nextIdSeed(kind: IdKind): Long

    /** Starts draining [bus] on the writer thread: every published fact gets journaled. */
    fun attach(bus: EventBus)

    /** Completes once every record handed over before the call is committed (bus events included). */
    suspend fun barrier()

    /**
     * Runs [query] on a read connection of the SQL pool, off the caller's thread.
     *
     * [timeoutMs] bounds the wait for a connection and the query; the query should also set its own
     * statement timeout (`Statement.setQueryTimeout`) so that it really stops. Fails with
     * [JournalUnavailableException] when [available] is false, with [JournalTimeoutException] after
     * [timeoutMs], and with the query's own exception otherwise.
     */
    suspend fun <T> read(timeoutMs: Long, query: (Connection) -> T): T

    /** Asks the writer to run `CHECKPOINT` after its next batch (called after each world save). */
    fun checkpointAsync()

    fun health(): JournalHealth

    /**
     * Writes what is queued, closes open sessions (`server_stop`), checkpoints and closes the database,
     * within [timeout]. What is still queued after [timeout] is lost and counted in the log.
     */
    fun close(timeout: Duration)

    override fun close() = close(DEFAULT_CLOSE_TIMEOUT)

    companion object {
        val DEFAULT_CLOSE_TIMEOUT: Duration = Duration.ofSeconds(10)

        /** Tables the model must never read, even through the `sql` tool. */
        val HIDDEN_FROM_MODEL: Set<String> = EventTable.entries.filterNot { it.visibleToModel }.map { it.sqlName }.toSet()

        /** DuckDB functions that run SQL given as a string, which could read a hidden table: refused in model queries. */
        val SQL_FROM_STRING_FUNCTIONS: Set<String> = setOf("query", "query_table")
    }
}

/**
 * The Journal when DuckDB could not be opened: facts and traces are not recorded (each dropped record
 * is logged at DEBUG), and reads fail with [JournalUnavailableException]. Its ids start at the current
 * time in milliseconds, so ids in logs stay unique across restarts.
 */
class DisabledJournal(private val reason: String, clock: Clock = Clock.systemUTC()) : Journal {
    private val log = Log.journal
    private val seed = clock.millis()
    private val ids = ConcurrentHashMap<IdKind, AtomicLong>()
    private val dropped = AtomicLong()

    override val available: Boolean get() = false

    override fun nextIdSeed(kind: IdKind): Long = counter(kind).get()

    override fun nextId(kind: IdKind): Long = counter(kind).getAndIncrement()

    override fun attach(bus: EventBus) = Unit

    override suspend fun barrier() = Unit

    override suspend fun <T> read(timeoutMs: Long, query: (Connection) -> T): T = throw JournalUnavailableException(reason)

    override fun checkpointAsync() = Unit

    override fun health() = JournalHealth(
        available = false, queued = 0, droppedEvents = dropped.get(), rowsPerMinute = 0,
        lastFlushMs = 0, lastFlushRows = 0, fileBytes = 0, walBytes = 0, lastError = reason,
    )

    override fun close(timeout: Duration) = Unit

    override fun llmCall(r: LlmCallRecord) = drop(r)

    override fun toolCall(r: ToolCallRecord) = drop(r)

    override fun decision(r: DecisionRecord) = drop(r)

    override fun turn(r: TurnRecord) = drop(r)

    override fun conversation(r: ConversationRecord) = drop(r)

    override fun task(r: TaskRecord) = drop(r)

    override fun chatSignals(r: ChatSignalsRecord) = drop(r)

    private fun counter(kind: IdKind): AtomicLong = ids.computeIfAbsent(kind) { AtomicLong(seed) }

    private fun drop(record: Any) {
        dropped.incrementAndGet()
        log.debug { "not recorded (the Journal is unavailable: $reason): $record" }
    }
}
