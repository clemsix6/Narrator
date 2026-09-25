package io.github.clemsix6.narrator2.reflex

import io.github.clemsix6.narrator2.config.JevConfig
import io.github.clemsix6.narrator2.core.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/** A question for Jev (`docs/research/JEV.md` §1.2). The key it is asked under is the caller's. */
sealed interface JevQuestion {
    val instructions: String

    /** A yes/no question; the answer is the probability of yes. [yes] and [no] say what each means, if needed. */
    data class Noul(override val instructions: String, val yes: String? = null, val no: String? = null) : JevQuestion

    /** One option out of [options] (option id → what it means); the answer is the likeliest one. */
    data class Choice(override val instructions: String, val options: Map<String, String>) : JevQuestion
}

/** Jev's answer to a [JevQuestion], checked against the question: a probability in 0..1, a known option. */
sealed interface JevAnswer {
    data class Noul(val value: Double) : JevAnswer

    data class Choice(val choice: String, val confidence: Double) : JevAnswer
}

/**
 * One call to Jev as the client measured it: what its `llm_calls` row records.
 *
 * @property model the versioned model that answered, or the configured one when none did.
 * @property latencyMs the whole call, retries included.
 * @property attempts HTTP requests sent.
 * @property httpStatus status of the last response; null when none came (timeout, network).
 * @property requestId the response's request id header, if Jev sent one.
 * @property error why the call brought no answer; null when it did.
 */
data class JevCall(
    val model: String,
    val latencyMs: Long,
    val attempts: Int,
    val httpStatus: Int?,
    val requestId: String?,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val error: String?,
)

/** What [JevClient.evaluate] gives. */
sealed interface JevResult {
    /** Jev answered every question. */
    data class Answered(val answers: Map<String, JevAnswer>, val call: JevCall) : JevResult

    /** A request went out and brought no usable answer ([JevCall.error] says why). */
    data class Failed(val call: JevCall) : JevResult

    /** Nothing went out: Jev is cut until the next reload, its circuit is open, or the client is closed. */
    data class Skipped(val reason: String) : JevResult
}

/**
 * Client of Jev (TypeSafe), the fast classifier behind the reflexes (M1_M2 §11): `POST
 * {jev.baseUrl}/v1/systemone` with the key as a bearer token, JSON in and out, no SDK. It is part of the
 * settings: `/narrator reload` builds a new one and closes the old one once its calls are over.
 *
 * - The whole call, retries included, takes at most `jev.timeoutMs`.
 * - 429, 529 and 5xx are retried once, after 200 to 400 ms, when the time left allows it.
 * - 401 and 422 are never retried: they will not heal by themselves (a bad key, a malformed question).
 *   Jev is then cut until `/narrator reload` builds a new client; one `ERROR` line says why.
 * - Circuit breaker: after [FAILURES_TO_OPEN] failed calls in a row, nothing is sent for [OPEN_FOR_MS];
 *   the next call after that is a probe that closes the circuit or opens it again.
 *
 * The key never leaves the `Authorization` header: it is in no log line, error or trace.
 * [evaluate] never blocks a thread and never throws, except the caller's cancellation.
 */
class JevClient internal constructor(
    private val config: JevConfig,
    private val apiKey: String,
    private val nanoTime: () -> Long = System::nanoTime,
    private val random: Random = Random.Default,
) : AutoCloseable {
    private val log = Log.jev
    private val endpoint = URI.create(config.baseUrl.trimEnd('/') + PATH)
    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofMillis(config.timeoutMs))
        .build()
    private val closed = AtomicBoolean(false)
    private val lock = Any()

    // Guarded by [lock].
    private var cutReason: String? = null
    private var failuresInARow = 0
    private var openUntilNanos: Long? = null

    /** The configured model id, e.g. `jev-1.13.0`. */
    val model: String get() = config.model

    /**
     * Asks [questions] (key → question) about [state] in one request. [JevResult.Answered] only when
     * every question got an answer of its type; anything else is a [JevResult.Failed] (a request went
     * out) or a [JevResult.Skipped] (nothing went out).
     */
    suspend fun evaluate(state: JsonElement, questions: Map<String, JevQuestion>): JevResult {
        skipReason()?.let { return JevResult.Skipped(it) }
        val body = requestBody(state, questions)
        val start = nanoTime()
        val deadline = start + TimeUnit.MILLISECONDS.toNanos(config.timeoutMs)
        var attempts = 0
        while (true) {
            val left = deadline - nanoTime()
            if (left <= 0) return failed(start, attempts, null, null, timeoutError())
            attempts++
            val response = try {
                send(body, left)
            } catch (e: HttpTimeoutException) {
                null
            } catch (e: IOException) {
                return failed(start, attempts, null, null, "network error: ${e.message ?: e.javaClass.simpleName}")
            } ?: return failed(start, attempts, null, null, timeoutError())

            val status = response.statusCode()
            val requestId = response.headers().firstValue("x-request-id").orElse(null)
            when {
                status == 200 -> return parse(response.body(), questions, start, attempts, requestId)
                status == 401 || status == 422 -> {
                    val error = "HTTP $status ${describe(status)}${detail(response.body())}"
                    cut(error)
                    return JevResult.Failed(call(start, attempts, status, requestId, error = error))
                }
                isRetryable(status) && attempts == 1 -> {
                    val backoff = BACKOFF_MIN_MS + random.nextLong(BACKOFF_MAX_MS - BACKOFF_MIN_MS + 1)
                    val leftAfterBackoff = deadline - nanoTime() - TimeUnit.MILLISECONDS.toNanos(backoff)
                    if (leftAfterBackoff < TimeUnit.MILLISECONDS.toNanos(MIN_ATTEMPT_MS)) {
                        return failed(start, attempts, status, requestId, "HTTP $status ${describe(status)}, no time left to retry")
                    }
                    log.debug { "HTTP $status ${describe(status)}; retrying in $backoff ms" }
                    delay(backoff)
                }
                else -> return failed(start, attempts, status, requestId, "HTTP $status ${describe(status)}${detail(response.body())}")
            }
        }
    }

    /**
     * One line for `/narrator status`: `active`, `circuit open …`, `cut until /narrator reload: …` or
     * `closed`. Never the key.
     */
    fun status(): String = synchronized(lock) {
        val openUntil = openUntilNanos
        when {
            closed.get() -> "closed"
            cutReason != null -> "cut until /narrator reload: $cutReason"
            openUntil != null && openUntil - nanoTime() > 0 ->
                "circuit open for ${TimeUnit.NANOSECONDS.toSeconds(openUntil - nanoTime()) + 1} s after $failuresInARow failures in a row"
            else -> "active"
        }
    }

    /** Closes the client once its calls in flight are over: the settings were replaced. */
    fun closeWhenIdle() {
        if (closed.compareAndSet(false, true)) http.shutdown()
    }

    /** Closes the client now: the server is stopping and its callers are cancelled. */
    override fun close() {
        closed.set(true)
        http.shutdownNow()
    }

    override fun toString(): String = "JevClient(${config.model} at ${config.baseUrl})"

    private suspend fun send(body: String, leftNanos: Long): HttpResponse<String>? {
        val timeout = Duration.ofNanos(leftNanos)
        val request = HttpRequest.newBuilder(endpoint)
            .timeout(timeout)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        // The request's own timeout covers the exchange; this one also covers connecting.
        return withTimeoutOrNull(timeout.toMillis().coerceAtLeast(1)) {
            http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        }
    }

    private fun requestBody(state: JsonElement, questions: Map<String, JevQuestion>): String = buildJsonObject {
        put("state", state)
        put("model", config.model)
        put(
            "questions",
            JsonObject(questions.mapValues { (_, question) -> question.toJson() }),
        )
    }.toString()

    private fun parse(
        body: String,
        questions: Map<String, JevQuestion>,
        start: Long,
        attempts: Int,
        requestId: String?,
    ): JevResult {
        val root = try {
            JSON.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            return failed(start, attempts, 200, requestId, "unexpected response: not a JSON object")
        }
        val model = (root["model"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: config.model
        val usage = root["usage"] as? JsonObject
        val inputTokens = (usage?.get("input_tokens") as? JsonPrimitive)?.intOrNull
        val outputTokens = (usage?.get("output_tokens") as? JsonPrimitive)?.intOrNull
        val answers = root["answers"] as? JsonObject
            ?: return failed(start, attempts, 200, requestId, "unexpected response: no answers", model, inputTokens, outputTokens)
        val parsed = LinkedHashMap<String, JevAnswer>()
        for ((key, question) in questions) {
            val answer = (answers[key] as? JsonObject)?.let { readAnswer(it, question) }
                ?: return failed(start, attempts, 200, requestId, "unexpected response: no valid answer to $key", model, inputTokens, outputTokens)
            parsed[key] = answer
        }
        recordSuccess()
        return JevResult.Answered(parsed, call(start, attempts, 200, requestId, model, inputTokens, outputTokens, error = null))
    }

    private fun readAnswer(answer: JsonObject, question: JevQuestion): JevAnswer? = when (question) {
        is JevQuestion.Noul -> probability(answer["noul"])?.let { JevAnswer.Noul(it) }
        is JevQuestion.Choice -> {
            val choice = (answer["choice"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val confidence = probability(answer["confidence"])
            if (choice != null && choice in question.options && confidence != null) JevAnswer.Choice(choice, confidence) else null
        }
    }

    /** A number in 0..1, give or take rounding. */
    private fun probability(value: JsonElement?): Double? {
        val number = (value as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull ?: return null
        return if (number in -PROBABILITY_SLACK..1 + PROBABILITY_SLACK) number.coerceIn(0.0, 1.0) else null
    }

    private fun skipReason(): String? = synchronized(lock) {
        val openUntil = openUntilNanos
        when {
            closed.get() -> "client closed"
            cutReason != null -> "cut until /narrator reload: $cutReason"
            openUntil != null && openUntil - nanoTime() > 0 -> "circuit open"
            else -> null
        }
    }

    private fun cut(error: String) {
        synchronized(lock) {
            if (cutReason != null) return
            cutReason = error
        }
        val hint = if (error.startsWith("HTTP 401")) "check the key in the environment variable ${config.apiKeyEnv}" else "a question is malformed"
        log.error("Jev cut until /narrator reload: $error ($hint); the heuristic answers meanwhile")
    }

    private fun recordSuccess() {
        val reopened = synchronized(lock) {
            val wasOpen = failuresInARow >= FAILURES_TO_OPEN
            failuresInARow = 0
            openUntilNanos = null
            wasOpen
        }
        if (reopened) log.info("circuit closed: Jev answers again")
    }

    private fun failed(
        start: Long,
        attempts: Int,
        status: Int?,
        requestId: String?,
        error: String,
        model: String = config.model,
        inputTokens: Int? = null,
        outputTokens: Int? = null,
    ): JevResult.Failed {
        val opened = synchronized(lock) {
            failuresInARow++
            if (failuresInARow >= FAILURES_TO_OPEN) {
                openUntilNanos = nanoTime() + TimeUnit.MILLISECONDS.toNanos(OPEN_FOR_MS)
                true
            } else {
                false
            }
        }
        if (opened) {
            log.warn("circuit open: $FAILURES_TO_OPEN or more failures in a row (last: $error); the heuristic answers for ${OPEN_FOR_MS / 60_000} min")
        }
        return JevResult.Failed(call(start, attempts, status, requestId, model, inputTokens, outputTokens, error))
    }

    private fun call(
        start: Long,
        attempts: Int,
        status: Int?,
        requestId: String?,
        model: String = config.model,
        inputTokens: Int? = null,
        outputTokens: Int? = null,
        error: String?,
    ) = JevCall(model, TimeUnit.NANOSECONDS.toMillis(nanoTime() - start), attempts, status, requestId, inputTokens, outputTokens, error)

    private fun timeoutError() = "timeout after ${config.timeoutMs} ms"

    companion object {
        /** Path of the evaluation endpoint, after `jev.baseUrl`. */
        const val PATH = "/v1/systemone"

        /** Failed calls in a row that open the circuit. */
        const val FAILURES_TO_OPEN = 5

        /** How long an open circuit sends nothing. */
        const val OPEN_FOR_MS = 5 * 60_000L

        const val BACKOFF_MIN_MS = 200L
        const val BACKOFF_MAX_MS = 400L

        /** A retry is only worth sending with at least this much time left after its backoff. */
        const val MIN_ATTEMPT_MS = 200L

        private const val PROBABILITY_SLACK = 1e-6
        private const val DETAIL_MAX_CHARS = 200

        private val JSON = Json { ignoreUnknownKeys = true }

        /**
         * A client when Jev is enabled and the environment variable named by `jev.apiKeyEnv` is set,
         * looked up with [env]; null otherwise (the reflexes use their heuristic).
         */
        fun create(config: JevConfig, env: (String) -> String?): JevClient? {
            if (!config.enabled) {
                Log.jev.info("Jev disabled in the configuration (jev.enabled); the heuristic answers")
                return null
            }
            val key = env(config.apiKeyEnv)?.trim()?.ifEmpty { null }
            if (key == null) {
                Log.jev.info("Jev off: no key in the environment variable ${config.apiKeyEnv}; the heuristic answers")
                return null
            }
            Log.jev.info("Jev on: ${config.model} at ${config.baseUrl}, key from ${config.apiKeyEnv}, ${config.timeoutMs} ms per call")
            return JevClient(config, key)
        }

        private fun isRetryable(status: Int) = status == 429 || status == 529 || status in 500..599

        private fun describe(status: Int): String = when (status) {
            401 -> "unauthorized"
            422 -> "unprocessable request"
            429 -> "rate limited"
            529 -> "overloaded"
            in 500..599 -> "server error"
            else -> "unexpected status"
        }

        /** The start of an error body, which says what was wrong (422); empty when there is none. */
        private fun detail(body: String?): String {
            val text = body?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            return if (text.isEmpty()) "" else ": " + if (text.length > DETAIL_MAX_CHARS) text.take(DETAIL_MAX_CHARS) + "…" else text
        }

        private fun JevQuestion.toJson(): JsonObject = when (this) {
            is JevQuestion.Noul -> buildJsonObject {
                put("type", "noul")
                put("instructions", instructions)
                if (yes != null || no != null) {
                    put(
                        "criteria",
                        buildJsonObject {
                            yes?.let { put("true", it) }
                            no?.let { put("false", it) }
                        },
                    )
                }
            }
            is JevQuestion.Choice -> buildJsonObject {
                put("type", "choice")
                put("instructions", instructions)
                put("criteria", JsonObject(options.mapValues { (_, meaning) -> JsonPrimitive(meaning) }))
            }
        }
    }
}
