package io.github.clemsix6.narrator2.web.pages

import com.anthropic.core.jsonMapper
import io.github.clemsix6.narrator2.command.Look
import io.github.clemsix6.narrator2.command.TraceText
import io.github.clemsix6.narrator2.trace.DecisionLine
import io.github.clemsix6.narrator2.trace.ModelCall
import io.github.clemsix6.narrator2.trace.ToolUse
import io.github.clemsix6.narrator2.trace.TurnSummary
import io.github.clemsix6.narrator2.web.Cost
import io.github.clemsix6.narrator2.web.Html
import io.github.clemsix6.narrator2.web.ModelTokens
import io.github.clemsix6.narrator2.web.PageContext
import io.github.clemsix6.narrator2.web.costOf
import io.github.clemsix6.narrator2.web.link

/*
 * What the trace pages of the dashboard share (M6-11): the table of turns, the requests of a turn or a task with their
 * tool calls, a request stopped or handed over by a safety classifier shown as `/narrator trace` shows it
 * ([TraceText.safety]), and the cost of calls. Everything is written through [Html]: every text is escaped.
 */

/** The CSS class of a [Look]: red for a failure, yellow for a warning, gray for a thought. */
internal val Look.cls: String?
    get() = when (this) {
        Look.FAILURE -> "failure"
        Look.WARNING -> "warning"
        Look.THOUGHT -> "muted"
        Look.DETAIL, Look.SAID -> null
    }

/** One line per turn, newest first: its link, start, chapter, trigger, what woke it, tools, outcome, context, requests. */
internal fun Html.turnTable(turns: List<TurnSummary>, context: PageContext) =
    table(listOf("#Turn", "Started", "Chapter", "Trigger", "Woken by", "Tools", "Outcome", "#Took", "#Context", "#Requests")) {
        turns.forEach { turn ->
            val outcome = turn.outcome
            row(if (outcome != null && outcome !in TraceText.QUIET_OUTCOMES) "failure" else null) {
                cell("num") { a(link("turns", turn.turnId), "#${turn.turnId}") }
                cell(context.time(turn.startedAt))
                cell { turn.chapter?.let { a(link("conversations", turn.conversationId), "#$it") } }
                cell(turn.trigger)
                cell(turn.woke?.let { TraceText.abbreviate(it, WOKE_CHARS) })
                cell(TraceText.toolList(turn.tools))
                cell(outcome ?: "running")
                number(TraceText.duration((turn.endedAt?.toEpochMilli() ?: context.now) - turn.startedAt.toEpochMilli()))
                number(turn.contextTokens?.let { TraceText.tokens(it) })
                number(turn.llmCalls)
            }
        }
    }

/**
 * The requests of a turn or a task, in order: each one's line (time, latency, model, tokens, stop reason, cost), its
 * thinking summary, and the tool calls its reply asked for. [ended] tells that the last request ended its turn or task
 * refused (`TraceText.refusedTurn`, `refusedTask`): a fallback model that said nothing.
 */
internal fun Html.requests(calls: List<ModelCall>, ended: Boolean, context: PageContext) {
    if (calls.isEmpty()) {
        p("No request was sent.", "muted")
        return
    }
    calls.forEachIndexed { i, call ->
        val safety = TraceText.safety(call.stopReason, call.refusalCategory, call.error, call.model, ended && i == calls.lastIndex)
        h3("Request ${i + 1}")
        p(safety.look.cls) {
            val parts = buildList {
                add(context.time(call.startedAt))
                add(TraceText.seconds(call.latencyMs.toLong()))
                add(call.model)
                if (call.purpose != "turn" && call.purpose != "worker") add(call.purpose)
                call.inputTokens?.let { input ->
                    val read = input.toLong() + (call.cacheReadTokens ?: 0) + (call.cacheWriteTokens ?: 0)
                    add("${TraceText.tokens(read)} in (${TraceText.tokens((call.cacheReadTokens ?: 0).toLong())} cached, ${TraceText.tokens((call.cacheWriteTokens ?: 0).toLong())} written)")
                }
                call.outputTokens?.let { add("${TraceText.tokens(it.toLong())} out") }
                call.stopReason?.let { add(it) }
                safety.note?.let { add(it) }
                add(context.prices.costOf(listOf(tokensOf(call))).text)
                call.error?.let { add("error: $it") }
            }
            text(parts.joinToString(" · "))
        }
        val thinking = call.thinking?.trim().orEmpty()
        if (thinking.isEmpty()) p("No thinking summary.", "muted") else details("Thought", open = true) { pre(thinking, "muted") }
        if (call.tools.isNotEmpty()) tools(call.tools)
    }
}

/** Tool calls: the tool and the players it affected, its arguments (JSON), its status, duration and result. */
internal fun Html.tools(tools: List<ToolUse>) = table(listOf("Tool", "Arguments", "Status", "#Took", "Result")) {
    tools.forEach { use ->
        row(if (use.status == "ok") null else "failure") {
            cell {
                code(use.tool)
                text(" ${use.effect}")
                if (use.targets.isNotEmpty()) {
                    br()
                    small("→ ${use.targets.joinToString(", ")}")
                }
            }
            cell { use.args?.let { pre(json(it)) } }
            cell(use.status)
            number("${use.durationMs} ms")
            cell { use.result?.let { folded(it) } }
        }
    }
}

/** The engine's decisions of a turn: kind, decision, subject and reason; red when they stopped something. */
internal fun Html.decisionLines(decisions: List<DecisionLine>, context: PageContext) =
    table(listOf("Time", "Kind", "Decision", "Subject", "Reason")) {
        decisions.forEach { decision ->
            row(if (decision.decision in TraceText.REFUSALS) "failure" else null) {
                cell(context.time(decision.ts))
                cell(decision.kind)
                cell(decision.decision)
                cell(decision.subject)
                cell(decision.reason)
            }
        }
    }

/** [text] in a `<pre>`; a long one folded, its start as the summary. */
internal fun Html.folded(text: String) {
    if (text.length <= FOLD_CHARS) pre(text) else details(TraceText.abbreviate(text, SUMMARY_CHARS)) { pre(text) }
}

/** [text] as indented JSON, or as it is when it is not JSON. */
internal fun json(text: String): String = runCatching { MAPPER.readTree(text).toPrettyString() }.getOrDefault(text)

/** What a request used, for its cost. */
internal fun tokensOf(call: ModelCall) = ModelTokens(
    model = call.model, calls = 1, failed = if (call.error != null) 1 else 0, input = (call.inputTokens ?: 0).toLong(),
    cacheRead = (call.cacheReadTokens ?: 0).toLong(), cacheWrite = (call.cacheWriteTokens ?: 0).toLong(), output = (call.outputTokens ?: 0).toLong(),
)

/** What [calls] cost at the prices in force. */
internal fun PageContext.costOfCalls(calls: List<ModelCall>): Cost = prices.costOf(calls.map(::tokensOf))

/** `98%`: the share of [part] in [whole]; `–` for nothing. */
internal fun percent(part: Long, whole: Long): String = if (whole == 0L) "–" else "${part * 100 / whole}%"

private val MAPPER = jsonMapper()

/** Characters of a text shown whole; a longer one is folded. */
private const val FOLD_CHARS = 600
private const val SUMMARY_CHARS = 100
private const val WOKE_CHARS = 80
