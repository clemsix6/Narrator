package io.github.clemsix6.narrator2.journal

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.event.Envelope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.ReceiveChannel
import org.duckdb.DuckDBAppender
import java.sql.PreparedStatement
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.EnumMap
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/**
 * Knobs of the writer that tests change; production uses the defaults.
 *
 * @property pollMs how often the writer moves what is queued into its batch (the batch is written
 *   every `journal.flushIntervalMs`, or at once when full, on a barrier and at the close).
 * @property healthIntervalMs how often the health report is logged and recorded.
 * @property maxBatch records written per transaction at most.
 */
internal data class JournalTuning(
    val pollMs: Long = 100,
    val healthIntervalMs: Long = TimeUnit.MINUTES.toMillis(5),
    val maxBatch: Int = 50_000,
)

/**
 * The Journal's writer (M1_M2 §5.6): a daemon thread, `narrator2-journal`, alone owner of the write
 * connection, its appenders and its prepared statements. Nothing else ever writes to the database.
 *
 * It takes facts from the bus subscription ([events]) and trace records and barriers from its own
 * bounded queue ([submit], [barrier]), and writes them in batches: one transaction per batch, the
 * append-only tables through one appender each, whole rows of the updated tables in bulk
 * ([ReplaceTable]), in-place updates through prepared statements ([Upsert]). A
 * batch that fails is rolled back and written again by halves, so that a faulty row is dropped alone
 * (logged with its content, counted as lost) and never takes the others with it. A failure of the
 * database itself (the connection no longer answers: disk full, corrupt file) turns the Journal
 * unavailable: what comes next is counted as lost.
 *
 * @param onHealthDue called on this thread every [JournalTuning.healthIntervalMs].
 * @param onFinished called on this thread once the writer stopped: closes the reads and the database.
 * @param endReads called on this thread at the close, before the last `CHECKPOINT`: a query left open
 *   on a read connection would hold it back.
 */
internal class JournalWriter(
    private val db: DuckDatabase,
    private val clock: Clock,
    flushIntervalMs: Long,
    private val queueCapacity: Int,
    private val tuning: JournalTuning,
    private val onHealthDue: () -> Unit,
    private val onFinished: () -> Unit,
    private val endReads: () -> Unit = {},
) {
    private val log = Log.journal
    private val connection = db.writer
    private val mapping = JournalMapping()
    private val appenders = EnumMap<AppendTable, DuckDBAppender>(AppendTable::class.java)
    private val statements = EnumMap<Upsert, PreparedStatement>(Upsert::class.java)

    private val flushNanos = TimeUnit.MILLISECONDS.toNanos(flushIntervalMs)
    private val pollNanos = TimeUnit.MILLISECONDS.toNanos(minOf(tuning.pollMs, flushIntervalMs))
    private val healthNanos = TimeUnit.MILLISECONDS.toNanos(tuning.healthIntervalMs)

    /** A `barrier()` call waiting for what was handed over before it. */
    private class Barrier(val done: CompletableDeferred<Unit> = CompletableDeferred())

    // Handed over by any thread.
    private val traces = ConcurrentLinkedQueue<Any>()
    private val tracesQueued = AtomicInteger()
    private val lostCount = AtomicLong()
    private val lastQueueWarning = AtomicLong(Long.MIN_VALUE)
    private val checkpointRequested = AtomicBoolean()
    private val closeRequested = AtomicBoolean()

    /** The bus subscription's queue, once attached. */
    @Volatile
    var events: ReceiveChannel<Envelope>? = null

    @Volatile
    private var drainDeadline = Long.MAX_VALUE

    // Writer thread only.
    private val pending = ArrayList<Any>()
    private val awaitingDrain = ArrayList<Barrier>()
    private val awaitingCommit = ArrayList<Barrier>()
    private val recentFlushes = ArrayDeque<LongArray>() // [nanoTime, rows] over the last minute
    private var eventsClosed = false
    private var rowErrorsThisMinute = 0
    private var rowErrorsSuppressed = 0
    private var rowErrorMinute = 0L

    // Read by any thread.
    @Volatile
    var available = true
        private set

    @Volatile
    var lastError: String? = null
        private set

    /** True once the writer stopped: nothing more is written, barriers return at once. */
    @Volatile
    var finished = false
        private set

    @Volatile
    var lastFlushMs = 0L
        private set

    @Volatile
    var lastFlushRows = 0
        private set

    @Volatile
    var rowsPerMinute = 0L
        private set

    @Volatile
    private var pendingCount = 0

    /** Records waiting for the writer on its side (not counting the bus subscription's queue). */
    val queued: Int get() = tracesQueued.get() + pendingCount

    /** Records lost since the start on the writer's side: full queue, faulty rows, unavailable or closed Journal. */
    val lost: Long get() = lostCount.get()

    private val thread = Thread(::run, "narrator2-journal").apply { isDaemon = true }

    fun start() = thread.start()

    /** Queues a trace record; never blocks. Counted as lost when the queue is full or the writer stopped. */
    fun submit(record: Any) {
        if (finished || !available) {
            lostCount.incrementAndGet()
            log.debug { "not recorded (${if (finished) "the Journal is closed" else "the Journal is unavailable"}): $record" }
            return
        }
        if (tracesQueued.incrementAndGet() > queueCapacity) {
            tracesQueued.decrementAndGet()
            lostCount.incrementAndGet()
            if (shouldWarn(lastQueueWarning)) log.warn("trace queue full ($queueCapacity): ${record.javaClass.simpleName} lost (${lost} lost so far)")
            return
        }
        traces.add(record)
    }

    /** Completes once everything handed over before the call is committed (or lost). */
    suspend fun barrier() {
        if (finished || !available) return
        val barrier = Barrier()
        traces.add(barrier)
        LockSupport.unpark(thread)
        // The writer may have stopped between the check and the add; it completes what it finds, not what comes later.
        if (finished) barrier.done.complete(Unit)
        barrier.done.await()
    }

    /** `CHECKPOINT` after the next batch. */
    fun requestCheckpoint() = checkpointRequested.set(true)

    /**
     * Asks the writer to write what is queued until [deadlineNanos] (a `System.nanoTime()` value), then
     * close the sessions still open, checkpoint and stop. The last [reserveNanos] before the deadline are
     * kept for that ending.
     */
    fun requestClose(deadlineNanos: Long, reserveNanos: Long) {
        drainDeadline = deadlineNanos - reserveNanos
        if (closeRequested.compareAndSet(false, true)) LockSupport.unpark(thread)
    }

    /** Waits for the writer thread to stop; true if it did within [timeoutMs]. */
    fun awaitStop(timeoutMs: Long): Boolean {
        if (thread.state == Thread.State.NEW) return true
        thread.join(timeoutMs.coerceAtLeast(1))
        return !thread.isAlive
    }

    // ── The loop ────────────────────────────────────────────────────────────────────────────────────

    private fun run() {
        var nextFlush = System.nanoTime() + flushNanos
        var nextHealth = System.nanoTime() + healthNanos
        try {
            while (true) {
                val closing = closeRequested.get()
                val drained = pull()
                val now = System.nanoTime()
                if (closing && now >= drainDeadline) break
                val due = now >= nextFlush || pending.size >= tuning.maxBatch || awaitingCommit.isNotEmpty() ||
                    closing || checkpointRequested.get()
                if (due) {
                    flush()
                    nextFlush = now + flushNanos
                    if (checkpointRequested.getAndSet(false)) checkpoint()
                }
                updateRowsPerMinute(now)
                if (now >= nextHealth) {
                    nextHealth = now + healthNanos
                    try {
                        onHealthDue()
                    } catch (e: Exception) {
                        log.error("the health report failed", e)
                    }
                }
                if (closing) {
                    if (drained && pending.isEmpty() && (events == null || eventsClosed)) break
                } else if (drained) {
                    LockSupport.parkNanos(pollNanos)
                }
            }
        } catch (e: Throwable) {
            log.error("the writer failed; the Journal stops recording", e)
            lastError = "the writer failed: ${e.message ?: e.javaClass.simpleName}"
            available = false
        } finally {
            finish()
        }
    }

    /**
     * Moves what is queued into [pending], up to [JournalTuning.maxBatch]; true when both queues were
     * emptied. A barrier waits for a pass that emptied the bus queue after it was taken: every fact
     * published before the `barrier()` call was in that queue before the barrier was queued.
     */
    private fun pull(): Boolean {
        try {
            while (true) {
                if (pending.size >= tuning.maxBatch) return false
                val item = traces.poll() ?: break
                if (item is Barrier) {
                    awaitingDrain += item
                } else {
                    tracesQueued.decrementAndGet()
                    pending += item
                }
            }
            val channel = events ?: return true.also { releaseDrainedBarriers() }
            while (true) {
                if (pending.size >= tuning.maxBatch) return false
                val result = channel.tryReceive()
                if (result.isSuccess) {
                    pending += result.getOrThrow()
                } else {
                    eventsClosed = result.isClosed
                    break
                }
            }
            releaseDrainedBarriers()
            return true
        } finally {
            pendingCount = pending.size
        }
    }

    private fun releaseDrainedBarriers() {
        awaitingCommit += awaitingDrain
        awaitingDrain.clear()
    }

    /** Writes [pending], then releases the barriers it satisfies. */
    private fun flush() {
        if (pending.isNotEmpty()) {
            write(pending)
            pending.clear()
            pendingCount = 0
        }
        awaitingCommit.forEach { it.done.complete(Unit) }
        awaitingCommit.clear()
    }

    private fun write(items: List<Any>) {
        if (!available) {
            lostCount.addAndGet(items.size.toLong())
            return
        }
        val start = System.nanoTime()
        val rows = ArrayList<Row>(items.size + 16)
        for (item in items) {
            try {
                rows += if (item is Envelope) mapping.rows(item) else mapping.traceRows(item)
            } catch (e: Exception) {
                lostCount.incrementAndGet()
                rowError("cannot turn ${describe(item)} into rows", e)
            }
        }
        val written = writeRows(rows)
        val ms = (System.nanoTime() - start) / 1_000_000
        lastFlushMs = ms
        lastFlushRows = written
        recentFlushes.addLast(longArrayOf(start, written.toLong()))
        log.debug { "wrote $written rows (${items.size} records) in $ms ms" }
    }
    // ── Writing rows ────────────────────────────────────────────────────────────────────────────────

    /**
     * Writes [rows] in one transaction and returns how many were written. When it fails, the database
     * is checked: if it no longer answers, the Journal turns unavailable; otherwise the rows are written
     * again by halves until each faulty row is alone, and dropped.
     */
    internal fun writeRows(rows: List<Row>): Int {
        if (rows.isEmpty()) return 0
        if (!available) {
            lostCount.addAndGet(rows.size.toLong())
            return 0
        }
        val error = commit(rows) ?: return rows.size
        if (!databaseAnswers()) {
            lostCount.addAndGet(rows.size.toLong())
            degrade(error)
            return 0
        }
        if (rows.size == 1) {
            lostCount.incrementAndGet()
            rowError("dropped ${rows[0]}", error)
            return 0
        }
        val half = rows.size / 2
        return writeRows(rows.subList(0, half)) + writeRows(rows.subList(half, rows.size))
    }

    /**
     * One transaction: the in-place updates in order, then the whole rows in bulk (the last record of
     * each key), then each appender filled and flushed, then the updates of appended rows
     * ([Upsert.afterAppends]) in order. Returns the failure, if any.
     */
    private fun commit(rows: List<Row>): Exception? {
        try {
            execute("BEGIN TRANSACTION")
            val appends = EnumMap<AppendTable, MutableList<Append>>(AppendTable::class.java)
            val replaces = EnumMap<ReplaceTable, LinkedHashMap<List<Any?>, Replace>>(ReplaceTable::class.java)
            val afterAppends = ArrayList<Execute>()
            for (row in rows) {
                when (row) {
                    is Execute -> if (row.upsert.afterAppends) afterAppends += row else update(row)
                    is Replace -> replaces.getOrPut(row.table) { LinkedHashMap() }[row.key] = row
                    is Append -> appends.getOrPut(row.table) { ArrayList() }.add(row)
                }
            }
            for ((table, latest) in replaces) replaceAll(table, latest.values.toList())
            for ((table, tableRows) in appends) {
                val appender = appender(table)
                for (row in tableRows) {
                    appender.beginRow()
                    row.values.forEach { appender.appendValue(it) }
                    appender.endRow()
                }
                appender.flush()
            }
            afterAppends.forEach(::update)
            execute("COMMIT")
            return null
        } catch (e: Exception) {
            // Still inside the failed transaction: whatever an appender holds is rolled back with it.
            discardAppenders()
            discardStatements()
            try {
                execute("ROLLBACK")
            } catch (_: SQLException) {
                // no transaction left to roll back
            }
            return e
        }
    }

    private fun appender(table: AppendTable): DuckDBAppender = appenders.getOrPut(table) { connection.createAppender(table.sqlName) }

    private fun statement(upsert: Upsert): PreparedStatement = statements.getOrPut(upsert) { connection.prepareStatement(upsert.sql) }

    private fun update(row: Execute) {
        val statement = statement(row.upsert)
        bind(statement, row.values)
        statement.executeUpdate()
    }

    /** `INSERT OR REPLACE` of [rows] (distinct keys), a few thousand values per statement. */
    private fun replaceAll(table: ReplaceTable, rows: List<Replace>) {
        val row = List(table.columns) { "?" }.joinToString(", ", "(", ")")
        rows.chunked(MAX_PARAMETERS / table.columns).forEach { chunk ->
            val sql = "INSERT OR REPLACE INTO ${table.sqlName} VALUES " + List(chunk.size) { row }.joinToString(", ")
            connection.prepareStatement(sql).use { statement ->
                chunk.forEachIndexed { index, replace -> bind(statement, replace.values, index * table.columns) }
                statement.executeUpdate()
            }
        }
    }

    /** Binds [values] from parameter `offset + 1`. */
    private fun bind(statement: PreparedStatement, values: List<Any?>, offset: Int = 0) {
        values.forEachIndexed { index, value ->
            val parameter = offset + index + 1
            when (value) {
                null -> statement.setObject(parameter, null)
                is Instant -> statement.setObject(parameter, OffsetDateTime.ofInstant(value, ZoneOffset.UTC))
                is List<*> -> statement.setArray(parameter, connection.createArrayOf("BIGINT", value.toTypedArray()))
                else -> statement.setObject(parameter, value)
            }
        }
    }

    private fun DuckDBAppender.appendValue(value: Any?) {
        when (value) {
            null -> appendNull()
            is Long -> append(value)
            is Int -> append(value)
            is Short -> append(value)
            is Byte -> append(value)
            is Double -> append(value)
            is Float -> append(value)
            is Boolean -> append(value)
            is String -> append(value)
            is UUID -> append(value)
            is Instant -> appendEpochMicros(Math.addExact(Math.multiplyExact(value.epochSecond, 1_000_000L), value.nano / 1_000L))
            is List<*> -> append(value)
            else -> throw IllegalArgumentException("no column type for ${value.javaClass.name}")
        }
    }

    private fun discardAppenders() {
        appenders.values.forEach { appender ->
            try {
                appender.close()
            } catch (_: SQLException) {
                // a broken appender: its rows go with the transaction
            }
        }
        appenders.clear()
    }

    private fun discardStatements() {
        statements.values.forEach { runCatching { it.close() } }
        statements.clear()
    }

    private fun execute(sql: String) {
        connection.createStatement().use { it.execute(sql) }
    }

    private fun databaseAnswers(): Boolean = try {
        connection.createStatement().use { statement -> statement.executeQuery("SELECT 1").use { it.next() } }
    } catch (_: SQLException) {
        false
    }

    private fun checkpoint(): Boolean {
        if (!available) return false
        val start = System.nanoTime()
        return try {
            execute("CHECKPOINT")
            log.debug { "checkpoint in ${(System.nanoTime() - start) / 1_000_000} ms" }
            true
        } catch (e: SQLException) {
            log.warn("CHECKPOINT failed, tried again at the next world save: ${e.message}")
            false
        }
    }

    // ── Failures ────────────────────────────────────────────────────────────────────────────────────

    private fun degrade(error: Exception) {
        if (!available) return
        lastError = error.message?.lineSequence()?.firstOrNull() ?: error.javaClass.simpleName
        available = false
        log.error(
            "the database failed, the Journal stops recording until the server restarts; facts and traces are counted as lost: $lastError",
            error,
        )
    }

    /** Logs a dropped row or record, at most [ROW_ERRORS_PER_MINUTE] lines a minute; the rest is counted. */
    private fun rowError(message: String, error: Exception) {
        lastError = error.message?.lineSequence()?.firstOrNull() ?: error.javaClass.simpleName
        val minute = System.nanoTime() / 60_000_000_000L
        if (minute != rowErrorMinute) {
            if (rowErrorsSuppressed > 0) log.warn("$rowErrorsSuppressed more faulty rows were dropped without a log line")
            rowErrorMinute = minute
            rowErrorsThisMinute = 0
            rowErrorsSuppressed = 0
        }
        if (rowErrorsThisMinute++ < ROW_ERRORS_PER_MINUTE) {
            log.error("$message: $lastError")
        } else {
            rowErrorsSuppressed++
        }
    }

    private fun shouldWarn(last: AtomicLong): Boolean {
        val now = System.nanoTime()
        val previous = last.get()
        return (previous == Long.MIN_VALUE || now - previous >= WARN_INTERVAL_NANOS) && last.compareAndSet(previous, now)
    }

    private fun describe(item: Any): String {
        val text = item.toString()
        return if (text.length <= 300) text else text.take(300) + "…"
    }

    private fun updateRowsPerMinute(now: Long) {
        while (recentFlushes.isNotEmpty() && now - recentFlushes.first()[0] > MINUTE_NANOS) recentFlushes.removeFirst()
        rowsPerMinute = recentFlushes.sumOf { it[1] }
    }

    // ── The end ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Closes the sessions still open, counts what is left as lost, releases every barrier, ends the
     * reads, checkpoints and hands over to [onFinished]. Runs once, on this thread.
     */
    private fun finish() {
        val start = System.nanoTime()
        val closing = closeRequested.get()
        if (available && closing) {
            try {
                val rows = mapping.closeOpenSessions(clock.instant())
                if (rows.isNotEmpty()) {
                    writeRows(rows)
                    log.info("closing: ${rows.size / 2} sessions still open closed (server_stop)")
                }
            } catch (e: Exception) {
                log.error("closing: the open sessions could not be closed", e)
            }
        }
        finished = true
        var left = pending.size.toLong()
        pending.clear()
        pendingCount = 0
        while (true) {
            val item = traces.poll() ?: break
            if (item is Barrier) item.done.complete(Unit) else left++
        }
        tracesQueued.set(0)
        events?.let { channel ->
            while (true) {
                if (channel.tryReceive().isSuccess) left++ else break
            }
        }
        (awaitingDrain + awaitingCommit).forEach { it.done.complete(Unit) }
        awaitingDrain.clear()
        awaitingCommit.clear()
        if (left > 0) {
            lostCount.addAndGet(left)
            log.warn("closing: $left records still queued are lost (the close timeout was reached)")
        }
        discardAppenders()
        discardStatements()
        // A writer that died without a close keeps the database open for reads; close() ends it later.
        if (!closing) return
        // No FORCE CHECKPOINT here: it waits for every open transaction, and one left open on a read
        // connection never ends by itself (verified: it hangs). Closing the read connections ends them.
        try {
            endReads()
        } catch (e: Exception) {
            log.error("closing: the reads could not be ended", e)
        }
        val checkpointed = checkpoint()
        log.info(
            "closing: ${if (checkpointed) "checkpointed" else "not checkpointed"} in ${(System.nanoTime() - start) / 1_000_000} ms, " +
                "$lost records lost since the start",
        )
        try {
            onFinished()
        } catch (e: Exception) {
            log.error("closing the database failed", e)
        }
    }

    private companion object {
        const val ROW_ERRORS_PER_MINUTE = 20

        /** Parameters bound to one bulk statement at most. */
        const val MAX_PARAMETERS = 10_000
        val WARN_INTERVAL_NANOS: Long = TimeUnit.MINUTES.toNanos(1)
        val MINUTE_NANOS: Long = TimeUnit.MINUTES.toNanos(1)
    }
}
