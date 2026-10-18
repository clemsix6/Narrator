package io.github.clemsix6.narrator2.memory

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.sql.Connection
import java.text.Normalizer
import java.time.Instant
import java.util.UUID

/**
 * Quotes checked against the Journal (M3 §4.2, decision 8): what the Chronicler records as someone's
 * words must be words really said. A commitment of the Narrator copies at least [MIN_WORDS] words (or
 * [MIN_CHARS] characters) of one of its lines **cited in the evidence**: a line of the `narrator` or
 * `operator` chat channels, or the text of a successful `say`, `give_book` or `show_title` call. An
 * interaction cites a public message or a prayer of that player, said in the job's window. The chat of
 * the players can thus never make up a promise of the Narrator.
 */
internal object Quotes {
    /** The speech tools whose arguments are the Narrator's words. */
    private val SPEECH_TOOLS = listOf("say", "give_book", "show_title")

    /** Arguments of the speech tools that are not words said (the players addressed). */
    private val NOT_SAID = setOf("to")

    /** A quote of the Narrator proves something from this many words, or [MIN_CHARS] characters once normalized. */
    const val MIN_WORDS = 3
    const val MIN_CHARS = 15

    private val QUOTE_MARKS = Regex("[«»“”„\"‹›]")
    private val SPACES = Regex("[\\s\\u00A0\\u202F]+")
    private val MARKS = Regex("\\p{M}+")
    private val EDGES = Regex("^[\\s.,;:!?…\\-–—]+|[\\s.,;:!?…\\-–—]+$")

    /** [text] for comparison: lowercase, accents folded, apostrophes straight, quote marks removed, spaces collapsed, no punctuation at the edges. */
    fun normalize(text: String): String {
        val folded = Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "").lowercase()
            .replace('’', '\'').replace('‘', '\'').replace('ʼ', '\'')
        return folded.replace(QUOTE_MARKS, " ").replace(SPACES, " ").replace(EDGES, "")
    }

    /** Whether [quote] is long enough to prove what the Narrator said: [MIN_WORDS] words or [MIN_CHARS] characters, normalized. */
    fun substantial(quote: String): Boolean {
        val normalized = normalize(quote)
        return normalized.length >= MIN_CHARS || normalized.split(' ').count { word -> word.any { it.isLetterOrDigit() } } >= MIN_WORDS
    }

    /** The Journal window of the job [runId] (`memory_runs`): from (or the beginning) to (or [now]); null when there is no such job. */
    fun window(connection: Connection, runId: Long, now: Instant): Pair<Instant, Instant>? = connection.query(
        "SELECT window_from, window_to FROM memory_runs WHERE run_id = ?",
        runId,
    ) { (instantOrNull(1) ?: Instant.EPOCH) to (instantOrNull(2) ?: now) }.singleOrNull()

    /**
     * The Narrator's lines among the evidence: the [events] that are lines of the `narrator` or `operator`
     * chat channels, and the words of the [tools] that are successful speech calls. A player's line, a
     * private message, a fact or another tool is none.
     */
    fun narratorLines(connection: Connection, events: List<Long>, tools: List<Long>): List<String> {
        val lines = if (events.isEmpty()) {
            emptyList()
        } else {
            connection.query(
                "SELECT text FROM chat WHERE list_contains(?, id) AND channel IN ('narrator', 'operator')",
                longArray(events),
            ) { getString(1) }.filterNotNull()
        }
        val calls = if (tools.isEmpty()) {
            emptyList()
        } else {
            connection.query(
                "SELECT args::VARCHAR FROM tool_calls WHERE list_contains(?, id) AND status = 'ok' AND list_contains(?, tool)",
                longArray(tools), stringArray(SPEECH_TOOLS),
            ) { getString(1) }.filterNotNull().flatMap { said(Json.parseToJsonElement(it)) }
        }
        return lines + calls
    }

    /** Whether [quote] is in one of [lines], once both are normalized. */
    fun saidIn(quote: String, lines: List<String>): Boolean {
        val wanted = normalize(quote)
        return wanted.isNotEmpty() && lines.any { normalize(it).contains(wanted) }
    }

    /** Whether [player] wrote [quote] between [from] and [to], in the public chat or a prayer (never a private message). */
    fun saidByPlayer(connection: Connection, player: UUID, quote: String, from: Instant, to: Instant): Boolean = saidIn(
        quote,
        connection.query(
            "SELECT text FROM chat WHERE channel IN ('public', 'pray') AND player_uuid = ? AND ts BETWEEN ? AND ?",
            player, from, to,
        ) { getString(1) }.filterNotNull(),
    )

    /** The strings of a speech tool's arguments, but the players addressed. */
    private fun said(element: JsonElement, key: String? = null): List<String> = when (element) {
        is JsonPrimitive -> if (element.isString && key !in NOT_SAID) listOf(element.content) else emptyList()
        is JsonArray -> element.flatMap { said(it, key) }
        is JsonObject -> element.flatMap { (name, value) -> said(value, name) }
    }
}
