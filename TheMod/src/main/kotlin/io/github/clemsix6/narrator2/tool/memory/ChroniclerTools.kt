package io.github.clemsix6.narrator2.tool.memory

import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.memory.Added
import io.github.clemsix6.narrator2.memory.ChronicleDraft
import io.github.clemsix6.narrator2.memory.ChronicleLevel
import io.github.clemsix6.narrator2.memory.DocKey
import io.github.clemsix6.narrator2.memory.MemoryDraft
import io.github.clemsix6.narrator2.memory.MemoryKind
import io.github.clemsix6.narrator2.memory.MemoryLimits
import io.github.clemsix6.narrator2.memory.MemoryTags
import io.github.clemsix6.narrator2.memory.NoteDraft
import io.github.clemsix6.narrator2.memory.NoteKind
import io.github.clemsix6.narrator2.memory.RecallChannel
import io.github.clemsix6.narrator2.memory.RecallContext
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.memory.RunContext
import io.github.clemsix6.narrator2.memory.RunKind
import io.github.clemsix6.narrator2.memory.SheetSection
import io.github.clemsix6.narrator2.memory.instantOrNull
import io.github.clemsix6.narrator2.memory.longArray
import io.github.clemsix6.narrator2.memory.query
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Doc
import io.github.clemsix6.narrator2.tool.Items
import io.github.clemsix6.narrator2.tool.MaxLen
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.Range
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.tool.refuse
import io.github.clemsix6.narrator2.tool.shownName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Chronicler's own tools (M3 §7.5): `record_episode`, `note_to_director`, `write_chapter`,
 * `since_last`, `use_canon`, `mark_used`. Never offered to the Director (`ToolCatalog`); the Dreamer gets
 * `note_to_director` too. They write through the store as the job's author (`Memory.runContext`): the
 * Chronicler's run, its window and its chapter.
 */
@Suppress("UNUSED_PARAMETER") // every family has this signature
fun chroniclerTools(services: NarratorServices): List<NarratorTool<*>> =
    listOf(RecordEpisodeTool(), NoteToDirectorTool(), WriteChapterTool(), SinceLastTool(), UseCanonTool(), MarkUsedTool())

/** Longest tag: `gag:<slug of 40>`. */
private const val TAG_MAX = 44

/** Longest game id the store takes. */
private const val ENTITY_MAX = 64

// ── record_episode ──────────────────────────────────────────────────────────────────────────────────

@Serializable
data class RecordEpisodeArgs(
    @Doc("A short title in French, a name for the moment: « La nuit des slimes ».")
    @MaxLen(MemoryLimits.MEMORY_TITLE)
    val title: String,
    @Doc("What happened, one to three factual sentences in French, in the third person, never in the Narrator's voice.")
    @MaxLen(MemoryLimits.MEMORY_CONTENT)
    val what: String,
    @Doc("The players it is about, by name, main one first; leave out for the whole server.")
    @Items(1, PLAYERS_MAX)
    @MaxLen(NAME_MAX)
    val players: List<String>? = null,
    @Doc("How long it deserves to be remembered: routine is no episode; 5 an evening will remember it; 8 the server will; 10 a legend.")
    @Range(1.0, 10.0)
    val importance: Int,
    @Doc("-3 … +3: how it reflects on the main player.")
    @Range(-3.0, 3.0)
    val valence: Int = 0,
    @Doc("Tags, from: death fall lava drowning explosion mob pvp duel insult provocation apology compliment gift punishment blessing challenge bet promise threat prophecy law nickname title build grief exploration mining farming trade first boss nether end raid luck fear friendship rivalry help joke prayer offering running_gag; or gag:<slug>, arc:C-<id>.")
    @Items(1, 8)
    @MaxLen(TAG_MAX)
    val tags: List<String>,
    @Doc("The refs of the exchange log that prove it (e1234, t88): its time and place are taken from them.")
    @Items(1, EVIDENCE_MAX)
    @MaxLen(REF_MAX)
    val evidence: List<String>,
    @Doc("Game ids it involves (minecraft:trial_chambers, minecraft:lava).")
    @Items(1, 8)
    @MaxLen(ENTITY_MAX)
    val entities: List<String>? = null,
)

/** `record_episode` (M3 §7.5): a moment of the chapter worth remembering, a memory `episode` (`M-`), dated and placed by its evidence. */
class RecordEpisodeTool : MemoryTool<RecordEpisodeArgs>(
    name = "record_episode",
    description = "Records a moment of the chapter worth remembering as an episode of your memory (M-…), dated and placed by " +
        "its evidence. Honest importance: routine is no episode. Factual, in French, in the third person. An episode that " +
        "shares an evidence, a player and a tag with one already written is not added twice.",
    args = RecordEpisodeArgs.serializer(),
    effect = ToolEffect.ENGINE,
) {
    override suspend fun act(ctx: ToolContext, args: RecordEpisodeArgs): ToolResult {
        val evidence = evidenceOf(args.evidence)
        val players = ctx.knownPlayers(args.players.orEmpty(), "players")
        val draft = MemoryDraft(
            kind = MemoryKind.EPISODE,
            title = args.title.trim(),
            content = args.what.trim(),
            players = players.map { it.uuid },
            entities = args.entities.orEmpty().map { it.trim() },
            tags = args.tags.map { it.trim() },
            importance = args.importance,
            valence = args.valence,
            evidenceEvents = evidence.events,
            evidenceTools = evidence.tools,
        )
        val added = ctx.memory.store.addMemory(draft, ctx.author())
        val row = added.row
        val about = if (row.players.isEmpty()) "" else " about ${row.players.joinToString { it.shownName }}"
        val place = row.place?.let { ", at ${ToolText.dimension(it.dim)} ${it.x} ${it.y} ${it.z}" }.orEmpty()
        val what = "episode$about, importance ${row.importance}, ${ctx.time(row.occurredFrom)}$place: « ${ModelText.escape(row.title)} »"
        if (added is Added.Duplicate) return ToolResult.ok("ok: already remembered as ${row.ref} ($what); nothing added", row.players)
        return ToolResult.ok("ok: remembered ${row.ref} ($what)", row.players)
    }

    override fun advice(message: String, args: RecordEpisodeArgs): String =
        if (message.startsWith("tags") && MemoryTags.ALL.none { it in args.tags }) "$message; pick the tags among the list of the tool" else message
}

// ── note_to_director ────────────────────────────────────────────────────────────────────────────────

@Serializable
data class NoteToDirectorArgs(
    @Doc("What the Narrator should know, one or two factual sentences in French, in the third person: « Le Narrateur a dit à Bartok que la lave était interdite, alors que la loi C-12 la permet. »")
    @MaxLen(MemoryLimits.NOTE_TEXT)
    val text: String,
    @Doc("The players it concerns, by name.")
    @Items(1, PLAYERS_MAX)
    @MaxLen(NAME_MAX)
    val players: List<String>? = null,
    @Doc("The rows of the memory it is about (C-12, M-812).")
    @Items(1, 8)
    @MaxLen(REF_MAX)
    val refs: List<String>? = null,
)

/**
 * `note_to_director` (M3 §7.5, §12.4): a `coherence` note (`N-`) for the Director, about an incoherence
 * between its words and the canon. It reaches the Director at its next wake-up (a `NOTE` line, which does
 * not wake it), then in its next briefings. The same note still shown (same players, same rows: the nap
 * then the dream on one contradiction) is not written twice, and reaches the Director once.
 */
class NoteToDirectorTool : MemoryTool<NoteToDirectorArgs>(
    name = "note_to_director",
    description = "Leaves a note for the Narrator about an incoherence: what it said or did against its canon (a law, a promise, " +
        "a nickname), or a fact it got wrong. It reads it at its next wake-up, without being woken, then in its next briefings. " +
        "Factual, in French, in the third person; cite the rows it is about.",
    args = NoteToDirectorArgs.serializer(),
    effect = ToolEffect.ENGINE,
) {
    override suspend fun act(ctx: ToolContext, args: NoteToDirectorArgs): ToolResult {
        val refs = args.refs.orEmpty().map { text ->
            Ref.parse(text) ?: refuse(ToolResult.invalid("refs: ${ModelText.quote(text, REF_MAX)} is not a row of the memory: cite C-45, M-812, I-512"))
        }.distinct()
        val players = ctx.knownPlayers(args.players.orEmpty(), "players")
        val added = ctx.memory.store.addNote(NoteDraft(NoteKind.COHERENCE, args.text.trim(), players.map { it.uuid }, refs), ctx.author())
        val note = added.row
        if (added is Added.Duplicate) {
            return ToolResult.ok("ok: already noted as ${note.ref} for the Narrator (the same rows, still shown to it); nothing added", note.players)
        }
        ctx.services.director.post(DirectorSignal.MemoryNote(note.ref, note.kind, note.text, ctx.clock.millis()))
        return ToolResult.ok("ok: noted ${note.ref} for the Narrator: it reads it at its next wake-up, then in its next briefings", note.players)
    }
}

// ── write_chapter ───────────────────────────────────────────────────────────────────────────────────

@Serializable
data class WriteChapterArgs(
    @Doc("The chapter's title in French, a few words: « La nuit des slimes ».")
    @MaxLen(MemoryLimits.CHRONICLE_TITLE)
    val title: String,
    @Doc("The chronicle of the chapter, in French, in the third person, factual: who was there, what happened, what the Narrator did and promised, what is left open.")
    @MaxLen(4_000)
    val text: String,
)

/** `write_chapter` (M3 §7.4, §7.5): the chronicle entry (`H-`) of a chapter the Director closed without a summary. */
class WriteChapterTool : MemoryTool<WriteChapterArgs>(
    name = "write_chapter",
    description = "Writes the chronicle of the chapter of this nap (H-…), when it closed without a summary: who was there, what " +
        "happened, what the Narrator did and promised, what is left open. Factual, in French, in the third person. A chapter " +
        "has one chronicle: refused when it has one.",
    args = WriteChapterArgs.serializer(),
    effect = ToolEffect.ENGINE,
) {
    override suspend fun act(ctx: ToolContext, args: WriteChapterArgs): ToolResult {
        val run = ctx.run()
        val chapter = run.chapter?.takeIf { run.kind == RunKind.NAP }
            ?: return ToolResult.invalid("write_chapter is for the nap of a closed chapter, not for a batch")
        val (from, to) = ctx.services.journal.read(READ_TIMEOUT_MS) { connection ->
            connection.query("SELECT started_at, ended_at FROM conversations WHERE conversation_id = ?", chapter) {
                instantOrNull(1) to instantOrNull(2)
            }.singleOrNull()
        } ?: return ToolResult.failed("the chapter $chapter is not in the Journal")
        if (from == null || to == null) return ToolResult.invalid("the chapter is still open: its chronicle is written once it closes")
        val draft = ChronicleDraft(ChronicleLevel.CHAPTER, from, to, args.title.trim(), args.text.trim(), conversationId = chapter)
        val entry = ctx.memory.store.addChronicle(draft, ctx.author())
        return ToolResult.ok("ok: wrote ${entry.ref}, the chronicle of this chapter (${ctx.time(from)}–${ctx.time(to)})")
    }
}

// ── since_last ──────────────────────────────────────────────────────────────────────────────────────

@Serializable
data class SinceLastArgs(
    @Doc("The player who left, by name.")
    @MaxLen(NAME_MAX)
    val player: String,
    @Doc("What they did in their session that the Narrator should know when they come back, in French, in the third person, factual.")
    @MaxLen(600)
    val text: String,
)

/** `since_last` (M3 §7.5): the « since last time » of a player who left (`sheet:<uuid>:since_last`), shown when they come back. */
class SinceLastTool : MemoryTool<SinceLastArgs>(
    name = "since_last",
    description = "Writes the « since last time » of a player who left in this chapter: what they did in their session that the " +
        "Narrator should know when they come back (progress, deaths, their dealings with the Narrator, what is left open). It " +
        "replaces the previous one on their sheet. Factual, in French, in the third person.",
    args = SinceLastArgs.serializer(),
    effect = ToolEffect.ENGINE,
) {
    override suspend fun act(ctx: ToolContext, args: SinceLastArgs): ToolResult {
        val player = ctx.knownPlayers(listOf(args.player), "player").single()
        val doc = ctx.memory.store.writeDoc(DocKey.Sheet(player.uuid, SheetSection.SINCE_LAST), args.text.trim(), emptyList(), ctx.author())
        return ToolResult.ok("ok: wrote the « since last time » of ${player.shownName} (version ${doc.version})", listOf(player))
    }
}

// ── use_canon ───────────────────────────────────────────────────────────────────────────────────────

@Serializable
data class UseCanonArgs(
    @Doc("The canon entry served again: a running gag, a nickname, a title, a law brought up, e.g. C-12.")
    @MaxLen(REF_MAX)
    val id: String,
    @Doc("The refs of the exchange log where the Narrator served it (e1236, t88).")
    @Items(1, 5)
    @MaxLen(REF_MAX)
    val evidence: List<String>,
)

/** `use_canon` (M3 §7.5, §9.3): the Narrator served an open canon entry again: `uses + 1`, `last_used_at` (the wear of gags). */
class UseCanonTool : MemoryTool<UseCanonArgs>(
    name = "use_canon",
    description = "Counts a use of an open canon entry the Narrator served again in the exchange log: a running gag, a nickname, " +
        "a title, a law or a threat brought up. Gags served too often wear out; a threat brought up again does not expire.",
    args = UseCanonArgs.serializer(),
    effect = ToolEffect.ENGINE,
) {
    override suspend fun act(ctx: ToolContext, args: UseCanonArgs): ToolResult {
        val ref = Ref.parse(args.id)?.takeIf { it.kind == RefKind.CANON }
            ?: return ToolResult.invalid("id must be a canon entry, like C-12 (got ${ModelText.quote(args.id, REF_MAX)})")
        val evidence = evidenceOf(args.evidence)
        val at = ctx.services.journal.read(READ_TIMEOUT_MS) { connection ->
            connection.query(FIRST_EVIDENCE_SQL, longArray(evidence.events), longArray(evidence.events), longArray(evidence.tools)) {
                instantOrNull(1)
            }.singleOrNull()
        } ?: return ToolResult.invalid("evidence: none of ${args.evidence.joinToString { ModelText.escape(it) }} is in the Journal: cite the refs of the exchange log")
        val row = ctx.memory.store.useCanon(ref, at, ctx.author())
        val players = if (row.players.isEmpty()) "" else " → ${row.players.joinToString { it.shownName }}"
        return ToolResult.ok("ok: ${row.ref} ${row.kind.id}$players served again at ${ctx.time(at)}: ${row.uses} use${if (row.uses > 1) "s" else ""}", row.players)
    }
}

// ── mark_used ───────────────────────────────────────────────────────────────────────────────────────

@Serializable
data class MarkUsedArgs(
    @Doc("The memories shown to the Narrator in this chapter that it really took up in its lines (M-120).")
    @Items(1, 20)
    @MaxLen(REF_MAX)
    val refs: List<String>,
)

/**
 * `mark_used` (M3 §7.5, §9.2): memories shown to the Director in the chapter that it took up in its lines:
 * a `recalls` row `used` each, and their strongest reinforcement. Only memories shown in this chapter;
 * one shown and already marked is not marked twice (a nap run again).
 */
class MarkUsedTool : MemoryTool<MarkUsedArgs>(
    name = "mark_used",
    description = "Marks the memories shown to the Narrator in this chapter (<shown>) that it really took up in its lines: they " +
        "are reinforced, and remembered longer. Only those it used, not those it was merely shown.",
    args = MarkUsedArgs.serializer(),
    effect = ToolEffect.ENGINE,
) {
    override suspend fun act(ctx: ToolContext, args: MarkUsedArgs): ToolResult {
        val refs = args.refs.map { text ->
            Ref.parse(text)?.takeIf { it.kind == RefKind.MEMORY }
                ?: refuse(ToolResult.invalid("refs: ${ModelText.quote(text, REF_MAX)} is not a memory: mark_used takes M-… refs"))
        }.distinct()
        val chapter = ctx.run().chapter ?: return ToolResult.invalid("mark_used is for the nap of a chapter")
        val (shown, used) = ctx.services.journal.read(READ_TIMEOUT_MS) { connection ->
            val ids = longArray(refs.map { it.id })
            val shown = connection.query(SHOWN_SQL, chapter, ids) { getLong(1) }.toSet()
            val used = connection.query(USED_SQL, chapter, ids) { getLong(1) }.toSet()
            shown to used
        }
        val never = refs.filter { it.id !in shown }
        if (never.isNotEmpty()) {
            return ToolResult.invalid("${never.joinToString()} ${if (never.size > 1) "were" else "was"} never shown to the Narrator in this chapter: mark only memories of <shown> that it took up")
        }
        val fresh = refs.filter { it.id !in used }
        if (fresh.isNotEmpty()) ctx.memory.store.recorded(fresh, RecallChannel.USED, RecallContext(chapter, turnId = null, eventId = null))
        val again = (refs - fresh.toSet()).takeIf { it.isNotEmpty() }?.let { "; ${it.joinToString()} already marked" }.orEmpty()
        val marked = if (fresh.isEmpty()) "nothing new marked" else "${fresh.joinToString()} marked as used by the Narrator, reinforced"
        return ToolResult.ok("ok: $marked$again")
    }
}

// ── Shared ──────────────────────────────────────────────────────────────────────────────────────────

private const val READ_TIMEOUT_MS = 5_000L

/** The first time among evidence refs: events and chat lines (`e…`), tool calls (`t…`). */
private const val FIRST_EVIDENCE_SQL = "SELECT min(ts) FROM (SELECT ts FROM events WHERE list_contains(?, id) " +
    "UNION ALL SELECT ts FROM chat WHERE list_contains(?, id) UNION ALL SELECT ts FROM tool_calls WHERE list_contains(?, id))"

/** The memories among some ids that the Director of a chapter was shown (its own reads). */
private const val SHOWN_SQL = "SELECT DISTINCT ref_id FROM recalls WHERE conversation_id = ? AND list_contains(?, ref_id) " +
    "AND channel IN ('briefing', 'join', 'reminiscence', 'recall_tool', 'profile')"

/** The memories among some ids already marked used in a chapter. */
private const val USED_SQL = "SELECT DISTINCT ref_id FROM recalls WHERE conversation_id = ? AND list_contains(?, ref_id) AND channel = 'used'"

/** The memory job of the caller's conversation; refuses outside one (only a memory job has these tools). */
private fun ToolContext.run(): RunContext =
    memory.runContext(caller.conversationId) ?: refuse(ToolResult.failed("no memory job runs this conversation"))

/** `22:10` today, `10-08 22:10` another day, in the server's time zone. */
private fun ToolContext.time(at: Instant): String {
    val zone = clock.zone
    val sameDay = at.atZone(zone).toLocalDate() == clock.instant().atZone(zone).toLocalDate()
    return (if (sameDay) HOUR else DAY_HOUR).withZone(zone).format(at)
}

private val HOUR = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
private val DAY_HOUR = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT)
