package io.github.clemsix6.narrator2.guard

import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.StringTag
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The guards' state saved with a player (M1_M2 §10.2), with what M4 adds (M4 §5.6, §7, §13.2): old states still read
 * back, and a stack of any length goes through the game's NBT writer whole.
 */
class GuardStateCodecTest {
    private val mimosa = UUID.fromString("00000000-0000-0000-0000-0000000041a0")

    private fun read(json: String): GuardState = GuardState.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow()

    private fun roundTrip(state: GuardState): GuardState = GuardState.CODEC.parse(JsonOps.INSTANCE, GuardState.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow()).getOrThrow()

    @Test
    fun `a state saved before M4 reads back, its episode's turn as the act`() {
        val old = read(
            """
            {"confiscations":[{"id":7,"items":["{id:\"minecraft:diamond\",count:3}"],"what":"3x minecraft:diamond","taken_at":10,"return_at":20,"reason":"greed","turn_id":42}],
             "episode":{"turn_id":42,"until":3000},"armed_until":120,"narrator_kills":[5]}
            """.trimIndent(),
        )
        assertEquals(OpenEpisode(act = 42, until = 3_000), old.episode)
        val taken = old.confiscations.single()
        assertEquals(LedgerPurpose.CONFISCATION, taken.purpose, "a confiscation of M2")
        assertEquals(null, taken.canon)
        assertEquals(null, taken.owedTo)
        assertEquals(null, old.graceUntil)
        assertEquals(emptyList(), old.jokersGivenAt)
        assertEquals(null, old.lastOfferAt)
        assertEquals(GuardState.EMPTY, read("{}"))
    }

    @Test
    fun `what M4 adds goes to the player's file and back, the act written as such`() {
        val state = GuardState(
            confiscations = listOf(
                Confiscation(812, listOf("{id:\"minecraft:iron_ingot\",count:12}"), "12x minecraft:iron_ingot", 10, 20, "bet on C-50", null, LedgerPurpose.STAKE, canon = 50, owedTo = mimosa),
                Confiscation(815, listOf("{id:\"minecraft:diamond\",count:10}"), "10x minecraft:diamond", 10, 30, "offering", null, LedgerPurpose.OFFERING),
                Confiscation(816, listOf("{id:\"minecraft:paper\",count:1}"), "1x minecraft:paper", 10, 10, "a joker", null, LedgerPurpose.OWED),
            ),
            episode = OpenEpisode(act = -812, until = 4_000),
            graceUntil = 5_000,
            lastGraceEnd = 5_000,
            jokersGivenAt = listOf(1_000, 2_000),
            lastOfferAt = 10,
        )
        assertEquals(state, roundTrip(state))
        val written = GuardState.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow().asJsonObject
        assertEquals(setOf("act", "until"), written.getAsJsonObject("episode").keySet(), "never turn_id again")
        assertEquals("stake", written.getAsJsonArray("confiscations")[0].asJsonObject["purpose"].asString)
    }

    @Test
    fun `a stack longer than an NBT string can hold goes to the player's file and back whole`() {
        // A book and quill full to the brim: 100 pages of 1,024 characters, more than the 65,535 bytes of writeUTF.
        val pages = (1..100).joinToString(",") { page -> "{raw:\"${"$page".padEnd(1_024, 'a')}\"}" }
        val book = "{count:1,id:\"minecraft:writable_book\",components:{\"minecraft:writable_book_content\":{pages:[$pages]}}}"
        val state = GuardState(
            confiscations = listOf(
                Confiscation(812, listOf(book, "{id:\"minecraft:diamond\",count:3}"), "1x minecraft:writable_book, 3x minecraft:diamond", 10, 20, "bet", null, LedgerPurpose.STAKE, canon = 50, dataVersion = 4_700),
            ),
        )
        assertTrue(book.length > 100_000)

        val tag = GuardState.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow() as CompoundTag
        val bytes = ByteArrayOutputStream().also { NbtIo.write(tag, DataOutputStream(it)) }.toByteArray()
        val read = NbtIo.read(DataInputStream(ByteArrayInputStream(bytes)))
        assertEquals(state, GuardState.CODEC.parse(NbtOps.INSTANCE, read).getOrThrow(), "nothing written as \"\"")

        val items = tag.getListOrEmpty("confiscations").getCompoundOrEmpty(0).getListOrEmpty("items")
        assertEquals(StringTag.valueOf("{id:\"minecraft:diamond\",count:3}"), items[1], "a short stack is one string, as it always was")
        assertTrue(items[0] is ListTag, "a long one is saved in chunks")
    }

    @Test
    fun `the data version, the settlement and the stakes received go to the player's file and back`() {
        val state = GuardState(
            confiscations = listOf(
                Confiscation(812, listOf("{id:\"minecraft:iron_ingot\",count:12}"), "12x minecraft:iron_ingot", 10, 20, "bet", null, LedgerPurpose.STAKE, canon = 50, dataVersion = 4_700, settledAt = 15),
            ),
            receivedStakes = listOf(ReceivedStake(790, 12)),
        )
        assertEquals(state, roundTrip(state))
        assertEquals(state, GuardState.CODEC.parse(NbtOps.INSTANCE, GuardState.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow()).getOrThrow())
        val written = GuardState.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow().asJsonObject
        assertEquals(4_700, written.getAsJsonArray("confiscations")[0].asJsonObject["data_version"].asInt)
        assertEquals(setOf("stake", "at"), written.getAsJsonArray("received_stakes")[0].asJsonObject.keySet())
    }

    @Test
    fun `a shield goes to the player's file and back, so that it holds across a restart`() {
        val state = GuardState(lastDeathAt = 9, shield = Shield(at = 10, until = 300_010, reason = "duel C-52: fatal blow stopped at 1 hp"))
        assertEquals(state, roundTrip(state))
        val tag = CompoundTag().apply { put("guard_state", GuardState.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow()) }
        val bytes = ByteArrayOutputStream().also { NbtIo.write(tag, DataOutputStream(it)) }.toByteArray()
        val back = NbtIo.read(DataInputStream(ByteArrayInputStream(bytes))).get("guard_state")!!
        assertEquals(state, GuardState.CODEC.parse(NbtOps.INSTANCE, back).getOrThrow(), "through the game's NBT writer")
        val written = GuardState.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow().asJsonObject
        assertEquals(setOf("at", "until", "reason"), written.getAsJsonObject("shield").keySet())
        assertEquals(null, read("{}").shield, "a state saved before it has none")
    }

    @Test
    fun `a purpose a later version wrote reads as a confiscation, so its items still go back to their owner`() {
        val state = read("""{"confiscations":[{"id":1,"items":[],"what":"","taken_at":1,"return_at":2,"purpose":"tithe"}]}""")
        assertEquals(LedgerPurpose.CONFISCATION, state.confiscations.single().purpose)
        assertEquals(LedgerPurpose.entries, LedgerPurpose.entries.map { LedgerPurpose.of(it.id) })
    }
}
