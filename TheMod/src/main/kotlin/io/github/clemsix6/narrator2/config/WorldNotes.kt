package io.github.clemsix6.narrator2.config

import io.github.clemsix6.narrator2.core.ModelText

/**
 * The operator's notes on this server (`worldNotes`, M7-NOTES): what the models cannot guess, such as the other mods
 * installed and what they change ("a grave keeps a dead player's inventory"). Written by the operator, in any language;
 * shown as they are, one a line, in `<server_notes>`: in the Director's briefing, next to `<world/>`, and in the first
 * message of the armorer's and the storyteller's tasks. Read when used: `/narrator reload` applies a change.
 */
object WorldNotes {
    const val MAX_NOTES = 12
    const val NOTE_MAX = 300

    private const val OPEN = "<server_notes>\n"
    private const val CLOSE = "</server_notes>"

    /** The longest block [render] writes, and the line break after it. */
    const val BLOCK_MAX = OPEN.length + MAX_NOTES * (NOTE_MAX + 1) + CLOSE.length + 1

    /** What is wrong with [notes], one English sentence each; the notes themselves are never quoted. */
    fun problems(notes: List<String>): List<String> = buildList {
        if (notes.size > MAX_NOTES) add("worldNotes holds at most $MAX_NOTES notes (got ${notes.size})")
        notes.forEachIndexed { index, note ->
            if (note.isBlank()) add("worldNotes[$index] must not be empty")
            if (note.length > NOTE_MAX) add("worldNotes[$index] must be at most $NOTE_MAX characters (got ${note.length})")
        }
    }

    /**
     * `<server_notes>` with one note a line, escaped ([ModelText.escape]: a note neither closes the block nor spans
     * lines); null without any note.
     */
    fun render(notes: List<String>): String? {
        val lines = notes.map { ModelText.escape(it).trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        return lines.joinToString("\n", OPEN, "\n$CLOSE")
    }
}
