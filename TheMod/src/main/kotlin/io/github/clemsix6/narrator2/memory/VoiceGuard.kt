package io.github.clemsix6.narrator2.memory

import java.text.Normalizer

/**
 * Keeps the Narrator's voice out of the memory (M3 §12): everything the memory keeps is factual, in the
 * third person, in French. « Le Narrateur a promis… », never « J'ai promis… »; « Bartok a insulté le
 * Narrateur », never « Tu m'as insulté ». The only words of the Narrator it keeps are short quotes, between
 * « », which this guard leaves alone. The store runs it on every text a model writes; pure, any thread.
 */
object VoiceGuard {
    /** First-person (Narrator's voice) and second-person (addressing someone) words, accents folded. */
    private val PERSON_WORDS = setOf(
        "je", "me", "moi", "mon", "ma", "mes", "nous", "notre", "nos",
        "tu", "te", "toi", "ton", "ta", "tes", "vous", "votre", "vos",
    )

    /** Elided forms: `j'`, `m'`, `t'` (straight or curly apostrophe), accents folded. */
    private val ELIDED = setOf('j', 'm', 't')

    /** Words before `ton` that make it the noun « tone » (« sur un ton moqueur »), not « your ». */
    private val TONE_DETERMINERS = setOf("le", "un", "du", "au", "ce", "cet", "son", "leur", "meme", "quel")

    /** Auxiliaries before `tu` that make it the participle of « taire » (« il s'est tu », « a tu son vol »). */
    private val AUXILIARIES = setOf(
        "a", "avait", "aura", "aurait", "ont", "avaient", "avoir", "est", "etait", "fut", "sera", "serait", "sont", "etaient", "etre",
    )

    /** Quotes left out of the checks: opener → closer. */
    private val QUOTES = mapOf('«' to '»', '“' to '”', '"' to '"')

    /** Grandiose or dark words (lowercase, accents kept), by their base form: the voice drifting into the memory. */
    private val LEXICON: Map<String, String> = buildMap {
        listOf("ténèbres").forEach { put(it, "ténèbres") }
        listOf("néant", "néants").forEach { put(it, "néant") }
        listOf("abîme", "abîmes").forEach { put(it, "abîme") }
        listOf("damnation", "damnations").forEach { put(it, "damnation") }
        listOf("supplice", "supplices").forEach { put(it, "supplice") }
        listOf("agonie", "agonies").forEach { put(it, "agonie") }
        listOf("désespoir", "désespoirs").forEach { put(it, "désespoir") }
        listOf("éternel", "éternelle", "éternels", "éternelles", "éternellement").forEach { put(it, "éternel") }
        listOf("âme", "âmes").forEach { put(it, "âme") }
    }

    /**
     * Null when [text] may be stored as a factual third-person memory in the field [field]; else the
     * problem, in English, with the fix, for the model. Refused: an empty text, a text longer than
     * [maxChars] characters (code points), `<` or `>` anywhere, and outside quotes (« … », “ … ”,
     * " … ") a first- or second-person pronoun or determiner (je, j', me, m', moi, mon, ma, mes, nous,
     * notre, nos; tu, t', te, toi, ton, ta, tes, vous, votre, vos), whole words, whatever the case and
     * the accents.
     */
    fun check(text: String, field: String, maxChars: Int): String? {
        if (text.isBlank()) return "$field must not be empty"
        val length = text.codePointCount(0, text.length)
        if (length > maxChars) return "$field is longer than $maxChars characters (got $length): shorten it"
        if ('<' in text || '>' in text) return "$field must not contain < or >: write the words instead"
        val found = personWord(unquoted(text)) ?: return null
        return "$field must be factual and in the third person, never in the Narrator's voice nor addressed to anyone " +
            "(found \"$found\" outside quotes): write « Le Narrateur a promis… » or « Bartok a insulté le Narrateur », " +
            "and put the words someone said between « »"
    }

    /**
     * Words of [text] that sound grandiose or dark (ténèbres, néant, abîme, damnation, supplice, agonie,
     * désespoir, éternel, âme), outside quotes, by their base form, in order of appearance. Never a
     * refusal: the store counts and logs them (decision `memory voice_warning`).
     */
    fun warnings(text: String): List<String> =
        words(Normalizer.normalize(unquoted(text), Normalizer.Form.NFC).lowercase()).mapNotNull { LEXICON[it] }.distinct()

    /** [text] with its closed quotes replaced by a space; an opener without its closer stays (and is checked). */
    internal fun unquoted(text: String): String = buildString {
        var i = 0
        while (i < text.length) {
            val closer = QUOTES[text[i]]
            val end = if (closer != null) text.indexOf(closer, i + 1) else -1
            if (end > i) {
                append(' ')
                i = end + 1
            } else {
                append(text[i])
                i++
            }
        }
    }

    /** The first first- or second-person word of [text] (already unquoted), as written after folding; null when none. */
    private fun personWord(text: String): String? {
        val words = words(fold(text))
        words.forEachIndexed { index, word ->
            val previous = words.getOrNull(index - 1)
            val elided = word.length == 2 && word[0] in ELIDED && word[1] == '\''
            when {
                elided -> return word
                word !in PERSON_WORDS -> Unit
                word == "ton" && previous in TONE_DETERMINERS -> Unit
                word == "tu" && previous in AUXILIARIES -> Unit
                else -> return word
            }
        }
        return null
    }

    /**
     * The words of [text], in order: runs of letters, digits, `_` and `:` (game ids stay whole); an
     * apostrophe ends a word and is kept on it when it follows a single letter (`j'`, `l'`, `s'`). The
     * auxiliary of « s'est tu » is the word before `tu`.
     */
    private fun words(text: String): List<String> = buildList {
        val word = StringBuilder()
        fun flush() {
            if (word.isNotEmpty()) add(word.toString())
            word.clear()
        }
        for (c in text) {
            when {
                c.isLetterOrDigit() || c == '_' || c == ':' -> word.append(c)
                (c == '\'' || c == '’') && word.length == 1 && word[0].isLetter() -> {
                    word.append('\'')
                    flush()
                }
                else -> flush()
            }
        }
        flush()
    }

    /** Lowercase, accents removed (`Tú` → `tu`, `œ` kept as a letter). */
    private fun fold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "").lowercase()

    private val MARKS = Regex("\\p{M}+")
}
