package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.journal.Journal
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Two memories the Dreamer may merge (M3 §8.6): [similarity] is the larger of the two directions. */
data class MergeCandidate(val a: Ref, val b: Ref, val similarity: Double)

/**
 * What a hygiene pass did: canon entries and ideas [expired], gags [worn], memories [archived], notes made
 * [notesDone], and the [mergeCandidates] for the Dreamer (empty for [Hygiene.tidy]).
 */
data class HygieneReport(
    val expired: List<Ref> = emptyList(),
    val worn: List<Ref> = emptyList(),
    val archived: List<Ref> = emptyList(),
    val notesDone: List<Ref> = emptyList(),
    val mergeCandidates: List<MergeCandidate> = emptyList(),
) {
    /** One line for `memory_runs.report` and the log. */
    fun summary(): String = "expired ${expired.size}, worn ${worn.size}, archived ${archived.size}, " +
        "notes done ${notesDone.size}, merge candidates ${mergeCandidates.size}"
}

/**
 * The memory's housekeeping, done by the code alone (M3 §6.6, §8.6, §9.3). Nothing is deleted: rows are
 * closed, archived or marked through the [store], as [Source.ENGINE], so every change is audited.
 *
 * - [tidy], every 10 minutes (`MemoryJobs`): canon expirations, the storyteller's ideas whose hours of world play are
 *   over (M6 §6.2), worn gags, notes done.
 * - [beforeDream], before the first step of each dream: [tidy], then archival of faded trivial memories
 *   and the merge candidates for the Dreamer's step 6. The caller runs `Journal.checkpointAsync()` after
 *   the dream.
 *
 * A write the store refuses (the entry was resolved meanwhile) is logged and skipped; Journal exceptions
 * propagate to the caller.
 */
class Hygiene(
    private val store: MemoryStore,
    private val journal: Journal,
    private val clock: DuckPlayClock,
    private val indexer: Indexer,
    private val decisions: Decisions,
    private val zone: ZoneId,
) {
    private val log = Log.memory
    private val dueFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm")

    /** Canon and idea expirations, worn gags and notes done at [now]; [runId]: the memory job, for the audit. */
    suspend fun tidy(now: Instant, runId: Long? = null): HygieneReport {
        val author = Author(Source.ENGINE, runId = runId)
        val expired = expire(now, author) + expireIdeas(author)
        val report = HygieneReport(expired = expired, worn = wearGags(author), notesDone = closeNotes(now, author))
        if (report.expired.isNotEmpty() || report.worn.isNotEmpty() || report.notesDone.isNotEmpty()) log.info("hygiene: ${report.summary()}")
        return report
    }

    /**
     * The full pass before a dream at [now]: [tidy], archival, and up to [MAX_MERGE_CANDIDATES] merge
     * candidates among the memories written since [mergeSince] (null: all of them), so that pairs the
     * Dreamer already left apart are not offered at every dream.
     */
    suspend fun beforeDream(now: Instant, mergeSince: Instant?, runId: Long? = null): HygieneReport {
        val author = Author(Source.ENGINE, runId = runId)
        val tidied = tidy(now, runId)
        val report = tidied.copy(
            archived = archive(now, author),
            mergeCandidates = mergeCandidates(mergeSince),
        )
        log.info("hygiene before the dream: ${report.summary()}")
        return report
    }

    // ── Canon (M3 §6.6) ─────────────────────────────────────────────────────────────────────────────

    private suspend fun expire(now: Instant, author: Author): List<Ref> {
        val open = store.canon(CanonFilter(kinds = EXPIRING, open = true, limit = MAX_ROWS))
            .filter { CanonStatus.EXPIRED in it.kind.closedBy }
        val expired = mutableListOf<Ref>()
        for (entry in open) {
            val due = entry.dueAt
            val reason = when {
                due != null -> if (!now.isBefore(due + DUE_GRACE)) "came due ${dueFormat.format(due.atZone(zone))} with no resolution recorded" else null
                entry.kind in WITHOUT_DUE -> silentFor(entry, now)?.let { "went ${it}h of world play without mention" }
                else -> null
            } ?: continue
            if (expireOne(entry, reason, author)) expired += entry.ref
        }
        return expired
    }

    /** Hours of world play since [entry] was last mentioned, when that is at least [SILENCE_HOURS]; else null. */
    private suspend fun silentFor(entry: CanonEntry, now: Instant): Long? {
        val since = entry.lastUsedAt ?: entry.createdAt
        if (Duration.between(since, now) < Duration.ofHours(SILENCE_HOURS)) return null // less wall time than play time needed
        val hours = clock.worldPlayBetween(since, now) / 3_600
        return hours.takeIf { it >= SILENCE_HOURS }
    }

    /** Expires [entry] and leaves a note for the next briefing; false when the store refused (resolved meanwhile). */
    private suspend fun expireOne(entry: CanonEntry, reason: String, author: Author): Boolean {
        try {
            store.resolveCanon(entry.ref, CanonStatus.EXPIRED, NO_RESOLUTION, emptyList(), author)
        } catch (e: MemoryRejected) {
            log.warn("hygiene: could not expire ${entry.ref}: ${e.message}")
            return false
        }
        decisions.record(log, DecisionRecord.Kind.MEMORY, "canon_expired", entry.ref.toString(), reason)
        try {
            store.addNote(
                NoteDraft(NoteKind.EXPIRED, expiredNote(entry, reason), entry.players.map { it.uuid }, listOf(entry.ref)),
                author,
            )
        } catch (e: MemoryRejected) {
            log.warn("hygiene: ${entry.ref} expired, but its note was refused: ${e.message}")
        }
        return true
    }

    /**
     * `C-45 promise (« Mimosa ») came due 05-14 22:10 with no resolution recorded: « … »`, or `C-12 threat
     * (« Bartok ») went 104h of world play without mention: « … »`, within the note's budget. The names are
     * between « » so that [VoiceGuard] leaves them alone: a player called `Moi` or `Tu` is not a pronoun.
     */
    private fun expiredNote(entry: CanonEntry, reason: String): String {
        val who = if (entry.players.isEmpty()) "" else " (${entry.players.joinToString { "« ${it.name} »" }})"
        val head = "${entry.ref} ${entry.kind.id}$who $reason: « "
        val room = MemoryLimits.NOTE_TEXT - head.length - 2
        val statement = entry.statement.let { if (it.length <= room) it else it.take((room - 1).coerceAtLeast(0)) + "…" }
        return "$head$statement »"
    }

    // ── Ideas (M6 §6.2) ─────────────────────────────────────────────────────────────────────────────

    /** The storyteller's open ideas whose hours of world play are over, closed `expired` by the store: no note, nobody waits for them. */
    private suspend fun expireIdeas(author: Author): List<Ref> = store.expireIdeas(author).map { idea ->
        decisions.record(log, DecisionRecord.Kind.MEMORY, "idea_expired", idea.ref.toString(), "${idea.kind.id} « ${idea.title} »: ${idea.closeReason}")
        idea.ref
    }

    // ── Running gags (M3 §9.3) ──────────────────────────────────────────────────────────────────────

    private suspend fun wearGags(author: Author): List<Ref> {
        val gags = store.canon(CanonFilter(kinds = setOf(CanonKind.RUNNING_GAG), open = true, limit = MAX_ROWS))
            .filter { it.freshness != GagState.WORN && it.freshness != GagState.RETIRED && it.uses >= Decay.GAG_WEAR_USES }
        val worn = mutableListOf<Ref>()
        for (gag in gags) {
            val uses = usesSinceFresh(store.log(gag.ref))
            if (uses.size < Decay.GAG_WEAR_USES) continue
            val last = uses.takeLast(Decay.GAG_WEAR_USES)
            val hours = clock.worldPlayBetween(last.first(), last.last()) / 3_600.0
            if (!Decay.gagWorn(uses.size, hours)) continue
            val reason = "served ${Decay.GAG_WEAR_USES} times within ${hours.toLong()}h of world play"
            try {
                store.setGag(gag.ref, GagState.WORN, reason, author)
                decisions.record(log, DecisionRecord.Kind.MEMORY, "gag_worn", gag.ref.toString(), reason)
                worn += gag.ref
            } catch (e: MemoryRejected) {
                log.warn("hygiene: could not wear ${gag.ref}: ${e.message}")
            }
        }
        return worn
    }

    // ── Memories, notes (M3 §8.6) ───────────────────────────────────────────────────────────────────

    private suspend fun archive(now: Instant, author: Author): List<Ref> {
        val world = clock.worldPlaySeconds(now.toEpochMilli())
        val faded = journal.read(READ_TIMEOUT_MS) { connection ->
            connection.query(
                "SELECT id, importance, stability_h, reinforced_play_s FROM memories " +
                    "WHERE NOT archived AND importance <= ${Decay.ARCHIVE_MAX_IMPORTANCE} ORDER BY id",
                timeoutSeconds = QUERY_TIMEOUT_S,
            ) {
                val id = getLong(1)
                id.takeIf { Decay.archivable(getInt(2), getDouble(3), getLong(4), world) }
            }.filterNotNull()
        }
        return faded.map { Ref(RefKind.MEMORY, it) }.filter { ref ->
            try {
                store.archive(ref, true, "faded: importance ≤ ${Decay.ARCHIVE_MAX_IMPORTANCE}, at the strength floor " +
                    "for over ${Decay.ARCHIVE_AFTER_FLOOR_HOURS.toLong()}h of world play", author)
                true
            } catch (e: MemoryRejected) {
                log.warn("hygiene: could not archive $ref: ${e.message}")
                false
            }
        }
    }

    /**
     * Marks done the notes the Director has seen enough: a dream hint once shown (it is shown once, at its
     * player's join), or after [DREAM_HINT_LIFE] without being shown; any other note once shown
     * [NOTE_SHOWINGS] times, as many as the briefing shows it.
     */
    private suspend fun closeNotes(now: Instant, author: Author): List<Ref> =
        store.openNotes().filter { note ->
            when (note.kind) {
                NoteKind.DREAM_HINT -> note.shownCount > 0 || !now.isBefore(note.createdAt + DREAM_HINT_LIFE)
                else -> note.shownCount >= NOTE_SHOWINGS
            }
        }.filter { note ->
            try {
                store.markNote(note.ref, NoteStatus.DONE, author)
                true
            } catch (e: MemoryRejected) {
                log.warn("hygiene: could not close ${note.ref}: ${e.message}")
                false
            }
        }.map { it.ref }

    /**
     * Pairs of memories not archived that share a player, happened less than 2 hours apart, and whose
     * text matches the other's at [MERGE_SIMILARITY] or more ([Indexer.similarity], either direction);
     * at least one of them written since [since]. The [MAX_MERGE_CANDIDATES] most similar.
     */
    suspend fun mergeCandidates(since: Instant?): List<MergeCandidate> = journal.read(READ_TIMEOUT_MS) { connection ->
        val recent = if (since == null) "" else "AND greatest(a.created_at, b.created_at) >= ?"
        val pairs = connection.query(
            """
            SELECT a.id, b.id FROM memories a JOIN memories b ON a.id < b.id
            WHERE NOT a.archived AND NOT b.archived
              AND len(list_intersect(a.players, b.players)) > 0
              AND b.occurred_from < a.occurred_to + INTERVAL 2 HOUR
              AND a.occurred_from < b.occurred_to + INTERVAL 2 HOUR
              $recent
            ORDER BY a.id, b.id LIMIT $MAX_PAIRS
            """.trimIndent(),
            *listOfNotNull(since).toTypedArray(),
            timeoutSeconds = QUERY_TIMEOUT_S,
        ) { getLong(1) to getLong(2) }
        if (pairs.isEmpty()) return@read emptyList()
        val ids = pairs.flatMap { listOf(it.first, it.second) }.toSet()
        val texts = connection.query(
            "SELECT id, title, content, tags, entities FROM memories WHERE id IN (${ids.joinToString(",")})",
            timeoutSeconds = QUERY_TIMEOUT_S,
        ) {
            getLong(1) to (listOf(getString(2), getString(3)) + strings(4) + strings(5)).joinToString(" ")
        }.toMap()
        val partners = pairs.flatMap { (a, b) -> listOf(a to b, b to a) }.groupBy({ it.first }, { it.second })
        val scores = HashMap<Pair<Long, Long>, Double>()
        for ((id, others) in partners) {
            val text = texts[id] ?: continue
            indexer.similarity(connection, text, others.map { Ref(RefKind.MEMORY, it) }).forEach { (other, score) ->
                val key = minOf(id, other.id) to maxOf(id, other.id)
                scores.merge(key, score, ::maxOf)
            }
        }
        scores.filterValues { it >= MERGE_SIMILARITY }
            .map { (pair, score) -> MergeCandidate(Ref(RefKind.MEMORY, pair.first), Ref(RefKind.MEMORY, pair.second), score) }
            .sortedWith(compareByDescending<MergeCandidate> { it.similarity }.thenBy { it.a.id }.thenBy { it.b.id })
            .take(MAX_MERGE_CANDIDATES)
    }

    companion object {
        /** The resolution of an expired entry. */
        const val NO_RESOLUTION = "no resolution recorded"

        /** How long after its due time an entry without resolution expires. */
        val DUE_GRACE: Duration = Duration.ofHours(24)

        /** Hours of world play without mention after which a threat or prophecy without due time expires. */
        const val SILENCE_HOURS = 100L

        /**
         * Times a note other than a dream hint (`coherence`, `expired`, `operator`) is shown before it is done:
         * `MemoryBriefing` shows it in that many accepted briefings (and at the wake-up of its kind).
         */
        const val NOTE_SHOWINGS = 2

        /** How long a dream hint waits for its player's join before it is done. */
        val DREAM_HINT_LIFE: Duration = Duration.ofDays(7)

        /** The merge threshold, as for duplicates (M3 §4.3). */
        const val MERGE_SIMILARITY = 0.6

        /** Merge candidates handed to the Dreamer at most. */
        const val MAX_MERGE_CANDIDATES = 20

        /** Kinds that may expire. Arcs are not: the Dreamer, who plans them, resolves or abandons them. */
        private val EXPIRING = CanonKind.entries.filter { CanonStatus.EXPIRED in it.closedBy }.toSet()

        /** Kinds that expire after [SILENCE_HOURS] when they have no due time. */
        private val WITHOUT_DUE = setOf(CanonKind.THREAT, CanonKind.PROPHECY)

        private const val MAX_ROWS = 10_000
        private const val MAX_PAIRS = 5_000
        private const val READ_TIMEOUT_MS = 30_000L
        private const val QUERY_TIMEOUT_S = 20

        /**
         * The times a canon entry was served since it was last made fresh, oldest first, from its audit
         * rows ([MemoryStore.log]): an update whose `uses` column grew is a use, at `last_used_at` (else the
         * row's time); an update that set `freshness` to `fresh` from something else starts the count again.
         */
        fun usesSinceFresh(rows: List<MemoryLogRow>): List<Instant> {
            val uses = mutableListOf<Instant>()
            for (row in rows.sortedBy { it.ts }) {
                val before = row.before ?: continue
                val after = row.after ?: continue
                if (after.text("freshness") == GagState.FRESH.id && before.text("freshness") != GagState.FRESH.id) uses.clear()
                val was = before.number("uses") ?: continue
                val now = after.number("uses") ?: continue
                if (now > was) uses += after.text("last_used_at")?.let { runCatching { parseTime(it) }.getOrNull() } ?: row.ts
            }
            return uses.sorted()
        }

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.number(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

        private fun parseTime(text: String): Instant =
            runCatching { Instant.parse(text) }.getOrElse { OffsetDateTime.parse(text.replace(' ', 'T')).toInstant() }
    }
}
