package io.github.clemsix6.narrator2.agent

import com.anthropic.models.messages.ContentBlockParam

/**
 * What one request adds after a conversation's history: the content blocks of its user message (tool
 * results first, then text) and the notes of the engine and the operators, rendered by
 * [OperatorChannel]. It joins the history only with a usable reply: when the request is refused,
 * truncated, failed or cancelled, nothing of it is committed, and the caller may send it again, as is
 * or changed ([plus] adds new events or a note).
 *
 * Blank text blocks and blank notes are dropped when rendered: the API rejects empty text.
 */
data class Pending(val blocks: List<ContentBlockParam>, val notes: List<OperatorNote> = emptyList()) {
    /** True when there is nothing to send. */
    val isEmpty: Boolean get() = blocks.none { !it.isText() || it.asText().text().isNotBlank() } && notes.none { it.text.isNotBlank() }

    /** These blocks then [other]'s, these notes then [other]'s. */
    operator fun plus(other: Pending): Pending = Pending(blocks + other.blocks, notes + other.notes)

    /** Adds [note] after the notes. */
    operator fun plus(note: OperatorNote): Pending = copy(notes = notes + note)

    companion object {
        val EMPTY = Pending(emptyList())

        /** A user message made of [text], with [notes]. */
        fun text(text: String, notes: List<OperatorNote> = emptyList()) = Pending(listOf(ContentBlockParam.ofText(text)), notes)
    }
}
