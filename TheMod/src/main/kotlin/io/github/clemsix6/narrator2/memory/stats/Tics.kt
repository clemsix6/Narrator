package io.github.clemsix6.narrator2.memory.stats

import io.github.clemsix6.narrator2.memory.query
import io.github.clemsix6.narrator2.tool.ToolText
import java.sql.Connection
import java.time.Instant
import kotlin.math.roundToInt

/**
 * The Narrator's verbal tics (M3 §6.4, normalized by M6 §3.4), found by the code in the Director's latest [LINES] lines
 * (`chat` channel `narrator` with a turn: neither an operator's line nor the text of a rule or a module, which repeat by
 * design), for the briefing's `<notes>`, the Dreamer and the watch of the style (`StyleWatchImpl`).
 *
 * Each line is first **normalized** ([tokens]): its words in lower case, the names of the players and the canon's
 * nicknames ([Names]) as [NAME] (a run of names, « Gaspard, Firmin » or « Pistache et Opaline », is one [NAME]), the
 * numbers in digits or in letters as [NUMBER] (« dix-sept » is one [NUMBER]). « Onze secondes » and « Vingt secondes »
 * are then one construction, `<n> secondes`; « Bonsoir, Mimosa » and « Bonsoir, Lisette, Gaspard » one, `bonsoir <nom>`.
 *
 * - **openers**: the first [OPENER_TOKENS] tokens of a line that [opens] (said with `say`, [OPENER_MIN_CHARS] characters
 *   or more: a title, a line above the hotbar or a short aside never opens), seen in [OPENER_MIN] lines at least; an
 *   opener without a content word (`<nom> vous`, `<n> <nom>`) is never a tic;
 * - **phrases**: runs of 3 to 5 tokens that neither start nor end with a stop word or a placeholder, in [PHRASE_MIN]
 *   distinct lines at least (a phrase inside a longer one found as often is left out);
 * - **ellipsis**: « … » ending a sentence in more than [ELLIPSIS_SHARE] of the lines.
 *
 * Of tics nested in one another (an opener inside a phrase, a phrase inside a longer one), one is shown
 * (M3-POST): the one in the most lines, the longest at equal count. « Ministère de l'Aviation Divine » ×5
 * leaves out « Ministère de » ×5 and « Ministère de l'Aviation Divine guichet » ×3: avoiding it avoids them too.
 *
 * A tic is shown as written in its latest line, from its first word to its last (« Vingt secondes », « Bonsoir,
 * mortels »). At most [MAX] tics, the most frequent first: `« Ministère de » ×7 (last 2h ago)`.
 */
object Tics {
    const val LINES = 200
    const val OPENER_TOKENS = 2
    const val OPENER_MIN = 4
    const val OPENER_MIN_CHARS = 25
    const val PHRASE_MIN = 3
    const val ELLIPSIS_SHARE = 0.4
    const val MAX = 8

    /** What a name of a player or a nickname becomes in a normalized line. */
    const val NAME = "<nom>"

    /** What a number, in digits or in letters, becomes in a normalized line. */
    const val NUMBER = "<n>"

    /** A line of the Narrator. */
    data class Line(val text: String, val ts: Instant)

    /** A tic: [text] as written last, in [count] lines, the latest at [last]; [share] of the lines for the ellipsis. */
    data class Tic(val text: String, val count: Int, val last: Instant, val share: Double? = null)

    /**
     * A word of a line once normalized: [key] is the word in lower case (`’` as `'`), or [NAME], or [NUMBER]; [start] and
     * [end] are its place in the line's text, so that a run of tokens can be shown as written.
     */
    data class Token(val key: String, val start: Int, val end: Int) {
        /** A content word: neither a stop word nor a placeholder. An opener needs one to be a tic. */
        val isWord: Boolean get() = key != NAME && key != NUMBER && !isStop(key)
    }

    /** The first tokens of a line that [opens]: [key] normalized (`<n> secondes`), [written] as said (« Vingt secondes »). */
    data class Opener(val key: String, val written: String, val hasWord: Boolean)

    /**
     * A line read for its tics: its [text] as recorded, its [tokens] (the kind of a title or a line above the hotbar left
     * out), and whether it [opens].
     */
    class Said(val text: String, val ts: Instant, val tokens: List<Token>) {
        /** Said with `say` and long enough to count for the openers ([OPENER_MIN_CHARS]). */
        val opens: Boolean = opens(text)

        /** Its first [OPENER_TOKENS] tokens; null when it does not open, or has fewer tokens. */
        val opener: Opener? = if (!opens || tokens.size < OPENER_TOKENS) {
            null
        } else {
            val first = tokens.subList(0, OPENER_TOKENS)
            Opener(first.joinToString(" ") { it.key }, text.substring(first.first().start, first.last().end), first.any { it.isWord })
        }
    }

    /**
     * A construction counted in lines (an opener or a phrase): [key] normalized, [forms] as written (distinct, the
     * latest first), in [count] lines, the latest at [last].
     */
    data class Construction(val key: String, val forms: List<String>, val count: Int, val last: Instant) {
        /** As written in its latest line. */
        val text: String get() = forms.first()

        /** Its number of tokens. */
        val size: Int get() = key.split(' ').size
    }

    /**
     * The names a line may hold: the players' names and the canon's nicknames, each as its lower-case words. A name made
     * only of stop words or numbers (a player called « Les ») is left out: it would hide every « les ».
     */
    class Names private constructor(private val byFirst: Map<String, List<List<String>>>) {
        val isEmpty: Boolean get() = byFirst.isEmpty()

        /** How many of [words] (lower case) from [from] the longest name there spans; 0 when none starts there. */
        fun match(words: List<String>, from: Int): Int = byFirst[words[from]]
            ?.firstOrNull { name -> from + name.size <= words.size && name.indices.all { words[from + it] == name[it] } }
            ?.size ?: 0

        companion object {
            val NONE = Names(emptyMap())

            fun of(names: Collection<String>): Names {
                val runs = names.map { name -> WORD.findAll(name).map { key(it.value) }.toList() }
                    .filter { words -> words.any { !isStop(it) && !isNumber(it) } }
                    .distinct()
                return Names(runs.groupBy { it.first() }.mapValues { (_, same) -> same.sortedByDescending { it.size } })
            }
        }
    }

    // ── Reading the Journal ─────────────────────────────────────────────────────────────────────────

    /** The Director's latest [LINES] lines, oldest first, on a read connection, within the stats' time limit. */
    fun lines(connection: Connection): List<Line> = connection.query(
        "SELECT text, epoch_ms(ts) FROM (SELECT text, ts, id FROM chat WHERE channel = 'narrator' AND turn_id IS NOT NULL AND operator IS NULL " +
            "ORDER BY ts DESC, id DESC LIMIT $LINES) ORDER BY ts, id",
        timeoutSeconds = SheetStats.QUERY_TIMEOUT_SECONDS,
    ) { Line(getString(1), Instant.ofEpochMilli(getLong(2))) }

    /**
     * The names a line may hold: every name a player had (`player_names`) and the name each active nickname of the canon
     * gives (between « » in its statement), within the stats' time limit.
     */
    fun names(connection: Connection): Set<String> {
        val players = connection.query("SELECT DISTINCT name FROM player_names", timeoutSeconds = SheetStats.QUERY_TIMEOUT_SECONDS) { getString(1) }
        val nicknames = connection.query(
            "SELECT statement FROM canon WHERE kind = 'nickname' AND status = 'active' AND NOT archived",
            timeoutSeconds = SheetStats.QUERY_TIMEOUT_SECONDS,
        ) { getString(1) }.mapNotNull { NICKNAME.find(it)?.groupValues?.get(1)?.trim() }
        return (players + nicknames).filter { it.isNotBlank() }.toSet()
    }

    /** The tics of the Director's latest lines, their names normalized: what the briefing and the Dreamer show. */
    fun read(connection: Connection): List<Tic> = find(lines(connection), Names.of(names(connection)))

    // ── Finding ─────────────────────────────────────────────────────────────────────────────────────

    /** The tics of [lines] (oldest first), with [names] normalized, the most frequent first, [MAX] at most. */
    fun find(lines: List<Line>, names: Names = Names.NONE): List<Tic> = tics(lines.map { said(it.text, it.ts, names) })

    /** The tics of [said] (oldest first), the most frequent first, [MAX] at most. */
    fun tics(said: List<Said>): List<Tic> {
        val openers = openers(said).filter { it.count >= OPENER_MIN }
        val shown = mutableListOf<Construction>()
        for (tic in (openers + phrases(said)).sortedWith(compareByDescending<Construction> { it.count }.thenByDescending { it.size })) {
            if (shown.none { inside(tic, it) || inside(it, tic) }) shown += tic
        }
        return (shown.map { Tic(it.text, it.count, it.last) } + listOfNotNull(ellipsis(said)))
            .sortedWith(compareByDescending<Tic> { it.count }.thenByDescending { it.last })
            .take(MAX)
    }

    /** [text] said at [ts], read with [names]. */
    fun said(text: String, ts: Instant, names: Names): Said = Said(text, ts, tokens(body(text), names))

    /**
     * The openers of [said] (oldest first) that have a content word, in any number of lines, the most frequent first
     * (the latest at equal count).
     */
    fun openers(said: List<Said>): List<Construction> =
        counted(said.map { line -> line.ts to listOfNotNull(line.opener?.takeIf { it.hasWord }?.let { it.key to it.written }) })
            .sortedWith(compareByDescending<Construction> { it.count }.thenByDescending { it.last })

    /** The phrases of [said] (oldest first) in [PHRASE_MIN] lines at least, without those inside a longer one found as often. */
    fun phrases(said: List<Said>): List<Construction> {
        val phrases = counted(said.map { line -> line.ts to phrases(line) })
            .filter { it.count >= PHRASE_MIN }
            .sortedWith(compareByDescending<Construction> { it.size }.thenByDescending { it.count })
        val kept = mutableListOf<Construction>()
        for (phrase in phrases) {
            if (kept.none { longer -> inside(phrase, longer) && longer.count >= phrase.count }) kept += phrase
        }
        return kept.sortedWith(compareByDescending<Construction> { it.count }.thenByDescending { it.last })
    }

    /** Whether the tokens of [tic] are a run of the tokens of [other]. */
    private fun inside(tic: Construction, other: Construction): Boolean = " ${other.key} ".contains(" ${tic.key} ")

    /** [tics] as lines for the model: `« Ministère de » ×7 (last 2h ago)`. */
    fun render(tics: List<Tic>, now: Instant): List<String> = tics.map { tic ->
        val ago = ToolText.duration(now.toEpochMilli() - tic.last.toEpochMilli())
        val share = tic.share?.let { "${(it * 100).roundToInt()}% of lines, " }.orEmpty()
        "« ${tic.text} » ×${tic.count} (${share}last $ago ago)"
    }

    // ── Normalizing ─────────────────────────────────────────────────────────────────────────────────

    /** Words as written: letters and digits, with their elisions (`t'es`, `aujourd'hui`). */
    private val WORD = Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)*")

    /** « … » or « ... » at the end of a sentence. */
    private val ELLIPSIS = Regex("(…|\\.\\.\\.)(?=\\s|$|[»\"”)])")

    /** The kind `SpeechTools` puts before a title, a line above the hotbar or a book in the `chat` table. */
    private val KIND = Regex("^\\[(?:title|actionbar|book \".*?\")] ")

    /** The name a nickname gives: what its statement puts between « ». */
    private val NICKNAME = Regex("«\\s*([^«»]{1,40})\\s*»")

    /** A number in digits: `18`, `18h42`, `3e`, `1er`. */
    private val DIGITS = Regex("\\d+(?:h\\d*)?(?:e|er|re|ème|eme)?")

    /**
     * French words too common to make a tic on their own (articles, pronouns, prepositions, auxiliaries).
     *
     * **These words are search data, not text for a human**: the Narrator speaks French, so its tics are
     * French phrases, and a phrase that starts or ends with « de » or « la » is not one. They are never shown
     * to a model nor to a player: like `CueWords` and the analyzer's stop words, they are the one legitimate
     * kind of French in the code. `FrenchAnalyzer`'s own list is private and has no accents.
     */
    private val STOP_WORDS: Set<String> = setOf(
        "le", "la", "les", "l", "un", "une", "des", "du", "de", "d", "au", "aux", "à", "a", "et", "ou", "en", "dans", "sur",
        "sous", "par", "pour", "avec", "sans", "que", "qu", "qui", "quoi", "ce", "cet", "cette", "ces", "se", "sa", "son",
        "ses", "ta", "ton", "tes", "ma", "mon", "mes", "notre", "nos", "votre", "vos", "leur", "leurs", "je", "j", "tu",
        "il", "elle", "on", "nous", "vous", "ils", "elles", "me", "m", "te", "t", "lui", "y", "ne", "n", "pas", "plus",
        "est", "es", "suis", "sont", "été", "être", "ai", "as", "ont", "avez", "avons", "c", "s", "si", "mais", "donc",
        "car", "ni", "comme", "pendant", "avant", "après", "depuis", "vers", "chez", "entre", "contre", "tout", "tous",
        "toute", "toutes", "très",
    )

    /**
     * French numbers in letters, cardinal and ordinal (« un » and « une » are left out: they are articles far more
     * often). Search data, like [STOP_WORDS].
     */
    private val NUMBER_WORDS: Set<String> = setOf(
        "zéro", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf", "dix", "onze", "douze", "treize", "quatorze",
        "quinze", "seize", "vingt", "vingts", "trente", "quarante", "cinquante", "soixante", "cent", "cents", "mille", "million",
        "millions", "milliard", "milliards", "premier", "première", "premiers", "premières", "deuxième", "troisième",
        "quatrième", "cinquième", "sixième", "septième", "huitième", "neuvième", "dixième", "onzième", "douzième",
        "treizième", "quatorzième", "quinzième", "seizième", "vingtième", "trentième", "quarantième", "cinquantième",
        "soixantième", "centième", "millième",
    )

    /** A word in lower case, `’` as `'`. */
    private fun key(word: String): String = word.lowercase().replace('’', '\'')

    /**
     * A stop word, or an elision of one (`c'est`, `qu'on`, `d'un`): what follows the apostrophe decides, so that
     * `l'aviation` stays a content word.
     */
    fun isStop(word: String): Boolean {
        val key = key(word)
        if (key in STOP_WORDS) return true
        val apostrophe = key.indexOf('\'')
        return apostrophe >= 0 && key.substring(apostrophe + 1) in STOP_WORDS
    }

    private fun isNumber(key: String): Boolean = key in NUMBER_WORDS || DIGITS.matches(key)

    /** Said with `say` (no kind before it) and [OPENER_MIN_CHARS] characters long at least: it counts for the openers. */
    fun opens(text: String): Boolean = !KIND.containsMatchIn(text) && text.codePointCount(0, text.length) >= OPENER_MIN_CHARS

    /** [text] without the kind `SpeechTools` puts before a title, a line above the hotbar or a book. */
    fun body(text: String): String = KIND.find(text)?.let { text.substring(it.range.last + 1) } ?: text

    /**
     * The tokens of [text]: its words in lower case, a run of [names] (also joined by « et ») as one [NAME], a run of
     * numbers as one [NUMBER].
     */
    fun tokens(text: String, names: Names): List<Token> {
        val words = WORD.findAll(text).toList()
        val keys = words.map { key(it.value) }
        val tokens = ArrayList<Token>(words.size)
        var i = 0
        while (i < words.size) {
            val named = if (names.isEmpty) 0 else names.match(keys, i)
            val span = maxOf(named, 1)
            val key = when {
                named > 0 -> NAME
                isNumber(keys[i]) -> NUMBER
                else -> keys[i]
            }
            val token = Token(key, words[i].range.first, words[i + span - 1].range.last + 1)
            val last = tokens.lastOrNull()
            when {
                (key == NAME || key == NUMBER) && last != null && last.key == key -> tokens[tokens.lastIndex] = last.copy(end = token.end)
                key == NAME && last?.key == "et" && tokens.getOrNull(tokens.lastIndex - 1)?.key == NAME -> {
                    tokens.removeAt(tokens.lastIndex)
                    tokens[tokens.lastIndex] = tokens.last().copy(end = token.end)
                }
                else -> tokens += token
            }
            i += span
        }
        return tokens
    }

    /**
     * The content words of [said] ([Token.isWord]), in lower case, an elided article or pronoun dropped (`l'enquête` →
     * `enquête`, `aujourd'hui` stays): what the watch counts to propose crutch words.
     */
    fun words(said: Said): Set<String> = said.tokens.filter { it.isWord }.mapTo(LinkedHashSet()) { token ->
        val apostrophe = token.key.indexOf('\'')
        if (apostrophe > 0 && token.key.substring(0, apostrophe) in STOP_WORDS) token.key.substring(apostrophe + 1) else token.key
    }

    /** The runs of 3 to 5 tokens of a line that neither start nor end with a stop word or a placeholder, as (key, written). */
    private fun phrases(line: Said): List<Pair<String, String>> = buildList {
        val tokens = line.tokens
        val body = body(line.text)
        for (size in 3..5) {
            for (start in 0..tokens.size - size) {
                val run = tokens.subList(start, start + size)
                if (run.first().isWord && run.last().isWord) add(run.joinToString(" ") { it.key } to body.substring(run.first().start, run.last().end))
            }
        }
    }

    /** Counts each key once per line (lines oldest first), keeping its written forms (the latest first) and the latest time. */
    private fun counted(lines: List<Pair<Instant, List<Pair<String, String>>>>): List<Construction> {
        val byKey = LinkedHashMap<String, Construction>()
        for ((ts, runs) in lines) {
            for ((key, written) in runs.distinctBy { it.first }) {
                val seen = byKey[key]
                val forms = listOf(written) + seen?.forms.orEmpty().filterNot { it.equals(written, ignoreCase = true) }
                byKey[key] = Construction(key, forms, (seen?.count ?: 0) + 1, ts)
            }
        }
        return byKey.values.toList()
    }

    private fun ellipsis(said: List<Said>): Tic? {
        val ending = said.filter { ELLIPSIS.containsMatchIn(it.text) }
        if (said.isEmpty() || ending.size <= said.size * ELLIPSIS_SHARE) return null
        return Tic("…", ending.size, ending.last().ts, ending.size.toDouble() / said.size)
    }
}
