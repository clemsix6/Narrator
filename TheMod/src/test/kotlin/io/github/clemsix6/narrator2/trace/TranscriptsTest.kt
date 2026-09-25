package io.github.clemsix6.narrator2.trace

import com.anthropic.core.jsonMapper
import io.github.clemsix6.narrator2.llm.ClaudeClient
import io.github.clemsix6.narrator2.llm.Conversation
import io.github.clemsix6.narrator2.testing.FakeAnthropic
import io.github.clemsix6.narrator2.testing.SAY_TOOL
import io.github.clemsix6.narrator2.testing.narratorConfig
import io.github.clemsix6.narrator2.testing.reply
import io.github.clemsix6.narrator2.testing.testConversation
import io.github.clemsix6.narrator2.testing.toolResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TranscriptsTest {
    @TempDir
    lateinit var dir: Path

    private val fake = FakeAnthropic()
    private val mapper = jsonMapper()

    @AfterEach
    fun closeFake() = fake.close()

    /** Plays a two-turn exchange: a tool call, its result, then a closing reply. */
    private fun playToolRoundTrip(transcripts: Transcripts): Pair<Conversation, String> {
        lateinit var toolUseId: String
        fake.enqueue(
            reply {
                thinking("Steve says hello.\nAnswer with the say tool.")
                text("I will answer him.")
                toolUseId = toolUse("say", """{"text": "Hello, mortal."}""")
                usage(input = 50, output = 20, cacheRead = 1_000)
            },
        )
        fake.enqueue(reply { text("Done.") })
        val conversation = testConversation(tools = listOf(SAY_TOOL), user = "Steve: hello!", label = "Test chapter")
        ClaudeClient(fake.narratorConfig(), transcripts).use { client ->
            runBlocking {
                client.send(conversation)
                conversation.append(toolResult(toolUseId, "message sent"))
                client.send(conversation)
            }
        }
        return conversation to toolUseId
    }

    @Test
    fun `writes an exact JSONL transcript per conversation, dated`() {
        val (conversation, _) = playToolRoundTrip(Transcripts(dir, enabled = true))

        val file = dir.resolve(LocalDate.now().toString()).resolve("${conversation.id}.jsonl")
        val lines = Files.readAllLines(file).map { mapper.readTree(it) }
        assertEquals(listOf("conversation", "message", "response", "message", "response"), lines.map { it.path("kind").asText() })
        assertTrue(lines.all { it.path("ts").asText().isNotEmpty() })

        val header = lines[0].path("data")
        assertEquals(conversation.id, header.path("conversation").asText())
        assertEquals("director", header.path("role").asText())
        assertEquals("Test chapter", header.path("label").asText())
        assertEquals("claude-opus-5-5", header.path("model").asText())
        assertEquals("medium", header.path("effort").asText())
        assertEquals("You are the Narrator.", header.path("system").path(0).path("text").asText())
        assertEquals("say", header.path("tools").path(0).path("name").asText())

        assertEquals("Steve: hello!", lines[1].path("data").path("content").asText())
        val response = lines[2].path("data")
        assertEquals(listOf("thinking", "text", "tool_use"), response.path("content").map { it.path("type").asText() })
        with(response.path("narrator2_stats")) {
            assertEquals("claude-opus-5-5", path("model").asText())
            assertEquals(50, path("input_tokens").asLong())
            assertEquals(1_000, path("cache_read_tokens").asLong())
            assertEquals(20, path("output_tokens").asLong())
            assertEquals(1_050, path("context_tokens").asLong())
            assertEquals("tool_use", path("stop_reason").asText())
            assertTrue(has("latency_ms"))
        }
        // The assistant turn is written once, as a response, never again as a history message.
        assertEquals("tool_result", lines[3].path("data").path("content").path(0).path("type").asText())
        assertEquals("Done.", lines[4].path("data").path("content").path(0).path("text").asText())
    }

    @Test
    fun `writes a readable Markdown transcript, in order`() {
        val (conversation, toolUseId) = playToolRoundTrip(Transcripts(dir, enabled = true))

        val markdown = dir.resolve(LocalDate.now().toString()).resolve("${conversation.id}.md").readText()
        val expectedInOrder = listOf(
            "# Test chapter",
            "`${conversation.id}` · started ",
            "`claude-opus-5-5` · effort `medium`",
            "Tools: `say`",
            "<details><summary>System prompt</summary>",
            "You are the Narrator.",
            " · RECEIVED",
            "Steve: hello!",
            " · CLAUDE — ",
            "input 50 (+1000 cache read, 0 cache write) · output 20 · tool_use",
            "> Steve says hello.\n> Answer with the say tool.",
            "*Text:* I will answer him.",
            "**→ say** `$toolUseId`",
            "\"text\" : \"Hello, mortal.\"",
            "**← result** `$toolUseId`",
            "message sent",
            "*Text:* Done.",
        )
        var from = 0
        expectedInOrder.forEach { expected ->
            val at = markdown.indexOf(expected, from)
            assertTrue(at >= 0, "missing or out of order: '$expected' in\n$markdown")
            from = at + expected.length
        }
        assertEquals(1, Regex("Steve: hello!").findAll(markdown).count(), "history already written is not repeated")
        assertEquals(1, Regex("I will answer him").findAll(markdown).count(), "the assistant turn is written once")
    }

    @Test
    fun `notes are appended to the transcript`() {
        val transcripts = Transcripts(dir, enabled = true)
        val (conversation, _) = playToolRoundTrip(transcripts)
        transcripts.note(conversation, "woken up by the chat")

        val file = dir.resolve(LocalDate.now().toString()).resolve("${conversation.id}")
        assertContains(Path.of("$file.md").readText(), "· woken up by the chat*")
        assertContains(Path.of("$file.jsonl").readText(), "\"kind\":\"note\"")
    }

    @Test
    fun `writes nothing when disabled`() {
        playToolRoundTrip(Transcripts(dir, enabled = false))
        assertFalse(dir.resolve(LocalDate.now().toString()).exists())
    }

    @Test
    fun `a transcript that cannot be written never breaks the call`() {
        val blocker = dir.resolve("not-a-directory")
        Files.writeString(blocker, "")
        fake.enqueue(reply { text("Still here.") })
        val conversation = testConversation()

        val reply = ClaudeClient(fake.narratorConfig(), Transcripts(blocker, enabled = true)).use { client ->
            runBlocking { client.send(conversation) }
        }
        assertEquals("Still here.", reply.text)
        assertEquals(2, conversation.size)
    }
}
