package io.github.clemsix6.narrator2.llm

import com.anthropic.core.http.Headers
import com.anthropic.core.jsonMapper
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.SseException
import com.anthropic.errors.UnauthorizedException
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.time.Instant
import java.time.OffsetDateTime

/**
 * LLMGW refused the call because the project's budget is spent (HTTP 402 `budget_exceeded`). Retrying
 * before [resetAt] is pointless, so the client never does.
 *
 * @property resetAt when the budget window resets (`error.reset_at`), when the gateway said so.
 */
class BudgetExceededException(val resetAt: Instant?, dimension: String?, window: String?) :
    RuntimeException("LLMGW budget exceeded (${dimension ?: "?"} per ${window ?: "?"}), resets at ${resetAt ?: "an unknown time"}")

/**
 * The client was closed: `/narrator reload` replaced it, or the server is stopping. Nothing was sent;
 * read the settings again for the client in force.
 */
class ClientClosedException :
    IllegalStateException("this LLMGW client is closed (replaced by /narrator reload, or the server is stopping)")

/** The response stream ended before `message_stop`: the connection was cut mid-answer. */
class TruncatedStreamException(afterContent: Boolean) :
    IOException("the response stream ended before message_stop, ${if (afterContent) "after" else "before"} any content")

/** A short explanation of a failed call, for logs, transcripts and operators. */
fun describeFailure(error: Throwable): String = when (error) {
    is BudgetExceededException, is TruncatedStreamException, is ClientClosedException -> error.message.orEmpty()
    is UnauthorizedException -> "key rejected by the gateway (HTTP 401)"
    is PermissionDeniedException -> "access denied by the gateway (HTTP 403)"
    is RateLimitException -> "rate limit reached (HTTP 429)"
    is SseException -> "error in the response stream: ${apiError(error) ?: error.message}"
    is AnthropicServiceException -> "HTTP ${error.statusCode()}: ${apiError(error) ?: error.message}"
    else -> {
        val cause = error.causes().last()
        when {
            error.causes().any { it is SocketTimeoutException } -> "read timeout: the gateway sent nothing for too long"
            error.causes().any { it is InterruptedIOException } -> "total timeout: the call took too long"
            isConnectionError(error) -> "gateway unreachable: ${cause.javaClass.simpleName}: ${cause.message}"
            else -> "${error.javaClass.simpleName}: ${error.message}"
        }
    }
}

/** LLMGW's 402 `budget_exceeded`, with its reset time; null for any other failure. */
internal fun budgetExceeded(error: Throwable): BudgetExceededException? {
    if (error !is AnthropicServiceException || error.statusCode() != 402) return null
    val detail = runCatching { jsonMapper().valueToTree<JsonNode>(error.body()) }.getOrNull()?.path("error") ?: return null
    if (detail.path("type").asText() != "budget_exceeded") return null
    val resetAt = detail.path("reset_at").takeIf { it.isTextual }
        ?.let { runCatching { OffsetDateTime.parse(it.asText()).toInstant() }.getOrNull() }
    return BudgetExceededException(resetAt, detail.path("dimension").textOrNull(), detail.path("window").textOrNull())
        .apply { initCause(error) }
}

/**
 * The id to quote to the gateway's operator: Anthropic's `request-id`, else CLIProxyAPI's
 * `X-Cpa-Trace-Id` (LLMGW does not relay `request-id`, verified 25/09/2026).
 */
internal fun requestIdOf(headers: Headers): String? =
    (headers.values("request-id") + headers.values("x-cpa-trace-id")).firstOrNull()

private fun JsonNode.textOrNull(): String? = if (isTextual) asText() else null

/** A network failure (refused, reset or timed-out connection), as opposed to a malformed answer. */
internal fun isConnectionError(error: Throwable): Boolean =
    error.causes().any { it is AnthropicIoException || (it is IOException && it !is JsonProcessingException) }

/** `type: message` from the API's error body, e.g. `overloaded_error: Overloaded`; null when it has none. */
private fun apiError(error: AnthropicServiceException): String? = runCatching {
    val detail = jsonMapper().valueToTree<JsonNode>(error.body()).path("error")
    listOf(detail.path("type").asText(""), detail.path("message").asText(""))
        .filter { it.isNotEmpty() }
        .joinToString(": ")
        .ifEmpty { null }
}.getOrNull()

private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }
