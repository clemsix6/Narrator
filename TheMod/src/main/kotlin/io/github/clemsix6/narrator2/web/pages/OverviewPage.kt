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
import io.github.clemsix6.narrator2.trace.Cost
import io.github.clemsix6.narrator2.trace.Prices
import io.github.clemsix6.narrator2.trace.TraceQueries
import io.github.clemsix6.narrator2.trace.TurnSummary
import io.github.clemsix6.narrator2.trace.Usage
import io.github.clemsix6.narrator2.web.Bar
import io.github.clemsix6.narrator2.web.Html
import io.github.clemsix6.narrator2.web.Page
import io.github.clemsix6.narrator2.web.PageContext
import io.github.clemsix6.narrator2.web.WebPage
import io.github.clemsix6.narrator2.web.WebRequest
import io.github.clemsix6.narrator2.web.bars
import io.github.clemsix6.narrator2.web.link
import java.time.Instant
import java.util.Locale

/**
 * `/`, the overview (M6 §8.4): what runs now, from the live snapshots (phase, Director, players online, tasks, Journal,
 * memory, mechanics, Forge), then, from the Journal in one read, the cost of the last 24 hours by role (SVG bars) and the
 * latest [TURNS] turns of the Director. The calls by role are those of `/narrator usage` ([TraceQueries.usage]), priced
 * and written as everywhere ([Prices.costOf], [Cost.text]); the turns are in the table of `/turns` ([turnTable]).
 * `?refresh=15` reloads it every 15 seconds. Without the Journal, the live part still shows, and the rest says why it
 * does not.
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

    override fun render(request: WebRequest, context: PageContext): Page? {
        if (request.path.isNotEmpty()) return null
        val refresh = request.param("refresh")?.toIntOrNull()?.coerceIn(MIN_REFRESH, MAX_REFRESH)
        val journal = try {
            context.read { connection ->
                val queries = TraceQueries(connection)
                Readings(queries.usage(context.now - COST_HOURS * HOUR_MS), queries.lastTurns(TURNS))
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
                    costs(journal.usage, context.prices)
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

    /** The bars of the last 24 hours' cost, by role ([usage]), the dearest first, then the busiest, and their total. */
    private fun Html.costs(usage: List<Usage>, prices: Prices) {
        h2("Cost, last $COST_HOURS hours")
        if (usage.isEmpty()) {
            p("No model call.", "muted")
            return
        }
        val roles = usage.map { it to prices.costOf(it.models) }
            .sortedWith(compareByDescending<Pair<Usage, Cost>> { it.second.dollars }.thenByDescending { it.first.calls })
        bars(
            "Cost of the last $COST_HOURS hours by role",
            roles.map { (role, cost) ->
                val failed = if (role.failed > 0) ", ${role.failed} failed" else ""
                Bar(role.role, cost.dollars, "${cost.text} · ${role.calls} ${TraceText.plural(role.calls, "call")}$failed")
            },
        )
        val total = roles.fold(Cost.NONE) { sum, (_, cost) -> sum + cost }
        p("Total ${total.text}. At the API's prices: behind a subscription, an order of magnitude.", "muted")
    }

    private fun Html.turns(turns: List<TurnSummary>, context: PageContext) {
        h2("Latest turns")
        if (turns.isEmpty()) p("The Director has not woken up yet.", "muted") else turnTable(turns, context)
    }

    /** What the page read from the Journal, or why it could not. */
    private sealed interface JournalPart

    private class Readings(val usage: List<Usage>, val turns: List<TurnSummary>) : JournalPart

    private class Unread(val why: String) : JournalPart

    private fun megabytes(bytes: Long): String = String.format(Locale.ROOT, "%.1f MB", bytes / 1_048_576.0)

    private const val HOUR_MS = 3_600_000L
    private const val BRIEF_CHARS = 160
}
