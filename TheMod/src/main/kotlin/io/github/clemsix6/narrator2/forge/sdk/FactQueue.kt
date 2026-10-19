package io.github.clemsix6.narrator2.forge.sdk

import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.forge.api.Events
import io.github.clemsix6.narrator2.forge.api.ForgeRefusal
import io.github.clemsix6.narrator2.forge.api.GameFact
import io.github.clemsix6.narrator2.forge.api.Subscription
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

/**
 * One deployment's facts (M5 §4.3, §6.5): its subscriptions ([Events]), and the facts [Facts] translated for it, queued
 * when they happen ([offer], any thread) and handed to the engine at the next tick ([drain], server thread): in order,
 * one [Delivery] per fact and subscription, each to run in a measured call of its own.
 *
 * Limits: `ForgeLimits.SUBSCRIPTIONS` active subscriptions ([ForgeRefusal] beyond); `ForgeLimits.FACTS_PER_TICK`
 * facts waiting and deliveries a tick, `ForgeLimits.FACTS_PER_MINUTE` deliveries a minute; the others are dropped and
 * counted ([takeDropped]). A fact is queued only when a subscription wants its class as it happens.
 *
 * **The module's handlers are opaque** (design review, finding 8): kept as they are, never a key, never logged, never
 * asked for `equals`, `hashCode` or `toString`, and called only by [Delivery.deliver], which the engine runs inside a
 * call armed by the host. While the module stops ([stopping]) nothing more is subscribed; once it ended ([close]),
 * every method of [Events] throws [ForgeRefusal] and nothing is queued.
 */
class FactQueue : Events {
    private val subscriptions = ArrayList<Sub>()
    private var lastId = 0

    /** The classes some active subscription wants: read by [offer] on any thread, rebuilt on the server thread. */
    @Volatile
    private var wanted: Set<Class<*>> = emptySet()

    private val pending = ConcurrentLinkedQueue<GameFact>()
    private val waiting = AtomicInteger()
    private val dropped = AtomicInteger()
    private var minuteStart = 0L
    private var minuteCount = 0

    @Volatile
    private var phase = Phase.RUNNING

    override fun <F : GameFact> on(type: Class<F>?, handler: Consumer<F>?): Subscription {
        when (phase) {
            Phase.RUNNING -> Unit
            Phase.STOPPING -> throw ForgeRefusal("Events.on: no subscription while the module stops")
            Phase.ENDED -> throw ForgeRefusal(ENDED)
        }
        if (type == null || type !in Facts.TYPES) {
            throw ForgeRefusal("Events.on: the type must be one of GameFact's records, such as GameFact.Chat.class (got ${type?.name})")
        }
        if (handler == null) throw ForgeRefusal("Events.on: the handler must not be null")
        if (subscriptions.size >= ForgeLimits.SUBSCRIPTIONS) {
            throw ForgeRefusal("Events.on: ${ForgeLimits.SUBSCRIPTIONS} subscriptions are active already (Limits.MAX_SUBSCRIPTIONS); cancel one first")
        }
        @Suppress("UNCHECKED_CAST") // unchecked: no call on the handler; a fact goes only to the subscriptions of its own class
        val sub = Sub(++lastId, type, handler as Consumer<GameFact>)
        subscriptions += sub
        rebuild()
        return sub
    }

    /** Whether some active subscription wants the facts of [type]: the engine may skip building them otherwise. Any thread. */
    fun wants(type: Class<out GameFact>): Boolean = type in wanted

    /**
     * Queues [fact], which [Facts] translated, when some subscription wants its class; past `ForgeLimits.FACTS_PER_TICK`
     * facts waiting, it is dropped and counted. Any thread (the bus calls inline).
     */
    fun offer(fact: GameFact) {
        if (phase == Phase.ENDED || fact.javaClass !in wanted) return
        if (waiting.incrementAndGet() > ForgeLimits.FACTS_PER_TICK) {
            waiting.decrementAndGet()
            dropped.incrementAndGet()
            return
        }
        pending += fact
    }

    /**
     * The deliveries of this tick, at [now] (epoch milliseconds): each fact waiting, in order, to each active
     * subscription of its class, in the order they were made. Past `ForgeLimits.FACTS_PER_TICK` deliveries, or
     * `ForgeLimits.FACTS_PER_MINUTE` within the minute, the rest are dropped and counted. Server thread, once a tick.
     */
    fun drain(now: Long): List<Delivery> {
        if (minuteCount == 0 || now - minuteStart >= MINUTE_MS || now < minuteStart) {
            minuteStart = now
            minuteCount = 0
        }
        val deliveries = ArrayList<Delivery>()
        while (true) {
            val fact = pending.poll() ?: break
            waiting.decrementAndGet()
            for (sub in subscriptions) {
                if (!sub.active || sub.type !== fact.javaClass) continue
                if (deliveries.size >= ForgeLimits.FACTS_PER_TICK || minuteCount >= ForgeLimits.FACTS_PER_MINUTE) {
                    dropped.incrementAndGet()
                } else {
                    deliveries += Delivery(sub, fact)
                    minuteCount++
                }
            }
        }
        return deliveries
    }

    /** The facts dropped since the last call (past the limits): the engine tells them once a minute at most. */
    fun takeDropped(): Int = dropped.getAndSet(0)

    /** The active subscriptions. */
    val active: Int get() = subscriptions.size

    /** The module's `stop` is about to run: no new subscription (the facts waiting are no longer delivered). Server thread. */
    fun stopping() {
        if (phase == Phase.RUNNING) phase = Phase.STOPPING
    }

    /** The deployment ended: every subscription cancelled, the queue emptied, every method of [Events] refused. Server thread. */
    fun close() {
        phase = Phase.ENDED
        subscriptions.forEach { it.active = false }
        subscriptions.clear()
        wanted = emptySet()
        pending.clear()
        waiting.set(0)
    }

    private fun rebuild() {
        wanted = subscriptions.mapTo(HashSet()) { it.type }
    }

    private enum class Phase { RUNNING, STOPPING, ENDED }

    /** A subscription: its number in the deployment, the class of its facts and the module's handler (opaque). */
    internal inner class Sub(val id: Int, val type: Class<out GameFact>, val handler: Consumer<GameFact>) : Subscription {
        var active = true

        override fun cancel() {
            if (!active) return
            active = false
            subscriptions.removeIf { it === this }
            rebuild()
        }

        override fun active(): Boolean = active
    }

    /**
     * One fact for one subscription. [deliver] calls the module's handler: only inside a call armed by the host.
     *
     * @property label for the logs: `subscription 2 (Chat)`.
     */
    class Delivery internal constructor(private val sub: FactQueue.Sub, val fact: GameFact) {
        val label: String get() = "subscription ${sub.id} (${fact.javaClass.simpleName})"

        /** False once its subscription was cancelled (by an earlier delivery of the same tick, say): nothing to run. */
        val live: Boolean get() = sub.active

        /** Calls the module's handler with [fact], unless its subscription was cancelled meanwhile. Inside an armed call only. */
        fun deliver() {
            if (sub.active) sub.handler.accept(fact)
        }
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val ENDED = "the deployment has ended: its SDK objects no longer work"
    }
}
