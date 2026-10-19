package io.github.clemsix6.narrator2.forge.sdk

import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.forge.api.BlockInfo
import io.github.clemsix6.narrator2.forge.api.Cell
import io.github.clemsix6.narrator2.forge.api.CreatureInfo
import io.github.clemsix6.narrator2.forge.api.ForgeRefusal
import io.github.clemsix6.narrator2.forge.api.Place
import io.github.clemsix6.narrator2.forge.api.PlayerInfo
import io.github.clemsix6.narrator2.forge.api.World
import io.github.clemsix6.narrator2.observer.PlacedBlocks
import io.github.clemsix6.narrator2.observer.PlayerStates
import io.github.clemsix6.narrator2.observer.dimensionId
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.tool.action.ItemSpecs
import io.github.clemsix6.narrator2.tool.action.parseId
import io.github.clemsix6.narrator2.tool.action.shortId
import io.github.clemsix6.narrator2.tool.speech.closestIds
import net.fabricmc.fabric.api.entity.FakePlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.Mob
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.AABB
import java.util.Collections

/**
 * The game's [World] for one deployment (M5 §4.2): reads of the world as it is now, **in loaded chunks only** (a chunk
 * is looked up with `ServerChunkCache.getChunkNow`, which never loads one), on the server thread only ([ForgeRefusal]
 * elsewhere). A player is a real player online who is not a spectator; a creature is a [Mob] (no player, item, vehicle
 * nor armor stand). [own] tells the deployment's own creatures (`CreatureInfo.own`); [players] gives the Narrator's
 * notion of underground (`PlayerInfo.underground`, read once a second by the sampler). Once the deployment ended
 * ([close]), every method throws [ForgeRefusal]. Lists come unmodifiable.
 */
class WorldView(
    private val server: MinecraftServer,
    private val players: PlayerStates,
    private val own: (Entity) -> Boolean,
) : World {
    private var closed = false

    override fun players(): List<PlayerInfo> {
        open()
        return unmodifiable(online().map(::info))
    }

    override fun player(name: String?): PlayerInfo? {
        open()
        if (name == null) throw ForgeRefusal("World.player: the name must not be null")
        return online().firstOrNull { it.plainTextName.equals(name, ignoreCase = true) }?.let(::info)
    }

    override fun creatures(around: Place?, radius: Double, type: String?): List<CreatureInfo> {
        open()
        if (around == null) throw ForgeRefusal("World.creatures: the place must not be null")
        if (!(radius >= 0 && radius <= ForgeLimits.CREATURES_RADIUS)) {
            throw ForgeRefusal("World.creatures: a radius is 0 to ${ForgeLimits.CREATURES_RADIUS} blocks (Limits.MAX_CREATURES_RADIUS; got $radius)")
        }
        val wanted = type?.let(::creatureType)
        val level = level(around.dimension, "World.creatures")
        val box = AABB(around.x - radius, around.y - radius, around.z - radius, around.x + radius, around.y + radius, around.z + radius)
        val reach = radius * radius
        val found = level.getEntitiesOfClass(Mob::class.java, box) { mob ->
            mob.isAlive && (wanted == null || mob.type === wanted) && mob.distanceToSqr(around.x, around.y, around.z) <= reach
        }
        return unmodifiable(
            found.sortedBy { it.distanceToSqr(around.x, around.y, around.z) }.take(ForgeLimits.CREATURES_FOUND).map { mob ->
                CreatureInfo(mob.stringUUID, shortId(EntityType.getKey(mob.type)), at(mob), mob.health, mob.maxHealth, mob.customName?.string, own(mob))
            },
        )
    }

    override fun block(cell: Cell?): BlockInfo? {
        open()
        if (cell == null) throw ForgeRefusal("World.block: the cell must not be null")
        val level = level(cell.dimension, "World.block")
        val pos = BlockPos(cell.x, cell.y, cell.z)
        val chunk = chunk(level, cell.x, cell.z) ?: return null
        val state = chunk.getBlockState(pos)
        return BlockInfo(
            cell,
            shortId(BuiltInRegistries.BLOCK.getKey(state.block)),
            state.isAir,
            state.liquid(),
            !state.getCollisionShape(level, pos).isEmpty,
            PlacedBlocks.placerAt(chunk, pos) != null,
        )
    }

    override fun surface(dimension: String?, x: Int, z: Int): Int {
        open()
        val chunk = chunk(level(dimension, "World.surface"), x, z) ?: return Int.MIN_VALUE
        return chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x and 15, z and 15) + 1
    }

    override fun biome(at: Place?): String? {
        open()
        if (at == null) throw ForgeRefusal("World.biome: the place must not be null")
        val level = level(at.dimension, "World.biome")
        val pos = BlockPos.containing(at.x, at.y, at.z)
        chunk(level, pos.x, pos.z) ?: return null
        return level.getBiome(pos).registeredName.removePrefix("minecraft:")
    }

    override fun count(player: String?, item: String?): Int {
        open()
        if (player == null || item == null) throw ForgeRefusal("World.count: the player and the item must not be null")
        val matches: (ItemStack) -> Boolean = if (item.trim().startsWith("#")) {
            val tag = ItemSpecs.itemTag(item) ?: throw ForgeRefusal("World.count: ${ItemSpecs.unknownTag(item)}")
            fun(stack: ItemStack): Boolean = stack.`is`(tag)
        } else {
            val known = ItemSpecs.item(item) ?: throw ForgeRefusal("World.count: ${ItemSpecs.unknownItem(item)}")
            fun(stack: ItemStack): Boolean = stack.`is`(known)
        }
        val carrier = online().firstOrNull { it.plainTextName.equals(player, ignoreCase = true) } ?: return -1
        val inventory = carrier.inventory
        var total = 0
        for (slot in 0 until inventory.containerSize) {
            val stack = inventory.getItem(slot)
            if (!stack.isEmpty && matches(stack)) total += stack.count
        }
        return total
    }

    override fun dayTime(): Long {
        open()
        return Math.floorMod(server.overworld().defaultClockTime, TICKS_PER_DAY)
    }

    override fun weather(): String {
        open()
        val overworld = server.overworld()
        return when {
            overworld.isThundering -> "thunder"
            overworld.isRaining -> "rain"
            else -> "clear"
        }
    }

    /** The deployment ended: every method throws [ForgeRefusal] from now on. */
    fun close() {
        closed = true
    }

    private fun open() {
        if (closed) throw ForgeRefusal("the deployment has ended: its SDK objects no longer work")
        if (!server.isSameThread) throw ForgeRefusal("the world is read on the server thread only")
    }

    /** The players a module sees: real ones online, not spectators, in the order they joined. */
    private fun online(): List<ServerPlayer> = server.playerList.players.filter { it !is FakePlayer && !it.isSpectator }

    private fun info(player: ServerPlayer): PlayerInfo {
        val held = player.mainHandItem
        return PlayerInfo(
            player.plainTextName, player.stringUUID, at(player), player.health, player.maxHealth, player.foodData.foodLevel,
            player.gameMode().serializedName, player.onGround(), player.isShiftKeyDown, player.isSprinting, player.isSwimming,
            player.isFallFlying, player.yRot, player.xRot, if (held.isEmpty) "air" else shortId(held.typeHolder()),
            players.get(player.uuid)?.underground == true,
        )
    }

    private fun at(entity: Entity): Place = Place(entity.level().dimensionId, entity.x, entity.y, entity.z)

    /** The level of [dimension] in any of its forms; [ForgeRefusal] naming the server's dimensions for an unknown one. */
    private fun level(dimension: String?, method: String): ServerLevel {
        val level = dimension?.takeIf { it.isNotBlank() }
            ?.let { Identifier.tryParse(ToolText.dimensionId(it)) }
            ?.let { server.getLevel(ResourceKey.create(Registries.DIMENSION, it)) }
        return level ?: throw ForgeRefusal(
            "$method: unknown dimension \"$dimension\"; dimensions: ${server.levelKeys().joinToString { ToolText.dimension(it.identifier().toString()) }}",
        )
    }

    /** The chunk of the block column [x], [z] when it is loaded; null otherwise (never loads it). */
    private fun chunk(level: ServerLevel, x: Int, z: Int): LevelChunk? =
        level.chunkSource.getChunkNow(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z))

    /** The creature type [raw] names (`zombie`, `minecraft:zombie`); [ForgeRefusal] for an unknown one or a player. */
    private fun creatureType(raw: String): EntityType<*> {
        val type = parseId(raw)?.let { BuiltInRegistries.ENTITY_TYPE.get(it).orElse(null)?.value() }
            ?: throw ForgeRefusal(
                "World.creatures: unknown creature type \"$raw\"; closest: " +
                    closestIds(raw, BuiltInRegistries.ENTITY_TYPE.keySet().map(::shortId)).joinToString(),
            )
        if (type === EntityTypes.PLAYER) throw ForgeRefusal("World.creatures: a player is not a creature; read world().players()")
        return type
    }

    private fun <T> unmodifiable(list: List<T>): List<T> = Collections.unmodifiableList(list)

    private companion object {
        const val TICKS_PER_DAY = 24_000L
    }
}
