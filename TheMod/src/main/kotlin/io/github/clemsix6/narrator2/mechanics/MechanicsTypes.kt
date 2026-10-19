package io.github.clemsix6.narrator2.mechanics

import io.github.clemsix6.narrator2.memory.MemoryLimits
import io.github.clemsix6.narrator2.tool.Doc
import io.github.clemsix6.narrator2.tool.Items
import io.github.clemsix6.narrator2.tool.MaxLen
import io.github.clemsix6.narrator2.tool.Range
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * The specifications of the mechanics (M4 §5.1, §5.5, §6.3, §8.1). They ARE the arguments of the tools
 * (`start_challenge`, `propose_duel`, `create_rule`): one class for the schema, the decoding, the
 * validation and the state saved with the world (rule of T08). Enum values and properties are snake_case
 * on the wire (`@SerialName`). Every text the model writes has its bound here (`@MaxLen`, checked by
 * `ToolArgs`), from [MechanicsLimits].
 *
 * Their schemas are in the cached prefix of every chapter: the `@Doc`s are short, and
 * `MechanicsContractsTest` keeps the seven new tools' arguments within their share of the 14 000 characters
 * of M4 §12.1. Say the rest in the tools' descriptions, once.
 *
 * The same classes are read back from the world (`MechanicsState`), leniently there: an unknown property is
 * ignored, an unknown enum value drops that entry alone.
 */

// ── Challenges (M4 §5.1) ─────────────────────────────────────────────────────────────────────────────

/** What a challenge asks (M4 §5.2): each kind has its detection, its required fields and its winners. */
@Serializable
enum class GoalKind {
    /** Hold [ChallengeSpec.count] of [ChallengeSpec.item] at once: the inventory, every second. */
    @SerialName("obtain") OBTAIN,

    /** Be at [ChallengeSpec.place]: the position, every second. */
    @SerialName("reach") REACH,

    /** Kill [ChallengeSpec.count] creatures of [ChallengeSpec.entity], never players: the `EntityKilled` facts. */
    @SerialName("kill") KILL,

    /** Still be in when it ends: no death, no departure, no armor with [ChallengeSpec.noArmor]. */
    @SerialName("survive") SURVIVE,

    /** Say one of [ChallengeSpec.answers] in the public chat or a prayer. */
    @SerialName("answer") ANSWER,

    /** Nothing detected: the Director names the winners when it ends (`JUDGE`). */
    @SerialName("judged") JUDGED,
    ;

    val id: String get() = name.lowercase()
}

/** Where a `reach` challenge leads (M4 §5.2): at least one field; every field given must hold at once. */
@Serializable
data class PlaceSpec(
    @MaxLen(MechanicsLimits.ID) val structure: String? = null,
    @MaxLen(MechanicsLimits.ID) val biome: String? = null,
    @MaxLen(MechanicsLimits.DIMENSION) val dimension: String? = null,
    @Doc("A point (x, y, z), within radius.") val x: Int? = null,
    val y: Int? = null,
    val z: Int? = null,
    @Range(2.0, 64.0) val radius: Int = 8,
    @SerialName("y_above") val yAbove: Int? = null,
    @SerialName("y_below") val yBelow: Int? = null,
) {
    /** Whether no field says where. */
    val isEmpty: Boolean get() = structure == null && biome == null && dimension == null && x == null && y == null && z == null && yAbove == null && yBelow == null
}

/** What a reward is (M4 §5.3), with the tool the engine pays it through. */
@Serializable
enum class RewardKind {
    @SerialName("item") ITEM,
    @SerialName("effect") EFFECT,
    @SerialName("grace") GRACE,
    @SerialName("joker") JOKER,
    ;

    val id: String get() = name.lowercase()

    /** The tool the engine calls to pay it (M4 §5.3). */
    val tool: String
        get() = when (this) {
            ITEM -> "give_item"
            EFFECT -> "apply_effect"
            GRACE, JOKER -> "favor"
        }
}

/**
 * What a winner gets (M4 §5.3): paid by the engine through the real tool of its [kind], one act per player,
 * under the guards (the rationing of gifts, a beneficial effect only, a joker a day). Only the fields of its
 * [kind] are read; each passes that tool's `check` when the challenge starts. An item may be named, without
 * lore (the schema's cost, M4 §12.1): the Director tells its story himself.
 */
@Serializable
data class RewardSpec(
    val kind: RewardKind,
    @MaxLen(MechanicsLimits.ID) val item: String? = null,
    @Range(1.0, 64.0) val count: Int = 1,
    @Doc("French") @MaxLen(MechanicsLimits.ITEM_NAME) val name: String? = null,
    @Doc("beneficial") @MaxLen(MechanicsLimits.ID) val effect: String? = null,
    @Doc("effect") @Range(1.0, 900.0) val seconds: Int = 120,
    @Doc("effect") @Range(1.0, 5.0) val level: Int = 1,
    @Doc("grace") @Range(1.0, 60.0) val minutes: Int = 15,
)

/**
 * What each loser enrolled at the start gets (M4 §5.3, decision H): a forfeit, a punishment of the catalogue at
 * severity 1 or 2, paid through `punish` under every guard of the punishments. Never a latecomer.
 */
@Serializable
data class ForfeitSpec(
    val punishment: PunishmentId,
    // MechanicsLimits.PENALTY_MAX_SEVERITY
    @Range(1.0, 2.0) val severity: Int = 1,
)

/**
 * A challenge (M4 §5.1): the arguments of `start_challenge`, validated by the engine (M4-12, M4-17), then kept
 * in its state. Participants are named in [players], else everyone online and whoever joins.
 */
@Serializable
data class ChallengeSpec(
    @Doc("Boss bar name, French, short.") @MaxLen(MechanicsLimits.TITLE) val title: String,
    @Doc("Your canon: one or two factual sentences, French, third person (« Le Narrateur a défié… »).")
    @MaxLen(MechanicsLimits.STATEMENT)
    val statement: String,
    @Doc("Your exact words announcing it.") @MaxLen(MechanicsLimits.QUOTE) val quote: String? = null,
    val goal: GoalKind,
    @Doc("obtain: item id or #tag.") @MaxLen(MechanicsLimits.ID) val item: String? = null,
    @Doc("obtain: held at once; kill: kills.") @Range(1.0, 256.0) val count: Int = 1,
    @Doc("kill: creature id or #tag, never a player.") @MaxLen(MechanicsLimits.ID) val entity: String? = null,
    val place: PlaceSpec? = null,
    @Doc("answer: accepted answers (case, accents, punctuation ignored).")
    @Items(1, 8)
    @MaxLen(MechanicsLimits.ANSWER)
    val answers: List<String>? = null,
    @Doc("survive: armor eliminates.") @SerialName("no_armor") val noArmor: Boolean = false,
    @Doc("By name; left out: everyone online, and whoever joins.")
    @Items(1, 8)
    @MaxLen(MechanicsLimits.PLAYER_NAME)
    val players: List<String>? = null,
    @Range(1.0, 180.0) val minutes: Int,
    @Doc("How many win: 1 the first, N the first N, 0 all who make it.") @Range(0.0, 8.0) val winners: Int = 1,
    @Doc("Each winner's reward.") val reward: RewardSpec? = null,
    @Doc("Each loser's forfeit; never a latecomer's.") val penalty: ForfeitSpec? = null,
    @Doc("Open /bet for the first minutes.") val bets: Boolean = false,
    @Range(1.0, 10.0) val importance: Int = 5,
)

// ── Duels (M4 §5.5) ──────────────────────────────────────────────────────────────────────────────────

/** Where a duel is fought: a point in the duelists' own dimension; they stand face to face around it. */
@Serializable
data class ArenaSpec(
    val x: Int,
    val y: Int,
    val z: Int,
    @MaxLen(MechanicsLimits.DIMENSION) val dimension: String? = null,
)

/** A duel (M4 §5.5): the arguments of `propose_duel`. It starts only once both players accepted. */
@Serializable
data class DuelSpec(
    @Items(2, 2) @MaxLen(MechanicsLimits.PLAYER_NAME) val players: List<String>,
    @Doc("Boss bar name, French, short.") @MaxLen(MechanicsLimits.TITLE) val title: String,
    @Doc("Your canon: one factual sentence, French, third person.") @MaxLen(MechanicsLimits.STATEMENT) val statement: String,
    @Doc("Your exact words proposing it.") @MaxLen(MechanicsLimits.QUOTE) val quote: String? = null,
    @Doc("Once both accepted.") @Range(1.0, 15.0) val minutes: Int = 5,
    @Doc("Left out: where they stand.") val arena: ArenaSpec? = null,
    @Doc("The winner's reward.") val reward: RewardSpec? = null,
    @Doc("Open /bet.") val bets: Boolean = false,
    @Range(1.0, 10.0) val importance: Int = 5,
)

// ── Punishments (M4 §6.3) ────────────────────────────────────────────────────────────────────────────

/** The punishments of the catalogue (M4 §6.3, `punish`), each with its category and its steps (M4-13). */
@Serializable
enum class PunishmentId {
    @SerialName("tiny") TINY,
    @SerialName("moon_gravity") MOON_GRAVITY,
    @SerialName("slow_hands") SLOW_HANDS,
    @SerialName("heavy_feet") HEAVY_FEET,
    @SerialName("hunger") HUNGER,
    @SerialName("butterfingers") BUTTERFINGERS,
    @SerialName("guests") GUESTS,
    @SerialName("slime_rain") SLIME_RAIN,
    @SerialName("skyward") SKYWARD,
    @SerialName("tree_top") TREE_TOP,
    @SerialName("smite") SMITE,
    @SerialName("cage") CAGE,
    @SerialName("web") WEB,
    ;

    /** `moon_gravity`: its name in the arguments and the results. */
    val id: String get() = name.lowercase()
}

// ── Rules (M4 §8.1) ──────────────────────────────────────────────────────────────────────────────────

/** A law (public, lasting) or a plan (secret, often once). Canon kinds `law` and `plan`. */
@Serializable
enum class RuleKind {
    @SerialName("law") LAW,
    @SerialName("plan") PLAN,
    ;

    val id: String get() = name.lowercase()
}

/**
 * What sets a rule off (M4 §8.2): a fact of the bus, the position checked every second (rising edge), or a
 * creature that appears or loads (`mob_spawn`, the engine's own hook).
 */
@Serializable
enum class RuleTrigger {
    @SerialName("block_break") BLOCK_BREAK,
    @SerialName("block_place") BLOCK_PLACE,
    @SerialName("kill") KILL,
    @SerialName("damage_dealt") DAMAGE_DEALT,
    @SerialName("projectile_hit") PROJECTILE_HIT,
    @SerialName("death") DEATH,
    @SerialName("chat") CHAT,
    @SerialName("dimension_change") DIMENSION_CHANGE,
    @SerialName("structure_enter") STRUCTURE_ENTER,
    @SerialName("biome_enter") BIOME_ENTER,
    @SerialName("advancement") ADVANCEMENT,
    @SerialName("craft") CRAFT,
    @SerialName("consume") CONSUME,
    @SerialName("sleep") SLEEP,
    @SerialName("join") JOIN,
    @SerialName("position") POSITION,
    @SerialName("mob_spawn") MOB_SPAWN,
    ;

    /** `block_break`: its name in the arguments, the decisions and the situation. */
    val id: String get() = name.lowercase()
}

/** Within [radius] blocks of a point (in [dimension], the overworld when left out). */
@Serializable
data class Near(
    val x: Int,
    val y: Int,
    val z: Int,
    @MaxLen(MechanicsLimits.DIMENSION) val dimension: String? = null,
    @Range(1.0, 256.0) val radius: Int = 16,
)

/** All the given conditions must hold, then [chance] is drawn; each applies to some triggers only (M4 §8.2, §8.3). */
@Serializable
data class RuleConditions(
    @Items(1, 8) @MaxLen(MechanicsLimits.PLAYER_NAME) val players: List<String>? = null,
    @Doc("The trigger's creature (victim, shooter, killer, new one): id or #tag.") @MaxLen(MechanicsLimits.ID) val entity: String? = null,
    @Doc("id or #tag") @MaxLen(MechanicsLimits.ID) val block: String? = null,
    @Doc("id or #tag") @MaxLen(MechanicsLimits.ID) val item: String? = null,
    @Doc("block_break: a block another player placed.") val grief: Boolean? = null,
    @MaxLen(MechanicsLimits.DIMENSION) val dimension: String? = null,
    @MaxLen(MechanicsLimits.ID) val structure: String? = null,
    @MaxLen(MechanicsLimits.ID) val biome: String? = null,
    @MaxLen(MechanicsLimits.ADVANCEMENT) val advancement: String? = null,
    @SerialName("y_below") val yBelow: Int? = null,
    @SerialName("y_above") val yAbove: Int? = null,
    val near: Near? = null,
    @Doc("chat: any of these words.") @Items(1, 8) @MaxLen(MechanicsLimits.WORD) val words: List<String>? = null,
    @Doc("Odds it fires once all else holds.") @Range(0.05, 1.0) val chance: Double = 1.0,
)

/** Whom or where an effect acts (M4 §8.4): the engine fills the tool's own target with it. */
@Serializable
enum class EffectTarget {
    /** The player of the trigger. */
    @SerialName("actor") ACTOR,

    /** Its creature, or the player hurt. */
    @SerialName("subject") SUBJECT,

    /** Where it happened. */
    @SerialName("position") POSITION,

    /** Every player online. */
    @SerialName("everyone") EVERYONE,
    ;

    val id: String get() = name.lowercase()
}

/** One effect of a rule: a tool with its usual arguments, its target filled by the engine, after a delay (M4 §8.4). */
@Serializable
data class RuleEffect(
    @MaxLen(MechanicsLimits.TOOL_NAME) val tool: String,
    @Doc("Fills the tool's target: leave it out of args.") val at: EffectTarget,
    @Doc("The tool's other arguments; {actor} in a text is the player's name.") val args: JsonObject = JsonObject(emptyMap()),
    @SerialName("delay_seconds") @Range(0.0, 60.0) val delaySeconds: Int = 0,
)

/**
 * A rule (M4 §8.1): the arguments of `create_rule`, validated when it is created (M4 §8.5, M4-14), then kept in
 * its state. No expression language, no "or", no rule that makes a rule: a flat object of conditions, all
 * true, and 1 to 4 effects.
 */
@Serializable
data class RuleSpec(
    val kind: RuleKind,
    @Doc("Your canon: one or two factual sentences, French, third person.") @MaxLen(MechanicsLimits.STATEMENT) val statement: String,
    @Doc("Your exact words decreeing it (law).") @MaxLen(MechanicsLimits.QUOTE) val quote: String? = null,
    val trigger: RuleTrigger,
    @SerialName("when") val conditions: RuleConditions = RuleConditions(),
    @Items(1, MechanicsLimits.RULE_EFFECTS) val effects: List<RuleEffect>,
    @Range(5.0, 10_080.0) val minutes: Int,
    @Doc("Fires once, then done.") val once: Boolean = false,
    @Range(1.0, 10.0) val importance: Int = 5,
)

// ── Bounds ───────────────────────────────────────────────────────────────────────────────────────────

/**
 * The bounds and fixed limits of the mechanics (M4 §11, §21.1): lengths of what the model writes, budgets of
 * what the engine renders, and the engine's own limits that are not settings (`mechanics` in the
 * configuration holds the others, M4 §17). The caps that depend on the chaos level are in `Caps`.
 */
object MechanicsLimits {
    // What the model writes, in characters (code points).
    const val TITLE = 40
    const val STATEMENT = MemoryLimits.CANON_STATEMENT
    const val QUOTE = MemoryLimits.CANON_QUOTE

    /** `end_challenge`, `remove_rule`: the canon's resolution. */
    const val NOTE = MemoryLimits.RESOLUTION

    /** `notify`: the Director's own note, in the `RULE` line. */
    const val NOTIFY_NOTE = 200

    /** `punish`: the reason, kept in the player's history (an interaction's summary). */
    const val REASON = MemoryLimits.INTERACTION_SUMMARY

    /** `favor` with an offering: what it was and what the Narrator made of it (an interaction's summary). */
    const val OFFERING_NOTE = MemoryLimits.INTERACTION_SUMMARY
    const val PLAYER_NAME = 16
    const val ID = 60
    const val DIMENSION = 40
    const val ADVANCEMENT = 80
    const val TOOL_NAME = 20
    const val ANSWER = 60
    const val WORD = 30
    const val ITEM_NAME = 40

    /** `C-50`, `O-812`: a reference given as an argument. */
    const val REF = 24

    // What the engine renders, in characters.
    /** A boss bar's name (`challenge.bar`). */
    const val BAR_NAME = 80

    /** The `stakes` of a canon row, written by the code: `reward 1x golden_apple · penalty tiny (2) · bets`. */
    const val STAKES = MemoryLimits.CANON_STAKES

    /** Each line of the situation (`Challenges`, `Rules`, `Pacing`, `Offerings`), cut with `(+N more)`. */
    const val SITUATION_LINE = 400

    /** The four lines of the situation together. */
    const val SITUATION_TOTAL = 1_200

    /** A `RULE` line of `<events>` (a `notify` note and its fact). */
    const val RULE_LINE = 600

    /** A mechanic's line of `<events>` after its kind (`CHALLENGE`, `DUEL`…), stakes and bets included. */
    const val EVENT_LINE = 1_000

    /** The briefing's `<engine_state>` section (1 500 before M4). */
    const val ENGINE_STATE = 2_700

    // The engine's fixed limits (M4 §11).
    /** A challenge's penalty is a forfeit: its severity, at most. */
    const val PENALTY_MAX_SEVERITY = 2

    /** Effects of a rule, at most. */
    const val RULE_EFFECTS = 4

    /** A rule lasts a week at most. */
    const val RULE_MAX_MINUTES = 10_080

    /** One firing per rule and per actor in this many milliseconds. */
    const val RULE_ACTOR_GAP_MS = 3_000L

    /** Firings of a rule in [RULE_WINDOW_MS], at most ([MOB_SPAWN_FIRINGS_PER_MINUTE] for `mob_spawn`). */
    const val RULE_FIRINGS_PER_WINDOW = 30
    const val RULE_WINDOW_MS = 600_000L
    const val MOB_SPAWN_FIRINGS_PER_MINUTE = 200

    /** A `throttled` decision per rule in this many milliseconds, at most. */
    const val THROTTLED_DECISION_GAP_MS = 60_000L

    /** A rule's `notify` wakes the Director once in this many milliseconds; the next ones are counted in it. */
    const val NOTIFY_GAP_MS = 300_000L

    /** Consecutive failed firings that disable a rule. */
    const val RULE_FAILURES_TO_DISABLE = 5

    /** Longest delay of a rule's effect, in seconds. */
    const val MAX_EFFECT_DELAY_SECONDS = 60

    /** A trap lasts this many seconds per point of severity (10·s s). */
    const val TRAP_SECONDS_PER_SEVERITY = 10

    /** Blocks one trap may set, and blocks waiting to be taken away on the whole server. */
    const val TRAP_BLOCKS_PER_CALL = 64
    const val TRAP_BLOCKS_PENDING = 512

    /** A stake's fallback deadline: the challenge's planned end (judging included) plus this many days. */
    const val STAKE_RESCUE_DAYS = 7

    /** A stake ceded to a winner who is not online goes back to its owner after this many days. */
    const val CEDED_RESCUE_DAYS = 30

    /** The bets stay open at least this long, whatever the challenge. */
    const val MIN_BET_WINDOW_MINUTES = 1

    /** Duelists start this many blocks from the arena's point, face to face. */
    const val ARENA_DISTANCE = 5

    /**
     * After a duel, each duelist stays protected at least this many seconds, and as long as a danger lasts (M4 review,
     * finding 3); the loser of a fatal blow gets resistance V as long (a game effect, no ledger).
     */
    const val DUEL_PROTECTION_SECONDS = 10

    /** A grace lasts this long at most; a new one comes this long after the last one ended. */
    const val GRACE_MAX_MINUTES = 60
    const val GRACE_GAP_MINUTES = 60

    /** Jokers given to one player per day. */
    const val JOKERS_PER_DAY = 1

    /** A second of the engine taking longer than this is a `WARN` (M4 §19). */
    const val SLOW_SECOND_MS = 2L
}
