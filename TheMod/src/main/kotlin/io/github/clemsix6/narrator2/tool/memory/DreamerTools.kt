package io.github.clemsix6.narrator2.tool.memory

import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.memory.ChronicleDraft
import io.github.clemsix6.narrator2.memory.ChronicleEntry
import io.github.clemsix6.narrator2.memory.ChronicleLevel
import io.github.clemsix6.narrator2.memory.DocKey
import io.github.clemsix6.narrator2.memory.GagState
import io.github.clemsix6.narrator2.memory.MemoryLimits
import io.github.clemsix6.narrator2.memory.NoteDraft
import io.github.clemsix6.narrator2.memory.NoteKind
import io.github.clemsix6.narrator2.memory.NoteStatus
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.memory.RelationKind
import io.github.clemsix6.narrator2.memory.SheetSection
import io.github.clemsix6.narrator2.memory.Skill
import io.github.clemsix6.narrator2.memory.Trait
import io.github.clemsix6.narrator2.memory.TraitAxis
import io.github.clemsix6.narrator2.memory.TraitDraft
import io.github.clemsix6.narrator2.memory.dream.DreamInputs
import io.github.clemsix6.narrator2.memory.dream.GrimoireFormat
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Doc
import io.github.clemsix6.narrator2.tool.Items
import io.github.clemsix6.narrator2.tool.MaxLen
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.Range
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.refuse
import io.github.clemsix6.narrator2.tool.shownName
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Dreamer's own tools (M3 §8.3): `set_trait`, `retire_trait`, `set_skill`, `set_relation`,
 * `write_sheet`, `dream_hint`, `set_gag`, `write_review`, `write_chronicle`, `write_grimoire`,
 * `merge_memories`. Never offered to the Director (`ToolCatalog`). They write through the store as the
 * dream's author (`Memory.runContext`): every write is checked there (vocabularies, budgets, evidence that
 * exists and concerns the player, the voice), and a refusal reaches the model with its fix.
 */
@Suppress("UNUSED_PARAMETER") // every family has this signature
fun dreamerTools(services: NarratorServices): List<NarratorTool<*>> = listOf(
    SetTraitTool(), RetireTraitTool(), SetSkillTool(), SetRelationTool(), WriteSheetTool(), DreamHintTool(),
    SetGagTool(), WriteReviewTool(), WriteChronicleTool(), WriteGrimoireTool(), MergeMemoriesTool(),
)

/** Longest trait: `free:<slug of 40>`. */
private const val TRAIT_MAX = 45

/** Longest written section of a sheet (the recap). */
private const val SECTION_MAX = 700

/** Longest chronicle entry the Dreamer writes (the saga). */
private const val CHRONICLE_MAX = 4_500

private const val AXES_DOC = "boldness (low cautious, high reckless), sociability (shy, outgoing), deference (respectful, defiant), " +
    "cooperation (loner, team_player), generosity (stingy, generous), temper (forgiving, vindictive), humor (serious, playful), " +
    "honesty (honest, scheming), modesty (modest, boastful), persistence (quitter, stubborn), curiosity (homebody, adventurous), " +
    "patience (impatient, patient)"

// ── set_trait ───────────────────────────────────────────────────────────────────────────────────────

/** The pole of an axis. */
@Serializable
enum class TraitSide(val side: Int) {
    @SerialName("low") LOW(-1),
    @SerialName("high") HIGH(1),
}

/** How sure the evidence makes the Dreamer of a trait (M3 §8.3). */
@Serializable
enum class TraitConfidence(val value: Double) {
    @SerialName("low") LOW(0.35),
    @SerialName("medium") MEDIUM(0.6),
    @SerialName("high") HIGH(0.85),
}

@Serializable
data class SetTraitArgs(
    @Doc("The player, by name.")
    @MaxLen(NAME_MAX)
    val player: String,
    @Doc("An axis: $AXES_DOC; or a free trait free:<slug> (a-z, 0-9, _), three per player at most.")
    @MaxLen(TRAIT_MAX)
    val trait: String,
    @Doc("The pole of an axis: low or high. Leave out for a free trait.")
    val side: TraitSide? = null,
    @Doc("A free trait only: its French label, « obsédé par le butin ».")
    @MaxLen(MemoryLimits.TRAIT_LABEL)
    val label: String? = null,
    @Doc("1 a hint … 5 what defines them.")
    @Range(1.0, 5.0)
    val intensity: Int,
    @Doc("How sure the evidence makes you: low, medium, high. A change of pole caps it at 0.5.")
    val confidence: TraitConfidence,
    @Doc("The memories (M-…) and interactions (I-…) about this player that show it.")
    @Items(1, 10)
    @MaxLen(REF_MAX)
    val evidence: List<String>,
    @Doc("What shows it, one factual line in French, in the third person: « plonge dans la lave pour du butin ».")
    @MaxLen(MemoryLimits.TRAIT_NOTE)
    val note: String? = null,
)

/**
 * `set_trait` (M3 §8.3, §9.3): a player's trait, one row per axis, with its evidence. Changing pole on an
 * axis replaces the previous value (kept in the audit) and caps the confidence at 0.5; the store computes
 * the trend and since when.
 */
class SetTraitTool : MemoryTool<SetTraitArgs>(
    name = "set_trait",
    description = "Sets a personality trait of a player, with the memories and interactions that show it: an axis on one of " +
        "its two poles, or a free trait. An axis has one value: to say the opposite of what the sheet says, set the other " +
        "pole, never both; the former value stays in the history. Only with evidence about this player.",
    args = SetTraitArgs.serializer(),
    effect = ToolEffect.ENGINE,
) {
    override suspend fun act(ctx: ToolContext, args: SetTraitArgs): ToolResult {
        val player = ctx.knownPlayers(listOf(args.player), "player").single()
        val trait = args.trait.trim()
        val store = ctx.memory.store
        val before = store.traits(player.uuid).firstOrNull { it.trait == trait }
        val draft = TraitDraft(
            player = player.uuid,
            trait = trait,
            side = args.side?.side ?: 0,
            label = args.label?.trim(),
            intensity = args.intensity,
            confidence = args.confidence.value,
            evidence = refsOf(args.evidence, "evidence", setOf(RefKind.MEMORY, RefKind.INTERACTION)),
            note = args.note?.trim(),
        )
        val set = store.setTrait(draft, ctx.author())
        val trend = when {
            set.trend > 0 -> ", stronger"
            set.trend < 0 && before?.side == set.side -> ", weaker"
            else -> ""
        }
        val turned = if (before != null && before.side * set.side < 0) {
            "; the pole changed from ${pole(before)}: confidence capped at 0.5, the former value stays in the history"
        } else {
            ""
        }
        return ToolResult.ok("ok: ${player.shownName} is ${pole(set)} ${set.intensity}/5 (${set.trait}), confidence ${"%.2f".format(Locale.ROOT, set.confidence)}, since ${ctx.day(set.since)}$trend$turned", listOf(player))
    }

    private fun pole(trait: Trait): String = TraitAxis.of(trait.trait)?.pole(trait.side) ?: "« ${ModelText.escape(trait.label.orEmpty())} »"
}

// ── retire_trait ────────────────────────────────────────────────────────────────────────────────────

@Serializable
data class RetireTraitArgs(
    @Doc("The player, by name.")
    @MaxLen(NAME_MAX)
    val player: String,
    @Doc("The trait to retire: an axis (boldness…) or free:<slug>.")
    @MaxLen(TRAIT_MAX)
    val trait: String,
    @Doc("Why nothing shows it any more, one factual line in French.")
    @MaxLen(MemoryLimits.TRAIT_NOTE)
    val reason: String,
)

/** `retire_trait` (M3 §8.3): a trait nothing shows any more leaves the sheet; its row stays, `retired`. */
class RetireTraitTool : MemoryTool<RetireTraitArgs>(
    name = "retire_trait",
    description = "Retires a trait of a player that nothing shows any more: it leaves the sheet, and its row stays in the history. " +
        "To say the opposite of a trait, set the other pole with set_trait instead.",
    args = RetireTraitArgs.serializer(),
    effect = ToolEffect.ENGINE,
) {
    override suspend fun act(ctx: ToolContext, args: RetireTraitArgs): ToolResult {
        val player = ctx.knownPlayers(listOf(args.player), "player").single()
        ctx.memory.store.retireTrait(player.uuid, args.trait.trim(), args.reason.trim(), ctx.author())
        return ToolResult.ok("ok: retired the trait ${args.trait.trim()} of ${player.shownName}; it stays in the history", listOf(player))
    }
}

// ── set_skill ───────────────────────────────────────────────────────────────────────────────────────

@Serializable
data class SetSkillArgs(
    @Doc("The player, by name.")
    @MaxLen(NAME_MAX)
    val player: String,
    @Doc("pve, pvp, building, mining, exploration, survival, luck.")
    val skill: Skill,
    @Doc("0 … 10.")
    @Range(0.0, 10.0)
    val score: Int,
    @Doc("Why, in French, citing the numbers of the stats you were given: « 41 diamants, minerai moyen à y=-38 ».")
    @MaxLen(MemoryLimits.SKILL_RATIONALE)
    val rationale: String,
    @Doc("Rows of the memory that show it (M-…, I-…, C-…), if any.")
    @Items(1, 10)
    @MaxLen(REF_MAX)
    val evidence: List<String>? = null,
)

/** `set_skill` (M3 §8.3): a player's score in a skill, 0 to 10, justified by the stats. */
class SetSkillTool : MemoryTool<SetSkillArgs>(
    name = "set_skill",
    description = "Rates a skill of a player from 0 to 10, with a short rationale that cites the numbers of their stats " +
        "(never a number that is not in them). It replaces the previous score.",
    args = SetSkillArgs.serializer(),
    effect = ToolEffect.ENGINE,
) {
    override suspend fun act(ctx: ToolContext, args: SetSkillArgs): ToolResult {
        val player = ctx.knownPlayers(listOf(args.player), "player").single()
        val evidence = refsOf(args.evidence, "evidence", RefKind.entries.toSet())
        val skill = ctx.memory.store.setSkill(player.uuid, args.skill, args.score, args.rationale.trim(), evidence, ctx.author())
        return ToolResult.ok("ok: ${player.shownName} ${skill.skill.id} ${skill.score}/10", listOf(player))
    }
}

// ── set_relation ────────────────────────────────────────────────────────────────────────────────────

@Serializable
data class SetRelationArgs(
    @Doc("The player whose relation it is, by name.")
    @MaxLen(NAME_MAX)
    val player: String,
    @Doc("The other player, by name.")
    @MaxLen(NAME_MAX)
    val other: String,
    @Doc("How the player stands toward the other: duo, ally, rival, enemy, protector, protege, mentor, student, scapegoat, tormentor, strangers.")
    val kind: RelationKind,
    @Doc("What shows it, one factual line in French: « se disputent chaque coffre des chambres d'épreuve ».")
    @MaxLen(MemoryLimits.RELATION_NOTE)
    val note: String,
    @Doc("The rows of the memory that show it (M-…, I-…).")
    @Items(1, 10)
    @MaxLen(REF_MAX)
    val evidence: List<String>,
)

/** `set_relation` (M3 §8.3): how a player stands toward another (directed), with evidence. */
class SetRelationTool : MemoryTool<SetRelationArgs>(
    name = "set_relation",
    description = "Names how a player stands toward another (one direction: set the other one apart if it differs), from " +
        "the facts of the pairs (time together, fights, griefs) and the memories that show it. It replaces the previous one.",
    args = SetRelationArgs.serializer(),
    effect = ToolEffect.ENGINE,
) {
    override suspend fun act(ctx: ToolContext, args: SetRelationArgs): ToolResult {
        val (player, other) = ctx.knownPlayers(listOf(args.player), "player").single() to ctx.knownPlayers(listOf(args.other), "other").single()
        val evidence = refsOf(args.evidence, "evidence", RefKind.entries.toSet())
        val relation = ctx.memory.store.setRelation(player.uuid, other.uuid, args.kind, args.note.trim(), evidence, ctx.author())
        return ToolResult.ok("ok: ${player.shownName} → ${relation.other.shownName}: ${relation.kind.id}", listOf(player, other))
    }
}

// ── write_sheet ─────────────────────────────────────────────────────────────────────────────────────

/** The sections of a sheet the Dreamer writes (the « since last time » is the Chronicler's). */
@Serializable
enum class DreamSection(val section: SheetSection) {
    @SerialName("recap") RECAP(SheetSection.RECAP),
    @SerialName("playstyle") PLAYSTYLE(SheetSection.PLAYSTYLE),
    @SerialName("attitude") ATTITUDE(SheetSection.ATTITUDE),
    @SerialName("likes") LIKES(SheetSection.LIKES),
    @SerialName("fears") FEARS(SheetSection.FEARS),
    @SerialName("hooks") HOOKS(SheetSection.HOOKS),
}

@Serializable
data class WriteSheetArgs(
    @Doc("The player, by name.")
    @MaxLen(NAME_MAX)
    val player: String,
    @Doc(
        "recap (3 to 6 sentences: who they are in this world's story, what they do now, where they stand with the Narrator; 700 " +
            "characters), playstyle (300), attitude (toward the Narrator, 300), likes (and what annoys them, 200), fears (200), " +
            "hooks (what the Narrator can play on, 300).",
    )
    val section: DreamSection,
    @Doc("The section, in French, factual, in the third person, within its budget.")
    @MaxLen(SECTION_MAX)
    val text: String,
    @Doc("The rows of the memory it rests on (M-…, I-…, C-…), if any.")
    @Items(1, 10)
    @MaxLen(REF_MAX)
    val evidence: List<String>? = null,
)

/** `write_sheet` (M3 §8.3): a new version of a written section of a player's sheet. */
class WriteSheetTool : MemoryTool<WriteSheetArgs>(
    name = "write_sheet",
    description = "Writes a section of a player's sheet (recap, playstyle, attitude, likes, fears, hooks) as a new version: " +
        "whole, within its budget, factual, in French, in the third person. The numbers come from the stats you were given.",
    args = WriteSheetArgs.serializer(),
    effect = ToolEffect.ENGINE,
) {
    override suspend fun act(ctx: ToolContext, args: WriteSheetArgs): ToolResult {
        val player = ctx.knownPlayers(listOf(args.player), "player").single()
        val evidence = refsOf(args.evidence, "evidence", RefKind.entries.toSet())
        val doc = ctx.memory.store.writeDoc(DocKey.Sheet(player.uuid, args.section.section), args.text.trim(), evidence, ctx.author())
        return ToolResult.ok("ok: wrote the ${args.section.section.id} of ${player.shownName} (version ${doc.version})", listOf(player))
    }
}

// ── dream_hint ──────────────────────────────────────────────────────────────────────────────────────

@Serializable
data class DreamHintArgs(
    @Doc("The player, by name.")
    @MaxLen(NAME_MAX)
    val player: String,
    @Doc("A factual hint in French, in the third person, from what happened: « 3 morts de chute cette semaine, toutes dans la même chambre d'épreuve ».")
    @MaxLen(MemoryLimits.DREAM_HINT)
    val text: String,
)

/**
 * `dream_hint` (M3 §8.3, §8.7): a factual hint about a player, shown once to the Narrator at their next join;
 * the dream it may tell is its own. One per player: a new hint replaces the one not shown yet.
 */
class DreamHintTool : MemoryTool<DreamHintArgs>(
    name = "dream_hint",
    description = "Leaves the Narrator a factual hint about a player, shown once at their next join, from which it may tell " +
        "them a dream. A fact, never a dream nor a line to say. One per player: it replaces the one not shown yet.",
    args = DreamHintArgs.serializer(),
    effect = ToolEffect.ENGINE,
) {
    override suspend fun act(ctx: ToolContext, args: DreamHintArgs): ToolResult {
        val player = ctx.knownPlayers(listOf(args.player), "player").single()
        val store = ctx.memory.store
        val author = ctx.author()
        val note = store.addNote(NoteDraft(NoteKind.DREAM_HINT, args.text.trim(), listOf(player.uuid)), author).row
        val replaced = store.openNotes()
            .filter { it.kind == NoteKind.DREAM_HINT && it.ref != note.ref && it.shownCount == 0 && it.players.any { p -> p.uuid == player.uuid } }
            .onEach { store.markNote(it.ref, NoteStatus.DONE, author) }
        val also = replaced.takeIf { it.isNotEmpty() }?.let { "; it replaces ${it.joinToString { hint -> hint.ref.toString() }}" }.orEmpty()
        return ToolResult.ok("ok: dream hint ${note.ref} for ${player.shownName}: shown once, at their next join$also", listOf(player))
    }
}

// ── set_gag ─────────────────────────────────────────────────────────────────────────────────────────

@Serializable
data class SetGagArgs(
    @Doc("The running gag, e.g. C-27.")
    @MaxLen(REF_MAX)
    val id: String,
    @Doc("fresh (it still works), worn (let it rest), retired (over: it closes).")
    val state: GagState,
    @Doc("Why, one factual line in French: « plus aucune réaction depuis le 05-14 ».")
    @MaxLen(MemoryLimits.GAG_NOTE)
    val note: String,
)

/** `set_gag` (M3 §8.3, §9.3): the freshness of a running gag; `retired` also closes it. */
class SetGagTool : MemoryTool<SetGagArgs>(
    name = "set_gag",
    description = "Sets how fresh a running gag is, from the players' reactions: fresh, worn (to let rest) or retired (over, " +
        "which closes it). The code wears out a gag served too often by itself.",
    args = SetGagArgs.serializer(),
    effect = ToolEffect.ENGINE,
) {
    override suspend fun act(ctx: ToolContext, args: SetGagArgs): ToolResult {
        val ref = Ref.parse(args.id)?.takeIf { it.kind == RefKind.CANON }
            ?: return ToolResult.invalid("id must be a running gag of the canon, like C-27 (got ${ModelText.quote(args.id, REF_MAX)})")
        val gag = ctx.memory.store.setGag(ref, args.state, args.note.trim(), ctx.author())
        val closed = if (args.state == GagState.RETIRED) ", and closed" else ""
        return ToolResult.ok("ok: ${gag.ref} running gag is now ${args.state.id}$closed", gag.players)
    }
}

// ── write_review ────────────────────────────────────────────────────────────────────────────────────

@Serializable
data class WriteReviewArgs(
    @Doc("The review, in French, factual: what worked, what fell flat, the registers worn out, from the reactions you were given.")
    @MaxLen(MemoryLimits.REVIEW)
    val text: String,
)

/** `write_review` (M3 §8.2, §12.1): the review of the Narrator's play, a new version of the document `review`. */
class WriteReviewTool : MemoryTool<WriteReviewArgs>(
    name = "write_review",
    description = "Writes the review of the Narrator's play as a new version, whole: the effects observed (what made players " +
        "laugh, reply, or left them cold; the registers and tics worn out), never a portrait of the Narrator.",
    args = WriteReviewArgs.serializer(),
    effect = ToolEffect.ENGINE,
) {
    override suspend fun act(ctx: ToolContext, args: WriteReviewArgs): ToolResult {
        val doc = ctx.memory.store.writeDoc(DocKey.Review, args.text.trim(), emptyList(), ctx.author())
        return ToolResult.ok("ok: wrote the review (version ${doc.version})")
    }
}

// ── write_chronicle ─────────────────────────────────────────────────────────────────────────────────

/** The levels of the chronicle the Dreamer writes (chapters are the nap's). */
@Serializable
enum class DreamChronicleLevel(val level: ChronicleLevel) {
    @SerialName("session") SESSION(ChronicleLevel.SESSION),
    @SerialName("season") SEASON(ChronicleLevel.SEASON),
    @SerialName("saga") SAGA(ChronicleLevel.SAGA),
}

@Serializable
data class WriteChronicleArgs(
    @Doc("session (a server session over, covering its chapters), season (the season in progress, rewritten whole, covering its sessions), saga (rewritten when a season closes, covering the closed seasons).")
    val level: DreamChronicleLevel,
    @Doc("Its title in French, a few words.")
    @MaxLen(MemoryLimits.CHRONICLE_TITLE)
    val title: String,
    @Doc("The chronicle in French, factual, in the third person: session 1 500 characters, season 3 000, saga 4 500.")
    @MaxLen(CHRONICLE_MAX)
    val text: String,
    @Doc("The entries of the level below it covers (H-…): a session its chapters, a season all its sessions, the saga all the closed seasons.")
    @Items(1, 40)
    @MaxLen(REF_MAX)
    val covers: List<String>,
)

/**
 * `write_chronicle` (M3 §8.5): a level of the chronicle, its period the span of what it covers.
 *
 * - **session**: chapters that no session covers yet.
 * - **season**: rewrites the season in progress (keeping what it covered) or starts one; it closes at
 *   [DreamInputs.SEASON_SESSIONS] sessions or [DreamInputs.SEASON_DAYS] days, and the result then asks for
 *   the saga. A session in a closed season is not covered again.
 * - **saga**: rewritten when a season closed since the last one, covering the closed seasons.
 */
class WriteChronicleTool : MemoryTool<WriteChronicleArgs>(
    name = "write_chronicle",
    description = "Writes a level of the chronicle: a session over (covering its chapters), the season in progress rewritten " +
        "whole (covering all its sessions; it closes at 12 sessions or 30 days), the saga when a season closed (covering the " +
        "closed seasons). Factual, in French, in the third person, within the level's budget.",
    args = WriteChronicleArgs.serializer(),
    effect = ToolEffect.ENGINE,
) {
    override suspend fun act(ctx: ToolContext, args: WriteChronicleArgs): ToolResult {
        val covers = refsOf(args.covers, "covers", setOf(RefKind.CHRONICLE))
        val entries = covers.map { ref -> ctx.memory.store.row(ref) as? ChronicleEntry ?: refuse(ToolResult.invalid("covers: $ref does not exist")) }
        val from = entries.minOf { it.from }
        val to = entries.maxOf { it.to }
        val draft = ChronicleDraft(args.level.level, from, to, args.title.trim(), args.text.trim(), covers)
        return when (args.level) {
            DreamChronicleLevel.SESSION -> session(ctx, draft)
            DreamChronicleLevel.SEASON -> season(ctx, draft)
            DreamChronicleLevel.SAGA -> saga(ctx, draft)
        }
    }

    private suspend fun session(ctx: ToolContext, draft: ChronicleDraft): ToolResult {
        val sessions = ctx.memory.store.chronicle(ChronicleLevel.SESSION, ENTRIES_READ)
        draft.covers.firstNotNullOfOrNull { ref -> sessions.firstOrNull { ref in it.covers }?.let { ref to it.ref } }?.let { (ref, session) ->
            return ToolResult.invalid("covers: $ref is in the session $session already; a session covers chapters no session covers")
        }
        val entry = ctx.memory.store.addChronicle(draft, ctx.author())
        return ToolResult.ok("ok: wrote ${entry.ref}, the session ${ctx.period(entry)} (${entry.covers.size} chapter${plural(entry.covers.size)})")
    }

    private suspend fun season(ctx: ToolContext, draft: ChronicleDraft): ToolResult {
        val store = ctx.memory.store
        val seasons = store.chronicle(ChronicleLevel.SEASON, ENTRIES_READ)
        val open = seasons.firstOrNull { it.open }
        open?.let { season ->
            val kept = season.covers - draft.covers.toSet()
            if (kept.isNotEmpty()) return ToolResult.invalid("covers: the season in progress ${season.ref} covers ${kept.joinToString()}; its rewrite keeps them: cite them too")
        }
        draft.covers.firstNotNullOfOrNull { ref -> seasons.firstOrNull { !it.open && ref in it.covers }?.let { ref to it.ref } }?.let { (ref, season) ->
            return ToolResult.invalid("covers: $ref is in the closed season $season; a new season covers the sessions after it")
        }
        if (draft.covers.size > DreamInputs.SEASON_SESSIONS) {
            return ToolResult.invalid(
                "covers: a season covers ${DreamInputs.SEASON_SESSIONS} sessions at most; write it with the first ${DreamInputs.SEASON_SESSIONS} " +
                    "(it closes), then a new season with the others",
            )
        }
        val days = Duration.between(draft.from, draft.to).toDays()
        val closes = draft.covers.size >= DreamInputs.SEASON_SESSIONS || days >= DreamInputs.SEASON_DAYS
        val entry = store.addChronicle(draft.copy(open = !closes, rewrites = open?.ref), ctx.author())
        val what = "${entry.covers.size} session${plural(entry.covers.size)} over $days day${plural(days.toInt())}" + (open?.let { ", rewriting ${it.ref}" } ?: "")
        if (!closes) {
            return ToolResult.ok("ok: wrote ${entry.ref}, the season in progress ($what); it closes at ${DreamInputs.SEASON_SESSIONS} sessions or ${DreamInputs.SEASON_DAYS} days")
        }
        val closed = store.chronicle(ChronicleLevel.SEASON, ENTRIES_READ).filter { !it.open }.sortedBy { it.from }.map { it.ref }
        return ToolResult.ok(
            "ok: wrote ${entry.ref}, and the season is closed ($what); now rewrite the saga: write_chronicle level saga, " +
                "covering ${closed.joinToString()}",
        )
    }

    private suspend fun saga(ctx: ToolContext, draft: ChronicleDraft): ToolResult {
        val store = ctx.memory.store
        val seasons = store.chronicle(ChronicleLevel.SEASON, ENTRIES_READ)
        draft.covers.firstOrNull { ref -> seasons.any { it.ref == ref && it.open } }?.let {
            return ToolResult.invalid("covers: $it is the season in progress; the saga covers the closed seasons only")
        }
        val saga = store.chronicle(ChronicleLevel.SAGA, 1).firstOrNull()
        saga?.let { current ->
            val kept = current.covers - draft.covers.toSet()
            if (kept.isNotEmpty()) return ToolResult.invalid("covers: the saga ${current.ref} covers ${kept.joinToString()}; its rewrite keeps them: cite them too")
            if (draft.covers.all { it in current.covers }) {
                return ToolResult.invalid("no season closed since the saga ${current.ref}: the saga is rewritten when a season closes")
            }
        }
        val entry = store.addChronicle(draft.copy(open = true, rewrites = saga?.ref), ctx.author())
        return ToolResult.ok("ok: wrote ${entry.ref}, the saga (${entry.covers.size} season${plural(entry.covers.size)}${saga?.let { ", rewriting ${it.ref}" } ?: ""})")
    }

    private companion object {
        /** Entries of a level read to check the covers: far more than a world will ever have open. */
        const val ENTRIES_READ = 1_000
    }
}

// ── write_grimoire ──────────────────────────────────────────────────────────────────────────────────

@Serializable
data class WriteGrimoireArgs(
    @Doc(
        "The whole Grimoire, in French, factual, in the third person, 10 000 characters at most: the seven headings ## Players, " +
            "## Running gags, ## Arcs, ## What works, ## What falls flat, ## Plans, ## Watch out, in this order, each with at least one " +
            "line (« - aucun » when there is nothing).",
    )
    @MaxLen(MemoryLimits.GRIMOIRE)
    val text: String,
)

/** `write_grimoire` (M3 §8.4): the Grimoire rewritten whole, checked by [GrimoireFormat] before the store writes it. */
class WriteGrimoireTool : MemoryTool<WriteGrimoireArgs>(
    name = "write_grimoire",
    description = "Rewrites the Grimoire whole, as a new version: the Narrator's compact memory, always in its briefing. What " +
        "serves to play, drawn from the sheets, the review and the chronicle; never a copy of the canon nor of the sheets. " +
        "Refused, with every problem listed, when a section is missing, out of order or empty, or the text is too long.",
    args = WriteGrimoireArgs.serializer(),
    effect = ToolEffect.ENGINE,
) {
    override suspend fun act(ctx: ToolContext, args: WriteGrimoireArgs): ToolResult {
        val text = args.text.trim()
        val problems = GrimoireFormat.problems(text)
        if (problems.isNotEmpty()) return ToolResult.invalid("the Grimoire is not written: ${problems.joinToString("; ")}")
        val doc = ctx.memory.store.writeDoc(DocKey.Grimoire, text, emptyList(), ctx.author())
        return ToolResult.ok("ok: wrote the Grimoire (version ${doc.version}, ${text.length} of ${MemoryLimits.GRIMOIRE} characters)")
    }
}

// ── merge_memories ──────────────────────────────────────────────────────────────────────────────────

@Serializable
data class MergeMemoriesArgs(
    @Doc("The memory kept, e.g. M-12.")
    @MaxLen(REF_MAX)
    val keep: String,
    @Doc("The memories merged into it: they are archived, their evidence and recalls join it.")
    @Items(1, 10)
    @MaxLen(REF_MAX)
    val merged: List<String>,
    @Doc("The kept memory's new text, in French, in the third person, when the merge adds to it; leave out to keep its text.")
    @MaxLen(MemoryLimits.MEMORY_CONTENT)
    val text: String? = null,
)

/** `merge_memories` (M3 §8.3, §8.6): near duplicates merged into one memory; the others are archived, never deleted. */
class MergeMemoriesTool : MemoryTool<MergeMemoriesArgs>(
    name = "merge_memories",
    description = "Merges memories that tell the same moment into one: the others are archived (merged into it), their " +
        "evidence and recalls join it, it keeps the larger importance and stability. Only true duplicates: two moments " +
        "that look alike stay apart.",
    args = MergeMemoriesArgs.serializer(),
    effect = ToolEffect.ENGINE,
) {
    override suspend fun act(ctx: ToolContext, args: MergeMemoriesArgs): ToolResult {
        val keep = refsOf(listOf(args.keep), "keep", setOf(RefKind.MEMORY)).single()
        val merged = refsOf(args.merged, "merged", setOf(RefKind.MEMORY))
        val row = ctx.memory.store.merge(keep, merged, args.text?.trim(), ctx.author())
        return ToolResult.ok("ok: merged ${merged.joinToString()} into ${row.ref}, archived; ${row.ref}: « ${ModelText.escape(row.title)} »", row.players)
    }
}

// ── Shared ──────────────────────────────────────────────────────────────────────────────────────────

/** [texts] as refs of [kinds]; refuses anything else. */
private fun refsOf(texts: List<String>?, field: String, kinds: Set<RefKind>): List<Ref> = texts.orEmpty().map { text ->
    Ref.parse(text)?.takeIf { it.kind in kinds }
        ?: refuse(ToolResult.invalid("$field: ${ModelText.quote(text, REF_MAX)} is not ${kinds.joinToString(" nor ") { "${it.prefix}-…" }}: cite the refs as given (M-120, I-512)"))
}.distinct()

/** `10-03`, in the server's time zone. */
private fun ToolContext.day(at: Instant): String = DAY.withZone(clock.zone).format(at)

/** `10-03 20:00–23:05`, or `10-03 20:00–10-04 01:10` over two days. */
private fun ToolContext.period(entry: ChronicleEntry): String {
    val zone = clock.zone
    val sameDay = entry.from.atZone(zone).toLocalDate() == entry.to.atZone(zone).toLocalDate()
    return "${DAY_TIME.withZone(zone).format(entry.from)}–${(if (sameDay) TIME else DAY_TIME).withZone(zone).format(entry.to)}"
}

private fun plural(count: Int): String = if (count == 1) "" else "s"

private val DAY = DateTimeFormatter.ofPattern("MM-dd", Locale.ROOT)
private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
private val DAY_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT)
