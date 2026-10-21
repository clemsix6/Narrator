package io.github.clemsix6.narrator2.journal

import io.github.clemsix6.narrator2.config.JournalConfig
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.observer.ObserverState
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.Locale

/**
 * Opens the Journal of a world, in [dir] (`world/narrator2/`, created if missing): the DuckDB file
 * `narrator.duckdb`, its migrations, the repair of what a crash left open, and the writer thread.
 * Called once when the server starts, on the server thread: opening DuckDB takes a few hundred
 * milliseconds, at startup only.
 *
 * Never two Journals on one file: a file an earlier Journal of this process still holds (its writer outlived its
 * close, at an earlier stop of the server or a restart) is refused before DuckDB opens it ([DuckDatabase.open]), at the
 * server's start as at a restart.
 *
 * A failure that can pass (the file held for an instant by another program or by that earlier Journal, an I/O error) is tried
 * again: three attempts in at most 10 seconds, at about 0 s, 2 s and 6 s ([OpenRetry]). A failure that
 * would repeat (corrupt file, failing migration, invalid configuration) is not. Throws a
 * [JournalOpenException] when it does not open; the runtime then runs with a [DisabledJournal] and
 * logs why ([openJournalOrDisable]).
 *
 * The `journal` settings are read here, once: `/narrator reload` does not change them.
 */
fun openJournal(dir: Path, config: JournalConfig, clock: Clock): Journal = openJournal(dir, config, clock, OpenRetry())

/**
 * [hookNanos]: unit tests cannot start the runtime that [observerHookNanos] reads, so they pass their own. [dashboard]:
 * the web dashboard is on, and gets a read connection of its own ([DuckDatabase.Options.of]).
 */
internal fun openJournal(
    dir: Path,
    config: JournalConfig,
    clock: Clock,
    retry: OpenRetry,
    hookNanos: () -> Long? = ::observerHookNanos,
    dashboard: Boolean = false,
): Journal = retry.run { openJournalOnce(dir, config, clock, hookNanos, dashboard) }

/**
 * [openJournal], or, when it fails for good, a [DisabledJournal] whose reason says why: facts and traces
 * are then not recorded until the next restart. The failure is logged at ERROR with its stack trace, and
 * the unavailable Journal at WARN. `/narrator status` and the commands that need the Journal show it as
 * unavailable. No operator is online yet at startup, so none is told.
 */
fun openJournalOrDisable(dir: Path, config: JournalConfig, clock: Clock, dashboard: Boolean = false): Journal =
    openJournalOrDisable(dir, config, clock, OpenRetry(), dashboard = dashboard)

internal fun openJournalOrDisable(
    dir: Path,
    config: JournalConfig,
    clock: Clock,
    retry: OpenRetry,
    hookNanos: () -> Long? = ::observerHookNanos,
    dashboard: Boolean = false,
): Journal {
    val log = Log.journal
    val journal = try {
        openJournal(dir, config, clock, retry, hookNanos, dashboard)
    } catch (e: Exception) {
        log.error("the Journal cannot open, facts will not be recorded: ${e.message}", e)
        DisabledJournal(e.message ?: e.javaClass.simpleName, clock)
    }
    if (!journal.available) log.warn("the Journal is unavailable: ${journal.health().lastError}")
    return journal
}

/** One attempt to open the Journal; throws what failed, closing what it opened. */
private fun openJournalOnce(dir: Path, config: JournalConfig, clock: Clock, hookNanos: () -> Long?, dashboard: Boolean): Journal {
    val log = Log.journal
    val start = System.nanoTime()
    Files.createDirectories(dir)
    val file = dir.resolve(DuckDatabase.FILE_NAME)
    val db = DuckDatabase.open(file, DuckDatabase.Options.of(config, dashboard))
    val journal = try {
        DuckJournal.open(db, config, clock, hookNanos = hookNanos)
    } catch (e: Exception) {
        db.close()
        throw e
    }
    val bytes = db.fileBytes() + db.walBytes()
    log.info(
        "opened $file · schema version ${db.schemaVersion} · %.1f MB · time zone ${db.timeZone} · ${db.readers.size} read connections · %d ms"
            .format(Locale.ROOT, bytes / 1e6, (System.nanoTime() - start) / 1_000_000),
    )
    if (bytes > config.warnSizeMb * 1_000_000) {
        log.warn(
            "the Journal is %.0f MB, over journal.warnSizeMb (${config.warnSizeMb}): nothing is ever deleted from it"
                .format(Locale.ROOT, bytes / 1e6),
        )
    }
    return journal
}

/** The time the Observer's hooks have taken, for the Journal's health report; null before the Observer exists. */
private fun observerHookNanos(): Long? = ObserverState.current()?.hookNanos
