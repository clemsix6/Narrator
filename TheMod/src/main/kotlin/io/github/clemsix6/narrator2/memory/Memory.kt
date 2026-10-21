package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.trace.ConversationRecord
import kotlinx.coroutines.CancellationException
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
     * within its budget and with its reduced form; a failing source says so in its section. What the form
     * the briefing renders ([reduced] or not) shows goes into [chapter] ([ChapterMemory.shownRefs], the
     * condensed sheets in [ChapterMemory.sheetsShownAt]) and waits in [ChapterMemory.unrecorded] for the
     * request to be accepted. [now]: epoch milliseconds.
     */
    suspend fun briefing(chapter: ChapterMemory, now: Long, reduced: Boolean = false): List<BriefingSection>

    /**
     * The `<memory>` block for [signals] told to the Director at a wake-up or between two tool calls (M3
     * §11.2, §11.3): the sheet of a player who joins, the reminiscences of the events; null when there
     * is nothing to attach. Updates [chapter] (refs and sheets shown, and what waits to be recorded).
     */
    suspend fun attachments(signals: List<DirectorSignal>, chapter: ChapterMemory, now: Long): String?

    /**
     * What [shown] showed reached the model: the Director calls it once the request that carried it is
     * accepted, off its own coroutine ([ChapterMemory.takeUnrecorded]). Each ref gets its `recalls` row,
     * which reinforces it and starts its cooldown; each note shown is marked `shown`, once. A write that
     * fails is logged (`WARN`); only a cancellation goes through.
     */
    suspend fun recordShown(shown: List<Shown>) = Shown.record(store, shown)

    /** The memory lines of the situation (M3 §11.3: favor, due soon, attention), from a cache: never waits for the database. */
    fun situationLines(now: Long): List<String>

    /**
     * The storyteller's open ideas (M6 §6.2), for the heartbeat's `Idea:` line and `inspire`: from the memory's cache,
     * never the database (the briefing's `<programme>` reads the store, like its other sections). An idea past its
     * deadline may stay in it until the hygiene closes it: a reader checks [Idea.expiresPlaySeconds]. Any thread; never
     * waits.
     */
    fun openIdeas(): List<Idea>

    /** A Director chapter closed for good, with or without a summary: its nap is queued (M3 §7.1). Any thread, never blocks. */
    fun chapterClosed(record: ConversationRecord)

    /** The memory run a chronicler, dreamer or storyteller conversation belongs to (for the author and the quote checks); null otherwise. */
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
 * Director's conversation of a batch or a nap; [role]: the role of the conversation, set by `MemoryAgents.run` (a
 * dream's steps are the Dreamer's, but its programme is the storyteller's, M6 §6.3).
 */
data class RunContext(
    val runId: Long,
    val kind: RunKind,
    val windowFrom: Instant?,
    val windowTo: Instant?,
    val chapter: String?,
    val role: Role? = null,
) {
    /**
     * The author of what the job writes: [Source.STORYTELLER] for the storyteller's conversation, else
     * [Source.CHRONICLER] for batches and naps, [Source.DREAMER] for dreams.
     */
    fun author(conversationId: String): Author {
        val source = when {
            role == Role.STORYTELLER -> Source.STORYTELLER
            kind == RunKind.DREAM -> Source.DREAMER
            else -> Source.CHRONICLER
        }
        return Author(source, runId = runId, conversationId = conversationId)
    }
}

/**
 * What the memory tracks for one open chapter (anti-repetition, M3 §10.6): the refs shown in it (briefing,
 * sheets, reminiscences, `recall`), when each player's condensed sheet was last shown (epoch milliseconds),
 * and what the requests not accepted yet showed ([unrecorded]). Touched by the Director's coroutine only.
 *
 * A request the model does not accept told it nothing: the Director puts the chapter back as it was
 * before ([mark], [Mark.restore]), so the same sheets and memories are shown again, and what it showed is
 * never recorded. A request accepted hands its [unrecorded] to [Memory.recordShown].
 */
class ChapterMemory(val conversationId: String, val number: Int) {
    val shownRefs: MutableSet<Ref> = HashSet()
    val sheetsShownAt: MutableMap<UUID, Long> = HashMap()

    /** What the requests not accepted yet showed, in order: recorded once the model accepts them, dropped otherwise. */
    val unrecorded: MutableList<Shown> = ArrayList()

    /** The chapter as it is now, to put back with [Mark.restore]. */
    fun mark(): Mark = Mark(shownRefs.toSet(), sheetsShownAt.toMap(), unrecorded.toList())

    /** What [unrecorded] holds, which it no longer does: the request that showed it was accepted. */
    fun takeUnrecorded(): List<Shown> = unrecorded.toList().also { unrecorded.clear() }

    /** What the chapter held when [mark] was taken. */
    inner class Mark internal constructor(private val refs: Set<Ref>, private val sheets: Map<UUID, Long>, private val pending: List<Shown>) {
        /** Puts the chapter back as it was: what was shown since is shown again, and never recorded. */
        fun restore() {
            shownRefs.retainAll(refs)
            sheetsShownAt.clear()
            sheetsShownAt.putAll(sheets)
            unrecorded.clear()
            unrecorded.addAll(pending)
        }
    }
}

/**
 * What one source showed the Director in a request (M3 §10.6): [refs] through [channel], in [context]
 * (`recalls` rows), and the [notes] it showed (marked `shown`). An idea among [refs] (`P-…`, the heartbeat's, M6 §5.6)
 * is dealt. Kept in [ChapterMemory.unrecorded] until the model accepts the request; dropped with it otherwise.
 */
data class Shown(val refs: List<Ref>, val channel: RecallChannel, val context: RecallContext, val notes: List<Ref> = emptyList()) {
    companion object {
        private val log = Log.memory

        /** Who marks the notes the Director was shown. */
        private val ENGINE = Author(Source.ENGINE)

        /**
         * [shown] reached the model: a `recalls` row for each of its refs, each idea dealt ([MemoryStore.dealt]: its
         * count and `last_dealt_at` too), each note marked `shown` once. Each write is tried on its own; one that fails
         * is logged (`WARN`), the others go on.
         */
        suspend fun record(store: MemoryStore, shown: List<Shown>) {
            for (one in shown) {
                val (ideas, rows) = one.refs.partition { it.kind == RefKind.IDEA }
                if (rows.isNotEmpty()) {
                    safely("the ${one.channel.id} refs ${rows.joinToString()} were shown but could not be recorded (no recalls row, no reinforcement)") {
                        store.recorded(rows, one.channel, one.context)
                    }
                }
                if (ideas.isNotEmpty()) {
                    safely("the ${one.channel.id} ideas ${ideas.joinToString()} were dealt but could not be recorded (they may be dealt again soon)") {
                        store.dealt(ideas, one.channel, one.context)
                    }
                }
            }
            for (note in shown.flatMap { it.notes }.distinct()) {
                safely("$note was shown but could not be marked") { store.markNote(note, NoteStatus.SHOWN, ENGINE) }
            }
        }

        private suspend fun safely(what: String, write: suspend () -> Unit) {
            try {
                write()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("$what: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }
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
        /** The memory sections of the briefing, in order (M3 §11.1; `programme`, the storyteller's ideas, M6 §7). */
        val TAGS: List<String> = listOf("grimoire", "programme", "chronicle", "canon", "sheets", "memories", "notes")

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

    override suspend fun briefing(chapter: ChapterMemory, now: Long, reduced: Boolean): List<BriefingSection> =
        BriefingSection.TAGS.map { tag -> BriefingSection(tag, "", listOf("(${unavailable()})"), listOf("(${unavailable()})")) }

    override suspend fun attachments(signals: List<DirectorSignal>, chapter: ChapterMemory, now: Long): String? = null

    override fun situationLines(now: Long): List<String> = emptyList()

    override fun openIdeas(): List<Idea> = emptyList()

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

        override suspend fun addNote(draft: NoteDraft, author: Author): Added<Note> = refuse()

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

        override suspend fun addIdea(draft: IdeaDraft, author: Author): Added<Idea> = refuse()

        override suspend fun dropIdea(ref: Ref, reason: String, author: Author): Idea = refuse()

        override suspend fun expireIdeas(author: Author): List<Idea> = refuse()

        override suspend fun dealt(refs: List<Ref>, channel: RecallChannel, context: RecallContext) {
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

        override suspend fun ideas(status: IdeaStatus?, limit: Int): List<Idea> = emptyList()

        override suspend fun log(ref: Ref): List<MemoryLogRow> = emptyList()
    }
}
