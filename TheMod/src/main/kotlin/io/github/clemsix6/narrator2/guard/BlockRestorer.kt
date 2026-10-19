package io.github.clemsix6.narrator2.guard

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.mechanics.MechanicsLimits
import io.github.clemsix6.narrator2.observer.id
import io.github.clemsix6.narrator2.observer.typeId
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry
import net.fabricmc.fabric.api.attachment.v1.AttachmentType
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.core.BlockPos
import net.minecraft.core.SectionPos
import net.minecraft.core.UUIDUtil
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.NbtUtils
import net.minecraft.nbt.TagParser
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.phys.AABB
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The persistent chunk attachment `narrator2:restorations` (M4 §9.1): the blocks the Narrator set in this
 * chunk, to take back out. Written in the same tick as the blocks, saved with them: a crash can never leave
 * a block without its snapshot.
 */
data class ChunkRestorations(val entries: List<BlockSnapshot> = emptyList()) {
    companion object {
        val CODEC: Codec<ChunkRestorations> = RecordCodecBuilder.create { instance ->
            instance.group(
                BlockSnapshot.CODEC.listOf().optionalFieldOf("entries", emptyList()).forGetter(ChunkRestorations::entries),
            ).apply(instance) { entries -> ChunkRestorations(entries) }
        }
    }
}

/**
 * One block the Narrator set in thin air (M4 §9.1).
 *
 * @property packed its position in the chunk ([BlockRestorer.pack], the packing of `PlacedBlocks.pack`).
 * @property placed the block state set there (there was air before), in SNBT: it is taken away only while it
 *   is still this one.
 * @property until when it is taken away, epoch milliseconds (wall clock).
 * @property player whom the trap holds.
 * @property act the act that set it (`Caller.act`: a Director turn, or the negative id of an engine act).
 */
data class BlockSnapshot(val packed: Int, val placed: String, val until: Long, val player: UUID, val act: Long?) {
    companion object {
        val CODEC: Codec<BlockSnapshot> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.INT.fieldOf("packed").forGetter(BlockSnapshot::packed),
                Codec.STRING.fieldOf("placed").forGetter(BlockSnapshot::placed),
                Codec.LONG.fieldOf("until").forGetter(BlockSnapshot::until),
                UUIDUtil.CODEC.fieldOf("player").forGetter(BlockSnapshot::player),
                Codec.LONG.optionalFieldOf("act").forGetter { Optional.ofNullable(it.act) },
            ).apply(instance) { packed, placed, until, player, act -> BlockSnapshot(packed, placed, until, player, act.orElse(null)) }
        }
    }
}

/**
 * The only way the Narrator writes blocks (M4 §9.1, M1_M2 §10.8): always for a while, and only in thin air.
 * A trap (a glass cage, cobwebs: `punish cage`, `punish web`) sets blocks in the air around a player, then
 * takes them away: it never replaces a block, natural or built, never touches a cell an entity is in, and
 * never changes a block while a chunk loads.
 *
 * The truth is the chunk's attachment [TYPE], written before the blocks change and saved with them. An index
 * mirrors it for the loaded chunks: `CHUNK_LOAD` only indexes the chunk's entries (`setBlock` would wait for
 * the very chunk being loaded, and the server would hang: M4 design review, finding 5), `CHUNK_UNLOAD` forgets
 * them, and once a second (wall clock) the blocks due in the loaded chunks are taken away. So a trap whose
 * chunk was unloaded, or whose server stopped, is taken away the second after its chunk loads again. A block
 * goes back to air only while it is still the one set: one a player put in its place stays (`trap skipped`).
 *
 * This runs without the Narrator too (it failed to start): the hooks are the mod's, and only the decisions
 * need the running Narrator (else the log alone says it). Server thread, but [activeOn] and [pending].
 */
object BlockRestorer {
    private val log = Log.guard

    /** Blocks one call may set; blocks waiting to be taken away on the whole server (in the chunks loaded). */
    const val MAX_PER_CALL = MechanicsLimits.TRAP_BLOCKS_PER_CALL
    const val MAX_PENDING = MechanicsLimits.TRAP_BLOCKS_PENDING

    /** Time between two passes that take away what is due, in milliseconds. */
    private const val PASS_MS = 1_000L

    /** At most this many changed blocks are named in a `trap skipped` decision. */
    private const val NAMED_MAX = 5

    /**
     * Flags of every write, setting and taking away alike: the clients see the block, and nothing else happens
     * (no item dropped, no `onPlace`, no shape update of the neighbours: ORACLE_ACTIONS_API.md §5.1).
     */
    private const val FLAGS = Block.UPDATE_CLIENTS or Block.UPDATE_SKIP_ALL_SIDEEFFECTS

    /** The persistent chunk attachment `narrator2:restorations`, registered by [registerHooks] before any world loads. */
    val TYPE: AttachmentType<ChunkRestorations> by lazy {
        AttachmentRegistry.create(Identifier.fromNamespaceAndPath("narrator2", "restorations")) { builder -> builder.persistent(ChunkRestorations.CODEC) }
    }

    /** A chunk of one level. */
    private data class ChunkKey(val dimension: ResourceKey<Level>, val x: Int, val z: Int)

    /** What [TYPE] holds in a loaded chunk, for the pass of each second and the readers of any thread. */
    private class Indexed(val level: ServerLevel, val entries: List<BlockSnapshot>)

    private val index = ConcurrentHashMap<ChunkKey, Indexed>()

    /** The block states of [BlockSnapshot.placed], read back once each (glass and cobwebs: a handful); empty when unreadable. */
    private val states = ConcurrentHashMap<String, Optional<BlockState>>()

    /** What may be set, and nothing else (M4 §9.1). Read on first use: the registries are ready by then. */
    private val allowed: Set<Block> by lazy { setOf(Blocks.GLASS, Blocks.COBWEB) }

    @Volatile
    private var lastPass = 0L

    /**
     * Puts [block] at [positions] until [until] (epoch milliseconds, wall clock), against [player], for [act]:
     * all or nothing. Only glass and cobwebs; each position must be inside the world, loaded, air, free of any
     * entity (but [player] for a block without collision, a cobweb), and not pending already ([cellRefusal]);
     * [MAX_PER_CALL] and [MAX_PENDING] hold. The snapshot is written to the chunk before the block changes, in
     * the same tick. The number of blocks set, or why none was (the message `punish` gives as `FAILED`).
     * Server thread.
     */
    fun place(level: ServerLevel, positions: List<BlockPos>, block: BlockState, until: Long, player: UUID, act: Long?): Result<Int> {
        val placed = snbt(block)
        // A block whose snapshot does not read back as itself would never be taken away.
        val refusal = refusal(level, positions, block, player) ?: "${block.block.id} does not read back from its snapshot".takeIf { stateOf(placed) != block }
        if (refusal != null) {
            log.debug { "trap refused against ${nameOf(level, player)}: $refusal" }
            return Result.failure(IllegalStateException(refusal))
        }
        for ((chunkPos, cells) in positions.groupBy { ChunkPos.containing(it) }) {
            val chunk = level.chunkSource.getChunkNow(chunkPos.x(), chunkPos.z()) ?: error("checked loaded") // refusal checked each cell
            val added = cells.map { BlockSnapshot(pack(chunk.minY, it), placed, until, player, act) }
            save(level, chunk, chunk.getAttached(TYPE)?.entries.orEmpty() + added)
        }
        val set = positions.count { level.setBlock(it, block, FLAGS) }
        if (set < positions.size) log.warn("${positions.size - set} of ${positions.size} ${block.block.id} did not change the world; their snapshots are skipped when due")
        val reason = "$set ${block.block.id} until ${time(until)}" + (act?.let { " (act $it)" } ?: "")
        record(level, "trap set", player, reason, act, detail(block, set, until, act, positions))
        return Result.success(set)
    }

    /**
     * Why [block] may not go at [pos] now: outside the world, in a chunk not loaded, not air (a liquid, a plant,
     * a snow layer, any block), holding a trap block already, or taken by an entity (a creature, a player, an
     * item on the ground, a frame, a painting, an armor stand, a minecart…), but [player] for a block without
     * collision. Null when it may. Never loads a chunk. Server thread.
     */
    fun cellRefusal(level: ServerLevel, pos: BlockPos, block: BlockState, player: UUID): String? {
        if (level.isOutsideBuildHeight(pos)) return "${at(pos)} is outside the world"
        val chunk = level.chunkSource.getChunkNow(SectionPos.blockToSectionCoord(pos.x), SectionPos.blockToSectionCoord(pos.z))
            ?: return "${at(pos)} is not loaded"
        val state = chunk.getBlockState(pos)
        if (!state.isAir) return "${at(pos)} is not air (${state.block.id})"
        if (isPending(level, chunk.minY, pos)) return "${at(pos)} already holds a trap block"
        val passable = block.getCollisionShape(level, pos).isEmpty
        val entity = level.getEntities(null as Entity?, AABB(pos)) { !(passable && it.uuid == player) }.firstOrNull()
        if (entity != null) return "${at(pos)} is taken by ${entity.typeId}"
        return null
    }

    /** Takes away now every block set against [player] (a pardon), in the loaded chunks; how many. Server thread. */
    fun restoreFor(player: UUID): Int =
        index.entries.toList().filter { (_, indexed) -> indexed.entries.any { it.player == player } }
            .sumOf { (key, indexed) -> takeAway(key, indexed.level, "lifted") { it.player == player } }

    /** Takes away now every block pending in the loaded chunks (`/narrator traps restore`); how many. Server thread. */
    fun restoreAll(): Int = index.entries.toList().sumOf { (key, indexed) -> takeAway(key, indexed.level, "taken away at once") { true } }

    /** The traps holding [player], for `activeOn`: kind `trap`, harmful, by block (`22 minecraft:glass`). Any thread. */
    fun activeOn(player: UUID): List<ActiveEffect> =
        index.values.flatMap { it.entries }.filter { it.player == player }.groupBy { blockId(it.placed) }
            .map { (block, entries) -> ActiveEffect("trap", "${entries.size} $block", entries.maxOf { it.until }, harmful = true) }

    /** Blocks waiting to be taken away, in the chunks loaded. Any thread. */
    fun pending(): Int = index.values.sumOf { it.entries.size }

    /**
     * Registers [TYPE] and the hooks: `CHUNK_LOAD` (indexes the chunk's entries; never a block change in that
     * call), `CHUNK_UNLOAD` (forgets them: they stay in the chunk, saved with it), `END_SERVER_TICK` (once a
     * second, takes away what is due) and `SERVER_STOPPED` (forgets everything). Once, at mod initialization
     * (`registerGuardHooks`).
     */
    fun registerHooks() {
        // Touching the type registers it: it must exist before the first chunk loads, or the chunk's entries are
        // dropped as an unknown attachment (seen on a real restart).
        log.info("attachment: ${TYPE.identifier()}")
        ServerChunkEvents.CHUNK_LOAD.register { level, chunk, _ -> hook("chunk_load") { onChunkLoad(level, chunk) } }
        ServerChunkEvents.CHUNK_UNLOAD.register { level, chunk -> hook("chunk_unload") { index.remove(keyOf(level, chunk.pos)) } }
        ServerTickEvents.END_SERVER_TICK.register { server -> hook("tick") { tick(server) } }
        ServerLifecycleEvents.SERVER_STOPPED.register { index.clear() }
    }

    /** The packing of [PlacedBlocks.pack][io.github.clemsix6.narrator2.observer.PlacedBlocks.pack]: `((y - minY) shl 8) or ((z and 15) shl 4) or (x and 15)`. */
    internal fun pack(minY: Int, pos: BlockPos): Int = ((pos.y - minY) shl 8) or ((pos.z and 15) shl 4) or (pos.x and 15)

    /** The position [packed] stands for in the chunk at [chunkX], [chunkZ] whose lowest block is at [minY]. */
    internal fun unpack(chunkX: Int, chunkZ: Int, minY: Int, packed: Int): BlockPos =
        BlockPos((chunkX shl 4) + (packed and 15), minY + (packed ushr 8), (chunkZ shl 4) + ((packed shr 4) and 15))

    /** Indexes what [chunk] holds to take away, without changing it: the next pass does (M4 design review, finding 5). */
    private fun onChunkLoad(level: ServerLevel, chunk: LevelChunk) {
        val entries = chunk.getAttached(TYPE)?.entries
        if (entries.isNullOrEmpty()) return
        entries.forEach { stateOf(it.placed) }
        index[keyOf(level, chunk.pos)] = Indexed(level, entries)
        val due = entries.count { it.until <= System.currentTimeMillis() }
        log.info("chunk ${chunk.pos.x()} ${chunk.pos.z()} of ${level.dimension().identifier()} loaded with ${entries.size} trap blocks ($due due): taken away from the next second on")
    }

    /** Once a second (wall clock): takes away the blocks due in the loaded chunks of [server]. */
    private fun tick(server: MinecraftServer) {
        val now = System.currentTimeMillis()
        if (now - lastPass < PASS_MS) return
        lastPass = now
        if (index.isEmpty()) return
        for ((key, indexed) in index.entries.toList()) {
            if (indexed.level.server !== server || indexed.entries.none { it.until <= now }) continue
            takeAway(key, indexed.level, "due") { it.until <= now }
        }
    }

    /**
     * Takes away the blocks of the chunk at [key] that [pick] chooses, when the chunk is ready (a chunk still
     * loading waits for a later pass): back to air while still the block set, else left as it is. Their entries
     * go in both cases. How many went back to air. Server thread.
     */
    private fun takeAway(key: ChunkKey, level: ServerLevel, why: String, pick: (BlockSnapshot) -> Boolean): Int {
        // Null while CHUNK_LOAD runs (its FULL stage is not done): a block change would wait for it forever.
        val chunk = level.chunkSource.getChunkNow(key.x, key.z) ?: return 0
        val (due, kept) = chunk.getAttached(TYPE)?.entries.orEmpty().partition(pick)
        if (due.isEmpty()) {
            if (kept.isEmpty()) index.remove(key) else index[key] = Indexed(level, kept)
            return 0
        }
        val air = Blocks.AIR.defaultBlockState()
        val restored = mutableListOf<BlockSnapshot>()
        val changed = mutableListOf<Pair<BlockSnapshot, String>>()
        for (entry in due) {
            val pos = unpack(key.x, key.z, chunk.minY, entry.packed)
            val current = chunk.getBlockState(pos)
            val placed = stateOf(entry.placed)
            if (placed != null && current == placed) {
                level.setBlock(pos, air, FLAGS)
                restored += entry
            } else {
                changed += entry to "${current.block.id} at ${at(pos)}"
            }
        }
        save(level, chunk, kept)
        for ((player, entries) in restored.groupBy { it.player }) {
            val blocks = entries.groupBy { blockId(it.placed) }.entries.joinToString { (block, same) -> "${same.size} $block" }
            record(level, "trap restored", player, "$blocks taken away ($why)", entries.first().act, null)
        }
        for ((player, entries) in changed.groupBy { it.first.player }) {
            val named = entries.take(NAMED_MAX).joinToString { it.second } + if (entries.size > NAMED_MAX) ", …" else ""
            val count = if (entries.size == 1) "1 block" else "${entries.size} blocks"
            record(level, "trap skipped", player, "$count changed since set, left as it is ($why): $named", entries.first().first.act, null)
        }
        return restored.size
    }

    /** Why [positions] may not take [block] now, all or nothing; null when they may. */
    private fun refusal(level: ServerLevel, positions: List<BlockPos>, block: BlockState, player: UUID): String? {
        if (block.block !in allowed) return "only glass and cobwebs may be set (not ${block.block.id})"
        if (positions.isEmpty()) return "no block to set"
        if (positions.size > MAX_PER_CALL) return "${positions.size} blocks: a trap sets $MAX_PER_CALL at most"
        if (positions.toSet().size < positions.size) return "the same cell is given twice"
        val waiting = pending()
        if (waiting + positions.size > MAX_PENDING) return "$waiting trap blocks already wait to be taken away on the server, $MAX_PENDING at most"
        for (pos in positions) cellRefusal(level, pos, block, player)?.let { return it }
        return null
    }

    /** Writes [entries] to [chunk] (or removes the attachment when none is left) and to the index. */
    private fun save(level: ServerLevel, chunk: LevelChunk, entries: List<BlockSnapshot>) {
        val key = keyOf(level, chunk.pos)
        if (entries.isEmpty()) {
            chunk.removeAttached(TYPE)
            index.remove(key)
        } else {
            chunk.setAttached(TYPE, ChunkRestorations(entries))
            index[key] = Indexed(level, entries)
        }
        chunk.markUnsaved()
    }

    private fun isPending(level: ServerLevel, minY: Int, pos: BlockPos): Boolean {
        val entries = index[keyOf(level, ChunkPos.containing(pos))]?.entries ?: return false
        val packed = pack(minY, pos)
        return entries.any { it.packed == packed }
    }

    private fun keyOf(level: ServerLevel, pos: ChunkPos) = ChunkKey(level.dimension(), pos.x(), pos.z())

    /**
     * [state] in SNBT (`{id:"minecraft:glass"}`), as [BlockSnapshot.placed] keeps it. Never put in [states]
     * directly: what a chunk saved is always read back the same way, before a restart as after it.
     */
    private fun snbt(state: BlockState): String = NbtUtils.writeBlockState(state).toString()

    /** The block state [snbt] stands for; null when it no longer reads (a block gone from the game): such a block is never touched. */
    private fun stateOf(snbt: String): BlockState? = states.computeIfAbsent(snbt) {
        try {
            Optional.of(NbtUtils.readBlockState(BuiltInRegistries.BLOCK, TagParser.parseCompoundFully(it)))
        } catch (e: Exception) {
            log.warn("a trap block no longer reads: $it", e)
            Optional.empty()
        }
    }.orElse(null)

    /** The id of the block [snbt] stands for, from what [stateOf] already read. Any thread. */
    private fun blockId(snbt: String): String = states[snbt]?.orElse(null)?.block?.id ?: "unknown block"

    /** [player]'s name when online, else their UUID. */
    private fun nameOf(level: ServerLevel, player: UUID): String = level.server.playerList.getPlayer(player)?.plainTextName ?: player.toString()

    /** Records a `guard` decision with the running Narrator of [level]'s server; else only the log says it. */
    private fun record(level: ServerLevel, decision: String, player: UUID, reason: String, act: Long?, detail: JsonObject?) {
        val subject = nameOf(level, player)
        val runtime = NarratorRuntime.current?.takeIf { it.server === level.server }
        if (runtime == null) {
            log.info(Decisions.line(decision, subject, reason))
            return
        }
        Decisions(runtime.trace, runtime.clock).record(
            log, DecisionRecord.Kind.GUARD, decision, subject, reason, turnId = act?.takeIf { it > 0 }, detail = detail,
        )
    }

    private fun detail(block: BlockState, count: Int, until: Long, act: Long?, positions: List<BlockPos>) = buildJsonObject {
        put("block", block.block.id)
        put("count", count)
        put("until", until)
        act?.let { put("act", it) }
        val first = positions.first()
        put("at", "${first.x} ${first.y} ${first.z}")
    }

    private fun at(pos: BlockPos) = "${pos.x} ${pos.y} ${pos.z}"

    private val timeOfDay: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    private fun time(epochMs: Long): String = timeOfDay.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    /** Runs a hook's [body]: a failure is logged, never thrown into the game. */
    private inline fun hook(name: String, body: () -> Unit) {
        try {
            body()
        } catch (e: Exception) {
            log.error("block restorer hook $name failed", e)
        }
    }
}
