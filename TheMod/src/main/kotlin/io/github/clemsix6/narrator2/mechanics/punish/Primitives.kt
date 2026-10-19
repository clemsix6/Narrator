package io.github.clemsix6.narrator2.mechanics.punish

import io.github.clemsix6.narrator2.guard.BlockRestorer
import io.github.clemsix6.narrator2.guard.Caps
import io.github.clemsix6.narrator2.guard.NarratorMob
import io.github.clemsix6.narrator2.mechanics.traps.TrapShapes
import io.github.clemsix6.narrator2.observer.id
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.tool.action.Teleports
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.BlockTags
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.Pose
import net.minecraft.world.entity.monster.cubemob.Slime
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.UUID

/** A primitive looked at and ready (M4 §6.3): nothing changed yet. */
fun interface Prepared {
    /** Does it now, for the act [act]: what was done, for the result, or why it could not be. Server thread. */
    fun apply(act: Long?): Result<String>

    /** How long a fall is the Narrator's once it is done, in seconds (the climb down from a tree top); 0 for none. */
    val fallSeconds: Int get() = 0
}

/**
 * The catalogue's own actions, those no tool does (M4 §6.3): the shuffle (`butterfingers`), the tree top
 * (`tree_top`) and the traps (`cage`, `web`). Each comes in two parts, on the server thread and in the same tick:
 * [prepare] looks at the world and changes nothing, so that a punishment that cannot happen (no room for a cage,
 * nothing high around, an empty inventory) opens no episode and counts nowhere; then, once the guards have allowed
 * the punishment, [Prepared.apply] does it, before anything could change in between.
 *
 * Plus what `slime_rain` needs of the creatures `spawn` summoned ([summonedFor], [shrink]).
 */
object Primitives {
    /** How far around a player `tree_top` looks for a top to put them on, in blocks. */
    const val TREE_TOP_RADIUS = 24

    /** A tree top is at least this many blocks above the player's feet. */
    const val TREE_TOP_MIN_RISE = 6

    /**
     * And at most this many (M4 review, finding 10): a tree, a roof, a cliff nearby, never the surface high above
     * a miner in a cave (the heightmap sees only the top of each column).
     */
    const val TREE_TOP_MAX_RISE = 32

    /** The main inventory (27 slots) after the hotbar (9): `Inventory` slots 9 to 35. */
    private val MAIN = Inventory.SELECTION_SIZE until Inventory.INVENTORY_SIZE
    private val WITH_HOTBAR = 0 until Inventory.INVENTORY_SIZE

    /**
     * [primitive] looked at for [player] (whose name the messages give), within [caps], lasting [seconds]: ready to
     * apply, or why it cannot happen there (nothing changed). Server thread.
     */
    fun prepare(primitive: Primitive, player: ServerPlayer, caps: Caps, seconds: Int): Result<Prepared> = when (primitive) {
        is Primitive.Shuffle -> shuffle(player, primitive.hotbar)
        Primitive.TreeTop -> treeTop(player, caps)
        is Primitive.Trap -> trap(player, primitive.shape, seconds)
    }

    // ── butterfingers ───────────────────────────────────────────────────────────────────────────

    /**
     * [items] in another order that moves something: a permutation (every item once, the same objects: nothing
     * made, nothing lost), drawn with [nextInt] (a draw in `0 until bound`). Null when all the items are [alike]:
     * there is nothing to shuffle.
     */
    internal fun <T> shuffled(items: List<T>, alike: (T, T) -> Boolean, nextInt: (Int) -> Int): List<T>? {
        val first = items.firstOrNull() ?: return null
        if (items.all { alike(it, first) }) return null
        repeat(SHUFFLE_TRIES) {
            val order = items.toMutableList()
            for (i in order.indices.reversed()) {
                val j = nextInt(i + 1)
                order[i] = order[j].also { order[j] = order[i] }
            }
            if (order.indices.any { !alike(order[it], items[it]) }) return order
        }
        // Unlucky draws: swap the first two that differ.
        val j = items.indexOfFirst { !alike(it, first) }
        return items.toMutableList().also { it[0] = items[j]; it[j] = first }
    }

    /** How many places hold something else in [after] than in [before]. */
    internal fun <T> moved(before: List<T>, after: List<T>, alike: (T, T) -> Boolean): Int = before.indices.count { !alike(before[it], after[it]) }

    private fun shuffle(player: ServerPlayer, hotbar: Boolean): Result<Prepared> {
        val inventory = player.inventory
        val slots = if (hotbar) WITH_HOTBAR else MAIN
        val before = slots.map { inventory.getItem(it) }
        val order = shuffled(before, ::alike, player.random::nextInt)
            ?: return refused("${player.plainTextName}'s ${if (hotbar) "inventory and hotbar hold" else "inventory holds"} nothing to shuffle")
        return Result.success(
            Prepared {
                // The same stacks, each put back once in another slot.
                slots.forEachIndexed { i, slot -> inventory.setItem(slot, order[i]) }
                inventory.setChanged()
                player.containerMenu.broadcastChanges()
                val where = if (hotbar) "inventory and hotbar" else "inventory"
                Result.success("$where shuffled, ${moved(before, order, ::alike)} slots changed, nothing lost")
            },
        )
    }

    private fun alike(a: ItemStack, b: ItemStack): Boolean = ItemStack.matches(a, b)

    // ── tree_top ────────────────────────────────────────────────────────────────────────────────

    /** A column to put a player on top of: [top] is its highest block, [leaves] whether that is a tree's. */
    private class Column(val x: Int, val top: Int, val z: Int, val leaves: Boolean, val distance: Int)

    /**
     * The top to put [player] on: among the columns within [TREE_TOP_RADIUS] blocks whose highest block (leaves
     * included, `Heightmap.Types.MOTION_BLOCKING`) is [TREE_TOP_MIN_RISE] to [TREE_TOP_MAX_RISE] blocks above
     * their feet and no farther than a teleport may go at the chaos level, a tree's leaves first, then the
     * highest, then the nearest; and only where they can stand ([standable]). Never in a dimension with a ceiling
     * (the Nether's roof). Its [Prepared.fallSeconds]: the tree top's episode, and
     * `PunishmentCatalog.TREE_TOP_FALL_SECONDS_PER_BLOCK` more per block of the climb.
     *
     * `teleport` cannot do it: its landing wants a sturdy floor, and leaves have none (`LeavesBlock` overrides
     * `getBlockSupportShape`, javap); they have a full block of collision, which is what holds a player.
     */
    private fun treeTop(player: ServerPlayer, caps: Caps): Result<Prepared> {
        val level = player.level()
        val name = player.plainTextName
        if (level.dimensionType().hasCeiling()) {
            return refused("no open sky above $name in ${ToolText.dimension(level.dimension().identifier().toString())}: no tree top to put them on")
        }
        val feet = player.blockY
        val columns = mutableListOf<Column>()
        for (dx in -TREE_TOP_RADIUS..TREE_TOP_RADIUS) {
            for (dz in -TREE_TOP_RADIUS..TREE_TOP_RADIUS) {
                val distance = dx * dx + dz * dz
                if (distance > TREE_TOP_RADIUS * TREE_TOP_RADIUS) continue
                val x = player.blockX + dx
                val z = player.blockZ + dz
                // Never loads a chunk: a column not loaded is no candidate.
                val chunk = level.chunkSource.getChunkNow(x shr 4, z shr 4) ?: continue
                val top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x and 15, z and 15)
                if (top + 1 - feet !in TREE_TOP_MIN_RISE..TREE_TOP_MAX_RISE) continue
                columns += Column(x, top, z, chunk.getBlockState(BlockPos(x, top, z)).`is`(BlockTags.LEAVES), distance)
            }
        }
        val order = compareByDescending<Column> { it.leaves }.thenByDescending { it.top }.thenBy { it.distance }
        val spot = columns.sortedWith(order).asSequence()
            .map { BlockPos(it.x, it.top + 1, it.z) }
            .firstOrNull { standable(level, player, it) && player.position().distanceTo(Vec3.atBottomCenterOf(it)) <= caps.teleportBlocks }
            ?: return refused(
                "nothing $TREE_TOP_MIN_RISE to $TREE_TOP_MAX_RISE blocks higher than $name within $TREE_TOP_RADIUS blocks to put them on top of " +
                    "(no tree, no roof, no cliff)",
            )
        val floor = level.getBlockState(spot.below()).block.id.removePrefix("minecraft:")
        val rise = spot.y - feet
        return Result.success(
            object : Prepared {
                override val fallSeconds = PunishmentCatalog.TREE_TOP_SECONDS + rise * PunishmentCatalog.TREE_TOP_FALL_SECONDS_PER_BLOCK

                override fun apply(act: Long?): Result<String> =
                    if (!Teleports.move(player, Vec3.atBottomCenterOf(spot))) {
                        Result.failure(IllegalStateException("the game refused to move $name"))
                    } else {
                        Result.success("put on top of $floor at x ${spot.x}, y ${spot.y}, z ${spot.z}, $rise blocks above where they stood, to climb down by themselves")
                    }
            },
        )
    }

    /**
     * Whether [player] can stand with their feet at [feet]: a floor with a full block of collision (leaves, logs,
     * any full block) that is neither liquid nor dangerous (magma, campfire…) nor unbreakable (a barrier, bedrock:
     * the walls of a map, never a place to put a player), and room for their standing box (their scale included)
     * with no liquid nor danger in it, inside the world border and its height. Reads loaded chunks only (the
     * column is). Server thread.
     */
    private fun standable(level: ServerLevel, player: ServerPlayer, feet: BlockPos): Boolean {
        val below = feet.below()
        val floor = level.getBlockState(below)
        if (!floor.fluidState.isEmpty || player.type.isBlockDangerous(floor) || !floor.isCollisionShapeFullBlock(level, below)) return false
        if (floor.getDestroySpeed(level, below) < 0f) return false
        val box = player.getDimensions(Pose.STANDING).makeBoundingBox(Vec3.atBottomCenterOf(feet))
        if (!level.worldBorder.isWithinBounds(box)) return false
        for (pos in BlockPos.betweenClosed(box)) {
            if (level.isOutsideBuildHeight(pos)) return false
            val state = level.getBlockState(pos)
            if (!state.fluidState.isEmpty || player.type.isBlockDangerous(state)) return false
        }
        return level.noCollision(player, box)
    }

    // ── cage, web ───────────────────────────────────────────────────────────────────────────────

    /**
     * The cells of [shape] around [player] ([TrapShapes]: the terrain is refused there), and room for them among
     * the blocks the server may hold pending. Applied, the [BlockRestorer] sets them for [seconds] (wall clock),
     * all or nothing, for the act.
     */
    private fun trap(player: ServerPlayer, shape: TrapShape, seconds: Int): Result<Prepared> {
        val cells = (if (shape == TrapShape.CAGE) TrapShapes.cage(player) else TrapShapes.web(player)).getOrElse { return Result.failure(it) }
        val pending = BlockRestorer.pending()
        if (pending + cells.size > BlockRestorer.MAX_PENDING) {
            return refused("$pending trap blocks are already waiting to be taken away on the server, ${BlockRestorer.MAX_PENDING} at most: no room for ${cells.size} more")
        }
        val block = if (shape == TrapShape.CAGE) TrapShapes.cageBlock else TrapShapes.webBlock
        return Result.success(
            Prepared { act ->
                val until = System.currentTimeMillis() + seconds * 1_000L
                BlockRestorer.place(player.level(), cells, block, until, player.uuid, act).map { set ->
                    if (shape == TrapShape.CAGE) "a glass cage of $set blocks (taken away by itself)" else "$set cobwebs (taken away by themselves)"
                }
            },
        )
    }

    // ── What spawn summoned ─────────────────────────────────────────────────────────────────────

    /** Around a player, where `spawn` may have put their creatures: its distance and height, and room to spare. */
    private const val SUMMONS_REACH = 40.0

    /** The Narrator's creatures loaded around [player] that were summoned for them, by UUID. Server thread. */
    fun summonedFor(player: ServerPlayer): Map<UUID, Mob> =
        player.level().getEntitiesOfClass(Mob::class.java, AABB(player.blockPosition()).inflate(SUMMONS_REACH)) {
            it.getAttached(NarratorMob.TYPE)?.player == player.uuid
        }.associateBy { it.uuid }

    /**
     * Makes each slime of [mobs] one of size 1 (`Slime.setSize`, its health with it): a slime of size 1 never
     * splits when it dies, so no smaller slime escapes the cap or the expiry (M4 §6.3), and it hurts nobody.
     * Server thread.
     */
    fun shrink(mobs: Collection<Mob>) {
        mobs.filterIsInstance<Slime>().filter { it.size != 1 }.forEach { it.setSize(1, true) }
    }

    /** Draws of a shuffle before falling back on a swap. */
    private const val SHUFFLE_TRIES = 8

    private fun <T> refused(why: String): Result<T> = Result.failure(IllegalStateException(why))
}
