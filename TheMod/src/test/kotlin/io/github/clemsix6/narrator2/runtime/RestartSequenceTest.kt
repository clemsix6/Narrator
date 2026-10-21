package io.github.clemsix6.narrator2.runtime

import io.github.clemsix6.narrator2.core.onServerThread
import io.github.clemsix6.narrator2.event.EventBus
import io.github.clemsix6.narrator2.journal.DisabledJournal
import io.github.clemsix6.narrator2.journal.Journal
import io.github.clemsix6.narrator2.testing.SampleEvents
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.testing.testSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking
import net.minecraft.util.thread.BlockableEventLoop
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `/narrator restart` (M6 §9) step by step, against a small event loop standing in for the server and fake Narrators:
 * on which thread each step runs, the logins held from the command to the wiring and released whatever happens, the
 * refusals, the bounds; and the two halves of [ShutdownSequence] a restart runs on two threads. The real runtime is
 * covered by `RestartGameTests`.
 */
class RestartSequenceTest {
    private val steps = CopyOnWriteArrayList<String>()

    /** The event loop of the "server", and its thread, which runs its tasks until [stopServer]. */
    private val loop = TestLoop()
    private val server = Thread({
        while (!Thread.currentThread().isInterrupted) {
            loop.runQueuedTasks()
            LockSupport.parkNanos(200_000)
        }
    }, "test-server")

    /** Unblocks what a test holds on purpose. */
    private val release = CountDownLatch(1)

    init {
        loop.thread = server
        server.start()
    }

    @AfterEach
    fun cleanUp() {
        release.countDown()
        server.interrupt()
    }

    /** An event loop whose thread is [thread]; once [stopped], it runs a task on the caller's thread, as a stopped MinecraftServer does. */
    private class TestLoop : BlockableEventLoop<Runnable>("test-server", false) {
        @Volatile
        var thread: Thread = Thread {}

        @Volatile
        var stopped = false

        override fun wrapRunnable(runnable: Runnable) = runnable

        override fun shouldRun(task: Runnable) = true

        override fun getRunningThread(): Thread = thread

        override fun scheduleExecutables(): Boolean = !stopped && super.scheduleExecutables()

        fun runQueuedTasks() = runAllTasks()
    }

    /** A Narrator that records each step it runs, and on which thread: `old stopInGame @test-server`. */
    private inner class FakeNarrator(
        private val name: String,
        private val wire: () -> Unit = {},
        private val close: () -> Unit = {},
    ) : Restartable {
        var report: RestartReport? = null

        override fun stopInGame() = record("stopInGame")

        override fun stopAndClose() {
            close()
            record("stopAndClose")
        }

        override fun wireAndRun() {
            record("wireAndRun")
            wire()
        }

        override fun summary() = "Journal available · memory on · mechanics on · forge on"

        override fun restarted(report: RestartReport) {
            this.report = report
            record("restarted")
        }

        private fun record(step: String) {
            steps += "$name $step @${Thread.currentThread().name}"
        }
    }

    /** The logins that reached `QUERY_START`: the futures they wait for. */
    private class Logins : ServerLoginNetworking.LoginSynchronizer {
        val waits = CopyOnWriteArrayList<Future<*>>()

        override fun waitFor(future: Future<*>) {
            waits += future
        }
    }

    /** Runs [start] on the "server" thread, as the command does, with what it reports going to the returned future. */
    private fun restart(
        sequence: RestartSequence,
        old: Restartable?,
        prepare: () -> Restartable,
        stopping: () -> Boolean = { false },
        reported: CompletableFuture<RestartReport> = CompletableFuture(),
    ): Pair<Boolean, CompletableFuture<RestartReport>> {
        val started = onServer {
            sequence.start(loop, "Server", old, prepare, stopping) { report ->
                steps += "done @${Thread.currentThread().name}"
                reported.complete(report)
            }
        }
        return started to reported
    }

    private fun <T> onServer(block: () -> T): T = runBlocking { loop.onServerThread(5_000, block) }

    private fun awaitIdle(sequence: RestartSequence) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (sequence.running) {
            check(System.nanoTime() < end) { "the restart never ended" }
            Thread.sleep(5)
        }
    }

    @Test
    fun `the steps run on the server thread, then the restart's thread, then the server thread, and the server thread never waits`() {
        val sequence = RestartSequence()
        val old = FakeNarrator("old", close = { release.await(10, TimeUnit.SECONDS) })
        val fresh = FakeNarrator("new")

        val (started, reported) = restart(sequence, old, prepare = { steps += "prepare @${Thread.currentThread().name}"; fresh })

        assertTrue(started)
        assertEquals(listOf("old stopInGame @test-server"), steps.toList(), "the command returned while the old Narrator still closes")
        release.countDown()
        val report = reported.get(10, TimeUnit.SECONDS)
        awaitIdle(sequence)
        assertEquals(
            listOf(
                "old stopInGame @test-server",
                "old stopAndClose @${RestartSequence.THREAD}",
                "prepare @${RestartSequence.THREAD}",
                "new wireAndRun @test-server",
                "new restarted @test-server",
                "done @test-server",
            ),
            steps.toList(),
        )
        assertTrue(report.ok)
        assertEquals(report, fresh.report, "the new Narrator records its decision")
        assertEquals("Server", report.by)
        assertTrue(report.text().startsWith("Narrator restarted in ") && report.text().endsWith(" s: Journal available · memory on · mechanics on · forge on"), report.text())
        assertTrue(report.gapMs >= report.wireMs && report.totalMs >= report.stopMs + report.prepareMs, report.times())
    }

    @Test
    fun `the logins wait from the command to the end of the wiring`() {
        val sequence = RestartSequence()
        val logins = Logins()
        sequence.holdLogin(logins)
        assertTrue(logins.waits.isEmpty(), "no restart, no wait")

        val prepared = CountDownLatch(1)
        val wiring = CompletableFuture<Boolean>()
        val fresh = FakeNarrator("new", wire = { wiring.complete(logins.waits.single().isDone) })
        val (_, reported) = restart(sequence, FakeNarrator("old"), prepare = {
            prepared.countDown()
            release.await(10, TimeUnit.SECONDS)
            fresh
        })
        assertTrue(prepared.await(10, TimeUnit.SECONDS))
        onServer { sequence.holdLogin(logins) }
        val held = logins.waits.single()
        assertFalse(held.isDone, "a login waits while the new Narrator is prepared")

        release.countDown()
        assertTrue(reported.get(10, TimeUnit.SECONDS).ok)
        assertFalse(wiring.get(), "still waiting while it is wired")
        assertTrue(held.isDone, "released once it is wired")
        awaitIdle(sequence)
        sequence.holdLogin(logins)
        assertEquals(1, logins.waits.size, "no wait once it is over")
    }

    @Test
    fun `a wiring that fails leaves the Narrator off, stops what it made, and releases the logins`() {
        val sequence = RestartSequence()
        val logins = Logins()
        val fresh = FakeNarrator("new", wire = {
            onServerThreadCheck()
            error("the Forge exploded")
        })
        val (_, reported) = restart(sequence, FakeNarrator("old"), prepare = {
            runBlocking { loop.onServerThread { sequence.holdLogin(logins) } }
            fresh
        })

        val report = reported.get(10, TimeUnit.SECONDS)
        awaitIdle(sequence)
        assertFalse(report.ok)
        assertNull(report.summary)
        assertTrue(report.text().contains("the Narrator stays off: it could not start: the Forge exploded; /narrator restart tries again"), report.text())
        assertTrue(logins.waits.single().isDone, "the logins go on, into a world without a Narrator, as after a start that failed")
        assertNull(fresh.report, "no decision: the Narrator is off")
        assertEquals(
            listOf("new wireAndRun @test-server", "new stopInGame @test-server", "new stopAndClose @${RestartSequence.THREAD}", "done @test-server"),
            steps.filter { it.startsWith("new") || it.startsWith("done") },
        )
    }

    private fun onServerThreadCheck() = check(Thread.currentThread() === server) { "wired off the server thread" }

    @Test
    fun `a preparation that fails releases the logins, and the next restart starts from no Narrator`() {
        val sequence = RestartSequence()
        val (_, reported) = restart(sequence, FakeNarrator("old"), prepare = { error("the Journal is held") })

        val report = reported.get(10, TimeUnit.SECONDS)
        awaitIdle(sequence)
        assertEquals("the new Narrator could not be prepared: the Journal is held", report.failure)
        assertEquals(listOf("old stopInGame @test-server", "old stopAndClose @${RestartSequence.THREAD}", "done @test-server"), steps.toList())

        steps.clear()
        val again = restart(sequence, null, prepare = { FakeNarrator("new") }).second.get(10, TimeUnit.SECONDS)
        assertTrue(again.ok, "none running: nothing to stop, a new one prepared and wired")
        assertEquals(listOf("new wireAndRun @test-server", "new restarted @test-server", "done @test-server"), steps.toList())
    }

    @Test
    fun `a second restart is refused while one runs, without touching anything`() {
        val sequence = RestartSequence()
        val prepared = CountDownLatch(1)
        val (first, reported) = restart(sequence, FakeNarrator("old"), prepare = {
            prepared.countDown()
            release.await(10, TimeUnit.SECONDS)
            FakeNarrator("new")
        })
        assertTrue(first)
        assertTrue(prepared.await(10, TimeUnit.SECONDS))
        assertTrue(sequence.running)

        val (second, _) = restart(sequence, FakeNarrator("other"), prepare = { error("never prepared") })
        assertFalse(second)
        assertTrue(steps.none { it.startsWith("other") }, "the second one stops nothing: $steps")

        release.countDown()
        assertTrue(reported.get(10, TimeUnit.SECONDS).ok)
        awaitIdle(sequence)
        assertTrue(restart(sequence, null, prepare = { FakeNarrator("third") }).first, "once it is over, another can start")
    }

    @Test
    fun `a server that stops before the wiring gets the new Narrator closed, never wired, and no answer`() {
        val sequence = RestartSequence()
        val logins = Logins()
        val fresh = FakeNarrator("new")
        val (_, reported) = restart(sequence, FakeNarrator("old"), prepare = {
            runBlocking { loop.onServerThread { sequence.holdLogin(logins) } }
            // The server stops: its thread ends, and it runs a task on the caller's thread from now on.
            server.interrupt()
            server.join(5_000)
            loop.stopped = true
            fresh
        })

        awaitIdle(sequence)
        assertFalse(reported.isDone, "no answer once the server has stopped")
        assertTrue(steps.none { it.startsWith("new wireAndRun") }, "never wired: $steps")
        assertTrue("new stopAndClose @${RestartSequence.THREAD}" in steps, "its Journal closed: $steps")
        assertTrue(logins.waits.single().isDone)
    }

    @Test
    fun `a server that is stopping gets the new Narrator closed, never wired`() {
        val sequence = RestartSequence()
        val fresh = FakeNarrator("new")
        val (_, reported) = restart(sequence, FakeNarrator("old"), prepare = { fresh }, stopping = { true })

        val report = reported.get(10, TimeUnit.SECONDS)
        awaitIdle(sequence)
        assertEquals("the server is stopping", report.failure)
        assertTrue(steps.none { it.startsWith("new wireAndRun") }, "never wired: $steps")
        assertTrue("new stopAndClose @${RestartSequence.THREAD}" in steps)
    }

    @Test
    fun `a server thread that never gets to the wiring is given up after its bound, and never wires it later`() {
        val sequence = RestartSequence(wireWaitMs = 200)
        server.interrupt() // the server thread is stuck: its tasks wait
        server.join(5_000)
        loop.thread = Thread {}
        val fresh = FakeNarrator("new")
        val reported = CompletableFuture<RestartReport>()
        assertTrue(sequence.start(loop, "Server", FakeNarrator("old"), { fresh }, { false }) { reported.complete(it) })

        awaitIdle(sequence)
        assertTrue("new stopAndClose @${RestartSequence.THREAD}" in steps, "closed: $steps")
        loop.thread = Thread.currentThread()
        loop.runQueuedTasks() // the server thread gets to its tasks at last
        assertEquals("the server thread did not get to its wiring within 200 ms", reported.get(1, TimeUnit.SECONDS).failure)
        assertTrue(steps.none { it.startsWith("new wireAndRun") }, "never wired late: $steps")
    }

    @Test
    fun `refusals name who is online or joining, and the stop of the server`() {
        assertEquals(
            "2 players online (Bartok, Mimosa); restart when the server is empty, or restart the server",
            RestartSequence.refusal(listOf("Bartok", "Mimosa"), emptyList(), stopping = false),
        )
        assertEquals(
            "1 player joining (Bartok); restart when the server is empty, or restart the server",
            RestartSequence.refusal(emptyList(), listOf("Bartok"), stopping = false),
        )
        assertEquals("the server is stopping", RestartSequence.refusal(listOf("Bartok"), emptyList(), stopping = true))
        assertNull(RestartSequence.refusal(emptyList(), emptyList(), stopping = false))
    }

    @Test
    fun `the report says how long and what runs, or why the Narrator stays off`() {
        val ok = RestartReport("Server", null, "Journal available · memory on · mechanics on · forge on", 412, 960, 38, 1_010, 2_340)
        assertEquals("Narrator restarted in 2.3 s: Journal available · memory on · mechanics on · forge on", ok.text())
        assertEquals("stop 412 ms · prepare 960 ms · wire 38 ms · 1010 ms without a Narrator", ok.times())
        val failed = ok.copy(failure = "it could not start: boom", summary = null)
        assertEquals("the restart failed after 2.3 s, the Narrator stays off: it could not start: boom; /narrator restart tries again", failed.text())
    }

    /**
     * The halves of the stop a restart runs on two threads ([ShutdownSequence.stopInGame] and the flush on the server
     * thread, [ShutdownSequence.stopWaiting] and [ShutdownSequence.close] on its own), in the order of the server's stop.
     */
    @Test
    fun `the stop's halves keep the order of the server's stop, each once`() {
        val clock = TestClock()
        val bus = EventBus(clock, { 0L }, 1)
        val journal = object : Journal by DisabledJournal("test") {
            override fun close(timeout: Duration) {
                steps += "journal closed"
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val shutdown = ShutdownSequence(
            scope, bus, journal, SettingsHolder(testSettings()), { steps += "observer flushed" }, { steps += "phase $it" },
            stopMemory = { steps += "memory stopped" }, stopMechanics = { steps += "mechanics stopped" }, stopForge = { steps += "forge stopped" },
            stopDashboard = { steps += "dashboard stopped" },
        )

        shutdown.stopInGame()
        shutdown.flush()
        assertEquals(listOf("phase STOPPING", "dashboard stopped", "mechanics stopped", "forge stopped", "observer flushed"), steps.toList())
        steps.clear()

        val thread = Thread({
            shutdown.stopWaiting()
            shutdown.close()
        }, "restart")
        thread.start()
        thread.join(10_000)
        assertEquals(listOf("memory stopped", "journal closed", "phase STOPPED"), steps.toList())
        assertNull(bus.publish(SampleEvents.all.first()), "the bus is closed")
        assertTrue(scope.launch { }.isCancelled, "the work in flight is cancelled")

        steps.clear()
        shutdown.begin()
        assertTrue(steps.isEmpty(), "each half runs once: $steps")
    }

}
