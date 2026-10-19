package io.github.clemsix6.narrator2.mechanics

import io.github.clemsix6.narrator2.config.MechanicsConfig
import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.journal.DisabledJournal
import io.github.clemsix6.narrator2.journal.Journal
import io.github.clemsix6.narrator2.mechanics.rule.RuleFixture
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.testing.FakeMemory
import io.github.clemsix6.narrator2.testing.LogCapture
import io.github.clemsix6.narrator2.testing.MechanicsSamples
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.testing.testSettings
import io.github.clemsix6.narrator2.tool.ToolStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The assembly of the mechanics engine (M4-23, M4 §4.1, §4.6): when `createMechanics` keeps it off and why, and what
 * the real [MechanicsService] hands to which part. The service runs here on a real rule book and a real canon
 * ([RuleFixture]), its state in memory, without a server; the GameTests (`MechanicsAssemblyGameTests`) run the
 * server's own engine.
 */
class MechanicsServiceTest {
    private val clock = TestClock()

    /** A Journal that says it is available, for the cases past that check. */
    private val journal: Journal = object : Journal by DisabledJournal("unit test", clock) {
        override val available = true
    }

    @Test
    fun `the engine stays off with its reason when the configuration, the Journal or the memory says so, or when it cannot start`() = runBlocking<Unit> {
        val off = NarratorConfig(mechanics = MechanicsConfig(enabled = false))
        val memoryOff = FakeMemory(clock).apply {
            available = false
            reason = "the Journal lacks the memory's schema"
        }
        val cases = listOf(
            TestServices(clock = clock, settings = testSettings(off), journal = journal) to "mechanics.enabled is false",
            TestServices(clock = clock, journal = DisabledJournal("the file is locked", clock)) to "the Journal is unavailable: the file is locked",
            TestServices(clock = clock, journal = journal, memory = memoryOff) to "the memory is unavailable: the Journal lacks the memory's schema",
            TestServices(clock = clock, journal = journal) to "it could not start: no Minecraft server in unit tests",
        )
        for ((services, reason) in cases) {
            LogCapture().use { logs ->
                val mechanics = createMechanics(services)
                assertIs<DisabledMechanics>(mechanics, reason)
                assertFalse(mechanics.available)
                assertEquals(reason, mechanics.unavailableReason)
                assertContains(logs.text, "the mechanics are off: $reason")
                val refused = assertFailsWith<MechanicRejected> { mechanics.challenges.start(MechanicsSamples.turtleEgg, MechanicsSamples.director) }
                assertEquals("DENIED (mechanics_off): the mechanics are unavailable: $reason", refused.toResult().text)
            }
        }
    }

    @Test
    fun `the service hands the rules to the book, every end to the duels, and its lines and its stop to the engine`() = runBlocking<Unit> {
        RuleFixture().use { f ->
            val service = MechanicsService(f.services, f.store, f.ids, tags = { _, _ -> emptySet() }, clock = f.clock)
            val unwired = f.guards.isDueling
            service.starting { service.engine.begin(f.clock.millis()) {} }
            try {
                assertNotSame(unwired, f.guards.isDueling, "once started, the guards ask the duels' part who duels")
                assertFalse(f.guards.isDueling(MechanicsSamples.bartok.uuid), "nobody duels")
                assertTrue(service.available)
                assertNull(service.unavailableReason)
                assertEquals(emptyList(), service.situationLines(f.clock.millis()))

                val law = service.rules.create(f.spec(LAW), f.caller)
                assertEquals(listOf(law.ref), service.rules.list().map { it.ref })
                assertEquals(listOf(law.ref.id), f.store.get().rules.map { it.id }, "the rule is in the engine's state")
                f.clock.advance(Duration.ofMillis(1_001))
                service.engine.beat(f.clock.millis(), {}, {})
                assertEquals(listOf("Rules: ${service.rules.list().single().line}"), service.situationLines(f.clock.millis()), "the rules' part is one of the engine's")

                // An end goes through the duels' part to the challenges' (a challenge's end as much as a duel's).
                val operator = Author(Source.OPERATOR, operator = "Clement")
                val refused = assertFailsWith<MechanicRejected> {
                    service.challenges.end(Ref(RefKind.CANON, 999), ChallengeOutcome.CANCELLED, emptyList(), "Le défi a été annulé par un opérateur.", operator)
                }
                assertEquals("Invalid arguments: C-999 is not one of your running challenges; running: none", refused.toResult().text)
                assertEquals(emptyList(), service.challenges.list())

                val wired = f.guards.isDueling
                service.stop()
                assertTrue(service.engine.stopped)
                assertEquals(emptyList(), service.situationLines(f.clock.millis()), "a stopped engine has nothing to say")
                assertNotSame(wired, f.guards.isDueling, "a stopped engine no longer answers who duels")
            } finally {
                service.stop()
            }
        }
    }

    @Test
    fun `the engine's acts reach the tools of the rules and the stakes, never sql, run_command nor delegate`() = runBlocking<Unit> {
        RuleFixture().use { f ->
            val service = MechanicsService(f.services, f.store, f.ids, tags = { _, _ -> emptySet() }, clock = f.clock)
            assertEquals(MechanicsService.TOOLS, service.actions.names)
            assertTrue(
                listOf("say", "explosion", "teleport", "mob_effect", "notify", "punish", "give_item", "apply_effect", "favor").all { it in service.actions.names },
                "the rules' tools and the stakes': ${service.actions.names}",
            )
            for (tool in listOf("sql", "run_command", "delegate", "remember", "start_challenge")) {
                val act = service.actions.open("fired", null, null)
                val refused = service.actions.run(act, tool, JsonObject(emptyMap()))
                assertEquals(ToolStatus.INVALID, refused.status, tool)
                assertEquals("Invalid arguments: unknown tool \"$tool\" for the engine", refused.text)
            }
        }
    }

    @Test
    fun `a start that fails stops the engine and puts the state back as it was, what ended while the server was down included`() = runBlocking<Unit> {
        RuleFixture().use { f ->
            // C-50 ended an hour ago, nobody made it: the start would take it out of the state and give its bet back.
            val ended = MechanicsSamples.runningChallenge.copy(endsAt = f.clock.millis() - 3_600_000)
            f.store.update { MechanicsState(challenges = listOf(ended)) }
            val before = f.store.get()
            val service = MechanicsService(f.services, f.store, f.ids, tags = { _, _ -> emptySet() }, clock = f.clock)
            val unwired = f.guards.isDueling
            f.services.bus.close() // the engine's subscriber cannot subscribe: the start fails after the startup pruned the state

            LogCapture().use { logs ->
                assertFailsWith<IllegalStateException> { service.starting { service.engine.begin(f.clock.millis()) {} } }
                assertContains(logs.text, "the engine's start failed: its state is back as it was in the world")
            }
            assertEquals(before, f.store.get(), "C-50 still in the state, its bet not settled: the next start reconciles it")
            assertTrue(service.engine.stopped)
            assertSame(unwired, f.guards.isDueling, "the guards are not wired to an engine that did not start")
        }
    }

    private companion object {
        const val LAW =
            """{"kind":"law","statement":"Le Narrateur a décrété une loi d'essai.","trigger":"chat","when":{"words":["nul"]},""" +
                """"effects":[{"tool":"notify","at":"actor","args":{"note":"on m'insulte encore"}}],"minutes":60}"""
    }
}
