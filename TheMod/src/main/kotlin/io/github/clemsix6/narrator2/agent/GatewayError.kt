package io.github.clemsix6.narrator2.agent

import com.anthropic.errors.AnthropicInvalidDataException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.SseException
import io.github.clemsix6.narrator2.llm.BudgetExceededException
import io.github.clemsix6.narrator2.llm.TruncatedStreamException
import io.github.clemsix6.narrator2.llm.describeFailure
import io.github.clemsix6.narrator2.llm.isConnectionError
import java.io.InterruptedIOException
import java.time.Instant

/**
 * Why a call to Claude failed, once `ClaudeClient` has made its retries: what the Director and the
 * workers decide on (the engine never retries a call at once itself).
 */
sealed interface GatewayError {
    /** One line for logs, decisions and operators. */
    val description: String

    /** HTTP 402 `budget_exceeded`: the project's budget is spent until [resetAt], null when the gateway did not say. */
    data class Budget(val resetAt: Instant?) : GatewayError {
        override val description get() = "LLMGW budget exceeded until ${resetAt ?: "an unknown time"}"
    }

    /** HTTP 401 or 403: the key is refused, until the settings are reloaded. */
    data object Auth : GatewayError {
        override val description get() = "LLMGW key refused (HTTP 401/403)"
    }

    /** HTTP 400, 413 or another 4xx: the request was malformed, a bug in its construction. */
    data class BadRequest(val status: Int, val message: String) : GatewayError {
        override val description get() = message
    }

    /** HTTP 400 about the `system` role: the gateway rejects system messages inside the conversation. */
    data class SystemRoleRejected(val message: String) : GatewayError {
        override val description get() = "system message rejected: $message"
    }

    /** The conversation no longer fits the model's context window. */
    data object ContextExceeded : GatewayError {
        override val description get() = "the conversation exceeds the context window"
    }

    /**
     * Worth trying again later: 429, 529, 5xx, timeout, network, broken or unreadable stream.
     *
     * @property kind `rate_limited`, `overloaded`, `server_error`, `timeout`, `network`, `stream` or `unexpected`.
     */
    data class Transient(val kind: String, val message: String) : GatewayError {
        override val description get() = "$kind: $message"
    }

    /** No usable client: the LLMGW key is missing, or the client was closed and not replaced. */
    data class Unavailable(val reason: String) : GatewayError {
        override val description get() = reason
    }

    companion object {
        private val SYSTEM_ROLE = Regex("""(?i)\brole\b.*\bsystem\b|\bsystem\b.*\brole\b""")
        private val CONTEXT = Regex("""(?i)prompt is too long|context (window|limit)""")

        /**
         * Classifies the error of a failed call (`Exchange.Failed`). Anything unforeseen, such as a
         * response the SDK cannot read, is a transient `unexpected` failure: the turn stops, the engine
         * lives on. (The caller's mistakes, a closed client or a conversation already in flight, are
         * thrown by `ClaudeClient.exchange` before anything is sent, never classified.)
         */
        fun of(error: Throwable): GatewayError {
            val message = describeFailure(error)
            return when {
                error is BudgetExceededException -> Budget(error.resetAt)
                error is TruncatedStreamException || error is SseException -> Transient("stream", message)
                error is AnthropicServiceException -> ofStatus(error.statusCode(), message)
                error is AnthropicInvalidDataException -> Transient("stream", message)
                isConnectionError(error) && error.causes().any { it is InterruptedIOException } -> Transient("timeout", message)
                isConnectionError(error) -> Transient("network", message)
                else -> Transient("unexpected", message)
            }
        }

        private fun ofStatus(status: Int, message: String): GatewayError = when {
            status == 401 || status == 403 -> Auth
            status == 400 && CONTEXT.containsMatchIn(message) -> ContextExceeded
            status == 400 && SYSTEM_ROLE.containsMatchIn(message) -> SystemRoleRejected(message)
            status == 429 -> Transient("rate_limited", message)
            status == 529 -> Transient("overloaded", message)
            status >= 500 -> Transient("server_error", message)
            else -> BadRequest(status, message)
        }

        private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }
    }
}
