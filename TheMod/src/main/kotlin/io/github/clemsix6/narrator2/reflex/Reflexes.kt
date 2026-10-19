package io.github.clemsix6.narrator2.reflex

/** A player's tone towards the Narrator. The model's labels are `id`, e.g. `mocking_playful`. */
enum class Tone {
    RESPECTFUL,

    /** Teasing, playful, without real hostility. */
    MOCKING_PLAYFUL,

    /** Rude or dismissive, short of a violent insult. */
    DISRESPECTFUL,

    /** Insulting, aggressive. */
    HOSTILE,

    /** The message does not target the Narrator. */
    NEUTRAL_NO_TARGET,
    ;

    val id: String get() = name.lowercase()
}

/** What a chat message is mainly for. The model's labels are `id`, e.g. `banter_between_players`. */
enum class ChatIntent {
    QUESTION, REQUEST, OFFERING, CHALLENGE, PROVOCATION, BANTER_BETWEEN_PLAYERS, OTHER;

    val id: String get() = name.lowercase()
}

/** A line of chat: who said what. */
data class ChatLine(val player: String, val text: String)

/**
 * What the chat triage looks at.
 *
 * @property message the message to judge (public chat or prayer).
 * @property recent the chat lines just before it, oldest first.
 * @property narratorLast the Narrator's last line, if recent.
 * @property prayer the message is a prayer (`/pray`): it speaks to the Narrator whatever its words.
 */
data class ChatTriageInput(val message: ChatLine, val recent: List<ChatLine>, val narratorLast: String?, val prayer: Boolean = false)

/** Personality signals of a message, each a probability in 0..1; stored for the player sheets (M3). */
data class PersonalitySignals(
    val politeness: Double,
    val boastfulness: Double,
    val helpfulness: Double,
    val aggressiveness: Double,
    val humor: Double,
)

/**
 * The reflexes' verdict on a chat message. Probabilities are in 0..1.
 *
 * @property toNarrator probability that the message addresses the Narrator.
 * @property manipulation probability of an attempt to manipulate the Narrator: an annotation for the
 *   Director, never a reason for an automatic action.
 * @property personality null with the heuristic fallback.
 * @property source `reflex:<model>` (e.g. `reflex:claude-haiku-5-5`) or `heuristic`.
 */
data class ChatVerdict(
    val toNarrator: Double,
    val tone: Tone,
    val toneConfidence: Double,
    val manipulation: Double,
    val intent: ChatIntent,
    val personality: PersonalitySignals?,
    val source: String,
    val latencyMs: Long,
)

/**
 * The reflexes' verdict on a heartbeat.
 *
 * @property worth probability that the activity is worth waking the Director for.
 * @property hook what could be worth a word, if the classifier found something.
 */
data class HeartbeatVerdict(val worth: Double, val hook: String?, val source: String, val latencyMs: Long)

/**
 * Fast judgments that decide whether to wake the Director: a small Claude model when it is configured
 * (`reflex`), a heuristic otherwise or when the model fails. They never throw and answer within
 * `reflex.timeoutMs` plus a little.
 */
interface Reflexes {
    /** Is this public message or prayer addressed to the Narrator, in what tone, and for what. */
    suspend fun triageChat(input: ChatTriageInput): ChatVerdict

    /**
     * Is the activity of the last [windowMinutes] minutes worth waking the Director for.
     *
     * @param summary one English line per active player (`ActivityDigest`).
     * @param online names of the players online.
     */
    suspend fun heartbeat(windowMinutes: Int, summary: List<String>, online: List<String>): HeartbeatVerdict
}
