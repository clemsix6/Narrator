package io.github.clemsix6.narrator2.journal

import io.github.clemsix6.narrator2.config.JournalConfig
import io.github.clemsix6.narrator2.event.BlockBroken
import io.github.clemsix6.narrator2.event.BlockPlaced
import io.github.clemsix6.narrator2.event.DamageTaken
import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.event.ItemsPickedUp
import io.github.clemsix6.narrator2.event.PlaceKind
import io.github.clemsix6.narrator2.event.PlayerJoined
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.event.PositionSampled
import io.github.clemsix6.narrator2.event.SampleReason
import io.github.clemsix6.narrator2.testing.SampleEvents
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Locale
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How fast the Journal writes and how much room a row takes on disk, through the real path: bus,
 * queue, writer thread, appenders, a file database. The Journal must hold hundreds of hours of play
 * (M1_M2 §5.7): the figures are printed (see the test report) and checked against loose floors.
 */
class JournalThroughputTest {
    @TempDir
    lateinit var dir: Path

    private val players = (1..5).map { PlayerRef(UUID(0, it.toLong()), "Player$it") }
    private val blocks = (0 until 60).map { "minecraft:block_$it" }
    private val random = Random(42)
    private val positions = players.associate { it.uuid to intArrayOf(0, 64, 0) }

    /** A mix shaped like play: mostly blocks broken and placed, pickups, damage and positions. */
    private fun playEvent(): GameEvent {
        val player = players[random.nextInt(players.size)]
        val xyz = positions.getValue(player.uuid)
        xyz[0] += random.nextInt(5) - 2
        xyz[1] = 20 + random.nextInt(50)
        xyz[2] += random.nextInt(5) - 2
        val pos = Pos("minecraft:overworld", xyz[0], xyz[1], xyz[2])
        return when (random.nextInt(10)) {
            in 0..3 -> BlockBroken(player, pos, blocks[random.nextInt(blocks.size)], "minecraft:iron_pickaxe", false, random.nextInt(8) != 0, null)
            in 4..5 -> BlockPlaced(player, pos, blocks[random.nextInt(blocks.size)], blocks[random.nextInt(blocks.size)], PlaceKind.BLOCK)
            6 -> ItemsPickedUp(player, pos, blocks[random.nextInt(blocks.size)], 1 + random.nextInt(64), 30)
            7 -> DamageTaken(player, pos, "minecraft:mob_attack", random.nextInt(100) / 10.0, 1, SampleEvents.zombie, false, random.nextInt(20).toDouble())
            else -> PositionSampled(player, pos, "minecraft:plains", null, false, 15, 0, 20.0, 20, null, SampleReason.MOVED)
        }
    }

    @Test
    fun `the Journal writes tens of thousands of events per second, in about 30 bytes each`() {
        val file = dir.resolve(DuckDatabase.FILE_NAME)
        val count = 200_000
        val fixture = JournalFixture(file, config = JournalConfig(queueCapacity = 1_048_576))
        players.forEachIndexed { index, player -> fixture.publish(PlayerJoined(player, SampleEvents.here, index + 1L, false, false)) }
        fixture.sync()

        val start = System.nanoTime()
        repeat(count) {
            if (it % 100 == 0) fixture.clock.advance(Duration.ofSeconds(1))
            fixture.gameTime += 1
            fixture.publish(playEvent())
        }
        val published = System.nanoTime()
        fixture.sync()
        val written = System.nanoTime()
        val perSecond = count / ((written - start) / 1e9)
        assertEquals(0L, fixture.journal.health().droppedEvents)
        assertEquals(count.toLong() + players.size, fixture.count("events") + fixture.count("positions"))

        // Every sample type too, for the slower paths (statements, JSON, lists).
        val mixedStart = System.nanoTime()
        repeat(200) { SampleEvents.all.forEach { event -> fixture.publish(event) } }
        fixture.sync()
        val mixedPerSecond = 200 * SampleEvents.all.size / ((System.nanoTime() - mixedStart) / 1e9)

        fixture.close()
        val bytes = Files.size(file)
        val rows = count + players.size + 200 * SampleEvents.all.size
        println(
            "Journal throughput: %,d play events published in %d ms, all written in %d ms: %,.0f events/s; every type: %,.0f events/s"
                .format(Locale.ROOT, count, (published - start) / 1_000_000, (written - start) / 1_000_000, perSecond, mixedPerSecond),
        )
        println("Journal size: %.1f MB for %,d rows after the checkpoint: %.1f bytes per row".format(Locale.ROOT, bytes / 1e6, rows, bytes.toDouble() / rows))
        assertTrue(perSecond > 10_000, "play events: $perSecond events/s")
        assertTrue(mixedPerSecond > 5_000, "every type: $mixedPerSecond events/s")
        assertTrue(bytes.toDouble() / rows < 80, "${bytes.toDouble() / rows} bytes per row")
    }
}
