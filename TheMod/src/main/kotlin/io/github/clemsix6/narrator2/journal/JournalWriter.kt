package io.github.clemsix6.narrator2.journal

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.event.Envelope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.ReceiveChannel
import org.duckdb.DuckDBAppender
import org.duckdb.DuckDBConnection
import java.sql.Connection
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
 * @property reopenIntervalMs how often the writer tries to open a failed database again.
 * @property idBlock ids reserved on disk at a time, for each kind but events ([IdReservations]).
 * @property eventIdBlock event ids reserved on disk at a time.
 * @property writeQueueCapacity write jobs ([Journal.write]) waiting for the writer at most.
 */
internal data class JournalTuning(
    val pollMs: Long = 100,
    val healthIntervalMs: Long = TimeUnit.MINUTES.toMillis(5),
    val maxBatch: Int = 50_000,
    val reopenIntervalMs: Long = TimeUnit.MINUTES.toMillis(1),
    val idBlock: Long = 1_000,
    val eventIdBlock: Long = 100_000,
    val writeQueueCapacity: Int = 1_024,
) {
    /** [reopenIntervalMs] for a log line: `60 s`. */
    val reopenInterval: String get() = if (reopenIntervalMs % 1000 == 0L) "${reopenIntervalMs / 1000} s" else "$reopenIntervalMs ms"
}

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
 * database itself (the connection no longer answers: disk full, file locked, connection closed) turns
 * the Journal unavailable: what comes next is counted as lost, and the writer tries to open the
 * database again every [JournalTuning.reopenIntervalMs] through [recovery]. While it is down, facts
 * still go through the mapping, which keeps following the sessions.
 *
 * It also runs the write jobs of the memory ([submitWrite], M3 §4.1), from a third queue, after the
 * batch of the turn if one was due: one transaction per job, in the order they came, each seeing every
 * fact committed before it. A job that fails is rolled back and its exception handed to its caller; the
 * writer goes on.
 *
 * @param onHealthDue called on this thread every [JournalTuning.healthIntervalMs].
 * @param onFinished called on this thread once the writer stopped: closes the reads and the database.
 * @param endReads called on this thread at the close, before the last `CHECKPOINT`: a query left open
 *   on a read connection would hold it back.
 * @param recovery how to get over a failed database; without it, a failure lasts until the restart.
 */
internal class JournalWriter(
    db: DuckDatabase,
    private val clock: Clock,
    flushIntervalMs: Long,
    private val queueCapacity: Int,
    private val tuning: JournalTuning,
    private val onHealthDue: () -> Unit,
    private val onFinished: () -> Unit,
    private val endReads: () -> Unit = {},
    private val recovery: Recovery? = null,
) {
    /** How the writer gets over a database that stopped answering (DuckJournal). Every call runs on the writer thread. */
    interface Recovery {
        /** The database stopped answering, for [error]: close it (its file stays locked while it is open). */
        fun failed(error: String)

        /** Opens the database again and returns its write connection; throws while it cannot. */
        fun reopen(): DuckDBConnection

        /** The database opened again: records are taken again, and the next batch is written at once. */
        fun reopened()

        /** A batch was committed on the reopened database: the outage is over. */
        fun recovered()

        /** The writer thread died of an unexpected error (a bug, not the database): nothing is recorded until the restart. */
        fun died(error: String)
    }

    private val log = Log.journal
    private var connection: DuckDBConnection = db.writer // writer thread
    private val mapping = JournalMapping()
    private val appenders = EnumMap<AppendTable, DuckDBAppender>(AppendTable::class.java)
    private val statements = EnumMap<Upsert, PreparedStatement>(Upsert::class.java)

    private val flushNanos = TimeUnit.MILLISECONDS.toNanos(flushIntervalMs)
    private val pollNanos = TimeUnit.MILLISECONDS.toNanos(minOf(tuning.pollMs, flushIntervalMs))
    private val healthNanos = TimeUnit.MILLISECONDS.toNanos(tuning.healthIntervalMs)
    private val reopenNanos = TimeUnit.MILLISECONDS.toNanos(tuning.reopenIntervalMs)

    /** A `barrier()` call waiting for what was handed over before it. */
    private class Barrier(val done: CompletableDeferred<Unit> = CompletableDeferred())

    /** A [submitWrite] call: [block] to run in a transaction of its own, its outcome to hand over. */
    private class WriteJob<T>(private val block: (Connection) -> T) {
        val result = CompletableDeferred<T>()

        /** Runs [block]; what it returns is handed over by the returned function, once committed. */
        fun run(connection: Connection): () -> Unit {
            val value = block(connection)
            return { result.complete(value) }
        }
    }

    // Handed over by any thread.
    private val traces = ConcurrentLinkedQueue<Any>()
    private val tracesQueued = AtomicInteger()
    private val jobs = ConcurrentLinkedQueue<WriteJob<*>>()
    private val jobsQueued = AtomicInteger()
    private val lastJobQueueWarning = AtomicLong(Long.MIN_VALUE)
    private val lostCount = AtomicLong()
    private val factsLostWhileDownCount = AtomicLong()
    private val tracesLostWhileDownCount = AtomicLong()
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
    private var nextReopen = Long.MAX_VALUE
    private var reopenFailures = 0
    private var flushSoon = false

    /** True from a reopening until the first batch committed on the reopened database. */
    private var recovering = false

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

    /** Records and write jobs waiting for the writer on its side (not counting the bus subscription's queue). */
    val queued: Int get() = tracesQueued.get() + pendingCount + jobsQueued.get()

    /** Records lost since the start on the writer's side: full queue, faulty rows, unavailable or closed Journal. */
    val lost: Long get() = lostCount.get()

    /** Facts from the bus dropped since the start because the database was down. */
    val factsLostWhileDown: Long get() = factsLostWhileDownCount.get()

    /** Trace records dropped since the start because the database was down. */
    val tracesLostWhileDown: Long get() = tracesLostWhileDownCount.get()

    private val thread = Thread(::run, "narrator2-journal").apply { isDaemon = true }

    fun start() = thread.start()

    /** Queues a trace record; never blocks. Counted as lost when the queue is full or the writer stopped. */
    fun submit(record: Any) {
        if (finished || !available) {
            lostCount.incrementAndGet()
            if (!finished) tracesLostWhileDownCount.incrementAndGet()
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

    /**
     * Queues [block] to run on the writer thread, in a transaction of its own, and returns its outcome:
     * its result once committed, or its exception once rolled back. Never blocks. Fails at once with
     * [JournalUnavailableException] when the writer stopped, the database is down or the queue holds
     * [JournalTuning.writeQueueCapacity] jobs. Cancelling the outcome before the job starts drops the job.
     */
    fun <T> submitWrite(block: (Connection) -> T): CompletableDeferred<T> {
        if (finished) throw JournalUnavailableException("the Journal is closed")
        if (!available) throw JournalUnavailableException(lastError ?: "the database is down")
        if (jobsQueued.incrementAndGet() > tuning.writeQueueCapacity) {
            jobsQueued.decrementAndGet()
            if (shouldWarn(lastJobQueueWarning)) log.warn("write queue full (${tuning.writeQueueCapacity} jobs): a memory write is refused")
            throw JournalUnavailableException("the Journal's write queue is full")
        }
        val job = WriteJob(block)
        jobs.add(job)
        LockSupport.unpark(thread)
        // The writer may have stopped between the check and the add; it fails what it finds, not what comes later.
        if (finished) failJobs("the Journal is closed")
        return job.result
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
                    closing || checkpointRequested.get() || flushSoon
                if (due) {
                    flushSoon = false
                    flush()
                    nextFlush = now + flushNanos
                    if (checkpointRequested.getAndSet(false)) checkpoint()
                }
                val jobsDone = runJobs()
                updateRowsPerMinute(now)
                if (!available && !closing && now >= nextReopen) {
                    nextReopen = now + reopenNanos
                    reopen()
                    if (available) continue // straight to the batch that confirms it
                }
                if (now >= nextHealth) {
                    nextHealth = now + healthNanos
                    try {
                        onHealthDue()
                    } catch (e: Exception) {
                        log.error("the health report failed", e)
                    }
                }
                if (closing) {
                    if (drained && jobsDone && pending.isEmpty() && (events == null || eventsClosed)) break
                } else if (drained && jobsDone) {
                    LockSupport.parkNanos(pollNanos)
                }
            }
        } catch (e: Throwable) {
            val cause = e.message?.lineSequence()?.firstOrNull() ?: e.javaClass.simpleName
            log.error("the writer failed: $cause. The writer thread died (a bug, not a database failure): the Journal records nothing until the server restarts", e)
            lastError = "the writer failed: $cause"
            available = false
            try {
                recovery?.died(cause)
            } catch (e2: Exception) {
                log.error("could not report the writer's death", e2)
            }
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
            dropWhileDown(items)
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
        if (recovering && available) {
            recovering = false
            recovery?.recovered()
        }
    }

    /**
     * Drops [items] while the database is down, counted as lost. Facts still go through the mapping,
     * which keeps following the sessions: once the database is back, facts get their session again
     * and a departure writes its whole session.
     */
    private fun dropWhileDown(items: List<Any>) {
        for (item in items) {
            if (item is Envelope) {
                factsLostWhileDownCount.incrementAndGet()
                try {
                    mapping.rows(item)
                } catch (_: Exception) {
                    // counted as lost all the same
                }
            } else {
                tracesLostWhileDownCount.incrementAndGet()
            }
        }
        lostCount.addAndGet(items.size.toLong())
    }

    // ── Write jobs ──────────────────────────────────────────────────────────────────────────────────

    /**
     * Runs the queued write jobs in order, for [JOBS_SLICE_NANOS] at most so that the bus queue is still
     * drained during a burst of jobs (and not past the close deadline); true when the queue was emptied.
     */
    private fun runJobs(): Boolean {
        val stop = minOf(System.nanoTime() + JOBS_SLICE_NANOS, drainDeadline)
        while (true) {
            if (System.nanoTime() >= stop) return jobs.isEmpty()
            val job = jobs.poll() ?: return true
            jobsQueued.decrementAndGet()
            runJob(job)
        }
    }

    /**
     * One job, one transaction: `BEGIN`, the block, `COMMIT`. When the block or the commit fails, the
     * transaction is rolled back and the failure handed to the caller, unless the database no longer
     * answers: then the Journal turns unavailable ([degrade]) and the caller gets a
     * [JournalUnavailableException]. A job whose caller gave up before it started is dropped.
     */
    private fun runJob(job: WriteJob<*>) {
        if (job.result.isCompleted) {
            log.debug { "a write job is dropped: its caller gave up before it started" }
            return
        }
        if (!available) {
            job.result.completeExceptionally(JournalUnavailableException(lastError ?: "the database is down"))
            return
        }
        val start = System.nanoTime()
        try {
            execute("BEGIN TRANSACTION")
            val complete = job.run(connection)
            execute("COMMIT")
            complete()
        } catch (e: Throwable) {
            try {
                execute("ROLLBACK")
            } catch (_: SQLException) {
                // no transaction left to roll back
            }
            if (databaseAnswers()) {
                log.debug { "a write job failed and was rolled back: $e" }
                job.result.completeExceptionally(e)
            } else {
                degrade(e as? Exception ?: RuntimeException(e))
                job.result.completeExceptionally(JournalUnavailableException(lastError ?: "the database is down").apply { initCause(e) })
            }
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        if (ms > SLOW_JOB_MS) log.warn("a write job held the writer for $ms ms (more than $SLOW_JOB_MS ms)") else log.debug { "write job in $ms ms" }
    }

    /** Fails every job still queued with [reason]; returns how many. */
    private fun failJobs(reason: String): Int {
        var failed = 0
        while (true) {
            val job = jobs.poll() ?: break
            jobsQueued.decrementAndGet()
            if (job.result.completeExceptionally(JournalUnavailableException(reason))) failed++
        }
        return failed
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
            degrade(error)
            lostCount.addAndGet(rows.size.toLong()) // after degrade: counted in the outage
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

    /**
     * The database no longer answers: the Journal turns unavailable, and [recovery] closes the
     * database and tries to open it again every [JournalTuning.reopenIntervalMs].
     */
    private fun degrade(error: Exception) {
        if (!available) return
        val why = error.message?.lineSequence()?.firstOrNull() ?: error.javaClass.simpleName
        lastError = why
        available = false
        recovering = false
        val retry = if (recovery == null) "until the server restarts" else "until it opens the database again (every ${tuning.reopenInterval})"
        log.error("the database failed, the Journal stops recording $retry; facts and traces are counted as lost: $why", error)
        discardAppenders()
        discardStatements()
        try {
            recovery?.failed(why)
        } catch (e: Exception) {
            log.error("closing the failed database failed", e)
        }
        nextReopen = System.nanoTime() + reopenNanos
    }

    /** One attempt to open the database again; once it opens, records are taken again and written at once. */
    private fun reopen() {
        val recovery = recovery ?: return
        val start = System.nanoTime()
        val fresh = try {
            recovery.reopen()
        } catch (e: Exception) {
            reopenFailures++
            val why = e.message?.lineSequence()?.firstOrNull() ?: e.javaClass.simpleName
            val line = "opening the database again failed (attempt $reopenFailures), next attempt in ${tuning.reopenInterval}: $why"
            if (reopenFailures == 1 || reopenFailures % 10 == 0) log.warn(line) else log.debug { line }
            return
        }
        connection = fresh
        reopenFailures = 0
        recovering = true
        flushSoon = true
        available = true
        log.info("opened the database again in ${(System.nanoTime() - start) / 1_000_000} ms")
        recovery.reopened()
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
        val failedJobs = failJobs(if (closing) "the Journal is closed" else lastError ?: "the writer stopped")
        if (failedJobs > 0) log.warn("$failedJobs write jobs still queued failed (the writer stopped)")
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

        /** A write job longer than this is logged as a warning (M3 §4.1). */
        const val SLOW_JOB_MS = 500L

        /** Time given to the write jobs in one turn of the loop, at most. */
        val JOBS_SLICE_NANOS: Long = TimeUnit.MILLISECONDS.toNanos(100)
        val WARN_INTERVAL_NANOS: Long = TimeUnit.MINUTES.toNanos(1)
        val MINUTE_NANOS: Long = TimeUnit.MINUTES.toNanos(1)
    }
}
