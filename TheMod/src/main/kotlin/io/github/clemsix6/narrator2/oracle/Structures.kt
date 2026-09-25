package io.github.clemsix6.narrator2.oracle

import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.TagKey
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.levelgen.LegacyRandomSource
import net.minecraft.world.level.levelgen.WorldgenRandom
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.level.levelgen.structure.Structure
import net.minecraft.world.level.levelgen.structure.StructureCheckResult
import net.minecraft.world.level.levelgen.structure.StructureSet
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A structure seen from a point.
 *
 * @property id e.g. `minecraft:village_plains`.
 * @property at the structure's centre when [exact], else the middle of its start chunk (y of the viewer).
 * @property distance blocks from the viewer to the structure's box (0 inside it), or to [at] when not [exact].
 */
data class StructureSighting(val id: String, val at: BlockPos, val distance: Int, val exact: Boolean)

/**
 * Where a structure or a biome was located.
 *
 * @property id the structure or biome found, e.g. `minecraft:village_taiga`.
 * @property y known only for a loaded structure (its centre) or a biome; null otherwise.
 * @property generated the structure exists in the world's files; false when it is predicted from the
 *   world's seed (it will generate there when the area is explored).
 */
data class Located(val id: String, val x: Int, val y: Int?, val z: Int, val generated: Boolean)

/**
 * Structures around a player, in three levels, from free to costly (ORACLE_ACTIONS_API.md §3.4):
 * 1. [at]: the structure a position is in, from its loaded chunk;
 * 2. [nearby]: the structures the loaded chunks around a point refer to, all in memory;
 * 3. [StructureSearch] (`locate`): the nearest structure of a kind, anywhere, found from the world's
 *    seed. The slowest: it runs in slices on the server thread, and its results are cached.
 *
 * None of them ever loads or generates a chunk. The game's own `/locate` generates the chunks it
 * doubts about up to their structure starts; the search here predicts the structure instead, with the
 * game's own rules (its structure checks, then the weighted choice within the structure set).
 * Server thread only, except the cache.
 */
object Structures {
    /** The structure with a piece at [pos], e.g. `minecraft:village_plains`; null outside any, or when its start chunk is not loaded. */
    fun at(reader: WorldReader, pos: BlockPos): String? {
        val chunk = reader.chunkAt(pos) ?: return null
        val registry = reader.level.registryAccess().lookupOrThrow(Registries.STRUCTURE)
        for ((structure, starts) in chunk.allReferences) {
            val it = starts.iterator()
            while (it.hasNext()) {
                val startChunk = ChunkPos.unpack(it.nextLong())
                val start = reader.chunk(startChunk.x(), startChunk.z())?.getStartForStructure(structure) ?: continue
                if (!start.isValid || !start.boundingBox.isInside(pos)) continue
                if (start.pieces.any { piece -> piece.boundingBox.isInside(pos) }) return registry.getKey(structure)?.toString()
            }
        }
        return null
    }

    /**
     * The structures the loaded chunks within [chunkRadius] chunks of [center] refer to, nearest first,
     * one per structure id. Reads what the chunks hold in memory; a structure whose start chunk is not
     * loaded is placed at its start chunk (not [StructureSighting.exact]).
     */
    fun nearby(reader: WorldReader, center: BlockPos, chunkRadius: Int): List<StructureSighting> {
        val registry = reader.level.registryAccess().lookupOrThrow(Registries.STRUCTURE)
        val best = HashMap<String, StructureSighting>()
        val seen = HashSet<Pair<Structure, Long>>()
        val middle = ChunkPos.containing(center)
        for (cx in middle.x() - chunkRadius..middle.x() + chunkRadius) {
            for (cz in middle.z() - chunkRadius..middle.z() + chunkRadius) {
                val chunk = reader.chunk(cx, cz) ?: continue
                for ((structure, starts) in chunk.allReferences) {
                    val it = starts.iterator()
                    while (it.hasNext()) {
                        val packed = it.nextLong()
                        if (!seen.add(structure to packed)) continue
                        val id = registry.getKey(structure)?.toString() ?: continue
                        val sighting = sighting(reader, center, structure, ChunkPos.unpack(packed), id) ?: continue
                        val previous = best[id]
                        if (previous == null || sighting.distance < previous.distance) best[id] = sighting
                    }
                }
            }
        }
        return best.values.sortedBy { it.distance }
    }

    private fun sighting(reader: WorldReader, center: BlockPos, structure: Structure, startChunk: ChunkPos, id: String): StructureSighting? {
        val loaded = reader.chunk(startChunk.x(), startChunk.z())
        if (loaded != null) {
            val start = loaded.getStartForStructure(structure) ?: return null
            if (!start.isValid) return null
            val box = start.boundingBox
            return StructureSighting(id, box.center, distanceToBox(center, box), exact = true)
        }
        val at = BlockPos(startChunk.middleBlockX, center.y, startChunk.middleBlockZ)
        return StructureSighting(id, at, sqrt(at.distSqr(center)).toInt(), exact = false)
    }

    private fun distanceToBox(pos: BlockPos, box: BoundingBox): Int {
        val dx = max(0, max(box.minX() - pos.x, pos.x - box.maxX()))
        val dy = max(0, max(box.minY() - pos.y, pos.y - box.maxY()))
        val dz = max(0, max(box.minZ() - pos.z, pos.z - box.maxZ()))
        return sqrt((dx.toLong() * dx + dy.toLong() * dy + dz.toLong() * dz).toDouble()).toInt()
    }

    // ── Level 3: locate ──────────────────────────────────────────────────────────────────────────────

    /** What a structure or biome name written by the model stands for. */
    sealed interface Target {
        /** How the result names what was asked, e.g. `#village`, `stronghold`. */
        val label: String

        data class Structures(override val label: String, val holders: List<Holder<Structure>>) : Target

        data class Biomes(override val label: String, val holders: List<Holder<Biome>>) : Target
    }

    /**
     * The structures [raw] names in [level]'s registry: an id (`village_plains`, `minecraft:stronghold`)
     * or a tag (`#village`); a bare word that is a tag's name and not an id (`village`) is the tag. A
     * failure carries an English message listing what exists.
     */
    fun structureTarget(level: ServerLevel, raw: String): Result<Target.Structures> {
        val registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE)
        val name = raw.trim().lowercase()
        val tagName = name.removePrefix("#")
        if (!name.startsWith("#")) {
            val id = Identifier.tryParse(name)
            val holder = id?.let { registry.get(it).orElse(null) }
            if (holder != null) return Result.success(Target.Structures(OracleText.bare(id.toString()), listOf(holder)))
        }
        val tag = Identifier.tryParse(tagName)?.let { registry.get(TagKey.create(Registries.STRUCTURE, it)).orElse(null) }
        if (tag != null) return Result.success(Target.Structures("#" + OracleText.bare(tag.key().location().toString()), tag.stream().toList()))
        val ids = registry.keySet().map { OracleText.bare(it.toString()) }.sorted()
        val tags = registry.tags.map { "#" + OracleText.bare(it.key().location().toString()) }.toList().sorted()
        return Result.failure(IllegalArgumentException(unknown("structure", name, ids, tags)))
    }

    /**
     * The biomes [raw] names: an id (`cherry_grove`) or a tag (`#is_ocean`); a bare word that is no id
     * but names an `is_…` tag (`ocean`) is that tag.
     */
    fun biomeTarget(level: ServerLevel, raw: String): Result<Target.Biomes> {
        val registry = level.registryAccess().lookupOrThrow(Registries.BIOME)
        val name = raw.trim().lowercase()
        if (!name.startsWith("#")) {
            val id = Identifier.tryParse(name)
            val holder = id?.let { registry.get(it).orElse(null) }
            if (holder != null) return Result.success(Target.Biomes(OracleText.bare(id.toString()), listOf(holder)))
        }
        val tagName = name.removePrefix("#")
        val candidates = if (name.startsWith("#") || ':' in tagName) listOf(tagName) else listOf(tagName, "is_$tagName")
        for (candidate in candidates) {
            val tag = Identifier.tryParse(candidate)?.let { registry.get(TagKey.create(Registries.BIOME, it)).orElse(null) } ?: continue
            return Result.success(Target.Biomes("#" + OracleText.bare(tag.key().location().toString()), tag.stream().toList()))
        }
        val ids = registry.keySet().map { OracleText.bare(it.toString()) }.sorted()
        val tags = registry.tags.map { "#" + OracleText.bare(it.key().location().toString()) }.toList().filter { it.startsWith("#is_") }.sorted()
        return Result.failure(IllegalArgumentException(unknown("biome", name, ids, tags)))
    }

    private fun unknown(kind: String, name: String, ids: List<String>, tags: List<String>): String {
        val word = name.removePrefix("#").substringAfter(':')
        val close = (ids + tags).filter { word.isNotEmpty() && (word in it || it.removePrefix("#") in word) }
        val listed = close.ifEmpty { ids + tags }
        return "unknown $kind \"$name\"; ${if (close.isEmpty()) "known" else "close"}: ${listed.joinToString()}"
    }

    /** Results of [StructureSearch] and of biome searches, per world, dimension, target, 512-block region and radius. */
    private val cache = ConcurrentHashMap<CacheKey, CachedResult>()

    private const val CACHE_MAX = 512

    private data class CacheKey(val seed: Long, val dimension: String, val target: String, val regionX: Int, val regionZ: Int, val radius: Int)

    private class CachedResult(val located: Located?)

    /** A cached result for a search of [target] from [origin]: the first of the pair says whether there is one. Any thread. */
    fun cached(level: ServerLevel, target: String, origin: BlockPos, radius: Int): Pair<Boolean, Located?> {
        val hit = cache[key(level, target, origin, radius)] ?: return false to null
        return true to hit.located
    }

    /** Keeps the result of a search. Any thread. */
    fun remember(level: ServerLevel, target: String, origin: BlockPos, radius: Int, located: Located?) {
        if (cache.size >= CACHE_MAX) cache.clear()
        cache[key(level, target, origin, radius)] = CachedResult(located)
    }

    private fun key(level: ServerLevel, target: String, origin: BlockPos, radius: Int) =
        CacheKey(level.seed, level.dimension().identifier().toString(), target, origin.x shr 9, origin.z shr 9, radius)

    /**
     * The nearest biome of [target] from [origin] within [radiusBlocks], sampled every 32 blocks
     * horizontally and 64 vertically (as `/locate biome`). Pure computation from the world's noise: it
     * reads no chunk, and may run on any thread (the game's world generation samples the same noise on
     * its worker threads).
     */
    fun findBiome(level: ServerLevel, target: Target.Biomes, origin: BlockPos, radiusBlocks: Int): Located? {
        val wanted = target.holders.toSet()
        val source = level.chunkSource.generator.biomeSource
        val found = source.findClosestBiome3d(origin, radiusBlocks, BIOME_STEP, BIOME_VERTICAL_STEP, { it in wanted }, level.chunkSource.randomState(), level)
            ?: return null
        val pos = found.first
        return Located(found.second.registeredName, pos.x, pos.y, pos.z, generated = true)
    }

    private const val BIOME_STEP = 32
    private const val BIOME_VERTICAL_STEP = 64
}

/**
 * The search behind `locate` for a structure: the nearest structure of [targets] around [origin], out to
 * [radius] placement cells, done in slices ([advance]) so that the server thread is never held long.
 *
 * It follows the game's `ChunkGenerator.findNearestMapStructure` (candidate chunks of each placement,
 * ring by ring for the spread ones, all positions for the concentric rings of strongholds), and decides
 * for each candidate without loading anything:
 * - a loaded chunk: its structure starts;
 * - otherwise the game's structure check (`StructureManager.checkStructurePresence`): what the chunk's
 *   file says when the chunk was generated (a small disk read, as `/locate` does), or, for a chunk never
 *   generated, whether the structure can generate there (biome, terrain);
 * - for a chunk never generated, the structure the game will choose there: the placement's own rules
 *   (frequency, exclusion zones), then the set's weighted choice with the chunk's seed. A nether fortress
 *   and a bastion share a set: only one of them generates in a given chunk.
 *
 * Server thread only.
 */
class StructureSearch(private val level: ServerLevel, targets: List<Holder<Structure>>, private val origin: BlockPos, private val radius: Int) {
    private val manager = level.structureManager()
    private val state = level.chunkSource.generatorState
    private val seed = state.levelSeed
    private val originChunk = ChunkPos.containing(origin)
    private val registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE)

    /** The placements of the targets and which targets each one places. */
    private val placements: Map<StructurePlacement, Set<Holder<Structure>>>
    private val spread: List<Pair<RandomSpreadStructurePlacement, Set<Holder<Structure>>>>
    private val rings: ArrayDeque<Triple<ChunkPos, StructurePlacement, Set<Holder<Structure>>>>

    private var ring = 0
    private var cells = ringCells(0)
    private var ringHit = false
    private var spreadIndex = 0
    private var cellIndex = 0

    /** Candidate chunks examined so far. */
    var examined = 0
        private set

    /** The nearest structure found so far; final once [done]. */
    var best: Located? = null
        private set
    private var bestDistanceSq = Long.MAX_VALUE

    var done = false
        private set

    init {
        val grouped = LinkedHashMap<StructurePlacement, MutableSet<Holder<Structure>>>()
        for (holder in targets) {
            for (placement in state.getPlacementsForStructure(holder)) grouped.getOrPut(placement) { LinkedHashSet() } += holder
        }
        placements = grouped
        spread = grouped.entries.mapNotNull { (placement, holders) -> (placement as? RandomSpreadStructurePlacement)?.let { it to holders } }
        val ringCandidates = grouped.entries.flatMap { (placement, holders) ->
            val positions = (placement as? ConcentricRingsStructurePlacement)?.let { state.getRingPositionsFor(it) }.orEmpty()
            positions.map { Triple(it, placement, holders) }
        }
        rings = ArrayDeque(ringCandidates.sortedBy { horizontalDistanceSq(it.first.middleBlockX, it.first.middleBlockZ) })
        done = grouped.isEmpty()
    }

    /** No placement generates the targets in this dimension. */
    val impossible: Boolean get() = placements.isEmpty()

    /** Examines candidates until the search ends or [budgetNanos] have passed; returns [done]. */
    fun advance(budgetNanos: Long): Boolean {
        val until = System.nanoTime() + budgetNanos
        while (!done && System.nanoTime() < until) step()
        return done
    }

    private fun step() {
        // Strongholds first: their positions are known, sorted by distance; the first found is the nearest.
        if (rings.isNotEmpty()) {
            val (chunk, placement, holders) = rings.removeFirst()
            examined++
            val distance = horizontalDistanceSq(chunk.middleBlockX, chunk.middleBlockZ)
            if (distance >= bestDistanceSq) {
                rings.clear()
            } else {
                val hit = presence(chunk, placement, holders)
                if (hit != null) {
                    offer(hit)
                    rings.clear()
                }
            }
            if (rings.isNotEmpty() || spread.isNotEmpty()) return
            done = true
            return
        }
        if (spread.isEmpty() || ring > radius) {
            done = true
            return
        }
        val (placement, holders) = spread[spreadIndex]
        if (cellIndex < cells.size) {
            val (dx, dz) = cells[cellIndex++]
            val spacing = placement.spacing()
            val candidate = placement.getPotentialStructureChunk(seed, originChunk.x() + spacing * dx, originChunk.z() + spacing * dz)
            examined++
            val hit = presence(candidate, placement, holders)
            if (hit != null) {
                offer(hit)
                ringHit = true
                cellIndex = cells.size // like the game: the first hit of a placement in a ring
            }
            return
        }
        // This placement's ring is done: the next placement, or the next ring.
        cellIndex = 0
        spreadIndex++
        if (spreadIndex < spread.size) return
        spreadIndex = 0
        if (ringHit) {
            done = true
            return
        }
        ring++
        cells = ringCells(ring)
    }

    private fun offer(hit: Located) {
        val distance = horizontalDistanceSq(hit.x, hit.z)
        if (distance < bestDistanceSq) {
            bestDistanceSq = distance
            best = hit
        }
    }

    /** Whether one of [holders] starts in [chunk] (see the class comment); never loads the chunk. */
    private fun presence(chunk: ChunkPos, placement: StructurePlacement, holders: Set<Holder<Structure>>): Located? {
        val loaded = level.chunkSource.getChunkNow(chunk.x(), chunk.z())
        if (loaded != null) {
            for (holder in holders) {
                val start = loaded.getStartForStructure(holder.value()) ?: continue
                if (!start.isValid) continue
                val center = start.boundingBox.center
                return Located(holder.registeredName, center.x, center.y, center.z, generated = true)
            }
            return null
        }
        var possible = false
        for (holder in holders) {
            when (manager.checkStructurePresence(chunk, holder.value(), placement, false)) {
                StructureCheckResult.START_PRESENT -> return locatedAt(chunk, placement, holder, generated = true)
                StructureCheckResult.CHUNK_LOAD_NEEDED -> possible = true
                else -> Unit
            }
        }
        if (!possible || !placement.isStructureChunk(state, chunk.x(), chunk.z())) return null
        val chosen = chosenIn(setOf(placement) ?: return null, chunk, placement) ?: return null
        return if (chosen in holders) locatedAt(chunk, placement, chosen, generated = false) else null
    }

    /** The structure set a placement belongs to. */
    private fun setOf(placement: StructurePlacement): StructureSet? =
        state.possibleStructureSets().firstOrNull { it.value().placement() === placement }?.value()

    /**
     * The structure of [set] the game will generate in [chunk], never generated yet: the game draws one
     * by weight with the chunk's seed, and draws again without it when it cannot generate there
     * (`ChunkGenerator.createStructures`).
     */
    private fun chosenIn(set: StructureSet, chunk: ChunkPos, placement: StructurePlacement): Holder<Structure>? {
        val entries = set.structures()
        if (entries.size == 1) return entries[0].structure().takeIf { canGenerate(it, chunk, placement) }
        val left = entries.toMutableList()
        var total = left.sumOf { it.weight() }
        val random = WorldgenRandom(LegacyRandomSource(0L))
        random.setLargeFeatureSeed(seed, chunk.x(), chunk.z())
        while (left.isNotEmpty()) {
            var pick = random.nextInt(total)
            var index = 0
            while (index < left.size - 1) {
                pick -= left[index].weight()
                if (pick < 0) break
                index++
            }
            val entry = left[index]
            if (canGenerate(entry.structure(), chunk, placement)) return entry.structure()
            left.removeAt(index)
            total -= entry.weight()
        }
        return null
    }

    private fun canGenerate(holder: Holder<Structure>, chunk: ChunkPos, placement: StructurePlacement): Boolean =
        manager.checkStructurePresence(chunk, holder.value(), placement, false) != StructureCheckResult.START_NOT_PRESENT

    private fun locatedAt(chunk: ChunkPos, placement: StructurePlacement, holder: Holder<Structure>, generated: Boolean): Located {
        val pos = placement.getLocatePos(chunk) // the start chunk's corner, at y = 0 (javap): no height known
        val id = registry.getKey(holder.value())?.toString() ?: holder.registeredName
        return Located(id, pos.x, null, pos.z, generated)
    }

    private fun horizontalDistanceSq(x: Int, z: Int): Long {
        val dx = (x - origin.x).toLong()
        val dz = (z - origin.z).toLong()
        return dx * dx + dz * dz
    }

    private companion object {
        /** The cells of the square ring at [ring] cells from the centre, in the game's order. */
        fun ringCells(ring: Int): List<Pair<Int, Int>> {
            val cells = ArrayList<Pair<Int, Int>>()
            for (dx in -ring..ring) {
                val edgeX = dx == -ring || dx == ring
                for (dz in -ring..ring) {
                    if (edgeX || dz == -ring || dz == ring) cells += dx to dz
                }
            }
            return cells
        }
    }
}
