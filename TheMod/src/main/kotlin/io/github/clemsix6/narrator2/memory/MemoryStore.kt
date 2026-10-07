package io.github.clemsix6.narrator2.memory

import java.time.Instant
import java.util.UUID

/**
 * Thrown by the store when a write breaks a rule (M3 §4.2). [message] is written for the model, in
 * English, and says how to fix it: tools return it as `Invalid arguments: <message>`.
 */
class MemoryRejected(message: String) : Exception(message)

/** The result of an add: the new row, or the open row it duplicates (nothing written, M3 §4.3). */
sealed interface Added<T> {
    val row: T

    data class New<T>(override val row: T) : Added<T>

    data class Duplicate<T>(override val row: T) : Added<T>
}

/**
 * Which canon entries to list. [kinds]: all when empty; [open]: true for open entries only, false for
 * closed ones, null for both; [player]: the entries that concern this player (or everyone). Archived
 * entries are never listed. Ordered by due time (none last), then importance, then newest.
 */
data class CanonFilter(
    val kinds: Set<CanonKind> = emptySet(),
    val open: Boolean? = true,
    val player: UUID? = null,
    val limit: Int = 50,
)

/** Where a row was shown (`recalls` columns): the conversation, the Director turn, the event it was recalled for. */
data class RecallContext(val conversationId: String?, val turnId: Long?, val eventId: Long?)

/**
 * The memory's only point of writing (M3 §2, §4.2): whoever writes (Director, Chronicler, Dreamer, the
 * code, an operator), every write goes through here. Each write is one `Journal.write` job, in which, in
 * this order:
 *
 * 1. vocabularies and bounds are checked (kinds, statuses and their transitions, tags, axes, lengths of
 *    [MemoryLimits], [SheetSection] and [ChronicleLevel], known players, existing refs);
 * 2. [VoiceGuard] checks every text a model wrote (`quote` fields are exempt, but bounded);
 * 3. quotes are checked for [Source.CHRONICLER]: a canon quote must be words the Narrator said in the
 *    run's window, an interaction quote words of that player;
 * 4. duplicates are looked for (M3 §4.3): an equivalent open row makes the add an [Added.Duplicate];
 * 5. the row, its index terms ([Indexer]) and its `memory_log` row are written;
 * 6. a `memory` decision is recorded (`written`, `duplicate`, `rejected`).
 *
 * A broken rule throws [MemoryRejected], with nothing written. A Journal that cannot write throws its
 * own exception (`JournalUnavailableException`, `JournalTimeoutException`). Reads run on the Journal's
 * read pool, never on the writer. Rows are never deleted.
 */
interface MemoryStore {
    // ── Writes ──────────────────────────────────────────────────────────────────────────────────────

    suspend fun addMemory(draft: MemoryDraft, author: Author): Added<MemoryEntry>

    /** A nickname or title replaces the player's active one, which is retired (`replaces`). */
    suspend fun addCanon(draft: CanonDraft, author: Author): Added<CanonEntry>

    /**
     * Moves an open canon entry to [status] ([CanonKind.allows]); [note] is its resolution (French, third
     * person). [winners]: required for `won`, refused otherwise. `running_gag` entries retire through
     * [setGag] as well.
     */
    suspend fun resolveCanon(ref: Ref, status: CanonStatus, note: String, winners: List<UUID>, author: Author): CanonEntry

    /** A running gag or a nickname served again at [at]: `uses + 1`, `last_used_at`. */
    suspend fun useCanon(ref: Ref, at: Instant, author: Author): CanonEntry

    suspend fun addInteraction(draft: InteractionDraft, author: Author): Added<Interaction>

    /** Sets the player's row for the trait (the history stays in `memory_log`); changing pole caps the confidence (M3 §9.3). */
    suspend fun setTrait(draft: TraitDraft, author: Author): Trait

    /** The row stays, `retired`. */
    suspend fun retireTrait(player: UUID, trait: String, reason: String, author: Author)

    suspend fun setSkill(
        player: UUID,
        skill: Skill,
        score: Int,
        rationale: String,
        evidence: List<Ref>,
        author: Author,
    ): SkillScore

    /** How [player] stands toward [other]: two known players, distinct. */
    suspend fun setRelation(
        player: UUID,
        other: UUID,
        kind: RelationKind,
        note: String,
        evidence: List<Ref>,
        author: Author,
    ): Relation

    /** A new version of a document, within its budget. */
    suspend fun writeDoc(key: DocKey, content: String, evidence: List<Ref>, author: Author): MemoryDoc

    suspend fun addChronicle(draft: ChronicleDraft, author: Author): ChronicleEntry

    suspend fun addNote(draft: NoteDraft, author: Author): Note

    /** A note was shown to the Director ([NoteStatus.SHOWN], `shown_count + 1`), or is [NoteStatus.DONE]. */
    suspend fun markNote(ref: Ref, status: NoteStatus, author: Author): Note

    /** Freshness of a running gag; [GagState.RETIRED] also closes the entry. */
    suspend fun setGag(ref: Ref, state: GagState, note: String, author: Author): CanonEntry

    /**
     * Merges [merged] into [keep]: they are archived with `merged_into`, their evidence and recalls join
     * [keep], which keeps the largest stability; [content], if given, is [keep]'s new content.
     */
    suspend fun merge(keep: Ref, merged: List<Ref>, content: String?, author: Author): MemoryEntry

    /** Archives a row (it stays searchable with `include_closed`), or restores it. */
    suspend fun archive(ref: Ref, archived: Boolean, reason: String, author: Author)

    /** [refs] were shown through [channel]: one `recalls` row each, and the memories are reinforced (M3 §9.2). */
    suspend fun recorded(refs: List<Ref>, channel: RecallChannel, context: RecallContext)

    /** Copies [version] of the document [key] as its new version. */
    suspend fun rollbackDoc(key: String, version: Int, author: Author): MemoryDoc

    /** Undoes every write of the run [runId], newest first, from `memory_log`; returns how many were undone. */
    suspend fun revertRun(runId: Long, author: Author): Int

    /** A value of `memory_state`; null when unset. */
    suspend fun state(key: String): String?

    suspend fun setState(key: String, value: String)

    // ── Reads (the Journal's read pool, never the writer) ───────────────────────────────────────────

    /** Any citable row: memory, canon entry, interaction, chronicle entry or note. */
    suspend fun row(ref: Ref): MemoryRow?

    suspend fun memory(ref: Ref): MemoryEntry?

    suspend fun canon(ref: Ref): CanonEntry?

    suspend fun canon(filter: CanonFilter): List<CanonEntry>

    /** The player's latest interactions, newest first; [marked]: only those of valence ±3 or disrespect. */
    suspend fun interactions(player: UUID, limit: Int, marked: Boolean = false): List<Interaction>

    /** The player's traits; retired ones only when [includeRetired]. */
    suspend fun traits(player: UUID, includeRetired: Boolean = false): List<Trait>

    suspend fun skills(player: UUID): List<SkillScore>

    suspend fun relations(player: UUID): List<Relation>

    /** A version of a document; the current one when [version] is null. */
    suspend fun doc(key: String, version: Int? = null): MemoryDoc?

    /** Every version of a document, newest first. */
    suspend fun docVersions(key: String): List<MemoryDoc>

    /** The latest entries of [level] that are not superseded, newest first. */
    suspend fun chronicle(level: ChronicleLevel, limit: Int): List<ChronicleEntry>

    /** Notes that are not done and not expired, oldest first. */
    suspend fun openNotes(): List<Note>

    /** The changes of [ref]'s row, oldest first. */
    suspend fun log(ref: Ref): List<MemoryLogRow>
}
