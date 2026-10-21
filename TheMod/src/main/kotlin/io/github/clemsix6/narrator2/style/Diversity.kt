package io.github.clemsix6.narrator2.style

import io.github.clemsix6.narrator2.memory.stats.Tics
import kotlin.math.ln

/**
 * The measures of the diversity of the Narrator's lines (M6 §3.7), pure functions: the watch takes them on its window
 * (`/narrator style`, [StyleWatch.diversity]), the style report and the evals on a period.
 *
 * | Measure | Definition |
 * |---|---|
 * | distinct openers | distinct normalized openers ([Tics.Said.opener]) over the lines that open |
 * | dominant register | the register of the voice ([ComicRegister.VOICE]) of the most labeled lines, and its share of them |
 * | register entropy | H / ln(min(K, n)), K the registers of the voice, n the lines labeled with one |
 * | crutch words | each word the review marked, and the lines that hold it |
 *
 * `plain` and `other` are labels, not registers of the voice: the rotation is measured on the lines labeled with a
 * register of the voice only. Distinct trigrams are left out: 0.95 on an evening full of tics (essay).
 */
object DiversityMeasures {
    /** The diversity of [said] (the lines measured), with the [labels] of the latest lines, the [crutches] marked and [tics] found. */
    fun measure(said: List<Tics.Said>, labels: List<LineLabels>, crutches: Collection<String>, tics: Int): Diversity {
        val registers = labels.map { it.register }
        val top = topRegister(registers)
        return Diversity(
            lines = said.size,
            openersDistinct = openersDistinct(said),
            topRegister = top?.first,
            topRegisterShare = top?.second ?: 0.0,
            registerEntropy = registerEntropy(registers),
            crutches = crutchLines(said, crutches),
            tics = tics,
        )
    }

    /** Distinct normalized openers over the lines of [said] that open (25 characters or more, said with `say`); 0 without one. */
    fun openersDistinct(said: List<Tics.Said>): Double {
        val openers = said.mapNotNull { it.opener?.key }
        return if (openers.isEmpty()) 0.0 else openers.toSet().size.toDouble() / openers.size
    }

    /**
     * The register of the voice most of [registers] have (the first of the voice's order at equal count) and its share of
     * those of the voice; null without one.
     */
    fun topRegister(registers: List<ComicRegister>): Pair<ComicRegister, Double>? {
        val voice = registers.filter { it in ComicRegister.VOICE }
        if (voice.isEmpty()) return null
        val counts = voice.groupingBy { it }.eachCount()
        val top = ComicRegister.VOICE.maxBy { counts[it] ?: 0 }
        return top to counts.getValue(top).toDouble() / voice.size
    }

    /**
     * How evenly the registers of the voice among [registers] spread: H / ln(min(K, n)), K the registers of the voice and
     * n the lines labeled with one of them; 1 when they spread as much as they can, 0 for a single register or a single line.
     */
    fun registerEntropy(registers: List<ComicRegister>): Double {
        val voice = registers.filter { it in ComicRegister.VOICE }
        val most = minOf(ComicRegister.VOICE.size, voice.size)
        if (most < 2) return 0.0
        val entropy = voice.groupingBy { it }.eachCount().values.sumOf { count ->
            val p = count.toDouble() / voice.size
            -p * ln(p)
        }
        return (entropy / ln(most.toDouble())).coerceIn(0.0, 1.0)
    }

    /** Each word of [crutches] and the lines of [said] that hold it, the most frequent first; the words no line holds left out. */
    fun crutchLines(said: List<Tics.Said>, crutches: Collection<String>): List<Pair<String, Int>> {
        val words = said.map { Tics.words(it) }
        return crutches.map { it.lowercase() }.distinct()
            .map { crutch -> crutch to words.count { crutch in it } }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
    }
}
