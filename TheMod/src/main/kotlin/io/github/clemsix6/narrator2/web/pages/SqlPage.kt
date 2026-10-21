package io.github.clemsix6.narrator2.web.pages

import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.journal.SchemaDoc
import io.github.clemsix6.narrator2.journal.SqlAccess
import io.github.clemsix6.narrator2.journal.SqlLimits
import io.github.clemsix6.narrator2.journal.SqlOutcome
import io.github.clemsix6.narrator2.journal.SqlRunner
import io.github.clemsix6.narrator2.web.Html
import io.github.clemsix6.narrator2.web.Page
import io.github.clemsix6.narrator2.web.PageContext
import io.github.clemsix6.narrator2.web.WebPage
import io.github.clemsix6.narrator2.web.WebRequest
import io.github.clemsix6.narrator2.web.link

/**
 * `/sql?q=…` (M6 §8.4): one read-only query on the Journal with the model's access ([SqlAccess.MODEL]), run by the
 * `sql` tool's own [SqlRunner]: its white list of tables, views and macros, never the private messages, the database's
 * catalog, a file or a setting of the server. Within the Director's limits ([limits]): 200 rows, and
 * `journal.sqlTimeoutSeconds`, never more than the dashboard's 5 s. The query runs in the dashboard's turn
 * ([PageContext.inTurn]): one read of the dashboard at a time. Nothing is recorded but the [SqlRunner]'s log line.
 */
object SqlPage : WebPage {
    override val root = "sql"

    override val label = "SQL"

    /** The page's limits: the Director's (`SqlLimits.MODEL_MAX_ROWS` rows, `journal.sqlTimeoutSeconds`), at most [QUERY_SECONDS]. */
    internal fun limits(config: NarratorConfig): SqlLimits =
        SqlLimits(maxRows = SqlLimits.MODEL_MAX_ROWS, timeoutSeconds = config.journal.sqlTimeoutSeconds.coerceIn(1, QUERY_SECONDS), maxChars = MAX_CHARS)

    override fun render(request: WebRequest, context: PageContext): Page? {
        if (request.path.isNotEmpty()) return null
        val query = request.param("q")
        val limits = limits(context.services.settings.get().config)
        val outcome = query?.let { sql ->
            context.inTurn { SqlRunner(context.services.journal, context.services.clock).query(sql, limits, SqlAccess.MODEL) }
        }
        return Page("SQL") {
            p(
                "One read-only SELECT on the Journal, as the model's sql tool runs it: its tables, views and macros, never the private " +
                    "messages, the catalog, a file or a setting of the server; ${limits.maxRows} rows and ${limits.timeoutSeconds} s at most.",
                "muted",
            )
            form(query)
            outcome?.let { outcome(it) }
            h2("Examples")
            ul {
                EXAMPLES.forEach { example -> li { a(link(root, query = mapOf("q" to example)), example) } }
            }
            details("What the model's sql tool knows of the Journal") { pre(SchemaDoc.text) }
        }
    }

    private fun Html.form(query: String?) = element("form", "method" to "get", "action" to link(root).href, "class" to "filters") {
        element("textarea", "name" to "q", "rows" to ROWS, "cols" to COLUMNS, "spellcheck" to "false") { text(query.orEmpty()) }
        element("button", "type" to "submit") { text("Run") }
    }

    private fun Html.outcome(outcome: SqlOutcome) = when (outcome) {
        is SqlOutcome.Rows -> pre(outcome.text)
        is SqlOutcome.Rejected -> p("Refused: ${outcome.reason}.", "failure")
        is SqlOutcome.Failed -> pre("Failed: ${outcome.message}", "failure")
        is SqlOutcome.TimedOut -> p("The query did not finish within ${outcome.seconds} s: aggregate, filter or add LIMIT.", "failure")
    }

    /** The text of a result: 200 rows of the longest cells fit. */
    private const val MAX_CHARS = 100_000

    private const val ROWS = 6
    private const val COLUMNS = 100

    private val EXAMPLES = listOf(
        "SELECT ref, kind, status, statement FROM mem_canon WHERE status IN ('planned', 'active') ORDER BY created_at DESC LIMIT 20",
        "SELECT ts, text, register, flat, repeats, crutches FROM narrator_lines ORDER BY ts DESC LIMIT 20",
        "SELECT player, count(*) AS deaths FROM deaths GROUP BY ALL ORDER BY deaths DESC",
        "SELECT * FROM columns_of('chat')",
    )
}
