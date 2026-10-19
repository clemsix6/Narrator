package io.github.clemsix6.narrator2.runtime

import io.github.clemsix6.narrator2.event.EventBus
import io.github.clemsix6.narrator2.journal.DisabledJournal
import io.github.clemsix6.narrator2.journal.Journal
import io.github.clemsix6.narrator2.testing.LogCapture
import io.github.clemsix6.narrator2.testing.SampleEvents
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.testing.testConfig
import io.github.clemsix6.narrator2.testing.testSettings
import io.github.clemsix6.narrator2.llm.ClaudeClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The order and the time limits of the Narrator's stop (M1_M2 §2.4). */
class ShutdownSequenceTest {
    private val steps = CopyOnWriteArrayList<String>()
    private val clock = TestClock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bus = EventBus(clock, { 0L }, 1)
    private val claude = ClaudeClient(testConfig("http://127.0.0.1:9"), "unused-key")
    private val settings = SettingsHolder(testSettings(claude = claude))
    private val journal = RecordingJournal()

    /** Unblocks a journal or a job that a test holds on purpose. */
    private val release = CountDownLatch(1)

    @AfterEach
    fun cleanUp() {
        release.countDown()
        claude.close()
    }

    /** A Journal that records when it closes, whether the bus was already closed, and with which timeout. */
    private inner class RecordingJournal(private val blocks: Boolean = false) : Journal by DisabledJournal("test") {
        override fun close(timeout: Duration) {
            val busClosed = bus.publish(SampleEvents.all.first()) == null
            steps += "journal closed (timeout ${timeout.toMillis()} ms, bus closed: $busClosed)"
            if (blocks) release.await(30, TimeUnit.SECONDS)
        }
    }

    private fun sequence(
        journal: Journal = this.journal,
        flushObserver: () -> Unit = { steps += "observer flushed" },
        workTimeout: Duration = Duration.ofSeconds(5),
        journalTimeout: Duration = Duration.ofSeconds(10),
        journalGrace: Duration = Duration.ofSeconds(2),
        stopMemory: suspend () -> Unit = {},
        memoryTimeout: Duration = Duration.ofSeconds(5),
        stopMechanics: () -> Unit = {},
    ) = ShutdownSequence(
        scope, bus, journal, settings, flushObserver, { steps += "phase $it" }, workTimeout, journalTimeout, journalGrace, stopMemory, memoryTimeout,
        stopMechanics,
    )

    /** Starts a job that runs until it is cancelled, and waits until it runs. */
    private fun startWork(name: String) {
        val started = CompletableDeferred<Unit>()
        scope.launch {
            try {
                started.complete(Unit)
                awaitCancellation()
            } finally {
                steps += "$name cancelled"
            }
        }
        runBlocking { started.await() }
    }

    @Test
    fun `stopping cancels the work in flight, refuses new work and closes the Director's subscriptions only`() {
        startWork("director")
        startWork("worker")
        val salience = bus.subscribeAsync("salience", ShutdownSequence.DIRECTOR_OWNER, capacity = 8)
        val facts = bus.subscribeAsync("journal", "journal", capacity = 8)

        sequence().begin()

        assertEquals("phase STOPPING", steps.first())
        assertEquals(setOf("director cancelled", "worker cancelled"), steps.drop(1).toSet(), "both jobs ended before begin returned")
        assertTrue(scope.launch { steps += "late work ran" }.isCancelled, "no new work once the server stops")

        bus.publish(SampleEvents.all.first())
        assertTrue(facts.events.tryReceive().isSuccess, "facts still reach the Journal")
        assertTrue(salience.events.tryReceive().isClosed, "the Director's subscription is closed")
        assertFalse("late work ran" in steps)
    }

    @Test
    fun `stopping waits for work that ignores cancellation only up to its time limit`() {
        val started = CountDownLatch(1)
        scope.launch {
            withContext(NonCancellable) {
                started.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))

        val logs = LogCapture()
        val start = System.nanoTime()
        logs.use { sequence(workTimeout = Duration.ofMillis(100)).begin() }

        assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000, "begin returned without waiting for the stuck job")
        assertContains(logs.text, "1 of 1 background jobs still running")
    }

    @Test
    fun `the memory stops first, while the work in flight still runs, and is waited for`() {
        startWork("director")
        val stopping = sequence(
            stopMemory = {
                delay(200) // its running job's row and the play clock are written
                steps += "memory stopped"
            },
        )

        stopping.begin()

        assertEquals(listOf("phase STOPPING", "memory stopped", "director cancelled"), steps.toList())
    }

    @Test
    fun `the mechanics stop first, before the memory (M4 §4-6)`() {
        startWork("director")
        sequence(stopMechanics = { steps += "mechanics stopped" }, stopMemory = { steps += "memory stopped" }).begin()
        assertEquals(listOf("phase STOPPING", "mechanics stopped", "memory stopped", "director cancelled"), steps.toList())
    }

    @Test
    fun `mechanics that fail to stop stop nothing else`() {
        startWork("worker")
        val logs = LogCapture()
        logs.use { sequence(stopMechanics = { error("boom") }, stopMemory = { steps += "memory stopped" }).begin() }
        assertEquals(listOf("phase STOPPING", "memory stopped", "worker cancelled"), steps.toList())
        assertContains(logs.text, "stopping: could not stop the mechanics")
    }

    @Test
    fun `a memory that does not stop in time does not hold the server`() {
        startWork("director")
        val logs = LogCapture()
        val start = System.nanoTime()
        logs.use { sequence(stopMemory = { delay(30_000) }, memoryTimeout = Duration.ofMillis(100)).begin() }

        assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000, "begin returned without waiting for the memory")
        assertContains(logs.text, "the memory did not stop within")
        assertEquals(listOf("phase STOPPING", "director cancelled"), steps.toList(), "the rest of the stop goes on")
    }

    @Test
    fun `stopped flushes the Observer, then closes the bus, then the Journal with its timeout, then the clients`() {
        val sequence = sequence()
        sequence.begin()
        steps.clear()

        sequence.finish()

        assertEquals(
            listOf("observer flushed", "journal closed (timeout 10000 ms, bus closed: true)", "phase STOPPED"),
            steps,
        )
        assertTrue(claude.isClosed, "the client is closed")
        assertNull(bus.publish(SampleEvents.all.first()), "the bus is closed")
    }

    @Test
    fun `stopped without stopping first runs both steps`() {
        sequence().finish()
        assertEquals("phase STOPPING", steps.first())
        assertEquals("phase STOPPED", steps.last())
    }

    @Test
    fun `a Journal that overruns its timeout does not hold the server`() {
        val logs = LogCapture()
        val start = System.nanoTime()
        logs.use {
            sequence(journal = RecordingJournal(blocks = true), journalTimeout = Duration.ofMillis(100), journalGrace = Duration.ofMillis(100))
                .finish()
        }

        assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000, "finish returned without waiting for the Journal")
        assertEquals("phase STOPPED", steps.last())
        assertContains(logs.text, "stopped waiting for the Journal")
        assertTrue(claude.isClosed, "the clients are closed anyway")
    }

    @Test
    fun `a step that fails does not stop the next ones`() {
        val logs = LogCapture()
        logs.use { sequence(flushObserver = { error("broken aggregator") }).finish() }

        assertNotNull(steps.firstOrNull { it.startsWith("journal closed") }, "the Journal is closed")
        assertEquals("phase STOPPED", steps.last())
        assertContains(logs.text, "stopping: could not flush the Observer")
    }
}
