package io.github.clemsix6.narrator2.web.pages

import io.github.clemsix6.narrator2.command.TraceText
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.trace.ConversationRecord
import io.github.clemsix6.narrator2.web.DashboardQueries
import io.github.clemsix6.narrator2.web.Filter
import io.github.clemsix6.narrator2.web.Page
import io.github.clemsix6.narrator2.web.PageContext
import io.github.clemsix6.narrator2.web.Paging
import io.github.clemsix6.narrator2.web.WebPage
import io.github.clemsix6.narrator2.web.WebRequest
import io.github.clemsix6.narrator2.web.filters
import io.github.clemsix6.narrator2.web.link
import io.github.clemsix6.narrator2.web.pager

/**
 * `/conversations` (M6 §8.4): the conversations with Claude, the latest first, a page at a time, filtered by role: the
 * Director's chapters, the assistants' tasks, the memory's jobs (the Chronicler's batches and naps, the Dreamer's and the
 * storyteller's dreams). `/conversations/<id>` is one of them with its transcript ([TranscriptPage]).
 */
object ConversationsPage : WebPage {
    override val root = "conversations"

    override val label = "Conversations"

    private val FILTERS = listOf(Filter.Choice("role", "Role", Role.entries.map { it.id }))

    /** How a conversation ends when something went wrong. */
    internal val FAILED_ENDS = setOf(
        ConversationRecord.EndReason.REFUSAL, ConversationRecord.EndReason.BAD_REQUEST, ConversationRecord.EndReason.RUNAWAY_TURN,
        ConversationRecord.EndReason.CONTEXT_EXCEEDED, ConversationRecord.EndReason.FAILED, ConversationRecord.EndReason.TIMEOUT,
        ConversationRecord.EndReason.INTERRUPTED,
    ).map { it.id }.toSet()

    override fun render(request: WebRequest, context: PageContext): Page? = when (request.path.size) {
        0 -> list(request, context)
        1 -> TranscriptPage.render(request.path.single(), request, context)
        else -> null
    }

    private fun list(request: WebRequest, context: PageContext): Page {
        val paging = Paging.of(request)
        val (conversations, more) = paging.rows(context.read { DashboardQueries(it).conversations(request.param("role"), paging) })
        return Page("Conversations") {
            filters(link(root), request, FILTERS)
            if (conversations.isEmpty()) p("No conversation here.", "muted")
            else table(listOf("Conversation", "Role", "Of", "Started", "#Took", "Ended", "#Requests", "#Turns")) {
                conversations.forEach { conversation ->
                    row(if (conversation.endReason in FAILED_ENDS) "failure" else null) {
                        cell { a(link(root, conversation.conversationId), conversation.label) }
                        cell(conversation.role)
                        cell {
                            conversation.chapter?.let { a(link("turns", query = mapOf("chapter" to it)), "chapter $it") }
                            conversation.taskId?.let { a(link("tasks", it), "T-$it") }
                        }
                        cell(context.time(conversation.startedAt))
                        number(TraceText.duration((conversation.endedAt?.toEpochMilli() ?: context.now) - conversation.startedAt.toEpochMilli()))
                        cell(conversation.endReason ?: if (conversation.endedAt == null) "open" else "ended")
                        number(conversation.calls)
                        number(conversation.turns.takeIf { it > 0 })
                    }
                }
            }
            pager(request, paging, more)
        }
    }
}
