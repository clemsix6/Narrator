package io.github.clemsix6.narrator2.event

import io.github.clemsix6.narrator2.core.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelIterator
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ReceiveChannel
import java.time.Clock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** A registration on the [EventBus]. Closing it unsubscribes; closing it again does nothing. */
interface Subscription : AutoCloseable {
    /** What the subscriber is, for logs and stats, e.g. `journal`. */
    val name: String

    /** Who registered it, e.g. `runtime`, a Forge module or a test: see [EventBus.unsubscribeOwner]. */
    val owner: String
}

/** A buffered subscription: the subscriber drains [events] at its own pace. */
interface AsyncSubscription : Subscription {
    /**
     * Envelopes in publication order. Closed when the subscription or the bus is closed: the consumer
     * drains what is left, then stops. `queued` in [EventBus.stats] counts what `receive`,
     * `tryReceive`, `receiveCatching` and iteration took; select clauses (`onReceive`) are not counted.
     */
    val events: ReceiveChannel<Envelope>
}

/**
 * Counters of one subscriber since it subscribed.
 *
 * @property delivered envelopes that passed the filter and reached the handler or the queue.
 * @property dropped envelopes lost because the queue was full (async only).
 * @property failures exceptions thrown by the filter or the handler (sync), or by the filter (async).
 * @property queued envelopes waiting in the queue (async only).
 */
data class SubscriberStats(
    val name: String,
    val owner: String,
    val delivered: Long,
    val dropped: Long,
    val failures: Long,
    val queued: Int,
)

/**
 * Delivers game facts to their subscribers: the Journal, player states, the engine's guards, the
 * Director's salience, and later mechanics and Forge modules. The bus only carries facts; it cannot
 * cancel or change what happens in the game.
 *
 * - Ids come from one sequence, seeded by the Journal ([firstEventId]) so they keep growing across restarts.
 * - Every subscriber sees envelopes in id order, whichever thread publishes them.
 * - Filters run on the publishing thread and must be trivial (`is`, a type comparison).
 * - A synchronous handler runs inside [publish], on the publishing thread (the server thread in
 *   practice): it must take well under 0.1 ms and never wait for another thread. Its exceptions are
 *   caught, counted and logged at most once a minute; they never stop the other subscribers.
 * - An asynchronous subscriber gets a bounded queue. When it is full, the envelope is lost for that
 *   subscriber only, counted, and a warning is logged at most once a minute.
 *
 * @param gameTime overworld game time, read when stamping an envelope.
 */
class EventBus(
    private val clock: Clock,
    private val gameTime: () -> Long,
    firstEventId: Long,
) : AutoCloseable {
    private val log = Log.bus
    private val lock = Any()
    private var nextId = firstEventId // guarded by lock
    private val subscribers = CopyOnWriteArrayList<Subscriber>()

    @Volatile
    private var closed = false
    private val closedDropCount = AtomicLong()

    /** Envelopes refused because the bus was closed. */
    val closedDrops: Long get() = closedDropCount.get()

    /**
     * Stamps [event] with the next id, the time and the game time, and delivers it. Never blocks on an
     * asynchronous subscriber. Returns null, without delivering, once the bus is closed. Any thread.
     */
    fun publish(event: GameEvent): Envelope? = synchronized(lock) {
        if (closed) {
            closedDropCount.incrementAndGet()
            return null
        }
        val envelope = Envelope(nextId++, clock.millis(), gameTime(), event)
        subscribers.forEach { it.deliver(envelope) }
        envelope
    }

    /** Delivers the events that pass [filter] to [handler], inline on the publishing thread. */
    fun subscribeSync(
        name: String,
        owner: String,
        filter: (GameEvent) -> Boolean = { true },
        handler: (Envelope) -> Unit,
    ): Subscription = add(SyncSubscriber(name, owner, filter, handler))

    /** Queues the events that pass [filter], up to [capacity] waiting, for the subscriber to drain. */
    fun subscribeAsync(
        name: String,
        owner: String,
        capacity: Int,
        filter: (GameEvent) -> Boolean = { true },
    ): AsyncSubscription {
        require(capacity > 0) { "capacity must be positive" }
        return add(AsyncSubscriber(name, owner, capacity, filter))
    }

    /** Removes every subscription of [owner]; their queues are closed. */
    fun unsubscribeOwner(owner: String) {
        subscribers.filter { it.owner == owner }.forEach { it.close() }
    }

    fun stats(): List<SubscriberStats> = subscribers.map { it.stats() }

    /** Refuses any further publication and closes every queue. Subscribers stay listed in [stats]. */
    override fun close() {
        synchronized(lock) { closed = true }
        subscribers.forEach { it.closeQueue() }
    }

    private fun <S : Subscriber> add(subscriber: S): S {
        synchronized(lock) {
            check(!closed) { "the event bus is closed" }
            subscribers += subscriber
        }
        return subscriber
    }

    private abstract inner class Subscriber(
        final override val name: String,
        final override val owner: String,
        private val filter: (GameEvent) -> Boolean,
    ) : Subscription {
        protected val delivered = AtomicLong()
        protected val dropped = AtomicLong()
        protected val failures = AtomicLong()
        private val lastReportAt = AtomicLong(Long.MIN_VALUE)

        abstract fun deliver(envelope: Envelope)

        abstract fun stats(): SubscriberStats

        open fun closeQueue() = Unit

        override fun close() {
            subscribers.remove(this)
            closeQueue()
        }

        protected fun accepts(envelope: Envelope): Boolean = try {
            filter(envelope.event)
        } catch (e: Throwable) {
            failed(envelope, "filter", e)
            false
        }

        protected fun failed(envelope: Envelope, what: String, error: Throwable) {
            failures.incrementAndGet()
            if (shouldReport()) {
                log.error("$name ($owner): $what failed on ${envelope.event.type} #${envelope.id} (${failures.get()} failures so far)", error)
            }
        }

        /** True at most once a minute per subscriber. */
        protected fun shouldReport(): Boolean {
            val now = clock.millis()
            val last = lastReportAt.get()
            return (last == Long.MIN_VALUE || now - last >= REPORT_INTERVAL_MS) && lastReportAt.compareAndSet(last, now)
        }
    }

    private inner class SyncSubscriber(
        name: String,
        owner: String,
        filter: (GameEvent) -> Boolean,
        private val handler: (Envelope) -> Unit,
    ) : Subscriber(name, owner, filter) {
        override fun deliver(envelope: Envelope) {
            if (!accepts(envelope)) return
            delivered.incrementAndGet()
            try {
                handler(envelope)
            } catch (e: Throwable) {
                failed(envelope, "handler", e)
            }
        }

        override fun stats() = SubscriberStats(name, owner, delivered.get(), dropped.get(), failures.get(), 0)
    }

    private inner class AsyncSubscriber(
        name: String,
        owner: String,
        private val capacity: Int,
        filter: (GameEvent) -> Boolean,
    ) : Subscriber(name, owner, filter), AsyncSubscription {
        private val channel = Channel<Envelope>(capacity)
        private val queued = AtomicInteger()

        override val events: ReceiveChannel<Envelope> = CountingChannel(channel, queued)

        override fun deliver(envelope: Envelope) {
            if (!accepts(envelope)) return
            if (channel.trySend(envelope).isSuccess) {
                queued.incrementAndGet()
                delivered.incrementAndGet()
            } else {
                dropped.incrementAndGet()
                if (shouldReport()) {
                    log.warn("$name ($owner): queue full ($capacity), ${envelope.event.type} #${envelope.id} lost (${dropped.get()} lost so far)")
                }
            }
        }

        override fun stats() = SubscriberStats(name, owner, delivered.get(), dropped.get(), failures.get(), queued.get())

        override fun closeQueue() {
            channel.close()
        }
    }

    /** The subscriber's side of a queue, which keeps [queued] up to date as envelopes are taken. */
    private class CountingChannel(
        private val channel: Channel<Envelope>,
        private val queued: AtomicInteger,
    ) : ReceiveChannel<Envelope> by channel {
        override fun tryReceive(): ChannelResult<Envelope> = channel.tryReceive().also { if (it.isSuccess) queued.decrementAndGet() }

        override suspend fun receive(): Envelope = channel.receive().also { queued.decrementAndGet() }

        override suspend fun receiveCatching(): ChannelResult<Envelope> =
            channel.receiveCatching().also { if (it.isSuccess) queued.decrementAndGet() }

        override fun iterator(): ChannelIterator<Envelope> {
            val inner = channel.iterator()
            return object : ChannelIterator<Envelope> {
                override suspend fun hasNext(): Boolean = inner.hasNext()

                override fun next(): Envelope = inner.next().also { queued.decrementAndGet() }
            }
        }
    }

    private companion object {
        val REPORT_INTERVAL_MS = TimeUnit.MINUTES.toMillis(1)
    }
}
