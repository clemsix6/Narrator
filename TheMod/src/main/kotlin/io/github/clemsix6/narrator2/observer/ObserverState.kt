package io.github.clemsix6.narrator2.observer

import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.config.ObserverConfig
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.event.Envelope
import io.github.clemsix6.narrator2.event.EventBus
import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.guard.Guards
import io.github.clemsix6.narrator2.runtime.NarratorServices
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Something that holds facts not published yet (a coalescer, an aggregator). Flushed when the server
 * stops, before the bus closes, so that what it holds reaches the Journal.
 */
fun interface Flushable {
    /** Publishes what is pending. Server thread. */
    fun flush()
}

/**
 * The Observer's state for one server: where hooks publish, what they may read, and what must be
 * flushed at the stop. Hooks are registered once per JVM (`FabricObserver.register`,
 * `ObserverHooks.register`) and reach the running server's state through [current]; each module keeps
 * its own per-server parts, created by its `attach(state)`.
 */
class ObserverState(val services: NarratorServices) {
    private val flushables = CopyOnWriteArrayList<Flushable>()

    @PublishedApi
    internal val hookTime = AtomicLong()

    private val lastReports = ConcurrentHashMap<String, Long>()

    val bus: EventBus get() = services.bus
    val players: PlayerStates get() = services.players
    val guards: Guards get() = services.guards

    /** The `observer` settings in force (read at each use: `/narrator reload` may change them). */
    val config: ObserverConfig get() = services.settings.get().config.observer

    /** Time spent in the Observer's hooks since the server started, in nanoseconds, for the Journal's health report. */
    val hookNanos: Long get() = hookTime.get()

    /** Publishes [event] on the bus; null once the bus is closed. */
    fun publish(event: GameEvent): Envelope? = services.bus.publish(event)

    /** [flushable] is flushed when the server stops, in registration order. */
    fun onStop(flushable: Flushable) {
        flushables += flushable
    }

    /** Flushes every [Flushable]; one that fails does not stop the others. Server thread, at the stop. */
    fun flushAll() {
        flushables.forEach { flushable ->
            try {
                flushable.flush()
            } catch (e: Exception) {
                log.error("flush failed at the stop: $flushable", e)
            }
        }
    }

    /**
     * Runs the body of hook [name]. Every hook entry point goes through here: an exception must never
     * escape into the game's code (it would cancel the player's action), so it is caught and logged,
     * at most once a minute per hook. The time spent is added to [hookNanos].
     */
    inline fun hook(name: String, body: () -> Unit) {
        val start = System.nanoTime()
        try {
            body()
        } catch (e: Throwable) {
            failed(name, e)
        } finally {
            hookTime.addAndGet(System.nanoTime() - start)
        }
    }

    @PublishedApi
    internal fun failed(name: String, error: Throwable) {
        val now = System.currentTimeMillis()
        val last = lastReports[name]
        if (last == null || now - last >= REPORT_INTERVAL_MS) {
            lastReports[name] = now
            log.error("hook $name failed", error)
        }
    }

    companion object {
        private val log = Log.observer
        private val REPORT_INTERVAL_MS = TimeUnit.MINUTES.toMillis(1)

        /**
         * The Observer of the running server: null before it has started and once it has stopped. Hooks
         * do nothing when it is null. Still set between `SERVER_STOPPING` and `SERVER_STOPPED`, when
         * the players' departures must be recorded.
         */
        fun current(): ObserverState? = NarratorRuntime.current?.observer
    }
}
