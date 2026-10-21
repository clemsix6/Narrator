package io.github.clemsix6.narrator2.storyteller

import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.memory.instant
import io.github.clemsix6.narrator2.memory.query
import io.github.clemsix6.narrator2.memory.stringArray
import io.github.clemsix6.narrator2.memory.strings
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.trace.DecisionRecord
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import java.time.Instant

/**
 * One intervention of the Narrator (M6 §5.4): the lever [key] it used at [at], aimed at [targets].
 *
 * @property key the lever's key ([Interventions.key]): `punish:tiny`, `challenge:obtain`, `duel`, `favor:joker`, `item`,
 *   `book`, `law`, `plan`, `module:tag`, `prophecy`, `effect:slowness`, `attribute:scale`, `spawn`, `teleport`, or a
 *   line's register, `say:fairy_tale`.
 * @property targets the names of the players it was aimed at, as the Journal wrote them; empty for a line said to
 *   everyone.
 */
data class Intervention(val key: String, val at: Instant, val targets: List<String>)

/** What `inspire` dealt the Director in one draw, at [at] (M6 §5.4): the keys of its levers, hooks, twists and ideas. */
data class Dealt(val at: Instant, val keys: Set<String>)

/**
 * The world's play from [from] to [now] (M3 §3.2): the spans when at least one player was online, from the sessions, in
 * epoch milliseconds. It tells how long ago something happened in play, which is how a lever rests (M6 §5.4: « 3 h de
 * jeu du monde »): a lever used last evening, two hours of play ago, is still fresh for the players.
 */
class PlayTimeline(private val spans: List<LongRange>, val now: Instant) {
    /** Seconds of world play between [at] and [now]; 0 for an instant after it. */
    fun playedSince(at: Instant): Long {
        val start = at.toEpochMilli()
        return spans.sumOf { span -> (span.last - maxOf(span.first, start)).coerceAtLeast(0) } / 1_000
    }

    companion object {
        /** No play at all: everything happened just now. */
        fun none(now: Instant) = PlayTimeline(emptyList(), now)
    }
}

/**
 * What the Narrator did lately, by lever (M6 §5.4), read from the Journal: what `inspire` leaves out, the `<history>`
 * of the storyteller's programme (M6 §6.3) and a page of the dashboard. Every function runs on the read [Connection] it
 * is given (`Journal.read`), never on the server thread.
 *
 * Sources: the Director's successful tool calls (`tool_calls`, the arguments read for the lever's sort) and the line
 * review (`line_labels`, a line's register). The engine's `mechanic started` decisions are not read: the engine starts
 * no challenge, rule or module of its own (`MechanicsService.TOOLS`), so each one follows a call of the Director already
 * counted here.
 */
object Interventions {
    /** The decision (`style`) that records what a draw of `inspire` dealt; its detail holds the [Dealt] keys. */
    const val INSPIRED = "inspired"

    /** The Director's tools that pull a lever, by name. */
    val TOOLS: Set<String> = setOf(
        "punish", "start_challenge", "propose_duel", "favor", "give_item", "give_book", "create_rule", "deploy_module",
        "apply_effect", "set_attribute", "spawn", "teleport", "remember",
    )

    /** The interventions since [from], the latest first: the Director's levers, then each reviewed line's register. */
    fun recent(connection: Connection, from: Instant): List<Intervention> {
        val calls = connection.query(CALLS_SQL, from, stringArray(TOOLS)) {
            val args = getString(3)?.let(::json)
            key(getString(2), args)?.let { Intervention(it, instant(1), strings(4)) }
        }.filterNotNull()
        val lines = connection.query(LINES_SQL, from) { Intervention("say:${getString(2)}", instant(1), strings(3)) }
        return (calls + lines).sortedByDescending { it.at }
    }

    /**
     * The key of the lever a call of [tool] with [args] pulled; null for a call that pulls none (a `remember` of
     * anything but a prophecy) or whose arguments cannot be read.
     */
    fun key(tool: String, args: JsonObject?): String? {
        fun arg(name: String): String? = (args?.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.ifEmpty { null }
        fun bare(id: String): String = id.lowercase().removePrefix("minecraft:")
        return when (tool) {
            "punish" -> arg("punishment")?.let { "punish:$it" }
            "start_challenge" -> arg("goal")?.let { "challenge:$it" }
            "propose_duel" -> DUEL
            "favor" -> arg("favor")?.let { "favor:$it" }
            "give_item" -> ITEM
            "give_book" -> BOOK
            "create_rule" -> arg("kind")?.takeIf { it == LAW || it == PLAN }
            "deploy_module" -> arg("module")?.let { "module:$it" }
            "apply_effect" -> arg("effect")?.let { "effect:${bare(it)}" }
            "set_attribute" -> arg("attribute")?.let { "attribute:$it" }
            "spawn" -> SPAWN
            "teleport" -> TELEPORT
            "remember" -> PROPHECY.takeIf { arg("kind") == PROPHECY }
            else -> null
        }
    }

    /** How a lever's [key] reads for the model: `punish tiny`, `module tag`, `named item`. */
    fun label(key: String): String = if (key == ITEM) "named item" else key.replace(':', ' ')

    /**
     * What `inspire` dealt the Director since [from], the latest first (its `style inspired` decisions; the storyteller's
     * own draws, which no player sees, are left out). A decision whose detail cannot be read is skipped.
     */
    fun dealt(connection: Connection, from: Instant): List<Dealt> =
        connection.query(DEALT_SQL, DecisionRecord.Kind.STYLE.id, INSPIRED, from) {
            val detail = getString(2)?.let(::json) ?: return@query null
            if ((detail["role"] as? JsonPrimitive)?.content != Role.DIRECTOR.id) return@query null
            val keys = runCatching { detail["keys"]?.jsonArray?.map { it.jsonPrimitive.content } }.getOrNull() ?: return@query null
            Dealt(instant(1), keys.toSet())
        }.filterNotNull()

    /**
     * The world's play from [from] to [now], from the sessions: an open session runs until [now] (the Journal closes at
     * its opening the sessions a crash left open, so an open session is one being played).
     */
    fun timeline(connection: Connection, from: Instant, now: Instant): PlayTimeline {
        val spans = connection.query(ISLANDS_SQL, from, now, now) { getLong(1)..getLong(2) }
        return PlayTimeline(spans, now)
    }

    private fun json(text: String): JsonObject? = try {
        Json.parseToJsonElement(text) as? JsonObject
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    const val DUEL = "duel"
    const val ITEM = "item"
    const val BOOK = "book"
    const val LAW = "law"
    const val PLAN = "plan"
    const val PROPHECY = "prophecy"
    const val SPAWN = "spawn"
    const val TELEPORT = "teleport"

    /** The rows read at most per source: a fortnight of a busy server. */
    private const val ROWS_MAX = 5_000

    private val CALLS_SQL = "SELECT ts, tool, CAST(args AS VARCHAR), targets FROM tool_calls " +
        "WHERE role = '${Role.DIRECTOR.id}' AND status = '${ToolStatus.OK.id}' AND ts >= ? AND tool IN (SELECT unnest(?::VARCHAR[])) " +
        "ORDER BY ts DESC LIMIT $ROWS_MAX"

    /** Each reviewed line of the Narrator once, with its latest review. */
    private const val LINES_SQL = "SELECT ts, register, recipients FROM (" +
        "SELECT DISTINCT ON (l.event_id) c.ts, l.register, c.recipients " +
        "FROM line_labels l JOIN chat c ON c.id = l.event_id " +
        "WHERE c.channel = 'narrator' AND c.ts >= ? ORDER BY l.event_id, l.ts DESC) " +
        "ORDER BY ts DESC LIMIT $ROWS_MAX"

    private const val DEALT_SQL = "SELECT ts, CAST(detail AS VARCHAR) FROM engine_decisions " +
        "WHERE kind = ? AND decision = ? AND ts >= ? ORDER BY ts DESC LIMIT $ROWS_MAX"

    /** The union of the sessions' spans between the two instants, as islands ("islands and gaps", like `DuckPlayClock`). */
    private val ISLANDS_SQL = """
        WITH spans AS (
            SELECT greatest(joined_at, ?) AS a, least(coalesce(left_at, ?), ?) AS b FROM sessions
        ), played AS (SELECT a, b FROM spans WHERE b > a),
        marked AS (
            SELECT a, b, CASE WHEN a <= max(b) OVER (ORDER BY a, b ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING)
                              THEN 0 ELSE 1 END AS starts
            FROM played
        ), islands AS (
            SELECT a, b, sum(starts) OVER (ORDER BY a, b ROWS UNBOUNDED PRECEDING) AS island FROM marked
        )
        SELECT epoch_ms(min(a)), epoch_ms(max(b)) FROM islands GROUP BY island ORDER BY 1
    """.trimIndent()
}
