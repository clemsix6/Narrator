package io.github.clemsix6.narrator2.agent

import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.MessageParam
import io.github.clemsix6.narrator2.config.OperatorChannelMode

/**
 * An instruction for the model that does not come from the players: an operator's
 * (`/narrator whisper`) or the engine's (resumed after a pause, step limit, chapter closing…). The
 * model's prompt says these are authoritative; players cannot forge them, since their text is always
 * quoted and escaped.
 *
 * @property source `operator:<name>` or `engine`.
 * @property text English for the engine, as typed for an operator; may span several lines, kept intact.
 */
data class OperatorNote(val source: String, val text: String) {
    init {
        require(source == ENGINE || (source.startsWith(OPERATOR_PREFIX) && source.length > OPERATOR_PREFIX.length)) {
            "a note comes from `engine` or `operator:<name>`, not `$source`"
        }
    }

    /** The operator's name; null for the engine. */
    val operator: String? get() = if (source == ENGINE) null else source.removePrefix(OPERATOR_PREFIX)

    companion object {
        const val ENGINE = "engine"
        const val OPERATOR_PREFIX = "operator:"

        fun engine(text: String) = OperatorNote(ENGINE, text)

        fun operator(name: String, text: String) = OperatorNote(OPERATOR_PREFIX + name, text)
    }
}

/**
 * Renders a [Pending] into the messages of one request, according to [OperatorChannelMode]:
 *
 * - [OperatorChannelMode.SYSTEM_MESSAGE]: the user message holds the blocks; the notes form one
 *   `role: system` message right after it, the last of the request. It is the only place the API
 *   allows: a system message in the conversation must follow a user message and be the last one or be
 *   followed by an assistant turn. One note per line, `Operator <name>: …` or `Engine: …`.
 * - [OperatorChannelMode.USER_TAG]: the notes become one text block at the end of the user message,
 *   after the tool results and the other text: `<operator from="<name>">…</operator>`, `<engine>…</engine>`.
 *
 * A note spanning several lines is kept intact in both modes. With notes but no block, there is no
 * user message for a system message to follow: the notes are rendered as tags whatever the mode.
 */
object OperatorChannel {
    /** The messages of [pending] for a request, in order. Fails when there is nothing to send. */
    fun render(pending: Pending, mode: OperatorChannelMode): List<MessageParam> {
        val blocks = pending.blocks.filterNot { it.isText() && it.asText().text().isBlank() }
        val notes = pending.notes.filter { it.text.isNotBlank() }
        require(blocks.isNotEmpty() || notes.isNotEmpty()) { "nothing to send: the pending message is empty" }
        return when {
            notes.isEmpty() -> listOf(user(blocks))
            mode == OperatorChannelMode.SYSTEM_MESSAGE && blocks.isNotEmpty() -> listOf(user(blocks), system(notes))
            else -> listOf(user(blocks + ContentBlockParam.ofText(notes.joinToString("\n") { tag(it) })))
        }
    }

    /** `Operator <name>: <text>` or `Engine: <text>`. */
    fun line(note: OperatorNote): String = note.operator?.let { "Operator $it: ${note.text}" } ?: "Engine: ${note.text}"

    /** `<operator from="<name>"><text></operator>` or `<engine><text></engine>`. */
    fun tag(note: OperatorNote): String =
        note.operator?.let { "<operator from=\"${attribute(it)}\">${note.text}</operator>" } ?: "<engine>${note.text}</engine>"

    private fun user(blocks: List<ContentBlockParam>): MessageParam =
        MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(blocks).build()

    private fun system(notes: List<OperatorNote>): MessageParam =
        MessageParam.builder().role(MessageParam.Role.SYSTEM).content(notes.joinToString("\n") { line(it) }).build()

    private fun attribute(value: String) = value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
}
