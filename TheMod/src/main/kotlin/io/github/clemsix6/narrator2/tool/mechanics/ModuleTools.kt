package io.github.clemsix6.narrator2.tool.mechanics

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.forge.DisabledForge
import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.forge.ForgeRejected
import io.github.clemsix6.narrator2.journal.JournalTimeoutException
import io.github.clemsix6.narrator2.journal.JournalUnavailableException
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.mechanics.DeploySpec
import io.github.clemsix6.narrator2.mechanics.DisabledMechanics
import io.github.clemsix6.narrator2.mechanics.MechanicRejected
import io.github.clemsix6.narrator2.mechanics.MechanicsLimits
import io.github.clemsix6.narrator2.mechanics.RuleKind
import io.github.clemsix6.narrator2.mechanics.rule.RuleText
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Doc
import io.github.clemsix6.narrator2.tool.ForgeTool
import io.github.clemsix6.narrator2.tool.MaxLen
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.Range
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.trace.TaskRecord.Status
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The Director's tools of the Forge (M5 §9.1): `forge_module` (a commission to the forger), `deploy_module` and
 * `stop_module`. Each is a `ForgeTool`, shown only while the mechanics engine and the Forge run and no operator
 * switched the Forge off (`ToolEnv.forgeAvailable`, [forgeAvailable]). A chapter keeps them declared when the Forge
 * falls or is switched off later: they then answer `DENIED (forge_off): …`, except `stop_module` while the Forge is
 * merely down (what runs can always be stopped). The work is the workers' (the forger's task) and the engine's
 * (`Mechanics.modules`): validation, build, canon, state; the tools hand it over and answer the model.
 */
@Suppress("UNUSED_PARAMETER")
fun moduleTools(services: NarratorServices): List<NarratorTool<*>> = listOf(ForgeModuleTool(), DeployModuleTool(), StopModuleTool())

/**
 * Whether the Director's tools of the Forge are shown in a chapter opened now (M5 §9.1, `ToolEnv.forgeAvailable`), and
 * the arsenal in its briefing (M5 §9.3): the engine and the Forge run, and no operator switched the Forge off. Any
 * thread.
 */
fun forgeAvailable(services: NarratorServices): Boolean = forgeRefusal(services) == null

/** The arguments of `forge_module` (M5 §9.1): a commission for the forger, who knows nothing else. */
@Serializable
data class ForgeModuleArgs(
    @Doc(
        "What you want, for the forger who knows nothing else: what players see and do, who, how long, how it ends, " +
            "what it must never do.",
    )
    @MaxLen(ForgeLimits.BRIEF_MAX_CHARS)
    val brief: String,
) {
    init {
        require(brief.trim().length >= ForgeLimits.BRIEF_MIN_CHARS) {
            "brief is too short (at least ${ForgeLimits.BRIEF_MIN_CHARS} characters): say what players see, who, how long, how it ends"
        }
    }
}

/** The arguments of `deploy_module` (M5 §6.4, §9.1). [challenge]: a running judged challenge it referees. */
@Serializable
data class DeployModuleArgs(
    @Doc("A module of your arsenal (briefing), e.g. orbiting_chickens.") @MaxLen(32) val module: String,
    @Doc("A version; the latest ready when left out.") @Range(0.0, 99.0) val version: Int? = null,
    @Doc("Its parameters, as your arsenal lists them.") val params: JsonObject = JsonObject(emptyMap()),
    val kind: RuleKind,
    @Doc("What it does, one or two factual sentences in French, third person, for your canon.") @MaxLen(MechanicsLimits.STATEMENT) val statement: String,
    @Doc("Your exact words when you decreed it (law).") @MaxLen(MechanicsLimits.QUOTE) val quote: String? = null,
    // ForgeLimits.DEPLOY_MIN_MINUTES and DEPLOY_MAX_MINUTES (an annotation takes literals only).
    @Doc("How long it runs.") @Range(5.0, 10_080.0) val minutes: Int,
    @Doc("A running judged challenge (C-…) it referees: it names the winners itself.") @MaxLen(MechanicsLimits.REF) val challenge: String? = null,
)

/** The arguments of `stop_module` (M5 §9.1). */
@Serializable
data class StopModuleArgs(
    @Doc("The module, e.g. C-70.") @MaxLen(MechanicsLimits.REF) val id: String,
    @Doc("Why, one factual sentence in French, third person.") @MaxLen(MechanicsLimits.NOTE) val note: String,
)

/**
 * `forge_module` (M5 §8.1, §9.1): queues a task for the forger (`Workers`, role `forger`) with the brief, and returns
 * at once with its id; the forger's report comes back as a `TASK` event. Answers `ok: task T-31 forger started; its
 * report will arrive as a TASK event`, or `queued (the forger is busy with T-30)`: the forger takes one task at a time.
 */
class ForgeModuleTool : ForgeTool<ForgeModuleArgs>(
    name = "forge_module",
    description = "Commissions a new mechanic from the forger, a worker who writes, tests and saves a Java module for your " +
        "arsenal: several minutes. Returns at once with a task id; its report (the module, its parameters, what players will " +
        "see, its limits) comes back as a TASK event; then deploy it. Only for what a rule cannot do: state over time, timers, " +
        "creatures steered every tick, a mini-game that names its winners. Look at your arsenal first.",
    args = ForgeModuleArgs.serializer(),
    effect = ToolEffect.TASK,
) {
    override suspend fun run(ctx: ToolContext, args: ForgeModuleArgs): ToolResult {
        forgeRefusal(ctx.services)?.let { return it }
        val workers = ctx.services.workers
        val id = workers.submit(Role.FORGER, args.brief.trim(), ctx.caller).getOrElse { return ToolResult.failed(it.message ?: "the task was refused") }
        val tasks = workers.list()
        val state = if (tasks.firstOrNull { it.taskId == id }?.status != Status.QUEUED) {
            "started"
        } else {
            val busy = tasks.firstOrNull { it.role == Role.FORGER && it.status == Status.RUNNING && it.taskId != id }
            if (busy != null) "queued (the forger is busy with T-${busy.taskId})" else "queued (the workers are busy: it starts when a running task ends)"
        }
        return ToolResult.ok("ok: task T-$id ${Role.FORGER.id} $state; its report will arrive as a TASK event")
    }
}

/**
 * `deploy_module` (M5 §6.4, §9.1): hands the engine the deployment as the model wrote it (`ModuleControl.deploy`, which
 * checks the module, its version and parameters, the challenge it referees and `forge.maxActive`, builds it, writes
 * its canon row and waits for its first start). Answers `ok: module orbiting_chickens v0 deployed as C-70 (law) until
 * 20:40 (in 10m): player Bartok, chickens 6; announce it if you like` (a plan: `it stays secret`), or the engine's
 * refusal as it is (`DENIED (module_limit): …`, `Invalid arguments: params.chickens …`, `FAILED: C-70 failed to start: …`).
 */
class DeployModuleTool : ForgeTool<DeployModuleArgs>(
    name = "deploy_module",
    description = "Runs a module of your arsenal for some minutes: a law (public: announce it) or a plan (secret). The engine " +
        "runs it under your rules: its actions are refused like yours (protected, recently dead, too often, the same " +
        "kind of punishment too soon); it never gives nor takes an item and touches no block but a few temporary ones. " +
        "Recorded in your canon (C-…); MODULE events tell you how it ends. With challenge, it referees a running judged " +
        "challenge and names the winners itself.",
    args = DeployModuleArgs.serializer(),
    effect = ToolEffect.ENGINE,
    timeoutMs = DEPLOY_TIMEOUT_MS,
) {
    /** The challenge it referees, when given, is a canon reference (`C-75`, `c75`, `75`). */
    override suspend fun check(ctx: ToolContext, args: DeployModuleArgs): ToolResult? =
        if (args.challenge != null && Ref.canonOf(args.challenge) == null) {
            ToolResult.invalid("challenge is one of your running judged challenges, like C-75 (got \"${ModelText.escape(args.challenge)}\")")
        } else {
            null
        }

    override suspend fun run(ctx: ToolContext, args: DeployModuleArgs): ToolResult {
        check(ctx, args)?.let { return it }
        forgeRefusal(ctx.services)?.let { return it }
        val spec = DeploySpec(
            module = args.module.trim(),
            version = args.version,
            params = args.params,
            kind = args.kind,
            statement = args.statement.trim(),
            quote = args.quote?.trim()?.ifEmpty { null },
            minutes = args.minutes,
            challenge = args.challenge?.let(Ref::canonOf),
        )
        return engine(name) {
            val view = ctx.services.mechanics.modules.deploy(spec, ctx.caller)
            val now = ctx.clock.millis()
            val until = RuleText.time(view.endsAt, now, ctx.clock.zone)
            val given = params(args.params)?.let { ": $it" }.orEmpty()
            val referee = view.challenge?.let { "; it referees $it" }.orEmpty()
            val after = if (view.kind == RuleKind.LAW) "announce it if you like" else "it stays secret"
            ToolResult.ok(
                "ok: module ${view.module} v${view.version} deployed as ${view.ref} (${view.kind.id}) until $until " +
                    "(in ${ToolText.duration(view.endsAt - now)})$given$referee; $after",
            )
        }
    }

    private companion object {
        /** The parameters an answer repeats, at most. */
        const val PARAMS_MAX = 300

        /** `player Bartok, chickens 6, players Bartok,Mimosa, center 120 64 -30 the_nether`: the parameters as given; null for none. */
        fun params(params: JsonObject): String? {
            if (params.isEmpty()) return null
            val text = params.entries.joinToString(", ") { (name, value) -> "$name ${value(value)}" }
            return ModelText.cut(ModelText.escape(text), PARAMS_MAX)
        }

        private fun value(element: JsonElement): String = when (element) {
            is JsonPrimitive -> element.content
            is JsonArray -> element.joinToString(",") { value(it) }
            is JsonObject -> element.values.joinToString(" ") { value(it) }
        }
    }
}

/**
 * `stop_module` (M5 §6.6, §9.1): stops one of the running modules now, as the Director (`ModuleControl.stop`): its
 * creatures back to normal, its canon resolved with the note; no `MODULE` event follows (the answer is it). Answers
 * `ok: module C-70 (orbiting_chickens) stopped: 212 acts, 0 faults`. It works while the Forge is down (its modules may
 * still run: stopping is always safe); once an operator switched the Forge off, nothing runs and it is refused like the
 * other two (`DENIED (forge_off)`).
 */
class StopModuleTool : ForgeTool<StopModuleArgs>(
    name = "stop_module",
    description = "Stops one of your running modules now; its creatures get back to normal.",
    args = StopModuleArgs.serializer(),
    effect = ToolEffect.ENGINE,
    timeoutMs = STOP_TIMEOUT_MS,
) {
    /** The id is a canon reference (`C-70`, `c70`, `70`). */
    override suspend fun check(ctx: ToolContext, args: StopModuleArgs): ToolResult? =
        if (Ref.canonOf(args.id) == null) ToolResult.invalid("id is one of your running modules, like C-70 (got \"${ModelText.escape(args.id)}\")") else null

    override suspend fun run(ctx: ToolContext, args: StopModuleArgs): ToolResult {
        check(ctx, args)?.let { return it }
        (haltRefusal(ctx.services) ?: engineRefusal(ctx.services))?.let { return it }
        val modules = ctx.services.mechanics.modules
        val ref = Ref.canonOf(args.id)!!
        val running = modules.list()
        if (running.none { it.ref == ref }) {
            return ToolResult.invalid("$ref is not a running module; running: ${running.joinToString { it.ref.toString() }.ifEmpty { "none" }}")
        }
        val author = Author(Source.DIRECTOR, turnId = ctx.caller.turnId, conversationId = ctx.caller.conversationId)
        return engine(name) {
            val view = modules.stop(ref, args.note.trim(), author)
            ToolResult.ok("ok: module ${view.ref} (${view.module}) stopped: ${count(view.acts, "act")}, ${count(view.faults, "fault")}")
        }
    }

    private fun count(n: Int, what: String): String = "$n $what${if (n == 1) "" else "s"}"
}

/**
 * Why the tools of the Forge cannot deploy nor commission now (M5 §6.4 step 1): the Forge does not run or an operator
 * switched it off (`DENIED (forge_off)`), the engine does not run (`DENIED (mechanics_off)`); null when they can.
 */
private fun forgeRefusal(services: NarratorServices): ToolResult? {
    val forge = services.forge
    if (!forge.available) return ToolResult.denied(DisabledForge.FORGE_OFF, "the Forge is unavailable: ${forge.unavailableReason}")
    return haltRefusal(services) ?: engineRefusal(services)
}

/** `DENIED (forge_off): the Forge was switched off by <operator>: <reason>` while an operator's switch holds (M5 §3.7); null otherwise. */
private fun haltRefusal(services: NarratorServices): ToolResult? =
    services.mechanics.modules.halted?.let { ToolResult.denied(DisabledForge.FORGE_OFF, "the Forge was switched off by ${it.by}: ${it.reason}") }

/** `DENIED (mechanics_off): the mechanics are unavailable: …` while the engine does not run (the words of `DisabledModules`); null when it does. */
private fun engineRefusal(services: NarratorServices): ToolResult? {
    val mechanics = services.mechanics
    return if (mechanics.available) null else ToolResult.denied(DisabledMechanics.MECHANICS_OFF, "the mechanics are unavailable: ${mechanics.unavailableReason}")
}

/** Runs [block], a call of the engine: its refusals, the Forge's and the Journal's failures become the answer. */
private suspend fun engine(tool: String, block: suspend () -> ToolResult): ToolResult = try {
    block()
} catch (e: MechanicRejected) {
    e.toResult()
} catch (e: ForgeRejected) {
    e.toResult()
} catch (e: JournalUnavailableException) {
    Log.forge.warn("$tool: ${e.message}")
    ToolResult.failed(e.message.orEmpty())
} catch (e: JournalTimeoutException) {
    Log.forge.warn("$tool: ${e.message}")
    ToolResult.failed(e.message.orEmpty())
}

/** A deployment waits for the build (the compiler thread: 10 s at most), the canon (the Journal) and its first start (1 s). */
private const val DEPLOY_TIMEOUT_MS = 30_000L

/** A stop waits for the module's `stop`, the canon (the Journal) and the server thread, like `remove_rule`. */
private const val STOP_TIMEOUT_MS = 15_000L
