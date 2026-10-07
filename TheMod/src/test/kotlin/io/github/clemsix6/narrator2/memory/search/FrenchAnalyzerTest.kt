package io.github.clemsix6.narrator2.memory.search

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** The French analyzer (M3 §10.2), on the samples of the spike (`.research/spikes/m3/results.txt`) and its rules. */
class FrenchAnalyzerTest {
    private fun terms(text: String) = FrenchAnalyzer.terms(text)

    @Test
    fun `the spike's samples give the same terms`() {
        assertEquals(listOf("chambr", "epreuv"), terms("chambres d'épreuve"))
        assertEquals(listOf("noyad"), terms("noyades"))
        assertEquals(listOf("insult"), terms("insulté"))
        assertEquals(listOf("pileu", "chambr"), terms("Pilleur de Chambres"))
        assertEquals(listOf("trial_chambers", "trial", "chamb"), terms("minecraft:trial_chambers"))
        assertEquals(listOf("elytr"), terms("élytres"))
        assertEquals(listOf("promes"), terms("promesses"))
        assertEquals(listOf("prophet"), terms("prophétie"))
        assertEquals(listOf("end", "dragon"), terms("l'Ender Dragon"))
    }

    @Test
    fun `game ids are kept whole and split into their parts`() {
        assertEquals(listOf("lava", "lava"), terms("minecraft:lava"), "the id, then its only part, stemmed")
        assertEquals(listOf("the_nether", "the", "neth"), terms("minecraft:the_nether"))
        assertEquals(listOf("othermod", "moon", "ston"), terms("othermod:moon_stone"), "another namespace: its parts only")
        assertEquals(listOf("diamond_ore", "diamond", "ore"), terms("diamond_ore"))
        assertEquals(terms("Nether"), terms("minecraft:the_nether").takeLast(1), "a French word finds the id's part")
    }

    @Test
    fun `elisions go, typographic apostrophe included`() {
        assertEquals(listOf("arbr"), terms("L’Arbre"))
        assertEquals(listOf("arbr"), terms("l'arbre"))
        assertEquals(emptyList(), terms("qu’il jusqu'au"), "what is left is a stop word")
        assertEquals(listOf("aujourdhui"), terms("aujourd'hui"), "not an elision: the apostrophe just goes")
        assertEquals(listOf("epee"), terms("d'épée"))
    }

    @Test
    fun `accents and case are folded`() {
        assertEquals(terms("epreuve"), terms("ÉPREUVE"))
        assertEquals(listOf("oeuf"), terms("Œuf"))
        assertEquals(listOf("noel"), terms("Noël"))
        assertEquals("epreuve coeur", FrenchAnalyzer.fold("épreuve cœur"))
    }

    @Test
    fun `stop words go, numbers stay`() {
        assertEquals(emptyList(), terms("le la les de des du un une et est a été"))
        assertEquals(listOf("3", "diamant"), terms("3 diamants"))
        assertEquals(listOf("bartok", "tomb", "lave"), terms("Bartok est tombé dans la lave !"))
    }

    @Test
    fun `the light stemmer joins the forms of a word`() {
        assertEquals(terms("cuisinier"), terms("cuisinière"))
        assertEquals(listOf("trois"), terms("troisième"), "the -ième rule")
        assertEquals(terms("noyade"), terms("noyades"))
        assertEquals(terms("chevaux"), terms("cheval"))
        assertEquals(terms("construction"), terms("constructions"))
        assertEquals(terms("menace"), terms("menacé"))
        assertEquals(emptyList(), terms("  … !? ' : _ "))
    }
}
