package io.github.clemsix6.narrator2.mechanics.challenge

import java.text.Normalizer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The answers of an `answer` challenge (a quiz, M4 §5.2): an accepted answer is found in a message when,
 * both normalized ([normalize]), its words appear as whole words of the message, in a row ([matches], which a
 * rule's `words` use too), and the message has at most [EXTRA_WORDS] words more than the answer ([answers]).
 * « C'est une Enclume ! » answers `enclume` and `une enclume`, never `clume`; a list of guesses (« diamant fer
 * or émeraude enclume pioche ») answers nothing (M4 review, finding 14). Pure, any thread.
 */
object Answers {
    /** Words a message may have besides those of the answer: « c'est une enclume » answers `enclume`. */
    const val EXTRA_WORDS = 3

    /** A participant's messages less than this apart count as one attempt: the first (M4 review, finding 14). */
    const val ATTEMPT_GAP_MS = 5_000L

    /** Ligatures and letters that the decomposition leaves whole, written out: `œuf` is `oeuf`. */
    private val SPELLED = mapOf('œ' to "oe", 'Œ' to "oe", 'æ' to "ae", 'Æ' to "ae", 'ß' to "ss", 'ø' to "o", 'Ø' to "o", 'ł' to "l", 'Ł' to "l")

    private val MARKS = Regex("\\p{M}+")

    /**
     * [text] as the answers compare it: lower case, without accents, every character that is neither a
     * letter nor a digit (punctuation, apostrophes, symbols) as a space, the spaces folded and trimmed.
     * `« L'Œuf, déjà ! »` is `l oeuf deja`.
     */
    fun normalize(text: String): String {
        val spelled = buildString(text.length) { text.forEach { c -> append(SPELLED[c] ?: c) } }
        val bare = MARKS.replace(Normalizer.normalize(spelled, Normalizer.Form.NFD), "").lowercase()
        val words = StringBuilder(bare.length)
        var space = true
        for (c in bare) {
            if (c.isLetterOrDigit()) {
                words.append(c)
                space = false
            } else if (!space) {
                words.append(' ')
                space = true
            }
        }
        return words.toString().trimEnd()
    }

    /** Whether [message] holds one of [answers], each normalized, as whole words. Answers that normalize to nothing never match. */
    fun matches(message: String, answers: List<String>): Boolean {
        val said = normalize(message)
        return answers.any { holds(said, normalize(it), Int.MAX_VALUE) }
    }

    /**
     * Whether [message] answers a quiz whose accepted answers are [answers]: it [matches] one of them and has at most
     * [EXTRA_WORDS] words besides it, so that a list of guesses wins nothing.
     */
    fun answers(message: String, answers: List<String>): Boolean {
        val said = normalize(message)
        return answers.any { answer -> normalize(answer).let { holds(said, it, wordCount(it) + EXTRA_WORDS) } }
    }

    /** Whether the normalized [said] holds the normalized [wanted] as whole words, in [most] words at most. */
    private fun holds(said: String, wanted: String, most: Int): Boolean = wanted.isNotEmpty() && wordCount(said) <= most && " $wanted " in " $said "

    private fun wordCount(normalized: String): Int = if (normalized.isEmpty()) 0 else normalized.count { it == ' ' } + 1

    /** The first of [answers] that has nothing left once normalized (only punctuation or spaces), with its index; null when all do. */
    fun firstEmpty(answers: List<String>): IndexedValue<String>? = answers.withIndex().firstOrNull { normalize(it.value).isEmpty() }
}

/**
 * The attempts of the participants of the quizzes (M4 review, finding 14): a participant's message is an attempt
 * when their last attempt in that quiz is [Answers.ATTEMPT_GAP_MS] old or more; the messages in between are not
 * read, so that a burst of guesses is one guess. Any thread.
 */
class Attempts {
    private val last = ConcurrentHashMap<Pair<Long, UUID>, Long>()

    /** Whether [uuid]'s message at [now] is an attempt at quiz [id]; it is then their last one. */
    fun take(id: Long, uuid: UUID, now: Long): Boolean {
        var taken = false
        last.compute(id to uuid) { _, before ->
            if (before == null || now - before >= Answers.ATTEMPT_GAP_MS || now < before) {
                taken = true
                now
            } else {
                before
            }
        }
        return taken
    }

    /** Forgets the attempts at quiz [id], which ended. */
    fun forget(id: Long) {
        last.keys.removeIf { it.first == id }
    }

    fun clear() = last.clear()
}
