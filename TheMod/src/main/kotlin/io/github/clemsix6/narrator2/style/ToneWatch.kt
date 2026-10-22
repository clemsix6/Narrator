package io.github.clemsix6.narrator2.style

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.director.StyleNoteKind
import io.github.clemsix6.narrator2.memory.stats.Tics
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Clock
import java.time.Instant
import java.util.EnumMap

/**
 * The tone's monitor (M6 §4.3): from the labels of the line review, and from the length of each line ([said]), it tells
 * the Director a drift of tone as a note of the engine ([DirectorSignal.StyleNote], urgency `NEXT`: it never wakes), which
 * the Director renders as `Engine: tone check: …` with its next request. The prompt already says what to do with it
 * (`director.md`, since M2).
 *
 * | Drift | When |
 * |---|---|
 * | dark | a line `dark` ≥ [DARK] |
 * | cruel | a line `cruel` ≥ [CRUEL] |
 * | out of character | a line `out_of_character` ≥ [OUT_OF_CHARACTER] |
 * | obscure | a line `obscure` ≥ [OBSCURE] (M7-CLARITY); or, measured when it is said, labeled or not, a line holding a word of [JARGON] |
 * | flat | the line, and [RUN] of the latest [WINDOW] labeled lines in all, `flat` ≥ [FLAT] |
 * | repeats | the line, and [RUN] of the latest [WINDOW] labeled lines in all, `repeats` ≥ [REPEATS] |
 * | long | a line of more than three sentences, or of two paragraphs or more ([LineLength], M6-18 finding 2): measured when it is said, labeled or not |
 *
 * One note a line at most, the gravest ([StyleNoteKind] is in order of gravity); the same drift at most every
 * [CADENCE_MS]. The Director tells one note a request, the gravest, and drops them at a silence. `style.toneNotes =
 * false` ([enabled]) stops them. Each note is a decision `style tone_noted` with the labels, or the length. Never a
 * refusal: the line is said, and the note shapes the next ones. Any thread.
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
        tell(drifts(labels, latest.take(WINDOW)), labels.eventId) {
            put("line", labels.text)
            put("register", labels.register.id)
            put("dark", labels.dark)
            put("cruel", labels.cruel)
            put("flat", labels.flat)
            put("out_of_character", labels.outOfCharacter)
            put("obscure", labels.obscure)
            put("repeats", labels.repeats)
        }
    }

    /**
     * The Director said [text], a line the review reads (its `chat` row [eventId]): measured here without a call, whether
     * the review labels it or not, a line holding invented paperwork ([jargon]) is obscure, and one longer than a message
     * allows ([LineLength.long]) is long.
     */
    fun said(eventId: Long, text: String) {
        if (!enabled()) return
        val words = jargon(text)
        val length = LineLength.of(text)
        val drifts = listOfNotNull(
            words.takeIf { it.isNotEmpty() }?.let { StyleNoteKind.OBSCURE to jargonNote(text, it) },
            (StyleNoteKind.LONG to long(text, length)).takeIf { length.long },
        )
        if (drifts.isEmpty()) return
        tell(drifts, eventId) {
            put("line", text)
            if (words.isNotEmpty()) put("jargon", words.joinToString(", "))
            put("sentences", length.sentences)
            put("paragraphs", length.paragraphs)
        }
    }

    /** Tells the first of [drifts] (the gravest first) not told within [CADENCE_MS]: a note to the Director and a decision. */
    private fun tell(drifts: List<Pair<StyleNoteKind, String>>, eventId: Long, detail: JsonObjectBuilder.() -> Unit) {
        val now = clock.millis()
        val (kind, text) = synchronized(noted) {
            drifts.firstOrNull { (kind, _) -> noted[kind]?.let { now - it >= CADENCE_MS } ?: true }?.also { noted[it.first] = now }
        } ?: return
        post(DirectorSignal.StyleNote(kind, text, now))
        decisions.record(
            log, DecisionRecord.Kind.STYLE, "tone_noted", subject = kind.name.lowercase(), reason = text, eventId = eventId,
            detail = buildJsonObject(detail),
        )
    }

    companion object {
        const val DARK = 0.7
        const val CRUEL = 0.7
        const val OUT_OF_CHARACTER = 0.8
        /**
         * Under the 0.8 that `line_review.md` asks for jargon: Haiku gives the owner's two obscure lines 0.6 to 0.7, and the
         * good lines of the evaluation 0.35 at most (M7-CLARITY).
         */
        const val OBSCURE = 0.6
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
            if (labels.obscure >= OBSCURE) add(StyleNoteKind.OBSCURE to "$line is hard to follow at the first read; $PLAINLY")
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

        /**
         * The paperwork the owner's players could not follow (« crayon retiré du dossier », « formulaire 12-T »,
         * M7-CLARITY): a word starting with one of these is invented jargon in a line of the Narrator. Search data, in
         * French like [Tics]'s stop words.
         */
        val JARGON: List<String> = listOf("dossier", "formulaire", "crayon", "tampon")

        /** What an obscure line should do instead, the rule of `director.md` (« Clair d'abord »). */
        private const val PLAINLY = "say plainly what happened, what you think and what comes next; no jargon, no metaphor carried over"

        /** The words of [text] that start with a word of [JARGON], in lower case, in order; empty for a line without any. */
        fun jargon(text: String): List<String> =
            Tics.words(Tics.said(text, Instant.EPOCH, Tics.Names.NONE)).filter { word -> JARGON.any { word.startsWith(it) } }

        /** The note of a [text] that holds the jargon [words]: `your line « … » uses invented paperwork (« dossier »); …`. */
        fun jargonNote(text: String, words: List<String>): String =
            "your line ${ModelText.guillemets(text, QUOTE_MAX)} uses invented paperwork (${words.joinToString(", ") { ModelText.guillemets(it) }}) " +
                "that players do not follow; $PLAINLY"

        /**
         * The note of a [text] too long ([length]): `your line « … » has 5 sentences in 2 paragraphs: …`, with the rule of
         * `director.md` ([LineLength.MAX_SENTENCES]).
         */
        fun long(text: String, length: LineLength): String =
            "your line ${ModelText.guillemets(text, QUOTE_MAX)} has ${length.describe()}: a message is one paragraph of one to " +
                "three sentences, often one; keep the punchline"
    }
}
