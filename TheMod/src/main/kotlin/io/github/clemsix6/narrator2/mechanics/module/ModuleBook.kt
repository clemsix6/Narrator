package io.github.clemsix6.narrator2.mechanics.module

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.forge.ArsenalEntry
import io.github.clemsix6.narrator2.forge.DisabledForge
import io.github.clemsix6.narrator2.forge.ForgeRejected
import io.github.clemsix6.narrator2.forge.ModuleStatus
import io.github.clemsix6.narrator2.forge.sdk.ParamCheck
import io.github.clemsix6.narrator2.mechanics.ChallengeStatus
import io.github.clemsix6.narrator2.mechanics.DeploySpec
import io.github.clemsix6.narrator2.mechanics.DisabledMechanics
import io.github.clemsix6.narrator2.mechanics.ForgeHalt
import io.github.clemsix6.narrator2.mechanics.GoalKind
import io.github.clemsix6.narrator2.mechanics.MechanicRejected
import io.github.clemsix6.narrator2.mechanics.ModuleControl
import io.github.clemsix6.narrator2.mechanics.ModuleRun
import io.github.clemsix6.narrator2.mechanics.ModuleView
import io.github.clemsix6.narrator2.mechanics.RuleKind
import io.github.clemsix6.narrator2.mechanics.StateStore
import io.github.clemsix6.narrator2.mechanics.rule.RuleText
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.CanonDraft
import io.github.clemsix6.narrator2.memory.CanonKind
import io.github.clemsix6.narrator2.memory.CanonMechanic
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.memory.MemoryRejected
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The modules deployed (M5 §6.4, §6.6, §3.7): the engine's [ModuleControl], used by `deploy_module`, `stop_module` and
 * the operators' commands. A deployment is a row of the canon (`law` or `plan`, mechanic `module`) and an entry of the
 * engine's state ([store]); the modules' part ([engine]) runs it, ends it and starts it again after a restart.
 *
 * - [deploy] (M5 §6.4), the first refusal thrown: the Forge runs and is not switched off, the engine runs; the module's
 *   version (the latest `ready`; `awaiting_approval`; failed when it no longer compiles); its parameters against its
 *   manifest; the judged challenge it referees; `forge.maxActive`; then its build (`Forge.build`), its canon row
 *   (written as the Director: the engine's tools write its rows), its entry in the state and its first start, in the
 *   server thread's task (a fault there ends it at once: `FAILED: C-70 failed to start: …`); then the decision `started`.
 * - [stop]: its module's `stop(STOPPED)`, then its canon resolved by the engine with the note given; an operator's stop is
 *   told to the Director (a `MODULE` line, no priority), the Director's is not (his tool's answer tells it).
 * - [halt], [resume]: the operator's switch, kept in the state (`forge_halt`): every module ends `forge_off`, one
 *   `MODULE` line for them all, the decisions `forge halted` and `forge resumed`.
 *
 * Deployments, stops and the switch go one at a time (a lock): the limit and the state never race. Never on the server
 * thread (the engine's side runs in its task).
 */
class ModuleBook(
    private val services: NarratorServices,
    private val store: StateStore,
    private val engine: ModuleEngine,
    private val clock: Clock = services.clock,
) : ModuleControl {
    private val log = Log.forge
    private val lock = Mutex()

    override val halted: ForgeHalt? get() = store.snapshot.forgeHalt

    override val leaks: Int get() = engine.leaks

    override suspend fun deploy(spec: DeploySpec, caller: Caller): ModuleView = lock.withLock {
        val forge = services.forge
        if (!forge.available) throw MechanicRejected(DisabledForge.FORGE_OFF, "the Forge is unavailable: ${forge.unavailableReason}")
        halted?.let { throw MechanicRejected(DisabledForge.FORGE_OFF, "the Forge was switched off by ${it.by}: ${it.reason}") }
        if (!engine.running) throw MechanicRejected(DisabledMechanics.MECHANICS_OFF, ENGINE_STOPPED)
        val entry = entry(spec.module, spec.version)
        val checked = ParamCheck.check(entry.params, spec.params, engine.server.params(caller))
        spec.challenge?.let(::refereed)
        val running = store.snapshot.modules
        val max = services.settings.get().config.forge.maxActive
        if (running.size >= max) {
            val run = if (max == 1) "module runs" else "modules run"
            throw MechanicRejected(MODULE_LIMIT, "$max $run (${running.joinToString { it.ref.toString() }}); stop one first")
        }
        val built = try {
            forge.build(entry.name, entry.version)
        } catch (e: ForgeRejected) {
            throw MechanicRejected(e.rule, e.message.orEmpty(), e.failed)
        }
        val row = try {
            services.memory.store.addCanon(
                CanonDraft(
                    kind = if (spec.kind == RuleKind.LAW) CanonKind.LAW else CanonKind.PLAN,
                    statement = spec.statement,
                    quote = spec.quote,
                    players = checked.players.map { it.uuid },
                    importance = IMPORTANCE,
                    status = CanonStatus.ACTIVE,
                    mechanic = CanonMechanic.MODULE,
                ),
                Author(Source.DIRECTOR, turnId = caller.turnId, conversationId = caller.conversationId),
            ).row
        } catch (e: MemoryRejected) {
            throw MechanicRejected(e.rule, e.message.orEmpty())
        }
        val now = clock.millis()
        val run = ModuleRun(
            id = row.ref.id,
            module = built.entry.name,
            version = built.entry.version,
            hash = built.entry.hash,
            params = checked.params,
            kind = spec.kind,
            createdAt = now,
            endsAt = now + spec.minutes * MINUTE_MS,
            challenge = spec.challenge?.id,
            playerIds = checked.players.map { it.uuid.toString() },
            turnId = caller.turnId,
            conversationId = caller.conversationId,
        )
        val failed = try {
            engine.server.onServer { engine.deploy(run, built) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("${run.ref} could not be deployed; its canon row is cancelled", e)
            resolveUnstarted(run)
            throw e
        }
        if (failed != null) {
            val (ended, why) = failed
            engine.close(ended)
            throw MechanicRejected(null, "${run.ref} failed to start: $why", failed = true)
        }
        val line = "${run.ref} ${run.module} v${run.version} ${run.kind.id} until ${RuleText.time(run.endsAt, now, clock.zone)}" +
            (given(spec.params)?.let { ": $it" } ?: "") + (run.challenge?.let { "; referees C-$it" } ?: "")
        engine.deployed(run.ref, line, caller.turnId)
        engine.view(run.id, line) ?: view(run, line)
    }

    override suspend fun stop(ref: Ref, note: String, author: Author): ModuleView = lock.withLock {
        if (!engine.running) throw MechanicRejected(DisabledMechanics.MECHANICS_OFF, ENGINE_STOPPED)
        val running = store.snapshot.modules
        if (ref.kind != RefKind.CANON || running.none { it.id == ref.id }) {
            throw MechanicRejected(null, "$ref is not a running module; running: ${running.joinToString { it.ref.toString() }.ifEmpty { "none" }}")
        }
        val ended = engine.server.onServer { engine.stop(ref.id, note, author) }
            ?: throw MechanicRejected(null, "$ref is not a running module any more: it just ended")
        engine.close(ended)
        ended.view
    }

    override suspend fun halt(reason: String, operator: String): List<ModuleView> = lock.withLock {
        halted?.let { throw MechanicRejected(null, "the Forge is already switched off (by ${it.by}): /narrator forge on lifts it") }
        if (!engine.running) throw MechanicRejected(DisabledMechanics.MECHANICS_OFF, ENGINE_STOPPED)
        val switch = ForgeHalt(reason, operator, clock.millis())
        val ended = engine.server.onServer { engine.halt(switch) }
        ended.forEach { engine.close(it) }
        val stopped = ended.joinToString { it.run.ref.toString() }
        engine.tell("${ModuleEngine.FORGE_OFF_NOTE}: " + if (ended.isEmpty()) "no module ran" else "$stopped stopped")
        Decisions(services.trace, clock).record(
            Log.forge, DecisionRecord.Kind.FORGE, HALTED, operator,
            "the Forge switched off by ${ModelText.name(operator)}: « ${ModelText.escape(reason)} »; " +
                (if (ended.isEmpty()) "no module ran" else "${ended.size} ${if (ended.size == 1) "module" else "modules"} stopped ($stopped)"),
            level = Log.Level.WARN,
        )
        ended.map { it.view }
    }

    override suspend fun resume(operator: String): Unit = lock.withLock {
        val halt = halted ?: throw MechanicRejected(null, "the Forge is not switched off: nothing to lift")
        engine.server.onServer { store.update { it.copy(forgeHalt = null) } }
        val since = if (halt.at > 0) " since ${STAMP.format(Instant.ofEpochMilli(halt.at).atZone(clock.zone))}" else ""
        Decisions(services.trace, clock).record(
            Log.forge, DecisionRecord.Kind.FORGE, RESUMED, operator,
            "the Forge switched back on by ${ModelText.name(operator)} (off$since by ${ModelText.name(halt.by)}: « ${ModelText.escape(halt.reason)} »)",
        )
    }

    override fun list(): List<ModuleView> = engine.views(clock.millis())

    /**
     * The version of [module] to deploy (M5 §6.4 step 2): [version], or the latest `ready` one. A version waiting for an
     * operator is `awaiting_approval`; one that no longer compiles fails; one rejected, or a module unknown, is invalid.
     */
    private fun entry(module: String, version: Int?): ArsenalEntry {
        val arsenal = services.forge.arsenal
        val found = arsenal.entry(module, version)
        val versions = arsenal.list().filter { it.name == module }
        val entry = found ?: versions.maxByOrNull { it.version }.takeIf { version == null }
        if (entry == null) {
            if (versions.isEmpty()) {
                val ready = arsenal.list().filter { it.status == ModuleStatus.READY }.map { it.name }.distinct()
                throw MechanicRejected(null, "no module $module in your arsenal; ready: ${ready.joinToString().ifEmpty { "none" }}")
            }
            throw MechanicRejected(null, "$module has no version $version; its versions: ${versions.sortedBy { it.version }.joinToString { "v${it.version} ${it.status.id}" }}")
        }
        val named = "${entry.name} v${entry.version}"
        return when (entry.status) {
            ModuleStatus.READY -> entry
            ModuleStatus.PENDING -> throw MechanicRejected(AWAITING_APPROVAL, "$named waits for an operator's approval (/narrator forge approve ${entry.name} ${entry.version})")
            ModuleStatus.REJECTED -> throw MechanicRejected(null, "$named was rejected by an operator; deploy another version")
            ModuleStatus.BROKEN -> throw MechanicRejected(null, "$named no longer compiles: ${entry.problem ?: "its source does not build"}", failed = true)
        }
    }

    /** [challenge] must be a judged challenge that runs, which no other module referees (M5 §6.4 step 4). */
    private fun refereed(challenge: Ref) {
        val view = engine.challenges.list().firstOrNull { it.ref == challenge }
        if (view == null || view.spec?.goal != GoalKind.JUDGED || view.status != ChallengeStatus.RUNNING) {
            throw MechanicRejected(null, "$challenge is not a running judged challenge")
        }
        store.snapshot.modules.firstOrNull { it.challenge == challenge.id }?.let {
            throw MechanicRejected(null, "$challenge is refereed by ${it.ref} (${it.module}) already")
        }
    }

    /** The canon row of [run], which never started (the server stopping): cancelled, or retired for a law. */
    private suspend fun resolveUnstarted(run: ModuleRun) {
        try {
            val status = if (run.kind == RuleKind.LAW) CanonStatus.RETIRED else CanonStatus.CANCELLED
            services.memory.store.resolveCanon(run.ref, status, NOT_STARTED, emptyList(), Author(Source.ENGINE, turnId = run.turnId, conversationId = run.conversationId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("the canon row of ${run.ref} could not be resolved; the reconciliation will close it", e)
        }
    }

    private fun view(run: ModuleRun, line: String) = ModuleView(
        run.ref, run.module, run.version, run.kind, run.endsAt, run.acts, run.faults, run.challenge?.let { Ref(RefKind.CANON, it) }, 0.0, line,
    )

    companion object {
        /** The rule of the limit of deployments at once (`forge.maxActive`): `DENIED (module_limit): …`. */
        const val MODULE_LIMIT = "module_limit"

        /** The rule of a version that waits for an operator (`forge.approval = operator`): `DENIED (awaiting_approval): …`. */
        const val AWAITING_APPROVAL = "awaiting_approval"

        /** The Forge's decisions of the switch (`forge halted`, `forge resumed`). */
        const val HALTED = "halted"
        const val RESUMED = "resumed"

        /** The canon's resolution of a deployment whose state could not be written (the server stopping). */
        const val NOT_STARTED = "it never took effect: the server was stopping"

        /** A deployment's importance in the canon. */
        const val IMPORTANCE = 5

        private const val ENGINE_STOPPED = "the mechanics are unavailable: the engine is stopped"
        private const val MINUTE_MS = 60_000L
        private const val GIVEN_MAX = 300

        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss", Locale.ROOT)

        /** `player Bartok, chickens 6`: the parameters as the Director gave them; null for none. */
        private fun given(params: JsonObject): String? {
            if (params.isEmpty()) return null
            return ModelText.cut(ModelText.escape(params.entries.joinToString(", ") { (name, value) -> "$name ${text(value)}" }), GIVEN_MAX)
        }

        private fun text(element: JsonElement): String = when (element) {
            is JsonNull -> "none"
            is JsonPrimitive -> element.content
            is JsonArray -> element.joinToString(",") { text(it) }
            is JsonObject -> element.values.joinToString(" ") { text(it) }
        }
    }
}
