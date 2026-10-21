package io.github.clemsix6.narrator2.runtime

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.runOnServerThread
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl
import net.minecraft.server.network.ServerLoginPacketListenerImpl
import net.minecraft.util.thread.BlockableEventLoop
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * A Narrator as `/narrator restart` handles it (M6 §9): the server's `NarratorRuntime`, fakes in the unit tests. It
 * stops in two halves, the first on the server thread without waiting, the second off it; a new one is prepared off
 * the server thread (its Journal opened, its memory read), then wired on it.
 */
interface Restartable {
    /**
     * The half of the stop that never waits: no new work (`STOPPING`), the dashboard, the mechanics (their modules stop
     * with their state kept, their boss bars taken down) and the Forge stop, the Observer publishes what it holds; then
     * it is no longer the running Narrator, and the hooks registered at the mod's initialization do nothing. On a
     * Narrator whose wiring failed halfway, it stops what the wiring made. Server thread.
     */
    fun stopInGame()

    /**
     * The half that waits, bounded: the memory, the work in flight, the Director's subscriptions, the bus, the Journal
     * and the clients; on a Narrator never wired, what its preparation opened. Off the server thread.
     */
    fun stopAndClose()

    /** Wires what was prepared, without waiting, and becomes the running Narrator; throws when it cannot start. Server thread. */
    fun wireAndRun()

    /** What runs, for the operator: `Journal available · memory on · mechanics on · forge on`. Any thread. */
    fun summary(): String

    /** Records the decision `operator restarted` of [report] (M6 §14), in its own Journal. Server thread. */
    fun restarted(report: RestartReport)
}

/**
 * How a restart went (M6 §9, step 4), asked by [by]. [failure]: why the Narrator stays off; null when it runs again,
 * [summary] saying what runs. Times in milliseconds: [stopMs] the old Narrator's stop (both halves), [prepareMs] the new
 * one's Journal and memory, [wireMs] its wiring on the server thread, [gapMs] without a running Narrator (no fact was
 * journaled, nobody was online), [totalMs] from the command to the end.
 */
data class RestartReport(
    val by: String,
    val failure: String?,
    val summary: String?,
    val stopMs: Long,
    val prepareMs: Long,
    val wireMs: Long,
    val gapMs: Long,
    val totalMs: Long,
) {
    val ok: Boolean get() = failure == null

    /** The operator's answer: `Narrator restarted in 2.3 s: Journal available · …`, or why the Narrator stays off. */
    fun text(): String =
        if (failure == null) {
            "Narrator restarted in ${seconds(totalMs)} s: $summary"
        } else {
            "the restart failed after ${seconds(totalMs)} s, the Narrator stays off: $failure; /narrator restart tries again"
        }

    /** `stop 412 ms · prepare 960 ms · wire 38 ms · 1410 ms without a Narrator`. */
    fun times(): String = "stop $stopMs ms · prepare $prepareMs ms · wire $wireMs ms · $gapMs ms without a Narrator"

    private fun seconds(ms: Long) = String.format(Locale.ROOT, "%.1f", ms / 1000.0)
}

/**
 * `/narrator restart` (design M6 §9): rebuilds the Narrator without stopping Minecraft, on an empty server only. With a
 * player online, the guards would fall while the Narrator is rebuilt: the command refuses ([refusal]). One restart at a
 * time; while it runs, the logins wait ([holdLogin], on Fabric's `ServerLoginConnectionEvents.QUERY_START`): nobody
 * enters a world without a Narrator.
 *
 * 1. Server thread, the command ([start]): the logins are held; the old Narrator stops in the game
 *    ([Restartable.stopInGame]) and leaves `NarratorRuntime.current` null.
 * 2. Thread [THREAD]: the rest of its stop ([Restartable.stopAndClose]: the memory, 11 s at most; the work in flight,
 *    2 s; the bus; the Journal, 14 s), then the new Narrator is prepared (its settings loaded, its Journal opened, three
 *    attempts in 10 s; its memory's reads, 30 s).
 * 3. Server thread: the new Narrator is wired ([Restartable.wireAndRun], no waiting), then the logins go on, in a
 *    `finally`: whatever happened. A wiring that fails leaves the Narrator off, as at the server's start ("cannot start,
 *    the Narrator stays off"): what it made stops there, the rest closes on [THREAD]. So does a new Narrator the server
 *    thread never gets to ([WIRE_WAIT_MS] by default) or a server that stops meanwhile: it is closed, never wired.
 * 4. The decision `operator restarted` and the answer ([RestartReport]), on the server thread.
 *
 * The next restart can start once [THREAD] is done. A login that waits longer than the game allows
 * (`ServerLoginPacketListenerImpl.MAX_TICKS_BEFORE_LOGIN`, 600 ticks: 30 s) is disconnected and tries again.
 */
class RestartSequence(private val wireWaitMs: Long = WIRE_WAIT_MS, private val nanoTime: () -> Long = System::nanoTime) {
    private val log = Log.main

    /** The logins' gate of the restart that runs; null when none does. */
    private val gate = AtomicReference<CompletableFuture<Unit>?>()

    /** Whether a restart runs, until its thread is done. Any thread. */
    val running: Boolean get() = gate.get() != null

    /**
     * A login that reaches `QUERY_START` while a restart runs waits for the end of its wiring (M6 §9). Registered once
     * in `Narrator2`. Server thread (the login's tick).
     */
    fun holdLogin(synchronizer: ServerLoginNetworking.LoginSynchronizer) {
        val held = gate.get()?.takeUnless { it.isDone } ?: return
        log.info("a login waits for the end of the restart")
        synchronizer.waitFor(held)
    }

    /**
     * Starts a restart asked by [by]: step 1 here, the rest on [THREAD] and [server]'s thread. False, doing nothing,
     * when one already runs. Server thread (the command's); the caller checked [refusal] first.
     *
     * @param old the running Narrator; null when none runs (it could not start).
     * @param prepare makes the new Narrator, off the server thread; it may throw.
     * @param stopping whether the server stops: the new Narrator is then closed, never wired.
     * @param done gets the report on the server thread, once it is over; never once the server has stopped.
     */
    fun start(
        server: BlockableEventLoop<*>,
        by: String,
        old: Restartable?,
        prepare: () -> Restartable,
        stopping: () -> Boolean,
        done: (RestartReport) -> Unit,
    ): Boolean {
        val held = CompletableFuture<Unit>()
        if (!gate.compareAndSet(null, held)) return false
        try {
            val began = nanoTime()
            log.info("restart asked by $by: the logins wait until it is done")
            if (old != null) {
                try {
                    old.stopInGame()
                } catch (e: Exception) {
                    log.error("restart: the old Narrator did not stop in the game; going on", e)
                }
            }
            val run = Run(server, by, old, prepare, stopping, done, held, began, left = nanoTime())
            Thread(run::go, THREAD).start() // not a daemon: a stop of the server meanwhile waits for the Journal to close
        } catch (e: Throwable) {
            held.complete(Unit)
            gate.set(null)
            throw e
        }
        return true
    }

    /** One restart, from step 2 on: its thread's work. */
    private inner class Run(
        val server: BlockableEventLoop<*>,
        val by: String,
        val old: Restartable?,
        val prepare: () -> Restartable,
        val stopping: () -> Boolean,
        val done: (RestartReport) -> Unit,
        val held: CompletableFuture<Unit>,
        val began: Long,
        val left: Long,
    ) {
        private var stopMs = ms(left - began)
        private var prepareMs = 0L
        private var wireMs = 0L
        private var gapMs = 0L

        fun go() {
            try {
                restart()
            } finally {
                held.complete(Unit)
                gate.compareAndSet(held, null)
            }
        }

        private fun restart() {
            var fresh: Restartable? = null
            var failure: String? = null
            var stage = "the old Narrator did not stop"
            try {
                val stopStart = nanoTime()
                old?.stopAndClose()
                val prepareStart = nanoTime()
                stopMs += ms(prepareStart - stopStart)
                log.info("restart: the old Narrator stopped in $stopMs ms${if (old == null) " (none was running)" else ""}")
                stage = "the new Narrator could not be prepared"
                fresh = prepare()
                prepareMs = ms(nanoTime() - prepareStart)
                log.info("restart: the new Narrator prepared in $prepareMs ms")
                failure = wireOnServer(fresh)
            } catch (e: Exception) {
                log.error("restart: $stage", e)
                failure = "$stage: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                held.complete(Unit)
            }
            try {
                if (failure != null) fresh?.stopAndClose()
            } catch (e: Exception) {
                log.error("restart: the new Narrator did not close", e)
            }
            val report = RestartReport(by, failure, fresh?.takeIf { failure == null }?.summary(), stopMs, prepareMs, wireMs, gapMs, ms(nanoTime() - began))
            if (report.ok) log.info("restart: ${report.text()} (${report.times()})") else log.warn("restart: ${report.text()} (${report.times()})")
            val told = server.runOnServerThread {
                if (report.ok) {
                    try {
                        fresh?.restarted(report)
                    } catch (e: Exception) {
                        log.error("restart: its decision was not recorded", e)
                    }
                }
                done(report)
            }
            if (!told) log.info("restart: the server has stopped, the answer is not sent")
        }

        /**
         * Step 3: [fresh] wired on the server thread, the logins released there; the reason it was not, or null. The
         * restart's thread claims it instead when the server stops or never gets to it within [wireWaitMs]: then the
         * server thread no longer wires it, even late.
         */
        private fun wireOnServer(fresh: Restartable): String? {
            val claimed = AtomicBoolean()
            val outcome = CompletableFuture<String?>()
            server.execute {
                when {
                    // A stopped server runs a task on the caller's thread: never wired there.
                    !server.isSameThread -> outcome.complete("the server has stopped")
                    claimed.compareAndSet(false, true) -> try {
                        outcome.complete(wire(fresh))
                    } catch (e: Throwable) {
                        outcome.complete("it could not start: $e")
                        throw e
                    }
                }
            }
            val deadline = nanoTime() + TimeUnit.MILLISECONDS.toNanos(wireWaitMs)
            while (true) {
                try {
                    return outcome.get(POLL_MS, TimeUnit.MILLISECONDS)
                } catch (e: TimeoutException) {
                    val late = nanoTime() > deadline
                    if ((late || stopping()) && claimed.compareAndSet(false, true)) {
                        return if (late) "the server thread did not get to its wiring within $wireWaitMs ms" else "the server is stopping"
                    }
                }
            }
        }

        /** Step 3 on the server thread: wires [fresh], then lets the logins go on, whatever happened. */
        private fun wire(fresh: Restartable): String? {
            val start = nanoTime()
            try {
                if (stopping()) return "the server is stopping"
                fresh.wireAndRun()
                return null
            } catch (e: Exception) {
                log.error("restart: cannot start, the Narrator stays off", e)
                try {
                    fresh.stopInGame()
                } catch (stop: Exception) {
                    log.error("restart: what the wiring made did not stop", stop)
                }
                return "it could not start: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                val end = nanoTime()
                wireMs = ms(end - start)
                gapMs = ms(end - left)
                held.complete(Unit)
            }
        }
    }

    private fun ms(nanos: Long) = nanos / 1_000_000

    companion object {
        /** The restart's own thread (M6 §1.1), the time of a restart. */
        const val THREAD = "narrator2-restart"

        /** Longest the restart waits for the server thread to wire the new Narrator; a server that stops is seen sooner. */
        const val WIRE_WAIT_MS = 60_000L

        /** How often the restart looks whether the server stops, while it waits for the wiring. */
        private const val POLL_MS = 100L

        /**
         * Why a restart cannot start (M6 §9), after `cannot restart: `: the players [online] (`2 players online (Bartok,
         * Mimosa); restart when the server is empty, or restart the server`), those [joining] (in the login or the
         * configuration phase: their join would not wait), a server [stopping]; null when it can.
         */
        fun refusal(online: List<String>, joining: List<String>, stopping: Boolean): String? {
            if (stopping) return "the server is stopping"
            val who = when {
                online.isNotEmpty() -> "${players(online.size)} online (${online.joinToString()})"
                joining.isNotEmpty() -> "${players(joining.size)} joining (${joining.joinToString()})"
                else -> return null
            }
            return "$who; restart when the server is empty, or restart the server"
        }

        /** [refusal] on [server]: its players online and joining; [stopping] also when the running Narrator stops. Server thread. */
        fun refusal(server: MinecraftServer, stopping: Boolean): String? =
            refusal(server.playerList.players.map { it.plainTextName }, joining(server), stopping || !server.isRunning)

        /** The players in the login or the configuration phase: not online yet, and past the hold of a login maybe. */
        private fun joining(server: MinecraftServer): List<String> {
            val connections = server.connection.connections
            return synchronized(connections) {
                connections.mapNotNull { connection ->
                    when (val listener = connection.packetListener) {
                        is ServerLoginPacketListenerImpl -> listener.userName
                        is ServerConfigurationPacketListenerImpl -> listener.owner.name()
                        else -> null
                    }
                }
            }
        }

        private fun players(count: Int) = if (count == 1) "1 player" else "$count players"
    }
}
