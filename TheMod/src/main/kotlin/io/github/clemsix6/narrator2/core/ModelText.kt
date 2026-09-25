package io.github.clemsix6.narrator2.core

/**
 * The one escape of text that players wrote, or that the model may have copied from them, before it
 * reaches the model: names, chat, SQL cells, Oracle texts. `<` and `>` become `‹` and `›`, so that no
 * such text can pass for a tag of the engine (`<engine>`, `<operator from="…">`, `<events>`…).
 *
 * Nothing else changes: quoting, line breaks and length caps stay with each caller, which knows its format.
 */
object ModelText {
    /** [text] with `<` `>` replaced by `‹` `›`. */
    fun escape(text: String): String = text.replace('<', '‹').replace('>', '›')
}
