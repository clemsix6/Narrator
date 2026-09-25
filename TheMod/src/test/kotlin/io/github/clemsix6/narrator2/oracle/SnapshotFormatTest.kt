package io.github.clemsix6.narrator2.oracle

import io.github.clemsix6.narrator2.event.PlayerRef
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The one-line forms of a player and of the world, shared by `list_players`, the situation and the briefing. */
class SnapshotFormatTest {
    private fun snapshot(
        name: String = "Bartok",
        dimension: String = "minecraft:overworld",
        x: Int = -120,
        y: Int = -58,
        z: Int = 330,
        enclosure: Enclosure = Enclosure.CAVE,
        depth: Int? = 41,
        structure: String? = "minecraft:mineshaft",
        health: Float = 14f,
        gameMode: String = "survival",
        vehicle: String? = null,
    ) = PlayerSnapshot(
        ref = PlayerRef(UUID.nameUUIDFromBytes(name.toByteArray()), name),
        gameMode = gameMode,
        dimension = dimension,
        x = x,
        y = y,
        z = z,
        biome = "minecraft:dripstone_caves",
        structure = structure,
        enclosure = enclosure,
        depth = depth,
        health = health,
        maxHealth = 20f,
        food = 12,
        vehicle = vehicle,
    )

    @Test
    fun `a player is one line`() {
        assertEquals(
            "overworld -120 -58 330 · dripstone_caves · cave, 41 below surface · in mineshaft · hp 14/20 food 12",
            snapshot().describe(),
        )
        assertEquals(
            "nether 40 72 -12 · dripstone_caves · open · riding strider · hp 13.5/20 food 12 · creative",
            snapshot(
                dimension = "minecraft:the_nether", x = 40, y = 72, z = -12, enclosure = Enclosure.OPEN, depth = null,
                structure = null, health = 13.5f, gameMode = "creative", vehicle = "minecraft:strider",
            ).describe(),
        )
        assertEquals("overworld -120 -58 330 · dripstone_caves · sheltered · dead", snapshot(enclosure = Enclosure.SHELTERED, depth = 3, structure = null, health = 0f).describe())
    }

    @Test
    fun `distances are measured within a dimension only`() {
        val bartok = snapshot()
        assertEquals(50, bartok.distanceTo(snapshot(name = "Mimosa", x = -90, y = -58, z = 370)))
        assertNull(bartok.distanceTo(snapshot(name = "Mimosa", dimension = "minecraft:the_nether")))
    }

    @Test
    fun `the world line gives the day, the hour and the weather`() {
        assertEquals("day 37 · day 06:00 · clear", WorldSnapshot(37, 0, "clear", emptyList()).describe())
        assertEquals("day 2 · day 12:00 · rain", WorldSnapshot(2, 6_000, "rain", emptyList()).describe())
        assertEquals("day 2 · dusk 18:30 · clear", WorldSnapshot(2, 12_500, "clear", emptyList()).describe())
        assertEquals("day 2 · night 00:00 · thunder", WorldSnapshot(2, 18_000, "thunder", emptyList()).describe())
        assertEquals("19:39", WorldSnapshot(2, 13_666, "clear", emptyList()).clock)
        assertEquals("dawn", WorldSnapshot(2, 23_500, "clear", emptyList()).time)
    }
}
