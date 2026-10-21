package io.github.clemsix6.narrator2.web.pages

import io.github.clemsix6.narrator2.command.TraceText
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.enumOfId
import io.github.clemsix6.narrator2.memory.instant
import io.github.clemsix6.narrator2.memory.longOrNull
import io.github.clemsix6.narrator2.memory.query
import io.github.clemsix6.narrator2.web.BadRequest
import io.github.clemsix6.narrator2.web.Html
import io.github.clemsix6.narrator2.web.Link
import io.github.clemsix6.narrator2.web.PageContext
import io.github.clemsix6.narrator2.web.READ_TIMEOUT_MS
import io.github.clemsix6.narrator2.web.WebRequest
import io.github.clemsix6.narrator2.web.link
import java.sql.Connection
import java.time.Instant

/*
 * What the pages of the memory, the players, the mechanics, the Forge, the style and SQL share (M6-12): the time limit
 * of their statements, links to a memory row or a player, tabs, and the engine's decisions as a table.
 */

/** Seconds a statement of these pages may run: the dashboard's read limit ([READ_TIMEOUT_MS]). */
internal const val QUERY_SECONDS = (READ_TIMEOUT_MS / 1_000).toInt()

/** Characters of a text in a table's cell; the whole text is on the row's own page. */
internal const val CELL_CHARS = 160

/** [ref] linked to its page, `/memory/C-45`. */
internal fun Html.refLink(ref: Ref) = a(link("memory", ref.toString()), ref.toString())

/** [names] linked to their players' pages, comma separated; [none] when there is nobody. */
internal fun Html.nameLinks(names: List<String>, none: String = "everyone") {
    if (names.isEmpty()) {
        span(none, "muted")
        return
    }
    names.forEachIndexed { i, name ->
        if (i > 0) text(", ")
        a(link("players", name), name)
    }
}

/** [players] linked to their pages; [none] when there is nobody. */
internal fun Html.playerLinks(players: List<PlayerRef>, none: String = "everyone") = nameLinks(players.map { it.name }, none)

/** `Canon · Memories · …`: links to the parts of a page, [current] in bold. */
internal fun Html.tabs(current: String, tabs: List<Pair<String, Link>>) = p {
    tabs.forEachIndexed { i, (label, target) ->
        if (i > 0) text(" · ")
        if (label == current) strong(label) else a(target, label)
    }
}

/** The filter [name] among [options]; null when absent; a value out of them is a [BadRequest]. */
internal fun WebRequest.choice(name: String, options: List<String>): String? =
    param(name)?.also { value -> if (value !in options) throw BadRequest("$name: no such value $value (${options.joinToString()})") }

/** The filter [name] as a constant of [E] (`running_gag` → `RUNNING_GAG`); null when absent; any other value is a [BadRequest]. */
internal inline fun <reified E : Enum<E>> WebRequest.choice(name: String): E? =
    choice(name, enumValues<E>().map { it.name.lowercase() })?.let { enumOfId<E>(it) }

/** [text] abbreviated to [max] characters, for a table's cell; null stays null. */
internal fun short(text: String?, max: Int = CELL_CHARS): String? = text?.let { TraceText.abbreviate(it, max) }

/** `+2`, `0`, `-3`. */
internal fun signed(value: Int): String = if (value > 0) "+$value" else value.toString()

/**
 * The conditions of a list's `WHERE`, built from a request's filters: fixed SQL with `?` for each value, never a text of
 * the request in the SQL itself.
 */
internal class Where {
    private val conditions = mutableListOf<String>()
    private val bound = mutableListOf<Any?>()

    /** The values of the `?`, in order. */
    val values: Array<Any?> get() = bound.toTypedArray()

    fun add(condition: String, vararg values: Any?) {
        conditions += condition
        bound.addAll(values)
    }

    override fun toString(): String = conditions.joinToString(" AND ").ifEmpty { "true" }
}

/** One row of `engine_decisions`, as the pages show it. */
internal data class DecisionRow(val ts: Instant, val kind: String, val decision: String, val subject: String?, val reason: String?, val turnId: Long?)

/**
 * The decisions matching [where] (fixed SQL, its values in [parameters]), the latest first, [limit] from [offset]. Reads
 * `engine_decisions` only.
 */
internal fun Connection.decisions(where: String, vararg parameters: Any?, limit: Int, offset: Int = 0): List<DecisionRow> = query(
    "SELECT ts, kind, decision, subject, reason, turn_id FROM engine_decisions WHERE $where ORDER BY ts DESC, id DESC LIMIT ? OFFSET ?",
    *parameters, limit, offset,
    timeoutSeconds = QUERY_SECONDS,
) { DecisionRow(instant(1), getString(2), getString(3), getString(4), getString(5), longOrNull(6)) }

/** [rows] as a table: when, what was decided ([kind] too when asked), about what, why, and the Director's turn. */
internal fun Html.decisionTable(rows: List<DecisionRow>, context: PageContext, kind: Boolean = false) {
    table(listOfNotNull("When", "Kind".takeIf { kind }, "Decision", "Subject", "Reason", "#Turn")) {
        rows.forEach { decision ->
            row(if (decision.decision in FAILED_DECISIONS) "failure" else null) {
                cell(context.time(decision.ts))
                if (kind) cell(decision.kind)
                cell(decision.decision)
                cell(decision.subject)
                cell(decision.reason)
                cell("num") { decision.turnId?.let { a(link("turns", it), "#$it") } }
            }
        }
    }
}

/** Decisions shown in red: something failed or was refused. */
private val FAILED_DECISIONS = setOf("failed", "fault", "refused", "broken", "stuck", "leak", "unreadable", "denied")

/** A line saying [what] is off, and why: the page shows what it can without it. */
internal fun Html.off(what: String, reason: String?) = p("$what is off: ${reason ?: "no reason given"}.", "warning")
