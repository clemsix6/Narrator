package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.observer.PlacedBlocks
import io.github.clemsix6.narrator2.oracle.Survey
import io.github.clemsix6.narrator2.oracle.WorldReader
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.tool.oracle.SurveyArgs
import io.github.clemsix6.narrator2.tool.oracle.SurveyTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.future
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.state.BlockState
import java.lang.management.ManagementFactory
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * In-game tests of the scout's `survey` (M4 §14): it finds a prepared flat square on rough ground, under the open
 * sky or under leaves, avoids a square that holds a block placed by a player, reads no chunk that is not loaded
 * (and loads none), refuses a dimension with a ceiling, and costs little on the server thread.
 *
 * The built case uses the Oracle's empty structure `oracle_field` (32×24×32) with sky access, so that the top of
 * the built ground is the surface of every column read.
 */
class SurveyGameTests {

    /**
     * Rough ground (a checkerboard of two heights) with three plateaus of grass 9×9: one open to the sky, one
     * under a leaf, one with a block a player placed. Surveyed for squares of 7 from the middle, 12 blocks around.
     */
    @GameTest(structure = FIELD, skyAccess = true, maxTicks = ASYNC_MAX_TICKS)
    fun surveyFindsAPreparedFlatSquareAndAvoidsPlacedBlocks(helper: GameTestHelper) {
        val stone = Blocks.STONE.defaultBlockState()
        val grass = Blocks.GRASS_BLOCK.defaultBlockState()
        for (x in 1..31) for (z in 1..31) {
            val plateau = PLATEAUS.any { (from, to) -> x in from.x..to.x && z in from.z..to.z }
            fill(helper, BlockPos(x, 0, z), BlockPos(x, 2, z), stone)
            when {
                plateau -> set(helper, BlockPos(x, 3, z), grass)
                (x + z) % 2 == 0 -> fill(helper, BlockPos(x, 3, z), BlockPos(x, 4, z), stone)
            }
        }
        set(helper, BlockPos(10, 9, 12), Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true))
        val planks = Blocks.OAK_PLANKS.defaultBlockState()
        set(helper, PLACED, planks)
        PlacedBlocks.mark(helper.level, helper.absolutePos(PLACED), planks, UUID.randomUUID())

        val player = TestPlayer(helper, "sv_")
        helper.runAfterDelay((ASYNC_MAX_TICKS - 1).toLong()) { player.close() }
        player.standAt(CENTRE)
        val centre = helper.absolutePos(CENTRE)
        val fromPoint = helper.survey(SurveyArgs(x = centre.x, y = centre.y, z = centre.z, radius = 12, size = 7))
        val fromPlayer = helper.survey(SurveyArgs(player = player.name, radius = 12, size = 7))
        fun at(rel: BlockPos): String = helper.absolutePos(rel).let { "${it.x} ${it.y} ${it.z}" }
        fun spots(from: String) = listOf(
            "1. ${at(BlockPos(10, 4, 12))} · flat 7x7 (±0) · under leaves · grass_block · 7 blocks NW of $from",
            "2. ${at(BlockPos(22, 4, 12))} · flat 7x7 (±0) · open sky · grass_block · 7 blocks NE of $from",
        )
        helper.startSequence()
            .thenAwait { helper.assertTrue(fromPoint.isDone && fromPlayer.isDone, "survey answers") }
            .thenExecute {
                val point = ok(helper, fromPoint)
                val around = ok(helper, fromPlayer)
                Log.tool.info("survey sample:\n$point")
                expectText(helper, point, listOf("survey around overworld ${at(CENTRE)}, radius 12, square 7: 2 spots") + spots("the point"))
                expectText(helper, around, listOf("survey around ${player.name} (overworld ${at(CENTRE)}), radius 12, square 7: 2 spots") + spots(player.name))
                player.close()
            }
            .thenSucceed()
    }

    /** A point far from any player: nothing read, nothing loaded. The Nether: refused, its roof is no ground. */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun surveyReadsNoChunkThatIsNotLoaded(helper: GameTestHelper) {
        val far = helper.absolutePos(BlockPos(0, 0, 0)).offset(48_000, 0, 0)
        val chunks = ChunkPos.rangeClosed(ChunkPos.containing(BlockPos(far.x - 16, 0, far.z - 16)), ChunkPos.containing(BlockPos(far.x + 16, 0, far.z + 16))).toList()
        val chunkSource = helper.level.chunkSource
        helper.assertTrue(chunks.none { chunkSource.getChunkNow(it.x(), it.z()) != null }, "the far chunks are not loaded to begin with")
        val unloaded = helper.survey(SurveyArgs(x = far.x, y = 70, z = far.z, radius = 8, size = 5))
        val nether = helper.survey(SurveyArgs(x = 0, y = 70, z = 0, dimension = "the_nether"))
        helper.startSequence()
            .thenAwait { helper.assertTrue(unloaded.isDone && nether.isDone, "survey answers") }
            .thenExecute {
                val result = unloaded.get()
                helper.assertTrue(
                    result.status == ToolStatus.FAILED &&
                        result.text == "FAILED: no chunk is loaded within 8 blocks of overworld ${far.x} 70 ${far.z}: the scout reads only the areas loaded around players",
                    "far away: ${result.text}",
                )
                helper.assertTrue(chunks.none { chunkSource.getChunkNow(it.x(), it.z()) != null }, "survey loaded no chunk")
                val roofed = nether.get()
                helper.assertTrue(
                    roofed.status == ToolStatus.INVALID &&
                        roofed.text == "Invalid arguments: the_nether has a ceiling: survey finds ground under the open sky only (overworld, the_end)",
                    "the Nether: ${roofed.text}",
                )
            }
            .thenSucceed()
    }

    /**
     * The worst case on the server thread: the largest radius and square (64 and 21), every chunk of the area
     * loaded (forced for the test, then released). The reading must stay under 30 ms of CPU time (FLAKY-1), on
     * average over 10 runs after 2 (a thread's CPU time moves by steps of about 16 ms on Windows: only a sum over
     * several runs reads well); the search, off the server thread in production, is measured too. The median
     * wall time is logged.
     */
    @GameTest(structure = FIELD, skyAccess = true, maxTicks = ASYNC_MAX_TICKS)
    fun surveyCostsLittleOnTheServerThread(helper: GameTestHelper) {
        val level = helper.level
        val centre = helper.absolutePos(CENTRE)
        val reach = 64 + 21 / 2
        val chunks = ChunkPos.rangeClosed(ChunkPos.containing(BlockPos(centre.x - reach, 0, centre.z - reach)), ChunkPos.containing(BlockPos(centre.x + reach, 0, centre.z + reach))).toList()
        val forced = chunks.filter { level.setChunkForced(it.x(), it.z(), true) }
        fun release() = forced.forEach { level.setChunkForced(it.x(), it.z(), false) }
        helper.runAfterDelay((ASYNC_MAX_TICKS - 1).toLong()) { release() }
        val answer = arrayOfNulls<CompletableFuture<ToolResult>>(1)
        helper.startSequence()
            .thenAwait { helper.assertTrue(chunks.all { level.chunkSource.getChunkNow(it.x(), it.z()) != null }, "the area's chunks are loaded") }
            .thenExecute {
                val read = measure { Survey.read(WorldReader(level), centre.x, centre.z, 64, 21) }
                val grid = read.value
                val search = measure { Survey.find(grid, centre.x, centre.z, 64, 21) }
                Log.tool.info(
                    "survey cost: read of ${grid.side * grid.side} columns in ${grid.loadedChunks}/${grid.chunks} chunks: $read; " +
                        "search (${search.value.size} spots): $search",
                )
                helper.assertTrue(grid.loadedChunks == grid.chunks, "every chunk read: ${grid.loadedChunks}/${grid.chunks}")
                helper.assertTrue(read.cpu < 30_000_000, "the reading stays under 30 ms on the server thread: $read")
                helper.assertTrue(search.cpu < 30_000_000, "the search stays under 30 ms: $search")
                answer[0] = helper.survey(SurveyArgs(x = centre.x, y = centre.y, z = centre.z, radius = 64, size = 21))
            }
            .thenAwait { helper.assertTrue(answer[0]!!.isDone, "survey answers") }
            .thenExecute {
                Log.tool.info("survey sample, radius 64, square 21:\n${ok(helper, answer[0]!!)}")
                release()
            }
            .thenSucceed()
    }

    private companion object {
        const val FIELD = "narrator2-gametest:oracle_field"

        /** Where the survey looks from: on the rough ground, between the plateaus. */
        val CENTRE = BlockPos(16, 5, 16)

        /** The plateaus of grass at y 3, 9×9 each: north-east, north-west (under a leaf), south-west (with a player's block). */
        val PLATEAUS = listOf(
            BlockPos(19, 3, 7) to BlockPos(27, 3, 15),
            BlockPos(5, 3, 7) to BlockPos(13, 3, 15),
            BlockPos(5, 3, 17) to BlockPos(13, 3, 25),
        )

        /** The block a player placed in the south-west plateau, inside every square of 7 that fits on it. */
        val PLACED = BlockPos(9, 3, 21)

        /** Sets blocks without updating their neighbours. */
        const val FLAGS = Block.UPDATE_CLIENTS or Block.UPDATE_KNOWN_SHAPE

        fun set(helper: GameTestHelper, rel: BlockPos, state: BlockState) {
            helper.level.setBlock(helper.absolutePos(rel), state, FLAGS)
        }

        fun fill(helper: GameTestHelper, from: BlockPos, to: BlockPos, state: BlockState) {
            for (rel in BlockPos.betweenClosed(from, to)) set(helper, rel, state)
        }

        fun ok(helper: GameTestHelper, future: CompletableFuture<ToolResult>): String {
            val result = future.get()
            helper.assertTrue(result.status == ToolStatus.OK, "expected ok, got ${result.text}")
            return result.text
        }

        fun expectText(helper: GameTestHelper, text: String, lines: List<String>) =
            helper.assertTrue(text.lines() == lines, "expected:\n${lines.joinToString("\n")}\ngot:\n$text")

        /** What [measure] found: the last [value], the average CPU time of a run on this thread, the median wall time. */
        class Measured<T>(val value: T, val cpu: Long, val wall: Long) {
            override fun toString() = "%.3f ms CPU on average, %.3f ms wall (median)".format(Locale.ROOT, cpu / 1e6, wall / 1e6)
        }

        /** Runs [block] 10 times after 2 that warm the code up. */
        fun <T> measure(block: () -> T): Measured<T> {
            val threads = ManagementFactory.getThreadMXBean()
            block()
            var value = block()
            val cpuStart = threads.currentThreadCpuTime
            val walls = List(10) {
                val start = System.nanoTime()
                value = block()
                System.nanoTime() - start
            }
            return Measured(value, (threads.currentThreadCpuTime - cpuStart) / walls.size, walls.sorted()[walls.size / 2])
        }
    }
}

/** Runs `survey` as the scout would, off the server thread; the future completes with its answer. */
private fun GameTestHelper.survey(args: SurveyArgs): CompletableFuture<ToolResult> {
    val runtime = narrator()
    val tool = SurveyTool()
    val context = ToolContext(runtime, Caller(Role.SCOUT, "survey-gametest", turnId = null, taskId = null), runtime.clock, tool.timeoutMs)
    return CoroutineScope(Dispatchers.Default).future { tool.run(context, args) }
}
