package io.github.clemsix6.narrator2.trace

import io.github.clemsix6.narrator2.llm.Role
import java.time.OffsetDateTime
import java.util.Locale

/**
 * Measurements of one call to Claude, successful or not: one row of `llm_calls`.
 *
 * @property model the model that answered: the role's, or the fallback model when a safety classifier
 *   handed the request to another one (`llm/Fallback`); the role's when the call failed.
 * @property startedAt when the call started, with the server's time zone.
 * @property attempts HTTP requests sent, retries included.
 * @property requestId id of the last attempt, when known: the `request-id` response header, or
 *   `X-Cpa-Trace-Id` behind LLMGW, which does not relay the former.
 * @property messageId id of the message Claude returned.
 * @property timeToFirstTokenMs from the start of the last attempt to the first streamed content.
 * @property durationMs whole call, retries and waits included.
 * @property stopReason the API's `stop_reason`, e.g. `end_turn`; null when the call failed.
 * @property refusalCategory category of a refusal (`stop_details.category`), e.g. `cyber`; also set when
 *   the requested model declined and a fallback model answered (the fallback's category, with a usable
 *   [stopReason]).
 * @property error operator-facing description of a failure; null when the call succeeded.
 */
data class CallStats(
    val conversationId: String,
    val role: Role,
    val model: String,
    val effort: String,
    val startedAt: OffsetDateTime,
    val attempts: Int,
    val durationMs: Long,
    val requestId: String? = null,
    val messageId: String? = null,
    val timeToFirstTokenMs: Long? = null,
    val inputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val outputTokens: Long = 0,
    val stopReason: String? = null,
    val refusalCategory: String? = null,
    val error: String? = null,
) {
    /** Whole context seen by the model: uncached input plus cache reads and writes. */
    val contextTokens: Long get() = inputTokens + cacheReadTokens + cacheWriteTokens

    /**
     * One line for logs and operators, e.g.
     * `2.4 s (first token 0.8 s) · input 120 (+900 cache read, 60 cache write) · output 45 · end_turn`.
     */
    fun summary(): String = buildString {
        append("%.1f s".format(Locale.ROOT, durationMs / 1000.0))
        timeToFirstTokenMs?.let { append(" (first token %.1f s)".format(Locale.ROOT, it / 1000.0)) }
        append(" · input $inputTokens (+$cacheReadTokens cache read, $cacheWriteTokens cache write) · output $outputTokens")
        append(" · ${stopReason ?: "failed"}")
        refusalCategory?.let { append(" ($it)") }
    }

    /** Where the call went: attempts, then the request and message ids when known. */
    fun ids(): String = buildString {
        append("$attempts attempt${if (attempts > 1) "s" else ""}")
        requestId?.let { append(" · request $it") }
        messageId?.let { append(" · message $it") }
    }
}
