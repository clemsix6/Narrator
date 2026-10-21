package io.github.clemsix6.narrator2.style

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.director.StyleNoteKind
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Clock
import java.util.EnumMap

/**
 * The tone's monitor (M6 §4.3): from the labels of the line review, it tells the Director a drift of tone as a note of
 * the engine ([DirectorSignal.StyleNote], urgency `NEXT`: it never wakes), which the Director renders as
 * `Engine: tone check: …` with its next request. The prompt already says what to do with it (`director.md`, since M2).
 *
 * | Drift | When |
 * |---|---|
 * | dark | a line `dark` ≥ [DARK] |
 * | cruel | a line `cruel` ≥ [CRUEL] |
 * | out of character | a line `out_of_character` ≥ [OUT_OF_CHARACTER] |
 * | flat | the line, and [RUN] of the latest [WINDOW] labeled lines in all, `flat` ≥ [FLAT] |
 * | repeats | the line, and [RUN] of the latest [WINDOW] labeled lines in all, `repeats` ≥ [REPEATS] |
 *
 * One note a labeled line at most, the gravest ([StyleNoteKind] is in order of gravity); the same drift at most every
 * [CADENCE_MS]. The Director tells one note a request, the gravest, and drops them at a silence. `style.toneNotes =
 * false` ([enabled]) stops them. Each note is a decision `style tone_noted` with the labels. Any thread.
 *
 * @param post where a note goes: the Director's inbox (read when a note is posted: the Director is created after the
 *   watch).
 */
class ToneWatch(
    private val clock: Clock,
    private val enabled: () -> Boolean,
    private val post: (DirectorSignal.StyleNote) -> Unit,
    private val decisions: Decisions,
) {
    private val log = Log.style

    /** When each drift was last told (epoch milliseconds). */
    private val noted = EnumMap<StyleNoteKind, Long>(StyleNoteKind::class.java) // guarded by itself

    /**
     * The review labeled a line: [labels]; [latest] are the latest labeled lines, the latest first ([WINDOW] are read),
     * [labels] among them when the line is recent.
     */
    fun labeled(labels: LineLabels, latest: List<LineLabels>) {
        if (!enabled()) return
        val now = clock.millis()
        val (kind, text) = synchronized(noted) {
            drifts(labels, latest.take(WINDOW))
                .firstOrNull { (kind, _) -> noted[kind]?.let { now - it >= CADENCE_MS } ?: true }
                ?.also { noted[it.first] = now }
        } ?: return
        post(DirectorSignal.StyleNote(kind, text, now))
        decisions.record(
            log, DecisionRecord.Kind.STYLE, "tone_noted", subject = kind.name.lowercase(), reason = text, eventId = labels.eventId,
            detail = buildJsonObject {
                put("line", labels.text)
                put("register", labels.register.id)
                put("dark", labels.dark)
                put("cruel", labels.cruel)
                put("flat", labels.flat)
                put("out_of_character", labels.outOfCharacter)
                put("repeats", labels.repeats)
            },
        )
    }

    companion object {
        const val DARK = 0.7
        const val CRUEL = 0.7
        const val OUT_OF_CHARACTER = 0.8
        const val FLAT = 0.6
        const val REPEATS = 0.6

        /** The latest labeled lines a flat or repetitive run is counted in. */
        const val WINDOW = 8

        /** Lines of [WINDOW] that make a flat or repetitive run. */
        const val RUN = 3

        /** The same drift is told at most this often. */
        const val CADENCE_MS = 30 * 60_000L

        /** Longest quote of a line in a note. */
        private const val QUOTE_MAX = 160

        /**
         * The drifts [labels] show, the gravest first, each with the text of its note (what follows `tone check: `);
         * [window] is the latest labeled lines, the latest first.
         */
        fun drifts(labels: LineLabels, window: List<LineLabels>): List<Pair<StyleNoteKind, String>> = buildList {
            val line = "your line ${ModelText.guillemets(labels.text, QUOTE_MAX)}"
            if (labels.dark >= DARK) add(StyleNoteKind.DARK to "$line reads dark; find its absurd version, or drop the idea")
            if (labels.cruel >= CRUEL) add(StyleNoteKind.CRUEL to "$line mocks the person, not the play; tease the choice made in the game")
            if (labels.outOfCharacter >= OUT_OF_CHARACTER) add(StyleNoteKind.OUT_OF_CHARACTER to "$line steps out of character")
            val recent = window.any { it.eventId == labels.eventId }
            val flat = window.count { it.flat >= FLAT }
            if (recent && labels.flat >= FLAT && flat >= RUN) {
                add(StyleNoteKind.FLAT to "$flat of your last ${window.size} lines are reports without a punchline")
            }
            val repeated = window.count { it.repeats >= REPEATS }
            if (recent && labels.repeats >= REPEATS && repeated >= RUN) {
                add(StyleNoteKind.REPEATS to "$repeated of your last ${window.size} lines reuse a recent construction")
            }
        }
    }
}
