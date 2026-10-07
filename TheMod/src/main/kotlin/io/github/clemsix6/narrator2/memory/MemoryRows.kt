package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/*
 * The SQL plumbing of the store (M3-03): binding, reading the memory's rows back, the JSON snapshots of
 * the audit. Every function takes the connection it runs on: the writer's inside a `Journal.write` job,
 * a read connection otherwise. Each statement has its own timeout and closes its result set.
 */

/** Seconds a statement of the store may run. */
internal const val STATEMENT_TIMEOUT_SECONDS = 10

/** A list bound as a typed SQL array: a Kotlin list does not know its element type, so it is said here. */
internal class SqlArray(val type: String, val values: List<Any>)

internal fun uuidArray(values: Collection<UUID>) = SqlArray("UUID", values.toList())

internal fun longArray(values: Collection<Long>) = SqlArray("BIGINT", values.toList())

internal fun stringArray(values: Collection<String>) = SqlArray("VARCHAR", values.toList())

private fun Connection.prepare(sql: String, parameters: Array<out Any?>): PreparedStatement {
    val statement = prepareStatement(sql)
    try {
        statement.queryTimeout = STATEMENT_TIMEOUT_SECONDS
        parameters.forEachIndexed { index, value ->
            val parameter = index + 1
            when (value) {
                null -> statement.setObject(parameter, null)
                is Instant -> statement.setObject(parameter, OffsetDateTime.ofInstant(value, ZoneOffset.UTC))
                is SqlArray -> statement.setArray(parameter, createArrayOf(value.type, value.values.toTypedArray()))
                else -> statement.setObject(parameter, value)
            }
        }
    } catch (e: Exception) {
        statement.close()
        throw e
    }
    return statement
}

/** Every row of [sql] with [parameters], each read by [row]. */
internal fun <T> Connection.query(sql: String, vararg parameters: Any?, row: ResultSet.() -> T): List<T> =
    prepare(sql, parameters).use { statement ->
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.row()) } }
    }

/** Runs [sql] with [parameters]; returns the number of rows changed. */
internal fun Connection.update(sql: String, vararg parameters: Any?): Int = prepare(sql, parameters).use { it.executeUpdate() }

internal fun ResultSet.instantOrNull(column: Int): Instant? = when (val value = getObject(column)) {
    null -> null
    is OffsetDateTime -> value.toInstant()
    is Timestamp -> value.toInstant()
    is Instant -> value
    else -> error("column $column is not a timestamp: ${value.javaClass}")
}

internal fun ResultSet.instant(column: Int): Instant = instantOrNull(column) ?: error("column $column is null")

internal fun ResultSet.longOrNull(column: Int): Long? = getLong(column).takeUnless { wasNull() }

internal fun ResultSet.intOrNull(column: Int): Int? = getInt(column).takeUnless { wasNull() }

private fun ResultSet.list(column: Int): List<Any> = (getArray(column)?.array as Array<*>?)?.filterNotNull().orEmpty()

internal fun ResultSet.uuids(column: Int): List<UUID> = list(column).map { it as? UUID ?: UUID.fromString(it.toString()) }

internal fun ResultSet.longs(column: Int): List<Long> = list(column).map { (it as Number).toLong() }

internal fun ResultSet.strings(column: Int): List<String> = list(column).map { it.toString() }

private fun ResultSet.uuid(column: Int): UUID = getObject(column).let { it as? UUID ?: UUID.fromString(it.toString()) }

private fun ResultSet.pos(column: Int): Pos? {
    val dim = getString(column) ?: return null
    return Pos(dim, getInt(column + 1), getInt(column + 2), getInt(column + 3))
}

private inline fun <reified E : Enum<E>> ResultSet.code(column: Int): E {
    val id = getString(column)
    return enumOfId<E>(id) ?: error("unknown ${E::class.simpleName} in the memory: $id")
}

/** Player names by UUID ([PlayerRef]s for rows that store UUIDs); a player never seen keeps a short UUID. */
internal class Names(private val names: Map<UUID, String>) {
    fun ref(player: UUID): PlayerRef = PlayerRef(player, names[player] ?: player.toString().take(8))

    fun refs(players: List<UUID>): List<PlayerRef> = players.map(::ref)

    fun known(player: UUID): Boolean = player in names

    companion object {
        /** Every player of the world: the table is small (one row per player ever seen). */
        fun of(connection: Connection): Names =
            Names(connection.query("SELECT player_uuid, name FROM players") { uuid(1) to getString(2) }.toMap())
    }
}

/** The tables of the five citable kinds hold [ids]: the refs they name. Ids found nowhere are left out. */
internal fun Connection.refsOf(ids: Collection<Long>): Map<Long, Ref> {
    if (ids.isEmpty()) return emptyMap()
    val union = RefKind.entries.joinToString(" UNION ALL ") { "SELECT id, '${it.prefix}' FROM ${it.table} WHERE list_contains(?, id)" }
    val parameters = Array<Any?>(RefKind.entries.size) { longArray(ids.toSet()) }
    return query(union, *parameters) { getLong(1) to Ref(RefKind.ofPrefix(getString(2))!!, getLong(1)) }.toMap()
}

/** Whether [ref]'s row exists. */
internal fun Connection.exists(ref: Ref): Boolean =
    query("SELECT 1 FROM ${ref.kind.table} WHERE id = ?", ref.id) { true }.isNotEmpty()

/** The row of [table] that matches [where], as a JSON object (`to_json` of the row: every column); null when none. */
internal fun Connection.snapshot(table: String, where: String, vararg parameters: Any?): String? =
    query("SELECT to_json(t)::VARCHAR FROM $table t WHERE $where", *parameters) { getString(1) }.singleOrNull()

internal fun Connection.snapshot(ref: Ref): String? = snapshot(ref.kind.table, "id = ?", ref.id)

private val json = Json { ignoreUnknownKeys = true }

/** A JSON object written by [snapshot]. */
internal fun jsonObjectOf(text: String?): JsonObject? = text?.let { json.parseToJsonElement(it).jsonObject }

// ── Rows of the citable tables ────────────────────────────────────────────────────────────────────────

internal const val MEMORY_COLUMNS = "id, kind, created_at, occurred_from, occurred_to, title, content, players, entities, tags, " +
    "importance, valence, dim, x, y, z, stability_h, reinforced_play_s, recalls, last_recalled_at, source, archived, merged_into"

internal fun ResultSet.memoryEntry(names: Names) = MemoryEntry(
    ref = Ref(RefKind.MEMORY, getLong(1)),
    kind = code(2),
    createdAt = instant(3),
    occurredFrom = instant(4),
    occurredTo = instant(5),
    title = getString(6),
    content = getString(7),
    players = names.refs(uuids(8)),
    entities = strings(9),
    tags = strings(10),
    importance = getInt(11),
    valence = getInt(12),
    place = pos(13),
    stabilityHours = getDouble(17),
    reinforcedPlaySeconds = getLong(18),
    recalls = getInt(19),
    lastRecalledAt = instantOrNull(20),
    source = code(21),
    archived = getBoolean(22),
    mergedInto = longOrNull(23)?.let { Ref(RefKind.MEMORY, it) },
)

internal const val CANON_COLUMNS = "id, kind, created_at, statement, quote, players, stakes, importance, status, due_at, " +
    "resolved_at, resolution, winners, replaces, dim, x, y, z, uses, last_used_at, freshness, source, archived"

internal fun ResultSet.canonEntry(names: Names) = CanonEntry(
    ref = Ref(RefKind.CANON, getLong(1)),
    kind = code(2),
    createdAt = instant(3),
    statement = getString(4),
    quote = getString(5),
    players = names.refs(uuids(6)),
    stakes = getString(7),
    importance = getInt(8),
    status = code(9),
    dueAt = instantOrNull(10),
    resolvedAt = instantOrNull(11),
    resolution = getString(12),
    winners = names.refs(uuids(13)),
    replaces = longOrNull(14)?.let { Ref(RefKind.CANON, it) },
    place = pos(15),
    uses = getInt(19),
    lastUsedAt = instantOrNull(20),
    freshness = getString(21)?.let { enumOfId<GagState>(it) },
    source = code(22),
    archived = getBoolean(23),
)

internal const val INTERACTION_COLUMNS = "id, ts, player_uuid, player, kind, valence, counts, disrespect, summary, quote, played_s, " +
    "canon_id, memory_id, tool_calls, source, archived"

internal fun ResultSet.interaction() = Interaction(
    ref = Ref(RefKind.INTERACTION, getLong(1)),
    ts = instant(2),
    player = PlayerRef(uuid(3), getString(4)),
    kind = code(5),
    valence = getInt(6),
    counts = getBoolean(7),
    disrespect = getBoolean(8),
    summary = getString(9),
    quote = getString(10),
    playedSeconds = getLong(11),
    canon = longOrNull(12)?.let { Ref(RefKind.CANON, it) },
    memory = longOrNull(13)?.let { Ref(RefKind.MEMORY, it) },
    toolCalls = longs(14),
    source = code(15),
    archived = getBoolean(16),
)

internal const val CHRONICLE_COLUMNS = "id, level, period_from, period_to, title, summary, covers, conversation_id, open, superseded_by, source"

internal fun ResultSet.chronicleEntry() = ChronicleEntry(
    ref = Ref(RefKind.CHRONICLE, getLong(1)),
    level = code(2),
    from = instant(3),
    to = instant(4),
    title = getString(5),
    summary = getString(6),
    covers = longs(7).map { Ref(RefKind.CHRONICLE, it) },
    conversationId = getString(8),
    open = getBoolean(9),
    supersededBy = longOrNull(10)?.let { Ref(RefKind.CHRONICLE, it) },
    source = code(11),
)

internal const val NOTE_COLUMNS = "id, created_at, kind, text, players, refs, status, shown_count, expires_at"

/** A note, and the ids of its refs (resolved with [refsOf]). */
internal fun ResultSet.note(names: Names): Pair<Note, List<Long>> = Note(
    ref = Ref(RefKind.NOTE, getLong(1)),
    createdAt = instant(2),
    kind = code(3),
    text = getString(4),
    players = names.refs(uuids(5)),
    refs = emptyList(),
    status = code(7),
    shownCount = getInt(8),
    expiresAt = instantOrNull(9),
) to longs(6)

/** Reads the row [ref] names, whatever its table; null when there is none. */
internal fun Connection.readRow(ref: Ref): MemoryRow? {
    val names = Names.of(this)
    return when (ref.kind) {
        RefKind.MEMORY -> query("SELECT $MEMORY_COLUMNS FROM memories WHERE id = ?", ref.id) { memoryEntry(names) }.singleOrNull()
        RefKind.CANON -> query("SELECT $CANON_COLUMNS FROM canon WHERE id = ?", ref.id) { canonEntry(names) }.singleOrNull()
        RefKind.INTERACTION -> query("SELECT $INTERACTION_COLUMNS FROM interactions WHERE id = ?", ref.id) { interaction() }.singleOrNull()
        RefKind.CHRONICLE -> query("SELECT $CHRONICLE_COLUMNS FROM chronicle WHERE id = ?", ref.id) { chronicleEntry() }.singleOrNull()
        RefKind.NOTE -> notes("SELECT $NOTE_COLUMNS FROM notes WHERE id = ?", ref.id).singleOrNull()
    }
}

/** The notes of [sql], their refs resolved. */
internal fun Connection.notes(sql: String, vararg parameters: Any?): List<Note> {
    val names = Names.of(this)
    val rows = query(sql, *parameters) { note(names) }
    val refs = refsOf(rows.flatMap { it.second })
    return rows.map { (note, ids) -> note.copy(refs = ids.mapNotNull(refs::get)) }
}

// ── Rows of the player sheets ─────────────────────────────────────────────────────────────────────────

internal const val TRAIT_COLUMNS = "player_uuid, trait, side, label, intensity, confidence, evidence, since, trend, note, set_at, " +
    "set_played_s, retired"

internal fun Connection.traits(where: String, vararg parameters: Any?): List<Trait> {
    val rows = query("SELECT $TRAIT_COLUMNS FROM player_traits WHERE $where ORDER BY trait", *parameters) {
        Trait(
            player = uuid(1), trait = getString(2), side = getInt(3), label = getString(4), intensity = getInt(5),
            confidence = getDouble(6), evidence = emptyList(), since = instant(8), trend = getInt(9), note = getString(10),
            setAt = instant(11), setPlayedSeconds = getLong(12), retired = getBoolean(13),
        ) to longs(7)
    }
    val refs = refsOf(rows.flatMap { it.second })
    return rows.map { (trait, ids) -> trait.copy(evidence = ids.mapNotNull(refs::get)) }
}

internal fun Connection.skills(where: String, vararg parameters: Any?): List<SkillScore> {
    val rows = query("SELECT player_uuid, skill, score, rationale, evidence, set_at FROM player_skills WHERE $where ORDER BY skill", *parameters) {
        SkillScore(uuid(1), code(2), getInt(3), getString(4), emptyList(), instant(6)) to longs(5)
    }
    val refs = refsOf(rows.flatMap { it.second })
    return rows.map { (skill, ids) -> skill.copy(evidence = ids.mapNotNull(refs::get)) }
}

internal fun Connection.relations(where: String, vararg parameters: Any?): List<Relation> {
    val names = Names.of(this)
    val rows = query(
        "SELECT player_uuid, other_uuid, kind, note, evidence, set_at FROM player_relations WHERE $where ORDER BY other_uuid", *parameters,
    ) {
        Relation(uuid(1), names.ref(uuid(2)), code(3), getString(4), emptyList(), instant(6)) to longs(5)
    }
    val refs = refsOf(rows.flatMap { it.second })
    return rows.map { (relation, ids) -> relation.copy(evidence = ids.mapNotNull(refs::get)) }
}

// ── What the index holds ──────────────────────────────────────────────────────────────────────────────

/**
 * The text [Indexer] indexes for [row] (M3 §10.3): a memory's `title + content + tags + entities`, a canon
 * entry's `statement + quote + kind`, an interaction's `summary + quote + kind`, a chronicle entry's
 * `title + summary`; null for a note, which is never indexed.
 */
internal fun indexText(row: MemoryRow): String? = when (row) {
    is MemoryEntry -> listOf(row.title, row.content, row.tags.joinToString(" "), row.entities.joinToString(" ")).joinToString("\n")
    is CanonEntry -> canonText(row.statement, row.quote, row.kind)
    is Interaction -> interactionText(row.summary, row.quote, row.kind)
    is ChronicleEntry -> "${row.title}\n${row.summary}"
    is Note -> null
}

internal fun canonText(statement: String, quote: String?, kind: CanonKind): String = listOfNotNull(statement, quote, kind.id).joinToString("\n")

internal fun interactionText(summary: String, quote: String?, kind: InteractionKind): String =
    listOfNotNull(summary, quote, kind.id).joinToString("\n")
