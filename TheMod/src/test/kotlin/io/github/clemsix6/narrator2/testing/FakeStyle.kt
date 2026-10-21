package io.github.clemsix6.narrator2.testing

import io.github.clemsix6.narrator2.style.AtRest
import io.github.clemsix6.narrator2.style.ComicRegister
import io.github.clemsix6.narrator2.style.Diversity
import io.github.clemsix6.narrator2.style.LineLabels
import io.github.clemsix6.narrator2.style.RestingOpener
import io.github.clemsix6.narrator2.style.StyleWatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A style watch for tests (M6 §12.1): it gives back what the test set and records what it was given. [labels] keeps
 * every [labeled] call; [openers] maps a line's text to the opener at rest it meets (none by default); [lines] are the
 * Director's lines [recentLines] picks from, by their id; the rest answers [atRest], [situation], [candidates],
 * [leastUsed], [last] and [measured] as set. Thread-safe enough for tests.
 */
class FakeStyle : StyleWatch {
    val labels = CopyOnWriteArrayList<LineLabels>()
    val openers = ConcurrentHashMap<String, RestingOpener>()

    /** The Director's lines by id, in the order they were said. */
    val lines = CopyOnWriteArrayList<Pair<Long, String>>()

    @Volatile
    var atRest: AtRest = AtRest.NONE

    @Volatile
    var situation: String? = null

    @Volatile
    var candidates: List<String> = emptyList()

    @Volatile
    var leastUsed: List<ComicRegister> = ComicRegister.VOICE

    @Volatile
    var last: List<ComicRegister> = emptyList()

    @Volatile
    var measured: Diversity = Diversity.NONE

    override fun labeled(labels: LineLabels) {
        this.labels += labels
    }

    override fun crutchCandidates(): List<String> = candidates

    override fun recentLines(beforeLineId: Long, count: Int): List<String> =
        lines.filter { it.first < beforeLineId }.takeLast(count).map { it.second }

    override fun restingOpener(text: String): RestingOpener? = openers[text]

    override fun atRest(): AtRest = atRest

    override fun situationLine(now: Long): String? = situation

    override fun leastUsedRegisters(count: Int): List<ComicRegister> = leastUsed.take(count)

    override fun lastRegisters(count: Int): List<ComicRegister> = last.take(count)

    override fun diversity(): Diversity = measured
}
