package io.github.clemsix6.narrator2.director

import com.fasterxml.jackson.databind.JsonNode
import io.github.clemsix6.narrator2.event.PlayerJoined
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.memory.BriefingSection
import io.github.clemsix6.narrator2.testing.reply
import io.github.clemsix6.narrator2.trace.ConversationRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Narrator's voice comes from the `director` prompt and from it alone (M3 §12.1, §12.3): whatever the
 * memory says, the system prompt of every chapter is, byte for byte, the `director` prompt of the
 * settings, and no memory section is in it. The memory only ever reaches the model in `user` messages.
 */
class PersonaFrozenTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `the system prompt of every chapter is the director prompt, byte for byte, whatever the memory holds`() = DirectorHarness(dir).use { h ->
        val prompt = h.services.settings.get().prompts.get(Role.DIRECTOR.id)
        h.memory.briefingSections = BriefingSection.TAGS.map { tag ->
            BriefingSection(tag, "", listOf("$MARKER $tag: le Narrateur a promis à Bartok « je te rends tes bottes »"), listOf("$MARKER $tag reduced"))
        }
        h.memory.attach = { "<memory>\nSHEET Bartok $MARKER\n</memory>" }
        h.memory.situation = listOf("Favor: Bartok -31 $MARKER")
        h.online("Bartok")
        h.fake.enqueue(reply { text("…") })
        h.fake.enqueue(reply { text("Joueurs vus : Bartok.") })
        h.fake.enqueue(reply { text("…") })
        h.fake.enqueue(reply { text("…") })

        // Chapter 1: a join, then closed by an operator with its summary.
        h.publish(PlayerJoined(DirectorHarness.ref("Bartok"), Pos("minecraft:overworld", 0, 64, 0), 1, firstTime = false, isOp = false))
        h.advance(3_000)
        h.awaitTurns(1)
        h.director.post(DirectorSignal.CloseChapter(ConversationRecord.EndReason.OPERATOR))
        h.advance(20_000)
        h.awaitUntil("chapter 1 is closed") { h.director.status().chapter == null }
        // Chapter 2: a chat, then a second turn in the same chapter.
        h.chat("Bartok", "narrateur ?")
        h.advance(1_500)
        h.awaitTurns(2)
        h.chat("Bartok", "encore ?")
        h.advance(1_500)
        h.awaitTurns(3)

        assertEquals(4, h.fake.requests.size)
        assertEquals(2, h.fake.requests.map { it.conversation }.distinct().size, "two chapters")
        h.fake.requests.forEachIndexed { index, request ->
            val system = text(request.json.path("system"))
            assertEquals(prompt, system, "request $index: the system prompt is the director prompt, byte for byte")
            assertFalse(MARKER in system, "request $index: nothing of the memory is in the system prompt")
            BriefingSection.TAGS.forEach { tag -> assertFalse("<$tag" in system, "request $index: no $tag section in the system prompt") }
        }
        // The memory did reach the model, in user messages.
        listOf(0, 2).forEach { request ->
            val first = h.messages(request).first()
            assertEquals("user", first.path("role").asText())
            assertContains(text(first.path("content")), "<grimoire>\n$MARKER grimoire")
        }
        assertTrue(h.wakeText(0).contains("SHEET Bartok $MARKER") && h.wakeText(0).contains("Favor: Bartok -31 $MARKER"))
        h.assertInvariants()
    }

    private fun text(node: JsonNode): String = if (node.isTextual) node.asText() else node.joinToString("") { it.path("text").asText() }

    private companion object {
        const val MARKER = "MEMORY-7f3a"
    }
}
