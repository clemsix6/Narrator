package io.github.clemsix6.narrator2.tool.oracle

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.journal.Journal
import io.github.clemsix6.narrator2.mixin.observer.StatsCounterAccessor
import io.github.clemsix6.narrator2.observer.id
import io.github.clemsix6.narrator2.oracle.AreaScan
import io.github.clemsix6.narrator2.oracle.BlockHit
import io.github.clemsix6.narrator2.oracle.Enclosure
import io.github.clemsix6.narrator2.oracle.EntityGroup
import io.github.clemsix6.narrator2.oracle.Found
import io.github.clemsix6.narrator2.oracle.Located
import io.github.clemsix6.narrator2.oracle.OracleText
import io.github.clemsix6.narrator2.oracle.Ores
import io.github.clemsix6.narrator2.oracle.PlacedCopy
import io.github.clemsix6.narrator2.oracle.PlacedCount
import io.github.clemsix6.narrator2.oracle.PlayerReport
import io.github.clemsix6.narrator2.oracle.PlayerSnapshot
import io.github.clemsix6.narrator2.oracle.SectionCopies
import io.github.clemsix6.narrator2.oracle.Spot
import io.github.clemsix6.narrator2.oracle.StatSummary
import io.github.clemsix6.narrator2.oracle.StructureSearch
import io.github.clemsix6.narrator2.oracle.StructureSighting
import io.github.clemsix6.narrator2.oracle.Structures
import io.github.clemsix6.narrator2.oracle.WorldReader
import io.github.clemsix6.narrator2.oracle.WorldSnapshot
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Doc
import io.github.clemsix6.narrator2.tool.Items
import io.github.clemsix6.narrator2.tool.MaxLen
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.NoArgs
import io.github.clemsix6.narrator2.tool.Range
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.BlockItemTags
import net.minecraft.tags.FluidTags
import net.minecraft.world.item.BlockItem
import net.minecraft.world.level.block.AbstractBedBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BedPart
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.Vec3
import java.util.Locale
import java.util.UUID

/**
 * The Oracle's tools, which read the world (M1_M2 §9.2): `list_players`, `inspect_player`, `scan_area`,
 * `area_info`, `locate`, `player_stats`. `ToolCatalog` builds the family once per conversation.
 *
 * They read loaded chunks only, never load or generate one, and hold the server thread briefly: block
 * by block reading happens on copies, off the server thread (`oracle/Ores`), and `locate` searches in
 * slices. Each call logs how long it held the server thread.
 */
@Suppress("UNUSED_PARAMETER")
fun oracleTools(services: NarratorServices): List<NarratorTool<*>> =
    listOf(ListPlayersTool(), InspectPlayerTool(), ScanAreaTool(), AreaInfoTool(), LocateTool(), PlayerStatsTool())

// ── list_players ─────────────────────────────────────────────────────────────────────────────────────

class ListPlayersTool : NarratorTool<NoArgs>(
    name = "list_players",
    description = "Every player online, one line each: dimension and position, biome, surroundings (open_sky, sheltered, " +
        "underground or cave; open or enclosed under the Nether's ceiling), the structure they are in, health and food, time " +
        "online, what they did in the last 5 minutes, and the nearest other player. The first line gives the day, time and weather.",
    args = NoArgs.serializer(),
    effect = ToolEffect.READ,
) {
    override suspend fun run(ctx: ToolContext, args: NoArgs): ToolResult {
        val cost = OracleCost(name)
        val (world, uuids) = cost.server(ctx) { server -> WorldSnapshot.capture(server, players = false) to server.playerList.players.map { it.uuid } }
        // One server task per player: a crowded server is read in short slices.
        val players = uuids.mapNotNull { uuid -> cost.server(ctx) { server -> server.playerList.getPlayer(uuid)?.let { PlayerSnapshot.capture(it) } } }
        val now = ctx.clock.millis()
        val lines = players.map { player ->
            val live = ctx.players.get(player.ref.uuid)
            listLine(player, players, live?.let { now - it.joinedAt }, live?.idle == true, ctx.services.director.recentActivity(player.ref.uuid, 5))
        }
        cost.log("${players.size} players")
        val count = when (players.size) {
            0 -> "no players online"
            1 -> "1 player online"
            else -> "${players.size} players online"
        }
        return ToolResult.ok((listOf("${world.describe()} · $count") + lines).joinToString("\n"))
    }
}

/**
 * One line of `list_players`: `Bartok: overworld -120 -58 330 · dripstone_caves · cave, 41 below
 * surface · hp 14/20 food 12 · online 1h12 · last 5m: mined 140 · nearest Mimosa 230 m NE`.
 */
internal fun listLine(player: PlayerSnapshot, all: List<PlayerSnapshot>, onlineMs: Long?, idle: Boolean, activity: String?): String {
    val parts = mutableListOf("${player.ref.name}: ${player.describe()}")
    onlineMs?.let { parts += "online ${OracleText.duration(it)}" }
    if (idle) parts += "idle"
    activity?.let { parts += "last 5m: $it" }
    val nearest = all.filter { it !== player }.mapNotNull { other -> player.distanceTo(other)?.let { other to it } }.minByOrNull { it.second }
    if (nearest != null) {
        val (other, _) = nearest
        parts += "nearest ${other.ref.name} ${OracleText.relative(other.x - player.x, other.y - player.y, other.z - player.z)}"
    } else if (all.size > 1) {
        parts += "alone in this dimension"
    }
    return parts.joinToString(" · ")
}

// ── inspect_player ───────────────────────────────────────────────────────────────────────────────────

@Serializable
enum class InspectSection {
    @SerialName("position") POSITION,
    @SerialName("surroundings") SURROUNDINGS,
    @SerialName("equipment") EQUIPMENT,
    @SerialName("inventory") INVENTORY,
    @SerialName("effects") EFFECTS,
    @SerialName("recent") RECENT,
}

@Serializable
data class InspectPlayerArgs(
    @Doc("Name of a player online.") @MaxLen(40) val player: String,
    @Doc("Sections to read: position, surroundings, equipment, inventory, effects, recent. All but inventory when left out.")
    @Items(1, 6)
    val include: List<InspectSection>? = null,
)

class InspectPlayerTool : NarratorTool<InspectPlayerArgs>(
    name = "inspect_player",
    description = "Everything about one player online. position: coordinates, biome, depth below the surface, light, " +
        "whether they are in a cave, the structure they are in, what they face and look at. surroundings: creatures and " +
        "players within 24 blocks, structures nearby, danger. equipment, inventory, effects (and yours on them, with " +
        "their end), recent: their last notable facts from the Journal. All sections but inventory by default.",
    args = InspectPlayerArgs.serializer(),
    effect = ToolEffect.READ,
) {
    override suspend fun run(ctx: ToolContext, args: InspectPlayerArgs): ToolResult {
        val live = ctx.players.byName(args.player) ?: return ctx.notOnline(args.player)
        val sections = args.include?.toSet() ?: DEFAULT_SECTIONS
        val cost = OracleCost(name)
        val now = ctx.clock.millis()
        val mine = if (InspectSection.EFFECTS in sections) ctx.guards.activeOn(live.ref.uuid) else emptyList()
        val lines = cost.server(ctx) { server ->
            val player = server.playerList.getPlayer(live.ref.uuid) ?: return@server null
            val report = PlayerReport(player)
            buildList<String> {
                add(report.header(now - live.joinedAt, live.idle))
                if (InspectSection.POSITION in sections) addAll(report.position())
                if (InspectSection.SURROUNDINGS in sections) addAll(report.surroundings())
                if (InspectSection.EQUIPMENT in sections) add(report.equipment())
                if (InspectSection.INVENTORY in sections) add(report.inventory())
                if (InspectSection.EFFECTS in sections) addAll(report.effects(mine, now))
            }
        } ?: return ctx.notOnline(args.player)
        val recent = if (InspectSection.RECENT in sections) recentFacts(ctx.services.journal, live.ref.uuid, now) else emptyList()
        cost.log("sections ${sections.joinToString { it.name.lowercase() }}")
        return ToolResult.ok((lines + recent).joinToString("\n"), targets = listOf(live.ref))
    }

    private companion object {
        val DEFAULT_SECTIONS = InspectSection.entries.toSet() - InspectSection.INVENTORY
    }
}

/** Notable facts of a player, most recent first: deaths, kills, progress, travels, first visits, precious ores. */
private const val RECENT_SQL = """
SELECT epoch_ms(ts), type, subject, other, dim, x, y, z
FROM events
WHERE player_uuid = CAST(? AS UUID)
  AND (type IN ('death', 'kill', 'advancement', 'dimension_change', 'teleport', 'structure_enter', 'trade', 'enchant',
                'anvil', 'tame', 'breed', 'loot_open', 'portal_lit', 'end_portal_lit', 'respawn', 'spawn_set', 'health_low',
                'sleep_start', 'fish', 'join', 'leave')
       OR (type = 'biome_enter' AND (detail ->> 'first_visit') = 'true')
       OR (type = 'block_break' AND subject IN ('minecraft:diamond_ore', 'minecraft:deepslate_diamond_ore',
           'minecraft:ancient_debris', 'minecraft:emerald_ore', 'minecraft:deepslate_emerald_ore')))
ORDER BY id DESC
LIMIT 10
"""

private const val JOURNAL_TIMEOUT_MS = 3_000L

/** How long the recent facts wait for the Journal's writer to commit what is queued (it writes at once on a barrier). */
private const val BARRIER_TIMEOUT_MS = 1_000L

/**
 * `recent (Journal):` then `[-3m] block_break deepslate_diamond_ore (overworld -120 -54 330)` lines,
 * the facts of the last second included. Off the server thread.
 */
internal suspend fun recentFacts(journal: Journal, uuid: UUID, now: Long): List<String> {
    val rows = try {
        if (journal.available) withTimeoutOrNull(BARRIER_TIMEOUT_MS) { journal.barrier() }
        journal.read(JOURNAL_TIMEOUT_MS) { connection ->
            connection.prepareStatement(RECENT_SQL).use { statement ->
                statement.queryTimeout = (JOURNAL_TIMEOUT_MS / 1000).toInt()
                statement.setString(1, uuid.toString())
                statement.executeQuery().use { rs ->
                    buildList<String> {
                        while (rs.next()) {
                            val what = listOfNotNull(rs.getString(3), rs.getString(4)).joinToString(" → ") { OracleText.bare(it) }
                            val where = rs.getString(5)?.let { " (${OracleText.dimension(it)} ${rs.getInt(6)} ${rs.getInt(7)} ${rs.getInt(8)})" }.orEmpty()
                            add("${OracleText.ago(now - rs.getLong(1))} ${rs.getString(2)} $what$where".replace("  ", " "))
                        }
                    }
                }
            }
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        return listOf("recent (Journal): (unavailable: ${e.message ?: e.javaClass.simpleName})")
    }
    return listOf("recent (Journal):") + rows.ifEmpty { listOf("(nothing notable yet)") }
}

// ── scan_area ────────────────────────────────────────────────────────────────────────────────────────

@Serializable
enum class ScanFind {
    @SerialName("ores") ORES,
    @SerialName("diamonds") DIAMONDS,
    @SerialName("ancient_debris") ANCIENT_DEBRIS,
    @SerialName("containers") CONTAINERS,
    @SerialName("spawners") SPAWNERS,
    @SerialName("lava") LAVA,
    @SerialName("beds") BEDS,
    @SerialName("portals") PORTALS,
    @SerialName("player_blocks") PLAYER_BLOCKS,
}

@Serializable
data class ScanAreaArgs(
    @Doc("What to look for. player_blocks finds blocks placed by players: a base.") val find: ScanFind,
    @Doc("Centre on this player online; or give x, y and z instead.") @MaxLen(40) val player: String? = null,
    @Doc("Centre x (with y and z), instead of a player.") @Range(-30_000_000.0, 30_000_000.0) val x: Int? = null,
    @Doc("Centre y.") @Range(-2048.0, 2048.0) val y: Int? = null,
    @Doc("Centre z.") @Range(-30_000_000.0, 30_000_000.0) val z: Int? = null,
    @Doc("Dimension of x, y, z: overworld (the default), nether or end.") @MaxLen(40) val dimension: String? = null,
    @Doc("Radius in blocks.") @Range(4.0, 48.0) val radius: Int = 24,
) {
    init {
        requireCenter(player, x, y, z)
    }
}

class ScanAreaTool : NarratorTool<ScanAreaArgs>(
    name = "scan_area",
    description = "Finds blocks of interest around a player or a point, in the chunks loaded near players: ores, diamonds, " +
        "ancient_debris, containers (with what they hold, and loot chests never opened), spawners, lava, beds (and whose " +
        "respawn point), portals, or player_blocks (blocks players placed: their bases). Gives how many, and the nearest " +
        "with coordinates, distance and direction.",
    args = ScanAreaArgs.serializer(),
    effect = ToolEffect.READ,
) {
    override suspend fun run(ctx: ToolContext, args: ScanAreaArgs): ToolResult {
        if (args.player != null && ctx.players.byName(args.player) == null) return ctx.notOnline(args.player)
        val cost = OracleCost(name)
        val radius = args.radius
        val read = cost.server(ctx) { server ->
            val center = Center.resolve(ctx, server, args.player, args.x, args.y, args.z, args.dimension).getOrElse { return@server Failure(it.message!!) }
            val reader = WorldReader(center.level)
            when (args.find) {
                ScanFind.CONTAINERS -> SpotsRead(center, AreaScan.blockEntities(reader, center.pos, radius, AreaScan::container), chunkCount(reader, center.pos, radius))
                ScanFind.SPAWNERS -> SpotsRead(center, AreaScan.blockEntities(reader, center.pos, radius, AreaScan::spawner), chunkCount(reader, center.pos, radius))
                ScanFind.PLAYER_BLOCKS -> {
                    val placements = AreaScan.copyPlacements(reader, center.pos, radius)
                    val names = placerNames(ctx, server, placements.flatMap { it.placers }.toSet())
                    PlacedRead(center, placements, Ores.copy(reader, center.pos, radius, null), names)
                }
                else -> {
                    val respawns = if (args.find == ScanFind.BEDS) respawnPoints(server, center.level) else emptyMap()
                    BlocksRead(center, Ores.copy(reader, center.pos, radius, blocksOf(args.find)), respawns)
                }
            }
        }
        val text = when (read) {
            is Failure -> return ToolResult.invalid(read.message)
            is SpotsRead -> {
                if (read.loaded.second == 0) return nothingLoaded(read.center, radius)
                spotsText(args.find.name.lowercase(), read.center, radius, read.spots, read.loaded)
            }
            is BlocksRead -> {
                if (read.copies.loadedChunks == 0) return nothingLoaded(read.center, radius)
                val wanted = blocksOf(args.find)
                val max = if (args.find == ScanFind.PORTALS) PORTAL_BLOCKS else MAX_SPOTS
                val found = cost.worker { Ores.find(read.copies, read.center.pos, radius, wanted, max) }
                blocksText(args.find, read, radius, found)
            }
            is PlacedRead -> {
                if (read.blocks.loadedChunks == 0) return nothingLoaded(read.center, radius)
                val count = cost.worker { AreaScan.countPlaced(read.placements, read.blocks, read.center.pos, radius, MAX_SPOTS) }
                placedText(read, radius, count)
            }
        }
        cost.log("${args.find.name.lowercase()} r=$radius")
        return ToolResult.ok(text)
    }

    private sealed interface Read

    private class Failure(val message: String) : Read

    private class SpotsRead(val center: Center, val spots: List<Spot>, val loaded: Pair<Int, Int>) : Read

    private class BlocksRead(val center: Center, val copies: SectionCopies, val respawns: Map<BlockPos, List<String>>) : Read

    private class PlacedRead(
        val center: Center,
        val placements: List<PlacedCopy>,
        val blocks: SectionCopies,
        val names: Map<UUID, String>,
    ) : Read

    private fun blocksText(find: ScanFind, read: BlocksRead, radius: Int, found: Found): String {
        val label = find.name.lowercase()
        val header = "$label within $radius blocks of ${read.center.label}: "
        val loaded = loadedNote(read.copies.loadedChunks, read.copies.chunks)
        if (found.total == 0) return header + "none" + loaded
        return when (find) {
            ScanFind.PORTALS -> {
                val portals = portalGroups(found.nearest)
                header + "${portals.size} portal${if (portals.size > 1) "s" else ""}$loaded\n" +
                    portals.take(MAX_SPOTS).joinToString("\n") { hitLine(it.first, it.second, read.center.pos) }
            }
            ScanFind.BEDS -> header + "${found.total}$loaded\n" + found.nearest.joinToString("\n") { hit ->
                val owners = read.respawns.entries.filter { it.key.distSqr(hit.pos) <= 2 }.flatMap { it.value }
                val whose = if (owners.isEmpty()) "" else " (respawn point of ${owners.joinToString()})"
                hitLine(OracleText.bare(hit.state.block.id) + whose, hit.pos, read.center.pos)
            }
            else -> {
                val kinds = if (find == ScanFind.ORES) " (" + found.byBlock.joinToString { "${OracleText.bare(it.first.id)} ${it.second}" } + ")" else ""
                header + "${found.total}$kinds$loaded\n" + found.nearest.joinToString("\n") { hitLine(OracleText.bare(it.state.block.id), it.pos, read.center.pos) }
            }
        }
    }

    /** Portal blocks grouped into portals (blocks at most 2 apart): label and position of each, nearest first. */
    private fun portalGroups(hits: List<BlockHit>): List<Pair<String, BlockPos>> {
        val groups = ArrayList<MutableList<BlockHit>>()
        for (hit in hits) {
            val group = groups.firstOrNull { list -> list.any { it.state.block == hit.state.block && it.pos.distSqr(hit.pos) <= 4 } }
            if (group == null) groups += mutableListOf(hit) else group += hit
        }
        return groups.map { group ->
            val block = group.first().state.block
            val label = if (block == Blocks.END_PORTAL_FRAME) {
                "end_portal_frame x${group.size} (${group.count { it.state.getValue(BlockStateProperties.EYE) }} with an eye)"
            } else {
                "${OracleText.bare(block.id)} (${group.size} blocks)"
            }
            label to group.first().pos
        }
    }

    private fun placedText(read: PlacedRead, radius: Int, count: PlacedCount): String {
        val header = "player blocks within $radius blocks of ${read.center.label}: "
        val loaded = loadedNote(read.blocks.loadedChunks, read.blocks.chunks)
        if (count.total == 0) return header + "none" + loaded
        fun name(uuid: UUID) = read.names[uuid] ?: uuid.toString()
        val byPlacer = count.byPlacer.joinToString { "${name(it.first)} ${it.second}" }
        val clusters = count.clusters.joinToString("\n") { cluster ->
            val blocks = if (cluster.blocks == 1) "1 block" else "${cluster.blocks} blocks"
            hitLine("$blocks by ${name(cluster.mainPlacer)} (${cluster.topBlocks.joinToString()})", cluster.center, read.center.pos)
        }
        return "$header${count.total} ($byPlacer)$loaded\ndensest spots:\n$clusters"
    }

    private companion object {
        const val PORTAL_BLOCKS = 512

        /** What each block search wants. */
        fun blocksOf(find: ScanFind): (BlockState) -> Boolean = when (find) {
            ScanFind.ORES -> { state -> state.`is`(BlockItemTags.ORES.block()) || state.`is`(Blocks.ANCIENT_DEBRIS) }
            ScanFind.DIAMONDS -> { state -> state.`is`(BlockItemTags.DIAMOND_ORES.block()) }
            ScanFind.ANCIENT_DEBRIS -> { state -> state.`is`(Blocks.ANCIENT_DEBRIS) }
            ScanFind.LAVA -> { state -> state.fluidState.`is`(FluidTags.LAVA) }
            // Every bed, the straw bed too (it is not in the `beds` tag), counted once: by its head.
            ScanFind.BEDS -> { state ->
                state.block is AbstractBedBlock &&
                    (!state.hasProperty(BlockStateProperties.BED_PART) || state.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD)
            }
            ScanFind.PORTALS -> { state ->
                state.`is`(Blocks.NETHER_PORTAL) || state.`is`(Blocks.END_PORTAL) || state.`is`(Blocks.END_GATEWAY) || state.`is`(Blocks.END_PORTAL_FRAME)
            }
            else -> error("${find.name} is not a block search")
        }

        /** Respawn points of the players online in [level]: position → names. */
        fun respawnPoints(server: MinecraftServer, level: ServerLevel): Map<BlockPos, List<String>> =
            server.playerList.players.mapNotNull { player ->
                val data = player.respawnConfig?.respawnData() ?: return@mapNotNull null
                if (data.dimension() != level.dimension()) null else data.pos() to player.plainTextName
            }.groupBy({ it.first }, { it.second })
    }
}

/** Places a scan lists at most, nearest first. */
private const val MAX_SPOTS = 8

private fun spotsText(label: String, center: Center, radius: Int, spots: List<Spot>, loaded: Pair<Int, Int>): String {
    val header = "$label within $radius blocks of ${center.label}: "
    val note = loadedNote(loaded.second, loaded.first)
    if (spots.isEmpty()) return header + "none" + note
    return header + "${spots.size}$note\n" + spots.take(MAX_SPOTS).joinToString("\n") { hitLine(it.label, it.pos, center.pos) }
}

/** `deepslate_diamond_ore -115 -60 335 (7 m SE, 2 below)`. */
internal fun hitLine(label: String, pos: BlockPos, from: BlockPos): String =
    "$label ${OracleText.coords(pos.x, pos.y, pos.z)} (${OracleText.relative(pos.x - from.x, pos.y - from.y, pos.z - from.z)})"

/** ` (7 of 9 chunks loaded; the others were not read)`, or nothing when all were. */
private fun loadedNote(loaded: Int, chunks: Int): String =
    if (loaded >= chunks) "" else " ($loaded of $chunks chunks loaded; the others were not read)"

/** Chunks covering [radius] around [center], and how many are loaded. */
private fun chunkCount(reader: WorldReader, center: BlockPos, radius: Int): Pair<Int, Int> {
    var chunks = 0
    var loaded = 0
    for (cx in ((center.x - radius) shr 4)..((center.x + radius) shr 4)) {
        for (cz in ((center.z - radius) shr 4)..((center.z + radius) shr 4)) {
            chunks++
            if (reader.chunk(cx, cz) != null) loaded++
        }
    }
    return chunks to loaded
}

private fun nothingLoaded(center: Center, radius: Int): ToolResult =
    ToolResult.failed("no chunk is loaded within $radius blocks of ${center.label}: the Oracle reads only the areas loaded around players")

/** Names of [placers]: online players first, then the server's name cache. Server thread. */
private fun placerNames(ctx: ToolContext, server: MinecraftServer, placers: Set<UUID>): Map<UUID, String> = placers.associateWith { uuid ->
    ctx.players.get(uuid)?.ref?.name ?: server.services().nameToIdCache().get(uuid).map { it.name() }.orElse("an unknown player")
}

// ── area_info ────────────────────────────────────────────────────────────────────────────────────────

@Serializable
data class AreaInfoArgs(
    @Doc("Centre on this player online; or give x, y and z instead.") @MaxLen(40) val player: String? = null,
    @Doc("Centre x (with y and z), instead of a player.") @Range(-30_000_000.0, 30_000_000.0) val x: Int? = null,
    @Doc("Centre y.") @Range(-2048.0, 2048.0) val y: Int? = null,
    @Doc("Centre z.") @Range(-30_000_000.0, 30_000_000.0) val z: Int? = null,
    @Doc("Dimension of x, y, z: overworld (the default), nether or end.") @MaxLen(40) val dimension: String? = null,
    @Doc("Radius in blocks.") @Range(4.0, 32.0) val radius: Int = 16,
) {
    init {
        requireCenter(player, x, y, z)
    }
}

class AreaInfoTool : NarratorTool<AreaInfoArgs>(
    name = "area_info",
    description = "Sums up the area around a player or a point, in the chunks loaded near players: biomes, surroundings at " +
        "the centre (open_sky, sheltered, underground, cave; open or enclosed in the Nether), light, main blocks, creatures by " +
        "kind, structures, and the share of blocks placed by players, and by whom.",
    args = AreaInfoArgs.serializer(),
    effect = ToolEffect.READ,
) {
    override suspend fun run(ctx: ToolContext, args: AreaInfoArgs): ToolResult {
        if (args.player != null && ctx.players.byName(args.player) == null) return ctx.notOnline(args.player)
        val cost = OracleCost(name)
        val radius = args.radius
        val read = cost.server(ctx) { server ->
            val center = Center.resolve(ctx, server, args.player, args.x, args.y, args.z, args.dimension).getOrElse { return@server Result.failure(it) }
            val reader = WorldReader(center.level)
            val pos = center.pos
            val placements = AreaScan.copyPlacements(reader, pos, radius)
            Result.success(
                AreaRead(
                    center = center,
                    blocks = Ores.copy(reader, pos, radius, null),
                    placements = placements,
                    names = placerNames(ctx, server, placements.flatMap { it.placers }.toSet()),
                    biomes = sampleBiomes(reader, pos, radius),
                    centre = if (reader.chunkAt(pos) == null) {
                        "its chunk is not loaded"
                    } else {
                        "${Enclosure.describeAt(reader, pos)}, light sky ${reader.skyLight(pos)}, block ${reader.blockLight(pos)}"
                    },
                    creatures = AreaScan.entities(reader, Vec3.atCenterOf(pos), radius.toDouble(), center.player),
                    structures = Structures.nearby(reader, pos, (radius shr 4) + 1).filter { it.distance <= radius },
                ),
            )
        }.getOrElse { return ToolResult.invalid(it.message!!) }
        if (read.blocks.loadedChunks == 0) return nothingLoaded(read.center, radius)
        val counted = cost.worker {
            Ores.histogram(read.blocks, read.center.pos, radius) to AreaScan.countPlaced(read.placements, read.blocks, read.center.pos, radius, 0)
        }
        cost.log("r=$radius, ${read.blocks.sections.size} sections")
        return ToolResult.ok(areaText(read, radius, counted.first, counted.second))
    }

    private class AreaRead(
        val center: Center,
        val blocks: SectionCopies,
        val placements: List<PlacedCopy>,
        val names: Map<UUID, String>,
        val biomes: List<String>,
        val centre: String,
        val creatures: List<EntityGroup>,
        val structures: List<StructureSighting>,
    )

    private fun areaText(
        read: AreaRead,
        radius: Int,
        histogram: Map<Block, Int>,
        placed: PlacedCount,
    ): String {
        val center = read.center
        val open = histogram.filterKeys { it.defaultBlockState().isAir }.values.sum()
        val filled = histogram.filterKeys { !it.defaultBlockState().isAir }
        val filledTotal = filled.values.sum()
        val all = open + filledTotal
        val blocks = filled.entries.sortedByDescending { it.value }.take(MAIN_BLOCKS)
            .joinToString { "${OracleText.bare(it.key.id)} ${OracleText.percent(it.value.toLong(), filledTotal.toLong())}" }
        val creatures = read.creatures.filter { it.type != "minecraft:player" }.groupBy { it.category }.entries
            .sortedByDescending { entry -> entry.value.sumOf { it.count } }
            .joinToString("; ") { (category, groups) ->
                "$category ${groups.sumOf { it.count }} (" + groups.sortedByDescending { it.count }.joinToString { "${OracleText.bare(it.type)} ${it.count}" } + ")"
            }.ifEmpty { "none" }
        val players = read.creatures.filter { it.type == "minecraft:player" }.flatMap { it.names }
        val structures = read.structures.joinToString("; ") { sighting ->
            val where = if (sighting.distance == 0) "here" else OracleText.relative(sighting.at.x - center.pos.x, sighting.at.y - center.pos.y, sighting.at.z - center.pos.z)
            OracleText.bare(sighting.id) + " " + (if (sighting.exact) where else "~$where")
        }.ifEmpty { "none" }
        val placedText = if (placed.total == 0) {
            "none"
        } else {
            "${OracleText.percent(placed.total.toLong(), filledTotal.toLong())} of the blocks (${placed.total}: " +
                placed.byPlacer.joinToString { "${read.names[it.first] ?: it.first} ${it.second}" } + ")"
        }
        return listOf(
            "area within $radius blocks of ${center.label}" + loadedNote(read.blocks.loadedChunks, read.blocks.chunks),
            "biomes: ${read.biomes.joinToString()}",
            "at the centre: ${read.centre}",
            "blocks: " + (if (blocks.isEmpty()) "none" else blocks) + "; open space ${OracleText.percent(open.toLong(), all.toLong())}",
            "creatures: $creatures" + if (players.isEmpty()) "" else "; players: ${players.joinToString()}",
            "structures: $structures",
            "player blocks: $placedText",
        ).joinToString("\n")
    }

    private companion object {
        const val MAIN_BLOCKS = 8

        /** The biome at the centre, then the others met at the corners and edges of the area, above and below it, most frequent first. */
        fun sampleBiomes(reader: WorldReader, center: BlockPos, radius: Int): List<String> {
            val around = listOf(-radius, 0, radius).flatMap { dx -> listOf(-radius, 0, radius).map { dz -> center.offset(dx, 0, dz) } } +
                listOf(center.above(radius / 2), center.below(radius / 2))
            val atCenter = OracleText.bare(reader.biome(center))
            val others = around.map { OracleText.bare(reader.biome(it)) }.filter { it != atCenter }
                .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
            return listOf("$atCenter (centre)") + others
        }
    }
}

// ── locate ───────────────────────────────────────────────────────────────────────────────────────────

@Serializable
enum class LocateKind {
    @SerialName("structure") STRUCTURE,
    @SerialName("biome") BIOME,
}

@Serializable
data class LocateArgs(
    @Doc("Name of a player online: the search starts from them, in their dimension.") @MaxLen(40) val player: String,
    @Doc("structure or biome.") val kind: LocateKind,
    @Doc(
        "A structure or biome id (village_plains, fortress, bastion_remnant, ancient_city, stronghold, cherry_grove, deep_dark) " +
            "or a #tag (#village, #is_ocean); a plain word that is no id but names a tag (village, ocean) means the tag.",
    )
    @MaxLen(64)
    val target: String,
    @Doc(
        "How far to search: in placement cells for a structure (a village cell is about 540 blocks), in steps of 128 blocks " +
            "for a biome. Larger is slower.",
    )
    @Range(8.0, 32.0)
    val radius: Int = 16,
)

class LocateTool : NarratorTool<LocateArgs>(
    name = "locate",
    description = "SLOW, up to several seconds: the nearest structure (village, fortress, bastion, ancient city, stronghold, " +
        "monument…) or biome to a player, anywhere in their dimension, even where nobody has been, from the world's seed. " +
        "For what is close, prefer inspect_player (structures nearby) or scan_area.",
    args = LocateArgs.serializer(),
    effect = ToolEffect.READ,
    timeoutMs = 8_000,
) {
    override suspend fun run(ctx: ToolContext, args: LocateArgs): ToolResult {
        val live = ctx.players.byName(args.player) ?: return ctx.notOnline(args.player)
        val cost = OracleCost(name)
        val started = System.nanoTime()
        val origin = cost.server(ctx) { server ->
            server.playerList.getPlayer(live.ref.uuid)?.let { it.level() to it.blockPosition() }
        } ?: return ctx.notOnline(args.player)
        val (level, pos) = origin
        val from = "${live.ref.name} (${OracleText.dimension(level.dimension().identifier().toString())} ${OracleText.coords(pos.x, pos.y, pos.z)})"
        val result = when (args.kind) {
            LocateKind.STRUCTURE -> locateStructure(ctx, cost, level, pos, from, args)
            LocateKind.BIOME -> locateBiome(cost, level, pos, from, args)
        }
        cost.log("${args.kind.name.lowercase()} ${args.target} r=${args.radius} in ${(System.nanoTime() - started) / 1_000_000} ms")
        return result.fold({ ToolResult.ok(it) }, { ToolResult.invalid(it.message!!) })
    }

    private suspend fun locateStructure(ctx: ToolContext, cost: OracleCost, level: ServerLevel, origin: BlockPos, from: String, args: LocateArgs): Result<String> {
        val target = cost.server(ctx) { Structures.structureTarget(level, args.target) }.getOrElse { return Result.failure(it) }
        val key = "structure ${target.label}"
        val (known, cachedHit) = Structures.cached(level, key, origin, args.radius)
        if (known) return Result.success(locatedText(target.label, cachedHit, origin, from, "within ${args.radius} cells"))
        val search = cost.server(ctx) { server ->
            if (!server.worldGenSettings.options().generateStructures()) null else StructureSearch(level, target.holders, origin, args.radius)
        } ?: return Result.success("structures are not generated in this world")
        if (search.impossible) return Result.success("${target.label} does not generate in ${OracleText.dimension(level.dimension().identifier().toString())}")
        val deadline = System.nanoTime() + SEARCH_BUDGET_NANOS
        while (!cost.server(ctx) { search.advance(SLICE_NANOS) }) {
            if (System.nanoTime() > deadline) {
                val partial = search.best
                return Result.success(
                    if (partial != null) {
                        locatedText(target.label, partial, origin, from, "") + " (search cut short after ${search.examined} places; a nearer one may exist)"
                    } else {
                        "no ${target.label} found near $from before the time ran out (${search.examined} places examined); try a smaller radius"
                    },
                )
            }
        }
        Structures.remember(level, key, origin, args.radius, search.best)
        return Result.success(locatedText(target.label, search.best, origin, from, "within ${args.radius} cells") + " (${search.examined} places examined)")
    }

    /** Biome searches read the world's noise only: off the server thread, and so is the (immutable) registry. */
    private suspend fun locateBiome(cost: OracleCost, level: ServerLevel, origin: BlockPos, from: String, args: LocateArgs): Result<String> {
        val target = Structures.biomeTarget(level, args.target).getOrElse { return Result.failure(it) }
        val key = "biome ${target.label}"
        val (known, cachedHit) = Structures.cached(level, key, origin, args.radius)
        val located = if (known) cachedHit else cost.worker { Structures.findBiome(level, target, origin, args.radius * BIOME_CELL) }
        if (!known) Structures.remember(level, key, origin, args.radius, located)
        return Result.success(locatedText(target.label, located, origin, from, "within ${args.radius * BIOME_CELL} blocks"))
    }

    private companion object {
        /** Longest the search holds the server thread at a time. */
        const val SLICE_NANOS = 4_000_000L

        /** The search gives up after this long, well within the tool's 8 s. */
        const val SEARCH_BUDGET_NANOS = 6_000_000_000L

        const val BIOME_CELL = 128
    }
}

/**
 * `nearest village_taiga to Bartok (overworld 10 64 20): 812 ~ -340, 1240 m NE`, followed by
 * `(not generated yet: …)` for a prediction; or `no #village within 16 cells of …`.
 */
internal fun locatedText(label: String, located: Located?, origin: BlockPos, from: String, scope: String): String {
    if (located == null) return "no $label $scope of $from".replace("  ", " ")
    val dx = located.x - origin.x
    val dz = located.z - origin.z
    val y = located.y
    val where = if (y == null) {
        "${located.x} ~ ${located.z}, ${OracleText.distance(dx, 0, dz)} m ${OracleText.compass(dx, dz)}"
    } else {
        "${OracleText.coords(located.x, y, located.z)}, ${OracleText.relative(dx, y - origin.y, dz)}"
    }
    val status = if (located.generated) "" else " (not generated yet: predicted from the world's seed)"
    return "nearest ${OracleText.bare(located.id)} to $from: $where$status"
}

// ── player_stats ─────────────────────────────────────────────────────────────────────────────────────

@Serializable
data class PlayerStatsArgs(
    @Doc("A player's name, online or not.") @MaxLen(40) val player: String,
    @Doc(
        "Stats to read instead of the summary, e.g. custom:deaths, custom:play_time, mined:diamond_ore, killed:zombie, " +
            "killed_by:player, used:ender_pearl, crafted:diamond_pickaxe.",
    )
    @Items(1, 20)
    @MaxLen(80)
    val stats: List<String>? = null,
)

class PlayerStatsTool : NarratorTool<PlayerStatsArgs>(
    name = "player_stats",
    description = "A player's vanilla statistics, online (live) or not (their totals at their last departure): by default a " +
        "summary (time played, deaths, kills, mining, blocks placed, distances, damage, trades…), or the stats you name.",
    args = PlayerStatsArgs.serializer(),
    effect = ToolEffect.READ,
) {
    override suspend fun run(ctx: ToolContext, args: PlayerStatsArgs): ToolResult {
        val wanted = args.stats?.map { raw ->
            StatSummary.fullName(raw) ?: return ToolResult.invalid("unknown stat \"$raw\"; write type:name with a type among ${StatSummary.TYPES.joinToString()}")
        }
        val cost = OracleCost(name)
        val live = ctx.players.byName(args.player)
        val (who, values) = if (live != null) {
            val values = cost.server(ctx) { server -> server.playerList.getPlayer(live.ref.uuid)?.let { liveStats(it) } }
                ?: return ctx.notOnline(args.player)
            "${live.ref.name} (online; live counters)" to values
        } else {
            storedStats(ctx.services.journal, args.player, ctx.clock.millis()).getOrElse { e ->
                return if (e is IllegalArgumentException) {
                    ToolResult.invalid("${e.message}; online: ${ctx.onlineNames()}")
                } else {
                    ToolResult.failed("${args.player} is offline and the Journal cannot be read: ${e.message}")
                }
            }
        }
        val lines = if (wanted == null) {
            StatSummary.summary(values) { isBlockItem(it) }
        } else {
            wanted.map { "${StatSummary.short(it)} ${values[it] ?: 0}" }
        }
        cost.log(if (live != null) "live" else "from the Journal")
        return ToolResult.ok((listOf(who) + lines).joinToString("\n"), targets = listOfNotNull(live?.ref))
    }

    private fun liveStats(player: ServerPlayer): Map<String, Long> {
        val table = (player.stats as StatsCounterAccessor).`narrator2$stats`()
        val values = HashMap<String, Long>(table.size)
        for (entry in table.object2IntEntrySet()) values[entry.key.name] = entry.intValue.toLong()
        return values
    }

    private fun isBlockItem(itemId: String): Boolean {
        val id = Identifier.tryParse(itemId) ?: return false
        return BuiltInRegistries.ITEM.get(id).map { it.value() is BlockItem }.orElse(false)
    }

    private companion object {
        const val PLAYER_SQL = """
SELECT CAST(p.player_uuid AS VARCHAR), p.name, epoch_ms(p.last_seen), p.sessions
FROM players p
WHERE lower(p.name) = lower(?)
   OR p.player_uuid IN (SELECT player_uuid FROM player_names WHERE lower(name) = lower(?))
ORDER BY lower(p.name) = lower(?) DESC, p.last_seen DESC
LIMIT 1
"""
        const val STATS_SQL = "SELECT stat, value FROM stat_totals WHERE player_uuid = CAST(? AS UUID)"

        /**
         * A player's totals in the Journal: `Mimosa (offline, last seen 3h ago, 12 sessions)` and the
         * stats. Fails with an [IllegalArgumentException] when no such player ever played here.
         */
        suspend fun storedStats(journal: Journal, name: String, now: Long): Result<Pair<String, Map<String, Long>>> = runCatching {
            journal.read(JOURNAL_TIMEOUT_MS) { connection ->
                val player = connection.prepareStatement(PLAYER_SQL).use { statement ->
                    statement.queryTimeout = (JOURNAL_TIMEOUT_MS / 1000).toInt()
                    repeat(3) { statement.setString(it + 1, name) }
                    statement.executeQuery().use { rs ->
                        if (!rs.next()) throw IllegalArgumentException("no player named $name has ever played here")
                        StoredPlayer(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getInt(4))
                    }
                }
                val stats = connection.prepareStatement(STATS_SQL).use { statement ->
                    statement.queryTimeout = (JOURNAL_TIMEOUT_MS / 1000).toInt()
                    statement.setString(1, player.uuid)
                    statement.executeQuery().use { rs ->
                        val values = HashMap<String, Long>()
                        while (rs.next()) values[rs.getString(1)] = rs.getLong(2)
                        values
                    }
                }
                val who = "${player.name} (offline, last seen ${OracleText.duration(now - player.lastSeen)} ago, " +
                    "${player.sessions} session${if (player.sessions == 1) "" else "s"}; totals at their last departure)"
                who to stats
            }
        }.onFailure { if (it is CancellationException) throw it }
    }

    /** A player of the Journal's `players` table; [lastSeen] in epoch milliseconds. */
    private class StoredPlayer(val uuid: String, val name: String, val lastSeen: Long, val sessions: Int)
}

// ── Shared ───────────────────────────────────────────────────────────────────────────────────────────

/** A place a scan is centred on: a player's position, or a point in a dimension. */
private class Center(val level: ServerLevel, val pos: BlockPos, val label: String, val player: ServerPlayer?) {
    companion object {
        /** From the arguments (a player online, or x, y, z and a dimension). Server thread. */
        fun resolve(ctx: ToolContext, server: MinecraftServer, player: String?, x: Int?, y: Int?, z: Int?, dimension: String?): Result<Center> {
            if (player != null) {
                val live = ctx.players.byName(player)?.let { server.playerList.getPlayer(it.ref.uuid) }
                    ?: return Result.failure(IllegalArgumentException("$player is not online; online: ${ctx.onlineNames()}"))
                val pos = live.blockPosition()
                val label = "${live.plainTextName} (${OracleText.dimension(live.level().dimension().identifier().toString())} ${OracleText.coords(pos.x, pos.y, pos.z)})"
                return Result.success(Center(live.level(), pos, label, live))
            }
            val id = OracleText.dimensionId(dimension ?: "overworld")
            val key = Identifier.tryParse(id)?.let { ResourceKey.create(Registries.DIMENSION, it) }
            val level = key?.let { server.getLevel(it) }
                ?: return Result.failure(
                    IllegalArgumentException(
                        "unknown dimension \"$dimension\"; dimensions: " + server.allLevels.joinToString { OracleText.dimension(it.dimension().identifier().toString()) },
                    ),
                )
            val pos = BlockPos(x!!, y!!, z!!)
            return Result.success(Center(level, pos, "${OracleText.dimension(id)} ${OracleText.coords(pos.x, pos.y, pos.z)}", null))
        }
    }
}

/** Arguments name a player, or x, y and z: exactly one of the two. */
private fun requireCenter(player: String?, x: Int?, y: Int?, z: Int?) {
    val point = listOf(x, y, z)
    require(point.all { it == null } || point.none { it == null }) { "give x, y and z together" }
    require((player == null) != (x == null)) { "give either player, or x, y and z" }
}

private fun ToolContext.onlineNames(): String = players.online.joinToString { it.ref.name }.ifEmpty { "nobody" }

private fun ToolContext.notOnline(name: String): ToolResult = ToolResult.invalid("$name is not online; online: ${onlineNames()}")

/**
 * Time one Oracle call spends on the server thread and off it, logged at its end: the server thread's
 * share is what the game pays (a tick lasts 50 ms).
 */
private class OracleCost(private val tool: String) {
    @Volatile
    private var serverNanos = 0L

    @Volatile
    private var workerNanos = 0L

    /** Runs [block] on the server thread (within the tool's timeout), counting the time it takes there. */
    suspend fun <T> server(ctx: ToolContext, block: (MinecraftServer) -> T): T = ctx.onServer { server ->
        val start = System.nanoTime()
        try {
            block(server)
        } finally {
            serverNanos += System.nanoTime() - start
        }
    }

    /** Runs [block] off the server thread, on the default dispatcher. */
    suspend fun <T> worker(block: () -> T): T = withContext(Dispatchers.Default) {
        val start = System.nanoTime()
        try {
            block()
        } finally {
            workerNanos += System.nanoTime() - start
        }
    }

    fun log(detail: String) {
        Log.tool.info("oracle $tool ($detail): server thread %.2f ms, off it %.2f ms".format(Locale.ROOT, serverNanos / 1e6, workerNanos / 1e6))
    }
}
