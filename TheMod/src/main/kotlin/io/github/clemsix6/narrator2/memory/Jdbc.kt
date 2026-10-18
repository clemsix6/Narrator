package io.github.clemsix6.narrator2.memory

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/*
 * The memory's one JDBC plumbing (M3 §1.1): every statement of the memory (the store, the search, the
 * hygiene, the play clock, the stats, the jobs) is prepared here with a time limit, its parameters bound,
 * its result set closed. Each function runs on the connection it is given: the writer's inside a
 * `Journal.write` job, a read connection otherwise.
 */

/** Seconds a statement may run, unless its caller gives another limit. */
internal const val STATEMENT_TIMEOUT_SECONDS = 10

/** A list bound as a typed SQL array: a Kotlin list does not know its element type, so it is said here. */
internal class SqlArray(val type: String, val values: List<Any>)

internal fun uuidArray(values: Collection<UUID>) = SqlArray("UUID", values.toList())

internal fun longArray(values: Collection<Long>) = SqlArray("BIGINT", values.toList())

internal fun stringArray(values: Collection<String>) = SqlArray("VARCHAR", values.toList())

/** [sql] prepared with [parameters] bound: an [Instant] as UTC, a [SqlArray] as an array, anything else as it is. */
private fun Connection.prepare(sql: String, parameters: Array<out Any?>, timeoutSeconds: Int): PreparedStatement {
    val statement = prepareStatement(sql)
    try {
        statement.queryTimeout = timeoutSeconds
        parameters.forEachIndexed { index, value ->
            val parameter = index + 1
            when (value) {
                null -> statement.setObject(parameter, null)
                is Instant -> statement.setObject(parameter, OffsetDateTime.ofInstant(value, ZoneOffset.UTC))
                is SqlArray -> statement.setArray(parameter, createArrayOf(value.type, value.values.toTypedArray()))
                else -> statement.setObject(parameter, value)
            }
        }
    } catch (e: Exception) {
        statement.close()
        throw e
    }
    return statement
}

/** Every row of [sql] with [parameters], each read by [row], within [timeoutSeconds]. */
internal fun <T> Connection.query(
    sql: String,
    vararg parameters: Any?,
    timeoutSeconds: Int = STATEMENT_TIMEOUT_SECONDS,
    row: ResultSet.() -> T,
): List<T> = prepare(sql, parameters, timeoutSeconds).use { statement ->
    statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.row()) } }
}

/** Runs [sql] with [parameters] within [timeoutSeconds]; returns the number of rows changed. */
internal fun Connection.update(sql: String, vararg parameters: Any?, timeoutSeconds: Int = STATEMENT_TIMEOUT_SECONDS): Int =
    prepare(sql, parameters, timeoutSeconds).use { it.executeUpdate() }

// ── Columns ─────────────────────────────────────────────────────────────────────────────────────────

internal fun ResultSet.instantOrNull(column: Int): Instant? = when (val value = getObject(column)) {
    null -> null
    is OffsetDateTime -> value.toInstant()
    is Timestamp -> value.toInstant()
    is Instant -> value
    else -> error("column $column is not a timestamp: ${value.javaClass}")
}

internal fun ResultSet.instant(column: Int): Instant = instantOrNull(column) ?: error("column $column is null")

internal fun ResultSet.longOrNull(column: Int): Long? = getLong(column).takeUnless { wasNull() }

internal fun ResultSet.intOrNull(column: Int): Int? = getInt(column).takeUnless { wasNull() }

internal fun ResultSet.doubleOrNull(column: Int): Double? = getDouble(column).takeUnless { wasNull() }

/** A `UUID` column, or its text. */
internal fun ResultSet.uuid(column: Int): UUID = getObject(column).let { it as? UUID ?: UUID.fromString(it.toString()) }

private fun ResultSet.list(column: Int): List<Any> = (getArray(column)?.array as Array<*>?)?.filterNotNull().orEmpty()

/** A `UUID[]` column, or its text (`players::VARCHAR[]`). */
internal fun ResultSet.uuids(column: Int): List<UUID> = list(column).map { it as? UUID ?: UUID.fromString(it.toString()) }

internal fun ResultSet.longs(column: Int): List<Long> = list(column).map { (it as Number).toLong() }

internal fun ResultSet.strings(column: Int): List<String> = list(column).map { it.toString() }
