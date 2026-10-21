package io.github.clemsix6.narrator2.memory

import java.time.Instant
import java.util.UUID

/**
 * Thrown by the store when a write breaks a rule (M3 §4.2). [message] is written for the model, in
 * English, and says how to fix it: tools return it as `Invalid arguments: <message>`, or as
 * `DENIED (<rule>): <message>` when [rule] names a limit of the pace rather than a wrong argument (M4 §11:
 * `arc_limit`).
 */
class MemoryRejected(message: String, val rule: String? = null) : Exception(message)

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
 * 2. [VoiceGuard] checks every text written, the players' names masked (`quote` fields are exempt, but
 *    bounded); in a model's text, a passage between quotes stays short;
 * 3. what each author may write is checked (decision 8: a commitment of the Narrator comes from its own
 *    words):
 *    - [Source.CHRONICLER]: a promise, threat, challenge, prophecy, law, nickname, title or place needs a
 *      quote; any canon quote is 3 words or 15 characters at least, copied from a Narrator line cited in
 *      its evidence (an `e…` line of the `narrator` or `operator` channel, a `t…` call of `say`,
 *      `give_book` or `show_title`). An interaction quote is words of that player in the run's window;
 *    - [Source.DREAMER] (it reads the players' chat, never hears the Narrator): only observations among
 *      the memories; only planned arcs and lore in the canon, without quote; resolves arcs only; no
 *      interaction. Its running gags change through [setGag];
 *    - [Source.STORYTELLER] (it invents what comes next, M6 §6.5): no memory, no interaction; only planned arcs and
 *      lore in the canon, without quote; resolves a planned arc only, to abandon it. It alone writes ideas;
 *    - a row the mechanics engine tracks (`mechanic`, M4 §3.3) is written by the Director's engine tools
 *      only, and changes status only through [Source.ENGINE] or [Source.OPERATOR]; arcs (M4 §10) have a
 *      theme that rests once their arc closed, two at most are active, and an active one is due within 7 days;
 * 4. duplicates are looked for (M3 §4.3): an equivalent open row makes the add an [Added.Duplicate] (never
 *    for a row the engine tracks);
 * 5. the row, its index terms ([Indexer]) and its `memory_log` row are written;
 * 6. a `memory` decision is recorded (`written`, `duplicate`, `rejected`).
 *
 * A broken rule throws [MemoryRejected], with nothing written. A Journal that cannot write throws its
 * own exception (`JournalUnavailableException`, `JournalTimeoutException`). Reads run on the Journal's
 * read pool, never on the writer. Rows are never deleted, but by [revertRun] (their content stays in
 * `memory_log`).
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

    /** A note for the Director; [Added.Duplicate] when the same is open already ([Dedup.note]). */
    suspend fun addNote(draft: NoteDraft, author: Author): Added<Note>

    /** A note was shown to the Director ([NoteStatus.SHOWN], `shown_count + 1`), or is [NoteStatus.DONE]. */
    suspend fun markNote(ref: Ref, status: NoteStatus, author: Author): Note

    /** Freshness of a running gag; [GagState.RETIRED] also closes the entry. */
    suspend fun setGag(ref: Ref, state: GagState, note: String, author: Author): CanonEntry

    /**
     * Merges [merged] into [keep]: they are archived with `merged_into`, their evidence and recalls join
     * [keep], which keeps the largest stability; [content], if given, is [keep]'s new content.
     */
    suspend fun merge(keep: Ref, merged: List<Ref>, content: String?, author: Author): MemoryEntry

    /**
     * Archives a row, or restores it. A row the code archived (the hygiene, a merge) stays searchable with
     * `include_closed`; one an operator archived (`/narrator memory forget`) does not: `recall` never finds
     * it again, even with `include_closed` (the raw tables stay readable by `sql`).
     */
    suspend fun archive(ref: Ref, archived: Boolean, reason: String, author: Author)

    /** [refs] were shown through [channel]: one `recalls` row each, and the memories are reinforced (M3 §9.2). */
    suspend fun recorded(refs: List<Ref>, channel: RecallChannel, context: RecallContext)

    /** Copies [version] of the document [key] as its new version. */
    suspend fun rollbackDoc(key: String, version: Int, author: Author): MemoryDoc

    /**
     * Undoes every write of the run [runId] from `memory_log`, newest first; returns how many were undone.
     * A row the run inserted is **deleted** (with its index terms; its content stays in `memory_log`), a row
     * it changed gets back its columns from before the run. Refused when the run was reverted already, or
     * when a row it touched changed since (by another author or run).
     *
     * A reverted nap or dream that wrote something did not happen: its `memory_runs` row turns `failed`
     * (`reverted by …`), so that the catch-up naps the chapter again; a dream also forgets its reflections
     * on players (`memory_state` `reflected:<uuid>` written while it ran), so the next dream reads them
     * again. What it does not undo:
     * - `recalls` rows and reinforcements, the rest of `memory_state`, and other `memory_runs` rows;
     * - refs to a deleted row left elsewhere: the `covers` of a later chronicle entry, the evidence of a
     *   trait, skill or relation written by another run, `recalls.ref_id`;
     * - `canon.due_signaled_at`, which is not audited: an entry the run changed gets back the value it had
     *   before the run, so a `DUE` already signaled since may be signaled again.
     */
    suspend fun revertRun(runId: Long, author: Author): Int

    /**
     * An idea of the storyteller (M6 §6.2), `open`, due after [IdeaDraft.playHours] of world play, indexed so that a
     * later idea can be compared with it. Only [Source.STORYTELLER] writes one. Checked: its texts ([VoiceGuard]), notes
     * and bounds ([MemoryLimits]); known players; refs that exist, facts only ([RefKind.facts]), an open arc among them
     * for an `arc_step`; a module named for a `module` only, a brief of [MemoryLimits.IDEA_BRIEF_MIN] to
     * [MemoryLimits.IDEA_BRIEF] characters for a `forge` only (whether the module is ready is the tool's to check: the
     * arsenal is not in the database); no repeat of an idea of the last 14 days (BM25 against it at 0.6 of BM25 against
     * itself). A new season closes the open one (`dropped`, `replaced by P-…`); beyond [MemoryLimits.OPEN_IDEAS] open
     * ideas, the oldest other than the season, never dealt if one is, expires (`superseded by P-…`). Never a duplicate.
     */
    suspend fun addIdea(draft: IdeaDraft, author: Author): Added<Idea>

    /** Closes the open idea [ref]: `dropped`, with [reason] (≤ [MemoryLimits.IDEA_DROP_REASON]). Nothing is deleted. */
    suspend fun dropIdea(ref: Ref, reason: String, author: Author): Idea

    /** Closes the open ideas whose hours of world play are over: `expired` (the hygiene, M6 §6.2). Returns them. */
    suspend fun expireIdeas(author: Author): List<Idea>

    /**
     * The ideas [refs] were dealt to the Director through [channel] (`inspire`, a heartbeat, M6 §5): `dealt + 1`,
     * `last_dealt_at`, and one `recalls` row each. Refs of another kind are refused (an argument error of the caller);
     * an idea that does not exist is left out, like [recorded].
     */
    suspend fun dealt(refs: List<Ref>, channel: RecallChannel, context: RecallContext)

    // ── Reads (the Journal's read pool, never the writer) ───────────────────────────────────────────

    /** Any citable row: memory, canon entry, interaction, chronicle entry, note or idea. */
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

    /** Notes that are not done, oldest first. */
    suspend fun openNotes(): List<Note>

    /** The ideas of [status] (every status when null), the newest first, [limit] at most. */
    suspend fun ideas(status: IdeaStatus? = IdeaStatus.OPEN, limit: Int = 50): List<Idea>

    /** The changes of [ref]'s row, oldest first. */
    suspend fun log(ref: Ref): List<MemoryLogRow>
}
