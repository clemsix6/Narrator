package io.github.clemsix6.narrator2.tool.action

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.guard.Peril
import io.github.clemsix6.narrator2.guard.Rules
import io.github.clemsix6.narrator2.guard.Verdict
import io.github.clemsix6.narrator2.mechanics.MechanicsLimits
import io.github.clemsix6.narrator2.mechanics.PunishmentId
import io.github.clemsix6.narrator2.mechanics.punish.Prepared
import io.github.clemsix6.narrator2.mechanics.punish.Primitives
import io.github.clemsix6.narrator2.mechanics.punish.PunishmentCatalog
import io.github.clemsix6.narrator2.mechanics.punish.PunishmentPlan
import io.github.clemsix6.narrator2.mechanics.punish.Step
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Doc
import io.github.clemsix6.narrator2.tool.MaxLen
import io.github.clemsix6.narrator2.tool.MechanicsTool
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.Range
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.tool.answer
import io.github.clemsix6.narrator2.tool.at
import io.github.clemsix6.narrator2.tool.chaos
import io.github.clemsix6.narrator2.tool.live
import io.github.clemsix6.narrator2.tool.online
import io.github.clemsix6.narrator2.tool.refuse
import io.github.clemsix6.narrator2.tool.shownName
import io.github.clemsix6.narrator2.tool.speech.Speech
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.Mob
import java.util.UUID

/**
 * The catalogue of punishments (M4 §6.3): `punish`, a `MechanicsTool` of effect `WORLD` that opens its own
 * episode with the punishment's category, arms the death guard for its perils, then runs its steps through
 * the real tools (or the catalogue's primitives) in the same act.
 */
fun punishTools(services: NarratorServices): List<NarratorTool<*>> = listOf(PunishTool())

/** The arguments of `punish` (M4 §6.3). */
@Serializable
data class PunishArgs(
    @Doc("Name of the online player.") @MaxLen(MechanicsLimits.PLAYER_NAME) val player: String,
    val punishment: PunishmentId,
    @Doc("1 a pinch … 5 the full wrath; capped by the chaos level.") @Range(1.0, 5.0) val severity: Int,
    @Doc("Why, one factual line, French, third person (« a traité le Narrateur de dieu de pacotille »); kept in their history.")
    @MaxLen(MechanicsLimits.REASON)
    val reason: String,
    @Doc("guests: their name.") @MaxLen(32) val name: String? = null,
)

/**
 * `punish` (M4 §6.3): a punishment of the catalogue ([PunishmentCatalog]), composed of the real tools' calls and the
 * catalogue's primitives, all in the caller's act (the Director's turn, or an engine's firing):
 * 1. its dry checks ([check]): not at chaos 0, a severity the chaos level allows, a reason;
 * 2. on the server thread, in one tick: what can fail without changing anything (the player alive, the terrain of a
 *    trap, a top to put them on, something to shuffle, the creatures allowed and room for them), so that a
 *    punishment that cannot happen opens no episode; then the episode itself, `Guards.hostile` with the
 *    punishment's category until its end, as the act's one `punish` of the player (a refusal comes back as it is:
 *    protected, chaos 0, grace, a duel, no piling on, already punished or already punished by this act, fair share,
 *    the category resting); then the death guard for its perils; then the primitive;
 * 3. the steps, in order, through the real tools: their own hostile checks see the act's episode and pass, each
 *    strikes its own category. A step that fails is said; the others stay. When nothing at all was done, the
 *    punishment is withdrawn from the guards (`Guards.withdraw`): it counts nowhere.
 *
 * The answer's first line says who, what, how hard and why: `Sanctions` makes it the summary of the player's
 * `punishment` interaction.
 */
class PunishTool : MechanicsTool<PunishArgs>(
    name = "punish",
    description = "Punishes a player with a ready-made, composed punishment of the catalogue, at a severity from 1 to 5 " +
        "(capped by the chaos level): tiny, moon_gravity, slow_hands, heavy_feet, hunger, butterfingers (inventory shuffled, " +
        "nothing lost), guests (named zombies in pumpkins; spawn dresses them up) and slime_rain (creatures after that player alone, who never kill), skyward, " +
        "tree_top, smite, cage (a glass cage) and web (cobwebs): " +
        "traps of a few seconds, set in thin air only, taken away by themselves. Refused like any punishment: protected, " +
        "recently dead, already punished, too many this hour, or the same kind of punishment too recently. The reason goes " +
        "into their history.",
    args = PunishArgs.serializer(),
    effect = ToolEffect.WORLD,
    timeoutMs = 10_000,
) {
    private val log = Log.tool
    private val setAttribute = SetAttributeTool()
    private val applyEffect = ApplyEffectTool()
    private val particles = ParticlesTool()
    private val spawn = SpawnTool()
    private val launch = LaunchTool()
    private val lightning = LightningTool()

    /** Not at chaos 0; a severity the chaos level allows; a reason, and a name for the guests, with something to read. */
    override suspend fun check(ctx: ToolContext, args: PunishArgs): ToolResult? {
        val caps = ctx.guards.caps()
        if (!caps.hostileAllowed) return ToolResult.denied(Rules.CHAOS_0, "the chaos level is 0: no hostile action against any player.")
        PunishmentCatalog.severityRefusal(args.severity, caps, ctx.chaos)?.let { return ToolResult.invalid(it) }
        if (Speech.clean(args.reason).isEmpty()) return ToolResult.invalid("reason has nothing to read once cleaned: say why, one factual line")
        if (args.punishment == PunishmentId.GUESTS && args.name != null && Speech.clean(args.name).isEmpty()) {
            return ToolResult.invalid("name has nothing to show once formatting codes are removed; write plain text")
        }
        return null
    }

    override suspend fun run(ctx: ToolContext, args: PunishArgs): ToolResult = answer {
        check(ctx, args)?.let { return@answer it }
        val ref = ctx.online(args.player)
        val name = args.name?.takeIf { args.punishment == PunishmentId.GUESTS }?.let { Speech.clean(it) }
        val plan = PunishmentCatalog.plan(args.punishment, args.severity, ctx.guards.caps(), ref.name, name)
        val until = ctx.clock.millis() + plan.seconds * 1_000L
        val opened = ctx.onServer { server -> open(ctx, server, ref, plan, until) }
        val outcomes = opened.steps.map { step -> Outcome(step, runStep(ctx, ref, plan.punishment, step, opened)) }
        if (nothingDone(opened, outcomes)) {
            // Every step failed: not a punishment the player bore, so not one the guards count (M4 review, finding 39).
            ctx.onServer { server -> server.playerList.getPlayer(ref.uuid)?.let { ctx.guards.withdraw(it, ctx.caller) } }
        }
        report(ctx, ref, args, plan, until, opened, outcomes)
    }

    /** Whether neither the primitive nor any step did anything. */
    private fun nothingDone(opened: Opened, outcomes: List<Outcome>): Boolean =
        opened.done?.isSuccess != true && outcomes.none { it.result.result.status == ToolStatus.OK }

    /**
     * What the punishment opened, on the server thread: [steps] (a spawn may be fewer creatures than planned),
     * the primitive's outcome ([done]), what the creature cap took off ([notes]), and the player's creatures before
     * the steps ([before]), to tell the new ones.
     */
    private class Opened(val steps: List<Step>, val done: Result<String>?, val notes: List<String>, val before: Set<UUID>)

    /** What one step did: [result] is the tool's answer; [summoned], the creatures a spawn brought. */
    private class Outcome(val step: Step, val result: StepResult)

    private class StepResult(val result: ToolResult, val summoned: Int = 0)

    /**
     * Step 2 of the class comment, on the server thread: what may fail first, then the episode, the death guard and
     * the primitive, in the same tick. Refuses (nothing changed) when the punishment cannot happen or the guards
     * refuse it.
     */
    private fun open(ctx: ToolContext, server: MinecraftServer, ref: PlayerRef, plan: PunishmentPlan, until: Long): Opened {
        val player = server.live(ref)
        if (player.isDeadOrDying) refuse(ToolResult.failed("${ref.shownName} is dead (on the respawn screen); nothing was done"))
        val caps = ctx.guards.caps()
        val prepared: Prepared? = plan.primitive?.let { primitive ->
            Primitives.prepare(primitive, player, caps, plan.seconds).getOrElse { refuse(ToolResult.failed("${it.message}; nothing was done")) }
        }
        val notes = mutableListOf<String>()
        val steps = plan.steps.map { step ->
            if (step !is Step.Spawn) return@map step
            val type = BuiltInRegistries.ENTITY_TYPE.get(Identifier.withDefaultNamespace(step.args.entity)).orElseThrow().value()
            ctx.guards.spawns.check(type, player.level()).let { if (it is Verdict.Denied) refuse(ToolResult.denied(it)) }
            val alive = ctx.guards.mobs.count()
            val room = caps.livingMobs - alive
            if (room <= 0) {
                refuse(
                    ToolResult.denied(
                        SpawnTool.MOB_CAP,
                        "the Narrator has $alive creatures in the world, at most ${caps.livingMobs} at chaos ${ctx.chaos}: none more can be " +
                            "summoned now; the others vanish at the end of their lifetime.",
                    ),
                )
            }
            if (room >= step.args.count) return@map step
            notes += "only $room ${step.args.entity}s: the Narrator has $alive creatures in the world, at most ${caps.livingMobs} at chaos ${ctx.chaos}"
            step.copy(args = step.args.copy(count = room))
        }
        val before = if (steps.any { it is Step.Spawn }) Primitives.summonedFor(player).keys else emptySet()
        // Once per act and player (M4 review, finding 16): a second punish of the same act is refused already_punished.
        val verdict = ctx.guards.hostile(player, ctx.caller, until, plan.category, plan.punishment)
        if (verdict is Verdict.Denied) refuse(ToolResult.denied(verdict))
        plan.peril?.let { ctx.guards.armDeathGuard(player.uuid, plan.seconds, it) }
        prepared?.fallSeconds?.takeIf { it > 0 }?.let { ctx.guards.armDeathGuard(player.uuid, it, Peril.FALL) }
        val done = prepared?.apply(ctx.caller.act)
        done?.exceptionOrNull()?.let { log.warn("punish ${plan.punishment.id} on ${ref.name}: the ${plan.punishment.id} failed after the guards allowed it: ${it.message}") }
        return Opened(steps, done, notes, before)
    }

    /**
     * Runs [step] of [punishment] through its real tool, in the punishment's act; an exception becomes the step's
     * failure. Its creatures are [punishment]'s: the player's alone, and they never kill (M4-POST).
     */
    private suspend fun runStep(ctx: ToolContext, ref: PlayerRef, punishment: PunishmentId, step: Step, opened: Opened): StepResult {
        val result = try {
            when (step) {
                is Step.Attribute -> StepResult(setAttribute.run(ctx, step.args))
                is Step.Effect -> StepResult(applyEffect.run(ctx, step.args))
                is Step.Particles -> StepResult(particles.run(ctx, step.args))
                is Step.Launch -> StepResult(launch.run(ctx, step.args))
                is Step.Lightning -> StepResult(lightning.run(ctx, step.args))
                is Step.Spawn -> {
                    val result = spawn.run(ctx, step.args, punishment)
                    val summoned = if (result.status != ToolStatus.OK) 0 else ctx.onServer { server -> summoned(server, ref, step, opened.before) }
                    StepResult(result, summoned)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            StepResult(ToolResult.failed(e.message ?: e.javaClass.simpleName))
        }
        val line = result.result.text.lineSequence().first()
        if (result.result.status == ToolStatus.OK) log.debug { "punish on ${ref.name}: ${step.tool} $line" } else log.info("punish on ${ref.name}: ${step.tool} $line")
        return result
    }

    /** The creatures [step] summoned for [ref] (those that were not there [before]); the slimes made of size 1. Server thread. */
    private fun summoned(server: MinecraftServer, ref: PlayerRef, step: Step.Spawn, before: Set<UUID>): Int {
        val player = server.playerList.getPlayer(ref.uuid) ?: return 0
        val new: Collection<Mob> = Primitives.summonedFor(player).filterKeys { it !in before }.values
        if (step.tiny) Primitives.shrink(new)
        return new.size
    }

    /**
     * `ok: Bartok punished, tiny (severity 2) for « … »: scale ×0.5, poof particles, until 20:21:10 (in 2m)`, then
     * the values the chaos level capped and the steps that failed. When nothing at all was done, the first failure.
     */
    private fun report(
        ctx: ToolContext,
        ref: PlayerRef,
        args: PunishArgs,
        plan: PunishmentPlan,
        until: Long,
        opened: Opened,
        outcomes: List<Outcome>,
    ): ToolResult {
        val done = mutableListOf<String>()
        val failed = mutableListOf<ToolResult>()
        opened.done?.fold({ done += it }, { failed += ToolResult.failed(it.message ?: "the ${plan.punishment.id} failed") })
        for (outcome in outcomes) {
            if (outcome.result.result.status == ToolStatus.OK) done += phrase(outcome) else failed += outcome.result.result
        }
        val notFor = failed.map { "not done: ${it.text}" }
        if (done.isEmpty()) {
            val first = failed.first()
            return ToolResult(first.status, "${ref.shownName}'s ${plan.punishment.id} failed: " + failed.joinToString("; ") { it.text })
        }
        val reason = ModelText.escape(Speech.clean(args.reason))
        val lasting = if (plan.lasting) ", until ${ctx.at(until)} (in ${ToolText.duration(plan.seconds * 1_000L)})" else ""
        val text = "ok: ${ref.shownName} punished, ${plan.punishment.id} (severity ${plan.severity}) for « $reason »: ${done.joinToString(", ")}$lasting" +
            (plan.capped + opened.notes + notFor).joinToString("") { "; $it" }
        return ToolResult.ok(text, listOf(ref))
    }

    /** What a step that went well did, briefly: `scale ×0.5`, `slowness II`, `3 zombies named « Comité » going for them`. */
    private fun phrase(outcome: Outcome): String = when (val step = outcome.step) {
        is Step.Attribute -> "${step.args.attribute.id} ×${ToolText.number(step.args.multiply ?: 1.0)}"
        is Step.Effect -> "${step.args.effect} ${ToolText.roman(step.args.level)}"
        is Step.Particles -> "${step.args.particle.id} particles"
        is Step.Launch -> "launched up at strength ${ToolText.number(step.args.strength)} with no fall damage"
        is Step.Lightning -> outcome.result.result.text.removePrefix("ok: ")
        is Step.Spawn -> {
            val count = outcome.result.summoned
            val planned = step.args.count
            val fewer = if (count < planned) " (of $planned: no more free ground)" else ""
            if (step.tiny) {
                "$count tiny slimes falling around them$fewer"
            } else {
                val baby = if (step.args.baby) "baby " else ""
                val named = step.args.name?.let { " named « ${ModelText.escape(it)} »" }.orEmpty()
                "$count $baby${step.args.entity}s$named going for them$fewer"
            }
        }
    }
}
