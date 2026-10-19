package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.command.DirectorCommands
import io.github.clemsix6.narrator2.command.MechanicsCommands
import io.github.clemsix6.narrator2.core.onServerThread
import io.github.clemsix6.narrator2.director.DirectorControl
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.director.MechanicSignal
import io.github.clemsix6.narrator2.guard.BlockRestorer
import io.github.clemsix6.narrator2.guard.Confiscation
import io.github.clemsix6.narrator2.guard.GuardsImpl
import io.github.clemsix6.narrator2.guard.LedgerPurpose
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.mechanics.Bossbars
import io.github.clemsix6.narrator2.mechanics.ChallengeControl
import io.github.clemsix6.narrator2.mechanics.ChallengeOutcome
import io.github.clemsix6.narrator2.mechanics.ChallengeSpec
import io.github.clemsix6.narrator2.mechanics.ChallengeView
import io.github.clemsix6.narrator2.mechanics.DisabledMechanics
import io.github.clemsix6.narrator2.mechanics.DuelSpec
import io.github.clemsix6.narrator2.mechanics.EngineActions
import io.github.clemsix6.narrator2.mechanics.GoalKind
import io.github.clemsix6.narrator2.mechanics.Mechanics
import io.github.clemsix6.narrator2.mechanics.RuleControl
import io.github.clemsix6.narrator2.mechanics.StateStore
import io.github.clemsix6.narrator2.mechanics.createMechanics
import io.github.clemsix6.narrator2.mechanics.challenge.Challenges
import io.github.clemsix6.narrator2.mechanics.rule.RuleBook
import io.github.clemsix6.narrator2.mechanics.rule.RuleValidator
import io.github.clemsix6.narrator2.mechanics.rule.ServerRuleIds
import io.github.clemsix6.narrator2.mechanics.rule.ruleOnlyTools
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.runtime.Settings
import io.github.clemsix6.narrator2.runtime.SettingsHolder
import io.github.clemsix6.narrator2.testing.FakeDirector
import io.github.clemsix6.narrator2.testing.MechanicsSamples
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolCatalog
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.TraceSink
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.permissions.LevelBasedPermissionSet
import net.minecraft.world.level.block.Blocks
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-game tests of the operators' commands of the mechanics (M4-21, M4 §18.1, §21.2): the output of each command.
 *
 * - Through the server's real dispatcher, typed by a game master: `/narrator traps` and `traps restore` on a trap
 *   set just before, `/narrator guards <player>` with what M4 added (pace, grace, jokers, the ledger's stakes and
 *   offerings, the trap), the `Mechanics:` line of `/narrator status`, and `challenge` and `rule` on the server's
 *   engine; then an engine off by the configuration (`createMechanics`, `mechanics.enabled` false), which the commands
 *   refuse with its reason. `traps restore` takes away every trap of the server: that test runs in a batch of its own
 *   ([COMMANDS_ENVIRONMENT]).
 * - The commands' own functions on a real engine of the test's own (the challenges' part and a rule book on states
 *   in memory, the server's canon): list, cancel, remove, and the canon closed by the engine.
 *
 * Owned by M4-21. Shared helpers: `GameTestSupport.kt`, `DirectorEndToEndTests.kt` (`runScript`).
 */
class MechanicsCommandGameTests {

    @GameTest(environment = COMMANDS_ENVIRONMENT, maxTicks = ASYNC_MAX_TICKS)
    fun theCommandsAnswerOnTheTestServer(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        val runtime = helper.narrator()
        val server = helper.level.server
        val operator = server.onServerThread { TestPlayer(helper, "mc_") }
        val output = CommandOutput()
        /** Runs [command] as a game master on the server thread; the lines it sent at once. */
        suspend fun perform(command: String): List<String> = server.onServerThread {
            output.lines.clear()
            val source = operator.player.createCommandSourceStack().withSource(output).withPermission(LevelBasedPermissionSet.GAMEMASTER)
            server.commands.performPrefixedCommand(source, command)
            output.lines.toList()
        }
        try {
            // A trap of two glass blocks against the operator, in the air of the test.
            val cells = listOf(BlockPos(1, 4, 1), BlockPos(1, 5, 1)).map(helper::absolutePos)
            server.onServerThread {
                cells.forEach { helper.level.setBlock(it, Blocks.AIR.defaultBlockState(), 3) }
                val set = BlockRestorer.place(helper.level, cells, Blocks.GLASS.defaultBlockState(), System.currentTimeMillis() + 120_000, operator.uuid, null)
                check(set.getOrNull() == 2) { "the trap: $set" }
            }
            val traps = perform("narrator traps")
            check(traps.first().startsWith("[Narrator] traps · ") && traps.first().endsWith(" pending in the loaded chunks")) { "traps: $traps" }
            check(traps.any { Regex("^${operator.name}: 2 minecraft:glass until \\d\\d:\\d\\d:\\d\\d \\(\\d+m\\d*s? left\\)$").matches(it) || it.startsWith("${operator.name}: 2 minecraft:glass until ") }) { "the operator's trap: $traps" }
            check(traps.last() == "/narrator traps restore takes them all away now.") { "the hint: $traps" }

            // What M4 added to /narrator guards: an offering waiting, another past its time, a stake; in one tick, so the
            // guards' own second never sees the entries of the test.
            val guards = runtime.guards as GuardsImpl
            val (inspected, waiting) = server.onServerThread {
                val now = runtime.clock.millis()
                val player = operator.live
                val entries = listOf(
                    Confiscation(9_101, emptyList(), "10x minecraft:diamond", now, now + 600_000, "an offering", null, LedgerPurpose.OFFERING),
                    Confiscation(9_102, emptyList(), "2x minecraft:emerald", now - 900_000, now - 1, "an offering", null, LedgerPurpose.OFFERING),
                    Confiscation(9_103, emptyList(), "12x minecraft:iron_ingot", now, now + 7 * 86_400_000L, "a bet", null, LedgerPurpose.STAKE, canon = 9_104),
                )
                guards.states.update(player) { it.copy(confiscations = it.confiscations + entries) }
                try {
                    output.lines.clear()
                    val source = operator.player.createCommandSourceStack().withSource(output).withPermission(LevelBasedPermissionSet.GAMEMASTER)
                    server.commands.performPrefixedCommand(source, "narrator guards ${operator.name}")
                    output.lines.toList() to guards.pacing(listOf(player.uuid), now).single().offerings.map { it.id }
                } finally {
                    guards.states.update(player) { state -> state.copy(confiscations = state.confiscations.filterNot { it.id in entries.map(Confiscation::id) }) }
                }
            }
            check(waiting == listOf(9_101L)) { "the pace shows only the offering still waiting for an answer (GuardsImpl.pacing): $waiting" }
            check(inspected.first() == "[Narrator] guards on ${operator.name} · chaos ${runtime.settings.get().config.chaos} of 5") { "guards: $inspected" }
            check(inspected.any { it.startsWith("Pace: punished 0 of 4 this hour · categories resting: none · offerings waiting: O-9101 until ") }) { "the pace: $inspected" }
            check("Grace: none" in inspected) { "the grace: $inspected" }
            check("Jokers: none given in the last 24 h · 0 in their inventory" in inspected) { "the jokers: $inspected" }
            check(inspected.any { it.startsWith("Offering O-9101: 10x minecraft:diamond, waiting for the Director's answer until ") }) { "the offering: $inspected" }
            check("Offering O-9102: 2x minecraft:emerald, not taken, going back as soon as there is room" in inspected) { "the offering past its time: $inspected" }
            check(inspected.any { it.startsWith("Stake #9103 on C-9104: 12x minecraft:iron_ingot, settled when C-9104 ends; back at ") }) { "the stake: $inspected" }
            check(inspected.any { it.startsWith("Trap: 2 minecraft:glass, until ") }) { "the trap: $inspected" }
            check("Nothing of the Narrator's on them." !in inspected) { "a trap is the Narrator's: $inspected" }

            val restored = perform("narrator traps restore")
            check(restored.size == 1 && Regex("^\\[Narrator] \\d+ trap blocks? taken away now\\.$").matches(restored.single())) { "restore: $restored" }
            server.onServerThread {
                check(cells.all { helper.level.getBlockState(it).isAir }) { "the glass is taken away at once" }
                check(BlockRestorer.byPlayer()[operator.uuid] == null) { "nothing left against the operator" }
            }
            check(perform("narrator traps").none { it.startsWith(operator.name) }) { "the trap is gone from the list" }
            check(perform("narrator traps restore") == listOf("[Narrator] no trap block pending in the loaded chunks: nothing to take away.")) { "a second restore" }

            // The server's engine runs (M4-23).
            check(runtime.mechanics.available) { "the server's engine: ${runtime.mechanics.unavailableReason}" }
            val status = perform("narrator status").singleOrNull { it.startsWith("Mechanics: ") }
            check(status?.startsWith("Mechanics: on · ") == true) { "the Mechanics line: $status" }
            check(perform("narrator challenge list").first().startsWith("[Narrator] challenges and duels · ")) { "challenge list" }
            check(perform("narrator rule").first().startsWith("[Narrator] rules · ")) { "rule list" }

            // The engine off by the configuration: built by createMechanics on the server's services, the commands refuse with its reason.
            val settings = runtime.settings.get()
            val offConfig = settings.config.copy(mechanics = settings.config.mechanics.copy(enabled = false))
            val configuredOff = object : NarratorServices by runtime {
                override val settings = SettingsHolder(Settings(offConfig, settings.prompts, settings.ui, settings.claude, settings.reflex))
            }
            val off = server.onServerThread { createMechanics(configuredOff) }
            check(off is DisabledMechanics && off.unavailableReason == "mechanics.enabled is false") { "off by the configuration: $off" }
            check(MechanicsCommands.statusLine(off, 0) == "Mechanics: off (mechanics.enabled is false) · 0 trap blocks pending") { MechanicsCommands.statusLine(off, 0) }
            val withoutEngine = object : NarratorServices by runtime {
                override val mechanics: Mechanics = off
            }
            val refusal = "[Narrator] the mechanics are unavailable (mechanics.enabled is false): no challenge nor rule to show or change."
            check(MechanicsCommands.challengeList(withoutEngine, 1).text() == listOf(refusal)) { "challenge list without the engine" }
            check(MechanicsCommands.ruleList(withoutEngine, 1).text() == listOf(refusal)) { "rule list without the engine" }
            check(MechanicsCommands.cancel(withoutEngine, "gametest", "C-1", null).text() == listOf(refusal)) { "cancel without the engine" }

            // The operator's decision, in the Journal.
            runtime.journal.barrier()
            val decisions = runtime.journal.read(5_000) { connection ->
                connection.prepareStatement("SELECT decision, reason FROM engine_decisions WHERE kind = 'operator' AND subject = ? ORDER BY id").use { statement ->
                    statement.setString(1, operator.name)
                    statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1) to rows.getString(2)) } }
                }
            }
            check(decisions.size == 1 && decisions.single().first == MechanicsCommands.TRAPS_RESTORE && decisions.single().second.endsWith(" taken away now.")) {
                "one operator decision, the restore: $decisions"
            }
        } finally {
            server.onServerThread { operator.close() }
        }
    }

    /**
     * The commands on a real engine: a challenge and a law, listed; the challenge cancelled (no stakes, the canon
     * cancelled with the operator's note, the Director told) and the law removed (the canon retired), each an
     * `operator` decision next to the engine's own.
     */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun challengeAndRuleCommandsDriveARealEngine(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        val runtime = helper.narrator()
        val server = helper.level.server
        val player = server.onServerThread { TestPlayer(helper, "mk_") }
        try {
            val bench = Bench(runtime)
            val services = bench.services
            val spec = ChallengeSpec(
                title = "Une pomme", statement = "Le Narrateur a lancé un défi d'essai à un joueur.", goal = GoalKind.OBTAIN, item = "apple",
                players = listOf(player.name), minutes = 10,
            )
            val challenge = bench.challenges.start(spec, CALLER)
            val law = bench.book.create(MechanicsSamples.rules.getValue("grief_law"), CALLER)

            check(MechanicsCommands.statusLine(services.mechanics, 0) == "Mechanics: on · 1 challenge · 1 rule · 0 trap blocks pending") {
                "status: ${MechanicsCommands.statusLine(services.mechanics, 0)}"
            }
            val challenges = MechanicsCommands.challengeList(services, 1).text()
            check(challenges[0] == "[Narrator] challenges and duels · 1 in progress") { "challenge list: $challenges" }
            check(challenges[1].startsWith("${challenge.ref} obtain 1 apple « Une pomme » · ${player.name} 0/1 · ends in ")) { "its line: $challenges" }
            val rules = MechanicsCommands.ruleList(services, 1).text()
            check(rules[0] == "[Narrator] rules · 1 active (${runtime.settings.get().config.mechanics.maxRules} at most)") { "rule list: $rules" }
            check(rules[1].startsWith("${law.ref} law · until ") && rules[1].endsWith(" · 0 fired, 0 skipped, 0 failed in a row")) { "its head: $rules" }
            check(rules[2] == "  block_break where grief → punish tiny (2) on the actor, actionbar to the actor") { "its trigger and effects: $rules" }

            val note = "Le défi a été annulé par un opérateur."
            val cancelled = MechanicsCommands.cancel(services, "gametest", challenge.ref.toString(), note)
            check(cancelled.done && cancelled.text()[0] == "[Narrator] ${challenge.ref} cancelled by gametest: no stakes, its bets go back.") { "cancel: ${cancelled.text()}" }
            check(cancelled.text()[1].startsWith("${challenge.ref} cancelled (ended by the operator gametest); note: « $note »")) { "the end's line: ${cancelled.text()}" }
            val cancelledRow = runtime.memory.store.canon(challenge.ref) ?: error("no canon row ${challenge.ref}")
            check(cancelledRow.status == CanonStatus.CANCELLED && cancelledRow.resolution == note) { "the challenge's canon: ${cancelledRow.status} ${cancelledRow.resolution}" }
            val told = bench.director.signals.filterIsInstance<DirectorSignal.Mechanic>().single()
            check(told.kind == MechanicSignal.CHALLENGE && told.priority && told.line == cancelled.text()[1]) { "the Director is told: $told" }
            check(MechanicsCommands.cancel(services, "gametest", challenge.ref.toString(), null).text() == listOf("[Narrator] ${challenge.ref} is not a challenge in progress; in progress: none.")) {
                "a second cancel"
            }

            val firstPerson = MechanicsCommands.removeRule(services, "gametest", law.ref.toString(), "Je lève cette loi.")
            check(!firstPerson.done && bench.book.list().size == 1) { "a note in the Narrator's voice removes nothing: ${firstPerson.text()}" }
            val lifted = "La loi a été levée par un opérateur."
            val removed = MechanicsCommands.removeRule(services, "gametest", law.ref.toString(), lifted)
            check(removed.done && removed.text()[0] == "[Narrator] ${law.ref} law retired by gametest: it fires no more.") { "remove: ${removed.text()}" }
            val lawRow = runtime.memory.store.canon(law.ref) ?: error("no canon row ${law.ref}")
            check(lawRow.status == CanonStatus.RETIRED && lawRow.resolution == lifted) { "the law's canon: ${lawRow.status} ${lawRow.resolution}" }
            check(bench.book.list().isEmpty() && bench.challenges.list().isEmpty()) { "nothing left in the engine" }
            check(MechanicsCommands.statusLine(services.mechanics, 0) == "Mechanics: on · 0 challenges · 0 rules · 0 trap blocks pending") { "status after" }

            val operator = bench.sink.kept.filter { it.kind == DecisionRecord.Kind.OPERATOR }.map { "${it.decision} ${it.subject}: ${it.reason}" }
            check(operator == listOf("challenge_cancel gametest: ${challenge.ref} cancelled: « $note »", "rule_remove gametest: ${law.ref} law retired: « $lifted »")) {
                "the operator's decisions: $operator"
            }
            val engine = bench.sink.kept.filter { it.kind == DecisionRecord.Kind.MECHANIC }.map { "${it.decision} ${it.subject}" }
            check("cancelled ${challenge.ref}" in engine && "removed ${law.ref}" in engine) { "the engine's own decisions: $engine" }
        } finally {
            server.onServerThread { player.close() }
        }
    }

    /**
     * A real engine of the test's own: the challenges' part and a rule book on states in memory, the server's
     * memory, tools and ids, a fake Director, the decisions kept; [services] has it as its engine.
     */
    private class Bench(runtime: NarratorRuntime) {
        val sink = KeptDecisions(runtime.trace)
        val director = FakeDirector(runtime.clock)
        private val base: NarratorServices = object : NarratorServices by runtime {
            override val trace: TraceSink get() = sink
            override val director: DirectorControl get() = this@Bench.director
        }
        val challenges = Challenges(base, StateStore.inMemory(), Bossbars())
        val book = RuleBook(base, StateStore.inMemory(), RuleValidator(base, EngineActions(base, ToolCatalog.allTools(base) + ruleOnlyTools()), ServerRuleIds(runtime.server)))
        val services: NarratorServices = object : NarratorServices by base {
            override val mechanics: Mechanics = object : Mechanics by runtime.mechanics {
                override val available = true
                override val unavailableReason: String? = null
                override val challenges: ChallengeControl = object : ChallengeControl {
                    override suspend fun start(spec: ChallengeSpec, caller: Caller) = this@Bench.challenges.start(spec, caller)
                    override suspend fun proposeDuel(spec: DuelSpec, caller: Caller): ChallengeView = error("no duel in this test")
                    override suspend fun end(ref: Ref, outcome: ChallengeOutcome, winners: List<UUID>, note: String, author: Author) =
                        this@Bench.challenges.end(ref, outcome, winners, note, author)
                    override fun answerDuel(player: ServerPlayer, ref: Ref, accept: Boolean): Component = error("no duel in this test")
                    override fun bet(player: ServerPlayer, ref: Ref, on: String): Component = error("no bet in this test")
                    override fun list() = this@Bench.challenges.list()
                }
                override val rules: RuleControl = book
            }
        }
    }

    /** A trace sink that keeps the decisions it passes on to the Journal. */
    private class KeptDecisions(private val inner: TraceSink) : TraceSink by inner {
        val kept = CopyOnWriteArrayList<DecisionRecord>()

        override fun decision(r: DecisionRecord) {
            kept += r
            inner.decision(r)
        }
    }

    private companion object {
        /** The batch of the test that runs `/narrator traps restore`: it takes away every trap of the server. */
        const val COMMANDS_ENVIRONMENT = "narrator2-gametest:mechanics_commands"

        val CALLER = Caller(Role.DIRECTOR, "director-gametest-commands", turnId = 4343, taskId = null)

        fun DirectorCommands.Reply.text(): List<String> = lines.map { it.string }
    }
}
