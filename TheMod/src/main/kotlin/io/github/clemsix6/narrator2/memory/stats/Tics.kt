package io.github.clemsix6.narrator2.memory.stats

import io.github.clemsix6.narrator2.tool.ToolText
import java.sql.Connection
import java.time.Instant
import kotlin.math.roundToInt

/**
 * The Narrator's verbal tics (M3 §6.4), found by the code in its latest [LINES] lines (`chat` channel
 * `narrator`), for the briefing's `<notes>` and the Dreamer:
 *
 * - **openers**: the first three words of a line, seen in [OPENER_MIN] lines at least;
 * - **phrases**: runs of 3 to 5 words that neither start nor end with a stop word, in [PHRASE_MIN] distinct
 *   lines at least (a phrase inside a longer one found as often is left out);
 * - **ellipsis**: « … » ending a sentence in more than [ELLIPSIS_SHARE] of the lines.
 *
 * Words are compared in lower case; a tic is shown as written in its latest line. At most [MAX] tics, the
 * most frequent first: `« Ministère de la » ×7 (last 2h ago)`.
 */
object Tics {
    const val LINES = 200
    const val OPENER_MIN = 4
    const val PHRASE_MIN = 3
    const val ELLIPSIS_SHARE = 0.4
    const val MAX = 8

    /** A line of the Narrator. */
    data class Line(val text: String, val ts: Instant)

    /** A tic: [text] as written last, in [count] lines, the latest at [last]; [share] of the lines for the ellipsis. */
    data class Tic(val text: String, val count: Int, val last: Instant, val share: Double? = null)

    /** The Narrator's latest [LINES] lines, oldest first, on a read connection. */
    fun lines(connection: Connection): List<Line> = connection.createStatement().use { statement ->
        statement.executeQuery(
            "SELECT text, epoch_ms(ts) FROM (SELECT text, ts, id FROM chat WHERE channel = 'narrator' ORDER BY ts DESC, id DESC LIMIT $LINES) ORDER BY ts, id",
        ).use { rows ->
            buildList { while (rows.next()) add(Line(rows.getString(1), Instant.ofEpochMilli(rows.getLong(2)))) }
        }
    }

    /** The tics of [lines] (oldest first), the most frequent first, [MAX] at most. */
    fun find(lines: List<Line>): List<Tic> {
        val tokenized = lines.map { it to words(it.text) }
        val openers = counted(tokenized.mapNotNull { (line, words) -> if (words.size >= 3) line to listOf(words.subList(0, 3)) else null })
            .filter { it.count >= OPENER_MIN }
        val openerTexts = openers.map { it.text.lowercase() }.toSet()
        val phrases = counted(tokenized.map { (line, words) -> line to phrases(words) })
            .filter { it.count >= PHRASE_MIN && it.text.lowercase() !in openerTexts }
            .sortedWith(compareByDescending<Tic> { it.text.split(' ').size }.thenByDescending { it.count })
        val kept = mutableListOf<Tic>()
        for (phrase in phrases) {
            val inside = kept.any { longer -> " ${longer.text.lowercase()} ".contains(" ${phrase.text.lowercase()} ") && longer.count >= phrase.count }
            if (!inside) kept += phrase
        }
        return (openers + kept + listOfNotNull(ellipsis(lines)))
            .sortedWith(compareByDescending<Tic> { it.count }.thenByDescending { it.last })
            .take(MAX)
    }

    /** [tics] as lines for the model: `« Ministère de la » ×7 (last 2h ago)`. */
    fun render(tics: List<Tic>, now: Instant): List<String> = tics.map { tic ->
        val ago = ToolText.duration(now.toEpochMilli() - tic.last.toEpochMilli())
        val share = tic.share?.let { "${(it * 100).roundToInt()}% of lines, " }.orEmpty()
        "« ${tic.text} » ×${tic.count} (${share}last $ago ago)"
    }

    /** Words as written: letters and digits, with their elisions (`t'es`, `aujourd'hui`). */
    private val WORD = Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)*")

    /** « … » or « ... » at the end of a sentence. */
    private val ELLIPSIS = Regex("(…|\\.\\.\\.)(?=\\s|$|[»\"”)])")

    /** French words too common to make a tic on their own (articles, pronouns, prepositions, auxiliaries). */
    private val STOP_WORDS: Set<String> = setOf(
        "le", "la", "les", "l", "un", "une", "des", "du", "de", "d", "au", "aux", "à", "a", "et", "ou", "en", "dans", "sur",
        "sous", "par", "pour", "avec", "sans", "que", "qu", "qui", "quoi", "ce", "cet", "cette", "ces", "se", "sa", "son",
        "ses", "ta", "ton", "tes", "ma", "mon", "mes", "notre", "nos", "votre", "vos", "leur", "leurs", "je", "j", "tu",
        "il", "elle", "on", "nous", "vous", "ils", "elles", "me", "m", "te", "t", "lui", "y", "ne", "n", "pas", "plus",
        "est", "es", "suis", "sont", "été", "être", "ai", "as", "ont", "avez", "avons", "c", "s", "si", "mais", "donc",
        "car", "ni", "comme",
    )

    private fun words(text: String): List<String> = WORD.findAll(text).map { it.value }.toList()

    private fun isStop(word: String) = word.lowercase() in STOP_WORDS

    /** The runs of 3 to 5 words of a line that neither start nor end with a stop word. */
    private fun phrases(words: List<String>): List<List<String>> = buildList {
        for (size in 3..5) {
            for (start in 0..words.size - size) {
                val run = words.subList(start, start + size)
                if (!isStop(run.first()) && !isStop(run.last())) add(run)
            }
        }
    }

    /** Counts each run (case-insensitive) once per line, keeping its form and time in the latest line. */
    private fun counted(runs: List<Pair<Line, List<List<String>>>>): List<Tic> {
        val byKey = LinkedHashMap<String, Tic>()
        for ((line, lineRuns) in runs) {
            for (run in lineRuns.distinctBy { it.joinToString(" ").lowercase() }) {
                val text = run.joinToString(" ")
                val key = text.lowercase()
                val seen = byKey[key]
                byKey[key] = Tic(text, (seen?.count ?: 0) + 1, line.ts)
            }
        }
        return byKey.values.toList()
    }

    private fun ellipsis(lines: List<Line>): Tic? {
        val ending = lines.filter { ELLIPSIS.containsMatchIn(it.text) }
        if (lines.isEmpty() || ending.size <= lines.size * ELLIPSIS_SHARE) return null
        return Tic("…", ending.size, ending.last().ts, ending.size.toDouble() / lines.size)
    }
}
