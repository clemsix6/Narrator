package io.github.clemsix6.narrator2.trace

import com.anthropic.core.jsonMapper
import com.fasterxml.jackson.databind.JsonNode
import io.github.clemsix6.narrator2.llm.Conversation
import io.github.clemsix6.narrator2.testing.FakeAnthropic
import io.github.clemsix6.narrator2.testing.SAY_TOOL
import io.github.clemsix6.narrator2.testing.TEST_API_KEY
import io.github.clemsix6.narrator2.testing.client
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
import java.time.OffsetDateTime
import kotlin.io.path.exists
import kotlin.io.path.name
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

    /**
     * The transcript files of [conversation], oldest date folder first: a test running across midnight
     * may start a second file in the next day's folder.
     */
    private fun transcripts(conversation: Conversation, extension: String): List<Path> =
        Files.walk(dir).use { paths -> paths.filter { it.name == "${conversation.id}.$extension" }.sorted().toList() }
            .also { assertTrue(it.isNotEmpty(), "no .$extension transcript for ${conversation.id}") }

    private fun markdown(conversation: Conversation): String = transcripts(conversation, "md").joinToString("") { it.readText() }

    private fun jsonl(conversation: Conversation): List<JsonNode> =
        transcripts(conversation, "jsonl").flatMap { Files.readAllLines(it) }.map { mapper.readTree(it) }

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
        val conversation = testConversation(tools = listOf(SAY_TOOL), user = "Steve: hello!", label = "Test chapter", transcripts = transcripts)
        fake.client().use { client ->
            runBlocking {
                client.send(conversation)
                conversation.append(toolResult(toolUseId, "message sent"))
                client.send(conversation)
            }
        }
        return conversation to toolUseId
    }

    @Test
    fun `writes an exact JSONL transcript per conversation, in a dated folder, with zoned timestamps`() {
        val (conversation, _) = playToolRoundTrip(Transcripts(dir, enabled = true))

        val file = transcripts(conversation, "jsonl").first()
        val date = LocalDate.parse(file.parent.name)
        assertTrue(date == LocalDate.now() || date == LocalDate.now().minusDays(1), "dated folder: $date")
        val lines = jsonl(conversation)
        assertEquals(
            listOf("conversation", "message", "request", "response", "message", "request", "response"),
            lines.map { it.path("kind").asText() },
        )
        lines.forEach { OffsetDateTime.parse(it.path("ts").asText()) }

        val header = lines[0].path("data")
        assertEquals(conversation.id, header.path("conversation").asText())
        assertEquals("director", header.path("role").asText())
        assertEquals("Test chapter", header.path("label").asText())
        assertEquals("You are the Narrator.", header.path("system").path(0).path("text").asText())
        assertEquals("say", header.path("tools").path(0).path("name").asText())

        assertEquals("Steve: hello!", lines[1].path("data").path("content").asText())
        with(lines[2].path("data")) {
            assertEquals("claude-opus-5-5", path("model").asText())
            assertEquals("medium", path("output_config").path("effort").asText())
            assertTrue(path("cache_control").isMissingNode, "no cache marker by default")
            assertEquals("adaptive", path("thinking").path("type").asText())
            assertEquals(conversation.id, path("headers").path("X-Session-ID").asText())
            assertEquals(1, path("message_count").asInt())
            assertTrue(path("messages").isMissingNode, "messages are transcribed one by one, not with each request")
            assertFalse(TEST_API_KEY in toString())
        }
        val response = lines[3].path("data")
        assertEquals(listOf("thinking", "text", "tool_use"), response.path("content").map { it.path("type").asText() })
        assertTrue(response.path("narrator2_appended").asBoolean())
        with(response.path("narrator2_stats")) {
            assertEquals(conversation.id, path("conversation").asText())
            assertEquals("director", path("role").asText())
            assertEquals("claude-opus-5-5", path("model").asText())
            assertEquals(1, path("attempts").asInt())
            assertTrue(path("request_id").asText().startsWith("req_fake_"))
            assertEquals(response.path("id").asText(), path("message_id").asText())
            assertTrue(path("time_to_first_token_ms").isNumber)
            assertTrue(path("duration_ms").isNumber)
            OffsetDateTime.parse(path("started_at").asText())
            assertEquals(50, path("input_tokens").asLong())
            assertEquals(1_000, path("cache_read_tokens").asLong())
            assertEquals(20, path("output_tokens").asLong())
            assertEquals(1_050, path("context_tokens").asLong())
            assertEquals("tool_use", path("stop_reason").asText())
            assertTrue(path("refusal_category").isNull)
        }
        // The assistant turn is written once, as a response, never again as a history message.
        assertEquals("tool_result", lines[4].path("data").path("content").path(0).path("type").asText())
        assertEquals(3, lines[5].path("data").path("message_count").asInt())
        assertEquals("Done.", lines[6].path("data").path("content").path(0).path("text").asText())
    }

    @Test
    fun `writes a readable Markdown transcript, in order`() {
        val (conversation, toolUseId) = playToolRoundTrip(Transcripts(dir, enabled = true))

        val markdown = markdown(conversation)
        val expectedInOrder = listOf(
            "# Test chapter",
            "`${conversation.id}` · role `director` · started ",
            "Tools: `say`",
            "<details><summary>System prompt</summary>",
            "You are the Narrator.",
            " · RECEIVED",
            "Steve: hello!",
            " · REQUEST — `claude-opus-5-5` · effort `medium` · 1 messages",
            " · CLAUDE — ",
            "input 50 (+1000 cache read, 0 cache write) · output 20 · tool_use",
            "1 attempt · request req_fake_",
            "> Steve says hello.\n> Answer with the say tool.",
            "*Text:* I will answer him.",
            "**→ say** `$toolUseId`",
            "\"text\" : \"Hello, mortal.\"",
            "**← result** `$toolUseId`",
            "message sent",
            " · REQUEST — ",
            "*Text:* Done.",
        )
        var from = 0
        expectedInOrder.forEach { expected ->
            val at = markdown.indexOf(expected, from)
            assertTrue(at >= 0, "missing or out of order: '$expected' in\n$markdown")
            from = at + expected.length
        }
        assertTrue(Regex("""started \d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} [+-]\d{2}:\d{2}""").containsMatchIn(markdown), "zoned start")
        assertEquals(1, Regex("Steve: hello!").findAll(markdown).count(), "history already written is not repeated")
        assertEquals(1, Regex("I will answer him").findAll(markdown).count(), "the assistant turn is written once")
    }

    @Test
    fun `notes are appended to the transcript`() {
        val transcripts = Transcripts(dir, enabled = true)
        val (conversation, _) = playToolRoundTrip(transcripts)
        transcripts.note(conversation, "woken up by the chat")

        assertContains(markdown(conversation), "· woken up by the chat*")
        assertEquals("note", jsonl(conversation).last().path("kind").asText())
    }

    @Test
    fun `close forgets the conversation`() {
        val transcripts = Transcripts(dir, enabled = true)
        val conversation = testConversation(transcripts = transcripts)
        conversation.close()
        transcripts.note(conversation, "after close")

        val kinds = jsonl(conversation).map { it.path("kind").asText() }
        assertEquals(listOf("conversation", "message", "conversation", "note"), kinds, "a write after close starts afresh")
    }

    @Test
    fun `writes nothing when disabled`() {
        playToolRoundTrip(Transcripts(dir, enabled = false))
        assertFalse(Files.list(dir).use { it.findAny().isPresent })
    }

    @Test
    fun `a transcript that cannot be written never breaks the call`() {
        val blocker = dir.resolve("not-a-directory")
        Files.writeString(blocker, "")
        fake.enqueue(reply { text("Still here.") })
        val conversation = testConversation(transcripts = Transcripts(blocker, enabled = true))

        val reply = fake.client().use { client -> runBlocking { client.send(conversation) } }
        assertEquals("Still here.", reply.text)
        assertEquals(2, conversation.size)
        assertFalse(blocker.resolve(LocalDate.now().toString()).exists())
    }
}
