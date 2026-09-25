package io.github.clemsix6.narrator2.llm

import io.github.clemsix6.narrator2.config.ConfigException
import io.github.clemsix6.narrator2.config.ConfigStore
import io.github.clemsix6.narrator2.testing.FakeAnthropic
import io.github.clemsix6.narrator2.testing.LogCapture
import io.github.clemsix6.narrator2.testing.SAY_TOOL
import io.github.clemsix6.narrator2.testing.budgetExceeded
import io.github.clemsix6.narrator2.testing.httpError
import io.github.clemsix6.narrator2.testing.reply
import io.github.clemsix6.narrator2.testing.streamError
import io.github.clemsix6.narrator2.testing.testConfig
import io.github.clemsix6.narrator2.testing.testConversation
import io.github.clemsix6.narrator2.testing.toolResult
import io.github.clemsix6.narrator2.trace.Transcripts
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The LLMGW key reaches the gateway, and nothing else: no log line, transcript, error message or
 * operator description. (The `/narrator status` output is checked in game, by the GameTests.)
 */
class ApiKeySecrecyTest {
    @TempDir
    lateinit var dir: Path

    private val key = "sk-llmgw-SECRET-7d41c0ffee"

    @Test
    fun `the key appears in no log, transcript or error`() {
        val errors = mutableListOf<Throwable>()
        val logs = LogCapture()
        logs.use {
            FakeAnthropic().use { fake ->
                lateinit var toolUseId: String
                fake.enqueue(reply { thinking("Greet."); toolUseId = toolUse("say", """{"text": "Hi"}""") })
                fake.enqueue(reply { text("Done.") })
                fake.enqueue(httpError(401))
                fake.enqueue(httpError(529), streamError(), httpError(500))
                fake.enqueue(budgetExceeded("2026-09-26T00:00:00Z"))
                fake.enqueue(reply { refusal() })

                val transcripts = Transcripts(dir.resolve("transcripts"), enabled = true)
                ClaudeClient(testConfig(fake.baseUrl, maxRetries = 2), key).use { client ->
                    runBlocking {
                        val conversation = testConversation(tools = listOf(SAY_TOOL), transcripts = transcripts)
                        client.send(conversation)
                        conversation.append(toolResult(toolUseId, "sent"))
                        client.send(conversation)
                        repeat(3) {
                            runCatching { client.send(testConversation(transcripts = transcripts)) }.exceptionOrNull()?.let(errors::add)
                        }
                        client.send(testConversation(transcripts = transcripts))
                    }
                }
                assertTrue(fake.requests.all { it.header("X-Api-Key") == key }, "the key does reach the gateway")
            }
            val closedPort = ServerSocket(0).use { it.localPort }
            ClaudeClient(testConfig("http://127.0.0.1:$closedPort", maxRetries = 1), key).use { client ->
                runCatching { runBlocking { client.send(testConversation()) } }.exceptionOrNull()?.let(errors::add)
            }
        }

        assertEquals(4, errors.size, "401, retries exhausted, budget, unreachable: $errors")
        errors.forEach { error ->
            assertFalse(key in error.stackTraceToString(), "error: ${error.javaClass.simpleName}")
            assertFalse(key in describeFailure(error))
        }
        assertTrue(logs.messages.size > 10, "the scenario was logged")
        assertFalse(key in logs.text, "the key is in the logs")
        val transcriptFiles = Files.walk(dir).use { paths -> paths.filter { it.isRegularFile() }.toList() }
        assertTrue(transcriptFiles.size >= 10, "transcripts were written: $transcriptFiles")
        transcriptFiles.forEach { file -> assertFalse(key in file.readText(), "the key is in $file") }
    }

    @Test
    fun `a configuration error never quotes the file`() {
        val file = dir.resolve(ConfigStore.FILE_NAME)
        file.writeText("""{ "gateway": { "apiKey": "$key", "baseUrl": "http://127.0.0.1:8088" oops } }""")

        val logs = LogCapture()
        val error = logs.use { assertFailsWith<ConfigException> { ConfigStore.load(dir) } }
        assertFalse(key in error.stackTraceToString())
        assertEquals(null, error.cause, "the parser's message, which quotes the file, is dropped")
        assertFalse(key in logs.text)
    }
}
