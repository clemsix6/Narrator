package io.github.clemsix6.narrator2.forge.sim

import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.forge.ParamSpec
import io.github.clemsix6.narrator2.forge.api.Cell
import io.github.clemsix6.narrator2.forge.api.CreatureInfo
import io.github.clemsix6.narrator2.forge.api.ForgeModule
import io.github.clemsix6.narrator2.forge.api.ForgeRefusal
import io.github.clemsix6.narrator2.forge.api.GameFact
import io.github.clemsix6.narrator2.forge.api.Limits
import io.github.clemsix6.narrator2.forge.api.Place
import io.github.clemsix6.narrator2.forge.api.StopReason
import io.github.clemsix6.narrator2.forge.api.test.Bench
import io.github.clemsix6.narrator2.forge.compile.Diagnostic
import io.github.clemsix6.narrator2.forge.compile.Diagnostics
import io.github.clemsix6.narrator2.forge.sdk.Facts
import io.github.clemsix6.narrator2.forge.sdk.ModuleActs
import io.github.clemsix6.narrator2.forge.sdk.ModuleCall
import io.github.clemsix6.narrator2.tool.ToolText
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Stops a run of the bench at its first failure (a fault, a slow call, a failed check, the bench's time): thrown by the
 * bench through the scenario's code, which cannot catch an `Error`. The failure itself is [BenchImpl.failure].
 */
internal class BenchStop(failure: Diagnostic?) : Error(failure?.let { "${it.where}: ${it.message}" }, null, false, false)

/**
 * One bench (M5 §7): a [SimWorld], at most one deployment of the module on it ([SimDeployment]), and the module's code,
 * which the bench never runs itself: every call (its constructor, `start`, `stop`, each fact handler, timer and
 * callback, the scenario and its class's constructor) goes through [host], on the host's own thread, measured (M5-04
 * handoff: a module call never runs on its caller's thread). A scenario drives the bench through [Bench], from the
 * scenario's own call; the smoke run ([SmokeRun]) the same way, from the bench's thread.
 *
 * Each tick: the acts asked at the tick before go to the bench's tools, the facts queued before it are delivered, the
 * timers due run, then the acts' outcomes come back to their callbacks; each a call of its own, through the
 * deployment's act queue (M5-07), as the engine runs them. A `ctx.end` is applied once its call returned.
 *
 * The bench stops at its first failure ([failure], then [BenchStop]): a fault of a call, a call longer than
 * `Limits.MAX_CALL_MILLIS`, more than `ForgeLimits.DEPLOYMENT_MS_PER_SECOND` of calls a second five seconds in a
 * row, a failed [check], the bench's [deadline] (a `System.nanoTime`), or its [cancelled] coroutine. At a failure the
 * deployment's SDK is closed at once, so that a call the host abandoned, still running on its thread, is refused by
 * every object of the SDK.
 *
 * @param phase how the failures name the run: `smoke`, `scenario theyCircleThePlayer`.
 * @param deployMinutes how long a deployment of this bench runs before it expires.
 */
internal class BenchImpl(
    private val host: BenchHost,
    private val specs: List<ParamSpec>,
    private val deadline: Long,
    private val budgetMs: Long,
    private val phase: String,
    private val deployMinutes: Int,
    private val cancel: () -> Boolean,
) : Bench {
    val world = SimWorld()
    private val refusals = HashMap<String, ArrayDeque<String>>()
    private var challenge: SimGame? = null

    var deployment: SimDeployment? = null
        private set

    /** The first failure, as the forger reads it; null while none. */
    @Volatile
    var failure: Diagnostic? = null
        private set

    /** The Forge cancelled the bench. */
    @Volatile
    var cancelled = false
        private set

    /** The longest call of the module so far, and the tick it ended itself at (`ctx.end`). */
    var slowestNanos = 0L
        private set
    var endedItselfAt: Int? = null
        private set

    private var secondNanos = 0L
    private var slowSeconds = 0

    /** Every call of the deployment's queue: the module's code through the host, measured; never throws. */
    private val moduleCall = ModuleCall { what, body ->
        val result = measured(ForgeLimits.CALL_NANOS, body) ?: return@ModuleCall
        val where = "$phase, $what at tick ${world.tick}"
        when {
            result.fault != null -> failure = failure ?: Diagnostics.fault(where, result.fault, result.line)
            result.nanos > ForgeLimits.CALL_NANOS -> failure = failure ?: Diagnostics.fault(where, slow(result.nanos))
        }
    }

    // ── The scenario's world ───────────────────────────────────────────────────────────────────────

    override fun player(name: String?, at: Place?) {
        if (name.isNullOrBlank()) throw ForgeRefusal("player needs a name")
        val place = at ?: throw ForgeRefusal("player needs a place")
        if (world.join(name, place)) running()?.events?.offer(GameFact.Joined(name, place))
    }

    override fun move(name: String?, to: Place?) {
        known(name).at = to ?: throw ForgeRefusal("move needs a place")
    }

    override fun pose(name: String?, sneaking: Boolean, onGround: Boolean, pitch: Float) {
        val player = known(name)
        player.sneaking = sneaking
        player.onGround = onGround
        player.pitch = pitch.coerceIn(MIN_PITCH, MAX_PITCH)
    }

    override fun hold(name: String?, item: String?) {
        known(name).heldItem = SimContext.id(item).ifEmpty { SimWorld.AIR }
    }

    override fun give(name: String?, item: String?, count: Int) {
        val player = known(name)
        if (count < 1) throw ForgeRefusal("give gives 1 item or more (asked $count)")
        player.items.merge(SimContext.id(item ?: throw ForgeRefusal("give needs an item")), count, Int::plus)
    }

    override fun health(name: String?, health: Float) {
        known(name).health = health.coerceIn(0f, SimPlayer.MAX_HEALTH)
    }

    override fun leave(name: String?) {
        val player = known(name)
        if (!player.online) throw ForgeRefusal("${player.name} is not online")
        player.online = false
        running()?.events?.offer(GameFact.Left(player.name, player.at))
    }

    override fun block(cell: Cell?, block: String?) {
        world.setBlock(cell ?: throw ForgeRefusal("block needs a cell"), SimContext.id(block ?: throw ForgeRefusal("block needs a block id")))
    }

    override fun creature(type: String?, at: Place?): String {
        if (type.isNullOrBlank()) throw ForgeRefusal("creature needs a type")
        return world.addCreature(SimContext.id(type), at ?: throw ForgeRefusal("creature needs a place"), null, own = false).uuid
    }

    override fun challenge(vararg players: String?) {
        if (deployment != null) throw ForgeRefusal("challenge comes before start")
        val names = players.filterNotNull().filter { it.isNotBlank() }
        if (names.isEmpty()) throw ForgeRefusal("challenge needs its players")
        challenge = SimGame(CHALLENGE_ID, frozen(names))
    }

    override fun refuseNext(tool: String?, text: String?) {
        if (tool == null || tool !in TOOLS) throw ForgeRefusal("refuseNext takes a tool: ${TOOLS.joinToString()} (got $tool)")
        if (text.isNullOrBlank()) throw ForgeRefusal("refuseNext needs the tool's answer, e.g. DENIED (already_punished): …")
        refusals.getOrPut(tool) { ArrayDeque() }.addLast(text)
    }

    override fun fact(fact: GameFact?) {
        val given = fact ?: throw ForgeRefusal("fact needs a fact, e.g. new GameFact.Chat(\"Bartok\", place, \"bonjour\")")
        running()?.events?.offer(given)
    }

    // ── The module ─────────────────────────────────────────────────────────────────────────────────

    override fun start(vararg params: String?) {
        if (deployment != null) throw ForgeRefusal("the module is started already: one start a bench (restart() starts it again)")
        val checked = BenchParamLookup.check(specs, params.toList(), world)
        val deployment = SimDeployment(
            world, specs, checked, world.now + deployMinutes * SimDeployment.MINUTE_MS, challenge, refusals, moduleCall, host::line,
        )
        this.deployment = deployment
        launch(deployment, restarted = false)
    }

    override fun restart() {
        val deployment = running() ?: throw ForgeRefusal("restart needs the module running: start it first")
        deployment.stopping()
        val module = deployment.instance
        val ctx = deployment.context
        if (module != null) invoke("stop (the server stops)") { module.stop(ctx, StopReason.SERVER_STOPPING) }
        deployment.restart()
        launch(deployment, restarted = true)
    }

    /** A new instance of the module, then its `start`, each a call of its own. */
    private fun launch(deployment: SimDeployment, restarted: Boolean) {
        val after = if (restarted) " (restarted)" else ""
        var module: ForgeModule? = null
        invoke("constructor$after") { module = construct() }
        val made = module ?: return
        deployment.instance = made
        val ctx = deployment.newContext(restarted)
        invoke("start$after") { made.start(ctx) }
    }

    /** The module's instance; runs in a call of the module (its static initializer and constructor are its code). */
    private fun construct(): ForgeModule {
        val type = host.main
        val constructor = try {
            type.getDeclaredConstructor()
        } catch (e: NoSuchMethodException) {
            throw IllegalStateException("${type.simpleName} needs a public constructor without arguments")
        }
        return unwrapped { constructor.newInstance() } as? ForgeModule ?: throw IllegalStateException("${type.simpleName} does not implement ForgeModule")
    }

    /** The smoke run's end: the deployment's time runs out. */
    fun expire() = end(StopReason.EXPIRED, EXPIRED)

    /** Ends the deployment: `stop` with [reason] (a call), then its SDK closed and its creatures taken back; [why] is what [ended] says. */
    private fun end(reason: StopReason, why: String) {
        val deployment = running() ?: return
        deployment.stopping()
        val module = deployment.instance
        val ctx = deployment.context
        if (module != null) invoke("stop") { module.stop(ctx, reason) }
        if (reason == StopReason.ENDED) endedItselfAt = world.tick
        deployment.finish(why)
    }

    private fun running(): SimDeployment? = deployment?.takeIf { it.state == SimState.RUNNING }

    // ── Time ───────────────────────────────────────────────────────────────────────────────────────

    override fun run(ticks: Int) {
        if (ticks < 0) throw ForgeRefusal("run runs 0 ticks or more (asked $ticks)")
        repeat(ticks) { tick() }
    }

    private fun tick() {
        guard()
        world.advance()
        val deployment = running() ?: return
        deployment.dispatcher.runAll()
        for (delivery in deployment.events.drain(world.now)) {
            if (deployment.state != SimState.RUNNING) break
            if (!delivery.live) continue
            deployment.factsDelivered++
            invoke(delivery.label) { delivery.deliver() }
        }
        deployment.timePasses()
        deployment.timers.advance()
        while (deployment.state == SimState.RUNNING) {
            val due = deployment.timers.next() ?: break
            invoke(due.label) { due.run() }
        }
        if (deployment.state == SimState.RUNNING) {
            deployment.queue.deliver()
            afterCalls(deployment)
        }
        second()
        if (deployment.state == SimState.RUNNING && world.now >= deployment.endsAt) end(StopReason.EXPIRED, EXPIRED)
    }

    /** At each second of the bench: more than 50 ms of calls a second, five seconds in a row, is a fault *slowness* (M5 §3.7). */
    private fun second() {
        if (world.tick % Limits.TICKS_PER_SECOND != 0) return
        slowSeconds = if (secondNanos > ForgeLimits.DEPLOYMENT_MS_PER_SECOND * NANOS_PER_MS) slowSeconds + 1 else 0
        secondNanos = 0
        if (slowSeconds >= ForgeLimits.SLOW_SECONDS) {
            fail(
                Diagnostics.fault(
                    "$phase at tick ${world.tick}",
                    "more than ${ForgeLimits.DEPLOYMENT_MS_PER_SECOND} ms of calls a second, ${ForgeLimits.SLOW_SECONDS} seconds in a row: " +
                        "do less at each tick, spread the work over timers",
                ),
            )
        }
    }

    // ── Calls ──────────────────────────────────────────────────────────────────────────────────────

    /** Runs [body], the call [label] of the deployment's module, through its queue (and the host); then its failure or its end. */
    private fun invoke(label: String, body: () -> Unit) {
        val deployment = deployment ?: return
        guard()
        deployment.queue.invocation(label, body)
        afterCalls(deployment)
    }

    /** After the module's calls: the first failure stops the bench, its SDK closed; else a `ctx.end` they asked ends it. */
    private fun afterCalls(deployment: SimDeployment) {
        failure?.let {
            deployment.finish(null)
            throw BenchStop(failure)
        }
        deployment.endWhy?.let { end(StopReason.ENDED, it) }
    }

    /** [body], the module's code, through the host on its thread, for [budgetNanos]: null once the bench has failed (nothing runs any more). */
    private fun measured(budgetNanos: Long, body: () -> Unit): HostCall? {
        if (failure != null) return null
        val finished = AtomicBoolean(false)
        val result = host.call(budgetNanos) {
            try {
                body()
            } finally {
                finished.set(true)
            }
        }
        slowestNanos = maxOf(slowestNanos, result.nanos)
        secondNanos += result.nanos
        if (!finished.get() && result.fault == null) {
            return result.copy(fault = "the call was abandoned after ${ms(budgetNanos)} ms")
        }
        return result
    }

    /**
     * Runs scenario [name], a method `public void name(Bench b)` of the module's `public static` class `Scenarios`, on
     * this bench, new: its class's constructor is a call of the module; the scenario's own code is a call too, for the
     * bench's time left, and the module's calls it makes happen each measured on their own. The first failure, or null.
     */
    fun scenario(name: String): Diagnostic? {
        try {
            val type = scenarios() ?: return Diagnostics.fault(phase, "the module has no public static class Scenarios with a public constructor without arguments")
            val method = type.methods.firstOrNull { it.name == name && it.parameterTypes.contentEquals(arrayOf(Bench::class.java)) && it.returnType == Void.TYPE }
                ?: return Diagnostics.fault(phase, "Scenarios has no method public void $name(Bench b)")
            var instance: Any? = null
            own("the constructor of Scenarios", ForgeLimits.CALL_NANOS) { instance = unwrapped { type.getDeclaredConstructor().newInstance() } }
            guard()
            val result = measured(deadline - System.nanoTime()) { unwrapped { method.invoke(instance, this) } }
            failure?.let { return it }
            return result?.fault?.let { Diagnostics.fault(phase, it, result.line) }
        } catch (stop: BenchStop) {
            return failure
        }
    }

    private fun scenarios(): Class<*>? = try {
        Class.forName(host.main.name + SCENARIOS, false, host.main.classLoader)
    } catch (e: ClassNotFoundException) {
        null
    }

    /** Runs [body], the module's code outside a deployment (the scenarios' constructor), through the host for [budgetNanos]. */
    private fun own(label: String, budgetNanos: Long, body: () -> Unit) {
        guard()
        val result = measured(budgetNanos, body) ?: throw BenchStop(failure)
        result.fault?.let { fail(Diagnostics.fault("$phase, $label", it, result.line)) }
        if (result.nanos > budgetNanos) fail(Diagnostics.fault("$phase, $label", slow(result.nanos)))
    }

    override fun check(condition: Boolean, message: String?) {
        if (condition) return
        fail(Diagnostics.fault(phase, "check failed: ${message.orEmpty()}", host.line(Throwable())))
    }

    /** Records [problem] as the bench's failure (the first one stays), closes the deployment's SDK and stops the run. */
    private fun fail(problem: Diagnostic): Nothing {
        if (failure == null) failure = problem
        deployment?.finish(null)
        throw BenchStop(failure)
    }

    /** Before each call and tick: the bench's time left, and whether the Forge cancelled it. */
    private fun guard() {
        if (cancel()) {
            cancelled = true
            fail(Diagnostics.fault(phase, "the bench was cancelled"))
        }
        if (System.nanoTime() - deadline > 0) {
            fail(
                Diagnostics.fault(
                    "$phase at tick ${world.tick}",
                    "the bench ran out of its ${ToolText.number(budgetMs / MS_PER_SECOND)} s: ask fewer ticks of the scenarios, and less of each call",
                ),
            )
        }
    }

    private fun slow(nanos: Long): String =
        "took ${ms(nanos)} ms, the most is ${Limits.MAX_CALL_MILLIS} ms a call: do less in one call, spread the work over timers"

    // ── What the scenario reads ────────────────────────────────────────────────────────────────────

    override fun acts(): List<String> = frozen(deployment?.acts.orEmpty().map { it.line })

    override fun creatures(): List<CreatureInfo> = frozen(world.creatures.values.filter { it.own }.map { it.info() })

    override fun notes(): List<String> = frozen(deployment?.notes.orEmpty())

    override fun ended(): String? = deployment?.ended

    /** What the module retains now, as [host] estimates it, while the deployment runs; null otherwise or when unknown. */
    fun retained(): Long? {
        val deployment = running() ?: return null
        val instance = deployment.instance ?: return null
        return host.retained(listOf<Any>(instance) + deployment.handed())
    }

    private fun known(name: String?): SimPlayer = world.player(name) ?: throw ForgeRefusal("$name is not a player of the bench: put them there with player(…) first")

    companion object {
        init {
            loadSdk()
        }

        /**
         * Loads, with the bench, what the SDK's calls would load the first time (the facts and the bus's classes, the
         * creatures and effects a module never has, the game's ids): never in a module's measured call, as in the game,
         * where the engine loaded them long before. A cold first call measured 31 ms of it.
         */
        private fun loadSdk() {
            check(Facts.TYPES.isNotEmpty() && ModuleActs.forbiddenEffect("wither") != null && SimContext.forbidden("warden") != null)
        }

        /** The judged challenge a deployment of the bench referees. */
        const val CHALLENGE_ID = "C-2"

        /** How long a deployment of a scenario runs before it expires. */
        const val SCENARIO_MINUTES = 10

        /** What [ended] says of a deployment whose time ran out (the canon's note, M5 §6.6). */
        const val EXPIRED = "its time ran out"

        /** The tools [refuseNext] knows: those of the SDK's acts, and `game` for a judged challenge's end. */
        val TOOLS = listOf(
            "say", "show_title", "hud", "play_sound", "particles", "fireworks", "lightning", "explosion", "apply_effect", "set_attribute",
            "launch", "teleport", "spawn", "punish", BenchGround.PLACE_BLOCKS, "creature_attack", ActRecord.GAME,
        )

        private const val SCENARIOS = "\$Scenarios"
        private const val MIN_PITCH = -90f
        private const val MAX_PITCH = 90f
        private const val NANOS_PER_MS = 1_000_000L
        private const val MS_PER_SECOND = 1_000.0

        fun ms(nanos: Long): String = ToolText.number(nanos / NANOS_PER_MS.toDouble())

        /** [make], the cause of a reflective call's failure thrown as itself: the module's own exception. */
        private inline fun <T> unwrapped(make: () -> T): T = try {
            make()
        } catch (e: InvocationTargetException) {
            throw e.targetException ?: e
        } catch (e: ExceptionInInitializerError) {
            throw e.cause ?: e
        }
    }
}
