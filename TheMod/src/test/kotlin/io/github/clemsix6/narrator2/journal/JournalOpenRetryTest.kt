package io.github.clemsix6.narrator2.journal

import io.github.clemsix6.narrator2.config.JournalConfig
import io.github.clemsix6.narrator2.testing.LogCapture
import io.github.clemsix6.narrator2.testing.TestClock
import kotlinx.coroutines.runBlocking
import org.apache.logging.log4j.Level
import org.duckdb.DuckDBDriver
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The Journal opens again at startup when its file is held for a moment (the real DuckDB lock, held by
 * a real other program: a JVM child), and gives up at once on what cannot pass (real corrupt files).
 * The waits are the policy's own, but they do not sleep: the other program lets go in the middle of a
 * wait, so which attempt opens the Journal does not depend on the speed of the machine.
 */
class JournalOpenRetryTest {
    @TempDir
    lateinit var dir: Path

    private val file: Path get() = dir.resolve(DuckDatabase.FILE_NAME)
    private val config = JournalConfig(memoryLimitMb = 256, timeZone = "UTC")
    private val clock = TestClock()

    /** The Observer's hook timer needs a running server, which a unit test does not have. */
    private val noHook: () -> Long? = { null }

    /** A valid database file for the other program to hold (it opens it read-only, as the DuckDB command line would). */
    private fun createDatabase() = DuckDatabase.open(file, JournalFixture.OPTIONS).close()

    /** Waits, without a fixed delay, until the operating system lets this process open [file]: the other program is gone. */
    private fun awaitFree() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (true) {
            try {
                DuckDBDriver().connect("jdbc:duckdb:$file", Properties()).close()
                return
            } catch (e: SQLException) {
                check(System.nanoTime() < deadline) { "the file is still held after 30 s: ${e.message}" }
                Thread.sleep(5) // the process that held it is still ending
            }
        }
    }

    @Test
    fun `a file held by another program is opened at the second attempt once it is let go`() {
        createDatabase()
        LogCapture().use { logs ->
            JournalChild.start("hold", file).use { other ->
                other.awaitLine(JournalChild.READY)
                // First attempt at 0 s: the other program holds the file. It lets go during the 2 s wait (at 1.5 s, say).
                val time = FakeWait { other.kill(); awaitFree() }
                val journal = openJournal(dir, config, clock, time.retry(), noHook)
                journal.use {
                    assertIs<DuckJournal>(journal)
                    assertTrue(journal.available)
                    assertEquals(listOf(2_000L), time.waits, "one wait, so the second attempt opened it")
                    val warning = logs.openingWarnings().single()
                    assertContains(warning, "opening the Journal failed (attempt 1 of 3), trying again in 2 s: IO Error")
                    assertContains(logs.at(Level.INFO).single { "opened at attempt" in it }, "opened at attempt 2 of 3")
                    assertEquals(
                        listOf(DuckDatabase.MIGRATIONS.size),
                        runBlocking { journal.read(10_000) { c -> c.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM schema_version").use { it.next(); listOf(it.getInt(1)) } } } },
                        "the migrations are applied: the Journal is the real one",
                    )
                }
            }
        }
    }

    @Test
    fun `a file held for good is tried three times, then the Journal is disabled, within 10 seconds`() {
        createDatabase()
        LogCapture().use { logs ->
            JournalChild.start("hold", file).use { other ->
                other.awaitLine(JournalChild.READY)
                val time = FakeWait()
                val journal = openJournalOrDisable(dir, config, clock, time.retry(), noHook)
                assertIs<DisabledJournal>(journal)
                assertFalse(journal.available)
                assertEquals(listOf(2_000L, 4_000L), time.waits, "three attempts: the waits come after the first two")
                assertTrue(time.nowMs <= 10_000, "${time.nowMs} ms")
                val warnings = logs.openingWarnings()
                assertEquals(3, warnings.size, "each failed attempt, the last one too: $warnings")
                assertContains(warnings[0], "(attempt 1 of 3), trying again in 2 s")
                assertContains(warnings[1], "(attempt 2 of 3), trying again in 4 s")
                assertContains(warnings[2], "(attempt 3 of 3), giving up")
                warnings.forEach { assertContains(it, "IO Error") }
                val error = logs.at(Level.ERROR).single { "the Journal cannot open" in it }
                assertContains(error, "the Journal cannot open, facts will not be recorded: opening failed after 3 attempts in 6 s: IO Error")
                assertContains(logs.at(Level.WARN).single { "the Journal is unavailable" in it }, "opening failed after 3 attempts")
                assertContains(journal.health().lastError.orEmpty(), "opening failed after 3 attempts")
            }
        }
    }

    @Test
    fun `a file that is not a database is tried once`() {
        Files.writeString(file, "this is not a database ".repeat(20_000))
        assertTriedOnce("is not a valid DuckDB database file")
    }

    @Test
    fun `a corrupt database is tried once`() {
        createDatabase()
        val bytes = Files.readAllBytes(file)
        bytes[4_096 + 20] = (bytes[4_096 + 20].toInt() xor 0x55).toByte() // the second header block, whose checksum no longer matches
        Files.write(file, bytes)
        assertTriedOnce("Corrupt database file")
    }

    @Test
    fun `a migration that fails is tried once`() {
        // A table the first migration creates, in a database that has no schema_version: "Table already exists".
        DuckDBDriver().connect("jdbc:duckdb:$file", Properties()).use { c -> c.createStatement().use { it.execute("CREATE TABLE players (x INTEGER)") } }
        assertTriedOnce("V1__journal.sql failed: Catalog Error")
    }

    @Test
    fun `a database written by a newer version of the mod is tried once`() {
        DuckDatabase.open(file, JournalFixture.OPTIONS).use { db ->
            db.writer.createStatement().use { it.execute("INSERT INTO schema_version VALUES (999, now())") }
        }
        assertTriedOnce("the Journal has schema version 999")
    }

    /** One attempt, no wait, one WARN, a [DisabledJournal] and the ERROR: what a failure that cannot pass does. */
    private fun assertTriedOnce(cause: String) {
        LogCapture().use { logs ->
            val time = FakeWait()
            val journal = openJournalOrDisable(dir, config, clock, time.retry(), noHook)
            assertIs<DisabledJournal>(journal)
            assertEquals(emptyList(), time.waits)
            val warning = logs.openingWarnings().single()
            assertContains(warning, "opening the Journal failed (attempt 1 of 3), not tried again as it would fail the same way")
            assertContains(warning, cause)
            assertContains(logs.at(Level.ERROR).single { "the Journal cannot open" in it }, "not tried again")
            assertContains(journal.health().lastError.orEmpty(), cause)
        }
    }
}
