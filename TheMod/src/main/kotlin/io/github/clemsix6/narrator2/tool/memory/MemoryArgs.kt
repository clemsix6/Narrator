package io.github.clemsix6.narrator2.tool.memory

import io.github.clemsix6.narrator2.memory.CanonKind
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.memory.InteractionKind
import io.github.clemsix6.narrator2.memory.MemoryKind
import io.github.clemsix6.narrator2.memory.MemoryLimits
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.memory.SheetPart
import io.github.clemsix6.narrator2.tool.Doc
import io.github.clemsix6.narrator2.tool.Items
import io.github.clemsix6.narrator2.tool.MaxLen
import io.github.clemsix6.narrator2.tool.Range
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The arguments of the memory tools the Director shares with the memory jobs and the Analyst (M3 §5).
 * Bounds come from `MemoryLimits`, the store's own: the schema tells the model what the store accepts.
 */

/** Longest player name the tools take (Minecraft names have at most 16 characters). */
internal const val NAME_MAX = 16

/** Longest ref of an exchange log (`e1234`, `t88`) or of the memory (`C-45`). */
internal const val REF_MAX = 20

/** Players a write may concern (the store's limit). */
internal const val PLAYERS_MAX = 8

/** Journal refs a write may cite as its evidence (the store's limit). */
internal const val EVIDENCE_MAX = 20

/** Longest theme of an arc: the bound of `MemoryLimits.THEME`. */
internal const val THEME_MAX = 40

/**
 * What `remember` records: a canon entry ([canon]) or a memory ([memory]); exactly one is set.
 *
 * @property told the Narrator says it to the players: the Chronicler records it only with the Narrator's
 *   own words (`quote`), from the line it cites in `evidence` (M3 review, finding 1).
 */
@Serializable
enum class RememberKind(val canon: CanonKind?, val memory: MemoryKind?, val told: Boolean = false) {
    @SerialName("promise") PROMISE(CanonKind.PROMISE, null, told = true),
    @SerialName("threat") THREAT(CanonKind.THREAT, null, told = true),
    @SerialName("challenge") CHALLENGE(CanonKind.CHALLENGE, null, told = true),
    @SerialName("law") LAW(CanonKind.LAW, null, told = true),
    @SerialName("prophecy") PROPHECY(CanonKind.PROPHECY, null, told = true),
    @SerialName("nickname") NICKNAME(CanonKind.NICKNAME, null, told = true),
    @SerialName("title") TITLE(CanonKind.TITLE, null, told = true),
    @SerialName("place") PLACE(CanonKind.PLACE, null, told = true),
    @SerialName("lore") LORE(CanonKind.LORE, null),
    @SerialName("opinion") OPINION(CanonKind.OPINION, null),
    @SerialName("running_gag") RUNNING_GAG(CanonKind.RUNNING_GAG, null),
    @SerialName("arc") ARC(CanonKind.ARC, null),
    @SerialName("claim") CLAIM(CanonKind.CLAIM, null),
    @SerialName("episode") EPISODE(null, MemoryKind.EPISODE),
    @SerialName("observation") OBSERVATION(null, MemoryKind.OBSERVATION),
    ;

    /** `running_gag`, as the model writes it. */
    val id: String get() = name.lowercase()
}

@Serializable
data class RememberArgs(
    @Doc(
        "What to remember. Your commitments and decisions: promise, threat, challenge (also bets), law, prophecy, " +
            "nickname, title, place (a name you gave to a spot), lore, opinion, running_gag, arc (a story you start), " +
            "claim (any other statement you will have to stand by). Facts about players: observation (what a player " +
            "revealed or showed), episode (a moment worth remembering).",
    )
    val kind: RememberKind,
    @Doc(
        "One or two factual sentences in French, in the third person, never in your voice: " +
            "« Le Narrateur a promis à Mimosa un cadeau si elle finit son pont avant dimanche. »",
    )
    @MaxLen(MemoryLimits.CANON_STATEMENT)
    val what: String,
    @Doc(
        "Your exact words to the players, when you said it to them (the promise, the threat, the decree, the nickname). " +
            "With an exchange log: required for what was said to players, 3 words or more copied from the NARRATOR line cited in evidence.",
    )
    @MaxLen(MemoryLimits.CANON_QUOTE)
    val quote: String? = null,
    @Doc("The players it concerns, by name, online or not; leave out for everyone. A nickname or a title: exactly one.")
    @Items(1, PLAYERS_MAX)
    @MaxLen(NAME_MAX)
    val players: List<String>? = null,
    @Doc("Minutes until it is due (promise, threat, challenge, prophecy, arc; an arc you start: 7 days when left out): a DUE event will wake you then.")
    @Range(1.0, 10_080.0)
    @SerialName("due_in_minutes")
    val dueInMinutes: Int? = null,
    @Doc("Challenge or bet: what the winner gets and what the loser risks.")
    @MaxLen(MemoryLimits.CANON_STAKES)
    val stakes: String? = null,
    @Doc("Place only: the player online who stands on the spot; their position now is the place.")
    @MaxLen(NAME_MAX)
    @SerialName("at_player")
    val atPlayer: String? = null,
    @Doc("Arc only, required: its theme, a slug (vengeance_du_warden). Once the arc is closed, its theme rests 20 h of play.")
    @MaxLen(THEME_MAX)
    val theme: String? = null,
    @Doc("1 trivial … 10 unforgettable.")
    @Range(1.0, 10.0)
    val importance: Int = 5,
    @Doc("Only when you are given an exchange log: the refs it proves by (e1234, t88).")
    @Items(1, EVIDENCE_MAX)
    @MaxLen(REF_MAX)
    val evidence: List<String>? = null,
)

/**
 * How `resolve` settles a canon entry: a [CanonStatus] other than `planned`, which only an arc the storyteller's
 * programme prepares starts with.
 */
@Serializable
enum class Outcome(val status: CanonStatus) {
    @SerialName("fulfilled") FULFILLED(CanonStatus.FULFILLED),
    @SerialName("broken") BROKEN(CanonStatus.BROKEN),
    @SerialName("expired") EXPIRED(CanonStatus.EXPIRED),
    @SerialName("cancelled") CANCELLED(CanonStatus.CANCELLED),
    @SerialName("won") WON(CanonStatus.WON),
    @SerialName("lost") LOST(CanonStatus.LOST),
    @SerialName("abandoned") ABANDONED(CanonStatus.ABANDONED),
    @SerialName("active") ACTIVE(CanonStatus.ACTIVE),
    @SerialName("resolved") RESOLVED(CanonStatus.RESOLVED),
    @SerialName("retired") RETIRED(CanonStatus.RETIRED),
}

@Serializable
data class ResolveArgs(
    @Doc("The canon entry, e.g. C-45.")
    @MaxLen(REF_MAX)
    val id: String,
    @Doc(
        "promise, threat, prophecy: fulfilled, broken, expired or cancelled; challenge: won, lost (nobody won), " +
            "abandoned, expired or cancelled; arc: active (start a planned arc), resolved or abandoned; " +
            "law, nickname, title, place, lore, opinion, running_gag, claim: retired.",
    )
    val outcome: Outcome,
    @Doc("What happened, one factual sentence in French, in the third person.")
    @MaxLen(MemoryLimits.RESOLUTION)
    val note: String,
    @Doc("Challenge won: the winners, by name.")
    @Items(1, PLAYERS_MAX)
    @MaxLen(NAME_MAX)
    val winners: List<String>? = null,
)

@Serializable
data class NoteInteractionArgs(
    @Doc("The player, by name, online or not.")
    @MaxLen(NAME_MAX)
    val player: String,
    @Doc(
        "conversation, prayer, request, offering, compliment, apology, help, joke, bet, challenge_result, " +
            "provocation, insult, manipulation, cheating, grief, punishment, blessing, gift.",
    )
    val kind: InteractionKind,
    @Doc(
        "-3 … +3: how this reflects on the player in your eyes (an insult -2, a sincere apology +1, " +
            "a brave or generous act +2). For punishment, blessing and gift: how deserved it was.",
    )
    @Range(-3.0, 3.0)
    val valence: Int,
    @Doc("One factual line in French, in the third person: « a traité le Narrateur de « nul » dans le chat ».")
    @MaxLen(MemoryLimits.INTERACTION_SUMMARY)
    val summary: String,
    @Doc("The player's own words, when they matter.")
    @MaxLen(MemoryLimits.INTERACTION_QUOTE)
    val quote: String? = null,
    @Doc("Only when you are given an exchange log: the refs it proves by (e1234, t88).")
    @Items(1, EVIDENCE_MAX)
    @MaxLen(REF_MAX)
    val evidence: List<String>? = null,
)

/** What `recall` searches: the citable tables but the notes. */
@Serializable
enum class RecallKind(val ref: RefKind) {
    @SerialName("memory") MEMORY(RefKind.MEMORY),
    @SerialName("canon") CANON(RefKind.CANON),
    @SerialName("interaction") INTERACTION(RefKind.INTERACTION),
    @SerialName("chronicle") CHRONICLE(RefKind.CHRONICLE),
}

@Serializable
data class RecallArgs(
    @Doc(
        "Words to look for, in French or as game ids (lave, chambre d'épreuve, minecraft:trial_chambers). " +
            "Leave out to list the latest.",
    )
    @MaxLen(200)
    val query: String? = null,
    @Doc("A player, online or not: what concerns them comes first, and only that without query.")
    @MaxLen(NAME_MAX)
    val player: String? = null,
    @Doc("What to search; everything when left out.")
    @Items(1, 4)
    val kinds: List<RecallKind>? = null,
    @Doc("Only the last N days.")
    @Range(1.0, 3650.0)
    val days: Int? = null,
    @Doc("Also resolved, retired and archived entries.")
    @SerialName("include_closed")
    val includeClosed: Boolean = false,
    @Doc("Results at most.")
    @Range(1.0, 20.0)
    val limit: Int = 8,
)

@Serializable
data class PlayerProfileArgs(
    @Doc("The player, online or not.")
    @MaxLen(NAME_MAX)
    val player: String,
    @Doc("Parts of the sheet: identity, recap, personality, stats, history, memories; all when left out.")
    @Items(1, 6)
    val parts: List<SheetPart>? = null,
)
