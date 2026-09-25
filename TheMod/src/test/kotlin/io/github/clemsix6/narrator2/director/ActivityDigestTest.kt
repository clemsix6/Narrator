package io.github.clemsix6.narrator2.director

import io.github.clemsix6.narrator2.event.BiomeEntered
import io.github.clemsix6.narrator2.event.BlockBroken
import io.github.clemsix6.narrator2.event.BlockPlaced
import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.event.ChatMessage
import io.github.clemsix6.narrator2.event.DamageTaken
import io.github.clemsix6.narrator2.event.EntityKilled
import io.github.clemsix6.narrator2.event.Envelope
import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.event.IdleChanged
import io.github.clemsix6.narrator2.event.ItemCount
import io.github.clemsix6.narrator2.event.ItemCrafted
import io.github.clemsix6.narrator2.event.PlaceKind
import io.github.clemsix6.narrator2.event.PlayerDied
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.event.PositionSampled
import io.github.clemsix6.narrator2.event.SampleReason
import io.github.clemsix6.narrator2.event.StructureEntered
import io.github.clemsix6.narrator2.event.Traded
import io.github.clemsix6.narrator2.testing.SampleEvents.alex
import io.github.clemsix6.narrator2.testing.SampleEvents.alexEntity
import io.github.clemsix6.narrator2.testing.SampleEvents.steve
import io.github.clemsix6.narrator2.testing.SampleEvents.zombie
import io.github.clemsix6.narrator2.testing.TestClock
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActivityDigestTest {
    private val clock = TestClock(Instant.parse("2026-09-25T21:30:00Z"))
    private val digest = ActivityDigest(clock)
    private val at = Pos("minecraft:overworld", 350, 70, -220)
    private var ids = 1L

    private fun record(event: GameEvent) = digest.record(Envelope(ids++, clock.millis(), 0, event))

    private fun broken(block: String, y: Int = 12) = record(BlockBroken(steve, at.copy(y = y), "minecraft:$block", null, false, true, null))

    private fun sample(x: Int, z: Int, underground: Boolean = false, reason: SampleReason = SampleReason.MOVED) =
        record(PositionSampled(steve, at.copy(x = x, z = z), "minecraft:plains", null, underground, 0, 0, 20.0, 20, null, reason))

    private fun minutes(n: Long) = clock.advance(Duration.ofMinutes(n))

    @Test
    fun `mining - ores first with their depth, then the most broken blocks, five at most`() {
        repeat(90) { broken("stone") }
        listOf(-54, -51, -52).forEach { broken("diamond_ore", it) }
        repeat(40) { broken("deepslate") }
        repeat(7) { broken("dirt") }
        broken("coal_ore", 40)
        repeat(3) { broken("gravel") }
        repeat(2) { broken("sand") }
        assertEquals("mined 146 (3 diamond_ore y=-54..-51, 1 coal_ore y=40, stone 90, deepslate 40, dirt 7)", digest.recent(steve.uuid, 5))
    }

    @Test
    fun `every kind of activity, most telling first`() {
        record(PlayerDied(steve, at, "minecraft:fall", null, null, null, 3, false, false))
        broken("stone")
        repeat(600) { record(BlockPlaced(steve, at, "minecraft:oak_planks", "minecraft:oak_planks", PlaceKind.BLOCK)) }
        repeat(300) { record(BlockPlaced(steve, at.copy(x = 351), "minecraft:glass", "minecraft:glass", PlaceKind.BLOCK)) }
        record(BlockPlaced(steve, at, "minecraft:water", "minecraft:water_bucket", PlaceKind.FLUID))
        repeat(4) { record(EntityKilled(steve, at, zombie, null, pvp = false, tamed = false)) }
        record(EntityKilled(steve, at, alexEntity, null, pvp = true, tamed = false))
        record(DamageTaken(steve, at, "minecraft:mob_attack", 10.5, 3, zombie, false, 9.5))
        record(DamageTaken(steve, at, "minecraft:fall", 7.0, 1, null, false, 2.5))
        record(StructureEntered(steve, at, "minecraft:village_plains", true))
        record(BiomeEntered(steve, at, "minecraft:cherry_grove", true))
        record(ItemCrafted(steve, at, "minecraft:torch", 16, "minecraft:crafting"))
        record(Traded(steve, at, ItemCount("minecraft:emerald", 1), ItemCount("minecraft:paper", 24), null, zombie, null, null, 1, 16))
        assertEquals(
            "died, mined 1 (stone 1), placed 900 (oak_planks 600, glass 300) near 351 70 -220, killed 4 zombie, 1 Alex, " +
                "took 18 damage (mob_attack), entered village_plains, cherry_grove, crafted 16 torch",
            digest.recent(steve.uuid, 5),
        )
    }

    @Test
    fun `distance walked, without jumps, and the share of time underground`() {
        sample(0, 0)
        sample(30, 40, underground = true)
        sample(1_000, 0, underground = true, reason = SampleReason.TELEPORT)
        sample(1_000, 20, underground = true)
        assertEquals("moved 70 blocks, 75% underground", digest.recent(steve.uuid, 5))
    }

    @Test
    fun `being idle or underground alone is no activity, and chat is not activity`() {
        sample(0, 0, underground = true)
        sample(1, 0, underground = true)
        sample(2, 0, underground = true)
        record(IdleChanged(steve, at, idle = true, idleSeconds = 300))
        record(ChatMessage(steve, ChatChannel.PUBLIC, "coucou", emptyList()))
        assertNull(digest.recent(steve.uuid, 5))
        assertTrue(digest.takeWindow().isEmpty())

        broken("stone")
        minutes(2)
        assertEquals("mined 1 (stone 1), 100% underground, idle 7m", digest.recent(steve.uuid, 5))
        record(IdleChanged(steve, at, idle = false, idleSeconds = 420))
        assertEquals("mined 1 (stone 1), 100% underground", digest.recent(steve.uuid, 5))
    }

    @Test
    fun `recent covers the last minutes, and slices older than 10 minutes are forgotten`() {
        broken("stone")
        minutes(4)
        broken("dirt")
        assertEquals("mined 1 (dirt 1)", digest.recent(steve.uuid, 1))
        assertEquals("mined 2 (stone 1, dirt 1)", digest.recent(steve.uuid, 5))
        minutes(7)
        assertEquals("mined 1 (dirt 1)", digest.recent(steve.uuid, 30), "the stone is older than 10 minutes")
        assertNull(digest.recent(alex.uuid, 5))
    }

    @Test
    fun `the heartbeat window, one line per active player, starts again once taken`() {
        broken("stone")
        record(BlockBroken(alex, at, "minecraft:dirt", null, false, true, null))
        assertEquals(listOf("Alex: mined 1 (dirt 1)", "Steve: mined 1 (stone 1)"), digest.takeWindow())
        minutes(1)
        assertTrue(digest.takeWindow().isEmpty(), "a new window")
        broken("gravel")
        minutes(1)
        digest.mark()
        minutes(1)
        broken("sand")
        assertEquals(listOf("Steve: mined 1 (sand 1)"), digest.takeWindow())
        assertEquals(clock.millis(), digest.windowStart)
    }

    @Test
    fun `a line holds at most 200 characters, name included, cut between two activities`() {
        listOf("stone", "dirt", "gravel", "sand", "andesite").forEach { broken(it) }
        listOf("oak_planks", "spruce_planks", "birch_planks").forEach { record(BlockPlaced(steve, at, "minecraft:$it", "minecraft:$it", PlaceKind.BLOCK)) }
        listOf("zombie", "skeleton", "spider").forEach { record(EntityKilled(steve, at, zombie.copy(type = "minecraft:$it"), null, false, false)) }
        record(DamageTaken(steve, at, "minecraft:mob_attack", 30.0, 3, zombie, false, 5.0))
        listOf("village_plains", "ancient_city", "stronghold").forEach { record(StructureEntered(steve, at, "minecraft:$it", true)) }
        listOf("torch", "diamond_pickaxe").forEach { record(ItemCrafted(steve, at, "minecraft:$it", 1, "minecraft:crafting")) }
        val line = digest.takeWindow().single()
        assertTrue(line.length <= ActivityDigest.LINE_MAX, "${line.length}: $line")
        assertTrue(line.startsWith("Steve: mined 5"), line)
        assertTrue(!line.endsWith(","), line)
    }
}
