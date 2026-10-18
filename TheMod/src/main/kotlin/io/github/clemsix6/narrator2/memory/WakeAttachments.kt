package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.tool.shownName

/**
 * The `<memory>` block of a Director's wake-up, or of the events injected between two tool calls (M3
 * §11.2, §11.3): what `Memory.attachments` returns, placed by the Director after `</events>` or
 * `</new_events>`.
 *
 * ```
 * <memory>
 * SHEET Bartok (joined; away 2d3h, last session 10-01 20:10–21:52):
 *   …
 * RECALLED for DEATH Mimosa: M-120 · 09-16 20:00 (3w ago) · Mimosa · ★7 · Mort dans la lave: « … »
 * </memory>
 * ```
 *
 * First the sheets of the players who join ([JoinBrief]), then the reminiscences of the events
 * ([Reminiscence.forWake]); null when there is neither. Both keep the [ChapterMemory] up to date (refs and
 * sheets shown, so nothing is attached twice in a chapter) and leave what they show in
 * [ChapterMemory.unrecorded], recorded once the model accepts the request. Runs on the Director's
 * coroutine, which owns the chapter; never throws for the memory's sake (each source says or logs its own
 * failure), only a cancellation goes through.
 */
class WakeAttachments(private val joins: JoinBrief, private val reminiscence: Reminiscence) {
    private val log = Log.memory

    /** The `<memory>` block for [signals] at [now] (epoch milliseconds), or null when there is nothing to attach. */
    suspend fun block(signals: List<DirectorSignal>, chapter: ChapterMemory, now: Long): String? {
        val started = System.nanoTime()
        val sheets = joins.lines(signals, chapter, now)
        val recalled = reminiscence.forWake(signals, chapter, now).map { it.line }
        if (sheets.isEmpty() && recalled.isEmpty()) return null
        val block = (listOf("<memory>") + sheets + recalled + "</memory>").joinToString("\n")
        log.debug {
            "attachments ${chapter.conversationId}: ${sheets.count { it.startsWith("SHEET ") }} sheet(s), ${recalled.size} reminiscence(s), " +
                "${block.length} characters in ${(System.nanoTime() - started) / 1_000_000} ms"
        }
        return block
    }

    companion object {
        /**
         * The `<memory>` block when the memory did not answer in time (the Director gave up and put [chapter]
         * back as it was): one line for each player among [signals] whose sheet it would have attached, so the
         * Narrator knows that it exists and how to read it; null when there is none.
         *
         * ```
         * <memory>
         * (the sheet of Bartok could not be read in time: player_profile Bartok)
         * </memory>
         * ```
         */
        fun late(signals: List<DirectorSignal>, chapter: ChapterMemory, now: Long): String? {
            val players = JoinBrief.sheetsDue(signals, chapter, now)
            if (players.isEmpty()) return null
            val lines = players.map { "(the sheet of ${it.shownName} could not be read in time: player_profile ${it.shownName})" }
            return (listOf("<memory>") + lines + "</memory>").joinToString("\n")
        }
    }
}
