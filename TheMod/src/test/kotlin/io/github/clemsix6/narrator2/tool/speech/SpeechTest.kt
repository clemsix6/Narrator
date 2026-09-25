package io.github.clemsix6.narrator2.tool.speech

import io.github.clemsix6.narrator2.config.UiTexts
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.TextColor
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** How the Narrator's words are cleaned and laid out (plain text, prefixes, book pages). */
class SpeechTest {
    private val ui = UiTexts.load("fr")

    @Test
    fun `formatting codes are removed with their letter, and any section sign left`() {
        assertEquals("Oyez, mortels !", Speech.clean("§6Oyez§r, §l§kmortels§R !"))
        assertEquals("Article 12, alinéa", Speech.clean("Article 12, alinéa §"))
        assertEquals("c", Speech.clean("§§cc"))
        assertFalse('§' in Speech.clean("§§§x§y§"))
    }

    @Test
    fun `invisible characters go, spaces are plain and single`() {
        assertEquals("abcd", Speech.clean("a\u0000b‮c​d"))
        assertEquals("un deux trois !", Speech.clean("  un\tdeux  trois !  "))
        assertEquals("a b", Speech.clean("a\uD800 b"), "a lone surrogate")
        assertEquals("⚡ Zeus ✓", Speech.clean("⚡ Zeus ✓"), "visible symbols stay")
    }

    @Test
    fun `lines are trimmed, empty ones dropped, and the extra ones join the last`() {
        assertEquals("un\ndeux", Speech.clean("  un \r\n\r\n deux\r", maxLines = 3))
        assertEquals("un\ndeux\ntrois quatre cinq", Speech.clean("un\ndeux\ntrois\nquatre\n\ncinq", maxLines = 3))
        assertEquals("un deux", Speech.clean("un deux"), "one line: breaks become spaces")
    }

    @Test
    fun `blank lines can be kept one at a time between paragraphs`() {
        assertEquals("un\n\ndeux\ntrois", Speech.clean("\n\nun\n\n\n\ndeux\ntrois\n\n", maxLines = 10, keepBlankLines = true))
    }

    @Test
    fun `cleaning a cleaned text changes nothing`() {
        val samples = listOf("§6Oyez§r !\n\n\nline two\tand​ more\nthree\nfour", "  ", "§", "{\"text\":\"x\",\"color\":\"red\"}")
        for (sample in samples) {
            val once = Speech.clean(sample, maxLines = 3)
            assertEquals(once, Speech.clean(once, maxLines = 3), sample)
        }
    }

    @Test
    fun `component JSON is shown as written`() {
        val json = "{\"text\":\"x\",\"color\":\"red\"}"

        assertEquals(json, Speech.clean(json))
        assertEquals("[Narrateur] $json", Speech.chatLine(ui, json, private = false).string)
    }

    @Test
    fun `a text is cut in UTF-16 units, never inside a character`() {
        assertEquals("abc", Speech.fit("abc", 32))
        assertEquals("a".repeat(32), Speech.fit("a".repeat(40), 32))
        val emoji = "😀" // two UTF-16 units
        assertEquals("a".repeat(31), Speech.fit("a".repeat(31) + emoji, 32))
    }

    @Test
    fun `a public line has the prefix in the Narrator's color and a plain text`() {
        val line = Speech.chatLine(ui, "§cVous êtes jugés.", private = false)

        assertEquals("[Narrateur] Vous êtes jugés.", line.string)
        val (prefix, space, body) = line.siblings
        assertEquals("[Narrateur]", prefix.string)
        assertEquals(TextColor.parseColor("gold").result().get(), prefix.style.color)
        assertEquals(" ", space.string)
        assertNull(body.style.color)
        assertFalse(body.style.isItalic)
    }

    @Test
    fun `a private line has the whisper prefix and a gray italic text`() {
        val line = Speech.chatLine(ui, "Ta prière est classée.\nSans suite.", private = true)

        assertEquals("[Narrateur → toi] Ta prière est classée.\nSans suite.", line.string)
        val (prefix, _, body) = line.siblings
        assertEquals(TextColor.parseColor("gold").result().get(), prefix.style.color)
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GRAY), body.style.color)
        assertTrue(body.style.isItalic)
    }

    @Test
    fun `the prefixes and the color come from the UI texts`(@TempDir dir: Path) {
        dir.resolve("fr.properties").writeText("narrator.prefix=<Dieu>\nnarrator.color=#1a2b3c\n")
        val custom = UiTexts.load("fr", dir)

        val line = Speech.chatLine(custom, "Silence.", private = false)
        assertEquals("<Dieu> Silence.", line.string)
        assertEquals(TextColor.fromRgb(0x1a2b3c), line.siblings.first().style.color)
        assertEquals(TextColor.fromRgb(0x1a2b3c), Speech.colored(custom, "Titre").style.color)
    }

    @Test
    fun `a short page stays one page, newlines and blank lines kept`() {
        assertEquals(listOf("Oyez !\n\nLe Narrateur décrète."), Speech.layOut(listOf("Oyez !\n\n\nLe Narrateur décrète.")))
    }

    @Test
    fun `a long page continues on the next pages, every word kept in order`() {
        val words = (1..120).map { "mot$it" }
        val pages = Speech.layOut(listOf(words.joinToString(" ")))

        assertTrue(pages.size > 1, "600 characters do not fit a book page: $pages")
        assertEquals(words, pages.joinToString(" ").split(' ', '\n'))
        pages.forEach { assertEquals(listOf(it), Speech.layOut(listOf(it)), "a laid-out page fits one page") }
    }

    @Test
    fun `a page of short lines breaks after 14 lines`() {
        val lines = (1..20).map { "ligne $it" }
        val pages = Speech.layOut(listOf(lines.joinToString("\n")))

        assertEquals(listOf(lines.take(14).joinToString("\n"), lines.drop(14).joinToString("\n")), pages)
    }

    @Test
    fun `a word wider than a line is cut, nothing lost`() {
        val word = "a".repeat(700)
        val pages = Speech.layOut(listOf(word))

        assertEquals(word, pages.joinToString(""))
        pages.forEach { assertEquals(listOf(it), Speech.layOut(listOf(it))) }
    }

    @Test
    fun `each page the model wrote starts a new book page, empty ones are dropped`() {
        assertEquals(listOf("un", "deux"), Speech.layOut(listOf("un", " §r ", "deux")))
    }

    @Test
    fun `a book never has more than 100 pages`() {
        val page = (1..300).joinToString("\n") { "x" }

        assertEquals(Speech.BOOK_MAX_PAGES, Speech.layOut(List(10) { page }).size)
    }
}
