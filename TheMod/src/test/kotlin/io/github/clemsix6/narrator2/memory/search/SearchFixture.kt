package io.github.clemsix6.narrator2.memory.search

import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.journal.DisabledJournal
import io.github.clemsix6.narrator2.journal.DuckDatabase
import io.github.clemsix6.narrator2.journal.Journal
import io.github.clemsix6.narrator2.memory.CanonKind
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.memory.ChronicleLevel
import io.github.clemsix6.narrator2.memory.InteractionKind
import io.github.clemsix6.narrator2.memory.RecallHit
import io.github.clemsix6.narrator2.memory.RecallQuery
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.testing.FakeMemory
import io.github.clemsix6.narrator2.testing.TestClock
import org.duckdb.DuckDBConnection
import java.time.Instant
import java.time.ZoneId

/**
 * A memory database in memory (V1 + V2) with the real index and recall, for the search tests. Rows are
 * written straight on the writer connection, as the store will do inside a `Journal.write` job, and
 * indexed with the texts of M3 §10.3. [search] runs [HybridRecall] on the same connection at [clock]'s time.
 */
internal class SearchFixture(
    val clock: TestClock = TestClock(NOW),
    journal: Journal = DisabledJournal("the search tests read the connection directly"),
) : AutoCloseable {
    val db: DuckDatabase = DuckDatabase.openInMemory(OPTIONS)
    val connection: DuckDBConnection get() = db.writer
    val index = SearchIndex()
    val playClock = FakeMemory.FakePlayClock()
    val recall = HybridRecall(journal, index, playClock, clock)

    fun search(query: RecallQuery): List<RecallHit> = recall.search(connection, query, clock.instant())

    fun player(player: PlayerRef) {
        connection.update(
            "INSERT INTO players VALUES (?::UUID, ?, now(), now(), 1, 0, false)", player.uuid.toString(), player.name,
        )
    }

    fun memory(
        id: Long,
        title: String,
        content: String,
        players: List<PlayerRef> = emptyList(),
        tags: List<String> = emptyList(),
        entities: List<String> = emptyList(),
        importance: Int = 5,
        at: Instant = clock.instant(),
        stabilityHours: Double = 38.0,
        reinforcedPlaySeconds: Long = 0,
        archived: Boolean = false,
        mergedInto: Long? = null,
    ): Ref {
        connection.update(
            """
            INSERT INTO memories (id, kind, created_at, occurred_from, occurred_to, title, content, players, entities, tags,
                importance, valence, stability_h, reinforced_play_s, source, evidence_events, evidence_tools, archived, merged_into)
            VALUES (?, 'episode', to_timestamp(?), to_timestamp(?), to_timestamp(?), ?, ?, ?::VARCHAR[]::UUID[], ?::VARCHAR[],
                ?::VARCHAR[], ?, 0, ?, ?, 'chronicler', [], [], ?, ?)
            """.trimIndent(),
            id, seconds(at), seconds(at), seconds(at), title, content, strings(players.map { it.uuid.toString() }),
            strings(entities), strings(tags), importance, stabilityHours, reinforcedPlaySeconds, archived, mergedInto,
        )
        val ref = Ref(RefKind.MEMORY, id)
        index.index(connection, ref, listOf(title, content, tags.joinToString(" "), entities.joinToString(" ")).joinToString(" "))
        return ref
    }

    fun canon(
        id: Long,
        kind: CanonKind,
        statement: String,
        quote: String? = null,
        players: List<PlayerRef> = emptyList(),
        importance: Int = 5,
        status: CanonStatus = CanonStatus.ACTIVE,
        at: Instant = clock.instant(),
        dueAt: Instant? = null,
        resolvedAt: Instant? = null,
        resolution: String? = null,
    ): Ref {
        connection.update(
            """
            INSERT INTO canon (id, kind, created_at, statement, quote, players, importance, status, due_at, resolved_at, resolution,
                source, evidence_events, evidence_tools)
            VALUES (?, ?, to_timestamp(?), ?, ?, ?::VARCHAR[]::UUID[], ?, ?, to_timestamp(?), to_timestamp(?), ?, 'director', [], [])
            """.trimIndent(),
            id, kind.id, seconds(at), statement, quote, strings(players.map { it.uuid.toString() }), importance, status.id,
            dueAt?.let(::seconds), resolvedAt?.let(::seconds), resolution,
        )
        val ref = Ref(RefKind.CANON, id)
        index.index(connection, ref, listOfNotNull(statement, quote, kind.id).joinToString(" "))
        return ref
    }

    fun interaction(
        id: Long,
        player: PlayerRef,
        kind: InteractionKind,
        valence: Int,
        summary: String,
        quote: String? = null,
        at: Instant = clock.instant(),
        playedSeconds: Long = 0,
        archived: Boolean = false,
    ): Ref {
        connection.update(
            """
            INSERT INTO interactions (id, ts, player_uuid, player, kind, valence, counts, disrespect, summary, quote, played_s,
                tool_calls, source, evidence_events, archived)
            VALUES (?, to_timestamp(?), ?::UUID, ?, ?, ?, true, false, ?, ?, ?, [], 'director', [], ?)
            """.trimIndent(),
            id, seconds(at), player.uuid.toString(), player.name, kind.id, valence, summary, quote, playedSeconds, archived,
        )
        val ref = Ref(RefKind.INTERACTION, id)
        index.index(connection, ref, listOfNotNull(summary, quote, kind.id).joinToString(" "))
        return ref
    }

    fun chronicle(
        id: Long,
        level: ChronicleLevel,
        title: String,
        summary: String,
        from: Instant,
        to: Instant,
        supersededBy: Long? = null,
    ): Ref {
        connection.update(
            """
            INSERT INTO chronicle (id, level, period_from, period_to, title, summary, covers, open, superseded_by, written_at, source)
            VALUES (?, ?, to_timestamp(?), to_timestamp(?), ?, ?, [], false, ?, now(), 'dreamer')
            """.trimIndent(),
            id, level.id, seconds(from), seconds(to), title, summary, supersededBy,
        )
        val ref = Ref(RefKind.CHRONICLE, id)
        index.index(connection, ref, "$title $summary")
        return ref
    }

    fun count(sql: String): Long = connection.rows(sql) { getLong(1) }.single()

    private fun strings(values: List<String>) = connection.array("VARCHAR", values)

    private fun seconds(at: Instant): Double = at.toEpochMilli() / 1_000.0

    override fun close() = db.close()

    companion object {
        val NOW: Instant = Instant.parse("2026-10-07T20:00:00Z")
        val OPTIONS = DuckDatabase.Options(memoryLimitMb = 512, threads = 2, timeZone = ZoneId.of("UTC"))
    }
}
