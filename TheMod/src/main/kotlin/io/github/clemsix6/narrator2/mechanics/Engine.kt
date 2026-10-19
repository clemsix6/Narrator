package io.github.clemsix6.narrator2.mechanics

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.event.Envelope
import io.github.clemsix6.narrator2.event.Subscription
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.runtime.Phase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import java.time.Clock
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The host of the engine's parts (M4 §4.1, §4.2, §4.6): it drives the [parts] on the server thread and
 * knows nothing of what each one does. `MechanicsService` (M4-23) builds it with the world's [store] and
 * hands it the server's hooks.
 *
 * - [start] (server thread, as the engine is created): the state as the server starts ([Reconcile.startup]:
 *   what ended while the server was down is taken out, what could not be read is reported), then each
 *   part's `started`, then one synchronous subscriber `mechanics` on the bus, whose filter lets through only
 *   the facts some part listens to ([MechanicPart.listensTo]).
 * - [tick] (every server tick): each part's `everyTick`; once a second of wall clock, each part's
 *   `everySecond`, then the snapshot of the situation lines ([situationLines]) is rebuilt. A second that
 *   takes more than [MechanicsLimits.SLOW_SECOND_MS] is a `WARN`, once a minute at most.
 * - Once the server runs, the reconciliation ([Reconcile.run]) goes in a coroutine, for the world's store
 *   only; the entries it names are dropped from the state at the next tick ([Reconcile.drop],
 *   [MechanicPart.dropped]), and the state's unreadable entries forgotten.
 * - The player and creature hooks go to every part; [allowDeath]: the first part that refuses wins.
 * - [stop] (`SERVER_STOPPING`): the subscriber closes, each part's `stopping`, and nothing runs any more.
 *
 * A part that throws is logged (once a minute at most per part and hook, with the count) and never stops
 * the other parts nor the game.
 *
 * @param reconciles whether to reconcile [store] with the canon: the world's store only, by default. A store
 *   [StateStore.inMemory] (a GameTest's) never reconciles: the canon it would compare is the server's.
 */
class Engine(
    private val services: NarratorServices,
    val store: StateStore,
    private val parts: List<MechanicPart>,
    private val clock: Clock = services.clock,
    private val reconciles: Boolean = store.inWorld,
) {
    private val log = Log.mechanics
    private val reconcile = Reconcile(services)

    /** The parts that listen to each class of fact (the facts are final classes). */
    private val listeners: Map<Class<*>, List<MechanicPart>> =
        parts.flatMap { part -> part.listensTo.map { it.java to part } }.groupBy({ it.first }, { it.second })

    private var subscription: Subscription? = null
    private var startup: Startup? = null
    private var reconciling = false

    /** What the reconciliation names to drop, waiting for the next tick; null until it is done. */
    @Volatile
    private var reconciled: Set<Long>? = null

    /** When the last second of wall clock ran; [Long.MIN_VALUE] before the first one. */
    private var lastSecondAt = Long.MIN_VALUE

    @Volatile
    private var lines: List<String> = emptyList()

    private val failures = ConcurrentHashMap<String, Failures>()
    private var slowSeconds = 0
    private var slowReportedAt = Long.MIN_VALUE

    @Volatile
    var started: Boolean = false
        private set

    @Volatile
    var stopped: Boolean = false
        private set

    /** The situation lines of the parts (M4 §4.5), as the last second built them: never a wait. Any thread. */
    fun situationLines(): List<String> = if (stopped) emptyList() else lines

    /** Starts the engine (M4 §4.6), once, on the server thread, while the server starts. */
    fun start(server: MinecraftServer) {
        val now = clock.millis()
        begin(now) { it.started(server, now) }
    }

    /** End of a server tick ([Mechanics.tick]). Server thread. */
    fun tick(server: MinecraftServer) {
        val now = clock.millis()
        beat(now, { it.everyTick(server, now) }, { it.everySecond(server, now) })
    }

    fun onJoin(player: ServerPlayer) = each("onJoin") { it.onJoin(player) }

    fun onLeave(player: ServerPlayer) = each("onLeave") { it.onLeave(player) }

    fun onRespawn(old: ServerPlayer, new: ServerPlayer) = each("onRespawn") { it.onRespawn(old, new) }

    fun onMobLoad(entity: Entity, level: ServerLevel) = each("onMobLoad") { it.onMobLoad(entity, level) }

    /**
     * [Mechanics.allowDeath]: false as soon as a part refuses the death (that part gave health back); the
     * parts after it are not asked. A part that throws allows it. Server thread.
     */
    fun allowDeath(player: ServerPlayer, source: DamageSource): Boolean {
        if (!started || stopped) return true
        return parts.all { part -> call(part, "allowDeath", true) { part.allowDeath(player, source) } }
    }

    /** `SERVER_STOPPING`: the bus subscriber closes, each part stops; the state stays in the world. Server thread. */
    fun stop() {
        if (stopped) return
        stopped = true
        subscription?.close()
        subscription = null
        parts.forEach { part -> call(part, "stopping", Unit) { part.stopping() } }
        lines = emptyList()
        log.info("the engine stopped; its state stays in the world")
    }

    /**
     * [start] without the server, for the tests: [onStart] is each part's `started` at [now]. The state
     * as the server starts, then the parts, then the bus, then the first snapshot.
     */
    internal fun begin(now: Long, onStart: (MechanicPart) -> Unit) {
        check(!started) { "the engine is started once" }
        val (kept, found) = reconcile.startup(store.get(), now)
        store.update { kept }
        startup = found
        parts.forEach { part -> call(part, "started", Unit) { onStart(part) } }
        if (listeners.isNotEmpty()) {
            val classes = listeners.keys
            subscription = services.bus.subscribeSync(SUBSCRIBER, SUBSCRIBER, filter = { it.javaClass in classes }, handler = ::deliver)
        }
        refresh(now)
        lastSecondAt = now
        started = true
        log.info(
            "the engine started with ${parts.size} parts: ${kept.challenges.size} challenges, ${kept.rules.size} rules" +
                (if (found.closings.isEmpty()) "" else "; ${found.closings.size} ended while the server was down") +
                (if (found.unreadable.isEmpty()) "" else "; ${found.unreadable.size} entries unreadable"),
        )
    }

    /**
     * [tick] without the server, for the tests: [onTick] is each part's `everyTick`, [onSecond] its
     * `everySecond`, at [now]. Server thread.
     */
    internal fun beat(now: Long, onTick: (MechanicPart) -> Unit, onSecond: (MechanicPart) -> Unit) {
        if (!started || stopped) return
        reconciled?.let { applyReconciliation(it) }
        if (!reconciling && reconciles && services.phase == Phase.RUNNING) launchReconciliation()
        each("everyTick", onTick)
        // Once a second of wall clock; a clock set back starts the count again.
        if (now - lastSecondAt < SECOND_MS && now >= lastSecondAt) return
        lastSecondAt = now
        val begun = System.nanoTime()
        val slowest = parts.map { part ->
            val partBegun = System.nanoTime()
            call(part, "everySecond", Unit) { onSecond(part) }
            part to System.nanoTime() - partBegun
        }.maxByOrNull { it.second }
        refresh(now)
        val nanos = System.nanoTime() - begun
        if (nanos > MechanicsLimits.SLOW_SECOND_MS * 1_000_000) slow(now, nanos, slowest)
    }

    /** The bus subscriber: [envelope] to the parts that listen to its class. Server thread, inline. */
    private fun deliver(envelope: Envelope) {
        if (stopped) return
        listeners[envelope.event.javaClass]?.forEach { part -> call(part, "onEvent", Unit) { part.onEvent(envelope) } }
    }

    /** Rebuilds the snapshot of the situation lines at [now], each cut to [MechanicsLimits.SITUATION_LINE]. */
    private fun refresh(now: Long) {
        lines = parts.flatMap { part -> call(part, "situation", emptyList()) { part.situation(now) } }
            .map { EngineActions.cut(it, MechanicsLimits.SITUATION_LINE) }
    }

    private fun launchReconciliation() {
        val found = startup ?: return
        reconciling = true
        services.scope.launch {
            reconciled = try {
                reconcile.run(found)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("the reconciliation failed; the state is kept as it is", e)
                emptySet()
            }
        }
    }

    /** Drops [ids] from the state and forgets its unreadable entries, then tells the parts. Server thread. */
    private fun applyReconciliation(ids: Set<Long>) {
        reconciled = null
        store.update { state ->
            val dropped = Reconcile.drop(state, ids)
            if (dropped.unreadable.isEmpty()) dropped else dropped.copy(unreadable = emptyList())
        }
        if (ids.isEmpty()) return
        log.info("dropped from the state by the reconciliation: ${ids.joinToString { "C-$it" }}")
        parts.forEach { part -> call(part, "dropped", Unit) { part.dropped(ids) } }
    }

    private fun slow(now: Long, nanos: Long, slowest: Pair<MechanicPart, Long>?) {
        slowSeconds++
        if (slowReportedAt != Long.MIN_VALUE && now - slowReportedAt < REPORT_GAP_MS) return
        val part = slowest?.let { " (slowest part: ${nameOf(it.first)} ${millis(it.second)} ms)" }.orEmpty()
        log.warn("a second of the engine took ${millis(nanos)} ms$part; $slowSeconds slow seconds since the last warning")
        slowSeconds = 0
        slowReportedAt = now
    }

    private inline fun each(hook: String, crossinline body: (MechanicPart) -> Unit) {
        if (!started || stopped) return
        parts.forEach { part -> call(part, hook, Unit) { body(part) } }
    }

    /** [body] for [part]'s [hook]; [fallback] when it throws, which is logged once a minute at most per part and hook. */
    private inline fun <T> call(part: MechanicPart, hook: String, fallback: T, body: () -> T): T = try {
        body()
    } catch (e: Exception) {
        failed(part, hook, e)
        fallback
    }

    private fun failed(part: MechanicPart, hook: String, error: Exception) {
        val key = "${nameOf(part)}.$hook"
        val now = clock.millis()
        val entry = failures.computeIfAbsent(key) { Failures() }
        val count = entry.count.incrementAndGet()
        val last = entry.reportedAt
        if (last != Long.MIN_VALUE && now - last in 0 until REPORT_GAP_MS) return
        entry.reportedAt = now
        log.error("the part $key failed ($count times so far)", error)
    }

    private class Failures {
        val count = AtomicLong()

        @Volatile
        var reportedAt = Long.MIN_VALUE
    }

    companion object {
        /** Name and owner of the engine's bus subscriber. */
        const val SUBSCRIBER = "mechanics"

        /** The engine's second, in milliseconds of wall clock. */
        const val SECOND_MS = 1_000L

        /** A failing part, or slow seconds, are reported once in this many milliseconds at most. */
        private const val REPORT_GAP_MS = 60_000L

        private fun nameOf(part: MechanicPart): String = part.javaClass.simpleName.ifEmpty { part.javaClass.name }

        private fun millis(nanos: Long): String = "%.1f".format(Locale.ROOT, nanos / 1_000_000.0)
    }
}
