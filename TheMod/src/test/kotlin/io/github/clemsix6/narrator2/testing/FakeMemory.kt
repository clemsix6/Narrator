package io.github.clemsix6.narrator2.testing

import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.memory.Added
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.BriefingSection
import io.github.clemsix6.narrator2.memory.CanonDraft
import io.github.clemsix6.narrator2.memory.CanonEntry
import io.github.clemsix6.narrator2.memory.CanonFilter
import io.github.clemsix6.narrator2.memory.CanonKind
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.memory.ChapterMemory
import io.github.clemsix6.narrator2.memory.ChronicleDraft
import io.github.clemsix6.narrator2.memory.ChronicleEntry
import io.github.clemsix6.narrator2.memory.ChronicleLevel
import io.github.clemsix6.narrator2.memory.DocKey
import io.github.clemsix6.narrator2.memory.GagState
import io.github.clemsix6.narrator2.memory.Idea
import io.github.clemsix6.narrator2.memory.IdeaDraft
import io.github.clemsix6.narrator2.memory.IdeaStatus
import io.github.clemsix6.narrator2.memory.Interaction
import io.github.clemsix6.narrator2.memory.InteractionDraft
import io.github.clemsix6.narrator2.memory.Memory
import io.github.clemsix6.narrator2.memory.MemoryDoc
import io.github.clemsix6.narrator2.memory.MemoryDraft
import io.github.clemsix6.narrator2.memory.MemoryEntry
import io.github.clemsix6.narrator2.memory.MemoryJobsControl
import io.github.clemsix6.narrator2.memory.MemoryLogRow
import io.github.clemsix6.narrator2.memory.MemoryRejected
import io.github.clemsix6.narrator2.memory.MemoryRow
import io.github.clemsix6.narrator2.memory.MemoryState
import io.github.clemsix6.narrator2.memory.MemoryStatus
import io.github.clemsix6.narrator2.memory.MemoryStore
import io.github.clemsix6.narrator2.memory.Note
import io.github.clemsix6.narrator2.memory.NoteDraft
import io.github.clemsix6.narrator2.memory.NoteStatus
import io.github.clemsix6.narrator2.memory.PlayClock
import io.github.clemsix6.narrator2.memory.PlayerSheet
import io.github.clemsix6.narrator2.memory.PlayerSheets
import io.github.clemsix6.narrator2.memory.Recall
import io.github.clemsix6.narrator2.memory.RecallChannel
import io.github.clemsix6.narrator2.memory.RecallContext
import io.github.clemsix6.narrator2.memory.RecallHit
import io.github.clemsix6.narrator2.memory.RecallQuery
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.memory.Relation
import io.github.clemsix6.narrator2.memory.RelationKind
import io.github.clemsix6.narrator2.memory.RunContext
import io.github.clemsix6.narrator2.memory.SheetDetail
import io.github.clemsix6.narrator2.memory.SheetPart
import io.github.clemsix6.narrator2.memory.Skill
import io.github.clemsix6.narrator2.memory.SkillScore
import io.github.clemsix6.narrator2.memory.Trait
import io.github.clemsix6.narrator2.memory.TraitDraft
import io.github.clemsix6.narrator2.trace.ConversationRecord
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * A memory for tests (design M3 §3.8): it records the calls and gives back what the test set. Its
 * [store] keeps rows in memory, without any of the real store's rules (no vocabulary, voice, quote nor
 * duplicate check); [recall] returns [FakeRecall.hits]; [sheets] the sheets put in [FakeSheets.sheets].
 * Set [available] false (and [reason]) to test what happens without a memory.
 */
class FakeMemory(private val clockSource: Clock = TestClock()) : Memory {
    @Volatile
    override var available: Boolean = true

    @Volatile
    var reason: String = "fake memory off"

    override val unavailableReason: String? get() = if (available) null else reason

    override val store = FakeMemoryStore(clockSource)
    override val recall = FakeRecall()
    override val sheets = FakeSheets()
    override val clock = FakePlayClock()
    override val jobs = FakeJobs()

    /** What [briefing] returns, once [brief] ran on the chapter. */
    @Volatile
    var briefingSections: List<BriefingSection> = emptyList()

    /** What [briefing] does to the chapter before it returns (shows refs, waits…); nothing by default. */
    @Volatile
    var brief: suspend (ChapterMemory) -> Unit = {}

    /** What [attachments] returns for the signals it is given; it may suspend, as a slow memory does. */
    @Volatile
    var attach: suspend (List<DirectorSignal>) -> String? = { null }

    /** What [situationLines] returns. */
    @Volatile
    var situation: List<String> = emptyList()

    /** The run contexts [runContext] knows, by conversation id. */
    val runs = ConcurrentHashMap<String, RunContext>()

    /**
     * Calls made: the chapters briefed (and the form asked for, [briefedReduced]), the signals given to
     * [attachments], the chapters closed. What [recordShown] records goes to [store] (`recalls`, `markNote`).
     */
    val briefed = CopyOnWriteArrayList<ChapterMemory>()
    val briefedReduced = CopyOnWriteArrayList<Boolean>()
    val attached = CopyOnWriteArrayList<List<DirectorSignal>>()
    val closed = CopyOnWriteArrayList<ConversationRecord>()

    override suspend fun briefing(chapter: ChapterMemory, now: Long, reduced: Boolean): List<BriefingSection> {
        briefed += chapter
        briefedReduced += reduced
        brief(chapter)
        return briefingSections
    }

    override suspend fun attachments(signals: List<DirectorSignal>, chapter: ChapterMemory, now: Long): String? {
        attached += signals
        return attach(signals)
    }

    override fun situationLines(now: Long): List<String> = situation

    /** The open ideas of [store], the newest first. */
    override fun openIdeas(): List<Idea> =
        store.rows.values.filterIsInstance<Idea>().filter { it.status == IdeaStatus.OPEN }.sortedByDescending { it.ref.id }

    override fun chapterClosed(record: ConversationRecord) {
        closed += record
    }

    override fun runContext(conversationId: String): RunContext? = runs[conversationId]

    /** Recall that returns [hits], minus the excluded refs, up to the query's limit; [queries] records the searches. */
    class FakeRecall : Recall {
        val hits = CopyOnWriteArrayList<RecallHit>()
        val queries = CopyOnWriteArrayList<RecallQuery>()

        override suspend fun search(query: RecallQuery): List<RecallHit> {
            queries += query
            return hits.filter { it.ref !in query.exclude }.take(query.limit)
        }
    }

    /** Sheets put in [sheets]; [render] writes `SHEET <name> (<detail>, <parts>)`. */
    class FakeSheets : PlayerSheets {
        val sheets = ConcurrentHashMap<UUID, PlayerSheet>()

        override suspend fun sheet(player: UUID, now: Long): PlayerSheet? = sheets[player]

        override fun render(sheet: PlayerSheet, detail: SheetDetail, now: Long, parts: Set<SheetPart>): String =
            "SHEET ${sheet.player.name} (${detail.name.lowercase()}, ${parts.joinToString(" ") { it.id }})"
    }

    /** Play clocks set by the test: [world] seconds for the world, [players] for each player (0 otherwise). */
    class FakePlayClock : PlayClock {
        @Volatile
        var world: Long = 0
        val players = ConcurrentHashMap<UUID, Long>()

        override fun worldPlaySeconds(atMillis: Long): Long = world

        override fun playerPlaySeconds(player: UUID, atMillis: Long): Long = players[player] ?: 0
    }

    /** Jobs that record the requests ([requests]: `chronicle by X`, `dream by X`, `pause by X`…) and report [status]. */
    class FakeJobs : MemoryJobsControl {
        val requests = CopyOnWriteArrayList<String>()
        private val ids = AtomicLong(1)

        @Volatile
        var status = MemoryStatus(
            paused = false, blockedUntil = null, running = null, queued = emptyList(), lastBatch = null, lastNap = null,
            lastDream = null, nextDreamAt = null, worldPlaySeconds = 0, rejected24h = 0, voiceWarnings24h = 0,
        )

        override fun status(): MemoryStatus = status

        override fun requestChronicle(by: String): Result<Long> {
            requests += "chronicle by $by"
            return Result.success(ids.getAndIncrement())
        }

        override fun requestDream(by: String): Result<Long> {
            requests += "dream by $by"
            return Result.success(ids.getAndIncrement())
        }

        override fun pause(by: String) {
            requests += "pause by $by"
        }

        override fun resume(by: String) {
            requests += "resume by $by"
        }
    }
}

/**
 * A store that keeps rows in memory, numbered from 1 in one sequence, and records every write in
 * [writes] (`addCanon C-1 by director`). No rule is checked, except that a ref must exist: a missing one
 * throws [MemoryRejected], like the real store. Player names come from [names] (else the UUID's first
 * 8 characters). Thread-safe enough for tests.
 */
class FakeMemoryStore(private val clock: Clock = TestClock()) : MemoryStore {
    val names = ConcurrentHashMap<UUID, String>()
    val writes = CopyOnWriteArrayList<String>()
    val recalls = CopyOnWriteArrayList<Triple<Ref, RecallChannel, RecallContext>>()
    val rows = ConcurrentHashMap<Ref, MemoryRow>()
    val traits = ConcurrentHashMap<Pair<UUID, String>, Trait>()
    val skills = ConcurrentHashMap<Pair<UUID, Skill>, SkillScore>()
    val relations = ConcurrentHashMap<Pair<UUID, UUID>, Relation>()
    val docs = ConcurrentHashMap<String, List<MemoryDoc>>()
    private val ids = AtomicLong(1)

    private fun now(): Instant = clock.instant()

    private fun player(uuid: UUID) = PlayerRef(uuid, names[uuid] ?: uuid.toString().take(8))

    private fun next(kind: RefKind) = Ref(kind, ids.getAndIncrement())

    private fun wrote(what: String, ref: Any, author: Author) {
        writes += "$what $ref by ${author.actor}"
    }

    private inline fun <reified T : MemoryRow> get(ref: Ref): T =
        rows[ref] as? T ?: throw MemoryRejected("$ref does not exist")

    override suspend fun addMemory(draft: MemoryDraft, author: Author): Added<MemoryEntry> {
        val at = now()
        val row = MemoryEntry(
            next(RefKind.MEMORY), draft.kind, at, draft.occurredFrom ?: at, draft.occurredTo ?: at, draft.title, draft.content,
            draft.players.map(::player), draft.entities, draft.tags, draft.importance, draft.valence, null,
            stabilityHours = 38.0, reinforcedPlaySeconds = 0, recalls = 0, lastRecalledAt = null, source = author.source,
            archived = false, mergedInto = null,
        )
        rows[row.ref] = row
        wrote("addMemory", row.ref, author)
        return Added.New(row)
    }

    override suspend fun addCanon(draft: CanonDraft, author: Author): Added<CanonEntry> {
        val row = CanonEntry(
            next(RefKind.CANON), draft.kind, now(), draft.statement, draft.quote, draft.players.map(::player), draft.stakes,
            draft.importance, draft.status ?: CanonStatus.ACTIVE, draft.dueAt, null, null, emptyList(), null, draft.place,
            0, null, if (draft.kind == CanonKind.RUNNING_GAG) GagState.FRESH else null, author.source, false,
        )
        rows[row.ref] = row
        wrote("addCanon", row.ref, author)
        return Added.New(row)
    }

    override suspend fun resolveCanon(ref: Ref, status: CanonStatus, note: String, winners: List<UUID>, author: Author): CanonEntry {
        val row = get<CanonEntry>(ref).copy(status = status, resolvedAt = now(), resolution = note, winners = winners.map(::player))
        rows[ref] = row
        wrote("resolveCanon", ref, author)
        return row
    }

    override suspend fun useCanon(ref: Ref, at: Instant, author: Author): CanonEntry {
        val old = get<CanonEntry>(ref)
        val row = old.copy(uses = old.uses + 1, lastUsedAt = at)
        rows[ref] = row
        wrote("useCanon", ref, author)
        return row
    }

    override suspend fun addInteraction(draft: InteractionDraft, author: Author): Added<Interaction> {
        val row = Interaction(
            next(RefKind.INTERACTION), draft.at ?: now(), player(draft.player), draft.kind, draft.valence, draft.kind.counts,
            draft.kind.isDisrespect(draft.valence), draft.summary, draft.quote, 0, draft.canon, draft.memory, draft.toolCalls,
            author.source, false,
        )
        rows[row.ref] = row
        wrote("addInteraction", row.ref, author)
        return Added.New(row)
    }

    override suspend fun setTrait(draft: TraitDraft, author: Author): Trait {
        val at = now()
        val row = Trait(
            draft.player, draft.trait, draft.side, draft.label, draft.intensity, draft.confidence, draft.evidence,
            traits[draft.player to draft.trait]?.since ?: at, 0, draft.note, at, 0, false,
        )
        traits[draft.player to draft.trait] = row
        wrote("setTrait", "${draft.player}:${draft.trait}", author)
        return row
    }

    override suspend fun retireTrait(player: UUID, trait: String, reason: String, author: Author) {
        val row = traits[player to trait] ?: throw MemoryRejected("$trait is not a trait of this player")
        traits[player to trait] = row.copy(retired = true)
        wrote("retireTrait", "$player:$trait", author)
    }

    override suspend fun setSkill(player: UUID, skill: Skill, score: Int, rationale: String, evidence: List<Ref>, author: Author): SkillScore {
        val row = SkillScore(player, skill, score, rationale, evidence, now())
        skills[player to skill] = row
        wrote("setSkill", "$player:${skill.id}", author)
        return row
    }

    override suspend fun setRelation(player: UUID, other: UUID, kind: RelationKind, note: String, evidence: List<Ref>, author: Author): Relation {
        val row = Relation(player, player(other), kind, note, evidence, now())
        relations[player to other] = row
        wrote("setRelation", "$player:$other", author)
        return row
    }

    override suspend fun writeDoc(key: DocKey, content: String, evidence: List<Ref>, author: Author): MemoryDoc {
        val versions = docs[key.key].orEmpty()
        val doc = MemoryDoc(key.key, versions.size + 1, content, evidence, now(), author.source)
        docs[key.key] = versions + doc
        wrote("writeDoc", "${key.key}:${doc.version}", author)
        return doc
    }

    override suspend fun addChronicle(draft: ChronicleDraft, author: Author): ChronicleEntry {
        val row = ChronicleEntry(
            next(RefKind.CHRONICLE), draft.level, draft.from, draft.to, draft.title, draft.summary, draft.covers,
            draft.conversationId, draft.open, null, author.source,
        )
        draft.rewrites?.let { old -> rows[old] = get<ChronicleEntry>(old).copy(open = false, supersededBy = row.ref) }
        rows[row.ref] = row
        wrote("addChronicle", row.ref, author)
        return row
    }

    override suspend fun addNote(draft: NoteDraft, author: Author): Added<Note> {
        val row = Note(next(RefKind.NOTE), now(), draft.kind, draft.text, draft.players.map(::player), draft.refs, NoteStatus.OPEN, 0)
        rows[row.ref] = row
        wrote("addNote", row.ref, author)
        return Added.New(row)
    }

    override suspend fun markNote(ref: Ref, status: NoteStatus, author: Author): Note {
        val old = get<Note>(ref)
        val row = old.copy(status = status, shownCount = old.shownCount + if (status == NoteStatus.SHOWN) 1 else 0)
        rows[ref] = row
        wrote("markNote", ref, author)
        return row
    }

    override suspend fun setGag(ref: Ref, state: GagState, note: String, author: Author): CanonEntry {
        val old = get<CanonEntry>(ref)
        val row = old.copy(freshness = state, status = if (state == GagState.RETIRED) CanonStatus.RETIRED else old.status)
        rows[ref] = row
        wrote("setGag", ref, author)
        return row
    }

    override suspend fun merge(keep: Ref, merged: List<Ref>, content: String?, author: Author): MemoryEntry {
        val kept = get<MemoryEntry>(keep).let { if (content != null) it.copy(content = content) else it }
        merged.forEach { ref -> rows[ref] = get<MemoryEntry>(ref).copy(archived = true, mergedInto = keep) }
        rows[keep] = kept
        wrote("merge", keep, author)
        return kept
    }

    override suspend fun archive(ref: Ref, archived: Boolean, reason: String, author: Author) {
        rows[ref] = when (val row = get<MemoryRow>(ref)) {
            is MemoryEntry -> row.copy(archived = archived)
            is CanonEntry -> row.copy(archived = archived)
            is Interaction -> row.copy(archived = archived)
            else -> throw MemoryRejected("$ref cannot be archived")
        }
        wrote(if (archived) "archive" else "restore", ref, author)
    }

    override suspend fun recorded(refs: List<Ref>, channel: RecallChannel, context: RecallContext) {
        refs.forEach { recalls += Triple(it, channel, context) }
    }

    override suspend fun rollbackDoc(key: String, version: Int, author: Author): MemoryDoc {
        val versions = docs[key].orEmpty()
        val old = versions.firstOrNull { it.version == version } ?: throw MemoryRejected("$key has no version $version")
        val doc = old.copy(version = versions.size + 1, writtenAt = now(), source = author.source, restoredFrom = version)
        docs[key] = versions + doc
        wrote("rollbackDoc", "$key:${doc.version}", author)
        return doc
    }

    override suspend fun revertRun(runId: Long, author: Author): Int {
        wrote("revertRun", runId, author)
        return 0
    }

    /** An idea `open`, expiring after its hours of play on the fake world clock (0). */
    override suspend fun addIdea(draft: IdeaDraft, author: Author): Added<Idea> {
        val row = Idea(
            next(RefKind.IDEA), now(), draft.kind, draft.title, draft.pitch, draft.fit, draft.players.map(::player), draft.register,
            draft.novelty, draft.funScore, draft.fairness, draft.refs, draft.module, draft.brief, IdeaStatus.OPEN, dealt = 0,
            lastDealtAt = null, expiresPlaySeconds = draft.playHours * 3_600L, closedAt = null, closeReason = null, source = author.source,
        )
        rows[row.ref] = row
        wrote("addIdea", row.ref, author)
        return Added.New(row)
    }

    override suspend fun dropIdea(ref: Ref, reason: String, author: Author): Idea {
        val row = get<Idea>(ref).copy(status = IdeaStatus.DROPPED, closedAt = now(), closeReason = reason)
        rows[ref] = row
        wrote("dropIdea", ref, author)
        return row
    }

    /** The world play clock the ideas expire against ([expireIdeas]): 0 unless the test moves it. */
    @Volatile
    var worldPlaySeconds: Long = 0

    override suspend fun expireIdeas(author: Author): List<Idea> =
        rows.values.filterIsInstance<Idea>().filter { it.status == IdeaStatus.OPEN && it.expiresPlaySeconds <= worldPlaySeconds }.sortedBy { it.ref.id }.map { idea ->
            idea.copy(status = IdeaStatus.EXPIRED, closedAt = now(), closeReason = "its hours of world play are over").also {
                rows[idea.ref] = it
                wrote("expireIdea", idea.ref, author)
            }
        }

    override suspend fun dealt(refs: List<Ref>, channel: RecallChannel, context: RecallContext) {
        require(refs.all { it.kind == RefKind.IDEA }) { "only ideas are dealt: ${refs.joinToString()}" }
        refs.distinct().forEach { ref ->
            val idea = rows[ref] as? Idea ?: return@forEach
            rows[ref] = idea.copy(dealt = idea.dealt + 1, lastDealtAt = now())
            recalls += Triple(ref, channel, context)
        }
    }

    override suspend fun row(ref: Ref): MemoryRow? = rows[ref]

    override suspend fun memory(ref: Ref): MemoryEntry? = rows[ref] as? MemoryEntry

    override suspend fun canon(ref: Ref): CanonEntry? = rows[ref] as? CanonEntry

    override suspend fun canon(filter: CanonFilter): List<CanonEntry> = rows.values.filterIsInstance<CanonEntry>()
        .filter { !it.archived && (filter.kinds.isEmpty() || it.kind in filter.kinds) }
        .filter { filter.open == null || it.status.open == filter.open }
        .filter { filter.player == null || it.players.isEmpty() || it.players.any { p -> p.uuid == filter.player } }
        .sortedBy { it.ref.id }
        .take(filter.limit)

    override suspend fun interactions(player: UUID, limit: Int, marked: Boolean): List<Interaction> =
        rows.values.filterIsInstance<Interaction>()
            .filter { it.player.uuid == player && (!marked || it.disrespect || it.valence == 3 || it.valence == -3) }
            .sortedByDescending { it.ref.id }
            .take(limit)

    override suspend fun traits(player: UUID, includeRetired: Boolean): List<Trait> =
        traits.values.filter { it.player == player && (includeRetired || !it.retired) }.sortedBy { it.trait }

    override suspend fun skills(player: UUID): List<SkillScore> = skills.values.filter { it.player == player }.sortedBy { it.skill }

    override suspend fun relations(player: UUID): List<Relation> = relations.values.filter { it.player == player }

    override suspend fun doc(key: String, version: Int?): MemoryDoc? =
        docs[key].orEmpty().let { versions -> if (version == null) versions.lastOrNull() else versions.firstOrNull { it.version == version } }

    override suspend fun docVersions(key: String): List<MemoryDoc> = docs[key].orEmpty().reversed()

    override suspend fun chronicle(level: ChronicleLevel, limit: Int): List<ChronicleEntry> =
        rows.values.filterIsInstance<ChronicleEntry>().filter { it.level == level && it.supersededBy == null }
            .sortedByDescending { it.ref.id }.take(limit)

    override suspend fun openNotes(): List<Note> =
        rows.values.filterIsInstance<Note>().filter { it.status != NoteStatus.DONE }.sortedBy { it.ref.id }

    override suspend fun ideas(status: IdeaStatus?, limit: Int): List<Idea> =
        rows.values.filterIsInstance<Idea>().filter { status == null || it.status == status }.sortedByDescending { it.ref.id }.take(limit)

    override suspend fun log(ref: Ref): List<MemoryLogRow> = emptyList()
}

/** `memory_state` in memory: [values] by key, set and read by the test as by the code. */
class FakeMemoryState : MemoryState {
    val values = ConcurrentHashMap<String, String>()

    override suspend fun state(key: String): String? = values[key]

    override suspend fun setState(key: String, value: String) {
        values[key] = value
    }

    override suspend fun deleteState(key: String) {
        values.remove(key)
    }
}
