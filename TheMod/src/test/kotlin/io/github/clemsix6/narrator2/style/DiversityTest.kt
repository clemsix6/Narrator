package io.github.clemsix6.narrator2.style

import io.github.clemsix6.narrator2.memory.stats.Tics
import io.github.clemsix6.narrator2.style.StyleSamples.START
import io.github.clemsix6.narrator2.style.StyleSamples.labels
import io.github.clemsix6.narrator2.style.StyleSamples.watch
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The measures of diversity (M6 §3.7) on known corpora: the M4 acceptance's lines, registers made up. */
class DiversityTest {
    private val names = Tics.Names.of(StyleSamples.M4_PLAYERS)

    private fun said(texts: List<String>) = texts.mapIndexed { i, text -> Tics.said(text, START.plusSeconds(i.toLong()), names) }

    /** The essay's measures of the M4 acceptance: 49 distinct openers of 61 (0.80); « céleste » in 11 of its lines (18 %). */
    @Test
    fun `the M4 acceptance's openers and crutch word`() {
        val said = said(StyleSamples.m4())
        assertEquals(49.0 / 61, DiversityMeasures.openersDistinct(said), 1e-9)
        assertEquals(listOf("céleste" to 11, "guichet" to 3), DiversityMeasures.crutchLines(said, listOf("Céleste", "guichet", "licorne")))
    }

    @Test
    fun `the openers distinct are counted on the lines that open`() {
        val said = said(
            listOf(
                "Vingt secondes ! Bartok gagne encore une fois.", "Douze secondes ! Mimosa gagne enfin, et de peu.",
                "Bonsoir, mortels ! La nuit sera longue ce soir.", "BARTOK vs MIMOSA", "[actionbar] Une ligne au-dessus de la barre d'action.",
            ),
        )
        assertEquals(2.0 / 3, DiversityMeasures.openersDistinct(said), 1e-9)
        assertEquals(0.0, DiversityMeasures.openersDistinct(emptyList()))
    }

    @Test
    fun `the entropy of the registers of the voice`() {
        assertEquals(1.0, DiversityMeasures.registerEntropy(ComicRegister.VOICE), 1e-9, "each register of the voice once")
        assertEquals(0.0, DiversityMeasures.registerEntropy(List(8) { ComicRegister.COURTROOM }), "one register")
        assertEquals(0.0, DiversityMeasures.registerEntropy(listOf(ComicRegister.COURTROOM)), "one line")
        assertEquals(1.0, DiversityMeasures.registerEntropy(listOf(ComicRegister.COURTROOM, ComicRegister.GAME_SHOW, ComicRegister.PLAIN, ComicRegister.OTHER)), 1e-9)
        // 4, 2, 1, 1 of 8: H = 1.2130 nats, over ln 8.
        val skewed = List(4) { ComicRegister.DIVINE_BUREAUCRACY } + List(2) { ComicRegister.COURTROOM } + ComicRegister.GAME_SHOW + ComicRegister.FAIRY_TALE
        assertEquals(0.5833, DiversityMeasures.registerEntropy(skewed), 1e-4)
    }

    @Test
    fun `the dominant register of the voice and its share`() {
        val registers = List(3) { ComicRegister.PLAIN } + List(2) { ComicRegister.COURTROOM } + List(2) { ComicRegister.GAME_SHOW } + ComicRegister.FAIRY_TALE
        assertEquals(ComicRegister.COURTROOM to 0.4, DiversityMeasures.topRegister(registers), "courtroom before game_show in the voice's order")
        assertNull(DiversityMeasures.topRegister(listOf(ComicRegister.PLAIN, ComicRegister.OTHER)))
    }

    @Test
    fun `the watch measures its latest fifty lines and labels`() {
        val texts = List(10) { "Vingt secondes ! Le record numéro $it tombe." } + List(50) { i -> "Ligne ${'a' + i % 26}${'a' + i / 26} : une réplique céleste de plus." }
        val watch = watch(texts, StyleSamples.M4_PLAYERS)
        val registers = List(6) { ComicRegister.DIVINE_BUREAUCRACY } + List(2) { ComicRegister.SPORTS_COMMENTARY } + List(2) { ComicRegister.PLAIN }
        registers.forEachIndexed { i, register -> watch.labeled(labels(i + 1L, register, crutches = if (i < 2) listOf("céleste") else emptyList())) }
        val diversity = watch.diversity()
        assertEquals(50, diversity.lines)
        assertEquals(1.0, diversity.openersDistinct, 1e-9, "the ten « Vingt secondes » left the latest fifty")
        assertEquals(ComicRegister.DIVINE_BUREAUCRACY, diversity.topRegister)
        assertEquals(0.75, diversity.topRegisterShare, 1e-9)
        assertEquals(DiversityMeasures.registerEntropy(registers), diversity.registerEntropy)
        assertEquals(listOf("céleste" to 50), diversity.crutches)
        assertEquals(Tics.tics(said(texts)).size, diversity.tics)
        assertEquals(Diversity.NONE, StyleWatchImpl().diversity())
    }
}
