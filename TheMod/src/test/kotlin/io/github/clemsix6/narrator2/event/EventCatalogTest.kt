package io.github.clemsix6.narrator2.event

import io.github.clemsix6.narrator2.journal.Journal
import io.github.clemsix6.narrator2.testing.SampleEvents
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventCatalogTest {
    private val eventClasses = GameEvent::class.sealedSubclasses
    private val snakeCase = Regex("[a-z][a-z0-9_]*")

    @Test
    fun `every event class has a catalog entry and a sample`() {
        assertTrue(eventClasses.size >= 40, "sealed subclasses found by reflection: ${eventClasses.size}")
        val catalogued = EventCatalog.all.map { it.eventClass }.toSet()
        val sampled = SampleEvents.all.map { it::class }.toSet()
        assertEquals(emptyList(), eventClasses.filterNot { it in catalogued }.map { it.simpleName }, "classes missing from EventCatalog")
        assertEquals(emptyList(), eventClasses.filterNot { it in sampled }.map { it.simpleName }, "classes missing from SampleEvents")
    }

    @Test
    fun `every event's type is catalogued under its own class`() {
        SampleEvents.all.forEach { event ->
            val entry = EventCatalog.of(event.type)
            assertEquals(event::class, entry?.eventClass, "type ${event.type} of ${event::class.simpleName}")
            assertEquals(entry, EventCatalog.of(event))
        }
    }

    @Test
    fun `every catalogued type can be produced`() {
        val produced = SampleEvents.all.map { it.type }.toSet()
        assertEquals(emptyList(), EventCatalog.all.map { it.name }.filterNot { it in produced }, "types no sample produces")
    }

    @Test
    fun `entries are unique, snake_case and documented`() {
        val names = EventCatalog.all.map { it.name }
        assertEquals(names.distinct(), names, "duplicate types")
        EventCatalog.all.forEach { entry ->
            assertTrue(snakeCase.matches(entry.name), entry.name)
            assertTrue(entry.summary.isNotBlank() && entry.summary.endsWith("."), "summary of ${entry.name}")
            assertTrue(entry.detail.keys.all { snakeCase.matches(it) }, "detail keys of ${entry.name}")
            assertTrue(entry.detail.values.all { it.isNotBlank() }, "detail docs of ${entry.name}")
            if (entry.table != EventTable.EVENTS) {
                assertTrue(entry.subject == null && entry.amount == null && entry.other == null && entry.detail.isEmpty(), entry.name)
            }
        }
    }

    @Test
    fun `private messages go to their own table, hidden from the model`() {
        val whisper = SampleEvents.all.filterIsInstance<ChatMessage>().single { it.channel == ChatChannel.WHISPER }
        val public = SampleEvents.all.filterIsInstance<ChatMessage>().first { it.channel == ChatChannel.PUBLIC }

        assertEquals(EventTable.PRIVATE_MESSAGES, EventCatalog.of(whisper).table)
        assertEquals(EventTable.CHAT, EventCatalog.of(public).table)
        assertEquals(setOf("private_messages"), Journal.HIDDEN_FROM_MODEL)
        assertEquals(listOf("whisper"), EventCatalog.types(EventTable.PRIVATE_MESSAGES).map { it.name })
        assertEquals(setOf("msg", "tell", "w", "teammsg", "tm"), CommandIssued.PRIVATE_MESSAGE_COMMANDS)
    }

    @Test
    fun `only the facts of the events table use its generic columns`() {
        assertEquals(
            listOf("chat", "narrator_say"),
            EventCatalog.types(EventTable.CHAT).map { it.name },
        )
        assertEquals(listOf("position"), EventCatalog.types(EventTable.POSITIONS).map { it.name })
        assertEquals(listOf("stats_snapshot"), EventCatalog.types(EventTable.STAT_TOTALS).map { it.name })
        assertEquals(EventCatalog.all.size - 5, EventCatalog.types(EventTable.EVENTS).size)
    }
}
