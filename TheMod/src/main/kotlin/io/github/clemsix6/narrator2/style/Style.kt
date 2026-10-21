package io.github.clemsix6.narrator2.style

import io.github.clemsix6.narrator2.trace.LineLabelsRecord
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/*
 * The contract of the Narrator's style (design M6 §3, §12.1): the registers, what the line review says about a line,
 * and what the code watches of the Director's latest lines. The line review (`LineReviewer`) feeds the watch, which
 * `say`, the situation, `inspire` and `/narrator style` read.
 */

/**
 * A comic register of the Narrator's voice, as the line review labels it (M6 §3.3, §12.1). The English ids live here and
 * nowhere else in the code; their French names only in the voice's line of `director.md` (« Des registres qui
 * tournent »). Renaming a register is that line; replacing one is that line and this enum, in the same commit. The
 * review's schema is generated from this enum: `line_review.md` never copies the list.
 */
@Serializable
enum class ComicRegister {
    @SerialName("nature_documentary") NATURE_DOCUMENTARY,
    @SerialName("sports_commentary") SPORTS_COMMENTARY,
    @SerialName("divine_bureaucracy") DIVINE_BUREAUCRACY,
    @SerialName("courtroom") COURTROOM,
    @SerialName("weather_report") WEATHER_REPORT,
    @SerialName("silly_prophecy") SILLY_PROPHECY,
    @SerialName("food_critic") FOOD_CRITIC,
    @SerialName("game_show") GAME_SHOW,
    @SerialName("fairy_tale") FAIRY_TALE,
    @SerialName("customer_service") CUSTOMER_SERVICE,
    @SerialName("classified_ads") CLASSIFIED_ADS,
    @SerialName("movie_trailer") MOVIE_TRAILER,
    @SerialName("tour_guide") TOUR_GUIDE,
    @SerialName("horoscope") HOROSCOPE,
    @SerialName("user_manual") USER_MANUAL,
    @SerialName("auction") AUCTION,

    /** A direct taunt or answer, without a frame: a label only, never a register of the voice. */
    @SerialName("plain") PLAIN,

    /** Any other frame: a label only, never a register of the voice. */
    @SerialName("other") OTHER,
    ;

    /** `divine_bureaucracy`: the value stored (`line_labels.register`, `ideas.register`) and shown. */
    val id: String get() = name.lowercase()

    companion object {
        /**
         * The registers of the voice, in the order of `director.md`: every register but [PLAIN] and [OTHER]. What
         * rotates (the least used, `inspire`'s register) and what the diversity's entropy counts.
         */
        val VOICE: List<ComicRegister> = entries - PLAIN - OTHER
    }
}

/**
 * What the line review said about one line of the Director (M6 §3.3): its [register] and its flaws, each from 0 to 1;
 * [crutches], the words of the line the review took for crutches, among the candidates it was given, 3 at most. Given
 * to [StyleWatch.labeled], and written to `line_labels` as its [record].
 *
 * @property eventId the line's id: its `chat` row, the id of its bus envelope.
 * @property text the line as it was said: what a note of tone quotes.
 * @property source `reflex:<model>`, the model that answered.
 * @property reason the reviewer's one sentence, written before its numbers. It quotes the Narrator, never a player; it is
 *   kept in `line_labels` and never logged.
 */
data class LineLabels(
    val eventId: Long,
    val ts: Instant,
    val text: String,
    val source: String,
    val register: ComicRegister,
    val dark: Double,
    val cruel: Double,
    val flat: Double,
    val outOfCharacter: Double,
    val repeats: Double,
    val crutches: List<String>,
    val reason: String?,
    val latencyMs: Int,
) {
    /** The `line_labels` row of these labels. */
    fun record(): LineLabelsRecord =
        LineLabelsRecord(eventId, ts, source, register.id, dark, cruel, flat, outOfCharacter, repeats, crutches, reason, latencyMs)
}

/**
 * An opener at rest (M6 §3.4): two normalized tokens (names as `<nom>`, numbers as `<n>`) that open [count] of the
 * Director's latest lines of 25 characters or more, 4 at least. [opener] is its latest written form (« Vingt
 * secondes »); [examples], other forms it took, the latest first.
 */
data class RestingOpener(val opener: String, val count: Int, val examples: List<String>)

/**
 * What the Director served too often lately, each list the most served first (M6 §3.4): what the `Style:` line names,
 * what `say` cites when it refuses an opener ([openers]), what `inspire` leaves out ([crutches]).
 *
 * @property openers the openers at rest.
 * @property phrases the phrases at rest (3 to 5 normalized tokens in 3 lines at least), as last written.
 * @property crutches the crutch words at rest: marked by the review in 3 of the latest 30 labeled lines at least, and
 *   at rest while they stay in 2.
 */
data class AtRest(val openers: List<RestingOpener>, val phrases: List<String>, val crutches: List<String>) {
    companion object {
        val NONE = AtRest(emptyList(), emptyList(), emptyList())
    }
}

/**
 * The diversity of the Director's latest lines (M6 §3.7), measured on demand (`StyleReport.measures`).
 *
 * @property lines the lines measured: the latest 50.
 * @property openersDistinct distinct normalized openers over [lines]; 0 without lines.
 * @property topRegister the register of the voice ([ComicRegister.VOICE]) of the most labeled lines; null when no labeled
 *   line has one: none labeled, or all `plain` or `other`, which are no frames ([plainShare] tells).
 * @property topRegisterShare its share of the labeled lines whose register is of the voice (neither `plain` nor `other`),
 *   0 to 1: how much one frame holds the rotation.
 * @property registerEntropy the registers' entropy over log(min(K, n)), 0 to 1, K the registers of the voice.
 * @property crutches each crutch word and the lines that hold it, the most frequent first.
 * @property tics the tics found (openers, phrases, ellipses).
 * @property plainShare the share of the labeled lines labeled `plain` (a direct taunt or answer, without a frame), 0 to
 *   1; null when none of [lines] is labeled.
 */
data class Diversity(
    val lines: Int,
    val openersDistinct: Double,
    val topRegister: ComicRegister?,
    val topRegisterShare: Double,
    val registerEntropy: Double,
    val crutches: List<Pair<String, Int>>,
    val tics: Int,
    val plainShare: Double? = null,
) {
    companion object {
        /** Nothing measured: no line yet. */
        val NONE = Diversity(0, 0.0, null, 0.0, 0.0, emptyList(), 0)
    }
}

/**
 * The Narrator's style as the code watches it (M6 §3.4): the Director's latest 200 lines and their labels, in memory.
 * Fed by the bus (`NarratorSpoke`) and the line review ([labeled]); read by `say` ([restingOpener]), the situation
 * ([situationLine]), the review ([crutchCandidates], [recentLines]), `inspire` ([atRest], [leastUsedRegisters],
 * [lastRegisters]) and `/narrator style` ([diversity]). Any thread; never waits, never reads the database.
 */
interface StyleWatch {
    /** The review labeled a line: its register, flaws and crutch words join the watch. */
    fun labeled(labels: LineLabels)

    /**
     * The 8 most frequent content words of the latest 50 lines (5 letters at least, neither a player's name, the
     * Narrator's, a number nor a stop word), the most frequent first: the review's `frequent_words`.
     */
    fun crutchCandidates(): List<String>

    /** The [count] lines of the Director said just before the line [beforeLineId], oldest first: the review's `recent_lines`. */
    fun recentLines(beforeLineId: Long, count: Int): List<String>

    /** The opener at rest [text] starts with; null when it starts freely, or holds fewer than 25 characters. */
    fun restingOpener(text: String): RestingOpener?

    /** What is at rest now. */
    fun atRest(): AtRest

    /** The `Style:` line of the situation at [now] (epoch milliseconds), 400 characters at most; null with nothing to say. */
    fun situationLine(now: Long): String?

    /**
     * The [count] registers of the voice ([ComicRegister.VOICE]) least used in the latest 50 labeled lines, the least
     * used first; ties go in the enum's order.
     */
    fun leastUsedRegisters(count: Int): List<ComicRegister>

    /** The registers of the latest [count] labeled lines, the latest first. */
    fun lastRegisters(count: Int): List<ComicRegister>

    /** The diversity of the latest 50 lines. */
    fun diversity(): Diversity
}

/** The style watch when it does not run (no Journal, or not wired): it says nothing, refuses nothing, keeps nothing. */
object DisabledStyle : StyleWatch {
    override fun labeled(labels: LineLabels) = Unit

    override fun crutchCandidates(): List<String> = emptyList()

    override fun recentLines(beforeLineId: Long, count: Int): List<String> = emptyList()

    override fun restingOpener(text: String): RestingOpener? = null

    override fun atRest(): AtRest = AtRest.NONE

    override fun situationLine(now: Long): String? = null

    override fun leastUsedRegisters(count: Int): List<ComicRegister> = emptyList()

    override fun lastRegisters(count: Int): List<ComicRegister> = emptyList()

    override fun diversity(): Diversity = Diversity.NONE
}
