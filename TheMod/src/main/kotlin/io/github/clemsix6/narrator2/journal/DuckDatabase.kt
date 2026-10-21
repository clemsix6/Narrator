package io.github.clemsix6.narrator2.journal

import io.github.clemsix6.narrator2.config.JournalConfig
import io.github.clemsix6.narrator2.core.Log
import org.duckdb.DuckDBConnection
import org.duckdb.DuckDBDriver
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.time.ZoneId
import java.util.Properties

/**
 * The Journal's DuckDB database, opened and locked down: one write connection (for the writer thread,
 * which alone owns it and its appenders) and a few read connections (one per thread of the SQL pool).
 *
 * Opening, in order:
 * 1. connection properties: no file system access from SQL (`read_csv`, `glob`, `ATTACH`, `COPY`
 *    fail), no extension download, bounded memory and threads, streamed results. DuckDB still lets
 *    SQL read its own files: this database, its `.wal`, `.wal.checkpoint`, and the `<file>.tmp/`
 *    folder where it spills blocks, all holding the private messages in raw bytes; and
 *    `current_setting('allowed_paths')` tells where they are. [SqlRunner] keeps them out of the
 *    model's reach ([SqlAccess.MODEL]);
 * 2. `SET TimeZone` on the write connection: TIMESTAMPTZ values read back in the Journal's zone;
 * 3. the migrations of [MIGRATIONS] not applied yet, each in one transaction with its `schema_version` row;
 * 4. [VIEWS], if bundled, run at every opening (`CREATE OR REPLACE`: views and macros follow the code);
 * 5. the `event_types` table recreated from the event catalog ([EventTypesTable]), also at every opening;
 * 6. `SET lock_configuration = true`: no setting can change afterwards, on any connection;
 * 7. the read connections, duplicated from the write connection (they inherit the time zone).
 *
 * A connection must only be used by one thread at a time.
 */
class DuckDatabase private constructor(
    /** The database file; null in memory. */
    val file: Path?,
    val writer: DuckDBConnection,
    val readers: List<DuckDBConnection>,
    /** Schema version after the migrations. */
    val schemaVersion: Int,
    val timeZone: ZoneId,
) : AutoCloseable {

    /**
     * @property memoryLimitMb DuckDB's memory limit (it would otherwise take 80 % of the RAM).
     * @property threads DuckDB's worker threads (it would otherwise take one per core).
     * @property timeZone zone of the TIMESTAMPTZ values read back.
     * @property readers read connections to open: two, for the Director, the memory and the rest; three with the
     *   dashboard, which reads one query at a time and so always leaves two to the others (M6 §8.5).
     */
    data class Options(
        val memoryLimitMb: Int = 512,
        val threads: Int = 2,
        val timeZone: ZoneId = ZoneId.systemDefault(),
        val readers: Int = 2,
    ) {
        companion object {
            /**
             * Options from the `journal` section; an empty `timeZone` means the server's zone. [dashboard]: the web
             * dashboard is on (`dashboard.enabled`), and gets a read connection of its own.
             */
            fun of(config: JournalConfig, dashboard: Boolean = false) = Options(
                memoryLimitMb = config.memoryLimitMb,
                threads = config.threads,
                timeZone = config.timeZone.ifBlank { null }?.let(ZoneId::of) ?: ZoneId.systemDefault(),
                readers = if (dashboard) 3 else 2,
            )
        }
    }

    /** Size of the database file in bytes; 0 in memory or before the first checkpoint. */
    fun fileBytes(): Long = sizeOf(file)

    /** Size of the write-ahead log in bytes. */
    fun walBytes(): Long = sizeOf(file?.let { it.resolveSibling("${it.fileName}.wal") })

    /** Closes the read connections, then the write connection (which closes the database). */
    override fun close() {
        (readers + writer).forEach { connection ->
            try {
                connection.close()
            } catch (e: SQLException) {
                log.warn("closing a connection to ${file ?: "the in-memory database"} failed: ${e.message}")
            }
        }
    }

    private fun sizeOf(path: Path?): Long = if (path != null && Files.isRegularFile(path)) Files.size(path) else 0

    companion object {
        /** File name of the Journal's database, in `world/narrator2/`. */
        const val FILE_NAME = "narrator.duckdb"

        /** Folder of the SQL scripts in the jar. */
        const val SCRIPTS = "/narrator2/sql/"

        /**
         * Schema migrations, applied in order: `V<version>__<name>.sql`. A migration is never edited once
         * released; adding one means adding its file name here.
         */
        val MIGRATIONS: List<String> = listOf("V1__journal.sql", "V2__memory.sql", "V3__mechanics.sql", "V4__style_ideas.sql")

        /** Views and macros for the model, run at every opening. */
        const val VIEWS = "views.sql"

        /** The schema version this code expects. */
        val SCHEMA_VERSION: Int get() = versionOf(MIGRATIONS.last())

        /** At most this much query data spilled to disk, all queries together (DuckDB's `max_temp_directory_size`). */
        const val MAX_TEMP_DIRECTORY_SIZE = "1GiB"

        private val log = Log.journal

        /** Opens (creating it if needed) the database at [file]. Fails if the file is unusable or a migration fails. */
        fun open(file: Path, options: Options): DuckDatabase {
            val absolute = file.toAbsolutePath().normalize()
            Files.createDirectories(absolute.parent)
            return open("jdbc:duckdb:$absolute", absolute, options)
        }

        /** An in-memory database with the same schema and settings, for tests. */
        fun openInMemory(options: Options = Options()): DuckDatabase = open("jdbc:duckdb:", null, options)

        private fun open(url: String, file: Path?, options: Options): DuckDatabase {
            val properties = Properties().apply {
                setProperty("enable_external_access", "false")
                setProperty("autoinstall_known_extensions", "false")
                setProperty("autoload_known_extensions", "false")
                setProperty("memory_limit", "${options.memoryLimitMb}MB")
                setProperty("threads", options.threads.toString())
                setProperty(DuckDBDriver.JDBC_STREAM_RESULTS, "true")
                // Query data spilled to disk (`<db>.tmp/`) is bounded: a query never fills the disk the Journal needs (default: 90 % of it).
                setProperty("max_temp_directory_size", MAX_TEMP_DIRECTORY_SIZE)
            }
            // Through the driver itself: DriverManager may not see a driver nested in the mod jar.
            val writer = DuckDBDriver().connect(url, properties) as DuckDBConnection
            val readers = mutableListOf<DuckDBConnection>()
            try {
                writer.execute("SET TimeZone = '${options.timeZone.id}'")
                val version = migrate(writer)
                bundled(VIEWS)?.let { views -> inTransaction(writer, VIEWS) { writer.execute(views) } }
                inTransaction(writer, EventTypesTable.NAME) { writer.execute(EventTypesTable.sql()) }
                writer.execute("SET lock_configuration = true")
                repeat(options.readers) { readers += writer.duplicate() }
                return DuckDatabase(file, writer, readers, version, options.timeZone)
            } catch (e: Exception) {
                (readers + writer).forEach { runCatching { it.close() } }
                throw e
            }
        }

        /** Applies the migrations the database does not have yet; returns its schema version. */
        private fun migrate(connection: DuckDBConnection): Int {
            val current = currentVersion(connection)
            if (current > SCHEMA_VERSION) {
                throw SQLException(
                    "the Journal has schema version $current, but this version of the mod only knows up to " +
                        "$SCHEMA_VERSION: use a newer version of the mod",
                )
            }
            MIGRATIONS.filter { versionOf(it) > current }.forEach { name ->
                val script = bundled(name) ?: error("migration missing from the jar: $name")
                inTransaction(connection, name) {
                    connection.execute(script)
                    connection.prepareStatement("INSERT INTO schema_version VALUES (?, now())").use {
                        it.setInt(1, versionOf(name))
                        it.executeUpdate()
                    }
                }
                log.info("applied migration $name")
            }
            return SCHEMA_VERSION
        }

        private fun currentVersion(connection: DuckDBConnection): Int {
            val exists = connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT count(*) FROM duckdb_tables() WHERE schema_name = 'main' AND table_name = 'schema_version'",
                ).use { it.next() && it.getLong(1) > 0 }
            }
            if (!exists) return 0
            return connection.createStatement().use { statement ->
                statement.executeQuery("SELECT coalesce(max(version), 0) FROM schema_version").use { it.next(); it.getInt(1) }
            }
        }

        /** Runs [block] in one transaction, rolled back if it fails; [what] names the script in the error. */
        private fun inTransaction(connection: DuckDBConnection, what: String, block: () -> Unit) {
            connection.autoCommit = false
            try {
                block()
                connection.commit()
            } catch (e: SQLException) {
                connection.rollback()
                throw SQLException("$what failed: ${e.message}", e)
            } finally {
                connection.autoCommit = true
            }
        }

        /** Version of a migration file, from its `V<n>__` prefix. */
        internal fun versionOf(name: String): Int =
            Regex("""V(\d+)__.+\.sql""").matchEntire(name)?.groupValues?.get(1)?.toInt()
                ?: error("a migration must be named V<version>__<name>.sql: $name")

        /** A script bundled in the jar under [SCRIPTS]; null when absent. */
        private fun bundled(name: String): String? =
            DuckDatabase::class.java.getResourceAsStream("$SCRIPTS$name")?.use { String(it.readAllBytes(), Charsets.UTF_8) }

        /** Runs [sql], which may hold several statements. */
        private fun DuckDBConnection.execute(sql: String) {
            createStatement().use { it.execute(sql) }
        }
    }
}
