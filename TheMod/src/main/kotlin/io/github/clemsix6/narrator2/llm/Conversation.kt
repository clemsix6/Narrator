package io.github.clemsix6.narrator2.llm

import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ToolUnion
import io.github.clemsix6.narrator2.config.RoleConfig
import io.github.clemsix6.narrator2.tool.ToolSet
import io.github.clemsix6.narrator2.trace.Transcripts
import java.util.UUID

/**
 * An append-only conversation with Claude.
 *
 * Earlier turns are never edited, and the system prompt and tool list are frozen for the
 * conversation's lifetime: Opus 5.5 binds its thinking to the exact conversation prefix, and the
 * prompt cache depends on the same prefix. Anything that changes over time is appended instead.
 *
 * [messages] is the committed history only. The engine never appends by itself: it gives
 * [ClaudeClient.send] the messages of the next request (the *pending* messages), and they join the
 * history together with the reply, only when the reply is usable. A refusal, a truncated reply, an
 * error or a cancellation commits nothing, so the history never holds an empty assistant turn nor a
 * user message left without an answer, and the next request may carry different messages.
 *
 * A conversation has a single writer: while [ClaudeClient.send] is in flight, another `send` or an
 * [append] fails with an [IllegalStateException] instead of racing the reply. Committed messages are
 * transcribed as they are sent or received.
 *
 * @property role whose model, effort and timeouts the calls use; the model and the effort are frozen by
 *   the first committed exchange ([frozen]).
 * @property label human-readable name used in transcripts and logs.
 * @property toolSet the tools the engine runs for this conversation (`agent/AgentLoop`); null when
 *   the conversation only declares [tools] (a ping, a live check).
 * @property tools the tool definitions sent with every request.
 * @property transcripts where this conversation is transcribed; null to keep no transcript.
 * @property id the conversation in the traces, the transcripts and the `X-Narrator-Conversation` header;
 *   the gateway's affinity (`X-Session-ID`) is the role's, shared by its conversations
 *   (`ClaudeClient.sessionOf`).
 */
class Conversation private constructor(
    val role: Role,
    val label: String,
    val system: List<TextBlockParam>,
    val toolSet: ToolSet?,
    val tools: List<ToolUnion>,
    val transcripts: Transcripts?,
    val id: String,
) : AutoCloseable {
    /** A conversation of the engine, whose [tools] `AgentLoop` runs. */
    constructor(
        role: Role,
        label: String,
        system: List<TextBlockParam>,
        tools: ToolSet,
        transcripts: Transcripts? = null,
        id: String = newId(role),
    ) : this(role, label, system, tools, tools.definitions, transcripts, id)

    /** A conversation that only declares [tools] (a ping, a live check): `AgentLoop` cannot run it. */
    constructor(
        role: Role,
        label: String,
        system: List<TextBlockParam>,
        tools: List<ToolUnion> = emptyList(),
        transcripts: Transcripts? = null,
        id: String = newId(role),
    ) : this(role, label, system, null, tools, transcripts, id)

    private val lock = Any()
    private val history = mutableListOf<MessageParam>() // guarded by lock
    private var inFlight = false // guarded by lock
    private var pinned: RoleConfig? = null // guarded by lock: the role settings of the last call made while nothing was committed

    /**
     * [current] (the role's settings in force) with this conversation's own model and effort: those of
     * the call that got its first committed exchange, frozen for the rest of its life. Opus binds its
     * thinking to the conversation, and the prompt cache to the model: a `/narrator reload` that changes
     * them applies to the next conversation (M2 review, finding 25). The other settings (tokens,
     * timeouts) follow the reloads.
     */
    internal fun frozen(current: RoleConfig): RoleConfig = synchronized(lock) {
        val pin = pinned?.takeIf { history.isNotEmpty() } ?: current.also { pinned = it }
        current.copy(model = pin.model, effort = pin.effort)
    }

    /** The committed history, a snapshot. */
    val messages: List<MessageParam>
        get() = synchronized(lock) { history.toList() }

    val size: Int
        get() = synchronized(lock) { history.size }

    /** Context size (input tokens, cached or not) of the latest request. */
    @Volatile
    var contextTokens: Long = 0
        internal set

    /**
     * Appends [message] to the history at once. For setting up a conversation outside the engine (a
     * ping, a test): the engine sends pending messages instead, committed with the reply.
     */
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

    /**
     * Ends the call, committing [committed] (the pending messages, then the reply) when the reply was
     * usable, nothing otherwise. The client transcribes them itself, with its measurements.
     */
    internal fun endCall(committed: List<MessageParam>?) = synchronized(lock) {
        committed?.let { history += it }
        inFlight = false
    }

    /** Releases the transcript's resources; the conversation must not be used afterwards. */
    override fun close() {
        transcripts?.close(this)
    }

    private companion object {
        fun newId(role: Role) = "${role.id}-${UUID.randomUUID().toString().substring(0, 8)}"
    }
}
