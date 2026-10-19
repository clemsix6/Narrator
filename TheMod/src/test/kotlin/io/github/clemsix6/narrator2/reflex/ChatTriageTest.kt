package io.github.clemsix6.narrator2.reflex

import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.event.ChatMessage
import io.github.clemsix6.narrator2.event.Envelope
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.testing.FakeReflexes
import io.github.clemsix6.narrator2.testing.SampleEvents
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.trace.ChatSignalsRecord
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [triageChatMessage] and [ReflexThresholds]: what the salience gets for a chat message, and its `chat_signals` row. */
class ChatTriageTest {
    private val player = PlayerRef(UUID.fromString("5a0e2b6c-1f7d-4c1b-9d8e-0a1b2c3d4e5f"), "Thawyn")
    private val sentAt = 1_790_000_000_123L

    private fun envelope(text: String, channel: ChatChannel = ChatChannel.PUBLIC, id: Long = 42) =
        Envelope(id, sentAt, 1_000, ChatMessage(player, channel, text, emptyList()))

    private fun triage(services: TestServices, text: String, recent: List<ChatLine> = emptyList(), narratorLast: String? = null, channel: ChatChannel = ChatChannel.PUBLIC) =
        runBlocking { triageChatMessage(services, envelope(text, channel), recent, narratorLast) }

    @Test
    fun `a triage asks the reflexes and writes the message's chat_signals row`() {
        val personality = PersonalitySignals(0.1, 0.2, 0.3, 0.9, 0.4)
        val reflexes = FakeReflexes(chat = { FakeReflexes.verdict(0.92, Tone.HOSTILE, ChatIntent.PROVOCATION, manipulation = 0.2).copy(personality = personality, latencyMs = 312) })
        val services = TestServices(reflexes = reflexes)
        val recent = (1..5).map { ChatLine("p$it", "line $it") }

        val triage = triage(services, "narrateur t'es nul", recent, narratorLast = "Silence, mortels.")

        assertEquals(ChatTriageInput(ChatLine("Thawyn", "narrateur t'es nul"), recent.takeLast(3), "Silence, mortels."), reflexes.chatInputs.single())
        assertTrue(triage.addressed)
        assertEquals("fake 0.92 >= 0.60", triage.reason)
        assertEquals(Tone.HOSTILE, triage.tone)
        assertFalse(triage.manipulation)
        assertFalse(triage.byHeuristic)
        assertEquals(
            ChatSignalsRecord(
                eventId = 42, ts = Instant.ofEpochMilli(sentAt), playerUuid = player.uuid, source = "fake",
                toNarrator = 0.92, tone = Tone.HOSTILE, toneConfidence = 0.9, intent = ChatIntent.PROVOCATION, manipulation = 0.2,
                politeness = 0.1, boastfulness = 0.2, helpfulness = 0.3, aggressiveness = 0.9, humor = 0.4, latencyMs = 312,
            ),
            services.traces.chatSignals.single(),
        )
    }

    @Test
    fun `without the reflex model the heuristic answers and the row has no personality signals`() {
        val services = TestServices()
        val reflexes = createReflexes(services)
        val row = runBlocking {
            triageChatMessage(TestServices(reflexes = reflexes, trace = services.trace), envelope("merci narrateur !"), emptyList(), null)
        }

        assertTrue(row.addressed)
        assertTrue(row.byHeuristic)
        assertEquals("heuristic 0.90 >= 0.60", row.reason)
        val signals = services.traces.chatSignals.single()
        assertEquals(HeuristicReflexes.SOURCE, signals.source)
        assertEquals(Tone.RESPECTFUL, signals.tone)
        assertNull(signals.politeness)
        assertNull(signals.humor)
        assertTrue(services.traces.llmCalls.isEmpty())
    }

    @Test
    fun `in a classifier's gray zone, the heuristic decides`() {
        val services = TestServices(reflexes = FakeReflexes(chat = { FakeReflexes.verdict(0.48) }))

        val named = triage(services, "narrateur t'es nul")
        val between = triage(services, "on continue à miner ?")

        assertTrue(named.addressed)
        assertEquals("fake 0.48 in the gray zone, heuristic 0.90 >= 0.60", named.reason)
        assertFalse(between.addressed)
        assertEquals("fake 0.48 in the gray zone, heuristic 0.05 < 0.60", between.reason)
        assertEquals(0.48, services.traces.chatSignals.first().toNarrator, "the row keeps the classifier's own answer")
    }

    @Test
    fun `the thresholds of the design`() {
        val never = { error("the heuristic is only asked in the gray zone") }
        fun model(toNarrator: Double) = FakeReflexes.verdict(toNarrator).copy(source = "reflex:claude-haiku-5-5")

        assertTrue(ReflexThresholds.triage(model(0.6), never).addressed)
        assertFalse(ReflexThresholds.triage(model(0.34), never).addressed)
        assertEquals("reflex:claude-haiku-5-5 0.34 < 0.35", ReflexThresholds.triage(model(0.34), never).reason)
        assertTrue(ReflexThresholds.triage(model(0.35)) { 0.6 }.addressed)
        assertFalse(ReflexThresholds.triage(model(0.59)) { 0.59 }.addressed)
        val heuristic = FakeReflexes.verdict(0.45).copy(source = HeuristicReflexes.SOURCE)
        assertFalse(ReflexThresholds.triage(heuristic, never).addressed, "the heuristic has no gray zone")

        val injection = ReflexThresholds.triage(model(0.05).copy(manipulation = 0.97), never)
        assertTrue(injection.addressed, "a manipulation is aimed at the Narrator, even without its name")
        assertEquals("reflex:claude-haiku-5-5 0.05, manipulation 0.97 >= 0.50", injection.reason)
        assertFalse(ReflexThresholds.triage(model(0.05).copy(manipulation = 0.49), never).addressed)

        val flagged = ReflexThresholds.triage(FakeReflexes.verdict(0.9, Tone.MOCKING_PLAYFUL, manipulation = 0.5).copy(toneConfidence = 0.59), never)
        assertTrue(flagged.manipulation)
        assertNull(flagged.tone, "a tone below 0.6 of confidence is not shown")
        assertNull(ReflexThresholds.triage(FakeReflexes.verdict(0.9, Tone.NEUTRAL_NO_TARGET), never).tone)
    }

    @Test
    fun `a prayer is triaged like a public message, and said to be one`() {
        val reflexes = FakeReflexes(chat = { FakeReflexes.verdict(0.97, Tone.MOCKING_PLAYFUL, ChatIntent.REQUEST) })
        val services = TestServices(reflexes = reflexes)

        assertTrue(triage(services, "rends-moi mes diamants", channel = ChatChannel.PRAY).addressed)
        assertEquals(1, services.traces.chatSignals.size)
        assertTrue(reflexes.chatInputs.single().prayer)
        triage(services, "rends-moi mes diamants")
        assertFalse(reflexes.chatInputs.last().prayer)
    }

    @Test
    fun `private messages and other events are never triaged`() {
        val reflexes = FakeReflexes()
        val services = TestServices(reflexes = reflexes)

        val whisper = assertFailsWith<IllegalArgumentException> { triage(services, "psst", channel = ChatChannel.WHISPER) }
        assertEquals("event 42 is a private message: those are never triaged", whisper.message)
        val other = Envelope(7, sentAt, 0, SampleEvents.all.first { it !is ChatMessage })
        assertFailsWith<IllegalArgumentException> { runBlocking { triageChatMessage(services, other, emptyList(), null) } }
        assertTrue(reflexes.chatInputs.isEmpty())
        assertTrue(services.traces.chatSignals.isEmpty())
    }
}
