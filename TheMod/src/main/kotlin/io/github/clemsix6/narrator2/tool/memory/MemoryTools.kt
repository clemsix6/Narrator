package io.github.clemsix6.narrator2.tool.memory

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.journal.JournalTimeoutException
import io.github.clemsix6.narrator2.journal.JournalUnavailableException
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.memory.Added
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.CanonDraft
import io.github.clemsix6.narrator2.memory.CanonEntry
import io.github.clemsix6.narrator2.memory.CanonKind
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.memory.InteractionDraft
import io.github.clemsix6.narrator2.memory.InteractionKind
import io.github.clemsix6.narrator2.memory.Memory
import io.github.clemsix6.narrator2.memory.MemoryDraft
import io.github.clemsix6.narrator2.memory.MemoryEntry
import io.github.clemsix6.narrator2.memory.MemoryRejected
import io.github.clemsix6.narrator2.memory.Quotes
import io.github.clemsix6.narrator2.memory.RecallChannel
import io.github.clemsix6.narrator2.memory.RecallContext
import io.github.clemsix6.narrator2.memory.RecallPurpose
import io.github.clemsix6.narrator2.memory.RecallQuery
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.memory.SheetDetail
import io.github.clemsix6.narrator2.memory.SheetPart
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.memory.query
import io.github.clemsix6.narrator2.memory.stats.Favor
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolEnv
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.tool.answer
import io.github.clemsix6.narrator2.tool.notOnline
import io.github.clemsix6.narrator2.tool.onlineNames
import io.github.clemsix6.narrator2.tool.refuse
import io.github.clemsix6.narrator2.tool.shownName
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/**
 * The memory tools the Director shares with the Chronicler, the Dreamer and the Analyst (M3 §5): the writes
 * `remember`, `resolve` and `note_interaction` (effect `ENGINE`: a turn that only remembers stays idle) and
 * the reads `recall` and `player_profile`. `ToolCatalog` gives each role its own set of them.
 */
@Suppress("UNUSED_PARAMETER") // every family has this signature
fun memoryTools(services: NarratorServices): List<NarratorTool<*>> =
    listOf(RememberTool(), ResolveTool(), NoteInteractionTool(), RecallTool(), PlayerProfileTool())

/**
 * A tool of the memory (M3 §5, §7.5, §8.3): shown only while the memory runs ([ToolEnv.memoryAvailable]).
 * Everything it writes goes through `MemoryStore`, which checks it: a refusal of the store reaches the
 * model as `Invalid arguments: <its message>` (written for the model, with the fix), or as
 * `DENIED (<rule>): <its message>` for a limit of the pace ([MemoryRejected.rule], `arc_limit`); a Journal
 * that cannot read or write as `FAILED: <why>`. [act] may [refuse] from anywhere.
 */
abstract class MemoryTool<A : Any>(name: String, description: String, args: KSerializer<A>, effect: ToolEffect) :
    NarratorTool<A>(name, description, args, effect, TIMEOUT_MS) {
    final override fun isAvailable(env: ToolEnv): Boolean = env.memoryAvailable

    final override suspend fun run(ctx: ToolContext, args: A): ToolResult = try {
        answer { act(ctx, args) }
    } catch (e: MemoryRejected) {
        val message = e.message.orEmpty()
        e.rule?.let { ToolResult.denied(it, message) } ?: ToolResult.invalid(advice(message, args))
    } catch (e: JournalUnavailableException) {
        unavailable(e)
    } catch (e: JournalTimeoutException) {
        unavailable(e)
    }

    /** What the tool does with checked [args]. */
    protected abstract suspend fun act(ctx: ToolContext, args: A): ToolResult

    /** A refusal of the store, with this tool's own advice added when it has some. */
    protected open fun advice(message: String, args: A): String = message

    private fun unavailable(e: Exception): ToolResult {
        log.warn("$name: ${e.message}")
        return ToolResult.failed(e.message ?: "the memory cannot be reached")
    }

    companion object {
        /** A write waits for the Journal's writer (10 s at most, `DuckMemoryStore`), after a barrier and a read or two. */
        const val TIMEOUT_MS = 15_000L
    }
}

// ── remember ────────────────────────────────────────────────────────────────────────────────────────

/**
 * `remember` (M3 §5.1): a commitment or decision of the Narrator in the canon (`C-`), or a fact about a
 * player in the memories (`M-`). A nickname or title replaces the player's active one; a place is where
 * [RememberArgs.atPlayer] stands now; a due time posts a `DUE` event to the Director then. An arc the
 * Dreamer remembers starts `planned` (M3 §8.2, step 4): the Director starts it with `resolve … active`.
 *
 * Who may remember what (M3 review, finding 1), checked before the store: the Director anything; the
 * Chronicler what was told to the players ([RememberKind.told]) only with the Narrator's words in `quote`;
 * the Dreamer only [DREAMER_KINDS], never with a quote.
 *
 * Mechanics (M4 §12.3, §10): while the engine runs, the Director starts a challenge with `start_challenge`,
 * which tracks it, never with `remember`; a plan exists only through `create_rule` (no kind here); an arc
 * has its [RememberArgs.theme], and an arc the Director starts is due within 7 days (the store's rules).
 *
 * The Director never sees the `e…` refs: a canon entry it remembers cites as its evidence the lines of its
 * turn that hold its quote ([linesHolding]), so that a promise is tied to the words that made it.
 */
class RememberTool : MemoryTool<RememberArgs>(
    name = "remember",
    description = "Records one of your commitments or decisions in the canon, or a fact about a player in your memory, " +
        "at the moment you make it or learn it. The canon is law for you: what you promise, threaten, bet or decree is " +
        "kept, reminded when due (DUE event) and shown in every briefing until you resolve it. Not for routine events " +
        "(deaths, fights, builds): they are recorded behind you. Write what in the third person, factually; your own " +
        "words go in quote. Returns the id (C-… or M-…).",
    args = RememberArgs.serializer(),
    effect = ToolEffect.ENGINE,
) {
    override suspend fun act(ctx: ToolContext, args: RememberArgs): ToolResult {
        checkApplies(args, ctx)
        val evidence = evidenceOf(args.evidence)
        val players = ctx.knownPlayers(args.players.orEmpty(), "players")
        val canon = args.kind.canon
        return if (canon != null) addCanon(ctx, args, canon, players, evidence) else addMemory(ctx, args, players, evidence)
    }

    private suspend fun addCanon(ctx: ToolContext, args: RememberArgs, kind: CanonKind, players: List<PlayerRef>, evidence: Evidence): ToolResult {
        val place = args.atPlayer?.let { name -> ctx.players.byName(name)?.pos ?: refuse(ctx.notOnline(name)) }
        val due = args.dueInMinutes?.let { ctx.clock.instant().plusSeconds(it * 60L) }
        val planned = kind == CanonKind.ARC && ctx.caller.role == Role.DREAMER
        val room = (EVIDENCE_MAX - evidence.events.size - evidence.tools.size).coerceAtLeast(0)
        val said = ctx.linesHolding(args.quote).filter { it !in evidence.events }.take(room)
        val draft = CanonDraft(
            kind = kind,
            statement = args.what.trim(),
            quote = args.quote?.trim(),
            players = players.map { it.uuid },
            stakes = args.stakes?.trim(),
            importance = args.importance,
            status = if (planned) CanonStatus.PLANNED else null,
            dueAt = due,
            place = place,
            evidenceEvents = evidence.events + said,
            evidenceTools = evidence.tools,
            theme = args.theme?.trim()?.lowercase(),
        )
        val added = ctx.memory.store.addCanon(draft, ctx.author())
        val row = added.row
        if (added is Added.Duplicate) return ToolResult.ok("ok: already remembered as ${row.ref} (${ctx.canonLine(row)}); nothing added", row.players)
        if (said.isNotEmpty()) log.debug { "${row.ref} cites ${said.joinToString { "e$it" }} of turn ${ctx.caller.turnId}" }
        val replaced = row.replaces?.let { "; $it retired" }.orEmpty()
        return ToolResult.ok("ok: remembered ${row.ref} (${ctx.canonLine(row)})$replaced${ctx.wake(row)}", row.players)
    }

    private suspend fun addMemory(ctx: ToolContext, args: RememberArgs, players: List<PlayerRef>, evidence: Evidence): ToolResult {
        val kind = checkNotNull(args.kind.memory)
        // The Director gives no title: the kind stands for it (`observation: « … »` in a recall line).
        val draft = MemoryDraft(
            kind = kind,
            title = kind.id,
            content = args.what.trim(),
            players = players.map { it.uuid },
            importance = args.importance,
            evidenceEvents = evidence.events,
            evidenceTools = evidence.tools,
        )
        val added = ctx.memory.store.addMemory(draft, ctx.author())
        val row = added.row
        val what = memoryLine(row)
        if (added is Added.Duplicate) return ToolResult.ok("ok: already remembered as ${row.ref} ($what); nothing added", row.players)
        return ToolResult.ok("ok: remembered ${row.ref} ($what)", row.players)
    }

    /** The arguments that do not go with the kind or with the caller's role, all at once. */
    private fun checkApplies(args: RememberArgs, ctx: ToolContext) {
        val kind = args.kind
        val role = ctx.caller.role
        if (role == Role.DREAMER && kind !in DREAMER_KINDS) {
            refuse(
                ToolResult.invalid(
                    "the Dreamer remembers only an arc (planned), lore or an observation, not ${an(kind.id)}: " +
                        "what the Narrator tells the players is recorded from its own words, by itself or the Chronicler",
                ),
            )
        }
        if (role == Role.DIRECTOR && kind == RememberKind.CHALLENGE && ctx.services.mechanics.available) {
            refuse(ToolResult.invalid("a challenge is started with start_challenge: it tracks it, shows its bar and pays its stakes"))
        }
        val problems = buildList {
            if (args.theme != null && kind != RememberKind.ARC) add("theme only goes with an arc: leave it out")
            if (args.dueInMinutes != null && kind.canon?.dueAllowed != true) add("due_in_minutes does not apply to ${an(kind.id)}: leave it out")
            if (args.stakes != null && kind != RememberKind.CHALLENGE) add("stakes only go with a challenge: leave stakes out")
            if (args.quote != null && kind.canon == null) add("quote does not apply to ${an(kind.id)}: put the player's own words in what, between « »")
            if (args.quote != null && kind.canon != null && role == Role.DREAMER) add("quote does not apply to the Dreamer, who never heard the Narrator: leave it out")
            if (args.quote == null && kind.told && role == Role.CHRONICLER) {
                add("${an(kind.id)} needs quote: copy 3 words or more exactly from the Narrator's NARRATOR line, and cite that line in evidence")
            }
            if (args.atPlayer != null && kind != RememberKind.PLACE) add("at_player only goes with a place: name who it concerns in players")
            if (args.atPlayer == null && kind == RememberKind.PLACE) add("a place needs its spot: give at_player, a player online who stands on it")
        }
        if (problems.isNotEmpty()) refuse(ToolResult.invalid(problems.joinToString("; ")))
    }

    override fun advice(message: String, args: RememberArgs): String =
        if (args.kind.canon != null && message.startsWith("what must be factual")) "$message; your own words to the players go in quote" else message

    private fun memoryLine(row: MemoryEntry): String =
        if (row.players.isEmpty()) row.kind.id else "${row.kind.id} about ${names(row.players)}"

    /**
     * The lines in which the Narrator said [quote], its words, in the caller's turn (M3-POST, like the wake
     * lines `note_interaction` cites): its own lines of the turn (`say`, a title, a book) and the lines of
     * the Narrator, or of an operator in its name (« YOU SAID (through operator …) »), among the events that
     * woke it. Nothing outside a Director's turn, and nothing without a quote: no other line says for sure
     * what the entry is about. A line said later in the turn, or injected between two calls, is not found.
     */
    private suspend fun ToolContext.linesHolding(quote: String?): List<Long> {
        val turn = caller.turnId ?: return emptyList()
        if (quote.isNullOrBlank()) return emptyList()
        services.journal.barrier() // the lines of the turn so far, and its row, are written
        val lines = services.journal.read(READ_TIMEOUT_MS) { connection ->
            connection.query(TURN_LINES_SQL, turn, turn) { getLong(1) to getString(2) }
        }
        return lines.filter { (_, text) -> Quotes.saidIn(quote, listOf(text)) }.map { it.first }
    }

    private companion object {
        /** What the Dreamer remembers: it plans arcs, keeps lore and observes players; it never commits the Narrator. */
        val DREAMER_KINDS = setOf(RememberKind.ARC, RememberKind.LORE, RememberKind.OBSERVATION)

        /** The Narrator's lines said in a turn, and its or an operator's lines among the events that woke the turn. */
        const val TURN_LINES_SQL = "SELECT id, text FROM chat WHERE channel IN ('narrator', 'operator') " +
            "AND (turn_id = ? OR id IN (SELECT unnest(trigger_events) FROM turns WHERE turn_id = ?)) ORDER BY id"
    }
}

// ── resolve ─────────────────────────────────────────────────────────────────────────────────────────

/**
 * `resolve` (M3 §5.2): a canon entry kept, broken, won, lost, retired…, or a planned arc started. The
 * Dreamer settles arcs only (M3 review, finding 1): whether a promise was kept is not for it to judge. A row
 * the mechanics engine tracks is settled by the engine (the store names the tool that ends it, M4 §3.3); an
 * arc started is due within 7 days, and a third active arc is `DENIED (arc_limit)` (M4 §10).
 */
class ResolveTool : MemoryTool<ResolveArgs>(
    name = "resolve",
    description = "Settles a canon entry as soon as it is settled: a promise kept or broken, a challenge won or lost, a law, " +
        "nickname or gag retired, a planned arc started. Until then it is reminded when due and shown in every briefing. " +
        "A settled entry never opens again: remember a new one instead.",
    args = ResolveArgs.serializer(),
    effect = ToolEffect.ENGINE,
) {
    override suspend fun act(ctx: ToolContext, args: ResolveArgs): ToolResult {
        val ref = Ref.parse(args.id)?.takeIf { it.kind == RefKind.CANON }
            ?: return ToolResult.invalid("id must be a canon entry, like C-45 (got ${ModelText.quote(args.id, REF_MAX)})")
        if (ctx.caller.role == Role.DREAMER) {
            val entry = ctx.memory.store.row(ref) as? CanonEntry
            if (entry != null && entry.kind != CanonKind.ARC) {
                return ToolResult.invalid("the Dreamer resolves only arcs, and $ref is ${an(entry.kind.id)}: the Narrator and the Chronicler settle the rest")
            }
        }
        val winners = ctx.knownPlayers(args.winners.orEmpty(), "winners")
        val row = ctx.memory.store.resolveCanon(ref, args.outcome.status, args.note.trim(), winners.map { it.uuid }, ctx.author())
        val by = if (row.winners.isEmpty()) "" else " by ${names(row.winners)}"
        val at = row.resolvedAt?.let { " (${ctx.time(it)})" }.orEmpty()
        val due = row.dueAt?.takeIf { row.status.open }?.let { ", ${ctx.due(it)}${ctx.wake(row)}" }.orEmpty()
        return ToolResult.ok("ok: ${row.ref} ${canonWhat(row)} is now ${row.status.id}$by$at$due", (row.players + row.winners).distinctBy { it.uuid })
    }
}

// ── note_interaction ────────────────────────────────────────────────────────────────────────────────

/**
 * `note_interaction` (M3 §5.3): a line of a player's history with the Narrator. What the Director notes
 * cites as its evidence the player's own chat lines among the events that woke its turn (M3 review,
 * finding 4): the Chronicler's note of the same line, ten minutes later, is then the same interaction. A
 * punishment, a blessing or a gift noted by the Director is linked to the successful world calls of its
 * turn that targeted the player (M3 §6.5): the code then knows it is covered. The favor before and after
 * is read by [Favor.read].
 */
class NoteInteractionTool : MemoryTool<NoteInteractionArgs>(
    name = "note_interaction",
    description = "Adds a line to a player's history with you: what they did to you or for you (an insult, a prayer, an " +
        "apology, an offering, a bet…) and what you did to them (punishment, blessing, gift: your calls of this turn on " +
        "them are linked). It moves their favor (shown in your situation) and stays on their sheet; disrespect is " +
        "remembered. Note what matters, not every line of chat.",
    args = NoteInteractionArgs.serializer(),
    effect = ToolEffect.ENGINE,
) {
    override suspend fun act(ctx: ToolContext, args: NoteInteractionArgs): ToolResult {
        val evidence = evidenceOf(args.evidence)
        val player = ctx.knownPlayers(listOf(args.player), "player").single()
        val linking = args.kind in SANCTIONS && ctx.caller.turnId != null
        val turn = ctx.turnOn(player, linking)
        val room = (EVIDENCE_MAX - evidence.events.size - evidence.tools.size).coerceAtLeast(0)
        val linked = turn.calls.filter { it !in evidence.tools }.take(room)
        val lines = turn.lines.filter { it !in evidence.events }.take(room - linked.size)
        val before = if (args.kind.counts) ctx.favor(player) else null
        val draft = InteractionDraft(
            player = player.uuid,
            kind = args.kind,
            valence = args.valence,
            summary = args.summary.trim(),
            quote = args.quote?.trim(),
            toolCalls = evidence.tools + linked,
            evidenceEvents = evidence.events + lines,
        )
        val added = ctx.memory.store.addInteraction(draft, ctx.author())
        val row = added.row
        val what = "${row.player.shownName}, ${row.kind.id} ${signed(row.valence)}"
        if (added is Added.Duplicate) return ToolResult.ok("ok: already noted as ${row.ref} ($what); nothing added", listOf(row.player))
        val calls = when {
            row.toolCalls.isNotEmpty() -> ", with ${row.toolCalls.joinToString { "t$it" }}"
            linking -> "; none of your calls of this turn acted on ${row.player.shownName}, nothing linked"
            else -> ""
        }
        if (linked.isNotEmpty() || lines.isNotEmpty()) {
            log.debug { "${row.ref} cites ${(lines.map { "e$it" } + linked.map { "t$it" }).joinToString()} of turn ${ctx.caller.turnId}" }
        }
        val favor = if (row.kind.counts) favorChange(row.player, before, ctx.favor(row.player)) else ""
        return ToolResult.ok("ok: noted ${row.ref} ($what)$calls$favor", listOf(row.player))
    }

    /** `; favor of Bartok -18 → -31`, `; favor of Bartok now -25`; empty when unknown. */
    private fun favorChange(player: PlayerRef, before: Double?, after: Double?): String = when {
        after == null -> ""
        before == null -> "; favor of ${player.shownName} now ${signed(after.roundToInt())}"
        else -> "; favor of ${player.shownName} ${signed(before.roundToInt())} → ${signed(after.roundToInt())}"
    }

    /** What the caller's turn shows of a player: their [lines] among the events that woke it, its world [calls] on them. */
    private class TurnEvidence(val lines: List<Long>, val calls: List<Long>)

    /**
     * What the caller's turn shows of [player], oldest first: their own chat lines (public, prayers) among
     * the events that woke it, and, with [calls], its successful world calls on them. Nothing outside a
     * Director turn (the memory jobs cite their own evidence).
     */
    private suspend fun ToolContext.turnOn(player: PlayerRef, calls: Boolean): TurnEvidence {
        val turn = caller.turnId ?: return TurnEvidence(emptyList(), emptyList())
        services.journal.barrier() // the turn's row and its calls so far are written
        return services.journal.read(READ_TIMEOUT_MS) { connection ->
            TurnEvidence(
                lines = connection.query(TURN_LINES_SQL, player.uuid, turn) { getLong(1) },
                calls = if (calls) connection.query(TURN_CALLS_SQL, caller.conversationId, turn, player.name) { getLong(1) } else emptyList(),
            )
        }
    }

    /** The player's favor now (M3 §6.2); null without an interaction that counts, or when it cannot be read. */
    private suspend fun ToolContext.favor(player: PlayerRef): Double? = try {
        val now = clock.instant()
        services.journal.read(READ_TIMEOUT_MS) { Favor.read(it, player.uuid, now) }?.favor
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("note_interaction: the favor of ${player.name} could not be read: ${e.message}")
        null
    }

    private companion object {
        /** The Narrator's answers: their tool calls are linked to the interaction (M3 §6.5). */
        val SANCTIONS = setOf(InteractionKind.PUNISHMENT, InteractionKind.BLESSING, InteractionKind.GIFT)

        const val TURN_CALLS_SQL = "SELECT id FROM tool_calls WHERE conversation_id = ? AND turn_id = ? AND status = 'ok' " +
            "AND effect = 'world' AND list_contains(targets, ?) ORDER BY id"

        /** The player's own lines among the events that woke a turn (`turns.trigger_events`, written when it starts). */
        const val TURN_LINES_SQL = "SELECT id FROM chat WHERE player_uuid = ? AND channel IN ('public', 'pray') " +
            "AND id IN (SELECT unnest(trigger_events) FROM turns WHERE turn_id = ?) ORDER BY id"
    }
}

// ── recall ──────────────────────────────────────────────────────────────────────────────────────────

/**
 * `recall` (M3 §5.4): a search of the memory ([RecallPurpose.TOOL]), one line per hit. What the Director
 * sees is recorded (`recalls`, `recall_tool`) and reinforces the memories (M3 §9.2).
 */
class RecallTool : MemoryTool<RecallArgs>(
    name = "recall",
    description = "Searches your memory: memories, the canon (your commitments, laws, names, places), your interactions " +
        "with players and the chronicle. Use it before stating an old fact, or to find what you said, promised or did. " +
        "One line per result, best first; with no words, the latest.",
    args = RecallArgs.serializer(),
    effect = ToolEffect.READ,
) {
    override suspend fun act(ctx: ToolContext, args: RecallArgs): ToolResult {
        val text = args.query?.trim()?.ifEmpty { null }
        val player = args.player?.let { ctx.knownPlayers(listOf(it), "player").single() }
        val kinds = args.kinds?.map { it.ref }?.toSet() ?: RefKind.searchable
        val since = args.days?.let { ctx.clock.millis() - it * DAY_MS }
        val query = RecallQuery(
            text = text,
            players = listOfNotNull(player?.uuid),
            kinds = kinds,
            sinceMillis = since,
            includeClosed = args.includeClosed,
            limit = args.limit,
            purpose = RecallPurpose.TOOL,
        )
        val hits = ctx.memory.recall.search(query)
        ctx.shown(hits.map { it.ref }, RecallChannel.RECALL_TOOL)
        val head = buildList {
            add(if (text == null && player == null) "recall (latest)" else "recall" + text?.let { " ${ModelText.quote(it, 80)}" }.orEmpty())
            player?.let { add("player ${it.shownName}") }
            args.kinds?.let { list -> add("kinds ${list.joinToString { it.ref.id }}") }
            args.days?.let { add("last $it days") }
            if (args.includeClosed) add("closed included")
            add(if (hits.isEmpty()) "nothing found: try other words, fewer filters or include_closed" else "${hits.size} found")
        }.joinToString(" · ")
        return ToolResult.ok((listOf(head) + hits.map { it.line }).joinToString("\n"), listOfNotNull(player))
    }
}

// ── player_profile ──────────────────────────────────────────────────────────────────────────────────

/** `player_profile` (M3 §5.5): the full sheet of a player ([SheetDetail.FULL]), or some of its parts. */
class PlayerProfileTool : MemoryTool<PlayerProfileArgs>(
    name = "player_profile",
    description = "A player's full sheet, online or not: who they are in this world's story, their personality with its " +
        "evidence, their computed stats, their history with you and their strongest memories. Use it to prepare an " +
        "important intervention; the briefing and the join of a player already give a condensed sheet.",
    args = PlayerProfileArgs.serializer(),
    effect = ToolEffect.READ,
) {
    override suspend fun act(ctx: ToolContext, args: PlayerProfileArgs): ToolResult {
        val player = ctx.knownPlayers(listOf(args.player), "player").single()
        val now = ctx.clock.millis()
        val sheets = ctx.memory.sheets
        val sheet = sheets.sheet(player.uuid, now)
            ?: return ToolResult.invalid("${player.shownName} has no sheet: the memory has never seen them")
        val parts = args.parts?.toSet() ?: SheetPart.ALL
        val text = sheets.render(sheet, SheetDetail.FULL, now, parts)
        if (SheetPart.MEMORIES in parts) ctx.shown(sheet.memories.map { it.ref }, RecallChannel.PROFILE)
        return ToolResult.ok(text, listOf(player))
    }
}

// ── Shared ──────────────────────────────────────────────────────────────────────────────────────────

private val log = Log.memory

/** How long a read of the Journal by a memory tool may take. */
private const val READ_TIMEOUT_MS = 5_000L

private const val DAY_MS = 86_400_000L

internal val ToolContext.memory: Memory get() = services.memory

/**
 * Who writes for the caller: the job of a Chronicler or Dreamer conversation (its run, for the audit and
 * the quote checks), else the Director's turn.
 */
internal fun ToolContext.author(): Author {
    services.memory.runContext(caller.conversationId)?.let { return it.author(caller.conversationId) }
    val source = when (caller.role) {
        Role.DIRECTOR -> Source.DIRECTOR
        Role.CHRONICLER -> Source.CHRONICLER
        Role.DREAMER -> Source.DREAMER
        else -> error("the ${caller.role.id} does not write in the memory")
    }
    return Author(source, turnId = caller.turnId, conversationId = caller.conversationId)
}

/**
 * The players named [names] ([field] for the message), online or not, by their current or a former name,
 * ignoring case, as the Journal knows them; refuses a name nobody ever played under.
 */
internal suspend fun ToolContext.knownPlayers(names: List<String>, field: String): List<PlayerRef> {
    if (names.isEmpty()) return emptyList()
    if (names.any { players.byName(it) != null }) services.journal.barrier() // a player who just joined is written
    val found = services.journal.read(READ_TIMEOUT_MS) { connection ->
        names.map { name ->
            name to connection.query(PLAYER_SQL, name, name, name) { PlayerRef(UUID.fromString(getString(1)), getString(2)) }.firstOrNull()
        }
    }
    val missing = found.filter { it.second == null }.map { ModelText.name(it.first) }.distinct()
    if (missing.isNotEmpty()) {
        refuse(ToolResult.invalid("$field: no player named ${missing.joinToString(" or ")} has ever played here; online: ${onlineNames()}"))
    }
    return found.mapNotNull { it.second }.distinctBy { it.uuid }
}

/** A player by their current name first, then by a former one, the latest seen first. */
private const val PLAYER_SQL = "SELECT player_uuid::VARCHAR, name FROM players WHERE lower(name) = lower(?) " +
    "OR player_uuid IN (SELECT player_uuid FROM player_names WHERE lower(name) = lower(?)) " +
    "ORDER BY lower(name) = lower(?) DESC, last_seen DESC LIMIT 1"

/**
 * [refs] were put before the caller through [channel] (M3 §9.2, §10.6): a `recalls` row each, and the
 * memories are reinforced. Only the Narrator's own reads count; the Dreamer's leave a `dream` row, which
 * reinforces nothing; the Chronicler's and the Analyst's leave nothing (they are not the Narrator, and the
 * Chronicler reads `recalls` as what the Narrator was shown). A failure to record is logged: the reading stands.
 */
private suspend fun ToolContext.shown(refs: List<Ref>, channel: RecallChannel) {
    val counted = when (caller.role) {
        Role.DIRECTOR -> channel
        Role.DREAMER -> RecallChannel.DREAM
        else -> return
    }
    if (refs.isEmpty()) return
    try {
        memory.store.recorded(refs, counted, RecallContext(caller.conversationId, caller.turnId, eventId = null))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("${refs.size} rows shown through ${counted.id} but not recorded: ${e.message}")
    }
}

/** The refs of an exchange log a write cites: [events] (`e1234`, events and chat) and [tools] (`t88`). */
internal class Evidence(val events: List<Long>, val tools: List<Long>)

private val EVIDENCE = Regex("([eEtT])(\\d{1,18})")

/** [refs] as an exchange log writes them; refuses anything else. */
internal fun evidenceOf(refs: List<String>?): Evidence {
    val events = mutableListOf<Long>()
    val tools = mutableListOf<Long>()
    for (raw in refs.orEmpty()) {
        val match = EVIDENCE.matchEntire(raw.trim())
            ?: refuse(ToolResult.invalid("evidence: ${ModelText.quote(raw, REF_MAX)} is not a ref of the exchange log: cite e… (events, chat) and t… (tool calls) as given"))
        val id = match.groupValues[2].toLong()
        if (match.groupValues[1].equals("e", ignoreCase = true)) events += id else tools += id
    }
    return Evidence(events.distinct(), tools.distinct())
}

/** `promise → Mimosa, due 22:10, in 40m`, `place at overworld 120 90 -400`, `arc, planned`. */
private fun ToolContext.canonLine(row: CanonEntry): String = buildString {
    append(canonWhat(row))
    if (row.status == CanonStatus.PLANNED) append(", planned")
    row.place?.takeIf { row.kind == CanonKind.PLACE }?.let { append(" at ${ToolText.dimension(it.dim)} ${it.x} ${it.y} ${it.z}") }
    row.dueAt?.let { append(", ${due(it)}") }
}

/** `due 22:10, in 40m`. */
private fun ToolContext.due(at: Instant): String = "due ${time(at)}, in ${ToolText.duration(at.toEpochMilli() - clock.millis())}"

/** Who [row]'s due time will wake: `; a DUE event will wake you then` for the Director; empty without one. */
private fun ToolContext.wake(row: CanonEntry): String = when {
    row.dueAt == null -> ""
    caller.role == Role.DIRECTOR -> "; a DUE event will wake you then"
    else -> "; the Director will get a DUE event then"
}

/** `promise → Mimosa`, `law`. */
private fun canonWhat(row: CanonEntry): String = if (row.players.isEmpty()) row.kind.id else "${row.kind.id} → ${names(row.players)}"

/** `22:10` today, `10-08 22:10` another day, in the server's time zone. */
private fun ToolContext.time(at: Instant): String {
    val zone = clock.zone
    val sameDay = at.atZone(zone).toLocalDate() == clock.instant().atZone(zone).toLocalDate()
    return (if (sameDay) HOUR else DAY_HOUR).withZone(zone).format(at)
}

private val HOUR = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
private val DAY_HOUR = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT)

private fun names(players: List<PlayerRef>): String = players.joinToString { it.shownName }

private fun signed(value: Int): String = if (value > 0) "+$value" else "$value"

/** [word] with its indefinite article: `a promise`, `an observation`. */
private fun an(word: String): String = if (word.first() in "aeiou") "an $word" else "a $word"
