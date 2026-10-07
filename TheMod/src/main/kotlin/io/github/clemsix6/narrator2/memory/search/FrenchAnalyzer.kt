package io.github.clemsix6.narrator2.memory.search

import java.text.Normalizer
import java.util.Locale

/**
 * The French analyzer of the memory search (M3 §10.2): turns a text into the terms of the full-text
 * index, the same way for the rows and for the queries. Pure, any thread.
 *
 * 1. Split on anything that is not a letter, a digit, `_`, `:` or an apostrophe.
 * 2. Drop the elision of the first apostrophe (`l'`, `d'`, `qu'`, `jusqu'`…; `’` too), then the other apostrophes.
 * 3. Lowercase, then fold accents (`é` → `e`, `œ` → `oe`).
 * 4. A game id (`:` or `_`) is kept whole without `minecraft:` (`trial_chambers`), and each of its parts is
 *    indexed too (`trial`, `chamb`): an event's id finds the memory that names it, and so do French words.
 * 5. Drop the stop words (articles, pronouns, auxiliaries, prepositions).
 * 6. Stem with Savoy's light French stemmer (`chambres` → `chambr`, `noyades` → `noyad`, `insulté` → `insult`).
 *
 * Rewritten from Savoy's published algorithm (the one of Lucene's `FrenchLightStemmer`); the spike
 * `.research/spikes/m3/FrenchText.java` (`OURS_LIGHT`) is the reference of its behavior. Since accents are
 * folded before stemming, the stemmer's suffixes are written folded (`ieme`, `ere`, `ete`).
 */
object FrenchAnalyzer {
    /** The terms of [text], in order, repeats kept (they are the term frequencies). */
    fun terms(text: String): List<String> {
        val out = ArrayList<String>()
        for (token in SPLIT.split(text)) {
            if (token.isEmpty()) continue
            var word = token.replace('’', '\'')
            val apostrophe = word.indexOf('\'')
            if (apostrophe > 0 && word.substring(0, apostrophe).lowercase(Locale.ROOT) in ELISIONS) word = word.substring(apostrophe + 1)
            word = fold(word.replace("'", "").lowercase(Locale.ROOT))
            when {
                word.isEmpty() -> Unit
                ':' in word || '_' in word -> gameId(word, out)
                word !in STOP_WORDS -> out += stem(word)
            }
        }
        return out
    }

    /** [text] without accents: `épreuve` → `epreuve`, `œuf` → `oeuf`. */
    fun fold(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val folded = StringBuilder(decomposed.length)
        for (c in decomposed) if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) folded.append(c)
        return folded.toString().replace("œ", "oe").replace("æ", "ae").replace("Œ", "OE").replace("Æ", "AE")
    }

    /** `minecraft:trial_chambers` → `trial_chambers`, `trial`, `chamb`; another namespace keeps only its parts. */
    private fun gameId(word: String, out: MutableList<String>) {
        val id = word.removePrefix(VANILLA)
        if (':' !in id && id.any { it.isLetterOrDigit() }) out += id
        for (part in id.split(':', '_')) if (part.length > 1 && part !in STOP_WORDS) out += stem(part)
    }

    /** Savoy's light stemmer, on a lowercase word without accents. */
    internal fun stem(word: String): String {
        val s = word.toCharArray()
        var len = s.size
        if (len > 5 && s[len - 1] == 'x') {
            if (s[len - 3] == 'a' && s[len - 2] == 'u' && s[len - 4] != 'e') s[len - 2] = 'l'
            len--
        }
        if (len > 3 && s[len - 1] == 'x') len--
        if (len > 3 && s[len - 1] == 's') len--
        if (len > 9 && s.ends(len, "issement")) {
            len -= 6
            s[len - 1] = 'r'
            return norm(s, len)
        }
        if (len > 8 && s.ends(len, "issant")) {
            len -= 4
            s[len - 1] = 'r'
            return norm(s, len)
        }
        if (len > 6 && s.ends(len, "ement")) {
            len -= 4
            if (len > 3 && s.ends(len, "ive")) {
                len--
                s[len - 1] = 'f'
            }
            return norm(s, len)
        }
        if (len > 11 && s.ends(len, "ficatrice")) {
            len -= 5
            s[len - 2] = 'e'
            s[len - 1] = 'r'
            return norm(s, len)
        }
        if (len > 10 && s.ends(len, "ficateur")) {
            len -= 4
            s[len - 2] = 'e'
            s[len - 1] = 'r'
            return norm(s, len)
        }
        if (len > 9 && s.ends(len, "catrice")) {
            len -= 3
            s[len - 4] = 'q'
            s[len - 3] = 'u'
            s[len - 2] = 'e'
            return norm(s, len)
        }
        if (len > 8 && s.ends(len, "cateur")) {
            len -= 2
            s[len - 4] = 'q'
            s[len - 3] = 'u'
            s[len - 2] = 'e'
            s[len - 1] = 'r'
            return norm(s, len)
        }
        if (len > 8 && s.ends(len, "atrice")) {
            len -= 4
            s[len - 2] = 'e'
            s[len - 1] = 'r'
            return norm(s, len)
        }
        if (len > 7 && s.ends(len, "ateur")) {
            len -= 3
            s[len - 2] = 'e'
            s[len - 1] = 'r'
            return norm(s, len)
        }
        if (len > 6 && s.ends(len, "trice")) {
            len--
            s[len - 3] = 'e'
            s[len - 2] = 'u'
            s[len - 1] = 'r'
        }
        if (len > 5 && s.ends(len, "ieme")) return norm(s, len - 4)
        if (len > 7 && s.ends(len, "teuse")) {
            len -= 2
            s[len - 1] = 'r'
            return norm(s, len)
        }
        if (len > 6 && s.ends(len, "teur")) {
            len--
            s[len - 1] = 'r'
            return norm(s, len)
        }
        if (len > 5 && s.ends(len, "euse")) return norm(s, len - 2)
        if (len > 8 && s.ends(len, "ere")) return norm(s, len - 1)
        if (len > 7 && s.ends(len, "ive")) {
            len--
            s[len - 1] = 'f'
            return norm(s, len)
        }
        if (len > 4 && (s.ends(len, "folle") || s.ends(len, "molle"))) {
            len -= 2
            s[len - 1] = 'u'
            return norm(s, len)
        }
        if (len > 9 && s.ends(len, "nnelle")) return norm(s, len - 5)
        if (len > 9 && s.ends(len, "nnel")) return norm(s, len - 3)
        if (len > 4 && s.ends(len, "ete")) len--
        if (len > 8 && s.ends(len, "ique")) len -= 4
        if (len > 8 && s.ends(len, "esse")) return norm(s, len - 3)
        if (len > 7 && s.ends(len, "inage")) return norm(s, len - 3)
        if (len > 9 && s.ends(len, "isation")) {
            len -= 7
            if (len > 5 && s.ends(len, "ual")) s[len - 2] = 'e'
            return norm(s, len)
        }
        if (len > 9 && s.ends(len, "isateur")) return norm(s, len - 7)
        if (len > 8 && s.ends(len, "ation")) return norm(s, len - 5)
        if (len > 8 && s.ends(len, "ition")) return norm(s, len - 5)
        return norm(s, len)
    }

    /** Savoy's last step: doubled letters collapsed, then `ie`, `r`, `e`, `e` and a doubled last letter dropped. */
    private fun norm(s: CharArray, length: Int): String {
        var len = length
        if (len > 4) {
            var previous = s[0]
            var i = 1
            while (i < len) {
                if (s[i] == previous && previous.isLetter()) {
                    System.arraycopy(s, i + 1, s, i, len - i - 1)
                    len--
                } else {
                    previous = s[i]
                    i++
                }
            }
        }
        if (len > 4 && s.ends(len, "ie")) len -= 2
        if (len > 4) {
            if (s[len - 1] == 'r') len--
            if (s[len - 1] == 'e') len--
            if (s[len - 1] == 'e') len--
            if (s[len - 1] == s[len - 2] && s[len - 1].isLetter()) len--
        }
        return String(s, 0, len)
    }

    private fun CharArray.ends(len: Int, suffix: String): Boolean {
        if (suffix.length > len) return false
        for (i in suffix.indices) if (this[len - suffix.length + i] != suffix[i]) return false
        return true
    }

    private const val VANILLA = "minecraft:"

    private val SPLIT = Regex("[^\\p{L}\\p{N}_:'’]+")

    private val ELISIONS = setOf("l", "m", "t", "qu", "n", "s", "j", "d", "c", "jusqu", "quoiqu", "lorsqu", "puisqu")

    /** A short French stop list (articles, pronouns, auxiliaries, prepositions), folded: the spike's. */
    private val STOP_WORDS: Set<String> = (
        "a au aux avec ce ces dans de des du elle en et eux il ils je la le les leur lui ma mais me meme mes moi mon ne " +
            "nos notre nous on ou par pas pour qu que qui sa se ses son sur ta te tes toi ton tu un une vos votre vous " +
            "c d j l m n s t y ete etee etees etes etant suis es est sommes sont serai sera serons seront serait etait " +
            "etaient fut ai as avons avez ont aurai aura avait avaient eu eue eus ceci cela celui celle ceux cet cette " +
            "ici lors puis tres plus moins si sans sous vers chez entre depuis comme dont quel quelle quels quelles fois " +
            "deja encore aussi tout tous toute toutes"
        ).split(' ').toSet()
}
