package io.github.clemsix6.narrator2.memory.search

import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The full-text index (M3 §10.3): postings in step with the rows, BM25 in DuckDB, and the similarity of the dedup. */
class SearchIndexTest {
    private val lava = Ref(RefKind.MEMORY, 1)
    private val fall = Ref(RefKind.MEMORY, 2)
    private val promise = Ref(RefKind.CANON, 3)

    private fun SearchFixture.postings(ref: Ref): Map<String, Int> =
        connection.rows("SELECT term, tf FROM search_postings WHERE ref_id = ${ref.id}") { getString(1) to getInt(2) }.toMap()

    private fun SearchFixture.doc(ref: Ref): Pair<String, Int>? =
        connection.rows("SELECT kind, len FROM search_docs WHERE ref_id = ${ref.id}") { getString(1) to getInt(2) }.singleOrNull()

    private fun SearchFixture.filled() {
        index.index(connection, lava, "Bartok est mort dans la lave du Nether, la lave l'a englouti. death lava minecraft:lava")
        index.index(connection, fall, "Mimosa est tombée d'une falaise du bosquet de cerisiers. death fall")
        index.index(connection, promise, "Le Narrateur a promis à Mimosa de lui rendre ses bottes. promise")
    }

    @Test
    fun `a row is indexed with its kind, its length and its term frequencies`() {
        SearchFixture().use { fixture ->
            fixture.filled()
            assertEquals("memory" to FrenchAnalyzer.terms("Bartok est mort dans la lave du Nether, la lave l'a englouti. death lava minecraft:lava").size, fixture.doc(lava))
            assertEquals("canon", fixture.doc(promise)?.first)
            val postings = fixture.postings(lava)
            assertEquals(2, postings["lave"], "two times lave")
            assertEquals(3, postings["lava"], "the tag, the id and its part")
            assertEquals(1, postings["bartok"])
        }
    }

    @Test
    fun `indexing again replaces, removing forgets, and a row never indexed is harmless`() {
        SearchFixture().use { fixture ->
            fixture.filled()
            fixture.index.index(fixture.connection, lava, "Bartok a dompté un cheval")
            assertEquals(setOf("bartok", "dompt", "cheval"), fixture.postings(lava).keys, "the old terms are gone")
            assertEquals("memory" to 3, fixture.doc(lava))
            assertEquals(3, fixture.count("SELECT count(*) FROM search_docs").toInt())

            fixture.index.remove(fixture.connection, lava)
            assertEquals(null, fixture.doc(lava))
            assertEquals(emptyMap(), fixture.postings(lava))
            fixture.index.remove(fixture.connection, Ref(RefKind.MEMORY, 99))
            assertEquals(2, fixture.count("SELECT count(*) FROM search_docs").toInt())

            fixture.index.index(fixture.connection, Ref(RefKind.MEMORY, 4), "le la les de")
            assertEquals(2, fixture.count("SELECT count(*) FROM search_docs").toInt(), "nothing to index: no row")
        }
    }

    @Test
    fun `bm25 ranks the rows that match best and keeps to the kinds asked`() {
        SearchFixture().use { fixture ->
            fixture.filled()
            val terms = FrenchAnalyzer.terms("Mimosa est tombée")
            val ranked = fixture.index.bm25(fixture.connection, terms, RefKind.searchable, 10)
            assertEquals(listOf(fall, promise), ranked.keys.toList(), "$ranked")
            assertTrue(ranked.values.zipWithNext().all { (a, b) -> a >= b })
            assertEquals(listOf(promise), fixture.index.bm25(fixture.connection, terms, listOf(RefKind.CANON), 10).keys.toList())
            assertEquals(listOf(fall), fixture.index.bm25(fixture.connection, terms, RefKind.searchable, 1).keys.toList())
            assertEquals(emptyMap(), fixture.index.bm25(fixture.connection, emptyList(), RefKind.searchable, 10))
            assertEquals(emptyMap(), fixture.index.bm25(fixture.connection, listOf("dragon"), RefKind.searchable, 10))
        }
    }

    @Test
    fun `similarity is about 1 for the same words, 0 for nothing in common`() {
        SearchFixture().use { fixture ->
            fixture.filled()
            val text = "Le Narrateur a promis à Mimosa de lui rendre ses bottes. promise"
            val unknown = Ref(RefKind.CANON, 77)
            val similarity = fixture.index.similarity(fixture.connection, text, listOf(promise, lava, fall, unknown))
            assertEquals(1.0, similarity.getValue(promise), 1e-9)
            assertEquals(0.0, similarity.getValue(lava))
            assertTrue(similarity.getValue(fall) in 0.01..0.4, "Mimosa only: ${similarity.getValue(fall)}")
            assertEquals(0.0, similarity.getValue(unknown), "not indexed")

            val reworded = fixture.index.similarity(fixture.connection, "Le Narrateur a promis de rendre ses bottes à Mimosa demain", listOf(promise))
            assertTrue(reworded.getValue(promise) >= 0.6, "the same promise, reworded: $reworded")
            assertEquals(mapOf(promise to 0.0), fixture.index.similarity(fixture.connection, "le la les", listOf(promise)))
            assertEquals(emptyMap(), fixture.index.similarity(fixture.connection, text, emptyList()))
        }
    }

    @Test
    fun `similarity works on an empty index`() {
        SearchFixture().use { fixture ->
            assertEquals(mapOf(lava to 0.0), fixture.index.similarity(fixture.connection, "la lave", listOf(lava)))
        }
    }
}
