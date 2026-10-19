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

    @Test
    fun `an answer with no letter nor digit never matches, and is found by firstEmpty`() {
        assertFalse(Answers.matches("…", listOf("…")))
        assertFalse(Answers.matches("anything at all", listOf("?!")))
        assertEquals(IndexedValue(1, "?!"), Answers.firstEmpty(listOf("enclume", "?!", "")))
        assertNull(Answers.firstEmpty(listOf("enclume", "une enclume")))
    }
}
