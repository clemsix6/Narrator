package io.github.clemsix6.narrator2.trace

import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.reflex.ChatIntent
import io.github.clemsix6.narrator2.reflex.ChatVerdict
import io.github.clemsix6.narrator2.reflex.Tone
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolStatus
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.util.UUID

/*
 * One data class per trace table of `sql/V1__journal.sql`, column for column, in the same order.
 * Enum values are stored as their `id`: the constant's name in lowercase, e.g. `step_limit`.
 */

/**
 * One call to a model: an Anthropic call (successful or not) or a Jev call. Row of `llm_calls`.
 *
 * @property role role id (`Role.id`) for an Anthropic call; `reflex` for Jev.
 * @property effort Anthropic effort; null for Jev.
 * @property latencyMs whole call, retries included.
 * @property ttftMs time to the first streamed token of the last attempt.
 * @property attempts HTTP requests sent, retries included.
 * @property requestId gateway request id of the last attempt (`request-id` or LLMGW's `X-Cpa-Trace-Id`).
 * @property inputTokens and the other token counts: null when the call failed before any reply.
 * @property error operator-facing description of the failure; null on success.
 * @property thinking thinking summary, at most [THINKING_MAX_CHARS] characters.
 */
data class LlmCallRecord(
    val id: Long,
    val provider: Provider,
    val purpose: Purpose,
    val conversationId: String?,
    val turnId: Long?,
    val role: String,
    val model: String,
    val effort: String?,
    val startedAt: Instant,
    val endedAt: Instant,
    val latencyMs: Int,
    val ttftMs: Int?,
    val attempts: Int,
    val requestId: String?,
    val messageId: String?,
    val inputTokens: Int?,
    val cacheReadTokens: Int?,
    val cacheWriteTokens: Int?,
    val outputTokens: Int?,
    val stopReason: String?,
    val refusalCategory: String?,
    val error: String?,
    val httpStatus: Int?,
    val thinking: String?,
) {
    enum class Provider {
        ANTHROPIC, JEV;

        val id: String get() = name.lowercase()
    }

    enum class Purpose {
        /** A request of a Director turn. */
        TURN,

        /** The request that closes a chapter with its summary. */
        CHAPTER_SUMMARY,

        /** A request of a worker's task (the Analyst in M2). */
        WORKER,

        /** Jev: is this chat message addressed to the Narrator, and how. */
        CHAT_TRIAGE,

        /** Jev: is this heartbeat worth waking the Director. */
        HEARTBEAT_FILTER,
        ;

        val id: String get() = name.lowercase()
    }

    companion object {
        const val THINKING_MAX_CHARS = 4_000

        /**
         * The row of an Anthropic call measured by [stats]; [thinking] is truncated to
         * [THINKING_MAX_CHARS]. Token counts are null when the call got no reply.
         */
        fun anthropic(
            id: Long,
            stats: CallStats,
            purpose: Purpose,
            turnId: Long?,
            thinking: String?,
            httpStatus: Int? = null,
        ): LlmCallRecord {
            val replied = stats.stopReason != null
            return LlmCallRecord(
                id = id,
                provider = Provider.ANTHROPIC,
                purpose = purpose,
                conversationId = stats.conversationId,
                turnId = turnId,
                role = stats.role.id,
                model = stats.model,
                effort = stats.effort,
                startedAt = stats.startedAt.toInstant(),
                endedAt = stats.startedAt.toInstant().plusMillis(stats.durationMs),
                latencyMs = stats.durationMs.toInt(),
                ttftMs = stats.timeToFirstTokenMs?.toInt(),
                attempts = stats.attempts,
                requestId = stats.requestId,
                messageId = stats.messageId,
                inputTokens = stats.inputTokens.toInt().takeIf { replied },
                cacheReadTokens = stats.cacheReadTokens.toInt().takeIf { replied },
                cacheWriteTokens = stats.cacheWriteTokens.toInt().takeIf { replied },
                outputTokens = stats.outputTokens.toInt().takeIf { replied },
                stopReason = stats.stopReason,
                refusalCategory = stats.refusalCategory,
                error = stats.error,
                httpStatus = httpStatus,
                thinking = thinking?.take(THINKING_MAX_CHARS),
            )
        }
    }
}

/**
 * One tool call. Row of `tool_calls`.
 *
 * @property llmCallId the `llm_calls` row of the reply that asked for it.
 * @property args the tool input, as JSON text.
 * @property result the text returned to the model, at most [RESULT_MAX_CHARS] characters.
 * @property targets names of the players it affected.
 */
data class ToolCallRecord(
    val id: Long,
    val ts: Instant,
    val conversationId: String,
    val turnId: Long?,
    val llmCallId: Long?,
    val toolUseId: String,
    val role: Role,
    val tool: String,
    val args: String?,
    val status: ToolStatus,
    val result: String?,
    val durationMs: Int,
    val targets: List<String>,
    val effect: ToolEffect,
) {
    companion object {
        const val RESULT_MAX_CHARS = 2_000
    }
}

/**
 * A decision of the engine, with its reason: why it woke up or stayed silent, what a guard refused…
 * Row of `engine_decisions`. Write it through [Decisions], which also logs it.
 *
 * @property decision what was decided, a short lowercase verb: `woke`, `queued`, `batched`, `skipped`,
 *   `denied`, `allowed`, `rescued`, `counted`, `died`, `returned`, `expired`, `opened`, `closed`, `blocked`,
 *   `resumed`…
 * @property subject what it is about: a player's name, a tool, a conversation id…
 * @property reason one English sentence.
 * @property eventId the event that caused it, if any.
 */
data class DecisionRecord(
    val id: Long,
    val ts: Instant,
    val kind: Kind,
    val decision: String,
    val subject: String?,
    val reason: String?,
    val eventId: Long?,
    val conversationId: String?,
    val turnId: Long?,
    val detail: JsonObject?,
) {
    enum class Kind {
        /** The Director woke up, or a wake was scheduled. */
        WAKE,

        /** An event was classified (urgency, chat verdict). */
        SALIENCE,

        /** A heartbeat woke the Director or was skipped. */
        HEARTBEAT,

        /** A guard refused or allowed an action. */
        GUARD,

        /** A death: attributed to the Narrator and counted, refused beyond the quota and the player rescued, or explained (`died`). */
        DEATH_GUARD,

        /** An attribute modifier or a confiscation was applied, expired, reapplied or returned. */
        LEDGER,

        /** A chapter was opened, marked for closing, closed or summarized. */
        CHAPTER,

        /** The gateway's budget is spent. */
        BUDGET,

        /** A transient gateway failure: the Director backs off. */
        BACKOFF,

        /** The model refused to answer. */
        REFUSAL,

        /** An operator command (pause, resume, whisper, say, protect, sql) or an operator channel switch. */
        OPERATOR,

        /** `/narrator reload`. */
        RELOAD,

        /** The Journal's periodic health report, or a loss. */
        JOURNAL_HEALTH,

        /** A tool set was built for a conversation. */
        TOOL_CATALOG,
        ;

        val id: String get() = name.lowercase()
    }
}

/**
 * One Director turn: from a wake-up to the end of the model's work. Row of `turns`, inserted when the
 * turn starts and updated when it ends.
 *
 * @property triggerEvents ids of the events rendered in the wake message.
 * @property injectedEvents ids of the events injected between tool calls.
 * @property llmCalls and [toolCalls]: counted when the turn ends.
 * @property outcome null while the turn runs.
 */
data class TurnRecord(
    val turnId: Long,
    val conversationId: String,
    val startedAt: Instant,
    val endedAt: Instant?,
    val trigger: Trigger,
    val triggerEvents: List<Long>,
    val injectedEvents: List<Long>,
    val llmCalls: Int?,
    val toolCalls: Int?,
    val outcome: Outcome?,
) {
    enum class Trigger {
        CHAT, PRAY, SALIENT, HEARTBEAT, TASK, REMINDER, OPERATOR, RESUME, CHAPTER_CLOSE;

        val id: String get() = name.lowercase()
    }

    enum class Outcome {
        /** At least one speech, world or task tool succeeded. */
        ACTED,

        /** Ended without acting: a normal, silent turn. */
        IDLE,
        STEP_LIMIT,
        REFUSED,
        TRUNCATED,
        FAILED,
        BUDGET,

        /** Cancelled by the server stopping. */
        CANCELLED,

        /** Found unfinished after a crash. */
        INTERRUPTED,
        ;

        val id: String get() = name.lowercase()
    }
}

/**
 * One conversation with Claude: a Director chapter or a worker's task. Row of `conversations`,
 * inserted when it opens and updated when it ends.
 *
 * @property conversationId the conversation's id (`Conversation.id`, also in the `X-Narrator-Conversation` header).
 * @property chapter Director chapters, numbered from 1.
 * @property taskId worker conversations.
 * @property summary chapter summary (Director) or task result (workers).
 * @property transcript path of the Markdown transcript, relative to `world/narrator2/`.
 */
data class ConversationRecord(
    val conversationId: String,
    val role: Role,
    val label: String,
    val chapter: Int?,
    val taskId: Long?,
    val startedAt: Instant,
    val endedAt: Instant?,
    val endReason: EndReason?,
    val summary: String?,
    val transcript: String?,
) {
    enum class EndReason {
        /** The context reached `director.chapterHardLimit`: closed at the end of the turn. */
        TOKEN_LIMIT,

        /** The context reached `director.chapterTokenLimit`: closed at the next quiet moment. */
        QUIET_LIMIT,

        /** No player online for `director.emptyServerCloseMinutes`. */
        SERVER_EMPTY,

        /** `/narrator reload` changed the prompt or the operator channel. */
        RELOAD,

        /** The model refused twice during a turn. */
        REFUSAL,

        /** The gateway rejected the request as malformed (HTTP 400). */
        BAD_REQUEST,

        /** A turn reached its hard step limit. */
        RUNAWAY_TURN,
        CONTEXT_EXCEEDED,

        /** `/narrator chapter close`. */
        OPERATOR,

        /** The server stopped (no summary). */
        SHUTDOWN,

        /** Found unfinished after a crash. */
        INTERRUPTED,

        /** Worker conversations: how the task ended. */
        DONE,
        FAILED,
        CANCELLED,
        TIMEOUT,
        ;

        val id: String get() = name.lowercase()
    }
}

/**
 * A task delegated to a worker. Row of `tasks`, inserted when queued and updated at each change.
 *
 * @property requestedByTurn the Director turn that delegated it.
 * @property conversationId the worker's conversation, once it started.
 */
data class TaskRecord(
    val taskId: Long,
    val role: Role,
    val brief: String,
    val status: Status,
    val createdAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val requestedByTurn: Long?,
    val conversationId: String?,
    val result: String?,
    val error: String?,
) {
    enum class Status {
        QUEUED, RUNNING, DONE, FAILED, CANCELLED, TIMEOUT;

        val id: String get() = name.lowercase()

        val finished: Boolean get() = this != QUEUED && this != RUNNING
    }
}

/**
 * The reflexes' verdict on one chat message (public chat or prayer). Row of `chat_signals`; the
 * personality signals are kept for the player sheets of M3.
 *
 * @property eventId id of the chat message.
 * @property source `jev:<model>` or `heuristic`.
 */
data class ChatSignalsRecord(
    val eventId: Long,
    val ts: Instant,
    val playerUuid: UUID,
    val source: String,
    val toNarrator: Double?,
    val tone: Tone?,
    val toneConfidence: Double?,
    val intent: ChatIntent?,
    val manipulation: Double?,
    val politeness: Double?,
    val boastfulness: Double?,
    val helpfulness: Double?,
    val aggressiveness: Double?,
    val humor: Double?,
    val latencyMs: Int?,
) {
    companion object {
        /** The row of [verdict] on the chat message [eventId] sent by [playerUuid]. */
        fun of(eventId: Long, ts: Instant, playerUuid: UUID, verdict: ChatVerdict) = ChatSignalsRecord(
            eventId = eventId,
            ts = ts,
            playerUuid = playerUuid,
            source = verdict.source,
            toNarrator = verdict.toNarrator,
            tone = verdict.tone,
            toneConfidence = verdict.toneConfidence,
            intent = verdict.intent,
            manipulation = verdict.manipulation,
            politeness = verdict.personality?.politeness,
            boastfulness = verdict.personality?.boastfulness,
            helpfulness = verdict.personality?.helpfulness,
            aggressiveness = verdict.personality?.aggressiveness,
            humor = verdict.personality?.humor,
            latencyMs = verdict.latencyMs.toInt(),
        )
    }
}
