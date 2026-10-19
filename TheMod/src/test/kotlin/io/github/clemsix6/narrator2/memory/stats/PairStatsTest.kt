package io.github.clemsix6.narrator2.memory.stats

import io.github.clemsix6.narrator2.event.PlayerJoined
import io.github.clemsix6.narrator2.event.PositionSampled
import io.github.clemsix6.narrator2.event.SampleReason
import io.github.clemsix6.narrator2.journal.Journal
import io.github.clemsix6.narrator2.journal.JournalFixture
import io.github.clemsix6.narrator2.memory.MemoryDataset
import io.github.clemsix6.narrator2.memory.MemoryDataset.NOW
import io.github.clemsix6.narrator2.memory.MemoryDataset.bartok
import io.github.clemsix6.narrator2.memory.MemoryDataset.kelvin
import io.github.clemsix6.narrator2.memory.MemoryDataset.mimosa
import io.github.clemsix6.narrator2.memory.MemoryDataset.ow
import io.github.clemsix6.narrator2.memory.stats.PairStats.PlayerPair
import io.github.clemsix6.narrator2.testing.TestClock
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pairs of players (M3 §6.7): minutes within 32 blocks in the same dimension, PvP kills and griefs each way. */
class PairStatsTest {
    @Test
    fun `minutes together, the dimension and distance, kills and griefs each way`() {
        MemoryDataset.journal().use { fixture ->
            fun refresh(now: java.time.Instant) {
                val pairs = MemoryDataset.read(fixture) { PairStats.compute(it, now) }
                MemoryDataset.write(fixture) { PairStats.write(it, pairs) }
            }
            refresh(NOW)
            assertEquals(
                listOf(
                    // Day 2: Kelvin 20 blocks from Bartok from 20:10 to 21:00.
                    PlayerPair(bartok.uuid, kelvin.uuid, 50.0, 50.0, 0, 0, 0, 0, NOW),
                    // Day 1: Mimosa 14 blocks from Bartok from 18:10 until he goes to the Nether (18:45, the minute before
                    // counts him there), then from his return (19:00) until she walks away (19:15): 34 + 15 minutes, more than
                    // 7 days ago. They killed each other once; he broke her planks, she broke two of his cobblestones.
                    PlayerPair(bartok.uuid, mimosa.uuid, 49.0, 0.0, 1, 1, 1, 2, NOW),
                ),
                MemoryDataset.read(fixture) { PairStats.read(it) },
            )
            assertEquals(listOf(kelvin.uuid), MemoryDataset.read(fixture) { PairStats.read(it, kelvin.uuid) }.map { it.b })
            // Again later: the totals stay, the 7-day window moves (Bartok and Kelvin still online, 10 minutes more).
            val later = NOW.plusSeconds(600)
            refresh(later)
            val pairs = MemoryDataset.read(fixture) { PairStats.read(it) }
            assertEquals(listOf(60.0 to 60.0, 49.0 to 0.0), pairs.map { it.minutesTogether to it.minutesTogether7d })
        }
    }

    /**
     * M3-05's promise, broken past a few hundred pairs (FLAKY-2: 1 506 pairs on the GameTests' server held the writer
     * 2 s in one job, a row at a time): the pairs are written in jobs of [PairStats.CHUNK], each well within 100 ms.
     */
    @Test
    fun `1 500 pairs are written in short jobs, none holding the writer 100 ms`() = runBlocking<Unit> {
        JournalFixture().use { fixture ->
            val now = Instant.parse("2026-10-03T20:00:00Z")
            val pairs = (0 until 1_500).map { i -> PlayerPair(UUID(0, 2L * i), UUID(0, 2L * i + 1), i.toDouble(), 1.0, 1, 0, 2, 0, now) }
            val jobs = mutableListOf<Long>()
            val timed = object : Journal by fixture.journal {
                override suspend fun <T> write(timeoutMs: Long, block: (Connection) -> T): T {
                    val started = System.nanoTime()
                    return fixture.journal.write(timeoutMs, block).also { jobs += (System.nanoTime() - started) / 1_000_000 }
                }
            }
            PairStats.store(timed, pairs, 10_000)

            assertEquals(1_500 / PairStats.CHUNK, jobs.size, "one job per ${PairStats.CHUNK} pairs")
            assertTrue(jobs.all { it < 100 }, "each job within 100 ms: $jobs ms")
            assertEquals(pairs.sortedByDescending { it.minutesTogether }, MemoryDataset.read(fixture) { PairStats.read(it) })
        }
    }

    /**
     * Players far from each other, as in the GameTests (structures ±9 million blocks away): the squared distance
     * overflowed 32-bit integers past 46 340 blocks, and the whole query failed.
     */
    @ParameterizedTest
    @ValueSource(ints = [40_000, 9_000_000])
    fun `players far apart are compared without overflow`(distance: Int) {
        val start = Instant.parse("2026-10-03T20:00:00Z")
        JournalFixture(clock = TestClock(start)).use { fixture ->
            // Kelvin 10 blocks from Bartok, far out on both axes; Mimosa as far out on the other side.
            listOf(bartok to ow(distance, 64, distance), kelvin to ow(distance + 10, 64, distance), mimosa to ow(-distance, 64, -distance))
                .forEachIndexed { i, (player, pos) ->
                    fixture.publish(PlayerJoined(player, pos, sessionId = i + 1L, firstTime = true, isOp = false))
                    fixture.publish(PositionSampled(player, pos, "minecraft:plains", null, false, 15, 0, 20.0, 20, null, SampleReason.JOIN))
                }
            val now = start.plusSeconds(600)
            assertEquals(
                listOf(PlayerPair(bartok.uuid, kelvin.uuid, 10.0, 10.0, 0, 0, 0, 0, now)),
                MemoryDataset.read(fixture) { PairStats.compute(it, now) },
            )
        }
    }
}
