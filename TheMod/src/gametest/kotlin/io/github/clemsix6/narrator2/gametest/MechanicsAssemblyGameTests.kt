package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.core.onServerThread
import io.github.clemsix6.narrator2.gametest.GuardGameTests.Companion.basin
import io.github.clemsix6.narrator2.gametest.GuardGameTests.Companion.count
import io.github.clemsix6.narrator2.gametest.GuardGameTests.Companion.homeAt
import io.github.clemsix6.narrator2.gametest.GuardGameTests.Companion.isAt
import io.github.clemsix6.narrator2.guard.GuardStates
import io.github.clemsix6.narrator2.guard.Guards
import io.github.clemsix6.narrator2.guard.GuardsImpl
import io.github.clemsix6.narrator2.guard.LedgerPurpose
import io.github.clemsix6.narrator2.guard.PunishmentCategory
import io.github.clemsix6.narrator2.guard.Rules
import io.github.clemsix6.narrator2.guard.Verdict
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.mechanics.Bet
import io.github.clemsix6.narrator2.mechanics.ChallengeOutcome
import io.github.clemsix6.narrator2.mechanics.ChallengeStatus
import io.github.clemsix6.narrator2.mechanics.ChallengeView
import io.github.clemsix6.narrator2.mechanics.DisabledMechanics
import io.github.clemsix6.narrator2.mechanics.EngineGuardian
import io.github.clemsix6.narrator2.mechanics.MechanicsService
import io.github.clemsix6.narrator2.mechanics.MechanicsState
import io.github.clemsix6.narrator2.mechanics.Participant
import io.github.clemsix6.narrator2.mechanics.SavedPosition
import io.github.clemsix6.narrator2.mechanics.Settlement
import io.github.clemsix6.narrator2.mechanics.StateStore
import io.github.clemsix6.narrator2.mechanics.rule.RuleEngine
import io.github.clemsix6.narrator2.mechanics.rule.RuleMarks
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.CanonEntry
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.memory.InteractionKind
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.testing.MechanicsSamples
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.ToolCatalog
import io.github.clemsix6.narrator2.tool.ToolEnv
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolRunner
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.tool.action.MobAttributeKey
import io.github.clemsix6.narrator2.tool.mechanics.CreateRuleTool
import io.github.clemsix6.narrator2.tool.mechanics.ProposeDuelTool
import io.github.clemsix6.narrator2.tool.mechanics.RemoveRuleTool
import io.github.clemsix6.narrator2.tool.mechanics.StartChallengeTool
import io.github.clemsix6.narrator2.tool.speech.Speech
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
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
import net.minecraft.world.InteractionHand
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

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
 * - A duel: a duelist is out of the Narrator's reach while it runs (the guards ask the engine), the opponent's fatal
 *   blow is stopped at 1 health and the Narrator then leaves the loser alone 5 minutes; then, in a second duel with
 *   the server ticking, lava is a rescue; nobody dies.
 * - A law made by `create_rule` fires on a real fact, then `remove_rule` retires it.
 * - The engine off: its guardian, on a state of the test's own, leaves nothing of the engine frozen in the world.
 *
 * The tests share the server's engine, its pace and its limits: they run in a batch of their own
 * ([ASSEMBLY_ENVIRONMENT]), name their own players, never hold more than one challenge and one duel at once (the
 * server's `maxChallenges` is 2), and end in their `finally` whatever they started on it, the pace included: a test
 * that fails leaves nothing to the next ones (M4 review, question 3).
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
        val bob = server.onServerThread { TestPlayer(helper, "ac_", keep = ::chatOrBars).also { it.makeVulnerable() } }
        val pace = server.onServerThread { StateStore(server).get().lastChallengeAt }
        var started: Ref? = null
        try {
            val answer = call(
                runtime, StartChallengeTool(),
                """{"title": "Un œuf", "statement": "Le Narrateur a défié un joueur de tenir un œuf de tortue.", "goal": "obtain",
                   "item": "turtle_egg", "players": ["${bob.name}"], "minutes": 5, "reward": {"kind": "item", "item": "diamond"}}""",
            )
            val view = runtime.mechanics.challenges.list().single { view -> view.participants.any { it.name == bob.name } }
            started = view.ref
            check(answer.status == ToolStatus.OK && answer.text.startsWith("ok: challenge ${view.ref} started: obtain 1 turtle_egg « Un œuf » · ${bob.name}")) {
                "the start: ${answer.text}"
            }
            check(view.status == ChallengeStatus.RUNNING && canon(runtime, view.ref).status == CanonStatus.ACTIVE) { "running, its canon open: $view" }
            awaitCondition("${bob.name}'s bar, at the engine's second") { bars(bob).size == 1 }

            server.onServerThread { bob.live.inventory.add(ItemStack(Items.TURTLE_EGG)) }
            awaitCondition("${view.ref} won") { canon(runtime, view.ref).status == CanonStatus.WON }
            check(canon(runtime, view.ref).winners.map { it.uuid } == listOf(bob.uuid)) { "won by Bob: ${canon(runtime, view.ref).winners}" }
            check(runtime.mechanics.challenges.list().none { it.ref == view.ref }) { "out of the engine" }
            awaitCondition("the diamond, paid by give_item") { server.onServerThread { count(bob.live, Items.DIAMOND) } == 1 }
            // The canon is resolved before the stakes are paid (M4 review, finding 24): the engine's decision comes last.
            awaitCondition("the end of ${view.ref}, its stakes paid") { decisions(runtime, view.ref).any { it.first == "won" } }
            check(bars(bob).isEmpty()) { "the bar is taken down: ${bars(bob)}" }

            val history = runtime.memory.store.interactions(bob.uuid, 20).filter { it.canon == view.ref }
            check(history.any { it.kind == InteractionKind.GIFT && it.source == Source.ENGINE && it.summary.startsWith("challenge reward: ") }) { "the reward in Bob's history: $history" }
            check(history.any { it.kind == InteractionKind.CHALLENGE_RESULT && it.valence == 1 }) { "the result in Bob's history: $history" }
            val decisions = decisions(runtime, view.ref)
            check(decisions.any { it.first == "won" && it.second.startsWith("${view.ref} won by ${bob.name} in ") }) { "the engine's decision: $decisions" }
            check(decisions.any { it.first == "stake" }) { "the stake is an act of the engine: $decisions" }
        } finally {
            endIfRunning(runtime, listOfNotNull(started))
            // The pace as found: a challenge of this test never holds back another batch's for 10 minutes.
            server.onServerThread {
                StateStore(server).update { it.copy(lastChallengeAt = pace) }
                bob.close()
            }
        }
    }

    /**
     * Two duels of the server's engine, through `propose_duel` and `/duel accept`, their deaths reaching the engine by
     * the real `ALLOW_DEATH` of `MechanicsHooks`:
     * - while the first runs, the guards refuse anything hostile against a duelist: they ask the engine (M4 review,
     *   finding 19); the opponent's fatal blow is stopped at 1 health, and the Narrator then leaves the loser alone for
     *   5 minutes, his creatures and explosions sparing him (finding 18);
     * - in the second, lava is a rescue. The server ticks meanwhile: the level ticks Bob, so the damage cooldown the
     *   first blow left runs down, and he ticks as his client would make him (M4 review, finding 22).
     *
     * Bob lives on with his diamonds; nothing is counted against the Narrator.
     */
    @GameTest(environment = ASSEMBLY_ENVIRONMENT, maxTicks = ASYNC_MAX_TICKS)
    fun aDuelOfTheServersEngineKillsNobody(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        val runtime = helper.narrator()
        val server = helper.level.server
        val (bob, ann) = server.onServerThread { TestPlayer(helper, "ad_") to TestPlayer(helper, "ad_") }
        val started = mutableListOf<Ref>()
        try {
            server.onServerThread {
                bob.makeVulnerable()
                ann.makeVulnerable()
                bob.live.inventory.add(ItemStack(Items.DIAMOND, 3))
            }

            val blow = duel(runtime, helper, bob, ann, "Le coup fatal").also { started += it.ref }
            server.onServerThread {
                val player = bob.live
                check(runtime.guards.isDueling(bob.uuid) && runtime.guards.isDueling(ann.uuid)) { "the guards know both duel" }
                val fighting = runtime.guards.vet(player, DIRECTOR, PunishmentCategory.HARM)
                check(fighting is Verdict.Denied && fighting.rule == Rules.DUEL) { "nothing hostile against a duelist: $fighting" }

                player.hurtServer(helper.level, helper.level.damageSources().playerAttack(ann.live), 1_000f)
                check(!player.isDeadOrDying && player.health == 1f) { "stopped at 1 health: ${player.health}" }
                check(runtime.mechanics.challenges.list().none { it.ref == blow.ref }) { "the duel is over" }
                check(GuardStates.read(player).rescues.isEmpty()) { "no rescue: nothing to take him out of" }

                check(!runtime.guards.isDueling(bob.uuid)) { "no longer in a duel" }
                val shield = GuardStates.read(player).shield
                val left = (shield?.until ?: 0L) - runtime.clock.millis()
                check(shield?.reason == "duel ${blow.ref}: fatal blow stopped at 1 hp" && left in (Guards.RESCUE_SHIELD_MS - 60_000)..Guards.RESCUE_SHIELD_MS) {
                    "shielded 5 minutes, in his file: $shield"
                }
                val spared = runtime.guards.vet(player, DIRECTOR, PunishmentCategory.HARM)
                check(spared is Verdict.Denied && spared.rule == Rules.NO_PILE_ON && "(duel ${blow.ref}: fatal blow stopped at 1 hp)" in spared.message) {
                    "out of the Narrator's reach: $spared"
                }
                check((runtime.guards as GuardsImpl).spares(player)) { "spared by the Narrator's creatures and explosions" }
            }
            val won = awaitClosed(runtime, blow.ref, CanonStatus.WON)
            check(won.winners.map { it.uuid } == listOf(ann.uuid)) { "Ann won: ${won.winners}" }
            check(won.resolution.orEmpty().contains("${ann.name}'s fatal blow on ${bob.name} was stopped at 1 hp")) { "how: ${won.resolution}" }

            val lava = duel(runtime, helper, bob, ann, "La lave").also { started += it.ref }
            val home = server.onServerThread {
                val player = bob.live
                player.removeEffect(MobEffects.RESISTANCE) // what the first duel left him
                val home = homeAt(helper, player)
                basin(helper, Blocks.LAVA)
                bob.standAt(BlockPos(1, 1, 1))
                player.health = 1f
                home
            }
            try {
                ServerTicks.run(listOf(bob), RESCUE_TICKS) { runtime.mechanics.challenges.list().none { it.ref == lava.ref } }
                server.onServerThread {
                    val live = bob.live
                    check(runtime.mechanics.challenges.list().none { it.ref == lava.ref }) { "the lava ended the duel within $RESCUE_TICKS ticks" }
                    check(!live.isDeadOrDying && live.health > 0f && !live.isOnFire) { "rescued, alive, put out: ${live.health}" }
                    check(isAt(live, home)) { "taken out of the lava to his respawn point: ${live.position()}" }
                    check(GuardStates.read(live).rescues.size == 1 && GuardStates.read(live).narratorKills.isEmpty()) { "one rescue, nothing counted" }
                }
            } finally {
                server.onServerThread { basin(helper, Blocks.STONE) }
            }
            val rescued = awaitClosed(runtime, lava.ref, CanonStatus.WON)
            check(rescued.winners.map { it.uuid } == listOf(ann.uuid)) { "Ann won again: ${rescued.winners}" }
            check(rescued.resolution.orEmpty().contains("${bob.name} would have died (lava) and was rescued instead")) { "how: ${rescued.resolution}" }

            ServerTicks.run(listOf(bob), LATER_TICKS)
            server.onServerThread {
                val live = bob.live
                check(!live.isDeadOrDying && !live.isRemoved && live.health > 0f) { "Bob lives on: ${live.health}" }
                check(server.playerList.getPlayer(bob.uuid) === live && count(live, Items.DIAMOND) == 3) { "still in the game, his diamonds with him" }
            }
        } finally {
            endIfRunning(runtime, started)
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
        val statement = "Le Narrateur a décrété une loi d'essai sur un mot."
        try {
            runtime.journal.barrier() // Bob's join row, which the rule's players are checked against
            val word = "assemblage"
            val created = call(
                runtime, CreateRuleTool(),
                """{"kind": "law", "statement": "$statement", "trigger": "chat",
                   "when": {"players": ["${bob.name}"], "words": ["$word"]},
                   "effects": [{"tool": "say", "at": "actor", "args": {"text": "{actor}, la loi a parlé."}}], "minutes": 30}""",
            )
            val law = runtime.mechanics.rules.list().single { it.spec.statement == statement }
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
            runtime.mechanics.rules.list().filter { it.spec.statement == statement }.forEach { law ->
                runCatching { runtime.mechanics.rules.remove(law.ref, "La loi d'essai a été levée.", CLEANUP) }
            }
            server.onServerThread { bob.close() }
        }
    }

    /**
     * The engine off (M4 review, finding 9): `DisabledMechanics` with its guardian, on a state of the test's own as an
     * earlier start left it in the world — a duel in an arena with Cid's bet on it, a challenge ending in a few seconds
     * with Ann's bet on it, Bob's stake to give back — and two creatures a rule changed. Nothing stays frozen: at its
     * first tick the duel is released (Bob and Ann put back where they stood, Cid's stake back, its end now), the
     * creature loaded loses the rule's marks, as does the one that loads after; at his join Bob gets his stake back and
     * hears that the duel is cancelled; Ann's stake comes back at the challenge's end, not before.
     */
    @GameTest(environment = ASSEMBLY_ENVIRONMENT, maxTicks = ASYNC_MAX_TICKS)
    fun theGuardianOfAnEngineOffLeavesNothingFrozen(helper: GameTestHelper) = helper.runScript(ASYNC_MAX_TICKS) {
        val runtime = helper.narrator()
        val server = helper.level.server
        val ledger = runtime.guards.items
        val (bob, ann, cid) = server.onServerThread { listOf(TestPlayer(helper, "ag_"), TestPlayer(helper, "ag_"), TestPlayer(helper, "ag_")) }
        val creatures = mutableListOf<Mob>()
        try {
            val now = runtime.clock.millis()
            val set = server.onServerThread {
                for (x in 0..7) for (z in 0..7) helper.setBlock(x, 0, z, Blocks.STONE)
                val stood = helper.absolutePos(BlockPos(1, 1, 1))
                val dimension = helper.level.dimension().identifier().toString()
                fun saved(player: TestPlayer) = SavedPosition(player.uuid.toString(), dimension, stood.x + 0.5, stood.y.toDouble(), stood.z + 0.5, 0f, 0f, savedAt = now)
                fun stake(player: TestPlayer, item: Item, canon: Long): Long {
                    player.live.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(item, 3))
                    return ledger.hold(player.live, InteractionHand.MAIN_HAND, LedgerPurpose.STAKE, now + DAY_MS, canon, "a bet of the guardian's test")!!.id
                }
                val returns = listOf(saved(bob), saved(ann))
                bob.standAt(BlockPos(6, 1, 6))
                ann.standAt(BlockPos(6, 1, 4))
                val cidStake = stake(cid, Items.EMERALD, DUEL_ID)
                val annStake = stake(ann, Items.GOLD_INGOT, CHALLENGE_ID)
                val bobStake = stake(bob, Items.IRON_INGOT, PAST_ID)
                val duel = MechanicsSamples.invitedDuel.copy(
                    id = DUEL_ID, status = ChallengeStatus.RUNNING, startedAt = now - 60_000, endsAt = now + 240_000,
                    participants = listOf(Participant(bob.uuid.toString(), bob.name, accepted = true), Participant(ann.uuid.toString(), ann.name, accepted = true)),
                    arena = returns, bets = listOf(Bet(cid.uuid.toString(), cid.name, bob.uuid.toString(), cidStake, "3x emerald")),
                )
                val challenge = MechanicsSamples.runningChallenge.copy(
                    id = CHALLENGE_ID, startedAt = now - 60_000, endsAt = now + CHALLENGE_LEFT_MS,
                    participants = listOf(Participant(bob.uuid.toString(), bob.name)),
                    bets = listOf(Bet(ann.uuid.toString(), ann.name, bob.uuid.toString(), annStake, "3x gold_ingot")),
                )
                val state = MechanicsState(challenges = listOf(duel, challenge), settlements = listOf(Settlement(bob.uuid.toString(), bobStake)))
                creatures += marked(helper, BlockPos(4, 1, 1))
                Triple(StateStore.inMemory(state), returns, Triple(cidStake, annStake, bobStake))
            }
            val (store, returns, stakes) = set
            val (cidStake, annStake, bobStake) = stakes
            val off = DisabledMechanics("the Journal is unavailable: a test", EngineGuardian(runtime, store)) { runtime.settings.get().ui }

            server.onServerThread {
                off.tick(server) // its first: the guardian starts
                val duel = store.get().challenge(DUEL_ID)!!
                check(duel.arena.isEmpty() && duel.bets.isEmpty() && duel.endsAt <= runtime.clock.millis()) { "the duel released, its end now: $duel" }
                check(store.get().challenge(CHALLENGE_ID)!!.bets.size == 1) { "the challenge's bet waits for its end" }
                check(creatures.single().let { RuleEngine.marksOn(it).isEmpty() && !it.entityTags().contains("narrator2") && speedModifier(it) == null }) {
                    "the creature loaded is freed of the rule's marks"
                }
                creatures += marked(helper, BlockPos(4, 1, 3))
                off.onMobLoad(creatures.last(), helper.level)
                check(RuleEngine.marksOn(creatures.last()).isEmpty() && speedModifier(creatures.last()) == null) { "the creature that loads too" }
                off.onJoin(bob.live)
                val ui = runtime.settings.get().ui
                val told = Speech.chatLine(ui, ui["duel.cancelled"], private = true).string
                check(bob.chat().any { it.content().string == told }) { "Bob hears the duel is cancelled: ${bob.chat().map { it.content().string }}" }
            }
            check(decisions(runtime, Ref(RefKind.CANON, DUEL_ID)).any { it.first == EngineGuardian.RELEASED }) { "the duel's release, traced" }

            val stood = returns.first()
            awaitCondition("the duelists put back, the stakes of the duel and of Bob given back") {
                server.onServerThread {
                    off.tick(server)
                    listOf(bob, ann).all { it.live.position().distanceTo(Vec3(stood.x, stood.y, stood.z)) < 1.0 } &&
                        count(cid.live, Items.EMERALD) == 3 && count(bob.live, Items.IRON_INGOT) == 3 &&
                        ledger.held(cid.live).none { it.id == cidStake } && ledger.held(bob.live).none { it.id == bobStake }
                }
            }
            server.onServerThread {
                check(store.get().returns.isEmpty()) { "every return done" }
                if (runtime.clock.millis() < now + CHALLENGE_LEFT_MS) check(ledger.held(ann.live).any { it.id == annStake }) { "Ann's stake is held until the challenge's end" }
            }
            awaitCondition("Ann's stake back at the challenge's end") {
                server.onServerThread {
                    off.tick(server)
                    count(ann.live, Items.GOLD_INGOT) == 3 && ledger.held(ann.live).none { it.id == annStake }
                }
            }
            server.onServerThread {
                check(store.get().settlements.isEmpty() && store.get().challenge(CHALLENGE_ID)!!.bets.isEmpty()) { "nothing left to settle: ${store.get()}" }
                check(store.get().challenges.size == 2) { "both stay in the state: the engine closes them and resolves their canon when it runs again" }
            }
        } finally {
            server.onServerThread {
                creatures.forEach { it.discard() }
                listOf(bob, ann, cid).forEach { it.close() }
            }
        }
    }

    /**
     * The server's own ticks, for the duel of [aDuelOfTheServersEngineKillsNobody] (M4 review, finding 22; after
     * `DuelGameTests.ServerTicks`): a loop of `doTick` within one server tick never lets a damage cooldown run down
     * (the level does that), so the lava of a duel that starts within a second of a fatal blow hurt nobody. Here each
     * server tick, after the level ticked the players and the server's engine ran, the run's players tick as their
     * client's connection would make them (fire, lava). Registered once, at its first use; a failure ends the run,
     * never the server's tick.
     */
    private object ServerTicks {
        private val runs = CopyOnWriteArrayList<Run>()

        private class Run(val players: List<TestPlayer>, val ticks: Int, val until: () -> Boolean) {
            val done = AtomicInteger()

            @Volatile
            var over = false

            @Volatile
            var failure: Throwable? = null

            fun tick() {
                if (over) return
                try {
                    if (until() || done.get() >= ticks) {
                        over = true
                        return
                    }
                    players.map { it.live }.filterNot { it.isRemoved }.forEach { it.doTick() }
                    done.incrementAndGet()
                } catch (e: Throwable) {
                    failure = e
                    over = true
                }
            }
        }

        init {
            ServerTickEvents.END_SERVER_TICK.register { _ -> runs.forEach { it.tick() } }
        }

        /** Lets the server run [ticks] ticks, fewer once [until] holds (checked at each). Off the server thread. */
        suspend fun run(players: List<TestPlayer>, ticks: Int, until: () -> Boolean = { false }) {
            val run = Run(players, ticks, until)
            runs += run
            try {
                awaitCondition("$ticks ticks of the server", timeoutMs = 60_000) { run.over }
            } finally {
                runs -= run
            }
            run.failure?.let { throw IllegalStateException("a tick of the server failed after ${run.done.get()} ticks", it) }
        }
    }

    private companion object {
        /** The batch of these tests (`test_environment/assembly.json`): they share the server's engine and its pace. */
        const val ASSEMBLY_ENVIRONMENT = "narrator2-gametest:assembly"

        val DIRECTOR = Caller(Role.DIRECTOR, "director-gametest-assembly", turnId = 4545, taskId = null)

        /** Who ends, in a test's `finally`, what it left running on the server's engine. */
        val CLEANUP = Author(Source.OPERATOR, operator = "gametest")

        /** The Director's tools that only the engine brings (M4 §12.1). */
        val M4_TOOLS = listOf("start_challenge", "end_challenge", "propose_duel", "create_rule", "remove_rule", "punish", "favor")

        /** Ticks Bob lives on after the duels: longer than the resistance, and than the 20 ticks a player left at 0 health takes to leave. */
        const val LATER_TICKS = 140

        /** Server ticks the lava has to end the second duel: its damage cooldown is 20 at most. */
        const val RESCUE_TICKS = 200

        /** The canon ids of the guardian's test: never the server's (its state is the test's own). */
        const val DUEL_ID = 990_401L
        const val CHALLENGE_ID = 990_402L
        const val PAST_ID = 990_403L
        const val RULE_ID = 990_404L

        /** The challenge of the guardian's test ends this long after its state is set. */
        const val CHALLENGE_LEFT_MS = 4_000L

        const val DAY_MS = 24 * 60 * 60_000L

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

        /** Ends [refs] still in progress on the server's engine, cancelled: a test leaves nothing running for the next ones. */
        suspend fun endIfRunning(runtime: NarratorRuntime, refs: List<Ref>) {
            for (ref in refs) {
                if (runtime.mechanics.challenges.list().none { it.ref == ref }) continue
                runCatching { runtime.mechanics.challenges.end(ref, ChallengeOutcome.CANCELLED, emptyList(), "Annulé à la fin d'un test.", CLEANUP) }
            }
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

        /** A cow at [pos] marked by rule [RULE_ID], as the rules' part would leave it: its tags and a speed modifier. Server thread. */
        fun marked(helper: GameTestHelper, pos: BlockPos): Mob {
            val cow = helper.spawnWithNoFreeWill(EntityTypes.COW, pos)
            RuleMarks.mark(cow, RULE_ID)
            cow.getAttribute(Attributes.MOVEMENT_SPEED)!!.addPermanentModifier(
                AttributeModifier(RuleMarks.modifier(RULE_ID, MobAttributeKey.MOVEMENT_SPEED), 2.0, AttributeModifier.Operation.ADD_MULTIPLIED_BASE),
            )
            return cow
        }

        fun speedModifier(mob: Mob): AttributeModifier? = mob.getAttribute(Attributes.MOVEMENT_SPEED)!!.getModifier(RuleMarks.modifier(RULE_ID, MobAttributeKey.MOVEMENT_SPEED))

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
