package io.github.clemsix6.narrator2.memory.search

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.journal.Journal
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.memory.ChronicleLevel
import io.github.clemsix6.narrator2.memory.PlayClock
import io.github.clemsix6.narrator2.memory.Recall
import io.github.clemsix6.narrator2.memory.RecallHit
import io.github.clemsix6.narrator2.memory.RecallPurpose
import io.github.clemsix6.narrator2.memory.RecallQuery
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.memory.enumOfId
import java.sql.Connection
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.random.Random

/** A memory's strength (M3 §9.1) from its stability and the hours of world play since it was last reinforced. */
fun interface MemoryStrength {
    fun of(stabilityHours: Double, hoursSinceReinforced: Double): Double
}

/**
 * The memory's search (M3 §10.4): SQL filters, BM25 in DuckDB ([SearchIndex]), then a score that weighs
 * relevance by importance, age and strength.
 *
 * 1. **Candidates**: the [BM25_CANDIDATES] best BM25 rows for the text, plus the rows that share a
 *    player, a tag or a game id with the query; filtered by kind, time and status ([RecallQuery.includeClosed]),
 *    minus [RecallQuery.exclude]. A query with neither words nor players, tags or ids lists the latest rows.
 * 2. **Relevance** ∈ [0, 1]: `0.6 × bm25 / best bm25 + 0.4 × structured`, where structured is 0.5 when one
 *    of the query's players is among the row's, plus 0.25 per tag or id in common (at most 1). Without text,
 *    structured alone; when listing the latest, 1.
 * 3. **Score**: `relevance × (0.4 + 0.4 × importance / 10 + 0.2 × 2^(−days / 30)) × strength`.
 * 4. [RecallPurpose.REMINISCENCE] keeps the hits with a score of at least [REMINISCENCE_SCORE] and a relevance
 *    of at least [REMINISCENCE_RELEVANCE], then draws them with a probability proportional to the squared
 *    score, seeded by [RecallQuery.seed] (M3 §10.6). Every other purpose sorts by score; the latest by time.
 *
 * Reads only: the caller records what it shows. The cooldowns and per-wake caps of the reminiscences are
 * the caller's (M3-14), which passes what to leave out in [RecallQuery.exclude].
 *
 * @param memoryStrength the decay of memories (M3 §9.1, `memory/Decay.kt`).
 */
class HybridRecall(
    private val journal: Journal,
    private val index: SearchIndex,
    private val playClock: PlayClock,
    private val memoryStrength: MemoryStrength,
    private val clock: Clock,
    private val readTimeoutMs: Long = READ_TIMEOUT_MS,
) : Recall {
    private val log = Log.memory
    private val dayTime = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT).withZone(clock.zone)
    private val hourTime = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withZone(clock.zone)
    private val day = DateTimeFormatter.ofPattern("MM-dd", Locale.ROOT).withZone(clock.zone)

    override suspend fun search(query: RecallQuery): List<RecallHit> {
        val started = System.nanoTime()
        val hits = journal.read(readTimeoutMs) { search(it, query, clock.instant()) }
        log.debug {
            "recall ${query.purpose.name.lowercase()} text=${query.text?.let { ModelText.quote(it, 80) }} players=${query.players.size} " +
                "tags=${query.tags} entities=${query.entities}: ${hits.joinToString(" ") { "${it.ref}(${"%.2f".format(Locale.ROOT, it.score)})" }} " +
                "in ${(System.nanoTime() - started) / 1_000_000} ms"
        }
        return hits
    }

    /** [query] on [connection] at [now]: what [search] runs on a read connection of the Journal. */
    fun search(connection: Connection, query: RecallQuery, now: Instant): List<RecallHit> {
        val kinds = RefKind.searchable.filter { it in query.kinds }
        if (query.limit <= 0 || kinds.isEmpty()) return emptyList()
        val terms = query.text?.let(FrenchAnalyzer::terms).orEmpty().distinct()
        val bm25 = index.bm25(connection, terms, kinds, BM25_CANDIDATES)
        val latest = terms.isEmpty() && query.players.isEmpty() && query.tags.isEmpty() && query.entities.isEmpty()
        val match = Match(query, bm25, latest, now)
        val candidates = kinds.flatMap { candidates(connection, it, match) }.filter { it.ref !in query.exclude }
        val best = candidates.maxOfOrNull { bm25[it.ref] ?: 0.0 }?.takeIf { it > 0 } ?: 1.0
        val scored = candidates.map { candidate ->
            val relevance = when {
                latest -> 1.0
                terms.isEmpty() -> candidate.structured
                else -> TEXT_WEIGHT * (bm25[candidate.ref] ?: 0.0) / best + (1 - TEXT_WEIGHT) * candidate.structured
            }
            val days = max(0L, now.toEpochMilli() - candidate.at) / 86_400_000.0
            val weight = 0.4 + 0.4 * candidate.importance / 10.0 + 0.2 * 2.0.pow(-days / 30)
            Scored(candidate, relevance, relevance * weight * candidate.strength)
        }.sortedWith(compareByDescending<Scored> { it.score }.thenByDescending { it.candidate.ref.id })
        val chosen = when {
            query.purpose == RecallPurpose.REMINISCENCE -> draw(scored, query)
            latest -> scored.sortedWith(compareByDescending<Scored> { it.candidate.at }.thenByDescending { it.candidate.ref.id }).take(query.limit)
            else -> scored.take(query.limit)
        }
        val lines = lines(connection, chosen.map { it.candidate.ref }, now)
        return chosen.mapNotNull { hit ->
            lines[hit.candidate.ref]?.let { RecallHit(hit.candidate.ref, hit.score, hit.relevance, hit.candidate.strength, it) }
        }
    }

    /** Above the thresholds, a draw without replacement weighted by the squared score (M3 §10.6). */
    private fun draw(scored: List<Scored>, query: RecallQuery): List<Scored> {
        val pool = scored.filter { it.score >= REMINISCENCE_SCORE && it.relevance >= REMINISCENCE_RELEVANCE }.toMutableList()
        val random = Random(query.seed ?: 0L)
        return buildList {
            while (size < query.limit && pool.isNotEmpty()) {
                var left = random.nextDouble() * pool.sumOf { it.score * it.score }
                val index = pool.indexOfFirst { left -= it.score * it.score; left < 0 }
                add(pool.removeAt(if (index >= 0) index else pool.lastIndex))
            }
        }
    }

    // ── Candidates ──────────────────────────────────────────────────────────────────────────────────

    /** What the candidates of a search must match: the BM25 rows, or the query's players, tags and ids; and the filters. */
    private class Match(val query: RecallQuery, val bm25: Map<Ref, Double>, val latest: Boolean, val now: Instant) {
        fun ids(kind: RefKind): List<Long> = bm25.keys.filter { it.kind == kind }.map { it.id }

        /** Whether rows of [kind] may match without a word in common (their players, tags or ids). */
        fun structured(kind: RefKind): Boolean = when (kind) {
            RefKind.MEMORY -> query.players.isNotEmpty() || query.tags.isNotEmpty() || query.entities.isNotEmpty()
            RefKind.CANON, RefKind.INTERACTION -> query.players.isNotEmpty() || query.tags.isNotEmpty()
            RefKind.CHRONICLE, RefKind.NOTE -> false
        }
    }

    private class Candidate(val ref: Ref, val at: Long, val importance: Int, val strength: Double, val structured: Double)

    private class Scored(val candidate: Candidate, val relevance: Double, val score: Double)

    /** One way for a row to be a candidate: an SQL condition and its one parameter. */
    private class Alternative(val condition: String, val value: Any?)

    private fun candidates(connection: Connection, kind: RefKind, match: Match): List<Candidate> {
        if (!match.latest && match.ids(kind).isEmpty() && !match.structured(kind)) return emptyList()
        return when (kind) {
            RefKind.MEMORY -> memories(CandidateQuery(connection, match, kind, "occurred_to"))
            RefKind.CANON -> canon(CandidateQuery(connection, match, kind, "created_at"))
            RefKind.INTERACTION -> interactions(CandidateQuery(connection, match, kind, "ts"))
            RefKind.CHRONICLE -> chronicle(CandidateQuery(connection, match, kind, "period_to"))
            RefKind.NOTE -> emptyList()
        }
    }

    private fun memories(query: CandidateQuery): List<Candidate> {
        val world = playClock.worldPlaySeconds(query.match.now.toEpochMilli())
        query.select(
            "id, epoch_ms(occurred_to), importance, stability_h, reinforced_play_s, least(1.0, " +
                "(CASE WHEN list_has_any(players, ?::VARCHAR[]::UUID[]) THEN 0.5 ELSE 0.0 END) " +
                "+ 0.25 * (len(list_intersect(tags, ?::VARCHAR[])) + len(list_intersect(entities, ?::VARCHAR[]))))::DOUBLE",
            query.players(), query.tags(), query.entities(),
        )
        query.matching(
            query.byIds(), query.anyPlayer("players"), query.anyOf("tags", query.match.query.tags),
            query.anyOf("entities", query.match.query.entities),
        )
        if (!query.match.query.includeClosed) query.where("NOT archived AND merged_into IS NULL")
        return query.rows("memories") {
            val hours = max(0L, world - getLong(5)) / 3_600.0
            Candidate(Ref(RefKind.MEMORY, getLong(1)), getLong(2), getInt(3), memoryStrength.of(getDouble(4), hours), getDouble(6))
        }
    }

    private fun canon(query: CandidateQuery): List<Candidate> {
        query.select(
            "id, epoch_ms(created_at), importance, status, least(1.0, " +
                "(CASE WHEN list_has_any(players, ?::VARCHAR[]::UUID[]) THEN 0.5 ELSE 0.0 END) " +
                "+ (CASE WHEN list_contains(?::VARCHAR[], kind) THEN 0.25 ELSE 0.0 END))::DOUBLE",
            query.players(), query.tags(),
        )
        query.matching(query.byIds(), query.anyPlayer("players"), query.oneOf("kind", query.match.query.tags))
        if (!query.match.query.includeClosed) query.where("status IN ('${CanonStatus.PLANNED.id}', '${CanonStatus.ACTIVE.id}') AND NOT archived")
        return query.rows("canon") {
            val open = enumOfId<CanonStatus>(getString(4))?.open ?: false
            Candidate(Ref(RefKind.CANON, getLong(1)), getLong(2), getInt(3), if (open) 1.0 else CLOSED_CANON_STRENGTH, getDouble(5))
        }
    }

    private fun interactions(query: CandidateQuery): List<Candidate> {
        val now = query.match.now.toEpochMilli()
        query.select(
            "id, epoch_ms(ts), valence, played_s, player_uuid::VARCHAR, least(1.0, " +
                "(CASE WHEN list_contains(?::VARCHAR[]::UUID[], player_uuid) THEN 0.5 ELSE 0.0 END) " +
                "+ (CASE WHEN list_contains(?::VARCHAR[], kind) THEN 0.25 ELSE 0.0 END))::DOUBLE",
            query.players(), query.tags(),
        )
        query.matching(query.byIds(), query.onePlayer("player_uuid"), query.oneOf("kind", query.match.query.tags))
        if (!query.match.query.includeClosed) query.where("NOT archived")
        return query.rows("interactions") {
            val played = playClock.playerPlaySeconds(UUID.fromString(getString(5)), now)
            val hours = max(0L, played - getLong(4)) / 3_600.0
            Candidate(
                Ref(RefKind.INTERACTION, getLong(1)), getLong(2), 3 + 2 * abs(getInt(3)),
                max(INTERACTION_FLOOR, 2.0.pow(-hours / INTERACTION_HALF_LIFE_H)), getDouble(6),
            )
        }
    }

    private fun chronicle(query: CandidateQuery): List<Candidate> {
        query.select("id, epoch_ms(period_to), level")
        query.matching(query.byIds())
        if (!query.match.query.includeClosed) query.where("superseded_by IS NULL")
        return query.rows("chronicle") {
            val level = enumOfId<ChronicleLevel>(getString(3))
            Candidate(Ref(RefKind.CHRONICLE, getLong(1)), getLong(2), CHRONICLE_IMPORTANCE[level] ?: 5, 1.0, 0.0)
        }
    }

    /**
     * The query of the candidates of [kind]: its columns (DuckDB computes the structured share of the
     * relevance), what a row must match (nothing when listing the latest, which keeps the [LATEST_CANDIDATES]
     * most recent rows instead), its time ([dateColumn], for [RecallQuery.sinceMillis]) and its status.
     */
    private class CandidateQuery(val connection: Connection, val match: Match, val kind: RefKind, val dateColumn: String) {
        private var columns = ""
        private val columnValues = mutableListOf<Any?>()
        private val conditions = mutableListOf<String>()
        private val conditionValues = mutableListOf<Any?>()

        init {
            match.query.sinceMillis?.let { where("epoch_ms($dateColumn) >= ?", it) }
        }

        fun select(columns: String, vararg parameters: Any?) {
            this.columns = columns
            columnValues.addAll(parameters)
        }

        fun where(condition: String, vararg parameters: Any?) {
            conditions += "($condition)"
            conditionValues.addAll(parameters)
        }

        /** The row matches one of [alternatives] (those the query gives a value for); anything when listing the latest. */
        fun matching(vararg alternatives: Alternative?) {
            if (match.latest) return
            val given = alternatives.filterNotNull()
            if (given.isEmpty()) where("false") else where(given.joinToString(" OR ") { it.condition }, *given.map { it.value }.toTypedArray())
        }

        /** One of the BM25 rows. */
        fun byIds(): Alternative? =
            match.ids(kind).takeIf { it.isNotEmpty() }?.let { Alternative("id IN (SELECT unnest(?::BIGINT[]))", connection.array("BIGINT", it)) }

        /** One of the query's players among the row's ([column] is a `UUID[]`). */
        fun anyPlayer(column: String): Alternative? =
            if (match.query.players.isEmpty()) null else Alternative("list_has_any($column, ?::VARCHAR[]::UUID[])", players())

        /** The row's player ([column] is a `UUID`) among the query's. */
        fun onePlayer(column: String): Alternative? =
            if (match.query.players.isEmpty()) null else Alternative("list_contains(?::VARCHAR[]::UUID[], $column)", players())

        /** One of [values] in the row's list [column]. */
        fun anyOf(column: String, values: List<String>): Alternative? =
            if (values.isEmpty()) null else Alternative("list_has_any($column, ?::VARCHAR[])", strings(values))

        /** The row's [column] among [values]. */
        fun oneOf(column: String, values: List<String>): Alternative? =
            if (values.isEmpty()) null else Alternative("list_contains(?::VARCHAR[], $column)", strings(values))

        fun players(): java.sql.Array = strings(match.query.players.map { it.toString() })

        fun tags(): java.sql.Array = strings(match.query.tags)

        fun entities(): java.sql.Array = strings(match.query.entities)

        private fun strings(values: List<String>): java.sql.Array = connection.array("VARCHAR", values.distinct())

        fun <T> rows(table: String, row: ResultSet.() -> T): List<T> {
            val where = if (conditions.isEmpty()) "" else " WHERE ${conditions.joinToString(" AND ")}"
            val order = if (match.latest) " ORDER BY $dateColumn DESC, id DESC LIMIT $LATEST_CANDIDATES" else ""
            return connection.rows("SELECT $columns FROM $table$where$order", *(columnValues + conditionValues).toTypedArray(), row = row)
        }
    }

    // ── Lines ───────────────────────────────────────────────────────────────────────────────────────

    /**
     * The `recall` line of each of [refs] (M3 §5.4), text escaped:
     * `M-120 · 05-14 21:40 (3w ago) · Bartok · ★7 · Mort dans la lave du Nether: « Bartok est mort… »`,
     * `C-41 · threat → Bartok · fulfilled 05-15 · ★6 · Le Narrateur a menacé… · said « … »`,
     * `I-512 · 05-14 21:40 · Bartok · insult -2 · a traité le Narrateur de « nul »`,
     * `H-88 · session 05-14 20:10–23:02 · « La nuit des slimes »: Bartok insulte le ciel…`.
     */
    private fun lines(connection: Connection, refs: List<Ref>, now: Instant): Map<Ref, String> {
        if (refs.isEmpty()) return emptyMap()
        val byKind = refs.groupBy({ it.kind }, { it.id })
        val rows = buildList {
            byKind[RefKind.MEMORY]?.let { addAll(memoryRows(connection, it)) }
            byKind[RefKind.CANON]?.let { addAll(canonRows(connection, it)) }
            byKind[RefKind.INTERACTION]?.let { addAll(interactionRows(connection, it)) }
            byKind[RefKind.CHRONICLE]?.let { addAll(chronicleRows(connection, it)) }
        }
        val names = names(connection, rows.flatMap { it.players })
        return rows.associate { it.ref to it.render(names, now) }
    }

    /** A row ready to render once the names of its [players] are known. */
    private class Row(val ref: Ref, val players: List<UUID>, val render: (Map<UUID, String>, Instant) -> String)

    private fun memoryRows(connection: Connection, ids: List<Long>): List<Row> = connection.rows(
        "SELECT id, epoch_ms(occurred_from), players::VARCHAR[], importance, title, content, archived, merged_into " +
            "FROM memories WHERE id IN (SELECT unnest(?::BIGINT[]))",
        connection.array("BIGINT", ids),
    ) {
        val ref = Ref(RefKind.MEMORY, getLong(1))
        val at = getLong(2)
        val players = uuids(3)
        val importance = getInt(4)
        val title = getString(5)
        val content = getString(6)
        val archived = getBoolean(7)
        val mergedInto = getLong(8).takeUnless { wasNull() }
        Row(ref, players) { names, now ->
            buildString {
                append("$ref · ${dayTime.format(instant(at))} (${ago(now.toEpochMilli() - at)}) · ${names(players, names)} · ★$importance · ")
                append("${text(title)}: « ${text(content)} »")
                if (mergedInto != null) append(" · merged into ${Ref(RefKind.MEMORY, mergedInto)}") else if (archived) append(" · archived")
            }
        }
    }

    private fun canonRows(connection: Connection, ids: List<Long>): List<Row> = connection.rows(
        "SELECT id, kind, players::VARCHAR[], importance, status, epoch_ms(due_at), epoch_ms(resolved_at), resolution, statement, " +
            "quote, stakes, archived FROM canon WHERE id IN (SELECT unnest(?::BIGINT[]))",
        connection.array("BIGINT", ids),
    ) {
        val ref = Ref(RefKind.CANON, getLong(1))
        val kind = getString(2)
        val players = uuids(3)
        val importance = getInt(4)
        val status = getString(5)
        val due = getLong(6).takeUnless { wasNull() }
        val resolved = getLong(7).takeUnless { wasNull() }
        val resolution = getString(8)
        val statement = getString(9)
        val quote = getString(10)
        val stakes = getString(11)
        val archived = getBoolean(12)
        Row(ref, players) { names, _ ->
            buildString {
                append("$ref · $kind → ${names(players, names)} · $status")
                if (resolved != null) append(" ${day.format(instant(resolved))}")
                if (resolution != null) append(" (${text(ModelText.cut(resolution, RESOLUTION_CHARS))})")
                if (due != null && enumOfId<CanonStatus>(status)?.open == true) append(", due ${dayTime.format(instant(due))}")
                append(" · ★$importance · ${text(statement)}")
                if (quote != null) append(" · said « ${text(quote)} »")
                if (stakes != null) append(" · stakes: ${text(stakes)}")
                if (archived) append(" · archived")
            }
        }
    }

    private fun interactionRows(connection: Connection, ids: List<Long>): List<Row> = connection.rows(
        "SELECT id, epoch_ms(ts), player_uuid::VARCHAR, player, kind, valence, summary, quote, archived " +
            "FROM interactions WHERE id IN (SELECT unnest(?::BIGINT[]))",
        connection.array("BIGINT", ids),
    ) {
        val ref = Ref(RefKind.INTERACTION, getLong(1))
        val at = getLong(2)
        val player = UUID.fromString(getString(3))
        val nameThen = getString(4)
        val kind = getString(5)
        val valence = getInt(6)
        val summary = getString(7)
        val quote = getString(8)
        val archived = getBoolean(9)
        Row(ref, listOf(player)) { names, _ ->
            buildString {
                val name = names[player] ?: ModelText.name(nameThen)
                append("$ref · ${dayTime.format(instant(at))} · $name · $kind ${if (valence > 0) "+$valence" else "$valence"} · ${text(summary)}")
                if (quote != null) append(" · « ${text(quote)} »")
                if (archived) append(" · archived")
            }
        }
    }

    private fun chronicleRows(connection: Connection, ids: List<Long>): List<Row> = connection.rows(
        "SELECT id, level, epoch_ms(period_from), epoch_ms(period_to), title, summary, superseded_by " +
            "FROM chronicle WHERE id IN (SELECT unnest(?::BIGINT[]))",
        connection.array("BIGINT", ids),
    ) {
        val ref = Ref(RefKind.CHRONICLE, getLong(1))
        val level = getString(2)
        val from = instant(getLong(3))
        val to = instant(getLong(4))
        val title = getString(5)
        val summary = getString(6)
        val supersededBy = getLong(7).takeUnless { wasNull() }
        Row(ref, emptyList()) { _, _ ->
            val sameDay = day.format(from) == day.format(to)
            val period = "${dayTime.format(from)}–${(if (sameDay) hourTime else dayTime).format(to)}"
            buildString {
                append("$ref · $level $period · « ${text(title)} »: ${text(ModelText.cut(summary, SUMMARY_CHARS))}")
                if (supersededBy != null) append(" · superseded by ${Ref(RefKind.CHRONICLE, supersededBy)}")
            }
        }
    }

    /** The latest name of each of [players], escaped. */
    private fun names(connection: Connection, players: List<UUID>): Map<UUID, String> {
        if (players.isEmpty()) return emptyMap()
        return connection.rows(
            "SELECT player_uuid::VARCHAR, name FROM players WHERE player_uuid IN (SELECT unnest(?::VARCHAR[]::UUID[]))",
            connection.array("VARCHAR", players.distinct().map { it.toString() }),
        ) { UUID.fromString(getString(1)) to ModelText.name(getString(2)) }.toMap()
    }

    private fun names(players: List<UUID>, names: Map<UUID, String>): String =
        players.joinToString { names[it] ?: it.toString().take(8) }.ifEmpty { "everyone" }

    private fun text(value: String): String = ModelText.escape(value)

    private fun instant(epochMs: Long): Instant = Instant.ofEpochMilli(epochMs)

    companion object {
        /** BM25 candidates of a search (M3 §10.4). */
        const val BM25_CANDIDATES = 50

        /** Rows per table considered when listing the latest. */
        const val LATEST_CANDIDATES = 200

        /** The share of full text in the relevance; the structured evidence has the rest. */
        const val TEXT_WEIGHT = 0.6

        /** Reminiscence thresholds (M3 §10.6). */
        const val REMINISCENCE_SCORE = 0.35
        const val REMINISCENCE_RELEVANCE = 0.4

        /** Open canon is law (strength 1); closed canon weighs this much. */
        const val CLOSED_CANON_STRENGTH = 0.6

        /** An interaction fades by half in this many hours of its player's play, down to [INTERACTION_FLOOR]. */
        const val INTERACTION_HALF_LIFE_H = 100.0
        const val INTERACTION_FLOOR = 0.1

        /** The importance of a chronicle entry, which has none of its own: the higher the level, the more it weighs. */
        private val CHRONICLE_IMPORTANCE = mapOf(
            ChronicleLevel.CHAPTER to 5, ChronicleLevel.SESSION to 6, ChronicleLevel.SEASON to 7, ChronicleLevel.SAGA to 8,
        )

        private const val READ_TIMEOUT_MS = 10_000L
        private const val SUMMARY_CHARS = 300
        private const val RESOLUTION_CHARS = 120

        /** `45m ago`, `3h ago`, `5d ago`, `3w ago`, `4mo ago`, `2y ago`. */
        fun ago(ms: Long): String {
            val minutes = max(0L, ms) / 60_000
            return when {
                minutes < 60 -> "${minutes}m ago"
                minutes < 48 * 60 -> "${minutes / 60}h ago"
                minutes < 14 * 1_440 -> "${minutes / 1_440}d ago"
                minutes < 9 * 7 * 1_440 -> "${minutes / (7 * 1_440)}w ago"
                minutes < 730 * 1_440 -> "${minutes / (30 * 1_440)}mo ago"
                else -> "${minutes / (365 * 1_440)}y ago"
            }
        }
    }
}

private fun ResultSet.strings(column: Int): List<String> =
    (getArray(column)?.array as? Array<*>)?.mapNotNull { it?.toString() }.orEmpty()

private fun ResultSet.uuids(column: Int): List<UUID> = strings(column).map(UUID::fromString)
