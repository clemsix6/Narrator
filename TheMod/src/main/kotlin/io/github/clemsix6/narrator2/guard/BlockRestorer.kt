package io.github.clemsix6.narrator2.guard

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.mechanics.MechanicsLimits
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry
import net.fabricmc.fabric.api.attachment.v1.AttachmentType
import net.minecraft.core.BlockPos
import net.minecraft.core.UUIDUtil
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.state.BlockState
import java.util.Optional
import java.util.UUID

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
 * @property packed its position in the chunk (`PlacedBlocks.pack`).
 * @property placed the block state set there (there was air before), in SNBT: it is taken away only while it
 *   is still this one.
 * @property until when it is taken away, epoch milliseconds.
 * @property player whom the trap holds.
 * @property act the act that set it (`Caller.act`).
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
 * never changes a block while a chunk loads (`CHUNK_LOAD` only indexes; the next second takes away what is
 * due). A block is taken away only while it is still the one set: a block a player put there stays.
 *
 * Until M4-05, [place] refuses (`not wired yet`) and nothing is ever pending; [TYPE] is registered so that
 * the chunks' data survive from the first world on.
 */
object BlockRestorer {
    private val log = Log.guard

    /** Blocks one call may set; blocks waiting to be taken away on the whole server. */
    const val MAX_PER_CALL = MechanicsLimits.TRAP_BLOCKS_PER_CALL
    const val MAX_PENDING = MechanicsLimits.TRAP_BLOCKS_PENDING

    /** The persistent chunk attachment `narrator2:restorations`, registered by [registerHooks] before any world loads. */
    val TYPE: AttachmentType<ChunkRestorations> by lazy {
        AttachmentRegistry.create(Identifier.fromNamespaceAndPath("narrator2", "restorations")) { builder -> builder.persistent(ChunkRestorations.CODEC) }
    }

    /**
     * Puts [block] at [positions] until [until] (epoch milliseconds), against [player], for [act]: all or
     * nothing. Each position must be loaded, air, free of any entity (but [player] for a block without
     * collision, a cobweb), and not pending already; [MAX_PER_CALL] and [MAX_PENDING] hold. The snapshot is
     * written to the chunk before the block changes, in the same tick. The number of blocks set, or why
     * none was. Server thread.
     */
    fun place(level: ServerLevel, positions: List<BlockPos>, block: BlockState, until: Long, player: UUID, act: Long?): Result<Int> =
        Result.failure(IllegalStateException("not wired yet"))

    /** Takes away now every block set against [player] (a pardon); how many. Server thread. */
    fun restoreFor(player: UUID): Int = 0

    /** Takes away now every block pending in the loaded chunks (`/narrator traps restore`); how many. Server thread. */
    fun restoreAll(): Int = 0

    /** The traps holding [player], for `activeOn` (kind `trap`, harmful). Any thread. */
    fun activeOn(player: UUID): List<ActiveEffect> = emptyList()

    /** Blocks waiting to be taken away, in the chunks loaded or indexed. Any thread. */
    fun pending(): Int = 0

    /**
     * Registers [TYPE] and the hooks: `CHUNK_LOAD` (index the chunk's entries; never a block change in that
     * call), `CHUNK_UNLOAD`, and `END_SERVER_TICK` (once a second, take away what is due). Once, at mod
     * initialization (`registerGuardHooks`).
     */
    fun registerHooks() {
        log.debug { "attachment: ${TYPE.identifier()}" }
    }
}
