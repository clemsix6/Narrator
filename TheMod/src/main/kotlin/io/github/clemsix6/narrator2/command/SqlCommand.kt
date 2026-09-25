package io.github.clemsix6.narrator2.command

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.journal.SqlAccess
import io.github.clemsix6.narrator2.journal.SqlLimits
import io.github.clemsix6.narrator2.journal.SqlOutcome
import io.github.clemsix6.narrator2.journal.SqlRunner
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component

/**
 * `/narrator sql <query>` (M1_M2 §6.6): a read-only query on the Journal for operators, through the
 * same [SqlRunner] as the model's `sql` tool, with the operator's caps ([SqlLimits.OPERATOR]: 20 rows,
 * 10 s) and access ([SqlAccess.OPERATOR]: every table, private messages and the catalog included). It
 * runs off the server thread ([replyLater]); the answer is one gray line per line of the result, or red
 * lines for an error. Each query is recorded as an `operator` decision (query and outcome, never the rows).
 */
object SqlCommand {
    private val log = Log.sql

    /** Adds this module's subcommands under `/narrator`. */
    fun attach(root: LiteralArgumentBuilder<CommandSourceStack>) {
        root.then(
            Commands.literal("sql").then(
                Commands.argument("query", StringArgumentType.greedyString())
                    .executes { run(it.source, StringArgumentType.getString(it, "query")) },
            ),
        )
    }

    private fun run(source: CommandSourceStack, query: String): Int {
        val runtime = runningNarrator(source) ?: return 0
        if (!runtime.journal.available) {
            source.sendFailure(errorLine("the Journal is unavailable (${runtime.journal.health().lastError ?: "see the server log"}): no query can run."))
            return 0
        }
        val operator = source.textName
        replyLater(source, runtime, "sql") {
            val outcome = SqlRunner(runtime.journal, runtime.clock).query(query, SqlLimits.OPERATOR, SqlAccess.OPERATOR)
            Decisions(runtime.trace, runtime.clock).record(
                log, DecisionRecord.Kind.OPERATOR, "sql",
                subject = operator,
                reason = "${summary(outcome)}: ${query.take(REASON_QUERY_CHARS)}",
                detail = buildJsonObject {
                    put("query", query.take(DETAIL_QUERY_CHARS))
                    put("outcome", summary(outcome))
                    if (outcome is SqlOutcome.Rows) {
                        put("rows", outcome.total)
                        put("elapsed_ms", outcome.elapsedMs)
                    }
                },
            )
            lines(outcome)
        }
        return 1
    }

    /** What the operator reads: the result's lines in gray, or the error in red. */
    internal fun lines(outcome: SqlOutcome): List<Component> = when (outcome) {
        is SqlOutcome.Rows -> outcome.text.lines().map(::detailLine)
        is SqlOutcome.Rejected -> listOf(errorLine("query refused: ${outcome.reason}"))
        is SqlOutcome.Failed -> outcome.message.lines().let { message ->
            listOf(errorLine("query failed: ${message.first()}")) +
                message.drop(1).filter { it.isNotBlank() }.map { Component.literal(it).withStyle(ChatFormatting.RED) }
        }
        is SqlOutcome.TimedOut -> listOf(errorLine("the query did not finish within ${outcome.seconds} s: aggregate, filter or add LIMIT."))
    }

    /** `3 rows in 12 ms`, `refused`, `failed`, `timed out`. */
    private fun summary(outcome: SqlOutcome): String = when (outcome) {
        is SqlOutcome.Rows -> "${outcome.total ?: ">10000"} rows in ${outcome.elapsedMs} ms"
        is SqlOutcome.Rejected -> "refused (${outcome.reason})"
        is SqlOutcome.Failed -> "failed (${outcome.message.lineSequence().first()})"
        is SqlOutcome.TimedOut -> "timed out after ${outcome.seconds} s"
    }

    private const val REASON_QUERY_CHARS = 200
    private const val DETAIL_QUERY_CHARS = 1_000
}
