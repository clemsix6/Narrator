package io.github.clemsix6.narrator2.oracle

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The verdict of the cave heuristic on measured cases (ORACLE_ACTIONS_API.md §3.2). The same cases are
 * built in game in `OracleGameTests`, which checks that the measures are what these tests assume.
 */
class EnclosureTest {
    private fun overworld(
        depth: Int?,
        sky: Int,
        volume: Int = 0,
        tall: Int = 0,
        built: Double = 0.0,
        caveBiome: Boolean = false,
    ) = EnclosureProbe(
        skyDimension = true, ceiling = false, aboveCeiling = false, skyLight = sky, depth = depth,
        volume = volume, tallCells = tall, builtShare = built, caveBiome = caveBiome,
    )

    private fun nether(volume: Int, aboveCeiling: Boolean = false) = EnclosureProbe(
        skyDimension = false, ceiling = true, aboveCeiling = aboveCeiling, skyLight = 0, depth = null,
        volume = volume, tallCells = volume / 2, builtShare = 0.0, caveBiome = false,
    )

    @Test
    fun `nothing solid overhead is open sky, even under water`() {
        assertEquals(Enclosure.OPEN_SKY, Enclosure.classify(overworld(depth = 0, sky = 15, volume = Enclosure.MAX_VOLUME, tall = 200)))
        assertEquals(Enclosure.OPEN_SKY, Enclosure.classify(overworld(depth = -12, sky = 4, volume = Enclosure.MAX_VOLUME, tall = 200)), "10 blocks deep in a lake")
        assertEquals(Enclosure.OPEN_SKY, Enclosure.classify(overworld(depth = 3, sky = 15)), "under glass: the sky is visible")
    }

    @Test
    fun `a tree, a roof or an overhang is a shelter`() {
        assertEquals(Enclosure.SHELTERED, Enclosure.classify(overworld(depth = 9, sky = 13, volume = Enclosure.MAX_VOLUME, tall = 200)), "under a canopy of leaves")
        assertEquals(Enclosure.SHELTERED, Enclosure.classify(overworld(depth = 4, sky = 0, volume = 20)), "a sealed hut")
        assertEquals(Enclosure.SHELTERED, Enclosure.classify(overworld(depth = 30, sky = 11, volume = 300, tall = 100)), "under an overhang")
        assertEquals(Enclosure.SHELTERED, Enclosure.classify(overworld(depth = Enclosure.SHALLOW_DEPTH, sky = 0, volume = 40)), "just under the grass")
    }

    @Test
    fun `a tight space well below the surface is underground`() {
        assertEquals(Enclosure.UNDERGROUND, Enclosure.classify(overworld(depth = 40, sky = 0, volume = 26)), "a 1x2 tunnel")
        assertEquals(Enclosure.UNDERGROUND, Enclosure.classify(overworld(depth = 40, sky = 0, volume = 190, tall = 0)), "a branch mine: large but 2 high")
        assertEquals(Enclosure.UNDERGROUND, Enclosure.classify(overworld(depth = 6, sky = 7, volume = 30)), "a tunnel 6 blocks down")
    }

    @Test
    fun `a large natural volume, or a cave biome, is a cave`() {
        assertEquals(Enclosure.CAVE, Enclosure.classify(overworld(depth = 40, sky = 0, volume = Enclosure.MAX_VOLUME, tall = 200)), "a cavern")
        assertEquals(Enclosure.CAVE, Enclosure.classify(overworld(depth = 40, sky = 0, volume = Enclosure.CAVE_VOLUME, tall = 20)), "a passage")
        assertEquals(Enclosure.UNDERGROUND, Enclosure.classify(overworld(depth = 40, sky = 0, volume = 119, tall = 60)), "just too small")
        assertEquals(Enclosure.CAVE, Enclosure.classify(overworld(depth = 40, sky = 0, volume = 45, caveBiome = true)), "a nook of lush caves")
        assertEquals(Enclosure.UNDERGROUND, Enclosure.classify(overworld(depth = 40, sky = 0, volume = 20, caveBiome = true)), "a tunnel in a cave biome")
    }

    @Test
    fun `what players built is a building near the surface and a bunker deeper, never a cave`() {
        assertEquals(Enclosure.SHELTERED, Enclosure.classify(overworld(depth = 12, sky = 0, volume = 300, tall = 100, built = 0.8)), "a windowless hall")
        assertEquals(Enclosure.UNDERGROUND, Enclosure.classify(overworld(depth = 40, sky = 0, volume = Enclosure.MAX_VOLUME, tall = 150, built = 0.3)), "a bunker")
        assertEquals(Enclosure.CAVE, Enclosure.classify(overworld(depth = 40, sky = 0, volume = Enclosure.MAX_VOLUME, tall = 150, built = 0.1)), "a cave where a player placed a few blocks")
    }

    @Test
    fun `under the Nether's ceiling there is no cave, only open or enclosed`() {
        assertEquals(Enclosure.ENCLOSED, Enclosure.classify(nether(volume = 26)))
        assertEquals(Enclosure.OPEN, Enclosure.classify(nether(volume = Enclosure.MAX_VOLUME)))
        assertEquals(Enclosure.OPEN, Enclosure.classify(nether(volume = Enclosure.OPEN_VOLUME)))
        assertEquals(Enclosure.OPEN_SKY, Enclosure.classify(nether(volume = Enclosure.MAX_VOLUME, aboveCeiling = true)), "on the Nether's roof")
    }

    @Test
    fun `the End has no caves`() {
        val endCityRoom = EnclosureProbe(
            skyDimension = false, ceiling = false, aboveCeiling = false, skyLight = 0, depth = 20,
            volume = 400, tallCells = 200, builtShare = 0.0, caveBiome = false,
        )
        assertEquals(Enclosure.UNDERGROUND, Enclosure.classify(endCityRoom))
        assertEquals(Enclosure.OPEN_SKY, Enclosure.classify(endCityRoom.copy(depth = 0)), "on an island")
    }

    @Test
    fun `the label tells the depth of deep verdicts only`() {
        assertEquals("cave, 41 below surface", Enclosure.label(Enclosure.CAVE, 41))
        assertEquals("underground, 12 below surface", Enclosure.label(Enclosure.UNDERGROUND, 12))
        assertEquals("sheltered", Enclosure.label(Enclosure.SHELTERED, 4))
        assertEquals("open", Enclosure.label(Enclosure.OPEN, null))
    }
}
