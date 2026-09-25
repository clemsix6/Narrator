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
 * milliseconds, at startup only. Throws when the database cannot be opened; the runtime then runs with
 * a [DisabledJournal] and logs why.
 *
 * The `journal` settings are read here, once: `/narrator reload` does not change them.
 */
fun openJournal(dir: Path, config: JournalConfig, clock: Clock): Journal {
    val log = Log.journal
    val start = System.nanoTime()
    Files.createDirectories(dir)
    val file = dir.resolve(DuckDatabase.FILE_NAME)
    val db = DuckDatabase.open(file, DuckDatabase.Options.of(config))
    val journal = try {
        DuckJournal.open(db, config, clock, hookNanos = { ObserverState.current()?.hookNanos })
    } catch (e: Exception) {
        db.close()
        throw e
    }
    val bytes = db.fileBytes() + db.walBytes()
    log.info(
        "opened $file · schema version ${db.schemaVersion} · %.1f MB · time zone ${db.timeZone} · %d ms"
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
