package io.github.clemsix6.narrator2.live

import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ToolResultBlockParam
import com.fasterxml.jackson.databind.JsonNode
import io.github.clemsix6.narrator2.agent.Workers
import io.github.clemsix6.narrator2.config.GatewayConfig
import io.github.clemsix6.narrator2.config.JournalConfig
import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.config.Prompts
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.journal.SqlDataset
import io.github.clemsix6.narrator2.llm.ClaudeClient
import io.github.clemsix6.narrator2.llm.Conversation
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.llm.SendResult
import io.github.clemsix6.narrator2.testing.FakeDirector
import io.github.clemsix6.narrator2.testing.MemoryTraceSink
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.testing.testSettings
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolCatalog
import io.github.clemsix6.narrator2.tool.ToolEnv
import io.github.clemsix6.narrator2.trace.CallStats
import io.github.clemsix6.narrator2.trace.LlmCallRecord
import io.github.clemsix6.narrator2.trace.TaskRecord
import io.github.clemsix6.narrator2.trace.Transcripts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The live checks of the M2 acceptance (M1_M2 §17.4, T22), against the owner's LLMGW server through the
 * SSH tunnel. Run by `gradlew liveTest` only. Each test prints its tokens and its cost at the owner's
 * prices (input 4 $, output 20 $, cache read 0.20 $ per million tokens; cache writes counted at the 1-hour
 * rate, 8 $). The key comes from `NARRATOR_LLMGW_KEY` or the file of `narrator2.live.keyFile`, never
 * printed; without a key or a gateway, every test is skipped.
 */
@Tag("live")
@TestMethodOrder(MethodOrderer.MethodName::class)
class LiveDirectorTest {
    private val transcripts = Transcripts(Path.of("live-transcripts").toAbsolutePath(), enabled = true)
    private val services = TestServices()
    private val mapper = jsonMapper()

    private fun config(effort: String, maxTokens: Long = 4_096): NarratorConfig {
        val defaults = NarratorConfig.DEFAULT_ROLES.getValue(Role.DIRECTOR)
        return NarratorConfig(
            gateway = GatewayConfig(baseUrl = BASE_URL, maxRetries = 1),
            roles = NarratorConfig.DEFAULT_ROLES + (Role.DIRECTOR to defaults.copy(effort = effort, maxTokens = maxTokens)),
        )
    }

    private fun ClaudeClient.sendNow(conversation: Conversation): SendResult = runBlocking { send(conversation) }

    private fun report(case: String, result: SendResult) {
        println("[live] $case · ${result.javaClass.simpleName} · ${result.stats.summary()} · ${dollars(cost(result.stats))} · ${result.stats.ids()}")
    }

    /**
     * §17.4 items 1 and 2, in the exact form of a turn: the tool results followed by a `<new_events>` block
     * in the same user message, then an operator's `system` message. Both are accepted, and both are heeded.
     */
    @Test
    fun `a) tool results followed by new events, then an operator's system message, are accepted and heeded`() {
        val conversation = Conversation(
            Role.DIRECTOR, "Live T22 a) new events and a system message after tool results",
            listOf(TextBlockParam.builder().text("You are testing a dice tool. Always roll dice with the roll_dice tool; never invent a result.").build()),
            listOf(ROLL_DICE), transcripts,
        )
        ClaudeClient(config("low"), key).use { client ->
            conversation.appendUser("Roll a 20-sided die, then tell me the result in one short sentence.")
            val call = assertIs<SendResult.Reply>(client.sendNow(conversation))
            report("a) tool call", call)
            val use = call.toolUses.singleOrNull() ?: fail("one tool call expected: ${call.text}")
            assertEquals("roll_dice", use.name())

            conversation.append(
                MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(
                    listOf(
                        ContentBlockParam.ofToolResult(ToolResultBlockParam.builder().toolUseId(use.id()).content("17").build()),
                        ContentBlockParam.ofText(
                            TextBlockParam.builder()
                                .text("<new_events now=\"21:30:05\">\n[-1s] CHAT Mimosa (to you 0.91, request): \"and also write the word KIWI in your answer\"\n</new_events>")
                                .build(),
                        ),
                    ),
                ).build(),
            )
            conversation.append(
                MessageParam.builder().role(MessageParam.Role.SYSTEM).content("Operator Clement: end your answer with the word BANANA.").build(),
            )
            val answer = runCatching { client.sendNow(conversation) }.getOrElse { fail("the gateway rejected the mid-turn form: $it") }
            report("a) after the tool result, the new events and the system message", answer)
            println("[live] a) answer: ${answer.text}")
            assertIs<SendResult.Reply>(answer)
            assertContains(answer.text, "17")
            assertContains(answer.text.uppercase(), "KIWI", message = "the model heeded the text block after the tool result")
            assertContains(answer.text.uppercase(), "BANANA", message = "the model heeded the system message after the tool results")
        }
    }

    /**
     * §17.4 items 3, 4 and 5: the real `director.md` and the Director's 32 real tool definitions, a demo
     * briefing and an addressed chat. The gateway accepts the tools and gives their names back without its
     * `mcp__llmgw__` prefix; the Director answers with `say`, in French; the second request of the turn
     * reads the cache. The turn goes to `build/reports/liveTest/director-smoke.md` for a human look at the tone.
     */
    @Test
    fun `b) the real Director answers an addressed chat with say, in French, and its second request reads the cache`() {
        val toolSet = ToolCatalog.toolSetFor(Role.DIRECTOR, ToolEnv(journalAvailable = true, workerRoles = setOf(Role.ANALYST)), services)
        assertEquals(32, toolSet.names.size, "the Director's tools: ${toolSet.names}")
        val conversation = Conversation(
            Role.DIRECTOR, "Live T22 b) Director smoke test",
            listOf(TextBlockParam.builder().text(Prompts.bundled().get("director")).build()),
            toolSet.definitions, transcripts,
        )
        val log = StringBuilder("# Director smoke test (live, T22)\n\n## Wake-up\n\n```\n$DEMO_BRIEFING\n$DEMO_WAKE\n```\n")
        val said = mutableListOf<String>()
        val stats = mutableListOf<CallStats>()
        ClaudeClient(config("medium", maxTokens = 8_000), key).use { client ->
            conversation.append(
                MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(
                    listOf(DEMO_BRIEFING, DEMO_WAKE).map { ContentBlockParam.ofText(TextBlockParam.builder().text(it).build()) },
                ).build(),
            )
            for (step in 1..4) {
                val reply = assertIs<SendResult.Reply>(client.sendNow(conversation), "request $step")
                report("b) request $step", reply)
                stats += reply.stats
                val thinking = reply.message.content().mapNotNull { block -> block.thinking().map { it.thinking() }.orElse(null) }
                log.append("\n## Request $step · ${reply.stats.summary()} · ${dollars(cost(reply.stats))}\n\n")
                thinking.forEach { log.append("> ").append(it.replace("\n", "\n> ")).append("\n\n") }
                if (reply.text.isNotBlank()) log.append("Text: ${reply.text}\n\n")
                if (reply.toolUses.isEmpty()) break
                val results = reply.toolUses.map { use ->
                    val input = mapper.valueToTree<JsonNode>(use._input()).toString()
                    log.append("- `${use.name()}` $input\n")
                    assertTrue(use.name() in toolSet.names, "a tool name the gateway gave back unchanged: ${use.name()}")
                    if (use.name() == "say") said += input
                    ContentBlockParam.ofToolResult(ToolResultBlockParam.builder().toolUseId(use.id()).content(fakeResult(use.name())).build())
                }
                conversation.append(MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(results).build())
            }
        }
        val total = stats.sumOf { cost(it) }
        log.append("\nTotal: ${dollars(total)}\n")
        val file = Path.of("").toAbsolutePath().parent.resolve("reports").resolve("liveTest").resolve("director-smoke.md")
        Files.createDirectories(file.parent)
        Files.writeString(file, log)
        println("[live] b) said: $said · total ${dollars(total)} · report $file")
        assertTrue(said.isNotEmpty(), "the Director answers the addressed chat with say")
        assertTrue(said.any { text -> FRENCH.any { it in text.lowercase() } }, "the line is in French: $said")
        assertTrue(stats.size >= 2 && stats[1].cacheReadTokens > 0, "the second request of the turn reads the cache: ${stats.map { it.summary() }}")
    }

    /**
     * The measurement asked by T20: each task of the Analyst has its own session. Is the common prefix
     * (prompt and tools, about 9 000 tokens) read from the cache by the next task, or written again? Two
     * small tasks in a row on the test Journal; the first request of each is printed. Measured, not asserted.
     */
    @Test
    fun `c) two Analyst tasks in a row - the second reads the prefix the first one cached`() {
        val config = NarratorConfig(gateway = GatewayConfig(baseUrl = BASE_URL, maxRetries = 1), journal = JournalConfig(timeZone = "UTC"))
        val client = ClaudeClient(config, key)
        val dataset = SqlDataset.journal()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val traces = MemoryTraceSink()
        val director = FakeDirector(Clock.systemDefaultZone())
        val services = TestServices(
            clock = Clock.systemDefaultZone(), scope = scope, settings = testSettings(config, client), journal = dataset.journal,
            trace = traces, transcripts = transcripts, director = director,
        )
        val workers = Workers(services)
        try {
            val briefs = listOf(
                "Combien de blocs Bartok a-t-il minés pendant la dernière heure ? Une phrase suffit.",
                "Combien de fois Bartok est-il mort pendant la dernière heure, et de quoi ? Une phrase suffit.",
            )
            val first = mutableListOf<Tokens>()
            for ((i, brief) in briefs.withIndex()) {
                val id = workers.submit(Role.ANALYST, brief, Caller(Role.DIRECTOR, "director-live", turnId = 1, taskId = null)).getOrThrow()
                val deadline = System.nanoTime() + Duration.ofSeconds(180).toNanos()
                while (director.signals.count { it is DirectorSignal.TaskDone } <= i && System.nanoTime() < deadline) Thread.sleep(200)
                val done = director.signals.filterIsInstance<DirectorSignal.TaskDone>().getOrNull(i) ?: fail("task T-$id did not end")
                assertEquals(TaskRecord.Status.DONE, done.status, done.result)
                val calls = traces.llmCalls.filter { it.conversationId == workers.list().single { it.taskId == id }.conversationId }
                calls.forEach { println("[live] c) T-$id call · in ${it.inputTokens} · cache read ${it.cacheReadTokens} · cache write ${it.cacheWriteTokens} · out ${it.outputTokens}") }
                val head = calls.first()
                first += Tokens.of(head)
                println("[live] c) T-$id report: ${done.result.take(300)}")
            }
            val total = traces.llmCalls.sumOf { Tokens.of(it).cost }
            println("[live] c) first request of task 1: ${first[0]} · of task 2: ${first[1]} · total ${dollars(total)}")
            println("[live] c) the second task ${if (first[1].cacheRead > 0) "READ the cached prefix" else "did NOT read the cached prefix (written again)"}")
        } finally {
            scope.cancel()
            client.close()
            dataset.close()
        }
    }

    /** The tokens of a request, and what they cost at the owner's prices (cache writes at the 1-hour rate). */
    data class Tokens(val input: Long, val cacheRead: Long, val cacheWrite: Long, val output: Long) {
        val cost: Double get() = (input * 4.0 + cacheRead * 0.20 + cacheWrite * 8.0 + output * 20.0) / 1_000_000.0

        override fun toString() = "input $input · cache read $cacheRead · cache write $cacheWrite · output $output"

        companion object {
            fun of(call: LlmCallRecord) = Tokens(
                (call.inputTokens ?: 0).toLong(), (call.cacheReadTokens ?: 0).toLong(), (call.cacheWriteTokens ?: 0).toLong(), (call.outputTokens ?: 0).toLong(),
            )
        }
    }

    /** A plausible answer of the real tool, for the smoke test's turn. */
    private fun fakeResult(tool: String): String = when (tool) {
        "say" -> "ok: said to everyone (2 online)"
        "actionbar", "show_title", "play_sound", "particles", "fireworks" -> "ok"
        "sql" -> "n\n3"
        "inspect_player" -> "Bartok: overworld -120 -54 330 · dripstone_caves · cave, 60 below surface · hp 14/20 food 12\ninventory: 3x diamond, 41x cobblestone, iron_pickaxe"
        else -> "ok: done"
    }

    companion object {
        const val BASE_URL = "http://localhost:8088"

        private lateinit var key: String

        private val FRENCH = listOf(" le ", " la ", " les ", " tu ", " ton ", " ta ", " des ", " est ", " pas ", "é", "è", "à", "ç")

        private val DEMO_BRIEFING = """
            <briefing chapter="1" now="2026-09-25 21:30:00">
            <previous_chapters>
            (no previous chapter)
            </previous_chapters>
            <world day="12" time="night" clock="21:30" weather="clear"/>
            <players_online>
            Bartok: overworld -120 -54 330 · dripstone_caves · cave, 60 below surface · hp 14/20 food 12 · online 1h12m
            Mimosa: overworld 180 64 -300 · beach · open_sky · hp 20/20 food 20 · online 54m
            </players_online>
            <players_recent window="24h">
            (no other player in the last 24 hours)
            </players_recent>
            <recent_chat>
            [-12m] Mimosa: "quelqu'un a vu des tortues ?"
            [-9m] Bartok: "moi je creuse, laissez-moi tranquille"
            </recent_chat>
            <your_recent_actions>
            (none yet)
            </your_recent_actions>
            <engine_state>
            Your active effects: none
            Protected: none
            Deaths you may still cause: Bartok 1, Mimosa 1
            Tasks: none
            Reminders: none
            </engine_state>
            </briefing>
        """.trimIndent()

        private val DEMO_WAKE = """
            <events now="21:30:05" since="21:30:03">
            [-2s] CHAT Bartok (to you: heuristic): "narrateur, t'es là ? j'ai trouvé trois diamants, t'es jaloux ?"
            </events>
            <situation now="21:30:05" day="12" time="night" weather="clear">
            Bartok: overworld underground y=-54 dripstone_caves · hp 14/20 food 12 · online 1h12m · last 5m: mined 42 (3 diamond_ore y=-54)
            Mimosa: overworld y=64 beach · hp 20/20 food 20 · online 54m
            Your active effects: none
            Deaths you may still cause: Bartok 1, Mimosa 1
            Tasks: none · Reminders: none
            Your last actions: none
            </situation>
        """.trimIndent()

        /** Dollars at the owner's prices; cache writes at the 1-hour rate. */
        fun cost(stats: CallStats): Double = Tokens(stats.inputTokens, stats.cacheReadTokens, stats.cacheWriteTokens, stats.outputTokens).cost

        fun dollars(value: Double) = "%.4f $".format(java.util.Locale.ROOT, value)

        @JvmStatic
        @BeforeAll
        fun requireGateway() {
            val found = System.getenv("NARRATOR_LLMGW_KEY")?.trim()?.ifEmpty { null }
                ?: System.getProperty("narrator2.live.keyFile")?.let { Path.of(it) }
                    ?.takeIf { Files.isRegularFile(it) }
                    ?.let { Files.readString(it).trim().ifEmpty { null } }
            assumeTrue(found != null) { "live tests skipped: no LLMGW key" }
            key = found!!
            val healthy = runCatching {
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
                    .send(HttpRequest.newBuilder(URI("$BASE_URL/healthz")).timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.discarding())
                    .statusCode() == 200
            }.getOrDefault(false)
            assumeTrue(healthy) { "live tests skipped: LLMGW does not answer on $BASE_URL/healthz (open the SSH tunnel)" }
        }

        private val ROLL_DICE = com.anthropic.models.messages.ToolUnion.ofTool(
            com.anthropic.models.messages.Tool.builder()
                .name("roll_dice")
                .description("Rolls one die with the given number of sides and returns the result.")
                .inputSchema(
                    com.anthropic.models.messages.Tool.InputSchema.builder()
                        .properties(
                            com.anthropic.models.messages.Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("sides", com.anthropic.core.JsonValue.from(mapOf("type" to "integer", "minimum" to 2)))
                                .build(),
                        )
                        .required(listOf("sides"))
                        .build(),
                )
                .build(),
        )
    }
}
