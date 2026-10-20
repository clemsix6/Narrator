package io.github.clemsix6.narrator2.mechanics.module

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.core.onServerThread
import io.github.clemsix6.narrator2.core.runOnServerThread
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.director.MechanicSignal
import io.github.clemsix6.narrator2.event.Envelope
import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.forge.ArsenalEntry
import io.github.clemsix6.narrator2.forge.Built
import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.forge.api.GameFact
import io.github.clemsix6.narrator2.forge.api.ParamType
import io.github.clemsix6.narrator2.forge.api.StopReason
import io.github.clemsix6.narrator2.forge.api.World
import io.github.clemsix6.narrator2.forge.host.Fault
import io.github.clemsix6.narrator2.forge.host.LeakWatch
import io.github.clemsix6.narrator2.forge.host.ModuleHost
import io.github.clemsix6.narrator2.forge.host.ModuleThreads
import io.github.clemsix6.narrator2.forge.sdk.ActQueue
import io.github.clemsix6.narrator2.forge.sdk.ActRunner
import io.github.clemsix6.narrator2.forge.sdk.ArgsView
import io.github.clemsix6.narrator2.forge.sdk.CreatureControl
import io.github.clemsix6.narrator2.forge.sdk.FactQueue
import io.github.clemsix6.narrator2.forge.sdk.Facts
import io.github.clemsix6.narrator2.forge.sdk.GroundView
import io.github.clemsix6.narrator2.forge.sdk.ModuleActs
import io.github.clemsix6.narrator2.forge.sdk.ModuleCall
import io.github.clemsix6.narrator2.forge.sdk.ModuleCreatures
import io.github.clemsix6.narrator2.forge.sdk.ModuleGame
import io.github.clemsix6.narrator2.forge.sdk.ParamLookup
import io.github.clemsix6.narrator2.forge.sdk.Scheduler
import io.github.clemsix6.narrator2.forge.sdk.ServerParamLookup
import io.github.clemsix6.narrator2.forge.sdk.StorageMap
import io.github.clemsix6.narrator2.forge.sdk.WorldView
import io.github.clemsix6.narrator2.mechanics.ChallengeControl
import io.github.clemsix6.narrator2.mechanics.MechanicPart
import io.github.clemsix6.narrator2.mechanics.MechanicsLimits
import io.github.clemsix6.narrator2.mechanics.ModuleRun
import io.github.clemsix6.narrator2.mechanics.ModuleView
import io.github.clemsix6.narrator2.mechanics.EngineActions
import io.github.clemsix6.narrator2.mechanics.ForgeHalt
import io.github.clemsix6.narrator2.mechanics.RuleKind
import io.github.clemsix6.narrator2.mechanics.StateStore
import io.github.clemsix6.narrator2.mechanics.rule.RuleText
import io.github.clemsix6.narrator2.memory.Author
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Mob
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import kotlin.reflect.KClass

/**
 * What the modules' engine needs of the game server (M5 §6.4, §6.5): [GameModuleServer] on a server; the unit tests,
 * which have none, give their own.
 */
interface ModuleServer {
    /** Runs [task] on the server thread at its next turn, or drops it once the server stopped (an act's outcome settled late). Any thread. */
    fun execute(task: Runnable)

    /** Runs [block] on the server thread and waits for it (a deployment, a stop, the switch). From a coroutine, never the server thread. */
    suspend fun <T> onServer(block: () -> T): T

    /** The reads of the world (`World`) of a deployment whose own creatures are those [own] tells. Server thread. */
    fun world(own: (Entity) -> Boolean): World

    /** The dimension of the online player [name] (`overworld`), or null: a module moves a player within their own. Server thread. */
    fun dimensionOf(name: String): String?

    /** How the parameters of a deployment by [caller] are checked: the players the Journal knows, where they stand, the dimensions. */
    fun params(caller: Caller): ParamLookup
}

/** The server's side of the modules' engine: the server thread, [WorldView], [ServerParamLookup]. */
class GameModuleServer(private val services: NarratorServices) : ModuleServer {
    override fun execute(task: Runnable) {
        services.server.runOnServerThread { task.run() }
    }

    override suspend fun <T> onServer(block: () -> T): T = services.server.onServerThread(SERVER_TIMEOUT_MS, block)

    override fun world(own: (Entity) -> Boolean): World = WorldView(services.server, services.players, own)

    override fun dimensionOf(name: String): String? = services.server.playerList.getPlayerByName(name)?.let { CreatureControl.placeOf(it).dimension() }

    override fun params(caller: Caller): ParamLookup = ServerParamLookup(services, caller)

    private companion object {
        /** A deployment starts its module in the server thread's task: two startup calls of 50 ms at most, and the wait for the turn. */
        const val SERVER_TIMEOUT_MS = 5_000L
    }
}

/** How a deployment ended (M5 §6.6): the `detail.ending` of its `module_ended` decision, what its module's `stop` learns, and its canon's status. */
enum class ModuleEnding(val stop: StopReason?, val law: CanonStatus, val plan: CanonStatus) {
    /** Its time ran out (also while the server was down: at its first second, then without `stop`). */
    EXPIRED(StopReason.EXPIRED, CanonStatus.RETIRED, CanonStatus.EXPIRED),

    /** The module ended itself (`ctx.end(why)`). */
    FULFILLED(StopReason.ENDED, CanonStatus.RETIRED, CanonStatus.FULFILLED),

    /** The Director (`stop_module`) or an operator (`/narrator module stop`) stopped it. */
    REMOVED(StopReason.STOPPED, CanonStatus.RETIRED, CanonStatus.CANCELLED),

    /** Its faults disabled it (M5 §3.7): its `stop` is never called. */
    DISABLED(null, CanonStatus.RETIRED, CanonStatus.CANCELLED),

    /** An operator switched the Forge off (`/narrator forge off`). */
    FORGE_OFF(StopReason.FORGE_OFF, CanonStatus.RETIRED, CanonStatus.CANCELLED),

    /** After a restart, its version could not be built again, or did not load (M5 §6.7). */
    RESTART_FAILED(null, CanonStatus.RETIRED, CanonStatus.CANCELLED),
    ;

    val id: String get() = name.lowercase()
}

/**
 * The modules at work (M5 §6.5-6.7): the part of the mechanics engine that runs the deployments. The book ([ModuleBook],
 * `ModuleControl`) deploys, stops and switches them through it; this part delivers their facts, fires their timers,
 * calls back their acts, ends them, and starts them again after a restart.
 *
 * - **One deployment** = one [ModuleHost] (its loader, meter, faults) on the engine's one [ModuleThreads], and its
 *   [RunContext] assembled from the SDK's real pieces. Every call of the module's code goes through its act queue's
 *   `invocation` (the acts it asks leave together, as one act of the engine), which has the host run it: its
 *   constructor and `start` ([ModuleHost.start]: the context behind the host's proxies, never bare), its facts, timers,
 *   callbacks and `stop` ([ModuleHost.call], [ModuleHost.stop]). After each call: disabled ([ModuleHost.disabled]) → it
 *   ends `disabled`; asked to end ([RunContext.endAsked]) → `fulfilled`.
 * - **Each tick** ([everyTick]), each deployment in the order they were deployed: its facts waiting (at most
 *   [ForgeLimits.FACTS_PER_TICK]), its timers due, its acts' callbacks. All the modules' code over
 *   [ForgeLimits.TICK_MS] in one tick: a `WARN`, once a minute at most, naming the slowest.
 * - **A busy machine** (M5-LOAD): a call its host skipped (the module thread did not start it in time) is asked again
 *   first at the next tick, nothing lost; while a call given up still runs ([ModuleHost.busy]), the deployment's tick
 *   waits — its timers keep counting, its facts and callbacks wait in their queues. Neither is a fault.
 * - **Each second** ([everySecond]): its time is up → `expired`; the host's second (*slowness*); its creatures gone
 *   (`CreatureGone` facts); its note to the Director when the gap allows; its storage and counters into the state when
 *   they changed; its facts dropped (a decision `facts_dropped` a minute at most); one deployment's memory gauge (each
 *   once a minute, every 10 s without allocation metering); the loaders that leak (`forge leak`); a stuck Forge
 *   (too many module threads lost) disables them all.
 * - **Facts** ([onEvent], inline on the bus's thread): translated once ([Facts.of]) and queued for each deployment that
 *   subscribed to their class; [onMobLoad]: a creature of a deployment comes back to it (or back to normal once its
 *   deployment is gone), a wild one is a `CreatureAppeared` fact.
 * - **The end** ([finish], server thread, then [close], a coroutine; M5 §6.6): `stop` of its module (but when disabled
 *   or never started), its pieces closed (queued acts dropped), out of the state, its creatures given back, its loader let
 *   go and watched ([LeakWatch]); then its canon resolved by the engine ([Source.ENGINE]), one decision `module_ended`
 *   (`detail.ending`: [ModuleEnding]) and, but for the Director's own stop, a start that failed and the switch, a `MODULE`
 *   line.
 * - **The restart** ([started], M5 §6.7): each deployment of the state is built again (`Forge.build`, off the server
 *   thread) and started at the next tick with `restarted()` and its storage; its creatures, saved with the world, come
 *   back to it as they load (from the start: its creatures are its own before it is built). One past its end, or the
 *   Forge switched off, ends at the first second, without `stop`; one that cannot be built ends `restart_failed`.
 * - **The server stops** ([stopping]): each module's `stop(SERVER_STOPPING)`, its storage kept in the state, its queued
 *   acts dropped and counted, its loader let go; its creatures stay in the world, marked, for the restart.
 *
 * Hooks on the server thread; [onEvent] from any thread; [views] and [situation] read a snapshot (any thread).
 *
 * @param threads the module threads of the whole engine (one caller: the server thread).
 * @param runner the engine's acts, with the tools of the modules (`EngineActRunner`).
 * @param challenges the engine's challenges, for the judged ones a module referees.
 * @param noteFault notes a deployment's fault on its version of the arsenal (`module_list` shows it to the forger).
 * @param area the creatures this part looks after: every one by default; a GameTest gives its own area.
 */
class ModuleEngine(
    private val services: NarratorServices,
    private val store: StateStore,
    private val threads: ModuleThreads,
    private val runner: ActRunner,
    internal val challenges: ChallengeControl,
    internal val server: ModuleServer = GameModuleServer(services),
    private val clock: Clock = services.clock,
    private val leakWatch: LeakWatch = LeakWatch(clock),
    private val noteFault: (ModuleRun, Fault) -> Unit = { _, _ -> },
    private val area: (Entity) -> Boolean = { true },
) : MechanicPart {
    private val log = Log.forge
    private val decisions get() = Decisions(services.trace, clock)

    /** The deployments running, being built again, or waiting for their first second, in the order they were deployed. Replaced, never changed in place: read from any thread. */
    @Volatile
    private var live: List<Deployment> = emptyList()

    /** The builds of a restart, back from the Forge, waiting for the next tick. */
    private val builds = ConcurrentLinkedQueue<Build>()

    /** The act queues' coroutines: cancelled when the server stops. */
    private val job = SupervisorJob(services.scope.coroutineContext[Job])
    private val scope = CoroutineScope(services.scope.coroutineContext + job)

    private var begun = false

    @Volatile
    private var stopped = false

    private var tickReportedAt = Long.MIN_VALUE
    private var slowTicks = 0

    /** Whether it runs: started and not stopped. Any thread. */
    val running: Boolean get() = begun && !stopped

    /** The loaders found leaking since the server started (`ModuleControl.leaks`). */
    val leaks: Int get() = leakWatch.leaks

    override val listensTo: Set<KClass<out GameEvent>> = Facts.EVENTS

    // ── One deployment ──

    private enum class Phase {
        /** Waiting for its version (a restart's build), or for its first second (past its end, or the Forge off). */
        WAITING,
        RUNNING,
        ENDED,
    }

    /** What a restart's build gave back: [built], or why it could not be built ([problem]). */
    private class Build(val deployment: Deployment, val built: Built?, val problem: String?)

    /** A call its host skipped, to ask again: its name, its body, the host's operation it was (a start), if any. */
    private class Skipped(val what: String, val body: () -> Unit, val operation: ((ModuleHost) -> ModuleHost.Called?)?)

    /** Why a deployment did not start: its [fault] (its constructor or `start` faulted), or a [problem] before any code of it ran. */
    internal class NotStarted(val text: String, val fault: Fault?)

    /**
     * One deployment: its act queue and creatures from its creation (creatures that load while it is built again come
     * back to it), its context and host once its version is there ([launch]).
     */
    private inner class Deployment(run: ModuleRun, val restarted: Boolean) {
        val id = run.id
        val ref = run.ref
        val label = "${run.ref} ${run.module}"

        /** As in the state: what it saves each second (its storage, its counters). */
        @Volatile
        var run: ModuleRun = run

        /** Its counters as it (re)started: the acts and faults before this run of the server. */
        val baseActs = run.acts
        val baseFaults = run.faults

        @Volatile
        var phase = Phase.WAITING

        val queue = ActQueue(ref, runner, scope, clock, ModuleCall { what, body -> call(what, body) }, Executor { server.execute(it) })
        val creatures = CreatureControl(services, id, queue)

        @Volatile
        var context: RunContext? = null
        var host: ModuleHost? = null

        @Volatile
        var entry: ArsenalEntry? = null

        /** The host's start or stop, run by the next invocation of the queue in place of a call of its body ([hosted]). */
        private var operation: ((ModuleHost) -> ModuleHost.Called?)? = null

        /** Nanoseconds its calls held the server thread since it started, and at the last second. */
        var spent = 0L
        private var spentAtSecond = 0L

        @Volatile
        var msPerSecond = 0.0

        @Volatile
        var faults = 0

        /** Whether its calls' allocation is measured (`jdk.management`): the gauge once a minute, else every 10 seconds. */
        var metered = false
        var gaugeAt = Long.MAX_VALUE

        var factsDropped = 0
        var factsSince = 0L
        var factsToldAt = Long.MIN_VALUE

        val acts: Int get() = baseActs + queue.asked

        /** The calls its host skipped (asked too late for the module thread, or while a call given up still ran): asked again first. */
        private val skipped = ArrayDeque<Skipped>()

        /** Runs the host's [operation] (its start or stop) as one invocation: the acts it asks leave together. Server thread. */
        fun hosted(what: String, operation: (ModuleHost) -> ModuleHost.Called?) {
            this.operation = operation
            queue.invocation(what) {}
        }

        /**
         * The calls its host skipped, asked again in their order, each one invocation; false once the deployment ended or
         * its host waits again ([busy]). Server thread.
         */
        fun replay(): Boolean {
            repeat(skipped.size) {
                val call = skipped.removeFirst()
                operation = call.operation
                queue.invocation(call.what, call.body)
                if (!after(this) || busy()) return false
            }
            return true
        }

        /** Whether a call of its module given up still runs: its tick waits (its host settles it, which may disable it). */
        fun busy(): Boolean = host?.busy() == true

        /** One call of the module, for its act queue: the host's operation if one waits, else [body] through the host. */
        private fun call(what: String, body: () -> Unit) {
            val host = host ?: return
            val operation = operation
            this.operation = null
            val called = if (operation != null) operation(host) else host.call(what, body)
            if (called != null) {
                spent += called.nanos
                if (called.allocated >= 0) metered = true
                if (called.skipped) skipped.addLast(Skipped(what, body, operation))
            }
        }

        /** Its milliseconds of the last second. */
        fun second() {
            msPerSecond = (spent - spentAtSecond) / 1e6
            spentAtSecond = spent
        }
    }

    // ── Deploying (the book's), starting, restarting ──

    /**
     * Puts [run] in the state and starts it with [built] (M5 §6.4 step 8), in the server thread's task of `deploy_module`:
     * the tool knows at once whether it started. Null when it started; else the end of its failed start (`disabled`, its
     * canon to resolve, no `MODULE` line: the tool's answer tells it) and why, for [close]. Server thread.
     */
    internal fun deploy(run: ModuleRun, built: Built): Pair<Ended, String>? {
        check(running) { "the modules' engine does not run" }
        store.update { it.copy(modules = it.modules + run) }
        val d = Deployment(run, restarted = false)
        live = live + d
        val failed = launch(d, built)
        if (failed == null) {
            after(d)
            return null
        }
        val ended = finish(d, ModuleEnding.DISABLED, "disabled: it failed to start: ${failed.text}", by = null, line = null) ?: return null
        return ended to failed.text
    }

    /**
     * Makes [d]'s context and host from [built] and starts its module ([ModuleHost.start], one invocation). Null when it
     * started; else why not (its parameters no longer read, its classes refused by the loader, or a fault starting it).
     * Server thread.
     */
    private fun launch(d: Deployment, built: Built): NotStarted? {
        val context = try {
            context(d, built.entry)
        } catch (e: IllegalArgumentException) {
            return NotStarted("its parameters no longer read: ${e.message}", null)
        }
        val host = try {
            ModuleHost(built.mainClass, built.classes, threads, d.label, clock) { fault -> faulted(d, fault) }
        } catch (e: Exception) {
            context.close()
            log.error("${d.label} could not be loaded", e)
            return NotStarted("it could not be loaded: ${e.message ?: e.javaClass.simpleName}", null)
        }
        d.entry = built.entry
        d.context = context
        d.host = host
        d.phase = Phase.RUNNING
        d.gaugeAt = clock.millis() + ForgeLimits.GAUGE_EVERY_MS_UNMETERED
        d.hosted(ModuleHost.START) { it.start(context) }
        // A start its host skipped (asked too late for the module thread) is asked again at the next tick.
        return host.disabled?.let { NotStarted(it.text, it) } ?: host.stalled?.let { NotStarted(it, null) }
    }

    /** [d]'s context: the SDK's real pieces on its parameters, storage, queue and creatures. Throws [IllegalArgumentException] when its parameters do not read. */
    private fun context(d: Deployment, entry: ArsenalEntry): RunContext {
        val run = d.run
        val args = ArgsView(entry.params, run.params)
        return RunContext(
            ref = run.ref,
            label = d.label,
            restarted = d.restarted,
            endsAt = run.endsAt,
            seed = run.id * SEED_PRIME xor run.createdAt,
            clock = clock,
            args = args,
            events = FactQueue(),
            timers = Scheduler(),
            world = server.world(d.creatures::owns),
            queue = d.queue,
            acts = ModuleActs(d.queue, d.creatures, server::dimensionOf),
            creatures = d.creatures,
            ground = GroundView(d.queue),
            storage = StorageMap(run.storage),
            game = run.challenge?.let { ModuleGame(Ref(RefKind.CANON, it), run.ref, challenges, d.queue) },
        ) { line -> told(d, line) }
    }

    /**
     * The server runs (M5 §6.7): each deployment of the state is built again, off the server thread, and started at the
     * next tick ([launchBuilt]); one past its end, or with the Forge switched off, waits for the first second to end. The
     * creatures already loaded (the spawn chunks) come back to their deployment, or back to normal. Server thread.
     */
    override fun started(server: MinecraftServer, now: Long) = begin(server, now)

    /** [started], [server] null in the unit tests. */
    internal fun begin(server: MinecraftServer?, now: Long) {
        check(!begun) { "the modules' engine starts once" }
        val state = store.get()
        val deployments = state.modules.map { Deployment(it, restarted = true) }
        live = deployments
        begun = true
        for (d in deployments) if (state.forgeHalt == null && d.run.endsAt > now) build(d)
        if (server != null) {
            for (level in server.allLevels) for (entity in level.allEntities) {
                if (entity is Mob && area(entity)) ModuleCreatures.onMobLoad(entity, ::controlOf, services.guards, now)
            }
        }
        val waiting = deployments.count { it.run.endsAt <= now }
        if (deployments.isNotEmpty()) {
            log.info(
                "the modules' engine started: ${deployments.size} deployments to restart" +
                    (if (waiting > 0) "; $waiting past their end" else "") + (state.forgeHalt?.let { "; the Forge is switched off by ${it.by}" } ?: ""),
            )
        }
    }

    /** Builds [d]'s version again, off the server thread: back at the next tick ([launchBuilt]). */
    private fun build(d: Deployment) {
        val run = d.run
        scope.launch {
            val build = try {
                val built = services.forge.build(run.module, run.version)
                if (built.entry.hash == run.hash) {
                    Build(d, built, null)
                } else {
                    Build(d, null, "the source of ${run.module} v${run.version} no longer matches the one deployed")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Build(d, null, e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
            }
            builds += build
        }
    }

    /** The restarts' builds back: each started (`restarted`), or ended (`restart_failed`, or `disabled` when it faulted starting). Server thread. */
    private fun launchBuilt() {
        while (true) {
            val build = builds.poll() ?: return
            val d = build.deployment
            if (d.phase != Phase.WAITING) continue
            val built = build.built ?: run {
                restartFailed(d, build.problem.orEmpty())
                null
            } ?: continue
            val failed = launch(d, built)
            when {
                failed == null -> {
                    decision(
                        RESTARTED, d.ref,
                        "${d.label} v${d.run.version} started again after a restart: ${d.run.storage.size} keys in its storage, ${d.creatures.count} creatures of its own",
                    )
                    after(d)
                }
                failed.fault != null -> disable(d, failed.fault)
                else -> restartFailed(d, failed.text)
            }
        }
    }

    private fun restartFailed(d: Deployment, problem: String) {
        endNow(d, ModuleEnding.RESTART_FAILED, "could not restart: $problem") { "${d.label} could not restart: $problem" }
    }

    // ── Ticks ──

    override fun everyTick(server: MinecraftServer, now: Long) = tick(now)

    /** A tick ([everyTick]): the restarts built start, then each deployment's facts, timers and callbacks. Server thread. */
    internal fun tick(now: Long) {
        if (!running) return
        launchBuilt()
        var total = 0L
        var slowest: Deployment? = null
        var slowestNanos = 0L
        for (d in live) {
            if (d.phase != Phase.RUNNING) continue
            val before = d.spent
            work(d, now)
            val nanos = d.spent - before
            total += nanos
            if (nanos > slowestNanos) {
                slowest = d
                slowestNanos = nanos
            }
        }
        if (total > ForgeLimits.TICK_MS * NANOS_PER_MS) slowTick(now, total, slowest, slowestNanos)
    }

    /**
     * [d]'s tick: the calls its host skipped, its facts waiting, its timers due, its callbacks; each call stops the
     * tick's work for it once it ends. While a call of it given up still runs, its tick waits (its timers count it all
     * the same); the calls asked in the meantime are skipped by its host, and asked again ([Deployment.replay]).
     */
    private fun work(d: Deployment, now: Long) {
        val context = d.context ?: return
        if (d.busy() || !d.replay()) {
            if (after(d)) context.timers.advance()
            return
        }
        for (delivery in context.events.drain(now)) {
            if (delivery.live && !invoke(d, FACT) { delivery.deliver() }) return
        }
        context.timers.advance()
        while (true) {
            val due = context.timers.next() ?: break
            if (!invoke(d, TIMER) { due.run() }) return
        }
        d.queue.deliver()
        after(d)
    }

    /**
     * Settles the calls given up (each deployment's host), ends the deployments that disabled, as their next tick would;
     * true while a call given up still runs. Server thread; for the tests.
     */
    internal fun settle(): Boolean = live.filter { it.phase == Phase.RUNNING }.map { d -> d.busy().also { after(d) } }.any { it }

    /** One call of [d]'s module ([what]: `fact`, `timer`), then what it left; false once it ended. */
    private fun invoke(d: Deployment, what: String, body: () -> Unit): Boolean {
        d.queue.invocation(what, body)
        return after(d)
    }

    /**
     * After a call of [d]: disabled → it ends `disabled`; a start given up without its fault ([ModuleHost.stalled]) → it
     * ends `disabled` with that reason, no fault noted; asked to end → `fulfilled`. True while it runs. Server thread.
     */
    private fun after(d: Deployment): Boolean {
        if (d.phase != Phase.RUNNING) return false
        val fault = d.host?.disabled
        if (fault != null) {
            disable(d, fault)
            return false
        }
        val stalled = d.host?.stalled
        if (stalled != null) {
            endNow(d, ModuleEnding.DISABLED, "disabled: it failed to start: $stalled") { "${d.label} could not start: $stalled" }
            return false
        }
        val why = d.context?.endAsked ?: return true
        endNow(d, ModuleEnding.FULFILLED, why) { counts -> "${d.label} ended by itself: « ${ModelText.escape(why)} » ($counts)" }
        return false
    }

    private fun slowTick(now: Long, nanos: Long, slowest: Deployment?, slowestNanos: Long) {
        slowTicks++
        if (tickReportedAt != Long.MIN_VALUE && now - tickReportedAt in 0 until MINUTE_MS) return
        log.warn(
            "the modules took ${millis(nanos)} ms of one tick (at most ${ForgeLimits.TICK_MS}); the slowest: ${slowest?.label} ${millis(slowestNanos)} ms; " +
                "$slowTicks slow ticks since the last warning",
        )
        slowTicks = 0
        tickReportedAt = now
    }

    // ── Seconds ──

    override fun everySecond(server: MinecraftServer, now: Long) = second(now)

    /** A second ([everySecond]). Server thread. */
    internal fun second(now: Long) {
        if (!running) return
        store.get().forgeHalt?.let { halt -> if (live.isNotEmpty()) offAtStart(halt) }
        if (threads.lost.stuck) {
            for (d in live) if (d.phase == Phase.RUNNING) disable(d, Fault(Fault.Kind.STUCK, SECOND, STUCK_CAUSE, 0, disables = true))
        }
        for (d in live) {
            if (d.run.endsAt <= now) {
                endNow(d, ModuleEnding.EXPIRED, EXPIRED_NOTE) { counts -> "${d.label} ended: its time is up ($counts)" }
                continue
            }
            if (d.phase != Phase.RUNNING) continue
            val host = d.host ?: continue
            val context = d.context ?: continue
            host.second()
            d.second()
            for (gone in d.creatures.sweep()) if (context.events.wants(gone.javaClass)) context.events.offer(gone)
            context.second(now)
            factsDropped(d, context.events.takeDropped(), now)
            save(d)
            after(d)
        }
        gauge(now)
        if (leakWatch.watching() == 0) return
        for (label in leakWatch.check()) {
            decisions.record(
                log, DecisionRecord.Kind.FORGE, LEAK, label,
                "the classes of $label are still in memory ${ForgeLimits.LEAK_CHECK_MS / 1_000} s and ${ForgeLimits.LEAK_GC_CYCLES} collections after its end",
                level = Log.Level.OFF,
            )
        }
    }

    /** [d]'s storage and counters into the state, when they changed. Server thread. */
    private fun save(d: Deployment) {
        val storage = d.context?.storage?.changes()
        val acts = d.acts
        val faults = d.baseFaults + d.faults
        val run = d.run
        if (storage == null && acts == run.acts && faults == run.faults) return
        val saved = run.copy(storage = storage ?: run.storage, acts = acts, faults = faults)
        d.run = saved
        store.update { state -> state.copy(modules = state.modules.map { if (it.id == saved.id) saved else it }) }
    }

    /** [dropped] facts of [d] since the last second: told in a decision `facts_dropped`, once a minute at most. */
    private fun factsDropped(d: Deployment, dropped: Int, now: Long) {
        if (dropped > 0) {
            if (d.factsDropped == 0) d.factsSince = now
            d.factsDropped += dropped
        }
        if (d.factsDropped == 0 || (d.factsToldAt != Long.MIN_VALUE && now - d.factsToldAt < MINUTE_MS)) return
        decision(
            FACTS_DROPPED, d.ref,
            "${d.label}: ${d.factsDropped} facts dropped since ${TIME.format(Instant.ofEpochMilli(d.factsSince).atZone(clock.zone))} " +
                "(${ForgeLimits.FACTS_PER_TICK} a tick, ${ForgeLimits.FACTS_PER_MINUTE} a minute at most)",
        )
        d.factsDropped = 0
        d.factsToldAt = now
    }

    /** One deployment's memory gauge a second, each in turn: once a minute, every 10 seconds without allocation metering. */
    private fun gauge(now: Long) {
        val d = live.firstOrNull { it.phase == Phase.RUNNING && now >= it.gaugeAt } ?: return
        d.gaugeAt = now + if (d.metered) ForgeLimits.GAUGE_EVERY_MS else ForgeLimits.GAUGE_EVERY_MS_UNMETERED
        val context = d.context ?: return
        d.host?.gauge(context.events.handlers() + context.timers.tasks() + d.queue.callbacks())
        after(d)
    }

    /** The Forge is switched off as the server starts: no deployment of the state starts again. One `MODULE` line for them all. */
    private fun offAtStart(halt: ForgeHalt) {
        val ended = live.toList().mapNotNull { finish(it, ModuleEnding.FORGE_OFF, FORGE_OFF_NOTE, by = null, line = null) }
        ended.forEach { services.scope.launch { close(it) } }
        tell("the Forge is switched off (by ${ModelText.name(halt.by)}): ${ended.joinToString { it.run.ref.toString() }} did not start again")
    }

    // ── Facts and creatures ──

    /** A fact of the bus: translated once, queued for each deployment that subscribed to its class. Inline on the bus's thread. */
    override fun onEvent(envelope: Envelope) {
        val running = live
        if (stopped || running.isEmpty()) return
        var fact: GameFact? = null
        for (d in running) {
            val events = d.context?.events ?: continue
            val translated = fact ?: Facts.of(envelope.event, services.players) ?: return
            fact = translated
            if (events.wants(translated.javaClass)) events.offer(translated)
        }
    }

    /**
     * A creature appeared or loaded: one of a deployment's comes back to it, or back to normal when its deployment is
     * gone; a wild one near a player is a `CreatureAppeared` fact for the deployments that want it. Server thread.
     */
    override fun onMobLoad(entity: Entity, level: ServerLevel) {
        if (!running || entity !is Mob || !area(entity)) return
        ModuleCreatures.onMobLoad(entity, ::controlOf, services.guards, clock.millis())
        val wanting = live.mapNotNull { it.context?.events }.filter { it.wants(GameFact.CreatureAppeared::class.java) }
        if (wanting.isEmpty()) return
        val fact = Facts.appeared(entity, level) ?: return
        wanting.forEach { it.offer(fact) }
    }

    /** The creatures of deployment [id], while it is live. */
    private fun controlOf(id: Long): CreatureControl? = live.firstOrNull { it.id == id }?.creatures

    /** A death near a module's creature: a bystander's is refused and they are rescued (M5 §4.5). */
    override fun allowDeath(player: ServerPlayer, source: DamageSource): Boolean = ModuleCreatures.allowDeath(player, source, services.guards)

    // ── Faults and notes ──

    /** A fault of [d]'s module: a decision `mechanic fault`, noted on its version of the arsenal. Server thread. */
    private fun faulted(d: Deployment, fault: Fault) {
        d.faults++
        decisions.record(
            log, DecisionRecord.Kind.MECHANIC, FAULT, d.ref.toString(), "${d.label}: ${fault.text}",
            conversationId = EngineActions.conversationOf(d.ref),
            detail = buildJsonObject {
                put("kind", fault.kind.id)
                put("call", fault.call)
                put("cause", fault.cause)
                put("line", fault.line)
                put("disables", fault.disables)
            },
            level = Log.Level.OFF, // the host logs it
        )
        try {
            noteFault(d.run, fault)
        } catch (e: Exception) {
            log.error("${d.label}: its fault could not be noted on its version", e)
        }
    }

    private fun disable(d: Deployment, fault: Fault) {
        endNow(d, ModuleEnding.DISABLED, "disabled: ${fault.text}") { "${d.label} disabled: ${fault.text}; ask the forger to fix it" }
    }

    /** [d]'s module tells the Director [line] (`tellDirector`): a `MODULE` line, and a decision `notified` for the Chronicler. */
    private fun told(d: Deployment, line: String) {
        val cut = ModelText.cut(line, MechanicsLimits.EVENT_LINE)
        decision(NOTIFIED, d.ref, cut)
        services.director.post(DirectorSignal.Mechanic(MechanicSignal.MODULE, cut, clock.millis(), priority = false))
    }

    /** A `MODULE` line without a deployment of its own (the switch). */
    internal fun tell(line: String) {
        services.director.post(DirectorSignal.Mechanic(MechanicSignal.MODULE, ModelText.cut(line, MechanicsLimits.EVENT_LINE), clock.millis(), priority = false))
    }

    // ── Ends ──

    /**
     * What an end left for [close] (M5 §6.6): [run] as it ended (counters, storage), how it [ending], the canon's [note],
     * who asked ([by], when someone did), the `MODULE` [line] (null when none is told), and [view] (its line is the end's,
     * [reason]).
     */
    internal class Ended(val run: ModuleRun, val ending: ModuleEnding, val note: String, val by: Author?, val line: String?, val reason: String, val view: ModuleView)

    /** Ends [d] now ([finish]) and closes it in a coroutine ([close]), its `MODULE` [line] told. Server thread. */
    private fun endNow(d: Deployment, ending: ModuleEnding, note: String, line: (String) -> String) {
        val ended = finish(d, ending, note, by = null, line = line) ?: return
        services.scope.launch { close(ended) }
    }

    /** [id] stopped by [by] (`stop_module`, `/narrator module stop`): an operator's stop is told to the Director. Null when it does not run. Server thread. */
    internal fun stop(id: Long, note: String, by: Author): Ended? {
        val d = live.firstOrNull { it.id == id } ?: return null
        val line: ((String) -> String)? = if (by.source == Source.DIRECTOR) {
            null
        } else {
            { counts -> "${d.label} ended: stopped by ${who(by)} ($counts); « ${ModelText.escape(note)} »" }
        }
        return finish(d, ModuleEnding.REMOVED, note, by, line)
    }

    /**
     * The switch (M5 §3.7): set in the state first (nothing starts again), then every deployment ends `forge_off`, its
     * module's `stop(FORGE_OFF)` called; no `MODULE` line each (the book tells one for all). Server thread.
     */
    internal fun halt(halt: ForgeHalt): List<Ended> {
        store.update { it.copy(forgeHalt = halt) }
        val by = Author(Source.OPERATOR, operator = halt.by)
        return live.toList().mapNotNull { finish(it, ModuleEnding.FORGE_OFF, FORGE_OFF_NOTE, by, line = null) }
    }

    /**
     * Ends [d] now (M5 §6.6, §3.7), on the server thread: its module's `stop` (but when disabled or never started), its
     * pieces closed, out of the state, its creatures given back, its loader watched. Null when it was ended already.
     * [line]: its `MODULE` line from its final counts (`12 acts, 0 faults`); null when none is told.
     */
    private fun finish(d: Deployment, ending: ModuleEnding, note: String, by: Author?, line: ((String) -> String)?): Ended? {
        if (d.phase == Phase.ENDED) return null
        val retired = retire(d, ending.stop)
        store.update { state -> state.copy(modules = state.modules.filterNot { it.id == d.id }) }
        val run = d.run.copy(acts = retired.acts, faults = retired.faults, storage = retired.storage)
        val status = if (run.kind == RuleKind.LAW) ending.law else ending.plan
        val asked = by?.let { " by ${who(it)}" }.orEmpty()
        val cut = ModelText.cut(note, MechanicsLimits.NOTE)
        val counts = counted(run.acts, run.faults)
        val reason = "${d.label} ${run.kind.id} ${ending.id}$asked, canon ${status.id}: $counts; « $cut »"
        log.info(
            "${d.label} ended (${ending.id}): $counts" +
                (if (retired.given > 0) ", ${retired.given} creatures given back" else "") +
                (if (retired.dropped > 0) ", ${retired.dropped} acts dropped" else ""),
        )
        return Ended(run, ending, cut, by, line?.invoke(counts), reason, view(d, run, reason))
    }

    /** What the retrait of a deployment counted. */
    private class Retired(val acts: Int, val faults: Int, val storage: Map<String, String>, val given: Int, val dropped: Int)

    /**
     * The retrait of [d] (M5 §3.7): its module's `stop` with [why] when it runs and is not disabled; its pieces closed (its
     * queued acts dropped, timers and subscriptions cancelled, its SDK objects refused); its creatures given back and its
     * loader watched, but at the server's stop (they stay for the restart). Server thread.
     */
    private fun retire(d: Deployment, why: StopReason?): Retired {
        d.phase = Phase.ENDED
        live = live.filterNot { it === d }
        val host = d.host
        val context = d.context
        if (why != null && host != null && context != null && host.disabled == null) {
            context.stopping()
            d.hosted(ModuleHost.STOP) { it.stop(why) }
        }
        if (context != null) context.close() else d.queue.close()
        val storage = context?.storage?.snapshot() ?: d.run.storage
        val stopping = why == StopReason.SERVER_STOPPING
        val given = if (stopping) 0 else d.creatures.releaseAll()
        val loader = host?.unload()
        if (loader != null && !stopping) leakWatch.watch(d.label, loader)
        return Retired(d.acts, d.baseFaults + d.faults, storage, given, d.queue.dropped)
    }

    /**
     * The end of [ended] once its deployment is out (M5 §6.6): its canon resolved by the engine, its decision
     * `module_ended`, and its `MODULE` line when it has one. A note the memory refuses (the module's own words in the
     * first or second person: `VoiceGuard`) closes the row all the same, with the engine's neutral words for its
     * ending ([neutralNote]; M5-16, defect 2). Never on the server thread.
     */
    internal suspend fun close(ended: Ended) {
        val run = ended.run
        val status = if (run.kind == RuleKind.LAW) ended.ending.law else ended.ending.plan
        val author = Author(Source.ENGINE, turnId = ended.by?.turnId, conversationId = ended.by?.conversationId)
        try {
            try {
                services.memory.store.resolveCanon(run.ref, status, ended.note, emptyList(), author)
            } catch (e: MemoryRejected) {
                val neutral = neutralNote(ended.ending)
                log.warn("the canon row of ${run.ref} refused its note (${e.message}): resolved ${status.id} with « $neutral »")
                services.memory.store.resolveCanon(run.ref, status, neutral, emptyList(), author)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: MemoryRejected) {
            log.warn("the canon row of ${run.ref} was not resolved ${status.id}: ${e.message}")
        } catch (e: Exception) {
            log.error("the canon row of ${run.ref} could not be resolved ${status.id}; the reconciliation will close it", e)
        }
        decisions.record(
            log, DecisionRecord.Kind.MECHANIC, ENDED, run.ref.toString(), ended.reason,
            conversationId = EngineActions.conversationOf(run.ref), turnId = ended.by?.turnId,
            detail = buildJsonObject { put(ENDING, ended.ending.id) },
        )
        ended.line?.let { tell(it) }
    }

    /** The reconciliation took deployments [ids] out of the state (M5 §6.5): their retrait, without `stop` nor canon. Server thread. */
    override fun dropped(ids: Set<Long>) {
        for (d in live.filter { it.id in ids }) {
            retire(d, why = null)
            log.info("${d.label} was dropped by the reconciliation: its creatures given back, its loader let go")
        }
    }

    /**
     * `SERVER_STOPPING`: each module's `stop(SERVER_STOPPING)`, its storage and counters kept in the state, its queued acts
     * dropped (counted in one decision `skipped`), its loader let go; its creatures stay in the world for the restart.
     */
    override fun stopping() {
        if (stopped) return
        stopped = true
        val deployments = live
        var dropped = 0
        for (d in deployments) {
            val retired = retire(d, StopReason.SERVER_STOPPING)
            dropped += retired.dropped
            val kept = d.run.copy(storage = retired.storage, acts = retired.acts, faults = retired.faults)
            store.update { state -> state.copy(modules = state.modules.map { if (it.id == kept.id) kept else it }) }
        }
        job.cancel()
        if (deployments.isNotEmpty()) {
            decisions.record(
                log, DecisionRecord.Kind.MECHANIC, SKIPPED, null,
                "the server stops: ${deployments.size} modules stopped until the restart (${deployments.joinToString { it.ref.toString() }}), $dropped acts in their queues dropped",
            )
        }
    }

    // ── Views ──

    /** The running deployments as the tools and the commands show them, oldest first; their line is their situation's. Any thread. */
    fun views(now: Long): List<ModuleView> = live.filter { it.phase != Phase.ENDED }.map { d -> view(d, d.run.copy(acts = d.acts, faults = d.baseFaults + d.faults), line(d, now)) }

    /** The view of deployment [id] with [line], while it is live; null otherwise. Any thread. */
    internal fun view(id: Long, line: String): ModuleView? = live.firstOrNull { it.id == id }?.let { d -> view(d, d.run.copy(acts = d.acts, faults = d.baseFaults + d.faults), line) }

    private fun view(d: Deployment, run: ModuleRun, line: String) = ModuleView(
        ref = run.ref, module = run.module, version = run.version, kind = run.kind, endsAt = run.endsAt, acts = run.acts,
        faults = run.faults, challenge = run.challenge?.let { Ref(RefKind.CANON, it) }, msPerSecond = d.msPerSecond, line = line,
    )

    override fun situation(now: Long): List<String> = listOfNotNull(situation(live.map { line(it, now) }))

    /** `C-70 orbiting_chickens law Bartok (until 20:40)`, `C-77 statues plan referees C-75 (until 21:20)`: one deployment in the situation. */
    private fun line(d: Deployment, now: Long): String {
        val run = d.run
        val players = players(d).takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = " ") { ModelText.name(it) }.orEmpty()
        val referee = run.challenge?.let { " referees C-$it" }.orEmpty()
        val waiting = if (d.phase == Phase.WAITING) ", starting again" else ""
        return "${run.ref} ${run.module} ${run.kind.id}$players$referee (until ${RuleText.time(run.endsAt, now, clock.zone)}$waiting)"
    }

    /** The players its parameters name, as it was deployed (its manifest says which: none known while it is built again). */
    private fun players(d: Deployment): List<String> {
        val entry = d.entry ?: return emptyList()
        return entry.params.filter { it.type == ParamType.PLAYER || it.type == ParamType.PLAYERS }.flatMap { spec ->
            when (val value = d.run.params[spec.name]) {
                is JsonPrimitive -> listOfNotNull(value.content.takeIf { value.isString })
                is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                else -> emptyList()
            }
        }.distinct()
    }

    private fun decision(what: String, ref: Ref, reason: String, turnId: Long? = null) {
        decisions.record(log, DecisionRecord.Kind.MECHANIC, what, ref.toString(), reason, conversationId = EngineActions.conversationOf(ref), turnId = turnId)
    }

    /** The decision `started` of a deployment (M5 §6.4 step 9), by the book. */
    internal fun deployed(ref: Ref, reason: String, turnId: Long?) = decision(STARTED, ref, reason, turnId)

    companion object {
        /** The calls of a module the engine makes (its constructor and `start`, its `stop` and gauge are the host's). */
        const val FACT = "fact"
        const val TIMER = "timer"
        const val SECOND = "second"

        /** A deployment's decisions (`mechanic`, M5 §15); its acts' are the act queue's (`act`) and `EngineActions`' (`action`). */
        const val STARTED = "started"
        const val RESTARTED = "restarted"
        const val FAULT = "fault"
        const val FACTS_DROPPED = "facts_dropped"
        const val NOTIFIED = "notified"
        const val SKIPPED = "skipped"

        /** The decision of a deployment ended, however it ended ([ENDING] in its detail: a [ModuleEnding] id). */
        const val ENDED = "module_ended"
        const val ENDING = "ending"

        /** The Forge's decision of a loader still alive after its module (`forge leak`). */
        const val LEAK = "leak"

        /** The canon's resolutions the engine writes itself. */
        const val EXPIRED_NOTE = "its time ran out"
        const val FORGE_OFF_NOTE = "the Forge was switched off by an operator"

        /** The canon's resolution of a module that ended itself, when the memory refused its own words. */
        const val ENDED_ITSELF_NOTE = "it ended by itself"

        /** The engine's neutral words for [ending], in place of a note the memory refused. */
        fun neutralNote(ending: ModuleEnding): String = when (ending) {
            ModuleEnding.EXPIRED -> EXPIRED_NOTE
            ModuleEnding.FULFILLED -> ENDED_ITSELF_NOTE
            ModuleEnding.REMOVED -> "it was stopped"
            ModuleEnding.DISABLED -> "it was disabled by its faults"
            ModuleEnding.FORGE_OFF -> FORGE_OFF_NOTE
            ModuleEnding.RESTART_FAILED -> "it could not restart"
        }

        /** Why every module is disabled once too many module threads were lost. */
        const val STUCK_CAUSE = "the Forge is stuck: too many module threads were lost"

        private const val MINUTE_MS = 60_000L
        private const val NANOS_PER_MS = 1_000_000L
        private const val SEED_PRIME = 1_000_003L

        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)

        /** `12 acts, 1 fault`. */
        fun counted(acts: Int, faults: Int): String = "$acts ${if (acts == 1) "act" else "acts"}, $faults ${if (faults == 1) "fault" else "faults"}"

        /**
         * The `Modules:` line of the situation (M5 §9.3), [ForgeLimits.MODULES_LINE] characters at most: each deployment,
         * oldest first, then the last ones left out (`(+2 more)`). Null without deployments.
         */
        fun situation(items: List<String>): String? {
            if (items.isEmpty()) return null
            val max = ForgeLimits.MODULES_LINE
            for (kept in items.size downTo 1) {
                val more = items.size - kept
                val text = "Modules: " + (items.take(kept) + listOfNotNull("(+$more more)".takeIf { more > 0 })).joinToString(" · ")
                if (text.length <= max) return text
            }
            val more = if (items.size > 1) " · (+${items.size - 1} more)" else ""
            return "Modules: " + ModelText.cut(items.first(), max - "Modules: ".length - more.length) + more
        }

        private fun who(author: Author): String = when (author.source) {
            Source.DIRECTOR -> "the director" + (author.turnId?.let { " (turn $it)" } ?: "")
            Source.OPERATOR -> "operator " + (author.operator?.let(ModelText::name) ?: "?")
            else -> author.source.id
        }

        private fun millis(nanos: Long): String = "%.1f".format(Locale.ROOT, nanos / 1e6)
    }
}
