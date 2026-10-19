package io.github.clemsix6.narrator2.tool

import com.anthropic.models.messages.ToolResultBlockParam
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.runtime.Phase
import io.github.clemsix6.narrator2.testing.LogCapture
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.ToolCallRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Running the tool calls of a reply (M1_M2 §8.4). */
class ToolRunnerTest {
    private val services = TestServices()
    private val runner = ToolRunner(services)
    private val caller = Caller(Role.DIRECTOR, "director-0badcafe", turnId = 7, taskId = null)
    private val ran = CopyOnWriteArrayList<String>()

    @Serializable
    data class NoteArgs(@MaxLen(20) val note: String)

    /** A tool that records its note and answers `ok: <note>`, after [delayMs]. */
    private fun noteTool(name: String, effect: ToolEffect = ToolEffect.SPEECH, delayMs: Long = 0) =
        TestTool(name, NoteArgs.serializer(), effect) { _, args ->
            delay(delayMs)
            ran += "$name ${args.note}"
            ToolResult.ok("ok: ${args.note}")
        }

    private val ToolResultBlockParam.text: String get() = content().get().asString()
    private val ToolResultBlockParam.error: Boolean get() = isError().orElse(false)

    private suspend fun runOne(tool: NarratorTool<*>, input: String = "{}", tools: ToolSet = ToolSet.of(listOf(tool))): ToolResultBlockParam =
        runner.runAll(listOf(toolUse("toolu_1", tool.name, input)), tools, caller, llmCallId = 42).single()

    @Test
    fun `calls run one after the other, in order, with one result per call`() = runTest {
        val tools = ToolSet.of(listOf(noteTool("slow", delayMs = 1_000), noteTool("fast")))
        val results = runner.runAll(
            listOf(
                toolUse("toolu_1", "slow", """{"note":"first"}"""),
                toolUse("toolu_2", "fast", """{"note":"second"}"""),
                toolUse("toolu_3", "slow", """{"note":"third"}"""),
            ),
            tools,
            caller,
            llmCallId = 42,
        )

        assertEquals(listOf("slow first", "fast second", "slow third"), ran)
        assertEquals(listOf("toolu_1", "toolu_2", "toolu_3"), results.map { it.toolUseId() })
        assertEquals(listOf("ok: first", "ok: second", "ok: third"), results.map { it.text })
        assertTrue(results.none { it.isError().isPresent }, "a success is not marked as an error")
        assertEquals(listOf("toolu_1", "toolu_2", "toolu_3"), services.traces.toolCalls.map { it.toolUseId })
    }

    @Test
    fun `each call writes its tool_calls row and its log line`() = runTest {
        val bob = PlayerRef(UUID.randomUUID(), "Bob")
        val tool = TestTool("every_type", EveryTypeArgs.serializer(), ToolEffect.WORLD) { ctx, args ->
            assertEquals(caller, ctx.caller)
            ToolResult.ok("ok: ${args.text} for ${args.seconds}s\nsecond line", targets = listOf(bob))
        }
        val result = LogCapture().use { logs ->
            runOne(tool, """{"text":"hi","seconds":30}""").also {
                assertTrue(
                    logs.messages.any { Regex("""\[Tool] director every_type ok \d+ms targets=\[Bob] ok: hi for 30s""").matches(it) },
                    logs.text,
                )
            }
        }

        assertEquals("ok: hi for 30s\nsecond line", result.text)
        val row = services.traces.toolCalls.single()
        assertEquals(
            ToolCallRecord(
                id = 1,
                ts = services.clock.instant(),
                conversationId = "director-0badcafe",
                turnId = 7,
                llmCallId = 42,
                toolUseId = "toolu_1",
                role = Role.DIRECTOR,
                tool = "every_type",
                args = """{"text":"hi","seconds":30}""",
                status = ToolStatus.OK,
                result = "ok: hi for 30s\nsecond line",
                durationMs = row.durationMs,
                targets = listOf("Bob"),
                effect = ToolEffect.WORLD,
            ),
            row,
        )
        assertTrue(row.durationMs >= 0)
    }

    @Test
    fun `a tool the conversation does not have is invalid`() = runTest {
        val tools = ToolSet.of(listOf(noteTool("say"), noteTool("show_title")))
        val result = runner.runAll(listOf(toolUse("toolu_1", "explode")), tools, caller, llmCallId = 42).single()

        assertTrue(result.error)
        assertEquals("Invalid arguments: unknown tool \"explode\"; your tools are: say, show_title", result.text)
        val row = services.traces.toolCalls.single()
        assertEquals(ToolStatus.INVALID, row.status)
        assertEquals("explode", row.tool)
        assertEquals(ToolEffect.READ, row.effect)
    }

    @Test
    fun `bad arguments are invalid and the tool does not run`() = runTest {
        val result = runOne(noteTool("say"), """{"note":"${"x".repeat(21)}","loud":true}""")

        assertTrue(result.error)
        assertEquals(
            "Invalid arguments: unknown argument \"loud\" (expected: note); note is longer than 20 characters (got 21)",
            result.text,
        )
        assertTrue(ran.isEmpty())
        assertEquals(ToolStatus.INVALID, services.traces.toolCalls.single().status)
    }

    @Test
    fun `arguments written as JSON text run the tool, with a tool args_coerced decision`() = runTest {
        val tool = TestTool("every_type", EveryTypeArgs.serializer(), ToolEffect.WORLD) { _, args -> ToolResult.ok("ok: ${args.to} for ${args.seconds}s") }

        val result = runOne(tool, """{"text":"hi","seconds":"30","to":"[\"Bob\"]"}""")

        assertEquals("ok: [Bob] for 30s", result.text)
        assertEquals(ToolStatus.OK, services.traces.toolCalls.single().status)
        assertEquals("""{"text":"hi","seconds":"30","to":"[\"Bob\"]"}""", services.traces.toolCalls.single().args, "the audit keeps what the model sent")
        val decision = services.traces.decisions.single()
        assertEquals(DecisionRecord.Kind.TOOL, decision.kind)
        assertEquals(
            listOf("args_coerced", "every_type", "seconds, to written as JSON text, read as the value it holds (director)"),
            listOf(decision.decision, decision.subject, decision.reason),
        )
        assertEquals("director-0badcafe" to 7L, decision.conversationId to decision.turnId)

        runOne(tool, """{"text":"hi","seconds":30,"to":["Bob"]}""")
        assertEquals(1, services.traces.decisions.size, "arguments of the right types are no decision")
    }

    @Test
    fun `a world tool past its time limit is unknown, a read tool a timeout`() = runTest {
        val world = TestTool("slow_world", NoArgs.serializer(), ToolEffect.WORLD, timeoutMs = 100) { _, _ -> awaitCancellation() }
        val read = TestTool("slow_read", NoArgs.serializer(), ToolEffect.READ, timeoutMs = 100) { _, _ -> awaitCancellation() }
        val tools = ToolSet.of(listOf(world, read))

        val results = runner.runAll(listOf(toolUse("toolu_1", "slow_world"), toolUse("toolu_2", "slow_read")), tools, caller, 42)

        assertEquals(
            listOf(
                "UNKNOWN: slow_world did not finish within 1s; the action may or may not have happened",
                "TIMEOUT: slow_read did not finish within 1s",
            ),
            results.map { it.text },
        )
        assertTrue(results.all { it.error })
        assertEquals(listOf(ToolStatus.UNKNOWN, ToolStatus.TIMEOUT), services.traces.toolCalls.map { it.status })
    }

    @Test
    fun `an exception fails the call with its message and logs its stack trace`() = runTest {
        val broken = TestTool("broken", NoArgs.serializer()) { _, _ -> error("the ledger is corrupt") }
        val unfinished = TestTool("unfinished", NoArgs.serializer()) { _, _ -> TODO("written in M4") }
        val tools = ToolSet.of(listOf(broken, unfinished))

        LogCapture().use { logs ->
            val results = runner.runAll(listOf(toolUse("toolu_1", "broken"), toolUse("toolu_2", "unfinished")), tools, caller, 42)

            assertEquals(
                listOf("FAILED: the ledger is corrupt", "FAILED: An operation is not implemented: written in M4"),
                results.map { it.text },
            )
            assertTrue(results.all { it.error })
            assertContains(logs.text, "[Tool] broken failed with an exception")
            assertContains(logs.text, "java.lang.IllegalStateException: the ledger is corrupt")
            assertContains(logs.text, "[Tool] director broken failed")
        }
        assertEquals(listOf(ToolStatus.FAILED, ToolStatus.FAILED), services.traces.toolCalls.map { it.status })
    }

    @Test
    fun `a cancellation from inside a tool fails that call only`() = runTest {
        val cancelling = TestTool("cancelling", NoArgs.serializer()) { _, _ -> throw CancellationException("gave up") }
        val tools = ToolSet.of(listOf(cancelling, noteTool("say")))

        val results = runner.runAll(listOf(toolUse("toolu_1", "cancelling"), toolUse("toolu_2", "say", """{"note":"after"}""")), tools, caller, 42)

        assertEquals(listOf("FAILED: gave up", "ok: after"), results.map { it.text })
    }

    @Test
    fun `nothing runs while the server is stopping`() = runTest {
        services.phase = Phase.STOPPING

        val result = runOne(noteTool("say"), """{"note":"hello"}""")

        assertTrue(result.error)
        assertEquals("FAILED: server is stopping", result.text)
        assertTrue(ran.isEmpty())
        assertEquals(ToolStatus.FAILED, services.traces.toolCalls.single().status)
    }

    @Test
    fun `a caller cancelled during a call records it as unknown and runs nothing after it`() = runTest {
        val started = CompletableDeferred<Unit>()
        val stuck = TestTool("stuck", NoArgs.serializer(), ToolEffect.WORLD, timeoutMs = 60_000) { _, _ ->
            started.complete(Unit)
            awaitCancellation()
        }
        val tools = ToolSet.of(listOf(stuck, noteTool("say")))
        val job = launch { runner.runAll(listOf(toolUse("toolu_1", "stuck"), toolUse("toolu_2", "say", """{"note":"late"}""")), tools, caller, 42) }
        runCurrent()
        assertTrue(started.isCompleted)

        job.cancelAndJoin()

        assertTrue(ran.isEmpty(), "the call after the cancelled one does not run")
        val row = services.traces.toolCalls.single()
        assertEquals(ToolStatus.UNKNOWN, row.status)
        assertEquals("UNKNOWN: cancelled while running, the server is stopping; the action may or may not have happened", row.result)
    }

    @Test
    fun `long results are cut for the model, and further for the audit`() = runTest {
        val verbose = TestTool("verbose", NoArgs.serializer()) { _, _ -> ToolResult.ok("x".repeat(20_000)) }

        val result = runOne(verbose)

        assertEquals(ToolRunner.MODEL_RESULT_MAX_CHARS, result.text.length)
        assertTrue(result.text.endsWith("x… (truncated, 20000 chars)"), result.text.takeLast(40))
        val audited = services.traces.toolCalls.single().result!!
        assertEquals(ToolCallRecord.RESULT_MAX_CHARS, audited.length)
        assertTrue(audited.endsWith("x… (truncated, 20000 chars)"))
    }

    @Test
    fun `truncation keeps short texts and never splits a character`() {
        assertEquals("short", ToolRunner.truncate("short", 100))
        val emoji = "😀".repeat(30)
        val cut = ToolRunner.truncate(emoji, 40)
        assertTrue(cut.length <= 40)
        assertFalse(cut.substringBefore("…").last().isHighSurrogate())
    }

    @Test
    fun `an empty result is still sent with a word`() = runTest {
        val silent = TestTool("silent", NoArgs.serializer()) { _, _ -> ToolResult.ok("") }

        assertEquals("ok", runOne(silent).text)
        assertEquals("", services.traces.toolCalls.single().result)
    }
}
