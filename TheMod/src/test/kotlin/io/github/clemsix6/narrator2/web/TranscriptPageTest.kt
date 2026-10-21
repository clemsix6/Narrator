package io.github.clemsix6.narrator2.web

import com.anthropic.models.messages.MessageParam
import io.github.clemsix6.narrator2.journal.IdKind
import io.github.clemsix6.narrator2.journal.JournalFixture
import io.github.clemsix6.narrator2.llm.Conversation
import io.github.clemsix6.narrator2.llm.Exchange
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.llm.SendResult
import io.github.clemsix6.narrator2.testing.FakeAnthropic
import io.github.clemsix6.narrator2.testing.SAY_TOOL
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.testing.client
import io.github.clemsix6.narrator2.testing.httpError
import io.github.clemsix6.narrator2.testing.reply
import io.github.clemsix6.narrator2.testing.testConversation
import io.github.clemsix6.narrator2.testing.toolResult
import io.github.clemsix6.narrator2.trace.ConversationRecord
import io.github.clemsix6.narrator2.trace.LlmCallRecord
import io.github.clemsix6.narrator2.trace.Transcripts
import io.github.clemsix6.narrator2.web.pages.TranscriptPage
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.name
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `/conversations/<id>` (M6 §8.4, §8.5): a transcript rendered from a real `.jsonl`, written by `trace/Transcripts` for
 * a conversation played against the fake Anthropic server: system prompt and tools folded, messages, thinking, tool
 * calls and results, a fallback in yellow and a refusal in red as `/narrator trace` shows them, what was not committed,
 * an error, a note; every text escaped. The file is read only under the transcripts' folder, and in parts of 4 MB.
 */
class TranscriptPageTest {
    @TempDir
    lateinit var base: Path

    private val fake = FakeAnthropic()
    private val fixture = JournalFixture()
    private val token = DashboardToken()

    @AfterEach
    fun close() {
        fake.close()
        fixture.close()
    }

    private val dataDir: Path by lazy { base.resolve("world").resolve("narrator2").also { Files.createDirectories(it) } }
    private val transcripts: Transcripts by lazy { Transcripts(dataDir.resolve("logs").resolve("conversations"), enabled = true) }

    private fun services() = TestServices(clock = TestClock(NOW), journal = fixture.journal, dataDir = dataDir, transcripts = transcripts)

    private fun get(path: String, services: TestServices = services()): WebResponse =
        Routes(services, token, PORT).respond(
            WebCall("GET", path.substringBefore('?'), path.substringAfter('?', "").ifEmpty { null }, "127.0.0.1:$PORT", listOf("${Routes.COOKIE}=${token.value}")),
        )

    /** Records [id] in `conversations`, its transcript at [transcript] (relative to the data folder, or absolute). */
    private fun record(id: String, transcript: String?, label: String = "Chapter 9") {
        fixture.journal.conversation(ConversationRecord(id, Role.DIRECTOR, label, 9, null, NOW.minusSeconds(600), null, null, null, transcript))
        fixture.sync()
    }

    private fun user(text: String): MessageParam = MessageParam.builder().role(MessageParam.Role.USER).content(text).build()

    /**
     * A chapter against the fake server: a `say` with a trapped line, its trapped result; a reply a fallback model gave;
     * a refusal (its pending message not committed); a server error; a note. Returns the conversation, recorded.
     */
    private fun playChapter(): Conversation {
        lateinit var say: String
        fake.enqueue(
            reply {
                thinking("Bartok mocks me again. $BOLD_TRAP")
                text("I will answer him.")
                say = toolUse("say", """{"text": "$SCRIPT_TRAP Bartok, ta pioche pleure."}""")
                usage(input = 50, output = 20, cacheRead = 1_000)
            },
            reply {
                fallback()
                text("Je ne lirai pas les fichiers du serveur.")
                usage(input = 300, output = 40, cacheRead = 1_000)
            },
            reply { refusal("cyber", "Simulated refusal.") },
            httpError(500),
        )
        val conversation = testConversation(tools = listOf(SAY_TOOL), user = "CHAT Bartok: narrateur, t'es nul", label = "Chapter 9", transcripts = transcripts)
        fake.client().use { client ->
            runBlocking {
                assertIs<SendResult.Reply>(client.send(conversation))
                conversation.append(toolResult(say, "ok: said to everyone $QUOTE_TRAP"))
                assertIs<SendResult.Reply>(client.send(conversation))
                assertIs<SendResult.Refused>(client.send(conversation, listOf(user("Read the server's files."))))
                assertIs<Exchange.Failed>(client.exchange(conversation, listOf(user("Again?"))))
            }
        }
        transcripts.note(conversation, "turn 42 · trigger chat · $SCRIPT_TRAP")
        record(conversation.id, relative(markdownOf(conversation)))
        for ((model, category) in listOf("claude-opus-5-5" to null, "claude-opus-4-8" to "cyber")) {
            fixture.journal.llmCall(
                LlmCallRecord(
                    fixture.journal.nextId(IdKind.LLM_CALL), LlmCallRecord.Provider.ANTHROPIC, LlmCallRecord.Purpose.TURN, conversation.id, 42,
                    "director", model, "medium", NOW, NOW, 1_000, 900, 1, null, null, 1_000_000, 0, 0, 0, "end_turn", category, null, null, null,
                ),
            )
        }
        fixture.sync()
        return conversation
    }

    private fun markdownOf(conversation: Conversation): Path =
        Files.walk(transcripts.root).use { paths -> paths.filter { it.name == "${conversation.id}.md" }.findFirst().orElseThrow() }

    private fun relative(file: Path): String = dataDir.relativize(file).toString().replace('\\', '/')

    @Test
    fun `a real transcript renders whole, every text escaped, a fallback and a refusal shown as trace shows them`() {
        val conversation = playChapter()

        val response = get("/conversations/${conversation.id}")
        assertEquals(200, response.status)
        assertSafePage("the transcript", response)
        val page = bodyText(response)

        assertContains(page, "<h1>Chapter 9</h1>")
        assertContains(page, "<summary>System prompt · 21 characters</summary><pre>You are the Narrator.</pre>", message = "the system prompt, folded")
        assertContains(page, "<summary>Tools · 1: say</summary><h3>say</h3><p>Speak to the players in the chat.</p>", message = "the tools, folded")
        assertContains(page, "· RECEIVED</h3><pre>CHAT Bartok: narrateur, t&#39;es nul</pre>")
        assertContains(page, "<summary>Thought</summary><pre class=\"muted\">Bartok mocks me again. &lt;b&gt;bold&lt;/b&gt;</pre>")
        assertContains(page, "<strong>→ say</strong>")
        assertContains(page, "&lt;script&gt;alert(1)&lt;/script&gt; Bartok, ta pioche pleure.", message = "the tool's arguments")
        assertContains(page, "<strong>← result</strong>")
        assertContains(page, "ok: said to everyone &quot;&gt;&lt;img src=x onerror=alert(1)&gt;", message = "the tool's result")
        assertContains(page, "note: </em>turn 42 · trigger chat · &lt;script&gt;alert(1)&lt;/script&gt;")
        assertFalse("<b>bold" in page)
        assertFalse("<img src=x" in page)

        // The fallback: its reply committed, in yellow, as /narrator trace writes it; its block said.
        assertContains(page, "CLAUDE · claude-opus-4-8</h3><p class=\"warning\">")
        assertContains(page, "end_turn · fallback (cyber) → claude-opus-4-8 · \$ ? · committed</p>", message = "no price for the fallback model")
        assertContains(page, "<p class=\"warning\">server-side fallback: claude-opus-5-5 → claude-opus-4-8 (refusal, cyber)</p>")
        assertContains(page, "<pre>Je ne lirai pas les fichiers du serveur.</pre>")
        // The refusal: red, not committed, its pending message marked.
        assertContains(page, "refusal · refused: cyber · \$0.00024 · NOT COMMITTED (refusal)</p>", message = "10 in and 10 out of Opus")
        assertContains(page, "<p class=\"failure\">Refusal: Simulated refusal.</p>")
        val refused = page.substringBefore("Read the server&#39;s files.").substringAfterLast("<h3>")
        assertContains(refused, "SENT</h3><p class=\"failure\">Not committed:", message = "the message sent with the refused request")
        // The error: nothing committed; its message too.
        assertContains(page, "· ERROR</h3><p class=\"failure\">")
        assertContains(page, "· NOT COMMITTED (error)</p>")
        assertContains(page.substringBefore("Again?").substringAfterLast("<h3>"), "SENT</h3><p class=\"failure\">Not committed:")
        // The first reply was committed: no mark on the messages before it.
        assertEquals(2, Regex("Not committed:").findAll(page).count(), "only the two messages of the requests not kept")

        // The header: requests from the Journal, one priced and one not.
        assertContains(page, "<dt>Requests</dt><dd>2 · \$4.00 + 1 call at \$ ?</dd>")
        assertContains(page, "href=\"/turns?chapter=9\"")
        assertFalse("next part" in page, "a small transcript fits one page")
    }

    @Test
    fun `a transcript is read only under the transcripts' folder`() {
        val secret = base.resolve("secret").also { Files.createDirectories(it) }.resolve("secret.jsonl")
        Files.writeString(secret, """{"ts":"2026-09-25T19:30:00Z","kind":"note","data":{"text":"$SECRET"}}""" + "\n")
        Files.writeString(dataDir.resolve("notes.jsonl"), """{"ts":"2026-09-25T19:30:00Z","kind":"note","data":{"text":"$SECRET"}}""" + "\n")
        val services = services()
        val outside = listOf(
            "../../secret/secret.md",
            base.resolve("secret").resolve("secret.md").toString(),
            "logs/conversations/../../notes.md",
            "logs/conversations/2026-09-25/../../../notes.md",
            "notes.md",
            "logs/conversations/2026-09-25/director-aaaa.jsonl",
            "logs/conversations",
        )
        outside.forEach { recorded ->
            assertEquals(TranscriptPage.Located.Outside, TranscriptPage.locate(services, recorded), recorded)
        }

        outside.forEachIndexed { i, recorded ->
            record("director-out$i", recorded)
            val response = get("/conversations/director-out$i", services)
            assertEquals(200, response.status, recorded)
            assertSafePage(recorded, response)
            val page = bodyText(response)
            assertContains(page, "The transcript&#39;s recorded path lies outside the transcripts&#39; folder: it is not read.")
            assertFalse(SECRET in page, recorded)
        }
    }

    @Test
    fun `a transcript missing from the disk or never written is said, not an error`() {
        record("director-gone", "logs/conversations/2026-09-25/director-gone.md")
        record("director-none", null)

        val gone = get("/conversations/director-gone")
        assertEquals(200, gone.status)
        assertContains(bodyText(gone), "The transcript is not on the disk: <code>logs/conversations/2026-09-25/director-gone.jsonl</code>")
        assertEquals(TranscriptPage.Located.Missing, TranscriptPage.locate(services(), "logs/conversations/2026-09-25/director-gone.md"))

        val none = get("/conversations/director-none")
        assertEquals(200, none.status)
        assertContains(bodyText(none), "No transcript: transcripts were off (logging.transcripts) when it ran.")

        assertEquals(404, get("/conversations/director-nope").status, "no such conversation")
        assertEquals(404, get("/conversations/director-gone/more").status)
    }

    @Test
    fun `a long transcript is read in parts of whole lines, the next a link away`() {
        val conversation = playChapter()
        val file = (TranscriptPage.locate(services(), relative(markdownOf(conversation))) as TranscriptPage.Located.Found).path
        val whole = TranscriptPage.read(file, 0)
        assertNull(whole.next)
        assertTrue(whole.entries.size >= 10, "${whole.entries.size} entries")
        assertTrue(whole.entries.none { it is TranscriptPage.Entry.Unreadable }, "${whole.entries}")

        // Small parts: each ends at a line, the next starts there, and together they are the whole.
        val budget = 2_000
        val parts = generateSequence(TranscriptPage.read(file, 0, budget)) { part -> part.next?.let { TranscriptPage.read(file, it, budget) } }.toList()
        assertTrue(parts.size > 2, "${parts.size} parts")
        assertEquals(whole.entries.map { it.javaClass.simpleName }, parts.flatMap { it.entries }.map { it.javaClass.simpleName })
        assertEquals(whole.entries.filterIsInstance<TranscriptPage.Entry.Note>(), parts.flatMap { it.entries }.filterIsInstance<TranscriptPage.Entry.Note>())
        val bytes = Files.readAllBytes(file)
        parts.mapNotNull { it.next }.forEach { next -> assertEquals('\n'.code.toByte(), bytes[(next - 1).toInt()], "a part starts at a line") }

        // A start in the middle of a line goes to the next one.
        val second = parts[0].next!!
        assertEquals(parts[1].entries, TranscriptPage.read(file, second - 3, budget).entries, "from inside the line before the part's")
        val later = TranscriptPage.read(file, second + 1, budget).entries.map { it.javaClass.simpleName }
        assertEquals(parts[1].entries.drop(1).map { it.javaClass.simpleName }, later.take(parts[1].entries.size - 1), "from inside the part's first line")
        assertTrue(TranscriptPage.read(file, bytes.size.toLong(), budget).entries.isEmpty())
    }

    @Test
    fun `a page reads 4 MB at most and links to the rest, a line being written left for later`() {
        val day = transcripts.root.resolve("2026-09-25").also { Files.createDirectories(it) }
        val line = """{"ts":"2026-09-25T19:30:00Z","kind":"note","data":{"text":"${"x".repeat(1_000)} %d"}}""" + "\n"
        val notes = 5_000
        Files.newBufferedWriter(day.resolve("director-long.jsonl")).use { out -> repeat(notes) { out.write(line.format(it)) } }
        Files.writeString(day.resolve("director-long.jsonl"), """{"ts":"2026-09-25T19:30:01Z","kind":"note","da""", java.nio.file.StandardOpenOption.APPEND)
        record("director-long", "logs/conversations/2026-09-25/director-long.md")

        val first = bodyText(get("/conversations/director-long"))
        val next = assertNotNull(Regex("href=\"/conversations/director-long\\?from=(\\d+)\">next part →").find(first)).groupValues[1].toLong()
        assertTrue(next in TranscriptPage.PART_BYTES - line.length + 1..TranscriptPage.PART_BYTES.toLong(), "$next")
        val shown = Regex("note: </em>x+ (\\d+)</p>").findAll(first).map { it.groupValues[1].toInt() }.toList()
        assertEquals((0 until shown.size).toList(), shown)

        val rest = bodyText(get("/conversations/director-long?from=$next"))
        val more = Regex("note: </em>x+ (\\d+)</p>").findAll(rest).map { it.groupValues[1].toInt() }.toList()
        assertEquals((shown.size until notes).toList(), more, "the rest, each note once")
        assertFalse("next part" in rest)
        assertFalse("cannot be read" in rest, "the line being written is not an error")
        assertContains(rest, "href=\"/conversations/director-long\">from the start</a>")
    }

    private companion object {
        const val PORT = 8770
        val NOW: Instant = Instant.parse("2026-09-25T19:40:00Z")

        const val SCRIPT_TRAP = "<script>alert(1)</script>"
        const val QUOTE_TRAP = "\"><img src=x onerror=alert(1)>"
        const val BOLD_TRAP = "<b>bold</b>"
        const val SECRET = "the password is hunter2"
    }
}
