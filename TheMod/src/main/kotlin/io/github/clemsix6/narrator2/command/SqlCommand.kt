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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * `/narrator sql <query>` (M1_M2 §6.6): a read-only query on the Journal for operators, through the
 * same [SqlRunner] as the model's `sql` tool, with the operator's caps ([SqlLimits.OPERATOR]: 20 rows,
 * 10 s) and access ([SqlAccess.OPERATOR]: every table, private messages and the catalog included). It
 * runs off the server thread ([replyLater]); the answer is one gray line per line of the result, or red
 * lines for an error.
 *
 * Each query is recorded as an `operator` decision, which the model can read in `engine_decisions`: so
 * the decision keeps neither the query nor DuckDB's error (an operator's query on the private messages,
 * or an error quoting a value, would hand them to the model), nor the row count. It keeps a fingerprint
 * of the query ([fingerprint]), its length and how it ended ([status]). The query itself goes to the
 * server log only, with its fingerprint.
 */
object SqlCommand {
    private val log = Log.sql

    /**
     * The fingerprint key, drawn at each start: a plain hash could be checked by the model against its
     * guesses with DuckDB's own `sha256()`, a keyed one cannot.
     */
    private val key = ByteArray(32).also(SecureRandom()::nextBytes)

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
            val fingerprint = fingerprint(query)
            log.info("operator query $fingerprint by $operator: ${query.replace(Regex("\\s+"), " ")}")
            val outcome = SqlRunner(runtime.journal, runtime.clock).query(query, SqlLimits.OPERATOR, SqlAccess.OPERATOR)
            Decisions(runtime.trace, runtime.clock).record(
                log, DecisionRecord.Kind.OPERATOR, "sql",
                subject = operator,
                reason = reason(query, outcome),
                detail = detail(query, outcome),
            )
            lines(outcome)
        }
        return 1
    }

    /** What an operator query leaves in `engine_decisions`: `ok, query 3f2a9c1e0b7d4a55 (57 chars)`. */
    internal fun reason(query: String, outcome: SqlOutcome): String = "${status(outcome)}, query ${fingerprint(query)} (${query.length} chars)"

    /** `{"query_hash": "3f2a9c1e0b7d4a55", "query_chars": 57, "status": "ok"}`: never the query, an error or a count. */
    internal fun detail(query: String, outcome: SqlOutcome): JsonObject = buildJsonObject {
        put("query_hash", fingerprint(query))
        put("query_chars", query.length)
        put("status", status(outcome))
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

    /** `ok`, `refused`, `failed`, `timed_out`. */
    private fun status(outcome: SqlOutcome): String = when (outcome) {
        is SqlOutcome.Rows -> "ok"
        is SqlOutcome.Rejected -> "refused"
        is SqlOutcome.Failed -> "failed"
        is SqlOutcome.TimedOut -> "timed_out"
    }

    /** 16 hex digits of the HMAC-SHA256 of [query] under [key]: the same query gives the same fingerprint until the next start. */
    private fun fingerprint(query: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        return mac.doFinal(query.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }
    }
}
