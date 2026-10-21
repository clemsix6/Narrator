package io.github.clemsix6.narrator2.style

/**
 * How long a line of the Director is, as `director.md` counts it (M6-18, finding 2): « une à trois phrases par message,
 * souvent une seule ». Measured, never asked of a model: its [sentences], each ended by `.`, `!`, `?` or `…` (a run of
 * them counts once, with the quotes and brackets that close after it) before a space or the end, a last part without
 * one counting too; and its [paragraphs], the parts a line break separates.
 */
data class LineLength(val sentences: Int, val paragraphs: Int) {
    /** More sentences than a message allows ([MAX_SENTENCES]), or a line break: two messages said as one. */
    val long: Boolean get() = sentences > MAX_SENTENCES || paragraphs > 1

    /** `5 sentences in 2 paragraphs`, `4 sentences`. */
    fun describe(): String = "$sentences ${if (sentences == 1) "sentence" else "sentences"}" + if (paragraphs > 1) " in $paragraphs paragraphs" else ""

    companion object {
        /** `director.md`: one to three sentences a message. */
        const val MAX_SENTENCES = 3

        /** The length of [text]. */
        fun of(text: String): LineLength {
            val paragraphs = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            return LineLength(paragraphs.sumOf(::sentences), paragraphs.size)
        }

        /** The sentences of [paragraph]: its ends ([END]), and one more for a last part that holds a word without one. */
        private fun sentences(paragraph: String): Int {
            val ends = END.findAll(paragraph).toList()
            val rest = paragraph.substring(ends.lastOrNull()?.let { it.range.last + 1 } ?: 0)
            return ends.size + if (rest.any { it.isLetterOrDigit() }) 1 else 0
        }

        /**
         * The end of a sentence: `.`, `!`, `?` or `…`, the quotes and brackets that close after it, then a space (French
         * no-break spaces included) or the end. The dot of `2.5` is none.
         */
        private val END = Regex("""[.!?…]+[»"”’)\]]*(?=[\s  ]|$)""")
    }
}
