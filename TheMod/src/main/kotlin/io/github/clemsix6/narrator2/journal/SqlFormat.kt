package io.github.clemsix6.narrator2.journal

import io.github.clemsix6.narrator2.core.ModelText
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.duckdb.DuckDBStruct
import org.duckdb.JsonNode
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * The compact text of a query result, as the model and operators read it (M1_M2 §6.2):
 *
 * ```
 * now 09-25 21:44:10 · 3 rows · 12 ms
 * player|diamonds|last_seen
 * Bartok|41|09-25 21:40:02
 * Mimosa|3|09-24 22:10:45
 * clemsix6|0|NULL
 * ```
 *
 * Columns are separated by `|` without alignment; `|` and line breaks inside a value become `¦` and `⏎`.
 * Cells and column names often hold what players wrote (chat, names, the values a `PIVOT` turns into
 * columns): `<` `>` become `‹` `›` ([ModelText.escape]), so that none can pass for a tag of the engine.
 * Timestamps are in the Journal's time zone, `MM-dd HH:mm:ss`, with the year when it is not the current
 * one; the header gives `now` so that the reader can work out how long ago. Numbers keep at most two
 * decimals, JSON is compacted, `NULL` is written as is. A cell longer than [SqlLimits.maxCellChars] is
 * cut with `…(+N)`; the whole text stays within [SqlLimits.maxChars], cut at the end of a row, and a
 * footer says when rows are missing.
 */
internal object SqlFormat {
    /** Rows are counted up to this many; beyond, the total is unknown and shown as `>10000`. */
    const val COUNT_LIMIT = 10_000L

    private val SAME_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
    private val OTHER_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    /** Room kept for the footer when rows must be left out. */
    private const val FOOTER_RESERVE = 80

    /**
     * The text of a result and the number of rows it shows.
     *
     * @property shown rows in [text]: fewer than read when [SqlLimits.maxChars] cut the text.
     */
    data class Rendered(val text: String, val shown: Int)

    /**
     * Where a value is shown: timestamps are converted to [zone], and written without their year in
     * [currentYear].
     */
    data class Context(val zone: ZoneId, val currentYear: Int) {
        companion object {
            fun at(now: Instant, zone: ZoneId) = Context(zone, now.atZone(zone).year)
        }
    }

    /**
     * The text of a result: header, column names, [rows] (cells already made by [cell]) and a footer
     * when rows are missing.
     *
     * @param total rows the query returned; null when more than [COUNT_LIMIT].
     */
    fun render(
        columns: List<String>,
        rows: List<List<String>>,
        total: Long?,
        now: Instant,
        zone: ZoneId,
        elapsedMs: Long,
        limits: SqlLimits,
    ): Rendered {
        val count = total?.let { if (it == 1L) "1 row" else "$it rows" } ?: ">$COUNT_LIMIT rows"
        val head = "now ${time(now.atZone(zone).toLocalDateTime(), Context.at(now, zone))} · $count · $elapsedMs ms\n" +
            columns.joinToString("|") { escape(it, limits.maxCellChars) }
        val lines = rows.map { it.joinToString("|") }

        val complete = total != null && rows.size.toLong() == total
        if (complete && head.length + lines.sumOf { it.length + 1 } <= limits.maxChars) {
            return Rendered((listOf(head) + lines).joinToString("\n"), rows.size)
        }
        val text = StringBuilder(head)
        var shown = 0
        for (line in lines) {
            if (text.length + 1 + line.length > limits.maxChars - FOOTER_RESERVE) break
            text.append('\n').append(line)
            shown++
        }
        text.append('\n').append("$shown of ${total ?: ">$COUNT_LIMIT"} rows shown — aggregate or add LIMIT")
        return Rendered(text.toString(), shown)
    }

    /** One cell: [value] as read from JDBC, written compactly, escaped, cut to [maxChars]. */
    fun cell(value: Any?, context: Context, maxChars: Int): String = escape(plain(value, context), maxChars)

    /** [value] written compactly, not escaped: lists and structures use it for their items. */
    private fun plain(value: Any?, context: Context): String = when (value) {
        null -> "NULL"
        is String -> value
        is Double -> number(value)
        is Float -> if (value.isNaN() || value.isInfinite()) number(value.toDouble()) else decimal(BigDecimal(value.toString()))
        is BigDecimal -> decimal(value)
        is OffsetDateTime -> time(value.atZoneSameInstant(context.zone).toLocalDateTime(), context)
        is ZonedDateTime -> time(value.withZoneSameInstant(context.zone).toLocalDateTime(), context)
        is Instant -> time(value.atZone(context.zone).toLocalDateTime(), context)
        is java.sql.Timestamp -> time(value.toLocalDateTime(), context)
        is LocalDateTime -> time(value, context)
        is LocalDate -> value.toString()
        is java.sql.Date -> value.toLocalDate().toString()
        is LocalTime -> TIME.format(value)
        is JsonNode -> compactJson(value.toString())
        is java.sql.Array -> (value.array as Array<*>).joinToString(", ", "[", "]") { plain(it, context) }
        is DuckDBStruct -> value.map.entries.joinToString(", ", "{", "}") { (key, item) -> "$key: ${plain(item, context)}" }
        is Map<*, *> -> value.entries.joinToString(", ", "{", "}") { (key, item) -> "${plain(key, context)}: ${plain(item, context)}" }
        is java.sql.Blob -> "(${value.length()} bytes)"
        else -> value.toString()
    }

    /** At most two decimals, no trailing zeros: `0.67`, `1.5`, `12`. */
    private fun number(value: Double): String = when {
        value.isNaN() -> "NaN"
        value == Double.POSITIVE_INFINITY -> "inf"
        value == Double.NEGATIVE_INFINITY -> "-inf"
        else -> decimal(BigDecimal.valueOf(value))
    }

    private fun decimal(value: BigDecimal): String {
        val rounded = if (value.scale() > 2) value.setScale(2, RoundingMode.HALF_UP) else value
        val stripped = rounded.stripTrailingZeros()
        return if (stripped.signum() == 0) "0" else stripped.toPlainString()
    }

    private fun time(value: LocalDateTime, context: Context): String =
        (if (value.year == context.currentYear) SAME_YEAR else OTHER_YEAR).format(value)

    private fun compactJson(text: String): String = try {
        Json.parseToJsonElement(text).toString()
    } catch (_: SerializationException) {
        text
    }

    /** `|`, line breaks and `<` `>` replaced, then cut to [maxChars] with `…(+N)`, never inside a character. */
    private fun escape(text: String, maxChars: Int): String {
        val flat = ModelText.escape(text.replace("\r\n", "⏎").replace('\n', '⏎').replace('\r', '⏎').replace('|', '¦'))
        if (flat.length <= maxChars) return flat
        val end = if (Character.isHighSurrogate(flat[maxChars - 1])) maxChars - 1 else maxChars
        return flat.substring(0, end) + "…(+${flat.length - end})"
    }
}
