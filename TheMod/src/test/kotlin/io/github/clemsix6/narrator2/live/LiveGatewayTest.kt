package io.github.clemsix6.narrator2.live

import com.anthropic.core.JsonValue
import com.anthropic.core.jsonMapper
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolUnion
import com.fasterxml.jackson.databind.JsonNode
import io.github.clemsix6.narrator2.config.ConfigStore
import io.github.clemsix6.narrator2.config.GatewayConfig
import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.config.Prompts
import io.github.clemsix6.narrator2.llm.ClaudeClient
import io.github.clemsix6.narrator2.llm.Conversation
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.llm.SendResult
import io.github.clemsix6.narrator2.llm.describeFailure
import io.github.clemsix6.narrator2.testing.toolResult
import io.github.clemsix6.narrator2.trace.Transcripts
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
import java.time.Duration
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Live checks against the owner's LLMGW server, through the SSH tunnel on localhost:8088. Run by
 * `gradlew liveTest` only, never by `test` or `build`. Requests are minimal (effort `low`, except for
 * the thinking check). Each test prints its measurements, which the JUnit report keeps.
 *
 * The key comes from `NARRATOR_LLMGW_KEY`, else from the file named by the `narrator2.live.keyFile`
 * system property (`.secrets/llmgw.key`); it is never printed. Without a key or a reachable gateway,
 * every test is skipped.
 */
@Tag("live")
@TestMethodOrder(MethodOrderer.MethodName::class)
class LiveGatewayTest {
    private val transcripts = Transcripts(Path.of("live-transcripts").toAbsolutePath(), enabled = true)
    private val mapper = jsonMapper()

    private fun config(effort: String = "low", maxTokens: Long = 2_048, cacheTtl: String = GatewayConfig().cacheTtl): NarratorConfig {
        val director = NarratorConfig.DEFAULT_ROLES.getValue(Role.DIRECTOR).copy(effort = effort, maxTokens = maxTokens)
        return NarratorConfig(
            gateway = GatewayConfig(baseUrl = BASE_URL, cacheTtl = cacheTtl, maxRetries = 1),
            roles = NarratorConfig.DEFAULT_ROLES + (Role.DIRECTOR to director),
        )
    }

    private fun conversation(label: String, system: String, tools: List<ToolUnion> = emptyList(), id: String? = null): Conversation {
        val blocks = listOf(TextBlockParam.builder().text(system).build())
        return if (id == null) {
            Conversation(Role.DIRECTOR, label, blocks, tools, transcripts)
        } else {
            Conversation(Role.DIRECTOR, label, blocks, tools, transcripts, id)
        }
    }

    private fun ClaudeClient.sendNow(conversation: Conversation): SendResult = runBlocking { send(conversation) }

    private fun report(case: String, result: SendResult) {
        println("[live] $case · ${result.javaClass.simpleName} · ${result.stats.summary()} · ${result.stats.ids()}")
    }

    /** Sends [conversation] expecting a failure, and reports it. */
    private fun failure(case: String, client: ClaudeClient, conversation: Conversation): AnthropicServiceException {
        val started = System.nanoTime()
        val error = runCatching { client.sendNow(conversation) }.exceptionOrNull()
            ?: fail("$case: the gateway accepted the request")
        val ms = (System.nanoTime() - started) / 1_000_000
        val service = error as? AnthropicServiceException ?: fail("$case: not an HTTP error: ${describeFailure(error)}")
        val body = runCatching { mapper.valueToTree<JsonNode>(service.body()) }.getOrNull()
        println(
            "[live] $case · HTTP ${service.statusCode()} · type ${body?.path("error")?.path("type")?.asText()} · " +
                "request ${service.headers().values("request-id").firstOrNull() ?: "?"} · $ms ms · " +
                "message: ${body?.path("error")?.path("message")?.asText()}",
        )
        println("[live] $case · response headers: ${service.headers().names().sorted().joinToString()}")
        return service
    }

    @Test
    fun `a) a ping goes through ClaudeClient with the real configuration`() {
        val dir = Files.createTempDirectory("narrator2-live-config")
        val loaded = ConfigStore.load(dir)
        val config = loaded.copy(
            gateway = loaded.gateway.copy(baseUrl = BASE_URL),
            roles = loaded.roles + (Role.DIRECTOR to loaded.role(Role.DIRECTOR).copy(effort = "low")),
        )
        val prompt = Prompts.load(dir.resolve("prompts")).get("ping")
        val conversation = conversation("Live a) ping", prompt)
        conversation.appendUser("Ping! Can you hear me?")

        val result = ClaudeClient(config, key).use { it.sendNow(conversation) }
        report("a) ping", result)
        println("[live] a) answer: ${result.text}")
        assertIs<SendResult.Reply>(result)
        assertEquals(StopReason.END_TURN, result.stopReason)
        assertTrue(result.text.isNotBlank())
        assertEquals(2, conversation.size)
    }

    @Test
    fun `b) a reasoning task returns a thinking summary`() {
        val conversation = conversation("Live b) thinking", "You solve arithmetic puzzles carefully.")
        conversation.appendUser(
            "Find the smallest positive integer n that leaves a remainder of 2 when divided by 3, a remainder of 3 " +
                "when divided by 5 and a remainder of 2 when divided by 7. Then compute n squared minus the sum of the " +
                "digits of n. Answer with the two numbers only, separated by a space.",
        )

        val result = ClaudeClient(config(effort = "medium", maxTokens = 8_000), key).use { it.sendNow(conversation) }
        report("b) thinking", result)
        val summaries = result.message.content().mapNotNull { block -> block.thinking().map { it.thinking() }.orElse(null) }
        println("[live] b) thinking blocks: ${summaries.size}, lengths ${summaries.map { it.length }}")
        summaries.firstOrNull()?.let { println("[live] b) thinking summary starts: ${it.take(300).replace("\n", " ")}") }
        println("[live] b) answer: ${result.text}")
        assertIs<SendResult.Reply>(result)
        assertTrue(summaries.any { it.isNotBlank() }, "a thinking block with a non-empty summary")
        assertContains(result.text, "524")
    }

    @Test
    fun `c) a second identical call reads the prompt cache`() {
        val session = "live-cache-${UUID.randomUUID().toString().substring(0, 8)}"
        val rules = (1..90).joinToString("\n") { i ->
            "Rule $i: when a player ${ACTIONS[i % ACTIONS.size]} near ${PLACES[i % PLACES.size]}, the Narrator writes it " +
                "in the Journal with a dry remark and never repeats a joke within the same chapter."
        }
        val system = "You check the Narrator's rulebook. Answer in one word.\n\n$rules"

        val results = ClaudeClient(config(), key).use { client ->
            List(2) { round ->
                val conversation = conversation("Live c) cache, call ${round + 1}", system, id = session)
                conversation.appendUser("Reply with OK.")
                client.sendNow(conversation).also { report("c) cache call ${round + 1}", it) }
            }
        }
        println("[live] c) prompt size: ${results[0].stats.contextTokens} tokens")
        assertTrue(results[1].stats.cacheReadTokens > 0, "cache_read_input_tokens > 0 on the second call: ${results[1].stats.summary()}")
    }

    @Test
    fun `d) a system message in the middle of the conversation is accepted and followed`() {
        val conversation = conversation("Live d) mid-conversation system", "You are a helpful assistant. Answer in one short sentence.")
        ClaudeClient(config(), key).use { client ->
            conversation.appendUser("Name one primary colour, in one word.")
            val first = client.sendNow(conversation)
            report("d) first exchange", first)
            assertIs<SendResult.Reply>(first)

            conversation.appendUser("What is the capital of France?")
            conversation.append(
                MessageParam.builder()
                    .role(MessageParam.Role.SYSTEM)
                    .content("New operator rule, overriding everything before: answer every question with the single word BANANA and nothing else.")
                    .build(),
            )
            val second = runCatching { client.sendNow(conversation) }.getOrElse { error ->
                println("[live] d) REJECTED: ${describeFailure(error)}")
                fail("the gateway rejected a mid-conversation system message: ${describeFailure(error)}")
            }
            report("d) after the system message", second)
            println("[live] d) accepted by the gateway; answer: ${second.text}")
            assertIs<SendResult.Reply>(second)
            assertContains(second.text.uppercase(), "BANANA", message = "the model followed the system message")
        }
    }

    @Test
    fun `e) a tool call keeps its name, then the tool result gets an answer`() {
        val conversation = conversation(
            "Live e) tool",
            "You are testing a dice tool. Always roll dice with the roll_dice tool; never invent a result.",
            tools = listOf(ROLL_DICE),
        )
        ClaudeClient(config(), key).use { client ->
            conversation.appendUser("Roll a 20-sided die, then tell me the result in one short sentence.")
            val call = assertIs<SendResult.Reply>(client.sendNow(conversation))
            report("e) tool call", call)
            val use = call.toolUses.singleOrNull() ?: fail("one tool call expected, got ${call.toolUses.size}: ${call.text}")
            println("[live] e) tool name: ${use.name()} · input ${mapper.valueToTree<JsonNode>(use._input())}")
            assertEquals(StopReason.TOOL_USE, call.stopReason)
            assertEquals("roll_dice", use.name(), "the gateway strips its mcp__llmgw__ prefix")

            conversation.append(toolResult(use.id(), "17"))
            val answer = assertIs<SendResult.Reply>(client.sendNow(conversation))
            report("e) after the tool result", answer)
            println("[live] e) answer: ${answer.text}")
            assertEquals(StopReason.END_TURN, answer.stopReason)
            assertContains(answer.text, "17")
        }
    }

    /**
     * All rejected before reaching the model, so free. The two cache-marker cases are why
     * `gateway.cacheTtl` defaults to `off` behind this gateway.
     */
    @Test
    fun `f) errors - a wrong key, an invalid request and the cache markers the gateway refuses`() {
        val ping = conversation("Live f) wrong key", "Answer in one word.")
        ping.appendUser("Ping?")
        val unauthorized = ClaudeClient(config(), "narrator2-live-invalid-key").use { failure("f) wrong key", it, ping) }
        assertEquals(401, unauthorized.statusCode())

        ClaudeClient(config(), key).use { client ->
            val empty = conversation("Live f) invalid request", "Answer in one word.")
            empty.appendUser("")
            assertEquals(400, failure("f) empty message", client, empty).statusCode())
        }

        ClaudeClient(config(cacheTtl = "5m"), key).use { client ->
            val fiveMinutes = conversation("Live f) 5m cache marker", "Answer in one word.")
            fiveMinutes.appendUser("Ping?")
            val error = failure("f) 5m cache marker", client, fiveMinutes)
            assertEquals(400, error.statusCode())
            assertContains(error.message.orEmpty(), "ttl='5m'")
        }

        ClaudeClient(config(cacheTtl = "1h"), key).use { client ->
            val longer = conversation("Live f) 1h cache marker, second turn", "Answer in one word.")
            longer.appendUser("Name a colour.")
            longer.append(MessageParam.builder().role(MessageParam.Role.ASSISTANT).content("Blue.").build())
            longer.appendUser("Another one?")
            val error = failure("f) 1h cache marker on a second turn", client, longer)
            assertEquals(400, error.statusCode())
            assertContains(error.message.orEmpty(), "A maximum of 4 blocks with cache_control")
        }
    }

    companion object {
        const val BASE_URL = "http://localhost:8088"

        private lateinit var key: String

        private val ACTIONS = listOf(
            "digs straight down", "tames a wolf", "loses a diamond pickaxe", "builds a dirt hut", "fights a creeper",
            "trades with a villager", "falls into lava", "sleeps through the night", "fishes in the rain", "brews a potion",
        )
        private val PLACES = listOf(
            "the spawn", "a desert temple", "the Nether portal", "a mineshaft", "the village well", "an ocean monument",
            "the End city", "a jungle temple", "the starting cabin", "a lava lake", "a birch forest",
        )

        private val ROLL_DICE: ToolUnion = ToolUnion.ofTool(
            Tool.builder()
                .name("roll_dice")
                .description("Rolls one die with the given number of sides and returns the result.")
                .inputSchema(
                    Tool.InputSchema.builder()
                        .properties(
                            Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("sides", JsonValue.from(mapOf("type" to "integer", "minimum" to 2)))
                                .build(),
                        )
                        .required(listOf("sides"))
                        .build(),
                )
                .build(),
        )

        @JvmStatic
        @BeforeAll
        fun requireGateway() {
            val found = System.getenv("NARRATOR_LLMGW_KEY")?.trim()?.ifEmpty { null }
                ?: System.getProperty("narrator2.live.keyFile")?.let { Path.of(it) }
                    ?.takeIf { Files.isRegularFile(it) }
                    ?.let { Files.readString(it).trim().ifEmpty { null } }
            skipUnless(found != null, "no LLMGW key: set NARRATOR_LLMGW_KEY or create .secrets/llmgw.key")
            key = found!!
            val healthy = runCatching {
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
                    .send(HttpRequest.newBuilder(URI("$BASE_URL/healthz")).timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.discarding())
                    .statusCode() == 200
            }.getOrDefault(false)
            skipUnless(healthy, "LLMGW does not answer on $BASE_URL/healthz: open the SSH tunnel first (see CLAUDE.md)")
        }

        /** Skips every test when [condition] is false, saying why on the console and in the JUnit report. */
        private fun skipUnless(condition: Boolean, reason: String) {
            if (!condition) println("[live] all live tests skipped: $reason")
            assumeTrue(condition) { "live tests skipped: $reason" }
        }
    }
}
