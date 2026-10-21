package io.github.clemsix6.narrator2.web.pages

import io.github.clemsix6.narrator2.command.TraceText
import io.github.clemsix6.narrator2.trace.ToldEvent
import io.github.clemsix6.narrator2.trace.TraceQueries
import io.github.clemsix6.narrator2.trace.TurnDetail
import io.github.clemsix6.narrator2.trace.TurnRecord
import io.github.clemsix6.narrator2.web.BadRequest
import io.github.clemsix6.narrator2.web.DashboardQueries
import io.github.clemsix6.narrator2.web.Filter
import io.github.clemsix6.narrator2.web.Html
import io.github.clemsix6.narrator2.web.Page
import io.github.clemsix6.narrator2.web.PageContext
import io.github.clemsix6.narrator2.web.Paging
import io.github.clemsix6.narrator2.web.TurnFilter
import io.github.clemsix6.narrator2.web.WebPage
import io.github.clemsix6.narrator2.web.WebRequest
import io.github.clemsix6.narrator2.web.filters
import io.github.clemsix6.narrator2.web.link
import io.github.clemsix6.narrator2.web.pager

/**
 * `/turns`, `/turns/<id>` (M6 §8.4): the Director's turns, a page at a time, filtered by trigger, outcome and chapter;
 * one turn in full: what woke it, each request (thinking, tokens, latency, cost), each tool call (arguments, result),
 * the lines it said, the engine's decisions, and the link to its chapter's transcript. What `/narrator trace turn`
 * shows, with nothing cut.
 */
object TurnsPage : WebPage {
    override val root = "turns"

    override val label = "Turns"

    /** The kinds of the Narrator's own chat lines: their player is `NARRATOR`, no player of the world. */
    private val NARRATOR_LINES = setOf("chat:narrator", "chat:operator")

    private val FILTERS = listOf(
        Filter.Choice("trigger", "Trigger", TurnRecord.Trigger.entries.map { it.id }),
        Filter.Choice("outcome", "Outcome", TurnRecord.Outcome.entries.map { it.id } + TurnFilter.RUNNING),
        Filter.Field("chapter", "Chapter", "number"),
    )

    override fun render(request: WebRequest, context: PageContext): Page? = when (request.path.size) {
        0 -> list(request, context)
        1 -> request.path.single().toLongOrNull()?.let { turn(it, context) }
        else -> null
    }

    private fun list(request: WebRequest, context: PageContext): Page {
        val chapter = request.param("chapter")?.let { it.trim().toIntOrNull() ?: throw BadRequest("The chapter is a number: $it.") }
        val filter = TurnFilter(request.param("trigger"), request.param("outcome"), chapter)
        val paging = Paging.of(request)
        val (turns, more) = paging.rows(context.read { DashboardQueries(it).turns(filter, paging) })
        return Page("Turns") {
            filters(link(root), request, FILTERS)
            when {
                turns.isNotEmpty() -> turnTable(turns, context)
                filter == TurnFilter() && paging.page == 1 -> p("The Director has not woken up yet.", "muted")
                else -> p("No turn here.", "muted")
            }
            pager(request, paging, more)
        }
    }

    private fun turn(id: Long, context: PageContext): Page? {
        val detail = context.read { TraceQueries(it).turn(id) } ?: return null
        val turn = detail.summary
        return Page("Turn #$id") {
            dl {
                item("Started", context.time(turn.startedAt))
                item("Ended") {
                    val end = turn.endedAt
                    if (end == null) text("running for ${TraceText.duration(context.now - turn.startedAt.toEpochMilli())}")
                    else text("${context.time(end)}, after ${TraceText.duration(end.toEpochMilli() - turn.startedAt.toEpochMilli())}")
                }
                item("Outcome") {
                    val outcome = turn.outcome
                    span(outcome ?: "running", if (outcome != null && outcome !in TraceText.QUIET_OUTCOMES) "failure" else null)
                }
                item("Chapter") {
                    a(link("conversations", turn.conversationId), turn.chapter?.let { "Chapter $it" } ?: turn.conversationId)
                    text(" (transcript)")
                }
                item("Trigger", turn.trigger)
                item("Woken by", turn.woke ?: "no wake decision")
                item("Context", turn.contextTokens?.let { "${TraceText.tokens(it)} tokens" } ?: "unknown")
                item("Cost") { text("${context.costOfCalls(detail.calls).text} for ${detail.calls.size} ${TraceText.plural(detail.calls.size, "request")}") }
            }
            told("Told at wake-up", detail.woken, context)
            told("Told between tool calls", detail.injected, context)
            h2("Requests")
            requests(detail.calls, TraceText.refusedTurn(turn.outcome), context)
            if (detail.unmatchedTools.isNotEmpty()) {
                h2("Tool calls without their request")
                p("Their request's row is missing from the Journal.", "muted")
                tools(detail.unmatchedTools)
            }
            said(detail)
            h2("Engine decisions")
            if (detail.decisions.isEmpty()) p("None during this turn.", "muted") else decisionLines(detail.decisions, context)
            p("muted") { a(link("decisions", query = mapOf("turn" to id)), "Every decision of this turn, the wake-up included") }
        }
    }

    /** The events and chat lines told to the Director, with how the salience classified each. */
    private fun Html.told(title: String, events: List<ToldEvent>, context: PageContext) {
        if (events.isEmpty()) return
        h2(title)
        table(listOf("Time", "Event", "Player", "What", "Where", "Salience")) {
            events.forEach { event ->
                row {
                    cell(context.time(event.ts))
                    cell(event.kind)
                    cell {
                        val player = event.player
                        if (player == null || event.kind in NARRATOR_LINES) text(player) else a(link("players", player), player)
                    }
                    cell(event.what)
                    cell(event.where)
                    cell(event.salience)
                }
            }
        }
    }

    /** The lines the turn said: to whom, and through which operator. */
    private fun Html.said(detail: TurnDetail) {
        if (detail.said.isEmpty()) return
        h2("Said")
        table(listOf("Channel", "To", "Line")) {
            detail.said.forEach { line ->
                row {
                    cell(line.channel + (line.operator?.let { " (operator $it)" } ?: ""))
                    cell(line.recipients?.joinToString(", ") ?: "everyone")
                    cell { strong(line.text) }
                }
            }
        }
    }
}
