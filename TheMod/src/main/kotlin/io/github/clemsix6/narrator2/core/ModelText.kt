package io.github.clemsix6.narrator2.core

/**
 * The one escape of player text on its way to a model (M2 review, finding 27).
 *
 * The engine speaks to Claude in tags: `<events>`, `<new_events>`, `<situation>`, `<heartbeat>`,
 * `<briefing>`, `<chapter_end/>`, `<task>`, `<context/>`, and the notes `<engine>` and `<operator>`.
 * Whatever a player chose is untrusted: their name (`<engine>` is a valid name on an offline server),
 * their chat and prayers, the names they give to items and mobs, the text of books and signs, the
 * cells of their data read back by SQL. None of it may pass for a tag of the engine, nor start a line
 * of its own. So, and only here:
 *
 * - `<` and `>` become `‹` and `›`;
 * - control characters, line breaks included, become spaces ([lines] keeps the line breaks of a text
 *   that is made of lines, such as a report or a summary).
 *
 * Every formatter that puts such text in front of a model goes through [escape] or one of its forms:
 * [name], [quote], [guillemets], [lines], [attribute]. Pure, any thread.
 */
object ModelText {
    /** [text] on one line: `<` `>` replaced by `‹` `›`, control characters and line breaks by spaces. */
    fun escape(text: String): String {
        if (text.none { it == '<' || it == '>' || it.isISOControl() }) return text
        return buildString(text.length) {
            for (c in text) append(escapeChar(c, keepLineBreaks = false))
        }
    }

    /** [text] kept on its lines (a report, a summary): like [escape], but `\n` stays. */
    fun lines(text: String): String {
        if (text.none { it == '<' || it == '>' || (it.isISOControl() && it != '\n') }) return text
        return buildString(text.length) {
            for (c in text) append(escapeChar(c, keepLineBreaks = true))
        }
    }

    /** A player's name, or another name a player chose (an operator's, a mob's name tag), unquoted. */
    fun name(name: String): String = escape(name)

    /**
     * [text] as a quote: cut to [max] characters (code points, `…` included), escaped, then between
     * double quotes with `\` and `"` escaped.
     */
    fun quote(text: String, max: Int): String =
        "\"" + escape(cut(text, max)).replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /**
     * [text] between « », as the engine quotes the Narrator's own words to the Director (`your line « … »`): cut to [max]
     * characters (code points, `…` included), escaped, its own « » turned into ‹ › so that the quote cannot end early.
     */
    fun guillemets(text: String, max: Int = Int.MAX_VALUE): String =
        "« " + escape(cut(text, max)).replace('«', '‹').replace('»', '›') + " »"

    /** [text] inside an attribute of an engine tag (`players_online="…"`): escaped, `&` and `"` as entities. */
    fun attribute(text: String): String = escape(text).replace("&", "&amp;").replace("\"", "&quot;")

    /** [text] cut to [max] characters (code points), the last one being `…` when it is cut. */
    fun cut(text: String, max: Int): String {
        if (text.codePointCount(0, text.length) <= max) return text
        return text.substring(0, text.offsetByCodePoints(0, (max - 1).coerceAtLeast(0))) + "…"
    }

    private fun escapeChar(c: Char, keepLineBreaks: Boolean): Char = when {
        c == '<' -> '‹'
        c == '>' -> '›'
        c == '\n' && keepLineBreaks -> c
        c.isISOControl() -> ' '
        else -> c
    }
}
