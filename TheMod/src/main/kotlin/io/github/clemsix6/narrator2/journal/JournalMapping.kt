package io.github.clemsix6.narrator2.journal

import io.github.clemsix6.narrator2.event.AdvancementEarned
import io.github.clemsix6.narrator2.event.AnimalTamed
import io.github.clemsix6.narrator2.event.AnimalsBred
import io.github.clemsix6.narrator2.event.AnvilUsed
import io.github.clemsix6.narrator2.event.BiomeEntered
import io.github.clemsix6.narrator2.event.BlockBroken
import io.github.clemsix6.narrator2.event.BlockPlaced
import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.event.ChatMessage
import io.github.clemsix6.narrator2.event.CommandIssued
import io.github.clemsix6.narrator2.event.ContainerOpened
import io.github.clemsix6.narrator2.event.DamageDealt
import io.github.clemsix6.narrator2.event.DamageTaken
import io.github.clemsix6.narrator2.event.DimensionChanged
import io.github.clemsix6.narrator2.event.EndPortalActivated
import io.github.clemsix6.narrator2.event.EntityKilled
import io.github.clemsix6.narrator2.event.EntityRef
import io.github.clemsix6.narrator2.event.Envelope
import io.github.clemsix6.narrator2.event.EventCatalog
import io.github.clemsix6.narrator2.event.EventTable
import io.github.clemsix6.narrator2.event.ExplosionHappened
import io.github.clemsix6.narrator2.event.FishCaught
import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.event.GrindstoneUsed
import io.github.clemsix6.narrator2.event.HealthCritical
import io.github.clemsix6.narrator2.event.IdleChanged
import io.github.clemsix6.narrator2.event.ItemConsumed
import io.github.clemsix6.narrator2.event.ItemCount
import io.github.clemsix6.narrator2.event.ItemCrafted
import io.github.clemsix6.narrator2.event.ItemEnchanted
import io.github.clemsix6.narrator2.event.ItemsDropped
import io.github.clemsix6.narrator2.event.ItemsPickedUp
import io.github.clemsix6.narrator2.event.LootChestOpened
import io.github.clemsix6.narrator2.event.MountChanged
import io.github.clemsix6.narrator2.event.NarratorSpoke
import io.github.clemsix6.narrator2.event.PlayerDied
import io.github.clemsix6.narrator2.event.PlayerJoined
import io.github.clemsix6.narrator2.event.PlayerLeft
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.PlayerRespawned
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.event.PositionSampled
import io.github.clemsix6.narrator2.event.ProjectileHit
import io.github.clemsix6.narrator2.event.RespawnPointSet
import io.github.clemsix6.narrator2.event.SleepEnded
import io.github.clemsix6.narrator2.event.SleepStarted
import io.github.clemsix6.narrator2.event.StatAwarded
import io.github.clemsix6.narrator2.event.StatsSnapshot
import io.github.clemsix6.narrator2.event.StructureEntered
import io.github.clemsix6.narrator2.event.StructureLeft
import io.github.clemsix6.narrator2.event.Teleported
import io.github.clemsix6.narrator2.event.Traded
import io.github.clemsix6.narrator2.event.UndergroundChanged
import io.github.clemsix6.narrator2.trace.ChatSignalsRecord
import io.github.clemsix6.narrator2.trace.ConversationRecord
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.LlmCallRecord
import io.github.clemsix6.narrator2.trace.TaskRecord
import io.github.clemsix6.narrator2.trace.ToolCallRecord
import io.github.clemsix6.narrator2.trace.TurnRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The append-only tables, each written through its own appender, with their column count (a row with
 * another count is a bug, refused before it reaches the appender).
 */
internal enum class AppendTable(val sqlName: String, val columns: Int) {
    EVENTS("events", 15),
    POSITIONS("positions", 18),
    CHAT("chat", 9),
    PRIVATE_MESSAGES("private_messages", 6),
    CHAT_SIGNALS("chat_signals", 15),
    LLM_CALLS("llm_calls", 24),
    TOOL_CALLS("tool_calls", 14),
    ENGINE_DECISIONS("engine_decisions", 10),
}

/**
 * The updated tables whose rows are always written whole: a new record of a key replaces the row. The
 * writer keeps the last record of each key in a batch and writes them in bulk, with one
 * `INSERT OR REPLACE` per table (a statement costs about 1 ms in DuckDB, a row of a bulk statement
 * about 0.02 ms). Lists bound to them are `BIGINT[]`.
 *
 * @property keyColumns the leading columns that make the primary key.
 */
internal enum class ReplaceTable(val sqlName: String, val columns: Int, val keyColumns: Int) {
    STAT_TOTALS("stat_totals", 4, 2),
    TURNS("turns", 10, 1),
    CONVERSATIONS("conversations", 10, 1),
    TASKS("tasks", 11, 1),
}

/**
 * The statements that update rows in place (counters, sessions that close), prepared once by the
 * writer and run one by one, in order. Never through an appender: an update would not see the rows
 * still held by an appender.
 */
internal enum class Upsert(val sql: String) {
    /** A session opens (join). A row already there is kept. */
    SESSION_OPENED(
        "INSERT INTO sessions (session_id, player_uuid, player, joined_at, join_dim, join_x, join_y, join_z) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
    ),

    /** A session closes; the row is created when its opening was lost. */
    SESSION_CLOSED(
        "INSERT INTO sessions (session_id, player_uuid, player, joined_at, left_at, duration_s, " +
            "leave_dim, leave_x, leave_y, leave_z, end_reason) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (session_id) DO UPDATE SET left_at = excluded.left_at, duration_s = excluded.duration_s, " +
            "leave_dim = excluded.leave_dim, leave_x = excluded.leave_x, leave_y = excluded.leave_y, " +
            "leave_z = excluded.leave_z, end_reason = excluded.end_reason",
    ),

    /** A player joins: one more session, latest name and operator status. */
    PLAYER_JOINED(
        "INSERT INTO players VALUES (?, ?, ?, ?, 1, 0, ?) ON CONFLICT (player_uuid) DO UPDATE SET " +
            "name = excluded.name, last_seen = excluded.last_seen, sessions = players.sessions + 1, is_op = excluded.is_op",
    ),

    /** A player's session closed: its duration is added to the play time. */
    PLAYER_LEFT(
        "INSERT INTO players VALUES (?, ?, ?, ?, 1, ?, false) ON CONFLICT (player_uuid) DO UPDATE SET " +
            "name = excluded.name, last_seen = excluded.last_seen, playtime_s = players.playtime_s + excluded.playtime_s",
    ),

    PLAYER_NAME("INSERT INTO player_names VALUES (?, ?, ?) ON CONFLICT DO NOTHING"),
}

/**
 * One write of the writer. Values are typed for their column: `Long` BIGINT, `Int` INTEGER, `Short`
 * SMALLINT, `Byte` TINYINT, `Double` DOUBLE, `Float` REAL, `Boolean`, `String` (VARCHAR and JSON),
 * `UUID`, `Instant` (TIMESTAMPTZ) and `List` (lists).
 */
internal sealed interface Row {
    val values: List<Any?>
}

/** A row appended to an append-only table. */
internal class Append(val table: AppendTable, override val values: List<Any?>) : Row {
    init {
        require(values.size == table.columns) { "${table.sqlName} has ${table.columns} columns, got ${values.size} values" }
    }

    override fun toString() = describe(table.sqlName, values)
}

/** A whole row of a [ReplaceTable]: inserted, or replacing the row with the same key. */
internal class Replace(val table: ReplaceTable, override val values: List<Any?>) : Row {
    init {
        require(values.size == table.columns) { "${table.sqlName} has ${table.columns} columns, got ${values.size} values" }
    }

    val key: List<Any?> get() = values.subList(0, table.keyColumns)

    override fun toString() = describe(table.sqlName, values)
}

/** A statement that updates a row in place. */
internal class Execute(val upsert: Upsert, override val values: List<Any?>) : Row {
    override fun toString() = describe(upsert.name.lowercase(), values)
}

/** `table(v1, v2, …)`, cut to [ROW_LOG_CHARS] characters: how a faulty row shows in the log. */
private fun describe(what: String, values: List<Any?>): String {
    val text = values.joinToString(prefix = "$what(", postfix = ")")
    return if (text.length <= ROW_LOG_CHARS) text else text.take(ROW_LOG_CHARS) + "…(+${text.length - ROW_LOG_CHARS})"
}

private const val ROW_LOG_CHARS = 300

/**
 * Turns bus envelopes and trace records into [Row]s: which table, which columns (M1_M2 §3.2, §5.3).
 * The table of an event comes from [EventCatalog]; the generic columns of `events` follow the catalog's
 * documentation of each type, and the keys of `detail` its order (null values are left out).
 *
 * It also follows the sessions in the order of the stream: the current session of each player gives
 * the `session_id` of their `events` and `positions` rows. Writer thread only.
 */
internal class JournalMapping {
    private class OpenSession(val id: Long, val player: PlayerRef, val joinedAt: Instant, var lastAt: Instant)

    private val sessions = HashMap<UUID, OpenSession>()

    /** The rows of one published fact. */
    fun rows(envelope: Envelope): List<Row> {
        val event = envelope.event
        val ts = Instant.ofEpochMilli(envelope.ts)
        // A join starts a new session: it is not the last fact of the one before.
        if (event !is PlayerJoined) event.player?.let { player -> sessions[player.uuid]?.lastAt = ts }
        return when (EventCatalog.of(event).table) {
            EventTable.EVENTS -> when (event) {
                is PlayerJoined -> joined(envelope, event, ts)
                is PlayerLeft -> left(envelope, event, ts)
                else -> listOf(eventRow(envelope, ts, sessionOf(event.player)))
            }
            EventTable.CHAT -> listOf(chatRow(envelope, ts))
            EventTable.PRIVATE_MESSAGES -> listOf(privateRow(envelope.id, ts, event as ChatMessage))
            EventTable.POSITIONS -> listOf(positionRow(envelope, ts, event as PositionSampled))
            EventTable.STAT_TOTALS -> (event as StatsSnapshot).changed.map { (stat, value) ->
                Replace(ReplaceTable.STAT_TOTALS, listOf(event.player.uuid, stat, value, ts))
            }
        }
    }

    /** The rows of one trace record (`TraceSink`). */
    fun traceRows(record: Any): List<Row> = listOf(
        when (record) {
            is LlmCallRecord -> llmCallRow(record)
            is ToolCallRecord -> toolCallRow(record)
            is DecisionRecord -> decisionRow(record)
            is TurnRecord -> turnRow(record)
            is ConversationRecord -> conversationRow(record)
            is TaskRecord -> taskRow(record)
            is ChatSignalsRecord -> chatSignalsRow(record)
            else -> throw IllegalArgumentException("not a trace record: ${record.javaClass.name}")
        },
    )

    /**
     * Closes the sessions still open when the Journal closes (`server_stop`): players whose departure
     * was never published. Their play time runs until [at].
     */
    fun closeOpenSessions(at: Instant): List<Row> {
        val rows = sessions.values.flatMap { closeWithoutLeave(it, at, SERVER_STOP) }
        sessions.clear()
        return rows
    }

    // ── Sessions and players ────────────────────────────────────────────────────────────────────────

    private fun sessionOf(player: PlayerRef?): Long? = player?.let { sessions[it.uuid]?.id }

    private fun joined(envelope: Envelope, event: PlayerJoined, ts: Instant): List<Row> {
        val player = event.player
        val previous = sessions.put(player.uuid, OpenSession(event.sessionId, player, ts, ts))
        // A departure that never reached the Journal (lost in a full queue): that session ends at its last fact.
        val recovered = previous?.takeIf { it.id != event.sessionId }?.let { closeWithoutLeave(it, it.lastAt, RECOVERED) }.orEmpty()
        return recovered + listOf(
            eventRow(envelope, ts, event.sessionId),
            Execute(
                Upsert.SESSION_OPENED,
                listOf(event.sessionId, player.uuid, player.name, ts, event.pos.dim, event.pos.x, event.pos.y, event.pos.z),
            ),
            Execute(Upsert.PLAYER_JOINED, listOf(player.uuid, player.name, ts, ts, event.isOp)),
            Execute(Upsert.PLAYER_NAME, listOf(player.uuid, player.name, ts)),
        )
    }

    private fun left(envelope: Envelope, event: PlayerLeft, ts: Instant): List<Row> {
        val player = event.player
        val open = sessions.remove(player.uuid)
        // Another session still open: its departure was lost, it ends at its last fact.
        val stale = open?.takeIf { it.id != event.sessionId }?.let { closeWithoutLeave(it, it.lastAt, RECOVERED) }.orEmpty()
        // Only used when the session's opening row was lost.
        val joinedAt = open?.takeIf { it.id == event.sessionId }?.joinedAt ?: ts.minusSeconds(event.sessionSeconds)
        return stale + listOf(
            eventRow(envelope, ts, event.sessionId),
            Execute(
                Upsert.SESSION_CLOSED,
                listOf(
                    event.sessionId, player.uuid, player.name, joinedAt, ts, event.sessionSeconds.toIntClamped(),
                    event.pos.dim, event.pos.x, event.pos.y, event.pos.z, event.reason.id,
                ),
            ),
            Execute(Upsert.PLAYER_LEFT, listOf(player.uuid, player.name, joinedAt, ts, event.sessionSeconds)),
        )
    }

    private fun closeWithoutLeave(session: OpenSession, at: Instant, reason: String): List<Row> {
        val seconds = Duration.between(session.joinedAt, at).seconds.coerceAtLeast(0)
        val player = session.player
        return listOf(
            Execute(
                Upsert.SESSION_CLOSED,
                listOf(session.id, player.uuid, player.name, session.joinedAt, at, seconds.toIntClamped(), null, null, null, null, reason),
            ),
            Execute(Upsert.PLAYER_LEFT, listOf(player.uuid, player.name, session.joinedAt, at, seconds)),
        )
    }

    // ── Facts ───────────────────────────────────────────────────────────────────────────────────────

    /** The generic columns of an `events` row. */
    private class Generic(val subject: String? = null, val amount: Double? = null, val other: String? = null, val detail: String? = null)

    private fun eventRow(envelope: Envelope, ts: Instant, sessionId: Long?): Append {
        val event = envelope.event
        val generic = generic(event)
        val player = event.player
        val pos = event.pos
        return Append(
            AppendTable.EVENTS,
            listOf(
                envelope.id, ts, envelope.gameTime, event.type,
                player?.uuid, player?.name, sessionId,
                pos?.dim, pos?.x, pos?.y, pos?.z,
                generic.subject, generic.amount, generic.other, generic.detail,
            ),
        )
    }

    /** What `subject`, `amount`, `other` and `detail` hold for each type (see [EventCatalog]). */
    private fun generic(event: GameEvent): Generic = when (event) {
        is BlockBroken -> Generic(
            subject = event.block, other = event.tool,
            detail = detail("creative" to event.creative, "correct_tool" to event.correctTool, "placed_by" to event.placedBy),
        )
        is BlockPlaced -> Generic(subject = event.block, other = event.item)
        is EndPortalActivated -> Generic()
        is ContainerOpened -> Generic(
            subject = event.menu, other = event.target,
            detail = detail("title" to event.title, "spectator" to event.spectator),
        )
        is LootChestOpened -> Generic(subject = event.lootTable)
        is ExplosionHappened -> Generic(
            subject = event.source, amount = event.radius, other = event.author?.label(),
            detail = detail(
                "interaction" to event.interaction,
                "blocks_hit" to event.blocksHit,
                "player_blocks_hit" to event.playerBlocksHit,
                "nearby" to event.nearby.map { it.name },
                "narrator" to event.narrator,
            ),
        )
        is DamageTaken -> Generic(
            subject = event.damageType, amount = event.amount, other = event.attacker?.label(),
            detail = detail("hits" to event.hits, "blocked" to event.blocked, "health_after" to event.healthAfter),
        )
        is DamageDealt -> Generic(
            subject = event.victim.type, amount = event.amount, other = event.weapon,
            detail = detail("damage_type" to event.damageType, "victim_name" to event.victim.name, "victim_health" to event.victimHealth),
        )
        is PlayerDied -> Generic(
            subject = event.cause, amount = event.xpLevel.toDouble(), other = event.killer?.label(),
            detail = detail(
                "message" to event.message,
                "weapon" to event.weapon,
                "kept_inventory" to event.keptInventory,
                "narrator" to event.narrator,
            ),
        )
        is EntityKilled -> Generic(
            subject = event.victim.type, other = event.weapon,
            detail = detail("pvp" to event.pvp, "victim_name" to event.victim.name, "tamed" to event.tamed),
        )
        is ProjectileHit -> Generic(
            subject = event.projectile, other = event.target?.label(),
            detail = detail("shooter" to event.shooter?.label(), "block" to event.block, "weapon" to event.weapon),
        )
        is ItemConsumed -> Generic(
            subject = event.item, amount = event.countBefore.toDouble(),
            detail = detail(
                "nutrition" to event.nutrition,
                "saturation" to event.saturation,
                "effects" to event.effects,
                "food_before" to event.foodBefore,
                "health_before" to event.healthBefore,
            ),
        )
        is ItemCrafted -> Generic(subject = event.item, amount = event.count.toDouble(), other = event.station)
        is AnvilUsed -> Generic(
            subject = event.result, amount = event.cost.toDouble(), other = event.left,
            detail = detail("right" to event.right, "renamed_to" to event.renamedTo),
        )
        is GrindstoneUsed -> Generic(subject = event.result)
        is ItemEnchanted -> Generic(subject = event.item, amount = event.cost.toDouble(), detail = detail("enchantments" to event.enchantments))
        is Traded -> Generic(
            subject = event.result.item, amount = event.result.count.toDouble(), other = event.profession,
            detail = detail(
                "cost_a" to event.costA,
                "cost_b" to event.costB,
                "level" to event.level,
                "uses" to event.uses,
                "max_uses" to event.maxUses,
                "villager" to event.villager.uuid,
            ),
        )
        is ItemsPickedUp -> Generic(subject = event.item, amount = event.count.toDouble(), detail = detail("window_s" to event.windowSeconds))
        is ItemsDropped -> Generic(subject = event.item, amount = event.count.toDouble())
        is FishCaught -> Generic(
            subject = event.loot.firstOrNull()?.item, amount = event.loot.sumOf { it.count }.toDouble(), other = event.hooked?.label(),
            detail = detail("loot" to event.loot),
        )
        is AnimalsBred -> Generic(subject = event.animal)
        is AnimalTamed -> Generic(subject = event.animal, detail = detail("name" to event.name))
        is StatAwarded -> Generic(subject = event.stat, amount = event.amount.toDouble())
        is AdvancementEarned -> Generic(subject = event.advancement, other = event.frame, detail = detail("title" to event.title))
        is DimensionChanged -> Generic(
            subject = event.pos.dim, other = event.from.dim,
            detail = detail("via" to event.via, "from" to event.from),
        )
        is Teleported -> Generic(amount = event.distance, detail = detail("from" to event.from))
        is SleepStarted -> Generic(subject = event.bed)
        is SleepEnded -> Generic(detail = detail("night_skipped" to event.nightSkipped))
        is RespawnPointSet -> Generic(
            subject = event.point?.dim,
            detail = detail("forced" to event.forced, "cleared" to event.cleared, "point" to event.point),
        )
        is MountChanged -> Generic(subject = event.vehicle.type, detail = detail("vehicle_uuid" to event.vehicle.uuid))
        is BiomeEntered -> Generic(subject = event.biome, detail = detail("first_visit" to event.firstVisit))
        is StructureEntered -> Generic(subject = event.structure, detail = detail("first_visit" to event.firstVisit))
        is StructureLeft -> Generic(subject = event.structure)
        is UndergroundChanged -> Generic(amount = event.pos.y.toDouble())
        is HealthCritical -> Generic(amount = event.health)
        is IdleChanged -> Generic(amount = event.idleSeconds.toDouble())
        is PlayerJoined -> Generic(detail = detail("first_time" to event.firstTime))
        is PlayerLeft -> Generic(amount = event.sessionSeconds.toDouble(), other = event.reason.id)
        is PlayerRespawned -> Generic(detail = detail("alive" to event.alive))
        is CommandIssued -> Generic(subject = event.root, detail = detail("text" to event.text))
        is ChatMessage, is NarratorSpoke, is PositionSampled, is StatsSnapshot ->
            throw IllegalArgumentException("${event.type} has its own table")
    }

    private fun chatRow(envelope: Envelope, ts: Instant): Append = when (val event = envelope.event) {
        is ChatMessage -> Append(
            AppendTable.CHAT,
            listOf(
                envelope.id, ts, event.channel.id, event.player.uuid, event.player.name,
                event.recipients.ifEmpty { null }, event.text, null, null,
            ),
        )
        is NarratorSpoke -> Append(
            AppendTable.CHAT,
            listOf(envelope.id, ts, event.channel.id, null, null, event.recipients?.map { it.name }, event.text, event.turnId, event.operator),
        )
        else -> throw IllegalArgumentException("${event.type} does not go to chat")
    }

    private fun privateRow(id: Long, ts: Instant, event: ChatMessage): Append {
        require(event.channel == ChatChannel.WHISPER) { "only private messages go to private_messages" }
        return Append(AppendTable.PRIVATE_MESSAGES, listOf(id, ts, event.player.uuid, event.player.name, event.recipients, event.text))
    }

    private fun positionRow(envelope: Envelope, ts: Instant, event: PositionSampled): Append = Append(
        AppendTable.POSITIONS,
        listOf(
            ts, envelope.gameTime, event.player.uuid, event.player.name, sessionOf(event.player),
            event.pos.dim, event.pos.x, event.pos.y, event.pos.z,
            event.biome, event.structure, event.underground,
            event.skyLight?.toTinyInt(), event.blockLight?.toTinyInt(),
            event.health.toFloat(), event.food.toTinyInt(), event.vehicle, event.reason.id,
        ),
    )

    // ── Traces ──────────────────────────────────────────────────────────────────────────────────────

    private fun llmCallRow(r: LlmCallRecord) = Append(
        AppendTable.LLM_CALLS,
        listOf(
            r.id, r.provider.id, r.purpose.id, r.conversationId, r.turnId, r.role,
            r.model, r.effort, r.startedAt, r.endedAt,
            r.latencyMs, r.ttftMs, r.attempts.toSmallInt(),
            r.requestId, r.messageId,
            r.inputTokens, r.cacheReadTokens, r.cacheWriteTokens, r.outputTokens,
            r.stopReason, r.refusalCategory, r.error, r.httpStatus?.toSmallInt(),
            r.thinking?.take(LlmCallRecord.THINKING_MAX_CHARS),
        ),
    )

    private fun toolCallRow(r: ToolCallRecord) = Append(
        AppendTable.TOOL_CALLS,
        listOf(
            r.id, r.ts, r.conversationId, r.turnId, r.llmCallId, r.toolUseId, r.role.id, r.tool,
            r.args?.let(::jsonOrString), r.status.id, r.result?.take(ToolCallRecord.RESULT_MAX_CHARS),
            r.durationMs, r.targets, r.effect.id,
        ),
    )

    private fun decisionRow(r: DecisionRecord) = Append(
        AppendTable.ENGINE_DECISIONS,
        listOf(r.id, r.ts, r.kind.id, r.decision, r.subject, r.reason, r.eventId, r.conversationId, r.turnId, r.detail?.toString()),
    )

    private fun chatSignalsRow(r: ChatSignalsRecord) = Append(
        AppendTable.CHAT_SIGNALS,
        listOf(
            r.eventId, r.ts, r.playerUuid, r.source, r.toNarrator, r.tone?.id, r.toneConfidence, r.intent?.id, r.manipulation,
            r.politeness, r.boastfulness, r.helpfulness, r.aggressiveness, r.humor, r.latencyMs,
        ),
    )

    private fun turnRow(r: TurnRecord) = Replace(
        ReplaceTable.TURNS,
        listOf(
            r.turnId, r.conversationId, r.startedAt, r.endedAt, r.trigger.id, r.triggerEvents, r.injectedEvents,
            r.llmCalls, r.toolCalls, r.outcome?.id,
        ),
    )

    private fun conversationRow(r: ConversationRecord) = Replace(
        ReplaceTable.CONVERSATIONS,
        listOf(
            r.conversationId, r.role.id, r.label, r.chapter, r.taskId, r.startedAt, r.endedAt, r.endReason?.id,
            r.summary, r.transcript,
        ),
    )

    private fun taskRow(r: TaskRecord) = Replace(
        ReplaceTable.TASKS,
        listOf(
            r.taskId, r.role.id, r.brief, r.status.id, r.createdAt, r.startedAt, r.finishedAt, r.requestedByTurn,
            r.conversationId, r.result, r.error,
        ),
    )

    companion object {
        /** `sessions.end_reason` of a session found open after a crash, or whose departure was lost. */
        const val RECOVERED = "recovered"

        /** `sessions.end_reason` of a session still open when the Journal closes. */
        const val SERVER_STOP = "server_stop"

        /**
         * A compact JSON object of the non-null [entries], in their order; null when all are null.
         * Values: strings, numbers, booleans, UUIDs (as strings), [Pos] (`{dim, x, y, z}`), [ItemCount]
         * (`{item, count}`), lists and maps of those.
         */
        fun detail(vararg entries: Pair<String, Any?>): String? {
            val present = entries.filter { it.second != null }
            if (present.isEmpty()) return null
            return JsonObject(present.associate { (key, value) -> key to json(value) }).toString()
        }

        private fun json(value: Any?): JsonElement = when (value) {
            null -> JsonNull
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is UUID -> JsonPrimitive(value.toString())
            is Pos -> JsonObject(
                mapOf("dim" to JsonPrimitive(value.dim), "x" to JsonPrimitive(value.x), "y" to JsonPrimitive(value.y), "z" to JsonPrimitive(value.z)),
            )
            is ItemCount -> JsonObject(mapOf("item" to JsonPrimitive(value.item), "count" to JsonPrimitive(value.count)))
            is List<*> -> JsonArray(value.map(::json))
            is Map<*, *> -> JsonObject(value.entries.associate { (key, item) -> key.toString() to json(item) })
            else -> throw IllegalArgumentException("no JSON form for ${value.javaClass.name}")
        }

        /** [text] if it is valid JSON, else [text] as a JSON string: the column stays queryable. */
        fun jsonOrString(text: String): String = try {
            Json.parseToJsonElement(text)
            text
        } catch (_: IllegalArgumentException) {
            JsonPrimitive(text).toString()
        }

        /** An entity in the generic columns: the player's name for a player, else its entity type id. */
        private fun EntityRef.label(): String = player?.name ?: type

        private fun Int.toTinyInt(): Byte = coerceIn(Byte.MIN_VALUE.toInt(), Byte.MAX_VALUE.toInt()).toByte()

        private fun Int.toSmallInt(): Short = coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

        private fun Long.toIntClamped(): Int = coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }
}
