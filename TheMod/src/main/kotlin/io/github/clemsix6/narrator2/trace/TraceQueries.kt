package io.github.clemsix6.narrator2.trace

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.math.roundToLong

/**
 * The reads behind `/narrator trace`, `why` and `usage`, and the consumption line of `/narrator status`
 * (M1_M2 §14.4): what the Director was told, what it thought, what it did and said, turn by turn, from
 * the trace tables (`turns`, `llm_calls`, `tool_calls`, `engine_decisions`, `conversations`, `tasks`)
 * and the chat.
 *
 * Runs on a read connection of the Journal, after `journal.barrier()` so that what just happened is
 * there: `journal.read(ms) { TraceQueries(it).turn(42) }`. Read-only; every statement has its own
 * timeout and closes its result set. Operators only: private messages are never read here.
 */
class TraceQueries(private val connection: Connection, private val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS) {

    /** The [limit] most recent Director turns, newest first. */
    fun lastTurns(limit: Int): List<TurnSummary> = rows("$TURN_SUMMARY ORDER BY t.turn_id DESC LIMIT ?", limit) { turnSummary() }

    /** One Director turn in full, or null when there is no such turn. */
    fun turn(turnId: Long): TurnDetail? {
        val summary = turnSummary(turnId) ?: return null
        val told = rows(TOLD_EVENTS, turnId, turnId) { getString(1) to toldEvent(2) }
        val tools = rows("$TOOL_USES WHERE turn_id = ? ORDER BY id", turnId) { toolUse() }
        val calls = rows("$MODEL_CALLS WHERE turn_id = ? AND provider = 'anthropic' ORDER BY id", turnId) { modelCall() }
        return TurnDetail(
            summary = summary,
            woken = told.filter { it.first == WAKE }.map { it.second },
            injected = told.filter { it.first == INJECTED }.map { it.second },
            calls = calls.map { call -> call.copy(tools = tools.filter { it.llmCallId == call.id }) },
            unmatchedTools = tools.filter { tool -> calls.none { it.id == tool.llmCallId } },
            said = rows(
                "SELECT ts, channel, array_to_string(recipients, ','), text, operator FROM chat WHERE turn_id = ? ORDER BY id",
                turnId,
            ) { SaidLine(instant(1)!!, getString(2), names(3), getString(4), getString(5)) },
            decisions = rows(
                "SELECT ts, kind, decision, subject, reason FROM engine_decisions WHERE turn_id = ? AND kind <> 'wake' ORDER BY id",
                turnId,
            ) { decisionLine(1) },
            transcript = single("SELECT transcript FROM conversations WHERE conversation_id = ?", summary.conversationId) { getString(1) },
        )
    }

    /** The latest Director turn that acted: a speech or world tool that succeeded (M1_M2 §14.4); null when none did. */
    fun lastActingTurnId(): Long? = single(
        "SELECT max(turn_id) FROM tool_calls WHERE role = 'director' AND status = 'ok' AND effect IN ('speech', 'world')",
    ) { getLong(1).takeUnless { wasNull() } }

    /** One task of a worker in full, or null when there is no such task. */
    fun task(taskId: Long): TaskDetail? {
        val task = single(
            """
            SELECT t.task_id, t.role, t.brief, t.status, t.created_at, t.started_at, t.finished_at, t.requested_by_turn,
                   t.conversation_id, t.result, t.error, c.transcript
            FROM tasks t LEFT JOIN conversations c ON c.conversation_id = t.conversation_id
            WHERE t.task_id = ?
            """.trimIndent(),
            taskId,
        ) {
            TaskDetail(
                taskId = getLong(1), role = getString(2), brief = getString(3), status = getString(4),
                createdAt = instant(5)!!, startedAt = instant(6), finishedAt = instant(7), requestedByTurn = long(8),
                conversationId = getString(9), result = getString(10), error = getString(11), transcript = getString(12),
                calls = emptyList(),
            )
        } ?: return null
        val conversation = task.conversationId ?: return task
        val tools = rows("$TOOL_USES WHERE conversation_id = ? ORDER BY id", conversation) { toolUse() }
        val calls = rows("$MODEL_CALLS WHERE conversation_id = ? ORDER BY id", conversation) { modelCall() }
        return task.copy(calls = calls.map { call -> call.copy(tools = tools.filter { it.llmCallId == call.id }) })
    }

    /**
     * What became of [player]'s latest public message or prayer (name ignoring case): how the reflexes
     * read it, where the salience sent it, and the turn that told it to the Director, if any. Null when
     * the player never wrote.
     */
    fun lastMessage(player: String): MessageFate? {
        val message = single(
            """
            SELECT id, ts, 'chat:' || channel, player, text, NULL FROM chat
            WHERE channel IN ('public', 'pray') AND lower(player) = lower(?) ORDER BY id DESC LIMIT 1
            """.trimIndent(),
            player,
        ) { toldEvent(1) } ?: return null
        val triage = single(
            "SELECT to_narrator, tone, intent, manipulation, source FROM chat_signals WHERE event_id = ? LIMIT 1",
            message.id,
        ) { Triage(double(1), getString(2), getString(3), double(4), getString(5)) }
        val decisions = rows(
            "SELECT ts, kind, decision, subject, reason FROM engine_decisions WHERE event_id = ? AND kind IN ('salience', 'refusal') ORDER BY id",
            message.id,
        ) { decisionLine(1) }
        val told = single(
            """
            SELECT turn_id, list_contains(trigger_events, ?) FROM turns
            WHERE list_contains(trigger_events, ?) OR list_contains(injected_events, ?) ORDER BY turn_id LIMIT 1
            """.trimIndent(),
            message.id, message.id, message.id,
        ) { getLong(1) to getBoolean(2) }
        return MessageFate(
            message = message.copy(salience = decisions.firstOrNull { it.kind == DecisionRecord.Kind.SALIENCE.id }?.let { "${it.decision}: ${it.reason}" }),
            triage = triage,
            turn = told?.let { turnSummary(it.first) },
            atWake = told?.second ?: false,
            dropped = decisions.firstOrNull { it.kind == DecisionRecord.Kind.REFUSAL.id && it.decision == "dropped" },
        )
    }

    /**
     * [player]'s latest death as the death guard explained it (name ignoring case): its `death_guard died`
     * decision, whose `detail` holds the whole report (M2 review, finding 18). Null when none is known.
     */
    fun lastDeath(player: String): DeathLine? = single(
        """
        SELECT ts, subject, reason, CAST(detail AS VARCHAR) FROM engine_decisions
        WHERE kind = 'death_guard' AND decision = 'died' AND lower(subject) = lower(?) ORDER BY id DESC LIMIT 1
        """.trimIndent(),
        player,
    ) { DeathLine(instant(1)!!, getString(2), getString(3), getString(4)) }

    /**
     * Model calls since [since] (epoch milliseconds), per provider and role, the busiest first, each with its calls by
     * the model that answered ([Usage.models]): what `/narrator usage`, the status line and the dashboard's overview
     * count and price ([Prices.costOf]). One statement, summed by provider, role and model.
     */
    fun usage(since: Long): List<Usage> =
        rows(USAGE, since) { ModelUsage(getString(1), getString(2), modelTokens(3), getLong(10)) }
            .groupBy { it.provider to it.role }
            .map { (key, rows) ->
                val all = ModelTokens.sum(key.second, rows.map { it.tokens })
                Usage(
                    provider = key.first, role = key.second, calls = all.calls.toInt(), failed = all.failed.toInt(),
                    inputTokens = all.input, cacheReadTokens = all.cacheRead, cacheWriteTokens = all.cacheWrite, outputTokens = all.output,
                    averageLatencyMs = (rows.sumOf { it.latencyMs }.toDouble() / all.calls).roundToLong(), models = rows.map { it.tokens },
                )
            }
            .sortedWith(compareBy<Usage> { it.provider }.thenByDescending { it.calls }.thenBy { it.role })

    /** A row of [USAGE]: the calls of one provider, role and model, and the sum of their latencies. */
    private class ModelUsage(val provider: String, val role: String, val tokens: ModelTokens, val latencyMs: Long)

    // ── Rows ────────────────────────────────────────────────────────────────────────────────────────

    private fun turnSummary(turnId: Long): TurnSummary? = single("$TURN_SUMMARY WHERE t.turn_id = ?", turnId) { turnSummary() }

    /** From column [from]: id, ts, kind, player, what, where. */
    private fun ResultSet.toldEvent(from: Int) = ToldEvent(
        id = getLong(from),
        ts = instant(from + 1)!!,
        kind = getString(from + 2),
        player = getString(from + 3),
        what = getString(from + 4),
        where = getString(from + 5),
        salience = if (metaData.columnCount >= from + 6) getString(from + 6) else null,
    )

    private fun ResultSet.toolUse() = ToolUse(
        id = getLong(1), llmCallId = long(2), tool = getString(3), args = getString(4), status = getString(5), result = getString(6),
        durationMs = getInt(7), targets = names(8).orEmpty(), effect = getString(9),
    )

    private fun ResultSet.modelCall() = ModelCall(
        id = getLong(1), startedAt = instant(2)!!, latencyMs = getInt(3), stopReason = getString(4), refusalCategory = getString(5),
        error = getString(6), inputTokens = int(7), cacheReadTokens = int(8), cacheWriteTokens = int(9), outputTokens = int(10),
        thinking = getString(11), purpose = getString(12), model = getString(13), tools = emptyList(),
    )

    private fun ResultSet.decisionLine(from: Int) = DecisionLine(
        ts = instant(from)!!, kind = getString(from + 1), decision = getString(from + 2), subject = getString(from + 3), reason = getString(from + 4),
    )

    // ── JDBC ────────────────────────────────────────────────────────────────────────────────────────

    private fun <T> rows(sql: String, vararg params: Any, row: ResultSet.() -> T): List<T> =
        prepare(sql, params).use { statement ->
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.row()) } }
        }

    private fun <T> single(sql: String, vararg params: Any, row: ResultSet.() -> T): T? =
        prepare(sql, params).use { statement ->
            statement.executeQuery().use { rows -> if (rows.next()) rows.row() else null }
        }

    private fun prepare(sql: String, params: Array<out Any>): PreparedStatement {
        val statement = connection.prepareStatement(sql)
        try {
            statement.queryTimeout = timeoutSeconds
            params.forEachIndexed { i, value ->
                when (value) {
                    is Long -> statement.setLong(i + 1, value)
                    is Int -> statement.setInt(i + 1, value)
                    is String -> statement.setString(i + 1, value)
                    else -> error("unsupported parameter type ${value.javaClass}")
                }
            }
        } catch (e: Exception) {
            statement.close()
            throw e
        }
        return statement
    }

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 5

        private const val WAKE = "wake"
        private const val INJECTED = "injected"

        /**
         * The sums of a group of `llm_calls` that [modelTokens] reads after a model column: calls, failed, input, cache
         * read, cache write and output tokens.
         */
        internal const val TOKEN_SUMS = "count(*), count(error), coalesce(sum(input_tokens), 0), coalesce(sum(cache_read_tokens), 0), " +
            "coalesce(sum(cache_write_tokens), 0), coalesce(sum(output_tokens), 0)"

        /** Since a time in epoch milliseconds, the parameter. */
        private const val SINCE = "to_timestamp(CAST(? AS DOUBLE) / 1000)"

        /** The calls since a time by provider, role and model ([TOKEN_SUMS]), and the sum of their latencies. */
        private const val USAGE = "SELECT provider, role, model, $TOKEN_SUMS, coalesce(sum(latency_ms), 0) " +
            "FROM llm_calls WHERE started_at >= $SINCE GROUP BY provider, role, model ORDER BY provider, role, model"

        /**
         * Columns of [TurnSummary], from `turns t` ([turnSummary] reads them); the counts come from the calls themselves,
         * so a running turn has them too. The dashboard's list of turns adds its filters to it.
         */
        internal val TURN_SUMMARY = """
            SELECT t.turn_id, t.conversation_id, c.chapter, t.started_at, t.ended_at, t.trigger, t.outcome,
                   (SELECT d.reason FROM engine_decisions d WHERE d.kind = 'wake' AND d.turn_id = t.turn_id ORDER BY d.id LIMIT 1),
                   (SELECT string_agg(u.tool, ',' ORDER BY u.id) FROM tool_calls u WHERE u.turn_id = t.turn_id),
                   (SELECT coalesce(l.input_tokens, 0) + coalesce(l.cache_read_tokens, 0) + coalesce(l.cache_write_tokens, 0)
                    FROM llm_calls l WHERE l.turn_id = t.turn_id AND l.provider = 'anthropic' AND l.input_tokens IS NOT NULL
                    ORDER BY l.id DESC LIMIT 1),
                   (SELECT count(*) FROM llm_calls l WHERE l.turn_id = t.turn_id AND l.provider = 'anthropic')
            FROM turns t LEFT JOIN conversations c ON c.conversation_id = t.conversation_id
        """.trimIndent()

        /** The events of a turn: phase, then id, ts, kind, player, what, where, salience. Parameters: the turn twice. */
        private val TOLD_EVENTS = """
            WITH told AS (
                SELECT '$WAKE' AS phase, unnest(trigger_events) AS id FROM turns WHERE turn_id = ?
                UNION ALL
                SELECT '$INJECTED', unnest(injected_events) FROM turns WHERE turn_id = ?
            ),
            lines AS (
                SELECT told.phase, e.id, e.ts, e.type AS kind, e.player,
                       CASE
                           WHEN e.subject IS NULL THEN bare(e.other)
                           WHEN e.other IS NULL THEN bare(e.subject)
                           ELSE bare(e.subject) || ' → ' || bare(e.other)
                       END AS what,
                       CASE WHEN e.dim IS NULL THEN NULL ELSE concat_ws(' ', bare(e.dim), e.x, e.y, e.z) END AS place
                FROM told JOIN events e ON e.id = told.id
                UNION ALL
                SELECT told.phase, c.id, c.ts, 'chat:' || c.channel, coalesce(c.player, 'NARRATOR'), c.text, NULL
                FROM told JOIN chat c ON c.id = told.id
            )
            SELECT phase, id, ts, kind, player, what, place,
                   (SELECT d.decision || ': ' || d.reason FROM engine_decisions d
                    WHERE d.kind = 'salience' AND d.event_id = lines.id ORDER BY d.id LIMIT 1)
            FROM lines ORDER BY phase DESC, ts, id
        """.trimIndent()

        private const val TOOL_USES =
            "SELECT id, llm_call_id, tool, CAST(args AS VARCHAR), status, result, duration_ms, array_to_string(targets, ','), effect FROM tool_calls"

        private const val MODEL_CALLS =
            "SELECT id, started_at, latency_ms, stop_reason, refusal_category, error, input_tokens, cache_read_tokens, " +
                "cache_write_tokens, output_tokens, thinking, purpose, model FROM llm_calls"
    }
}

/** The [TurnSummary] of a row of [TraceQueries.TURN_SUMMARY]. */
internal fun ResultSet.turnSummary() = TurnSummary(
    turnId = getLong(1),
    conversationId = getString(2),
    chapter = int(3),
    startedAt = instant(4)!!,
    endedAt = instant(5),
    trigger = getString(6),
    outcome = getString(7),
    woke = getString(8),
    tools = getString(9)?.split(',').orEmpty(),
    contextTokens = long(10),
    llmCalls = getInt(11),
)

/** The [ModelTokens] of a row: the model at column [from], then the sums of [TraceQueries.TOKEN_SUMS]. */
internal fun ResultSet.modelTokens(from: Int) = ModelTokens(
    model = getString(from), calls = getLong(from + 1), failed = getLong(from + 2), input = getLong(from + 3),
    cacheRead = getLong(from + 4), cacheWrite = getLong(from + 5), output = getLong(from + 6),
)

private fun ResultSet.instant(column: Int): Instant? = when (val value = getObject(column)) {
    null -> null
    is OffsetDateTime -> value.toInstant()
    is Timestamp -> value.toInstant()
    is Instant -> value
    else -> error("column $column is not a timestamp: ${value.javaClass}")
}

private fun ResultSet.long(column: Int): Long? = getLong(column).takeUnless { wasNull() }

private fun ResultSet.int(column: Int): Int? = getInt(column).takeUnless { wasNull() }

private fun ResultSet.double(column: Int): Double? = getDouble(column).takeUnless { wasNull() }

/** A list of names written by `array_to_string(…, ',')`; null for a NULL list (everyone). */
private fun ResultSet.names(column: Int): List<String>? = getString(column)?.split(',')?.filter { it.isNotEmpty() }

/**
 * One Director turn, in short.
 *
 * @property woke what woke the Director, from its `wake` decision: `chat Bartok + 2 items`.
 * @property outcome null while the turn runs.
 * @property tools the tools it called, in order.
 * @property contextTokens context size of its last answered request: input, cache read and cache write tokens.
 * @property llmCalls requests sent to Claude.
 */
data class TurnSummary(
    val turnId: Long,
    val conversationId: String,
    val chapter: Int?,
    val startedAt: Instant,
    val endedAt: Instant?,
    val trigger: String,
    val outcome: String?,
    val woke: String?,
    val tools: List<String>,
    val contextTokens: Long?,
    val llmCalls: Int,
)

/**
 * An event or chat line told to the Director.
 *
 * @property kind the event type (`death`), or `chat:<channel>` for a chat line.
 * @property what subject, `subject → other`, or the text of a chat line.
 * @property where `overworld 1 64 3`, when the event has a place.
 * @property salience how the salience classified it: `immediate: addressed: …`.
 */
data class ToldEvent(
    val id: Long,
    val ts: Instant,
    val kind: String,
    val player: String?,
    val what: String?,
    val where: String?,
    val salience: String?,
)

/** One tool call. [targets] are the players it affected. */
data class ToolUse(
    val id: Long,
    val llmCallId: Long?,
    val tool: String,
    val args: String?,
    val status: String,
    val result: String?,
    val durationMs: Int,
    val targets: List<String>,
    val effect: String,
)

/**
 * One request to Claude, with its thinking summary and the tool calls its reply asked for.
 *
 * @property model the model that answered (`llm_calls.model`): a fallback model when a safety classifier handed the
 *   request over, the call then with a [refusalCategory] and a usable [stopReason] (`CallStats`).
 */
data class ModelCall(
    val id: Long,
    val startedAt: Instant,
    val latencyMs: Int,
    val stopReason: String?,
    val refusalCategory: String?,
    val error: String?,
    val inputTokens: Int?,
    val cacheReadTokens: Int?,
    val cacheWriteTokens: Int?,
    val outputTokens: Int?,
    val thinking: String?,
    val purpose: String,
    val model: String,
    val tools: List<ToolUse>,
)

/** A line the Narrator said; [recipients] null when it went to everyone; [operator] for `/narrator say`. */
data class SaidLine(val ts: Instant, val channel: String, val recipients: List<String>?, val text: String, val operator: String?)

/** An engine decision. */
data class DecisionLine(val ts: Instant, val kind: String, val decision: String, val subject: String?, val reason: String?)

/**
 * A whole Director turn.
 *
 * @property woken what the wake-up message told; [injected] what came between two tool calls.
 * @property unmatchedTools tool calls whose request row is missing (lost by the Journal).
 * @property decisions the engine's decisions during the turn (guards, refusals, backoff…), the wake-up excepted.
 * @property transcript the chapter's Markdown transcript, relative to `world/narrator2/`.
 */
data class TurnDetail(
    val summary: TurnSummary,
    val woken: List<ToldEvent>,
    val injected: List<ToldEvent>,
    val calls: List<ModelCall>,
    val unmatchedTools: List<ToolUse>,
    val said: List<SaidLine>,
    val decisions: List<DecisionLine>,
    val transcript: String?,
)

/** A worker's task, with the requests of its conversation. */
data class TaskDetail(
    val taskId: Long,
    val role: String,
    val brief: String,
    val status: String,
    val createdAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val requestedByTurn: Long?,
    val conversationId: String?,
    val result: String?,
    val error: String?,
    val transcript: String?,
    val calls: List<ModelCall>,
)

/** The reflexes' reading of a chat message (`chat_signals`). */
data class Triage(val toNarrator: Double?, val tone: String?, val intent: String?, val manipulation: Double?, val source: String)

/**
 * What became of a player's message.
 *
 * @property message the message; its [ToldEvent.salience] says how it was classified.
 * @property turn the turn that told it to the Director; null when none did (yet).
 * @property atWake told in the wake-up message rather than between two tool calls.
 * @property dropped the decision that dropped it after requests that got no usable answer.
 */
data class MessageFate(
    val message: ToldEvent,
    val triage: Triage?,
    val turn: TurnSummary?,
    val atWake: Boolean,
    val dropped: DecisionLine?,
)

/** A player's death as the death guard explained it: its decision's time, subject, reason and `detail` (JSON text). */
data class DeathLine(val ts: Instant, val player: String, val reason: String?, val detail: String?)

/**
 * Model calls of one provider (`anthropic`) and role over a period; [failed] ended with an error.
 *
 * @property models the same calls by the model that answered, a fallback model included: what they cost
 *   ([Prices.costOf]).
 */
data class Usage(
    val provider: String,
    val role: String,
    val calls: Int,
    val failed: Int,
    val inputTokens: Long,
    val cacheReadTokens: Long,
    val cacheWriteTokens: Long,
    val outputTokens: Long,
    val averageLatencyMs: Long,
    val models: List<ModelTokens> = emptyList(),
)
