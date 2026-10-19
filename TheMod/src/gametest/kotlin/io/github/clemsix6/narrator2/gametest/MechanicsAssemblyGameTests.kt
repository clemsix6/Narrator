package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.core.onServerThread
import io.github.clemsix6.narrator2.gametest.GuardGameTests.Companion.basin
import io.github.clemsix6.narrator2.gametest.GuardGameTests.Companion.count
import io.github.clemsix6.narrator2.gametest.GuardGameTests.Companion.homeAt
import io.github.clemsix6.narrator2.gametest.GuardGameTests.Companion.isAt
import io.github.clemsix6.narrator2.guard.GuardStates
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.mechanics.ChallengeStatus
import io.github.clemsix6.narrator2.mechanics.ChallengeView
import io.github.clemsix6.narrator2.mechanics.MechanicsService
import io.github.clemsix6.narrator2.memory.CanonEntry
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.memory.InteractionKind
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.ToolCatalog
import io.github.clemsix6.narrator2.tool.ToolEnv
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolRunner
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.tool.mechanics.CreateRuleTool
import io.github.clemsix6.narrator2.tool.mechanics.ProposeDuelTool
import io.github.clemsix6.narrator2.tool.mechanics.RemoveRuleTool
import io.github.clemsix6.narrator2.tool.mechanics.StartChallengeTool
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.chat.ChatType
import net.minecraft.network.chat.PlayerChatMessage
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundBossEventPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.server.permissions.LevelBasedPermissionSet
import net.minecraft.world.BossEvent.BossBarColor
import net.minecraft.world.BossEvent.BossBarOverlay
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import java.util.UUID

/**
 * In-game tests of the assembly of the mechanics engine (M4-23, M4 §4, §21.2): the server's own engine, as
 * `createMechanics` built it, reached the way the game reaches it — the Director's real tools (`start_challenge`,
 * `propose_duel`, `create_rule`, `remove_rule`), the players' commands (`/duel accept`), the facts of the bus, the
 * server's ticks, and the Fabric hooks of `MechanicsHooks` (`ALLOW_DEATH`): no engine of the test's own.
 *
 * - The engine runs: the mechanics are available, the Director has his 44 tools, `/narrator status` says
 *   `Mechanics: on`.
 * - A challenge started by `start_challenge` is won at the engine's second, its reward paid by the real `give_item`,
 *   its boss bar taken down.
 * - A duel: the opponent's fatal blow is stopped at 1 health, then, in a second duel, lava is a rescue; nobody dies.
 * - A law made by `create_rule` fires on a real fact, then `remove_rule` retires it.
 *
 * The tests share the server's engine, its pace and its limits (two challenges or duels at once): they run in a
 * batch of their own ([ASSEMBLY_ENVIRONMENT]), name their own players and leave the engine as they found it.
 */
class MechanicsAssemblyGameTests {

    @GameTest(environment = ASSEMBLY_ENVIRONMENT, maxTicks = ASYNC_MAX_TICKS)
    fun theServersEngineRunsAndTheDirectorHasItsFortyFourTools(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        val runtime = helper.narrator()
        val mechanics = runtime.mechanics
        check(mechanics is MechanicsService && mechanics.available && mechanics.unavailableReason == null) { "the engine runs: $mechanics" }

        // The Director's tools, as a chapter opens them (Chapters.open).
        val env = ToolEnv(
            journalAvailable = runtime.journal.available,
            workerRoles = runtime.workers.roles,
            memoryAvailable = runtime.memory.available,
            mechanicsAvailable = mechanics.available,
        )
        val tools = ToolCatalog.toolSetFor(Role.DIRECTOR, env, runtime).names
        check(tools.size == 44) { "the Director's 44 tools: ${tools.size} $tools" }
        check(tools.containsAll(M4_TOOLS)) { "the tools of M4: ${M4_TOOLS - tools.toSet()} missing" }

        val server = helper.level.server
        val operator = server.onServerThread { TestPlayer(helper, "ao_") }
        try {
            val output = CommandOutput()
            val status = server.onServerThread {
                val source = operator.player.createCommandSourceStack().withSource(output).withPermission(LevelBasedPermissionSet.GAMEMASTER)
                server.commands.performPrefixedCommand(source, "narrator status")
                output.lines.singleOrNull { it.startsWith("Mechanics: ") }
            }
            check(status?.startsWith("Mechanics: on · ") == true) { "/narrator status: $status" }
        } finally {
            server.onServerThread { operator.close() }
        }
    }

    /** `start_challenge` as the model writes it: the bar shown at the engine's second, the egg held, won, the diamond paid, the bar down. */
    @GameTest(environment = ASSEMBLY_ENVIRONMENT, maxTicks = ASYNC_MAX_TICKS)
    fun aChallengeStartedByTheRealToolIsWonPaidAndItsBarTakenDown(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        val runtime = helper.narrator()
        val server = helper.level.server
        val bob = server.onServerThread { TestPlayer(helper, "ac_", keep = ::chatOrBars) }
        try {
            val started = call(
                runtime, StartChallengeTool(),
                """{"title": "Un œuf", "statement": "Le Narrateur a défié un joueur de tenir un œuf de tortue.", "goal": "obtain",
                   "item": "turtle_egg", "players": ["${bob.name}"], "minutes": 5, "reward": {"kind": "item", "item": "diamond"}}""",
            )
            val view = runtime.mechanics.challenges.list().single { view -> view.participants.any { it.name == bob.name } }
            check(started.status == ToolStatus.OK && started.text.startsWith("ok: challenge ${view.ref} started: obtain 1 turtle_egg « Un œuf » · ${bob.name}")) {
                "the start: ${started.text}"
            }
            check(view.status == ChallengeStatus.RUNNING && canon(runtime, view.ref).status == CanonStatus.ACTIVE) { "running, its canon open: $view" }
            awaitCondition("${bob.name}'s bar, at the engine's second") { bars(bob).size == 1 }

            server.onServerThread { bob.live.inventory.add(ItemStack(Items.TURTLE_EGG)) }
            awaitCondition("${view.ref} won") { canon(runtime, view.ref).status == CanonStatus.WON }
            check(canon(runtime, view.ref).winners.map { it.uuid } == listOf(bob.uuid)) { "won by Bob: ${canon(runtime, view.ref).winners}" }
            check(runtime.mechanics.challenges.list().none { it.ref == view.ref }) { "out of the engine" }
            awaitCondition("the diamond, paid by give_item") { server.onServerThread { count(bob.live, Items.DIAMOND) } == 1 }
            check(bars(bob).isEmpty()) { "the bar is taken down: ${bars(bob)}" }

            val history = runtime.memory.store.interactions(bob.uuid, 20).filter { it.canon == view.ref }
            check(history.any { it.kind == InteractionKind.GIFT && it.source == Source.ENGINE && it.summary.startsWith("challenge reward: ") }) { "the reward in Bob's history: $history" }
            check(history.any { it.kind == InteractionKind.CHALLENGE_RESULT && it.valence == 1 }) { "the result in Bob's history: $history" }
            val decisions = decisions(runtime, view.ref)
            check(decisions.any { it.first == "won" && it.second.startsWith("${view.ref} won by ${bob.name} in ") }) { "the engine's decision: $decisions" }
            check(decisions.any { it.first == "stake" }) { "the stake is an act of the engine: $decisions" }
        } finally {
            server.onServerThread { bob.close() }
        }
    }

    /**
     * Two duels of the server's engine, through `propose_duel` and `/duel accept`, their deaths reaching the engine by
     * the real `ALLOW_DEATH` of `MechanicsHooks`: the opponent's fatal blow is stopped at 1 health; lava is a rescue.
     * Bob lives on with his diamonds; nothing is counted against the Narrator.
     */
    @GameTest(environment = ASSEMBLY_ENVIRONMENT, maxTicks = ASYNC_MAX_TICKS)
    fun aDuelOfTheServersEngineKillsNobody(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        val runtime = helper.narrator()
        val server = helper.level.server
        val (bob, ann) = server.onServerThread { TestPlayer(helper, "ad_") to TestPlayer(helper, "ad_") }
        try {
            server.onServerThread {
                bob.makeVulnerable()
                ann.makeVulnerable()
                bob.live.inventory.add(ItemStack(Items.DIAMOND, 3))
            }

            val blow = duel(runtime, helper, bob, ann, "Le coup fatal")
            server.onServerThread {
                val player = bob.live
                player.hurtServer(helper.level, helper.level.damageSources().playerAttack(ann.live), 1_000f)
                check(!player.isDeadOrDying && player.health == 1f) { "stopped at 1 health: ${player.health}" }
                check(runtime.mechanics.challenges.list().none { it.ref == blow.ref }) { "the duel is over" }
                check(GuardStates.read(player).rescues.isEmpty()) { "no rescue: nothing to take him out of" }
            }
            val won = awaitClosed(runtime, blow.ref, CanonStatus.WON)
            check(won.winners.map { it.uuid } == listOf(ann.uuid)) { "Ann won: ${won.winners}" }
            check(won.resolution.orEmpty().contains("${ann.name}'s fatal blow on ${bob.name} was stopped at 1 hp")) { "how: ${won.resolution}" }

            val lava = duel(runtime, helper, bob, ann, "La lave")
            server.onServerThread {
                val player = bob.live
                player.removeEffect(MobEffects.RESISTANCE) // what the first duel left him
                val home = homeAt(helper, player)
                basin(helper, Blocks.LAVA)
                bob.standAt(BlockPos(1, 1, 1))
                player.health = 1f
                repeat(40) { if (runtime.mechanics.challenges.list().any { it.ref == lava.ref }) player.doTick() }
                basin(helper, Blocks.STONE)
                val live = bob.live
                check(!live.isDeadOrDying && live.health > 0f && !live.isOnFire) { "rescued, alive, put out: ${live.health}" }
                check(isAt(live, home)) { "taken out of the lava to his respawn point: ${live.position()}" }
                check(GuardStates.read(live).rescues.size == 1 && GuardStates.read(live).narratorKills.isEmpty()) { "one rescue, nothing counted" }
            }
            val rescued = awaitClosed(runtime, lava.ref, CanonStatus.WON)
            check(rescued.winners.map { it.uuid } == listOf(ann.uuid)) { "Ann won again: ${rescued.winners}" }
            check(rescued.resolution.orEmpty().contains("${bob.name} would have died (lava) and was rescued instead")) { "how: ${rescued.resolution}" }

            server.onServerThread {
                val live = bob.live
                repeat(LATER_TICKS) { live.doTick() }
                check(!live.isDeadOrDying && !live.isRemoved && live.health > 0f) { "Bob lives on: ${live.health}" }
                check(server.playerList.getPlayer(bob.uuid) === live && count(live, Items.DIAMOND) == 3) { "still in the game, his diamonds with him" }
            }
        } finally {
            server.onServerThread {
                bob.close()
                ann.close()
            }
        }
    }

    /** `create_rule`: a law that answers a word in the public chat fires on the real fact, through the real `say`; `remove_rule` retires it. */
    @GameTest(environment = ASSEMBLY_ENVIRONMENT, maxTicks = ASYNC_MAX_TICKS)
    fun aLawMadeByTheRealToolFiresAndIsRetired(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        val runtime = helper.narrator()
        val server = helper.level.server
        val bob = server.onServerThread { TestPlayer(helper, "ar_") }
        try {
            runtime.journal.barrier() // Bob's join row, which the rule's players are checked against
            val word = "assemblage"
            val created = call(
                runtime, CreateRuleTool(),
                """{"kind": "law", "statement": "Le Narrateur a décrété une loi d'essai sur un mot.", "trigger": "chat",
                   "when": {"players": ["${bob.name}"], "words": ["$word"]},
                   "effects": [{"tool": "say", "at": "actor", "args": {"text": "{actor}, la loi a parlé."}}], "minutes": 30}""",
            )
            val law = runtime.mechanics.rules.list().single { it.spec.statement == "Le Narrateur a décrété une loi d'essai sur un mot." }
            check(created.status == ToolStatus.OK && created.text.startsWith("ok: rule ${law.ref} (law) active until ")) { "the law: ${created.text}" }
            check(canon(runtime, law.ref).status == CanonStatus.ACTIVE) { "its canon" }

            server.onServerThread {
                val live = bob.live
                server.playerList.broadcastChatMessage(PlayerChatMessage.unsigned(live.uuid, "Voici le mot : $word."), live, ChatType.bind(ChatType.CHAT, live))
            }
            awaitCondition("the law's answer") { bob.chat().any { "${bob.name}, la loi a parlé." in it.content().string } }
            awaitCondition("the firing counted") { runtime.mechanics.rules.list().singleOrNull { it.ref == law.ref }?.fired == 1 }
            check(decisions(runtime, law.ref).any { it.first == "fired" && it.second == "${law.ref} ${bob.name} spoke in the public chat" }) { "the act: ${decisions(runtime, law.ref)}" }

            val removed = call(runtime, RemoveRuleTool(), """{"id": "${law.ref}", "note": "La loi d'essai a été levée."}""")
            check(removed.text == "ok: rule ${law.ref} (law) retired (1 fired)") { "removed: ${removed.text}" }
            check(runtime.mechanics.rules.list().none { it.ref == law.ref }) { "out of the engine" }
            check(canon(runtime, law.ref).status == CanonStatus.RETIRED) { "its canon retired" }
        } finally {
            server.onServerThread { bob.close() }
        }
    }

    private companion object {
        /** The batch of these tests (`test_environment/assembly.json`): they share the server's engine and its pace. */
        const val ASSEMBLY_ENVIRONMENT = "narrator2-gametest:assembly"

        val DIRECTOR = Caller(Role.DIRECTOR, "director-gametest-assembly", turnId = 4545, taskId = null)

        /** The Director's tools that only the engine brings (M4 §12.1). */
        val M4_TOOLS = listOf("start_challenge", "end_challenge", "propose_duel", "create_rule", "remove_rule", "punish", "favor")

        /** Ticks Bob lives on after the duels: longer than the resistance, and than the 20 ticks a player left at 0 health takes to leave. */
        const val LATER_TICKS = 140

        /** [tool] called by a Director's turn with [json], as the model writes it. Off the server thread. */
        suspend fun call(runtime: NarratorRuntime, tool: NarratorTool<*>, json: String): ToolResult = ToolRunner(runtime).invoke(tool, DIRECTOR, json)

        /**
         * A duel of [bob] and [ann] proposed by `propose_duel`, both accepting by `/duel accept` on the server's
         * dispatcher: running.
         */
        suspend fun duel(runtime: NarratorRuntime, helper: GameTestHelper, bob: TestPlayer, ann: TestPlayer, title: String): ChallengeView {
            val proposed = call(
                runtime, ProposeDuelTool(),
                """{"players": ["${bob.name}", "${ann.name}"], "title": "$title", "statement": "Le Narrateur a proposé un duel d'essai à deux joueurs.", "minutes": 5}""",
            )
            check(proposed.status == ToolStatus.OK) { "the proposal: ${proposed.text}" }
            val view = runtime.mechanics.challenges.list().single { it.duel?.title == title && it.participants.any { p -> p.name == bob.name } }
            val server = helper.level.server
            server.onServerThread {
                for (player in listOf(bob, ann)) server.commands.performPrefixedCommand(player.live.createCommandSourceStack(), "duel accept ${view.ref}")
            }
            val running = runtime.mechanics.challenges.list().single { it.ref == view.ref }
            check(running.status == ChallengeStatus.RUNNING) { "both accepted: it runs ($running)" }
            return running
        }

        suspend fun canon(runtime: NarratorRuntime, ref: Ref): CanonEntry = runtime.memory.store.canon(ref) ?: error("no canon row $ref")

        /** Waits for [ref]'s canon row to be closed [status] by the engine. */
        suspend fun awaitClosed(runtime: NarratorRuntime, ref: Ref, status: CanonStatus): CanonEntry {
            awaitCondition("$ref closed $status") { canon(runtime, ref).status == status }
            return canon(runtime, ref)
        }

        /** The engine's `mechanic` decisions about [ref] in the Journal, as (decision, reason). */
        suspend fun decisions(runtime: NarratorRuntime, ref: Ref): List<Pair<String, String>> {
            runtime.journal.barrier()
            return runtime.journal.read(5_000) { connection ->
                connection.prepareStatement("SELECT decision, reason FROM engine_decisions WHERE kind = 'mechanic' AND conversation_id = ? ORDER BY id").use { statement ->
                    statement.setString(1, "mechanics:$ref")
                    statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1) to rows.getString(2).orEmpty()) } }
                }
            }
        }

        fun chatOrBars(packet: Packet<*>): Boolean = packet is ClientboundSystemChatPacket || packet is ClientboundBossEventPacket

        /** The boss bars [player]'s client shows: the ids added and not removed since. */
        fun bars(player: TestPlayer): Set<UUID> {
            val shown = LinkedHashSet<UUID>()
            player.packets.filterIsInstance<ClientboundBossEventPacket>().forEach { packet ->
                packet.dispatch(object : ClientboundBossEventPacket.Handler {
                    override fun add(
                        id: UUID, name: net.minecraft.network.chat.Component, progress: Float, color: BossBarColor, overlay: BossBarOverlay,
                        darkenScreen: Boolean, playMusic: Boolean, createWorldFog: Boolean,
                    ) {
                        shown += id
                    }

                    override fun remove(id: UUID) {
                        shown -= id
                    }
                })
            }
            return shown
        }
    }
}
