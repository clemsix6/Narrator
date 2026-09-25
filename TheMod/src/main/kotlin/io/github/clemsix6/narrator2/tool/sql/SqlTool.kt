package io.github.clemsix6.narrator2.tool.sql

import io.github.clemsix6.narrator2.journal.SchemaDoc
import io.github.clemsix6.narrator2.journal.SqlAccess
import io.github.clemsix6.narrator2.journal.SqlLimits
import io.github.clemsix6.narrator2.journal.SqlOutcome
import io.github.clemsix6.narrator2.journal.SqlRunner
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Doc
import io.github.clemsix6.narrator2.tool.MaxLen
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.Range
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolEnv
import io.github.clemsix6.narrator2.tool.ToolResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The Journal in SQL (M1_M2 §9.5): `sql`, whose description is the schema generated from the database
 * (§6.5). `ToolCatalog` builds the family once per conversation.
 */
fun sqlTools(services: NarratorServices): List<NarratorTool<*>> =
    listOf(SqlTool(services.settings.get().config.journal.sqlTimeoutSeconds))

@Serializable
data class SqlArgs(
    @Doc("One read-only SELECT in DuckDB SQL.")
    @MaxLen(SqlRunner.MAX_QUERY_CHARS)
    val query: String,
    @Doc("Rows shown at most; the others are only counted.")
    @Range(1.0, 200.0)
    @SerialName("max_rows")
    val maxRows: Int = SqlLimits.MODEL_DEFAULT_ROWS,
)

/**
 * `sql`: one read-only query on the Journal, run by [SqlRunner] for the model ([SqlAccess.MODEL]: the
 * private messages and the catalog are out of reach). Its description is [SchemaDoc.text] as is: the
 * same bytes in every conversation with the same schema, so the cached prefix holds. Shown to the model
 * only when the Journal opened.
 *
 * [SqlRunner] first waits for the facts still queued to be written (`barrier()`), so that the query
 * sees the last second too. A query stops after `journal.sqlTimeoutSeconds` for the Director and
 * [SqlLimits.ANALYST_TIMEOUT_SECONDS] for the Analyst. [SqlRunner] may take that long plus 2.5 s (the
 * barrier, then waiting for a connection), so the tool's own delay is [MARGIN_SECONDS] more than the
 * longest of the two, taken when the tool is built; a reload that raises the Director's limit
 * afterwards is capped to it until the next conversation.
 *
 * @param directorSeconds `journal.sqlTimeoutSeconds` when the tool is built.
 */
class SqlTool(directorSeconds: Int) : NarratorTool<SqlArgs>(
    name = "sql",
    description = SchemaDoc.text,
    args = SqlArgs.serializer(),
    effect = ToolEffect.READ,
    timeoutMs = (maxOf(directorSeconds, SqlLimits.ANALYST_TIMEOUT_SECONDS) + MARGIN_SECONDS) * 1_000L,
) {
    /** The longest a query may run within this tool's delay. */
    private val maxQuerySeconds = (timeoutMs / 1_000L - MARGIN_SECONDS).toInt()

    override fun isAvailable(env: ToolEnv): Boolean = env.journalAvailable

    override suspend fun run(ctx: ToolContext, args: SqlArgs): ToolResult {
        val journal = ctx.services.journal
        val seconds = when (ctx.caller.role) {
            Role.ANALYST -> SqlLimits.ANALYST_TIMEOUT_SECONDS
            else -> ctx.settings.config.journal.sqlTimeoutSeconds
        }.coerceAtMost(maxQuerySeconds)
        return when (val outcome = SqlRunner(journal, ctx.clock).query(args.query, SqlLimits(args.maxRows, seconds))) {
            is SqlOutcome.Rows -> ToolResult.ok(outcome.text)
            is SqlOutcome.Rejected -> ToolResult.invalid(outcome.reason)
            // DuckDB's errors are the query's fault (a column, a type): the model fixes it. A Journal down is not.
            is SqlOutcome.Failed -> if (journal.available) ToolResult.invalid(outcome.message) else ToolResult.failed(outcome.message)
            is SqlOutcome.TimedOut -> ToolResult.timeout("the query did not finish within ${outcome.seconds} s: aggregate, filter or add LIMIT")
        }
    }

    companion object {
        /** The tool's delay beyond the query's: [SqlRunner] waits up to 0.5 s for the barrier and 2 s for a connection. */
        const val MARGIN_SECONDS = 3
    }
}
