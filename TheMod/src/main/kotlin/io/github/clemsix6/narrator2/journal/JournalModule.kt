package io.github.clemsix6.narrator2.journal

import io.github.clemsix6.narrator2.config.JournalConfig
import java.nio.file.Path
import java.time.Clock

/**
 * Opens the Journal of a world, in [dir] (`world/narrator2/`). Called once when the server starts, on
 * the server thread: opening DuckDB takes a few hundred milliseconds, at startup only. Throws when the
 * database cannot be opened; the runtime then runs with a [DisabledJournal] and logs why.
 *
 * Provisional body (T02): the Journal is not written yet, so facts and traces are not recorded. T03
 * replaces it with the DuckDB Journal.
 */
@Suppress("UNUSED_PARAMETER")
fun openJournal(dir: Path, config: JournalConfig, clock: Clock): Journal =
    DisabledJournal("the Journal is not written yet", clock)
