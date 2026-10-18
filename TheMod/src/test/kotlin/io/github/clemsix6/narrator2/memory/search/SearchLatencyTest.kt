package io.github.clemsix6.narrator2.memory.search

import io.github.clemsix6.narrator2.memory.MemoryTags
import io.github.clemsix6.narrator2.memory.RecallPurpose
import io.github.clemsix6.narrator2.memory.RecallQuery
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import org.junit.jupiter.api.Test
import java.util.Locale
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertTrue

/**
 * The search stays fast as the memory grows (M3 §10.1, §19.2): 20 000 synthetic memories made from the
 * spike's corpus (names swapped, sentences mixed, importances, ages and strengths drawn), indexed through
 * [SearchIndex] like the store does, then [HybridRecall] run end to end (BM25, candidates, score, lines):
 * under 50 ms per search on average. The figures are printed for the report.
 */
class SearchLatencyTest {
    private class Doc(val players: List<String>, val labels: List<String>, val text: String)

    private fun resource(name: String): List<List<String>> =
        javaClass.getResource("/narrator2/memory/search_eval/$name")!!.readText(Charsets.UTF_8).lines().filter { it.isNotBlank() }.map { it.split('\t') }

    private val base = resource("corpus.tsv").map { f -> Doc(f[1].split(',').filter { it.isNotEmpty() }, f[2].split(' ').filter { it.isNotEmpty() }, f[3]) }
    private val questions = resource("queries.tsv").map { f -> Triple(f[1], f[2], f[3]) }
    private val names = listOf("Bartok", "Mimosa", "clemsix6", "Josiane", "Kelvin", "Aldo", "Brume", "Caspar", "Dune", "Esteban")

    private fun uuid(name: String): UUID = UUID.nameUUIDFromBytes(name.toByteArray())

    @Test
    fun `a search over 20 000 memories takes less than 50 ms`() {
        SearchFixture().use { fixture ->
            val random = Random(7)
            val count = 20_000
            val ids = (1L..count).toList()
            val docs = ids.map {
                val a = base[random.nextInt(base.size)]
                val b = base[random.nextInt(base.size)]
                val name = names[random.nextInt(names.size)]
                val text = a.text.replace("Bartok", name).replace("Mimosa", names[random.nextInt(names.size)]) + " " +
                    b.text.split('.', ';').first() + "."
                Doc(listOf(name), a.labels, text)
            }
            names.forEach { fixture.connection.update("INSERT INTO players VALUES (?::UUID, ?, now(), now(), 1, 0, false)", uuid(it).toString(), it) }
            val now = fixture.clock.instant().epochSecond.toDouble()
            fixture.connection.update(
                """
                INSERT INTO memories (id, kind, created_at, occurred_from, occurred_to, title, content, players, entities, tags,
                    importance, valence, stability_h, reinforced_play_s, source, evidence_events, evidence_tools)
                SELECT id, 'episode', to_timestamp(at_s), to_timestamp(at_s), to_timestamp(at_s), '', content, [player::UUID],
                    list_filter(string_split(entities, ' '), x -> x <> ''), list_filter(string_split(tags, ' '), x -> x <> ''),
                    importance, 0, 38.0, reinforced, 'chronicler', [], []
                FROM (SELECT unnest(?::BIGINT[]) AS id, unnest(?::VARCHAR[]) AS content, unnest(?::VARCHAR[]) AS player,
                    unnest(?::VARCHAR[]) AS entities, unnest(?::VARCHAR[]) AS tags, unnest(?::INTEGER[]) AS importance,
                    unnest(?::DOUBLE[]) AS at_s, unnest(?::BIGINT[]) AS reinforced)
                """.trimIndent(),
                fixture.connection.array("BIGINT", ids),
                fixture.connection.array("VARCHAR", docs.map { it.text }),
                fixture.connection.array("VARCHAR", docs.map { uuid(it.players.single()).toString() }),
                fixture.connection.array("VARCHAR", docs.map { d -> d.labels.filter { ':' in it }.joinToString(" ") }),
                fixture.connection.array("VARCHAR", docs.map { d -> d.labels.filter { ':' !in it }.joinToString(" ") }),
                fixture.connection.array("INTEGER", docs.map { random.nextInt(1, 11) }),
                fixture.connection.array("DOUBLE", docs.map { now - random.nextDouble(0.0, 90 * 86_400.0) }),
                fixture.connection.array("BIGINT", docs.map { -random.nextLong(0, 300 * 3_600L) }),
            )

            // The first rows go through SearchIndex one by one, as the store writes them; the rest in bulk, same terms.
            val texts = docs.map { it.text + " " + it.labels.joinToString(" ") }
            fixture.connection.autoCommit = false
            val indexMs = timed {
                (0 until ONE_BY_ONE).forEach { i -> fixture.index.index(fixture.connection, Ref(RefKind.MEMORY, ids[i]), texts[i]) }
                fixture.connection.commit()
            }
            bulkIndex(fixture, ids.drop(ONE_BY_ONE), texts.drop(ONE_BY_ONE))
            val postings = fixture.count("SELECT count(*) FROM search_postings")

            val queries = questions.map { (kind, player, text) ->
                if (kind == "dir") {
                    RecallQuery(text, purpose = RecallPurpose.TOOL)
                } else {
                    val words = text.split(' ')
                    RecallQuery(
                        text, players = listOf(uuid(player)), tags = words.filter { it in MemoryTags.ALL },
                        entities = words.filter { ':' in it }, purpose = RecallPurpose.REMINISCENCE, seed = 42, limit = 2,
                    )
                }
            } + names.map { RecallQuery(null, players = listOf(uuid(it)), purpose = RecallPurpose.BRIEFING, limit = 12) } +
                RecallQuery(null, purpose = RecallPurpose.TOOL)

            queries.forEach { fixture.search(it) } // warm-up
            val byPurpose = HashMap<String, MutableList<Double>>()
            val times = buildList {
                repeat(5) {
                    queries.forEach { query ->
                        val time = timed { fixture.search(query) }
                        add(time)
                        val what = "${query.purpose.name.lowercase()}${if (query.text == null) " (no text)" else ""}"
                        byPurpose.getOrPut(what) { mutableListOf() } += time
                    }
                }
            }.sorted()
            val bm25 = queries.mapNotNull { it.text }.let { texts ->
                buildList {
                    repeat(5) {
                        texts.forEach { text ->
                            add(timed { fixture.index.bm25(fixture.connection, FrenchAnalyzer.terms(text), RefKind.searchable, HybridRecall.BM25_CANDIDATES) })
                        }
                    }
                }
            }
            println("SearchLatency BM25 alone (top %d): average %.1f ms, max %.1f ms".format(Locale.ROOT, HybridRecall.BM25_CANDIDATES, bm25.average(), bm25.max()))
            byPurpose.forEach { (what, list) -> println("SearchLatency %s: average %.1f ms, max %.1f ms".format(Locale.ROOT, what, list.average(), list.max())) }
            val average = times.average()
            val p95 = times[(times.size * 95) / 100]

            val added = (1L..50L).map { Ref(RefKind.MEMORY, count + it) }
            val adding = timed {
                added.forEachIndexed { i, ref -> fixture.index.index(fixture.connection, ref, docs[i].text) }
                fixture.connection.commit()
            }
            val similarity = timed { fixture.index.similarity(fixture.connection, docs[0].text, (1L..20L).map { Ref(RefKind.MEMORY, it) }) }

            println(
                "SearchLatency: %,d memories, %,d postings; %,d indexed one by one in %.0f ms (%.2f ms each); %d searches: average %.1f ms, p95 %.1f ms, max %.1f ms; +50 rows in %.1f ms; similarity against 20 rows %.1f ms"
                    .format(Locale.ROOT, count, postings, ONE_BY_ONE, indexMs, indexMs / ONE_BY_ONE, times.size, average, p95, times.last(), adding, similarity),
            )
            assertTrue(average < 50.0, "average search over $count memories: $average ms")
        }
    }

    /** What [SearchIndex.index] writes for each of [texts], in two statements. */
    private fun bulkIndex(fixture: SearchFixture, ids: List<Long>, texts: List<String>) {
        val lengths = ArrayList<Int>()
        val postingIds = ArrayList<Long>()
        val terms = ArrayList<String>()
        val frequencies = ArrayList<Int>()
        ids.zip(texts).forEach { (id, text) ->
            val analyzed = FrenchAnalyzer.terms(text)
            lengths += analyzed.size
            analyzed.groupingBy { it }.eachCount().forEach { (term, tf) ->
                postingIds += id
                terms += term
                frequencies += tf
            }
        }
        val connection = fixture.connection
        connection.update(
            "INSERT INTO search_docs SELECT unnest(?::BIGINT[]), 'memory', unnest(?::INTEGER[])",
            connection.array("BIGINT", ids), connection.array("INTEGER", lengths),
        )
        connection.update(
            "INSERT INTO search_postings SELECT unnest(?::BIGINT[]), unnest(?::VARCHAR[]), unnest(?::INTEGER[])::SMALLINT",
            connection.array("BIGINT", postingIds), connection.array("VARCHAR", terms), connection.array("INTEGER", frequencies),
        )
        connection.commit()
    }

    private companion object {
        const val ONE_BY_ONE = 1_000
    }

    private inline fun timed(block: () -> Unit): Double {
        val started = System.nanoTime()
        block()
        return (System.nanoTime() - started) / 1e6
    }
}
