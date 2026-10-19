package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.agent.GatewayError
import io.github.clemsix6.narrator2.config.DirectorConfig
import io.github.clemsix6.narrator2.config.OperatorChannelMode
import io.github.clemsix6.narrator2.director.CueKind
import io.github.clemsix6.narrator2.director.DirectorControl
import io.github.clemsix6.narrator2.director.DirectorHarness
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.director.DirectorState
import io.github.clemsix6.narrator2.director.DirectorStatus
import io.github.clemsix6.narrator2.event.LeaveReason
import io.github.clemsix6.narrator2.event.PlayerLeft
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.event.PositionSampled
import io.github.clemsix6.narrator2.event.SampleReason
import io.github.clemsix6.narrator2.journal.IdKind
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.observer.LivePlayer
import io.github.clemsix6.narrator2.observer.PlayerStates
import io.github.clemsix6.narrator2.runtime.Phase
import io.github.clemsix6.narrator2.testing.FakeMemoryState
import io.github.clemsix6.narrator2.testing.FakeMemoryStore
import io.github.clemsix6.narrator2.testing.LogCapture
import io.github.clemsix6.narrator2.testing.MechanicsSamples
import io.github.clemsix6.narrator2.testing.MemorySamples
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.trace.ConversationRecord
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The memory's scheduler in virtual time (M3 §6.6, §7.1, §8.1, §10.5, §14), with fake runners and data. */
class MemoryJobsTest {
    private val bartok = MemorySamples.bartok
    private val mimosa = MemorySamples.mimosa

    // ── Batches and naps (§7.1) ─────────────────────────────────────────────────────────────────────

    @Test
    fun `a batch runs every 10 minutes, only when the Narrator spoke or acted since the last one`() = runTest {
        val h = Harness(this)
        h.openChapter("director-c1", START - Duration.ofMinutes(1))
        h.online(bartok)
        h.jobs.start()
        h.advance(Duration.ZERO)
        h.data.activity += START + Duration.ofMinutes(3)

        h.advance(Duration.ofMinutes(10))
        h.advance(Duration.ofMinutes(10))
        h.data.activity += START + Duration.ofMinutes(25)
        h.advance(Duration.ofMinutes(10))
        h.advance(Duration.ofMinutes(2))

        assertEquals(
            listOf(
                "batch director-c1 ${START - Duration.ofMinutes(1)}..${START + Duration.ofMinutes(10) - LAG} at +10m",
                "batch director-c1 ${START + Duration.ofMinutes(10) - LAG}..${START + Duration.ofMinutes(30) - LAG} at +30m",
            ),
            h.runner.calls,
        )
        val batches = h.data.runs(RunKind.BATCH)
        assertEquals(listOf(RunStatus.DONE, RunStatus.DONE), batches.map { it.status })
        assertEquals(listOf(RunTrigger.TIMER, RunTrigger.TIMER), batches.map { it.trigger })
        assertEquals(listOf("chronicler-${batches[0].runId}"), batches[0].conversations)
        assertEquals("ok", batches[0].report)
        assertEquals(START + Duration.ofMinutes(12), batches[0].endedAt)
        assertEquals(listOf("batch", "batch"), h.decisions())
    }

    @Test
    fun `a batch window that fails twice from the same start is passed, with a warning and a decision, and the next batch starts after it`() = runTest {
        val h = Harness(this)
        h.openChapter("director-c1", START - Duration.ofMinutes(1))
        h.online(bartok)
        repeat(2) { h.runner.outcomes += RunOutcome(RunStatus.FAILED, "refused by the safety filter (category cyber)", 0, 0, listOf("chronicler-$it")) }
        h.jobs.start()
        h.advance(Duration.ZERO)
        h.data.activity += START + Duration.ofMinutes(3)
        h.data.activity += START + Duration.ofMinutes(25)

        LogCapture().use { logs ->
            h.advance(Duration.ofMinutes(10))
            h.advance(Duration.ofMinutes(10))
            h.advance(Duration.ofMinutes(10))
            h.advance(Duration.ofMinutes(2))
            assertTrue(logs.messages.any { it.startsWith("[Memory] window_passed run ") }, logs.text)
        }

        val from = START - Duration.ofMinutes(1)
        assertEquals(
            listOf(
                "batch director-c1 $from..${START + Duration.ofMinutes(10) - LAG} at +10m",
                "batch director-c1 $from..${START + Duration.ofMinutes(20) - LAG} at +20m",
                "batch director-c1 ${START + Duration.ofMinutes(20) - LAG}..${START + Duration.ofMinutes(30) - LAG} at +30m",
            ),
            h.runner.calls,
            "the second failure from 19:59 passes the window: the third batch starts where it ended",
        )
        val batches = h.data.runs(RunKind.BATCH)
        assertEquals(listOf(RunStatus.FAILED, RunStatus.FAILED, RunStatus.DONE), batches.map { it.status })
        assertEquals("refused by the safety filter (category cyber)", batches[0].error, "a first failure is tried again")
        assertEquals(
            "passed: the window 19:59:00–20:19:55 failed twice, the next batch starts after it: refused by the safety filter (category cyber)",
            batches[1].error,
        )
        assertEquals(listOf("batch", "window_passed", "batch", "batch"), h.decisions())
        val passed = h.services.traces.decisions.single { it.decision == "window_passed" }
        assertEquals("run ${batches[1].runId}", passed.subject)
        assertEquals(
            "the batch window 19:59:00–20:19:55 of director-c1 failed twice: passed, the next batch starts at 20:19:55: " +
                "refused by the safety filter (category cyber)",
            passed.reason,
        )
    }

    @Test
    fun `a closed chapter gets its nap, its waiting batch is dropped, and an operator pause holds the model jobs but not the code`() = runTest {
        val h = Harness(this)
        val chapter = h.openChapter("director-c1", START - Duration.ofMinutes(1))
        h.online(bartok)
        val promise = h.store.addCanon(CanonDraft(CanonKind.PROMISE, "Le Narrateur a promis.", players = listOf(mimosa.uuid), dueAt = START + Duration.ofMinutes(5)), DIRECTOR).row.ref
        h.jobs.start()
        h.jobs.pause("Clem")
        h.data.activity += START + Duration.ofMinutes(3)
        h.advance(Duration.ofMinutes(10))
        assertEquals(listOf(RunKind.BATCH), h.jobs.status().queued)
        assertTrue(h.jobs.status().paused)
        assertEquals(listOf(promise), h.director.signals.filterIsInstance<DirectorSignal.MemoryDue>().map { it.ref }, "due times go on")

        val closed = chapter.copy(endedAt = START + Duration.ofMinutes(10), endReason = ConversationRecord.EndReason.QUIET_LIMIT)
        h.jobs.chapterClosed(closed)
        h.jobs.chapterClosed(closed)
        h.director.conversationId = null
        assertEquals(listOf(RunKind.NAP), h.jobs.status().queued)
        h.advance(Duration.ofMinutes(5))
        assertEquals(emptyList(), h.runner.calls)

        h.jobs.resume("Clem")
        h.advance(Duration.ZERO)
        h.advance(Duration.ofMinutes(5))

        assertEquals(listOf("nap director-c1 at +15m"), h.runner.calls)
        val nap = h.data.runs(RunKind.NAP).single()
        assertEquals(RunTrigger.CHAPTER_CLOSED, nap.trigger)
        assertEquals(RunStatus.DONE, nap.status)
        assertEquals(START - Duration.ofMinutes(1), nap.windowFrom)
        assertEquals(START + Duration.ofMinutes(10), nap.windowTo)
        assertEquals("director-c1", nap.chapter)
        assertEquals(listOf("paused", "due", "resumed", "nap"), h.decisions())
    }

    @Test
    fun `the catch-up naps the chapters closed without a nap, 30 seconds after the server runs, and runs a crash left open are interrupted`() = runTest {
        val h = Harness(this)
        h.services.phase = Phase.STARTING
        h.data.chapters += record("director-c1", 1, START - Duration.ofHours(3), START - Duration.ofHours(2), ConversationRecord.EndReason.SHUTDOWN)
        h.data.chapters += record("director-c2", 2, START - Duration.ofHours(2), START - Duration.ofHours(1), ConversationRecord.EndReason.QUIET_LIMIT)
        h.data.runs[900] = run(900, RunKind.NAP, RunStatus.DONE, START - Duration.ofMinutes(50), "director-c2")
        h.data.runs[901] = run(901, RunKind.BATCH, RunStatus.RUNNING, START - Duration.ofMinutes(10), "director-c2")
        h.jobs.start()
        h.advance(Duration.ofMinutes(2))
        assertEquals(RunStatus.INTERRUPTED, h.data.runs.getValue(901).status)
        assertEquals(emptyList(), h.runner.calls, "nothing runs before the server does")

        h.services.phase = Phase.RUNNING
        h.advance(Duration.ofSeconds(30))
        assertEquals(emptyList(), h.runner.calls)
        h.advance(Duration.ofSeconds(30))

        assertEquals(listOf("nap director-c1 at +3m"), h.runner.calls)
        assertEquals(RunTrigger.STARTUP, h.data.runs.values.single { it.kind == RunKind.NAP && it.chapter == "director-c1" }.trigger)
    }

    @Test
    fun `the catch-up naps only the chapters closed since the memory first started less catchUpDays, and tells how many it never will`() = runTest {
        val h = Harness(this)
        val quiet = ConversationRecord.EndReason.QUIET_LIMIT
        h.data.chapters += record("director-c1", 1, START - Duration.ofDays(4), START - Duration.ofDays(3), quiet)
        h.data.chapters += record("director-c2", 2, START - Duration.ofHours(25), START - Duration.ofDays(1), quiet)
        h.data.chapters += record("director-c3", 3, START - Duration.ofHours(2), START - Duration.ofHours(1), ConversationRecord.EndReason.SHUTDOWN)
        h.runner.outcomes += RunOutcome(RunStatus.FAILED, "no end within 10m", 0, 0, listOf("chronicler-x"))
        h.online(bartok) // no dream in between

        LogCapture().use { logs ->
            h.jobs.start()
            h.advance(Duration.ofMinutes(33))
            assertTrue(
                logs.messages.any {
                    it == "[Memory] the memory starts on this world: the chapters closed since 2026-10-01 20:00 get their nap (memory.catchUpDays = 2); " +
                        "the 1 older one(s) never will"
                },
                logs.text,
            )
        }

        assertEquals(START.toEpochMilli().toString(), h.state.values[MemoryJobs.SINCE_KEY])
        assertEquals(listOf("nap director-c2 at +30s", "nap director-c3 at +2m30s", "nap director-c2 at +30m"), h.runner.calls)
        assertEquals(
            listOf(RunTrigger.STARTUP, RunTrigger.STARTUP, RunTrigger.CATCH_UP),
            h.data.runs(RunKind.NAP).map { it.trigger },
            "the catch-up after the start, then the one every 30 minutes (the failed nap of chapter 2 tried again)",
        )

        // A later start keeps the first one's date, and says nothing more: chapter 1 stays out.
        h.jobs.stop()
        h.advance(Duration.ofHours(1))
        LogCapture().use { logs ->
            h.newJobs().start()
            h.advance(Duration.ofMinutes(1))
            assertTrue(logs.messages.none { "starts on this world" in it }, logs.text)
        }
        assertEquals(START.toEpochMilli().toString(), h.state.values[MemoryJobs.SINCE_KEY])
        assertEquals(3, h.runner.calls.size)
    }

    @Test
    fun `an operator asks for the Chronicler, then for a dream, never two dreams`() = runTest {
        val h = Harness(this)
        h.openChapter("director-c2", START)
        h.data.chapters += record("director-c1", 1, START - Duration.ofHours(1), START - Duration.ofMinutes(1), ConversationRecord.EndReason.QUIET_LIMIT)
        h.online(bartok)
        val jobs = h.jobs
        assertTrue(jobs.requestChronicle("Clem").isFailure, "not started")
        jobs.start()
        h.runner.took = Duration.ofMinutes(30)
        h.advance(Duration.ZERO) // the status cache knows the chapter to nap

        val first = jobs.requestChronicle("Clem").getOrThrow()
        h.advance(Duration.ZERO)
        assertEquals(RunKind.NAP, jobs.status().running?.kind)
        assertEquals(listOf(RunKind.BATCH), jobs.status().queued)
        assertEquals(first, jobs.status().running?.runId)
        assertTrue(jobs.requestChronicle("Clem").isFailure, "everything is queued already")

        val dream = jobs.requestDream("Clem").getOrThrow()
        val again = jobs.requestDream("Clem")
        assertTrue(again.exceptionOrNull()!!.message!!.contains("a dream is already queued (run $dream)"))
        h.advance(Duration.ofMinutes(61))
        assertEquals(RunKind.DREAM, jobs.status().running?.kind)
        assertTrue(jobs.requestDream("Clem").exceptionOrNull()!!.message!!.contains("already running"))
        h.advance(Duration.ofHours(3))

        assertEquals(
            listOf("nap director-c1 at +0s", "batch director-c2 $START..${START + Duration.ofMinutes(30) - LAG} at +30m", "dream from 1 at +1h00m"),
            h.runner.calls,
        )
        assertEquals(listOf(RunTrigger.OPERATOR), h.data.runs(RunKind.DREAM).map { it.trigger })
        h.services.phase = Phase.STOPPING
        assertEquals("the server is stopping: no memory job can start", jobs.requestDream("Clem").exceptionOrNull()?.message)
    }

    // ── Dreams (§8.1) ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a dream comes after 20 minutes of empty server once a nap was done, and not again before another nap`() = runTest {
        val h = Harness(this)
        h.data.runs[900] = run(900, RunKind.NAP, RunStatus.DONE, START - Duration.ofHours(1))
        h.jobs.start()
        h.advance(Duration.ofMinutes(19))
        assertEquals(emptyList(), h.runner.calls)
        assertEquals(START + Duration.ofMinutes(20), h.jobs.status().nextDreamAt)

        h.advance(Duration.ofMinutes(3))
        assertEquals(listOf("dream from 1 at +20m"), h.runner.calls)
        val dream = h.data.runs(RunKind.DREAM).single()
        assertEquals(RunTrigger.SERVER_EMPTY, dream.trigger)
        assertEquals(RunStatus.DONE, dream.status)
        assertEquals("", h.state.values[MemoryJobs.DREAM_PENDING_KEY])
        assertEquals(listOf(listOf(bartok.uuid, mimosa.uuid) to "dream"), h.stats, "the stats of the players active since the last dream, first")
        assertEquals(listOf(Instant.EPOCH), h.activeSince)

        h.advance(Duration.ofHours(30))
        assertEquals(1, h.runner.calls.size, "no nap since the last dream")
        assertNull(h.jobs.status().nextDreamAt)

        h.data.runs[901] = run(901, RunKind.NAP, RunStatus.DONE, h.clock.instant())
        h.advance(Duration.ofSeconds(90))
        assertEquals(2, h.runner.calls.size)
    }

    @Test
    fun `a daily dream comes 24 hours after the last one even with players online`() = runTest {
        val h = Harness(this)
        h.online(bartok)
        h.state.values[MemoryJobs.DREAM_END_KEY] = (START - Duration.ofHours(23)).toEpochMilli().toString()
        h.data.runs[900] = run(900, RunKind.NAP, RunStatus.DONE, START - Duration.ofHours(2))
        h.jobs.start()
        h.advance(Duration.ofMinutes(59))
        assertEquals(emptyList(), h.runner.calls)
        h.advance(Duration.ofMinutes(1))
        assertEquals(listOf("dream from 1 at +1h00m"), h.runner.calls)
        assertEquals(RunTrigger.DAILY, h.data.runs(RunKind.DREAM).single().trigger)
    }

    @Test
    fun `a dream stopped with the server is cancelled and resumes at its step after a restart`() = runTest {
        val h = Harness(this)
        h.data.runs[900] = run(900, RunKind.NAP, RunStatus.DONE, START - Duration.ofHours(1))
        h.runner.onDream = { runId, _ -> h.state.setState(MemoryJobs.stepKey(runId), "2") } // the Dreamer saves each step
        h.runner.took = Duration.ofHours(2)
        h.jobs.start()
        val first = h.jobs.requestDream("Clem").getOrThrow()
        h.advance(Duration.ofMinutes(10))
        h.jobs.stop()

        val cancelled = h.data.runs.getValue(first)
        assertEquals(RunStatus.CANCELLED, cancelled.status)
        assertEquals("the server stopped", cancelled.error)
        assertEquals(first.toString(), h.state.values[MemoryJobs.DREAM_PENDING_KEY])
        assertEquals("2", h.state.values[MemoryJobs.stepKey(first)], "kept to resume")
        assertTrue(h.saves > 0, "the clock is saved at the stop")

        h.runner.onDream = { _, _ -> }
        h.runner.took = Duration.ofMinutes(5)
        val restarted = h.newJobs()
        restarted.start()
        val second = restarted.requestDream("Clem").getOrThrow()
        h.advance(Duration.ofMinutes(10))

        assertEquals(listOf("dream from 1 at +0s", "dream from 3 at +10m"), h.runner.calls)
        assertEquals(1, h.stats.count { it.second == "dream" }, "a resumed dream does not compute the stats again")
        assertEquals(RunStatus.DONE, h.data.runs.getValue(second).status)
        assertEquals("", h.state.values[MemoryJobs.DREAM_PENDING_KEY])
        assertEquals(emptyList(), h.state.values.keys.filter { it.startsWith("dream:") && it.endsWith(":step") }, "no step is kept once the dream is over")
    }

    @Test
    fun `a dream after an empty server waits dreamMinHours after the last one, an operator's dream does not`() = runTest {
        val h = Harness(this)
        h.state.values[MemoryJobs.DREAM_END_KEY] = (START - Duration.ofHours(1)).toEpochMilli().toString()
        h.data.runs[900] = run(900, RunKind.NAP, RunStatus.DONE, START - Duration.ofMinutes(30))
        h.jobs.start()
        h.advance(Duration.ofMinutes(30))
        assertEquals(emptyList(), h.runner.calls, "the server is empty for 30 minutes, but the last dream ended an hour before")
        assertEquals(START + Duration.ofHours(5), h.jobs.status().nextDreamAt)

        h.advance(Duration.ofMinutes(269))
        assertEquals(emptyList(), h.runner.calls)
        h.advance(Duration.ofMinutes(1))
        assertEquals(listOf("dream from 1 at +5h00m"), h.runner.calls, "6 hours after the end of the last dream")
        assertEquals(RunTrigger.SERVER_EMPTY, h.data.runs(RunKind.DREAM).single().trigger)

        h.advance(Duration.ofMinutes(5))
        h.data.runs[901] = run(901, RunKind.NAP, RunStatus.DONE, h.clock.instant())
        h.jobs.requestDream("Clem").getOrThrow()
        h.advance(Duration.ZERO)
        assertEquals(listOf("dream from 1 at +5h00m", "dream from 1 at +5h05m"), h.runner.calls, "an operator's dream does not wait")
    }

    @Test
    fun `the stop saves the clock first, waits 5 seconds at most for a job that holds on, and a chapter closed after it queues nothing`() = runTest {
        val h = Harness(this)
        h.runner.holdsOn = true
        h.runner.took = Duration.ofHours(1)
        h.jobs.start()
        h.jobs.requestDream("Clem").getOrThrow()
        h.advance(Duration.ofMinutes(1))
        assertEquals(RunKind.DREAM, h.jobs.status().running?.kind)

        val stopAt = testScheduler.currentTime
        h.jobs.stop()

        assertEquals(MemoryJobs.STOP_WAIT_MS, testScheduler.currentTime - stopAt, "the job that does not let go is left behind")
        assertEquals(START.toEpochMilli() + stopAt, h.savedAt.last(), "saved when the stop began, before waiting for the jobs")
        val next = h.services.trace.nextId(IdKind.MEMORY_RUN)
        h.jobs.chapterClosed(record("director-c1", 1, START, START + Duration.ofMinutes(1), ConversationRecord.EndReason.SHUTDOWN))
        assertEquals(emptyList(), h.jobs.status().queued)
        assertEquals(next + 1, h.services.trace.nextId(IdKind.MEMORY_RUN), "no run id drawn for a nap that never runs")
    }

    // ── The gateway's budget (§7.1) ─────────────────────────────────────────────────────────────────

    @Test
    fun `a 402 holds every model job until the budget resets, then the nap and the dream go on from their step`() = runTest {
        val h = Harness(this)
        val reset = START + Duration.ofHours(1)
        h.runner.outcomes += RunOutcome(RunStatus.FAILED, "budget", 0, 0, listOf("dreamer-a"), lastStep = 3, budget = GatewayError.Budget(reset))
        h.runner.outcomes += RunOutcome(RunStatus.FAILED, "budget", 0, 0, listOf("chronicler-b"), budget = GatewayError.Budget(null))
        h.jobs.start()
        val dream = h.jobs.requestDream("Clem").getOrThrow()
        h.advance(Duration.ofMinutes(2))
        assertEquals(reset, h.jobs.status().blockedUntil)
        val failed = h.data.runs.getValue(dream)
        assertEquals(RunStatus.FAILED, failed.status)
        assertEquals("budget: the LLMGW budget is spent until 21:00", failed.error)

        h.jobs.chapterClosed(record("director-c1", 1, START - Duration.ofHours(1), START, ConversationRecord.EndReason.QUIET_LIMIT))
        h.advance(Duration.ofMinutes(57))
        assertEquals(listOf(RunKind.NAP, RunKind.DREAM), h.jobs.status().queued)
        assertEquals(listOf("dream from 1 at +0s"), h.runner.calls)

        h.advance(Duration.ofMinutes(3)) // the reset passed: the nap hits a 402 without reset time
        assertEquals(listOf("dream from 1 at +0s", "nap director-c1 at +1h00m"), h.runner.calls)
        assertEquals(START + Duration.ofMinutes(62) + MemoryJobs.BUDGET_WAIT, h.jobs.status().blockedUntil)
        assertEquals("budget: the LLMGW budget is spent", h.data.runs(RunKind.NAP).single().error)

        h.advance(Duration.ofMinutes(30))
        assertEquals(
            listOf("dream from 1 at +0s", "nap director-c1 at +1h00m", "nap director-c1 at +1h17m", "dream from 4 at +1h19m"),
            h.runner.calls,
        )
        assertEquals(listOf("dream", "budget", "nap", "budget", "nap", "dream"), h.decisions())
    }

    // ── Due times (§6.6) ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a due commitment is told to the Director once, at its due time, and never while it is disabled`() = runTest {
        val h = Harness(this)
        h.store.names[mimosa.uuid] = mimosa.name
        val due = START + Duration.ofMinutes(5)
        val ref = h.store.addCanon(
            CanonDraft(CanonKind.PROMISE, "Le Narrateur a promis de rendre ses bottes.", "je te rends tes ‹bottes›", listOf(mimosa.uuid), dueAt = due),
            DIRECTOR,
        ).row.ref
        h.store.addCanon(CanonDraft(CanonKind.PROMISE, "Plus tard.", dueAt = START + Duration.ofHours(5)), DIRECTOR)
        h.director.state = DirectorState.DISABLED
        h.jobs.start()
        h.advance(Duration.ofMinutes(6))
        assertEquals(emptyList(), h.director.signals)
        assertEquals(emptyMap(), h.data.signaled)

        h.director.state = DirectorState.PAUSED
        h.advance(Duration.ofSeconds(30))
        h.advance(Duration.ofMinutes(30))

        assertEquals<List<DirectorSignal>>(
            listOf(DirectorSignal.MemoryDue(ref, "$ref promise → Mimosa, set 6m30s ago: « je te rends tes ‹bottes› »", due.toEpochMilli())),
            h.director.signals,
        )
        assertEquals(mapOf(ref to START + Duration.ofSeconds(390)), h.data.signaled)
        assertEquals(listOf("due"), h.decisions())
    }

    // ── Cues (§10.5): found by the reminiscence (ReminiscenceTest), posted here ─────────────────────

    @Test
    fun `each position sample is asked for a place return, whose cue is posted, only while the Director listens to cues`() = runTest {
        val h = Harness(this)
        val here = Pos("minecraft:overworld", 100, 64, -300)
        val cue = DirectorSignal.MemoryCue(CueKind.RETURN, Ref(RefKind.MEMORY, 1), bartok, "Bartok is where M-1 happened (4w ago, ★7): « … »", START.toEpochMilli())
        h.online(bartok)
        h.online(mimosa)
        h.services.phase = Phase.STARTING
        h.jobs.start()
        h.advance(Duration.ZERO)
        h.sample(bartok, here)
        assertEquals(emptyList(), h.asked, "nothing before the server runs")

        h.services.phase = Phase.RUNNING
        h.returns += cue
        h.sample(bartok, here)
        h.sample(mimosa, Pos("minecraft:the_nether", 5, 70, 8))
        for (muted in listOf(DirectorState.PAUSED, DirectorState.BLOCKED, DirectorState.DISABLED, DirectorState.STOPPED)) {
            h.director.state = muted
            h.sample(bartok, here)
        }

        assertEquals(
            listOf(
                "return Bartok at minecraft:overworld 100,64,-300 (online Bartok+Mimosa) at +0s",
                "return Mimosa at minecraft:the_nether 5,70,8 (online Bartok+Mimosa) at +0s",
            ),
            h.asked,
        )
        assertEquals(listOf<DirectorSignal>(cue), h.director.signals, "the cue as the reminiscence wrote it; nothing for a null")
        assertEquals(emptyList(), h.decisions(), "the reminiscence records its cue decision itself")
    }

    @Test
    fun `the anniversary is asked at each pass with someone online while the Director listens to cues, and its cue is posted`() = runTest {
        val h = Harness(this)
        val cue = DirectorSignal.MemoryCue(CueKind.ANNIVERSARY, Ref(RefKind.MEMORY, 3), bartok, "M-3, 2 weeks ago today (★10): « … »", START.toEpochMilli())
        h.jobs.start()
        h.advance(Duration.ofMinutes(5))
        h.online(bartok)
        h.director.state = DirectorState.PAUSED
        h.advance(Duration.ofMinutes(1))
        assertEquals(emptyList(), h.asked, "nobody online, then a muted Director: it would drop the cue")

        h.director.state = DirectorState.IDLE
        h.anniversaries += cue
        h.advance(Duration.ofMinutes(1))

        assertEquals(listOf("anniversary (online Bartok) at +390s", "anniversary (online Bartok) at +420s"), h.asked)
        assertEquals(listOf<DirectorSignal>(cue), h.director.signals)
        assertEquals(emptyList(), h.decisions(), "the reminiscence records its cue decision itself")
        assertNull(h.state.values[Reminiscence.ANNIVERSARY_STATE], "the reminiscence keeps the day, not the jobs")
    }

    // ── Code jobs ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the clock, the housekeeping, the stats and the situation run on their own timers`() = runTest {
        val h = Harness(this)
        h.store.names[mimosa.uuid] = mimosa.name
        h.tidyReport = HygieneReport(expired = listOf(Ref(RefKind.CANON, 4)))
        val promise = h.store.addCanon(CanonDraft(CanonKind.PROMISE, "Des bottes.", players = listOf(mimosa.uuid), dueAt = START + Duration.ofMinutes(40)), DIRECTOR)
        val challenge = h.store.addCanon(CanonDraft(CanonKind.CHALLENGE, "Un œuf de tortue.", dueAt = START + Duration.ofMinutes(50)), DIRECTOR)
        h.store.addCanon(CanonDraft(CanonKind.PROMISE, "Plus tard.", dueAt = START + Duration.ofHours(2)), DIRECTOR)
        h.jobs.start()
        h.advance(Duration.ofMinutes(5))
        assertEquals(emptyList(), h.stats, "nobody online")
        h.online(bartok)
        h.advance(Duration.ofMinutes(25))

        assertEquals(61, h.ticks)
        assertEquals(7, h.saves)
        assertEquals(4, h.tidied.size)
        val hygiene = h.data.runs(RunKind.HYGIENE)
        assertEquals(h.tidied, hygiene.map { it.runId })
        assertEquals("expired 1, worn 0, archived 0, notes done 0, merge candidates 0", hygiene.first().report)
        assertEquals(List(3) { listOf(bartok.uuid) to "timer" }, h.stats)
        assertEquals(
            listOf(
                "Favor: Bartok -31",
                "Due soon: ${promise.row.ref} promise → Mimosa in 10m · ${challenge.row.ref} challenge → all ends in 20m",
                "Attention: Mimosa has had nothing from you for 50m of play",
            ),
            h.jobs.situationLines(),
        )
        assertEquals(3_600, h.jobs.status().worldPlaySeconds)

        h.services.bus.publish(PlayerLeft(bartok, Pos("minecraft:overworld", 0, 64, 0), 1, 1_500, LeaveReason.LEAVE))
        h.advance(Duration.ZERO)
        assertEquals(listOf(bartok.uuid) to "left", h.stats.last())
    }

    @Test
    fun `departures waiting together are computed in one refresh, and the samples between them are still asked`() = runTest {
        val h = Harness(this)
        val here = Pos("minecraft:overworld", 100, 64, -300)
        h.online(bartok)
        h.jobs.start()
        h.advance(Duration.ZERO)
        h.stats.clear()
        h.asked.clear()

        val gone = Pos("minecraft:overworld", 0, 64, 0)
        h.services.bus.publish(PlayerLeft(mimosa, gone, 1, 1_500, LeaveReason.LEAVE))
        h.services.bus.publish(PositionSampled(bartok, here, "minecraft:plains", null, false, 15, 0, 20.0, 20, null, SampleReason.MOVED))
        h.services.bus.publish(PlayerLeft(MechanicsSamples.josiane, gone, 1, 1_500, LeaveReason.LEAVE))
        h.advance(Duration.ZERO)

        assertEquals(listOf(listOf(mimosa.uuid, MechanicsSamples.josiane.uuid) to "left"), h.stats, "one refresh for both departures")
        assertEquals(listOf("return Bartok at minecraft:overworld 100,64,-300 (online Bartok) at +0s"), h.asked.filter { it.startsWith("return") }, "the sample between them is asked")
    }

    @Test
    fun `the housekeeping writes its run when it only closed notes, and none when it changed nothing`() = runTest {
        val h = Harness(this)
        h.jobs.start()
        h.advance(Duration.ZERO)
        assertEquals(1, h.tidied.size)
        assertEquals(emptyList(), h.data.runs(RunKind.HYGIENE), "nothing changed: no row")

        h.tidyReport = HygieneReport(notesDone = listOf(Ref(RefKind.NOTE, 9)))
        h.advance(Duration.ofMinutes(10))

        val run = h.data.runs(RunKind.HYGIENE).single()
        assertEquals(h.tidied.last(), run.runId, "the run id its memory_log rows carry")
        assertEquals(RunStatus.DONE, run.status)
        assertEquals("expired 0, worn 0, archived 0, notes done 1, merge candidates 0", run.report)
    }

    // ── Harness ─────────────────────────────────────────────────────────────────────────────────────

    private inner class Harness(private val test: TestScope) {
        val clock = DirectorHarness.SchedulerClock(test.testScheduler, START)
        val director = ChapterDirector()
        val players = PlayerStates()
        val store = FakeMemoryStore(clock).apply { names[bartok.uuid] = bartok.name }
        val state = FakeMemoryState()
        val data = FakeJobsData(store)
        val runner = FakeRunner(clock)
        val services = TestServices(clock = clock, scope = test.backgroundScope, director = director, players = players)
        var world = 3_600L
        var ticks = 0
        var saves = 0
        val savedAt = mutableListOf<Long>()
        val tidied = mutableListOf<Long>()
        val stats = mutableListOf<Pair<List<UUID>, String>>()
        val activeSince = mutableListOf<Instant>()
        var tidyReport = HygieneReport()

        /** What the reminiscence was asked (place returns, anniversaries), and the cues it answers next (null otherwise). */
        val asked = mutableListOf<String>()
        val returns = ArrayDeque<DirectorSignal.MemoryCue>()
        val anniversaries = ArrayDeque<DirectorSignal.MemoryCue>()
        private val hooks = JobHooks(
            tickClock = { ticks++ },
            saveClock = { saves++; savedAt += it },
            worldPlaySeconds = { world },
            tidy = { _, runId -> tidied += runId; tidyReport },
            refreshStats = { players, why -> stats += players.toList() to why },
            activeSince = { since -> activeSince += since; listOf(bartok.uuid, mimosa.uuid) },
            favorLines = { _, _ -> listOf("Favor: Bartok -31", "Attention: Mimosa has had nothing from you for 50m of play") },
            placeReturn = { player, pos, online, now ->
                asked += "return ${player.name} at ${pos.dim} ${pos.x},${pos.y},${pos.z} ${names(online)} at ${since(now)}"
                returns.removeFirstOrNull()
            },
            anniversary = { now, online ->
                asked += "anniversary ${names(online.map { it.uuid })} at ${since(now)}"
                anniversaries.removeFirstOrNull()
            },
        )
        val jobs = newJobs()

        fun newJobs() = MemoryJobs(services, store, state, data, runner, runner, hooks)

        private fun names(online: Collection<UUID>) = "(online ${listOf(bartok, mimosa).filter { it.uuid in online }.joinToString("+") { it.name }})"

        private fun since(now: Long) = "+${(now - START.toEpochMilli()) / 1_000}s"

        /** Moves virtual time by [duration], then runs what is due at that very moment. */
        fun advance(duration: Duration) {
            test.advanceTimeBy(duration.toMillis())
            test.runCurrent()
        }

        fun openChapter(id: String, startedAt: Instant): ConversationRecord {
            val chapter = record(id, id.substringAfter("-c").toInt(), startedAt, null, null)
            data.chapters += chapter
            director.conversationId = id
            return chapter
        }

        fun online(player: PlayerRef) = players.put(
            LivePlayer(
                player, 1, START.toEpochMilli(), false, "survival", Pos("minecraft:overworld", 0, 64, 0), null, null, false, 20f, 20f, 20,
                null, null, START.toEpochMilli(), false,
            ),
        )

        fun sample(player: PlayerRef, pos: Pos) {
            services.bus.publish(PositionSampled(player, pos, "minecraft:plains", null, false, 15, 0, 20.0, 20, null, SampleReason.MOVED))
            test.runCurrent()
        }

        fun decisions() = services.traces.decisions.map { it.decision }
    }

    /** A Director whose state and open chapter the test sets; it records the signals posted. */
    private class ChapterDirector : DirectorControl {
        val signals = CopyOnWriteArrayList<DirectorSignal>()

        @Volatile
        var state = DirectorState.IDLE

        @Volatile
        var conversationId: String? = null

        override fun post(signal: DirectorSignal) {
            signals += signal
        }

        override fun scheduleReminder(inSeconds: Int, note: String, caller: Caller): Result<Long> = Result.failure(UnsupportedOperationException())

        override fun status() = DirectorStatus(
            state, null, null, conversationId?.let { 1 }, conversationId, 0, 0, 0, null, OperatorChannelMode.SYSTEM_MESSAGE, emptyList(), 0,
            DirectorConfig().turnsPerHour,
        )

        override fun recentActivity(player: UUID, minutes: Int): String? = null
    }

    /**
     * The Chronicler and the Dreamer: each call takes [took] of virtual time (not cancellable when it
     * [holdsOn]), is written in [calls] with its time from [START], and ends with the next of [outcomes]
     * (done otherwise).
     */
    private class FakeRunner(private val clock: java.time.Clock) : ChronicleRunner, DreamRunner {
        val calls = CopyOnWriteArrayList<String>()
        val outcomes = ArrayDeque<RunOutcome>()
        var took: Duration = Duration.ofMinutes(2)
        var holdsOn = false
        var onDream: suspend (Long, Int) -> Unit = { _, _ -> }

        override suspend fun batch(runId: Long, chapter: String, from: Instant, to: Instant): RunOutcome {
            calls += "batch $chapter $from..$to at ${since()}"
            return end(runId, "chronicler")
        }

        override suspend fun nap(runId: Long, chapter: ConversationRecord): RunOutcome {
            calls += "nap ${chapter.conversationId} at ${since()}"
            return end(runId, "chronicler")
        }

        override suspend fun dream(runId: Long, fromStep: Int): RunOutcome {
            calls += "dream from $fromStep at ${since()}"
            onDream(runId, fromStep)
            return end(runId, "dreamer").let { if (it.status == RunStatus.DONE) it.copy(lastStep = 5) else it }
        }

        private suspend fun end(runId: Long, role: String): RunOutcome {
            if (holdsOn) withContext(NonCancellable) { delay(took.toMillis()) } else delay(took.toMillis())
            return outcomes.removeFirstOrNull() ?: RunOutcome(RunStatus.DONE, "ok", 1, 0, listOf("$role-$runId"))
        }

        private fun since() = io.github.clemsix6.narrator2.tool.ToolText.duration(Duration.between(START, clock.instant()).toMillis()).let { "+$it" }
    }

    /** [JobsData] in memory; due canon read from the fake store. */
    private class FakeJobsData(private val store: FakeMemoryStore) : JobsData {
        val runs = ConcurrentHashMap<Long, MemoryRun>()
        val chapters = CopyOnWriteArrayList<ConversationRecord>()
        val activity = CopyOnWriteArrayList<Instant>()
        val signaled = ConcurrentHashMap<Ref, Instant>()

        fun runs(kind: RunKind) = runs.values.filter { it.kind == kind }.sortedBy { it.runId }

        override suspend fun interruptRunning(now: Instant): Int {
            val open = runs.values.filter { it.status == RunStatus.RUNNING }
            open.forEach { runs[it.runId] = it.copy(status = RunStatus.INTERRUPTED, endedAt = now) }
            return open.size
        }

        override suspend fun insertRun(run: MemoryRun) {
            runs[run.runId] = run
        }

        override suspend fun finishRun(run: MemoryRun) {
            runs[run.runId] = run
        }

        override suspend fun lastRun(kind: RunKind): MemoryRun? =
            runs.values.filter { it.kind == kind }.maxWithOrNull(compareBy<MemoryRun> { it.startedAt }.thenBy { it.runId })

        override suspend fun batchEnd(chapter: String): Instant? = runs.values
            .filter { it.kind == RunKind.BATCH && it.chapter == chapter }
            .filter { it.status in setOf(RunStatus.DONE, RunStatus.SKIPPED) || it.status == RunStatus.FAILED && it.error?.startsWith(DuckJobsData.PASSED_PREFIX) == true }
            .mapNotNull { it.windowTo }.maxOrNull()

        override suspend fun batchFailedFrom(chapter: String, from: Instant, runId: Long): Boolean = runs.values.any {
            it.kind == RunKind.BATCH && it.chapter == chapter && it.windowFrom == from && it.runId != runId && it.status == RunStatus.FAILED &&
                it.error?.startsWith(DuckJobsData.BUDGET_PREFIX) != true
        }

        override suspend fun firstNapDone(after: Instant?): Instant? =
            runs.values.filter { it.kind == RunKind.NAP && it.status == RunStatus.DONE && it.endedAt!!.isAfter(after ?: Instant.EPOCH) }
                .minOfOrNull { it.endedAt!! }

        override suspend fun chaptersToNap(maxFailures: Int, limit: Int, closedSince: Instant): List<ConversationRecord> = chapters.filter { chapter ->
            val naps = runs.values.filter { it.kind == RunKind.NAP && it.chapter == chapter.conversationId }
            chapter.endedAt?.isBefore(closedSince) == false &&
                naps.none { it.status in setOf(RunStatus.DONE, RunStatus.SKIPPED, RunStatus.RUNNING) } &&
                naps.count { it.status == RunStatus.FAILED && it.error?.startsWith(DuckJobsData.BUDGET_PREFIX) != true } < maxFailures
        }.sortedBy { it.endedAt }.take(limit)

        override suspend fun chaptersNeverNapped(closedBefore: Instant): Int = chapters.count { chapter ->
            chapter.endedAt?.isBefore(closedBefore) == true &&
                runs.values.none { it.kind == RunKind.NAP && it.chapter == chapter.conversationId && it.status in setOf(RunStatus.DONE, RunStatus.SKIPPED) }
        }

        override suspend fun chapter(conversationId: String): ConversationRecord? = chapters.firstOrNull { it.conversationId == conversationId }

        override suspend fun narratorActive(chapter: String, from: Instant, to: Instant): Boolean = activity.any { it.isAfter(from) && !it.isAfter(to) }

        override suspend fun dueCanon(now: Instant): List<Ref> = store.rows.values.filterIsInstance<CanonEntry>()
            .filter { it.status == CanonStatus.ACTIVE && it.dueAt != null && !it.dueAt!!.isAfter(now) && !signaled.containsKey(it.ref) }
            .sortedBy { it.dueAt }.map { it.ref }

        override suspend fun markDueSignaled(ref: Ref, at: Instant) {
            signaled[ref] = at
        }

        override suspend fun refusals(since: Instant) = Refusals(0, 0)
    }

    companion object {
        /** Saturday 3 October 2026, 20:00 UTC: virtual time 0. */
        val START: Instant = Instant.parse("2026-10-03T20:00:00Z")
        val LAG: Duration = MemoryJobs.BATCH_LAG
        val DIRECTOR = Author(Source.DIRECTOR)

        fun record(id: String, number: Int, startedAt: Instant, endedAt: Instant?, reason: ConversationRecord.EndReason?) =
            ConversationRecord(id, Role.DIRECTOR, "Chapter $number", number, null, startedAt, endedAt, reason, null, null)

        fun run(id: Long, kind: RunKind, status: RunStatus, endedAt: Instant, chapter: String? = null) = MemoryRun(
            id, kind, RunTrigger.TIMER, status, endedAt - Duration.ofMinutes(2), endedAt.takeIf { status != RunStatus.RUNNING }, null, null,
            chapter, emptyList(), 0, 0, null, null, null, null,
        )
    }
}
