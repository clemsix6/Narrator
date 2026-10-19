package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.util.UUID

/*
 * The memory's vocabulary and rows (design M3 §3): the values the store accepts, the rows it returns and
 * the drafts it takes. Codes are stored as their `id`: the constant's name in lowercase, e.g.
 * `running_gag`. Enums a tool receives as an argument are `@Serializable`, with a snake_case
 * `@SerialName` per value (T08).
 */

/** The constant of [E] whose `id` (lowercase name) is [id]; null for anything else. */
inline fun <reified E : Enum<E>> enumOfId(id: String): E? = enumValues<E>().firstOrNull { it.name.lowercase() == id }

/** The tables of the memory schema (`V2__memory.sql`), readable by `sql` but not described to the model. */
object MemorySchema {
    /**
     * The latest migration the memory reads: `V3__mechanics.sql` adds `canon.mechanic` and `canon.theme` (M4
     * §3.3) to the tables of `V2__memory.sql`. A Journal below it leaves the memory off.
     */
    const val MIGRATION = "V3__mechanics.sql"

    val TABLES: List<String> = listOf(
        "memory_state", "memories", "canon", "interactions", "player_traits", "player_skills", "player_relations",
        "sheet_stats", "pair_stats", "memory_docs", "chronicle", "notes", "recalls", "memory_runs", "memory_log",
        "search_docs", "search_postings",
    )
}

/**
 * Lengths and bounds of what the memory stores (M3 §3.3, §3.6), in characters (code points). The store
 * refuses beyond; tools use the same constants in their `@MaxLen`.
 */
object MemoryLimits {
    const val MEMORY_TITLE = 80
    const val MEMORY_CONTENT = 400
    const val CANON_STATEMENT = 300
    const val CANON_QUOTE = 200
    const val CANON_STAKES = 160
    const val RESOLUTION = 200
    const val INTERACTION_SUMMARY = 160
    const val INTERACTION_QUOTE = 120
    const val TRAIT_LABEL = 40
    const val TRAIT_NOTE = 160
    const val SKILL_RATIONALE = 200
    const val RELATION_NOTE = 160
    const val GAG_NOTE = 160
    const val CHRONICLE_TITLE = 80
    const val NOTE_TEXT = 300
    const val DREAM_HINT = 200
    const val GRIMOIRE = 10_000
    const val REVIEW = 3_000

    /** Importance of memories and canon entries: 1 trivial … 10 unforgettable. */
    val IMPORTANCE = 1..10

    /** Valence of memories and interactions. */
    val VALENCE = -3..3

    /** Intensity of a trait. */
    val INTENSITY = 1..5

    /** A skill score. */
    val SKILL_SCORE = 0..10

    /** Arcs active at once, at most (M4 §10): a third is refused (`arc_limit`). */
    const val ACTIVE_ARCS = 2

    /** An active arc always ends within this many days; without `due_in_minutes`, it is due then (M4 §10). */
    const val ARC_MAX_DAYS = 7

    /** A theme rests this many hours of world play once its arc closed (M4 §10). */
    const val THEME_REST_PLAY_HOURS = 20

    /** An arc's theme (`canon.theme`): lowercase letters, digits and `_`, 2 to 40 characters. */
    val THEME = Regex("[a-z0-9_]{2,40}")
}

/** The tables whose rows the model can cite, with the prefix of their reference (M3 §3.1). */
enum class RefKind(val prefix: String, val table: String) {
    MEMORY("M", "memories"),
    CANON("C", "canon"),
    INTERACTION("I", "interactions"),
    CHRONICLE("H", "chronicle"),
    NOTE("N", "notes"),
    ;

    /** `memory`, `canon`…: also the value of `search_docs.kind`. */
    val id: String get() = name.lowercase()

    companion object {
        /** What recall searches: notes are shown, never searched. */
        val searchable: Set<RefKind> = setOf(MEMORY, CANON, INTERACTION, CHRONICLE)

        /** The kind whose prefix is [prefix], whatever its case; null for anything else. */
        fun ofPrefix(prefix: String): RefKind? = entries.firstOrNull { it.prefix.equals(prefix, ignoreCase = true) }
    }
}

/**
 * A citable memory row: `C-45`. The id alone names the row (one sequence for the five tables,
 * `IdKind.MEMORY`); the kind says which table it is in.
 */
data class Ref(val kind: RefKind, val id: Long) {
    init {
        require(id > 0) { "a memory id is positive: $id" }
    }

    override fun toString(): String = "${kind.prefix}-$id"

    companion object {
        private val PATTERN = Regex("""([A-Za-z])-?(\d{1,18})""")

        /** `C-45`, `c45`, ` M-812 ` → the ref; null for anything else (another prefix, no number, `C-0`). */
        fun parse(text: String): Ref? {
            val match = PATTERN.matchEntire(text.trim()) ?: return null
            val kind = RefKind.ofPrefix(match.groupValues[1]) ?: return null
            val id = match.groupValues[2].toLong()
            return if (id > 0) Ref(kind, id) else null
        }

        /**
         * A canon row as a player, an operator or the model names a challenge, a duel or a rule: `C-50`, `c50` or
         * `50` → `C-50`; null for anything else (another kind, `0`).
         */
        fun canonOf(typed: String): Ref? {
            val ref = parse(typed) ?: typed.trim().toLongOrNull()?.takeIf { it > 0 }?.let { Ref(RefKind.CANON, it) }
            return ref?.takeIf { it.kind == RefKind.CANON }
        }
    }
}

/** Who wrote a row (`source` columns). */
enum class Source {
    DIRECTOR, CHRONICLER, DREAMER, ENGINE, OPERATOR;

    val id: String get() = name.lowercase()
}

/**
 * Who writes, for the audit (`memory_log`, `source`, `run_id`, `turn_id`, `conversation_id` columns).
 *
 * @property operator the operator's name, for [Source.OPERATOR].
 */
data class Author(
    val source: Source,
    val runId: Long? = null,
    val turnId: Long? = null,
    val conversationId: String? = null,
    val operator: String? = null,
) {
    /** `director`, `chronicler`, `operator:Clement`: the `memory_log.actor` column. */
    val actor: String get() = if (source == Source.OPERATOR && operator != null) "operator:$operator" else source.id
}

/** What kind of memory: a moment (`episode`), or a fact a player revealed (`observation`). */
enum class MemoryKind {
    EPISODE, OBSERVATION;

    val id: String get() = name.lowercase()
}

/** Status of a canon entry (M3 §3.6). Only [PLANNED] and [ACTIVE] are open; a closed entry never opens again. */
enum class CanonStatus {
    PLANNED, ACTIVE, FULFILLED, BROKEN, EXPIRED, WON, LOST, ABANDONED, CANCELLED, RESOLVED, RETIRED;

    val id: String get() = name.lowercase()

    val open: Boolean get() = this == PLANNED || this == ACTIVE
}

/**
 * What the Narrator stated, promised or decided (M3 §3.6).
 *
 * @property dueAllowed it may have a due time (a `DUE` event wakes the Director then).
 */
enum class CanonKind(val dueAllowed: Boolean) {
    PROMISE(true), THREAT(true), PROPHECY(true), CHALLENGE(true), ARC(true),
    LAW(false), NICKNAME(false), TITLE(false), PLACE(false), LORE(false), OPINION(false), RUNNING_GAG(false), CLAIM(false),

    /**
     * The Narrator's secret intention, a rule the mechanics engine runs (M4 §3.3, §8): a trap, a welcome, a
     * reaction prepared in advance. It exists only with `mechanic = rule`; the engine holds its deadline.
     */
    PLAN(false),
    ;

    val id: String get() = name.lowercase()

    /** The statuses that close it. */
    val closedBy: Set<CanonStatus>
        get() = when (this) {
            PROMISE, THREAT, PROPHECY -> setOf(CanonStatus.FULFILLED, CanonStatus.BROKEN, CanonStatus.EXPIRED, CanonStatus.CANCELLED)
            CHALLENGE -> setOf(CanonStatus.WON, CanonStatus.LOST, CanonStatus.ABANDONED, CanonStatus.EXPIRED, CanonStatus.CANCELLED)
            ARC -> setOf(CanonStatus.RESOLVED, CanonStatus.ABANDONED)
            // Fulfilled once it fired (a plan of one use), expired at its end, cancelled by remove_rule.
            PLAN -> setOf(CanonStatus.FULFILLED, CanonStatus.EXPIRED, CanonStatus.CANCELLED)
            else -> setOf(CanonStatus.RETIRED)
        }

    /** The statuses it may start with: [CanonStatus.ACTIVE], or [CanonStatus.PLANNED] for an arc the Dreamer prepares. */
    val startsAs: Set<CanonStatus>
        get() = if (this == ARC) setOf(CanonStatus.PLANNED, CanonStatus.ACTIVE) else setOf(CanonStatus.ACTIVE)

    /** Whether an entry of this kind may go from [from] to [to]: open to one of [closedBy], or a planned arc to active. */
    fun allows(from: CanonStatus, to: CanonStatus): Boolean = when {
        !from.open -> false
        this == ARC && from == CanonStatus.PLANNED && to == CanonStatus.ACTIVE -> true
        else -> to in closedBy
    }

    /** At most one open entry of this kind per player: a new one retires the previous one (`replaces`). */
    val onePerPlayer: Boolean get() = this == NICKNAME || this == TITLE
}

/**
 * What the mechanics engine tracks among the canon (`canon.mechanic`, M4 §3.3): such a row is written by an
 * engine's tool (`Source.DIRECTOR`), changes status only through `Source.ENGINE` or `Source.OPERATOR`, is never
 * deduplicated, and has no `due_at` (the engine holds the deadline).
 *
 * @property kinds the canon kinds a row of this mechanic may have.
 * @property endedBy the Director's tool that ends it, for the store's refusal: `C-50 is a challenge the engine
 *   tracks: end it with end_challenge`.
 */
enum class CanonMechanic(val kinds: Set<CanonKind>, val endedBy: String) {
    CHALLENGE(setOf(CanonKind.CHALLENGE), "end_challenge"),
    DUEL(setOf(CanonKind.CHALLENGE), "end_challenge"),
    RULE(setOf(CanonKind.LAW, CanonKind.PLAN), "remove_rule"),

    /** A module of the Forge deployed as a law or a plan (M5 §6.4). */
    MODULE(setOf(CanonKind.LAW, CanonKind.PLAN), "stop_module"),
    ;

    /** `challenge`: the value of `canon.mechanic`. */
    val id: String get() = name.lowercase()

    companion object {
        /** The mechanic stored as [id]; null for anything else (a row the engine does not track). */
        fun of(id: String?): CanonMechanic? = id?.let { enumOfId<CanonMechanic>(it) }
    }
}

/**
 * The kind of a rated interaction (M3 §3.6, §6.2).
 *
 * @property counts it counts toward the player's favor: the player's behavior, not the Narrator's answer.
 * @property disrespect it is always a lack of respect (an interaction with a valence of -2 or less is too).
 */
@Serializable
enum class InteractionKind(val counts: Boolean, val disrespect: Boolean) {
    @SerialName("conversation") CONVERSATION(true, false),
    @SerialName("prayer") PRAYER(true, false),
    @SerialName("request") REQUEST(true, false),
    @SerialName("offering") OFFERING(true, false),
    @SerialName("compliment") COMPLIMENT(true, false),
    @SerialName("apology") APOLOGY(true, false),
    @SerialName("help") HELP(true, false),
    @SerialName("joke") JOKE(true, false),
    @SerialName("bet") BET(true, false),
    @SerialName("challenge_result") CHALLENGE_RESULT(true, false),
    @SerialName("provocation") PROVOCATION(true, true),
    @SerialName("insult") INSULT(true, true),
    @SerialName("manipulation") MANIPULATION(true, true),
    @SerialName("cheating") CHEATING(true, true),
    @SerialName("grief") GRIEF(true, false),
    @SerialName("punishment") PUNISHMENT(false, false),
    @SerialName("blessing") BLESSING(false, false),
    @SerialName("gift") GIFT(false, false),
    ;

    val id: String get() = name.lowercase()

    /**
     * Whether an interaction of this kind with [valence] is a lack of respect toward the Narrator (kept on
     * the sheet): always for [disrespect] kinds, and for the player's own behavior toward the Narrator at
     * -2 or less. A grief wrongs another player, and the Narrator's answers (punishment, blessing, gift)
     * are not the player's conduct: neither is a lack of respect by its valence.
     */
    fun isDisrespect(valence: Int): Boolean = disrespect || (counts && this != GRIEF && valence <= -2)
}

/**
 * Personality axes (M3 §3.6): one row per axis and player, on one side ([low] or [high]); free traits
 * (`free:<slug>`) besides, [MAX_FREE] at most per player.
 */
enum class TraitAxis(val low: String, val high: String) {
    BOLDNESS("cautious", "reckless"),
    SOCIABILITY("shy", "outgoing"),
    DEFERENCE("respectful", "defiant"),
    COOPERATION("loner", "team_player"),
    GENEROSITY("stingy", "generous"),
    TEMPER("forgiving", "vindictive"),
    HUMOR("serious", "playful"),
    HONESTY("honest", "scheming"),
    MODESTY("modest", "boastful"),
    PERSISTENCE("quitter", "stubborn"),
    CURIOSITY("homebody", "adventurous"),
    PATIENCE("impatient", "patient"),
    ;

    /** `boldness`: the `player_traits.trait` of this axis. */
    val id: String get() = name.lowercase()

    /** The pole of [side]: -1 [low], +1 [high]. */
    fun pole(side: Int): String = if (side < 0) low else high

    companion object {
        /** Prefix of a free trait: `free:obsede_par_le_butin`. */
        const val FREE_PREFIX = "free:"

        /** Free traits a player may have at most. */
        const val MAX_FREE = 3

        /** The slug of a free trait: lowercase letters, digits and `_`, 2 to 40 characters. */
        val FREE_SLUG = Regex("[a-z0-9_]{2,40}")

        fun of(trait: String): TraitAxis? = enumOfId(trait)

        /** Whether [trait] names a trait: an axis id or `free:<slug>`. */
        fun isTrait(trait: String): Boolean = of(trait) != null || isFree(trait)

        fun isFree(trait: String): Boolean = trait.startsWith(FREE_PREFIX) && FREE_SLUG.matches(trait.removePrefix(FREE_PREFIX))
    }
}

@Serializable
enum class Skill {
    @SerialName("pve") PVE,
    @SerialName("pvp") PVP,
    @SerialName("building") BUILDING,
    @SerialName("mining") MINING,
    @SerialName("exploration") EXPLORATION,
    @SerialName("survival") SURVIVAL,
    @SerialName("luck") LUCK,
    ;

    val id: String get() = name.lowercase()
}

/** How a player stands toward another (directed). */
@Serializable
enum class RelationKind {
    @SerialName("duo") DUO,
    @SerialName("ally") ALLY,
    @SerialName("rival") RIVAL,
    @SerialName("enemy") ENEMY,
    @SerialName("protector") PROTECTOR,
    @SerialName("protege") PROTEGE,
    @SerialName("mentor") MENTOR,
    @SerialName("student") STUDENT,
    @SerialName("scapegoat") SCAPEGOAT,
    @SerialName("tormentor") TORMENTOR,
    @SerialName("strangers") STRANGERS,
    ;

    val id: String get() = name.lowercase()
}

/** The written sections of a player sheet (`memory_docs` `sheet:<uuid>:<section>`), with their budget. */
@Serializable
enum class SheetSection(val maxChars: Int) {
    @SerialName("recap") RECAP(700),
    @SerialName("since_last") SINCE_LAST(600),
    @SerialName("playstyle") PLAYSTYLE(300),
    @SerialName("attitude") ATTITUDE(300),
    @SerialName("likes") LIKES(200),
    @SerialName("fears") FEARS(200),
    @SerialName("hooks") HOOKS(300),
    ;

    val id: String get() = name.lowercase()
}

/** Levels of the chronicle (M3 §8.5), with the budget of an entry. */
@Serializable
enum class ChronicleLevel(val maxChars: Int) {
    @SerialName("chapter") CHAPTER(4_000),
    @SerialName("session") SESSION(1_500),
    @SerialName("season") SEASON(3_000),
    @SerialName("saga") SAGA(4_500),
    ;

    val id: String get() = name.lowercase()

    /** The level its entries cover: sessions cover chapters…; null for chapters. */
    val below: ChronicleLevel? get() = entries.getOrNull(ordinal - 1)
}

enum class NoteKind {
    /** An incoherence the Chronicler found between the Narrator's words and the canon. */
    COHERENCE,

    /** The Dreamer's factual hint about a player, shown once at their next join. */
    DREAM_HINT,

    /** A commitment that expired without a resolution. */
    EXPIRED,

    /** `/narrator memory note`. */
    OPERATOR,
    ;

    val id: String get() = name.lowercase()
}

/** Where a note stands: shown to the Director yet or not, or done. */
enum class NoteStatus {
    OPEN, SHOWN, DONE;

    val id: String get() = name.lowercase()
}

/** Freshness of a running gag (the Dreamer, and the code when it is served too often, M3 §9.3). */
@Serializable
enum class GagState {
    @SerialName("fresh") FRESH,
    @SerialName("worn") WORN,
    @SerialName("retired") RETIRED,
    ;

    val id: String get() = name.lowercase()
}

/** What a memory job does (`memory_runs.kind`). */
enum class RunKind {
    BATCH, NAP, DREAM, HYGIENE;

    val id: String get() = name.lowercase()
}

/** How a memory row was put before a model (`recalls.channel`); it sets how much a memory is reinforced (M3 §9.2). */
enum class RecallChannel {
    BRIEFING, JOIN, REMINISCENCE, RECALL_TOOL, PROFILE, DREAM,

    /** The Narrator took it up in a line (the nap's `mark_used`). */
    USED,
    ;

    val id: String get() = name.lowercase()
}

/** What a `memory_log` row did. */
enum class LogOp {
    INSERT, UPDATE, ARCHIVE, RESTORE, MERGE, ROLLBACK, REVERT;

    val id: String get() = name.lowercase()
}

/**
 * The tags of memories (M3 §3.6): an English controlled vocabulary that bridges the (English) events and
 * the (French) memories, plus two free forms, `gag:<slug>` and `arc:C-<id>`. Game ids go in `entities`.
 */
object MemoryTags {
    val ALL: Set<String> = setOf(
        "death", "fall", "lava", "drowning", "explosion", "mob", "pvp", "duel", "insult", "provocation", "apology",
        "compliment", "gift", "punishment", "blessing", "challenge", "bet", "promise", "threat", "prophecy", "law",
        "nickname", "title", "build", "grief", "exploration", "mining", "farming", "trade", "first", "boss", "nether",
        "end", "raid", "luck", "fear", "friendship", "rivalry", "help", "joke", "prayer", "offering", "running_gag",
    )

    private val GAG = Regex("gag:[a-z0-9_]{2,40}")
    private val ARC = Regex("arc:C-[1-9][0-9]{0,17}")

    /** Whether [tag] is in the vocabulary or one of its free forms. */
    fun isValid(tag: String): Boolean = tag in ALL || GAG.matches(tag) || ARC.matches(tag)
}

/** Keys of the versioned documents (`memory_docs.key`). */
sealed interface DocKey {
    val key: String

    data object Grimoire : DocKey {
        override val key = "grimoire"
    }

    /** The Dreamer's review of the Narrator's play: what worked, what fell flat. */
    data object Review : DocKey {
        override val key = "review"
    }

    data class Sheet(val player: UUID, val section: SheetSection) : DocKey {
        override val key = "sheet:$player:${section.id}"
    }

    companion object {
        /** `grimoire`, `review`, `sheet:<uuid>:<section>` → the key; null for anything else. */
        fun parse(key: String): DocKey? = when (key) {
            Grimoire.key -> Grimoire
            Review.key -> Review
            else -> {
                val parts = key.split(':')
                val player = parts.getOrNull(1)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                val section = parts.getOrNull(2)?.let { enumOfId<SheetSection>(it) }
                if (parts.size == 3 && parts[0] == "sheet" && player != null && section != null) Sheet(player, section) else null
            }
        }
    }
}

// ── Rows ──────────────────────────────────────────────────────────────────────────────────────────────

/** A row of one of the five citable tables, as `recall`, `/narrator memory show` and the briefing see it. */
sealed interface MemoryRow {
    val ref: Ref
}

/** A row of `memories`. [place]: where it happened; [mergedInto]: the memory that absorbed this one. */
data class MemoryEntry(
    override val ref: Ref,
    val kind: MemoryKind,
    val createdAt: Instant,
    val occurredFrom: Instant,
    val occurredTo: Instant,
    val title: String,
    val content: String,
    val players: List<PlayerRef>,
    val entities: List<String>,
    val tags: List<String>,
    val importance: Int,
    val valence: Int,
    val place: Pos?,
    val stabilityHours: Double,
    val reinforcedPlaySeconds: Long,
    val recalls: Int,
    val lastRecalledAt: Instant?,
    val source: Source,
    val archived: Boolean,
    val mergedInto: Ref?,
) : MemoryRow

/**
 * A row of `canon`. [winners]: the players who won a challenge; [replaces]: the entry it superseded;
 * [mechanic]: what the mechanics engine tracks it as (M4 §3.3), null for a row it does not track; [theme]: an
 * arc's theme (M4 §10).
 */
data class CanonEntry(
    override val ref: Ref,
    val kind: CanonKind,
    val createdAt: Instant,
    val statement: String,
    val quote: String?,
    val players: List<PlayerRef>,
    val stakes: String?,
    val importance: Int,
    val status: CanonStatus,
    val dueAt: Instant?,
    val resolvedAt: Instant?,
    val resolution: String?,
    val winners: List<PlayerRef>,
    val replaces: Ref?,
    val place: Pos?,
    val uses: Int,
    val lastUsedAt: Instant?,
    val freshness: GagState?,
    val source: Source,
    val archived: Boolean,
    val mechanic: CanonMechanic? = null,
    val theme: String? = null,
) : MemoryRow

/**
 * A row of `interactions`. [playedSeconds]: the player's play clock when it happened; [toolCalls]: the
 * `tool_calls` that punished, blessed or gave.
 */
data class Interaction(
    override val ref: Ref,
    val ts: Instant,
    val player: PlayerRef,
    val kind: InteractionKind,
    val valence: Int,
    val counts: Boolean,
    val disrespect: Boolean,
    val summary: String,
    val quote: String?,
    val playedSeconds: Long,
    val canon: Ref?,
    val memory: Ref?,
    val toolCalls: List<Long>,
    val source: Source,
    val archived: Boolean,
) : MemoryRow

/**
 * A row of `player_traits`: [trait] is an axis id ([TraitAxis.id]) or `free:<slug>`; [side] -1 or +1 on an
 * axis, 0 for a free trait. [confidence] is the value set; it decays at read time (M3 §9.3).
 */
data class Trait(
    val player: UUID,
    val trait: String,
    val side: Int,
    val label: String?,
    val intensity: Int,
    val confidence: Double,
    val evidence: List<Ref>,
    val since: Instant,
    val trend: Int,
    val note: String?,
    val setAt: Instant,
    val setPlayedSeconds: Long,
    val retired: Boolean,
)

/** A row of `player_skills`. */
data class SkillScore(
    val player: UUID,
    val skill: Skill,
    val score: Int,
    val rationale: String,
    val evidence: List<Ref>,
    val setAt: Instant,
)

/** A row of `player_relations`: how [player] stands toward [other]. */
data class Relation(
    val player: UUID,
    val other: PlayerRef,
    val kind: RelationKind,
    val note: String,
    val evidence: List<Ref>,
    val setAt: Instant,
)

/** A version of a document (`memory_docs`); [restoredFrom]: the version a rollback copied. */
data class MemoryDoc(
    val key: String,
    val version: Int,
    val content: String,
    val evidence: List<Ref>,
    val writtenAt: Instant,
    val source: Source,
    val restoredFrom: Int? = null,
)

/**
 * A row of `chronicle`. [covers]: the entries one level down; [open]: the season in progress and the saga,
 * rewritten; [supersededBy]: the entry that rewrote this one.
 */
data class ChronicleEntry(
    override val ref: Ref,
    val level: ChronicleLevel,
    val from: Instant,
    val to: Instant,
    val title: String,
    val summary: String,
    val covers: List<Ref>,
    val conversationId: String?,
    val open: Boolean,
    val supersededBy: Ref?,
    val source: Source,
) : MemoryRow

/** A row of `notes`, for the Director. */
data class Note(
    override val ref: Ref,
    val createdAt: Instant,
    val kind: NoteKind,
    val text: String,
    val players: List<PlayerRef>,
    val refs: List<Ref>,
    val status: NoteStatus,
    val shownCount: Int,
) : MemoryRow

/**
 * A row of `memory_log`: one change. [rowKey]: `812`, `b1f…:boldness`, `grimoire:12`; [before] null for
 * an insert, [after] the row's columns after the change.
 */
data class MemoryLogRow(
    val ts: Instant,
    val actor: String,
    val runId: Long?,
    val turnId: Long?,
    val table: String,
    val rowKey: String,
    val op: LogOp,
    val before: JsonObject?,
    val after: JsonObject?,
    val reason: String?,
)

// ── Drafts: what a writer gives; the store computes the rest ────────────────────────────────────────

/*
 * The store computes, never the writer: ids, timestamps of writing, play clocks (`played_s`,
 * `reinforced_play_s`, `set_played_s`), the initial stability (M3 §9.1), `counts` and `disrespect`
 * ([InteractionKind]), the place of a memory (the first of its evidence events that has one), the
 * previous nickname or title it replaces, and a trait's `since` and `trend`.
 */

/**
 * A memory to write. [occurredFrom] and [occurredTo]: null to take them from the evidence (first and last
 * evidence event), else the time of writing. [evidenceEvents]: `events.id` / `chat.id`; [evidenceTools]:
 * `tool_calls.id`.
 */
data class MemoryDraft(
    val kind: MemoryKind,
    val title: String,
    val content: String,
    val players: List<UUID> = emptyList(),
    val entities: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val importance: Int = 5,
    val valence: Int = 0,
    val occurredFrom: Instant? = null,
    val occurredTo: Instant? = null,
    val evidenceEvents: List<Long> = emptyList(),
    val evidenceTools: List<Long> = emptyList(),
)

/**
 * A canon entry to write. [status]: null for the kind's start ([CanonStatus.ACTIVE]; an arc may start
 * [CanonStatus.PLANNED]). [place]: where a `place` is (required for that kind), else where it was said.
 * [mechanic]: a row the mechanics engine tracks (M4 §3.3), written only by its tools (`Source.DIRECTOR`) with a
 * kind of [CanonMechanic.kinds]; [theme]: an arc's theme ([MemoryLimits.THEME], M4 §10).
 */
data class CanonDraft(
    val kind: CanonKind,
    val statement: String,
    val quote: String? = null,
    val players: List<UUID> = emptyList(),
    val stakes: String? = null,
    val importance: Int = 5,
    val status: CanonStatus? = null,
    val dueAt: Instant? = null,
    val place: Pos? = null,
    val evidenceEvents: List<Long> = emptyList(),
    val evidenceTools: List<Long> = emptyList(),
    val mechanic: CanonMechanic? = null,
    val theme: String? = null,
)

/**
 * An interaction to write. [at]: null to take it from the first evidence event, else the first tool call,
 * else the time of writing. [toolCalls]: the `tool_calls` that punished, blessed or gave.
 */
data class InteractionDraft(
    val player: UUID,
    val kind: InteractionKind,
    val valence: Int,
    val summary: String,
    val quote: String? = null,
    val canon: Ref? = null,
    val memory: Ref? = null,
    val toolCalls: List<Long> = emptyList(),
    val evidenceEvents: List<Long> = emptyList(),
    val at: Instant? = null,
)

/**
 * A trait to set (the Dreamer). [side]: -1 or +1 on an axis, 0 for a free trait, which needs a [label].
 * [evidence]: `M-` and `I-` rows about this player, at least one.
 */
data class TraitDraft(
    val player: UUID,
    val trait: String,
    val side: Int,
    val label: String? = null,
    val intensity: Int,
    val confidence: Double,
    val evidence: List<Ref>,
    val note: String? = null,
)

/**
 * A chronicle entry to write. [covers]: entries of the level below ([ChronicleLevel.below]), none for a
 * chapter. [rewrites]: the open entry (season in progress, saga) this one replaces: it gets
 * `superseded_by` and closes.
 */
data class ChronicleDraft(
    val level: ChronicleLevel,
    val from: Instant,
    val to: Instant,
    val title: String,
    val summary: String,
    val covers: List<Ref> = emptyList(),
    val conversationId: String? = null,
    val open: Boolean = false,
    val rewrites: Ref? = null,
)

/** A note for the Director. [refs]: the `C-`/`M-` rows it is about. */
data class NoteDraft(
    val kind: NoteKind,
    val text: String,
    val players: List<UUID> = emptyList(),
    val refs: List<Ref> = emptyList(),
)
