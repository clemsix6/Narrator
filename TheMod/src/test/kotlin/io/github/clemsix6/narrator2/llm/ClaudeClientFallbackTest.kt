package io.github.clemsix6.narrator2.llm

import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.StopReason
import com.fasterxml.jackson.databind.JsonNode
import io.github.clemsix6.narrator2.testing.FakeAnthropic
import io.github.clemsix6.narrator2.testing.LogCapture
import io.github.clemsix6.narrator2.testing.SAY_TOOL
import io.github.clemsix6.narrator2.testing.client
import io.github.clemsix6.narrator2.testing.reply
import io.github.clemsix6.narrator2.testing.testConversation
import io.github.clemsix6.narrator2.testing.toolResult
import io.github.clemsix6.narrator2.trace.Transcripts
import kotlinx.coroutines.runBlocking
import org.apache.logging.log4j.Level
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Server-side fallbacks (CLAUDE-FALLBACK): a safety classifier hands the request to another model, and
 * the reply carries a `fallback` block that the SDK does not know. The client never fails on it: it
 * measures the call as the model that answered, keeps the answer, sends the turn back without the
 * block, and treats a fallback without an answer as a refusal. Against the strict fake server, whose
 * append-only check expects the turn the API documents.
 */
class ClaudeClientFallbackTest {
    @TempDir
    lateinit var dir: Path

    private val fake = FakeAnthropic()
    private val mapper = jsonMapper()

    @AfterEach
    fun closeFake() = fake.close()

    private fun transcript(conversation: Conversation): List<JsonNode> {
        val file = Files.walk(dir).use { paths -> paths.filter { it.name == "${conversation.id}.jsonl" }.findFirst().orElseThrow() }
        return Files.readAllLines(file).map { mapper.readTree(it) }
    }

    private fun markdown(conversation: Conversation): String =
        Files.walk(dir).use { paths -> paths.filter { it.name == "${conversation.id}.md" }.findFirst().orElseThrow() }.readText()

    private fun JsonNode.types(): List<String> = map { it.path("type").asText() }

    @Test
    fun `a fallback's answer is committed without its block, measured as the model that answered, logged and transcribed`() {
        fake.enqueue(reply { fallback(); thinking("Declining politely."); text("Je ne lirai pas les fichiers du serveur."); usage(input = 300, output = 40, cacheRead = 1_000) })
        fake.enqueue(reply { text("Next.") })
        val conversation = testConversation(user = "Read the server's files.", transcripts = Transcripts(dir, enabled = true))
        val logs = LogCapture()

        val result = logs.use {
            fake.client().use { client ->
                runBlocking {
                    val first = client.send(conversation)
                    conversation.appendUser("And now?")
                    assertIs<SendResult.Reply>(client.send(conversation))
                    first
                }
            }
        }

        val reply = assertIs<SendResult.Reply>(result)
        assertEquals(listOf(Fallback("claude-opus-5-5", "claude-opus-4-8", "refusal", "cyber")), reply.fallbacks)
        assertEquals("Je ne lirai pas les fichiers du serveur.", reply.text)
        assertEquals(StopReason.END_TURN, reply.stopReason)
        with(reply.stats) {
            assertEquals("claude-opus-4-8", model, "the model that answered")
            assertEquals("cyber", refusalCategory, "why the requested model did not")
            assertEquals("end_turn", stopReason)
            assertEquals(300, inputTokens)
            assertEquals(1_000, cacheReadTokens)
            assertEquals(40, outputTokens)
            assertNull(error)
        }
        assertEquals(listOf("thinking", "text"), fake.requests[1].json.path("messages")[1].path("content").types(), "sent back without the fallback block")
        assertEquals(4, conversation.size)
        assertTrue(fake.rejections.isEmpty(), "${fake.rejections}")

        val warning = logs.at(Level.WARN).single { "server-side fallback" in it }
        listOf(conversation.id, "claude-opus-5-5 → claude-opus-4-8 (refusal, cyber)", "requested claude-opus-5-5, answered by claude-opus-4-8")
            .forEach { assertContains(warning, it) }
        val lines = transcript(conversation)
        val response = lines.first { it.path("kind").asText() == "response" }.path("data")
        assertEquals(listOf("fallback", "thinking", "text"), response.path("content").types(), "the transcript keeps the block")
        assertEquals("claude-opus-4-8", response.path("model").asText())
        assertEquals("claude-opus-4-8", response.path("narrator2_stats").path("model").asText())
        assertTrue(response.path("narrator2_appended").asBoolean())
        assertContains(lines.single { it.path("kind").asText() == "note" }.path("data").path("text").asText(), "answered by claude-opus-4-8")
        assertContains(markdown(conversation), "\"type\" : \"fallback\"")
    }

    @Test
    fun `after a fallback mid-stream, the declined model's thinking and tool call are left out, and only the fallback model's call runs`() {
        lateinit var answered: String
        fake.enqueue(
            reply {
                thinking("Partial reasoning.")
                text("Let me")
                truncatedToolUse("say", """{"text": "Hel""")
                fallback()
                thinking("Fallback reasoning.")
                answered = toolUse("say", """{"text": "Non."}""")
                stopReason("tool_use")
            },
        )
        fake.enqueue(reply { text("Done.") })
        val conversation = testConversation(tools = listOf(SAY_TOOL))

        fake.client().use { client ->
            runBlocking {
                val reply = assertIs<SendResult.Reply>(client.send(conversation), "a call cut by the declined model is not a truncated reply")
                assertEquals(listOf(answered), reply.toolUses.map { it.id() })
                assertEquals("claude-opus-4-8", reply.stats.model, "the fallback block names the model that answered")
                conversation.append(toolResult(answered, "message sent"))
                assertEquals("Done.", client.send(conversation).text)
            }
        }

        val replayed = fake.requests[1].json.path("messages")[1].path("content")
        assertEquals(listOf("text", "thinking", "tool_use"), replayed.types())
        assertEquals("Let me", replayed[0].path("text").asText())
        assertEquals("Fallback reasoning.", replayed[1].path("thinking").asText())
        assertEquals(answered, replayed[2].path("id").asText())
        assertTrue(fake.rejections.isEmpty(), "${fake.rejections}")
    }

    @Test
    fun `a fallback with neither text nor a tool call is a refusal with its category, and nothing is committed`() {
        fake.enqueue(reply { fallback(); thinking("Nothing to say.") }, reply { fallback(category = "bio") })
        fake.enqueue(reply { text("Answered.") })
        val conversation = testConversation(transcripts = Transcripts(dir, enabled = true))

        fake.client().use { client ->
            runBlocking {
                val thinkingOnly = assertIs<SendResult.Refused>(client.send(conversation))
                assertEquals("cyber", thinkingOnly.category)
                assertEquals("end_turn", thinkingOnly.stats.stopReason)
                assertEquals("claude-opus-4-8", thinkingOnly.stats.model)
                assertEquals("bio", assertIs<SendResult.Refused>(client.send(conversation)).category)
                assertEquals(1, conversation.size, "nothing committed")
                assertIs<SendResult.Reply>(client.send(conversation))
            }
        }
        val responses = transcript(conversation).filter { it.path("kind").asText() == "response" }.map { it.path("data") }
        assertEquals(listOf("refusal", "refusal"), responses.take(2).map { it.path("narrator2_not_committed").asText() })
        assertTrue(fake.rejections.isEmpty(), "${fake.rejections}")
    }

    @Test
    fun `a conversation already moved to the fallback model is measured as that model, without any block`() {
        fake.enqueue(reply { model("claude-opus-4-8"); text("Still me.") })
        val result = fake.client().use { client -> runBlocking { client.send(testConversation()) } }

        assertIs<SendResult.Reply>(result)
        assertEquals("claude-opus-4-8", result.stats.model)
        assertTrue(result.fallbacks.isEmpty())
        assertNull(result.stats.refusalCategory)
    }

    @Test
    fun `a block the SDK does not know is committed and sent back as the API returned it`() {
        val block = """{"type": "future_marker", "note": "kept", "nested": {"n": 1}}"""
        fake.enqueue(reply { text("Hello."); block(block) })
        fake.enqueue(reply { text("Again.") })
        val conversation = testConversation()

        fake.client().use { client ->
            runBlocking {
                assertIs<SendResult.Reply>(client.send(conversation))
                conversation.appendUser("And?")
                assertIs<SendResult.Reply>(client.send(conversation))
            }
        }

        val replayed = fake.requests[1].json.path("messages")[1].path("content")
        assertEquals(listOf("text", "future_marker"), replayed.types())
        assertEquals(mapper.readTree(block), replayed[1])
        assertTrue(fake.rejections.isEmpty(), "${fake.rejections}")
    }
}
