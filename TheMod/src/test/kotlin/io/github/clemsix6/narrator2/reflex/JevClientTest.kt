package io.github.clemsix6.narrator2.reflex

import io.github.clemsix6.narrator2.config.JevConfig
import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.config.Prompts
import io.github.clemsix6.narrator2.config.UiTexts
import io.github.clemsix6.narrator2.runtime.Settings
import io.github.clemsix6.narrator2.testing.FakeJev
import io.github.clemsix6.narrator2.testing.LogCapture
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.trace.LlmCallRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [JevClient] and [JevReflexes] against [FakeJev]: request shape, answers, errors, fallback and traces. */
class JevClientTest {
    private val key = "ts-SECRET-jev-key-5e5e"

    private val input = ChatTriageInput(
        message = ChatLine("Thawyn", "narrateur t'es nul"),
        recent = listOf(ChatLine("Clowew", "clemsix6 tu crois qu'on continue à miner ?"), ChatLine("clemsix6", "ouais encore un peu")),
        narratorLast = null,
    )

    private fun client(fake: FakeJev, timeoutMs: Long = 1_500, nanoTime: () -> Long = System::nanoTime) =
        JevClient(JevConfig(baseUrl = fake.baseUrl, timeoutMs = timeoutMs), key, nanoTime)

    private fun services(jev: JevClient?): TestServices {
        val config = NarratorConfig()
        return TestServices(settings = Settings(config, Prompts.bundled(), UiTexts.load(config.language), claude = null, jev = jev))
    }

    private fun triage(services: TestServices, input: ChatTriageInput = this.input) = runBlocking { JevReflexes(services).triageChat(input) }

    @Test
    fun `a chat triage sends one request with the nine questions and reads every answer`() {
        FakeJev().use { fake ->
            fake.enqueue(FakeJev.chat())
            val services = services(client(fake))

            val verdict = triage(services)

            val request = fake.requests.single()
            assertEquals("POST", request.method)
            assertEquals("/v1/systemone", request.path)
            assertEquals("Bearer $key", request.header("Authorization"))
            assertEquals("application/json", request.header("Content-Type"))
            val body = request.json
            assertEquals(listOf("state", "model", "questions"), body.keys.toList())
            assertEquals("jev-1.13.0", body.getValue("model").jsonPrimitive.content)

            val questions = body.getValue("questions").jsonObject
            assertEquals(
                listOf(
                    "addressed_to_narrator", "tone", "manipulation_attempt", "message_type",
                    "politeness", "boastfulness", "helpfulness", "aggressiveness", "humor",
                ),
                questions.keys.toList(),
            )
            val nouls = questions.filterValues { it.jsonObject.getValue("type").jsonPrimitive.content == "noul" }.keys
            assertEquals(setOf("addressed_to_narrator", "manipulation_attempt", "politeness", "boastfulness", "helpfulness", "aggressiveness", "humor"), nouls)
            assertEquals(Tone.entries.map { it.id }, questions.criteria("tone").keys.toList())
            assertEquals(ChatIntent.entries.map { it.id }, questions.criteria("message_type").keys.toList())
            assertEquals(setOf("true", "false"), questions.criteria("addressed_to_narrator").keys)
            assertNull(questions.getValue("humor").jsonObject["criteria"], "personality questions have no criteria")
            questions.values.forEach { question ->
                val instructions = question.jsonObject.getValue("instructions").jsonPrimitive.content
                assertContains(instructions, "?", message = "a question")
                assertFalse(Regex("[éèàù]").containsMatchIn(instructions), "questions are in English: $instructions")
            }

            val state = body.getValue("state").jsonObject
            assertEquals("""{"player":"Thawyn","text":"narrateur t'es nul"}""", state.getValue("message").toString())
            assertEquals(2, state.getValue("recent_chat").jsonArray.size)
            assertNull(state["narrator_last_message"], "left out when there is none")

            assertEquals(0.92, verdict.toNarrator)
            assertEquals(Tone.HOSTILE, verdict.tone)
            assertEquals(0.81, verdict.toneConfidence)
            assertEquals(0.1, verdict.manipulation)
            assertEquals(ChatIntent.PROVOCATION, verdict.intent)
            assertEquals(PersonalitySignals(0.05, 0.2, 0.1, 0.85, 0.3), verdict.personality)
            assertEquals("jev:jev-1.13.0", verdict.source)

            val row = services.traces.llmCalls.single()
            assertEquals(LlmCallRecord.Provider.JEV, row.provider)
            assertEquals(LlmCallRecord.Purpose.CHAT_TRIAGE, row.purpose)
            assertEquals("reflex", row.role)
            assertEquals("jev-1.13.0", row.model)
            assertNull(row.effort)
            assertNull(row.conversationId)
            assertNull(row.turnId)
            assertEquals(services.clock.instant(), row.startedAt)
            assertEquals(row.startedAt.plusMillis(row.latencyMs.toLong()), row.endedAt)
            assertEquals(1, row.attempts)
            assertEquals(200, row.httpStatus)
            assertEquals("req-fake-1", row.requestId)
            assertEquals(412, row.inputTokens)
            assertEquals(63, row.outputTokens)
            assertNull(row.error)
            assertTrue(services.traces.chatSignals.isEmpty(), "chat_signals rows are written by triageChatMessage, which knows the event")
        }
    }

    @Test
    fun `the state keeps the last three lines and the Narrator's last line, and the versioned model is recorded`() {
        FakeJev().use { fake ->
            fake.enqueue(FakeJev.chat(model = "jev-1.13.1"))
            val services = services(client(fake))
            val lines = (1..5).map { ChatLine("p$it", "line $it") }

            val verdict = triage(services, ChatTriageInput(ChatLine("Mimosa", "et moi alors ?"), lines, narratorLast = "Silence, mortels."))

            val state = fake.requests.single().json.getValue("state").jsonObject
            assertEquals(listOf("line 3", "line 4", "line 5"), state.getValue("recent_chat").jsonArray.map { it.jsonObject.getValue("text").jsonPrimitive.content })
            assertEquals("Silence, mortels.", state.getValue("narrator_last_message").jsonPrimitive.content)
            assertEquals("jev:jev-1.13.1", verdict.source)
            assertEquals("jev-1.13.1", services.traces.llmCalls.single().model)
        }
    }

    @Test
    fun `a timeout falls back to the heuristic within the delay`() {
        FakeJev().use { fake ->
            fake.enqueue(FakeJev.chat(delayMs = 5_000))
            val services = services(client(fake, timeoutMs = 400))

            val started = System.nanoTime()
            val verdict = triage(services)
            val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

            assertTrue(tookMs < 1_500, "gave up after the delay, took $tookMs ms")
            assertEquals(HeuristicReflexes.SOURCE, verdict.source)
            assertTrue(verdict.toNarrator >= ReflexThresholds.ADDRESSED, "the heuristic sees the name: $verdict")
            assertNull(verdict.personality)
            val row = services.traces.llmCalls.single()
            assertEquals("timeout after 400 ms", row.error)
            assertNull(row.httpStatus)
            assertEquals(1, row.attempts)
            assertNull(row.inputTokens)
        }
    }

    @Test
    fun `a 401 cuts Jev until a new client is built, with one error line`() {
        FakeJev().use { fake ->
            fake.enqueue(FakeJev.error(401, """{"detail": "invalid api key"}"""), FakeJev.chat())
            val jev = client(fake)
            val services = services(jev)

            LogCapture().use { logs ->
                val first = triage(services)
                val second = triage(services)

                assertEquals(HeuristicReflexes.SOURCE, first.source)
                assertEquals(HeuristicReflexes.SOURCE, second.source)
                assertEquals(1, fake.requests.size, "401 is never retried, and nothing is sent after it")
                val row = services.traces.llmCalls.single()
                assertEquals(401, row.httpStatus)
                assertEquals("HTTP 401 unauthorized: {\"detail\": \"invalid api key\"}", row.error)
                assertEquals(1, logs.messages.count { "Jev cut until /narrator reload" in it }, logs.text)
                assertContains(logs.text, "check the key in the environment variable TYPESAFE_API_KEY")
            }
            assertTrue(jev.status().startsWith("cut until /narrator reload: HTTP 401"), jev.status())

            // `/narrator reload` builds a new client: Jev answers again.
            val reloaded = services(client(fake))
            assertEquals("jev:jev-1.13.0", triage(reloaded).source)
            assertEquals(2, fake.requests.size)
        }
    }

    @Test
    fun `a 422 cuts Jev too, and the error says what was wrong`() {
        FakeJev().use { fake ->
            fake.enqueue(FakeJev.error(422, """{"detail": [{"loc": ["questions", "tone"], "msg": "field required"}]}"""))
            val jev = client(fake)
            val result = runBlocking { jev.evaluate(JsonObject(emptyMap()), JevQuestions.CHAT) }

            val call = assertIs<JevResult.Failed>(result).call
            assertEquals(422, call.httpStatus)
            assertContains(call.error.orEmpty(), "HTTP 422 unprocessable request: {\"detail\": [{\"loc\": [\"questions\", \"tone\"]")
            assertIs<JevResult.Skipped>(runBlocking { jev.evaluate(JsonObject(emptyMap()), JevQuestions.CHAT) })
            assertEquals(1, fake.requests.size)
        }
    }

    @Test
    fun `a 429 is retried once after a short backoff`() {
        FakeJev().use { fake ->
            fake.enqueue(FakeJev.error(429), FakeJev.chat())
            val services = services(client(fake))

            val started = System.nanoTime()
            val verdict = triage(services)
            val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

            assertEquals("jev:jev-1.13.0", verdict.source)
            assertEquals(2, fake.requests.size)
            assertTrue(tookMs >= JevClient.BACKOFF_MIN_MS, "waited before retrying: $tookMs ms")
            val row = services.traces.llmCalls.single()
            assertEquals(2, row.attempts)
            assertEquals(200, row.httpStatus)
            assertNull(row.error)
        }
    }

    @Test
    fun `a second 429 is not retried again`() {
        FakeJev().use { fake ->
            fake.enqueue(FakeJev.error(429), FakeJev.error(429), FakeJev.chat())
            val services = services(client(fake))

            val verdict = triage(services)

            assertEquals(HeuristicReflexes.SOURCE, verdict.source)
            assertEquals(2, fake.requests.size)
            val row = services.traces.llmCalls.single()
            assertEquals(2, row.attempts)
            assertEquals(429, row.httpStatus)
            assertEquals("HTTP 429 rate limited: {\"detail\": \"error 429\"}", row.error)
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [529, 500, 503])
    fun `an overload or a server error is retried once`(status: Int) {
        FakeJev().use { fake ->
            fake.enqueue(FakeJev.error(status), FakeJev.chat())
            val services = services(client(fake))

            assertEquals("jev:jev-1.13.0", triage(services).source)
            assertEquals(2, services.traces.llmCalls.single().attempts)
        }
    }

    @Test
    fun `no retry when the time left is too short`() {
        FakeJev().use { fake ->
            fake.enqueue(FakeJev.Response(503, "{}", delayMs = 250), FakeJev.chat())
            val jev = client(fake, timeoutMs = 400)

            val call = assertIs<JevResult.Failed>(runBlocking { jev.evaluate(JevQuestions.chatState(input), JevQuestions.CHAT) }).call

            assertEquals(1, fake.requests.size)
            assertEquals("HTTP 503 server error, no time left to retry", call.error)
        }
    }

    @Test
    fun `other statuses fail at once`() {
        FakeJev().use { fake ->
            fake.enqueue(FakeJev.error(404, ""), FakeJev.chat())
            val call = assertIs<JevResult.Failed>(runBlocking { client(fake).evaluate(JevQuestions.chatState(input), JevQuestions.CHAT) }).call

            assertEquals(1, fake.requests.size)
            assertEquals(404, call.httpStatus)
            assertEquals("HTTP 404 unexpected status", call.error)
        }
    }

    @Test
    fun `five failures in a row open the circuit for five minutes, then a probe decides`() {
        FakeJev().use { fake ->
            val shift = AtomicLong()
            val jev = client(fake) { System.nanoTime() + shift.get() }
            val services = services(jev)
            fun fail() = fake.enqueue(FakeJev.error(404))
            fun advance(ms: Long) = shift.addAndGet(TimeUnit.MILLISECONDS.toNanos(ms))

            LogCapture().use { logs ->
                repeat(JevClient.FAILURES_TO_OPEN) {
                    fail()
                    assertEquals(HeuristicReflexes.SOURCE, triage(services).source)
                }
                assertEquals(5, fake.requests.size)
                assertEquals(1, logs.messages.count { "circuit open" in it }, logs.text)
                assertContains(logs.text, "the heuristic answers for 5 min")
                assertTrue(jev.status().startsWith("circuit open for "), jev.status())

                // Open: nothing is sent (the fake would answer 500), no row is written.
                assertEquals(HeuristicReflexes.SOURCE, triage(services).source)
                assertEquals(5, fake.requests.size)
                assertEquals(5, services.traces.llmCalls.size)

                // After five minutes, one probe: it fails, the circuit opens again at once.
                advance(JevClient.OPEN_FOR_MS - 1_000)
                assertEquals(HeuristicReflexes.SOURCE, triage(services).source)
                assertEquals(5, fake.requests.size, "still open")
                advance(2_000)
                fake.requests.clear()
                fake.enqueue(FakeJev.error(503), FakeJev.error(503))
                assertEquals(HeuristicReflexes.SOURCE, triage(services).source)
                assertEquals(2, fake.requests.size, "the probe went out and was retried once")
                triage(services)
                assertEquals(2, fake.requests.size, "open again")

                // The next probe succeeds: the circuit closes, and one failure does not reopen it.
                advance(JevClient.OPEN_FOR_MS + 1_000)
                fake.enqueue(FakeJev.chat())
                assertEquals("jev:jev-1.13.0", triage(services).source)
                assertContains(logs.text, "circuit closed: Jev answers again")
                assertEquals("active", jev.status())
                fail()
                triage(services)
                fake.enqueue(FakeJev.chat())
                assertEquals("jev:jev-1.13.0", triage(services).source)
            }
        }
    }

    @Test
    fun `an answer that is missing, of the wrong type or out of range is a failure`() {
        val broken: List<Pair<String, String>> = listOf(
            "not json" to "unexpected response: not a JSON object",
            """{"model": "jev-1.13.0"}""" to "unexpected response: no answers",
            chatBody { remove(JevQuestions.HUMOR) } to "unexpected response: no valid answer to humor",
            chatBody { put(JevQuestions.TONE, noul(0.5)) } to "unexpected response: no valid answer to tone",
            chatBody { put(JevQuestions.TONE, choice("furious")) } to "unexpected response: no valid answer to tone",
            chatBody { put(JevQuestions.ADDRESSED, noul(1.7)) } to "unexpected response: no valid answer to addressed_to_narrator",
        )
        FakeJev().use { fake ->
            broken.forEach { (body, error) ->
                // A new client each time: five failures in a row would open the circuit.
                val services = services(client(fake))
                fake.enqueue(FakeJev.Response(200, body))
                assertEquals(HeuristicReflexes.SOURCE, triage(services).source, body)
                val row = services.traces.llmCalls.last()
                assertEquals(error, row.error, body)
                assertEquals(200, row.httpStatus)
            }
        }
    }

    @Test
    fun `a heartbeat asks two questions and gives the hook`() {
        FakeJev().use { fake ->
            fake.enqueue(FakeJev.heartbeat(0.31, "comedy"), FakeJev.heartbeat(0.8, "other"))
            val services = services(client(fake))
            val reflexes = JevReflexes(services)
            val summary = listOf("Bartok: mined 312 (5 diamond_ore y=-54..-49)", "Mimosa: placed 1204 near 350 70 -220")

            val first = runBlocking { reflexes.heartbeat(8, summary, listOf("Bartok", "Mimosa")) }
            val second = runBlocking { reflexes.heartbeat(8, summary, listOf("Bartok", "Mimosa")) }

            val body = fake.requests.first().json
            assertEquals(listOf("worth_intervening", "hook_type"), body.getValue("questions").jsonObject.keys.toList())
            assertEquals(
                listOf("danger", "progress", "comedy", "rivalry", "exploration", "other"),
                body.getValue("questions").jsonObject.criteria("hook_type").keys.toList(),
            )
            val state = body.getValue("state").jsonObject
            assertEquals(8, state.getValue("window_minutes").jsonPrimitive.int)
            assertEquals(summary, state.getValue("summary").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(listOf("Bartok", "Mimosa"), state.getValue("players_online").jsonArray.map { it.jsonPrimitive.content })

            assertEquals(HeartbeatVerdict(0.31, "comedy", "jev:jev-1.13.0", first.latencyMs), first)
            assertEquals(0.8, second.worth)
            assertNull(second.hook, "other is no hook")
            assertTrue(services.traces.llmCalls.all { it.purpose == LlmCallRecord.Purpose.HEARTBEAT_FILTER })
            assertEquals(2, services.traces.llmCalls.size)
        }
    }

    @Test
    fun `a heartbeat Jev cannot judge wakes the Director`() {
        FakeJev().use { fake ->
            fake.enqueue(FakeJev.error(404))
            val verdict = runBlocking { JevReflexes(services(client(fake))).heartbeat(5, listOf("Bartok: mined 3 stone"), listOf("Bartok")) }

            assertEquals(1.0, verdict.worth)
            assertEquals(HeuristicReflexes.SOURCE, verdict.source)
        }
    }

    @Test
    fun `without a client the heuristic answers and nothing is recorded`() {
        val services = services(null)
        val verdict = triage(services)
        val heartbeat = runBlocking { JevReflexes(services).heartbeat(5, listOf("Bartok: mined 3 stone"), listOf("Bartok")) }

        assertEquals(HeuristicReflexes.SOURCE, verdict.source)
        assertEquals(HeartbeatVerdict(1.0, null, HeuristicReflexes.SOURCE, 0), heartbeat)
        assertTrue(services.traces.llmCalls.isEmpty())
    }

    @Test
    fun `a client exists only when Jev is enabled and its key is set`() {
        assertNull(JevClient.create(JevConfig(enabled = false), env = { key }))
        assertNull(JevClient.create(JevConfig(), env = { null }))
        assertNull(JevClient.create(JevConfig(), env = { "   " }))
        assertNull(JevClient.create(JevConfig(apiKeyEnv = "OTHER"), env = { name -> if (name == "TYPESAFE_API_KEY") key else null }))
        val client = assertNotNull(JevClient.create(JevConfig(), env = { name -> if (name == "TYPESAFE_API_KEY") " $key " else null }))
        client.use {
            assertEquals("jev-1.13.0", it.model)
            assertEquals("active", it.status())
            assertFalse(key in it.toString())
        }
    }

    @Test
    fun `a replaced client finishes its call in flight, then sends nothing`() {
        FakeJev().use { fake ->
            fake.enqueue(FakeJev.chat(delayMs = 300), FakeJev.chat())
            val jev = client(fake)

            val result = runBlocking {
                val call = async(Dispatchers.IO) { jev.evaluate(JevQuestions.chatState(input), JevQuestions.CHAT) }
                while (fake.requests.isEmpty()) Thread.sleep(5)
                jev.closeWhenIdle()
                call.await()
            }

            assertIs<JevResult.Answered>(result)
            assertEquals(JevResult.Skipped("client closed"), runBlocking { jev.evaluate(JevQuestions.chatState(input), JevQuestions.CHAT) })
            assertEquals(1, fake.requests.size)
            assertEquals("closed", jev.status())
        }
    }

    @Test
    fun `the key appears in no log line, trace or status`() {
        FakeJev().use { fake ->
            val logs = LogCapture()
            val services = logs.use {
                fake.enqueue(
                    FakeJev.chat(),
                    FakeJev.error(429), FakeJev.error(503),
                    FakeJev.chat(delayMs = 2_000),
                    FakeJev.Response(200, "not json"),
                    FakeJev.error(401),
                )
                val services = services(client(fake, timeoutMs = 700))
                repeat(6) { triage(services) }
                assertNotNull(JevClient.create(JevConfig(), env = { key })).close()
                services
            }
            assertEquals(5, services.traces.llmCalls.size)
            assertTrue(fake.requests.all { it.header("Authorization") == "Bearer $key" }, "the key does reach Jev")
            assertTrue(logs.messages.size >= 5, "the scenario was logged: ${logs.text}")
            assertFalse(key in logs.text, "a log line holds the key")
            assertFalse(services.traces.llmCalls.any { key in it.toString() }, "a trace holds the key")
            assertFalse(key in services.settings.get().jev!!.status())
        }
    }

    private fun JsonObject.criteria(question: String): JsonObject = getValue(question).jsonObject.getValue("criteria").jsonObject

    private fun noul(value: Double) = buildJsonObject {
        put("type", "noul")
        put("noul", value)
    }

    private fun choice(choice: String) = buildJsonObject {
        put("type", "choice")
        put("choice", choice)
        put("confidence", 0.9)
    }

    /** The body of a full chat answer, changed by [edit]. */
    private fun chatBody(edit: MutableMap<String, JsonObject>.() -> Unit): String {
        val full = kotlinx.serialization.json.Json.parseToJsonElement(FakeJev.chat().body).jsonObject
        val answers = full.getValue("answers").jsonObject.mapValuesTo(LinkedHashMap()) { it.value.jsonObject }
        answers.edit()
        return JsonObject(full + ("answers" to JsonObject(answers))).toString()
    }
}
