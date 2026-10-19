package io.github.clemsix6.narrator2.mechanics

import io.github.clemsix6.narrator2.event.BlockBroken
import io.github.clemsix6.narrator2.event.ChatMessage
import io.github.clemsix6.narrator2.event.Envelope
import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.event.PlayerDied
import io.github.clemsix6.narrator2.memory.CanonKind
import io.github.clemsix6.narrator2.memory.CanonMechanic
import io.github.clemsix6.narrator2.testing.FakeMemory
import io.github.clemsix6.narrator2.testing.LogCapture
import io.github.clemsix6.narrator2.testing.MechanicsSamples
import io.github.clemsix6.narrator2.testing.MemorySamples
import io.github.clemsix6.narrator2.testing.SampleEvents
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.testing.TestServices
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.apache.logging.log4j.Level
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The host of the engine's parts (M4 §4.1, §4.2): the bus filter, the cadence, the snapshot of the situation, the stop. */
class EngineTest {
    private val start = MechanicsSamples.evening
    private val clock = TestClock(Instant.ofEpochMilli(start))
    private val services = TestServices(clock = clock)

    /** A part that records what the engine gives it; [lines] are its situation lines at a time. */
    private class RecordingPart(
        override val listensTo: Set<KClass<out GameEvent>> = emptySet(),
        val lines: (Long) -> List<String> = { emptyList() },
    ) : MechanicPart {
        val events = CopyOnWriteArrayList<GameEvent>()
        val calls = CopyOnWriteArrayList<String>()

        override fun onEvent(envelope: Envelope) {
            events += envelope.event
        }

        override fun situation(now: Long): List<String> = lines(now)

        override fun dropped(ids: Set<Long>) {
            calls += "dropped $ids"
        }

        override fun stopping() {
            calls += "stopping"
        }
    }

    private val ticks = CopyOnWriteArrayList<Pair<MechanicPart, Long>>()
    private val seconds = CopyOnWriteArrayList<Pair<MechanicPart, Long>>()

    private fun Engine.beatAt(now: Long) = beat(now, { ticks += it to now }, { seconds += it to now })

    private fun engine(vararg parts: MechanicPart, store: StateStore = StateStore.inMemory()) = Engine(services, store, parts.toList(), clock)

    private val chat = SampleEvents.all.filterIsInstance<ChatMessage>().first()
    private val died = SampleEvents.all.filterIsInstance<PlayerDied>().first()
    private val broken = SampleEvents.all.filterIsInstance<BlockBroken>().first()

    @Test
    fun `the bus lets through only the facts some part listens to, and each fact reaches only its parts`() {
        val chatter = RecordingPart(setOf(ChatMessage::class))
        val reaper = RecordingPart(setOf(ChatMessage::class, PlayerDied::class))
        val deaf = RecordingPart()
        val engine = engine(chatter, reaper, deaf)
        engine.begin(start) {}

        listOf(chat, died, broken, chat).forEach { services.bus.publish(it) }

        assertEquals(listOf<GameEvent>(chat, chat), chatter.events)
        assertEquals(listOf(chat, died, chat), reaper.events)
        assertEquals(emptyList(), deaf.events)
        val stats = services.bus.stats().single { it.name == Engine.SUBSCRIBER }
        assertEquals(Engine.SUBSCRIBER to 3L, stats.owner to stats.delivered, "the block broken never passed the filter")
    }

    @Test
    fun `without a part that listens, the engine does not subscribe at all`() {
        engine(RecordingPart()).begin(start) {}

        assertTrue(services.bus.stats().none { it.name == Engine.SUBSCRIBER })
    }

    @Test
    fun `every tick reaches every part, and once a second of wall clock the second runs`() {
        val first = RecordingPart()
        val second = RecordingPart()
        val engine = engine(first, second)
        engine.beatAt(start)
        assertTrue(ticks.isEmpty(), "nothing runs before the start")
        engine.begin(start) {}

        for (at in listOf(0L, 400, 999, 1_000, 1_500, 1_999, 2_600, 2_700, 100)) engine.beatAt(start + at)

        assertEquals(18, ticks.size, "9 ticks, 2 parts")
        assertEquals(
            listOf(1_000L, 1_000, 2_600, 2_600, 100, 100).map { start + it },
            seconds.map { it.second },
            "a second after the start, then a second after the last one; a clock set back starts again",
        )
        assertEquals(listOf(first, second, first, second, first, second), seconds.map { it.first })
    }

    @Test
    fun `the situation is the snapshot of the last second, each line cut to its budget`() {
        var mood = "calm"
        val talker = RecordingPart(lines = { now -> listOf("Challenges: $mood at ${now - start}") })
        val verbose = RecordingPart(lines = { listOf("Rules: " + "x".repeat(500)) })
        val broken = RecordingPart(lines = { error("no situation today") })
        val engine = engine(talker, broken, verbose)
        engine.begin(start) {}

        assertEquals("Challenges: calm at 0", engine.situationLines().first(), "built at the start")
        mood = "angry"
        engine.beatAt(start + 500)
        assertEquals("Challenges: calm at 0", engine.situationLines().first(), "never rebuilt between two seconds")
        engine.beatAt(start + 1_000)

        val lines = engine.situationLines()
        assertEquals(2, lines.size, "the part that failed has no line, the others do")
        assertEquals("Challenges: angry at 1000", lines[0])
        assertEquals(MechanicsLimits.SITUATION_LINE, lines[1].length)
        assertTrue(lines[1].endsWith("x…"))
    }

    @Test
    fun `a failing part never stops the others, and is reported once a minute with its count`() {
        val failing = RecordingPart()
        val healthy = RecordingPart()
        val engine = engine(failing, healthy)
        engine.begin(start) {}

        LogCapture().use { logs ->
            for (at in listOf(1_000L, 2_000, 3_000, 61_000, 62_000)) {
                clock.now = Instant.ofEpochMilli(start + at)
                engine.beat(start + at, {}) { part -> if (part === failing) error("boom at $at") else seconds += part to at }
            }
            val errors = logs.at(Level.ERROR).filter { "everySecond failed" in it }
            assertEquals(2, errors.size, logs.text)
            assertTrue(errors[0].contains("(1 times so far)") && errors[0].contains("boom at 1000"), errors[0])
            assertTrue(errors[1].contains("(4 times so far)") && errors[1].contains("boom at 61000"), errors[1])
        }
        assertEquals(5, seconds.size, "the healthy part ran every second")
    }

    @Test
    fun `the stop closes the subscription, stops each part once, and nothing runs after`() {
        val part = RecordingPart(setOf(ChatMessage::class), lines = { listOf("Rules: one") })
        val engine = engine(part)
        engine.begin(start) {}
        services.bus.publish(chat)

        engine.stop()
        engine.stop()
        services.bus.publish(chat)
        engine.beatAt(start + 5_000)

        assertEquals(listOf<GameEvent>(chat), part.events)
        assertEquals(listOf("stopping"), part.calls)
        assertTrue(ticks.isEmpty() && seconds.isEmpty())
        assertEquals(emptyList(), engine.situationLines())
        assertTrue(services.bus.stats().none { it.name == Engine.SUBSCRIBER })
        assertTrue(engine.stopped)
    }

    @Test
    fun `the parts start on the state as the server starts, what ended while it was down already taken out`() {
        val store = StateStore.inMemory(MechanicsSamples.state)
        val engine = engine(RecordingPart(), store = store)
        val seen = mutableListOf<List<Long>>()

        engine.begin(start + 30 * 60_000L) { seen += store.get().challenges.map { it.id } }

        assertEquals(listOf(emptyList<Long>()), seen, "C-50 and C-52 ended before the parts started")
        assertEquals(listOf(60L, 61L), store.snapshot.rules.map { it.id })
        assertTrue(engine.started)
        assertFailsWith<IllegalStateException> { engine.begin(start) {} }
    }

    @Test
    fun `the reconciliation runs once the server runs, and what it names is dropped at the next tick`() = runTest {
        val memory = FakeMemory(clock)
        val services = TestServices(clock = clock, scope = backgroundScope, memory = memory)
        val unreadable = MechanicsState.decode(
            MechanicsState.encode(MechanicsSamples.state).replace("\"challenges\":[", "\"challenges\":[{\"id\":55},"),
        )
        val store = StateStore.inMemory(unreadable)
        val part = RecordingPart()
        val engine = Engine(services, store, listOf(part), clock, reconciles = true)
        val canon = MemorySamples.canon.copy(kind = CanonKind.LAW, mechanic = CanonMechanic.RULE, dueAt = null)
        // C-50, C-52 and C-61 are open; C-60 has no canon row any more; C-55 could not be read back.
        for ((id, mechanic) in listOf(50L to CanonMechanic.CHALLENGE, 52L to CanonMechanic.DUEL, 61L to CanonMechanic.RULE, 55L to CanonMechanic.CHALLENGE)) {
            val kind = if (mechanic == CanonMechanic.RULE) CanonKind.PLAN else CanonKind.CHALLENGE
            memory.store.rows[MechanicsSamples.canon(id)] = canon.copy(ref = MechanicsSamples.canon(id), kind = kind, mechanic = mechanic)
        }
        engine.begin(start) {}

        engine.beatAt(start + 50)
        runCurrent()
        assertEquals(listOf(55L), store.get().unreadable.map { it.id }, "applied at the next tick only")
        engine.beatAt(start + 100)
        engine.beatAt(start + 150)
        runCurrent()

        assertEquals(listOf(50L, 52L), store.get().challenges.map { it.id })
        assertEquals(listOf(61L), store.get().rules.map { it.id })
        assertEquals(emptyList(), store.get().unreadable)
        assertEquals(listOf("dropped [60]"), part.calls)
        assertEquals(listOf("resolveCanon C-55 by engine"), memory.store.writes)
    }

    @Test
    fun `a store in memory never reconciles, as the canon it would compare is the server's`() = runTest {
        val memory = FakeMemory(clock)
        val services = TestServices(clock = clock, scope = backgroundScope, memory = memory)
        val engine = Engine(services, StateStore.inMemory(), listOf(RecordingPart()), clock)
        memory.store.rows[MechanicsSamples.canon(70)] = MemorySamples.canon.copy(
            ref = MechanicsSamples.canon(70), kind = CanonKind.CHALLENGE, mechanic = CanonMechanic.CHALLENGE, dueAt = null,
        )
        engine.begin(start) {}

        engine.beatAt(start + 50)
        runCurrent()
        engine.beatAt(start + 100)

        assertTrue(memory.store.writes.isEmpty())
        assertFalse(services.traces.decisions.any { it.decision == Reconcile.RECONCILED })
    }
}
