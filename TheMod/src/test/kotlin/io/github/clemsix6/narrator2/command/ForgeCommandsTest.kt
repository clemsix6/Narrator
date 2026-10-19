package io.github.clemsix6.narrator2.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.github.clemsix6.narrator2.agent.WorkersControl
import io.github.clemsix6.narrator2.forge.ArsenalEntry
import io.github.clemsix6.narrator2.forge.DisabledForge
import io.github.clemsix6.narrator2.forge.Forge
import io.github.clemsix6.narrator2.forge.ForgeStatus
import io.github.clemsix6.narrator2.forge.ModuleStatus
import io.github.clemsix6.narrator2.forge.ParamSpec
import io.github.clemsix6.narrator2.forge.api.ParamType
import io.github.clemsix6.narrator2.forge.api.Sdk
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.mechanics.DisabledMechanics
import io.github.clemsix6.narrator2.mechanics.DisabledModules
import io.github.clemsix6.narrator2.mechanics.MechanicRejected
import io.github.clemsix6.narrator2.mechanics.Mechanics
import io.github.clemsix6.narrator2.mechanics.MechanicsState
import io.github.clemsix6.narrator2.mechanics.ModuleControl
import io.github.clemsix6.narrator2.mechanics.ModuleView
import io.github.clemsix6.narrator2.mechanics.RuleKind
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.testing.FakeForge
import io.github.clemsix6.narrator2.testing.FakeMechanics
import io.github.clemsix6.narrator2.testing.FakeModules
import io.github.clemsix6.narrator2.testing.LogCapture
import io.github.clemsix6.narrator2.testing.MechanicsSamples
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.TaskRecord
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.minecraft.commands.CommandSourceStack
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `/narrator forge …` and `/narrator module …` (M5 §14) against [FakeForge] and [FakeModules]: what each command
 * renders, what it asks of the Forge and of the modules' control (as the operator), and the `operator` decisions it
 * writes; the switch (`forge off`: every module stopped at once and the forger's tasks cancelled; kept in the world's
 * state across a restart, a damaged one included; lifted only by `forge on`); each command's refusal without the
 * Forge or the engine; the `Forge:` line of `/narrator status`; and the command tree.
 */
class ForgeCommandsTest {
    private val clock = TestClock(Instant.ofEpochMilli(MechanicsSamples.evening), ZoneOffset.UTC)
    private val now = clock.millis()
    private val forge = FakeForge(clock)
    private val mechanics = FakeMechanics(clock)
    private val modules = mechanics.modules
    private val tasks = Tasks(
        task(30, Role.FORGER, TaskRecord.Status.DONE),
        task(12, Role.ANALYST, TaskRecord.Status.RUNNING, startedAgo = Duration.ofSeconds(40)),
        task(31, Role.FORGER, TaskRecord.Status.RUNNING, startedAgo = Duration.ofSeconds(125)),
        task(32, Role.FORGER, TaskRecord.Status.QUEUED),
    )
    private val services = TestServices(clock = clock, forge = forge, mechanics = mechanics, workers = tasks)

    private fun DirectorCommands.Reply.text() = lines.map { it.string }

    private fun operatorDecisions(services: TestServices = this.services) =
        services.traces.decisions.filter { it.kind == DecisionRecord.Kind.OPERATOR }.map { "${it.decision} ${it.subject}: ${it.reason}" }

    private fun task(id: Long, role: Role, status: TaskRecord.Status, startedAgo: Duration? = null) = TaskRecord(
        id, role, "brief", status, clock.instant(), startedAgo?.let { clock.instant() - it }, null, 42, null, null, null,
    )

    /** Workers that hold [records] and cancel the unfinished ones. */
    private class Tasks(vararg records: TaskRecord) : WorkersControl {
        val tasks = CopyOnWriteArrayList(records.toList())
        val cancelled = CopyOnWriteArrayList<Long>()
        override val roles = setOf(Role.ANALYST, Role.FORGER)

        override fun submit(role: Role, brief: String, caller: Caller) = Result.failure<Long>(UnsupportedOperationException())

        override fun list(): List<TaskRecord> = tasks.toList()

        override fun cancel(taskId: Long): Result<Unit> {
            val index = tasks.indexOfFirst { it.taskId == taskId }
            if (index < 0 || tasks[index].status.finished) return Result.failure(IllegalArgumentException("task T-$taskId already ended"))
            cancelled += taskId
            tasks[index] = tasks[index].copy(status = TaskRecord.Status.CANCELLED)
            return Result.success(Unit)
        }
    }

    private fun entry(
        name: String,
        version: Int,
        summary: String,
        status: ModuleStatus = ModuleStatus.READY,
        savedAt: Instant = Instant.EPOCH,
        by: String = "built-in",
        note: String? = null,
        params: List<ParamSpec> = emptyList(),
        scenarios: List<String> = listOf("itWorks"),
        classes: Int = 0,
        bytes: Int = 0,
        problem: String? = null,
    ) = ArsenalEntry(
        name, version, status, builtIn = version == 0, summary, params, scenarios, savedAt, by, note, "sha256:$name$version", Sdk.VERSION, classes, bytes, problem,
    )

    private val statuesParams = listOf(
        ParamSpec("players", ParamType.PLAYERS, "Who plays.", required = true, fallback = null, min = 2.0, max = 8.0, choices = emptyList()),
        ParamSpec("seconds", ParamType.INTEGER, "How long a freeze lasts.", required = false, fallback = "5", min = 1.0, max = 30.0, choices = emptyList()),
        ParamSpec("mood", ParamType.TEXT, "How the Narrator speaks.", required = false, fallback = "taquin", min = null, max = null, choices = listOf("taquin", "sévère")),
        ParamSpec("radius", ParamType.NUMBER, "How far the Narrator looks.", required = false, fallback = "4.5", min = 0.5, max = null, choices = emptyList()),
    )

    /** The arsenal of an evening: three built-in modules (one broken), and two versions of a forged one, the newest pending. */
    private fun fillArsenal() {
        forge.arsenal.add(entry("tag", 0, "Tag: the wolf passes on by touch; whoever was the wolf the least wins."))
        forge.arsenal.add(entry("orbiting_chickens", 0, "Chickens orbit a player, then fly away."))
        forge.arsenal.add(entry("hot_cold", 0, "Hot or cold: the closer a player gets, the louder the hint.", ModuleStatus.BROKEN, problem = "no longer compiles: World.surface(Place) is undefined"))
        forge.arsenal.add(
            entry(
                "statues", 1, "Players freeze when the Narrator looks at them.", savedAt = clock.instant() - Duration.ofDays(1), by = "forger T-30",
                note = "Freezes players who move while watched.", params = statuesParams, scenarios = listOf("freezesEveryone", "endsAfterThreeRounds"),
                classes = 4, bytes = 12_345,
            ),
        )
        forge.arsenal.add(
            entry(
                "statues", 2, "Players freeze when the Narrator looks at them.", ModuleStatus.PENDING, savedAt = clock.instant() - Duration.ofMinutes(10),
                by = "forger T-31", note = "Freezes for 3 s instead of 5.", params = statuesParams, scenarios = listOf("freezesEveryone", "endsAfterThreeRounds"),
            ),
        )
    }

    private fun view(id: Long, module: String, acts: Int = 0, faults: Int = 0, msPerSecond: Double = 0.0) = ModuleView(
        Ref(RefKind.CANON, id), module, 1, RuleKind.LAW, now + 600_000, acts, faults, null, msPerSecond, "C-$id $module law Bartok (until 18:10)",
    )

    /** Services whose engine has [control] for its modules, the rest as in [services]. */
    private fun withModules(control: ModuleControl, forge: Forge = this.forge) = TestServices(
        clock = clock, forge = forge, workers = tasks,
        mechanics = object : Mechanics by FakeMechanics(clock) {
            override val modules: ModuleControl = control
        },
    )

    // ── Without the Forge ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `without the Forge every command is refused with the reason, and the status says why`() = runBlocking<Unit> {
        // The server until the assembly (M5-21): no Forge, and the engine's modules refused.
        val off = withModules(DisabledModules(DisabledForge.FORGE_OFF, "the Forge is unavailable: not wired yet"), DisabledForge("not wired yet"))
        val noArsenal = listOf("[Narrator] the Forge is unavailable (not wired yet): no arsenal to show or change.")
        val noModule = listOf("[Narrator] the Forge is unavailable (not wired yet): no module runs.")
        for ((what, reply) in listOf(
            "forge list" to ForgeCommands.arsenalList(off, 1),
            "forge show" to ForgeCommands.showEntry(off, "tag", null),
            "forge approve" to ForgeCommands.review(off, "Clement", "tag", null, approve = true),
            "forge reject" to ForgeCommands.review(off, "Clement", "tag", 2, approve = false),
        )) {
            assertFalse(reply.done, what)
            assertEquals(noArsenal, reply.text(), what)
        }
        for ((what, reply) in listOf("module list" to ForgeCommands.moduleList(off, 1), "module stop" to ForgeCommands.stopModule(off, "Clement", "C-70", null))) {
            assertFalse(reply.done, what)
            assertEquals(noModule, reply.text(), what)
        }
        val switchOff = ForgeCommands.off(off, "Clement", "maintenance")
        assertFalse(switchOff.done)
        assertEquals(listOf("[Narrator] the Forge is unavailable (not wired yet): no module can run, nothing to switch off."), switchOff.text())
        val switchOn = ForgeCommands.on(off, "Clement")
        assertFalse(switchOn.done)
        assertEquals(listOf("[Narrator] the Forge is unavailable (not wired yet): its switch is not set, nothing to lift."), switchOn.text())

        val status = ForgeCommands.status(off)
        assertTrue(status.done, "the status always answers")
        assertEquals(
            listOf(
                "[Narrator] forge status",
                "Forge: off (not wired yet) · ECJ unknown · arsenal 0 (0 forged, 0 pending, 0 broken) · 0 running · compiler off · leaks 0",
                "Switch: not set. /narrator forge off stops every module now and keeps the Forge off, across restarts, until /narrator forge on.",
                "Approval: auto · 4 modules at most · forger: T-31 running 2m05s, T-32 queued",
            ),
            status.text(),
        )
        assertEquals("Forge: off (not wired yet) · 0 modules running", ForgeCommands.statusLine(off))
        assertTrue(off.traces.decisions.isEmpty(), "nothing done, nothing recorded")
        assertTrue(tasks.cancelled.isEmpty(), "no task cancelled by a switch refused")
    }

    @Test
    fun `without the engine the modules' commands are refused with its reason, the arsenal stays readable`() = runBlocking<Unit> {
        val off = TestServices(clock = clock, forge = forge, mechanics = DisabledMechanics("mechanics.enabled is false"), workers = tasks)
        fillArsenal()
        val noModule = listOf("[Narrator] the mechanics are unavailable (mechanics.enabled is false): no module runs.")
        assertEquals(noModule, ForgeCommands.moduleList(off, 1).text())
        assertEquals(noModule, ForgeCommands.stopModule(off, "Clement", "C-70", null).text())
        assertEquals(
            listOf("[Narrator] the mechanics are unavailable (mechanics.enabled is false): no module can run, nothing to switch off."),
            ForgeCommands.off(off, "Clement", null).text(),
        )
        assertTrue(ForgeCommands.arsenalList(off, 1).done, "the arsenal is the Forge's")
        assertEquals("Forge: on · 0 modules running", ForgeCommands.statusLine(off))

        // The Forge off by the configuration, the engine on.
        val disabled = withModules(modules, DisabledForge("forge.enabled is false"))
        assertEquals(listOf("[Narrator] the Forge is unavailable (forge.enabled is false): no arsenal to show or change."), ForgeCommands.arsenalList(disabled, 1).text())
        assertEquals(listOf("[Narrator] the Forge is unavailable (forge.enabled is false): no module runs."), ForgeCommands.moduleList(disabled, 1).text())
        assertTrue(off.traces.decisions.isEmpty() && disabled.traces.decisions.isEmpty(), "nothing done, nothing recorded")
    }

    // ── forge status ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `forge status and the status line count the arsenal, the modules, the leaks, the switch and the forger`() {
        assertEquals("Forge: on · 0 modules running", ForgeCommands.statusLine(services))
        fillArsenal()
        modules.views += view(70, "orbiting_chickens")
        modules.views += view(73, "tag")
        modules.leaks = 1
        forge.status = ForgeStatus("3.46.100", "compiling statues (2s)")
        assertEquals("Forge: on · 2 modules running", ForgeCommands.statusLine(services))
        assertEquals(
            listOf(
                "[Narrator] forge status",
                "Forge: on · ECJ 3.46.100 · arsenal 5 (2 forged, 1 pending, 1 broken) · 2 running · compiler compiling statues (2s) · leaks 1",
                "Switch: not set. /narrator forge off stops every module now and keeps the Forge off, across restarts, until /narrator forge on.",
                "Approval: auto · 4 modules at most · forger: T-31 running 2m05s, T-32 queued",
            ),
            ForgeCommands.status(services).text(),
        )
    }

    // ── forge list, show ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `forge list shows every version, newest first, one line each, in pages when long`() {
        assertEquals(listOf("[Narrator] arsenal · empty"), ForgeCommands.arsenalList(services, 1).text())
        fillArsenal()
        val list = ForgeCommands.arsenalList(services, 1)
        assertTrue(list.done)
        assertEquals(
            listOf(
                "[Narrator] arsenal · 5 versions (2 forged, 1 pending, 1 broken)",
                "statues v2 pending · forger T-31 · 17:50:00 · Players freeze when the Narrator looks at them.",
                "statues v1 ready · forger T-30 · 10-18 18:00 · Players freeze when the Narrator looks at them.",
                "hot_cold v0 broken · built-in · Hot or cold: the closer a player gets, the louder the hint.",
                "orbiting_chickens v0 ready · built-in · Chickens orbit a player, then fly away.",
                "tag v0 ready · built-in · Tag: the wolf passes on by touch; whoever was the wolf the least wins.",
                "Show one: /narrator forge show <name> [version]",
                "Review a pending one: /narrator forge approve|reject <name> [version]",
            ),
            list.text(),
        )
        assertTrue(list.text().all { it.length <= WRAP + "[Narrator] ".length }, "one chat line each")

        (1..40).forEach { forge.arsenal.add(entry("game_$it", 0, "A built-in game whose summary is long enough to be cut at the end of the line, as here.")) }
        val first = ForgeCommands.arsenalList(services, 1).text()
        assertEquals("[Narrator] arsenal · 45 versions (2 forged, 1 pending, 1 broken) · page 1/4", first.first())
        assertTrue(first.size <= 20, "a page fits the open chat: ${first.size} lines")
        assertTrue(first.last().startsWith("page 1/4") && "/narrator forge list 2" in first.last(), first.last())
        assertTrue(first[1].startsWith("game_40 v0 ready · built-in · A built-in game") && first[1].endsWith("…"), first[1])
    }

    @Test
    fun `forge show gives a version in full, the newest whatever its status by default, in at most 20 lines`() {
        fillArsenal()
        val newest = ForgeCommands.showEntry(services, "statues", null)
        assertTrue(newest.done)
        assertEquals(
            listOf(
                "[Narrator] statues v2 · pending · forger T-31 · saved 17:50:00",
                "Summary: Players freeze when the Narrator looks at them.",
                "Note: Freezes for 3 s instead of 5.",
                "Param players: players, required, 2 to 8 · Who plays.",
                "Param seconds: integer, default 5, 1 to 30 · How long a freeze lasts.",
                "Param mood: text, default taquin, one of taquin, sévère · How the Narrator speaks.",
                "Param radius: number, default 4.5, at least 0.5 · How far the Narrator looks.",
                "Scenarios: freezesEveryone, endsAfterThreeRounds",
                "Built: not yet (SDK ${Sdk.VERSION})",
                "Hash: sha256:statues2",
                "Source: ${Path.of("world", "narrator2", "forge", "statues", "v2.java")}",
                "Versions: v2 pending, v1 ready",
                "Review it: /narrator forge approve|reject statues 2",
            ),
            newest.text(),
        )
        val older = ForgeCommands.showEntry(services, "statues", 1).text()
        assertEquals("[Narrator] statues v1 · ready · forger T-30 · saved 10-18 18:00", older.first())
        assertTrue("Built: SDK ${Sdk.VERSION} · 4 classes, 12345 bytes" in older, older.toString())
        assertTrue(older.none { it.startsWith("Review it") }, "a ready version needs no review")

        val broken = ForgeCommands.showEntry(services, "hot_cold", null).text()
        assertEquals("[Narrator] hot_cold v0 · broken · built-in", broken.first())
        assertTrue("Source: in the mod's jar (narrator2/forge/arsenal/hot_cold.java)" in broken, broken.toString())
        assertTrue("Problem: no longer compiles: World.surface(Place) is undefined" in broken, broken.toString())
        assertTrue(broken.none { it.startsWith("Note:") }, "a built-in module has no note")

        assertEquals(listOf("[Narrator] statues has no v7; its versions: v2, v1."), ForgeCommands.showEntry(services, "statues", 7).text())
        assertEquals(
            listOf("[Narrator] no module statue in the arsenal; it has: hot_cold, orbiting_chickens, statues, tag."),
            ForgeCommands.showEntry(services, "statue", null).text(),
        )

        // The longest a version can be: 8 parameters, 20 versions, every text at its limit.
        val long = "x".repeat(120)
        val params = (1..8).map { ParamSpec("param_$it", ParamType.TEXT, long, required = false, fallback = "a".repeat(40), min = 1.0, max = 300.0, choices = listOf(long, long)) }
        (1..20).forEach { forge.arsenal.add(entry("big", it, long, ModuleStatus.PENDING, clock.instant(), "forger T-99", note = long, params = params, problem = long)) }
        val big = ForgeCommands.showEntry(services, "big", null).text()
        assertTrue(big.size <= 20, "show fits the open chat: ${big.size} lines\n${big.joinToString("\n")}")
    }

    // ── forge approve, reject ───────────────────────────────────────────────────────────────────────

    @Test
    fun `approve and reject review a pending version as the operator, and record it`() = runBlocking<Unit> {
        fillArsenal()
        val approved = ForgeCommands.review(services, "Clement", "statues", null, approve = true)
        assertTrue(approved.done, approved.text().toString())
        assertEquals(listOf("[Narrator] statues v2 approved by Clement: the Director can deploy it."), approved.text())
        assertEquals(listOf(FakeForge.Review("statues", null, true, "Clement")), forge.reviews)
        assertEquals(ModuleStatus.READY, forge.arsenal.entry("statues", 2)?.status)

        val again = ForgeCommands.review(services, "Clement", "statues", null, approve = true)
        assertFalse(again.done)
        assertEquals(listOf("[Narrator] refused: statues has no version waiting for approval."), again.text())

        forge.arsenal.add(entry("statues", 3, "Players freeze.", ModuleStatus.PENDING, clock.instant(), "forger T-32"))
        val rejected = ForgeCommands.review(services, "Mimosa", "statues", 3, approve = false)
        assertEquals(listOf("[Narrator] statues v3 rejected by Mimosa: the Director will not see it."), rejected.text())
        assertEquals(ModuleStatus.REJECTED, forge.arsenal.entry("statues", 3)?.status)
        assertEquals(listOf("forge_approve Clement: statues v2 approved", "forge_reject Mimosa: statues v3 rejected"), operatorDecisions())
    }

    // ── The switch ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `forge off stops every module at once, cancels the forger's tasks, and records it`() = runBlocking<Unit> {
        modules.views += view(70, "orbiting_chickens")
        modules.views += view(73, "tag")
        val off = ForgeCommands.off(services, "Clement", "  maintenance du serveur  ")
        assertTrue(off.done, off.text().toString())
        assertEquals(
            listOf(
                "[Narrator] the Forge is switched off by Clement: 2 modules stopped (C-70 orbiting_chickens, C-73 tag), the forger's tasks T-31, T-32 cancelled.",
                "Nothing is deployed nor restarted, across restarts too, until /narrator forge on.",
            ),
            off.text(),
        )
        assertEquals(listOf(FakeModules.Halt("maintenance du serveur", "Clement")), modules.halts, "one switch, the operator's words")
        assertTrue(modules.list().isEmpty(), "no module runs once the command has answered")
        assertEquals(listOf(31L, 32L), tasks.cancelled, "the forger's running and queued tasks, not the analyst's nor a finished one")
        assertEquals(TaskRecord.Status.RUNNING, tasks.list().single { it.taskId == 12L }.status)

        val decision = services.traces.decisions.single { it.kind == DecisionRecord.Kind.OPERATOR }
        assertEquals(ForgeCommands.FORGE_OFF, decision.decision)
        assertEquals("Clement", decision.subject)
        assertEquals("the Forge switched off: « maintenance du serveur »; 2 modules stopped (C-70 orbiting_chickens, C-73 tag), the forger's tasks T-31, T-32 cancelled", decision.reason)
        assertEquals(listOf("C-70", "C-73"), decision.detail!!.getValue("stopped").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("31", "32"), decision.detail!!.getValue("cancelled").jsonArray.map { it.jsonPrimitive.content })

        // What the operators see while it is off.
        assertEquals("Forge: switched off by Clement at 18:00:00 · 0 modules running", ForgeCommands.statusLine(services))
        assertEquals(
            "Switch: set by Clement at 18:00:00: « maintenance du serveur ». Nothing is deployed nor restarted until /narrator forge on lifts it.",
            ForgeCommands.status(services).text()[2],
        )
        assertEquals(
            listOf("[Narrator] modules · none running · the Forge is switched off by Clement at 18:00:00; /narrator forge on lifts it"),
            ForgeCommands.moduleList(services, 1).text(),
        )
        val again = ForgeCommands.off(services, "Mimosa", null)
        assertFalse(again.done)
        assertEquals(listOf("[Narrator] the Forge is already switched off (by Clement at 18:00:00: « maintenance du serveur »); /narrator forge on lifts it."), again.text())
        assertEquals(1, modules.halts.size, "a second switch changes nothing")
    }

    @Test
    fun `without modules nor forger the switch still holds, with the default reason, and a reason too long is refused`() = runBlocking<Unit> {
        val tooLong = ForgeCommands.off(services, "Clement", "x".repeat(ForgeCommands.REASON_CHARS + 1))
        assertEquals(listOf("[Narrator] the reason has 201 characters; 200 at most."), tooLong.text())
        assertTrue(modules.halts.isEmpty())

        val quiet = withModules(modules).also { tasks.tasks.clear() }
        val off = ForgeCommands.off(quiet, "Clement", "   ")
        assertEquals(listOf("[Narrator] the Forge is switched off by Clement: no module was running.", "Nothing is deployed nor restarted, across restarts too, until /narrator forge on."), off.text())
        assertEquals(ForgeCommands.NO_REASON, modules.halted?.reason)
    }

    @Test
    fun `a switch the modules' control refuses stops nothing and cancels nothing`() = runBlocking<Unit> {
        modules.views += view(70, "orbiting_chickens")
        modules.rejection = MechanicRejected(DisabledForge.FORGE_OFF, "the modules are stopping with the server.")
        val refused = ForgeCommands.off(services, "Clement", null)
        assertFalse(refused.done)
        assertEquals(listOf("[Narrator] refused: the modules are stopping with the server."), refused.text())
        assertTrue(tasks.cancelled.isEmpty(), "the forger's tasks run on")
        assertTrue(operatorDecisions().isEmpty())
    }

    @Test
    fun `the switch survives a restart, read back from the world's state, and only forge on lifts it`() = runBlocking<Unit> {
        modules.views += view(70, "orbiting_chickens")
        ForgeCommands.off(services, "Clement", "maintenance du serveur")

        // The world saves the engine's state, the server stops, starts again: the modules' control reads its switch back.
        val saved = MechanicsState.encode(MechanicsState.EMPTY.copy(forgeHalt = modules.halted))
        val restarted = FakeModules(clock, AtomicLong(100)).apply { halted = MechanicsState.decode(saved).forgeHalt }
        val after = withModules(restarted)
        clock.advance(Duration.ofHours(1))

        assertEquals("Forge: switched off by Clement at 18:00:00 · 0 modules running", ForgeCommands.statusLine(after))
        assertEquals(
            "Switch: set by Clement at 18:00:00: « maintenance du serveur ». Nothing is deployed nor restarted until /narrator forge on lifts it.",
            ForgeCommands.status(after).text()[2],
        )
        assertEquals(
            listOf("[Narrator] the Forge is already switched off (by Clement at 18:00:00: « maintenance du serveur »); /narrator forge on lifts it."),
            ForgeCommands.off(after, "Mimosa", "encore").text(),
        )
        assertTrue(restarted.halts.isEmpty() && restarted.resumes.isEmpty(), "nothing but forge on touches the switch")

        val on = ForgeCommands.on(after, "Josiane")
        assertTrue(on.done, on.text().toString())
        assertEquals(
            listOf(
                "[Narrator] the Forge is switched back on by Josiane: modules may be deployed again.",
                "It was switched off by Clement at 18:00:00: « maintenance du serveur ». The modules it stopped stay stopped.",
            ),
            on.text(),
        )
        assertEquals(listOf("Josiane"), restarted.resumes, "lifted by the operator who typed it")
        assertNull(restarted.halted)
        assertEquals("Forge: on · 0 modules running", ForgeCommands.statusLine(after))
        val decision = after.traces.decisions.single { it.kind == DecisionRecord.Kind.OPERATOR }
        assertEquals("forge_on Josiane: the Forge switched back on; it was switched off by Clement at 18:00:00: « maintenance du serveur »", "${decision.decision} ${decision.subject}: ${decision.reason}")
        assertEquals("Clement", decision.detail!!.getValue("by").jsonPrimitive.content)

        val again = ForgeCommands.on(after, "Josiane")
        assertFalse(again.done)
        assertEquals(listOf("[Narrator] the Forge is not switched off: nothing to lift."), again.text())
        assertEquals(1, restarted.resumes.size)
    }

    @Test
    fun `a switch that could not be read back keeps the Forge off until an operator lifts it`() = runBlocking<Unit> {
        val written = Json.parseToJsonElement(MechanicsState.encode(MechanicsState.EMPTY)).jsonObject
        val damaged = JsonObject(written + ("forge_halt" to JsonPrimitive(3))).toString()
        val read = LogCapture().use { MechanicsState.decode(damaged) }
        val restarted = FakeModules(clock, AtomicLong(1)).apply { halted = read.forgeHalt }
        val after = withModules(restarted)

        assertEquals("Forge: switched off by unknown at an unknown time · 0 modules running", ForgeCommands.statusLine(after))
        assertTrue(ForgeCommands.status(after).text()[2].startsWith("Switch: set by unknown at an unknown time: « the Forge's switch could not be read when the server started: "))
        val on = ForgeCommands.on(after, "Clement")
        assertTrue(on.done)
        assertTrue(on.text()[1].startsWith("It was switched off by unknown at an unknown time: « the Forge's switch could not be read"), on.text()[1])
        assertEquals(listOf("Clement"), restarted.resumes)
        assertEquals("Forge: on · 0 modules running", ForgeCommands.statusLine(after))
    }

    // ── module list, stop ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `module list shows each deployment with its version, acts, faults and time per second`() {
        assertEquals(listOf("[Narrator] modules · none running"), ForgeCommands.moduleList(services, 1).text())
        modules.views += view(70, "orbiting_chickens", acts = 212, msPerSecond = 0.4)
        modules.views += view(73, "tag", acts = 1, faults = 1, msPerSecond = 12.5)
        assertEquals(
            listOf(
                "[Narrator] modules · 2 running (4 at most)",
                "C-70 orbiting_chickens law Bartok (until 18:10) · v1 · 212 acts, 0 faults · 0.4 ms/s",
                "C-73 tag law Bartok (until 18:10) · v1 · 1 act, 1 fault · 12.5 ms/s",
                "Stop one: /narrator module stop <id> [note] · all: /narrator forge off [reason]",
            ),
            ForgeCommands.moduleList(services, 1).text(),
        )
    }

    @Test
    fun `module stop ends one as the operator, the note checked first, and records it`() = runBlocking<Unit> {
        modules.views += view(70, "orbiting_chickens")
        val voice = ForgeCommands.stopModule(services, "Clement", "C-70", "Je l'arrête, il m'agace.")
        assertFalse(voice.done)
        assertTrue(voice.text().single().startsWith("[Narrator] refused: note must be factual and in the third person"), voice.text().toString())
        assertTrue(modules.stopped.isEmpty(), "a note in the Narrator's voice stops nothing")

        assertEquals(listOf("[Narrator] M-12 is not a module's reference (C-70, for instance)."), ForgeCommands.stopModule(services, "Clement", "M-12", null).text())
        assertEquals(listOf("[Narrator] C-71 is not a running module; running: C-70."), ForgeCommands.stopModule(services, "Clement", "71", null).text())

        val stopped = ForgeCommands.stopModule(services, "Clement", "c70", null)
        assertTrue(stopped.done, stopped.text().toString())
        assertEquals(
            listOf("[Narrator] C-70 orbiting_chickens stopped by Clement: its creatures go back to normal.", "C-70 orbiting_chickens law Bartok (until 18:10)"),
            stopped.text(),
        )
        assertEquals(listOf(FakeModules.Stopped(Ref(RefKind.CANON, 70), ForgeCommands.STOP_NOTE, Author(Source.OPERATOR, operator = "Clement"))), modules.stopped)

        modules.views += view(73, "tag")
        val noted = ForgeCommands.stopModule(services, "Mimosa", "C-73", "Le jeu a été arrêté par un opérateur.")
        assertTrue(noted.done)
        assertEquals("Le jeu a été arrêté par un opérateur.", modules.stopped.last().note)
        assertEquals(
            listOf(
                "module_stop Clement: C-70 orbiting_chickens stopped: « stopped by an operator »",
                "module_stop Mimosa: C-73 tag stopped: « Le jeu a été arrêté par un opérateur. »",
            ),
            operatorDecisions(),
        )
        assertEquals(listOf("[Narrator] C-73 is not a running module; running: none."), ForgeCommands.stopModule(services, "Clement", "C-73", null).text())
    }

    // ── The command tree ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `every documented syntax parses to a command`() {
        val dispatcher = CommandDispatcher<CommandSourceStack>()
        dispatcher.register(LiteralArgumentBuilder.literal<CommandSourceStack>("narrator").also(ForgeCommands::attach))
        fun parsed(command: String): List<String> {
            val result = dispatcher.parse(command, null)
            assertTrue(!result.reader.canRead() && result.exceptions.isEmpty() && result.context.command != null, "$command does not parse: ${result.exceptions}")
            return result.context.nodes.map { it.node.name }
        }
        assertEquals(listOf("narrator", "forge"), parsed("narrator forge"))
        assertEquals(listOf("narrator", "forge", "status"), parsed("narrator forge status"))
        assertEquals(listOf("narrator", "forge", "list"), parsed("narrator forge list"))
        assertEquals(listOf("narrator", "forge", "list", "page"), parsed("narrator forge list 2"))
        assertEquals(listOf("narrator", "forge", "show", "name"), parsed("narrator forge show statues"))
        assertEquals(listOf("narrator", "forge", "show", "name", "version"), parsed("narrator forge show statues 0"))
        assertEquals(listOf("narrator", "forge", "approve", "name"), parsed("narrator forge approve statues"))
        assertEquals(listOf("narrator", "forge", "approve", "name", "version"), parsed("narrator forge approve statues 2"))
        assertEquals(listOf("narrator", "forge", "reject", "name"), parsed("narrator forge reject statues"))
        assertEquals(listOf("narrator", "forge", "reject", "name", "version"), parsed("narrator forge reject statues 2"))
        assertEquals(listOf("narrator", "forge", "off"), parsed("narrator forge off"))
        assertEquals(listOf("narrator", "forge", "off", "reason"), parsed("narrator forge off maintenance du serveur, 10 minutes"))
        assertEquals(listOf("narrator", "forge", "on"), parsed("narrator forge on"))
        assertEquals(listOf("narrator", "module"), parsed("narrator module"))
        assertEquals(listOf("narrator", "module", "list"), parsed("narrator module list"))
        assertEquals(listOf("narrator", "module", "list", "page"), parsed("narrator module list 2"))
        assertEquals(listOf("narrator", "module", "stop", "id"), parsed("narrator module stop C-70"))
        assertEquals(listOf("narrator", "module", "stop", "id", "note"), parsed("narrator module stop C-70 Le jeu a été arrêté."))
    }
}
