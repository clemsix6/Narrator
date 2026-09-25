package io.github.clemsix6.narrator2.reflex

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.journal.IdKind
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.trace.LlmCallRecord
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The reflexes of a running server (M1_M2 §11): Jev when the settings have a client, read at each call
 * since `/narrator reload` may replace it; [HeuristicReflexes] when they have none, and for any call Jev
 * fails to answer. One request of 9 questions per chat message, one of 2 per heartbeat
 * ([JevQuestions]); each request gives an `llm_calls` row (provider `jev`, role [ROLE]).
 *
 * Never throws, except the caller's cancellation.
 */
class JevReflexes(private val services: NarratorServices) : Reflexes {
    private val log = Log.jev

    override suspend fun triageChat(input: ChatTriageInput): ChatVerdict {
        val heuristic = heuristic()
        val jev = services.settings.get().jev ?: return heuristic.judge(input).also { logHeuristic(input, it) }
        val start = System.nanoTime()
        val answers = ask(jev, LlmCallRecord.Purpose.CHAT_TRIAGE, "chat triage ${input.message.player}") {
            jev.evaluate(JevQuestions.chatState(input), JevQuestions.CHAT)
        }
        val latency = elapsedMs(start)
        if (answers == null) return heuristic.judge(input).copy(latencyMs = latency)
        val verdict = chatVerdict(answers.first, "$SOURCE_PREFIX${answers.second}", latency)
        log.info(
            "chat triage ${input.message.player} by ${answers.second} in $latency ms: to you ${p(verdict.toNarrator)}, " +
                "${verdict.tone.id} ${p(verdict.toneConfidence)}, ${verdict.intent.id}, manipulation ${p(verdict.manipulation)}",
        )
        return verdict
    }

    override suspend fun heartbeat(windowMinutes: Int, summary: List<String>, online: List<String>): HeartbeatVerdict {
        val jev = services.settings.get().jev ?: return heuristic().heartbeat(windowMinutes, summary, online)
        val start = System.nanoTime()
        val answers = ask(jev, LlmCallRecord.Purpose.HEARTBEAT_FILTER, "heartbeat") {
            jev.evaluate(JevQuestions.heartbeatState(windowMinutes, summary, online), JevQuestions.HEARTBEAT)
        }
        val latency = elapsedMs(start)
        if (answers == null) return heuristic().heartbeat(windowMinutes, summary, online).copy(latencyMs = latency)
        val (values, model) = answers
        val worth = (values.getValue(JevQuestions.WORTH) as JevAnswer.Noul).value
        val hook = (values.getValue(JevQuestions.HOOK) as JevAnswer.Choice).choice.takeIf { it != JevQuestions.OTHER_HOOK }
        log.info("heartbeat by $model in $latency ms: worth ${p(worth)}, hook ${hook ?: "none"}")
        return HeartbeatVerdict(worth, hook, "$SOURCE_PREFIX$model", latency)
    }

    /** The heuristic of the configured language. */
    private fun heuristic() = HeuristicReflexes.forLanguage(services.settings.get().config.language)

    /**
     * Runs [call], writes its `llm_calls` row when a request went out, and gives the answers with the model
     * that gave them; null when there are none, and the heuristic answers.
     */
    private suspend fun ask(
        jev: JevClient,
        purpose: LlmCallRecord.Purpose,
        what: String,
        call: suspend () -> JevResult,
    ): Pair<Map<String, JevAnswer>, String>? {
        val startedAt = services.clock.instant()
        val result = try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("$what: unexpected failure of the Jev client; the heuristic answers", e)
            return null
        }
        return when (result) {
            is JevResult.Answered -> {
                record(purpose, startedAt, result.call)
                result.answers to result.call.model
            }
            is JevResult.Failed -> {
                record(purpose, startedAt, result.call)
                log.warn("$what failed after ${result.call.latencyMs} ms (${result.call.error}); the heuristic answers")
                null
            }
            is JevResult.Skipped -> {
                log.debug { "$what: Jev skipped (${result.reason}); the heuristic answers" }
                null
            }
        }
    }

    private fun record(purpose: LlmCallRecord.Purpose, startedAt: Instant, call: JevCall) {
        try {
            val trace = services.trace
            trace.llmCall(
                LlmCallRecord(
                    id = trace.nextId(IdKind.LLM_CALL),
                    provider = LlmCallRecord.Provider.JEV,
                    purpose = purpose,
                    conversationId = null,
                    turnId = null,
                    role = ROLE,
                    model = call.model,
                    effort = null,
                    startedAt = startedAt,
                    endedAt = startedAt.plusMillis(call.latencyMs),
                    latencyMs = call.latencyMs.toInt(),
                    ttftMs = null,
                    attempts = call.attempts,
                    requestId = call.requestId,
                    messageId = null,
                    inputTokens = call.inputTokens,
                    cacheReadTokens = null,
                    cacheWriteTokens = null,
                    outputTokens = call.outputTokens,
                    stopReason = null,
                    refusalCategory = null,
                    error = call.error,
                    httpStatus = call.httpStatus,
                    thinking = null,
                ),
            )
        } catch (e: Exception) {
            log.error("cannot record a Jev call in llm_calls", e)
        }
    }

    private fun logHeuristic(input: ChatTriageInput, verdict: ChatVerdict) = log.debug {
        "chat triage ${input.message.player} by heuristic: to you ${p(verdict.toNarrator)}, ${verdict.tone.id}, " +
            "${verdict.intent.id}, manipulation ${p(verdict.manipulation)}"
    }

    companion object {
        /** `llm_calls.role` of a Jev call. */
        const val ROLE = "reflex"

        /** [ChatVerdict.source] of a Jev verdict is this prefix and the model that answered. */
        const val SOURCE_PREFIX = "jev:"

        private fun elapsedMs(start: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)

        /** A probability with two decimals, whatever the server's locale. */
        internal fun p(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

        private fun chatVerdict(answers: Map<String, JevAnswer>, source: String, latencyMs: Long): ChatVerdict {
            fun noul(key: String) = (answers.getValue(key) as JevAnswer.Noul).value
            fun choice(key: String) = answers.getValue(key) as JevAnswer.Choice
            val tone = choice(JevQuestions.TONE)
            val intent = choice(JevQuestions.MESSAGE_TYPE)
            return ChatVerdict(
                toNarrator = noul(JevQuestions.ADDRESSED),
                tone = Tone.entries.first { it.id == tone.choice },
                toneConfidence = tone.confidence,
                manipulation = noul(JevQuestions.MANIPULATION),
                intent = ChatIntent.entries.first { it.id == intent.choice },
                personality = PersonalitySignals(
                    politeness = noul(JevQuestions.POLITENESS),
                    boastfulness = noul(JevQuestions.BOASTFULNESS),
                    helpfulness = noul(JevQuestions.HELPFULNESS),
                    aggressiveness = noul(JevQuestions.AGGRESSIVENESS),
                    humor = noul(JevQuestions.HUMOR),
                ),
                source = source,
                latencyMs = latencyMs,
            )
        }
    }
}
