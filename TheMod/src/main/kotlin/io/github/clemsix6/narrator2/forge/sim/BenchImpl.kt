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
import io.github.clemsix6.narrator2.tool.ToolText
import java.lang.reflect.InvocationTargetException

/**
 * Stops a run of the bench at its first failure (a fault, a slow call, a failed check, the bench's time): thrown by the
 * bench through the scenario's code, which cannot catch an `Error`. The failure itself is [BenchImpl.failure].
 */
internal class BenchStop : Error(null, null, false, false)

/**
 * One bench (M5 §7): a [SimWorld], at most one deployment of the module on it, and the module's code run through
 * [host], each call measured. A scenario drives it through [Bench]; the smoke run ([SmokeRun]) drives it the same
 * way. Each tick: the creatures move, the facts queued before it are delivered, the timers due run, then the
 * callbacks of the acts answered at the tick before; each is a call of its own. After each call, its acts are
 * answered ([SimDeployment.settle]) and its `ctx.end` applied.
 *
 * The bench stops at its first failure ([failure], then [BenchStop]): a fault of a call, a call longer than
 * `Limits.MAX_CALL_MILLIS`, more than `ForgeLimits.DEPLOYMENT_MS_PER_SECOND` of calls a second five seconds in a
 * row, a failed [check], or the bench's [deadline] (a `System.nanoTime`). An interrupted thread stops it too
 * ([cancelled]).
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
) : Bench {
    val world = SimWorld()
    private val refusals = HashMap<String, ArrayDeque<String>>()
    private var challenge: SimGame? = null

    var deployment: SimDeployment? = null
        private set

    /** The first failure, as the forger reads it; null while none. */
    var failure: String? = null
        private set

    /** The bench's thread was interrupted: the Forge stops. */
    var cancelled = false
        private set

    /** The longest call of the module so far, and the tick it ended itself at (`ctx.end`). */
    var slowestNanos = 0L
        private set
    var endedItselfAt: Int? = null
        private set

    private var secondNanos = 0L
    private var slowSeconds = 0

    // ── The scenario's world ───────────────────────────────────────────────────────────────────────

    override fun player(name: String?, at: Place?) {
        if (name.isNullOrBlank()) throw ForgeRefusal("player needs a name")
        val place = at ?: throw ForgeRefusal("player needs a place")
        val joined = world.join(name, place)
        if (joined) deployment?.queue(GameFact.Joined(name, place))
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
        deployment?.queue(GameFact.Left(player.name, player.at))
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
        if (tool !in TOOLS) throw ForgeRefusal("refuseNext takes a tool: ${TOOLS.joinToString()} (got $tool)")
        if (text.isNullOrBlank()) throw ForgeRefusal("refuseNext needs the tool's answer, e.g. DENIED (already_punished): …")
        refusals.getOrPut(tool!!) { ArrayDeque() }.addLast(text)
    }

    override fun fact(fact: GameFact?) {
        deployment?.queue(fact ?: throw ForgeRefusal("fact needs a fact, e.g. new GameFact.Chat(\"Bartok\", place, \"bonjour\")"))
    }

    // ── The module ─────────────────────────────────────────────────────────────────────────────────

    override fun start(vararg params: String?) {
        if (deployment != null) throw ForgeRefusal("the module is started already: one start a bench (restart() starts it again)")
        val values = SimArgs.parse(specs, params.toList(), world)
        val deployment = SimDeployment(world, DEPLOYMENT_ID, specs, values, world.now + deployMinutes * SimDeployment.MINUTE_MS, challenge, refusals)
        this.deployment = deployment
        launch(deployment, restarted = false)
    }

    override fun restart() {
        val deployment = deployment?.takeIf { it.state == SimState.RUNNING } ?: throw ForgeRefusal("restart needs the module running: start it first")
        deployment.state = SimState.STOPPING
        val module = deployment.instance
        val ctx = deployment.context
        if (module != null) invoke("stop (server stopping)") { module.stop(ctx, StopReason.SERVER_STOPPING) }
        deployment.restart()
        launch(deployment, restarted = true)
    }

    /** A new instance of the module, then its `start`, each a call of its own. */
    private fun launch(deployment: SimDeployment, restarted: Boolean) {
        val after = if (restarted) " after the restart" else ""
        var module: ForgeModule? = null
        invoke("the constructor$after") { module = construct() }
        val made = module ?: return
        deployment.instance = made
        val ctx = deployment.newContext(restarted)
        invoke("start$after") { made.start(ctx) }
    }

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

    /** Ends the deployment: `stop` with [reason] (a call), then its creatures taken back; [why] is what [ended] says. */
    private fun end(reason: StopReason, why: String) {
        val deployment = deployment ?: return
        if (deployment.state != SimState.RUNNING) return
        deployment.state = SimState.STOPPING
        val module = deployment.instance
        val ctx = deployment.context
        if (module != null) invoke("stop") { module.stop(ctx, reason) }
        if (reason == StopReason.ENDED) endedItselfAt = world.tick
        deployment.finish(why)
    }

    // ── Time ───────────────────────────────────────────────────────────────────────────────────────

    override fun run(ticks: Int) {
        if (ticks < 0) throw ForgeRefusal("run runs 0 ticks or more (asked $ticks)")
        repeat(ticks) { tick() }
    }

    private fun tick() {
        guard()
        world.advance()
        val deployment = deployment?.takeIf { it.state == SimState.RUNNING } ?: return
        val facts = deployment.takeFacts()
        deployment.timePasses()
        for (fact in facts) deliver(deployment, fact)
        timers(deployment)
        callbacks(deployment)
        second()
        if (deployment.state == SimState.RUNNING && world.now >= deployment.endsAt) end(StopReason.EXPIRED, EXPIRED)
    }

    private fun deliver(deployment: SimDeployment, fact: GameFact) {
        var delivered = false
        for (subscription in deployment.subscriptions.toList()) {
            if (deployment.state != SimState.RUNNING || !subscription.active || !subscription.type.isInstance(fact)) continue
            delivered = true
            invoke("a handler of ${subscription.type.simpleName}") { subscription.handler.accept(fact) }
        }
        if (delivered) deployment.factsDelivered++
    }

    private fun timers(deployment: SimDeployment) {
        val due = deployment.timers.filter { it.due <= world.tick }.sortedWith(compareBy({ it.due }, { it.seq }))
        for (timer in due) {
            if (deployment.state != SimState.RUNNING || !timer.active) continue
            if (timer.every > 0) {
                timer.due += timer.every
            } else {
                timer.active = false
                deployment.timers.remove(timer)
            }
            invoke("a timer") { timer.task.run() }
        }
    }

    private fun callbacks(deployment: SimDeployment) {
        val ready = deployment.awaiting.filter { it.readyAt <= world.tick }
        deployment.awaiting.removeAll(ready.toSet())
        for (act in ready) {
            if (deployment.state != SimState.RUNNING) return
            val callback = act.callback ?: continue
            val outcome = act.outcome ?: continue
            invoke("the callback of ${act.tool}") { callback.accept(outcome) }
        }
    }

    /** At each second of the bench: more than 50 ms of calls a second, five seconds in a row, is a fault *slowness* (M5 §3.7). */
    private fun second() {
        if (world.tick % Limits.TICKS_PER_SECOND != 0) return
        slowSeconds = if (secondNanos > ForgeLimits.DEPLOYMENT_MS_PER_SECOND * NANOS_PER_MS) slowSeconds + 1 else 0
        secondNanos = 0
        if (slowSeconds >= ForgeLimits.SLOW_SECONDS) {
            fail(
                "slow in $phase: more than ${ForgeLimits.DEPLOYMENT_MS_PER_SECOND} ms of calls a second, ${ForgeLimits.SLOW_SECONDS} seconds in a " +
                    "row (tick ${world.tick}): do less at each tick, spread the work over timers",
            )
        }
    }

    // ── Calls ──────────────────────────────────────────────────────────────────────────────────────

    /** Runs [body], a call of the module labelled [label] (`a timer`), measured; then answers its acts and applies its end. */
    private fun invoke(label: String, body: () -> Unit) {
        val deployment = deployment ?: return
        guard()
        val call = Invocation()
        val outer = deployment.call
        deployment.call = call
        val result = try {
            host.call(ForgeLimits.CALL_NANOS, body)
        } finally {
            deployment.call = outer
        }
        slowestNanos = maxOf(slowestNanos, result.nanos)
        secondNanos += result.nanos
        deployment.settle(call)
        result.fault?.let { fail("fault in $phase, $label at tick ${world.tick}: $it") }
        if (result.nanos > ForgeLimits.CALL_NANOS) {
            fail(
                "slow in $phase, $label at tick ${world.tick}: ${ms(result.nanos)} ms, the most is ${Limits.MAX_CALL_MILLIS} ms a call: " +
                    "do less in one call, spread the work over timers",
            )
        }
        call.end?.let { end(StopReason.ENDED, it) }
    }

    /**
     * Runs scenario [name], a method `public void name(Bench b)` of the module's `public static` class `Scenarios`, on
     * this bench, new: its class's constructor is a call of the module; the scenario's own code runs within the bench's
     * time, the module's calls it makes happen each measured on their own. The first failure, or null when it passed.
     */
    fun scenario(name: String): String? {
        try {
            val type = scenarios() ?: return "$phase: the module has no public static class Scenarios with a public constructor without arguments"
            val method = type.methods.firstOrNull { it.name == name && it.parameterTypes.contentEquals(arrayOf(Bench::class.java)) && it.returnType == Void.TYPE }
                ?: return "$phase: Scenarios has no method public void $name(Bench b)"
            var instance: Any? = null
            own("the constructor of Scenarios", ForgeLimits.CALL_NANOS) { instance = unwrapped { type.getDeclaredConstructor().newInstance() } }
            guard()
            val result = host.call(deadline - System.nanoTime()) { unwrapped { method.invoke(instance, this) } }
            failure?.let { return it }
            return result.fault?.let { "$phase failed: $it" }
        } catch (stop: BenchStop) {
            return failure
        }
    }

    private fun scenarios(): Class<*>? = try {
        Class.forName(host.main.name + SCENARIOS, false, host.main.classLoader)
    } catch (e: ClassNotFoundException) {
        null
    }

    /** Runs [body], the module's code outside a deployment (the scenarios' constructor), measured for [budgetNanos]. */
    private fun own(label: String, budgetNanos: Long, body: () -> Unit) {
        guard()
        val result = host.call(budgetNanos, body)
        result.fault?.let { fail("fault in $phase, $label: $it") }
        if (result.nanos > budgetNanos) fail("slow in $phase, $label: ${ms(result.nanos)} ms, the most is ${Limits.MAX_CALL_MILLIS} ms a call")
    }

    override fun check(condition: Boolean, message: String?) {
        if (condition) return
        val where = host.where(Throwable())?.let { " at $it" }.orEmpty()
        fail("$phase failed: check \"${message.orEmpty()}\"$where")
    }

    /** Records [message] as the bench's failure (the first one stays) and stops the run. */
    private fun fail(message: String): Nothing {
        if (failure == null) failure = message
        throw BenchStop()
    }

    /** Before each call and tick: the bench's time left, and whether the Forge cancelled it. */
    private fun guard() {
        if (Thread.currentThread().isInterrupted) {
            cancelled = true
            fail("$phase was cancelled")
        }
        if (System.nanoTime() - deadline > 0) {
            fail(
                "the bench ran out of its ${ToolText.number(budgetMs / MS_PER_SECOND)} s in $phase at tick ${world.tick}: " +
                    "ask fewer ticks of the scenarios, and less of each call",
            )
        }
    }

    // ── What the scenario reads ────────────────────────────────────────────────────────────────────

    override fun acts(): List<String> = frozen(deployment?.lines.orEmpty())

    override fun creatures(): List<CreatureInfo> = frozen(world.creatures.values.filter { it.own }.map { it.info() })

    override fun notes(): List<String> = frozen(deployment?.notes.orEmpty())

    override fun ended(): String? = deployment?.ended

    /** What the module retains now, as [host] estimates it, while the deployment runs; null otherwise or when unknown. */
    fun retained(): Long? {
        val deployment = deployment?.takeIf { it.state == SimState.RUNNING } ?: return null
        val instance = deployment.instance ?: return null
        return host.retained(listOf<Any>(instance) + deployment.handed())
    }

    private fun known(name: String?): SimPlayer = world.player(name) ?: throw ForgeRefusal("$name is not a player of the bench: put them there with player(…) first")

    companion object {
        /** The deployment of a bench, and the judged challenge it referees. */
        const val DEPLOYMENT_ID = "C-1"
        const val CHALLENGE_ID = "C-2"

        /** How long a deployment of a scenario runs before it expires. */
        const val SCENARIO_MINUTES = 10

        /** What [ended] says of a deployment whose time ran out (the canon's note, M5 §6.6). */
        const val EXPIRED = "its time ran out"

        /** The tools [refuseNext] knows: those of the SDK's acts, and `game` for a judged challenge's end. */
        val TOOLS = listOf(
            "say", "show_title", "hud", "play_sound", "particles", "fireworks", "lightning", "explosion", "apply_effect", "set_attribute",
            "launch", "teleport", "spawn", "punish", "place_blocks", "creature_attack", "game",
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
