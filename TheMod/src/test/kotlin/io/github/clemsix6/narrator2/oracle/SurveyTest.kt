package io.github.clemsix6.narrator2.oracle

import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The search of `survey` (M4 §14) on grids built by hand: which squares fit, where their centre is, in which
 * order they come, and what the worst case costs. Reading the world is tested in game (`SurveyGameTests`).
 */
class SurveyTest {
    /** One column of a hand-made grid: its ground's y, its flag, its ground block. */
    private data class Column(val top: Int, val flag: Byte = 0, val ground: String = "grass_block")

    /** A grid around [centerX], [centerZ] as [Survey.read] would cover it, each column given by [column]. */
    private fun grid(radius: Int, size: Int, centerX: Int = 0, centerZ: Int = 0, column: (x: Int, z: Int) -> Column): SurveyGrid {
        val reach = radius + size / 2
        val side = 2 * reach + 1
        val minX = centerX - reach
        val minZ = centerZ - reach
        val palette = ArrayList<String>()
        val tops = IntArray(side * side)
        val flags = ByteArray(side * side)
        val grounds = IntArray(side * side)
        for (z in minZ until minZ + side) for (x in minX until minX + side) {
            val i = (z - minZ) * side + (x - minX)
            val c = column(x, z)
            tops[i] = c.top
            flags[i] = c.flag
            grounds[i] = if (c.flag == Survey.UNFIT || c.flag == Survey.UNLOADED) -1 else palette.indexOf(c.ground).takeIf { it >= 0 } ?: palette.size.also { palette += c.ground }
        }
        return SurveyGrid(minX, minZ, side, tops, flags, grounds, palette, chunks = 9, loadedChunks = 9)
    }

    /** Rough ground everywhere: a checkerboard of y 64 and 66, where no square fits. */
    private fun rough(x: Int, z: Int) = Column(if ((x + z) and 1 == 0) 64 else 66)

    /** Within the rectangle from [x0], [z0] to [x1], [z1] (inclusive). */
    private fun inside(x: Int, z: Int, x0: Int, z0: Int, x1: Int, z1: Int) = x in x0..x1 && z in z0..z1

    @Test
    fun `a flat square in rough ground is found where it is, with its ground and the open sky`() {
        // A plateau of sand of 11 × 11 at y 70, from 7 to 17 east and 13 to 3 north: one centre of the grid fits a 9 × 9 square.
        val grid = grid(radius = 16, size = 9) { x, z -> if (inside(x, z, 7, -13, 17, -3)) Column(70, ground = "sand") else rough(x, z) }

        assertEquals(listOf(SurveySpot(12, 71, -8, 0, true, "sand")), Survey.find(grid, 0, 0, 16, 9))
        assertEquals(emptyList(), Survey.find(grid(radius = 16, size = 9) { x, z -> rough(x, z) }, 0, 0, 16, 9), "rough ground has no spot")
    }

    @Test
    fun `the ground may vary by one block, not two`() {
        val step = grid(radius = 8, size = 5) { x, z -> if (inside(x, z, -2, -2, 2, 2)) Column(if (x < 0) 70 else 71) else rough(x, z) }
        assertEquals(listOf(SurveySpot(0, 72, 0, 1, true, "grass_block")), Survey.find(step, 0, 0, 8, 5))

        val stairs = grid(radius = 8, size = 5) { x, z -> if (inside(x, z, -2, -2, 2, 2)) Column(70 + (x + 2) / 2) else rough(x, z) }
        assertEquals(emptyList(), Survey.find(stairs, 0, 0, 8, 5), "70 to 72 is not flat")
    }

    @Test
    fun `one unfit or unread column rules out every square that holds it`() {
        for (flag in listOf(Survey.UNFIT, Survey.UNLOADED)) {
            val grid = grid(radius = 8, size = 5) { x, z ->
                when {
                    x == 1 && z == 0 -> Column(70, flag)
                    inside(x, z, -3, -3, 3, 3) -> Column(70)
                    else -> rough(x, z)
                }
            }
            assertEquals(emptyList(), Survey.find(grid, 0, 0, 8, 5), "flag $flag")
        }
    }

    @Test
    fun `leaves above a square make it under leaves, not under the open sky`() {
        val grid = grid(radius = 8, size = 5) { x, z ->
            when {
                inside(x, z, -1, -1, 0, 0) -> Column(70, Survey.LEAVES)
                inside(x, z, -2, -2, 2, 2) -> Column(70)
                else -> rough(x, z)
            }
        }
        assertEquals(listOf(SurveySpot(0, 71, 0, 0, false, "grass_block")), Survey.find(grid, 0, 0, 8, 5))
    }

    @Test
    fun `the ground of a spot is its most common block`() {
        val grid = grid(radius = 8, size = 5) { x, z ->
            when {
                inside(x, z, -2, -2, 2, -1) -> Column(70, ground = "dirt") // 10 columns
                inside(x, z, -2, 0, 2, 2) -> Column(70, ground = "podzol") // 15 columns
                else -> rough(x, z)
            }
        }
        assertEquals("podzol", Survey.find(grid, 0, 0, 8, 5).single().ground)
    }

    @Test
    fun `the nearest spots come first, five at most, none overlapping another`() {
        val grid = grid(radius = 32, size = 9) { _, _ -> Column(64) }

        val spots = Survey.find(grid, 0, 0, 32, 9)

        assertEquals(Survey.MAX_SPOTS, spots.size)
        assertEquals(SurveySpot(0, 65, 0, 0, true, "grass_block"), spots.first(), "the centre itself first")
        val distances = spots.map { it.x * it.x + it.z * it.z }
        assertEquals(distances.sorted(), distances, "nearest first: $spots")
        for ((i, a) in spots.withIndex()) for (b in spots.drop(i + 1)) {
            assertTrue(abs(a.x - b.x) >= 9 || abs(a.z - b.z) >= 9, "$a and $b overlap")
        }
        assertTrue(spots.all { it.x % Survey.STEP == 0 && it.z % Survey.STEP == 0 }, "centres on the grid: $spots")
    }

    @Test
    fun `the flatter of two spots at the same distance comes first`() {
        val grid = grid(radius = 16, size = 5) { x, z ->
            when {
                inside(x, z, 8, -2, 12, 2) -> Column(70 + (x and 1)) // east: ±1
                inside(x, z, -12, -2, -8, 2) -> Column(70) // west: ±0
                else -> rough(x, z)
            }
        }
        assertEquals(listOf(SurveySpot(-10, 71, 0, 0, true, "grass_block"), SurveySpot(10, 71, 0, 1, true, "grass_block")), Survey.find(grid, 0, 0, 16, 5))
    }

    @Test
    fun `squares whose centre is beyond the radius are not offered`() {
        // Flat only in the corners: the nearest centres of a fit square, ±16 ±16, are 22.6 blocks away.
        val grid = grid(radius = 23, size = 5) { x, z -> if (abs(x) >= 13 && abs(z) >= 13) Column(70) else rough(x, z) }
        assertEquals(emptyList(), Survey.find(grid, 0, 0, 22, 5))
        assertEquals(4, Survey.find(grid, 0, 0, 23, 5).size, "the four corners, with a radius of 23")
    }

    @Test
    fun `a square of an even side reaches one column further after its centre`() {
        // 6 columns: 2 before the centre, 3 after; only x -2..3, z -2..3 is flat.
        val grid = grid(radius = 8, size = 6) { x, z -> if (inside(x, z, -2, -2, 3, 3)) Column(70) else rough(x, z) }
        assertEquals(listOf(SurveySpot(0, 71, 0, 0, true, "grass_block")), Survey.find(grid, 0, 0, 8, 6))
    }

    /**
     * The worst case of the search, which runs off the server thread: the largest radius and square on ground
     * flat everywhere, where every candidate is read in full. CPU time (FLAKY-1), on average over 10 runs after 3:
     * a thread's CPU time moves by steps of about 16 ms on Windows, so only a sum over several runs reads well.
     */
    @Test
    fun `the worst search takes less than 30 ms`() {
        val grid = grid(radius = 64, size = 21) { _, _ -> Column(64) }
        val threads = ManagementFactory.getThreadMXBean()
        repeat(3) { Survey.find(grid, 0, 0, 64, 21) }
        val start = threads.currentThreadCpuTime
        repeat(10) { assertEquals(Survey.MAX_SPOTS, Survey.find(grid, 0, 0, 64, 21).size) }
        val average = (threads.currentThreadCpuTime - start) / 10 / 1_000_000.0
        assertTrue(average < 30.0, "the worst search takes $average ms of CPU on average")
    }
}
