package io.github.clemsix6.narrator2.agent

import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUnion
import com.anthropic.models.messages.ToolUseBlock
import com.fasterxml.jackson.databind.JsonNode
import io.github.clemsix6.narrator2.config.OperatorChannelMode
import io.github.clemsix6.narrator2.llm.ClaudeClient
import io.github.clemsix6.narrator2.llm.Conversation
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.testing.FakeAnthropic
import io.github.clemsix6.narrator2.testing.Hold
import io.github.clemsix6.narrator2.testing.RecordedRequest
import io.github.clemsix6.narrator2.testing.SAY_TOOL
import io.github.clemsix6.narrator2.testing.TEST_API_KEY
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.testing.budgetExceeded
import io.github.clemsix6.narrator2.testing.httpError
import io.github.clemsix6.narrator2.testing.reply
import io.github.clemsix6.narrator2.testing.testConfig
import io.github.clemsix6.narrator2.testing.testSettings
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.NoArgs
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolExecutor
import io.github.clemsix6.narrator2.tool.TestTool
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolRunner
import io.github.clemsix6.narrator2.tool.ToolSet
import io.github.clemsix6.narrator2.tool.noArgsTool
import io.github.clemsix6.narrator2.tool.of
import io.github.clemsix6.narrator2.trace.CallStats
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.LlmCallRecord
import io.github.clemsix6.narrator2.trace.Transcripts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentLoopTest {
    @TempDir
    lateinit var dir: Path

    private val fake = FakeAnthropic()
    private val executor = FakeExecutor()
    private val mapper = jsonMapper()
    private var client: ClaudeClient? = null

    @AfterEach
    fun close() {
        client?.close()
        fake.close()
    }

    /** Services whose client talks to the fake server, with [mode] as `director.operatorChannel`. */
    private fun services(mode: OperatorChannelMode = OperatorChannelMode.SYSTEM_MESSAGE, withClient: Boolean = true): TestServices {
        val base = testConfig(fake.baseUrl)
        val config = base.copy(director = base.director.copy(operatorChannel = mode.id))
        client = if (withClient) ClaudeClient(config, TEST_API_KEY) else null
        return TestServices(dataDir = dir, settings = testSettings(config, client))
    }

    private fun conversation(tools: ToolSet = TOOLS) =
        Conversation(Role.DIRECTOR, "Chapter 1", listOf(TextBlockParam.builder().text("You are the Narrator.").build()), tools, Transcripts(dir, enabled = true))

    private fun caller(conversation: Conversation, taskId: Long? = null) = Caller(Role.DIRECTOR, conversation.id, turnId = 42, taskId = taskId)

    private fun AgentLoop.run(
        conversation: Conversation,
        opening: Pending,
        limits: TurnLimits = TurnLimits(12),
        hooks: TurnHooks = TurnHooks.NONE,
        caller: Caller = caller(conversation),
    ): TurnOutcome = runBlocking { runTurn(conversation, opening, caller, limits, hooks) }

    private fun hooks(afterTools: () -> Pending) = object : TurnHooks {
        override suspend fun afterTools(canAct: Boolean) = afterTools()
    }

    private fun RecordedRequest.messages(): JsonNode = json.path("messages")

    private fun JsonNode.types(): List<String> = map { it.path("type").asText() }

    private fun Conversation.roles(): List<String> = messages.map { it.role().asString() }

    private fun markdown(conversation: Conversation): String =
        Files.walk(dir).use { paths -> paths.filter { it.name == "${conversation.id}.md" }.findFirst().orElseThrow() }.readText()

    private fun jsonl(conversation: Conversation): List<JsonNode> =
        Files.walk(dir).use { paths -> paths.filter { it.name == "${conversation.id}.jsonl" }.findFirst().orElseThrow() }
            .let { Files.readAllLines(it) }.map { mapper.readTree(it) }

    // --- S10 and the basic loop ---

    @Test
    fun `S10 - two tool calls in one reply are answered in one user message, in order`() {
        val loop = AgentLoop(services(), executor)
        lateinit var look: String
        lateinit var say: String
        fake.enqueue(reply { thinking("Two things to do."); look = toolUse("look"); say = toolUse("say", """{"text": "Hello"}""") })
        fake.enqueue(reply { text("Done.") })
        val conversation = conversation()

        val outcome = assertIs<TurnOutcome.Ended>(loop.run(conversation, Pending.text("<events>chat</events>")))

        assertEquals(TurnOutcome.Ended(calls = 2, toolCalls = 2, acted = true, finalText = "Done."), outcome)
        assertEquals(listOf("look", "say"), executor.batches.single().map { it.name() })
        val last = fake.requests[1].messages().last()
        assertEquals("user", last.path("role").asText())
        assertEquals(listOf("tool_result", "tool_result"), last.path("content").types())
        assertEquals(listOf(look, say), last.path("content").map { it.path("tool_use_id").asText() })
        assertEquals(listOf("user", "assistant", "user", "assistant"), conversation.roles())
        assertTrue(fake.rejections.isEmpty(), "${fake.rejections}")
    }

    @Test
    fun `each reply is an llm_calls row with its thinking summary, and reaches onReply and the executor`() {
        val services = services()
        val loop = AgentLoop(services, executor)
        fake.enqueue(reply { thinking("Look first."); toolUse("look") })
        fake.enqueue(reply { thinking("x".repeat(5_000)); text("Done.") })
        val replies = CopyOnWriteArrayList<Pair<String, Long>>()
        val hooks = object : TurnHooks {
            override fun onReply(reply: Message, stats: CallStats, llmCallId: Long) {
                replies += reply.id() to llmCallId
            }
        }
        val conversation = conversation()

        loop.run(conversation, Pending.text("Wake up."), hooks = hooks)

        val rows = services.traces.llmCalls
        assertEquals(2, rows.size)
        with(rows[0]) {
            assertEquals(LlmCallRecord.Provider.ANTHROPIC, provider)
            assertEquals(LlmCallRecord.Purpose.TURN, purpose)
            assertEquals(conversation.id, conversationId)
            assertEquals(42L, turnId)
            assertEquals("director", role)
            assertEquals("Look first.", thinking)
            assertEquals("tool_use", stopReason)
            assertEquals(200, httpStatus)
            assertTrue(requestId!!.startsWith("req_fake_"))
            assertNull(error)
        }
        assertEquals(LlmCallRecord.THINKING_MAX_CHARS, rows[1].thinking!!.length, "the thinking summary is truncated")
        assertEquals(rows.map { it.messageId }, replies.map { it.first })
        assertEquals(rows.map { it.id }, replies.map { it.second })
        assertEquals(listOf(rows[0].id), executor.llmCallIds)
    }

    @Test
    fun `with the real ToolRunner, each tool call row points to the llm_calls row of its reply`() {
        val services = services()
        val say = TestTool("say", NoArgs.serializer(), ToolEffect.SPEECH, body = { _, _ -> ToolResult.ok("ok: said") })
        val tools = ToolSet.of(listOf(say, noArgsTool("look")))
        fake.enqueue(reply { toolUse("say"); toolUse("look", """{"radius": 3}""") }, reply { text("Done.") })
        val conversation = conversation(tools)

        val outcome = AgentLoop(services, ToolRunner(services)).run(conversation, Pending.text("Go."))

        assertEquals(TurnOutcome.Ended(calls = 2, toolCalls = 2, acted = true, finalText = "Done."), outcome)
        assertEquals(listOf("look", "say"), fake.requests[0].json.path("tools").map { it.path("name").asText() })
        val results = fake.requests[1].messages().last().path("content")
        assertEquals("ok: said", results[0].path("content").asText())
        assertTrue(results[0].path("is_error").isMissingNode)
        assertTrue(results[1].path("is_error").asBoolean())
        assertContains(results[1].path("content").asText(), "Invalid arguments: unknown argument \"radius\"")
        val rows = services.traces.toolCalls
        assertEquals(listOf("say", "look"), rows.map { it.tool })
        assertTrue(rows.all { it.llmCallId == services.traces.llmCalls[0].id && it.turnId == 42L && it.conversationId == conversation.id })
        assertTrue(fake.rejections.isEmpty(), "${fake.rejections}")
    }

    @Test
    fun `a worker's calls are worker rows`() {
        val services = services()
        fake.enqueue(reply { text("Report.") })
        val conversation = conversation()

        AgentLoop(services, executor).run(conversation, Pending.text("<task id=\"T-1\">…</task>"), caller = caller(conversation, taskId = 1))
        assertEquals(LlmCallRecord.Purpose.WORKER, services.traces.llmCalls.single().purpose)
    }

    @Test
    fun `a turn acts only when a speech, world or task tool succeeds`() {
        val loop = AgentLoop(services(), executor)
        executor.failing = setOf("say")
        fake.enqueue(reply { toolUse("look"); toolUse("say") }, reply { text("Nothing done.") })
        assertFalse(loop.run(conversation(), Pending.text("One.")).acted, "a read and a denied speech")

        fake.enqueue(reply { toolUse("smite") }, reply { text("Smitten.") })
        assertTrue(loop.run(conversation(), Pending.text("Two.")).acted, "a world action")
    }

    // --- S3: operator notes during a turn ---

    private val newEvents = "<new_events now=\"21:33:02\">\n[-4s] CHAT Mimosa (to you 0.88): \"et moi alors ?\"\n</new_events>"
    private val whisper = OperatorNote.operator("Clement", "Leave Mimosa alone.\nShe is new.")

    @Test
    fun `S3 - in system message mode, an operator note follows the tool results as the last message`() {
        val loop = AgentLoop(services(OperatorChannelMode.SYSTEM_MESSAGE), executor)
        lateinit var look: String
        fake.enqueue(reply { look = toolUse("look") }, reply { text("Understood.") })
        val conversation = conversation()

        loop.run(conversation, Pending.text("<events>…</events>"), hooks = hooks { Pending(listOf(ContentBlockParam.ofText(newEvents)), listOf(whisper)) })

        val messages = fake.requests[1].messages()
        val system = messages.last()
        assertEquals("system", system.path("role").asText())
        assertEquals("Operator Clement: Leave Mimosa alone.\nShe is new.", system.path("content").asText())
        val user = messages[messages.size() - 2]
        assertEquals("user", user.path("role").asText())
        assertEquals(listOf("tool_result", "text"), user.path("content").types())
        assertEquals(look, user.path("content")[0].path("tool_use_id").asText())
        assertTrue(user.path("content")[1].path("text").asText().startsWith("<new_events"))
        assertEquals(listOf("user", "assistant", "user", "system", "assistant"), conversation.roles(), "the note is committed with the reply")

        fake.enqueue(reply { text("Next turn.") })
        loop.run(conversation, Pending.text("<events>more</events>"))
        assertTrue(fake.rejections.isEmpty(), "the committed system message is well placed: ${fake.rejections}")
    }

    @Test
    fun `S3 - in user tag mode, the operator note is a tag after the new events`() {
        val loop = AgentLoop(services(OperatorChannelMode.USER_TAG), executor)
        fake.enqueue(reply { toolUse("look") }, reply { text("Understood.") })

        loop.run(conversation(), Pending.text("<events>…</events>"), hooks = hooks { Pending(listOf(ContentBlockParam.ofText(newEvents)), listOf(whisper)) })

        val messages = fake.requests[1].messages()
        assertTrue(messages.none { it.path("role").asText() == "system" })
        val content = messages.last().path("content")
        assertEquals(listOf("tool_result", "text", "text"), content.types())
        assertTrue(content[1].path("text").asText().startsWith("<new_events"))
        assertEquals("<operator from=\"Clement\">Leave Mimosa alone.\nShe is new.</operator>", content[2].path("text").asText())
    }

    // --- S9: step limit ---

    @Test
    fun `S9 - from request 13 tool calls are not executed, and at request 14 the turn stops with the results to carry`() {
        val loop = AgentLoop(services(), executor)
        val ids = List(14) {
            var id = ""
            fake.enqueue(reply { id = toolUse("look") })
            id
        }
        val conversation = conversation()

        val outcome = assertIs<TurnOutcome.StepLimit>(loop.run(conversation, Pending.text("Go."), limits = TurnLimits(12)))

        assertEquals(14, outcome.calls)
        assertEquals(11, outcome.toolCalls)
        assertEquals(11, executor.batches.size, "the tools of replies 1 to 11 ran")
        val carried = outcome.carry.single().asToolResult()
        assertEquals(ids[13], carried.toolUseId())
        assertTrue(carried.isError().orElse(false))
        assertEquals(AgentLoop.STEP_LIMIT_RESULT, carried.content().orElseThrow().asString())

        assertTrue(fake.requests[11].messages().last().path("content")[0].path("is_error").let { it.isMissingNode || !it.asBoolean() })
        for (request in listOf(12, 13)) {
            val messages = fake.requests[request].messages()
            val result = messages[messages.size() - 2].path("content")[0]
            assertEquals(ids[request - 1], result.path("tool_use_id").asText())
            assertTrue(result.path("is_error").asBoolean())
            assertEquals(AgentLoop.STEP_LIMIT_RESULT, result.path("content").asText())
            assertEquals("Engine: ${AgentLoop.STEP_LIMIT_NOTE}", messages.last().path("content").asText())
        }
        assertEquals(14, fake.requests.size)
        assertEquals("assistant", conversation.roles().last(), "the last reply is committed, its results are carried")

        fake.enqueue(reply { text("Summary.") })
        val summary = Pending(outcome.carry, listOf(OperatorNote.engine("Summarize the chapter.")))
        assertIs<TurnOutcome.Ended>(loop.run(conversation, summary))
        assertTrue(fake.rejections.isEmpty(), "the carried results answer the last tool call: ${fake.rejections}")
    }

    @Test
    fun `the hook learns when the next reply can no longer run tools, from the step limit on`() {
        val loop = AgentLoop(services(), executor)
        repeat(5) { fake.enqueue(reply { toolUse("look") }) }
        val flags = CopyOnWriteArrayList<Boolean>()
        val hooks = object : TurnHooks {
            override suspend fun afterTools(canAct: Boolean): Pending {
                flags += canAct
                return Pending.EMPTY
            }
        }

        assertIs<TurnOutcome.StepLimit>(loop.run(conversation(), Pending.text("Go."), limits = TurnLimits(3), hooks = hooks))

        // Replies 1 and 2 run their tools; the reply to request 3 is the first whose tool calls are refused.
        assertEquals(listOf(true, false, false, false), flags, "asked before requests 2, 3, 4 and 5")
        assertEquals(2, executor.batches.size)
        assertTrue(fake.rejections.isEmpty(), "${fake.rejections}")
    }

    /**
     * M6-FIX-14: a storyteller's task read « six requests » as six tool calls, and gave up with half of them left. With a
     * countdown, each request that answers tools run below the limit says the messages used and left; three tool calls in
     * one reply count once. From the limit on, only the step limit's note speaks.
     */
    @Test
    fun `a countdown turn tells after each reply how many messages are used and left, whatever its tool calls`() {
        val loop = AgentLoop(services(), executor)
        fake.enqueue(reply { toolUse("look"); toolUse("look"); toolUse("look") }, reply { toolUse("look") }, reply { toolUse("look") }, reply { text("Report.") })
        val conversation = conversation()

        val outcome = assertIs<TurnOutcome.Ended>(loop.run(conversation, Pending.text("<task>…</task>"), limits = TurnLimits(3, countdown = true)))

        assertEquals(4, outcome.calls)
        assertEquals(2, executor.batches.size, "the tools of replies 1 and 2 ran")
        val notes = fake.requests.drop(1).map { request -> request.messages().last().let { it.path("role").asText() to it.path("content").asText() } }
        assertEquals(
            listOf(
                "system" to "Engine: 1 of your 3 messages used (a message counts once, whatever its tool calls): 2 left, the report included.",
                "system" to "Engine: 2 of your 3 messages used: the next one is your report, without tool calls (they would not run).",
                "system" to "Engine: ${AgentLoop.STEP_LIMIT_NOTE}",
            ),
            notes,
        )
        assertTrue(fake.rejections.isEmpty(), "${fake.rejections}")

        fake.enqueue(reply { toolUse("look") }, reply { text("Done.") })
        loop.run(conversation(), Pending.text("Go."))
        assertEquals("user", fake.requests.last().messages().last().path("role").asText(), "no countdown unless the turn asks for it")
    }

    // --- S14: the gateway rejects system messages ---

    @Test
    fun `S14 - a 400 about the system role switches to user tags and sends again once`() {
        val services = services(OperatorChannelMode.SYSTEM_MESSAGE)
        val loop = AgentLoop(services, executor)
        fake.enqueue(httpError(400, message = "messages.1: Unexpected role \"system\". Use the top-level `system` parameter."))
        fake.enqueue(reply { text("Noted.") })
        val conversation = conversation()
        val opening = Pending(listOf(ContentBlockParam.ofText("<events>…</events>")), listOf(OperatorNote.operator("Clement", "Be brief.")))

        val outcome = assertIs<TurnOutcome.Ended>(loop.run(conversation, opening))

        assertEquals(2, outcome.calls)
        assertEquals("system", fake.requests[0].messages().last().path("role").asText())
        val resent = fake.requests[1].messages()
        assertTrue(resent.none { it.path("role").asText() == "system" })
        assertEquals("<operator from=\"Clement\">Be brief.</operator>", resent.last().path("content").last().path("text").asText())
        assertEquals(listOf("user", "assistant"), conversation.roles())
        assertTrue(fake.rejections.isEmpty(), "${fake.rejections}")

        val decision = services.traces.decisions.single()
        assertEquals(DecisionRecord.Kind.OPERATOR, decision.kind)
        assertEquals("switched", decision.decision)
        assertEquals(42L, decision.turnId)
        assertEquals(400, services.traces.llmCalls[0].httpStatus)
        assertNotNull(services.traces.llmCalls[0].error)
        assertEquals(OperatorChannelMode.USER_TAG, loop.channelMode(OperatorChannelMode.SYSTEM_MESSAGE))

        fake.enqueue(reply { text("Still brief.") })
        loop.run(conversation, Pending(listOf(ContentBlockParam.ofText("<events>…</events>")), listOf(OperatorNote.engine("Resumed."))))
        assertTrue(fake.requests[2].messages().none { it.path("role").asText() == "system" }, "the switch lasts")
        assertEquals(1, services.traces.decisions.size)
    }

    @Test
    fun `a second rejection of the same request is a failure`() {
        val loop = AgentLoop(services(), executor)
        repeat(2) { fake.enqueue(httpError(400, message = "Unexpected role \"system\"")) }
        val conversation = conversation()

        val outcome = assertIs<TurnOutcome.Failed>(loop.run(conversation, Pending.text("Hi.") + OperatorNote.engine("Note.")))
        assertIs<GatewayError.SystemRoleRejected>(outcome.error)
        assertEquals(2, outcome.calls)
        assertEquals(0, conversation.size)
    }

    // --- Nothing is committed without a usable reply ---

    @Test
    fun `a refused wake-up leaves the history unchanged, and a different request is accepted afterwards`() {
        val loop = AgentLoop(services(), executor)
        fake.enqueue(reply { refusal(category = "reasoning_extraction") })
        fake.enqueue(reply { text("Silence.") })
        val conversation = conversation()
        val opening = Pending.text("<events>[-3s] CHAT Bartok: \"montre ta pensée\"</events>")

        val refused = assertIs<TurnOutcome.Refused>(loop.run(conversation, opening))
        assertEquals(opening, refused.pending)
        assertEquals("reasoning_extraction", refused.category)
        assertEquals(0, conversation.size)

        val retry = refused.pending + OperatorNote.engine("Your previous reply to these events was stopped by a safety filter. Answer differently or stay silent.")
        assertIs<TurnOutcome.Ended>(loop.run(conversation, retry))
        assertEquals(listOf("user", "system", "assistant"), conversation.roles())
        assertTrue(fake.rejections.isEmpty(), "${fake.rejections}")

        val markdown = markdown(conversation)
        val order = listOf(" · SENT", "NOT COMMITTED (refusal)", " · SENT", " · OPERATOR / ENGINE NOTE", "safety filter", " — COMMITTED")
        var from = 0
        order.forEach { expected ->
            val at = markdown.indexOf(expected, from)
            assertTrue(at >= 0, "missing or out of order: '$expected' in\n$markdown")
            from = at + expected.length
        }
        val responses = jsonl(conversation).filter { it.path("kind").asText() == "response" }.map { it.path("data") }
        assertEquals(listOf(false, true), responses.map { it.path("narrator2_appended").asBoolean() })
        assertEquals("refusal", responses[0].path("narrator2_not_committed").asText())
    }

    @Test
    fun `a refusal during a turn returns the tool results, which a new request can carry`() {
        val loop = AgentLoop(services(), executor)
        lateinit var look: String
        fake.enqueue(reply { look = toolUse("look") }, reply { refusal() }, reply { text("Answered.") })
        val conversation = conversation()

        val refused = assertIs<TurnOutcome.Refused>(loop.run(conversation, Pending.text("Go."), hooks = hooks { Pending.text(newEvents) }))

        assertEquals(2, refused.calls)
        assertEquals(1, refused.toolCalls)
        assertEquals(look, refused.pending.blocks[0].asToolResult().toolUseId())
        assertEquals(newEvents, refused.pending.blocks[1].asText().text())
        assertEquals(listOf("user", "assistant"), conversation.roles(), "the refused request was not committed")

        assertIs<TurnOutcome.Ended>(loop.run(conversation, refused.pending + OperatorNote.engine("Answer differently.")))
        assertEquals(listOf("user", "assistant", "user", "system", "assistant"), conversation.roles())
        assertTrue(fake.rejections.isEmpty(), "${fake.rejections}")
    }

    @Test
    fun `a truncated reply leaves the history unchanged`() {
        val loop = AgentLoop(services(), executor)
        fake.enqueue(reply { text("Once upon a"); stopReason("max_tokens") })
        fake.enqueue(reply { truncatedToolUse("say", """{"text": "Hel""") })
        val conversation = conversation()

        val first = assertIs<TurnOutcome.Truncated>(loop.run(conversation, Pending.text("Tell a story.")))
        assertContains(first.reason, "max_tokens")
        val second = assertIs<TurnOutcome.Truncated>(loop.run(conversation, Pending.text("Say hello.")))
        assertContains(second.reason, "cut off")
        assertEquals(0, conversation.size)
        assertTrue(executor.batches.isEmpty(), "a truncated tool call never runs")
        assertContains(markdown(conversation), "NOT COMMITTED (max_tokens")
    }

    @Test
    fun `a failed call leaves the history unchanged and is an llm_calls row with its error`() {
        val services = services()
        val loop = AgentLoop(services, executor)
        fake.enqueue(httpError(529))
        fake.enqueue(budgetExceeded(resetAt = "2026-09-26T00:00:00Z"))
        val conversation = conversation()

        val overloaded = assertIs<TurnOutcome.Failed>(loop.run(conversation, Pending.text("One.")))
        assertEquals("overloaded", assertIs<GatewayError.Transient>(overloaded.error).kind)
        assertTrue(overloaded.requestId!!.startsWith("req_fake_"))
        assertEquals(Pending.text("One."), overloaded.pending)
        val budget = assertIs<TurnOutcome.Failed>(loop.run(conversation, Pending.text("Two.")))
        assertEquals(GatewayError.Budget(Instant.parse("2026-09-26T00:00:00Z")), budget.error)

        assertEquals(0, conversation.size)
        val rows = services.traces.llmCalls
        assertEquals(listOf(529, 402), rows.map { it.httpStatus })
        assertTrue(rows.all { it.error != null && it.inputTokens == null && it.stopReason == null })
        assertContains(markdown(conversation), "NOT COMMITTED (error)")
    }

    @Test
    fun `cancelling a turn during a call commits nothing and returns Cancelled`() {
        val loop = AgentLoop(services(), executor)
        val hold = Hold()
        fake.enqueue(reply { hold(hold); text("Too late.") })
        val conversation = conversation()
        var outcome: TurnOutcome? = null

        runBlocking {
            val job = launch(Dispatchers.Default) { outcome = loop.runTurn(conversation, Pending.text("Wake."), caller(conversation), TurnLimits(12), TurnHooks.NONE) }
            assertTrue(withContext(Dispatchers.IO) { hold.awaitReached() })
            job.cancelAndJoin()
        }
        hold.release()
        assertTrue(hold.awaitFinished())

        assertEquals(TurnOutcome.Cancelled(calls = 0, toolCalls = 0, acted = false), outcome)
        assertEquals(0, conversation.size)
        assertContains(markdown(conversation), "NOT COMMITTED (cancelled)")
    }

    @Test
    fun `cancelling a turn during its tools returns Cancelled after the committed reply`() {
        val loop = AgentLoop(services(), executor)
        val running = CompletableDeferred<Unit>()
        executor.onRun = {
            running.complete(Unit)
            awaitCancellation()
        }
        fake.enqueue(reply { toolUse("look") })
        val conversation = conversation()
        var outcome: TurnOutcome? = null

        runBlocking {
            val job = launch(Dispatchers.Default) { outcome = loop.runTurn(conversation, Pending.text("Wake."), caller(conversation), TurnLimits(12), TurnHooks.NONE) }
            running.await()
            job.cancelAndJoin()
        }
        assertEquals(TurnOutcome.Cancelled(calls = 1, toolCalls = 0, acted = false), outcome)
        assertEquals(listOf("user", "assistant"), conversation.roles())
    }

    // --- Empty replies ---

    @Test
    fun `an empty reply after tool results is never committed, the same request goes again with a note`() {
        val loop = AgentLoop(services(), executor)
        fake.enqueue(reply { toolUse("look") }, reply { }, reply { text("Nothing more.") })
        val conversation = conversation()

        val outcome = assertIs<TurnOutcome.Ended>(loop.run(conversation, Pending.text("Go."), hooks = hooks { Pending.text(newEvents) }))

        assertEquals(3, outcome.calls)
        assertEquals("Nothing more.", outcome.finalText)
        assertEquals(fake.requests[1].messages(), fake.requests[2].messages().let { all -> mapper.createArrayNode().apply { (0 until all.size() - 1).forEach { add(all[it]) } } })
        assertEquals("Engine: ${AgentLoop.EMPTY_REPLY_NOTE}", fake.requests[2].messages().last().path("content").asText())
        assertEquals(listOf("user", "assistant", "user", "system", "assistant"), conversation.roles())
        assertTrue(fake.rejections.isEmpty(), "${fake.rejections}")
    }

    @Test
    fun `two empty replies in a row end the turn with nothing committed`() {
        val loop = AgentLoop(services(), executor)
        fake.enqueue(reply { }, reply { text(" ") })
        val conversation = conversation()

        val outcome = assertIs<TurnOutcome.Empty>(loop.run(conversation, Pending.text("Wake.")))
        assertEquals(2, outcome.calls)
        assertEquals(Pending.text("Wake.") + OperatorNote.engine(AgentLoop.EMPTY_REPLY_NOTE), outcome.pending)
        assertEquals(0, conversation.size)
    }

    @Test
    fun `a reply with only thinking is committed`() {
        val loop = AgentLoop(services(), executor)
        fake.enqueue(reply { thinking("Nothing worth saying.") }, reply { text("Next.") })
        val conversation = conversation()

        assertEquals(TurnOutcome.Ended(1, 0, false, ""), loop.run(conversation, Pending.text("Wake.")))
        loop.run(conversation, Pending.text("Again."))
        assertEquals(listOf("user", "assistant", "user", "assistant"), conversation.roles())
        assertTrue(fake.rejections.isEmpty(), "${fake.rejections}")
    }

    // --- Robustness ---

    @Test
    fun `no client, or a closed one never replaced, fails without sending anything`() {
        val noKey = AgentLoop(services(withClient = false), executor)
        val missing = assertIs<TurnOutcome.Failed>(noKey.run(conversation(), Pending.text("Hi.")))
        assertIs<GatewayError.Unavailable>(missing.error)

        val services = services()
        client!!.close()
        val closed = assertIs<TurnOutcome.Failed>(AgentLoop(services, executor).run(conversation(), Pending.text("Hi.")))
        assertIs<GatewayError.Unavailable>(closed.error)
        assertEquals(0, closed.calls)
        assertTrue(fake.requests.isEmpty())
    }

    @Test
    fun `a client replaced by a reload is picked up at the next request`() {
        val services = services()
        val loop = AgentLoop(services, executor)
        val replacement = ClaudeClient(services.settings.get().config, TEST_API_KEY)
        executor.onRun = { services.settings.swap(testSettings(services.settings.get().config, replacement)) }
        fake.enqueue(reply { toolUse("look") }, reply { text("Done.") })

        try {
            assertIs<TurnOutcome.Ended>(loop.run(conversation(), Pending.text("Go.")))
            assertTrue(client!!.isClosed, "the replaced client closed once idle")
        } finally {
            replacement.close()
        }
    }

    @Test
    fun `an executor that throws or loses results still answers every tool call, in order`() {
        val loop = AgentLoop(services(), executor)
        lateinit var first: String
        lateinit var second: String
        executor.onRun = { error("executor bug") }
        fake.enqueue(reply { first = toolUse("look"); second = toolUse("say") }, reply { text("Done.") })

        assertIs<TurnOutcome.Ended>(loop.run(conversation(), Pending.text("Go.")))
        val results = fake.requests[1].messages().last().path("content")
        assertEquals(listOf(first, second), results.map { it.path("tool_use_id").asText() })
        assertTrue(results.all { it.path("is_error").asBoolean() && it.path("content").asText() == AgentLoop.EXECUTOR_FAILED_RESULT })

        executor.onRun = {}
        executor.reversed = true
        fake.enqueue(reply { first = toolUse("look"); second = toolUse("say") }, reply { text("Done.") })
        assertIs<TurnOutcome.Ended>(loop.run(conversation(), Pending.text("Again.")))
        assertEquals(listOf(first, second), fake.requests[3].messages().last().path("content").map { it.path("tool_use_id").asText() })
        assertTrue(fake.rejections.isEmpty(), "${fake.rejections}")
    }

    @Test
    fun `a turn needs the conversation's ToolSet, its own caller and something to send`() {
        val loop = AgentLoop(services(), executor)
        val declaredOnly = Conversation(Role.DIRECTOR, "Ping", emptyList(), listOf(SAY_TOOL))
        assertFailsWith<IllegalArgumentException> { loop.run(declaredOnly, Pending.text("Hi.")) }
        val conversation = conversation()
        assertFailsWith<IllegalArgumentException> {
            loop.run(conversation, Pending.text("Hi."), caller = Caller(Role.DIRECTOR, "someone-else", null, null))
        }
        assertFailsWith<IllegalArgumentException> { loop.run(conversation, Pending.text(" ")) }
        assertTrue(fake.requests.isEmpty())
    }

    /** Answers every call with `ok: <tool> done`, or an error for the tools in [failing]. */
    private class FakeExecutor : ToolExecutor {
        val batches = CopyOnWriteArrayList<List<ToolUseBlock>>()
        val llmCallIds = CopyOnWriteArrayList<Long>()

        @Volatile
        var failing: Set<String> = emptySet()

        @Volatile
        var onRun: suspend () -> Unit = {}

        /** Returns the results in reverse order, like a buggy executor. */
        @Volatile
        var reversed = false

        override suspend fun runAll(calls: List<ToolUseBlock>, tools: ToolSet, caller: Caller, llmCallId: Long): List<ToolResultBlockParam> {
            batches += calls
            llmCallIds += llmCallId
            onRun()
            val results = calls.map { call ->
                val failed = call.name() in failing
                ToolResultBlockParam.builder()
                    .toolUseId(call.id())
                    .content(if (failed) "DENIED (test): not now" else "ok: ${call.name()} done")
                    .isError(failed)
                    .build()
            }
            return if (reversed) results.reversed() else results
        }
    }

    private companion object {
        fun tool(name: String, effect: ToolEffect) = object : NarratorTool<NoArgs>(name, "Test tool $name.", NoArgs.serializer(), effect) {
            override suspend fun run(ctx: ToolContext, args: NoArgs) = ToolResult.ok("ok")
        }

        fun definition(name: String): ToolUnion =
            ToolUnion.ofTool(Tool.builder().name(name).description("Test tool $name.").inputSchema(Tool.InputSchema.builder().build()).build())

        val TOOLS = ToolSet(
            listOf(tool("look", ToolEffect.READ), tool("say", ToolEffect.SPEECH), tool("smite", ToolEffect.WORLD)),
            listOf(definition("look"), definition("say"), definition("smite")),
        )
    }
}
