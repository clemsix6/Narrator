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
import kotlinx.serialization.json.JsonObject
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
 * `pvp_off`, `mechanics_off`, `module_limit`…) it is `DENIED (rule): message`; when [failed] (M5 §6.4: a module that
 * no longer compiles, or fails to start: not the caller's fault), `FAILED: message`; otherwise `Invalid arguments:
 * message`, and the message says how to fix the call.
 */
class MechanicRejected(val rule: String?, message: String, val failed: Boolean = false) : Exception(message) {
    init {
        require(!(failed && rule != null)) { "a refusal of the mechanics is denied by a rule or failed, not both" }
    }

    /** As a tool's answer: [ToolResult.denied] with a [rule], [ToolResult.failed] when [failed], else [ToolResult.invalid]. */
    fun toResult(): ToolResult = when {
        rule != null -> ToolResult.denied(rule, message.orEmpty())
        failed -> ToolResult.failed(message.orEmpty())
        else -> ToolResult.invalid(message.orEmpty())
    }
}

/**
 * The mechanics engine (M4 §4), exposed by `NarratorServices.mechanics`: challenges, duels and bets, rules,
 * the modules of the Forge (M5 §6), and their lines of the situation. Built once per server by `createMechanics` (after the memory, before
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

    /** The modules deployed (M5 §6.4-6.7) and the Forge's switch. */
    val modules: ModuleControl

    /**
     * The `Challenges: …`, `Rules: …` and `Modules: …` lines of the situation (M4 §4.5, M5 §9.3), from the snapshot
     * rebuilt once a second: never a wait. Each at most [MechanicsLimits.SITUATION_LINE] characters; none when there
     * is nothing to say, or when unavailable. Any thread.
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
 * The modules deployed (M5 §6.4-6.7), and the Forge's switch (M5 §3.7). Task M5-11 writes it (`ModuleBook`, on the
 * engine's state); while the engine is off, it is a [DisabledModules].
 */
interface ModuleControl {
    /** The operator's switch (`/narrator forge off`), kept in the world's state; null while modules may run. Any thread. */
    val halted: ForgeHalt?

    /**
     * Loaders of ended deployments still alive two minutes and two GC cycles after their end, since the server
     * started (each also a `WARN` and a decision `forge leak`, M5 §3.7). Any thread.
     */
    val leaks: Int

    /**
     * `deploy_module` (M5 §6.4), in this order, the first refusal thrown as a [MechanicRejected]: the Forge runs and is
     * not switched off (`forge_off`), the engine runs (`mechanics_off`); the module and its version (the latest `ready`
     * when [DeploySpec.version] is null; `awaiting_approval`; failed when it no longer compiles); its parameters
     * against its manifest; the judged challenge it referees; `forge.maxActive` (`module_limit`); then it is built
     * (`Forge.build`), its canon row written (`law` or `plan`, mechanic `module`, as [caller]), its entry put in the
     * state, and its first start awaited (a second at most: failed when it faults there).
     */
    suspend fun deploy(spec: DeploySpec, caller: Caller): ModuleView

    /**
     * Stops the running module [ref] now (`stop_module`, `/narrator module stop`): `stop(STOPPED)`, its creatures back
     * to normal, its canon `retired` (a law) or `cancelled` (a plan) with [note]; [author] says who asked (an operator's
     * stop tells the Director). Throws [MechanicRejected] for a module that does not run.
     */
    suspend fun stop(ref: Ref, note: String, author: Author): ModuleView

    /**
     * The switch (M5 §3.7): every module ends (`stop(FORGE_OFF)`, canon resolved, one `MODULE` line for all), and
     * nothing is deployed nor restarted until [resume], across restarts (kept in the state). [reason]: the operator's
     * words, or a default. Returns the modules stopped.
     */
    suspend fun halt(reason: String, operator: String): List<ModuleView>

    /** Lifts the switch: modules may be deployed again. */
    suspend fun resume(operator: String)

    /** The running modules, oldest first. Any thread. */
    fun list(): List<ModuleView>
}

/**
 * What `deploy_module` asks (M5 §6.4, §9.1), its arguments as the tool decoded them.
 *
 * @property version null for the latest `ready` version.
 * @property params the module's parameters, unchecked: [ModuleControl.deploy] checks them against its manifest.
 * @property statement its canon's statement, French, third person; [quote] the Director's words (a law).
 * @property challenge a running judged challenge it referees.
 */
data class DeploySpec(
    val module: String,
    val version: Int?,
    val params: JsonObject,
    val kind: RuleKind,
    val statement: String,
    val quote: String?,
    val minutes: Int,
    val challenge: Ref?,
)

/**
 * What the tools and the commands show of a running module (M5 §9, §14).
 *
 * @property acts the acts it asked so far (refused ones included); [faults] its faults (M5 §3.7).
 * @property msPerSecond its code's time in the last second, in milliseconds.
 * @property line its line as the call that gives the view tells it: the line of its deployment from
 *   [ModuleControl.deploy], of its end from [ModuleControl.stop]; otherwise its line of the situation, e.g.
 *   `C-70 orbiting_chickens law Bartok (until 20:40)`.
 */
data class ModuleView(
    val ref: Ref,
    val module: String,
    val version: Int,
    val kind: RuleKind,
    val endsAt: Long,
    val acts: Int,
    val faults: Int,
    val challenge: Ref?,
    val msPerSecond: Double,
    val line: String,
)

/**
 * What the tools and the commands show of a challenge or a duel.
 *
 * @property spec a challenge's specification; [duel] a duel's (one of the two is set).
 * @property bets how many bets were placed.
 * @property line its line as the call that gives the view tells it: the line of its start from [ChallengeControl.start]
 *   (`C-50 started: obtain 1 turtle_egg « Œuf de tortue » · Mimosa · ends 20:15 (in 15m)`), the line of its end, stakes
 *   and bets included, from [ChallengeControl.end]; otherwise its line of the situation (M4 §4.5), e.g. `C-50 obtain
 *   « Œuf de tortue » → all · Mimosa 0/1 · ends in 12m`.
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

    /**
     * A fact of [listensTo], inline on the thread that published it (the server thread, for the facts of the game):
     * read the snapshot, match and queue, in less than 0.05 ms, never a change of the state nor of the world.
     */
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
 * The engine when it does not run (M4 §4.1): [reason] says why (`mechanics.enabled is false`, `the Journal is
 * unavailable: …`, `the memory is unavailable: …`, `it could not start: …`). Starting a challenge, a duel, a rule or a
 * module fails with `DENIED (mechanics_off): the mechanics are unavailable: <reason>`; ending, removing or stopping
 * one fails the same way. Lists are empty, there is no situation line and every death is allowed.
 *
 * [guardian], on a server, keeps what the engine left in the world from staying frozen there (M4 review, finding
 * 9): its ticks, joins and creature loads go to it; without one, the hooks do nothing. [ui] gives the texts of the
 * players' commands (`mechanics.unavailable`); null in tests, where an English line answers instead.
 */
class DisabledMechanics(
    private val reason: String,
    private val guardian: EngineGuardian? = null,
    private val ui: (() -> UiTexts)? = null,
) : Mechanics {
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

    override val modules: ModuleControl = DisabledModules(MECHANICS_OFF, "the mechanics are unavailable: $reason")

    private fun rejected() = MechanicRejected(MECHANICS_OFF, "the mechanics are unavailable: $reason")

    private fun unavailable(): Component = Component.literal(ui?.invoke()?.get("mechanics.unavailable") ?: "the mechanics are unavailable")

    override fun situationLines(now: Long): List<String> = emptyList()

    override fun tick(server: MinecraftServer) {
        guardian?.tick(server)
    }

    override fun onJoin(player: ServerPlayer) {
        guardian?.onJoin(player)
    }

    override fun onLeave(player: ServerPlayer) {}

    override fun onRespawn(old: ServerPlayer, new: ServerPlayer) {}

    override fun onMobLoad(entity: Entity, level: ServerLevel) {
        guardian?.onMobLoad(entity)
    }

    override fun allowDeath(player: ServerPlayer, source: DamageSource): Boolean = true

    override fun stop() {}

    companion object {
        /** The rule of every refusal: `DENIED (mechanics_off): …`. */
        const val MECHANICS_OFF = "mechanics_off"
    }
}

/**
 * The modules when they cannot run (M5 §11): every deployment, stop or switch is refused with
 * `DENIED (<rule>): <reason>` ([rule] `mechanics_off` with the engine off, `forge_off` until the modules are wired,
 * M5-21); nothing runs, nothing is halted, nothing leaks.
 */
class DisabledModules(private val rule: String, private val reason: String) : ModuleControl {
    override val halted: ForgeHalt? = null
    override val leaks: Int = 0

    override suspend fun deploy(spec: DeploySpec, caller: Caller): ModuleView = throw rejected()

    override suspend fun stop(ref: Ref, note: String, author: Author): ModuleView = throw rejected()

    override suspend fun halt(reason: String, operator: String): List<ModuleView> = throw rejected()

    override suspend fun resume(operator: String): Unit = throw rejected()

    override fun list(): List<ModuleView> = emptyList()

    private fun rejected() = MechanicRejected(rule, reason)
}
