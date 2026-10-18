package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.trace.ConversationRecord
import java.time.Instant
import java.util.UUID

/**
 * The Narrator's memory (design M3), as `NarratorServices.memory` exposes it: the store, recall, the
 * sheets, the play clocks, the jobs, and what the Director reads (its briefing, the `<memory>` block of
 * its wake-ups, the lines of its situation). [DisabledMemory] stands in when it is off or broken.
 */
interface Memory {
    val available: Boolean

    /** Why the memory is unavailable (briefing, `/narrator memory status`); null when it is available. */
    val unavailableReason: String?

    val store: MemoryStore
    val recall: Recall
    val sheets: PlayerSheets
    val clock: PlayClock
    val jobs: MemoryJobsControl

    /**
     * The memory sections of a new chapter's briefing (M3 §11.1), in [BriefingSection.TAGS] order, each
     * within its budget and with its reduced form; a failing source says so in its section. Every ref
     * rendered goes into [chapter]'s [ChapterMemory.shownRefs] and gets a `recalls` row (`briefing`).
     * [now]: epoch milliseconds.
     */
    suspend fun briefing(chapter: ChapterMemory, now: Long): List<BriefingSection>

    /**
     * The `<memory>` block for [signals] told to the Director at a wake-up or between two tool calls (M3
     * §11.2, §11.3): the sheet of a player who joins, the reminiscences of the events; null when there
     * is nothing to attach. Updates [chapter] (refs and sheets shown).
     */
    suspend fun attachments(signals: List<DirectorSignal>, chapter: ChapterMemory, now: Long): String?

    /** The memory lines of the situation (M3 §11.3: favor, due soon, attention), from a cache: never waits for the database. */
    fun situationLines(now: Long): List<String>

    /** A Director chapter closed for good, with or without a summary: its nap is queued (M3 §7.1). Any thread, never blocks. */
    fun chapterClosed(record: ConversationRecord)

    /** The memory run a chronicler or dreamer conversation belongs to (for the author and the quote checks); null otherwise. */
    fun runContext(conversationId: String): RunContext?

    /**
     * `SERVER_STOPPING`, before anything else is cancelled (M3-18): the jobs stop, the running one ends
     * `cancelled`, the world play clock is saved. A few seconds at most; nothing to do for a memory that
     * runs no job.
     */
    suspend fun stop() = Unit
}

/**
 * A running memory job, as its tools see it: kept by `MemoryAgents` for the life of its conversation.
 * [windowFrom]..[windowTo]: the Journal window it reads (quotes are checked against it); [chapter]: the
 * Director's conversation of a batch or a nap.
 */
data class RunContext(val runId: Long, val kind: RunKind, val windowFrom: Instant?, val windowTo: Instant?, val chapter: String?) {
    /** The author of what the job writes: [Source.CHRONICLER] for batches and naps, [Source.DREAMER] for dreams. */
    fun author(conversationId: String): Author =
        Author(if (kind == RunKind.DREAM) Source.DREAMER else Source.CHRONICLER, runId = runId, conversationId = conversationId)
}

/**
 * What the memory tracks for one open chapter (anti-repetition, M3 §10.6): the refs shown in it (briefing,
 * sheets, reminiscences, `recall`) and when each player's sheet was last shown (epoch milliseconds).
 * Touched by the Director's coroutine only.
 */
class ChapterMemory(val conversationId: String, val number: Int) {
    val shownRefs: MutableSet<Ref> = HashSet()
    val sheetsShownAt: MutableMap<UUID, Long> = HashMap()
}

/**
 * One memory section of the briefing: `<tag attributes>`, its lines, `</tag>`. [reduced]: its lines when
 * the briefing must shrink (M3 §11.1); null when the section is left out then. The Grimoire and the canon
 * are never reduced. Lines are ready for the model (escaped).
 */
data class BriefingSection(val tag: String, val attributes: String, val lines: List<String>, val reduced: List<String>? = null) {
    /** The section as text; null when [reducedForm] is asked and the section is left out of a reduced briefing. */
    fun render(reducedForm: Boolean = false): String? {
        val shown = if (reducedForm) reduced ?: return null else lines
        val open = if (attributes.isBlank()) "<$tag>" else "<$tag $attributes>"
        return (listOf(open) + shown + "</$tag>").joinToString("\n")
    }

    companion object {
        /** The memory sections of the briefing, in order (M3 §11.1). */
        val TAGS: List<String> = listOf("grimoire", "chronicle", "canon", "sheets", "memories", "notes")

        /** The sections that stay whole in a reduced briefing. */
        val NEVER_REDUCED: Set<String> = setOf("grimoire", "canon")
    }
}

/**
 * The memory when it is off (`memory.enabled` false), when the Journal is unavailable, or when the memory
 * could not start: nothing is read nor written, writes fail with [MemoryRejected] saying why, every memory
 * section of the briefing says `(memory unavailable: <reason>)`, there is nothing to attach nor to add to
 * the situation, and no job runs.
 */
class DisabledMemory(private val reason: String) : Memory {
    override val available: Boolean get() = false

    override val unavailableReason: String get() = reason

    override val store: MemoryStore = DisabledStore(reason)

    override val recall: Recall = object : Recall {
        override suspend fun search(query: RecallQuery): List<RecallHit> = emptyList()
    }

    override val sheets: PlayerSheets = object : PlayerSheets {
        override suspend fun sheet(player: UUID, now: Long): PlayerSheet? = null

        override fun render(sheet: PlayerSheet, detail: SheetDetail, now: Long, parts: Set<SheetPart>): String = unavailable()
    }

    override val clock: PlayClock = object : PlayClock {
        override fun worldPlaySeconds(atMillis: Long): Long = 0

        override fun playerPlaySeconds(player: UUID, atMillis: Long): Long = 0
    }

    override val jobs: MemoryJobsControl = object : MemoryJobsControl {
        override fun status() = MemoryStatus(
            paused = false, blockedUntil = null, running = null, queued = emptyList(), lastBatch = null, lastNap = null,
            lastDream = null, nextDreamAt = null, worldPlaySeconds = 0, rejected24h = 0, voiceWarnings24h = 0,
        )

        override fun requestChronicle(by: String): Result<Long> = Result.failure(IllegalStateException(unavailable()))

        override fun requestDream(by: String): Result<Long> = Result.failure(IllegalStateException(unavailable()))

        override fun pause(by: String) = Unit

        override fun resume(by: String) = Unit
    }

    override suspend fun briefing(chapter: ChapterMemory, now: Long): List<BriefingSection> =
        BriefingSection.TAGS.map { tag -> BriefingSection(tag, "", listOf("(${unavailable()})"), listOf("(${unavailable()})")) }

    override suspend fun attachments(signals: List<DirectorSignal>, chapter: ChapterMemory, now: Long): String? = null

    override fun situationLines(now: Long): List<String> = emptyList()

    override fun chapterClosed(record: ConversationRecord) = Unit

    override fun runContext(conversationId: String): RunContext? = null

    private fun unavailable() = "memory unavailable: $reason"

    /** Reads find nothing; writes are refused with the reason. */
    private class DisabledStore(private val reason: String) : MemoryStore {
        private fun refuse(): Nothing = throw MemoryRejected("the memory is unavailable: $reason")

        override suspend fun addMemory(draft: MemoryDraft, author: Author): Added<MemoryEntry> = refuse()

        override suspend fun addCanon(draft: CanonDraft, author: Author): Added<CanonEntry> = refuse()

        override suspend fun resolveCanon(ref: Ref, status: CanonStatus, note: String, winners: List<UUID>, author: Author): CanonEntry = refuse()

        override suspend fun useCanon(ref: Ref, at: Instant, author: Author): CanonEntry = refuse()

        override suspend fun addInteraction(draft: InteractionDraft, author: Author): Added<Interaction> = refuse()

        override suspend fun setTrait(draft: TraitDraft, author: Author): Trait = refuse()

        override suspend fun retireTrait(player: UUID, trait: String, reason: String, author: Author) {
            refuse()
        }

        override suspend fun setSkill(player: UUID, skill: Skill, score: Int, rationale: String, evidence: List<Ref>, author: Author): SkillScore =
            refuse()

        override suspend fun setRelation(player: UUID, other: UUID, kind: RelationKind, note: String, evidence: List<Ref>, author: Author): Relation =
            refuse()

        override suspend fun writeDoc(key: DocKey, content: String, evidence: List<Ref>, author: Author): MemoryDoc = refuse()

        override suspend fun addChronicle(draft: ChronicleDraft, author: Author): ChronicleEntry = refuse()

        override suspend fun addNote(draft: NoteDraft, author: Author): Note = refuse()

        override suspend fun markNote(ref: Ref, status: NoteStatus, author: Author): Note = refuse()

        override suspend fun setGag(ref: Ref, state: GagState, note: String, author: Author): CanonEntry = refuse()

        override suspend fun merge(keep: Ref, merged: List<Ref>, content: String?, author: Author): MemoryEntry = refuse()

        override suspend fun archive(ref: Ref, archived: Boolean, reason: String, author: Author) {
            refuse()
        }

        override suspend fun recorded(refs: List<Ref>, channel: RecallChannel, context: RecallContext) {
            refuse()
        }

        override suspend fun rollbackDoc(key: String, version: Int, author: Author): MemoryDoc = refuse()

        override suspend fun revertRun(runId: Long, author: Author): Int = refuse()

        override suspend fun state(key: String): String? = null

        override suspend fun setState(key: String, value: String) {
            refuse()
        }

        override suspend fun row(ref: Ref): MemoryRow? = null

        override suspend fun memory(ref: Ref): MemoryEntry? = null

        override suspend fun canon(ref: Ref): CanonEntry? = null

        override suspend fun canon(filter: CanonFilter): List<CanonEntry> = emptyList()

        override suspend fun interactions(player: UUID, limit: Int, marked: Boolean): List<Interaction> = emptyList()

        override suspend fun traits(player: UUID, includeRetired: Boolean): List<Trait> = emptyList()

        override suspend fun skills(player: UUID): List<SkillScore> = emptyList()

        override suspend fun relations(player: UUID): List<Relation> = emptyList()

        override suspend fun doc(key: String, version: Int?): MemoryDoc? = null

        override suspend fun docVersions(key: String): List<MemoryDoc> = emptyList()

        override suspend fun chronicle(level: ChronicleLevel, limit: Int): List<ChronicleEntry> = emptyList()

        override suspend fun openNotes(): List<Note> = emptyList()

        override suspend fun log(ref: Ref): List<MemoryLogRow> = emptyList()
    }
}
