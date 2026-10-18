package io.github.clemsix6.narrator2.memory.search

import io.github.clemsix6.narrator2.memory.Indexer
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.memory.SqlArray
import io.github.clemsix6.narrator2.memory.longArray
import io.github.clemsix6.narrator2.memory.query
import io.github.clemsix6.narrator2.memory.stringArray
import io.github.clemsix6.narrator2.memory.update
import java.sql.Connection
import kotlin.math.ln

/**
 * The full-text index of the memory (M3 §10.1, §10.3): BM25 (k1 = 1.2, b = 0.75) computed by DuckDB over
 * `search_docs` (one row per indexed row: its kind and its number of terms) and `search_postings` (one row
 * per term of a row, with its frequency), terms from [FrenchAnalyzer]. The index is written in the same
 * transaction as the row it indexes: nothing to keep in step afterwards. Stateless, any thread; each call
 * runs on the connection it is given.
 */
class SearchIndex : Indexer {
    override fun index(connection: Connection, ref: Ref, text: String) {
        remove(connection, ref)
        val terms = FrenchAnalyzer.terms(text)
        if (terms.isEmpty()) return
        val frequencies = terms.groupingBy { it }.eachCount()
        connection.update("INSERT INTO search_docs (ref_id, kind, len) VALUES (?, ?, ?)", ref.id, ref.kind.id, terms.size)
        connection.update(
            "INSERT INTO search_postings (ref_id, term, tf) SELECT ?, unnest(?::VARCHAR[]), unnest(?::INTEGER[])::SMALLINT",
            ref.id,
            stringArray(frequencies.keys),
            SqlArray("INTEGER", frequencies.values.map { it.coerceAtMost(Short.MAX_VALUE.toInt()) }),
        )
    }

    override fun remove(connection: Connection, ref: Ref) {
        // search_postings has no index: a row that was never indexed costs no scan of it.
        if (connection.update("DELETE FROM search_docs WHERE ref_id = ?", ref.id) > 0) {
            connection.update("DELETE FROM search_postings WHERE ref_id = ?", ref.id)
        }
    }

    override fun similarity(connection: Connection, text: String, candidates: Collection<Ref>): Map<Ref, Double> {
        val zero = candidates.associateWith { 0.0 }
        val terms = FrenchAnalyzer.terms(text)
        if (terms.isEmpty() || candidates.isEmpty()) return zero
        val query = terms.distinct()
        val (count, averageLength) = connection.query("SELECT count(*)::DOUBLE, coalesce(avg(len), 0) FROM search_docs") {
            getDouble(1) to getDouble(2)
        }.single()
        val frequencies = connection.query(
            "SELECT term, count(*)::DOUBLE FROM search_postings WHERE term IN (SELECT unnest(?::VARCHAR[])) GROUP BY term",
            stringArray(query),
        ) { getString(1) to getDouble(2) }.toMap()
        val stats = Stats(count, averageLength.takeIf { it > 0 } ?: terms.size.toDouble())
        val own = terms.groupingBy { it }.eachCount()
        val itself = query.sumOf { stats.weight(it, frequencies, own.getValue(it), terms.size) }

        val byId = candidates.associateBy { it.id }
        val scores = HashMap<Long, Double>()
        connection.query(
            "SELECT p.ref_id, p.term, p.tf, d.len FROM search_postings p JOIN search_docs d USING (ref_id) " +
                "WHERE p.ref_id IN (SELECT unnest(?::BIGINT[])) AND p.term IN (SELECT unnest(?::VARCHAR[]))",
            longArray(byId.keys),
            stringArray(query),
        ) {
            val id = getLong(1)
            scores.merge(id, stats.weight(getString(2), frequencies, getInt(3), getInt(4)), Double::plus)
        }
        return zero + scores.mapKeys { byId.getValue(it.key) }.mapValues { (it.value / itself).coerceAtMost(1.0) }
    }

    /**
     * The [limit] best rows of [kinds] for [terms] (already analyzed), best first, with their BM25 score: the
     * query of M3 §10.3. Archived and closed rows are in it; the caller filters.
     */
    fun bm25(connection: Connection, terms: Collection<String>, kinds: Collection<RefKind>, limit: Int): Map<Ref, Double> {
        if (terms.isEmpty() || kinds.isEmpty() || limit <= 0) return emptyMap()
        val kindIds = kinds.map { it.id }
        return connection.query(
            BM25,
            stringArray(terms.distinct()),
            stringArray(kindIds),
            stringArray(kindIds),
            limit,
        ) { Ref(RefKind.entries.first { it.id == getString(2) }, getLong(1)) to getDouble(3) }.toMap(LinkedHashMap())
    }

    /** The index's statistics: number of rows and average length (in terms). */
    private class Stats(val count: Double, val averageLength: Double) {
        /** The BM25 weight of [term] occurring [tf] times in a row of [length] terms. */
        fun weight(term: String, documentFrequencies: Map<String, Double>, tf: Int, length: Int): Double {
            val df = documentFrequencies[term] ?: 0.0
            val idf = ln(1 + (count - df + 0.5) / (df + 0.5))
            return idf * tf * (K1 + 1) / (tf + K1 * (1 - B + B * length / averageLength))
        }
    }

    private companion object {
        const val K1 = 1.2
        const val B = 0.75

        /** BM25 in SQL (M3 §10.3, measured in the spike): one statement, the terms and kinds as lists. */
        val BM25 = """
            WITH q AS (SELECT DISTINCT unnest(?::VARCHAR[]) AS term),
            n AS (SELECT count(*)::DOUBLE AS n, avg(len) AS avgdl FROM search_docs WHERE kind = ANY(?::VARCHAR[])),
            df AS (SELECT p.term, count(*)::DOUBLE AS df FROM search_postings p SEMI JOIN q USING (term) GROUP BY p.term)
            SELECT p.ref_id, any_value(d.kind), sum(ln(1 + (n.n - df.df + 0.5) / (df.df + 0.5))
                                 * p.tf * 2.2 / (p.tf + 1.2 * (0.25 + 0.75 * d.len / n.avgdl))) AS bm25
            FROM search_postings p JOIN df USING (term) JOIN search_docs d USING (ref_id) CROSS JOIN n
            WHERE d.kind = ANY(?::VARCHAR[])
            GROUP BY p.ref_id ORDER BY bm25 DESC, p.ref_id DESC LIMIT ?
        """.trimIndent()
    }
}
