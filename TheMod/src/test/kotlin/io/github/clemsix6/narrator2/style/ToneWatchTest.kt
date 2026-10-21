package io.github.clemsix6.narrator2.style

import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.director.StyleNoteKind
import io.github.clemsix6.narrator2.style.StyleSamples.labels
import io.github.clemsix6.narrator2.testing.MemoryTraceSink
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The tone's monitor (M6 §4.3): each note, its threshold, the gravest only, its cadence, its switch, its decision. */
class ToneWatchTest {
    private val clock = TestClock()
    private val trace = MemoryTraceSink()
    private val notes = mutableListOf<DirectorSignal.StyleNote>()
    private var enabled = true
    private val tone = ToneWatch(clock, { enabled }, { notes += it }, Decisions(trace, clock))

    /** The review labels [line] alone: no run of earlier lines. */
    private fun alone(line: LineLabels) = tone.labeled(line, listOf(line))

    @Test
    fun `a dark, cruel or out of character line is told at its threshold, quoting it`() {
        alone(labels(1, dark = 0.69, cruel = 0.69, outOfCharacter = 0.79))
        assertEquals(emptyList(), notes, "under every threshold")

        alone(labels(2, dark = 0.7, text = "Tu vas mourir seul dans le noir."))
        alone(labels(3, cruel = 0.7, text = "Même ta mère joue mieux que toi."))
        alone(labels(4, outOfCharacter = 0.8, text = "En tant qu'IA, je ne peux pas."))
        assertEquals(
            listOf(
                StyleNoteKind.DARK to "your line « Tu vas mourir seul dans le noir. » reads dark; find its absurd version, or drop the idea",
                StyleNoteKind.CRUEL to "your line « Même ta mère joue mieux que toi. » mocks the person, not the play; tease the choice made in the game",
                StyleNoteKind.OUT_OF_CHARACTER to "your line « En tant qu'IA, je ne peux pas. » steps out of character",
            ),
            notes.map { it.kind to it.text },
        )
        assertTrue(notes.all { it.at == clock.millis() })
    }

    @Test
    fun `a flat or repetitive run is three of the last eight, the line among them`() {
        val window = mutableListOf<LineLabels>()
        fun review(line: LineLabels) {
            window.add(0, line)
            tone.labeled(line, window.toList())
        }
        review(labels(1, flat = 0.6, repeats = 0.6))
        review(labels(2, flat = 0.9, repeats = 0.7))
        repeat(5) { review(labels(3L + it)) }
        assertEquals(emptyList(), notes, "two of seven")
        review(labels(8, flat = 0.6))
        assertEquals(listOf(StyleNoteKind.FLAT to "3 of your last 8 lines are reports without a punchline"), notes.map { it.kind to it.text })
        notes.clear()
        review(labels(9, repeats = 0.8))
        review(labels(10, repeats = 0.6))
        assertEquals(emptyList(), notes, "the first lines left the last eight: two repetitions in them")
        review(labels(11, repeats = 0.6))
        assertEquals(listOf(StyleNoteKind.REPEATS to "3 of your last 8 lines reuse a recent construction"), notes.map { it.kind to it.text })

        notes.clear()
        tone.labeled(labels(13), listOf(labels(13), labels(12, flat = 0.9), labels(11, flat = 0.9), labels(10, flat = 0.9)))
        assertEquals(emptyList(), notes, "a line that is not flat itself tells no flat run")
        val old = labels(2, flat = 0.9, repeats = 0.9)
        tone.labeled(old, listOf(labels(14, flat = 0.9), labels(13, flat = 0.9), labels(12, flat = 0.9)))
        assertEquals(emptyList(), notes, "an old line's late review is not among the latest")
    }

    @Test
    fun `one note a line, the gravest, and the same drift at most every thirty minutes`() {
        alone(labels(1, dark = 0.9, cruel = 0.9, outOfCharacter = 0.9))
        assertEquals(listOf(StyleNoteKind.DARK), notes.map { it.kind })
        alone(labels(2, dark = 0.9, cruel = 0.9))
        assertEquals(listOf(StyleNoteKind.DARK, StyleNoteKind.CRUEL), notes.map { it.kind }, "dark waits: the next gravest is told")
        alone(labels(3, dark = 0.9, cruel = 0.9))
        assertEquals(2, notes.size, "both told within thirty minutes")
        clock.advance(Duration.ofMinutes(30))
        alone(labels(4, dark = 0.9))
        assertEquals(StyleNoteKind.DARK, notes.last().kind, "thirty minutes later, dark again")
    }

    @Test
    fun `style toneNotes off tells nothing`() {
        enabled = false
        alone(labels(1, dark = 1.0))
        assertEquals(emptyList(), notes)
        assertEquals(emptyList(), trace.decisions)
    }

    @Test
    fun `each note is a decision with the labels, the quote escaped`() {
        alone(labels(7, cruel = 0.8, register = ComicRegister.COURTROOM, text = "« Nul » <b>, dit le juge\nau perdant."))
        val note = notes.single()
        assertEquals("your line « ‹ Nul › ‹b›, dit le juge au perdant. » mocks the person, not the play; tease the choice made in the game", note.text)
        val decision = trace.decisions.single()
        assertEquals(DecisionRecord.Kind.STYLE to "tone_noted", decision.kind to decision.decision)
        assertEquals("cruel", decision.subject)
        assertEquals(7L, decision.eventId)
        assertEquals(note.text, decision.reason)
        assertEquals("""{"line":"« Nul » <b>, dit le juge\nau perdant.","register":"courtroom","dark":0.0,"cruel":0.8,"flat":0.0,"out_of_character":0.0,"repeats":0.0}""", decision.detail.toString())
    }

    @Test
    fun `the watch tells the tone each label with the latest ones`() {
        val watch = StyleWatchImpl(tone)
        watch.labeled(labels(1, flat = 0.7))
        watch.labeled(labels(3, flat = 0.7))
        watch.labeled(labels(2))
        assertEquals(emptyList(), notes)
        watch.labeled(labels(4, flat = 0.7))
        assertEquals(listOf(StyleNoteKind.FLAT to "3 of your last 4 lines are reports without a punchline"), notes.map { it.kind to it.text })
    }
}
