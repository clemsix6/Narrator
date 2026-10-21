package io.github.clemsix6.narrator2.style

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The length of a line as `director.md` counts it (M6-18, finding 2): its sentences and its paragraphs, without a call. */
class LineLengthTest {
    @Test
    fun `the two long lines of the quiet evening are five and six sentences in two paragraphs`() {
        val search = LineLength.of(
            "Ce que j'ai trouvé ? Deux fers et deux charbons dans tes poches, Gaspard, et un seul fer dans celles de Firmin. " +
                "Huit minutes que vous ne bougez plus.\nLes bédouins appellent ça une sieste. Moi, j'appelle ça le calme avant que je m'ennuie.",
        )
        assertEquals(LineLength(sentences = 5, paragraphs = 2), search)
        assertTrue(search.long)
        assertEquals("5 sentences in 2 paragraphs", search.describe())

        val simon = LineLength.of(
            "Huit ordres, zéro faute, deux vainqueurs. Jacques est vexé : il n'a jamais eu de disciples aussi dociles.\n" +
                "Une Pomme de l'Obéissance chacun. Firmin, je note que tu as obéi. Sans un seul « trop facile ». Inquiétant.",
        )
        assertEquals(LineLength(sentences = 6, paragraphs = 2), simon)
        assertTrue(simon.long)
    }

    @Test
    fun `the good lines of the evening are three sentences at most, in one paragraph`() {
        val ad = LineLength.of(
            "Petite annonce : « Cherche meilleur mineur du serveur. Douze diamants exigés, modestie non requise. » " +
                "Gaspard a postulé deux fois en quatre secondes.",
        )
        assertEquals(LineLength(sentences = 3, paragraphs = 1), ad)
        assertFalse(ad.long)
        val lava = LineLength.of("Firmin découvre enfin quelque chose de chaud et rouge. Pas le Nether, hélas : une flaque. Ça compte pour l'entraînement.")
        assertEquals(LineLength(3, 1), lava)
        assertFalse(lava.long)
        assertEquals(LineLength(1, 1), LineLength.of("Trop facile, Firmin ?"))
    }

    @Test
    fun `what ends a sentence, and what does not`() {
        // French spacing, no-break spaces included; a run of marks counts once; quotes and brackets close with the sentence.
        assertEquals(4, LineLength.of("Quoi ?! Encore ? Oui (vraiment.) Fin").sentences)
        assertEquals(2, LineLength.of("Il a dit « non ». Puis il est parti…").sentences)
        // No end without a space after: a number, a time.
        assertEquals(1, LineLength.of("Gravité réduite à 0.5 jusqu'à 21:49:30, pas une seconde de plus").sentences)
        // A last part without its mark is a sentence; closing marks alone are none.
        assertEquals(2, LineLength.of("Première phrase. Et la seconde sans point").sentences)
        assertEquals(1, LineLength.of("Une seule phrase. »").sentences)
        // Blank lines are no paragraphs.
        assertEquals(LineLength(2, 2), LineLength.of("Un.\n\n  \nDeux."))
    }

    @Test
    fun `a line is long past three sentences, or with a line break`() {
        assertFalse(LineLength.of("Un. Deux. Trois.").long)
        assertTrue(LineLength.of("Un. Deux. Trois. Quatre.").long)
        assertTrue(LineLength.of("Un.\nDeux.").long, "two messages said as one")
        assertEquals("4 sentences", LineLength.of("Un. Deux. Trois. Quatre.").describe())
        assertEquals("1 sentence", LineLength.of("Un.").describe())
    }
}
