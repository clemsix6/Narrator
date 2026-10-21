package io.github.clemsix6.narrator2.runtime

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.runOnServerThread
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking
import net.minecraft.network.PacketListener
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
 * A Narrator as `/narrator restart` handles it (M6 §9): the server's `NarratorRuntime`, fakes in the unit tests. A new
 * one is made, then prepared off the server thread (its Journal opened, its memory read), then wired on it. It stops in
 * two halves, the first on the server thread without waiting, the second off it.
 */
interface Restartable {
    /** Opens what the wiring needs and may wait for, bounded: its Journal, its memory's reads. Off the server thread; it may throw. */
    fun prepare()

    /**
     * The half of the stop that never waits: no new work (`STOPPING`), the dashboard, the mechanics (their modules stop
     * with their state kept, their boss bars taken down) and the Forge stop, the Observer publishes what it holds; then
     * it is no longer the running Narrator, and the hooks registered at the mod's initialization do nothing. On a
     * Narrator whose wiring failed halfway, it stops what the wiring made. Server thread.
     */
    fun stopInGame()

    /**
     * The half that waits, bounded: the memory, the work in flight, the Director's subscriptions, the bus, the Journal
     * and the clients; on a Narrator never wired, what its preparation opened. Throws when its Journal is still closing
     * past its time, its database open (`Journal.holdsDatabase`); called again, it waits for that Journal once more, as
     * boundedly. Off the server thread.
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

    /** `stop 412 ms · prepare 960 ms · wire 38 ms · 1410 ms without a Narrator`; no time without a Narrator when it stays off. */
    fun times(): String = "stop $stopMs ms · prepare $prepareMs ms · wire $wireMs ms" + if (ok) " · $gapMs ms without a Narrator" else ""

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
 *    2 s; the bus; the Journal, 10 s and 2 s of grace). A server that stops meanwhile ends the restart there. Then the
 *    new Narrator is made and prepared (its settings loaded, its Journal opened, three attempts in 10 s; its memory's
 *    reads, 30 s).
 * 3. Server thread: the new Narrator is wired ([Restartable.wireAndRun], no waiting), then the logins go on, in a
 *    `finally`: whatever happened. A wiring that fails leaves the Narrator off, as at the server's start ("cannot start,
 *    the Narrator stays off"): what it made stops there, the rest closes on [THREAD]. So does a new Narrator the server
 *    thread never gets to ([WIRE_WAIT_MS] by default) or a server that stops meanwhile: it is closed, never wired.
 * 4. The decision `operator restarted` and the answer ([RestartReport]), on the server thread.
 *
 * Never two Journals on one file: a Narrator whose close fails (its Journal still closing past its time) leaves the
 * Narrator off, and the next restart closes it again before anything is prepared ([unclosed]). Below this, the Journal's
 * file itself never opens twice in the process (`DuckDatabase.open`), whoever opens it. The next restart can start once
 * [THREAD] is done. A login that waits longer than the game allows
 * (`ServerLoginPacketListenerImpl.MAX_TICKS_BEFORE_LOGIN`, 600 ticks: 30 s) is disconnected and tries again.
 */
class RestartSequence(private val wireWaitMs: Long = WIRE_WAIT_MS, private val nanoTime: () -> Long = System::nanoTime) {
    private val log = Log.main

    /** The logins' gate of the restart that runs; null when none does. */
    private val gate = AtomicReference<CompletableFuture<Unit>?>()

    /**
     * The Narrators a restart stopped or made whose close failed, their Journal still holding its database: the next
     * restart closes them first, and prepares nothing while one still fails. Thread [THREAD], one restart at a time.
     */
    @Volatile
    private var unclosed: List<Restartable> = emptyList()

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
     * @param create makes the new Narrator, off the server thread, before it is prepared; it may throw.
     * @param stopping whether the server stops: the new Narrator is then not prepared, or closed, never wired.
     * @param done gets the report on the server thread, once it is over; never once the server has stopped.
     */
    fun start(
        server: BlockableEventLoop<*>,
        by: String,
        old: Restartable?,
        create: () -> Restartable,
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
            val run = Run(server, by, old, create, stopping, done, held, began, left = nanoTime())
            Thread(run::go, THREAD).start() // not a daemon: a stop of the server meanwhile waits for the Journal to close
        } catch (e: Throwable) {
            held.complete(Unit)
            gate.set(null)
            throw e
        }
        return true
    }

    /**
     * Closes [narrators] ([Restartable.stopAndClose]) after those an earlier restart could not close; throws the first
     * failure, the Narrators that failed kept in [unclosed] for the next restart. Thread [THREAD].
     */
    private fun close(narrators: List<Restartable>) {
        val earlier = unclosed
        val failed = (earlier + narrators).mapNotNull { narrator ->
            try {
                narrator.stopAndClose()
                if (earlier.any { it === narrator }) log.info("restart: the Narrator an earlier restart could not close is closed")
                null
            } catch (e: Exception) {
                narrator to e
            }
        }
        unclosed = failed.map { it.first }
        failed.firstOrNull()?.let { throw it.second }
    }

    /** One restart, from step 2 on: its thread's work. */
    private inner class Run(
        val server: BlockableEventLoop<*>,
        val by: String,
        val old: Restartable?,
        val create: () -> Restartable,
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

        /** The new Narrator, once made: closed again when the restart fails. */
        private var fresh: Restartable? = null

        fun go() {
            try {
                restart()
            } finally {
                held.complete(Unit)
                gate.compareAndSet(held, null)
            }
        }

        private fun restart() {
            var stage = "the old Narrator did not stop"
            val failure = try {
                stopOld()
                if (stopping()) {
                    "the server is stopping"
                } else {
                    stage = "the new Narrator could not be prepared"
                    wireOnServer(prepareNew())
                }
            } catch (e: Exception) {
                log.error("restart: $stage", e)
                "$stage: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                held.complete(Unit)
            }
            val fresh = fresh
            if (failure != null && fresh != null) {
                try {
                    close(listOf(fresh))
                } catch (e: Exception) {
                    log.error("restart: the new Narrator did not close", e)
                }
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

        /** Step 2, first: the old Narrator closed, after those an earlier restart could not close; throws when one is not. */
        private fun stopOld() {
            val start = nanoTime()
            try {
                close(listOfNotNull(old))
            } finally {
                stopMs += ms(nanoTime() - start)
            }
            log.info("restart: the old Narrator stopped in $stopMs ms${if (old == null) " (none was running)" else ""}")
        }

        /** Step 2, then: the new Narrator made and prepared. */
        private fun prepareNew(): Restartable {
            val start = nanoTime()
            val made = create()
            fresh = made
            made.prepare()
            prepareMs = ms(nanoTime() - start)
            log.info("restart: the new Narrator prepared in $prepareMs ms")
            return made
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
            return synchronized(connections) { connections.mapNotNull { joiningName(it.packetListener) } }
        }

        /**
         * Who joins through [listener] when it is in the login or the configuration phase; null in another phase. A name,
         * never an address: a login that has not said its name yet is `a connection`.
         */
        fun joiningName(listener: PacketListener?): String? = when (listener) {
            is ServerLoginPacketListenerImpl -> loginName(listener.userName)
            is ServerConfigurationPacketListenerImpl -> listener.owner.name()
            else -> null
        }

        /**
         * The name in `ServerLoginPacketListenerImpl.getUserName()`: `<name> (<address>)` once the login said its name
         * (the address, `IP hidden` or `local`), the address alone before, which is `a connection` here. A name never holds
         * ` (` (`StringUtil.isValidPlayerName`, checked as the name comes).
         */
        internal fun loginName(userName: String): String = if (" (" in userName) userName.substringBefore(" (") else "a connection"

        private fun players(count: Int) = if (count == 1) "1 player" else "$count players"
    }
}
