package io.github.clemsix6.narrator2.llm

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.RequestOptions
import com.anthropic.core.Timeout
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
 * sends an `X-Session-ID` stable **per role** ([sessionOf]) so the gateway keeps every conversation of a
 * role on the upstream account where their common prefix is cached; the conversation's own id goes in
 * [CONVERSATION_HEADER].
 *
 * A request is the conversation's committed history followed by the *pending* messages of this call;
 * the pending messages join the history with the reply, and only when the reply is usable (see
 * [send] and [Conversation]).
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

    private val lifecycle = Any()
    private var callsInFlight = 0 // guarded by lifecycle
    private var closeRequested = false // guarded by lifecycle

    /** True once the client is closed: [send] then fails with a [ClientClosedException]. */
    @Volatile
    var isClosed: Boolean = false
        private set

    /** Calls running right now. */
    val inFlight: Int get() = synchronized(lifecycle) { callsInFlight }

    /**
     * Sends [conversation]'s history followed by [pending], the messages of this request. When the
     * reply is usable (a [SendResult.Reply]), [pending] and the reply join the history together;
     * otherwise nothing does, and the next request may carry different messages. Blocking network I/O
     * runs on [Dispatchers.IO].
     *
     * Cancelling the caller closes the response stream at once and commits nothing; the
     * [CancellationException] is rethrown as is. A failure is thrown after the last attempt, with
     * nothing committed (see [exchange] to get its measurements too). A second `send` on the same
     * conversation while this one is in flight fails with an [IllegalStateException]. Once the client
     * is closed, fails with a [ClientClosedException] before sending anything.
     */
    suspend fun send(conversation: Conversation, pending: List<MessageParam> = emptyList()): SendResult =
        when (val exchange = exchange(conversation, pending)) {
            is Exchange.Answered -> exchange.result
            is Exchange.Failed -> throw exchange.error
        }

    /**
     * Like [send], but a failure after the last attempt is returned with its measurements (a row of
     * `llm_calls`) instead of thrown. Cancellation, a closed client and a conversation already in
     * flight still throw.
     */
    suspend fun exchange(conversation: Conversation, pending: List<MessageParam> = emptyList()): Exchange {
        enter()
        try {
            return withContext(Dispatchers.IO) {
                val history = conversation.beginCall()
                var committed: List<MessageParam>? = null
                try {
                    execute(conversation, history, pending).also { exchange ->
                        val result = (exchange as? Exchange.Answered)?.result
                        if (result is SendResult.Reply) committed = pending + replayOf(result.message)
                    }
                } finally {
                    conversation.endCall(committed)
                }
            }
        } finally {
            leave()
        }
    }

    /**
     * Closes the client once the calls in flight are over, at once when there are none. Used when
     * `/narrator reload` replaces the client: the calls it already started finish normally. A call that
     * starts in the meantime is still served and delays the closing.
     */
    fun closeWhenIdle() {
        val closeNow = synchronized(lifecycle) {
            closeRequested = true
            callsInFlight == 0
        }
        if (closeNow) {
            closeClient("replaced client closed, no call was in flight")
        } else {
            log.info("replaced client closes after its calls in flight")
        }
    }

    private fun enter() = synchronized(lifecycle) {
        if (isClosed) throw ClientClosedException()
        callsInFlight++
    }

    private fun leave() {
        val closeNow = synchronized(lifecycle) {
            callsInFlight--
            closeRequested && callsInFlight == 0
        }
        if (closeNow) closeClient("replaced client closed after its last call")
    }

    private suspend fun execute(conversation: Conversation, history: List<MessageParam>, pending: List<MessageParam>): Exchange {
        val role = conversation.frozen(config.role(conversation.role))
        val messages = history + pending
        val params = params(conversation, role, messages)
        val call = CallContext(conversation, role)
        log.info(
            "${conversation.id} · call · role ${conversation.role.id} · ${role.model} · effort ${role.effort} · " +
                "${messages.size} messages${if (pending.isEmpty()) "" else " (${pending.size} pending)"}",
        )
        conversation.transcripts?.sent(conversation, pending)
        conversation.transcripts?.request(conversation, params, messages.size)

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
                        return Exchange.Failed(failure, stats)
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
                return Exchange.Answered(finish(call, number, attempt, message))
            }
        } catch (e: CancellationException) {
            log.info("${conversation.id} · cancelled after ${call.elapsedMs()} ms, during attempt $number")
            conversation.transcripts?.note(conversation, "call cancelled after ${call.elapsedMs()} ms — NOT COMMITTED (cancelled)")
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
            stopReason != null && stopReason in USABLE && isEmptyReply(message) -> SendResult.Incomplete(message, stats, EMPTY_REPLY)
            stopReason != null && stopReason in USABLE -> SendResult.Reply(message, stats)
            stopReason == StopReason.MAX_TOKENS -> SendResult.Incomplete(message, stats, "max_tokens (${call.role.maxTokens}) reached")
            else -> SendResult.Incomplete(message, stats, "unexpected stop reason ${stats.stopReason}")
        }
        conversation.contextTokens = stats.contextTokens
        val notCommitted = when (result) {
            is SendResult.Refused -> "refusal"
            is SendResult.Incomplete -> result.reason
            is SendResult.Reply -> null
        }
        conversation.transcripts?.response(conversation, message, stats, notCommitted)
        log.info("${conversation.id} · ${stats.summary()} · ${stats.ids()}")
        when (result) {
            is SendResult.Refused -> log.warn(
                "${conversation.id} · refused (${result.category ?: "no category"}): " +
                    "${result.explanation ?: "no explanation"}; nothing committed",
            )
            is SendResult.Incomplete -> log.warn("${conversation.id} · incomplete reply, nothing committed: ${result.reason}")
            is SendResult.Reply -> Unit
        }
        return result
    }

    private fun params(conversation: Conversation, role: RoleConfig, messages: List<MessageParam>): MessageCreateParams =
        MessageCreateParams.builder()
            .model(role.model)
            .maxTokens(role.maxTokens)
            .thinking(ThinkingConfigAdaptive.builder().display(ThinkingConfigAdaptive.Display.SUMMARIZED).build())
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(role.effort)).build())
            .systemOfTextBlockParams(conversation.system)
            .messages(messages)
            .apply {
                if (gateway.cacheTtl != NarratorConfig.NO_CACHE_MARKER) {
                    cacheControl(CacheControlEphemeral.builder().ttl(CacheControlEphemeral.Ttl.of(gateway.cacheTtl)).build())
                }
            }
            .putAdditionalHeader(SESSION_HEADER, sessionOf(conversation.role))
            .putAdditionalHeader(CONVERSATION_HEADER, conversation.id)
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

    private fun requestIdOf(error: Throwable): String? = (error as? AnthropicServiceException)?.headers()?.let(::requestIdOf)

    /**
     * Closes the client now; calls still in flight fail. Their callers are cancelled first when the
     * server stops (see [closeWhenIdle] to let them finish). Closing again does nothing.
     */
    override fun close() = closeClient("client closed")

    private fun closeClient(what: String) {
        synchronized(lifecycle) {
            if (isClosed) return
            isClosed = true
        }
        client.close()
        log.info(what)
    }

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

    companion object {
        /** [SendResult.Incomplete.reason] of a usable stop reason with nothing to commit, see [isEmptyReply]. */
        const val EMPTY_REPLY = "empty reply: no text, no tool call"

        /** The gateway's session affinity: the requests with the same value go to the same upstream account. */
        const val SESSION_HEADER = "X-Session-ID"

        /** The conversation of a request ([Conversation.id]): for the gateway's logs and the tests, never for routing. */
        const val CONVERSATION_HEADER = "X-Narrator-Conversation"

        /**
         * The [SESSION_HEADER] of every conversation of [role]: `narrator2-director`, `narrator2-analyst`. The
         * prompt cache lives on the upstream account, and the gateway picks the account by this value: a value
         * of its own per conversation sent each chapter, each task of the Analyst, to any account, where its
         * prefix (prompt and tools, the same for the whole role) was written again (T22, finding 3). One
         * value per role keeps them all on the account that holds it: the next chapter or task reads it.
         */
        fun sessionOf(role: Role): String = "narrator2-${role.id}"

        private val USABLE = setOf(StopReason.END_TURN, StopReason.TOOL_USE, StopReason.PAUSE_TURN, StopReason.STOP_SEQUENCE)
    }
}

/** What [ClaudeClient.exchange] got back. */
sealed interface Exchange {
    /** Claude answered; only a [SendResult.Reply] was committed. */
    class Answered(val result: SendResult) : Exchange

    /** The call failed after its last attempt, with nothing committed; [stats] carries its error. */
    class Failed(val error: Throwable, val stats: CallStats) : Exchange
}

/**
 * The reply as it joins the history: its blocks, minus blank text blocks, which the API rejects in a
 * request (a streamed text block can end up empty).
 */
internal fun replayOf(message: Message): MessageParam =
    MessageParam.builder()
        .role(MessageParam.Role.ASSISTANT)
        .contentOfBlockParams(message.content().filterNot { it.isText() && it.asText().text().isBlank() }.map { it.toParam() })
        .build()

/**
 * True when [message] has nothing to commit: no block but blank text. The API rejects an empty
 * assistant turn, so such a reply is never committed (it happens after tool results, when the model
 * considers the turn over). A reply with only thinking is not empty.
 */
fun isEmptyReply(message: Message): Boolean = message.content().all { it.isText() && it.asText().text().isBlank() }

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
