package io.github.clemsix6.narrator2.trace

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.journal.IdKind
import kotlinx.serialization.json.JsonObject
import java.time.Clock

/**
 * Records the engine's decisions: one `engine_decisions` row and one log line
 * `[<tag>] <decision> <subject>: <reason>` per decision, so the log and the Journal always agree.
 * Stateless and cheap: build one wherever needed.
 */
class Decisions(private val trace: TraceSink, private val clock: Clock) {

    /**
     * Records a decision and returns its id.
     *
     * @param log the subsystem that decided; its tag starts the log line.
     * @param level level of the log line; the row is written whatever the level.
     */
    fun record(
        log: Log,
        kind: DecisionRecord.Kind,
        decision: String,
        subject: String? = null,
        reason: String? = null,
        eventId: Long? = null,
        conversationId: String? = null,
        turnId: Long? = null,
        detail: JsonObject? = null,
        level: Log.Level = Log.Level.INFO,
    ): Long {
        val id = trace.nextId(IdKind.DECISION)
        trace.decision(DecisionRecord(id, clock.instant(), kind, decision, subject, reason, eventId, conversationId, turnId, detail))
        val line = line(decision, subject, reason)
        when (level) {
            Log.Level.DEBUG -> log.debug { line }
            Log.Level.INFO -> log.info(line)
            Log.Level.WARN -> log.warn(line)
            Log.Level.ERROR -> log.error(line)
            Log.Level.OFF -> Unit
        }
        return id
    }

    companion object {
        /** `<decision> <subject>: <reason>`, leaving out what is null. */
        fun line(decision: String, subject: String?, reason: String?): String = buildString {
            append(decision)
            subject?.let { append(' ').append(it) }
            reason?.let { append(": ").append(it) }
        }
    }
}
