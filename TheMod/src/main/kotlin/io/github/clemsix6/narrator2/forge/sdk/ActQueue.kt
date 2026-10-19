package io.github.clemsix6.narrator2.forge.sdk

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.forge.api.Acts
import io.github.clemsix6.narrator2.forge.api.ForgeRefusal
import io.github.clemsix6.narrator2.forge.api.Game
import io.github.clemsix6.narrator2.forge.api.Outcome
import io.github.clemsix6.narrator2.forge.api.Pending
import io.github.clemsix6.narrator2.forge.api.Place
import io.github.clemsix6.narrator2.forge.api.Spawn
import io.github.clemsix6.narrator2.mechanics.ChallengeControl
import io.github.clemsix6.narrator2.mechanics.ChallengeOutcome
import io.github.clemsix6.narrator2.mechanics.ChallengeStatus
import io.github.clemsix6.narrator2.mechanics.EngineActions
import io.github.clemsix6.narrator2.mechanics.Firing
import io.github.clemsix6.narrator2.mechanics.MechanicRejected
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.tool.action.parseId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.minecraft.server.MinecraftServer
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer
import kotlin.math.floor

/** One call of a module's own code, measured and guarded by its host (M5-05, `ModuleHost`): never throws, a fault is the host's to count. */
fun interface ModuleCall {
    fun run(what: String, body: () -> Unit)
}

/** How a deployment's acts reach the Narrator's tools: [EngineActRunner] in the game, a fake in the tests. */
interface ActRunner {
    /** Opens one act of the engine for [ref] (`EngineActions.open`), about [subject], the first player its calls aim at. Any thread. */
    fun open(ref: Ref, subject: String?, reason: String): Firing

    /** Runs [tool] with [args] in [firing] (`EngineActions.run`): the real tool, its checks, guards and limits. Never on the server thread. */
    suspend fun run(firing: Firing, tool: String, args: JsonObject): ToolResult
}

/** The engine's own acts ([EngineActions], with the tools of the modules: M5-21 gives it `moduleOnlyTools()`). */
class EngineActRunner(private val actions: EngineActions) : ActRunner {
    override fun open(ref: Ref, subject: String?, reason: String): Firing = actions.open(ActQueue.ACT, ref, subject, reason = reason)

    override suspend fun run(firing: Firing, tool: String, args: JsonObject): ToolResult = actions.run(firing, tool, args)
}

/**
 * What a module asks (M5 §4.4): a call of one of the Narrator's tools, [tool] with [args] (or a judged challenge's end,
 * [run], [tool] null), aimed first at [player] (the act's subject), of [kind]. [refusal]: the SDK refused it itself,
 * before anything (a forbidden effect, a place in another dimension): it is never sent. [settle] sees its result on the
 * server thread, done, refused or dropped alike, before the module does (a summoning's creatures become its own).
 */
class Act(
    val tool: String?,
    val args: JsonObject,
    val player: String?,
    val kind: Kind = Kind.ACT,
    val refusal: ToolResult? = null,
    val run: (suspend () -> ToolResult)? = null,
    val settle: ((ToolResult) -> Unit)? = null,
) {
    /** How an act counts against the limits of the deployment ([Limits][io.github.clemsix6.narrator2.forge.api.Limits]). */
    enum class Kind {
        /** Any act: [ForgeLimits.ACTS_PER_CALL] per call, [ForgeLimits.ACTS_PER_MINUTE] a minute. */
        ACT,

        /** `say`, `sayTo`, `title`: an act, and one of the [ForgeLimits.LINES_PER_MINUTE] lines of the minute. */
        LINE,

        /** `hud`: an act of the call, never counted in the minute (the action bar of a game, once a second per player, `hud`'s own limit). */
        HUD,
    }
}

/**
 * The acts of one deployment (M5 §4.4), between its module's calls and the Narrator's tools.
 *
 * - **Calls**: every call of the module's code goes through [invocation] (start, stop, a fact, a timer, a callback),
 *   one at a time, on the server thread. The acts it asks ([ask], from [ModuleActs], [GroundView], [Creature.attack],
 *   [ModuleGame]) are kept, never done inline; once the call has returned they leave together, in order, as **one act of
 *   the engine** ([ActRunner.open] at the first tool, `EngineActions.open("act", C-70, <first player aimed>)`): one
 *   episode, `already_punished`, `fair_share` and categories for the guards, as one firing of a rule (M5 §2, 5).
 * - **Queue**: one coroutine per deployment ([scope]) runs the acts one after the other, never on the server thread.
 * - **Limits** ([ForgeLimits]): beyond [ForgeLimits.ACTS_PER_CALL] acts in a call, [ForgeLimits.ACTS_PER_MINUTE] acts
 *   or [ForgeLimits.LINES_PER_MINUTE] lines a minute, an act is `DENIED (module_acts)` and nothing goes. `hud` is never
 *   counted in the minute: its own limit is a line per player and second (the `hud` tool).
 * - **Outcomes** come back on the server thread ([deliver], each tick): first [Act.settle], then the module's callback
 *   ([Pending.then]), each in a call of its own, [CALLBACKS_PER_TICK] a tick at most; a callback that acts opens its
 *   own act of the engine.
 * - **The end**: [stopping] while the module's `stop` runs (its acts are `dropped` at once), then [close]: what is
 *   still queued is `dropped`, no callback is ever called again, and what an act still running brings back is
 *   settled all the same (the creatures of a summoning go).
 *
 * Every object the module hands over is opaque (M5 §4.1, review finding 8): a callback ([Consumer]) is only called
 * inside [invocation], and never used as a key, compared nor logged.
 */
class ActQueue(
    private val ref: Ref,
    private val runner: ActRunner,
    scope: CoroutineScope,
    private val clock: Clock,
    private val call: ModuleCall,
    private val server: Executor,
) {
    private val log = Log.forge

    private enum class State { OPEN, STOPPING, CLOSED }

    @Volatile
    private var state = State.OPEN

    /** The acts the call running now asked and that will leave (server thread); null between calls. */
    private var batch: MutableList<Asked>? = null

    /** The acts the call running now asked, refused ones included. */
    private var inCall = 0

    /** When each act of the last minute left, and each line ([Act.Kind.LINE]). Server thread. */
    private val sent = ArrayDeque<Long>()
    private val lines = ArrayDeque<Long>()

    /** What the queue's coroutine brought back, for [deliver]. */
    private val done = ConcurrentLinkedQueue<Pair<Asked, ToolResult>>()

    /** Outcomes whose callback waits for its call (server thread). */
    private val ready = ArrayDeque<Asked>()

    private val queue = Channel<List<Asked>>(Channel.UNLIMITED)

    private val askedCount = AtomicInteger()
    private val droppedCount = AtomicInteger()

    /** The acts asked so far, refused ones included (`ModuleView.acts`). Any thread. */
    val asked: Int get() = askedCount.get()

    /** The acts that never went, because the deployment ended first (M5 §3.7: counted at the end). Any thread. */
    val dropped: Int get() = droppedCount.get()

    init {
        scope.launch { for (acts in queue) perform(acts) }
    }

    /**
     * Runs [body], one call of the module's code ([what]: `start`, `fact`, `timer`, `callback`…), through its host
     * ([call]); then the acts it asked leave, together. Server thread.
     */
    fun invocation(what: String, body: () -> Unit) {
        check(batch == null) { "a call of the module of $ref runs already" }
        val acts = mutableListOf<Asked>()
        batch = acts
        inCall = 0
        try {
            call.run(what, body)
        } finally {
            batch = null
        }
        if (acts.isNotEmpty() && state != State.CLOSED) queue.trySend(acts)
    }

    /**
     * [act], asked by the call running now: kept until the call returns, or answered at once when the SDK or a limit
     * refuses it. Server thread, inside [invocation].
     */
    internal fun ask(act: Act): Pending {
        val asked = Asked(act)
        askedCount.incrementAndGet()
        val acts = batch
        val now = clock.millis()
        when {
            state != State.OPEN -> finish(asked, null)
            acts == null -> throw IllegalStateException("$ref asked an act outside one of its calls")
            ++inCall > ForgeLimits.ACTS_PER_CALL -> finish(asked, denied("${ForgeLimits.ACTS_PER_CALL} acts in one call already, the most: act on the next call"))
            act.refusal != null -> finish(asked, act.refusal)
            act.kind != Act.Kind.HUD && full(sent, ForgeLimits.ACTS_PER_MINUTE, now) ->
                finish(asked, denied("${ForgeLimits.ACTS_PER_MINUTE} acts in the last minute already, the most; the next is possible in ${wait(sent)}"))
            act.kind == Act.Kind.LINE && full(lines, ForgeLimits.LINES_PER_MINUTE, now) ->
                finish(asked, denied("${ForgeLimits.LINES_PER_MINUTE} lines (say, sayTo, title) in the last minute already, the most; the next is possible in ${wait(lines)}"))
            else -> {
                if (act.kind != Act.Kind.HUD) sent.addLast(now)
                if (act.kind == Act.Kind.LINE) lines.addLast(now)
                acts += asked
            }
        }
        return asked
    }

    /**
     * Each tick: the outcomes back from the queue are settled ([Act.settle]), then the callbacks waiting are called,
     * each in a call of its own, [CALLBACKS_PER_TICK] at most (the others wait for the next ticks;
     * beyond [WAITING_CALLBACKS], the oldest are forgotten). Nothing once closed. Server thread.
     */
    fun deliver() {
        if (state == State.CLOSED) return
        while (true) {
            val (asked, result) = done.poll() ?: break
            finish(asked, result)
        }
        var called = 0
        while (called < CALLBACKS_PER_TICK && state != State.CLOSED) {
            val asked = ready.removeFirstOrNull() ?: break
            val callback = asked.callback ?: continue
            val outcome = asked.outcome ?: continue
            called++
            invocation("callback") { callback.accept(outcome) }
        }
    }

    /** The module's `stop` runs: from now on, what it asks is `dropped` at once (M5-01 handoff, 15). Server thread. */
    fun stopping() {
        if (state == State.OPEN) state = State.STOPPING
    }

    /**
     * The deployment ended (or the server stops): what is still queued is `dropped` (counted in [dropped]), no callback
     * is called again, and what an act still running brings back is settled when it comes ([Act.settle]: the creatures
     * of a summoning go). Server thread.
     */
    fun close() {
        if (state == State.CLOSED) return
        state = State.CLOSED
        queue.close()
        ready.clear()
        settleLate()
    }

    /** The outcomes that came back since the end: settled, never seen by the module. Server thread. */
    private fun settleLate() {
        while (true) {
            val (asked, result) = done.poll() ?: return
            settle(asked, result)
        }
    }

    /**
     * The acts of one call, in order, as one act of the engine (opened at the first tool). The queue's coroutine: an
     * act that throws answers `FAILED` and the next ones go on.
     */
    private suspend fun perform(acts: List<Asked>) {
        var firing: Firing? = null
        for (asked in acts) {
            val act = asked.act
            val result = try {
                when {
                    state == State.CLOSED -> null
                    act.run != null -> act.run.invoke()
                    else -> {
                        val opened = firing ?: runner.open(ref, acts.firstNotNullOfOrNull { it.act.player }, reasonOf(acts)).also { firing = it }
                        runner.run(opened, act.tool!!, act.args)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("$ref: an act of its module (${act.tool ?: "game"}) failed", e)
                ToolResult.failed(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
            }
            if (result == null) {
                droppedCount.incrementAndGet()
                server.execute { settle(asked, droppedResult()) }
                continue
            }
            done.add(asked to result)
            // Closed meanwhile: the outcome is settled all the same, the module never sees it.
            if (state == State.CLOSED) server.execute(::settleLate)
        }
    }

    /** [asked] answered: [result], or dropped when null. Settled, then its callback waits for the next [deliver]. Server thread. */
    private fun finish(asked: Asked, result: ToolResult?) {
        settle(asked, result ?: droppedResult())
        asked.outcome = if (result == null) droppedOutcome() else outcome(result)
        if (result == null) droppedCount.incrementAndGet()
        if (asked.callback != null) waiting(asked)
    }

    private fun settle(asked: Asked, result: ToolResult) {
        val settle = asked.act.settle ?: return
        try {
            settle(result)
        } catch (e: Exception) {
            log.error("$ref: settling a ${asked.act.tool ?: "game"} act failed", e)
        }
    }

    /** [asked] waits for its callback's call; the oldest is forgotten past [WAITING_CALLBACKS] (a module that asks in a loop). Server thread. */
    private fun waiting(asked: Asked) {
        if (state == State.CLOSED) return
        if (ready.size >= WAITING_CALLBACKS) {
            ready.removeFirst()
            log.debug { "$ref: more than $WAITING_CALLBACKS callbacks waiting, the oldest forgotten" }
        }
        ready.addLast(asked)
    }

    private fun full(window: ArrayDeque<Long>, max: Int, now: Long): Boolean {
        while (window.isNotEmpty() && now - window.first() >= MINUTE_MS) window.removeFirst()
        return window.size >= max
    }

    private fun wait(window: ArrayDeque<Long>): String = ToolText.wait(window.first() + MINUTE_MS - clock.millis())

    private fun reasonOf(acts: List<Asked>): String = "$ref: ${acts.size} acts of its module (${acts.mapNotNull { it.act.tool }.joinToString()})"

    private fun droppedResult(): ToolResult = ToolResult.failed("dropped: $ref ended before this act was done")

    private fun droppedOutcome(): Outcome = Outcome(DROPPED, "dropped: $ref ended before this act was done", emptyList())

    /** An act asked: its [Pending]. Its callback is the module's, opaque: only called inside [invocation]. */
    private inner class Asked(val act: Act) : Pending {
        var callback: Consumer<Outcome>? = null
        var outcome: Outcome? = null

        override fun then(callback: Consumer<Outcome>?) {
            if (callback == null) throw ForgeRefusal("then needs a callback")
            if (this.callback != null) throw ForgeRefusal("one callback per act: then was given one already")
            this.callback = callback
            if (outcome != null) waiting(this)
        }
    }

    companion object {
        /** The decision of an act of a module's call (`mechanic act`, M5 §15). */
        const val ACT = "act"

        /** The rule of every refusal of the SDK's own limits: `DENIED (module_acts): …`. */
        const val RULE = "module_acts"

        /** The status of an act that never went: the deployment ended first. */
        const val DROPPED = "dropped"

        /** Callbacks called in one tick, at most; the others wait for the next ticks. */
        const val CALLBACKS_PER_TICK = ForgeLimits.ACTS_PER_CALL

        /** Callbacks waiting at once, at most: past it, the oldest are forgotten (a module that asks in a loop). */
        const val WAITING_CALLBACKS = 64

        private const val MINUTE_MS = 60_000L

        /** A refusal of the SDK's limits: `DENIED (module_acts): …`. */
        fun denied(message: String): ToolResult = ToolResult.denied(RULE, message)

        /** [result] as the module reads it ([Outcome]): `ok`, `invalid`, `denied`, `failed`, or `unknown` (a timeout). */
        fun outcome(result: ToolResult): Outcome = Outcome(
            when (result.status) {
                ToolStatus.OK -> "ok"
                ToolStatus.INVALID -> "invalid"
                ToolStatus.DENIED -> "denied"
                ToolStatus.FAILED -> "failed"
                ToolStatus.TIMEOUT, ToolStatus.UNKNOWN -> "unknown"
            },
            result.text,
            result.entities.map(UUID::toString),
        )
    }
}

/**
 * The module's [Acts] (M5 §4.4): each method is one call of one of the Narrator's tools, its arguments written as a
 * model would write them, asked to the deployment's [queue]. Only what the SDK must know itself is checked here: the
 * effects a module never gives (as a law, M4-14), a move to another dimension, the deployment's own creatures. The rest
 * is the tool's: its checks, the chaos level's caps, the guards. Nothing here throws for a refused act.
 *
 * [dimensionOf]: the dimension of an online player (`overworld`), or null; the server's by default. Server thread.
 */
class ModuleActs(
    private val queue: ActQueue,
    private val creatures: CreatureControl,
    private val dimensionOf: (String) -> String?,
) : Acts {
    /** The module's acts, the players' dimensions read on [server]. */
    constructor(queue: ActQueue, creatures: CreatureControl, server: MinecraftServer) :
        this(queue, creatures, { name -> server.playerList.getPlayerByName(name)?.let { CreatureControl.placeOf(it).dimension() } })

    override fun say(text: String?): Pending = queue.ask(Act("say", json { put("text", text) }, null, Act.Kind.LINE))

    override fun sayTo(player: String?, text: String?): Pending =
        queue.ask(Act("say", json { put("text", text); to(player) }, player, Act.Kind.LINE))

    override fun title(player: String?, title: String?, subtitle: String?): Pending =
        queue.ask(Act("show_title", json { put("title", title); subtitle?.let { put("subtitle", it) }; player?.let { to(it) } }, player, Act.Kind.LINE))

    override fun hud(player: String?, text: String?): Pending =
        queue.ask(Act("hud", json { put("text", text); player?.let { to(it) } }, player, Act.Kind.HUD))

    override fun sound(player: String?, sound: String?): Pending =
        queue.ask(Act("play_sound", json { put("sound", sound); player?.let { to(it) } }, player))

    override fun particles(at: Place?, particle: String?, count: Int): Pending =
        queue.ask(Act("particles", json { at(at); put("particle", particle); put("count", count) }, null, refusal = noPlace("particles", at)))

    override fun fireworks(at: Place?, count: Int): Pending =
        queue.ask(Act("fireworks", json { at(at); put("count", count) }, null, refusal = noPlace("fireworks", at)))

    override fun lightning(at: Place?): Pending = queue.ask(Act("lightning", json { at(at) }, null, refusal = noPlace("lightning", at)))

    override fun smite(player: String?, hearts: Int): Pending {
        val refusal = if (hearts < 1) ToolResult.invalid("smite hurts 1 to 5 hearts (asked $hearts); lightning(at) is the harmless one") else null
        return queue.ask(Act("lightning", json { put("player", player); put("damage_hearts", hearts); put("offset", 0) }, player, refusal = refusal))
    }

    override fun explosion(at: Place?, power: Double, hurts: Boolean): Pending =
        queue.ask(Act("explosion", json { at(at); put("power", power); put("hurts", hurts); put("offset", 0) }, null, refusal = noPlace("explosion", at)))

    override fun effect(player: String?, effect: String?, seconds: Int, level: Int): Pending {
        val refusal = effect?.let(::forbiddenEffect)?.let { ToolResult.denied(FORBIDDEN_EFFECT, it) }
        return queue.ask(
            Act("apply_effect", json { putJsonArray("players") { add(player) }; put("effect", effect); put("seconds", seconds); put("level", level) }, player, refusal = refusal),
        )
    }

    override fun attribute(player: String?, attribute: String?, multiply: Double, seconds: Int): Pending =
        queue.ask(Act("set_attribute", json { put("player", player); put("attribute", attribute); put("multiply", multiply); put("seconds", seconds) }, player))

    override fun launch(player: String?, direction: String?, strength: Double): Pending =
        queue.ask(Act("launch", json { put("player", player); put("direction", direction); put("strength", strength) }, player))

    override fun teleport(player: String?, to: Place?): Pending {
        val refusal = when {
            to == null -> ToolResult.invalid("teleport needs a place to go")
            player != null && dimensionOf(player).let { it != null && it != to.dimension() } ->
                ToolResult.invalid("a module moves a player within their own dimension: ${ModelText.name(player)} is in ${dimensionOf(player)}, not ${to.dimension()}")
            else -> null
        }
        return queue.ask(
            Act(
                "teleport",
                json {
                    put("player", player)
                    if (to != null) {
                        put("x", floor(to.x()).toInt())
                        put("y", floor(to.y()).toInt())
                        put("z", floor(to.z()).toInt())
                    }
                },
                player,
                refusal = refusal,
            ),
        )
    }

    override fun teleportRandom(player: String?, radius: Int): Pending =
        queue.ask(Act("teleport", json { put("player", player); put("random_radius", radius) }, player))

    override fun spawn(type: String?, near: String?, how: Spawn?): Pending {
        val spawn = how ?: Spawn.of(1)
        val forbidden = type?.let { parseId(it)?.toString() }?.let { ModuleCreatures.FORBIDDEN[it]?.let { why -> "$it is never a module's: $why" } }
        val reserved = forbidden == null && creatures.reserve(spawn.count())
        val refusal = when {
            forbidden != null -> ToolResult.denied(ModuleCreatures.FORBIDDEN_CREATURE, forbidden)
            !reserved -> ToolResult.denied(
                ModuleCreatures.OWN_LIMIT,
                "${creatures.count} creatures of its own, ${creatures.coming} on their way, and ${spawn.count()} more asked: " +
                    "${ForgeLimits.OWN_CREATURES} at most; release some first",
            )
            else -> null
        }
        val args = json {
            put("entity", type)
            put("count", spawn.count())
            put("near", near)
            put("distance", spawn.distance())
            spawn.name()?.let { put("name", it) }
            put("target", spawn.target())
            put("lifetime_minutes", spawn.lifetimeMinutes())
            put("height", spawn.height())
            put("baby", spawn.baby())
        }
        val settle: ((ToolResult) -> Unit)? = if (reserved) { result -> creatures.adopt(result.entities, spawn.count(), near, spawn.target()) } else null
        return queue.ask(Act("spawn", args, near, refusal = refusal, settle = settle))
    }

    override fun punish(player: String?, punishment: String?, severity: Int, reason: String?): Pending =
        queue.ask(Act("punish", json { put("player", player); put("punishment", punishment); put("severity", severity); put("reason", reason) }, player))

    companion object {
        /** The rule of an effect a module never gives: `DENIED (forbidden_effect): …`, as for a law (M4-14). */
        const val FORBIDDEN_EFFECT = "forbidden_effect"

        /**
         * The effects `apply_effect` never gives in a module's act, as in a law's (`RuleValidator`, M4 code review,
         * finding 8): they wound, or end in a fall; the Director gives them himself, call by call.
         */
        private val FORBIDDEN_EFFECTS = mapOf(
            "minecraft:wither" to "an effect that wounds",
            "minecraft:poison" to "an effect that wounds",
            "minecraft:instant_damage" to "an effect that wounds",
            "minecraft:levitation" to "an effect that ends in a fall",
        )

        /** Why a module never gives [effect] (`wither`, `minecraft:poison`), or null. */
        fun forbiddenEffect(effect: String): String? {
            val id = parseId(effect)?.toString() ?: return null
            return FORBIDDEN_EFFECTS[id]?.let { "a module never gives ${id.removePrefix("minecraft:")}: $it is the Director's to give, one call at a time" }
        }

        private fun noPlace(tool: String, at: Place?): ToolResult? = if (at == null) ToolResult.invalid("$tool needs a place") else null
    }
}

/**
 * The judged challenge a deployment referees (M5 §4.4, §6.4): its end is asked like an act ([ActQueue.ask]) and done
 * after the call by the engine, `ChallengeControl.end(…, Author(ENGINE))`: the stakes are paid, the Director told.
 * Winners are named among its participants when they are asked, the module's list copied in its own call.
 */
class ModuleGame(private val challenge: Ref, private val deployment: Ref, private val challenges: ChallengeControl, private val queue: ActQueue) : Game {
    override fun id(): String = challenge.toString()

    override fun players(): List<String> = java.util.List.copyOf(view()?.participants?.map { it.name }.orEmpty())

    override fun running(): Boolean = view()?.status == ChallengeStatus.RUNNING

    override fun win(winners: List<String>?, note: String?): Pending {
        // Copied here, in the module's own call: a module may implement List.
        val names = winners?.let { ArrayList<Any?>(it) }.orEmpty()
        val participants = view()?.participants.orEmpty()
        val found = names.map { name -> participants.firstOrNull { name is String && it.name.equals(name, ignoreCase = true) } }
        val refusal = when {
            names.isEmpty() -> ToolResult.invalid("win names one winner at least; lose when nobody won")
            null in found -> ToolResult.invalid(
                "${names.filterIndexed { i, _ -> found[i] == null }.joinToString { ModelText.name(it.toString()) }} not among the participants of $challenge: " +
                    participants.joinToString { ModelText.name(it.name) }.ifEmpty { "nobody" },
            )
            else -> null
        }
        val uuids = found.filterNotNull().map { it.playerId }.distinct()
        return queue.ask(Act(null, gameArgs("won", names.map { it.toString() }, note), names.firstOrNull()?.toString(), refusal = refusal, run = { end(ChallengeOutcome.WON, uuids, note) }))
    }

    override fun lose(note: String?): Pending = queue.ask(Act(null, gameArgs("lost", emptyList(), note), null, run = { end(ChallengeOutcome.LOST, emptyList(), note) }))

    private fun view() = challenges.list().firstOrNull { it.ref == challenge }

    private suspend fun end(outcome: ChallengeOutcome, winners: List<UUID>, note: String?): ToolResult = try {
        val said = note?.trim()?.takeIf { it.isNotEmpty() }?.let { ModelText.cut(it, ForgeLimits.NOTE_CHARS) } ?: "refereed by the module of $deployment"
        ToolResult.ok("ok: ${challenges.end(challenge, outcome, winners, said, Author(Source.ENGINE)).line}")
    } catch (e: MechanicRejected) {
        e.toResult()
    }

    private fun gameArgs(outcome: String, winners: List<String>, note: String?) = json {
        put("challenge", challenge.toString())
        put("outcome", outcome)
        putJsonArray("winners") { winners.forEach { add(it) } }
        put("note", note)
    }
}

private fun json(build: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(build)

/** `to`: one player, as the speech tools take it. */
private fun JsonObjectBuilder.to(player: String?) {
    putJsonArray("to") { add(player) }
}

/** A place as the world tools take it: block coordinates and a dimension. Nothing for null (the act is refused). */
private fun JsonObjectBuilder.at(at: Place?) {
    if (at == null) return
    put("x", floor(at.x()).toInt())
    put("y", floor(at.y()).toInt())
    put("z", floor(at.z()).toInt())
    put("dimension", at.dimension())
}
