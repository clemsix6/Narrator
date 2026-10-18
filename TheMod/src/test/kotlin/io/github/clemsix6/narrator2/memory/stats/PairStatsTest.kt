package io.github.clemsix6.narrator2.memory.stats

import io.github.clemsix6.narrator2.memory.MemoryDataset
import io.github.clemsix6.narrator2.memory.MemoryDataset.NOW
import io.github.clemsix6.narrator2.memory.MemoryDataset.bartok
import io.github.clemsix6.narrator2.memory.MemoryDataset.kelvin
import io.github.clemsix6.narrator2.memory.MemoryDataset.mimosa
import io.github.clemsix6.narrator2.memory.stats.PairStats.PlayerPair
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

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
}
