package io.github.clemsix6.narrator2.llm

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.RequestOptions
import com.anthropic.core.Timeout
import com.anthropic.core.http.Headers
import com.anthropic.core.jsonMapper
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.SseException
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.RawMessageStreamEvent
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.fasterxml.jackson.databind.JsonNode
import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.config.RoleConfig
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.trace.CallStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/**
 * Talks to Claude through LLMGW, which speaks the Anthropic Messages API: the official SDK is simply
 * pointed at the gateway with an LLMGW project key, given by the caller (never read from a file).
 *
 * Every call streams, asks for summarized thinking (logged for debugging; Opus 5.5 never returns raw
 * reasoning), sets the role's effort, enables automatic prompt caching with the configured TTL, and
 * sends a stable `X-Session-ID` so the gateway keeps the conversation on the same upstream account,
 * where its prompt cache lives.
 *
 * Timeouts are per role: a short read timeout (silence from the gateway) and a long total timeout for
 * the whole call, retries included. The SDK's own retries are off: [send] retries 429, 529, 5xx,
 * connection errors and streams cut before any content, with exponential backoff and jitter, honouring
 * `retry-after`. A 402 `budget_exceeded` is never retried: it becomes a [BudgetExceededException].
 */
class ClaudeClient(private val config: NarratorConfig, private val apiKey: String?) : AutoCloseable {
    private val log = Log.llm
    private val gateway = config.gateway
    private val mapper = jsonMapper()

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .baseUrl(gateway.baseUrl)
        .apiKey(apiKey ?: "missing-llmgw-key")
        .maxRetries(0)
        .build()

    /** False when no LLMGW key was given: calls would be refused by the gateway. */
    val isConfigured: Boolean get() = apiKey != null

    /**
     * Sends [conversation] to Claude and appends the reply to it when the reply is usable (see
     * [SendResult]). Blocking network I/O runs on [Dispatchers.IO].
     *
     * Cancelling the caller closes the response stream at once and leaves the conversation unchanged;
     * the [CancellationException] is rethrown as is. A failure is thrown after the last attempt, with
     * the conversation unchanged. A second `send` on the same conversation while this one is in flight
     * fails with an [IllegalStateException].
     */
    suspend fun send(conversation: Conversation): SendResult = withContext(Dispatchers.IO) {
        val history = conversation.beginCall()
        var reply: MessageParam? = null
        try {
            execute(conversation, history).also { result ->
                if (result is SendResult.Reply) reply = result.message.toParam()
            }
        } finally {
            conversation.endCall(reply)
        }
    }

    private suspend fun execute(conversation: Conversation, history: List<MessageParam>): SendResult {
        val role = config.role(conversation.role)
        val params = params(conversation, role, history)
        val call = CallContext(conversation, role)
        log.info(
            "${conversation.id} · call · role ${conversation.role.id} · ${role.model} · effort ${role.effort} · " +
                "${history.size} messages",
        )
        conversation.transcripts?.request(conversation, params, history.size)

        var number = 0
        try {
            while (true) {
                number++
                val attempt = Attempt()
                val message = try {
                    attempt.run(params, call.requestOptions())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val failure = budgetExceeded(e) ?: e
                    val reason = if (failure is BudgetExceededException) null else retryReason(failure, attempt.sawContent)
                    val delayMs = reason?.let { retryDelayMs(failure, number) }
                    if (reason == null || delayMs == null || number > gateway.maxRetries || delayMs >= call.remainingMs()) {
                        val stats = call.stats(number, attempt.requestId ?: requestIdOf(e)).copy(error = describeFailure(failure))
                        log.warn("${conversation.id} · failed · ${stats.ids()} · ${stats.durationMs} ms: ${stats.error}", failure)
                        conversation.transcripts?.error(conversation, failure, stats)
                        throw failure
                    }
                    log.warn(
                        "${conversation.id} · attempt $number/${gateway.maxRetries + 1} failed ($reason), " +
                            "retrying in $delayMs ms: ${describeFailure(failure)}",
                    )
                    conversation.transcripts?.retry(conversation, number, reason, delayMs)
                    delay(delayMs)
                    continue
                }
                currentCoroutineContext().ensureActive()
                return finish(call, number, attempt, message)
            }
        } catch (e: CancellationException) {
            log.info("${conversation.id} · cancelled after ${call.elapsedMs()} ms, during attempt $number")
            conversation.transcripts?.note(conversation, "call cancelled after ${call.elapsedMs()} ms")
            throw e
        }
    }

    /** Measures the reply, decides whether it is usable, and records it. */
    private fun finish(call: CallContext, number: Int, attempt: Attempt, message: Message): SendResult {
        val conversation = call.conversation
        val usage = message.usage()
        val stopReason = message.stopReason().orElse(null)
        val stats = call.stats(number, attempt.requestId).copy(
            messageId = message.id(),
            timeToFirstTokenMs = attempt.timeToFirstTokenMs,
            inputTokens = usage.inputTokens(),
            cacheReadTokens = usage.cacheReadInputTokens().orElse(0L),
            cacheWriteTokens = usage.cacheCreationInputTokens().orElse(0L),
            outputTokens = usage.outputTokens(),
            stopReason = stopReason?.asString(),
            refusalCategory = message.stopDetails().flatMap { it.category() }.map { it.asString() }.orElse(null),
        )
        val result = when {
            stopReason == StopReason.REFUSAL -> SendResult.Refused(message, stats)
            attempt.incompleteToolUse -> SendResult.Incomplete(message, stats, "a tool call was cut off (${stats.stopReason})")
            stopReason == StopReason.TOOL_USE && message.content().none { it.isToolUse() } ->
                SendResult.Incomplete(message, stats, "stop reason tool_use without any tool call")
            stopReason != null && stopReason in USABLE -> SendResult.Reply(message, stats)
            stopReason == StopReason.MAX_TOKENS -> SendResult.Incomplete(message, stats, "max_tokens (${call.role.maxTokens}) reached")
            else -> SendResult.Incomplete(message, stats, "unexpected stop reason ${stats.stopReason}")
        }
        conversation.contextTokens = stats.contextTokens
        conversation.transcripts?.response(conversation, message, stats, appended = result is SendResult.Reply)
        log.info("${conversation.id} · ${stats.summary()} · ${stats.ids()}")
        when (result) {
            is SendResult.Refused -> log.warn(
                "${conversation.id} · refused (${result.category ?: "no category"}): " +
                    "${result.explanation ?: "no explanation"}; not appended",
            )
            is SendResult.Incomplete -> log.warn("${conversation.id} · incomplete reply, not appended: ${result.reason}")
            is SendResult.Reply -> Unit
        }
        return result
    }

    private fun params(conversation: Conversation, role: RoleConfig, history: List<MessageParam>): MessageCreateParams =
        MessageCreateParams.builder()
            .model(role.model)
            .maxTokens(role.maxTokens)
            .thinking(ThinkingConfigAdaptive.builder().display(ThinkingConfigAdaptive.Display.SUMMARIZED).build())
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(role.effort)).build())
            .systemOfTextBlockParams(conversation.system)
            .messages(history)
            .apply {
                if (gateway.cacheTtl != NarratorConfig.NO_CACHE_MARKER) {
                    cacheControl(CacheControlEphemeral.builder().ttl(CacheControlEphemeral.Ttl.of(gateway.cacheTtl)).build())
                }
            }
            .putAdditionalHeader("X-Session-ID", conversation.id)
            .apply { if (conversation.tools.isNotEmpty()) tools(conversation.tools) }
            .build()

    /** Why [error] deserves another attempt, or null when it does not. */
    private fun retryReason(error: Throwable, sawContent: Boolean): String? = when {
        error is SseException -> if (sawContent) null else "stream error before any content"
        error is AnthropicServiceException -> error.statusCode().let { if (it == 429 || it >= 500) "transient HTTP error" else null }
        error is TruncatedStreamException -> if (sawContent) null else "stream cut before any content"
        isConnectionError(error) -> if (sawContent) null else "connection error"
        else -> null
    }

    /** The gateway's `retry-after` when it sent one, else exponential backoff with jitter. */
    private fun retryDelayMs(error: Throwable, attempt: Int): Long {
        retryAfterMs(error)?.let { return it }
        val exponential = gateway.retryInitialDelayMs * (1L shl (attempt - 1).coerceAtMost(20))
        val capped = exponential.coerceAtMost(gateway.retryMaxDelayMs)
        return capped / 2 + Random.nextLong(capped / 2 + 1)
    }

    private fun retryAfterMs(error: Throwable): Long? {
        val headers = (error as? AnthropicServiceException)?.headers() ?: return null
        headers.values("retry-after-ms").firstOrNull()?.trim()?.toDoubleOrNull()?.let { return it.toLong().coerceAtLeast(0) }
        val value = headers.values("retry-after").firstOrNull()?.trim() ?: return null
        value.toDoubleOrNull()?.let { return (it * 1000).toLong().coerceAtLeast(0) }
        return runCatching {
            Duration.between(Instant.now(), ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)).toMillis()
        }.getOrNull()?.coerceAtLeast(0)
    }

    /** LLMGW's 402 `budget_exceeded`, with its reset time; null for any other failure. */
    private fun budgetExceeded(error: Throwable): BudgetExceededException? {
        if (error !is AnthropicServiceException || error.statusCode() != 402) return null
        val detail = runCatching { mapper.valueToTree<JsonNode>(error.body()) }.getOrNull()?.path("error") ?: return null
        if (detail.path("type").asText() != "budget_exceeded") return null
        val resetAt = detail.path("reset_at").takeIf { it.isTextual }
            ?.let { runCatching { OffsetDateTime.parse(it.asText()).toInstant() }.getOrNull() }
        return BudgetExceededException(resetAt, detail.path("dimension").textOrNull(), detail.path("window").textOrNull())
            .apply { initCause(error) }
    }

    private fun requestIdOf(error: Throwable): String? = (error as? AnthropicServiceException)?.headers()?.let(::requestIdOf)

    /**
     * The id to quote to the gateway's operator: Anthropic's `request-id`, else CLIProxyAPI's
     * `X-Cpa-Trace-Id` (LLMGW does not relay `request-id`, verified 25/09/2026).
     */
    private fun requestIdOf(headers: Headers): String? =
        (headers.values("request-id") + headers.values("x-cpa-trace-id")).firstOrNull()

    private fun JsonNode.textOrNull(): String? = if (isTextual) asText() else null

    override fun close() = client.close()

    /** What every attempt of one call shares: its role, its start and its deadline. */
    private class CallContext(val conversation: Conversation, val role: RoleConfig) {
        private val startedAt: OffsetDateTime = OffsetDateTime.now()
        private val start = System.nanoTime()
        private val deadline = start + Duration.ofSeconds(role.totalTimeoutSeconds).toNanos()

        fun elapsedMs(): Long = (System.nanoTime() - start) / 1_000_000

        fun remainingMs(): Long = (deadline - System.nanoTime()) / 1_000_000

        /** The attempt may read for at most the time left before the deadline. */
        fun requestOptions(): RequestOptions = RequestOptions.builder()
            .timeout(
                Timeout.builder()
                    .read(Duration.ofSeconds(role.readTimeoutSeconds))
                    .request(Duration.ofMillis(remainingMs().coerceAtLeast(1)))
                    .build(),
            )
            .build()

        fun stats(attempts: Int, requestId: String?) = CallStats(
            conversationId = conversation.id,
            role = conversation.role,
            model = role.model,
            effort = role.effort,
            startedAt = startedAt,
            attempts = attempts,
            durationMs = elapsedMs(),
            requestId = requestId,
        )
    }

    /** One HTTP request and its streamed answer. */
    private inner class Attempt {
        private val start = System.nanoTime()
        private val accumulator = MessageAccumulator.create()
        private val toolInputs = mutableMapOf<Long, StringBuilder>() // raw JSON arguments, per tool_use block
        private var stopped = false

        var requestId: String? = null
            private set
        var timeToFirstTokenMs: Long? = null
            private set

        /** True once a content block has started: after that, a failed stream is not retried. */
        var sawContent = false
            private set

        /**
         * A tool call whose arguments are not a whole JSON object: cut off by `max_tokens`. (The SDK's
         * accumulator silently replaces such arguments with `{}`, so they are checked here.)
         */
        var incompleteToolUse = false
            private set

        suspend fun run(params: MessageCreateParams, options: RequestOptions): Message {
            val response = client.messages().withRawResponse().createStreaming(params, options)
            requestId = requestIdOf(response.headers())
            response.parse().use { stream -> closeOnCancel(stream) { stream.stream().forEach(::accept) } }
            if (!stopped) throw TruncatedStreamException(sawContent)
            return accumulator.message()
        }

        private fun accept(event: RawMessageStreamEvent) {
            event.contentBlockStart().ifPresent { start ->
                sawContent = true
                if (start.contentBlock().isToolUse()) toolInputs[start.index()] = StringBuilder()
            }
            event.contentBlockDelta().ifPresent { delta ->
                if (timeToFirstTokenMs == null) timeToFirstTokenMs = (System.nanoTime() - start) / 1_000_000
                delta.delta().inputJson().ifPresent { toolInputs[delta.index()]?.append(it.partialJson()) }
            }
            event.contentBlockStop().ifPresent { stop ->
                toolInputs[stop.index()]?.let { if (!isWholeObject(it.toString())) incompleteToolUse = true }
            }
            if (event.isMessageStop()) stopped = true
            accumulator.accumulate(event)
        }

        private fun isWholeObject(json: String): Boolean =
            json.isBlank() || runCatching { mapper.readTree(json).isObject }.getOrDefault(false)
    }

    private companion object {
        val USABLE = setOf(StopReason.END_TURN, StopReason.TOOL_USE, StopReason.PAUSE_TURN, StopReason.STOP_SEQUENCE)
    }
}

/**
 * Runs the blocking [block] on this thread and closes [resource] as soon as the calling coroutine is
 * cancelled, so that a read blocked on the network fails at once. `Job.invokeOnCompletion` would not
 * do: it only fires once the job has completed, which the blocked read prevents.
 */
private suspend fun <T> closeOnCancel(resource: AutoCloseable, block: () -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { runCatching { resource.close() } }
        continuation.resumeWith(runCatching(block))
    }
