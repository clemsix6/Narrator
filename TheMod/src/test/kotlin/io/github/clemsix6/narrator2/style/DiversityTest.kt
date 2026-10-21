package io.github.clemsix6.narrator2.style

import io.github.clemsix6.narrator2.memory.stats.Tics
import io.github.clemsix6.narrator2.style.StyleSamples.START
import io.github.clemsix6.narrator2.style.StyleSamples.labels
import io.github.clemsix6.narrator2.style.StyleSamples.watch
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The measures of diversity (M6 §3.7) on known corpora: one definition ([StyleReport.measures]) for a period and for the
 * watch's latest lines ([StyleWatch.diversity]), the openers and tics normalized ([Tics]).
 */
class DiversityTest {
    private val names = Tics.Names.of(StyleSamples.M4_PLAYERS)

    /** [texts] as lines of the Journal, not reviewed. */
    private fun lines(texts: List<String>) = texts.mapIndexed { i, text -> StyleReport.Line(i + 1L, START.plusSeconds(i.toLong()), text, null, emptyMap(), emptyList()) }

    /**
     * The essay's measures of the M4 acceptance: 49 distinct openers of 61 (0.80); « céleste » in 11 of its lines (18 %).
     * Its tics: « Dix-sept secondes » ×5, « mouton ferait mieux » ×3, « l'écran de réapparition » ×3.
     */
    @Test
    fun `the M4 acceptance's openers, tics and crutch word`() {
        val m4 = StyleSamples.m4()
        val measures = StyleReport.measures(lines(m4), names)
        assertEquals(66, measures.lines)
        assertEquals(49.0 / 61, measures.openersDistinct, 1e-9)
        assertEquals(3, measures.tics)
        val said = m4.map { Tics.said(it, START, names) }
        assertEquals(11, said.count { "céleste" in Tics.words(it) })
        val unnamed = StyleReport.measures(lines(m4)).openersDistinct
        assertTrue(unnamed > 49.0 / 61, "without the names, « Bonsoir, Lisette » and « Bonsoir, Mimosa » differ: $unnamed")
    }

    @Test
    fun `the openers distinct are counted on the lines that open`() {
        val texts = listOf(
            "Vingt secondes ! Bartok gagne encore une fois.", "Douze secondes ! Mimosa gagne enfin, et de peu.",
            "Bonsoir, mortels ! La nuit sera longue ce soir.", "BARTOK vs MIMOSA", "[actionbar] Une ligne au-dessus de la barre d'action.",
        )
        assertEquals(2.0 / 3, StyleReport.openersDistinct(texts.map { Tics.said(it, START, names) }), 1e-9)
        assertEquals(0.0, StyleReport.openersDistinct(emptyList()))
    }

    @Test
    fun `the watch measures its latest fifty lines with their labels`() {
        val texts = List(10) { "Vingt secondes ! Le record numéro $it tombe." } + List(50) { i -> "Ligne ${'a' + i % 26}${'a' + i / 26} : une réplique céleste de plus." }
        val watch = watch(texts, StyleSamples.M4_PLAYERS)
        watch.labeled(labels(5, ComicRegister.COURTROOM, crutches = listOf("vingt")))
        val registers = List(6) { ComicRegister.DIVINE_BUREAUCRACY } + List(2) { ComicRegister.SPORTS_COMMENTARY } + List(2) { ComicRegister.PLAIN }
        registers.forEachIndexed { i, register -> watch.labeled(labels(51L + i, register, crutches = if (i < 2) listOf("Céleste") else emptyList())) }

        val diversity = watch.diversity()
        assertEquals(50, diversity.lines)
        assertEquals(1.0, diversity.openersDistinct, 1e-9, "the ten « Vingt secondes » left the latest fifty")
        assertEquals(ComicRegister.DIVINE_BUREAUCRACY, diversity.topRegister, "the line 5 left too, with its courtroom")
        assertEquals(0.6, diversity.topRegisterShare, 1e-9, "of the ten lines reviewed")
        assertEquals(StyleReport.entropy(listOf(6, 2)), diversity.registerEntropy, "plain is not a register of the voice")
        assertEquals(listOf("céleste" to 2), diversity.crutches)
        assertEquals(StyleReport.measures(lines(texts.takeLast(50)), names).tics, diversity.tics)
        assertEquals(Diversity.NONE, StyleWatchImpl().diversity())
    }
}
