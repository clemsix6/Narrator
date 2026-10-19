package io.github.clemsix6.narrator2.mechanics

import io.github.clemsix6.narrator2.mechanics.challenge.Bets
import io.github.clemsix6.narrator2.mechanics.challenge.Challenges
import io.github.clemsix6.narrator2.mechanics.challenge.Duels
import io.github.clemsix6.narrator2.mechanics.challenge.Stakes
import io.github.clemsix6.narrator2.mechanics.rule.RuleBook
import io.github.clemsix6.narrator2.mechanics.rule.RuleEngine
import io.github.clemsix6.narrator2.mechanics.rule.RuleIds
import io.github.clemsix6.narrator2.mechanics.rule.RuleTags
import io.github.clemsix6.narrator2.mechanics.rule.RuleValidator
import io.github.clemsix6.narrator2.mechanics.rule.ServerRuleTags
import io.github.clemsix6.narrator2.mechanics.rule.ruleOnlyTools
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolCatalog
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
 * - One [EngineActions] for every act of the engine, with every tool of the catalogue and the rules' own three.
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
    services: NarratorServices,
    store: StateStore,
    ids: RuleIds,
    tags: RuleTags = ServerRuleTags(services),
    clock: Clock = services.clock,
) : Mechanics {
    private val actions = EngineActions(services, ToolCatalog.allTools(services) + ruleOnlyTools())
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

    /** Starts the engine on the world's state (M4 §4.6). Server thread, while the server starts. */
    fun start(server: MinecraftServer) {
        try {
            engine.start(server)
        } catch (e: Exception) {
            engine.stop()
            throw e
        }
    }

    override fun situationLines(now: Long): List<String> = engine.situationLines()

    override fun tick(server: MinecraftServer) = engine.tick(server)

    override fun onJoin(player: ServerPlayer) = engine.onJoin(player)

    override fun onLeave(player: ServerPlayer) = engine.onLeave(player)

    override fun onRespawn(old: ServerPlayer, new: ServerPlayer) = engine.onRespawn(old, new)

    override fun onMobLoad(entity: Entity, level: ServerLevel) = engine.onMobLoad(entity, level)

    override fun allowDeath(player: ServerPlayer, source: DamageSource): Boolean = engine.allowDeath(player, source)

    override fun stop() = engine.stop()
}
