package io.github.clemsix6.narrator2.memory

import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Finds the open row an add would duplicate (M3 §4.3). The Director and the Chronicler may both record
 * the same thing: the second pass is the safety net, the duplicate is not wanted. The texts are compared
 * by [Indexer.similarity] (BM25 against the row over BM25 against itself); [THRESHOLD] or more is the same.
 */
internal class Dedup(private val indexer: Indexer) {
    /**
     * An open, unarchived canon entry of the same kind about the same players that says the same: the
     * same quote once normalized, or a statement at least [THRESHOLD] similar. Null when there is none.
     */
    fun canon(connection: Connection, kind: CanonKind, players: List<UUID>, quote: String?, text: String): Ref? {
        val candidates = connection.query(
            "SELECT id, quote FROM canon WHERE kind = ? AND NOT archived AND status IN ('planned', 'active') " +
                "AND list_sort(players) = list_sort(?) ORDER BY id DESC",
            kind.id, uuidArray(players),
        ) { Ref(RefKind.CANON, getLong(1)) to getString(2) }
        if (candidates.isEmpty()) return null
        val wanted = quote?.let(Quotes::normalize)?.takeIf { it.isNotEmpty() }
        candidates.firstOrNull { (_, other) -> wanted != null && other != null && Quotes.normalize(other) == wanted }?.let { return it.first }
        return mostSimilar(connection, text, candidates.map { it.first })
    }

    /**
     * An unarchived interaction of the same player and kind less than [INTERACTION_WINDOW] from [at] that
     * shares an evidence event or a tool call, or whose text is at least [THRESHOLD] similar. Null when none.
     */
    fun interaction(
        connection: Connection,
        player: UUID,
        kind: InteractionKind,
        at: Instant,
        events: List<Long>,
        tools: List<Long>,
        text: String,
    ): Ref? {
        val candidates = connection.query(
            "SELECT id, list_has_any(evidence_events, ?) OR list_has_any(tool_calls, ?) FROM interactions " +
                "WHERE player_uuid = ? AND kind = ? AND NOT archived AND ts BETWEEN ? AND ? ORDER BY id DESC",
            longArray(events), longArray(tools), player, kind.id, at - INTERACTION_WINDOW, at + INTERACTION_WINDOW,
        ) { Ref(RefKind.INTERACTION, getLong(1)) to getBoolean(2) }
        if (candidates.isEmpty()) return null
        candidates.firstOrNull { it.second }?.let { return it.first }
        return mostSimilar(connection, text, candidates.map { it.first })
    }

    /** An unarchived memory that shares an evidence (event or tool call), a player and a tag with the draft; null when none. */
    fun memory(connection: Connection, players: List<UUID>, tags: List<String>, events: List<Long>, tools: List<Long>): Ref? {
        if (events.isEmpty() && tools.isEmpty()) return null
        return connection.query(
            "SELECT id FROM memories WHERE NOT archived AND (list_has_any(evidence_events, ?) OR list_has_any(evidence_tools, ?)) " +
                "AND list_has_any(players, ?) AND list_has_any(tags, ?) ORDER BY id DESC LIMIT 1",
            longArray(events), longArray(tools), uuidArray(players), stringArray(tags),
        ) { Ref(RefKind.MEMORY, getLong(1)) }.firstOrNull()
    }

    /** The player's active, unarchived entry of a [CanonKind.onePerPlayer] kind (the nickname, the title); null when none. */
    fun active(connection: Connection, kind: CanonKind, player: UUID): Ref? = connection.query(
        "SELECT id FROM canon WHERE kind = ? AND status = 'active' AND NOT archived AND list_contains(players, ?) ORDER BY id DESC LIMIT 1",
        kind.id, player,
    ) { Ref(RefKind.CANON, getLong(1)) }.firstOrNull()

    private fun mostSimilar(connection: Connection, text: String, candidates: List<Ref>): Ref? =
        indexer.similarity(connection, text, candidates).filterValues { it >= THRESHOLD }.maxByOrNull { it.value }?.key

    companion object {
        /** Similarity from which two texts say the same (M3 §4.3, §8.6). */
        const val THRESHOLD = 0.6

        /** Two interactions of a player further apart than this are not the same one. */
        val INTERACTION_WINDOW: Duration = Duration.ofMinutes(10)
    }
}
