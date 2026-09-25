package io.github.clemsix6.narrator2.llm

import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ToolUnion
import io.github.clemsix6.narrator2.trace.Transcripts
import java.util.UUID

/**
 * An append-only conversation with Claude.
 *
 * Earlier turns are never edited, and the system prompt and tool list are frozen for the
 * conversation's lifetime: Opus 5.5 binds its thinking to the exact conversation prefix, and the
 * prompt cache depends on the same prefix. Anything that changes over time is appended instead.
 *
 * A conversation has a single writer: while [ClaudeClient.send] is in flight, another `send` or an
 * [append] fails with an [IllegalStateException] instead of racing the reply. Each message is
 * transcribed as soon as it is appended.
 *
 * @property role whose model, effort and timeouts the calls use.
 * @property label human-readable name used in transcripts and logs.
 * @property transcripts where this conversation is transcribed; null to keep no transcript.
 */
class Conversation(
    val role: Role,
    val label: String,
    val system: List<TextBlockParam>,
    val tools: List<ToolUnion> = emptyList(),
    val transcripts: Transcripts? = null,
    val id: String = "${role.id}-${UUID.randomUUID().toString().substring(0, 8)}",
) : AutoCloseable {
    private val lock = Any()
    private val history = mutableListOf<MessageParam>() // guarded by lock
    private var inFlight = false // guarded by lock

    val messages: List<MessageParam>
        get() = synchronized(lock) { history.toList() }

    val size: Int
        get() = synchronized(lock) { history.size }

    /** Context size (input tokens, cached or not) of the latest request. */
    @Volatile
    var contextTokens: Long = 0
        internal set

    fun append(message: MessageParam) = synchronized(lock) {
        check(!inFlight) { "$id: cannot append while a call is in flight (a conversation has a single writer)" }
        history += message
        transcripts?.message(this, message)
    }

    fun appendUser(text: String) = append(MessageParam.builder().role(MessageParam.Role.USER).content(text).build())

    /** Marks a call as in flight and returns the history to send. */
    internal fun beginCall(): List<MessageParam> = synchronized(lock) {
        check(!inFlight) { "$id: a call is already in flight (a conversation has a single writer)" }
        inFlight = true
        history.toList()
    }

    /** Ends the call, appending [reply] when there is one; the client transcribes it with its measurements. */
    internal fun endCall(reply: MessageParam?) = synchronized(lock) {
        reply?.let { history += it }
        inFlight = false
    }

    /** Releases the transcript's resources; the conversation must not be used afterwards. */
    override fun close() {
        transcripts?.close(this)
    }
}
