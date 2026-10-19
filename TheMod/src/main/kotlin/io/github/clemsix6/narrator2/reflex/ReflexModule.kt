package io.github.clemsix6.narrator2.reflex

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.event.ChatMessage
import io.github.clemsix6.narrator2.event.Envelope
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.trace.ChatSignalsRecord
import java.time.Instant

/**
 * Creates the reflexes of a server that starts: the reflex model when the settings have a client (read at
 * each call, `/narrator reload` may replace it), the heuristic otherwise and whenever the model fails.
 */
fun createReflexes(services: NarratorServices): Reflexes = ClaudeReflexes(services)

/**
 * A chat message's triage, and what the engine makes of it with [ReflexThresholds]. Given by
 * [triageChatMessage].
 *
 * @property addressed the message addresses the Narrator: its probability reaches
 *   [ReflexThresholds.ADDRESSED], or it falls in a classifier's gray zone and the heuristic says so.
 * @property reason why, in one English line for the salience decision, e.g.
 *   `reflex:claude-haiku-5-5 0.48 in the gray zone, heuristic 0.90 >= 0.60`.
 */
data class ChatTriage(val verdict: ChatVerdict, val addressed: Boolean, val reason: String) {
    /** A possible manipulation: an annotation for the Director, never a reason for an automatic action. */
    val manipulation: Boolean get() = verdict.manipulation >= ReflexThresholds.MANIPULATION

    /** The tone to show the Director; null when the classifier is not sure enough or the message targets no one. */
    val tone: Tone?
        get() = verdict.tone.takeIf { verdict.toneConfidence >= ReflexThresholds.TONE_SHOWN && it != Tone.NEUTRAL_NO_TARGET }

    /** The verdict comes from the heuristic (no reflex model, or it failed): its numbers are rules of thumb, not probabilities. */
    val byHeuristic: Boolean get() = verdict.source == HeuristicReflexes.SOURCE
}

/**
 * Triages a public message or a prayer and writes its `chat_signals` row: what the salience calls for
 * each of them, in a child coroutine (it takes up to `reflex.timeoutMs`). Never throws, except the caller's
 * cancellation and a wrong event (not a chat message, or a private message: those never leave the server).
 *
 * @param envelope a [ChatMessage] on the public or prayer channel.
 * @param recent the public chat lines just before it, oldest first; only the last
 *   [ReflexLabels.RECENT_LINES] are used.
 * @param narratorLast the Narrator's last line seen by this player, if it is less than
 *   [ReflexLabels.NARRATOR_LAST_MAX_AGE_SECONDS] old: a message right after it may answer it.
 */
suspend fun triageChatMessage(
    services: NarratorServices,
    envelope: Envelope,
    recent: List<ChatLine>,
    narratorLast: String?,
): ChatTriage {
    val message = requireNotNull(envelope.event as? ChatMessage) { "event ${envelope.id} is not a chat message" }
    require(message.channel != ChatChannel.WHISPER) { "event ${envelope.id} is a private message: those are never triaged" }
    val input = ChatTriageInput(
        ChatLine(message.player.name, message.text),
        recent.takeLast(ReflexLabels.RECENT_LINES),
        narratorLast,
        prayer = message.channel == ChatChannel.PRAY,
    )
    val verdict = services.reflexes.triageChat(input)
    val triage = ReflexThresholds.triage(verdict) {
        HeuristicReflexes.forLanguage(services.settings.get().config.language).judge(input).toNarrator
    }
    try {
        services.trace.chatSignals(ChatSignalsRecord.of(envelope.id, Instant.ofEpochMilli(envelope.ts), message.player.uuid, verdict))
    } catch (e: Exception) {
        Log.reflex.error("cannot record the chat signals of event ${envelope.id}", e)
    }
    return triage
}
