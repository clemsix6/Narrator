package io.github.clemsix6.narrator2.memory

import java.sql.Connection
import java.time.Instant

/**
 * The versioned documents (`memory_docs`, M3 §3.3): the Grimoire, the review and the written sections of
 * the player sheets. Append only: a write is a new version, the current one is the highest, and a
 * rollback copies an older version into a new one.
 */
internal object MemoryDocs {
    private const val COLUMNS = "key, version, content, evidence, written_at, source, restored_from"

    /** The `row_key` of a version in `memory_log`: `grimoire:12`. */
    fun rowKey(key: String, version: Int): String = "$key:$version"

    /** Writes [content] as the next version of [key] and returns it. */
    fun write(
        connection: Connection,
        key: String,
        content: String,
        evidence: List<Ref>,
        at: Instant,
        author: Author,
        restoredFrom: Int? = null,
    ): MemoryDoc {
        val version = latestVersion(connection, key) + 1
        connection.update(
            "INSERT INTO memory_docs ($COLUMNS, run_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            key, version, content, longArray(evidence.map { it.id }), at, author.source.id, restoredFrom, author.runId,
        )
        return read(connection, key, version) ?: error("$key version $version was not written")
    }

    /** The highest version of [key]; 0 when it has none. */
    fun latestVersion(connection: Connection, key: String): Int =
        connection.query("SELECT coalesce(max(version), 0) FROM memory_docs WHERE key = ?", key) { getInt(1) }.single()

    /** Version [version] of [key], the current one when null; null when there is none. */
    fun read(connection: Connection, key: String, version: Int? = null): MemoryDoc? {
        val wanted = version ?: latestVersion(connection, key).takeIf { it > 0 } ?: return null
        return docs(connection, "key = ? AND version = ?", key, wanted).singleOrNull()
    }

    /** Every version of [key], newest first. */
    fun versions(connection: Connection, key: String): List<MemoryDoc> = docs(connection, "key = ?", key)

    private fun docs(connection: Connection, where: String, vararg parameters: Any?): List<MemoryDoc> {
        val rows = connection.query("SELECT $COLUMNS FROM memory_docs WHERE $where ORDER BY version DESC", *parameters) {
            MemoryDoc(
                key = getString(1), version = getInt(2), content = getString(3), evidence = emptyList(), writtenAt = instant(5),
                source = enumOfId<Source>(getString(6)) ?: error("unknown source ${getString(6)}"), restoredFrom = intOrNull(7),
            ) to longs(4)
        }
        val refs = connection.refsOf(rows.flatMap { it.second })
        return rows.map { (doc, ids) -> doc.copy(evidence = ids.mapNotNull(refs::get)) }
    }
}
