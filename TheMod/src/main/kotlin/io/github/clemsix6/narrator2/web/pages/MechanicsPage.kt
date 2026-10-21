package io.github.clemsix6.narrator2.web.pages

import io.github.clemsix6.narrator2.command.MechanicsCommands
import io.github.clemsix6.narrator2.guard.ActiveEffect
import io.github.clemsix6.narrator2.guard.BlockRestorer
import io.github.clemsix6.narrator2.journal.JournalTimeoutException
import io.github.clemsix6.narrator2.journal.JournalUnavailableException
import io.github.clemsix6.narrator2.mechanics.ChallengeView
import io.github.clemsix6.narrator2.mechanics.ModuleView
import io.github.clemsix6.narrator2.mechanics.RuleView
import io.github.clemsix6.narrator2.mechanics.rule.RuleText
import io.github.clemsix6.narrator2.memory.CANON_COLUMNS
import io.github.clemsix6.narrator2.memory.CanonEntry
import io.github.clemsix6.narrator2.memory.CanonMechanic
import io.github.clemsix6.narrator2.memory.Names
import io.github.clemsix6.narrator2.memory.canonEntry
import io.github.clemsix6.narrator2.memory.query
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.web.Filter
import io.github.clemsix6.narrator2.web.Html
import io.github.clemsix6.narrator2.web.Page
import io.github.clemsix6.narrator2.web.PageContext
import io.github.clemsix6.narrator2.web.Paging
import io.github.clemsix6.narrator2.web.WebPage
import io.github.clemsix6.narrator2.web.WebRequest
import io.github.clemsix6.narrator2.web.filters
import io.github.clemsix6.narrator2.web.link
import io.github.clemsix6.narrator2.web.pager
import java.time.Instant
import java.util.Locale
import java.util.UUID

/**
 * `/mechanics` (M6 §8.4): what the mechanics engine runs now, from its snapshot (challenges, duels, rules, the modules
 * of the Forge, its lines of the situation) and the guards' traps; then, from the Journal, their history in the canon
 * (`/mechanics`: the rows the engine tracks, open and closed) or the engine's `mechanic` decisions
 * (`/mechanics/decisions`). Nothing here touches the world: the snapshots are safe from any thread. Without the
 * Journal, what runs still shows, and the history says why it is missing.
 */
object MechanicsPage : WebPage {
    override val root = "mechanics"

    override val label = "Mechanics"

    private const val DECISIONS = "decisions"

    override fun render(request: WebRequest, context: PageContext): Page? = when (request.path) {
        emptyList<String>() -> page(showDecisions = false, request, context)
        listOf(DECISIONS) -> page(showDecisions = true, request, context)
        else -> null
    }

    private fun page(showDecisions: Boolean, request: WebRequest, context: PageContext): Page {
        val paging = Paging.of(request)
        val history: Html.() -> Unit = try {
            if (showDecisions) decisions(request, context, paging) else history(request, context, paging)
        } catch (e: JournalUnavailableException) {
            { p("Not shown: ${e.message}.", "failure") }
        } catch (e: JournalTimeoutException) {
            { p("Not shown: ${e.message}: the Journal is busy, reload the page in a moment.", "failure") }
        }
        return Page("Mechanics") {
            live(context)
            h2(if (showDecisions) "The engine's decisions" else "Their history in the canon")
            tabs(if (showDecisions) DECISIONS_TAB else HISTORY_TAB, listOf(HISTORY_TAB to link(root), DECISIONS_TAB to link(root, DECISIONS)))
            history()
        }
    }

    // ── What runs now ───────────────────────────────────────────────────────────────────────────────

    private fun Html.live(context: PageContext) {
        val mechanics = context.services.mechanics
        p(MechanicsCommands.statusLine(mechanics, BlockRestorer.pending()))
        if (mechanics.available) {
            challenges(mechanics.challenges.list(), context)
            rules(mechanics.rules.list(), context)
            modules(mechanics.modules.list(), context)
            val lines = mechanics.situationLines(context.now)
            h2("Lines of the situation")
            if (lines.isEmpty()) p("None: nothing to tell the Director.", "muted") else ul("lines") { lines.forEach { li(it) } }
        }
        traps(BlockRestorer.byPlayer(), context)
    }

    private fun Html.challenges(views: List<ChallengeView>, context: PageContext) {
        h2("Challenges and duels")
        if (views.isEmpty()) {
            p("None in progress.", "muted")
            return
        }
        table(listOf("Ref", "What", "Status", "Ends", "Players", "#Bets", "Line")) {
            views.forEach { view ->
                row {
                    cell { refLink(view.ref) }
                    cell {
                        text(view.spec?.let { "challenge ${it.goal.id}" } ?: "duel")
                        (view.spec?.title ?: view.duel?.title)?.let { small(" « $it »") }
                    }
                    cell(view.status.id)
                    cell(ends(view.endsAt, context))
                    cell {
                        view.participants.forEachIndexed { i, participant ->
                            if (i > 0) text(", ")
                            a(link("players", participant.name), participant.name)
                            val state = listOfNotNull(
                                participant.progress.takeIf { it > 0 }?.toString(),
                                "done".takeIf { participant.doneAt != null },
                                participant.out?.let { "out: ${it.id}" },
                                "late".takeIf { participant.late },
                            )
                            if (state.isNotEmpty()) small(" (${state.joinToString(", ")})")
                        }
                    }
                    number(view.bets)
                    cell(view.line)
                }
            }
        }
    }

    private fun Html.rules(views: List<RuleView>, context: PageContext) {
        h2("Laws and plans")
        if (views.isEmpty()) {
            p("None active.", "muted")
            return
        }
        table(listOf("Ref", "Kind", "Rule", "Until", "#Fired", "#Skipped", "#Failed", "Statement")) {
            views.forEach { view ->
                row(if (view.failures > 0) "warning" else null) {
                    cell { refLink(view.ref) }
                    cell(view.spec.kind.id + if (view.spec.once) ", once" else "")
                    cell(RuleText.summary(view.spec))
                    cell(ends(view.endsAt, context))
                    number(view.fired)
                    number(view.skipped)
                    number(view.failures)
                    cell(view.spec.statement)
                }
            }
        }
    }

    private fun Html.modules(views: List<ModuleView>, context: PageContext) {
        h2("Modules")
        if (views.isEmpty()) {
            p("None running.", "muted")
            return
        }
        table(listOf("Ref", "Module", "Kind", "Until", "#Acts", "#Faults", "#ms/s", "Referees")) {
            views.forEach { view ->
                row(if (view.faults > 0) "warning" else null) {
                    cell { refLink(view.ref) }
                    cell { a(link("forge", view.module, view.version), "${view.module} v${view.version}") }
                    cell(view.kind.id)
                    cell(ends(view.endsAt, context))
                    number(view.acts)
                    number(view.faults)
                    number(String.format(Locale.ROOT, "%.1f", view.msPerSecond))
                    cell { view.challenge?.let { refLink(it) } }
                }
            }
        }
    }

    /** The guards' trap blocks waiting to be taken away, by player (they run with the engine off too). */
    private fun Html.traps(traps: Map<UUID, List<ActiveEffect>>, context: PageContext) {
        h2("Traps")
        if (traps.isEmpty()) {
            p("No trap block waits in the loaded chunks.", "muted")
            return
        }
        table(listOf("Player", "Blocks", "Until")) {
            traps.entries.sortedBy { it.key }.take(MAX_TRAPPED).forEach { (uuid, effects) ->
                val name = context.services.players.get(uuid)?.ref?.name
                effects.forEach { effect ->
                    row {
                        cell { if (name != null) a(link("players", name), name) else text(uuid.toString()) }
                        cell(effect.what)
                        cell(effect.until?.let { context.time(Instant.ofEpochMilli(it)) })
                    }
                }
            }
        }
        if (traps.size > MAX_TRAPPED) p("… and ${traps.size - MAX_TRAPPED} more players.", "muted")
    }

    /** `21:40:12 (in 12m)`, or `21:40:12 (2m ago)` for an end already past. */
    private fun ends(at: Long, context: PageContext): String {
        val left = at - context.now
        return context.time(Instant.ofEpochMilli(at)) + if (left >= 0) " (in ${ToolText.wait(left)})" else " (${ToolText.duration(-left)} ago)"
    }

    // ── From the Journal ────────────────────────────────────────────────────────────────────────────

    private fun history(request: WebRequest, context: PageContext, paging: Paging): Html.() -> Unit {
        val where = Where()
        where.add("mechanic IS NOT NULL")
        request.choice<CanonMechanic>("mechanic")?.let { where.add("mechanic = ?", it.id) }
        when (request.choice("status", listOf(OPEN, CLOSED))) {
            OPEN -> where.add("status IN ('planned', 'active')")
            CLOSED -> where.add("status NOT IN ('planned', 'active')")
        }
        val (rows, more) = paging.rows(
            context.read { c ->
                val names = Names.of(c)
                c.query(
                    "SELECT $CANON_COLUMNS FROM canon WHERE $where ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                    *where.values, paging.limit, paging.offset, timeoutSeconds = QUERY_SECONDS,
                ) { canonEntry(names) }
            },
        )
        return {
            filters(link(root), request, listOf(Filter.Choice("mechanic", "Mechanic", CanonMechanic.entries.map { it.id }), Filter.Choice("status", "Status", listOf(OPEN, CLOSED))))
            if (rows.isEmpty()) p("Nothing yet.", "muted")
            else table(listOf("Ref", "Mechanic", "Status", "Set", "To", "Statement", "Resolution")) {
                rows.forEach { entry -> historyRow(entry, context) }
            }
            pager(request, paging, more)
        }
    }

    private fun Html.Table.historyRow(entry: CanonEntry, context: PageContext) = row(if (entry.status.open) null else "muted") {
        cell { refLink(entry.ref) }
        cell(entry.mechanic?.id + if (entry.mechanic?.id == entry.kind.id) "" else " (${entry.kind.id})")
        cell(entry.status.id + (entry.resolvedAt?.let { " ${context.time(it)}" }.orEmpty()))
        cell(context.time(entry.createdAt))
        cell { playerLinks(entry.players) }
        cell(short(entry.statement))
        cell(short(entry.resolution))
    }

    private fun decisions(request: WebRequest, context: PageContext, paging: Paging): Html.() -> Unit {
        val where = Where()
        where.add("kind = ?", DecisionRecord.Kind.MECHANIC.id)
        request.param("decision")?.let { where.add("decision = ?", it) }
        request.param("subject")?.let { where.add("contains(lower(subject), lower(?))", it) }
        val (rows, more) = paging.rows(context.read { c -> c.decisions(where.toString(), *where.values, limit = paging.limit, offset = paging.offset) })
        return {
            filters(link(root, DECISIONS), request, listOf(Filter.Field("decision", "Decision", "started"), Filter.Field("subject", "Subject", "C-50")))
            if (rows.isEmpty()) p("No decision of the engine.", "muted") else decisionTable(rows, context)
            pager(request, paging, more)
        }
    }

    private const val HISTORY_TAB = "History"
    private const val DECISIONS_TAB = "Decisions"
    private const val OPEN = "open"
    private const val CLOSED = "closed"
    private const val MAX_TRAPPED = 15
}
