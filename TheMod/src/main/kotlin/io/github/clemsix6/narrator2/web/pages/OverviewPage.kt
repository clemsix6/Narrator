package io.github.clemsix6.narrator2.web.pages

import io.github.clemsix6.narrator2.command.ForgeCommands
import io.github.clemsix6.narrator2.command.MechanicsCommands
import io.github.clemsix6.narrator2.command.MemoryCommands
import io.github.clemsix6.narrator2.command.TraceText
import io.github.clemsix6.narrator2.director.DirectorStatus
import io.github.clemsix6.narrator2.guard.BlockRestorer
import io.github.clemsix6.narrator2.journal.JournalHealth
import io.github.clemsix6.narrator2.journal.JournalTimeoutException
import io.github.clemsix6.narrator2.journal.JournalUnavailableException
import io.github.clemsix6.narrator2.trace.Prices
import io.github.clemsix6.narrator2.trace.TraceQueries
import io.github.clemsix6.narrator2.trace.TurnSummary
import io.github.clemsix6.narrator2.web.Bar
import io.github.clemsix6.narrator2.web.Html
import io.github.clemsix6.narrator2.web.Page
import io.github.clemsix6.narrator2.web.PageContext
import io.github.clemsix6.narrator2.web.WebPage
import io.github.clemsix6.narrator2.web.WebRequest
import io.github.clemsix6.narrator2.web.bars
import io.github.clemsix6.narrator2.web.link
import io.github.clemsix6.narrator2.web.rows
import java.time.Instant
import java.util.Locale

/**
 * `/`, the overview (M6 §8.4): what runs now, from the live snapshots (phase, Director, players online, tasks, Journal,
 * memory, mechanics, Forge), then, from the Journal in one read, the cost of the last 24 hours by role (SVG bars) and the
 * latest [TURNS] turns of the Director. `?refresh=15` reloads it every 15 seconds. Without the Journal, the live part
 * still shows, and the rest says why it does not.
 */
object OverviewPage : WebPage {
    override val root = ""

    override val label = "Overview"

    /** Turns of the Director listed. */
    const val TURNS = 15

    /** The period of the costs. */
    const val COST_HOURS = 24

    /** The refresh the page offers, in seconds; `?refresh=` accepts [MIN_REFRESH] to [MAX_REFRESH]. */
    const val REFRESH = 15
    private const val MIN_REFRESH = 5
    private const val MAX_REFRESH = 3_600

    /**
     * The model calls since a time (epoch milliseconds, the parameter), by role and by the model that answered: calls,
     * failed, input, output, cache read and cache write tokens.
     */
    internal const val COST_BY_ROLE = """
        SELECT role, model, count(*), count(error), coalesce(sum(input_tokens), 0), coalesce(sum(output_tokens), 0),
               coalesce(sum(cache_read_tokens), 0), coalesce(sum(cache_write_tokens), 0)
        FROM llm_calls WHERE started_at >= to_timestamp(CAST(? AS DOUBLE) / 1000)
        GROUP BY role, model
    """

    /** What one role cost: its [dollars] (null when no model it used has a price), its [calls], and its calls of a model without a price. */
    internal data class RoleCost(val role: String, val dollars: Double?, val calls: Long, val failed: Long, val unpriced: Long)

    override fun render(request: WebRequest, context: PageContext): Page? {
        if (request.path.isNotEmpty()) return null
        val refresh = request.param("refresh")?.toIntOrNull()?.coerceIn(MIN_REFRESH, MAX_REFRESH)
        val journal = try {
            context.read { connection ->
                val since = context.now - COST_HOURS * HOUR_MS
                val costs = connection.rows(COST_BY_ROLE, since) {
                    CostRow(getString(1), getString(2), getLong(3), getLong(4), getLong(5), getLong(6), getLong(7), getLong(8))
                }
                Readings(costByRole(costs, context.prices), TraceQueries(connection).lastTurns(TURNS))
            }
        } catch (e: JournalUnavailableException) {
            Unread(e.message.orEmpty())
        } catch (e: JournalTimeoutException) {
            Unread("${e.message}: the Journal is busy, reload the page in a moment")
        }
        return Page("Overview", refresh = refresh) {
            p("muted") {
                text("At ${context.time(Instant.ofEpochMilli(context.now))} · ")
                if (refresh == null) a(request.with("refresh" to REFRESH), "refresh every $REFRESH s") else a(request.with("refresh" to null), "stop refreshing")
            }
            live(context)
            when (journal) {
                is Readings -> {
                    costs(journal.costs)
                    turns(journal.turns, context)
                }
                is Unread -> {
                    h2("Cost and turns")
                    p("Not shown: ${journal.why}.", "failure")
                }
            }
        }
    }

    // ── The live state ──────────────────────────────────────────────────────────────────────────────

    /** The cards of what runs now, from the snapshots of the services: no read of the Journal. */
    private fun Html.live(context: PageContext) {
        val services = context.services
        val text = TraceText(context.zone, context.now)
        val settings = services.settings.get()
        section("cards") {
            card("Narrator") {
                dl {
                    item("Phase", services.phase.name.lowercase())
                    item("Settings", settings.loadError?.let { "defaults ($it)" } ?: "loaded")
                    item("LLMGW key", if (settings.claude != null) "present" else "missing")
                    item("Reflexes", settings.reflex?.let { "${it.model}, ${it.status()}" } ?: "heuristic")
                }
            }
            card("Director") { director(services.director.status(), context) }
            card("Journal") { journal(services.journal.health()) }
            card("Players online") {
                val online = services.players.online
                if (online.isEmpty()) p("Nobody.", "muted")
                else table(listOf("Player", "Mode", "#Health", "#Food", "Where", "State")) {
                    online.forEach { player ->
                        row(if (player.idle) "muted" else null) {
                            cell { a(link("players", player.ref.name), player.ref.name) }
                            cell(player.gameMode)
                            number("${player.health.toInt()}/${player.maxHealth.toInt()}")
                            number(player.food)
                            cell("${player.pos.dim.removePrefix("minecraft:")} ${player.pos.x} ${player.pos.y} ${player.pos.z}" + (player.biome?.let { " · ${it.removePrefix("minecraft:")}" } ?: ""))
                            cell(if (player.idle) "idle" else "active")
                        }
                    }
                }
            }
            card("Tasks") {
                val open = services.workers.list().filterNot { it.status.finished }
                if (open.isEmpty()) p("None running.", "muted")
                else ul {
                    open.forEach { task ->
                        li {
                            a(link("tasks", task.taskId), "T-${task.taskId}")
                            text(" ${task.role.id} ${task.status.id}")
                            task.startedAt?.let { text(" for ${TraceText.duration(context.now - it.toEpochMilli())}") }
                            text(": ${TraceText.abbreviate(task.brief, BRIEF_CHARS)}")
                        }
                    }
                }
            }
            card("Memory") {
                p(MemoryCommands.statusLine(services.memory, text))
                lines(services.memory.situationLines(context.now))
            }
            card("Mechanics") {
                p(MechanicsCommands.statusLine(services.mechanics, BlockRestorer.pending()))
                lines(services.mechanics.situationLines(context.now))
            }
            card("Forge") {
                p(ForgeCommands.statusLine(services))
                p("Compiler: ${services.forge.status().compiler}", "muted")
            }
        }
    }

    private fun Html.card(title: String, body: Html.() -> Unit) = div("card") {
        h2(title)
        body()
    }

    private fun Html.lines(lines: List<String>) {
        if (lines.isNotEmpty()) ul("lines") { lines.forEach { li(it) } }
    }

    /** State, the open chapter and its context, the turns of the hour, the inbox, the reminders. */
    private fun Html.director(status: DirectorStatus, context: PageContext) = dl {
        item("State") {
            text(status.state.name.lowercase())
            status.until?.let { text(" until ${context.time(Instant.ofEpochMilli(it))}") }
            status.reason?.let { text(" ($it)") }
        }
        status.turnId?.let { turn -> item("Turn") { a(link("turns", turn), "#$turn"); text(" running") } }
        item("Chapter", status.chapter?.let { "#$it · context ${TraceText.tokens(status.contextTokens)} tokens" } ?: "none open")
        item("Turns this hour", "${status.turnsThisHour} of ${status.turnsPerHour}")
        item("Inbox", "${status.inbox}" + if (status.droppedSignals > 0) " (${status.droppedSignals} dropped)" else "")
        item("Reminders", status.reminders.joinToString { "R-${it.id} ${context.time(Instant.ofEpochMilli(it.at))}" }.ifEmpty { "none" })
    }

    private fun Html.journal(health: JournalHealth) = dl {
        if (!health.available) {
            item("State") { span("unavailable (${health.lastError})", "failure") }
            item("Lost", "${health.droppedEvents}")
            return@dl
        }
        item("State", "available")
        item("Size", "${megabytes(health.fileBytes)} + ${megabytes(health.walBytes)} WAL")
        item("Writes", "${health.rowsPerMinute} rows/min · last flush ${health.lastFlushMs} ms (${health.lastFlushRows} rows)")
        item("Queue", "${health.queued} queued · ${health.droppedEvents} lost")
    }

    // ── From the Journal ────────────────────────────────────────────────────────────────────────────

    /** The bars of the last 24 hours' cost, by role, the dearest first, and their total. */
    private fun Html.costs(costs: List<RoleCost>) {
        h2("Cost, last $COST_HOURS hours")
        if (costs.isEmpty()) {
            p("No model call.", "muted")
            return
        }
        val total = costs.sumOf { it.dollars ?: 0.0 }
        val unpriced = costs.sumOf { it.unpriced }
        bars(
            "Cost of the last $COST_HOURS hours by role",
            costs.map { cost ->
                val failed = if (cost.failed > 0) ", ${cost.failed} failed" else ""
                Bar(cost.role, cost.dollars ?: 0.0, "${Prices.dollars(cost.dollars)} · ${cost.calls} ${TraceText.plural(cost.calls.toInt(), "call")}$failed")
            },
        )
        p("muted") {
            text("Total ${Prices.dollars(total)}")
            if (unpriced > 0) text(" + $unpriced ${TraceText.plural(unpriced.toInt(), "call")} of a model without a price in the configuration (prices)")
            text(". At the API's prices: behind a subscription, an order of magnitude.")
        }
    }

    private fun Html.turns(turns: List<TurnSummary>, context: PageContext) {
        h2("Latest turns")
        if (turns.isEmpty()) {
            p("The Director has not woken up yet.", "muted")
            return
        }
        table(listOf("#Turn", "Started", "Chapter", "Trigger", "Woken by", "Tools", "Outcome", "#Context", "#Requests")) {
            turns.forEach { turn ->
                val outcome = turn.outcome
                row(if (outcome != null && outcome !in QUIET_OUTCOMES) "failure" else null) {
                    cell("num") { a(link("turns", turn.turnId), "#${turn.turnId}") }
                    cell(context.time(turn.startedAt))
                    cell(turn.chapter?.let { "#$it" })
                    cell(turn.trigger)
                    cell(turn.woke?.let { TraceText.abbreviate(it, WOKE_CHARS) })
                    cell(TraceText.toolList(turn.tools))
                    cell(outcome ?: "running")
                    number(turn.contextTokens?.let { TraceText.tokens(it) })
                    number(turn.llmCalls)
                }
            }
        }
    }

    /** The calls of [rows] summed by role, each priced by its model; the dearest roles first, then the busiest. */
    internal fun costByRole(rows: List<CostRow>, prices: Prices): List<RoleCost> =
        rows.groupBy { it.role }.map { (role, calls) ->
            val priced = calls.mapNotNull { prices.cost(it.model, it.input, it.output, it.cacheRead, it.cacheWrite) }
            val unpriced = calls.filter { prices.of(it.model) == null }.sumOf { it.calls }
            RoleCost(role, priced.takeIf { it.isNotEmpty() }?.sum(), calls.sumOf { it.calls }, calls.sumOf { it.failed }, unpriced)
        }.sortedWith(compareByDescending<RoleCost> { it.dollars ?: 0.0 }.thenByDescending { it.calls })

    /** One row of [COST_BY_ROLE]. */
    internal data class CostRow(
        val role: String, val model: String, val calls: Long, val failed: Long,
        val input: Long, val output: Long, val cacheRead: Long, val cacheWrite: Long,
    )

    /** What the page read from the Journal, or why it could not. */
    private sealed interface JournalPart

    private class Readings(val costs: List<RoleCost>, val turns: List<TurnSummary>) : JournalPart

    private class Unread(val why: String) : JournalPart

    private fun megabytes(bytes: Long): String = String.format(Locale.ROOT, "%.1f MB", bytes / 1_048_576.0)

    private const val HOUR_MS = 3_600_000L
    private const val BRIEF_CHARS = 160
    private const val WOKE_CHARS = 80

    /** Outcomes of a turn that are no failure. */
    private val QUIET_OUTCOMES = setOf("acted", "idle")
}
