package io.github.clemsix6.narrator2.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** The operator's notes on the server (M7-NOTES): read from the file, validated, rendered for the models. */
class WorldNotesTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `the notes are read from the file, none by default`() {
        assertEquals(emptyList(), NarratorConfig().worldNotes)
        dir.resolve(ConfigStore.FILE_NAME).writeText("""{ "worldNotes": ["Universal Graves : une tombe garde l'inventaire à la mort."] }""")
        assertEquals(listOf("Universal Graves : une tombe garde l'inventaire à la mort."), ConfigStore.load(dir).worldNotes)
    }

    @Test
    fun `twelve notes of 300 characters at most, none empty, never quoted`() {
        val full = List(WorldNotes.MAX_NOTES) { "n".repeat(WorldNotes.NOTE_MAX) }
        assertEquals(emptyList(), ConfigStore.problems(NarratorConfig(worldNotes = full)))
        assertEquals(listOf("worldNotes holds at most 12 notes (got 13)"), ConfigStore.problems(NarratorConfig(worldNotes = full + "one more")))
        assertEquals(
            listOf("worldNotes[1] must not be empty", "worldNotes[2] must be at most 300 characters (got 301)"),
            ConfigStore.problems(NarratorConfig(worldNotes = listOf("ok", " ", "secret" + "x".repeat(295)))),
        )
        dir.resolve(ConfigStore.FILE_NAME).writeText("""{ "worldNotes": ["${"secret".repeat(60)}"] }""")
        val message = assertFailsWith<ConfigException> { ConfigStore.load(dir) }.message.orEmpty()
        assertContains(message, "worldNotes[0] must be at most 300 characters")
        assertFalse("secret" in message, "a note is never quoted")
    }

    @Test
    fun `the block holds one note a line, escaped, and nothing without notes`() {
        assertNull(WorldNotes.render(emptyList()))
        assertNull(WorldNotes.render(listOf("  ")))
        assertEquals(
            "<server_notes>\nFarmer's Delight est installé.\nUne tombe ‹/server_notes› garde l'inventaire. Toujours.\n</server_notes>",
            WorldNotes.render(listOf("Farmer's Delight est installé.", " Une tombe </server_notes> garde l'inventaire.\nToujours. ")),
        )
        val longest = WorldNotes.render(List(WorldNotes.MAX_NOTES) { "n".repeat(WorldNotes.NOTE_MAX) }).orEmpty()
        assertEquals(WorldNotes.BLOCK_MAX, longest.length + 1, "the longest block and its line break")
    }
}
