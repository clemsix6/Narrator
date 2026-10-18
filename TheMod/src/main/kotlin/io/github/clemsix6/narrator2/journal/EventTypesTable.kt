package io.github.clemsix6.narrator2.journal

import io.github.clemsix6.narrator2.event.EventCatalog
import io.github.clemsix6.narrator2.event.EventTable
import io.github.clemsix6.narrator2.event.EventType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The `event_types` table (M3 §13): what each `events.type` means and what it stores in `subject`,
 * `amount`, `other` and `detail`, from [EventCatalog]. Reference data the model queries when it needs it,
 * instead of reading the whole catalog in the `sql` tool's description.
 *
 * Its `detail` is a JSON object with the keys of `events.detail` for that type, each with its meaning:
 * `(detail ->> 'placed_by')` reads the same key in both tables.
 *
 * Recreated by [DuckDatabase] at every opening, right after the views: like them, it follows the code. Only
 * the types of the `events` table are listed; the hidden tables' types never are.
 */
object EventTypesTable {
    const val NAME = "event_types"

    /** Read back by `SchemaDoc` as the table's line in the `sql` tool's description. */
    const val COMMENT = "What each events.type means and stores in subject, amount, other and detail"

    private val COLUMNS = listOf(
        "type" to "the value of events.type",
        "summary" to "what happened",
        "subject" to "what events.subject holds; NULL: unused",
        "amount" to "what events.amount holds; NULL: unused",
        "other" to "what events.other holds; NULL: unused",
        "detail" to "the keys of events.detail, each with what it holds; NULL: no detail",
    )

    /** The statements that recreate the table with its rows and comments, to run in one transaction. */
    fun sql(): String = buildString {
        append("CREATE OR REPLACE TABLE $NAME (type VARCHAR, summary VARCHAR, subject VARCHAR, amount VARCHAR, other VARCHAR, detail JSON);\n")
        append("INSERT INTO $NAME VALUES\n")
        append(EventCatalog.types(EventTable.EVENTS).joinToString(",\n") { row(it) }).append(";\n")
        append("COMMENT ON TABLE $NAME IS ${literal(COMMENT)};\n")
        COLUMNS.forEach { (column, comment) -> append("COMMENT ON COLUMN $NAME.$column IS ${literal(comment)};\n") }
    }

    /** `('block_break', 'A player broke a block.', 'block', NULL, 'tool in the main hand…', '{"creative":"…",…}')`. */
    private fun row(type: EventType): String {
        val detail = type.detail.takeIf { it.isNotEmpty() }?.let { keys -> JsonObject(keys.mapValues { JsonPrimitive(it.value) }).toString() }
        return listOf(type.name, type.summary, type.subject, type.amount, type.other, detail).joinToString(", ", "(", ")", transform = ::literal)
    }

    /** A SQL string literal; the texts come from the code ([EventCatalog]), never from a player. */
    private fun literal(text: String?): String = if (text == null) "NULL" else "'" + text.replace("'", "''") + "'"
}
