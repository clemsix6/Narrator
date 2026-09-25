package io.github.clemsix6.narrator2.reflex

import io.github.clemsix6.narrator2.core.Log
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The reflexes without Jev (M1_M2 §11, `docs/research/JEV.md` §2.1): word lists and a few rules. Used
 * when Jev is off or fails, and to settle Jev's gray zone. The words are data, one file per language
 * (`narrator2/reflex/heuristics_<language>.txt`); the rules are here.
 *
 * - Addressed: the Narrator's name without a determiner, or after an interjection ("hé le narrateur");
 *   a manipulation formula; or, right after a Narrator line, a message in the second person or a
 *   question that names no other player.
 * - Tone, only when addressed: insult → hostile; manipulation or rude word → disrespectful; challenge or
 *   laughter → mocking; courtesy → respectful; nothing → respectful with a low confidence (hostility is
 *   never presumed).
 * - Intent: not addressed → chat between players; then offering, challenge, insult (provocation),
 *   manipulation (request), request, question, rude word (provocation), other.
 * - No personality signals; a heartbeat is always worth a wake-up (nothing is filtered without Jev).
 *
 * Pure and fast: never throws, never suspends for real.
 */
class HeuristicReflexes(private val words: HeuristicWords) : Reflexes {

    override suspend fun triageChat(input: ChatTriageInput): ChatVerdict = judge(input)

    override suspend fun heartbeat(windowMinutes: Int, summary: List<String>, online: List<String>): HeartbeatVerdict =
        HeartbeatVerdict(worth = 1.0, hook = null, source = SOURCE, latencyMs = 0)

    /** The verdict on [input], at once. */
    fun judge(input: ChatTriageInput): ChatVerdict {
        val tokens = HeuristicWords.normalize(input.message.text)
        val manipulation = words.matches(Category.MANIPULATION, tokens)
        val toNarrator = maxOf(addressScore(input, tokens), if (manipulation) MANIPULATION_ADDRESS else 0.0)
        val addressed = toNarrator >= ReflexThresholds.ADDRESSED
        val (tone, toneConfidence) = if (addressed) tone(tokens, manipulation) else Tone.NEUTRAL_NO_TARGET to MATCH_CONFIDENCE
        return ChatVerdict(
            toNarrator = toNarrator,
            tone = tone,
            toneConfidence = toneConfidence,
            manipulation = if (manipulation) MANIPULATION_SCORE else 0.0,
            intent = if (addressed) intent(input.message.text, tokens, manipulation) else ChatIntent.BANTER_BETWEEN_PLAYERS,
            personality = null,
            source = SOURCE,
            latencyMs = 0,
        )
    }

    private fun addressScore(input: ChatTriageInput, tokens: List<String>): Double {
        val names = words.find(Category.ADDRESS, tokens)
        val secondPerson = tokens.any { words.matchesWord(Category.SECOND_PERSON, it) }
        if (names.isNotEmpty()) {
            val direct = names.any { at -> !isThirdPerson(tokens, at) }
            return when {
                direct -> DIRECT
                // "le narrateur t'es nul": the name opens the message, so it is most likely called.
                secondPerson && names.any { it == 1 } -> OPENING_MENTION_IN_SECOND_PERSON
                secondPerson -> MENTION_IN_SECOND_PERSON
                else -> THIRD_PERSON
            }
        }
        if (input.narratorLast.isNullOrBlank()) return if (secondPerson) SECOND_PERSON_ONLY else NOTHING
        val namesAnotherPlayer = (input.recent.map { it.player }.toSet() - input.message.player)
            .any { name -> HeuristicWords.contains(tokens, HeuristicWords.normalize(name)) }
        return when {
            namesAnotherPlayer -> ANSWERS_ANOTHER_PLAYER
            secondPerson || '?' in input.message.text -> ANSWERS_THE_NARRATOR
            else -> AFTER_THE_NARRATOR
        }
    }

    /** A name at [at] with a determiner before it, and no interjection before that: "le narrateur". */
    private fun isThirdPerson(tokens: List<String>, at: Int): Boolean {
        val before = tokens.getOrNull(at - 1) ?: return false
        if (words.matchesWord(Category.VOCATIVE, before)) return false
        if (!words.matchesWord(Category.ARTICLE, before)) return false
        return tokens.getOrNull(at - 2)?.let { words.matchesWord(Category.VOCATIVE, it) } != true
    }

    private fun tone(tokens: List<String>, manipulation: Boolean): Pair<Tone, Double> = when {
        words.matches(Category.HOSTILE, tokens) -> Tone.HOSTILE to MATCH_CONFIDENCE
        manipulation || words.matches(Category.RUDE, tokens) -> Tone.DISRESPECTFUL to MATCH_CONFIDENCE
        words.matches(Category.CHALLENGE, tokens) || words.matches(Category.LAUGHTER, tokens) -> Tone.MOCKING_PLAYFUL to MATCH_CONFIDENCE
        words.matches(Category.POLITENESS, tokens) -> Tone.RESPECTFUL to MATCH_CONFIDENCE
        else -> Tone.RESPECTFUL to DEFAULT_CONFIDENCE
    }

    private fun intent(text: String, tokens: List<String>, manipulation: Boolean): ChatIntent = when {
        words.matches(Category.OFFERING, tokens) -> ChatIntent.OFFERING
        words.matches(Category.CHALLENGE, tokens) -> ChatIntent.CHALLENGE
        words.matches(Category.HOSTILE, tokens) -> ChatIntent.PROVOCATION
        manipulation || words.matches(Category.REQUEST, tokens) -> ChatIntent.REQUEST
        '?' in text || words.matches(Category.QUESTION, tokens) -> ChatIntent.QUESTION
        words.matches(Category.RUDE, tokens) -> ChatIntent.PROVOCATION
        else -> ChatIntent.OTHER
    }

    /** The word lists, one per `[section]` of the file. */
    enum class Category {
        ADDRESS, VOCATIVE, ARTICLE, SECOND_PERSON, HOSTILE, RUDE, LAUGHTER, POLITENESS, MANIPULATION, OFFERING, CHALLENGE, REQUEST, QUESTION;

        /** Section name in the file, e.g. `second_person`. */
        val id: String get() = name.lowercase()
    }

    companion object {
        /** [ChatVerdict.source] and [HeartbeatVerdict.source] of the heuristic. */
        const val SOURCE = "heuristic"

        /** Language whose words are used when there are none for the configured one. */
        const val DEFAULT_LANGUAGE = "fr"

        // Address scores: at or above ReflexThresholds.ADDRESSED means addressed.
        private const val DIRECT = 0.9
        private const val MANIPULATION_ADDRESS = 0.7
        private const val OPENING_MENTION_IN_SECOND_PERSON = 0.65
        private const val ANSWERS_THE_NARRATOR = 0.65
        private const val MENTION_IN_SECOND_PERSON = 0.45
        private const val AFTER_THE_NARRATOR = 0.4
        private const val THIRD_PERSON = 0.2
        private const val SECOND_PERSON_ONLY = 0.15
        private const val ANSWERS_ANOTHER_PLAYER = 0.1
        private const val NOTHING = 0.05

        private const val MANIPULATION_SCORE = 0.8
        private const val MATCH_CONFIDENCE = 0.7
        private const val DEFAULT_CONFIDENCE = 0.4

        private val byLanguage = ConcurrentHashMap<String, HeuristicReflexes>()

        /**
         * The heuristic for [language], its words read once from the jar. A language without a word
         * file uses [DEFAULT_LANGUAGE]'s, with a warning.
         */
        fun forLanguage(language: String): HeuristicReflexes = byLanguage.computeIfAbsent(language) {
            val words = HeuristicWords.bundled(language) ?: run {
                Log.jev.warn("no chat heuristic for language '$language'; using '$DEFAULT_LANGUAGE' words")
                HeuristicWords.bundled(DEFAULT_LANGUAGE) ?: error("the jar has no ${HeuristicWords.resource(DEFAULT_LANGUAGE)}")
            }
            HeuristicReflexes(words)
        }
    }
}

/**
 * Word lists of the chat heuristic, by [HeuristicReflexes.Category]. Entries and messages are compared
 * as normalized words ([normalize]); an entry is a sequence of words whose last one may end with `*` to
 * match any word starting with it.
 */
class HeuristicWords private constructor(private val entries: Map<HeuristicReflexes.Category, List<Entry>>) {

    /** A sequence of words; [prefix]: the last word is a stem. */
    private class Entry(val words: List<String>, val prefix: Boolean)

    /** Whether an entry of [category] occurs in [tokens]. */
    fun matches(category: HeuristicReflexes.Category, tokens: List<String>): Boolean = find(category, tokens).isNotEmpty()

    /** Where entries of [category] start in [tokens]. */
    fun find(category: HeuristicReflexes.Category, tokens: List<String>): List<Int> =
        tokens.indices.filter { at -> entries.getValue(category).any { it.occursAt(tokens, at) } }

    /** Whether the single word [token] is an entry of [category]. */
    fun matchesWord(category: HeuristicReflexes.Category, token: String): Boolean =
        entries.getValue(category).any { it.words.size == 1 && it.occursAt(listOf(token), 0) }

    private fun Entry.occursAt(tokens: List<String>, at: Int): Boolean {
        if (at + words.size > tokens.size) return false
        return words.indices.all { i ->
            val token = tokens[at + i]
            if (prefix && i == words.lastIndex) token.startsWith(words[i]) else token == words[i]
        }
    }

    companion object {
        private val WORD = Regex("[a-z0-9]+|:")
        private val MARKS = Regex("\\p{M}+")
        private val SECTION = Regex("\\[([a-z_]+)]")

        /** Resource path of the word file of [language]. */
        fun resource(language: String) = "/narrator2/reflex/heuristics_$language.txt"

        /** The words of [language] shipped in the jar; null when there is no file for it. */
        fun bundled(language: String): HeuristicWords? =
            HeuristicWords::class.java.getResourceAsStream(resource(language))?.use { parse(it.readBytes().decodeToString(), resource(language)) }

        /**
         * Reads a word file (format in `heuristics_fr.txt`). Throws [IllegalArgumentException] naming the
         * line when a section is unknown, missing or empty, or an entry is outside a section.
         */
        fun parse(text: String, source: String): HeuristicWords {
            val byName = HeuristicReflexes.Category.entries.associateBy { it.id }
            val entries = HeuristicReflexes.Category.entries.associateWith { mutableListOf<Entry>() }
            var current: HeuristicReflexes.Category? = null
            text.lines().forEachIndexed { index, raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
                val section = SECTION.matchEntire(line)
                if (section != null) {
                    current = byName[section.groupValues[1]]
                        ?: throw IllegalArgumentException("$source:${index + 1}: unknown section [${section.groupValues[1]}]")
                    return@forEachIndexed
                }
                val category = current ?: throw IllegalArgumentException("$source:${index + 1}: entry outside a section")
                val prefix = line.endsWith("*")
                val words = normalize(line.removeSuffix("*"))
                require(words.isNotEmpty()) { "$source:${index + 1}: entry without a word" }
                entries.getValue(category) += Entry(words, prefix)
            }
            val empty = entries.filterValues { it.isEmpty() }.keys
            require(empty.isEmpty()) { "$source: no entries for ${empty.joinToString { "[${it.id}]" }}" }
            return HeuristicWords(entries)
        }

        /**
         * The words of [text]: lowercase, without accents, split on every character other than a letter,
         * a digit or ':', which is a word of its own ("système :" gives `systeme`, `:`).
         */
        fun normalize(text: String): List<String> {
            val lower = text.lowercase(Locale.ROOT).replace("œ", "oe").replace("æ", "ae")
            val plain = MARKS.replace(Normalizer.normalize(lower, Normalizer.Form.NFD), "")
            return WORD.findAll(plain).map { it.value }.toList()
        }

        /** Whether [phrase] occurs as consecutive words in [tokens]. */
        fun contains(tokens: List<String>, phrase: List<String>): Boolean =
            phrase.isNotEmpty() && tokens.windowed(phrase.size).any { it == phrase }
    }
}
