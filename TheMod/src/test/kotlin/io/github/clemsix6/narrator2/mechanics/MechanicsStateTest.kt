package io.github.clemsix6.narrator2.mechanics

import com.mojang.serialization.JsonOps
import io.github.clemsix6.narrator2.director.ReminderState
import io.github.clemsix6.narrator2.director.SavedReminder
import io.github.clemsix6.narrator2.testing.LogCapture
import io.github.clemsix6.narrator2.testing.MechanicsSamples
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtOps
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The engine's live state saved with the world (M4 §3.2), and the Director's reminders (M4 §15.1). */
class MechanicsStateTest {
    private val sample = MechanicsSamples.state

    private fun json(state: MechanicsState): JsonObject = Json.parseToJsonElement(MechanicsState.encode(state)).jsonObject

    @Test
    fun `the state goes to JSON and back unchanged, through the attachment's codec too`() {
        assertEquals(sample, MechanicsState.decode(MechanicsState.encode(sample)))

        val saved = MechanicsState.CODEC.encodeStart(JsonOps.INSTANCE, sample).getOrThrow()
        assertTrue(saved.isJsonPrimitive && saved.asJsonPrimitive.isString, "one JSON string in the world")
        assertEquals(sample, MechanicsState.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow())
    }

    @Test
    fun `an empty state is empty both ways, and every default is written`() {
        assertEquals(MechanicsState.EMPTY, MechanicsState.decode(MechanicsState.encode(MechanicsState.EMPTY)))
        assertEquals(MechanicsState.EMPTY, MechanicsState.decode("{}"))
        val written = json(sample)
        assertEquals(setOf("challenges", "rules", "last_challenge_at", "returns", "settlements", "rewards"), written.keys)
        val challenge = written.getValue("challenges").jsonArray[0].jsonObject
        assertEquals(JsonPrimitive(1), challenge.getValue("spec").jsonObject["count"], "a default value is saved: a later default never changes it")
        assertEquals(JsonPrimitive("obtain"), challenge.getValue("spec").jsonObject["goal"], "snake_case on the wire")
    }

    @Test
    fun `an unreadable challenge or rule is dropped alone, logged, and kept for the reconciliation`() {
        val written = json(sample).toMutableMap()
        val challenges = written.getValue("challenges").jsonArray
        // C-50 asks for a goal the code no longer knows; C-52 still reads.
        val removedGoal = JsonObject(
            challenges[0].jsonObject + ("spec" to JsonObject(challenges[0].jsonObject.getValue("spec").jsonObject + ("goal" to JsonPrimitive("dance")))),
        )
        written["challenges"] = JsonArray(listOf(removedGoal, challenges[1]))
        // C-60 has a field of the wrong type; C-61 still reads.
        val rules = written.getValue("rules").jsonArray
        written["rules"] = JsonArray(listOf(JsonObject(rules[0].jsonObject + ("fired" to JsonPrimitive("many"))), rules[1]))

        val logs = LogCapture()
        val read = logs.use { MechanicsState.decode(JsonObject(written).toString()) }

        assertEquals(listOf(52L), read.challenges.map { it.id })
        assertEquals(listOf(61L), read.rules.map { it.id })
        assertEquals(sample.returns, read.returns)
        assertEquals(sample.settlements + Settlement(MechanicsSamples.josiane.uuid.toString(), 812), read.settlements, "C-50's stake goes back")
        assertEquals(listOf("challenge" to 50L, "rule" to 60L), read.unreadable.map { it.what to it.id })
        assertContains(logs.text, "a challenge of the mechanics' state cannot be read and is dropped (C-50)")
        assertContains(logs.text, "a rule of the mechanics' state cannot be read and is dropped (C-60)")
        assertEquals(MechanicsState.EMPTY.unreadable, MechanicsState.decode(MechanicsState.encode(read)).unreadable, "never saved again")
    }

    @Test
    fun `the stakes of an unreadable challenge or settlement go back to their owners, not a week later`() {
        val written = json(sample).toMutableMap()
        val challenges = written.getValue("challenges").jsonArray
        // C-50, with Josiane's bet (stake 812), asks for a goal the code no longer knows.
        val removedGoal = JsonObject(
            challenges[0].jsonObject + ("spec" to JsonObject(challenges[0].jsonObject.getValue("spec").jsonObject + ("goal" to JsonPrimitive("dance")))),
        )
        written["challenges"] = JsonArray(listOf(removedGoal, challenges[1]))
        // Stake 790 was ceded to Mimosa, but its winner no longer reads; a settlement with no stake at all is lost.
        val settlement = written.getValue("settlements").jsonArray[0].jsonObject
        written["settlements"] = JsonArray(listOf(JsonObject(settlement + ("to" to JsonPrimitive(3))), JsonObject(mapOf("owner" to JsonPrimitive("x")))))

        val read = LogCapture().use { MechanicsState.decode(JsonObject(written).toString()) }

        val josiane = MechanicsSamples.josiane.uuid.toString()
        assertEquals(listOf(Settlement(josiane, 790), Settlement(josiane, 812)), read.settlements.sortedBy { it.stake }, "both given back")
        assertEquals(listOf("challenge" to 50L, "settlement" to null, "settlement" to null), read.unreadable.map { it.what to it.id })
        assertEquals(listOf(812L), sample.challenge(50)!!.bets.map { it.stake }, "the sample's bet")
    }

    @Test
    fun `a reward owed goes to the world's file and back, and one unreadable is dropped alone, its canon left alone`() {
        val mimosa = MechanicsSamples.runningChallenge.participants[1]
        val owed = OwedReward(MechanicsSamples.runningChallenge.copy(bets = emptyList()), mimosa, at = 1_000)
        val state = sample.copy(rewards = listOf(owed))
        assertEquals(state, MechanicsState.decode(MechanicsState.encode(state)))
        val tag = CompoundTag()
        tag.put("mechanics", MechanicsState.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow())
        val bytes = ByteArrayOutputStream().also { NbtIo.write(tag, DataOutputStream(it)) }.toByteArray()
        val read = NbtIo.read(DataInputStream(ByteArrayInputStream(bytes))).get("mechanics")!!
        assertEquals(listOf(owed), MechanicsState.CODEC.parse(NbtOps.INSTANCE, read).getOrThrow().rewards, "through the world's file")

        val written = json(state).toMutableMap()
        val reward = written.getValue("rewards").jsonArray[0].jsonObject
        written["rewards"] = JsonArray(listOf(JsonObject(reward + ("at" to JsonPrimitive("soon"))), reward))
        val partly = LogCapture().use { MechanicsState.decode(JsonObject(written).toString()) }
        assertEquals(listOf(owed), partly.rewards)
        assertEquals(listOf("reward" to null), partly.unreadable.map { it.what to it.id }, "no canon id: the reconciliation never cancels a challenge won")
    }

    @Test
    fun `a state longer than an NBT string can hold goes to the world's file and back whole`() {
        val long = "Une loi au texte interminable. ".repeat(3_000)
        val state = sample.copy(rules = sample.rules.map { it.copy(spec = it.spec.copy(statement = long)) }, settlements = sample.settlements.map { it.copy(at = 5) })
        assertTrue(MechanicsState.encode(state).length > 100_000)

        val tag = CompoundTag()
        tag.put("mechanics", MechanicsState.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow())
        val bytes = ByteArrayOutputStream().also { NbtIo.write(tag, DataOutputStream(it)) }.toByteArray()
        val read = NbtIo.read(DataInputStream(ByteArrayInputStream(bytes))).get("mechanics")!!
        assertEquals(state, MechanicsState.CODEC.parse(NbtOps.INSTANCE, read).getOrThrow(), "nothing written as \"\"")
        assertTrue(read is ListTag, "in chunks")
    }

    @Test
    fun `a challenge with both a spec and a duel, or neither, cannot be read nor built`() {
        val both = JsonObject(json(sample).getValue("challenges").jsonArray[0].jsonObject + ("duel" to Json.parseToJsonElement(
            MechanicsState.JSON.encodeToString(DuelSpec.serializer(), MechanicsSamples.duel),
        )))
        val read = LogCapture().use { MechanicsState.decode("""{"challenges":[$both]}""") }
        assertEquals(emptyList(), read.challenges)
        assertEquals("C-50 has both a challenge and a duel", read.unreadable.single().error)

        assertFailsWith<IllegalArgumentException> { MechanicsSamples.runningChallenge.copy(spec = null) }
    }

    @Test
    fun `unknown properties are ignored, at every level`() {
        val written = json(sample).toMutableMap()
        written["since_m9"] = JsonPrimitive(true)
        val challenge = written.getValue("challenges").jsonArray[0].jsonObject
        written["challenges"] = JsonArray(
            listOf(
                JsonObject(challenge + ("mood" to JsonPrimitive("grumpy")) + ("spec" to JsonObject(challenge.getValue("spec").jsonObject + ("glitter" to JsonPrimitive(3))))),
                written.getValue("challenges").jsonArray[1],
            ),
        )
        assertEquals(sample, MechanicsState.decode(JsonObject(written).toString()))
    }

    @Test
    fun `a text that is not a state at all gives an empty state that says so, never an exception`() {
        listOf("", "not json", "[1, 2]", """{"challenges": 3}""").forEach { text ->
            val read = LogCapture().use { MechanicsState.decode(text) }
            assertEquals(emptyList(), read.challenges, text)
            assertEquals("state", read.unreadable.single().what, text)
        }
    }

    @Test
    fun `the state finds its challenges and rules by canon id, and the canon references are theirs`() {
        assertEquals("Œuf de tortue", sample.challenge(50)?.title)
        assertEquals("Duel au soleil", sample.challenge(52)?.title)
        assertTrue(sample.challenge(52)!!.isDuel)
        assertEquals("C-61", sample.rule(61)?.ref.toString())
        assertEquals(null, sample.challenge(60))
        assertEquals(MechanicsSamples.mimosa.uuid, sample.challenge(50)!!.participants[1].playerId)
        assertEquals("Mimosa", sample.challenge(50)!!.participant(MechanicsSamples.mimosa.uuid)?.name)
    }

    @Test
    fun `the reminders go to JSON and back, a reminder that cannot be read is dropped alone`() {
        val reminders = MechanicsSamples.reminders
        assertEquals(reminders, ReminderState.decode(ReminderState.encode(reminders)))
        val saved = ReminderState.CODEC.encodeStart(JsonOps.INSTANCE, reminders).getOrThrow()
        assertEquals(reminders, ReminderState.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow())

        val bad = """{"next_id":9,"reminders":[{"id":7,"note":"x","set_at":1,"at":"soon"},{"id":8,"note":"y","set_at":1,"at":2,"extra":1}]}"""
        assertEquals(ReminderState(9, listOf(SavedReminder(8, "y", 1, 2))), LogCapture().use { ReminderState.decode(bad) })
        assertEquals(ReminderState.EMPTY, LogCapture().use { ReminderState.decode("oops") })
        assertEquals(1, ReminderState.EMPTY.nextId)
    }
}
