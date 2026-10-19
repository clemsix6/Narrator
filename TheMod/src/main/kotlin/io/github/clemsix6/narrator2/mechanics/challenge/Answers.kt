package io.github.clemsix6.narrator2.mechanics.challenge

import java.text.Normalizer

/**
 * The answers of an `answer` challenge (a quiz, M4 §5.2): an accepted answer is found in a message when,
 * both normalized ([normalize]), its words appear as whole words of the message, in a row. « C'est une
 * Enclume ! » answers `enclume` and `une enclume`, never `clume`. Pure, any thread.
 */
object Answers {
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
        val words = " ${normalize(message)} "
        return answers.any { answer -> normalize(answer).let { it.isNotEmpty() && " $it " in words } }
    }

    /** The first of [answers] that has nothing left once normalized (only punctuation or spaces), with its index; null when all do. */
    fun firstEmpty(answers: List<String>): IndexedValue<String>? = answers.withIndex().firstOrNull { normalize(it.value).isEmpty() }
}
