package io.github.clemsix6.narrator2.config

import io.github.clemsix6.narrator2.testing.LogCapture
import net.minecraft.network.chat.TextColor
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UiTextsTest {
    @TempDir
    lateinit var dir: Path

    private fun bundledKeys(language: String): Set<String> {
        val properties = Properties()
        UiTexts::class.java.getResourceAsStream("/narrator2/ui/$language.properties")!!.reader(Charsets.UTF_8).use { properties.load(it) }
        return properties.stringPropertyNames()
    }

    @Test
    fun `the French texts have exactly the known keys, with their placeholders`() {
        val ui = UiTexts.load("fr")

        assertEquals(UiTexts.KEYS.keys, bundledKeys("fr"), "no missing and no unknown key")
        UiTexts.KEYS.forEach { (key, placeholders) ->
            assertTrue(ui[key].isNotBlank(), key)
            placeholders.forEach { assertContains(ui[key], "{$it}", message = key) }
        }
        assertEquals("fr", ui.language)
    }

    @Test
    fun `texts are read as UTF-8`() {
        val ui = UiTexts.load("fr")
        assertEquals("Narrateur", ui["narrator.name"])
        assertEquals("Le Narrateur réfléchit…", ui["narrator.thinking"])
        assertContains(ui["narrator.whisper_prefix"], "→")
    }

    @Test
    fun `the prefix color is a Minecraft color, checked at load`() {
        assertEquals(TextColor.GOLD, TextColor.parseColor(UiTexts.load("fr")["narrator.color"]).result().get())

        dir.resolve("fr.properties").writeText("narrator.color=#1a2b3c\n")
        assertEquals(TextColor.fromRgb(0x1a2b3c), TextColor.parseColor(UiTexts.load("fr", dir)["narrator.color"]).result().get())

        dir.resolve("fr.properties").writeText("narrator.color=golden\n")
        val error = assertFailsWith<IllegalArgumentException> { UiTexts.load("fr", dir) }
        assertContains(error.message.orEmpty(), "narrator.color must be a color name or #rrggbb")
    }

    @Test
    fun `format fills the placeholders and checks their names`() {
        val ui = UiTexts.load("fr")
        val text = ui.format("pray.cooldown", "seconds" to 7)
        assertContains(text, "7 s")
        assertFalse("{seconds}" in text)
        assertContains(ui.format("items.returned", "items" to "3x diamond"), "3x diamond")

        assertFailsWith<IllegalArgumentException> { ui.format("pray.cooldown", "secs" to 7) }
        assertFailsWith<IllegalArgumentException> { ui.format("pray.cooldown") }
        assertFailsWith<IllegalStateException> { ui["narrator.nickname"] }
    }

    @Test
    fun `an override replaces texts key by key and warns about unknown keys`() {
        dir.resolve("fr.properties").writeText("narrator.name=Oracle\npray.cooldown=Encore {seconds} s, mortel.\nnarrator.nickname=X\n")

        val logs = LogCapture()
        val ui = logs.use { UiTexts.load("fr", dir) }

        assertEquals("Oracle", ui["narrator.name"])
        assertEquals("Encore 3 s, mortel.", ui.format("pray.cooldown", "seconds" to 3))
        assertEquals(UiTexts.load("fr")["narrator.thinking"], ui["narrator.thinking"], "other keys stay bundled")
        assertContains(logs.text, "unknown UI text 'narrator.nickname' is ignored")
    }

    @Test
    fun `an override that loses a placeholder is refused`() {
        dir.resolve("fr.properties").writeText("items.returned=Tiens, rendu.\n")
        val error = assertFailsWith<IllegalArgumentException> { UiTexts.load("fr", dir) }
        assertContains(error.message.orEmpty(), "items.returned must contain {items}")
    }

    @Test
    fun `only bundled languages load`() {
        assertTrue(UiTexts.isBundled("fr"))
        assertFalse(UiTexts.isBundled("xx"))
        assertFailsWith<IllegalArgumentException> { UiTexts.load("xx") }
        assertFalse(Files.exists(dir.resolve("fr.properties")), "loading never writes an override")
    }
}
