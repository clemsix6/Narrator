package io.github.clemsix6.narrator2.web.pages

import io.github.clemsix6.narrator2.command.ForgeCommands
import io.github.clemsix6.narrator2.forge.ModuleStatus
import io.github.clemsix6.narrator2.forge.ParamSpec
import io.github.clemsix6.narrator2.forge.arsenal.ArsenalStore
import io.github.clemsix6.narrator2.journal.JournalTimeoutException
import io.github.clemsix6.narrator2.journal.JournalUnavailableException
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.web.DashboardQueries
import io.github.clemsix6.narrator2.web.DecisionFilter
import io.github.clemsix6.narrator2.web.DecisionRow
import io.github.clemsix6.narrator2.web.Html
import io.github.clemsix6.narrator2.web.Page
import io.github.clemsix6.narrator2.web.PageContext
import io.github.clemsix6.narrator2.web.Paging
import io.github.clemsix6.narrator2.web.WebPage
import io.github.clemsix6.narrator2.web.WebRequest
import io.github.clemsix6.narrator2.web.link
import io.github.clemsix6.narrator2.web.pager
import kotlinx.coroutines.runBlocking
import java.time.Instant

/**
 * `/forge` and `/forge/<module>[/<version>]` (M6 §8.4): the Forge's state, its arsenal (every version, its status,
 * who saved it, its scenarios, its problem) and its latest decisions (tests, saves, reviews); then one version: its
 * manifest, its parameters, its other versions, its deployments running, its decisions, and its source in a `<pre>`.
 *
 * The arsenal is read from the Forge's index in memory, and a source through the arsenal (`Arsenal.source`, the files
 * it checked): an address never becomes a path. A forged source's place is given relative to the world, never as a
 * path of the server.
 */
object ForgePage : WebPage {
    override val root = "forge"

    override val label = "Forge"

    override fun render(request: WebRequest, context: PageContext): Page? = when (request.path.size) {
        0 -> overview(request, context)
        1, 2 -> module(request.path[0], request.path.getOrNull(1), context)
        else -> null
    }

    // ── The Forge ───────────────────────────────────────────────────────────────────────────────────

    private fun overview(request: WebRequest, context: PageContext): Page {
        val services = context.services
        val forge = services.forge
        val paging = Paging.of(request)
        val (entries, more) = paging.rows(forge.arsenal.list().drop(paging.offset).take(paging.limit))
        val history = readDecisions(context, DecisionFilter(kind = FORGE))
        return Page("Forge") {
            p(ForgeCommands.statusLine(services))
            dl {
                item("State", if (forge.available) "on" else "off (${forge.unavailableReason ?: "no reason given"})")
                val status = forge.status()
                item("Compiler", "${status.compiler}" + (status.ecj?.let { " · ECJ $it" }.orEmpty()))
                services.mechanics.modules.halted?.let { halt ->
                    item("Switch", "set by ${halt.by} at ${context.time(Instant.ofEpochMilli(halt.at))}: « ${halt.reason} »; nothing is deployed until /narrator forge on")
                }
                val config = services.settings.get().config.forge
                item("Approval", "${config.approval} · ${config.maxActive} modules at most at once")
            }
            h2("Arsenal")
            if (entries.isEmpty()) p(if (forge.available) "Empty." else "Unknown while the Forge is off.", "muted")
            else table(listOf("Module", "Status", "By", "Saved", "Summary", "#Params", "#Scenarios", "Problem")) {
                entries.forEach { entry ->
                    row(look(entry.status)) {
                        cell { a(link(root, entry.name, entry.version), "${entry.name} v${entry.version}") }
                        cell(entry.status.id)
                        cell(entry.by)
                        cell(if (entry.builtIn) null else context.time(entry.savedAt))
                        cell(short(entry.summary))
                        number(entry.params.size)
                        number(entry.scenarios.size)
                        cell(short(entry.problem))
                    }
                }
            }
            pager(request, paging, more)
            h2("Latest decisions")
            shown(history, context)
            p("muted") {
                text("All of them: ")
                a(link("decisions", query = mapOf("kind" to FORGE)), "decisions of the Forge")
                text(".")
            }
        }
    }

    // ── One version ─────────────────────────────────────────────────────────────────────────────────

    private fun module(name: String, typedVersion: String?, context: PageContext): Page? {
        val services = context.services
        val arsenal = services.forge.arsenal
        val versions = arsenal.list().filter { it.name == name }.sortedByDescending { it.version }
        val entry = when (typedVersion) {
            null -> versions.firstOrNull()
            else -> typedVersion.removePrefix("v").toIntOrNull()?.let { version -> versions.firstOrNull { it.version == version } }
        } ?: return null
        val source = runBlocking { arsenal.source(entry.name, entry.version) }
        val running = services.mechanics.modules.list().filter { it.module == entry.name }
        val history = readDecisions(context, DecisionFilter(kind = FORGE, module = entry.name))
        return Page("${entry.name} v${entry.version}") {
            dl {
                item("Status", entry.status.id)
                item("Summary", entry.summary)
                entry.note?.let { item("Note", it) }
                item("By", if (entry.builtIn) "built in the mod" else "${entry.by}, saved ${context.time(entry.savedAt)}")
                entry.problem?.let { item("Problem") { span(it, "failure") } }
                item("Scenarios", entry.scenarios.joinToString().ifEmpty { "none" })
                item("Built", if (entry.classes > 0) "SDK ${entry.sdk} · ${entry.classes} classes, ${entry.bytes} bytes" else "not yet (SDK ${entry.sdk})")
                item("Hash", entry.hash)
                item("Source", if (entry.builtIn) "in the mod's jar (narrator2/forge/arsenal/${entry.name}.java)" else "world/narrator2/forge/${entry.name}/${ArsenalStore.sourceName(entry.version)}")
                item("Versions") {
                    versions.forEachIndexed { i, version ->
                        if (i > 0) text(", ")
                        if (version.version == entry.version) strong("v${version.version} ${version.status.id}")
                        else a(link(root, version.name, version.version), "v${version.version} ${version.status.id}")
                    }
                }
            }
            h2("Parameters")
            if (entry.params.isEmpty()) p("None.", "muted") else params(entry.params)
            h2("Running")
            if (running.isEmpty()) p("Not deployed now.", "muted")
            else ul { running.forEach { view -> li { refLink(view.ref); text(" v${view.version} ${view.kind.id}: ${view.acts} acts, ${view.faults} faults") } } }
            h2("Decisions")
            shown(history, context)
            h2("Source")
            if (source == null) p("The source cannot be read (see the server log).", "failure") else pre(source)
        }
    }

    private fun Html.params(params: List<ParamSpec>) = table(listOf("Name", "Type", "Default", "Bounds", "Choices", "What")) {
        params.forEach { param ->
            row {
                cell(param.name)
                cell(param.type.name.lowercase())
                cell(if (param.required) "required" else param.fallback ?: "none")
                cell(bounds(param))
                cell(param.choices.joinToString())
                cell(param.doc)
            }
        }
    }

    /** `2 to 15`, `at least 2`, `at most 8`, or nothing. */
    private fun bounds(param: ParamSpec): String? {
        val min = param.min?.let(::number)
        val max = param.max?.let(::number)
        return when {
            min != null && max != null -> "$min to $max"
            min != null -> "at least $min"
            max != null -> "at most $max"
            else -> null
        }
    }

    private fun number(value: Double): String = if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

    // ── Parts ───────────────────────────────────────────────────────────────────────────────────────

    /** The decisions read from the Journal, or why they could not be: the rest of the page shows anyway. */
    private sealed interface Latest {
        class Read(val rows: List<DecisionRow>) : Latest

        class Unread(val why: String) : Latest
    }

    /** The latest decisions matching [filter] ([LATEST]), with the table and the query of `/decisions`. */
    private fun readDecisions(context: PageContext, filter: DecisionFilter): Latest = try {
        Latest.Read(LATEST.rows(context.read { DashboardQueries(it).decisions(filter, LATEST) }).first)
    } catch (e: JournalUnavailableException) {
        Latest.Unread("${e.message}")
    } catch (e: JournalTimeoutException) {
        Latest.Unread("${e.message}: the Journal is busy, reload the page in a moment")
    }

    private fun Html.shown(latest: Latest, context: PageContext) = when (latest) {
        is Latest.Unread -> p("Not shown: ${latest.why}.", "failure")
        is Latest.Read -> if (latest.rows.isEmpty()) p("None yet.", "muted") else decisionTable(latest.rows, context)
    }

    private fun look(status: ModuleStatus): String? = when (status) {
        ModuleStatus.READY -> null
        ModuleStatus.PENDING -> "warning"
        ModuleStatus.REJECTED -> "muted"
        ModuleStatus.BROKEN -> "failure"
    }

    private val FORGE = DecisionRecord.Kind.FORGE.id

    /** Decisions listed: the latest 20, the rest on `/decisions`. */
    private val LATEST = Paging(1, size = 20)
}
