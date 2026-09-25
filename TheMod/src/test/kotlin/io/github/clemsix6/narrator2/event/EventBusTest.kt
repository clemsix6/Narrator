package io.github.clemsix6.narrator2.event

import io.github.clemsix6.narrator2.testing.LogCapture
import io.github.clemsix6.narrator2.testing.SampleEvents
import io.github.clemsix6.narrator2.testing.SampleEvents.here
import io.github.clemsix6.narrator2.testing.SampleEvents.steve
import io.github.clemsix6.narrator2.testing.TestClock
import kotlinx.coroutines.channels.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventBusTest {
    private val clock = TestClock()
    private val gameTime = AtomicLong(24_000)
    private val bus = EventBus(clock, gameTime::get, firstEventId = 1_000)

    private fun chat(text: String) = ChatMessage(steve, ChatChannel.PUBLIC, text, emptyList())

    private fun died() = SampleEvents.all.filterIsInstance<PlayerDied>().first()

    @Test
    fun `stamps each event with increasing ids from the seed, the time and the game time`() {
        val first = bus.publish(chat("one"))!!
        clock.advance(Duration.ofSeconds(2))
        gameTime.set(24_040)
        val second = bus.publish(chat("two"))!!

        assertEquals(Envelope(1_000, clock.now.minusSeconds(2).toEpochMilli(), 24_000, chat("one")), first)
        assertEquals(Envelope(1_001, clock.now.toEpochMilli(), 24_040, chat("two")), second)
    }

    @Test
    fun `every subscriber gets the events in publication order`() {
        val sync = CopyOnWriteArrayList<Long>()
        bus.subscribeSync("sync", "test") { sync += it.id }
        val async = bus.subscribeAsync("async", "test", capacity = 100)

        val published = SampleEvents.all.map { bus.publish(it)!!.id }

        assertEquals(published, sync)
        assertEquals(published, generateSequence { async.events.tryReceive().getOrNull() }.map { it.id }.toList())
        assertEquals((1_000L until 1_000L + SampleEvents.all.size).toList(), published)
    }

    @Test
    fun `ids stay in delivery order when several threads publish`() {
        val seen = CopyOnWriteArrayList<Long>()
        bus.subscribeSync("sync", "test") { seen += it.id }
        val start = CountDownLatch(1)
        val threads = List(4) { thread { start.await(); repeat(500) { bus.publish(chat("x")) } } }
        start.countDown()
        threads.forEach { it.join() }

        assertEquals(2_000, seen.size)
        assertEquals(seen.sorted(), seen.toList(), "each subscriber sees increasing ids")
    }

    @Test
    fun `a filter keeps only what the subscriber asked for`() {
        val deaths = CopyOnWriteArrayList<GameEvent>()
        bus.subscribeSync("deaths", "test", filter = { it is PlayerDied }) { deaths += it.event }
        val chats = bus.subscribeAsync("chat", "test", capacity = 10, filter = { it is ChatMessage })

        bus.publish(chat("hello"))
        bus.publish(died())
        bus.publish(BlockPlaced(steve, here, "minecraft:stone", "minecraft:stone", PlaceKind.BLOCK))

        assertEquals(listOf<GameEvent>(died()), deaths)
        assertEquals(listOf<GameEvent>(chat("hello")), generateSequence { chats.events.tryReceive().getOrNull() }.map { it.event }.toList())
        assertEquals(1, bus.stats().single { it.name == "deaths" }.delivered)
    }

    @Test
    fun `a failing synchronous subscriber is counted and logged once a minute, and stops nobody`() {
        val after = CopyOnWriteArrayList<Long>()
        bus.subscribeSync("broken", "test") { error("boom") }
        bus.subscribeSync("after", "test") { after += it.id }

        val logs = LogCapture()
        val envelopes = logs.use {
            val first = listOf(bus.publish(chat("a")), bus.publish(chat("b")))
            clock.advance(Duration.ofMinutes(1))
            first + bus.publish(chat("c"))
        }

        assertEquals(envelopes.map { it!!.id }, after, "the next subscriber still gets every event")
        assertEquals(3, bus.stats().single { it.name == "broken" }.failures)
        val reports = logs.messages.filter { "broken (test): handler failed" in it }
        assertEquals(2, reports.size, "one report per minute: ${logs.messages}")
        assertContains(reports.first(), "chat #1000")
        assertContains(logs.text, "boom")
    }

    @Test
    fun `a full queue loses events for its subscriber only`() {
        val small = bus.subscribeAsync("small", "test", capacity = 2)
        val big = bus.subscribeAsync("big", "test", capacity = 10)

        val logs = LogCapture()
        logs.use { repeat(5) { bus.publish(chat("m$it")) } }

        val smallStats = bus.stats().single { it.name == "small" }
        assertEquals(SubscriberStats("small", "test", delivered = 2, dropped = 3, failures = 0, queued = 2), smallStats)
        assertEquals(SubscriberStats("big", "test", delivered = 5, dropped = 0, failures = 0, queued = 5), bus.stats().single { it.name == "big" })
        assertEquals(1, logs.messages.count { "small (test): queue full (2)" in it }, "warned once: ${logs.messages}")
        assertEquals(listOf("m0", "m1"), generateSequence { small.events.tryReceive().getOrNull() }.map { (it.event as ChatMessage).text }.toList())
        assertEquals(5, generateSequence { big.events.tryReceive().getOrNull() }.count())
        assertEquals(0, bus.stats().single { it.name == "big" }.queued, "queued goes down as the subscriber takes")
    }

    @Test
    fun `an asynchronous subscriber drains its queue with a coroutine`() = runTest {
        val subscription = bus.subscribeAsync("async", "test", capacity = 10)
        val received = mutableListOf<String>()
        val consumer = launch { for (envelope in subscription.events) received += (envelope.event as ChatMessage).text }

        listOf("a", "b", "c").forEach { bus.publish(chat(it)) }
        subscription.close()
        consumer.join()

        assertEquals(listOf("a", "b", "c"), received, "a closed queue is drained, then the loop ends")
        assertEquals(0, bus.stats().size, "a closed subscription is removed")
    }

    @Test
    fun `unsubscribeOwner removes every subscription of that owner`() = runTest {
        val mine = CopyOnWriteArrayList<Long>()
        val theirs = CopyOnWriteArrayList<Long>()
        bus.subscribeSync("a", "module") { mine += it.id }
        val queue = bus.subscribeAsync("b", "module", capacity = 10)
        bus.subscribeSync("c", "other") { theirs += it.id }

        bus.publish(chat("before"))
        bus.unsubscribeOwner("module")
        bus.publish(chat("after"))

        assertEquals(1, mine.size)
        assertEquals(2, theirs.size)
        assertEquals(1, queue.events.toList().size, "the queue keeps what it had, then ends")
        assertEquals(listOf("c"), bus.stats().map { it.name })
    }

    @Test
    fun `nothing is delivered once the bus is closed`() = runTest {
        val sync = CopyOnWriteArrayList<Long>()
        bus.subscribeSync("sync", "test") { sync += it.id }
        val queue = bus.subscribeAsync("async", "test", capacity = 10)
        bus.publish(chat("before"))

        bus.close()

        assertNull(bus.publish(chat("after")))
        assertNull(bus.publish(chat("again")))
        assertEquals(2, bus.closedDrops)
        assertEquals(1, sync.size)
        assertEquals(1, queue.events.toList().size, "queues are closed after what they hold")
        assertFailsWith<IllegalStateException> { bus.subscribeSync("late", "test") {} }
        assertTrue(bus.stats().isNotEmpty(), "stats stay readable")
    }
}
