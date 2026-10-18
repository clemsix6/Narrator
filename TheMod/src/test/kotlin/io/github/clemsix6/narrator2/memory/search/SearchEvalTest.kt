package io.github.clemsix6.narrator2.memory.search

import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.memory.MemoryTags
import io.github.clemsix6.narrator2.memory.RecallPurpose
import io.github.clemsix6.narrator2.memory.RecallQuery
import io.github.clemsix6.narrator2.memory.RefKind
import org.junit.jupiter.api.Test
import java.util.Locale
import java.util.UUID
import kotlin.math.ln
import kotlin.test.assertTrue

/**
 * The search's non-regression test (M3 §10.1, §15.1): the spike's corpus of 68 memories written as the
 * Chronicler will write them and its 30 queries judged by hand (20 questions of the Director, 10 queries
 * built from events), run through the real index and [HybridRecall]. Thresholds: MRR@10 ≥ 0.90 and
 * nDCG@5 ≥ 0.85 over all queries, MRR@10 ≥ 0.85 over the questions.
 *
 * Every memory has the same importance, age and strength, so the ranking is the relevance's. A question
 * is text only; an event query also gives its player, its tags and its game ids, as reminiscences will.
 */
class SearchEvalTest {
    private class Doc(val id: Long, val players: List<String>, val labels: List<String>, val text: String)
    private class Query(val id: String, val kind: String, val player: String, val text: String, val judged: Map<Long, Int>)
    private class Metrics(val mrr: Double, val ndcg: Double, val recall: Double, val p1: Double) {
        override fun toString() = "MRR@10 %.3f · nDCG@5 %.3f · R@5 %.2f · P@1 %.2f".format(Locale.ROOT, mrr, ndcg, recall, p1)
    }

    private fun resource(name: String): List<String> =
        javaClass.getResource("/narrator2/memory/search_eval/$name")!!.readText(Charsets.UTF_8).lines().filter { it.isNotBlank() }

    private val docs = resource("corpus.tsv").map { line ->
        val f = line.split('\t')
        Doc(f[0].toLong(), f[1].split(',').filter { it.isNotEmpty() }, f[2].split(' ').filter { it.isNotEmpty() }, f[3])
    }

    private val queries = resource("queries.tsv").map { line ->
        val f = line.split('\t')
        val judged = f[4].trim().split(' ').associate { it.substringBefore(':').toLong() to it.substringAfter(':').toInt() }
        Query(f[0], f[1], f[2], f[3], judged)
    }

    private fun player(name: String) = PlayerRef(UUID.nameUUIDFromBytes(name.toByteArray()), name)

    private fun SearchFixture.load() {
        docs.flatMap { it.players }.distinct().forEach { player(player(it)) }
        docs.forEach { doc ->
            val (entities, tags) = doc.labels.partition { ':' in it }
            memory(doc.id, title = "", content = doc.text, players = doc.players.map(::player), tags = tags, entities = entities)
        }
    }

    private fun recallQuery(query: Query): RecallQuery {
        if (query.kind == "dir") return RecallQuery(query.text, limit = 10, purpose = RecallPurpose.TOOL)
        val words = query.text.split(' ')
        return RecallQuery(
            query.text,
            players = listOf(player(query.player).uuid),
            tags = words.filter { it in MemoryTags.ALL },
            entities = words.filter { ':' in it },
            limit = 10,
            purpose = RecallPurpose.TOOL,
        )
    }

    private fun metrics(runs: Map<Query, List<Long>>): Metrics {
        var mrr = 0.0
        var ndcg = 0.0
        var recall = 0.0
        var p1 = 0.0
        runs.forEach { (query, run) ->
            val first = run.take(10).indexOfFirst { it in query.judged }
            if (first >= 0) mrr += 1.0 / (first + 1)
            if (run.firstOrNull() in query.judged) p1++
            val dcg = run.take(5).withIndex().sumOf { (i, id) -> gain(query.judged[id] ?: 0) / log2(i + 2.0) }
            val ideal = query.judged.values.sortedDescending().take(5).withIndex().sumOf { (i, g) -> gain(g) / log2(i + 2.0) }
            ndcg += dcg / ideal
            recall += run.take(5).count { it in query.judged }.toDouble() / minOf(5, query.judged.size)
        }
        val n = runs.size
        return Metrics(mrr / n, ndcg / n, recall / n, p1 / n)
    }

    private fun gain(grade: Int): Double = if (grade == 2) 3.0 else grade.toDouble()

    private fun log2(x: Double): Double = ln(x) / ln(2.0)

    @Test
    fun `the spike's corpus and queries pass the thresholds`() {
        SearchFixture().use { fixture ->
            fixture.load()
            val runs = queries.associateWith { query -> fixture.search(recallQuery(query)).map { it.ref.id } }
            val bm25 = queries.associateWith { query ->
                fixture.index.bm25(fixture.connection, FrenchAnalyzer.terms(query.text), listOf(RefKind.MEMORY), 10).keys.map { it.id }
            }
            val all = metrics(runs)
            val questions = metrics(runs.filterKeys { it.kind == "dir" })
            val events = metrics(runs.filterKeys { it.kind == "evt" })
            println("SearchEval hybrid, all:       $all")
            println("SearchEval hybrid, questions: $questions")
            println("SearchEval hybrid, events:    $events")
            println("SearchEval BM25 only, all:    ${metrics(bm25)}")
            println("SearchEval BM25 only, questions: ${metrics(bm25.filterKeys { it.kind == "dir" })}")
            runs.forEach { (query, run) ->
                if (run.firstOrNull() !in query.judged) println("  ${query.id} \"${query.text}\": ${run.take(5)} expected ${query.judged.keys}")
            }
            assertTrue(all.mrr >= 0.90, "MRR@10 over all queries: $all")
            assertTrue(all.ndcg >= 0.85, "nDCG@5 over all queries: $all")
            assertTrue(questions.mrr >= 0.85, "MRR@10 over the questions: $questions")
        }
    }
}
