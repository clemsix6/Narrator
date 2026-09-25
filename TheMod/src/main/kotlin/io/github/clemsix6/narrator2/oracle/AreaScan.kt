package io.github.clemsix6.narrator2.oracle

import io.github.clemsix6.narrator2.observer.PlacedBlocks
import io.github.clemsix6.narrator2.observer.id
import io.github.clemsix6.narrator2.observer.typeId
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.world.RandomizableContainer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.SpawnerBlockEntity
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.UUID

/** Something found in an area, e.g. `chest (loot, never opened)` at [pos]. */
data class Spot(val label: String, val pos: BlockPos)

/**
 * The player-placed blocks of one loaded chunk, copied from [PlacedBlocks] on the server thread.
 * `positions[i]`, packed by [PlacedBlocks.pack], was placed by `placers[owners[i]]`.
 */
class PlacedCopy(val chunkX: Int, val chunkZ: Int, val minY: Int, val placers: List<UUID>, val positions: IntArray, val owners: IntArray)

/**
 * Player-placed blocks still standing in an area.
 *
 * @property byPlacer blocks per player, most first.
 * @property clusters the densest 8×8×8 cells: centre, blocks, main placer, most frequent blocks.
 */
class PlacedCount(val total: Int, val byPlacer: List<Pair<UUID, Int>>, val clusters: List<PlacedCluster>)

class PlacedCluster(val center: BlockPos, val blocks: Int, val mainPlacer: UUID, val topBlocks: List<String>)

/** Living things near a point, grouped by type, nearest first. */
data class EntityGroup(val type: String, val category: String, val count: Int, val nearest: Vec3, val names: List<String>)

/**
 * The other readings of an area: block entities (containers, spawners), player-placed blocks
 * ([PlacedBlocks]) and entities. Reads loaded chunks only.
 */
object AreaScan {
    /** Block entities within [radius] of [center] that [label] names (null: not wanted), nearest first. Server thread only. */
    fun blockEntities(reader: WorldReader, center: BlockPos, radius: Int, label: (BlockEntity) -> String?): List<Spot> {
        val radiusSq = radius.toLong() * radius
        val spots = ArrayList<Spot>()
        forEachChunk(center, radius) { cx, cz ->
            val chunk = reader.chunk(cx, cz) ?: return@forEachChunk
            for ((pos, entity) in chunk.blockEntities) {
                if (pos.distSqr(center) > radiusSq) continue
                val name = label(entity) ?: continue
                spots += Spot(name, pos)
            }
        }
        return spots.sortedBy { it.pos.distSqr(center) }
    }

    /**
     * A container's label: `chest (12 stacks)`, `barrel (empty)`, `chest (loot, never opened)`; null for
     * any other block entity. Server thread only.
     */
    fun container(entity: BlockEntity): String? {
        if (entity !is BaseContainerBlockEntity) return null
        val name = OracleText.bare(entity.blockState.block.id)
        if (entity is RandomizableContainer && entity.lootTable != null) return "$name (loot, never opened)"
        val stacks = (0 until entity.containerSize).map { entity.getItem(it) }.filterNot { it.isEmpty }
        if (stacks.isEmpty()) return "$name (empty)"
        val top = stacks.groupBy { OracleText.bare(it.id) }.mapValues { (_, list) -> list.sumOf { it.count } }
            .entries.sortedByDescending { it.value }.take(3).joinToString { "${it.value} ${it.key}" }
        return "$name (${stacks.size} stacks: $top)"
    }

    /** A spawner's label, `spawner (zombie)`, `trial_spawner`; null for any other block entity. Server thread only. */
    fun spawner(entity: BlockEntity): String? = when (entity) {
        is SpawnerBlockEntity -> {
            val mob = entity.spawner.getOrCreateDisplayEntity(entity.level ?: return "spawner", entity.blockPos)
            if (mob == null) "spawner" else "spawner (${OracleText.bare(mob.typeId)})"
        }
        is TrialSpawnerBlockEntity -> "trial_spawner"
        else -> null
    }

    /** Copies the player-placed positions of the loaded chunks within [radius] of [center]. Server thread only. */
    fun copyPlacements(reader: WorldReader, center: BlockPos, radius: Int): List<PlacedCopy> {
        val copies = ArrayList<PlacedCopy>()
        forEachChunk(center, radius) { cx, cz ->
            val chunk = reader.chunk(cx, cz) ?: return@forEachChunk
            val placements = chunk.getAttached(PlacedBlocks.TYPE) ?: return@forEachChunk
            val positions = IntArray(placements.cells.size)
            val owners = IntArray(placements.cells.size)
            var i = 0
            val entries = placements.cells.int2IntEntrySet().fastIterator()
            while (entries.hasNext()) {
                val entry = entries.next()
                positions[i] = entry.intKey
                owners[i] = entry.intValue
                i++
            }
            copies += PlacedCopy(cx, cz, chunk.minY, placements.placers.toList(), positions, owners)
        }
        return copies
    }

    /**
     * The placed blocks of [placements] still standing (not air in [blocks]) within [radius] of [center]:
     * per player, and the [maxClusters] densest 8×8×8 cells (a base). Any thread.
     */
    fun countPlaced(placements: List<PlacedCopy>, blocks: SectionCopies, center: BlockPos, radius: Int, maxClusters: Int): PlacedCount {
        val radiusSq = radius.toLong() * radius
        val perPlacer = HashMap<UUID, Int>()
        val cells = HashMap<Long, MutableList<Pair<UUID, String>>>()
        var total = 0
        for (copy in placements) {
            for (i in copy.positions.indices) {
                val packed = copy.positions[i]
                val x = (copy.chunkX shl 4) + (packed and 15)
                val z = (copy.chunkZ shl 4) + ((packed shr 4) and 15)
                val y = (packed shr 8) + copy.minY
                val dx = (x - center.x).toLong()
                val dy = (y - center.y).toLong()
                val dz = (z - center.z).toLong()
                if (dx * dx + dy * dy + dz * dz > radiusSq) continue
                val state = blocks.stateAt(x, y, z) ?: continue
                if (state.isAir) continue // broken by something else than a player: the index keeps it (PlacedBlocks)
                val placer = copy.placers[copy.owners[i]]
                total++
                perPlacer.merge(placer, 1, Int::plus)
                cells.getOrPut(SectionPos.asLong(x shr 3, y shr 3, z shr 3)) { ArrayList() } += placer to OracleText.bare(state.block.id)
            }
        }
        val clusters = cells.entries.sortedByDescending { it.value.size }.take(maxClusters).map { (key, list) ->
            val center8 = BlockPos((SectionPos.x(key) shl 3) + 4, (SectionPos.y(key) shl 3) + 4, (SectionPos.z(key) shl 3) + 4)
            PlacedCluster(
                center8,
                list.size,
                list.groupingBy { it.first }.eachCount().maxBy { it.value }.key,
                list.groupingBy { it.second }.eachCount().entries.sortedByDescending { it.value }.take(3).map { it.key },
            )
        }
        return PlacedCount(total, perPlacer.entries.sortedByDescending { it.value }.map { it.key to it.value }, clusters)
    }

    /**
     * Living entities and players within [radius] of [center], except [except], grouped by type, nearest
     * first. Items on the ground and other non-living entities are left out. Server thread only.
     */
    fun entities(reader: WorldReader, center: Vec3, radius: Double, except: Entity?): List<EntityGroup> {
        val box = AABB.ofSize(center, radius * 2, radius * 2, radius * 2)
        val found = reader.level.getEntities(except, box) { it.isAlive && it.position().distanceToSqr(center) <= radius * radius }
        return found.filter { it is net.minecraft.world.entity.LivingEntity }
            .groupBy { it.typeId }
            .map { (type, list) ->
                val sorted = list.sortedBy { it.position().distanceToSqr(center) }
                val names = sorted.mapNotNull { if (it is Player) it.plainTextName else it.customName?.string }
                EntityGroup(type, sorted.first().type.category.serializedName, list.size, sorted.first().position(), names)
            }
            .sortedBy { it.nearest.distanceToSqr(center) }
    }

    /** Runs [action] on the chunk coordinates of the chunks within [radius] blocks of [center] (horizontally). */
    private inline fun forEachChunk(center: BlockPos, radius: Int, action: (Int, Int) -> Unit) {
        for (cx in SectionPos.blockToSectionCoord(center.x - radius)..SectionPos.blockToSectionCoord(center.x + radius)) {
            for (cz in SectionPos.blockToSectionCoord(center.z - radius)..SectionPos.blockToSectionCoord(center.z + radius)) {
                action(cx, cz)
            }
        }
    }
}
