package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.testing.MemorySamples
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The voice stays out of the memory (M3 §12.2): every rule, quotes left alone, case and accents, the lexicon. */
class VoiceGuardTest {
    private fun check(text: String, max: Int = 400) = VoiceGuard.check(text, "what", max)

    private fun refused(text: String): String = assertNotNull(check(text), "refused: $text")

    @Test
    fun `factual third-person texts pass`() {
        listOf(
            "Le Narrateur a promis à Mimosa un cadeau si elle finit son pont avant dimanche.",
            "Bartok a insulté le Narrateur ; il a reçu une minute de lenteur.",
            "Mimosa est tombée de sa cabane en canopée, devant ses amis.",
            "Le Narrateur a répondu sur un ton moqueur, puis s'est tu.",
            "Bartok a tu son vol de diamants pendant deux jours.",
            "Le ton de Bartok est monté ; son ton reste insolent.",
            "Kelvin a miné 4 minecraft:diamond_ore près de minecraft:trial_chambers (C-45, M-812).",
            "Aujourd'hui, Bartok s'est noyé ; l'Ender Dragon reste invaincu.",
        ).forEach { assertNull(check(it), it) }
        MemorySamples.texts.forEach { assertNull(check(it), it) }
    }

    @Test
    fun `every first-person word is refused`() {
        listOf(
            "Je l'ai puni" to "je", "J'ai promis un cadeau" to "j'", "Il me doit trois diamants" to "me",
            "Il m'a insulté" to "m'", "Bartok a défié moi-même" to "moi", "Bartok a volé mon trône" to "mon",
            "Bartok a ri de ma colère" to "ma", "Bartok a moqué mes lois" to "mes", "Nous avons ri" to "nous",
            "Bartok a défié notre loi" to "notre", "Bartok a brisé nos règles" to "nos",
        ).forEach { (text, word) -> assertContains(refused(text), "(found \"$word\" outside quotes)", message = text) }
    }

    @Test
    fun `every second-person word is refused`() {
        listOf(
            "Tu m'as insulté" to "tu", "Bartok t'a vu" to "t'", "Bartok te regarde" to "te", "Rends-toi, Bartok" to "toi",
            "Bartok vise ton trône" to "ton", "Bartok vise ta maison" to "ta", "Bartok a pris tes bottes" to "tes",
            "Vous avez perdu" to "vous", "Bartok vise votre base" to "votre", "Bartok a pris vos diamants" to "vos",
        ).forEach { (text, word) -> assertContains(refused(text), "(found \"$word\" outside quotes)", message = text) }
    }

    @Test
    fun `case, accents and curly apostrophes do not hide a word`() {
        assertContains(refused("JE suis le Narrateur"), "\"je\"")
        assertContains(refused("Bartok a pris VOS diamants"), "\"vos\"")
        assertContains(refused("J’ai promis un cadeau"), "\"j'\"")
        assertContains(refused("Bartok a dit que Tú étais nul"), "\"tu\"")
        assertContains(refused("Bartok a défié Toî"), "\"toi\"")
    }

    @Test
    fun `quoted words are exempt, an unclosed quote is not`() {
        listOf(
            "Le Narrateur a promis : « je te rends tes bottes demain, promis ».",
            "Bartok a écrit \"tu es nul\" dans le chat.",
            "Le Narrateur a dit “nous verrons bien” à Mimosa.",
            "« Moi » et « toi » : Bartok a moqué le Narrateur.",
        ).forEach { assertNull(check(it), it) }
        assertContains(refused("Le Narrateur a dit « je te rends tes bottes"), "\"je\"")
        assertContains(refused("Bartok a écrit \"tu es nul dans le chat"), "\"tu\"")
    }

    @Test
    fun `whole words only`() {
        listOf(
            "Bartok a vu la mer, des mesures, une tente et un tonneau.",
            "Mimosa a monté une tour ; Bartok a tué un vousseur imaginaire.",
            "Bartok a trouvé un nouveau minecraft:mob_spawner_te et un bloc ton_block.",
        ).forEach { assertNull(check(it), it) }
    }

    @Test
    fun `empty, too long and angle brackets are refused`() {
        assertEquals("what must not be empty", check("  "))
        assertEquals("what is longer than 10 characters (got 11): shorten it", check("Bartok rit.", max = 10))
        assertNull(check("Bartok rit", max = 10), "the bound is inclusive")
        assertNull(check("😀".repeat(10), max = 10), "characters are code points, not UTF-16 units")
        assertEquals("what must not contain < or >: write the words instead", check("Bartok a écrit <engine> dans le chat"))
        assertEquals("what must not contain < or >: write the words instead", check("Bartok a écrit « <3 »"))
    }

    @Test
    fun `the message says how to fix it`() {
        assertEquals(
            "what must be factual and in the third person, never in the Narrator's voice nor addressed to anyone " +
                "(found \"j'\" outside quotes): write « Le Narrateur a promis… » or « Bartok a insulté le Narrateur », " +
                "and put the words someone said between « »",
            check("J'ai promis à Mimosa un cadeau"),
        )
    }

    @Test
    fun `grandiose or dark words are warnings, never refusals`() {
        val text = "Les ténèbres ont englouti l'âme éternelle de Bartok ; le néant, l'abîme, la damnation, " +
            "le supplice, l'agonie et le désespoir l'attendent. Encore des âmes."
        assertNull(check(text, max = 1_000))
        assertEquals(
            listOf("ténèbres", "âme", "éternel", "néant", "abîme", "damnation", "supplice", "agonie", "désespoir"),
            VoiceGuard.warnings(text),
        )
        assertEquals(emptyList(), VoiceGuard.warnings("Bartok a abîmé sa pioche et nommé « l'Abîme » sa mine."))
        assertEquals(emptyList(), VoiceGuard.warnings(MemorySamples.memory.content))
    }
}
