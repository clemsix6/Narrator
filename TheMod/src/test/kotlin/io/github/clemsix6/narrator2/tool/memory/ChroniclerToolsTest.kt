package io.github.clemsix6.narrator2.tool.memory

import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.memory.NoteKind
import io.github.clemsix6.narrator2.memory.RunContext
import io.github.clemsix6.narrator2.memory.RunKind
import io.github.clemsix6.narrator2.memory.chronicle.ChroniclerFixture
import io.github.clemsix6.narrator2.memory.chronicle.ChroniclerFixture.Companion.at
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.tool.ToolCatalog
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolEnv
import io.github.clemsix6.narrator2.tool.ToolStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Chronicler's own tools (M3 §7.5) on the real store, called as the tool runner calls them. */
class ChroniclerToolsTest {
    private val f = ChroniclerFixture()
    private val batch = RunContext(7, RunKind.BATCH, at("20:00"), at("20:10"), "director-c13")

    @AfterEach
    fun close() = f.close()

    @Test
    fun `six tools, all writes, shown only with the memory, to the Chronicler and never to the Director`() {
        val tools = chroniclerTools(TestServices())
        assertEquals(listOf("record_episode", "note_to_director", "write_chapter", "since_last", "use_canon", "mark_used"), tools.map { it.name })
        tools.forEach { tool ->
            assertEquals(ToolEffect.ENGINE, tool.effect, tool.name)
            assertTrue(tool.description.length <= 600, "${tool.name}: a description read at every request stays short")
            assertTrue(tool.isAvailable(ToolEnv(true, emptySet(), memoryAvailable = true)))
            assertTrue(!tool.isAvailable(ToolEnv(true, emptySet(), memoryAvailable = false)))
        }
        val wired = ToolEnv(true, setOf(Role.ANALYST), memoryAvailable = true)
        val director = ToolCatalog.toolSetFor(Role.DIRECTOR, wired, f.services).names
        assertTrue(tools.none { it.name in director })
        assertTrue("note_to_director" in ToolCatalog.toolSetFor(Role.DREAMER, wired, f.services).names)
    }

    @Test
    fun `note_to_director writes a coherence note and gives it to the Director without waking it`() {
        val e = f.scenario()
        f.runRow(7, RunKind.BATCH, "20:00", "20:10")
        val result = f.call(
            NoteToDirectorTool(),
            """{"text": "Le Narrateur a appelé Bartok « le Plongeur » alors que la lave était en cause, pas l'eau.", "players": ["Bartok"], "refs": ["${e.nickname}"]}""",
            batch,
        )

        assertEquals(ToolStatus.OK, result.status, result.text)
        val note = f.rows("SELECT id, kind, status, refs::VARCHAR, run_id FROM notes") {
            listOf(getLong(1).toString(), getString(2), getString(3), getString(4), getLong(5).toString())
        }.single()
        assertEquals(listOf("coherence", "open", "[${e.nickname.id}]", "7"), note.drop(1))
        assertEquals("ok: noted N-${note[0]} for the Narrator: it reads it at its next wake-up, then in its next briefings", result.text)
        val posted = f.director.signals.filterIsInstance<DirectorSignal.MemoryNote>().single()
        assertEquals(NoteKind.COHERENCE, posted.kind)
        assertEquals("N-${note[0]}", posted.ref.toString())
        assertContains(posted.text, "le Plongeur")
    }

    @Test
    fun `what the Narrator told the players is remembered only with its own words`() {
        val e = f.scenario()
        f.runRow(7, RunKind.BATCH, "20:00", "20:10")
        val threat = """"kind": "threat", "what": "Le Narrateur a menacé Bartok d'une pluie de slimes s'il recommence.", "players": ["Bartok"]"""

        val bare = f.call(RememberTool(), """{$threat, "evidence": ["e${e.slimes}"]}""", batch)
        assertEquals(
            "Invalid arguments: a threat needs quote: copy 3 words or more exactly from the Narrator's NARRATOR line, and cite that line in evidence",
            bare.text,
        )
        assertEquals(0L, f.count("canon", "source = 'chronicler'"))

        val quoted = f.call(RememberTool(), """{$threat, "quote": "une pluie de slimes si tu recommences", "evidence": ["e${e.slimes}"]}""", batch)
        assertEquals(ToolStatus.OK, quoted.status, quoted.text)
        assertEquals(
            listOf(listOf("threat", "une pluie de slimes si tu recommences", "[${e.slimes}]")),
            f.rows("SELECT kind, quote, evidence_events::VARCHAR FROM canon WHERE source = 'chronicler'") { listOf(getString(1), getString(2), getString(3)) },
        )
    }

    @Test
    fun `the refusals say how to do better`() {
        val e = f.scenario()
        f.runRow(7, RunKind.BATCH, "20:00", "20:10")

        val chapter = f.call(WriteChapterTool(), """{"title": "Titre", "text": "Texte."}""", batch)
        assertEquals("Invalid arguments: write_chapter is for the nap of a closed chapter, not for a batch", chapter.text)

        val notCanon = f.call(UseCanonTool(), """{"id": "${e.lava}", "evidence": ["e${e.plongeur}"]}""", batch)
        assertEquals("Invalid arguments: id must be a canon entry, like C-12 (got \"${e.lava}\")", notCanon.text)

        val nowhere = f.call(UseCanonTool(), """{"id": "${e.nickname}", "evidence": ["e999999"]}""", batch)
        assertContains(nowhere.text, "is in the Journal: cite the refs of the exchange log")

        val tags = f.call(
            RecordEpisodeTool(),
            """{"title": "Titre", "what": "Mimosa est tombée.", "players": ["Mimosa"], "importance": 3, "tags": ["chute"], "evidence": ["e${e.death}"]}""",
            batch,
        )
        assertEquals(ToolStatus.INVALID, tags.status)
        assertContains(tags.text, "fall")

        val stranger = f.call(SinceLastTool(), """{"player": "Kelvin", "text": "Kelvin est parti."}""", batch)
        assertContains(stranger.text, "no player named Kelvin has ever played here")

        val voice = f.call(SinceLastTool(), """{"player": "Bartok", "text": "Tu es parti trop tôt."}""", batch)
        assertContains(voice.text, "never in the Narrator's voice")
    }
}
