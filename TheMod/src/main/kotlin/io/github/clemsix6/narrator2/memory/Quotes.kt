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
 * words must be words really said in the window of its job. A commitment of the Narrator cites one of its
 * lines (the `narrator` and `operator` chat channels, or the text of a `say`, `give_book` or `show_title`
 * call); an interaction cites a public message or a prayer of that player. The chat of the players can
 * thus never make up a promise of the Narrator.
 */
internal object Quotes {
    /** The speech tools whose arguments are the Narrator's words. */
    private val SPEECH_TOOLS = listOf("say", "give_book", "show_title")

    /** Arguments of the speech tools that are not words said (the players addressed). */
    private val NOT_SAID = setOf("to")

    private val QUOTE_MARKS = Regex("[«»“”„\"‹›]")
    private val SPACES = Regex("[\\s\\u00A0\\u202F]+")
    private val MARKS = Regex("\\p{M}+")
    private val EDGES = Regex("^[\\s.,;:!?…\\-–—]+|[\\s.,;:!?…\\-–—]+$")

    /** [text] for comparison: lowercase, accents folded, quote marks removed, spaces collapsed, no punctuation at the edges. */
    fun normalize(text: String): String {
        val folded = Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "").lowercase()
            .replace('’', '\'').replace('‘', '\'')
        return folded.replace(QUOTE_MARKS, " ").replace(SPACES, " ").replace(EDGES, "")
    }

    /** The Journal window of the job [runId] (`memory_runs`): from (or the beginning) to (or [now]); null when there is no such job. */
    fun window(connection: Connection, runId: Long, now: Instant): Pair<Instant, Instant>? = connection.query(
        "SELECT window_from, window_to FROM memory_runs WHERE run_id = ?",
        runId,
    ) { (instantOrNull(1) ?: Instant.EPOCH) to (instantOrNull(2) ?: now) }.singleOrNull()

    /** Whether the Narrator said [quote] between [from] and [to], in the chat or through a speech tool. */
    fun saidByNarrator(connection: Connection, quote: String, from: Instant, to: Instant): Boolean {
        val wanted = normalize(quote)
        if (wanted.isEmpty()) return false
        val lines = connection.query(
            "SELECT text FROM chat WHERE channel IN ('narrator', 'operator') AND ts BETWEEN ? AND ?",
            from, to,
        ) { getString(1) }
        if (lines.any { normalize(it).contains(wanted) }) return true
        val calls = connection.query(
            "SELECT args::VARCHAR FROM tool_calls WHERE status = 'ok' AND list_contains(?, tool) AND ts BETWEEN ? AND ?",
            stringArray(SPEECH_TOOLS), from, to,
        ) { getString(1) }
        return calls.filterNotNull().any { args -> said(Json.parseToJsonElement(args)).any { normalize(it).contains(wanted) } }
    }

    /** Whether [player] wrote [quote] between [from] and [to], in the public chat or a prayer (never a private message). */
    fun saidByPlayer(connection: Connection, player: UUID, quote: String, from: Instant, to: Instant): Boolean {
        val wanted = normalize(quote)
        if (wanted.isEmpty()) return false
        return connection.query(
            "SELECT text FROM chat WHERE channel IN ('public', 'pray') AND player_uuid = ? AND ts BETWEEN ? AND ?",
            player, from, to,
        ) { getString(1) }.any { normalize(it).contains(wanted) }
    }

    /** The strings of a speech tool's arguments, but the players addressed. */
    private fun said(element: JsonElement, key: String? = null): List<String> = when (element) {
        is JsonPrimitive -> if (element.isString && key !in NOT_SAID) listOf(element.content) else emptyList()
        is JsonArray -> element.flatMap { said(it, key) }
        is JsonObject -> element.flatMap { (name, value) -> said(value, name) }
    }
}
