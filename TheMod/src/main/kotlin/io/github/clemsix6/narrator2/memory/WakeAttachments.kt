package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.director.DirectorSignal

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
 * sheets shown, so nothing is attached twice in a chapter) and record what they show. Runs on the
 * Director's coroutine, which owns the chapter; never throws for the memory's sake (each source says or
 * logs its own failure), only a cancellation goes through.
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
}
