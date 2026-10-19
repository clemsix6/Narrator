package io.github.clemsix6.narrator2.forge.sdk

import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.forge.api.ForgeRefusal
import io.github.clemsix6.narrator2.forge.api.GameFact
import io.github.clemsix6.narrator2.forge.api.Place
import org.junit.jupiter.api.Test
import java.util.function.Consumer
import kotlin.concurrent.thread
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A deployment's facts (M5 §4.3, §6.5; task M5-06): subscriptions and their limit, the queue and its limits, the order
 * of the deliveries, cancellations, the module's stop and end, and the module's handlers kept opaque (design review,
 * finding 8).
 */
class FactQueueTest {
    private val at = Place("overworld", 0.5, 64.0, 0.5)
    private fun chat(text: String) = GameFact.Chat("Bartok", at, text)
    private fun broke(block: String) = GameFact.BlockBroken("Mimosa", at, block, false)

    /** Runs each delivery as the engine does, inside a call it "arms": a handler that runs elsewhere fails the test. */
    private var armed = false

    private fun run(deliveries: List<FactQueue.Delivery>) = deliveries.forEach { delivery ->
        armed = true
        try {
            delivery.deliver()
        } finally {
            armed = false
        }
    }

    /** A handler that records what it gets, inside an armed call only, and that no host may ever ask for equals, hashCode or toString. */
    private inner class Hostile<F : GameFact>(val name: String, val got: MutableList<String>, val then: (F) -> Unit = {}) : Consumer<F> {
        override fun accept(fact: F) {
            check(armed) { "a handler ran outside an armed call" }
            got += "$name:${(fact as? GameFact.Chat)?.text ?: (fact as GameFact.BlockBroken).block}"
            then(fact)
        }

        override fun equals(other: Any?): Boolean = error("the host called equals on a module's object")

        override fun hashCode(): Int = error("the host called hashCode on a module's object")

        override fun toString(): String = error("the host called toString on a module's object")
    }

    @Test
    fun `facts come the tick after they happened, in order, to each subscription of their class, in the order they were made`() {
        val queue = FactQueue()
        val got = mutableListOf<String>()
        queue.on(GameFact.Chat::class.java, Hostile("a", got))
        queue.on(GameFact.BlockBroken::class.java, Hostile("b", got))
        queue.on(GameFact.Chat::class.java, Hostile("c", got))
        assertTrue(queue.wants(GameFact.Chat::class.java) && !queue.wants(GameFact.Joined::class.java))

        queue.offer(chat("un"))
        queue.offer(GameFact.Joined("Bartok", at))
        queue.offer(broke("stone"))
        queue.offer(chat("deux"))
        assertEquals(emptyList(), got, "nothing runs before the tick")
        val deliveries = queue.drain(0)
        assertEquals(
            listOf("subscription 1 (Chat)", "subscription 3 (Chat)", "subscription 2 (BlockBroken)", "subscription 1 (Chat)", "subscription 3 (Chat)"),
            deliveries.map { it.label },
        )
        run(deliveries)
        assertEquals(listOf("a:un", "c:un", "b:stone", "a:deux", "c:deux"), got)
        assertEquals(emptyList(), queue.drain(0), "each fact once")
        assertEquals(0, queue.takeDropped(), "a fact nobody wants is not queued, nor dropped")
    }

    @Test
    fun `a cancelled subscription gets nothing more, not even what is queued, and frees its place`() {
        val queue = FactQueue()
        val got = mutableListOf<String>()
        lateinit var second: io.github.clemsix6.narrator2.forge.api.Subscription
        queue.on(GameFact.Chat::class.java, Hostile<GameFact.Chat>("a", got) { second.cancel() })
        second = queue.on(GameFact.Chat::class.java, Hostile("b", got))
        queue.offer(chat("un"))
        queue.offer(chat("deux"))
        val deliveries = queue.drain(0)
        assertEquals(4, deliveries.size)
        run(deliveries)
        assertEquals(listOf("a:un", "a:deux"), got, "cancelled by the first delivery, the second subscription gets nothing")
        assertFalse(deliveries[1].live)
        assertFalse(second.active())
        second.cancel()
        assertEquals(1, queue.active, "cancelling twice changes nothing")

        val slept = (1 until ForgeLimits.SUBSCRIPTIONS).map { queue.on(GameFact.Slept::class.java, Hostile("s", got)) }
        val full = assertFailsWith<ForgeRefusal> { queue.on(GameFact.Left::class.java, Hostile("l", got)) }
        assertContains(full.message.orEmpty(), "16 subscriptions are active already")
        slept.first().cancel()
        assertTrue(queue.on(GameFact.Left::class.java, Hostile("l", got)).active(), "a cancelled subscription's place is free again")
    }

    @Test
    fun `only the twenty records may be subscribed, with a handler`() {
        val queue = FactQueue()
        @Suppress("UNCHECKED_CAST")
        fun raw(type: Class<*>) = queue.on(type as Class<GameFact>, Consumer<GameFact> { })
        assertContains(assertFailsWith<ForgeRefusal> { raw(GameFact::class.java) }.message.orEmpty(), "one of GameFact's records")
        assertFailsWith<ForgeRefusal> { raw(String::class.java) }
        assertFailsWith<ForgeRefusal> { queue.on<GameFact.Chat>(null, Consumer { }) }
        assertContains(assertFailsWith<ForgeRefusal> { queue.on(GameFact.Chat::class.java, null) }.message.orEmpty(), "handler")
        for (type in Facts.TYPES) raw(type).cancel()
    }

    @Test
    fun `past the limits of a tick and a minute, facts are dropped and counted`() {
        val queue = FactQueue()
        val got = mutableListOf<String>()
        queue.on(GameFact.Chat::class.java, Hostile("a", got))
        repeat(ForgeLimits.FACTS_PER_TICK + 10) { queue.offer(chat("$it")) }
        assertEquals(ForgeLimits.FACTS_PER_TICK, queue.drain(0).size)
        assertEquals(10, queue.takeDropped(), "the queue holds a tick's worth")
        assertEquals(0, queue.takeDropped())

        queue.on(GameFact.Chat::class.java, Hostile("b", got))
        repeat(ForgeLimits.FACTS_PER_TICK / 2 + 1) { queue.offer(chat("$it")) }
        assertEquals(ForgeLimits.FACTS_PER_TICK, queue.drain(50).size, "deliveries a tick: two subscriptions share them")
        assertEquals(2, queue.takeDropped())

        var delivered = 2 * ForgeLimits.FACTS_PER_TICK
        var now = 100L
        while (delivered < ForgeLimits.FACTS_PER_MINUTE) {
            repeat(ForgeLimits.FACTS_PER_TICK / 2) { queue.offer(chat("$it")) }
            delivered += queue.drain(now).size
            now += 50
        }
        assertEquals(ForgeLimits.FACTS_PER_MINUTE, delivered)
        queue.takeDropped()
        queue.offer(chat("trop"))
        assertEquals(0, queue.drain(now).size, "${ForgeLimits.FACTS_PER_MINUTE} a minute")
        assertEquals(2, queue.takeDropped())
        queue.offer(chat("enfin"))
        assertEquals(2, queue.drain(60_000).size, "a new minute")
    }

    @Test
    fun `facts may be offered from any thread`() {
        val queue = FactQueue()
        queue.on(GameFact.Chat::class.java, Consumer { })
        val threads = (1..4).map { n -> thread { repeat(50) { queue.offer(chat("$n-$it")) } } }
        threads.forEach { it.join() }
        assertEquals(200, queue.drain(0).size)
        assertEquals(0, queue.takeDropped())
    }

    @Test
    fun `while the module stops nothing is subscribed, and once it ended every method refuses`() {
        val queue = FactQueue()
        val got = mutableListOf<String>()
        val sub = queue.on(GameFact.Chat::class.java, Hostile("a", got))
        queue.offer(chat("un"))
        queue.stopping()
        assertContains(assertFailsWith<ForgeRefusal> { queue.on(GameFact.Chat::class.java, Hostile("b", got)) }.message.orEmpty(), "while the module stops")
        queue.close()
        assertFalse(sub.active())
        assertFalse(queue.wants(GameFact.Chat::class.java))
        queue.offer(chat("deux"))
        assertEquals(emptyList(), queue.drain(0), "nothing is delivered after the end")
        assertContains(assertFailsWith<ForgeRefusal> { queue.on(GameFact.Chat::class.java, Hostile("c", got)) }.message.orEmpty(), "has ended")
        sub.cancel()
        assertEquals(emptyList(), got)
    }
}
