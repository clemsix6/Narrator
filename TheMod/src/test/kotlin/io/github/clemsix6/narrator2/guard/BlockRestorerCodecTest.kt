package io.github.clemsix6.narrator2.guard

import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import net.minecraft.core.BlockPos
import net.minecraft.nbt.NbtOps
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/** The trap blocks saved with a chunk (`narrator2:restorations`, M4 §9.1), and how a position is packed in it. */
class BlockRestorerCodecTest {
    private val bartok = UUID.fromString("00000000-0000-0000-0000-00000000ba70")
    private val glass = "{id:\"minecraft:glass\"}"

    private val saved = ChunkRestorations(
        listOf(
            BlockSnapshot(packed = 0x4_0812, placed = glass, until = 1_760_900_412_000, player = bartok, act = 42),
            BlockSnapshot(packed = 0x4_1812, placed = "{id:\"minecraft:cobweb\"}", until = 1_760_900_430_000, player = bartok, act = -812),
            BlockSnapshot(packed = 7, placed = glass, until = 1, player = bartok, act = null),
        ),
    )

    @Test
    fun `the blocks to take away go to the chunk's file and back, as JSON and as NBT`() {
        val json = ChunkRestorations.CODEC.encodeStart(JsonOps.INSTANCE, saved).getOrThrow()
        assertEquals(saved, ChunkRestorations.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow())
        val nbt = ChunkRestorations.CODEC.encodeStart(NbtOps.INSTANCE, saved).getOrThrow()
        assertEquals(saved, ChunkRestorations.CODEC.parse(NbtOps.INSTANCE, nbt).getOrThrow())
        val entry = json.asJsonObject.getAsJsonArray("entries")[0].asJsonObject
        assertEquals(setOf("packed", "placed", "until", "player", "act"), entry.keySet())
        assertEquals(glass, entry["placed"].asString, "the block set, in SNBT")
    }

    @Test
    fun `an entry without act reads back, and an empty or foreign attachment is no block`() {
        val read = ChunkRestorations.CODEC.parse(
            JsonOps.INSTANCE,
            JsonParser.parseString("""{"entries":[{"packed":7,"placed":"{id:\"minecraft:glass\"}","until":1,"player":[0,0,0,47728],"later":true}]}"""),
        ).getOrThrow()
        assertEquals(listOf(BlockSnapshot(7, glass, 1, bartok, null)), read.entries, "no act, and a key of a later version ignored")
        assertEquals(ChunkRestorations(), ChunkRestorations.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{}")).getOrThrow())
        val written = ChunkRestorations.CODEC.encodeStart(JsonOps.INSTANCE, ChunkRestorations(listOf(BlockSnapshot(7, glass, 1, bartok, null)))).getOrThrow()
        assertEquals(false, written.asJsonObject.getAsJsonArray("entries")[0].asJsonObject.has("act"), "no act written when there is none")
    }

    @Test
    fun `a position packs into its chunk and back, below zero and at the top of the world`() {
        val minY = -64
        val cases = listOf(BlockPos(0, -64, 0), BlockPos(15, 319, 15), BlockPos(-1, 70, -1), BlockPos(-17, -3, 33), BlockPos(30_000, 150, -29_999))
        for (pos in cases) {
            val packed = BlockRestorer.pack(minY, pos)
            assertEquals(pos, BlockRestorer.unpack(pos.x shr 4, pos.z shr 4, minY, packed), "$pos packed as $packed")
        }
        assertEquals(((70 + 64) shl 8) or (15 shl 4) or 15, BlockRestorer.pack(minY, BlockPos(-1, 70, -1)), "the packing of PlacedBlocks.pack")
    }
}
