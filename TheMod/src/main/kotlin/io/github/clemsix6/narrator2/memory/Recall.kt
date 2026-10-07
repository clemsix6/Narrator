package io.github.clemsix6.narrator2.memory

import java.sql.Connection
import java.util.UUID

/**
 * Keeps `search_docs` and `search_postings` in step with the memory rows (M3 §10.3), inside the writing
 * job's transaction: the store calls it on the connection of its `Journal.write` job. Text indexed: a
 * memory's `title + content + tags + entities`; a canon entry's `statement + quote + kind`; an
 * interaction's `summary + quote + kind`; a chronicle entry's `title + summary`. Notes are not indexed.
 * Archived rows stay indexed (recall filters them at query time). Rewriting a row is [remove] then
 * [index]. Implemented by `search/SearchIndex.kt` with the French analyzer (M3-04).
 */
interface Indexer {
    /** Indexes [ref]'s [text] (replacing what was indexed for [ref]). */
    fun index(connection: Connection, ref: Ref, text: String)

    fun remove(connection: Connection, ref: Ref)

    /**
     * How close [text] is to each of [candidates]: the BM25 of [text] as a query against the candidate,
     * divided by its BM25 against a document made of [text] itself, with the index's statistics. About 1
     * means the same words; the duplicate (M3 §4.3) and merge (§8.6) threshold is 0.6. A candidate that
     * is not indexed, or shares no term, scores 0. Any connection: the writer's inside a job, or a read one.
     */
    fun similarity(connection: Connection, text: String, candidates: Collection<Ref>): Map<Ref, Double>
}

/** Why a search runs: it sets the thresholds and the controlled randomness (M3 §10.4, §10.6). */
enum class RecallPurpose {
    /** The `recall` tool and `/narrator memory recall`: sorted by score. */
    TOOL,

    /** The `<memories>` section of a chapter's briefing. */
    BRIEFING,

    /** The sheet of a player who joins. */
    JOIN,

    /** Automatic reminiscences for the events of a wake-up: thresholds, then a draw weighted by the squared score. */
    REMINISCENCE,

    /** The Dreamer's inputs. */
    DREAM,
}

/**
 * A search of the memory (M3 §10.4).
 *
 * @property text words to look for (French, or game ids); null to filter and rank without full text.
 * @property players rows that concern one of them rank higher (and match without [text]).
 * @property entities game ids and refs shared with the rows (`minecraft:lava`, `C-45`).
 * @property tags tags of the vocabulary ([MemoryTags]).
 * @property kinds which tables to search; notes never are.
 * @property sinceMillis only rows of this time or later (epoch milliseconds).
 * @property includeClosed also resolved and retired canon, and archived rows.
 * @property exclude rows not to return (already shown in the chapter, M3 §10.6).
 * @property seed the controlled randomness of [RecallPurpose.REMINISCENCE]: the event id, so tests are
 *   deterministic; null elsewhere.
 */
data class RecallQuery(
    val text: String?,
    val players: List<UUID> = emptyList(),
    val entities: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val kinds: Set<RefKind> = RefKind.searchable,
    val sinceMillis: Long? = null,
    val includeClosed: Boolean = false,
    val limit: Int = 8,
    val purpose: RecallPurpose,
    val exclude: Set<Ref> = emptySet(),
    val seed: Long? = null,
)

/**
 * One result. [relevance] ∈ [0, 1] (full text and shared players, tags, ids); [strength] ∈ [0.02, 1] (the
 * memory's decay, 1 for open canon); [score] combines them with importance and age; [line] is the row
 * rendered for the model, one line, French text escaped (`recall` output, M3 §5.4).
 */
data class RecallHit(val ref: Ref, val score: Double, val relevance: Double, val strength: Double, val line: String)

/**
 * Searches the memory (M3 §10.4): SQL filters, BM25 in DuckDB, strength and anti-repetition. Reads only:
 * the caller records what it shows ([MemoryStore.recorded]). Implemented by `search/HybridRecall.kt`.
 */
interface Recall {
    /** The best hits of [query], best first, [RecallQuery.limit] at most. */
    suspend fun search(query: RecallQuery): List<RecallHit>
}
