package io.github.clemsix6.narrator2.testing

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.clemsix6.narrator2.reflex.JevQuestions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A fake Jev (TypeSafe) server on the loopback interface, built on the JDK's `HttpServer`: it records
 * every request and answers with the queued responses, in order. With nothing queued it answers 500
 * `no response queued`, which a test sees as a Jev failure.
 */
class FakeJev : AutoCloseable {
    /** A request as received. */
    class Request(val method: String, val path: String, val headers: Map<String, List<String>>, val body: String) {
        val json: JsonObject get() = Json.parseToJsonElement(body).jsonObject

        fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    }

    /** A response to send, after [delayMs]. */
    class Response(val status: Int, val body: String, val delayMs: Long = 0, val headers: Map<String, String> = emptyMap())

    private val executor: ExecutorService = Executors.newCachedThreadPool { task -> Thread(task, "fake-jev").apply { isDaemon = true } }
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val queue = ConcurrentLinkedQueue<Response>()

    val requests = CopyOnWriteArrayList<Request>()

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange -> handle(exchange) }
        server.executor = executor
        server.start()
    }

    fun enqueue(vararg responses: Response) {
        queue.addAll(responses)
    }

    private fun handle(exchange: HttpExchange) {
        exchange.use {
            requests += Request(
                it.requestMethod,
                it.requestURI.path,
                it.requestHeaders.toMap(),
                it.requestBody.readBytes().decodeToString(),
            )
            val response = queue.poll() ?: Response(500, """{"error": "no response queued"}""")
            try {
                if (response.delayMs > 0) Thread.sleep(response.delayMs)
                val bytes = response.body.encodeToByteArray()
                it.responseHeaders.add("Content-Type", "application/json")
                response.headers.forEach { (name, value) -> it.responseHeaders.add(name, value) }
                it.sendResponseHeaders(response.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
                if (bytes.isNotEmpty()) it.responseBody.write(bytes)
            } catch (_: IOException) {
                // The client gave up (timeout): nothing to answer.
            } catch (_: InterruptedException) {
                // The server is closing.
            }
        }
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    companion object {
        const val MODEL = "jev-1.13.0"

        /** An error response. */
        fun error(status: Int, body: String = """{"detail": "error $status"}""") = Response(status, body)

        /** A 200 with the 9 answers of a chat triage. */
        fun chat(
            addressed: Double = 0.92,
            tone: String = "hostile",
            toneConfidence: Double = 0.81,
            manipulation: Double = 0.1,
            type: String = "provocation",
            typeConfidence: Double = 0.7,
            politeness: Double = 0.05,
            boastfulness: Double = 0.2,
            helpfulness: Double = 0.1,
            aggressiveness: Double = 0.85,
            humor: Double = 0.3,
            model: String = MODEL,
            delayMs: Long = 0,
        ) = Response(
            200,
            answers(model) {
                noul(JevQuestions.ADDRESSED, addressed)
                choice(JevQuestions.TONE, tone, toneConfidence)
                noul(JevQuestions.MANIPULATION, manipulation)
                choice(JevQuestions.MESSAGE_TYPE, type, typeConfidence)
                noul(JevQuestions.POLITENESS, politeness)
                noul(JevQuestions.BOASTFULNESS, boastfulness)
                noul(JevQuestions.HELPFULNESS, helpfulness)
                noul(JevQuestions.AGGRESSIVENESS, aggressiveness)
                noul(JevQuestions.HUMOR, humor)
            },
            delayMs,
            headers = mapOf("x-request-id" to "req-fake-1"),
        )

        /** A 200 with the 2 answers of a heartbeat. */
        fun heartbeat(worth: Double, hook: String, model: String = MODEL) = Response(
            200,
            answers(model) {
                noul(JevQuestions.WORTH, worth)
                choice(JevQuestions.HOOK, hook, 0.75)
            },
        )

        /** A response body with the answers [build] gives and made-up usage. */
        fun answers(model: String = MODEL, build: AnswersBuilder.() -> Unit): String = buildJsonObject {
            put("model", model)
            put("answers", AnswersBuilder().apply(build).build())
            putJsonObject("usage") {
                put("input_tokens", 412)
                put("output_tokens", 63)
            }
        }.toString()
    }

    class AnswersBuilder {
        private val answers = LinkedHashMap<String, JsonObject>()

        fun noul(key: String, value: Double) {
            answers[key] = buildJsonObject {
                put("type", "noul")
                put("noul", value)
            }
        }

        fun choice(key: String, choice: String, confidence: Double) {
            answers[key] = buildJsonObject {
                put("type", "choice")
                put("choice", choice)
                putJsonObject("probabilities") { put(choice, confidence) }
                put("confidence", confidence)
            }
        }

        fun raw(key: String, answer: JsonObject) {
            answers[key] = answer
        }

        fun build() = JsonObject(answers)
    }
}
