package io.github.clemsix6.narrator2.reflex

import com.anthropic.models.messages.JsonOutputFormat
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.journal.IdKind
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.trace.LlmCallRecord
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * The reflexes of a running server: a small Claude model through LLMGW when the settings have a
 * [ReflexClient], read at each call since `/narrator reload` may replace it; [HeuristicReflexes] when they
 * have none, and for any call the model fails to answer. One request per chat message ([ReflexLabels.chatRequest]),
 * one per heartbeat ([ReflexLabels.heartbeatRequest]); each request gives an `llm_calls` row (provider
 * `anthropic`, role [ROLE]). Private messages never reach it: [triageChatMessage] refuses them.
 *
 * Never throws, except the caller's cancellation.
 */
class ClaudeReflexes(private val services: NarratorServices) : Reflexes {
    private val log = Log.reflex

    override suspend fun triageChat(input: ChatTriageInput): ChatVerdict {
        val heuristic = heuristic()
        val client = services.settings.get().reflex ?: return heuristic.judge(input).also { logHeuristic(input, it) }
        val start = System.nanoTime()
        val verdict = ask(client, LlmCallRecord.Purpose.CHAT_TRIAGE, "chat triage ${input.message.player}", ReflexLabels.chatRequest(input), ReflexLabels.CHAT_SCHEMA) { labels, model ->
            ReflexLabels.chatVerdict(labels, "$SOURCE_PREFIX$model", elapsedMs(start))
        } ?: return heuristic.judge(input).copy(latencyMs = elapsedMs(start))
        log.info(
            "chat triage ${input.message.player} by ${verdict.source.removePrefix(SOURCE_PREFIX)} in ${verdict.latencyMs} ms: " +
                "to you ${ReflexThresholds.p(verdict.toNarrator)}, ${verdict.tone.id} ${ReflexThresholds.p(verdict.toneConfidence)}, " +
                "${verdict.intent.id}, manipulation ${ReflexThresholds.p(verdict.manipulation)}",
        )
        return verdict
    }

    override suspend fun heartbeat(windowMinutes: Int, summary: List<String>, online: List<String>): HeartbeatVerdict {
        val client = services.settings.get().reflex ?: return heuristic().heartbeat(windowMinutes, summary, online)
        val start = System.nanoTime()
        val request = ReflexLabels.heartbeatRequest(windowMinutes, summary, online)
        val verdict = ask(client, LlmCallRecord.Purpose.HEARTBEAT_FILTER, "heartbeat", request, ReflexLabels.HEARTBEAT_SCHEMA) { labels, model ->
            ReflexLabels.heartbeatVerdict(labels, "$SOURCE_PREFIX$model", elapsedMs(start))
        } ?: return heuristic().heartbeat(windowMinutes, summary, online).copy(latencyMs = elapsedMs(start))
        log.info("heartbeat by ${verdict.source.removePrefix(SOURCE_PREFIX)} in ${verdict.latencyMs} ms: worth ${ReflexThresholds.p(verdict.worth)}, hook ${verdict.hook ?: "none"}")
        return verdict
    }

    /** The heuristic of the configured language. */
    private fun heuristic() = HeuristicReflexes.forLanguage(services.settings.get().config.language)

    /**
     * Sends [request], writes its `llm_calls` row when it went out, and gives what [read] makes of the
     * labels with the model that answered; null when there is nothing usable, and the heuristic answers.
     */
    private suspend fun <T : Any> ask(
        client: ReflexClient,
        purpose: LlmCallRecord.Purpose,
        what: String,
        request: String,
        schema: JsonOutputFormat.Schema,
        read: (JsonObject, String) -> T?,
    ): T? {
        val startedAt = services.clock.instant()
        val result = try {
            client.label(services.settings.get().prompts.get(ReflexLabels.PROMPT), request, schema)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("$what: unexpected failure of the reflex client; the heuristic answers", e)
            return null
        }
        return when (result) {
            is ReflexResult.Labeled -> {
                val value = read(result.labels, result.call.model)
                // The reason quotes the player: like the chat itself, it never goes to the log.
                val labels = JsonObject(result.labels - ReflexLabels.REASON)
                if (value == null) {
                    record(purpose, startedAt, client.effort, result.call.copy(error = "unexpected labels: a label is missing or invalid"))
                    log.warn("$what: unexpected labels $labels; the heuristic answers")
                } else {
                    record(purpose, startedAt, client.effort, result.call)
                    log.debug { "$what: labels $labels" }
                }
                value
            }
            is ReflexResult.Failed -> {
                record(purpose, startedAt, client.effort, result.call)
                log.warn("$what failed after ${result.call.latencyMs} ms (${result.call.error}); the heuristic answers")
                null
            }
            is ReflexResult.Skipped -> {
                log.debug { "$what: reflex model skipped (${result.reason}); the heuristic answers" }
                null
            }
        }
    }

    private fun record(purpose: LlmCallRecord.Purpose, startedAt: Instant, effort: String, call: ReflexCall) {
        try {
            val trace = services.trace
            trace.llmCall(
                LlmCallRecord(
                    id = trace.nextId(IdKind.LLM_CALL),
                    provider = LlmCallRecord.Provider.ANTHROPIC,
                    purpose = purpose,
                    conversationId = null,
                    turnId = null,
                    role = ROLE,
                    model = call.model,
                    effort = effort,
                    startedAt = startedAt,
                    endedAt = startedAt.plusMillis(call.latencyMs),
                    latencyMs = call.latencyMs.toInt(),
                    ttftMs = null,
                    attempts = 1,
                    requestId = call.requestId,
                    messageId = call.messageId,
                    inputTokens = call.inputTokens,
                    cacheReadTokens = call.cacheReadTokens,
                    cacheWriteTokens = call.cacheWriteTokens,
                    outputTokens = call.outputTokens,
                    stopReason = call.stopReason,
                    refusalCategory = call.refusalCategory,
                    error = call.error,
                    httpStatus = call.httpStatus,
                    thinking = null,
                ),
            )
        } catch (e: Exception) {
            log.error("cannot record a reflex call in llm_calls", e)
        }
    }

    private fun logHeuristic(input: ChatTriageInput, verdict: ChatVerdict) = log.debug {
        "chat triage ${input.message.player} by heuristic: to you ${ReflexThresholds.p(verdict.toNarrator)}, ${verdict.tone.id}, " +
            "${verdict.intent.id}, manipulation ${ReflexThresholds.p(verdict.manipulation)}"
    }

    companion object {
        /** `llm_calls.role` of a reflex call. */
        const val ROLE = "reflex"

        /** [ChatVerdict.source] and [HeartbeatVerdict.source] of the model's verdicts: this prefix and the model that answered. */
        const val SOURCE_PREFIX = "reflex:"

        private fun elapsedMs(start: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
    }
}
