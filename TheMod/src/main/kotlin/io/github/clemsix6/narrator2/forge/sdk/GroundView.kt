package io.github.clemsix6.narrator2.forge.sdk

import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.forge.api.Cell
import io.github.clemsix6.narrator2.forge.api.Ground
import io.github.clemsix6.narrator2.forge.api.Pending
import io.github.clemsix6.narrator2.tool.ToolResult
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The module's [Ground] (M5 §4.4): temporary blocks through the `place_blocks` tool, hence the `BlockRestorer`, in thin
 * air only (decision Q4 of M5: no block ever replaces a block that is there). An act like the others ([ActQueue.ask]):
 * near a player it is a trap of that player, which the guards check.
 */
class GroundView(private val queue: ActQueue) : Ground {
    override fun place(cells: List<Cell>?, block: String?, seconds: Int): Pending {
        // Copied here, in the module's own call: a module may implement List (its size or its elements may lie later).
        val copied = cells?.let { ArrayList<Any?>(it) }.orEmpty()
        if (copied.size > ForgeLimits.PLACE_CELLS) {
            val refusal = ToolResult.invalid("place sets ${ForgeLimits.PLACE_CELLS} cells at most (asked ${copied.size})")
            return queue.ask(Act(PLACE_BLOCKS, buildJsonObject { put("cells", copied.size) }, null, refusal = refusal))
        }
        val given = copied.filterIsInstance<Cell>()
        val dimensions = given.map { it.dimension() }.distinct()
        val refusal = when {
            given.size < copied.size -> ToolResult.invalid("place takes cells, never null nor anything else")
            dimensions.size > 1 -> ToolResult.invalid("place sets blocks in one dimension at a time (asked ${dimensions.joinToString()})")
            else -> null
        }
        val args = buildJsonObject {
            put("dimension", dimensions.firstOrNull())
            putJsonArray("cells") {
                for (cell in given) {
                    addJsonObject {
                        put("x", cell.x())
                        put("y", cell.y())
                        put("z", cell.z())
                    }
                }
            }
            put("block", block)
            put("seconds", seconds)
        }
        return queue.ask(Act(PLACE_BLOCKS, args, null, refusal = refusal))
    }

    companion object {
        /** The tool of [Ground.place] (`mechanics/module/ModuleOnlyTools.kt`). */
        const val PLACE_BLOCKS = "place_blocks"
    }
}
