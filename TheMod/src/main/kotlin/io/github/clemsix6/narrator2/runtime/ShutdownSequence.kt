package io.github.clemsix6.narrator2.runtime

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.event.EventBus
import io.github.clemsix6.narrator2.journal.Journal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration

/**
 * Stops the Narrator in the two steps the server offers (M1_M2 §2.4). Both run on the server thread
 * and are bounded in time; a step that fails is logged and the next ones still run.
 *
 * [begin], on `SERVER_STOPPING`, comes before the server saves and disconnects the players. No new work
 * is taken ([Phase.STOPPING]: the Director, the workers, the memory and the tools check it). The dashboard
 * stops first ([stopDashboard], at once: no page is served while the rest stops). Then the mechanics
 * engine ([stopMechanics], on the server thread, without waiting: no new act, its queue
 * dropped, its boss bars taken down, its modules stopped; its state stays in the world, which the stopping save
 * writes). Then the Forge ([stopForge], a second at most: its compiler thread ends, its bench runs are cancelled).
 * Then the memory ([stopMemory], waited for at most [memoryTimeout]): its running job is recorded as
 * cancelled and the world play clock saved, while the Journal still writes. Then the work in flight is
 * cancelled: every coroutine of [scope] (Director, workers, model calls, which close their streams
 * without appending anything, SQL reads, reloads), waited for at most [workTimeout]. The
 * subscriptions of [DIRECTOR_OWNER] close; the Journal, the player states and the Observer stay
 * subscribed, because the players' departures come after this step.
 *
 * [finish], on `SERVER_STOPPED`, comes once the players are gone: the Observer publishes what it still
 * holds, the bus closes, the Journal writes what is queued and closes within [journalTimeout], then the
 * clients close.
 */
class ShutdownSequence(
    private val scope: CoroutineScope,
    private val bus: EventBus,
    private val journal: Journal,
    private val settings: SettingsHolder,
    private val flushObserver: () -> Unit,
    private val setPhase: (Phase) -> Unit,
    private val workTimeout: Duration = WORK_TIMEOUT,
    private val journalTimeout: Duration = Journal.DEFAULT_CLOSE_TIMEOUT,
    private val journalGrace: Duration = JOURNAL_GRACE,
    private val stopMemory: suspend () -> Unit = {},
    private val memoryTimeout: Duration = MEMORY_TIMEOUT,
    private val stopMechanics: () -> Unit = {},
    private val stopForge: () -> Unit = {},
    private val stopDashboard: () -> Unit = {},
) {
    private val log = Log.main

    @Volatile
    private var begun = false

    /**
     * `SERVER_STOPPING`: takes no new work, stops the dashboard, the mechanics engine (its modules first), then the
     * Forge, then the memory, then cancels the work in flight.
     */
    fun begin() {
        if (begun) return
        begun = true
        setPhase(Phase.STOPPING)
        step("stop the dashboard") { stopDashboard() }
        step("stop the mechanics") { stopMechanics() }
        step("stop the Forge") { stopForge() }
        step("stop the memory") {
            val start = System.nanoTime()
            val stopped = runBlocking { withTimeoutOrNull(memoryTimeout.toMillis()) { stopMemory() } } != null
            val ms = (System.nanoTime() - start) / 1_000_000
            if (stopped) log.info("stopping: memory stopped in $ms ms") else log.warn("stopping: the memory did not stop within $ms ms; not waiting for it")
        }
        step("cancel the work in flight") {
            val start = System.nanoTime()
            val job = scope.coroutineContext.job
            val running = job.children.toList()
            job.cancel(CancellationException("the server is stopping"))
            val ended = runBlocking { withTimeoutOrNull(workTimeout.toMillis()) { running.joinAll() } } != null
            val ms = (System.nanoTime() - start) / 1_000_000
            if (ended) {
                log.info("stopping: ${running.size} background jobs cancelled in $ms ms")
            } else {
                val left = running.count { !it.isCompleted }
                log.warn("stopping: $left of ${running.size} background jobs still running after $ms ms; not waiting for them")
            }
        }
        step("close the Director's subscriptions") { bus.unsubscribeOwner(DIRECTOR_OWNER) }
    }

    /** `SERVER_STOPPED`: publishes what the Observer holds, then closes the bus, the Journal and the clients. */
    fun finish() {
        begin()
        step("flush the Observer") { flushObserver() }
        step("close the event bus") { bus.close() }
        step("close the Journal") { closeJournal() }
        step("close the clients") { settings.get().close() }
        setPhase(Phase.STOPPED)
        log.info("stopped")
    }

    /**
     * Closes the Journal on its own thread: [Journal.close] promises to return within [journalTimeout],
     * and the server never waits for it longer than that plus [journalGrace].
     */
    private fun closeJournal() {
        val start = System.nanoTime()
        val closer = Thread({
            try {
                journal.close(journalTimeout)
            } catch (e: Throwable) {
                log.error("the Journal failed to close", e)
            }
        }, "narrator2-journal-close")
        closer.isDaemon = true
        closer.start()
        closer.join(journalTimeout.plus(journalGrace).toMillis())
        val ms = (System.nanoTime() - start) / 1_000_000
        if (closer.isAlive) {
            log.error("stopped waiting for the Journal after $ms ms: it did not close within its ${journalTimeout.toMillis()} ms")
        } else {
            log.info("stopping: Journal closed in $ms ms")
        }
    }

    private inline fun step(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log.error("stopping: could not $what", e)
        }
    }

    companion object {
        /**
         * Bus owner of the subscriptions that feed the Director (salience, thinking indicator): closed at
         * `SERVER_STOPPING`, unlike the subscribers that record facts.
         */
        const val DIRECTOR_OWNER = "director"

        /** Longest the server waits for cancelled background work. */
        val WORK_TIMEOUT: Duration = Duration.ofSeconds(2)

        /**
         * Longest the server waits for the memory to stop: the `cancelled` row of its running job, then the
         * save of the world play clock, 5 s at most each (`MemoryJobs.stop`).
         */
        val MEMORY_TIMEOUT: Duration = Duration.ofSeconds(11)

        /** Extra time given to a Journal that overruns its own close timeout. */
        val JOURNAL_GRACE: Duration = Duration.ofSeconds(2)
    }
}
