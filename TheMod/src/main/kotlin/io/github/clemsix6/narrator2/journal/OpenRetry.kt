package io.github.clemsix6.narrator2.journal

import io.github.clemsix6.narrator2.core.Log
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.sql.SQLException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * How the Journal is opened at startup when the first attempt fails: three attempts, the second two
 * seconds after the first failed and the third four seconds after the second, so at about 0 s, 2 s and 6 s.
 * Something else may hold the database file for an instant (a backup, an antivirus scan, a previous
 * process still closing), and a Journal that stays disabled until the next restart is expensive.
 *
 * Only a failure that can pass is tried again ([isTransientOpenFailure]). Waiting happens on the server
 * thread, before the first tick: it is the bounded startup wait that ARCHITECTURE §17.8 allows, and
 * [budgetMs] bounds it: an attempt starts only if it can start within [budgetMs] of the first one, so slow
 * failures leave fewer attempts, never a longer wait. An attempt already running is not interrupted (it
 * is a native call), but an open that fails returns at once.
 *
 * [nanoTime] and [pause] are the clock and the wait: tests replace them to order events without sleeping.
 */
internal class OpenRetry(
    /** The wait before each new attempt: there is one more attempt than there are waits. */
    val waitsMs: List<Long> = listOf(2_000, 4_000),
    /** No attempt starts later than this after the first one began. */
    val budgetMs: Long = 10_000,
    private val nanoTime: () -> Long = System::nanoTime,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
) {
    val attempts: Int get() = waitsMs.size + 1

    /**
     * Runs [open] until it returns, or fails for good. Each failed attempt is logged at WARN with its
     * cause. Throws a [JournalOpenException], whose cause is the last failure, once no attempt is left.
     */
    fun <T> run(open: () -> T): T {
        val start = nanoTime()
        var attempt = 1
        while (true) {
            try {
                val opened = open()
                if (attempt > 1) log.info("opened at attempt $attempt of $attempts, ${seconds(elapsedMs(start))} after the first")
                return opened
            } catch (e: Exception) {
                val failure = "opening the Journal failed (attempt $attempt of $attempts)"
                val why = oneLine(e)
                val elapsed = elapsedMs(start)
                val wait = waitsMs.getOrNull(attempt - 1)
                if (!isTransientOpenFailure(e)) {
                    log.warn("$failure, not tried again as it would fail the same way: $why")
                    throw JournalOpenException("opening failed, and is not tried again as it would fail the same way: $why", attempt, e)
                }
                if (wait == null) {
                    log.warn("$failure, giving up: $why")
                    throw JournalOpenException("opening failed after $attempt attempts in ${seconds(elapsed)}: $why", attempt, e)
                }
                if (elapsed + wait > budgetMs) {
                    log.warn("$failure, not tried again as the next attempt would start past the ${seconds(budgetMs)} allowed: $why")
                    throw JournalOpenException("opening failed after $attempt attempts in ${seconds(elapsed)}, with no time left to try again: $why", attempt, e)
                }
                log.warn("$failure, trying again in ${seconds(wait)}: $why")
                try {
                    pause(wait)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    log.warn("interrupted while waiting to open the Journal again, giving up")
                    throw JournalOpenException("opening failed after $attempt attempts, then was interrupted: $why", attempt, e)
                }
                attempt++
            }
        }
    }

    private fun elapsedMs(start: Long): Long = TimeUnit.NANOSECONDS.toMillis(nanoTime() - start)

    private companion object {
        val log = Log.journal

        fun seconds(ms: Long): String = if (ms % 1_000 == 0L) "${ms / 1_000} s" else String.format(Locale.ROOT, "%.1f s", ms / 1_000.0)

        /** The error on one line: DuckDB's lock message names the other process on lines of its own, which is worth keeping. */
        fun oneLine(error: Exception): String = (error.message ?: error.javaClass.simpleName).trim().replace(Regex("\\s+"), " ")
    }
}

/**
 * The Journal did not open, after [attempts] attempts; [cause] is the failure of the last one. The runtime
 * logs it and runs with a [DisabledJournal] whose reason is [message].
 */
class JournalOpenException(message: String, val attempts: Int, cause: Exception) : Exception(message, cause)

/**
 * Whether opening the Journal may succeed if tried again.
 *
 * DuckDB reports every failure as a plain [SQLException], with no subclass, SQL state or error code, whose
 * message begins with the name of its own error category. `IO Error:` covers a file held by another
 * process, which passes, and a file that is not a database, which does not. So the category is not enough:
 * what DuckDB itself says of a file that will never open is told apart (these messages were taken from the
 * DuckDB of this build, see [PERMANENT_IO_ERRORS]). The words of the operating system are not used, as
 * they are translated.
 *
 * A failure can pass when its root cause is:
 * - a [SQLException] of DuckDB's `IO Error:` category, unless it says that the file is not a database, is
 *   corrupt, is of a storage version this DuckDB cannot read, or has a WAL that cannot be replayed. A file
 *   held by another process, a sharing violation, an "access denied" caused by an antivirus scan or a file
 *   pending deletion, a full disk: all are tried again, as they may clear;
 * - an [IOException], except [FileAlreadyExistsException] (the folder is a file).
 *
 * Everything else is permanent, and tried only once: a migration that fails (its root cause is a catalog
 * or binder error), a database written by a newer version of the mod, a setting DuckDB refuses (bad
 * configuration), an out-of-memory error. The price of a wrong guess is small either way: two more
 * attempts for an error that stays, against a Journal lost until the next restart for one that would have passed.
 */
internal fun isTransientOpenFailure(error: Throwable): Boolean {
    var root = error
    var depth = 0
    while (root.cause != null && depth++ < MAX_CAUSE_DEPTH) root = root.cause!!
    return when (root) {
        is FileAlreadyExistsException -> false
        is IOException -> true
        is SQLException -> root.message.orEmpty().let { message ->
            message.startsWith("IO Error:") && PERMANENT_IO_ERRORS.none { it in message }
        }
        else -> false
    }
}

private const val MAX_CAUSE_DEPTH = 10

/** What DuckDB says in an `IO Error:` that opening again will not change. */
private val PERMANENT_IO_ERRORS = listOf(
    "is not a valid DuckDB database file",
    "Corrupt database file",
    "Trying to read a database file with version number",
    "Failure while replaying WAL file",
)
