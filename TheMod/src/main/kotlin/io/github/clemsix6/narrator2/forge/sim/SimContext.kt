package io.github.clemsix6.narrator2.forge.sim

import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.forge.ParamSpec
import io.github.clemsix6.narrator2.forge.api.Acts
import io.github.clemsix6.narrator2.forge.api.Args
import io.github.clemsix6.narrator2.forge.api.BlockInfo
import io.github.clemsix6.narrator2.forge.api.Cell
import io.github.clemsix6.narrator2.forge.api.Creature
import io.github.clemsix6.narrator2.forge.api.CreatureInfo
import io.github.clemsix6.narrator2.forge.api.Creatures
import io.github.clemsix6.narrator2.forge.api.Events
import io.github.clemsix6.narrator2.forge.api.ForgeModule
import io.github.clemsix6.narrator2.forge.api.ForgeRefusal
import io.github.clemsix6.narrator2.forge.api.Game
import io.github.clemsix6.narrator2.forge.api.GameFact
import io.github.clemsix6.narrator2.forge.api.Ground
import io.github.clemsix6.narrator2.forge.api.Limits
import io.github.clemsix6.narrator2.forge.api.ModuleContext
import io.github.clemsix6.narrator2.forge.api.Outcome
import io.github.clemsix6.narrator2.forge.api.ParamType
import io.github.clemsix6.narrator2.forge.api.Pending
import io.github.clemsix6.narrator2.forge.api.Place
import io.github.clemsix6.narrator2.forge.api.PlayerInfo
import io.github.clemsix6.narrator2.forge.api.Scheduled
import io.github.clemsix6.narrator2.forge.api.Spawn
import io.github.clemsix6.narrator2.forge.api.Storage
import io.github.clemsix6.narrator2.forge.api.Subscription
import io.github.clemsix6.narrator2.forge.api.Timers
import io.github.clemsix6.narrator2.forge.api.Who
import io.github.clemsix6.narrator2.forge.api.World
import io.github.clemsix6.narrator2.mechanics.rule.MobAttributeTool
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.tool.action.MobAttributeKey
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.Collections
import java.util.Locale
import java.util.Random
import java.util.function.Consumer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Where a deployment of the bench stands. */
internal enum class SimState { RUNNING, STOPPING, ENDED }

/** [items] as a list nobody can change: what the SDK returns (a Kotlin list is a `java.util.ArrayList` to a module). */
internal fun <T> frozen(items: Collection<T>): List<T> = java.util.List.copyOf(items)

/** One call of the module at the bench (an invocation, M5 §4.4): the acts it asked, in order, and its `ctx.end`. */
internal class Invocation {
    val acts = ArrayList<SimAct>()
    var end: String? = null
}

/**
 * A call of the module that the bench checks dry by one of the Narrator's real tools (M5 §6.2): an act through an
 * engine's tool, or the steering of a creature that a law's tool bounds (`mob_effect`, `mob_attribute`).
 *
 * @property line what the forger reads: the act's line ([BenchImpl.acts]).
 */
internal class DryCall(val tool: String, val args: JsonObject, val line: String) {
    /** Two calls with the same tool and arguments are one to check. */
    val key: String get() = "$tool $args"
}

/** How an act counts toward the module's own limits (M5 §3.6). */
internal enum class ActKind {
    /** An act: 4 a call, 30 a minute. */
    ACT,

    /** `say`, `sayTo`, `title`: an act, and one of the 4 lines a minute. */
    LINE,

    /** `hud`: an act, and one line a second per player. */
    HUD,

    /** `Game.win`, `Game.lose`: queued like an act, counted nowhere. */
    GAME,
}

/**
 * An act the module asked at the bench (M5 §7): recorded, never done. Once its call returns, the bench answers it
 * ([SimDeployment.settle]): the module's own limits, a refusal the scenario imposed ([BenchImpl.refuseNext]), a
 * player who is not online, then [effect] (the little the bench does: creatures for `spawn`, a game's end, checks
 * of the bench's world), else `ok (simulated)`. The outcome comes back to [then]'s callback at the next tick.
 *
 * @property tool the Narrator's tool it goes through (`say`, `show_title`, `hud`, …, `place_blocks`,
 *   `creature_attack`), or `game` for a judged challenge's end: the name [BenchImpl.refuseNext] takes.
 * @property args its arguments as the tool reads them, for the dry checks; null for the tools only the engine has
 *   and the game's end, which the bench checks itself.
 * @property player the online player it names, if any.
 */
internal class SimAct(
    private val deployment: SimDeployment,
    val tool: String,
    val line: String,
    val args: JsonObject?,
    val kind: ActKind,
    val player: String?,
    val effect: () -> Outcome?,
) : Pending {
    val generation = deployment.generation
    var outcome: Outcome? = null
    var callback: Consumer<Outcome>? = null

    /** The tick its callback is due at. */
    var readyAt = 0

    override fun then(callback: Consumer<Outcome>?) = deployment.then(this, callback)
}

/** A subscription of the bench ([Events.on]). */
internal class SimSubscription(private val deployment: SimDeployment, val type: Class<*>, val handler: Consumer<Any>) : Subscription {
    var active = true

    override fun cancel() {
        active = false
        deployment.subscriptions.remove(this)
    }

    override fun active(): Boolean = active
}

/** A timer of the bench ([Timers]): due at tick [due], again every [every] ticks when it repeats. */
internal class SimTimer(private val deployment: SimDeployment, val task: Runnable, var due: Int, val every: Int, val seq: Int) : Scheduled {
    var active = true

    override fun cancel() {
        active = false
        deployment.timers.remove(this)
    }

    override fun active(): Boolean = active
}

/** The judged challenge a deployment of the bench referees ([BenchImpl.challenge]). */
internal class SimGame(val id: String, val players: List<String>) {
    var running = true
}

/**
 * A deployment of a module at the bench (M5 §7): what a [SimContext] reads and writes (storage, subscriptions,
 * timers, facts and acts on their way, its creatures), the SDK's own limits (M5 §3.6), and the records the bench
 * shows (acts' lines, notes, the calls to check dry). [BenchImpl] drives it: it calls the module and sets [call]
 * around each call, then [settle]s the call's acts. A restart keeps the deployment (storage, creatures, records)
 * and gives the new instance a new [context].
 *
 * @property refusals what [BenchImpl.refuseNext] queued, by tool.
 */
internal class SimDeployment(
    val world: SimWorld,
    val id: String,
    val specs: List<ParamSpec>,
    val values: Map<String, Any?>,
    val endsAt: Long,
    val game: SimGame?,
    private val refusals: Map<String, ArrayDeque<String>>,
) {
    var state = SimState.RUNNING
    var generation = 0
        private set
    var instance: ForgeModule? = null
    var context: SimContext = SimContext(this, generation, restarted = false)
        private set

    /** The module's call under way; null between calls. */
    var call: Invocation? = null

    val storage = LinkedHashMap<String, String>()
    val random = Random(SimWorld.SEED)

    /** Its creatures' UUIDs, in the order they became its own; and those it had and released. */
    val own = LinkedHashSet<String>()
    private val released = HashSet<String>()

    val subscriptions = ArrayList<SimSubscription>()
    val timers = ArrayList<SimTimer>()
    private var timerSeq = 0
    private val facts = ArrayDeque<GameFact>()

    /** The order of timers due at the same tick: the order they were made. */
    fun nextTimerSeq(): Int = timerSeq++

    /** The classes of facts the module subscribes to now. */
    fun subscribedTypes(): List<Class<*>> = subscriptions.filter { it.active }.map { it.type }.distinct()

    /** Acts answered whose callback is due. */
    val awaiting = ArrayList<SimAct>()

    // What the bench shows.
    val lines = ArrayList<String>()
    val notes = ArrayList<String>()
    val dry = LinkedHashMap<String, DryCall>()
    val counts = LinkedHashMap<String, Int>()
    var ended: String? = null
    var factsDelivered = 0
    var factsDropped = 0
    var logsDropped = 0
    var actsOverLimits = 0
    var dryDropped = 0

    // The module's own limits, over the last minute of the bench's clock.
    private val actTimes = ArrayDeque<Long>()
    private val lineTimes = ArrayDeque<Long>()
    private val hudTimes = HashMap<String, Long>()
    private val logTimes = ArrayDeque<Long>()
    private val factTimes = ArrayDeque<Long>()
    private var toldAt: Long? = null
    private var held: String? = null
    private var heldDropped = 0

    /** A new context for a new instance of the module: the earlier one stops answering. */
    fun newContext(restarted: Boolean): SimContext {
        context = SimContext(this, generation, restarted)
        return context
    }

    /** After `stop(SERVER_STOPPING)`: what a restart does not bring back (subscriptions, timers, facts and acts on their way). */
    fun restart() {
        generation++
        state = SimState.RUNNING
        instance = null
        clearRuns()
    }

    /** The deployment is over: [why] it ended (null when the bench just stops), its creatures taken back. */
    fun finish(why: String?) {
        state = SimState.ENDED
        ended = why
        own.toList().forEach { release(it) }
        clearRuns()
    }

    private fun clearRuns() {
        subscriptions.forEach { it.active = false }
        subscriptions.clear()
        timers.forEach { it.active = false }
        timers.clear()
        awaiting.clear()
        facts.clear()
    }

    // ── Facts ──────────────────────────────────────────────────────────────────────────────────────

    /** Queues [fact] for the next tick when a subscription of the module wants its class. */
    fun queue(fact: GameFact) {
        if (state == SimState.RUNNING && subscriptions.any { it.active && it.type.isInstance(fact) }) facts.addLast(fact)
    }

    /** The facts queued before this tick, within the engine's limits (256 a tick, 3,000 a minute); the others are dropped and counted. */
    fun takeFacts(): List<GameFact> {
        val now = world.now
        while (factTimes.isNotEmpty() && factTimes.first() <= now - MINUTE_MS) factTimes.removeFirst()
        val room = minOf(ForgeLimits.FACTS_PER_TICK, ForgeLimits.FACTS_PER_MINUTE - factTimes.size).coerceAtLeast(0)
        val batch = facts.take(room)
        factsDropped += facts.size - batch.size
        facts.clear()
        repeat(batch.size) { factTimes.addLast(now) }
        return batch
    }

    // ── Time ───────────────────────────────────────────────────────────────────────────────────────

    /** At each tick: its summoned creatures whose lifetime ran out vanish (a `CreatureGone`), a held note to the Director is told. */
    fun timePasses() {
        val now = world.now
        val vanished = own.mapNotNull { world.creatures[it] }.filter { creature -> creature.vanishesAt?.let { it <= now } == true }
        for (creature in vanished) {
            world.creatures.remove(creature.uuid)
            own.remove(creature.uuid)
            queue(GameFact.CreatureGone(null, creature.at, Who(creature.type, creature.uuid, creature.name), "vanished"))
        }
        val note = held
        val last = toldAt
        if (note != null && last != null && now - last >= ForgeLimits.DIRECTOR_NOTE_GAP_MS) {
            notes += "director: $note" + if (heldDropped > 0) " (+$heldDropped earlier notes dropped)" else ""
            toldAt = now
            held = null
            heldDropped = 0
        }
    }

    // ── Acts ───────────────────────────────────────────────────────────────────────────────────────

    /** Asks [act] in the module's call under way. */
    fun ask(act: SimAct): SimAct {
        val call = call ?: throw ForgeRefusal("acts are asked from the module's own calls only")
        call.acts += act
        counts.merge(act.tool, 1, Int::plus)
        act.args?.let { keep(DryCall(act.tool, it, act.line)) }
        return act
    }

    /** Keeps [call] for the dry checks, once per tool and arguments, [MAX_DRY_CALLS] at most. */
    fun keep(call: DryCall) {
        if (call.key in dry) return
        if (dry.size >= MAX_DRY_CALLS) dryDropped++ else dry[call.key] = call
    }

    /** Answers the acts of [call], in order, once it returned; an act's line then says how it ended when it was not `ok`. */
    fun settle(call: Invocation) {
        var counted = 0
        for (act in call.acts) {
            val outcome = if (state != SimState.RUNNING) {
                DROPPED
            } else {
                val limited = overLimits(act, counted)
                if (limited != null) {
                    actsOverLimits++
                    limited
                } else {
                    if (act.kind != ActKind.GAME) counted++
                    refusal(act) ?: offline(act) ?: act.effect() ?: OK
                }
            }
            act.outcome = outcome
            lines += act.line + if (outcome.ok()) "" else " → ${outcome.status}"
            if (act.callback != null && outcome !== DROPPED) schedule(act)
        }
    }

    /** [callback] gets [act]'s outcome at the tick after it came. */
    fun then(act: SimAct, callback: Consumer<Outcome>?) {
        if (callback == null) throw ForgeRefusal("then needs a callback")
        if (act.callback != null) throw ForgeRefusal("this act has its callback already: one per act")
        act.callback = callback
        val outcome = act.outcome
        if (outcome != null && outcome !== DROPPED && act.generation == generation && state == SimState.RUNNING) schedule(act)
    }

    private fun schedule(act: SimAct) {
        act.readyAt = world.tick + 1
        awaiting += act
    }

    /** The module's own limits on acts (M5 §3.6): 4 a call, 30 a minute, 4 lines a minute, one action bar a second per player. */
    private fun overLimits(act: SimAct, counted: Int): Outcome? {
        if (act.kind == ActKind.GAME) return null
        val now = world.now
        trim(actTimes, now)
        trim(lineTimes, now)
        val hudTargets = if (act.kind == ActKind.HUD) (act.player?.let { listOf(it) } ?: world.online().map { it.name }).map { key(it) } else emptyList()
        val refusal = when {
            counted >= Limits.MAX_ACTS_PER_CALL -> "${Limits.MAX_ACTS_PER_CALL} acts in this call already: the next ones need another call"
            actTimes.size >= Limits.MAX_ACTS_PER_MINUTE -> "${Limits.MAX_ACTS_PER_MINUTE} acts in the last minute already"
            act.kind == ActKind.LINE && lineTimes.size >= Limits.MAX_LINES_PER_MINUTE ->
                "${Limits.MAX_LINES_PER_MINUTE} lines (say, sayTo, title) in the last minute already"
            hudTargets.any { target -> hudTimes[target]?.let { now - it < ForgeLimits.HUD_GAP_MS } == true } ->
                "an action bar line less than a second ago for ${act.player ?: "someone online"}"
            else -> null
        }
        if (refusal != null) return denied(MODULE_ACTS, refusal)
        actTimes.addLast(now)
        if (act.kind == ActKind.LINE) lineTimes.addLast(now)
        hudTargets.forEach { hudTimes[it] = now }
        return null
    }

    private fun refusal(act: SimAct): Outcome? = refusals[act.tool]?.removeFirstOrNull()?.let { Outcome(statusOf(it), it, null) }

    private fun offline(act: SimAct): Outcome? {
        val player = act.player ?: return null
        return if (world.online(player) == null) invalid("$player is not online") else null
    }

    // ── Notes ──────────────────────────────────────────────────────────────────────────────────────

    fun log(line: String) {
        val now = world.now
        trim(logTimes, now)
        if (logTimes.size >= Limits.MAX_LOG_LINES_PER_MINUTE) {
            logsDropped++
        } else {
            logTimes.addLast(now)
            notes += "log: $line"
        }
    }

    fun tell(note: String) {
        val now = world.now
        val last = toldAt
        if (last == null || now - last >= ForgeLimits.DIRECTOR_NOTE_GAP_MS) {
            notes += "director: $note"
            toldAt = now
        } else {
            if (held != null) heldDropped++
            held = note
        }
    }

    // ── Creatures ──────────────────────────────────────────────────────────────────────────────────

    fun isReleased(uuid: String): Boolean = uuid in released

    /** Its creature [uuid] is not its own any more: a summoned one vanishes, a claimed one gets back to normal, where it was claimed. */
    fun release(uuid: String) {
        own.remove(uuid)
        released += uuid
        val creature = world.creatures[uuid] ?: return
        if (creature.summoned) {
            world.creatures.remove(uuid)
            return
        }
        creature.own = false
        creature.at = creature.claimedAt ?: creature.at
        creature.claimedAt = null
        creature.name = creature.nameBefore
        creature.gravity = true
        creature.glow = false
        creature.target = null
        creature.vx = 0.0
        creature.vy = 0.0
        creature.vz = 0.0
        creature.walkTo = null
    }

    /** The objects the module handed over: what the memory gauge starts from, with its instance. */
    fun handed(): List<Any> = subscriptions.map { it.handler } + timers.map { it.task } + awaiting.mapNotNull { it.callback }

    companion object {
        /** `DENIED (module_acts)`: past the module's own limits on acts (M5 §3.6). */
        const val MODULE_ACTS = "module_acts"

        /** `DENIED (module_creatures)`: a `spawn` past the module's own creatures (M5 §3.6). */
        const val MODULE_CREATURES = "module_creatures"

        /** Distinct calls kept for the dry checks, per deployment. */
        const val MAX_DRY_CALLS = 200

        const val MINUTE_MS = 60_000L

        val OK = Outcome(ToolStatus.OK.id, "ok (simulated)", null)
        val DROPPED = Outcome("dropped", "dropped: the deployment ended before this act", null)

        fun invalid(problem: String) = Outcome(ToolStatus.INVALID.id, ToolResult.invalid(problem).text, null)

        fun denied(rule: String, text: String) = Outcome(ToolStatus.DENIED.id, ToolResult.denied(rule, text).text, null)

        /** The status of an answer a scenario imposed, read from its start (`DENIED (…)`, `Invalid arguments: …`, `FAILED: …`, `ok: …`). */
        fun statusOf(text: String): String = when {
            text.startsWith("ok") -> ToolStatus.OK.id
            text.startsWith("Invalid arguments") -> ToolStatus.INVALID.id
            text.startsWith("FAILED") -> ToolStatus.FAILED.id
            text.startsWith("UNKNOWN") -> ToolStatus.UNKNOWN.id
            else -> ToolStatus.DENIED.id
        }

        private fun trim(times: ArrayDeque<Long>, now: Long) {
            while (times.isNotEmpty() && times.first() <= now - MINUTE_MS) times.removeFirst()
        }

        private fun key(name: String) = name.lowercase(Locale.ROOT)
    }
}

/**
 * The [ModuleContext] of a module at the bench (M5 §7): the SDK over the simulated world, with the SDK's own limits
 * and refusals, and every act recorded rather than done. One per instance of the module: after a restart or the end,
 * every method throws [ForgeRefusal]; during `stop`, reads work, acts are dropped, timers and subscriptions refused.
 * Objects the module hands over (handlers, tasks, lists) are only called or read inside the module's own calls.
 */
internal class SimContext(private val deployment: SimDeployment, private val generation: Int, private val restarted: Boolean) : ModuleContext {
    private val world = deployment.world
    private val args = SimArgs(deployment.specs, deployment.values)
    private val events = SimEvents()
    private val timers = SimTimers()
    private val worldView = SimWorldView()
    private val acts = SimActs()
    private val creatures = SimCreatures()
    private val ground = SimGround()
    private val storage = SimStorage()
    private val game = deployment.game?.let { SimGameView(it) }

    /** The deployment, while this context answers. */
    private fun live(): SimDeployment {
        if (deployment.generation != generation || deployment.state == SimState.ENDED) {
            throw ForgeRefusal("the deployment ${deployment.id} is over: this context no longer answers")
        }
        return deployment
    }

    /** The deployment, while it runs (not in `stop`). */
    private fun running(what: String): SimDeployment {
        val deployment = live()
        if (deployment.state != SimState.RUNNING) throw ForgeRefusal("the deployment ${deployment.id} is ending: no new $what in stop")
        return deployment
    }

    override fun id(): String = live().id

    override fun restarted(): Boolean = live().let { restarted }

    override fun now(): Long = live().world.now

    override fun endsAt(): Long = live().endsAt

    override fun random(): Random = live().random

    override fun args(): Args = live().let { args }

    override fun events(): Events = live().let { events }

    override fun timers(): Timers = live().let { timers }

    override fun world(): World = live().let { worldView }

    override fun acts(): Acts = live().let { acts }

    override fun creatures(): Creatures = live().let { creatures }

    override fun ground(): Ground = live().let { ground }

    override fun storage(): Storage = live().let { storage }

    override fun game(): Game? = live().let { game }

    override fun log(line: String?) {
        live().log(ModelText.cut(line.orEmpty(), Limits.MAX_NOTE_CHARS))
    }

    override fun tellDirector(note: String?) {
        live().tell(ModelText.cut(note.orEmpty(), Limits.MAX_NOTE_CHARS))
    }

    override fun end(why: String?) {
        val deployment = live()
        if (deployment.state != SimState.RUNNING) return
        val call = deployment.call ?: throw ForgeRefusal("end is called from the module's own calls only")
        if (call.end == null) call.end = ModelText.cut(why.orEmpty(), Limits.MAX_NOTE_CHARS)
    }

    // ── Events and timers ──────────────────────────────────────────────────────────────────────────

    private inner class SimEvents : Events {
        override fun <F : GameFact> on(type: Class<F>?, handler: Consumer<F>?): Subscription {
            val deployment = running("subscription")
            if (type == null || handler == null) throw ForgeRefusal("on needs a fact's class and a handler: on(GameFact.Chat.class, chat -> …)")
            if (type !in FACTS) throw ForgeRefusal("subscribe to one of GameFact's records (GameFact.Chat.class…), not ${type.simpleName}")
            if (deployment.subscriptions.size >= Limits.MAX_SUBSCRIPTIONS) {
                throw ForgeRefusal("${Limits.MAX_SUBSCRIPTIONS} subscriptions are active already, the most: cancel one first")
            }
            @Suppress("UNCHECKED_CAST")
            val subscription = SimSubscription(deployment, type, handler as Consumer<Any>)
            deployment.subscriptions += subscription
            return subscription
        }
    }

    private inner class SimTimers : Timers {
        override fun after(ticks: Int, task: Runnable?): Scheduled = schedule(ticks, task, repeating = false)

        override fun every(ticks: Int, task: Runnable?): Scheduled = schedule(ticks, task, repeating = true)

        private fun schedule(ticks: Int, task: Runnable?, repeating: Boolean): Scheduled {
            val deployment = running("timer")
            if (task == null) throw ForgeRefusal("a timer needs a task")
            if (ticks !in 1..Limits.MAX_DELAY_TICKS) throw ForgeRefusal("a timer's delay is 1 to ${Limits.MAX_DELAY_TICKS} ticks (asked $ticks)")
            if (deployment.timers.size >= Limits.MAX_PENDING_TIMERS) {
                throw ForgeRefusal("${Limits.MAX_PENDING_TIMERS} timers are waiting already, the most: cancel one first")
            }
            if (repeating && deployment.timers.count { it.every > 0 } >= Limits.MAX_REPEATING_TIMERS) {
                throw ForgeRefusal("${Limits.MAX_REPEATING_TIMERS} repeating timers are active already, the most: cancel one first")
            }
            val timer = SimTimer(deployment, task, world.tick + ticks, if (repeating) ticks else 0, deployment.nextTimerSeq())
            deployment.timers += timer
            return timer
        }
    }

    // ── World ──────────────────────────────────────────────────────────────────────────────────────

    private inner class SimWorldView : World {
        override fun players(): List<PlayerInfo> = frozen(live().world.online().map { it.info() })

        override fun player(name: String?): PlayerInfo? = live().world.online(name)?.info()

        override fun creatures(around: Place?, radius: Double, type: String?): List<CreatureInfo> {
            val deployment = live()
            if (around == null) throw ForgeRefusal("creatures needs a place to look around")
            if (!(radius >= 0.0 && radius <= Limits.MAX_CREATURES_RADIUS)) {
                throw ForgeRefusal("creatures looks ${Limits.MAX_CREATURES_RADIUS} blocks around at most (asked ${ToolText.number(radius)})")
            }
            val wanted = type?.let { id(it) }
            return frozen(
                deployment.world.creatures.values
                    .filter { (wanted == null || it.type == wanted) && it.at.distance(around) <= radius }
                    .sortedBy { it.at.distance(around) }
                    .take(Limits.MAX_CREATURES_FOUND)
                    .map { it.info() },
            )
        }

        override fun block(cell: Cell?): BlockInfo = live().world.blockInfo(cell ?: throw ForgeRefusal("block needs a cell"))

        override fun surface(dimension: String?, x: Int, z: Int): Int {
            if (dimension.isNullOrBlank()) throw ForgeRefusal("surface needs a dimension: overworld, the_nether or the_end")
            return live().world.surface(ToolText.dimension(ToolText.dimensionId(dimension)), x, z)
        }

        override fun biome(at: Place?): String = live().let { BIOME }

        override fun count(player: String?, item: String?): Int {
            val online = live().world.online(player) ?: return -1
            val wanted = id(item)
            return (online.items[wanted] ?: 0) + if (online.heldItem == wanted && wanted != SimWorld.AIR) 1 else 0
        }

        override fun dayTime(): Long = (NOON + live().world.tick) % DAY_TICKS

        override fun weather(): String = live().let { WEATHER }
    }

    // ── Acts ───────────────────────────────────────────────────────────────────────────────────────

    private fun act(tool: String, line: String, args: JsonObject?, kind: ActKind = ActKind.ACT, player: String? = null, effect: () -> Outcome? = { null }): Pending {
        val deployment = live()
        return deployment.ask(SimAct(deployment, tool, line, args, kind, player, effect))
    }

    private inner class SimActs : Acts {
        override fun say(text: String?): Pending =
            act("say", "say: ${need(text, "say", "a text")}", buildJsonObject { put("text", text) }, ActKind.LINE)

        override fun sayTo(player: String?, text: String?): Pending {
            val name = need(player, "sayTo", "a player")
            return act("say", "say to $name: ${need(text, "sayTo", "a text")}", buildJsonObject { put("text", text); to(name) }, ActKind.LINE, name)
        }

        override fun title(player: String?, title: String?, subtitle: String?): Pending {
            val line = "title ${player ?: EVERYONE}: ${need(title, "title", "a title")}" + (subtitle?.let { " / $it" } ?: "")
            val args = buildJsonObject {
                put("title", title)
                subtitle?.let { put("subtitle", it) }
                to(player)
            }
            return act("show_title", line, args, ActKind.LINE, player)
        }

        override fun hud(player: String?, text: String?): Pending {
            val line = need(text, "hud", "a text")
            return act("hud", "hud ${player ?: EVERYONE}: $line", null, ActKind.HUD, player) {
                val length = line.codePointCount(0, line.length)
                when {
                    line.isBlank() -> SimDeployment.invalid("hud needs a text")
                    length > ForgeLimits.HUD_CHARS -> SimDeployment.invalid("hud shows ${ForgeLimits.HUD_CHARS} characters at most (asked $length)")
                    else -> null
                }
            }
        }

        override fun sound(player: String?, sound: String?): Pending {
            val id = id(need(sound, "sound", "a sound id"))
            return act("play_sound", "sound ${player ?: EVERYONE}: $id", buildJsonObject { put("sound", id); to(player) }, player = player)
        }

        override fun particles(at: Place?, particle: String?, count: Int): Pending {
            val place = need(at, "particles", "a place")
            val id = id(need(particle, "particles", "a particle id"))
            return act("particles", "particles $id x$count at ${shown(place)}", buildJsonObject { at(place); put("particle", id); put("count", count) })
        }

        override fun fireworks(at: Place?, count: Int): Pending {
            val place = need(at, "fireworks", "a place")
            return act("fireworks", "fireworks x$count at ${shown(place)}", buildJsonObject { at(place); put("count", count) })
        }

        override fun lightning(at: Place?): Pending {
            val place = need(at, "lightning", "a place")
            return act("lightning", "lightning at ${shown(place)}", buildJsonObject { at(place) })
        }

        override fun smite(player: String?, hearts: Int): Pending {
            val name = need(player, "smite", "a player")
            return act("lightning", "smite $name $hearts", buildJsonObject { put("player", name); put("damage_hearts", hearts) }, player = name)
        }

        override fun explosion(at: Place?, power: Double, hurts: Boolean): Pending {
            val place = need(at, "explosion", "a place")
            val line = "explosion ${ToolText.number(power)} at ${shown(place)}" + if (hurts) "" else " harmless"
            return act("explosion", line, buildJsonObject { at(place); put("power", power); put("hurts", hurts); put("offset", 0) })
        }

        override fun effect(player: String?, effect: String?, seconds: Int, level: Int): Pending {
            val name = need(player, "effect", "a player")
            val id = id(need(effect, "effect", "an effect id"))
            val args = buildJsonObject {
                putJsonArray("players") { add(name) }
                put("effect", id)
                put("seconds", seconds)
                put("level", level)
            }
            return act("apply_effect", "effect $name $id ${seconds}s level $level", args, player = name)
        }

        override fun attribute(player: String?, attribute: String?, multiply: Double, seconds: Int): Pending {
            val name = need(player, "attribute", "a player")
            val id = id(need(attribute, "attribute", "an attribute id"))
            val args = buildJsonObject {
                put("player", name)
                put("attribute", id)
                put("multiply", multiply)
                put("seconds", seconds)
            }
            return act("set_attribute", "attribute $name $id x${ToolText.number(multiply)} ${seconds}s", args, player = name)
        }

        override fun launch(player: String?, direction: String?, strength: Double): Pending {
            val name = need(player, "launch", "a player")
            val way = id(need(direction, "launch", "a direction"))
            val args = buildJsonObject {
                put("player", name)
                put("direction", way)
                put("strength", strength)
            }
            return act("launch", "launch $name $way ${ToolText.number(strength)}", args, player = name)
        }

        override fun teleport(player: String?, to: Place?): Pending {
            val name = need(player, "teleport", "a player")
            val place = need(to, "teleport", "a place")
            val args = buildJsonObject {
                put("player", name)
                put("x", block(place.x))
                put("z", block(place.z))
                put("y", block(place.y))
            }
            return act("teleport", "teleport $name to ${shown(place)}", args, player = name) {
                val where = world.online(name)?.at?.dimension
                if (where != null && where != place.dimension) SimDeployment.invalid("$name is in $where: a teleport stays in their dimension") else null
            }
        }

        override fun teleportRandom(player: String?, radius: Int): Pending {
            val name = need(player, "teleportRandom", "a player")
            return act("teleport", "teleport $name at random within $radius", buildJsonObject { put("player", name); put("random_radius", radius) }, player = name)
        }

        override fun spawn(type: String?, near: String?, how: Spawn?): Pending {
            val entity = id(need(type, "spawn", "a creature type"))
            val name = need(near, "spawn", "a player")
            val spawn = how ?: Spawn.of(1)
            val args = buildJsonObject {
                put("entity", entity)
                put("count", spawn.count)
                put("near", name)
                put("distance", spawn.distance)
                spawn.name?.let { put("name", it) }
                put("target", spawn.target)
                put("lifetime_minutes", spawn.lifetimeMinutes)
                put("height", spawn.height)
                put("baby", spawn.baby)
            }
            return act("spawn", "spawn $entity x${spawn.count} near $name", args, player = name) { summon(entity, name, spawn) }
        }

        override fun punish(player: String?, punishment: String?, severity: Int, reason: String?): Pending {
            val name = need(player, "punish", "a player")
            val id = id(need(punishment, "punish", "a punishment"))
            val args = buildJsonObject {
                put("player", name)
                put("punishment", id)
                put("severity", severity)
                put("reason", reason)
            }
            return act("punish", "punish $name $id $severity", args, player = name)
        }
    }

    /** `spawn` at the bench: its creatures around [near], evenly, [Spawn.distance] away and [Spawn.height] up, its own for their lifetime. */
    private fun summon(type: String, near: String, how: Spawn): Outcome? {
        val player = world.online(near) ?: return null
        if (how.count !in 1..MAX_SPAWN) return SimDeployment.invalid("count is 1 to $MAX_SPAWN (asked ${how.count})")
        if (deployment.own.size + how.count > Limits.MAX_OWN_CREATURES) {
            return SimDeployment.denied(
                SimDeployment.MODULE_CREATURES,
                "${deployment.own.size} creatures of its own already, ${Limits.MAX_OWN_CREATURES} at most (asked ${how.count} more)",
            )
        }
        val distance = how.distance.coerceIn(MIN_DISTANCE, MAX_DISTANCE).toDouble()
        val uuids = (0 until how.count).map { i ->
            val angle = 2 * PI * i / how.count
            val at = player.at.offset(cos(angle) * distance, how.height.coerceAtLeast(0).toDouble(), sin(angle) * distance)
            val creature = world.addCreature(type, at, how.name?.let { ModelText.cut(it, CREATURE_NAME) }, own = true)
            creature.vanishesAt = world.now + how.lifetimeMinutes.coerceIn(1, MAX_LIFETIME_MINUTES) * SimDeployment.MINUTE_MS
            deployment.own += creature.uuid
            creature.uuid
        }
        return Outcome(ToolStatus.OK.id, SimDeployment.OK.text, uuids)
    }

    // ── Creatures ──────────────────────────────────────────────────────────────────────────────────

    private inner class SimCreatures : Creatures {
        override fun own(): List<Creature> = frozen(live().own.filter { it in world.creatures }.map { SimCreatureHandle(it) })

        override fun get(uuid: String?): Creature? = uuid?.takeIf { it in live().own && it in world.creatures }?.let { SimCreatureHandle(it) }

        override fun claim(uuid: String?): Creature? {
            val deployment = live()
            val creature = uuid?.let { world.creatures[it] } ?: return null
            val nearPlayer = world.online().any { it.at.distance(creature.at) <= Limits.MAX_CLAIM_DISTANCE }
            if (creature.own || creature.summoned || !nearPlayer || deployment.own.size >= Limits.MAX_OWN_CREATURES) return null
            creature.own = true
            creature.claimedAt = creature.at
            creature.nameBefore = creature.name
            deployment.own += creature.uuid
            return SimCreatureHandle(creature.uuid)
        }
    }

    /** One of the deployment's creatures, by its UUID (M5 §4.5): steered at once, without physics. */
    private inner class SimCreatureHandle(private val uuid: String) : Creature {
        override fun uuid(): String = uuid

        override fun info(): CreatureInfo? = live().let { mine()?.info() }

        override fun alive(): Boolean = live().let { mine() != null }

        override fun moveTo(to: Place?, speed: Double) = steer { creature ->
            if (to == null) throw ForgeRefusal("moveTo needs a place")
            if (!(speed >= ForgeLimits.SPEED_MIN && speed <= ForgeLimits.SPEED_MAX)) {
                throw ForgeRefusal("a creature walks at a speed of ${ForgeLimits.SPEED_MIN} to ${ForgeLimits.SPEED_MAX} (asked ${ToolText.number(speed)})")
            }
            if (to.dimension != creature.at.dimension) throw ForgeRefusal("moveTo stays in the creature's dimension (${creature.at.dimension})")
            creature.walkTo = to
            creature.speed = speed
            creature.vx = 0.0
            creature.vy = 0.0
            creature.vz = 0.0
        }

        override fun velocity(x: Double, y: Double, z: Double) = steer { creature ->
            motion("velocity", x, y, z)
            creature.walkTo = null
            creature.vx = x
            creature.vy = y
            creature.vz = z
        }

        override fun push(x: Double, y: Double, z: Double) = steer { creature ->
            motion("push", x, y, z)
            creature.walkTo = null
            creature.vx = (creature.vx + x).coerceIn(-ForgeLimits.VELOCITY_MAX, ForgeLimits.VELOCITY_MAX)
            creature.vy = (creature.vy + y).coerceIn(-ForgeLimits.VELOCITY_MAX, ForgeLimits.VELOCITY_MAX)
            creature.vz = (creature.vz + z).coerceIn(-ForgeLimits.VELOCITY_MAX, ForgeLimits.VELOCITY_MAX)
        }

        override fun lookAt(at: Place?) = steer { if (at == null) throw ForgeRefusal("lookAt needs a place") }

        override fun gravity(on: Boolean) = steer { it.gravity = on }

        override fun glow(on: Boolean) = steer { it.glow = on }

        override fun name(name: String?) = steer { creature ->
            if (name != null && name.codePointCount(0, name.length) > ForgeLimits.CREATURE_NAME_CHARS) {
                throw ForgeRefusal("a creature's name is ${ForgeLimits.CREATURE_NAME_CHARS} characters at most")
            }
            creature.name = name
        }

        override fun effect(effect: String?, seconds: Int, level: Int) = steer { creature ->
            val id = id(need(effect, "effect", "an effect id"))
            if (seconds !in 1..MAX_EFFECT_SECONDS || level !in 1..MAX_EFFECT_LEVEL) {
                throw ForgeRefusal("a creature's effect lasts 1 to $MAX_EFFECT_SECONDS seconds, level 1 to $MAX_EFFECT_LEVEL (asked ${seconds}s, level $level)")
            }
            val args = buildJsonObject {
                put("effect", id)
                put("seconds", seconds)
                put("level", level)
            }
            live().keep(DryCall("mob_effect", args, "creature ${creature.type} effect $id ${seconds}s level $level"))
        }

        override fun attribute(attribute: String?, multiply: Double) = steer { creature ->
            val id = id(need(attribute, "attribute", "an attribute id"))
            val key = MobAttributeKey.entries.firstOrNull { it.id == id }
                ?: throw ForgeRefusal("a creature's attribute is one of ${MobAttributeKey.entries.joinToString { it.id }} (asked $id)")
            val bounds = MobAttributeTool.bounds(key)
            if (multiply !in bounds) {
                throw ForgeRefusal(
                    "$id multiplies by ${ToolText.number(bounds.start)} to ${ToolText.number(bounds.endInclusive)} (asked ${ToolText.number(multiply)})",
                )
            }
            val args = buildJsonObject {
                put("attribute", id)
                put("multiply", multiply)
            }
            live().keep(DryCall("mob_attribute", args, "creature ${creature.type} attribute $id x${ToolText.number(multiply)}"))
        }

        override fun attack(player: String?): Pending {
            val name = need(player, "attack", "a player")
            val type = mine(strict = true)?.type ?: world.creatures[uuid]?.type ?: "creature"
            return act("creature_attack", "attack $name with $type", null, player = name) {
                val creature = world.creatures[uuid]?.takeIf { it.own }
                if (creature != null) creature.target = name
                if (creature == null) SimDeployment.invalid("the creature $uuid is gone") else null
            }
        }

        override fun calm() = steer { it.target = null }

        override fun release() {
            val deployment = live()
            if (deployment.isReleased(uuid)) notOwn()
            if (uuid in deployment.own) deployment.release(uuid)
        }

        /** The creature while it is the deployment's own and there; null when it is gone. [strict]: throws when it is not its own any more. */
        private fun mine(strict: Boolean = false): SimCreature? {
            val deployment = live()
            if (strict && deployment.isReleased(uuid)) notOwn()
            return world.creatures[uuid]?.takeIf { it.own && uuid in deployment.own }
        }

        /** Runs [action] on the creature: nothing when it is gone; [ForgeRefusal] when it is not its own any more. */
        private fun steer(action: (SimCreature) -> Unit) {
            val creature = mine(strict = true) ?: return
            action(creature)
        }

        private fun notOwn(): Nothing = throw ForgeRefusal("the creature $uuid is not its own any more")

        private fun motion(what: String, x: Double, y: Double, z: Double) {
            val max = ForgeLimits.VELOCITY_MAX
            if (listOf(x, y, z).any { !(it >= -max && it <= max) }) {
                throw ForgeRefusal("$what takes each component within -${ToolText.number(max)} and ${ToolText.number(max)} blocks a tick")
            }
        }
    }

    // ── Ground, storage, game ──────────────────────────────────────────────────────────────────────

    private inner class SimGround : Ground {
        override fun place(cells: List<Cell>?, block: String?, seconds: Int): Pending {
            val copied: List<Cell> = need(cells, "place", "cells").map { it }
            val id = id(need(block, "place", "a block id"))
            return act("place_blocks", "place $id x${copied.size} for ${seconds}s", null) {
                val taken = copied.firstOrNull { !SimWorld.isAir(world.block(it)) }
                when {
                    copied.isEmpty() || copied.size > Limits.MAX_PLACE_CELLS -> SimDeployment.invalid("place takes 1 to ${Limits.MAX_PLACE_CELLS} cells (asked ${copied.size})")
                    seconds !in 1..Limits.MAX_PLACE_SECONDS -> SimDeployment.invalid("place lasts 1 to ${Limits.MAX_PLACE_SECONDS} seconds (asked $seconds)")
                    !(id == "glass" || id.endsWith("_stained_glass") || id in PLACEABLE) ->
                        SimDeployment.invalid("place puts glass, a stained glass (red_stained_glass…), cobweb, slime_block or honey_block (asked $id)")
                    taken != null -> SimDeployment.invalid("${taken.x} ${taken.y} ${taken.z} is not air (${world.block(taken)}): blocks go in thin air only")
                    else -> null
                }
            }
        }
    }

    private inner class SimStorage : Storage {
        override fun get(key: String?): String? = live().storage[key]

        override fun put(key: String?, value: String?) {
            val storage = live().storage
            if (key == null) throw ForgeRefusal("a storage key is not null")
            if (value == null) {
                storage.remove(key)
                return
            }
            if (key.length > Limits.MAX_STORAGE_KEY_CHARS) throw ForgeRefusal("a storage key is ${Limits.MAX_STORAGE_KEY_CHARS} characters at most: $key")
            if (value.length > Limits.MAX_STORAGE_VALUE_CHARS) throw ForgeRefusal("a storage value is ${Limits.MAX_STORAGE_VALUE_CHARS} characters at most (${key}: ${value.length})")
            if (key !in storage && storage.size >= Limits.MAX_STORAGE_KEYS) throw ForgeRefusal("the storage holds ${Limits.MAX_STORAGE_KEYS} keys at most")
            val total = storage.entries.sumOf { (k, v) -> if (k == key) 0 else k.length + v.length } + key.length + value.length
            if (total > Limits.MAX_STORAGE_CHARS) throw ForgeRefusal("the storage holds ${Limits.MAX_STORAGE_CHARS} characters at most, keys and values (this would be $total)")
            storage[key] = value
        }

        override fun remove(key: String?) {
            live().storage.remove(key)
        }

        override fun keys(): Set<String> = Collections.unmodifiableSet(LinkedHashSet(live().storage.keys))

        override fun getInt(key: String?, fallback: Int): Int = get(key)?.toIntOrNull() ?: fallback

        override fun putInt(key: String?, value: Int) = put(key, value.toString())

        override fun getDouble(key: String?, fallback: Double): Double = get(key)?.toDoubleOrNull() ?: fallback

        override fun putDouble(key: String?, value: Double) = put(key, value.toString())
    }

    private inner class SimGameView(private val game: SimGame) : Game {
        override fun id(): String = live().let { game.id }

        override fun players(): List<String> = live().let { game.players }

        override fun running(): Boolean = live().let { game.running }

        override fun win(winners: List<String>?, note: String?): Pending {
            val names: List<String> = need(winners, "win", "the winners").map { it }
            return act("game", "game won: ${names.joinToString()}", null, ActKind.GAME) {
                val stranger = names.firstOrNull { name -> game.players.none { it.equals(name, ignoreCase = true) } }
                val refusal = when {
                    !game.running -> SimDeployment.invalid("${game.id} is over already")
                    names.isEmpty() -> SimDeployment.invalid("win names one winner at least")
                    stranger != null -> SimDeployment.invalid("$stranger is not among ${game.id}'s players: ${game.players.joinToString()}")
                    else -> null
                }
                if (refusal == null) game.running = false
                refusal
            }
        }

        override fun lose(note: String?): Pending = act("game", "game lost", null, ActKind.GAME) {
            val refusal = if (game.running) null else SimDeployment.invalid("${game.id} is over already")
            game.running = false
            refusal
        }
    }

    companion object {
        /** The records of [GameFact], the classes a module subscribes to. */
        private val FACTS: Set<Class<*>> = GameFact::class.java.permittedSubclasses.toSet()

        private const val EVERYONE = "everyone"
        private const val OVERWORLD = "overworld"
        private const val BIOME = "plains"
        private const val WEATHER = "clear"
        private const val NOON = 6_000L
        private const val DAY_TICKS = 24_000L

        private const val MAX_SPAWN = 12
        private const val MIN_DISTANCE = 2
        private const val MAX_DISTANCE = 24
        private const val MAX_LIFETIME_MINUTES = 10
        private const val CREATURE_NAME = 32
        private const val MAX_EFFECT_SECONDS = 900
        private const val MAX_EFFECT_LEVEL = 5
        private val PLACEABLE = setOf("cobweb", "slime_block", "honey_block")

        /** An id as the SDK's lines and the tools write it: without `minecraft:`. */
        fun id(raw: String?): String = raw?.trim()?.removePrefix("minecraft:").orEmpty()

        /** A place in an act's line: `0.5 66 0`, its dimension after it when it is not the overworld. */
        fun shown(at: Place): String =
            "${ToolText.number(at.x)} ${ToolText.number(at.y)} ${ToolText.number(at.z)}" + if (at.dimension == OVERWORLD) "" else " ${at.dimension}"

        private fun block(coordinate: Double): Int = floor(coordinate).toInt()

        private fun JsonObjectBuilder.at(at: Place) {
            put("x", block(at.x))
            put("y", block(at.y))
            put("z", block(at.z))
            put("dimension", at.dimension)
        }

        private fun JsonObjectBuilder.to(player: String?) {
            if (player != null) putJsonArray("to") { add(player) }
        }

        /** [value], or a [ForgeRefusal]: `say needs a text`. */
        private fun <T : Any> need(value: T?, what: String, needs: String): T = value ?: throw ForgeRefusal("$what needs $needs")
    }
}

/**
 * The parameters of a deployment at the bench (M5 §7): [values] read from their text form (`player=Bartok`) and
 * checked as `deploy_module` checks them ([parse]), against the module's `@Param`s.
 */
internal class SimArgs(private val specs: List<ParamSpec>, private val values: Map<String, Any?>) : Args {
    override fun player(name: String?): String? = read(name, ParamType.PLAYER) as String?

    @Suppress("UNCHECKED_CAST")
    override fun players(name: String?): List<String> = read(name, ParamType.PLAYERS) as List<String>? ?: emptyList()

    override fun integer(name: String?): Int = read(name, ParamType.INTEGER) as Int? ?: throw absent(name)

    override fun number(name: String?): Double = (read(name, ParamType.NUMBER, ParamType.INTEGER) as Number?)?.toDouble() ?: throw absent(name)

    override fun text(name: String?): String? = read(name, ParamType.TEXT) as String?

    override fun bool(name: String?): Boolean = read(name, ParamType.BOOLEAN) as Boolean? ?: throw absent(name)

    override fun place(name: String?): Place? = read(name, ParamType.PLACE) as Place?

    private fun read(name: String?, vararg types: ParamType): Any? {
        val spec = specs.firstOrNull { it.name == name } ?: throw ForgeRefusal("the module has no parameter $name (${listed(specs)})")
        if (spec.type !in types) throw ForgeRefusal("params.$name is ${spec.type.name.lowercase()}: read it with ${READERS.getValue(spec.type)}()")
        return values[spec.name]
    }

    private fun absent(name: String?) = ForgeRefusal("params.$name has no value: it was left out and has no fallback")

    companion object {
        private val READERS = mapOf(
            ParamType.PLAYER to "player", ParamType.PLAYERS to "players", ParamType.INTEGER to "integer", ParamType.NUMBER to "number",
            ParamType.TEXT to "text", ParamType.BOOLEAN to "bool", ParamType.PLACE to "place",
        )

        /** Most players a `PLAYERS` parameter takes, and most characters a `TEXT` one. */
        private const val MAX_PLAYERS = 8
        private const val MAX_TEXT = 300

        private fun listed(specs: List<ParamSpec>): String =
            if (specs.isEmpty()) "it has none" else "its parameters: " + specs.joinToString { it.name }

        /**
         * The values of [texts] (`name=value`) for [specs], in the forms of `ParamType`: a parameter left out reads its
         * fallback, or none; players are the bench's [world]'s, a place a point or an online player's name. Throws
         * [ForgeRefusal] as `deploy_module` refuses (`params.chickens must be between 3 and 8 (got 12)`).
         */
        fun parse(specs: List<ParamSpec>, texts: List<String?>, world: SimWorld): Map<String, Any?> {
            val given = LinkedHashMap<String, String>()
            for (text in texts) {
                val pair = text ?: throw ForgeRefusal("params: a parameter is null; write name=value")
                val eq = pair.indexOf('=')
                if (eq <= 0) throw ForgeRefusal("params: \"$pair\" is not name=value")
                val name = pair.substring(0, eq).trim()
                if (specs.none { it.name == name }) throw ForgeRefusal("params.$name is not a parameter of the module (${listed(specs)})")
                if (given.put(name, pair.substring(eq + 1).trim()) != null) throw ForgeRefusal("params.$name is given twice")
            }
            return specs.associate { spec ->
                val text = given[spec.name]
                val fallback = spec.fallback
                spec.name to when {
                    text != null -> value(spec, text, world, "params.${spec.name}")
                    spec.required -> throw ForgeRefusal("params.${spec.name} is required: ${spec.type.name.lowercase()}, ${spec.doc}")
                    fallback != null -> value(spec, fallback, world, "the fallback of params.${spec.name}")
                    spec.type == ParamType.PLAYERS -> emptyList<String>()
                    else -> null
                }
            }
        }

        private fun value(spec: ParamSpec, text: String, world: SimWorld, what: String): Any = when (spec.type) {
            ParamType.PLAYER -> known(text, world, what)
            ParamType.PLAYERS -> players(spec, text, world, what)
            ParamType.INTEGER -> (text.toIntOrNull() ?: throw ForgeRefusal("$what must be a whole number (got \"$text\")")).also { bounded(spec, it.toDouble(), what) }
            ParamType.NUMBER -> (text.toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw ForgeRefusal("$what must be a number (got \"$text\")"))
                .also { bounded(spec, it, what) }
            ParamType.TEXT -> text(spec, text, what)
            ParamType.BOOLEAN -> when (text.lowercase(Locale.ROOT)) {
                "true" -> true
                "false" -> false
                else -> throw ForgeRefusal("$what must be true or false (got \"$text\")")
            }
            ParamType.PLACE -> world.online(text)?.at ?: point(text, what)
        }

        private fun known(name: String, world: SimWorld, what: String): String =
            world.player(name)?.name ?: throw ForgeRefusal("$what: $name is not a player of the bench (put them there with player(…) first)")

        private fun players(spec: ParamSpec, text: String, world: SimWorld, what: String): List<String> {
            val names = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { known(it, world, what) }
            val twice = names.groupBy { it }.filterValues { it.size > 1 }.keys
            if (twice.isNotEmpty()) throw ForgeRefusal("$what names ${twice.first()} twice")
            val least = spec.min?.toInt() ?: 1
            val most = minOf(spec.max?.toInt() ?: MAX_PLAYERS, MAX_PLAYERS)
            if (names.size !in least..most) throw ForgeRefusal("$what needs $least to $most players (got ${names.size})")
            return names
        }

        private fun text(spec: ParamSpec, text: String, what: String): String {
            val length = text.codePointCount(0, text.length)
            val least = spec.min?.toInt() ?: 0
            val most = minOf(spec.max?.toInt() ?: MAX_TEXT, MAX_TEXT)
            if (length !in least..most) throw ForgeRefusal("$what must have $least to $most characters (got $length)")
            if (spec.choices.isNotEmpty() && text !in spec.choices) throw ForgeRefusal("$what must be one of ${spec.choices.joinToString()} (got \"$text\")")
            return text
        }

        private fun bounded(spec: ParamSpec, value: Double, what: String) {
            val min = spec.min
            val max = spec.max
            val got = ToolText.number(value)
            when {
                min != null && max != null && value !in min..max -> throw ForgeRefusal("$what must be between ${ToolText.number(min)} and ${ToolText.number(max)} (got $got)")
                min != null && value < min -> throw ForgeRefusal("$what must be at least ${ToolText.number(min)} (got $got)")
                max != null && value > max -> throw ForgeRefusal("$what must be at most ${ToolText.number(max)} (got $got)")
            }
        }

        private fun point(text: String, what: String): Place {
            val parts = text.split(' ', '\t').filter { it.isNotEmpty() }
            val numbers = parts.take(3).map { it.toDoubleOrNull() }
            if (parts.size !in 3..4 || numbers.any { it == null || !it.isFinite() }) {
                throw ForgeRefusal("$what must be a place: 120 64 -30, 120 64 -30 the_nether, or an online player's name (got \"$text\")")
            }
            return Place(parts.getOrElse(3) { "overworld" }, numbers[0]!!, numbers[1]!!, numbers[2]!!)
        }
    }
}
