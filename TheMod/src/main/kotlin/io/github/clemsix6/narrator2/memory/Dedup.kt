package io.github.clemsix6.narrator2.memory

import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Finds the open row an add would duplicate (M3 §4.3). The Director and the Chronicler may both record
 * the same thing: the second pass is the safety net, the duplicate is not wanted (an interaction counted
 * twice moves the favor twice). The texts are compared by [Indexer.similarity] (BM25 against the row over
 * BM25 against itself); [THRESHOLD] or more is the same.
 *
 * What the mechanics engine writes is precise (M4 §3.3): a row it tracks is never looked up here (the
 * store skips it), and its interactions without evidence are the same as another only for the same canon
 * entry ([interaction]).
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
     * is the same one, the most recent first: it shares an evidence event or a tool call; or one of the two
     * has no evidence at all (the Director, who notes an interaction as it happens, may cite none, and its
     * words never match the Chronicler's); or its text is at least [THRESHOLD] similar. Null when none.
     *
     * The engine's interactions without evidence ([engine] for the draft, `source = engine` for a row: a
     * challenge's result, its stakes, a rule's punishment) cite no Journal fact, yet each is its own: two
     * challenges won within ten minutes are two. One of them is the same as another only for the same
     * [canon] entry (M4 §3.3, review finding 12).
     */
    fun interaction(
        connection: Connection,
        player: UUID,
        kind: InteractionKind,
        at: Instant,
        events: List<Long>,
        tools: List<Long>,
        text: String,
        canon: Ref?,
        engine: Boolean,
    ): Ref? {
        val bare = events.isEmpty() && tools.isEmpty()
        val engineBare = engine && bare
        val candidates = connection.query(
            "SELECT id, canon_id, source = 'engine' AND len(evidence_events) = 0 AND len(tool_calls) = 0, " +
                "list_has_any(evidence_events, ?) OR list_has_any(tool_calls, ?) OR (len(evidence_events) = 0 AND len(tool_calls) = 0) " +
                "FROM interactions WHERE player_uuid = ? AND kind = ? AND NOT archived AND ts BETWEEN ? AND ? ORDER BY id DESC",
            longArray(events), longArray(tools), player, kind.id, at - INTERACTION_WINDOW, at + INTERACTION_WINDOW,
        ) { Candidate(Ref(RefKind.INTERACTION, getLong(1)), longOrNull(2), getBoolean(3), getBoolean(4)) }
        // true: the same; false: another; null: their texts decide.
        fun Candidate.same(): Boolean? = when {
            engineBare || this.engineBare -> canon != null && canonId == canon.id
            bare || shared -> true
            else -> null
        }
        candidates.firstOrNull { it.same() == true }?.let { return it.ref }
        return mostSimilar(connection, text, candidates.filter { it.same() == null }.map { it.ref })
    }

    /** An interaction an add may duplicate: its [canonId], whether it is the engine's without evidence, whether it shares the draft's evidence or has none. */
    private class Candidate(val ref: Ref, val canonId: Long?, val engineBare: Boolean, val shared: Boolean)

    /** An unarchived memory that shares an evidence (event or tool call), a player and a tag with the draft; null when none. */
    fun memory(connection: Connection, players: List<UUID>, tags: List<String>, events: List<Long>, tools: List<Long>): Ref? {
        if (events.isEmpty() && tools.isEmpty()) return null
        return connection.query(
            "SELECT id FROM memories WHERE NOT archived AND (list_has_any(evidence_events, ?) OR list_has_any(evidence_tools, ?)) " +
                "AND list_has_any(players, ?) AND list_has_any(tags, ?) ORDER BY id DESC LIMIT 1",
            longArray(events), longArray(tools), uuidArray(players), stringArray(tags),
        ) { Ref(RefKind.MEMORY, getLong(1)) }.firstOrNull()
    }

    /**
     * A note not done yet of the same kind, about the same players and the same rows of the memory: the same
     * incoherence noted twice, by the nap then by the dream (N-8 and N-12 on C-5 in M3-19). Null when the
     * draft cites no row: a note without refs (an operator's word, a dream hint) is never the same as another.
     */
    fun note(connection: Connection, kind: NoteKind, players: List<UUID>, refs: List<Ref>): Ref? {
        if (refs.isEmpty()) return null
        return connection.query(
            "SELECT id FROM notes WHERE kind = ? AND status <> 'done' AND list_sort(players) = list_sort(?) " +
                "AND list_sort(list_distinct(refs)) = list_sort(?) ORDER BY id DESC LIMIT 1",
            kind.id, uuidArray(players.distinct()), longArray(refs.map { it.id }.distinct()),
        ) { Ref(RefKind.NOTE, getLong(1)) }.firstOrNull()
    }

    /** The player's active, unarchived entry of a [CanonKind.onePerPlayer] kind (the nickname, the title); null when none. */
    fun active(connection: Connection, kind: CanonKind, player: UUID): Ref? = connection.query(
        "SELECT id FROM canon WHERE kind = ? AND status = 'active' AND NOT archived AND list_contains(players, ?) ORDER BY id DESC LIMIT 1",
        kind.id, player,
    ) { Ref(RefKind.CANON, getLong(1)) }.firstOrNull()

    private fun mostSimilar(connection: Connection, text: String, candidates: List<Ref>): Ref? {
        if (candidates.isEmpty()) return null
        return indexer.similarity(connection, text, candidates).filterValues { it >= THRESHOLD }.maxByOrNull { it.value }?.key
    }

    companion object {
        /** Similarity from which two texts say the same (M3 §4.3, §8.6). */
        const val THRESHOLD = 0.6

        /** Two interactions of a player further apart than this are not the same one. */
        val INTERACTION_WINDOW: Duration = Duration.ofMinutes(10)
    }
}
