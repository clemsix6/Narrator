package io.github.clemsix6.narrator2.mechanics

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.guard.Guards
import io.github.clemsix6.narrator2.mechanics.challenge.Bets
import io.github.clemsix6.narrator2.mechanics.challenge.Challenges
import io.github.clemsix6.narrator2.mechanics.challenge.Duels
import io.github.clemsix6.narrator2.mechanics.challenge.Stakes
import io.github.clemsix6.narrator2.mechanics.rule.RuleBook
import io.github.clemsix6.narrator2.mechanics.rule.RuleEngine
import io.github.clemsix6.narrator2.mechanics.rule.RuleIds
import io.github.clemsix6.narrator2.mechanics.rule.RuleTags
import io.github.clemsix6.narrator2.mechanics.rule.RuleTargets
import io.github.clemsix6.narrator2.mechanics.rule.RuleValidator
import io.github.clemsix6.narrator2.mechanics.rule.ServerRuleTags
import io.github.clemsix6.narrator2.mechanics.rule.ruleOnlyTools
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.action.favorTools
import io.github.clemsix6.narrator2.tool.action.playerActionTools
import io.github.clemsix6.narrator2.tool.action.punishTools
import io.github.clemsix6.narrator2.tool.action.worldActionTools
import io.github.clemsix6.narrator2.tool.speech.speechTools
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import java.time.Clock
import java.util.UUID

/**
 * The real mechanics engine (M4 §4, task M4-23): the parts written side by side, assembled on one [store].
 *
 * - One [EngineActions] for every act of the engine, with the tools of the rules and of the stakes only ([TOOLS]).
 * - One [Stakes], shared by the challenges and the duels (a duel's reward is checked when it is proposed).
 * - One [Bossbars], shared by the challenges (taken down in the tick a challenge ends) and the bars' part.
 * - The parts, in this order: challenges, duels, bets, bars, rules. A challenge or a duel that ends in a second is
 *   settled (bets) and drawn (bars) in that same second.
 * - [rules] is the [RuleBook]; the rules' part ([RuleEngine]) sets the rules off and ends them through it.
 * - [challenges]: `start` and `list` go to the challenges' part, `proposeDuel`, `answerDuel` and every `end` to the
 *   duels' (which hands a challenge's end to the challenges' part and tells the duelists how a duel ended), `bet`
 *   to the bets'.
 * - The hooks, the situation lines and [stop] go to the [engine]'s host, which drives the parts and reconciles
 *   the world's state with the canon once the server runs.
 *
 * Built on the server thread while the server starts, after the memory and before the Director; [start] then
 * starts the engine. [ids], [tags] and [clock] are the server's by default; the unit tests give their own.
 */
class MechanicsService(
    private val services: NarratorServices,
    private val store: StateStore,
    ids: RuleIds,
    tags: RuleTags = ServerRuleTags(services),
    clock: Clock = services.clock,
) : Mechanics {
    /** The engine's acts, with the tools of the rules and of the stakes only (M4 review, finding 23); internal for the tests. */
    internal val actions = EngineActions(services, engineTools(services))
    private val bars = Bossbars()
    private val stakes = Stakes(services, actions)
    private val bets = Bets(services, store, clock = clock)
    private val challengeParts = Challenges(services, store, bars, stakes, bets, clock)
    private val duels = Duels(services, store, challengeParts, stakes, clock)
    private val book = RuleBook(services, store, RuleValidator(services, actions, ids))
    private val ruleParts = RuleEngine(services, store, book, actions, tags, clock)

    /** The host of the parts; internal for the tests. */
    internal val engine = Engine(
        services,
        store,
        listOf(challengeParts, duels, bets, ChallengeBars(bars, store::get) { services.settings.get().ui }, ruleParts),
        clock,
    )

    override val available = true
    override val unavailableReason: String? = null

    override val challenges: ChallengeControl = object : ChallengeControl {
        override suspend fun start(spec: ChallengeSpec, caller: Caller): ChallengeView = challengeParts.start(spec, caller)

        override suspend fun proposeDuel(spec: DuelSpec, caller: Caller): ChallengeView = duels.propose(spec, caller)

        override suspend fun end(ref: Ref, outcome: ChallengeOutcome, winners: List<UUID>, note: String, author: Author): ChallengeView =
            duels.end(ref, outcome, winners, note, author)

        override fun answerDuel(player: ServerPlayer, ref: Ref, accept: Boolean): Component = duels.answer(player, ref, accept)

        override fun bet(player: ServerPlayer, ref: Ref, on: String): Component = bets.bet(player, ref, on)

        override fun list(): List<ChallengeView> = challengeParts.list()
    }

    override val rules: RuleControl = book

    /** What the guards ask about the duels while this engine runs ([Guards.isDueling]): a lookup of the duels' part. */
    private val dueling: (UUID) -> Boolean = { duels.isDueling(it) }

    /**
     * Starts the engine on the world's state (M4 §4.6), then tells the guards who duels ([Guards.isDueling], M4 review,
     * finding 19). Server thread, while the server starts. A start that fails is undone ([starting]).
     */
    fun start(server: MinecraftServer) = starting { engine.start(server) }

    /**
     * Runs [start] (the engine's start, or the tests' own) and wires the guards to the duels. When it fails, the
     * engine stops and the state goes back to what it was before (M4 review, finding 24): a start that took out
     * what ended while the server was down, and then failed, would leave canon rows that nothing resolves until a
     * later start mistakes them for a world rolled back. Then it throws. Server thread.
     */
    internal fun starting(start: () -> Unit) {
        val before = store.get()
        try {
            start()
        } catch (e: Exception) {
            engine.stop()
            store.update { before }
            log.warn("the engine's start failed: its state is back as it was in the world")
            throw e
        }
        services.guards.isDueling = dueling
    }

    override fun situationLines(now: Long): List<String> = engine.situationLines()

    override fun tick(server: MinecraftServer) = engine.tick(server)

    override fun onJoin(player: ServerPlayer) = engine.onJoin(player)

    override fun onLeave(player: ServerPlayer) = engine.onLeave(player)

    override fun onRespawn(old: ServerPlayer, new: ServerPlayer) = engine.onRespawn(old, new)

    override fun onMobLoad(entity: Entity, level: ServerLevel) = engine.onMobLoad(entity, level)

    override fun allowDeath(player: ServerPlayer, source: DamageSource): Boolean = engine.allowDeath(player, source)

    /** Stops the engine; the guards no longer see anyone in a duel (unless another engine took over since). */
    override fun stop() {
        engine.stop()
        if (services.guards.isDueling === dueling) services.guards.isDueling = { false }
    }

    companion object {
        private val log = Log.mechanics

        /**
         * The tools the engine's acts may call (M4 review, finding 23): those a rule may use ([RuleTargets.ALLOWED],
         * the rules' own three included) and those that pay a challenge's stakes (each reward's tool, `punish` for a
         * penalty). Never `run_command`, `sql` nor `delegate`, whatever a validation lets through; and `sql`'s
         * description (its schema, a database in memory) is not built while the server starts.
         */
        val TOOLS: Set<String> = RuleTargets.ALLOWED.keys + RewardKind.entries.map { it.tool } + PUNISH

        private const val PUNISH = "punish"

        /** The [TOOLS], built from the families that hold them. Throws when one is missing: a tool renamed or moved. */
        fun engineTools(services: NarratorServices): List<NarratorTool<*>> {
            val families = speechTools(services) + playerActionTools(services) + worldActionTools(services) + punishTools(services) +
                favorTools(services) + ruleOnlyTools()
            val tools = families.filter { it.name in TOOLS }
            val missing = TOOLS - tools.map { it.name }.toSet()
            check(missing.isEmpty()) { "the engine's tools are missing from their families: ${missing.sorted().joinToString()}" }
            return tools
        }
    }
}
