package io.github.clemsix6.narrator2.style

import com.anthropic.core.JsonValue
import com.anthropic.models.messages.JsonOutputFormat
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.memory.enumOfId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.text.Normalizer
import java.time.Instant
import java.util.Locale

/*
 * What the line review asks the reflexes' model and how its answer is read (M6 §3.3), in one file, like
 * `reflex/ReflexLabels.kt`: what a human should review and tune. What each label means is in the prompt
 * (`prompts/line_review.md`); the request only carries the line and its context, in English tags, escaped.
 */

/**
 * The labels of the line review: one request per line of the Director ([request]), answered with [SCHEMA] under the
 * prompt [PROMPT], read by [labels]. A flaw is a number in 0..1; the register, one of [ComicRegister].
 */
object LineReviewLabels {
    /** The prompt of every line review (`prompts/line_review.md`). */
    const val PROMPT = "line_review"

    // The reason comes first: written before the numbers, it is what makes them right without thinking (REFLEX-HAIKU).
    const val REASON = "reason"
    const val REGISTER = "register"
    const val DARK = "dark"
    const val CRUEL = "cruel"
    const val FLAT = "flat"
    const val OUT_OF_CHARACTER = "out_of_character"
    const val REPEATS = "repeats"
    const val CRUTCHES = "crutches"

    /** Lines of the Director given as context before the line, at most. */
    const val RECENT_LINES = 5

    /** Crutch candidates given with the line (the watch's frequent words), at most. */
    const val CANDIDATES = 8

    /** Crutch words kept for a line, at most. */
    const val MAX_CRUTCHES = 3

    /** The line is cut to this many characters (the speech tools already bound it). */
    const val LINE_MAX_CHARS = 1_000

    /** Each line of the context is cut to this many characters. */
    const val RECENT_MAX_CHARS = 300

    /** The reason is kept to this many characters. */
    const val REASON_MAX_CHARS = 300

    private val FLAWS = listOf(DARK, CRUEL, FLAT, OUT_OF_CHARACTER, REPEATS)

    /** The labels, in the order the model writes them: its reason, the register, the five flaws, the crutch words. */
    val SCHEMA: JsonOutputFormat.Schema = JsonOutputFormat.Schema.builder()
        .putAdditionalProperty("type", JsonValue.from("object"))
        .putAdditionalProperty(
            "properties",
            JsonValue.from(
                linkedMapOf<String, Any>(
                    REASON to mapOf("type" to "string"),
                    REGISTER to mapOf("type" to "string", "enum" to ComicRegister.entries.map { it.id }),
                ) + FLAWS.associateWith { mapOf("type" to "number") } +
                    (CRUTCHES to mapOf("type" to "array", "items" to mapOf("type" to "string"))),
            ),
        )
        .putAdditionalProperty("required", JsonValue.from(listOf(REASON, REGISTER) + FLAWS + CRUTCHES))
        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
        .build()

    /**
     * The request of a line review: the Director's lines just before it ([recent], oldest first, the last
     * [RECENT_LINES]), the crutch [candidates] the line holds ([inLine]), then the line, with the players it was said to
     * ([to], null when to everyone). Every text goes through [ModelText]: a player's words quoted in a line cannot
     * close a tag or start a line.
     */
    fun request(text: String, to: List<String>?, recent: List<String>, candidates: List<String>): String = buildString {
        append("Label this line of the Narrator.\n")
        val context = recent.takeLast(RECENT_LINES)
        if (context.isNotEmpty()) {
            append("<recent_lines>\n")
            context.forEach { append("« ").append(ModelText.escape(ModelText.cut(it, RECENT_MAX_CHARS))).append(" »\n") }
            append("</recent_lines>\n")
        }
        val words = inLine(candidates, text)
        if (words.isNotEmpty()) append("<frequent_words>").append(ModelText.escape(words.joinToString(" · "))).append("</frequent_words>\n")
        append("<line")
        if (!to.isNullOrEmpty()) append(" to=\"").append(ModelText.attribute(to.joinToString(", "))).append('"')
        append('>').append(ModelText.escape(ModelText.cut(text, LINE_MAX_CHARS))).append("</line>")
    }

    /**
     * The labels of the line [eventId] ([text], said to the players at [ts]) that [answer] gives; null when a label is
     * missing, a flaw is out of 0..1, or the register is unknown: a line is labeled whole or not at all. The crutch words
     * are kept only among [candidates] (in their written form), when the line holds them, [MAX_CRUTCHES] at most.
     */
    fun labels(answer: JsonObject, eventId: Long, ts: Instant, text: String, source: String, latencyMs: Int, candidates: List<String>): LineLabels? {
        val reason = string(answer[REASON]) ?: return null
        val register = string(answer[REGISTER])?.let { enumOfId<ComicRegister>(it) } ?: return null
        val flaws = FLAWS.associateWith { flaw(answer[it]) ?: return null }
        val crutches = crutches(answer[CRUTCHES], candidates.take(CANDIDATES), text) ?: return null
        return LineLabels(
            eventId = eventId,
            ts = ts,
            text = text,
            source = source,
            register = register,
            dark = flaws.getValue(DARK),
            cruel = flaws.getValue(CRUEL),
            flat = flaws.getValue(FLAT),
            outOfCharacter = flaws.getValue(OUT_OF_CHARACTER),
            repeats = flaws.getValue(REPEATS),
            crutches = crutches,
            reason = ModelText.cut(reason.trim(), REASON_MAX_CHARS),
            latencyMs = latencyMs,
        )
    }

    /**
     * The first [CANDIDATES] of [candidates] that [text] holds: the only words it can use as crutches. The review asks
     * about these alone, rather than about every frequent word of the latest lines (T1, M6-02).
     */
    fun inLine(candidates: List<String>, text: String): List<String> {
        val line = fold(text)
        return candidates.take(CANDIDATES).filter { fold(it) in line }
    }

    /**
     * The words of [value] that are [candidates] found in [text], as the candidates write them (a plural matches its
     * singular); null when it is no list of words.
     */
    private fun crutches(value: JsonElement?, candidates: List<String>, text: String): List<String>? {
        val words = (value as? JsonArray)?.map { string(it) ?: return null } ?: return null
        val held = inLine(candidates, text)
        return words.mapNotNull { word -> held.firstOrNull { same(it, word) } }.distinct().take(MAX_CRUTCHES)
    }

    /** Whether [candidate] and [word] are the same word, give or take case, accents and a plural `s`. */
    private fun same(candidate: String, word: String): Boolean {
        val a = fold(candidate)
        val b = fold(word)
        return a == b || a + "s" == b || a == b + "s"
    }

    /** A number in 0..1, give or take rounding. */
    private fun flaw(value: JsonElement?): Double? {
        val number = (value as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull ?: return null
        return if (number in -SLACK..1 + SLACK) number.coerceIn(0.0, 1.0) else null
    }

    private fun string(value: JsonElement?): String? = (value as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** [word] without case or accents: `Céleste` and `celeste` are the same candidate. */
    private fun fold(word: String): String =
        Normalizer.normalize(word, Normalizer.Form.NFD).replace(DIACRITICS, "").lowercase(Locale.ROOT).trim()

    private val DIACRITICS = Regex("\\p{M}+")

    private const val SLACK = 1e-6
}
