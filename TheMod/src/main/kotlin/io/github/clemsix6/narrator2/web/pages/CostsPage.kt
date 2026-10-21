package io.github.clemsix6.narrator2.web.pages

import io.github.clemsix6.narrator2.command.TraceText
import io.github.clemsix6.narrator2.trace.Prices
import io.github.clemsix6.narrator2.web.Bar
import io.github.clemsix6.narrator2.web.BadRequest
import io.github.clemsix6.narrator2.web.Cost
import io.github.clemsix6.narrator2.web.DashboardQueries
import io.github.clemsix6.narrator2.web.Filter
import io.github.clemsix6.narrator2.web.HourCalls
import io.github.clemsix6.narrator2.web.Html
import io.github.clemsix6.narrator2.web.KindCalls
import io.github.clemsix6.narrator2.web.ModelTokens
import io.github.clemsix6.narrator2.web.Page
import io.github.clemsix6.narrator2.web.PageContext
import io.github.clemsix6.narrator2.web.WebPage
import io.github.clemsix6.narrator2.web.WebRequest
import io.github.clemsix6.narrator2.web.bars
import io.github.clemsix6.narrator2.web.costOf
import io.github.clemsix6.narrator2.web.curve
import io.github.clemsix6.narrator2.web.filters
import io.github.clemsix6.narrator2.web.link
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * `/costs` (M6 §8.4, §8.7): what the model calls of a period cost (`?period=24h|7d|30d|90d`, a week by default): the total,
 * by role (SVG bars), by role, purpose and model (calls, tokens, share of the cache, dollars, latencies p50 and p95), the
 * last 24 hours hour by hour and the period day by day. In dollars at the configuration's prices, by the model that
 * answered (`llm_calls.model`); a model without a price counts `$ ?`. Behind a subscription, an order of magnitude, and
 * the page says so. Two sums of the database, in one read.
 */
object CostsPage : WebPage {
    override val root = "costs"

    override val label = "Costs"

    /** The periods offered, in hours; the first of [DEFAULT_PERIOD] when none is asked. */
    private val PERIODS = linkedMapOf("24h" to 24, "7d" to 7 * 24, "30d" to 30 * 24, "90d" to 90 * 24)
    private const val DEFAULT_PERIOD = "7d"

    /** The hours of the hourly part. */
    private const val HOURS = 24

    private const val HOUR_MS = 3_600_000L

    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE MM-dd", Locale.ROOT)
    private val HOUR: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:00", Locale.ROOT)

    override fun render(request: WebRequest, context: PageContext): Page? {
        if (request.path.isNotEmpty()) return null
        val name = request.param("period") ?: DEFAULT_PERIOD
        val hours = PERIODS[name] ?: throw BadRequest("The period is one of ${PERIODS.keys.joinToString()}: $name.")
        val since = context.now - hours * HOUR_MS
        val (kinds, byHour) = context.read { connection ->
            val queries = DashboardQueries(connection)
            queries.callsByKind(since) to queries.callsByHour(since)
        }
        return Page("Costs") {
            filters(link(root), request, listOf(Filter.Choice("period", "Period", PERIODS.keys.toList())))
            p("muted") {
                text("Since ${context.time(Instant.ofEpochMilli(since))} (the last $name). At the API's prices of the configuration (prices), by the model that answered: ")
                text("behind a subscription, an order of magnitude. A model without a price counts ${Prices.dollars(null)}.")
            }
            if (kinds.isEmpty()) {
                p("No model call in this period.", "muted")
                return@Page
            }
            total(kinds, context)
            byRole(kinds, context)
            byKind(kinds, context)
            lastHours(byHour, context)
            byDay(byHour, context, hours)
        }
    }

    private fun Html.total(kinds: List<KindCalls>, context: PageContext) {
        val tokens = ModelTokens.sum("all", kinds.map { it.tokens })
        dl {
            item("Cost", context.prices.costOf(kinds.map { it.tokens }).text)
            item("Calls", "${tokens.calls}" + if (tokens.failed > 0) " (${tokens.failed} failed)" else "")
            item("Read", "${TraceText.tokens(tokens.read)} tokens, ${percent(tokens.cacheRead, tokens.read)} from the cache")
            item("Written", "${TraceText.tokens(tokens.output)} tokens")
        }
    }

    private fun Html.byRole(kinds: List<KindCalls>, context: PageContext) {
        h2("By role")
        val roles = kinds.groupBy { it.role }.map { (role, calls) -> Line(role, calls.map { it.tokens }, context) }.sortedWith(DEAREST)
        bars("Cost by role", roles.map { Bar(it.name, it.cost.dollars, "${it.cost.text} · ${it.tokens.calls} ${TraceText.plural(it.tokens.calls.toInt(), "call")}") })
        lines("Role", roles)
    }

    private fun Html.byKind(kinds: List<KindCalls>, context: PageContext) {
        h2("By role, purpose and model")
        val rows = kinds.map { it to context.prices.costOf(listOf(it.tokens)) }
            .sortedWith(compareByDescending<Pair<KindCalls, Cost>> { it.second.dollars }.thenByDescending { it.first.tokens.calls })
        table(listOf("Role", "Purpose", "Model", "#Calls", "#Failed", "#Input", "#Cache read", "#Cache write", "#Cached", "#Output", "#Cost", "#p50", "#p95")) {
            rows.forEach { (kind, cost) ->
                val tokens = kind.tokens
                row(if (cost.unpriced > 0) "warning" else null) {
                    cell(kind.role)
                    cell(kind.purpose)
                    cell(tokens.model)
                    number(tokens.calls)
                    number(tokens.failed.takeIf { it > 0 })
                    number(TraceText.tokens(tokens.input))
                    number(TraceText.tokens(tokens.cacheRead))
                    number(TraceText.tokens(tokens.cacheWrite))
                    number(percent(tokens.cacheRead, tokens.read))
                    number(TraceText.tokens(tokens.output))
                    number(cost.text)
                    number(TraceText.seconds(kind.p50Ms))
                    number(TraceText.seconds(kind.p95Ms))
                }
            }
        }
    }

    /** The last [HOURS] hours, the latest last: a curve of their cost, and the hours in a folded table. */
    private fun Html.lastHours(byHour: List<HourCalls>, context: PageContext) {
        h2("The last $HOURS hours")
        val last = context.now / HOUR_MS
        val hours = (last - HOURS + 1..last).map { hour -> hour to Line(label(hour, context), byHour.filter { it.hour == hour }.map { it.tokens }, context) }
        curve("Cost by hour, the last $HOURS hours", hours.map { it.second.cost.dollars }, hours.first().second.name, hours.last().second.name, Prices.dollars(hours.maxOf { it.second.cost.dollars }))
        details("Hour by hour") { lines("Hour", hours.map { it.second }.filter { it.tokens.calls > 0 }.reversed()) }
    }

    /** The days of the period, the latest first: a curve of their cost when there are several, and a line for each day with calls. */
    private fun Html.byDay(byHour: List<HourCalls>, context: PageContext, periodHours: Int) {
        if (periodHours <= HOURS) return
        h2("Day by day")
        val dayOf = { hour: Long -> Instant.ofEpochMilli(hour * HOUR_MS).atZone(context.zone).toLocalDate() }
        val today = Instant.ofEpochMilli(context.now).atZone(context.zone).toLocalDate()
        val first = Instant.ofEpochMilli(context.now - periodHours * HOUR_MS).atZone(context.zone).toLocalDate()
        val calls = byHour.groupBy { dayOf(it.hour) }
        val days = generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }
            .map { day -> Line(DAY.format(day), calls[day].orEmpty().map { it.tokens }, context) }.toList()
        curve("Cost by day", days.map { it.cost.dollars }, days.first().name, days.last().name, Prices.dollars(days.maxOf { it.cost.dollars }))
        lines("Day", days.filter { it.tokens.calls > 0 }.reversed())
    }

    /** A table of [lines], first column [what]: calls, tokens read and the cache's share, written, cost. */
    private fun Html.lines(what: String, lines: List<Line>) = table(listOf(what, "#Calls", "#Failed", "#Read", "#Cached", "#Output", "#Cost")) {
        lines.forEach { line ->
            row(if (line.cost.unpriced > 0) "warning" else null) {
                cell(line.name)
                number(line.tokens.calls)
                number(line.tokens.failed.takeIf { it > 0 })
                number(TraceText.tokens(line.tokens.read))
                number(percent(line.tokens.cacheRead, line.tokens.read))
                number(TraceText.tokens(line.tokens.output))
                number(line.cost.text)
            }
        }
    }

    private fun label(hour: Long, context: PageContext): String = HOUR.format(Instant.ofEpochMilli(hour * HOUR_MS).atZone(context.zone))

    /** One line of a table: its [name], the [tokens] of its calls, all models summed, and their [cost]. */
    private class Line(val name: String, models: List<ModelTokens>, context: PageContext) {
        val tokens = ModelTokens.sum(name, models)
        val cost = context.prices.costOf(models)
    }

    /** The dearest first, then the busiest. */
    private val DEAREST = compareByDescending<Line> { it.cost.dollars }.thenByDescending { it.tokens.calls }
}
