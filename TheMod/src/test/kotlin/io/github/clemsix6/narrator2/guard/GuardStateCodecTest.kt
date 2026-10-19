package io.github.clemsix6.narrator2.guard

import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/** The guards' state saved with a player (M1_M2 §10.2), with what M4 adds (M4 §5.6, §7, §13.2): old states still read back. */
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
    fun `a purpose a later version wrote reads as a confiscation, so its items still go back to their owner`() {
        val state = read("""{"confiscations":[{"id":1,"items":[],"what":"","taken_at":1,"return_at":2,"purpose":"tithe"}]}""")
        assertEquals(LedgerPurpose.CONFISCATION, state.confiscations.single().purpose)
        assertEquals(LedgerPurpose.entries, LedgerPurpose.entries.map { LedgerPurpose.of(it.id) })
    }
}
