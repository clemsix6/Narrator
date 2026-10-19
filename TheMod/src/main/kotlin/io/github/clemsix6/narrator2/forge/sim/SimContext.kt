package io.github.clemsix6.narrator2.forge.sim

import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.event.PlayerRef
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
import io.github.clemsix6.narrator2.forge.api.Pending
import io.github.clemsix6.narrator2.forge.api.Place
import io.github.clemsix6.narrator2.forge.api.PlayerInfo
import io.github.clemsix6.narrator2.forge.api.Scheduled
import io.github.clemsix6.narrator2.forge.api.Storage
import io.github.clemsix6.narrator2.forge.api.Subscription
import io.github.clemsix6.narrator2.forge.api.Timers
import io.github.clemsix6.narrator2.forge.api.Who
import io.github.clemsix6.narrator2.forge.api.World
import io.github.clemsix6.narrator2.forge.sdk.Act
import io.github.clemsix6.narrator2.forge.sdk.ActQueue
import io.github.clemsix6.narrator2.forge.sdk.ArgsView
import io.github.clemsix6.narrator2.forge.sdk.FactQueue
import io.github.clemsix6.narrator2.forge.sdk.ModuleCall
import io.github.clemsix6.narrator2.forge.sdk.ModuleCreatures
import io.github.clemsix6.narrator2.forge.sdk.ParamCheck
import io.github.clemsix6.narrator2.forge.sdk.ParamLookup
import io.github.clemsix6.narrator2.forge.sdk.Scheduler
import io.github.clemsix6.narrator2.forge.sdk.StorageMap
import io.github.clemsix6.narrator2.guard.SpawnPolicy
import io.github.clemsix6.narrator2.mechanics.MechanicRejected
import io.github.clemsix6.narrator2.mechanics.rule.MobAttributeTool
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.tool.action.MobAttributeKey
import io.github.clemsix6.narrator2.tool.action.parseId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Random
import java.util.UUID
import java.util.concurrent.Executor
import java.util.function.Consumer
import kotlin.coroutines.CoroutineContext

/** Where a deployment of the bench stands. */
internal enum class SimState { RUNNING, STOPPING, ENDED }

/** [items] as a list nobody can change: what the SDK returns (a Kotlin list is a `java.util.ArrayList` to a module). */
internal fun <T> frozen(items: Collection<T>): List<T> = java.util.List.copyOf(items)

/**
 * The coroutines of a deployment's act queue at the bench: queued, and run when the bench says ([runAll]), at the
 * start of each tick. So the acts of a call go to the bench's tools after the call, as in the game; and those of a call
 * that ended its deployment come back `dropped`, as the game's queue drops what it had not sent yet (M5-07 handoff, 4).
 */
internal class BenchDispatcher : CoroutineDispatcher() {
    private val tasks = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        synchronized(tasks) { tasks.addLast(block) }
    }

    fun runAll() {
        while (true) (synchronized(tasks) { tasks.removeFirstOrNull() } ?: return).run()
    }
}

/** The bench's clock as a [Clock]: the time of the [world], for the act queue's minute. */
private class BenchClock(private val world: SimWorld) : Clock() {
    override fun millis(): Long = world.now

    override fun instant(): Instant = Instant.ofEpochMilli(world.now)

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this
}

/**
 * A deployment of a module at the bench (M5 §7). Its SDK is the game's own wherever the game's needs no server
 * (M5-06, M5-07): the parameters ([ArgsView], checked by [ParamCheck]), the facts ([FactQueue]), the timers
 * ([Scheduler]), the storage ([StorageMap]) and the act queue ([ActQueue]: limits, refusals, callbacks, drops), whose
 * acts go to the bench's tools ([BenchActRunner]) instead of the Narrator's. The world, the creatures and the acts'
 * translation are the bench's ([SimContext], `SimActs.kt`). A restart keeps the deployment (storage, creatures,
 * records) and gives the next instance a new SDK.
 *
 * [call] runs one call of the module through the bench's host (`BenchImpl`): every call of the queue goes through it.
 * [lineOf]: the module's line in a stack, for the dry checks' problems. [refusals]: what `Bench.refuseNext` queued.
 */
internal class SimDeployment(
    val world: SimWorld,
    val specs: List<ParamSpec>,
    private val params: JsonObject,
    val endsAt: Long,
    val game: SimGame?,
    val refusals: Map<String, ArrayDeque<String>>,
    private val call: ModuleCall,
    private val lineOf: (Throwable) -> Int,
) {
    val id = "C-$NUMBER"
    val ref = Ref(RefKind.CANON, NUMBER)

    var state = SimState.RUNNING
        private set
    var generation = 0
        private set
    var instance: ForgeModule? = null
    var context = SimContext(this, generation, restarted = false)
        private set

    /** What `ctx.end` asked in the module's call, applied once the call returned. */
    var endWhy: String? = null

    val dispatcher = BenchDispatcher()
    private val clock: Clock = BenchClock(world)
    private val runner = BenchActRunner(this)
    val random = Random(SimWorld.SEED)

    var args = ArgsView(specs, params)
        private set
    var events = FactQueue()
        private set
    var timers = Scheduler()
        private set
    var storage = StorageMap()
        private set
    var queue = newQueue()
        private set

    /** Its creatures' UUIDs, in the order they became its own; those of its summonings on their way; those it released. */
    val own = LinkedHashSet<String>()
    var reserved = 0
    private val released = HashSet<String>()

    /** What the module handed to the SDK and that the SDK still holds, with whether it still does: the memory gauge's roots. */
    private val handed = ArrayList<Pair<Any, () -> Boolean>>()

    // What the bench shows.
    val acts = ArrayList<ActRecord>()
    val notes = ArrayList<String>()
    val dry = LinkedHashMap<String, DryCall>()
    val counts = LinkedHashMap<String, Int>()
    var ended: String? = null
    var factsDelivered = 0
    var logsDropped = 0
    var dryDropped = 0
    private val logTimes = ArrayDeque<Long>()
    private var toldAt: Long? = null
    private var held: String? = null
    private var heldDropped = 0

    private fun newQueue() = ActQueue(ref, runner, CoroutineScope(dispatcher), clock, call, Executor { it.run() })

    /** A new context for a new instance of the module: the earlier one stops answering. */
    fun newContext(restarted: Boolean): SimContext {
        context = SimContext(this, generation, restarted)
        return context
    }

    /** The module's `stop` is about to run: its acts are dropped, no new subscription nor timer. */
    fun stopping() {
        state = SimState.STOPPING
        queue.stopping()
        events.stopping()
        timers.stopping()
    }

    /** After `stop(SERVER_STOPPING)`: a new SDK for the next instance, the storage kept, nothing else of the earlier one. */
    fun restart() {
        close()
        generation++
        state = SimState.RUNNING
        instance = null
        endWhy = null
        handed.clear()
        args = ArgsView(specs, params)
        events = FactQueue()
        timers = Scheduler()
        storage = StorageMap(storage.snapshot())
        queue = newQueue()
    }

    /** The deployment is over ([why] it ended; null when the bench stopped it at a failure): its SDK closed, its creatures taken back. */
    fun finish(why: String?) {
        if (state == SimState.ENDED) return
        state = SimState.ENDED
        ended = why
        close()
        own.toList().forEach { release(it) }
    }

    /** Every piece of the SDK closed (their objects refuse from now on); what the queue still held comes back dropped. */
    private fun close() {
        queue.close()
        events.close()
        timers.close()
        storage.close()
        args.close()
        dispatcher.runAll()
    }

    // ── What the module hands over ─────────────────────────────────────────────────────────────────

    /** [item], one of the module's objects that the SDK keeps while [kept] says so. */
    fun hand(item: Any, kept: () -> Boolean) {
        if (handed.size >= MAX_HANDED) handed.removeIf { !it.second() }
        handed += item to kept
    }

    /** The objects the SDK still holds for the module: the memory gauge's roots, with its instance. */
    fun handed(): List<Any> {
        handed.removeIf { !it.second() }
        return handed.map { it.first }
    }

    // ── Acts ───────────────────────────────────────────────────────────────────────────────────────

    /**
     * An act asked at the bench: its line, its count, and (with the [args] of a tool) its call for the dry checks,
     * [known] when the SDK refused it itself for good (a forbidden effect, a place in another dimension).
     */
    fun record(tool: String, line: String, args: JsonObject?, known: ToolResult?): ActRecord {
        counts.merge(tool, 1, Int::plus)
        val record = ActRecord(line)
        acts += record
        args?.let { keep(DryCall(tool, it, line, known)) }
        return record
    }

    /** Keeps [call] for the dry checks, once per tool and arguments, [MAX_DRY_CALLS] at most, with the module's line. */
    fun keep(call: DryCall) {
        if (call.key in dry) return
        if (dry.size >= MAX_DRY_CALLS) {
            dryDropped++
            return
        }
        call.moduleLine = lineOf(Throwable())
        dry[call.key] = call
    }

    /** The bench's answer to an act sent to [tool] with [args]: what a scenario imposed, else the bench's tools ([BenchActRunner]). */
    fun imposed(tool: String): ToolResult? = refusals[tool]?.removeFirstOrNull()?.let { ToolResult(ActRecord.statusOf(it), it) }

    // ── Time and notes ─────────────────────────────────────────────────────────────────────────────

    /** At each tick: its summoned creatures whose lifetime ran out vanish (a `CreatureGone` for the next tick), a held note is told. */
    fun timePasses() {
        val now = world.now
        val vanished = own.mapNotNull { world.creatures[it] }.filter { creature -> creature.vanishesAt?.let { it <= now } == true }
        for (creature in vanished) {
            world.creatures.remove(creature.uuid)
            own.remove(creature.uuid)
            events.offer(GameFact.CreatureGone(null, creature.at, Who(creature.type, creature.uuid, creature.name), "vanished"))
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

    fun log(line: String) {
        val now = world.now
        while (logTimes.isNotEmpty() && logTimes.first() <= now - MINUTE_MS) logTimes.removeFirst()
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

    companion object {
        /** The deployment's canon row at the bench: `C-1`. */
        const val NUMBER = 1L

        /** Distinct calls kept for the dry checks, per deployment. */
        const val MAX_DRY_CALLS = 200

        /** Objects handed over that are kept before the ones the SDK let go are forgotten. */
        private const val MAX_HANDED = 256

        const val MINUTE_MS = 60_000L
    }
}

/**
 * The [ModuleContext] of a module at the bench (M5 §7). One per instance of the module: after a restart or the end,
 * every method throws [ForgeRefusal] (and so do the SDK's objects it gave, closed). The objects the module hands over
 * (handlers, tasks, callbacks, lists) are only called or read inside the module's own calls.
 */
internal class SimContext(private val deployment: SimDeployment, private val generation: Int, private val restarted: Boolean) : ModuleContext {
    private val world = deployment.world
    private val worldView = SimWorldView()
    private val creatures = SimCreatures()
    private val events = BenchEvents()
    private val timers = BenchTimers()
    private val acts = BenchActs(this)
    private val ground = BenchGround(this)
    private val game = deployment.game?.let { BenchGame(this, it) }

    /** The deployment, while this context answers. */
    fun live(): SimDeployment {
        if (deployment.generation != generation || deployment.state == SimState.ENDED) {
            throw ForgeRefusal("the deployment ${deployment.id} has ended: its SDK objects no longer work")
        }
        return deployment
    }

    override fun id(): String = live().id

    override fun restarted(): Boolean = live().let { restarted }

    override fun now(): Long = live().world.now

    override fun endsAt(): Long = live().endsAt

    override fun random(): Random = live().random

    override fun args(): Args = live().args

    override fun events(): Events = live().let { events }

    override fun timers(): Timers = live().let { timers }

    override fun world(): World = live().let { worldView }

    override fun acts(): Acts = live().let { acts }

    override fun creatures(): Creatures = live().let { creatures }

    override fun ground(): Ground = live().let { ground }

    override fun storage(): Storage = live().storage

    override fun game(): Game? = live().let { game }

    override fun log(line: String?) {
        live().log(ModelText.cut(line.orEmpty(), Limits.MAX_NOTE_CHARS))
    }

    override fun tellDirector(note: String?) {
        live().tell(ModelText.cut(note.orEmpty(), Limits.MAX_NOTE_CHARS))
    }

    override fun end(why: String?) {
        val deployment = live()
        if (deployment.state == SimState.RUNNING && deployment.endWhy == null) deployment.endWhy = ModelText.cut(why.orEmpty(), Limits.MAX_NOTE_CHARS)
    }

    /**
     * Asks an act through the deployment's queue, as the game's SDK does (M5-07: [Act], its kind and the SDK's own
     * [refusal]), and records it as the bench shows it: [line]; [args] go to the dry checks with [tool] (a judged
     * challenge's end, [tool] null, has none). [settle] sees its result first (a summoning's creatures).
     */
    fun ask(
        line: String,
        tool: String?,
        args: JsonObject,
        player: String?,
        kind: Act.Kind = Act.Kind.ACT,
        refusal: ToolResult? = null,
        run: (suspend () -> ToolResult)? = null,
        settle: ((ToolResult) -> Unit)? = null,
    ): Pending {
        val deployment = live()
        val record = deployment.record(tool ?: ActRecord.GAME, line, args.takeIf { tool != null }, refusal?.takeIf { ActRecord.lasting(it) })
        val act = Act(tool, args, player, kind, refusal, run) { result ->
            record.settled(result)
            settle?.invoke(result)
        }
        return BenchPending(deployment.queue.ask(act))
    }

    /** A [Pending] whose callback the deployment counts among what the module handed over, until it is called. */
    private inner class BenchPending(private val pending: Pending) : Pending {
        override fun then(callback: Consumer<Outcome>?) {
            if (callback == null) return pending.then(null)
            var called = false
            pending.then { outcome ->
                called = true
                callback.accept(outcome)
            }
            deployment.hand(callback) { !called && deployment.generation == generation }
        }
    }

    // ── Events and timers: the game's, the objects they keep counted for the gauge ─────────────────

    private inner class BenchEvents : Events {
        override fun <F : GameFact> on(type: Class<F>?, handler: Consumer<F>?): Subscription {
            val deployment = live()
            val subscription = deployment.events.on(type, handler)
            handler?.let { deployment.hand(it, subscription::active) }
            return subscription
        }
    }

    private inner class BenchTimers : Timers {
        override fun after(ticks: Int, task: Runnable?): Scheduled = kept(live().timers.after(ticks, task), task)

        override fun every(ticks: Int, task: Runnable?): Scheduled = kept(live().timers.every(ticks, task), task)

        private fun kept(timer: Scheduled, task: Runnable?): Scheduled {
            task?.let { deployment.hand(it, timer::active) }
            return timer
        }
    }

    // ── World ──────────────────────────────────────────────────────────────────────────────────────

    private inner class SimWorldView : World {
        override fun players(): List<PlayerInfo> = frozen(live().world.online().map { it.info() })

        override fun player(name: String?): PlayerInfo? = live().world.online(name)?.info()

        override fun creatures(around: Place?, radius: Double, type: String?): List<CreatureInfo> {
            val deployment = live()
            if (around == null) throw ForgeRefusal("World.creatures needs a place to look around")
            if (!(radius >= 0.0 && radius <= Limits.MAX_CREATURES_RADIUS)) {
                throw ForgeRefusal("World.creatures looks ${Limits.MAX_CREATURES_RADIUS} blocks around at most (asked ${ToolText.number(radius)})")
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

        override fun block(cell: Cell?): BlockInfo = live().world.blockInfo(cell ?: throw ForgeRefusal("World.block needs a cell"))

        override fun surface(dimension: String?, x: Int, z: Int): Int {
            if (dimension.isNullOrBlank()) throw ForgeRefusal("World.surface needs a dimension: overworld, the_nether or the_end")
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

    // ── Creatures ──────────────────────────────────────────────────────────────────────────────────

    private inner class SimCreatures : Creatures {
        override fun own(): List<Creature> = frozen(live().own.filter { it in world.creatures }.map { SimCreatureHandle(it) })

        override fun get(uuid: String?): Creature? = uuid?.takeIf { it in live().own && it in world.creatures }?.let { SimCreatureHandle(it) }

        override fun claim(uuid: String?): Creature? {
            val deployment = live()
            val creature = uuid?.let { world.creatures[it] } ?: return null
            val nearPlayer = world.online().any { it.at.distance(creature.at) <= Limits.MAX_CLAIM_DISTANCE }
            val full = deployment.own.size + deployment.reserved >= Limits.MAX_OWN_CREATURES
            if (creature.own || creature.summoned || !nearPlayer || full || forbidden(creature.type) != null) return null
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
            if (to.dimension() != creature.at.dimension()) throw ForgeRefusal("moveTo stays in the creature's dimension (${creature.at.dimension()})")
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
            val id = id(effect ?: throw ForgeRefusal("effect needs an effect id"))
            if (seconds !in 1..MAX_EFFECT_SECONDS || level !in 1..MAX_EFFECT_LEVEL) {
                throw ForgeRefusal("a creature's effect lasts 1 to $MAX_EFFECT_SECONDS seconds, level 1 to $MAX_EFFECT_LEVEL (asked ${seconds}s, level $level)")
            }
            val args = buildJsonObject {
                put("effect", id)
                put("seconds", seconds)
                put("level", level)
            }
            live().keep(DryCall("mob_effect", args, "creature ${creature.type} effect $id ${seconds}s level $level", null))
        }

        override fun attribute(attribute: String?, multiply: Double) = steer { creature ->
            val id = id(attribute ?: throw ForgeRefusal("attribute needs an attribute id"))
            val key = MobAttributeKey.entries.firstOrNull { it.id == id }
                ?: throw ForgeRefusal("a creature's attribute is one of ${MobAttributeKey.entries.joinToString { it.id }} (asked $id)")
            val bounds = MobAttributeTool.bounds(key)
            if (multiply !in bounds) {
                throw ForgeRefusal(
                    "$id multiplies by ${ToolText.number(bounds.start)} to ${ToolText.number(bounds.endInclusive)} on a module's creature (asked ${ToolText.number(multiply)})",
                )
            }
            val args = buildJsonObject {
                put("attribute", id)
                put("multiply", multiply)
            }
            live().keep(DryCall("mob_attribute", args, "creature ${creature.type} attribute $id x${ToolText.number(multiply)}", null))
        }

        override fun attack(player: String?): Pending {
            val creature = mine(strict = true)
            val type = creature?.type ?: world.creatures[uuid]?.type ?: "creature"
            val args = buildJsonObject {
                put("creature", uuid)
                put("player", player)
            }
            val gone = if (creature == null) ToolResult.failed("$type $uuid is gone") else null
            return ask("attack ${player ?: "nobody"} with $type", "creature_attack", args, player, refusal = gone)
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

    companion object {
        private const val BIOME = "plains"
        private const val WEATHER = "clear"
        private const val NOON = 6_000L
        private const val DAY_TICKS = 24_000L
        private const val MAX_EFFECT_SECONDS = 900
        private const val MAX_EFFECT_LEVEL = 5

        /** An id as the SDK's lines and the bench's world write it: without `minecraft:`. */
        fun id(raw: String?): String = raw?.trim()?.removePrefix("minecraft:").orEmpty()

        /** Why a module never summons nor claims a creature of [type] (a boss, one that explodes), as in the game; null otherwise. */
        fun forbidden(type: String?): String? {
            val full = type?.let { parseId(it) }?.toString() ?: return null
            return ModuleCreatures.FORBIDDEN[full] ?: SpawnPolicy.FORBIDDEN[full]
        }
    }
}

/** The judged challenge a deployment of the bench referees (`Bench.challenge`). */
internal class SimGame(val id: String, val players: List<String>) {
    @Volatile
    var running = true
}

/**
 * What `deploy_module` checks of the parameters (M5-06's [ParamCheck]), against the bench's world: its players, where
 * they stand, the three dimensions of the game.
 */
internal class BenchParamLookup(private val world: SimWorld) : ParamLookup {
    override val dimensions: Set<String> = setOf("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end")

    override suspend fun players(names: List<String>, field: String): List<PlayerRef> = names.map { name ->
        val player = world.player(name) ?: throw MechanicRejected(null, "$field: $name is not a player of the bench; put them there with player(…) first")
        PlayerRef(UUID.fromString(player.uuid), player.name)
    }

    override suspend fun standing(name: String): Place? = world.online(name)?.at

    companion object {
        /**
         * [texts] (`name=value`, `Bench.start`) checked against [specs] as `deploy_module` checks them, resolved:
         * `ModuleRun.params`' form. A refusal is a [ForgeRefusal] with the game's text.
         */
        fun check(specs: List<ParamSpec>, texts: List<String?>, world: SimWorld): JsonObject {
            val given = LinkedHashMap<String, JsonPrimitive>()
            for (text in texts) {
                val pair = text ?: throw ForgeRefusal("start: a parameter is null; write name=value")
                val eq = pair.indexOf('=')
                if (eq <= 0) throw ForgeRefusal("start: \"$pair\" is not name=value")
                val name = pair.substring(0, eq).trim()
                if (given.put(name, JsonPrimitive(pair.substring(eq + 1).trim())) != null) throw ForgeRefusal("start: params.$name is given twice")
            }
            // The lookup never suspends: nothing waits here.
            return try {
                runBlocking { ParamCheck.check(specs, JsonObject(given), BenchParamLookup(world)) }.params
            } catch (e: MechanicRejected) {
                throw ForgeRefusal(e.message.orEmpty())
            }
        }
    }
}
