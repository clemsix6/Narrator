package io.github.clemsix6.narrator2.testing

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.MissingNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A fake Anthropic Messages API for tests: no network, no real Claude.
 *
 * It serves `POST /v1/messages` as an SSE stream, exactly like the real API with `stream: true`,
 * answering each request with the next scripted [FakeResponse]: first from the queue of the request's
 * `X-Session-ID` ([enqueueFor]), else from the global queue ([enqueue]). Every request is recorded
 * (headers and JSON body) so tests can check what the client sent.
 *
 * ```
 * FakeAnthropic().use { fake ->
 *     fake.enqueue(reply { thinking("Checking the line."); text("Pong.") })
 *     fake.enqueue(reply { toolUse("say", """{"text": "Hello"}""") })  // stop_reason tool_use
 *     fake.enqueue(httpError(529))                                       // overloaded_error
 *     fake.enqueue { request -> reply { text("${request.json.path("messages").size()} messages") } }
 *     fake.enqueueFor("director-1234", reply { text("Only for this conversation.") })
 *
 *     // Point the client at fake.baseUrl, run the code under test, then check what it sent:
 *     fake.requests[0].json.path("model").asText()
 *     fake.requests[0].header("X-Session-ID")
 * }
 * ```
 *
 * In strict mode (the default) it rejects, like the real API or LLMGW, with an HTTP 400 and an explicit
 * message, before consuming any script: an empty message or text block; a `tool_use` without its
 * `tool_result` in the next message; a forced `tool_choice`; a history that is not an append-only
 * continuation of the previous request with the same `X-Session-ID` (system prompt and tools
 * included); a `cache_control` whose TTL is not `1h` (LLMGW already marks the prompt with `1h`).
 *
 * A request that finds the script empty gets an HTTP 400 `invalid_request_error` saying so, and is
 * counted in [unscripted].
 */
class FakeAnthropic(val strict: Boolean = true) : AutoCloseable {
    private val script = ConcurrentLinkedQueue<(RecordedRequest) -> FakeResponse>()
    private val sessionScripts = ConcurrentHashMap<String, ConcurrentLinkedQueue<(RecordedRequest) -> FakeResponse>>()
    private val recorded = CopyOnWriteArrayList<RecordedRequest>()
    private val histories = HashMap<String, History>() // guarded by itself
    private val unscriptedCount = AtomicInteger()
    private val closed = AtomicBoolean(false)
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

    /** Messages of the requests rejected by strict mode, in order. */
    val rejections: MutableList<String> = CopyOnWriteArrayList()

    /** Scripted responses not served yet. */
    val pending: Int get() = script.size + sessionScripts.values.sumOf { it.size }

    /** Requests that arrived when the script was empty. */
    val unscripted: Int get() = unscriptedCount.get()

    /** Queues fixed responses, served one per request in order. */
    fun enqueue(vararg responses: FakeResponse) {
        responses.forEach { response -> script.add { response } }
    }

    /** Queues a response computed from the request it answers, e.g. to echo a `tool_use` id. */
    fun enqueue(responder: (RecordedRequest) -> FakeResponse) {
        script.add(responder)
    }

    /** Queues responses for the requests whose `X-Session-ID` is [sessionId]; they go before the global queue. */
    fun enqueueFor(sessionId: String, vararg responses: FakeResponse) {
        responses.forEach { response -> sessionScript(sessionId).add { response } }
    }

    fun enqueueFor(sessionId: String, responder: (RecordedRequest) -> FakeResponse) {
        sessionScript(sessionId).add(responder)
    }

    /** Stops the server; calling it again does nothing. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        server.stop(0)
        executor.shutdownNow()
    }

    private fun sessionScript(sessionId: String) = sessionScripts.computeIfAbsent(sessionId) { ConcurrentLinkedQueue() }

    private fun handle(exchange: HttpExchange) {
        try {
            val request = RecordedRequest(
                method = exchange.requestMethod,
                path = exchange.requestURI.path,
                headers = exchange.requestHeaders,
                body = String(exchange.requestBody.readAllBytes(), Charsets.UTF_8),
            )
            recorded += request
            val violation = if (strict) violation(request) else null
            val response = when {
                request.method != "POST" || request.path != "/v1/messages" ->
                    httpError(404, message = "FakeAnthropic only serves POST /v1/messages (got ${request.method} ${request.path})")
                !request.json.path("stream").asBoolean(false) ->
                    httpError(400, message = "FakeAnthropic only serves stream=true requests")
                violation != null -> {
                    rejections += violation
                    httpError(400, message = violation)
                }
                else -> nextResponse(request)
            }
            response.writeTo(exchange, request)
        } catch (e: InterruptedException) {
            // close() while a reply was held.
        } catch (e: Exception) {
            System.err.println("FakeAnthropic: failed to respond")
            e.printStackTrace()
        } finally {
            exchange.close()
        }
    }

    private fun nextResponse(request: RecordedRequest): FakeResponse {
        val responder = request.header("X-Session-ID")?.let { sessionScripts[it]?.poll() } ?: script.poll()
        return responder?.invoke(request) ?: run {
            unscriptedCount.incrementAndGet()
            httpError(400, message = "FakeAnthropic: no scripted response left for request #${recorded.size}")
        }
    }

    /** What the real API (or LLMGW) would reject in [request], or null. */
    private fun violation(request: RecordedRequest): String? {
        val body = request.json
        val messages = body.path("messages")
        for (i in 0 until messages.size()) {
            val content = messages[i].path("content")
            if ((content.isTextual && content.asText().isEmpty()) || (content.isArray && content.isEmpty)) {
                return "messages.$i: all messages must have non-empty content except for the optional final assistant message"
            }
            for (j in 0 until content.size()) {
                val block = content[j]
                if (block.path("type").asText() == "text" && block.path("text").asText().isEmpty()) {
                    return "messages.$i.content.$j.text: text content blocks must be non-empty"
                }
            }
        }
        for (i in 0 until messages.size()) {
            if (messages[i].path("role").asText() != "assistant") continue
            val calls = messages[i].path("content").filter { it.path("type").asText() == "tool_use" }.map { it.path("id").asText() }
            val next = messages.path(i + 1)
            val answered = if (next.path("role").asText() == "user") {
                next.path("content").filter { it.path("type").asText() == "tool_result" }.map { it.path("tool_use_id").asText() }
            } else {
                emptyList()
            }
            val missing = calls - answered.toSet()
            if (missing.isNotEmpty()) {
                return "messages.${i + 1}: `tool_use` ids were found without `tool_result` blocks immediately after: " +
                    "${missing.joinToString()}. Each `tool_use` block must have a corresponding `tool_result` block in the next message."
            }
        }
        val choice = body.path("tool_choice").path("type").asText()
        if (choice == "any" || choice == "tool") return "tool_choice: type \"tool\" and \"any\" are not supported for this model."
        val cache = body.path("cache_control")
        if (!cache.isMissingNode) {
            val ttl = cache.path("ttl").asText("5m")
            if (ttl != "1h") {
                return "Top-level cache_control has ttl='$ttl' but the target block already has cache_control with ttl='1h'. " +
                    "When both are specified on the same block, they must have matching TTLs."
            }
        }
        return appendOnlyViolation(request)
    }

    /** Checks [request] against the previous accepted request of its session, then remembers it. */
    private fun appendOnlyViolation(request: RecordedRequest): String? {
        val session = request.header("X-Session-ID") ?: return null
        val body = request.json
        val history = History(body.path("system"), body.path("tools"), body.path("messages"))
        synchronized(histories) {
            val previous = histories[session]
            if (previous != null) {
                val prefix = "FakeAnthropic (strict): session $session is not append-only:"
                if (previous.system != history.system) return "$prefix the system prompt changed"
                if (previous.tools != history.tools) return "$prefix the tools changed"
                for (i in 0 until previous.messages.size()) {
                    if (previous.messages[i] != history.messages.path(i)) return "$prefix messages.$i differs from the previous request"
                }
            }
            histories[session] = history
        }
        return null
    }

    private class History(val system: JsonNode, val tools: JsonNode, val messages: JsonNode)
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

/** What [FakeAnthropic] answers to one request. Built with [reply], [httpError], [budgetExceeded] or [streamError]. */
sealed class FakeResponse {
    internal abstract fun writeTo(exchange: HttpExchange, request: RecordedRequest)
}

/**
 * A streamed assistant message: `message_start`, a `ping`, then each content block with its deltas
 * (text and JSON arguments arrive in several chunks, as they do from the real API), `message_delta`
 * with the stop reason and `message_stop`. Usage is complete in both `message_start` and
 * `message_delta`.
 */
fun reply(build: ReplyBuilder.() -> Unit): FakeResponse = ReplyBuilder().apply(build).build()

/**
 * An HTTP error with the API's JSON error body. Errors worth retrying (429, 5xx, 529) carry
 * `retry-after-ms: 10` by default so that retry tests stay fast.
 */
fun httpError(
    status: Int,
    type: String = errorTypeOf(status),
    message: String = "simulated error ($status)",
    retryAfterMs: Long? = if (status == 429 || status >= 500) 10 else null,
    retryAfterSeconds: Long? = null,
): FakeResponse {
    val body = obj("error")
    body.putObject("error").put("type", type).put("message", message)
    val headers = buildMap {
        retryAfterMs?.let { put("retry-after-ms", it.toString()) }
        retryAfterSeconds?.let { put("retry-after", it.toString()) }
    }
    return HttpErrorResponse(status, body, headers)
}

/** LLMGW's HTTP 402 when the project's budget is spent: no `retry-after`, the reset time is in the body. */
fun budgetExceeded(resetAt: String, dimension: String = "cost", window: String = "day"): FakeResponse {
    val body = MAPPER.createObjectNode()
    body.putObject("error").put("type", "budget_exceeded").put("dimension", dimension).put("window", window).put("reset_at", resetAt)
    return HttpErrorResponse(402, body, emptyMap())
}

/** A stream that starts normally, then fails with an `error` event (e.g. overloaded mid-answer). */
fun streamError(type: String = "overloaded_error", message: String = "Overloaded"): FakeResponse =
    StreamErrorResponse(type, message)

/**
 * A point where a scripted reply stops, after `message_start`, until the test calls [release]; see
 * [ReplyBuilder.hold]. It tells whether the client was still there when the reply resumed.
 */
class Hold {
    private val reached = CountDownLatch(1)
    private val released = CountDownLatch(1)
    private val finished = CountDownLatch(1)

    /** True when writing the rest of the reply failed: the client had closed the connection. */
    @Volatile
    var clientGone: Boolean = false
        private set

    /** Waits until the reply reaches the hold. */
    fun awaitReached(timeoutMs: Long = 10_000): Boolean = reached.await(timeoutMs, TimeUnit.MILLISECONDS)

    fun release() = released.countDown()

    /** Waits until the rest of the reply has been written, or has failed. */
    fun awaitFinished(timeoutMs: Long = 10_000): Boolean = finished.await(timeoutMs, TimeUnit.MILLISECONDS)

    internal fun pause() {
        reached.countDown()
        released.await(60, TimeUnit.SECONDS)
    }

    internal fun finish(clientGone: Boolean) {
        this.clientGone = clientGone
        finished.countDown()
    }
}

/** Builds a scripted assistant message; see [reply]. */
class ReplyBuilder internal constructor() {
    private val steps = mutableListOf<Step>()
    private var stopReason: String? = null
    private var stopDetails: ObjectNode? = null
    private var usage = Usage()

    /** A thinking block whose text is [summary], as returned with `thinking.display: summarized`. */
    fun thinking(summary: String) {
        steps += Step.Thinking(summary)
    }

    fun text(text: String) {
        steps += Step.Text(text)
    }

    /**
     * A call to tool [name]; [input] is the JSON object of its arguments. Returns the `tool_use` id,
     * unique across every fake server of the test run unless given.
     */
    fun toolUse(name: String, input: String = "{}", id: String = nextId("toolu")): String {
        require(MAPPER.readTree(input).isObject) { "tool_use input must be a JSON object: $input" }
        steps += Step.ToolUse(id, name, input)
        return id
    }

    /** A tool call cut off by `max_tokens`: [partialInput] is the start of its JSON arguments. Stops with `max_tokens`. */
    fun truncatedToolUse(name: String, partialInput: String, id: String = nextId("toolu")): String {
        steps += Step.ToolUse(id, name, partialInput)
        stopReason = stopReason ?: "max_tokens"
        return id
    }

    /** Stops here, after `message_start` and what comes before, until the test releases [hold]. */
    fun hold(hold: Hold) {
        steps += Step.Pause(hold)
    }

    /** Ends the stream here, without `message_delta` nor `message_stop`, as a dropped connection would. */
    fun cut() {
        steps += Step.Cut
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
        val stop = stopReason ?: if (steps.any { it is Step.ToolUse }) "tool_use" else "end_turn"
        return ReplyResponse(steps.toList(), stop, stopDetails, usage)
    }
}

private val MAPPER = ObjectMapper()
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

private sealed class Step {
    data class Thinking(val summary: String) : Step()
    data class Text(val text: String) : Step()
    data class ToolUse(val id: String, val name: String, val input: String) : Step()
    data class Pause(val hold: Hold) : Step()
    data object Cut : Step()
}

private data class Usage(val input: Long = 10, val output: Long = 10, val cacheRead: Long = 0, val cacheWrite: Long = 0)

private class ReplyResponse(
    val steps: List<Step>,
    val stopReason: String,
    val stopDetails: ObjectNode?,
    val usage: Usage,
) : FakeResponse() {
    override fun writeTo(exchange: HttpExchange, request: RecordedRequest) {
        val holds = steps.filterIsInstance<Step.Pause>().map { it.hold }
        try {
            write(openStream(exchange), request)
            holds.forEach { it.finish(clientGone = false) }
        } catch (e: IOException) {
            holds.forEach { it.finish(clientGone = true) }
        }
    }

    private fun write(sse: SseWriter, request: RecordedRequest) {
        sse.messageStart(request, usage)
        sse.event("ping", obj("ping"))
        var index = 0
        for (step in steps) {
            when (step) {
                is Step.Pause -> step.hold.pause()
                Step.Cut -> return
                is Step.Thinking -> {
                    sse.blockStart(index, obj("thinking").put("thinking", "").put("signature", ""))
                    chunks(step.summary).forEach { sse.blockDelta(index, obj("thinking_delta").put("thinking", it)) }
                    sse.blockDelta(index, obj("signature_delta").put("signature", nextId("sig")))
                }
                is Step.Text -> {
                    sse.blockStart(index, obj("text").put("text", ""))
                    chunks(step.text).forEach { sse.blockDelta(index, obj("text_delta").put("text", it)) }
                }
                is Step.ToolUse -> {
                    val start = obj("tool_use").put("id", step.id).put("name", step.name)
                    start.putObject("input")
                    sse.blockStart(index, start)
                    chunks(step.input).forEach { sse.blockDelta(index, obj("input_json_delta").put("partial_json", it)) }
                }
            }
            if (step is Step.Thinking || step is Step.Text || step is Step.ToolUse) {
                sse.event("content_block_stop", obj("content_block_stop").put("index", index))
                index++
            }
        }
        val delta = MAPPER.createObjectNode().put("stop_reason", stopReason).putNull("stop_sequence")
        stopDetails?.let { delta.set<JsonNode>("stop_details", it) }
        val messageDelta = obj("message_delta")
        messageDelta.set<JsonNode>("delta", delta)
        messageDelta.set<JsonNode>("usage", usageNode(usage, output = usage.output))
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

private class HttpErrorResponse(val status: Int, val body: ObjectNode, val headers: Map<String, String>) : FakeResponse() {
    override fun writeTo(exchange: HttpExchange, request: RecordedRequest) {
        val requestId = nextId("req")
        val bytes = MAPPER.writeValueAsBytes(body.deepCopy().put("request_id", requestId))
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.responseHeaders.add("request-id", requestId)
        headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
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
        message.set<JsonNode>("usage", usageNode(usage, output = 1))
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

/** Usage as the real API reports it, with the cache writes broken down by TTL (all `1h` here). */
private fun usageNode(usage: Usage, output: Long): ObjectNode {
    val node = MAPPER.createObjectNode()
        .put("input_tokens", usage.input)
        .put("cache_creation_input_tokens", usage.cacheWrite)
        .put("cache_read_input_tokens", usage.cacheRead)
        .put("output_tokens", output)
    node.putObject("cache_creation").put("ephemeral_5m_input_tokens", 0).put("ephemeral_1h_input_tokens", usage.cacheWrite)
    return node
}

private fun obj(type: String): ObjectNode = MAPPER.createObjectNode().put("type", type)

/** Splits [text] into up to three chunks, so accumulation across deltas is exercised. */
private fun chunks(text: String): List<String> = if (text.length < 2) listOf(text) else text.chunked((text.length + 2) / 3)
