package io.github.clemsix6.narrator2.journal

import io.github.clemsix6.narrator2.config.JournalConfig
import io.github.clemsix6.narrator2.event.Envelope
import io.github.clemsix6.narrator2.event.EventBus
import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.testing.TestClock
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.sql.ResultSet
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * A Journal on a real DuckDB database (in memory, or in [file]) attached to a real bus, for tests.
 * [sync] waits until everything published or recorded so far is committed; [rows] reads it back.
 */
internal class JournalFixture(
    val file: Path? = null,
    val config: JournalConfig = JournalConfig(),
    val tuning: JournalTuning = JournalTuning(),
    val clock: TestClock = TestClock(),
    hookNanos: () -> Long? = { null },
    /** DuckDB options; a low memory limit lets a test force a spill to `<db>.tmp/` cheaply (SqlFileAccessTest). */
    options: DuckDatabase.Options = OPTIONS,
    /** Runs before each attempt to open a failed database again (JournalRecoveryTest). */
    beforeReopen: () -> Unit = {},
) : AutoCloseable {
    val db: DuckDatabase = if (file == null) DuckDatabase.openInMemory(options) else DuckDatabase.open(file, options)
    val journal: DuckJournal = DuckJournal.open(db, config, clock, hookNanos, tuning, beforeReopen)
    var gameTime = 1_000L
    val bus = EventBus(clock, { gameTime }, journal.nextIdSeed(IdKind.EVENT)).also { journal.attach(it) }

    fun publish(event: GameEvent): Envelope = bus.publish(event) ?: error("the bus is closed")

    fun sync() = runBlocking { journal.barrier() }

    /** Every row of [sql], each read by [row], after [sync]. */
    fun <T> rows(sql: String, row: ResultSet.() -> T): List<T> {
        sync()
        return runBlocking {
            journal.read(10_000) { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(sql).use { rows -> buildList { while (rows.next()) add(rows.row()) } }
                }
            }
        }
    }

    /** Every row of [sql] as a list of column values, after [sync]. */
    fun values(sql: String): List<List<Any?>> = rows(sql) { (1..metaData.columnCount).map { plain(getObject(it)) } }

    fun count(table: String): Long = rows("SELECT count(*) FROM $table") { getLong(1) }.single()

    override fun close() {
        bus.close()
        journal.close(Duration.ofSeconds(10))
    }

    companion object {
        val OPTIONS = DuckDatabase.Options(memoryLimitMb = 256, threads = 2, timeZone = ZoneId.of("UTC"))

        /** JDBC values made comparable: timestamps as instants, arrays as lists, JSON as text. */
        fun plain(value: Any?): Any? = when (value) {
            is OffsetDateTime -> value.toInstant()
            is java.sql.Array -> (value.array as Array<*>).toList()
            null, is Number, is String, is Boolean, is java.util.UUID -> value
            else -> value.toString()
        }
    }
}
