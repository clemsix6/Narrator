package io.github.clemsix6.narrator2.memory.stats

import io.github.clemsix6.narrator2.journal.Journal
import io.github.clemsix6.narrator2.journal.JournalUnavailableException
import io.github.clemsix6.narrator2.memory.MemoryDataset
import io.github.clemsix6.narrator2.memory.MemoryDataset.NOW
import io.github.clemsix6.narrator2.memory.MemoryDataset.bartok
import io.github.clemsix6.narrator2.memory.MemoryDataset.day1
import io.github.clemsix6.narrator2.memory.MemoryDataset.day2
import io.github.clemsix6.narrator2.memory.MemoryDataset.kelvin
import io.github.clemsix6.narrator2.memory.MemoryDataset.mimosa
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.Connection
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The refresh the memory's jobs call (M3 §6.1): pairs, then one write job per player, a `stats` decision. */
class StatsRefresherTest {
    @Test
    fun `pairs then each player, one job each, with a decision`() {
        MemoryDataset.journal().use { fixture ->
            val journal = MemoryDataset.Writable(fixture)
            val refresher = StatsRefresher(journal, fixture.clock)
            val stranger = UUID.fromString("00000000-0000-4000-8000-0000000000ff")
            val outcome = runBlocking { refresher.refresh(listOf(bartok.uuid, kelvin.uuid, bartok.uuid, stranger), "timer") }
            assertEquals(StatsRefresher.Outcome(players = 2, failed = 0, pairs = 2, elapsedMs = outcome.elapsedMs), outcome)
            assertEquals(3, journal.writes.get()) // the pairs, Bartok, Kelvin; nothing for the stranger
            val (players, pairs) = MemoryDataset.read(fixture) { connection ->
                fun count(sql: String) = connection.createStatement().use { s -> s.executeQuery(sql).use { it.next(); it.getLong(1) } }
                count("SELECT count(DISTINCT player_uuid) FROM sheet_stats") to count("SELECT count(*) FROM pair_stats")
            }
            assertEquals(2L to 2L, players to pairs)
            val decision = fixture.values("SELECT decision, subject, reason FROM engine_decisions WHERE kind = 'memory'").single()
            assertEquals("stats", decision[0])
            assertEquals("timer", decision[1])
            assertTrue((decision[2] as String).startsWith("2 players, 2 pairs, "), decision[2] as String)
            // The stats are at the refresher's clock: the dataset's NOW.
            assertEquals(NOW, MemoryDataset.read(fixture) { SheetStats.read(it, bartok.uuid) }.getValue("playtime").computedAt)
        }
    }

    @Test
    fun `a failing job is skipped, an unavailable Journal is not`() {
        MemoryDataset.journal().use { fixture ->
            val failing = object : Journal by MemoryDataset.Writable(fixture) {
                var calls = 0

                override suspend fun <T> write(timeoutMs: Long, block: (Connection) -> T): T {
                    if (calls++ == 1) error("disk full") // Bartok's job
                    return MemoryDataset.write(fixture, block)
                }
            }
            val outcome = runBlocking { StatsRefresher(failing, fixture.clock).refresh(listOf(bartok.uuid, mimosa.uuid), "left") }
            assertEquals(1, outcome.players)
            assertEquals(1, outcome.failed)
            assertEquals(2, outcome.pairs)

            val closed = object : Journal by fixture.journal {
                override suspend fun <T> write(timeoutMs: Long, block: (Connection) -> T): T = throw JournalUnavailableException("closing")
            }
            assertThrows<JournalUnavailableException> { runBlocking { StatsRefresher(closed, fixture.clock).refresh(listOf(bartok.uuid), "timer") } }
        }
    }

    @Test
    fun `the players active since a time`() {
        MemoryDataset.journal().use { fixture ->
            val refresher = StatsRefresher(fixture.journal, fixture.clock)
            assertEquals(listOf(bartok.uuid, kelvin.uuid), runBlocking { refresher.activeSince(day2("00:00")) })
            assertEquals(listOf(bartok.uuid, mimosa.uuid, kelvin.uuid), runBlocking { refresher.activeSince(day1("19:00")) })
        }
    }
}
