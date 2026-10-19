package io.github.clemsix6.narrator2.journal

import io.github.clemsix6.narrator2.testing.LogCapture
import org.apache.logging.log4j.Level
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.sql.SQLException
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A clock and a wait that never sleep: [nowMs] only moves when a test moves it or when [pause] "waits".
 * [during] runs inside each wait, for what the scenario does while the Journal waits (the other program lets go).
 */
internal class FakeWait(private val during: (Long) -> Unit = {}) {
    var nowMs = 0L
    val waits = mutableListOf<Long>()
    val nanoTime: () -> Long = { TimeUnit.MILLISECONDS.toNanos(nowMs) }
    val pause: (Long) -> Unit = { ms ->
        waits += ms
        during(ms)
        nowMs += ms
    }

    fun retry(waitsMs: List<Long> = listOf(2_000, 4_000), budgetMs: Long = 10_000) = OpenRetry(waitsMs, budgetMs, nanoTime, pause)
}

/** The WARN lines about failed attempts to open the Journal: the capture also sees lines of threads that other tests left behind. */
internal fun LogCapture.openingWarnings(): List<String> = at(Level.WARN).filter { "opening the Journal failed" in it }

/** How the Journal's opening at startup is tried again, and which failures it tries again for. */
class OpenRetryTest {
    private fun held() = SQLException(
        "IO Error: Cannot open file \"world/narrator2/narrator.duckdb\": The process cannot access the file because it is being used " +
            "by another process.\n\nFile is already open in\nC:\\Program Files\\Java\\bin\\java.exe (PID 4242)",
    )

    private fun notADatabase() = SQLException("IO Error: The file \"narrator.duckdb\" exists, but it is not a valid DuckDB database file!")

    @Test
    fun `the policy is three attempts at about 0, 2 and 6 seconds, within 10`() {
        val retry = OpenRetry()
        assertEquals(3, retry.attempts)
        assertEquals(listOf(2_000L, 4_000L), retry.waitsMs)
        assertEquals(10_000L, retry.budgetMs)
        assertTrue(retry.waitsMs.sum() <= retry.budgetMs)
    }

    @Test
    fun `an attempt that works is followed by no wait and no log`() {
        val time = FakeWait()
        LogCapture().use { logs ->
            assertEquals("journal", time.retry().run { "journal" })
            assertEquals(emptyList(), time.waits)
            assertTrue(logs.messages.none { "Journal" in it }, "nothing to say: ${logs.messages}")
        }
    }

    @Test
    fun `a failure that can pass is tried again after 2 seconds, then 4, and each failed attempt is logged with its cause`() {
        val time = FakeWait()
        var attempts = 0
        LogCapture().use { logs ->
            val journal = time.retry().run {
                if (++attempts < 3) throw held()
                "journal"
            }
            assertEquals("journal", journal)
            assertEquals(3, attempts)
            assertEquals(listOf(2_000L, 4_000L), time.waits)
            val warnings = logs.openingWarnings()
            assertEquals(2, warnings.size, "one WARN per failed attempt: $warnings")
            assertContains(warnings[0], "opening the Journal failed (attempt 1 of 3), trying again in 2 s")
            assertContains(warnings[1], "opening the Journal failed (attempt 2 of 3), trying again in 4 s")
            warnings.forEach {
                assertContains(it, "being used by another process")
                assertContains(it, "File is already open in C:\\Program Files\\Java\\bin\\java.exe (PID 4242)", message = "the other process is named, on one line")
            }
            assertContains(logs.at(Level.INFO).single { "opened at" in it }, "opened at attempt 3 of 3, 6 s after the first")
        }
    }

    @Test
    fun `a failure that never passes stops after three attempts, within 10 seconds`() {
        val time = FakeWait()
        var attempts = 0
        val last = held()
        LogCapture().use { logs ->
            val failure = assertFailsWith<JournalOpenException> {
                time.retry().run {
                    attempts++
                    throw if (attempts == 3) last else held()
                }
            }
            assertEquals(3, attempts)
            assertEquals(3, failure.attempts)
            assertSame(last, failure.cause, "the cause is the last failure")
            assertContains(failure.message.orEmpty(), "opening failed after 3 attempts in 6 s")
            assertEquals(listOf(2_000L, 4_000L), time.waits)
            assertTrue(time.nowMs <= 10_000, "waited ${time.nowMs} ms")
            val warnings = logs.openingWarnings()
            assertEquals(3, warnings.size, "the last attempt is logged too: $warnings")
            assertContains(warnings[2], "opening the Journal failed (attempt 3 of 3), giving up")
        }
    }

    @Test
    fun `a failure that cannot pass is tried once`() {
        val time = FakeWait()
        var attempts = 0
        LogCapture().use { logs ->
            val failure = assertFailsWith<JournalOpenException> {
                time.retry().run {
                    attempts++
                    throw notADatabase()
                }
            }
            assertEquals(1, attempts)
            assertEquals(1, failure.attempts)
            assertEquals(emptyList(), time.waits)
            assertContains(failure.message.orEmpty(), "not tried again")
            assertContains(failure.message.orEmpty(), "is not a valid DuckDB database file")
            val warning = logs.openingWarnings().single()
            assertContains(warning, "opening the Journal failed (attempt 1 of 3), not tried again")
        }
    }

    @Test
    fun `a slow failure leaves fewer attempts, never a longer wait`() {
        val time = FakeWait()
        var attempts = 0
        LogCapture().use { logs ->
            val failure = assertFailsWith<JournalOpenException> {
                time.retry().run {
                    attempts++
                    time.nowMs += 3_000 // each attempt takes 3 s to fail
                    throw held()
                }
            }
            // 0-3 s, the wait to 5 s, 5-8 s; a third attempt would start at 12 s, past the 10 s allowed.
            assertEquals(2, attempts)
            assertEquals(listOf(2_000L), time.waits)
            assertEquals(8_000L, time.nowMs)
            assertContains(failure.message.orEmpty(), "opening failed after 2 attempts in 8 s, with no time left to try again")
            assertContains(logs.openingWarnings()[1], "would start past the 10 s allowed")
        }
    }

    @Test
    fun `the third attempt may start exactly at the end of the budget`() {
        val time = FakeWait()
        var attempts = 0
        assertFailsWith<JournalOpenException> {
            time.retry().run {
                attempts++
                time.nowMs += 2_000 // 0-2 s, the wait to 4 s, 4-6 s, the wait to 10 s
                throw held()
            }
        }
        assertEquals(3, attempts)
        assertEquals(listOf(2_000L, 4_000L), time.waits)
        assertEquals(12_000L, time.nowMs, "the third attempt started at 10 s and took 2 s: the wait itself never passed the budget")
    }

    @Test
    fun `an interruption while waiting ends the attempts and keeps the interrupt`() {
        var attempts = 0
        val retry = OpenRetry(nanoTime = FakeWait().nanoTime, pause = { throw InterruptedException("test") })
        val failure = try {
            assertFailsWith<JournalOpenException> {
                retry.run {
                    attempts++
                    throw held()
                }
            }
        } finally {
            assertTrue(Thread.interrupted(), "the thread stays interrupted for whoever stops the server (and is cleared here)")
        }
        assertEquals(1, attempts)
        assertContains(failure.message.orEmpty(), "interrupted")
    }

    // ── Which failures can pass ─────────────────────────────────────────────────────────────────────

    @Test
    fun `a file held by another program, or an I-O error, can pass whatever the language of the system`() {
        assertTrue(isTransientOpenFailure(held()))
        assertTrue(
            isTransientOpenFailure(
                SQLException(
                    "IO Error: Cannot open file \"narrator.duckdb\": Le processus ne peut pas accéder au fichier car il est utilisé par " +
                        "un autre processus.\n\nFile is already open in\nC:\\java.exe (PID 7)",
                ),
            ),
        )
        assertTrue(isTransientOpenFailure(SQLException("IO Error: Could not set lock on file \"narrator.duckdb\": Conflicting lock is held in /usr/bin/java (PID 7) by user mc")))
        assertTrue(isTransientOpenFailure(SQLException("IO Error: Cannot open file \"narrator.duckdb\": Access is denied.")))
        assertTrue(isTransientOpenFailure(SQLException("IO Error: Could not write file \"narrator.duckdb\": No space left on device")))
        assertTrue(isTransientOpenFailure(IOException("the disk is busy")))
    }

    @Test
    fun `a failure wrapped by a migration is judged by its root cause`() {
        val io = SQLException("V1__journal.sql failed: IO Error: Could not write file", SQLException("IO Error: Could not write file"))
        assertTrue(isTransientOpenFailure(io))
        val catalog = SQLException(
            "V1__journal.sql failed: Catalog Error: Table with name \"players\" already exists!",
            SQLException("Catalog Error: Table with name \"players\" already exists!"),
        )
        assertFalse(isTransientOpenFailure(catalog))
        assertTrue(isTransientOpenFailure(RuntimeException("opening", IOException("busy"))))
    }

    @Test
    fun `a corrupt or unreadable database, a failed migration and an invalid configuration cannot pass`() {
        assertFalse(isTransientOpenFailure(notADatabase()))
        assertFalse(isTransientOpenFailure(SQLException("IO Error: Corrupt database file: computed checksum 1 does not match stored checksum 2 in block at location 4096")))
        assertFalse(isTransientOpenFailure(SQLException("IO Error: Trying to read a database file with version number 99, but we can only read versions between 64 and 65.")))
        assertFalse(isTransientOpenFailure(SQLException("IO Error: Failure while replaying WAL file \"narrator.duckdb.wal\": Catalog Error")))
        assertFalse(isTransientOpenFailure(SQLException("the Journal has schema version 9, but this version of the mod only knows up to 2: use a newer version of the mod")))
        assertFalse(isTransientOpenFailure(SQLException("Invalid Input Error: The following options were not recognized: memry_limit")))
        assertFalse(isTransientOpenFailure(SQLException("Out of Memory Error: could not allocate block")))
        assertFalse(isTransientOpenFailure(java.time.zone.ZoneRulesException("Unknown time-zone ID: Mars/Olympus")))
        assertFalse(isTransientOpenFailure(IllegalArgumentException("an id block holds 2 ids at least")))
        assertFalse(isTransientOpenFailure(FileAlreadyExistsException("world/narrator2")))
    }
}
