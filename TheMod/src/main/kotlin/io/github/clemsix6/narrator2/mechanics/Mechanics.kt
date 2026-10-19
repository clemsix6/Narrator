package io.github.clemsix6.narrator2.mechanics

import io.github.clemsix6.narrator2.config.UiTexts
import io.github.clemsix6.narrator2.event.Envelope
import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolResult
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import java.util.UUID
import kotlin.reflect.KClass

/**
 * A mechanic refused, written for the model (M4 §4.1): with a [rule] (`challenge_limit`, `rule_limit`,
 * `pvp_off`, `mechanics_off`…) it is `DENIED (rule): message`; without, `Invalid arguments: message`, and the
 * message says how to fix the call.
 */
class MechanicRejected(val rule: String?, message: String) : Exception(message) {
    /** As a tool's answer: [ToolResult.denied] with a [rule], else [ToolResult.invalid]. */
    fun toResult(): ToolResult = if (rule != null) ToolResult.denied(rule, message.orEmpty()) else ToolResult.invalid(message.orEmpty())
}

/**
 * The mechanics engine (M4 §4), exposed by `NarratorServices.mechanics`: challenges, duels and bets, rules,
 * and their lines of the situation. Built once per server by `createMechanics` (after the memory, before
 * the Director); [DisabledMechanics] when it is off, when the memory is unavailable (the mechanics live in
 * the canon) or when it could not start.
 *
 * Its hooks ([tick], [onJoin]…) are called by `registerMechanicsHooks` on the server thread; its controls
 * by the tools (coroutines) and the player commands (server thread).
 */
interface Mechanics {
    /** False when `mechanics.enabled` is false, the memory is unavailable, or the engine failed to start. Any thread. */
    val available: Boolean

    /** Why it is not [available]; null when it is. */
    val unavailableReason: String?

    val challenges: ChallengeControl
    val rules: RuleControl

    /**
     * The `Challenges: …` and `Rules: …` lines of the situation (M4 §4.5), from the snapshot rebuilt once a
     * second: never a wait. Each at most [MechanicsLimits.SITUATION_LINE] characters; none when there is
     * nothing to say, or when unavailable. Any thread.
     */
    fun situationLines(now: Long): List<String>

    /** End of a server tick (`END_SERVER_TICK`): the effects due, the acts ready, and once a second the parts' work. Server thread. */
    fun tick(server: MinecraftServer)

    /** A player joined (before the guards' own join: settlements first, M4 §5.6). Server thread. */
    fun onJoin(player: ServerPlayer)

    fun onLeave(player: ServerPlayer)

    /** [old] respawned as [new] (or left the End), after the attachments were copied. Server thread. */
    fun onRespawn(old: ServerPlayer, new: ServerPlayer)

    /** A creature ([net.minecraft.world.entity.Mob], never a player) was born or loaded from a chunk (`mob_spawn`, M4 §8.2). Server thread. */
    fun onMobLoad(entity: Entity, level: ServerLevel)

    /**
     * `ALLOW_DEATH`, before the guards' own: false refuses the death (a duelist, M4 §5.5). A refusal must
     * have given health back first: a refused death leaves the player at 0 health or less, and the game
     * removes them 20 ticks later without a death. Server thread.
     */
    fun allowDeath(player: ServerPlayer, source: DamageSource): Boolean

    /**
     * `SERVER_STOPPING`, before the memory stops: no new act, the firings in the queue and the delayed
     * effects dropped (counted in a decision), boss bars taken down. The state stays in the world, saved by
     * the stopping save. Server thread.
     */
    fun stop()
}

/** Challenges, duels and bets (M4 §5). */
interface ChallengeControl {
    /**
     * Validates [spec] as [caller] (M4 §5.1), writes the canon row (`challenge`, mechanic `challenge`), then
     * starts tracking it: state, boss bars, bets. Throws [MechanicRejected] with the fix.
     */
    suspend fun start(spec: ChallengeSpec, caller: Caller): ChallengeView

    /** Validates [spec] (M4 §5.5), writes the canon row (mechanic `duel`) and invites both players. Throws [MechanicRejected]. */
    suspend fun proposeDuel(spec: DuelSpec, caller: Caller): ChallengeView

    /**
     * Ends challenge or duel [ref] now: [ChallengeOutcome.WON] with [winners] (among its participants),
     * [ChallengeOutcome.LOST] (nobody won) or [ChallengeOutcome.CANCELLED] (no stakes, bets go back).
     * [note] is the canon's resolution, French; [author] who asked (the Director, an operator): the engine
     * still resolves the canon as `Source.ENGINE` (M4 §3.3). Throws [MechanicRejected].
     */
    suspend fun end(ref: Ref, outcome: ChallengeOutcome, winners: List<UUID>, note: String, author: Author): ChallengeView

    /** `/duel accept|decline <id>`: what [player] is told (`UiTexts`). Server thread. */
    fun answerDuel(player: ServerPlayer, ref: Ref, accept: Boolean): Component

    /** `/bet <id> <player|nobody>`: the stack in [player]'s main hand on [on] (a participant's name, or `nobody`); what they are told. Server thread. */
    fun bet(player: ServerPlayer, ref: Ref, on: String): Component

    /** The challenges and duels in progress, invitations included, in the order they started. Any thread. */
    fun list(): List<ChallengeView>
}

/** Laws and plans (M4 §8). */
interface RuleControl {
    /** Validates [spec] as [caller] (M4 §8.5), writes the canon row (`law` or `plan`, mechanic `rule`), activates it. Throws [MechanicRejected]. */
    suspend fun create(spec: RuleSpec, caller: Caller): RuleView

    /**
     * Ends rule [ref] now (a law `retired`, a plan `cancelled`); the creatures it changed get back to normal.
     * [note] is the canon's resolution; [author] who asked. Throws [MechanicRejected].
     */
    suspend fun remove(ref: Ref, note: String, author: Author): RuleView

    /** The active rules, oldest first. Any thread. */
    fun list(): List<RuleView>
}

/**
 * What the tools and the commands show of a challenge or a duel.
 *
 * @property spec a challenge's specification; [duel] a duel's (one of the two is set).
 * @property bets how many bets were placed.
 * @property line its line of the situation (M4 §4.5), e.g. `C-50 obtain « Œuf de tortue » → all · Mimosa 0/1 · ends in 12m`.
 */
data class ChallengeView(
    val ref: Ref,
    val spec: ChallengeSpec?,
    val duel: DuelSpec?,
    val status: ChallengeStatus,
    val endsAt: Long,
    val participants: List<Participant>,
    val bets: Int,
    val line: String,
)

/**
 * What the tools and the commands show of a rule (`/narrator rule list`: trigger, effects, end, counters).
 *
 * @property line its line of the situation, e.g. `C-60 law block_break grief → punish (2 fired, until 10-25 20:15)`.
 */
data class RuleView(
    val ref: Ref,
    val spec: RuleSpec,
    val endsAt: Long,
    val fired: Int,
    val skipped: Int,
    val failures: Int,
    val line: String,
)

/** How a challenge or a duel ends early (`end_challenge`, `/narrator challenge cancel`). */
@Serializable
enum class ChallengeOutcome {
    /** The winners named get the reward, the others the penalty. */
    @SerialName("won") WON,

    /** Nobody won: every participant enrolled at the start gets the penalty. */
    @SerialName("lost") LOST,

    /** No stakes; the bets go back. */
    @SerialName("cancelled") CANCELLED,
    ;

    val id: String get() = name.lowercase()
}

/**
 * One act of the engine (M4 §4.3): a rule's firing, a player's stake. Like a Director turn for the guards:
 * its own episode, its own `already_punished`, `fair_share` and categories.
 *
 * @property id the id of its `mechanic` decision (`EngineActions.open`), the act's [Caller.firingId].
 * @property ref the challenge or rule it acts for.
 * @property caller `Caller(DIRECTOR, "mechanics:C-60", turnId = null, taskId = null, firingId = id)`.
 */
data class Firing(val id: Long, val ref: Ref?, val caller: Caller)

/**
 * One part of the engine (challenges, duels, bets, rules, boss bars), driven on the server thread by the
 * engine's host ([Engine], M4-02), which knows nothing of what each part does. The parts do not know each
 * other either: that is what lets them be written in parallel and assembled by `MechanicsService` (M4-23).
 * Every method has a default that does nothing. Public, so that the GameTests (another compilation) can
 * build parts on their own store (M4 §21.2).
 */
interface MechanicPart {
    /** The facts of the bus this part wants; the engine's subscriber lets only these through ([onEvent]). */
    val listensTo: Set<KClass<out GameEvent>> get() = emptySet()

    /** A fact of [listensTo], inline on the server thread: match and queue, in less than 0.05 ms, never a world change. */
    fun onEvent(envelope: Envelope) {}

    /** Every server tick: the effects and acts due now (a rule's delayed effect). */
    fun everyTick(server: MinecraftServer, now: Long) {}

    /** Once a second (wall clock), on the server thread: detection, deadlines, bars. */
    fun everySecond(server: MinecraftServer, now: Long) {}

    /** Its lines of the situation, built once a second into the snapshot ([Mechanics.situationLines]). */
    fun situation(now: Long): List<String> = emptyList()

    /** The server runs: rebuilt from the state (bars, edges, the creatures already loaded), after the stale entries are dropped (M4 §4.6). */
    fun started(server: MinecraftServer, now: Long) {}

    /**
     * The engine took the challenges, duels or rules [ids] out of the state, without them ending through
     * their part: the reconciliation found their canon row missing, or closed before the server started
     * (M4 §4.6). Their bets are already turned into settlements and their duelists into returns
     * (`Reconcile.drop`); take down what the part shows or changed for them (bars, creature marks). Server
     * thread.
     */
    fun dropped(ids: Set<Long>) {}

    fun onJoin(player: ServerPlayer) {}

    fun onLeave(player: ServerPlayer) {}

    fun onRespawn(old: ServerPlayer, new: ServerPlayer) {}

    fun onMobLoad(entity: Entity, level: ServerLevel) {}

    /** False refuses the death, after giving health back ([Mechanics.allowDeath]); the first part that refuses wins. */
    fun allowDeath(player: ServerPlayer, source: DamageSource): Boolean = true

    /** `SERVER_STOPPING`: drop what is queued, take down what is shown. */
    fun stopping() {}
}

/**
 * The engine when it does not run (M4 §4.1): [reason] says why (`mechanics.enabled is false`, `the memory is
 * unavailable: …`, `not wired yet`). Starting a challenge, a duel or a rule fails with
 * `DENIED (mechanics_off): the mechanics are unavailable: <reason>`; ending or removing one fails the same
 * way. Lists are empty, there is no situation line, the hooks do nothing and every death is allowed.
 *
 * [ui] gives the texts of the players' commands (`mechanics.unavailable`); null in tests, where an English
 * line answers instead.
 */
class DisabledMechanics(private val reason: String, private val ui: (() -> UiTexts)? = null) : Mechanics {
    override val available = false
    override val unavailableReason: String = reason

    override val challenges: ChallengeControl = object : ChallengeControl {
        override suspend fun start(spec: ChallengeSpec, caller: Caller): ChallengeView = throw rejected()

        override suspend fun proposeDuel(spec: DuelSpec, caller: Caller): ChallengeView = throw rejected()

        override suspend fun end(ref: Ref, outcome: ChallengeOutcome, winners: List<UUID>, note: String, author: Author): ChallengeView =
            throw rejected()

        override fun answerDuel(player: ServerPlayer, ref: Ref, accept: Boolean): Component = unavailable()

        override fun bet(player: ServerPlayer, ref: Ref, on: String): Component = unavailable()

        override fun list(): List<ChallengeView> = emptyList()
    }

    override val rules: RuleControl = object : RuleControl {
        override suspend fun create(spec: RuleSpec, caller: Caller): RuleView = throw rejected()

        override suspend fun remove(ref: Ref, note: String, author: Author): RuleView = throw rejected()

        override fun list(): List<RuleView> = emptyList()
    }

    private fun rejected() = MechanicRejected(MECHANICS_OFF, "the mechanics are unavailable: $reason")

    private fun unavailable(): Component = Component.literal(ui?.invoke()?.get("mechanics.unavailable") ?: "the mechanics are unavailable")

    override fun situationLines(now: Long): List<String> = emptyList()

    override fun tick(server: MinecraftServer) {}

    override fun onJoin(player: ServerPlayer) {}

    override fun onLeave(player: ServerPlayer) {}

    override fun onRespawn(old: ServerPlayer, new: ServerPlayer) {}

    override fun onMobLoad(entity: Entity, level: ServerLevel) {}

    override fun allowDeath(player: ServerPlayer, source: DamageSource): Boolean = true

    override fun stop() {}

    companion object {
        /** The rule of every refusal: `DENIED (mechanics_off): …`. */
        const val MECHANICS_OFF = "mechanics_off"
    }
}
