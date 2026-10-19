package io.github.clemsix6.narrator2.reflex

import com.anthropic.client.AnthropicClientAsync
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.RequestOptions
import com.anthropic.core.Timeout
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.anthropic.models.messages.ThinkingConfigDisabled
import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.config.ReflexConfig
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.llm.ClaudeClient
import io.github.clemsix6.narrator2.llm.budgetExceeded
import io.github.clemsix6.narrator2.llm.describeFailure
import io.github.clemsix6.narrator2.llm.requestIdOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * One call of the reflexes as the client measured it: what its `llm_calls` row records.
 *
 * @property model the model that answered, or the configured one when none did.
 * @property httpStatus status of the error response; null on success and when none came (timeout, network).
 * @property inputTokens and the other token counts: null when no answer came.
 * @property error why the call brought no labels; null when it did.
 */
data class ReflexCall(
    val model: String,
    val latencyMs: Long,
    val httpStatus: Int? = null,
    val requestId: String? = null,
    val messageId: String? = null,
    val inputTokens: Int? = null,
    val cacheReadTokens: Int? = null,
    val cacheWriteTokens: Int? = null,
    val outputTokens: Int? = null,
    val stopReason: String? = null,
    val refusalCategory: String? = null,
    val error: String? = null,
)

/** What [ReflexClient.label] gives. */
sealed interface ReflexResult {
    /** The model answered with a JSON object, [labels], not checked yet against what was asked. */
    data class Labeled(val labels: JsonObject, val call: ReflexCall) : ReflexResult

    /** A request went out and brought no labels ([ReflexCall.error] says why). */
    data class Failed(val call: ReflexCall) : ReflexResult

    /** Nothing went out: the circuit is open, the LLMGW budget is spent, or the client is closed. */
    data class Skipped(val reason: String) : ReflexResult
}

/**
 * The reflexes' line to a small Claude model (Haiku 5.5 by default) through LLMGW, with the gateway's
 * key: one request per item to label, answered with one JSON object (structured output). It is part of
 * the settings: `/narrator reload` builds a new one and closes the old one once its calls are over.
 *
 * - Thinking is off unless `reflex.thinking` (adaptive, with room for it: [MAX_TOKENS_THINKING]); the
 *   effort is `reflex.effort`. The chat labels start with a short reason, which the model writes before
 *   its numbers ([ReflexLabels.CHAT_SCHEMA]): without thinking, that is what makes them right.
 * - The whole call takes at most `reflex.timeoutMs` and is never retried: the heuristic answers at once
 *   instead (a 429, a 5xx, a timeout).
 * - A 402 (LLMGW budget spent) sends nothing more until the budget's reset, or for [OPEN_FOR_MS] when the
 *   gateway gave no reset time.
 * - Circuit breaker: after [FAILURES_TO_OPEN] failed calls in a row, nothing is sent for [OPEN_FOR_MS];
 *   the next call after that is a probe that closes the circuit or opens it again.
 * - The system prompt is the same for every call and comes first: the gateway caches it.
 *
 * The key never leaves the SDK's header: it is in no log line, error or trace. [label] never blocks a
 * thread and never throws, except the caller's cancellation.
 */
class ReflexClient internal constructor(
    config: NarratorConfig,
    apiKey: String,
    private val nanoTime: () -> Long = System::nanoTime,
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    private val log = Log.reflex
    private val reflex: ReflexConfig = config.reflex
    private val cacheTtl = config.gateway.cacheTtl
    private val client: AnthropicClientAsync = AnthropicOkHttpClient.builder()
        .baseUrl(config.gateway.baseUrl)
        .apiKey(apiKey)
        .maxRetries(0)
        .build()
        .async()
    private val lock = Any()

    // Guarded by [lock].
    private var failuresInARow = 0
    private var openUntilNanos: Long? = null
    private var budgetResetAt: Instant? = null
    private var inFlight = 0
    private var closeRequested = false
    private var closed = false

    /** The configured model id, e.g. `claude-haiku-5-5`. */
    val model: String get() = reflex.model

    /** The configured effort, e.g. `low`. */
    val effort: String get() = reflex.effort

    /** Room for the labels, and for the thinking when it is on. */
    private val maxTokens: Long = if (reflex.thinking) MAX_TOKENS_THINKING else MAX_TOKENS

    /**
     * Asks the model to label [request] (the user message) under [system] (the prompt), answering with
     * [schema]. [ReflexResult.Labeled] when it answered a JSON object; [ReflexResult.Failed] when a request
     * went out and brought none; [ReflexResult.Skipped] when nothing went out.
     */
    suspend fun label(system: String, request: String, schema: JsonOutputFormat.Schema): ReflexResult {
        enter()?.let { return ReflexResult.Skipped(it) }
        val start = nanoTime()
        try {
            val params = params(system, request, schema)
            val (message, requestId) = try {
                val response = withTimeoutOrNull(reflex.timeoutMs) { client.messages().withRawResponse().create(params, options()).await() }
                    ?: return failed(start, null, error = "timeout after ${reflex.timeoutMs} ms")
                response.parse() to requestIdOf(response.headers())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return failed(start, e)
            }
            return answered(start, message, requestId)
        } finally {
            leave()
        }
    }

    /**
     * One line for `/narrator status`: `active`, `circuit open …`, `LLMGW budget spent …` or `closed`.
     * Never the key.
     */
    fun status(): String = synchronized(lock) {
        val openUntil = openUntilNanos
        when {
            closed || closeRequested -> "closed"
            openUntil != null && openUntil - nanoTime() > 0 -> {
                val seconds = TimeUnit.NANOSECONDS.toSeconds(openUntil - nanoTime() + 999_999_999)
                budgetResetAt?.let { "LLMGW budget spent until $it" } ?: "circuit open for $seconds s after $failuresInARow failures in a row"
            }
            else -> "active"
        }
    }

    /** Closes the client once its calls in flight are over: the settings were replaced. */
    fun closeWhenIdle() {
        val now = synchronized(lock) {
            closeRequested = true
            inFlight == 0
        }
        if (now) close()
    }

    /** Closes the client now: the server is stopping and its callers are cancelled. */
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }
        client.close()
    }

    override fun toString(): String = "ReflexClient(${reflex.model})"

    private fun params(system: String, request: String, schema: JsonOutputFormat.Schema): MessageCreateParams =
        MessageCreateParams.builder()
            .model(reflex.model)
            .maxTokens(maxTokens)
            .apply {
                if (reflex.thinking) thinking(ThinkingConfigAdaptive.builder().build()) else thinking(ThinkingConfigDisabled.builder().build())
            }
            .outputConfig(
                OutputConfig.builder()
                    .effort(OutputConfig.Effort.of(reflex.effort))
                    .format(JsonOutputFormat.builder().schema(schema).build())
                    .build(),
            )
            .system(system)
            .addUserMessage(request)
            .apply {
                if (cacheTtl != NarratorConfig.NO_CACHE_MARKER) {
                    cacheControl(CacheControlEphemeral.builder().ttl(CacheControlEphemeral.Ttl.of(cacheTtl)).build())
                }
            }
            .putAdditionalHeader(ClaudeClient.SESSION_HEADER, SESSION)
            .build()

    /** The SDK's own deadline, a little after ours: ours cuts the call, this one frees the connection. */
    private fun options(): RequestOptions = RequestOptions.builder()
        .timeout(Timeout.builder().request(Duration.ofMillis(reflex.timeoutMs + SDK_TIMEOUT_MARGIN_MS)).build())
        .build()

    private fun answered(start: Long, message: Message, requestId: String?): ReflexResult {
        val usage = message.usage()
        val stopReason = message.stopReason().orElse(null)
        val call = ReflexCall(
            model = message.model().asString(),
            latencyMs = elapsedMs(start),
            requestId = requestId,
            messageId = message.id(),
            inputTokens = usage.inputTokens().toInt(),
            cacheReadTokens = usage.cacheReadInputTokens().orElse(0L).toInt(),
            cacheWriteTokens = usage.cacheCreationInputTokens().orElse(0L).toInt(),
            outputTokens = usage.outputTokens().toInt(),
            stopReason = stopReason?.asString(),
            refusalCategory = message.stopDetails().flatMap { it.category() }.map { it.asString() }.orElse(null),
        )
        val text = message.content().mapNotNull { block -> block.text().map { it.text() }.orElse(null) }.joinToString("")
        val labels = jsonObjectIn(text)
        val error = when {
            stopReason == StopReason.REFUSAL -> "refused (${call.refusalCategory ?: "no category"})"
            stopReason == StopReason.MAX_TOKENS -> "max_tokens ($maxTokens) reached"
            labels == null -> "unexpected answer: no JSON object"
            else -> null
        }
        if (labels == null || error != null) return failure(call.copy(error = error))
        recordSuccess()
        return ReflexResult.Labeled(labels, call)
    }

    /** A call that got no answer: [cause] was thrown, or [error] says what happened instead. */
    private fun failed(start: Long, cause: Throwable?, error: String? = null): ReflexResult.Failed {
        val service = generateSequence(cause) { it.cause }.filterIsInstance<AnthropicServiceException>().firstOrNull()
        val budget = service?.let(::budgetExceeded)
        if (budget != null) {
            synchronized(lock) {
                budgetResetAt = budget.resetAt ?: clock.instant().plusMillis(OPEN_FOR_MS)
                val waitMs = Duration.between(clock.instant(), budgetResetAt).toMillis().coerceAtLeast(0)
                openUntilNanos = nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs)
            }
            log.warn("${budget.message}: the heuristic answers until then")
        }
        val call = ReflexCall(
            model = reflex.model,
            latencyMs = elapsedMs(start),
            httpStatus = service?.statusCode(),
            requestId = service?.headers()?.let(::requestIdOf),
            error = error ?: (budget ?: service ?: cause)?.let(::describeFailure) ?: "no answer",
        )
        return failure(call)
    }

    /** Counts a failed call: [FAILURES_TO_OPEN] in a row open the circuit. */
    private fun failure(call: ReflexCall): ReflexResult.Failed {
        val opened = synchronized(lock) {
            failuresInARow++
            if (failuresInARow >= FAILURES_TO_OPEN && budgetResetAt == null) {
                openUntilNanos = nanoTime() + TimeUnit.MILLISECONDS.toNanos(OPEN_FOR_MS)
                true
            } else {
                false
            }
        }
        if (opened) {
            log.warn("circuit open: $FAILURES_TO_OPEN or more failures in a row (last: ${call.error}); the heuristic answers for ${OPEN_FOR_MS / 60_000} min")
        }
        return ReflexResult.Failed(call)
    }

    private fun recordSuccess() {
        val reopened = synchronized(lock) {
            val wasOpen = openUntilNanos != null
            failuresInARow = 0
            openUntilNanos = null
            budgetResetAt = null
            wasOpen
        }
        if (reopened) log.info("circuit closed: ${reflex.model} answers again")
    }

    /** Counts a call in flight; the reason to send nothing instead, when there is one. */
    private fun enter(): String? = synchronized(lock) {
        val openUntil = openUntilNanos
        when {
            closed || closeRequested -> "client closed"
            openUntil != null && openUntil - nanoTime() > 0 -> budgetResetAt?.let { "LLMGW budget spent until $it" } ?: "circuit open"
            else -> {
                budgetResetAt = null // the budget's window is over: this call is a probe
                inFlight++
                null
            }
        }
    }

    private fun leave() {
        val closeNow = synchronized(lock) {
            inFlight--
            closeRequested && inFlight == 0
        }
        if (closeNow) close()
    }

    private fun elapsedMs(start: Long) = TimeUnit.NANOSECONDS.toMillis(nanoTime() - start)

    companion object {
        /** The gateway session of every reflex call (`X-Session-ID`): one upstream account keeps the prompt cached. */
        const val SESSION = "narrator2-reflex"

        /** A verdict is a JSON object of a few hundred characters, its reason included. */
        const val MAX_TOKENS = 400L

        /** With `reflex.thinking`: the thinking must never eat the room of the labels. */
        const val MAX_TOKENS_THINKING = 4_000L

        /** Failed calls in a row that open the circuit. */
        const val FAILURES_TO_OPEN = 5

        /** How long an open circuit sends nothing. */
        const val OPEN_FOR_MS = 5 * 60_000L

        private const val SDK_TIMEOUT_MARGIN_MS = 2_000L

        private val JSON = Json { ignoreUnknownKeys = true }

        /**
         * A client when the reflexes are enabled and there is an LLMGW key ([apiKey], null when missing);
         * null otherwise, and the reflexes use their heuristic.
         */
        fun create(config: NarratorConfig, apiKey: String?): ReflexClient? {
            val reflex = config.reflex
            return when {
                !reflex.enabled -> null.also { Log.reflex.info("reflexes: heuristic only (reflex.enabled is false)") }
                apiKey == null -> null.also { Log.reflex.info("reflexes: heuristic only (no LLMGW key)") }
                else -> ReflexClient(config, apiKey).also {
                    Log.reflex.info(
                        "reflexes: ${reflex.model} through LLMGW, effort ${reflex.effort}, thinking ${if (reflex.thinking) "on" else "off"}, " +
                            "${reflex.timeoutMs} ms per call, heuristic as fallback",
                    )
                }
            }
        }

        /**
         * The JSON object in [text]: the whole text, or what lies between its first `{` and its last `}`
         * (a model may wrap it in a Markdown code block); null when there is none.
         */
        internal fun jsonObjectIn(text: String): JsonObject? {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return runCatching { JSON.parseToJsonElement(text.substring(start, end + 1)) as? JsonObject }.getOrNull()
        }
    }
}
