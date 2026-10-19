package io.github.clemsix6.narrator2.tool.mechanics

import com.anthropic.core.jsonMapper
import io.github.clemsix6.narrator2.agent.WorkersControl
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.mechanics.DeploySpec
import io.github.clemsix6.narrator2.mechanics.ForgeHalt
import io.github.clemsix6.narrator2.mechanics.MechanicRejected
import io.github.clemsix6.narrator2.mechanics.ModuleView
import io.github.clemsix6.narrator2.mechanics.RuleKind
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.testing.FakeForge
import io.github.clemsix6.narrator2.testing.FakeMechanics
import io.github.clemsix6.narrator2.testing.FakeModules
import io.github.clemsix6.narrator2.testing.FakeWorkers
import io.github.clemsix6.narrator2.testing.ForgeSamples
import io.github.clemsix6.narrator2.testing.MechanicsSamples
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ForgeTool
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.ToolArgs
import io.github.clemsix6.narrator2.tool.ToolCatalog
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolEnv
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolSchema
import io.github.clemsix6.narrator2.trace.TaskRecord
import io.github.clemsix6.narrator2.trace.TaskRecord.Status
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The Director's three tools of the Forge (M5 §9.1, task M5-12) against fake workers, a fake Forge and a fake engine:
 * they are `ForgeTool`s, their texts are the design's and fit their budget, they hand the forger and the engine what
 * the model wrote and answer with what they say, their refusals included. What the engine does with a deployment is
 * M5-11's (`ModuleBook`); the whole evening is `DirectorForgeScenariosTest`.
 */
class ModuleToolsTest {
    private val clock = TestClock(Instant.ofEpochMilli(MechanicsSamples.evening))
    private val mechanics = FakeMechanics(clock)
    private val forge = FakeForge(clock)
    private val workers = FakeWorkers(setOf(Role.ANALYST, Role.SCOUT, Role.FORGER))
    private val caller = Caller(Role.DIRECTOR, "director-c13", turnId = 42, taskId = null)
    private val modules: FakeModules get() = mechanics.modules

    private fun services(workers: WorkersControl = this.workers) = TestServices(clock = clock, mechanics = mechanics, forge = forge, workers = workers)

    private fun run(name: String, json: String, services: TestServices = services()): ToolResult = runBlocking {
        call(moduleTools(services).single { it.name == name }, json, services)
    }

    private suspend fun <A : Any> call(tool: NarratorTool<A>, json: String, services: TestServices): ToolResult {
        val decoded = ToolArgs.decode(tool.args, json)
        assertIs<ToolArgs.Decoded.Ok<A>>(decoded, "$json: $decoded")
        return tool.run(ToolContext(services, caller, clock, tool.timeoutMs), decoded.args)
    }

    @Test
    fun `the three tools are the Forge's, shown only with the engine and the Forge, and say what the design says`() {
        val tools = moduleTools(services()).associateBy { it.name }
        assertEquals(listOf("forge_module", "deploy_module", "stop_module"), tools.keys.toList())
        assertEquals(listOf(ToolEffect.TASK, ToolEffect.ENGINE, ToolEffect.ENGINE), tools.values.map { it.effect })
        val wired = ToolEnv(journalAvailable = true, workerRoles = emptySet(), mechanicsAvailable = true, forgeAvailable = true)
        for (tool in tools.values) {
            assertIs<ForgeTool<*>>(tool)
            assertTrue(tool.isAvailable(wired))
            assertFalse(tool.isAvailable(wired.copy(forgeAvailable = false)), "${tool.name} hidden without the Forge")
            assertFalse(tool.isAvailable(wired.copy(mechanicsAvailable = false)), "${tool.name} hidden without the engine")
        }
        assertEquals(
            "Commissions a new mechanic from the forger, a worker who writes, tests and saves a Java module for your arsenal: several " +
                "minutes. Returns at once with a task id; its report (the module, its parameters, what players will see, its limits) comes " +
                "back as a TASK event; then deploy it. Only for what a rule cannot do: state over time, timers, creatures steered every " +
                "tick, a mini-game that names its winners. Look at your arsenal first.",
            tools.getValue("forge_module").description,
        )
        assertEquals(
            "Runs a module of your arsenal for some minutes: a law (public: announce it) or a plan (secret). The engine runs it under " +
                "your rules: its actions are refused like yours (protected, recently dead, already punished, the same kind of punishment " +
                "too soon); it never gives nor takes an item and touches no block but a few temporary ones. Recorded in your canon " +
                "(C-…); MODULE events tell you how it ends. With challenge, it referees a running judged challenge and names the " +
                "winners itself.",
            tools.getValue("deploy_module").description,
        )
        assertEquals("Stops one of your running modules now; its creatures get back to normal.", tools.getValue("stop_module").description)
    }

    /**
     * M5 §9.1: the three definitions, as the SDK sends them (name, description, input schema), take 5,000 characters at
     * most of the cached prefix of every chapter; with them the Director has 47 tools (`ToolCatalogTest` asserts it from
     * M5-20 on). The sizes are printed for the hand-off.
     */
    @Test
    fun `with the Forge the Director gets its three new tools, whose definitions fit their budget`() {
        val services = services()
        val engine = ToolEnv(journalAvailable = true, workerRoles = setOf(Role.ANALYST, Role.SCOUT), memoryAvailable = true, mechanicsAvailable = true)
        val set = ToolCatalog.toolSetFor(Role.DIRECTOR, engine.copy(forgeAvailable = true), services)
        val without = ToolCatalog.toolSetFor(Role.DIRECTOR, engine, services).names
        assertEquals(47, set.names.size, "${set.names}")
        assertEquals(listOf("deploy_module", "forge_module", "stop_module"), set.names - without.toSet())

        val mapper = jsonMapper()
        val sizes = set.names.associateWith { name -> mapper.writeValueAsString(ToolSchema.definition(set[name]!!)).length }
        val forge = listOf("forge_module", "deploy_module", "stop_module").associateWith { sizes.getValue(it) }
        println("the Forge's three definitions: ${forge.values.sum()} characters $forge; the Director's 47: ${sizes.values.sum()} characters")
        assertTrue(forge.values.sum() <= FORGE_DEFINITIONS_BUDGET, "the three definitions take ${forge.values.sum()} characters: $forge")
    }

    // ── forge_module ──

    @Test
    fun `forge_module wants a brief worth the forger's time`() {
        val short = assertIs<ToolArgs.Decoded.Invalid>(ToolArgs.decode(ForgeModuleArgs.serializer(), """{"brief": "Un jeu de chat.                     "}"""))
        assertEquals("brief is too short (at least 40 characters): say what players see, who, how long, how it ends", short.problem)
    }

    @Test
    fun `forge_module hands the forger the brief as the Director's task, and returns at once`() {
        val brief = "  Un jeu de 1, 2, 3, soleil pour Bartok et Mimosa : qui bouge quand le Narrateur regarde recule ; 10 minutes. "

        val result = run("forge_module", """{"brief": "$brief"}""")

        assertEquals("ok: task T-1 forger started; its report will arrive as a TASK event", result.text)
        assertEquals(FakeWorkers.Submitted(1, Role.FORGER, brief.trim(), caller), workers.submitted.single())
    }

    @Test
    fun `forge_module says when the forger is busy with another task, and fails when the workers refuse`() {
        val brief = "Un jeu de chat perché pour Bartok et Mimosa, dix minutes, le moins longtemps loup gagne."
        val busy = Listing(
            TaskRecord(30, Role.FORGER, "un autre module", Status.RUNNING, clock.instant(), clock.instant(), null, 41, "forger-1", null, null),
        )
        assertEquals("ok: task T-31 forger queued (the forger is busy with T-30); its report will arrive as a TASK event", run("forge_module", """{"brief": "$brief"}""", services(busy)).text)
        val full = Listing(TaskRecord(30, Role.ANALYST, "une question", Status.RUNNING, clock.instant(), clock.instant(), null, 41, "analyst-1", null, null))
        assertEquals(
            "ok: task T-31 forger queued (the workers are busy: it starts when a running task ends); its report will arrive as a TASK event",
            run("forge_module", """{"brief": "$brief"}""", services(full)).text,
        )

        assertEquals("FAILED: no worker for role forger", run("forge_module", """{"brief": "$brief"}""", services(FakeWorkers(setOf(Role.ANALYST)))).text)
    }

    @Test
    fun `forge_module and deploy_module refuse while the Forge is down or switched off, or the engine is`() {
        val brief = """{"brief": "Un jeu de chat perché pour Bartok et Mimosa, dix minutes, le moins longtemps loup gagne."}"""
        val deploy = """{"module": "tag_game", "kind": "law", "statement": "Le Narrateur a lancé une partie de chat.", "minutes": 10}"""

        forge.available = false
        assertEquals("DENIED (forge_off): the Forge is unavailable: fake forge off", run("forge_module", brief).text)
        assertEquals("DENIED (forge_off): the Forge is unavailable: fake forge off", run("deploy_module", deploy).text)
        forge.available = true
        modules.halted = ForgeHalt("the forger made a mess", "Clément", clock.millis())
        assertEquals("DENIED (forge_off): the Forge was switched off by Clément: the forger made a mess", run("forge_module", brief).text)
        assertEquals("DENIED (forge_off): the Forge was switched off by Clément: the forger made a mess", run("deploy_module", deploy).text)
        modules.halted = null
        mechanics.available = false
        assertEquals("DENIED (mechanics_off): the mechanics are unavailable: fake mechanics off", run("forge_module", brief).text)
        assertEquals("DENIED (mechanics_off): the mechanics are unavailable: fake mechanics off", run("deploy_module", deploy).text)

        assertTrue(workers.submitted.isEmpty() && modules.deployed.isEmpty(), "nothing commissioned, nothing deployed")
        assertFalse(forgeAvailable(services()))
        mechanics.available = true
        assertTrue(forgeAvailable(services()))
    }

    // ── deploy_module ──

    @Test
    fun `deploy_module hands the engine the deployment as the Director wrote it, and answers with what runs`() {
        val json = """{"module": " tag_game ", "params": {"players": ["Bartok", "Mimosa"], "minutes": 5}, "kind": "law",
            "statement": " Le Narrateur a lancé une partie de chat entre Bartok et Mimosa. ", "quote": "Jouez, mortels.", "minutes": 10}"""

        val result = run("deploy_module", json)

        assertEquals("ok: module tag_game v1 deployed as C-1 (law) until 18:10 (in 10m): players Bartok,Mimosa, minutes 5; announce it if you like", result.text)
        val (spec, by) = modules.deployed.single()
        assertEquals(
            DeploySpec("tag_game", null, ForgeSamples.params, RuleKind.LAW, "Le Narrateur a lancé une partie de chat entre Bartok et Mimosa.", "Jouez, mortels.", 10, null),
            spec,
        )
        assertEquals(caller, by)
        assertTrue(result.targets.isEmpty(), "the module's acts are the engine's, never the Director's")
    }

    @Test
    fun `a plan that referees a judged challenge stays secret`() {
        val json = """{"module": "tag_game", "version": 0, "kind": "plan", "statement": "Le Narrateur arbitre le défi C-75.", "minutes": 30, "challenge": "c75"}"""

        val result = run("deploy_module", json)

        assertEquals("ok: module tag_game v0 deployed as C-1 (plan) until 18:30 (in 30m); it referees C-75; it stays secret", result.text)
        assertEquals(MechanicsSamples.canon(75), modules.deployed.single().first.challenge)
        assertEquals(0, modules.deployed.single().first.version)
    }

    @Test
    fun `the engine's refusals come back as they are, and a challenge must be a canon reference`() {
        val json = """{"module": "orbiting_chickens", "params": {"player": "Bartok", "chickens": 12}, "kind": "law", "statement": "Des poules.", "minutes": 10}"""
        modules.rejection = MechanicRejected("module_limit", "4 modules run (C-70, C-71, C-72, C-73); stop one first")
        assertEquals("DENIED (module_limit): 4 modules run (C-70, C-71, C-72, C-73); stop one first", run("deploy_module", json).text)
        modules.rejection = MechanicRejected(null, "params.chickens must be between 3 and 8 (got 12)")
        assertEquals("Invalid arguments: params.chickens must be between 3 and 8 (got 12)", run("deploy_module", json).text)
        modules.rejection = MechanicRejected(null, "C-70 failed to start: IllegalStateException at OrbitingChickens.java:42", failed = true)
        assertEquals("FAILED: C-70 failed to start: IllegalStateException at OrbitingChickens.java:42", run("deploy_module", json).text)
        modules.rejection = null

        val statue = """{"module": "statues", "kind": "law", "statement": "Un jeu.", "minutes": 10, "challenge": "the statue"}"""
        assertEquals("Invalid arguments: challenge is one of your running judged challenges, like C-75 (got \"the statue\")", run("deploy_module", statue).text)
        assertTrue(modules.deployed.isEmpty())
    }

    // ── stop_module ──

    private fun running(id: Long, module: String, acts: Int, faults: Int) = ModuleView(
        MechanicsSamples.canon(id), module, 1, RuleKind.LAW, clock.millis() + 600_000, acts, faults, null, 0.4, "C-$id $module law Bartok (until 18:10)",
    ).also { modules.views += it }

    @Test
    fun `stop_module stops a running module as the Director, and says what it did`() {
        running(70, "orbiting_chickens", acts = 212, faults = 1)

        val result = run("stop_module", """{"id": "c70", "note": " Le Narrateur a rappelé ses poules. "}""")

        assertEquals("ok: module C-70 (orbiting_chickens) stopped: 212 acts, 1 fault", result.text)
        assertEquals(
            FakeModules.Stopped(MechanicsSamples.canon(70), "Le Narrateur a rappelé ses poules.", Author(Source.DIRECTOR, turnId = 42, conversationId = "director-c13")),
            modules.stopped.single(),
        )
    }

    @Test
    fun `stop_module names the running modules when the id is not one, works while the Forge is down, not once it is switched off`() {
        running(77, "statues", acts = 3, faults = 0)

        assertEquals("Invalid arguments: C-70 is not a running module; running: C-77", run("stop_module", """{"id": "C-70", "note": "Fini."}""").text)
        assertEquals("Invalid arguments: id is one of your running modules, like C-70 (got \"poules\")", run("stop_module", """{"id": "poules", "note": "Fini."}""").text)
        assertTrue(modules.stopped.isEmpty())

        forge.available = false
        assertEquals("ok: module C-77 (statues) stopped: 3 acts, 0 faults", run("stop_module", """{"id": "C-77", "note": "Le Narrateur a arrêté les statues."}""").text)
        assertEquals("Invalid arguments: C-77 is not a running module; running: none", run("stop_module", """{"id": "C-77", "note": "Fini."}""").text)
        modules.halted = ForgeHalt("the forger made a mess", "Clément", clock.millis())
        assertEquals("DENIED (forge_off): the Forge was switched off by Clément: the forger made a mess", run("stop_module", """{"id": "C-77", "note": "Fini."}""").text)
        modules.halted = null
        mechanics.available = false
        assertEquals("DENIED (mechanics_off): the mechanics are unavailable: fake mechanics off", run("stop_module", """{"id": "C-77", "note": "Fini."}""").text)
        assertEquals(1, modules.stopped.size)
    }

    /** Workers whose listing holds [tasks] besides the one just submitted, which waits in line (T-31). */
    private class Listing(private vararg val tasks: TaskRecord) : WorkersControl {
        override val roles: Set<Role> = setOf(Role.FORGER)

        override fun submit(role: Role, brief: String, caller: Caller): Result<Long> = Result.success(31)

        override fun list(): List<TaskRecord> =
            tasks.toList() + TaskRecord(31, Role.FORGER, "queued", Status.QUEUED, Instant.EPOCH, null, null, 42, null, null, null)

        override fun cancel(taskId: Long): Result<Unit> = Result.success(Unit)
    }

    private companion object {
        /** M5 §9.1: the three definitions together. */
        const val FORGE_DEFINITIONS_BUDGET = 5_000
    }
}
