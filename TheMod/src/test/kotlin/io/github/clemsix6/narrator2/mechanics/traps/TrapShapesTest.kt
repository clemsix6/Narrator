package io.github.clemsix6.narrator2.mechanics.traps

import net.minecraft.core.BlockPos
import net.minecraft.world.phys.AABB
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The cells of the traps (M4 §9.2); the terrain they refuse is tested in game (`TrapGameTests`). */
class TrapShapesTest {
    /** A player standing in the middle of the block at 10 64 -5: 0.6 wide, 1.8 high. */
    private val centered = AABB(10.2, 64.0, -4.8, 10.8, 65.8, -4.2)

    @Test
    fun `a player standing in the middle of a block is in two cells, feet and head`() {
        assertEquals(Pair(BlockPos(10, 64, -5), BlockPos(10, 65, -5)), TrapShapes.occupied(centered))
    }

    @Test
    fun `the cage around them is the 3x3x4 shell, floor, walls and roof, never their own cells`() {
        val (low, high) = TrapShapes.occupied(centered)
        val cells = TrapShapes.cageCells(low, high)
        assertEquals(34, cells.size, "36 cells of the box but the 2 the player is in")
        assertEquals(cells.size, cells.toSet().size, "each cell once")
        assertEquals(9..11, cells.minOf { it.x }..cells.maxOf { it.x })
        assertEquals(63..66, cells.minOf { it.y }..cells.maxOf { it.y }, "floor under the feet, roof over the head")
        assertEquals(-6..-4, cells.minOf { it.z }..cells.maxOf { it.z })
        assertFalse(BlockPos(10, 64, -5) in cells || BlockPos(10, 65, -5) in cells, "never where the player is")
        assertEquals(9, cells.count { it.y == 63 }, "the floor")
        assertEquals(8, cells.count { it.y == 64 }, "the walls at the feet")
        assertEquals(8, cells.count { it.y == 65 }, "the walls at the head")
        assertEquals(9, cells.count { it.y == 66 }, "the roof")
    }

    @Test
    fun `a player standing across two blocks, or on a slab, gets a cage around every cell they are in`() {
        val across = TrapShapes.occupied(AABB(9.9, 64.0, -4.8, 10.5, 65.8, -4.2))
        assertEquals(Pair(BlockPos(9, 64, -5), BlockPos(10, 65, -5)), across)
        val wide = TrapShapes.cageCells(across.first, across.second)
        assertEquals(4 * 3 * 4 - 4, wide.size, "a 4x3x4 box but the 4 cells of the player")
        assertTrue(wide.none { it.x in 9..10 && it.y in 64..65 && it.z == -5 })

        val onSlab = TrapShapes.occupied(AABB(10.2, 64.5, -4.8, 10.8, 66.3, -4.2))
        assertEquals(Pair(BlockPos(10, 64, -5), BlockPos(10, 66, -5)), onSlab, "three cells high")
        assertEquals(3 * 3 * 5 - 3, TrapShapes.cageCells(onSlab.first, onSlab.second).size)
        // The largest: across four blocks and on a slab, 68 cells; more than BlockRestorer.MAX_PER_CALL free is refused.
        val largest = TrapShapes.occupied(AABB(9.9, 64.5, -5.1, 10.5, 66.3, -4.5))
        assertEquals(4 * 4 * 5 - 2 * 2 * 3, TrapShapes.cageCells(largest.first, largest.second).size)
    }

    @Test
    fun `a box that only touches a cell's face is not in that cell`() {
        assertEquals(Pair(BlockPos(10, 64, -5), BlockPos(10, 65, -5)), TrapShapes.occupied(AABB(10.4, 64.0, -5.0, 11.0, 66.0, -4.4)))
    }

    @Test
    fun `cobwebs take the player's two cells and the four next to their feet`() {
        val cells = TrapShapes.webCells(BlockPos(10, 64, -5))
        assertEquals(
            setOf(BlockPos(10, 64, -5), BlockPos(10, 65, -5), BlockPos(11, 64, -5), BlockPos(9, 64, -5), BlockPos(10, 64, -4), BlockPos(10, 64, -6)),
            cells.toSet(),
        )
        assertEquals(6, cells.size)
    }

    @Test
    fun `half of the cells free is enough room, fewer is not`() {
        assertTrue(TrapShapes.enoughRoom(17, 34))
        assertFalse(TrapShapes.enoughRoom(16, 34))
        assertTrue(TrapShapes.enoughRoom(3, 6))
        assertFalse(TrapShapes.enoughRoom(2, 6))
    }

    @Test
    fun `the dangers are looked for in the shape's box and the layer above it`() {
        val (low, high) = TrapShapes.occupied(centered)
        assertEquals(Pair(BlockPos(9, 63, -6), BlockPos(11, 67, -4)), TrapShapes.hazardBox(TrapShapes.cageCells(low, high)))
        assertEquals(Pair(BlockPos(9, 64, -6), BlockPos(11, 66, -4)), TrapShapes.hazardBox(TrapShapes.webCells(BlockPos(10, 64, -5))))
    }
}
