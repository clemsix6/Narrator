package io.github.clemsix6.narrator2.mechanics

import io.github.clemsix6.narrator2.config.MechanicsConfig
import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.director.MechanicSignal
import io.github.clemsix6.narrator2.forge.DisabledForge
import io.github.clemsix6.narrator2.forge.Forge
import io.github.clemsix6.narrator2.forge.ForgeService
import io.github.clemsix6.narrator2.forge.host.Hosts
import io.github.clemsix6.narrator2.forge.sim.ActRecord
import io.github.clemsix6.narrator2.forge.sim.BenchImpl
import io.github.clemsix6.narrator2.journal.DisabledJournal
import io.github.clemsix6.narrator2.journal.Journal
import io.github.clemsix6.narrator2.mechanics.module.ModuleBook
import io.github.clemsix6.narrator2.mechanics.module.ModuleEngine
import io.github.clemsix6.narrator2.mechanics.module.ModuleEnding
import io.github.clemsix6.narrator2.mechanics.module.ModuleFixture
import io.github.clemsix6.narrator2.mechanics.rule.RuleFixture
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.testing.FakeForge
import io.github.clemsix6.narrator2.testing.FakeMemory
import io.github.clemsix6.narrator2.testing.ForgeSamples
import io.github.clemsix6.narrator2.testing.LogCapture
import io.github.clemsix6.narrator2.testing.MechanicsSamples
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.testing.testSettings
import io.github.clemsix6.narrator2.tool.ToolStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
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
 * The assembly of the mechanics engine (M4-23, M4 §4.1, §4.6; its modules' part, M5-21): when `createMechanics` keeps it
 * off and why, and what the real [MechanicsService] hands to which part. The service runs here on a real rule book and a
 * real canon ([RuleFixture]), its state in memory, without a server (its modules on a test's server,
 * `ModuleFixture.Server`); the GameTests (`MechanicsAssemblyGameTests`, `ForgeAssemblyGameTests`) run the
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
    fun `the engine's acts reach the tools of the rules, the stakes and the modules, never sql, run_command nor delegate`() = runBlocking<Unit> {
        RuleFixture().use { f ->
            val service = MechanicsService(f.services, f.store, f.ids, tags = { _, _ -> emptySet() }, clock = f.clock)
            assertEquals(MechanicsService.TOOLS, service.actions.names)
            assertTrue(
                listOf("say", "explosion", "teleport", "mob_effect", "notify", "punish", "give_item", "apply_effect", "favor").all { it in service.actions.names },
                "the rules' tools and the stakes': ${service.actions.names}",
            )
            // Every act a module may ask (the bench checks them dry by these very tools) is one of the engine's: none
            // passes the bench to be refused `unknown tool` in the game.
            val asked = BenchImpl.TOOLS - ActRecord.GAME
            assertEquals(emptySet(), asked.toSet() - service.actions.names, "the modules' acts the engine lacks")
            assertTrue(listOf("hud", "place_blocks", "creature_attack").all { it in service.actions.names }, "the modules' own tools")
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

    @Test
    fun `the modules' part runs on the engine, deployed through the book, its fault noted on its version, its thread ended at the stop`() = runBlocking<Unit> {
        RuleFixture().use { f ->
            val forge = NotingForge(FakeForge(f.clock))
            forge.fake.module("fumbler", ModuleFixture.source("fumbler", body = """m.timers().after(1, () -> { throw new IllegalStateException("boom"); });"""))
            val service = f.service(f.servicesWith(forge))
            val before = moduleThreads()
            service.begin(f.clock.millis())
            val view = try {
                assertIs<ModuleBook>(service.modules)
                val view = service.modules.deploy(spec("fumbler"), f.caller)
                assertEquals(listOf(view.ref), service.modules.list().map { it.ref })
                awaitTrue("the fault noted on its version") {
                    f.clock.advance(Duration.ofMillis(TICK_MS))
                    service.beat(f.clock.millis())
                    forge.noted.isNotEmpty()
                }
                val (name, version, fault) = forge.noted.single()
                assertEquals("fumbler v1", "$name v$version")
                assertTrue(fault.startsWith("exception in timer: IllegalStateException: boom at Fumbler.java:"), fault)
                assertTrue((moduleThreads() - before).isNotEmpty(), "its code ran on the engine's module thread")
                view
            } finally {
                service.stop()
            }
            assertEquals(listOf(view.ref.id), f.store.get().modules.map { it.id }, "stopped with the server, it stays in the state for the restart")
            awaitTrue("the module threads ended") { (moduleThreads() - before).isEmpty() }
        }
    }

    @Test
    fun `without a Forge the modules' part stays, what the state holds ends restart_failed, its canon row closed, and nothing deploys`() = runBlocking<Unit> {
        RuleFixture().use { f ->
            val fake = FakeForge(f.clock)
            fake.module("waiter", ModuleFixture.source("waiter"))
            val first = f.service(f.servicesWith(fake))
            first.begin(f.clock.millis())
            val view = try {
                first.modules.deploy(spec("waiter"), f.caller)
            } finally {
                first.stop()
            }
            assertEquals(listOf(view.ref.id), f.store.get().modules.map { it.id })

            // The server starts again with forge.enabled false.
            val off = f.servicesWith(DisabledForge("forge.enabled is false"))
            val second = f.service(off)
            second.begin(f.clock.millis())
            try {
                val refused = assertFailsWith<MechanicRejected> { second.modules.deploy(spec("waiter"), f.caller) }
                assertEquals("DENIED (forge_off): the Forge is unavailable: forge.enabled is false", refused.toResult().text)
                val why = "could not restart: the Forge is unavailable: forge.enabled is false"
                awaitTrue("its canon row closed") {
                    f.clock.advance(Duration.ofMillis(TICK_MS))
                    second.beat(f.clock.millis())
                    f.memory.store.canon(view.ref)?.status == CanonStatus.CANCELLED
                }
                assertEquals(why, f.memory.store.canon(view.ref)!!.resolution)
                assertEquals(emptyList(), f.store.get().modules, "out of the state")
                awaitTrue("its decision") { off.traces.decisions.any { it.decision == ModuleEngine.ENDED && it.subject == view.ref.toString() } }
                val ended = off.traces.decisions.single { it.decision == ModuleEngine.ENDED }
                assertEquals(ModuleEnding.RESTART_FAILED.id, ended.detail!!.getValue(ModuleEngine.ENDING).jsonPrimitive.content)
                val lines = f.director.signals.filterIsInstance<DirectorSignal.Mechanic>().filter { it.kind == MechanicSignal.MODULE }.map { it.line }
                assertTrue("${view.ref} waiter could not restart: the Forge is unavailable: forge.enabled is false" in lines, lines.toString())
            } finally {
                second.stop()
            }
        }
    }

    @Test
    fun `the modules' thread shares the lost threads of the Forge with its bench`() {
        RuleFixture().use { f ->
            val services = f.servicesWith(FakeForge(f.clock))
            val forge = ForgeService(services)
            try {
                val service = f.service(f.servicesWith(forge))
                assertSame(forge.lostThreads, service.moduleThreads.lost, "one ceiling for the whole Forge (M5-05)")
                assertNotSame(forge.lostThreads, f.service(services).moduleThreads.lost, "without the Forge's service, the engine's own")
            } finally {
                forge.stop()
            }
        }
    }

    /** A Forge that keeps the faults noted on its versions (`Forge.noteFault`); the rest is [fake]'s. */
    private class NotingForge(val fake: FakeForge) : Forge by fake {
        val noted = CopyOnWriteArrayList<Triple<String, Int, String>>()

        override fun noteFault(name: String, version: Int, fault: String) {
            noted += Triple(name, version, fault)
        }
    }

    /** The fixture's services, with [forge]. */
    private fun RuleFixture.servicesWith(forge: Forge) =
        TestServices(clock = clock, journal = base.journal, memory = memory, guards = guards, director = director, forge = forge)

    /** The engine assembled on [services] and the fixture's state, a test's server for its modules. */
    private fun RuleFixture.service(services: TestServices) =
        MechanicsService(services, store, ids, tags = { _, _ -> emptySet() }, clock = clock, moduleServer = ModuleFixture.Server())

    /** Starts the engine without a server: the modules' part begins on the state (the others need a server). */
    private fun MechanicsService.begin(now: Long) = starting { engine.begin(now) { part -> if (part === moduleParts) moduleParts.begin(null, now) } }

    /** A tick of the engine at [now] (its second when one is due), for the modules' part. */
    private fun MechanicsService.beat(now: Long) =
        engine.beat(now, { part -> if (part === moduleParts) moduleParts.tick(now) }, { part -> if (part === moduleParts) moduleParts.second(now) })

    /** Module [name] built from [source], version 1 and ready, without parameters. */
    private fun FakeForge.module(name: String, source: String) {
        arsenal.add(ForgeSamples.manifest.copy(name = name, version = 1, params = emptyList(), hash = "sha256:$name-v1", scenarios = emptyList()), source)
        builtClasses[name] = Hosts.build(FakeForge.mainClass(name), source)
    }

    /** What the Director asks for [module], as a plan: no parameter, ten minutes. */
    private fun spec(module: String) = DeploySpec(
        module = module, version = null, params = JsonObject(emptyMap()), kind = RuleKind.PLAN,
        statement = "Le Narrateur a lancé le module $module.", quote = null, minutes = 10, challenge = null,
    )

    /** The live threads of the server's modules (`narrator2-module-N`). */
    private fun moduleThreads(): Set<Thread> = Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith("narrator2-module-") }.toSet()

    private suspend fun awaitTrue(what: String, condition: suspend () -> Boolean) {
        val until = System.nanoTime() + 10_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < until) { "timed out waiting for: $what" }
            delay(20)
        }
    }

    private companion object {
        const val TICK_MS = 50L

        const val LAW =
            """{"kind":"law","statement":"Le Narrateur a décrété une loi d'essai.","trigger":"chat","when":{"words":["nul"]},""" +
                """"effects":[{"tool":"notify","at":"actor","args":{"note":"on m'insulte encore"}}],"minutes":60}"""
    }
}
