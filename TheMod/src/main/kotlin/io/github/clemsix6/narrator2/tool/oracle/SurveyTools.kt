package io.github.clemsix6.narrator2.tool.oracle

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.oracle.OracleText
import io.github.clemsix6.narrator2.oracle.Survey
import io.github.clemsix6.narrator2.oracle.SurveyGrid
import io.github.clemsix6.narrator2.oracle.SurveySpot
import io.github.clemsix6.narrator2.oracle.WorldReader
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Doc
import io.github.clemsix6.narrator2.tool.MaxLen
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.Range
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.tool.notOnline
import io.github.clemsix6.narrator2.tool.shownName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import java.util.Locale

/**
 * The scout's survey (M4 §14): `survey`, a read tool that finds flat, open squares around a player or a point,
 * in the loaded chunks only, for an arena or a scene. The scout's alone (`ToolCatalog`).
 */
@Suppress("UNUSED_PARAMETER")
fun surveyTools(services: NarratorServices): List<NarratorTool<*>> = listOf(SurveyTool())

/** The arguments of `survey` (M4 §14). */
@Serializable
data class SurveyArgs(
    @Doc("Name of the online player around whom to look; leave it out to give x, y and z instead.") @MaxLen(16) val player: String? = null,
    @Doc("Block coordinate; x, y and z together, instead of a player.") @Range(-30_000_000.0, 30_000_000.0) val x: Int? = null,
    @Doc("Block coordinate; x, y and z together, instead of a player.") @Range(-2048.0, 2048.0) val y: Int? = null,
    @Doc("Block coordinate; x, y and z together, instead of a player.") @Range(-30_000_000.0, 30_000_000.0) val z: Int? = null,
    @Doc("Dimension of x, y and z: overworld (the default), the_nether or the_end.") @MaxLen(40) val dimension: String? = null,
    @Doc("How far to look, in blocks.") @Range(8.0, 64.0) val radius: Int = 32,
    @Doc("Side of the flat square wanted, in blocks.") @Range(5.0, 21.0) val size: Int = 9,
) {
    init {
        val point = listOf(x, y, z)
        require(point.all { it == null } || point.none { it == null }) { "give x, y and z together" }
        require((player == null) != (x == null)) { "give either player, or x, y and z" }
    }
}

/**
 * `survey`: the flat, open squares around a player or a point ([Survey]). The columns are read on the server
 * thread from the loaded chunks only; the squares are searched off it. Logs how long each part took.
 */
class SurveyTool : NarratorTool<SurveyArgs>(
    name = "survey",
    description = "Finds flat, open squares of ground around a player or a point, for an arena or a scene: at most 5, " +
        "nearest first, none overlapping. A square fits when its ground varies by 1 block at most, with no water or lava, " +
        "nothing dangerous (magma, fire), no block placed by a player, and only the sky or leaves above. Gives each centre " +
        "(where to stand), its ground, and its distance and direction. Reads the chunks loaded near players only, and says " +
        "how many it could not read. Not where the world has a ceiling (the Nether).",
    args = SurveyArgs.serializer(),
    effect = ToolEffect.READ,
) {
    override suspend fun run(ctx: ToolContext, args: SurveyArgs): ToolResult {
        if (args.player != null && ctx.players.byName(args.player) == null) return ctx.notOnline(args.player)
        var serverNanos = 0L
        val read = ctx.onServer { server ->
            val started = System.nanoTime()
            try {
                read(ctx, server, args)
            } finally {
                serverNanos = System.nanoTime() - started
            }
        }
        val (centre, grid) = when (read) {
            is Read.Refused -> return read.result
            is Read.Done -> read.centre to read.grid
        }
        if (grid.loadedChunks == 0) {
            return ToolResult.failed("no chunk is loaded within ${args.radius} blocks of ${centre.label}: the scout reads only the areas loaded around players")
        }
        val searchStarted = System.nanoTime()
        val spots = withContext(Dispatchers.Default) { Survey.find(grid, centre.x, centre.z, args.radius, args.size) }
        Log.tool.info(
            "oracle survey (r=${args.radius} size=${args.size}, ${grid.loadedChunks}/${grid.chunks} chunks, ${spots.size} spots): " +
                "server thread %.2f ms, off it %.2f ms".format(Locale.ROOT, serverNanos / 1e6, (System.nanoTime() - searchStarted) / 1e6),
        )
        return ToolResult.ok(surveyText(centre, args.radius, args.size, grid, spots))
    }

    /** The centre of the survey and its columns, or why there is none. Server thread. */
    private fun read(ctx: ToolContext, server: MinecraftServer, args: SurveyArgs): Read {
        val level: ServerLevel
        val centre: SurveyCentre
        if (args.player != null) {
            val player = ctx.players.byName(args.player)?.let { server.playerList.getPlayer(it.ref.uuid) }
                ?: return Read.Refused(ctx.notOnline(args.player))
            level = player.level()
            val pos = player.blockPosition()
            centre = SurveyCentre(pos.x, pos.y, pos.z, ToolText.dimension(level.dimension().identifier().toString()), player.shownName)
        } else {
            val id = ToolText.dimensionId(args.dimension ?: "overworld")
            level = Identifier.tryParse(id)?.let { server.getLevel(ResourceKey.create(Registries.DIMENSION, it)) }
                ?: return Read.Refused(
                    ToolResult.invalid(
                        "unknown dimension \"${ModelText.name(args.dimension.orEmpty())}\"; dimensions: " +
                            server.allLevels.joinToString { ToolText.dimension(it.dimension().identifier().toString()) },
                    ),
                )
            centre = SurveyCentre(args.x!!, args.y!!, args.z!!, ToolText.dimension(id), null)
        }
        if (level.dimensionType().hasCeiling()) {
            val where = if (centre.player != null) "${centre.player} is in ${centre.dimension}, which" else centre.dimension
            return Read.Refused(ToolResult.invalid("$where has a ceiling: survey finds ground under the open sky only (overworld, the_end)"))
        }
        return Read.Done(centre, Survey.read(WorldReader(level), centre.x, centre.z, args.radius, args.size))
    }

    private sealed interface Read {
        class Refused(val result: ToolResult) : Read

        class Done(val centre: SurveyCentre, val grid: SurveyGrid) : Read
    }
}

/**
 * Where a survey looks from: a block position in [dimension] (its short id, `overworld`), around [player]
 * (their name, escaped) or a point (null).
 */
class SurveyCentre(val x: Int, val y: Int, val z: Int, val dimension: String, val player: String?) {
    /** `Bartok (overworld 100 64 -30)`, or `overworld 100 64 -30` for a point. */
    val label: String
        get() = OracleText.coords(x, y, z).let { if (player == null) "$dimension $it" else "$player ($dimension $it)" }
}

/**
 * What `survey` answers (M4 §14):
 * ```
 * survey around Bartok (overworld 100 64 -30), radius 32, square 9: 2 spots
 * 1. 112 70 -40 · flat 9x9 (±0) · open sky · grass_block · 16 blocks NE of Bartok
 * 2. 131 71 -55 · flat 9x9 (±1) · under leaves · grass_block · 40 blocks NE of Bartok
 * ```
 * with ` (20 of 25 chunks loaded; the others were not read)` after the count when some were not.
 */
internal fun surveyText(centre: SurveyCentre, radius: Int, size: Int, grid: SurveyGrid, spots: List<SurveySpot>): String {
    val loaded = if (grid.loadedChunks >= grid.chunks) "" else " (${grid.loadedChunks} of ${grid.chunks} chunks loaded; the others were not read)"
    val head = "survey around ${centre.label}, radius $radius, square $size: "
    if (spots.isEmpty()) {
        return head + "no flat open square$loaded; the ground there is uneven, wet, dangerous or built on: try a smaller square, or elsewhere"
    }
    val from = centre.player ?: "the point"
    val lines = spots.mapIndexed { i, spot ->
        val dx = spot.x - centre.x
        val dz = spot.z - centre.z
        val where = when {
            dx != 0 || dz != 0 -> "${OracleText.distance(dx, 0, dz)} blocks ${OracleText.compass(dx, dz)} of $from"
            centre.player != null -> "right where ${centre.player} stands"
            else -> "at the point"
        }
        "${i + 1}. ${OracleText.coords(spot.x, spot.y, spot.z)} · flat ${size}x$size (±${spot.spread}) · " +
            "${if (spot.openSky) "open sky" else "under leaves"} · ${spot.ground} · $where"
    }
    return (listOf(head + "${spots.size} spot${if (spots.size > 1) "s" else ""}$loaded") + lines).joinToString("\n")
}
