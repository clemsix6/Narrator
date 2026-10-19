package io.github.clemsix6.narrator2.mechanics.challenge

import io.github.clemsix6.narrator2.testing.MechanicsSamples
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The answers of a quiz (M4 §5.2): case, accents and punctuation ignored, spaces folded, whole words only. */
class AnswersTest {
    @Test
    fun `a text is normalized to lower-case words without accents nor punctuation`() {
        assertEquals("l oeuf deja", Answers.normalize("« L'Œuf, déjà ! »"))
        assertEquals("une enclume", Answers.normalize("  UNE   Enclume…  "))
        assertEquals("ca coute 12 lingots", Answers.normalize("Ça coûte 12 lingots ?!"))
        assertEquals("aeroport strasse", Answers.normalize("Æroport Straße"))
        assertEquals("", Answers.normalize(" ?! … "))
    }

    @Test
    fun `an accepted answer is found as whole words of the message, whatever the case, the accents and the punctuation`() {
        val answers = MechanicsSamples.riddle.answers!!
        assertTrue(Answers.matches("C'est une ENCLUME !", answers))
        assertTrue(Answers.matches("enclume", answers))
        assertTrue(Answers.matches("Je dirais... l'enclume ?", answers), "the apostrophe splits the words")
        assertTrue(Answers.matches("une   enclume", answers), "the spaces are folded")
        assertTrue(Answers.matches("Énclume", answers), "accents ignored")
        assertFalse(Answers.matches("une enclumette", answers), "never part of a word")
        assertFalse(Answers.matches("clume", answers))
        assertFalse(Answers.matches("un marteau", answers))
        assertFalse(Answers.matches("", answers))
    }

    @Test
    fun `an answer of several words matches only in a row`() {
        val answers = listOf("Le Nether")
        assertTrue(Answers.matches("c'est le nether, évidemment", answers))
        assertFalse(Answers.matches("le monde du nether", answers))
        assertTrue(Answers.matches("Œuf de tortue", listOf("oeuf de tortue")), "a ligature is its two letters")
    }

    /** M4 review, finding 14: « diamant fer or émeraude enclume pioche épée arc » won the quiz `enclume`. */
    @Test
    fun `a message that lists guesses answers nothing, three words besides the answer at most`() {
        val answers = MechanicsSamples.riddle.answers!!
        assertFalse(Answers.answers("diamant fer or émeraude enclume pioche épée arc", answers), "a list of guesses")
        assertFalse(Answers.answers("marteau, enclume, scie, établi, pioche", answers))
        assertTrue(Answers.answers("c'est une enclume", listOf("enclume")), "« c est une enclume »: the answer and three words")
        assertTrue(Answers.answers("C'est une ENCLUME, évidemment !", answers), "« une enclume » and three words")
        assertFalse(Answers.answers("C'est une ENCLUME, évidemment !", listOf("enclume")), "« enclume » and four words")
        assertFalse(Answers.answers("je crois bien que c'est une enclume", answers))
        assertTrue(Answers.answers("c'est le nether, évidemment", listOf("le nether")))
        assertFalse(Answers.answers("je pense que c'est le nether", listOf("le nether")))
        assertFalse(Answers.answers("une enclumette", answers), "never part of a word")
        assertFalse(Answers.answers("…", listOf("…")), "nor an answer of no word")

        assertTrue(Answers.matches("diamant fer or émeraude enclume pioche épée arc", answers), "a rule's words are found anywhere")
    }

    @Test
    fun `a participant's messages are one attempt every five seconds, in each quiz`() {
        val attempts = Attempts()
        val bartok = MechanicsSamples.bartok.uuid
        val mimosa = MechanicsSamples.mimosa.uuid
        val t = MechanicsSamples.evening

        assertTrue(attempts.take(50, bartok, t))
        assertFalse(attempts.take(50, bartok, t + 1_000), "a second guess a second later is not read")
        assertFalse(attempts.take(50, bartok, t + Answers.ATTEMPT_GAP_MS - 1), "nor just before the five seconds")
        assertTrue(attempts.take(50, mimosa, t + 1_000), "each participant has their own")
        assertTrue(attempts.take(53, bartok, t + 1_000), "and each quiz")
        assertTrue(attempts.take(50, bartok, t + Answers.ATTEMPT_GAP_MS), "five seconds after the last attempt")
        assertFalse(attempts.take(50, bartok, t + Answers.ATTEMPT_GAP_MS + 4_000), "counted from the last attempt, not from a message not read")

        attempts.forget(50)
        assertTrue(attempts.take(50, bartok, t + Answers.ATTEMPT_GAP_MS + 4_001), "a quiz that ended forgets its attempts")
        assertFalse(attempts.take(53, bartok, t + 2_000), "the other quiz keeps its own")
    }

    @Test
    fun `an answer with no letter nor digit never matches, and is found by firstEmpty`() {
        assertFalse(Answers.matches("…", listOf("…")))
        assertFalse(Answers.matches("anything at all", listOf("?!")))
        assertEquals(IndexedValue(1, "?!"), Answers.firstEmpty(listOf("enclume", "?!", "")))
        assertNull(Answers.firstEmpty(listOf("enclume", "une enclume")))
    }
}
