package io.github.clemsix6.narrator2.testing

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.MissingNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * A fake Anthropic Messages API for tests: no network, no real Claude.
 *
 * It serves `POST /v1/messages` as an SSE stream, exactly like the real API with `stream: true`,
 * answering each request with the next scripted [FakeResponse], in order. Every request is recorded
 * (headers and JSON body) so tests can check what the client sent.
 *
 * ```
 * FakeAnthropic().use { fake ->
 *     fake.enqueue(reply { thinking("Checking the line."); text("Pong.") })
 *     fake.enqueue(reply { toolUse("say", """{"text": "Hello"}""") })  // stop_reason tool_use
 *     fake.enqueue(httpError(529))                                       // overloaded_error
 *     fake.enqueue { request -> reply { text("${request.json.path("messages").size()} messages") } }
 *
 *     // Point the client at fake.baseUrl, run the code under test, then check what it sent:
 *     fake.requests[0].json.path("model").asText()
 *     fake.requests[0].header("X-Session-ID")
 * }
 * ```
 *
 * A request that finds the script empty gets an HTTP 400 `invalid_request_error` (which the SDK does
 * not retry) saying so, and is counted in [unscripted].
 */
class FakeAnthropic : AutoCloseable {
    private val script = ConcurrentLinkedQueue<(RecordedRequest) -> FakeResponse>()
    private val recorded = CopyOnWriteArrayList<RecordedRequest>()
    private val executor: ExecutorService = Executors.newCachedThreadPool { task ->
        Thread(task, "fake-anthropic").apply { isDaemon = true }
    }
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    init {
        server.createContext("/") { exchange -> handle(exchange) }
        server.executor = executor
        server.start()
    }

    /** Base URL for the client; the SDK appends `/v1/messages`. */
    val baseUrl: String = "http://127.0.0.1:${server.address.port}"

    /** Every request received so far, in order. */
    val requests: List<RecordedRequest> get() = recorded.toList()

    /** Scripted responses not served yet. */
    val pending: Int get() = script.size

    /** Requests that arrived when the script was empty. */
    @Volatile
    var unscripted: Int = 0
        private set

    /** Queues fixed responses, served one per request in order. */
    fun enqueue(vararg responses: FakeResponse) {
        responses.forEach { response -> script.add { response } }
    }

    /** Queues a response computed from the request it answers, e.g. to echo a `tool_use` id. */
    fun enqueue(responder: (RecordedRequest) -> FakeResponse) {
        script.add(responder)
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun handle(exchange: HttpExchange) {
        try {
            val request = RecordedRequest(
                method = exchange.requestMethod,
                path = exchange.requestURI.path,
                headers = exchange.requestHeaders,
                body = String(exchange.requestBody.readAllBytes(), Charsets.UTF_8),
            )
            recorded += request
            val response = when {
                request.method != "POST" || request.path != "/v1/messages" ->
                    httpError(404, message = "FakeAnthropic only serves POST /v1/messages (got ${request.method} ${request.path})")
                !request.json.path("stream").asBoolean(false) ->
                    httpError(400, message = "FakeAnthropic only serves stream=true requests")
                else -> script.poll()?.invoke(request) ?: run {
                    unscripted++
                    httpError(400, message = "FakeAnthropic: no scripted response left for request #${recorded.size}")
                }
            }
            response.writeTo(exchange, request)
        } catch (e: Exception) {
            System.err.println("FakeAnthropic: failed to respond")
            e.printStackTrace()
        } finally {
            exchange.close()
        }
    }
}

/** A request received by [FakeAnthropic]. */
class RecordedRequest(
    val method: String,
    val path: String,
    headers: Map<String, List<String>>,
    /** Raw body, as sent. */
    val body: String,
) {
    private val headers: Map<String, List<String>> = headers.mapKeys { it.key.lowercase() }

    /** Lower-case names of every header received. */
    val headerNames: Set<String> get() = headers.keys

    /** First value of header [name] (case-insensitive), or null. */
    fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()

    /** The body as JSON; a missing node when it is not JSON, so `json.path(…)` chains never throw. */
    val json: JsonNode by lazy { runCatching { MAPPER.readTree(body) }.getOrNull() ?: MissingNode.getInstance() }
}

/** What [FakeAnthropic] answers to one request. Built with [reply], [httpError] or [streamError]. */
sealed class FakeResponse {
    internal abstract fun writeTo(exchange: HttpExchange, request: RecordedRequest)
}

/**
 * A streamed assistant message: `message_start`, a `ping`, then each content block with its deltas
 * (text and JSON arguments arrive in several chunks, as they do from the real API), `message_delta`
 * with the stop reason and `message_stop`.
 */
fun reply(build: ReplyBuilder.() -> Unit): FakeResponse = ReplyBuilder().apply(build).build()

/**
 * An HTTP error with the API's JSON error body. Errors the SDK retries (429, 5xx, 529) carry
 * `retry-after-ms: 10` so that retry tests stay fast.
 */
fun httpError(status: Int, type: String = errorTypeOf(status), message: String = "simulated error ($status)"): FakeResponse =
    HttpErrorResponse(status, type, message)

/** A stream that starts normally, then fails with an `error` event (e.g. overloaded mid-answer). */
fun streamError(type: String = "overloaded_error", message: String = "Overloaded"): FakeResponse =
    StreamErrorResponse(type, message)

/** Builds a scripted assistant message; see [reply]. */
class ReplyBuilder internal constructor() {
    private val blocks = mutableListOf<Block>()
    private var stopReason: String? = null
    private var stopDetails: ObjectNode? = null
    private var usage = Usage()

    /** A thinking block whose text is [summary], as returned with `thinking.display: summarized`. */
    fun thinking(summary: String) {
        blocks += Block.Thinking(summary)
    }

    fun text(text: String) {
        blocks += Block.Text(text)
    }

    /**
     * A call to tool [name]; [input] is the JSON object of its arguments. Returns the `tool_use` id,
     * unique across every fake server of the test run unless given.
     */
    fun toolUse(name: String, input: String = "{}", id: String = "toolu_fake_%03d".format(TOOL_USE_IDS.incrementAndGet())): String {
        require(MAPPER.readTree(input).isObject) { "tool_use input must be a JSON object: $input" }
        blocks += Block.ToolUse(id, name, input)
        return id
    }

    /** Overrides the stop reason; by default `tool_use` when the reply calls a tool, else `end_turn`. */
    fun stopReason(reason: String) {
        stopReason = reason
    }

    /** Ends the reply with `stop_reason: refusal` and its `stop_details`. */
    fun refusal(category: String = "cyber", explanation: String = "Simulated refusal.") {
        stopReason = "refusal"
        stopDetails = MAPPER.createObjectNode().put("type", "refusal").put("category", category).put("explanation", explanation)
    }

    /** Token counts reported for this reply. */
    fun usage(input: Long = 10, output: Long = 10, cacheRead: Long = 0, cacheWrite: Long = 0) {
        usage = Usage(input, output, cacheRead, cacheWrite)
    }

    internal fun build(): FakeResponse {
        val stop = stopReason ?: if (blocks.any { it is Block.ToolUse }) "tool_use" else "end_turn"
        return ReplyResponse(blocks.toList(), stop, stopDetails, usage)
    }
}

private val MAPPER = ObjectMapper()
private val TOOL_USE_IDS = AtomicInteger()
private val IDS = AtomicInteger()

private fun nextId(prefix: String) = "${prefix}_fake_%03d".format(IDS.incrementAndGet())

private fun errorTypeOf(status: Int): String = when (status) {
    400 -> "invalid_request_error"
    401 -> "authentication_error"
    403 -> "permission_error"
    404 -> "not_found_error"
    413 -> "request_too_large"
    429 -> "rate_limit_error"
    529 -> "overloaded_error"
    else -> "api_error"
}

private sealed class Block {
    data class Thinking(val summary: String) : Block()
    data class Text(val text: String) : Block()
    data class ToolUse(val id: String, val name: String, val input: String) : Block()
}

private data class Usage(val input: Long = 10, val output: Long = 10, val cacheRead: Long = 0, val cacheWrite: Long = 0)

private class ReplyResponse(
    val blocks: List<Block>,
    val stopReason: String,
    val stopDetails: ObjectNode?,
    val usage: Usage,
) : FakeResponse() {
    override fun writeTo(exchange: HttpExchange, request: RecordedRequest) {
        val sse = openStream(exchange)
        sse.messageStart(request, usage)
        sse.event("ping", obj("ping"))
        blocks.forEachIndexed { index, block ->
            when (block) {
                is Block.Thinking -> {
                    sse.blockStart(index, obj("thinking").put("thinking", "").put("signature", ""))
                    chunks(block.summary).forEach { sse.blockDelta(index, obj("thinking_delta").put("thinking", it)) }
                    sse.blockDelta(index, obj("signature_delta").put("signature", nextId("sig")))
                }
                is Block.Text -> {
                    sse.blockStart(index, obj("text").put("text", ""))
                    chunks(block.text).forEach { sse.blockDelta(index, obj("text_delta").put("text", it)) }
                }
                is Block.ToolUse -> {
                    val start = obj("tool_use").put("id", block.id).put("name", block.name)
                    start.putObject("input")
                    sse.blockStart(index, start)
                    chunks(block.input).forEach { sse.blockDelta(index, obj("input_json_delta").put("partial_json", it)) }
                }
            }
            sse.event("content_block_stop", obj("content_block_stop").put("index", index))
        }
        val delta = MAPPER.createObjectNode().put("stop_reason", stopReason).putNull("stop_sequence")
        stopDetails?.let { delta.set<JsonNode>("stop_details", it) }
        val messageDelta = obj("message_delta")
        messageDelta.set<JsonNode>("delta", delta)
        messageDelta.putObject("usage").put("output_tokens", usage.output)
        sse.event("message_delta", messageDelta)
        sse.event("message_stop", obj("message_stop"))
    }
}

private class StreamErrorResponse(val type: String, val message: String) : FakeResponse() {
    override fun writeTo(exchange: HttpExchange, request: RecordedRequest) {
        val sse = openStream(exchange)
        sse.messageStart(request, Usage())
        val error = obj("error")
        error.putObject("error").put("type", type).put("message", message)
        sse.event("error", error)
    }
}

private class HttpErrorResponse(val status: Int, val type: String, val message: String) : FakeResponse() {
    override fun writeTo(exchange: HttpExchange, request: RecordedRequest) {
        val body = obj("error")
        body.putObject("error").put("type", type).put("message", message)
        val requestId = nextId("req")
        body.put("request_id", requestId)
        val bytes = MAPPER.writeValueAsBytes(body)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.responseHeaders.add("request-id", requestId)
        if (status == 429 || status >= 500) exchange.responseHeaders.add("retry-after-ms", "10")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }
}

/** Sends the SSE response headers and returns a writer for its events. */
private fun openStream(exchange: HttpExchange): SseWriter {
    exchange.responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
    exchange.responseHeaders.add("request-id", nextId("req"))
    exchange.sendResponseHeaders(200, 0)
    return SseWriter(exchange.responseBody)
}

private class SseWriter(private val out: OutputStream) {
    fun event(name: String, data: JsonNode) {
        out.write("event: $name\ndata: ${MAPPER.writeValueAsString(data)}\n\n".toByteArray(Charsets.UTF_8))
        out.flush()
    }

    fun messageStart(request: RecordedRequest, usage: Usage) {
        val message = obj("message")
            .put("id", nextId("msg"))
            .put("role", "assistant")
            .put("model", request.json.path("model").asText("claude-opus-5-5"))
            .putNull("stop_reason")
            .putNull("stop_sequence")
        message.putArray("content")
        message.putObject("usage")
            .put("input_tokens", usage.input)
            .put("output_tokens", 1)
            .put("cache_read_input_tokens", usage.cacheRead)
            .put("cache_creation_input_tokens", usage.cacheWrite)
        val start = obj("message_start")
        start.set<JsonNode>("message", message)
        event("message_start", start)
    }

    fun blockStart(index: Int, block: ObjectNode) {
        val start = obj("content_block_start").put("index", index)
        start.set<JsonNode>("content_block", block)
        event("content_block_start", start)
    }

    fun blockDelta(index: Int, delta: ObjectNode) {
        val data = obj("content_block_delta").put("index", index)
        data.set<JsonNode>("delta", delta)
        event("content_block_delta", data)
    }
}

private fun obj(type: String): ObjectNode = MAPPER.createObjectNode().put("type", type)

/** Splits [text] into up to three chunks, so accumulation across deltas is exercised. */
private fun chunks(text: String): List<String> = if (text.length < 2) listOf(text) else text.chunked((text.length + 2) / 3)
