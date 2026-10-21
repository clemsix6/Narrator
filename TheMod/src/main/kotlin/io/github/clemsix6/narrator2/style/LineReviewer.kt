package io.github.clemsix6.narrator2.style

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.event.Envelope
import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.event.NarratorChannel
import io.github.clemsix6.narrator2.event.NarratorSpoke
import io.github.clemsix6.narrator2.reflex.ClaudeReflexes
import io.github.clemsix6.narrator2.reflex.ReflexCall
import io.github.clemsix6.narrator2.reflex.ReflexResult
import io.github.clemsix6.narrator2.reflex.ReflexThresholds
import io.github.clemsix6.narrator2.reflex.recordReflexCall
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.runtime.Phase
import io.github.clemsix6.narrator2.runtime.ShutdownSequence
import io.github.clemsix6.narrator2.trace.LlmCallRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * The line review (M6 §3.3): once a line of the Director is said, the reflexes' model labels it (its comic register,
 * its flaws, its crutch words), for the style watch ([StyleWatch.labeled]) and `line_labels`. Never before the line is
 * sent, never on the Director's path: an asynchronous subscriber of the bus, whose queue ([CAPACITY] lines) drops what
 * overflows (counted by the bus), and one coroutine that reviews the lines one at a time.
 *
 * Only the Director's lines are reviewed ([reviewed]): a turn's, of [MIN_CHARS] characters or more; neither an operator's
 * (`/narrator say`), nor a rule's or a module's, nor a title or a line above the hotbar. Each review is one call of
 * the reflexes' client (`settings.reflex`, read at each line), with its `llm_calls` row (purpose `line_review`).
 *
 * Bounded: [MAX_PER_HOUR] reviews in any hour at most. A failure costs nothing but the labels: a timeout, an error, an
 * invalid answer, an open circuit or `style.lineReview = false` give no labels, and the line goes unlabeled. The
 * client's circuit is shared with the chat triage, which wakes the Director: after [FAILURES_TO_PAUSE] failures in a
 * row, the review pauses itself for [PAUSE_MS], before it could open that circuit; the first review after the pause is
 * a probe, and pauses again if it fails. The reviewer's reason is never logged, nor the line itself.
 */
class LineReviewer(private val services: NarratorServices) {
    private val log = Log.style

    // Touched by the review's coroutine only (or by a test calling [review], one line at a time).
    private var failuresInARow = 0
    private var pausedUntil = Long.MIN_VALUE
    private val calls = ArrayDeque<Long>()
    private var capWarnedAt = Long.MIN_VALUE

    /** Subscribes to the bus and reviews its lines in `services.scope` until the work is cancelled (`STOPPING`). */
    fun start(): Job {
        val subscription = services.bus.subscribeAsync(SUBSCRIBER, ShutdownSequence.DIRECTOR_OWNER, CAPACITY, ::reviewed)
        return services.scope.launch(CoroutineName(COROUTINE)) {
            try {
                for (envelope in subscription.events) {
                    try {
                        review(envelope)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.error("line review: line #${envelope.id} not reviewed", e)
                    }
                }
            } finally {
                subscription.close()
            }
        }
    }

    /**
     * Reviews the line [envelope] carries: asks the model, writes the call's `llm_calls` row when one went out, and, when
     * the labels are valid, their `line_labels` row, then gives them to the style watch. The labels; null when there
     * are none.
     */
    suspend fun review(envelope: Envelope): LineLabels? {
        val line = (envelope.event as? NarratorSpoke)?.takeIf { reviewed(it) } ?: return null
        if (services.phase != Phase.RUNNING) return null
        val settings = services.settings.get()
        if (!settings.config.style.lineReview) return skip(envelope, "style.lineReview is false")
        val client = settings.reflex ?: return skip(envelope, "no reflex model")
        val now = services.clock.millis()
        if (now < pausedUntil) return skip(envelope, "paused after $failuresInARow failures in a row")
        if (!withinRate(now)) return null

        val candidates = services.style.crutchCandidates().take(LineReviewLabels.CANDIDATES)
        val recent = services.style.recentLines(envelope.id, LineReviewLabels.RECENT_LINES)
        val request = LineReviewLabels.request(line.text, line.recipients?.map { it.name }, recent, candidates)
        val startedAt = services.clock.instant()
        val result = try {
            client.label(settings.prompts.get(LineReviewLabels.PROMPT), request, LineReviewLabels.SCHEMA)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("line review: unexpected failure of the reflex client on line #${envelope.id}", e)
            failed()
            return null
        }
        return when (result) {
            is ReflexResult.Labeled -> {
                val call = result.call
                val labels = LineReviewLabels.labels(
                    result.labels, envelope.id, services.clock.instant(), line.text, "${ClaudeReflexes.SOURCE_PREFIX}${call.model}",
                    call.latencyMs.toInt(), candidates,
                )
                if (labels == null) {
                    record(startedAt, client.effort, call.copy(error = INVALID))
                    log.warn("line review: line #${envelope.id}: unexpected labels; the line stays unlabeled")
                    failed()
                } else {
                    record(startedAt, client.effort, call)
                    failuresInARow = 0
                    labeled(labels)
                }
                labels
            }
            is ReflexResult.Failed -> {
                record(startedAt, client.effort, result.call)
                log.warn("line review: line #${envelope.id} failed after ${result.call.latencyMs} ms (${result.call.error}); the line stays unlabeled")
                failed()
                null
            }
            is ReflexResult.Skipped -> skip(envelope, result.reason)
        }
    }

    /** Writes the labels' `line_labels` row and gives them to the watch. */
    private fun labeled(labels: LineLabels) {
        log.info(
            "line #${labels.eventId} reviewed by ${labels.source.removePrefix(ClaudeReflexes.SOURCE_PREFIX)} in ${labels.latencyMs} ms: " +
                "${labels.register.id}, dark ${p(labels.dark)}, cruel ${p(labels.cruel)}, flat ${p(labels.flat)}, " +
                "out of character ${p(labels.outOfCharacter)}, repeats ${p(labels.repeats)}, crutches ${labels.crutches.joinToString().ifEmpty { "none" }}",
        )
        try {
            services.trace.lineLabels(labels.record())
        } catch (e: Exception) {
            log.error("line review: cannot record the labels of line #${labels.eventId}", e)
        }
        try {
            services.style.labeled(labels)
        } catch (e: Exception) {
            log.error("line review: the style watch refused the labels of line #${labels.eventId}", e)
        }
    }

    private fun record(startedAt: Instant, effort: String, call: ReflexCall) =
        recordReflexCall(services.trace, LlmCallRecord.Purpose.LINE_REVIEW, startedAt, effort, call)

    /** Counts a failure: [FAILURES_TO_PAUSE] in a row pause the review for [PAUSE_MS], and so does any failure after. */
    private fun failed() {
        failuresInARow++
        if (failuresInARow < FAILURES_TO_PAUSE) return
        pausedUntil = services.clock.millis() + PAUSE_MS
        log.warn(
            "line review paused for ${PAUSE_MS / 60_000} min after $failuresInARow failures in a row: " +
                "the chat triage keeps the reflexes' circuit",
        )
    }

    /** Whether one more call fits in the latest hour ([MAX_PER_HOUR]); counts it when it does. */
    private fun withinRate(now: Long): Boolean {
        while (calls.isNotEmpty() && now - calls.first() >= HOUR_MS) calls.removeFirst()
        if (calls.size < MAX_PER_HOUR) {
            calls.addLast(now)
            return true
        }
        if (capWarnedAt == Long.MIN_VALUE || now - capWarnedAt >= HOUR_MS) {
            capWarnedAt = now
            log.warn("line review: $MAX_PER_HOUR lines reviewed in the latest hour; the next ones stay unlabeled until it frees up")
        }
        return false
    }

    private fun skip(envelope: Envelope, why: String): LineLabels? {
        log.debug { "line review: line #${envelope.id} not reviewed ($why)" }
        return null
    }

    private fun p(value: Double) = ReflexThresholds.p(value)

    companion object {
        /** The bus subscriber's name. */
        const val SUBSCRIBER = "line-review"

        /** Lines waiting for their review, at most: a burst of the Director never waits on the review. */
        const val CAPACITY = 64

        /** A line shorter than this is a title or a line above the hotbar, as for the openers (M6 §3.4): not reviewed. */
        const val MIN_CHARS = 25

        /** Reviews in any hour, at most: three times the busiest evening of M4's acceptance (40 lines an hour). */
        const val MAX_PER_HOUR = 120

        /** Failures in a row that pause the review, below the reflexes' circuit ([io.github.clemsix6.narrator2.reflex.ReflexClient.FAILURES_TO_OPEN]). */
        const val FAILURES_TO_PAUSE = 3

        /** How long the review pauses. */
        val PAUSE_MS = TimeUnit.MINUTES.toMillis(5)

        /** `llm_calls.error` of an answer whose labels are missing or invalid. */
        const val INVALID = "unexpected labels: a label is missing or invalid"

        private const val COROUTINE = "narrator2-line-review"

        private val HOUR_MS = TimeUnit.HOURS.toMillis(1)

        /** The Director's lines: said in a turn, by the speech tools, of [MIN_CHARS] characters or more. The bus's filter. */
        fun reviewed(event: GameEvent): Boolean =
            event is NarratorSpoke && event.channel == NarratorChannel.NARRATOR && event.turnId != null && event.operator == null &&
                event.text.length >= MIN_CHARS
    }
}
