package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.event.EventBus
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.journal.DuckDatabase
import io.github.clemsix6.narrator2.journal.IdKind
import io.github.clemsix6.narrator2.journal.Journal
import io.github.clemsix6.narrator2.journal.JournalHealth
import io.github.clemsix6.narrator2.testing.FakeMemory
import io.github.clemsix6.narrator2.testing.MemorySamples
import io.github.clemsix6.narrator2.testing.MemoryTraceSink
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.trace.TraceSink
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A Journal whose write jobs run directly on the write connection of an in-memory database, each in its
 * own transaction (rolled back on an exception, as `Journal.write` does), and whose reads run on a read
 * connection: the store's tests run without the Journal's writer thread (M3-02 writes the real one).
 * Traces go to [sink]; memory ids start at 1 000, past the rows the tests insert by hand.
 */
internal class DirectJournal(val db: DuckDatabase, val sink: MemoryTraceSink = MemoryTraceSink()) : Journal, TraceSink by sink {
    private val ids = ConcurrentHashMap<IdKind, AtomicLong>()
    private val lock = Any()
    val barriers = AtomicInteger()
    val writes = AtomicInteger()

    override val available: Boolean get() = true

    override fun nextId(kind: IdKind): Long = ids.computeIfAbsent(kind) { AtomicLong(1_000) }.getAndIncrement()

    override fun nextIdSeed(kind: IdKind): Long = ids[kind]?.get() ?: 1_000

    override fun attach(bus: EventBus) = Unit

    override suspend fun barrier() {
        barriers.incrementAndGet()
    }

    override suspend fun <T> read(timeoutMs: Long, query: (Connection) -> T): T = synchronized(db.readers[0]) { query(db.readers[0]) }

    override suspend fun <T> write(timeoutMs: Long, block: (Connection) -> T): T = synchronized(lock) {
        val connection = db.writer
        connection.autoCommit = false
        try {
            block(connection).also {
                connection.commit()
                writes.incrementAndGet()
            }
        } catch (e: Throwable) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    override fun checkpointAsync() = Unit

    override fun health() = JournalHealth(true, 0, 0, 0, 0, 0, 0, 0, null)

    override fun close(timeout: Duration) = db.close()
}

/**
 * A simple [Indexer] on the real index tables (so that the job's transaction covers it): terms are the
 * lowercase words of three letters or more; [similarity] is the share of the text's terms the candidate has.
 */
internal class FakeIndexer : Indexer {
    override fun index(connection: Connection, ref: Ref, text: String) {
        remove(connection, ref)
        val terms = terms(text)
        connection.update("INSERT INTO search_docs VALUES (?, ?, ?)", ref.id, ref.kind.id, terms.size)
        terms.groupingBy { it }.eachCount().forEach { (term, tf) ->
            connection.update("INSERT INTO search_postings VALUES (?, ?, ?)", ref.id, term, tf.toShort())
        }
    }

    override fun remove(connection: Connection, ref: Ref) {
        connection.update("DELETE FROM search_postings WHERE ref_id = ?", ref.id)
        connection.update("DELETE FROM search_docs WHERE ref_id = ?", ref.id)
    }

    override fun similarity(connection: Connection, text: String, candidates: Collection<Ref>): Map<Ref, Double> {
        val query = terms(text).toSet()
        return candidates.associateWith { ref ->
            val document = connection.query("SELECT term FROM search_postings WHERE ref_id = ?", ref.id) { getString(1) }.toSet()
            if (query.isEmpty()) 0.0 else query.count { it in document }.toDouble() / query.size
        }
    }

    private fun terms(text: String): List<String> = text.lowercase().split(Regex("[^\\p{L}\\p{N}_:]+")).filter { it.length >= 3 }
}

/**
 * A store on an in-memory database with two players (Bartok, Mimosa), and helpers to put Journal facts
 * (events, chat, tool calls, memory runs) under it.
 */
internal class StoreFixture : AutoCloseable {
    val clock = TestClock(MemorySamples.evening, ZoneId.of("UTC"))
    val db: DuckDatabase = DuckDatabase.openInMemory(DuckDatabase.Options(memoryLimitMb = 128, threads = 1, timeZone = ZoneId.of("UTC")))
    val journal = DirectJournal(db)
    val playClock = FakeMemory.FakePlayClock()
    val indexer = FakeIndexer()
    val store = DuckMemoryStore(journal, indexer, playClock, clock)

    val bartok = MemorySamples.bartok
    val mimosa = MemorySamples.mimosa
    val director = MemorySamples.director
    val dreamer = MemorySamples.dreamer
    val operator = MemorySamples.operator

    init {
        player(bartok.uuid, bartok.name)
        player(mimosa.uuid, mimosa.name)
    }

    fun sql(sql: String, vararg parameters: Any?) {
        db.writer.update(sql, *parameters)
    }

    fun <T> rows(sql: String, vararg parameters: Any?, row: java.sql.ResultSet.() -> T): List<T> = db.writer.query(sql, *parameters, row = row)

    fun count(table: String, where: String = "true"): Long = rows("SELECT count(*) FROM $table WHERE $where") { getLong(1) }.single()

    /** Every row of [table] as text, in a stable order: to compare states. */
    fun dump(table: String): List<String> = rows("SELECT to_json(t)::VARCHAR AS j FROM $table t ORDER BY j") { getString(1) }

    fun player(uuid: UUID, name: String) =
        sql("INSERT INTO players VALUES (?, ?, ?, ?, 1, 0, false)", uuid, name, clock.instant(), clock.instant())

    fun event(id: Long, at: Instant, type: String, player: UUID? = null, pos: Pos? = null) = sql(
        "INSERT INTO events (id, ts, game_time, type, player_uuid, dim, x, y, z) VALUES (?, ?, 0, ?, ?, ?, ?, ?, ?)",
        id, at, type, player, pos?.dim, pos?.x, pos?.y, pos?.z,
    )

    /** A line of the chat: [player] null for the Narrator (`narrator` or `operator` [channel]). */
    fun chat(id: Long, at: Instant, channel: String, text: String, player: UUID? = null) = sql(
        "INSERT INTO chat (id, ts, channel, player_uuid, text) VALUES (?, ?, ?, ?, ?)",
        id, at, channel, player, text,
    )

    fun toolCall(id: Long, at: Instant, tool: String, args: String = "{}", status: String = "ok") = sql(
        "INSERT INTO tool_calls (id, ts, conversation_id, tool_use_id, role, tool, args, status, duration_ms, effect) " +
            "VALUES (?, ?, 'director-c13', 'tu', 'director', ?, ?::JSON, ?, 5, 'speech')",
        id, at, tool, args, status,
    )

    /** A batch job of the Chronicler over [from]..[to]; returns its author. */
    fun run(runId: Long, from: Instant, to: Instant): Author {
        sql(
            "INSERT INTO memory_runs (run_id, kind, trigger, status, started_at, window_from, window_to, conversations) " +
                "VALUES (?, 'batch', 'timer', 'running', ?, ?, ?, [])",
            runId, to, from, to,
        )
        return Author(Source.CHRONICLER, runId = runId, conversationId = "chronicler-$runId")
    }

    override fun close() = db.close()
}
