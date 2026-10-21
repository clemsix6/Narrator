package io.github.clemsix6.narrator2.web.pages

import io.github.clemsix6.narrator2.command.TraceText
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.web.BadRequest
import io.github.clemsix6.narrator2.web.DashboardQueries
import io.github.clemsix6.narrator2.web.DecisionFilter
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
 * `/decisions` (M6 §8.4): the engine's decisions, the latest first, a page at a time, filtered by kind, decision,
 * subject (words of it, ignoring case), period and turn (`?turn=42`, from a turn's page): each with its reason, its
 * turn and conversation, and its detail folded.
 */
object DecisionsPage : WebPage {
    override val root = "decisions"

    override val label = "Decisions"

    /** The periods offered, and their length in milliseconds. */
    private val PERIODS = linkedMapOf("1h" to 3_600_000L, "24h" to 86_400_000L, "7d" to 7 * 86_400_000L, "30d" to 30 * 86_400_000L)

    private val FILTERS = listOf(
        Filter.Choice("kind", "Kind", DecisionRecord.Kind.entries.map { it.id }),
        Filter.Field("decision", "Decision", "denied"),
        Filter.Field("subject", "Subject", "a player, a tool…"),
        Filter.Choice("period", "Period", PERIODS.keys.toList()),
        Filter.Field("turn", "Turn", "number"),
    )

    override fun render(request: WebRequest, context: PageContext): Page? {
        if (request.path.isNotEmpty()) return null
        val period = request.param("period")?.let { PERIODS[it] ?: throw BadRequest("The period is one of ${PERIODS.keys.joinToString()}: $it.") }
        val turn = request.param("turn")?.let { it.trim().toLongOrNull() ?: throw BadRequest("The turn is a number: $it.") }
        val filter = DecisionFilter(
            kind = request.param("kind"),
            decision = request.param("decision")?.trim()?.lowercase()?.ifEmpty { null },
            subject = request.param("subject")?.trim()?.ifEmpty { null },
            since = period?.let { context.now - it },
            turn = turn,
        )
        val paging = Paging.of(request)
        val (decisions, more) = paging.rows(context.read { DashboardQueries(it).decisions(filter, paging) })
        return Page("Decisions") {
            filters(link(root), request, FILTERS)
            if (decisions.isEmpty()) p("No decision here.", "muted")
            else table(listOf("Time", "Kind", "Decision", "Subject", "Reason", "Where", "Detail")) {
                decisions.forEach { decision ->
                    row(if (decision.decision in TraceText.REFUSALS) "failure" else null) {
                        cell(context.time(decision.ts))
                        cell(decision.kind)
                        cell(decision.decision)
                        cell(decision.subject)
                        cell(decision.reason)
                        cell {
                            decision.turnId?.let { a(link("turns", it), "turn #$it") }
                            if (decision.turnId == null) decision.conversationId?.let { a(link("conversations", it), it) }
                            decision.eventId?.let { small(" event $it") }
                        }
                        cell { decision.detail?.let { details("detail") { pre(json(it)) } } }
                    }
                }
            }
            pager(request, paging, more)
        }
    }
}
