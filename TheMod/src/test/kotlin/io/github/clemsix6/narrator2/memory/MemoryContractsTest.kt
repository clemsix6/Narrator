package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.director.CueKind
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.director.Item
import io.github.clemsix6.narrator2.director.Urgency
import io.github.clemsix6.narrator2.testing.MemorySamples
import io.github.clemsix6.narrator2.testing.TestServices
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The seams of M3-01: the disabled memory the server runs with, the briefing sections, the Director's new signals. */
class MemoryContractsTest {
    @Test
    fun `the server runs with a disabled memory until it is wired`() {
        val memory = createMemory(TestServices())
        assertIs<DisabledMemory>(memory)
        assertFalse(memory.available)
        assertEquals("not wired yet", memory.unavailableReason)
    }

    @Test
    fun `a disabled memory reads nothing, refuses writes and says why in the briefing`() = runBlocking {
        val memory = DisabledMemory("memory.enabled is false")
        val refused = assertFailsWith<MemoryRejected> { memory.store.addCanon(MemorySamples.canonDraft, MemorySamples.director) }
        assertEquals("the memory is unavailable: memory.enabled is false", refused.message)
        assertNull(memory.store.canon(MemorySamples.canon.ref))
        assertEquals(emptyList(), memory.store.openNotes())
        assertEquals(emptyList(), memory.recall.search(RecallQuery("lave", purpose = RecallPurpose.TOOL)))
        assertNull(memory.sheets.sheet(MemorySamples.bartok.uuid, 0))
        assertTrue(memory.jobs.requestDream("Clement").isFailure)

        val chapter = ChapterMemory("director-c1", 1)
        val sections = memory.briefing(chapter, 0)
        assertEquals(BriefingSection.TAGS, sections.map { it.tag })
        sections.forEach { section ->
            assertEquals("<${section.tag}>\n(memory unavailable: memory.enabled is false)\n</${section.tag}>", section.render())
            assertEquals(section.render(), section.render(reducedForm = true))
        }
        assertNull(memory.attachments(emptyList(), chapter, 0))
        assertEquals(emptyList(), memory.situationLines(0))
        assertNull(memory.runContext("chronicler-1"))
    }

    @Test
    fun `a briefing section renders whole or reduced`() {
        val section = BriefingSection("chronicle", "level=\"saga\"", listOf("Saga (H-3): …", "Session (H-88): …"), reduced = listOf("Saga (H-3): …"))
        assertEquals("<chronicle level=\"saga\">\nSaga (H-3): …\nSession (H-88): …\n</chronicle>", section.render())
        assertEquals("<chronicle level=\"saga\">\nSaga (H-3): …\n</chronicle>", section.render(reducedForm = true))
        assertNull(BriefingSection("memories", "", listOf("M-120 …")).render(reducedForm = true), "left out of a reduced briefing")
        assertEquals(setOf("grimoire", "canon"), BriefingSection.NEVER_REDUCED)
    }

    @Test
    fun `a due commitment wakes the Director soon, notes and cues never wake it`() {
        val due = Item.of(DirectorSignal.MemoryDue(MemorySamples.canon.ref, "C-45 promise → Mimosa, set 1h ago: « … »", at = 1_000))
        val note = Item.of(DirectorSignal.MemoryNote(MemorySamples.note.ref, NoteKind.COHERENCE, "…", at = 2_000))
        val cue = Item.of(DirectorSignal.MemoryCue(CueKind.RETURN, MemorySamples.memory.ref, MemorySamples.mimosa, "Mimosa is where M-120 happened", at = 3_000))
        assertEquals(listOf(Urgency.SOON, Urgency.NEXT, Urgency.NEXT), listOf(due, note, cue).map { it.urgency })
        assertEquals(listOf(1_000L, 2_000L, 3_000L), listOf(due, note, cue).map { it.at })
    }
}
