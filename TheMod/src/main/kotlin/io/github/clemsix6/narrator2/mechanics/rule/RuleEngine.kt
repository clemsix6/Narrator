package io.github.clemsix6.narrator2.mechanics.rule

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.core.onServerThread
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.director.MechanicSignal
import io.github.clemsix6.narrator2.event.Envelope
import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.guard.NarratorMob
import io.github.clemsix6.narrator2.guard.NarratorMobs
import io.github.clemsix6.narrator2.mechanics.EffectTarget
import io.github.clemsix6.narrator2.mechanics.EngineActions
import io.github.clemsix6.narrator2.mechanics.Firing
import io.github.clemsix6.narrator2.mechanics.MechanicPart
import io.github.clemsix6.narrator2.mechanics.MechanicsLimits
import io.github.clemsix6.narrator2.mechanics.RuleEffect
import io.github.clemsix6.narrator2.mechanics.RuleKind
import io.github.clemsix6.narrator2.mechanics.RuleState
import io.github.clemsix6.narrator2.mechanics.RuleTrigger
import io.github.clemsix6.narrator2.mechanics.StateStore
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.memory.InteractionDraft
import io.github.clemsix6.narrator2.memory.InteractionKind
import io.github.clemsix6.narrator2.memory.MemoryLimits
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.observer.ref
import io.github.clemsix6.narrator2.observer.toPos
import io.github.clemsix6.narrator2.observer.typeId
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolArgs
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.tool.action.MobAttributeKey
import io.github.clemsix6.narrator2.tool.answer
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.fabricmc.fabric.api.entity.FakePlayer
import net.minecraft.core.Holder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.OwnableEntity
import net.minecraft.world.entity.ai.attributes.Attribute
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.random.Random
import kotlin.reflect.KClass

/**
 * The laws and plans at work (M4 §8.2, §8.6): the part of the engine that sets the rules off and ends them. The
 * rule book ([book], M4-14) creates and removes them; this part matches the facts, fires within the limits, runs
 * the effects through the real tools, counts what came of each firing, ends a rule when its time is up, when a
 * plan fired once or after five failures in a row, and takes its marks off the creatures it changed.
 *
 * - **Matching** ([onEvent], inline in the bus's call): the rules are indexed by trigger; a fact
 *   ([RuleFacts]) matches a rule's conditions, then its chance is drawn ([RuleMatcher]); the match waits for the
 *   next tick. `position` is looked at each second for each player online (rising edge, [PositionEdges]);
 *   `mob_spawn` when a creature appears or loads ([onMobLoad]), and, once, for the creatures already loaded
 *   when a rule is created or the server starts.
 * - **Limits** (at the tick, in the order of the facts): one firing per rule and per actor every 3 s, 30 per
 *   rule in 10 min, 200 a minute for `mob_spawn` ([RuleLimits]). Past them nothing fires: the firing is counted
 *   `skipped`, and a `throttled` decision tells it, once a minute per rule at most.
 * - **Firing**: an act of the engine ([EngineActions.open] `fired`), then the effects in order, each at its delay
 *   after the fact (the tick wakes them), through the real tools with their target filled ([RuleTargets]), in a
 *   coroutine: same checks, caps, guards and limits as the Director. An `explosion` or a `lightning` refused by a
 *   guard is played once more harmless. A `notify` gets what happened as its `fact`. A `punish` done is an
 *   interaction of the player (`punishment`, canon = the rule). The effects of `mob_spawn` (`mob_effect`,
 *   `mob_attribute`) run on the server thread, on the creature itself: cheap.
 * - **Outcome**: `fired` (an effect done), `skipped` (all refused or skipped), `failed` (a `FAILED` or an
 *   `INVALID`, none done), counted in the rule's state; a skipped or failed firing is a decision of that name.
 *   Five failed firings in a row disable the rule; a skipped one neither counts nor breaks the row.
 * - **End** ([RuleBook.end], in a coroutine): `once` after its first `fired` (`fulfilled`), its time up
 *   (`expired`, a `RULE` event), disabled (`disabled`, a `RULE` event). A rule that leaves the state, however,
 *   gets its marks (tag and modifiers, [RuleMarks]) taken off the creatures loaded; a creature that loads later
 *   with the marks of a rule no longer active gets them taken off then.
 *
 * Hooks on the server thread; [onEvent] from any thread; [situation] from any thread (the snapshot).
 *
 * @param tags the tags of the conditions: the server's, by default.
 * @param clock the engine's: the delays, the limits, the ends. A GameTest moves its own.
 * @param area the creatures this part looks after: every one by default; a GameTest gives its own area, so that
 *   engines running side by side never touch each other's creatures.
 */
class RuleEngine(
    private val services: NarratorServices,
    private val store: StateStore,
    private val book: RuleBook,
    private val actions: EngineActions,
    private val tags: RuleTags = ServerRuleTags(services),
    private val clock: Clock = services.clock,
    private val random: Random = Random.Default,
    private val area: (Entity) -> Boolean = { true },
) : MechanicPart {
    private val log = Log.mechanics
    private val decisions get() = Decisions(services.trace, clock)

    /** The rules of the state by trigger, their conditions compiled; rebuilt when the state's list changes. */
    private class Index(val source: List<RuleState>, val byTrigger: Map<RuleTrigger, List<RuleMatcher>>, val byId: Map<Long, RuleMatcher>)

    @Volatile
    private var index = Index(emptyList(), emptyMap(), emptyMap())

    /** A fact that matched rule [rule] at [at], waiting for the next tick. */
    private class Candidate(val rule: Long, val fact: RuleFact, val at: Long)

    /** A firing in a coroutine, waiting for [due]. */
    private class Wait(val due: Long, val wake: CompletableDeferred<Unit>)

    /** A firing done: what came of each of its effects. */
    private class Done(val rule: Long, val fact: RuleFact, val runs: List<EffectRun>)

    /** A `mob_spawn` firing on [mob], on the server thread: its effects from [next] on, each at its delay after [at]. */
    private class MobFiring(val rule: RuleState, val mob: Mob, val what: String, val at: Long, val act: Firing, val sweep: Boolean) {
        var next = 0
        val runs = ArrayList<EffectRun>()
    }

    /** The firings of a rule held back by the limits: [pending] not counted in its state yet, [untold] since the last decision. */
    private class Held {
        var pending = 0
        var untold = 0
        var since = 0L
        var toldAt = Long.MIN_VALUE
    }

    private val candidates = ConcurrentLinkedQueue<Candidate>()
    private val waits = ConcurrentLinkedQueue<Wait>()
    private val done = ConcurrentLinkedQueue<Done>()

    /** The rules being ended ([RuleBook.end] runs in a coroutine): they fire no more. */
    private val ending: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    // The server thread's own.
    private val limits = RuleLimits()
    private val edges = PositionEdges()
    private val held = HashMap<Long, Held>()
    private val running = HashMap<Long, Int>()
    private val mobFirings = ArrayList<MobFiring>()

    /** The rules this part has seen: one that leaves the state gets its marks taken off. */
    private val known = HashSet<Long>()

    /** The `mob_spawn` rules that changed the creatures already loaded (once each). */
    private val swept = HashSet<Long>()
    private var server: MinecraftServer? = null

    /** The firings' coroutines: cancelled when the server stops. */
    private val job = SupervisorJob(services.scope.coroutineContext[Job])
    private val scope = CoroutineScope(services.scope.coroutineContext + job)

    @Volatile
    private var stopped = false

    override val listensTo: Set<KClass<out GameEvent>> = RuleFacts.EVENTS

    // ── Matching ──

    /** A fact of the bus: the rules of its trigger whose conditions hold and whose chance is drawn wait for the tick. Any thread. */
    override fun onEvent(envelope: Envelope) {
        if (stopped) return
        val matchers = current().byTrigger[RuleFacts.triggerOf(envelope.event) ?: return] ?: return
        val fact = RuleFacts.of(envelope.event, services.players) ?: return
        val now = clock.millis()
        for (matcher in matchers) {
            if (matcher.matches(fact) && matcher.drawn(random)) candidates += Candidate(matcher.id, fact, now)
        }
    }

    /**
     * A creature appeared or loaded: the marks of the rules no longer active come off it; then each active
     * `mob_spawn` rule that matches it, and has not changed it already (a creature loaded again keeps its
     * marks), fires on it within its limit. Never a player, one of the Narrator's creatures, a tamed animal nor a
     * creature a player named. Server thread.
     */
    override fun onMobLoad(entity: Entity, level: ServerLevel) {
        if (stopped || entity !is Mob || !area(entity)) return
        val rules = store.snapshot.rules
        val stale = marksOn(entity).filter { id -> rules.none { it.id == id } }
        if (stale.isNotEmpty()) {
            unmark(entity, stale)
            log.debug { "${entity.typeId} ${entity.uuid} loaded with the marks of ${stale.joinToString { "C-$it" }}, ended: taken off" }
        }
        val matchers = current().byTrigger[RuleTrigger.MOB_SPAWN] ?: return
        if (!spawnable(entity)) return
        val now = clock.millis()
        val fact = RuleFacts.spawned(entity.uuid, entity.typeId, entity.blockPosition().toPos(level))
        for (matcher in matchers) {
            val rule = rules.firstOrNull { it.id == matcher.id } ?: continue
            if (!live(rule, now) || RuleMarks.tag(rule.id) in entity.entityTags() || !matcher.matches(fact) || !matcher.drawn(random)) continue
            if (!limits.allow(rule.id, RuleTrigger.MOB_SPAWN, null, now)) {
                hold(rule.id, now)
                continue
            }
            known += rule.id
            val act = actions.open(FIRED, rule.ref, short(entity.typeId), detail = detail(rule, fact), reason = "${rule.ref} ${fact.what}")
            mobFirings += MobFiring(rule, entity, fact.what, now, act, sweep = false)
        }
    }

    /** The index of the rules in the state now: rebuilt when the state's list changed, each rule's conditions compiled once. Any thread. */
    private fun current(): Index {
        val rules = store.snapshot.rules
        val last = index
        if (last.source === rules) return last
        val matchers = rules.map { rule -> last.byId[rule.id] ?: RuleMatcher(rule, tags) }
        return Index(rules, matchers.groupBy { it.trigger }, matchers.associateBy { it.id }).also { index = it }
    }

    // ── Ticks and seconds ──

    override fun everyTick(server: MinecraftServer, now: Long) = tick(now)

    /** A tick: the firings done are counted, the facts matched fire within the limits, the delays due wake up, the creatures' effects due run. Server thread. */
    internal fun tick(now: Long) {
        if (stopped) return
        conclude(now)
        fireCandidates(now)
        waits.removeIf { wait -> (wait.due <= now).also { if (it) wait.wake.complete(Unit) } }
        runMobFirings(now)
    }

    override fun everySecond(server: MinecraftServer, now: Long) = second(server, now)

    /**
     * A second: the rules that left the state get their marks taken off ([server] given), the new `mob_spawn` ones
     * change the creatures already loaded, the rules whose time is up end, the players' positions are looked at,
     * and the firings held back by the limits are counted and told. Server thread; [server] null in unit tests.
     */
    internal fun second(server: MinecraftServer?, now: Long) {
        if (stopped) return
        if (server != null) this.server = server
        val rules = store.get().rules
        val ids = rules.mapTo(HashSet()) { it.id }
        val gone = known.filterTo(HashSet()) { it !in ids }
        if (gone.isNotEmpty()) left(gone)
        known += ids
        if (server != null) {
            rules.filter { it.spec.trigger == RuleTrigger.MOB_SPAWN && it.id !in swept && live(it, now) }.forEach { sweep(server, it, now) }
        }
        rules.filter { it.endsAt <= now && it.id !in ending }.forEach { end(it, Ending.EXPIRED, null) }
        server?.let { positions(it, now) }
        release(now)
    }

    /** The rule is still at work at [now]: not past its time, not being ended. */
    private fun live(rule: RuleState, now: Long): Boolean = rule.id !in ending && rule.endsAt > now

    // ── Firing ──

    /** The facts matched since the last tick, in order: each fires within its rule's limits. Server thread. */
    private fun fireCandidates(now: Long) {
        while (true) {
            val candidate = candidates.poll() ?: return
            val rule = store.get().rule(candidate.rule) ?: continue
            if (!live(rule, now)) continue
            // A plan fires once: while its first firing runs, the next facts wait for none.
            if (rule.spec.once && (running[rule.id] ?: 0) > 0) continue
            if (!limits.allow(rule.id, rule.spec.trigger, candidate.fact.actor?.uuid, candidate.at)) {
                hold(rule.id, candidate.at)
                continue
            }
            fire(rule, candidate.fact, candidate.at)
        }
    }

    /** Fires [rule] for [fact] (at [at]): an act of the engine, then its effects in a coroutine. Server thread. */
    private fun fire(rule: RuleState, fact: RuleFact, at: Long) {
        known += rule.id
        val act = actions.open(FIRED, rule.ref, fact.actor?.name, detail = detail(rule, fact), reason = "${rule.ref} ${fact.what}")
        running.merge(rule.id, 1, Int::plus)
        scope.launch {
            val runs = rule.spec.effects.map { effect ->
                until(at + effect.delaySeconds * SECOND_MS)
                try {
                    effect(act, rule, fact, effect)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("${rule.ref}: its effect ${effect.tool} failed", e)
                    EffectRun(effect.tool, EffectOutcome.FAILED, "FAILED: ${e.message ?: e.javaClass.simpleName}")
                }
            }
            done += Done(rule.id, fact, runs)
        }
    }

    /** Waits for the tick at or after [due] (the engine's clock). */
    private suspend fun until(due: Long) {
        if (clock.millis() >= due) return
        val wait = Wait(due, CompletableDeferred())
        waits += wait
        wait.wake.await()
    }

    /**
     * One effect of a firing: its target filled, then the real tool in the act. A target gone (the player left,
     * the creature died) or of the wrong kind (a creature for a tool of players) skips it.
     */
    private suspend fun effect(act: Firing, rule: RuleState, fact: RuleFact, effect: RuleEffect): EffectRun {
        val tool = effect.tool
        val aiming = RuleTargets.ALLOWED[tool] ?: return EffectRun(tool, EffectOutcome.FAILED, "Invalid arguments: $tool cannot be used in a rule")
        val aim = when (val target = aim(effect.at, aiming, fact)) {
            is Target.Gone -> return EffectRun(tool, EffectOutcome.SKIPPED, target.why)
            is Target.At -> target.aim
        }
        val args = fact.actor?.let { RuleTargets.withActor(effect.args, it.name) } ?: effect.args
        var filled = RuleTargets.aimed(tool, args, aim)
            ?: return EffectRun(tool, EffectOutcome.SKIPPED, "its ${effect.at.id} is a creature, and $tool acts on players")
        if (aiming == Aiming.NEWS) filled = JsonObject(filled + (FACT to JsonPrimitive(ModelText.cut(fact.what, MechanicsLimits.RULE_LINE))))
        val result = actions.run(act, tool, filled)
        if (result.status == ToolStatus.DENIED) {
            harmless(tool, filled)?.let { quiet ->
                val again = actions.run(act, tool, quiet)
                if (again.status == ToolStatus.OK) return EffectRun(tool, EffectOutcome.DONE, "${firstLine(result)}; played harmless: ${firstLine(again)}")
            }
        }
        if (result.status == ToolStatus.OK && tool == PUNISH) punished(rule, result)
        return EffectRun(tool, outcomeOf(result.status), firstLine(result))
    }

    /** Where an effect at [at] acts for [fact]: a target, or why there is none now. */
    private sealed interface Target {
        data class At(val aim: Aim) : Target

        data class Gone(val why: String) : Target
    }

    private suspend fun aim(at: EffectTarget, aiming: Aiming, fact: RuleFact): Target = when (at) {
        EffectTarget.ACTOR -> fact.actor?.let(::online) ?: Target.Gone("${fact.trigger.id} has no player")
        EffectTarget.SUBJECT -> when (val subject = fact.subject) {
            null -> Target.Gone("${fact.trigger.id} had no subject this time")
            is RuleSubject.Player -> online(subject.ref)
            is RuleSubject.Creature -> creature(subject, aiming)
        }
        EffectTarget.POSITION -> fact.position?.let { Target.At(Aim.Point(it.x, it.y, it.z, ToolText.dimension(it.dim))) }
            ?: Target.Gone("where it happened is unknown")
        EffectTarget.EVERYONE -> Target.At(Aim.Everyone)
    }

    /** [player], by their name now, if they are online. */
    private fun online(player: PlayerRef): Target =
        services.players.get(player.uuid)?.let { Target.At(Aim.Player(it.ref.name)) } ?: Target.Gone("${ModelText.name(player.name)} is not online")

    /**
     * The creature [subject], alive and loaded now: where it stands for a tool of places, itself for the
     * creatures' tools; for a tool of players, itself, which [RuleTargets.aimed] refuses (skipped).
     */
    private suspend fun creature(subject: RuleSubject.Creature, aiming: Aiming): Target {
        if (aiming != Aiming.PLACE && aiming != Aiming.CREATURE) return Target.At(Aim.Creature(subject.uuid))
        val server = services.server
        val where = server.onServerThread(SERVER_TIMEOUT_MS) {
            server.overworld().getEntityInAnyDimension(subject.uuid)?.takeIf { it.isAlive }?.let { it.blockPosition().toPos(it.level()) }
        } ?: return Target.Gone("the ${short(subject.type)} is gone")
        return Target.At(if (aiming == Aiming.PLACE) Aim.Point(where.x, where.y, where.z, ToolText.dimension(where.dim)) else Aim.Creature(subject.uuid))
    }

    /** [args] of an `explosion` or a `lightning` that hurts nobody, to play again after a guard refused it; null for any other. */
    private fun harmless(tool: String, args: JsonObject): JsonObject? = when (tool) {
        EXPLOSION -> if ((args[HURTS] as? JsonPrimitive)?.content == "false") null else JsonObject(args + (HURTS to JsonPrimitive(false)))
        LIGHTNING -> if (args[DAMAGE_HEARTS] == null) null else JsonObject(args + (DAMAGE_HEARTS to JsonPrimitive(0)))
        else -> null
    }

    /** A `punish` done by [rule]: an interaction of each player punished (`punishment`, canon = the rule), as a punishment for a fault (M4 §8.6). */
    private suspend fun punished(rule: RuleState, result: ToolResult) {
        if (!services.memory.available) return
        val summary = ModelText.cut(firstLine(result).removePrefix("ok: "), MemoryLimits.INTERACTION_SUMMARY)
        for (player in result.targets) {
            try {
                services.memory.store.addInteraction(
                    InteractionDraft(player.uuid, InteractionKind.PUNISHMENT, 0, summary, canon = rule.ref),
                    Author(Source.ENGINE, conversationId = EngineActions.conversationOf(rule.ref)),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("${rule.ref}: the punishment of ${player.name} could not be written in their history", e)
            }
        }
    }

    // ── Creatures (mob_spawn) ──

    /** The `mob_spawn` firings whose effects are due, in order; each one done is counted. Server thread. */
    private fun runMobFirings(now: Long) {
        if (mobFirings.isEmpty()) return
        val iterator = mobFirings.iterator()
        while (iterator.hasNext()) {
            val firing = iterator.next()
            val effects = firing.rule.spec.effects
            while (firing.next < effects.size && firing.at + effects[firing.next].delaySeconds * SECOND_MS <= now) {
                firing.runs += onCreature(firing, effects[firing.next])
                firing.next++
            }
            if (firing.next < effects.size) continue
            iterator.remove()
            count(firing.rule.id, firing.runs, firing.what, now, told = !firing.sweep)
        }
    }

    /** One effect of a `mob_spawn` firing, on its creature: the tool's dry checks now (the chaos level may have changed), then the change itself. */
    private fun onCreature(firing: MobFiring, effect: RuleEffect): EffectRun {
        val mob = firing.mob
        if (mob.isRemoved || !mob.isAlive) return EffectRun(effect.tool, EffectOutcome.SKIPPED, "the ${short(mob.typeId)} is gone")
        val args = JsonObject(effect.args + (CREATURE to JsonPrimitive(mob.uuid.toString())))
        dryChecks(effect.tool, args, firing.act.caller)?.let { return EffectRun(effect.tool, outcomeOf(it.status), firstLine(it)) }
        val result = answer {
            when (effect.tool) {
                MOB_EFFECT -> MobEffectTool.apply(mob, firing.rule.id, decoded(MobEffectArgs.serializer(), args))
                MOB_ATTRIBUTE -> MobAttributeTool.apply(mob, firing.rule.id, decoded(MobAttributeArgs.serializer(), args))
                else -> ToolResult.invalid("${effect.tool} does not change a creature that appears")
            }
        }
        return EffectRun(effect.tool, outcomeOf(result.status), firstLine(result))
    }

    /**
     * The creatures already loaded that a new `mob_spawn` rule changes (M4 §8.5): at its creation, or when the server
     * starts. One decision for them all; no limit. Server thread.
     */
    private fun sweep(server: MinecraftServer, rule: RuleState, now: Long) {
        swept += rule.id
        val matcher = current().byId[rule.id] ?: RuleMatcher(rule, tags)
        val found = creatures(server).filterIsInstance<Mob>()
            .filter { mob -> spawnable(mob) && RuleMarks.tag(rule.id) !in mob.entityTags() }
            .filter { mob -> matcher.matches(RuleFacts.spawned(mob.uuid, mob.typeId, mob.blockPosition().toPos(mob.level()))) && matcher.drawn(random) }
            .toList()
        if (found.isEmpty()) return
        val types = found.groupingBy { short(it.typeId) }.eachCount().entries.joinToString { "${it.value} ${it.key}" }
        val act = actions.open(FIRED, rule.ref, "${found.size} creatures", reason = "${rule.ref} changes the ${found.size} creatures already loaded: $types")
        found.forEach { mobFirings += MobFiring(rule, it, "already loaded", now, act, sweep = true) }
    }

    /** Whether a `mob_spawn` rule may change [mob]: never one of the Narrator's own, a tamed animal, nor a creature a player named. */
    private fun spawnable(mob: Mob): Boolean {
        if (mob.hasAttached(NarratorMob.TYPE)) return false
        if (NarratorMobs.TAG in mob.entityTags() && marksOn(mob).isEmpty()) return false
        if ((mob as? OwnableEntity)?.ownerReference != null) return false
        return !mob.hasCustomName()
    }

    /** The creatures loaded in [area]. Server thread. */
    private fun creatures(server: MinecraftServer): Sequence<Entity> =
        server.allLevels.asSequence().flatMap { it.allEntities.asSequence() }.filter(area)

    /** Rules [ids] left the state: forgotten, and their marks taken off the creatures loaded. Server thread. */
    private fun left(ids: Set<Long>) {
        known -= ids
        swept -= ids
        for (id in ids) {
            limits.forget(id)
            edges.forgetRule(id)
            held.remove(id)
            running.remove(id)
            ending.remove(id)
        }
        val server = server ?: return
        var creatures = 0
        for (entity in creatures(server)) {
            val marks = marksOn(entity).filter { it in ids }
            if (marks.isEmpty()) continue
            unmark(entity, marks)
            creatures++
        }
        log.info("${ids.joinToString { "C-$it" }} left the rules at work; marks taken off $creatures creatures")
    }

    // ── Outcomes and ends ──

    /** The firings done since the last tick, counted. Server thread. */
    private fun conclude(now: Long) {
        while (true) {
            val finished = done.poll() ?: return
            running.computeIfPresent(finished.rule) { _, n -> (n - 1).takeIf { it > 0 } }
            count(finished.rule, finished.runs, finished.fact.what, now, told = true)
        }
    }

    /**
     * What came of a firing of rule [id] ([runs]) counted in its state; a skipped or failed one told in a decision
     * when [told]. A plan that fired once ends; five failures in a row disable the rule. Server thread.
     */
    private fun count(id: Long, runs: List<EffectRun>, what: String, now: Long, told: Boolean) {
        val outcome = FiringOutcome.of(runs)
        val rule = update(id) { rule ->
            when (outcome) {
                FiringOutcome.FIRED -> rule.copy(fired = rule.fired + 1, failures = 0, lastFiredAt = now)
                FiringOutcome.SKIPPED -> rule.copy(skipped = rule.skipped + 1)
                FiringOutcome.FAILED -> rule.copy(failures = rule.failures + 1)
            }
        } ?: return
        if (outcome != FiringOutcome.FIRED && told) {
            decision(outcome.id, rule.ref, ModelText.cut("${rule.ref} ${outcome.id} ($what): ${summary(runs)}", REASON_MAX), Log.Level.INFO)
        }
        when {
            outcome == FiringOutcome.FIRED && rule.spec.once -> end(rule, Ending.ONCE, what)
            outcome == FiringOutcome.FAILED && rule.failures >= MechanicsLimits.RULE_FAILURES_TO_DISABLE -> {
                val failure = runs.first { it.outcome == EffectOutcome.FAILED }
                end(rule, Ending.DISABLED, "${failure.tool}: ${failure.text}")
            }
        }
    }

    /** How a rule ends here: its decision, its canon's status (law, plan), and whether the Director hears of it. */
    private enum class Ending(val decision: String, val law: CanonStatus, val plan: CanonStatus) {
        /** A rule that fires once fired. */
        ONCE("fulfilled", CanonStatus.RETIRED, CanonStatus.FULFILLED),

        /** Its time is up (also while the server was down: at its first second). */
        EXPIRED("expired", CanonStatus.RETIRED, CanonStatus.EXPIRED),

        /** Five failed firings in a row. */
        DISABLED("disabled", CanonStatus.RETIRED, CanonStatus.CANCELLED),
    }

    /**
     * Ends [rule] ([RuleBook.end], in a coroutine): its canon resolved by the engine with a note that says why
     * ([why]: the fact of a plan fulfilled, the failure that disabled it), then a `RULE` event to the Director for
     * a rule disabled or past its time. It fires no more from now on. Server thread.
     */
    private fun end(rule: RuleState, ending: Ending, why: String?) {
        if (!this.ending.add(rule.id)) return
        val law = rule.spec.kind == RuleKind.LAW
        val note = ModelText.cut(
            when (ending) {
                Ending.ONCE -> "fired once: $why"
                Ending.EXPIRED -> "its time ran out"
                Ending.DISABLED -> "disabled after ${rule.failures} failures in a row: $why"
            },
            MechanicsLimits.NOTE,
        )
        val line = when (ending) {
            Ending.ONCE -> null
            Ending.EXPIRED -> "${rule.ref} ${rule.spec.kind.id} ended: its time is up (${RuleBook.counts(rule)})"
            Ending.DISABLED -> "${rule.ref} disabled after ${rule.failures} failures: $why"
        }
        services.scope.launch {
            val ended = try {
                book.end(rule.id, if (law) ending.law else ending.plan, note, ending.decision)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("${rule.ref} could not be ended (${ending.decision}); it is tried again at its next end", e)
                this@RuleEngine.ending.remove(rule.id)
                null
            }
            if (ended != null && line != null) {
                services.director.post(DirectorSignal.Mechanic(MechanicSignal.RULE, ModelText.cut(line, MechanicsLimits.RULE_LINE), clock.millis(), priority = false))
            }
        }
    }

    // ── Limits held ──

    /** A firing of rule [id] held back by the limits at [at]. Server thread. */
    private fun hold(id: Long, at: Long) {
        val rule = held.getOrPut(id) { Held() }
        if (rule.untold == 0) rule.since = at
        rule.pending++
        rule.untold++
    }

    /** The firings held back, counted `skipped` in their rules, and told in a `throttled` decision once a minute per rule at most. Server thread. */
    private fun release(now: Long) {
        for ((id, rule) in held) {
            if (rule.pending > 0) {
                val n = rule.pending
                rule.pending = 0
                update(id) { it.copy(skipped = it.skipped + n) }
            }
            if (rule.untold == 0 || (rule.toldAt != Long.MIN_VALUE && now - rule.toldAt < MechanicsLimits.THROTTLED_DECISION_GAP_MS)) continue
            val ref = Ref(RefKind.CANON, id)
            decision(
                THROTTLED, ref,
                "C-$id: ${rule.untold} firings held back by the limits since ${TIME.format(Instant.ofEpochMilli(rule.since).atZone(clock.zone))} " +
                    "(once per player every ${MechanicsLimits.RULE_ACTOR_GAP_MS / SECOND_MS}s, ${MechanicsLimits.RULE_FIRINGS_PER_WINDOW} per rule in " +
                    "${MechanicsLimits.RULE_WINDOW_MS / 60_000}m, ${MechanicsLimits.MOB_SPAWN_FIRINGS_PER_MINUTE} a minute for mob_spawn)",
                Log.Level.INFO,
            )
            rule.untold = 0
            rule.toldAt = now
        }
    }

    // ── Positions ──

    /** Each `position` rule against each player online: it fires on the rising edge of its place (M4 §8.2). Server thread. */
    private fun positions(server: MinecraftServer, now: Long) {
        val matchers = current().byTrigger[RuleTrigger.POSITION] ?: return
        val players = server.playerList.players.filter { it !is FakePlayer }
        for (matcher in matchers) {
            for (player in players) {
                val fact = position(player)
                if (edges.rose(matcher.id, player.uuid, matcher.matches(fact)) && matcher.drawn(random)) candidates += Candidate(matcher.id, fact, now)
            }
        }
    }

    /** Where [player] stands, for the `position` rules: the structure the sampler saw last, the biome under their feet. */
    private fun position(player: ServerPlayer): RuleFact {
        val level = player.level()
        val block = player.blockPosition()
        return RuleFacts.position(player.ref(), block.toPos(level), services.players.get(player.uuid)?.structure, level.getBiome(block).registeredName)
    }

    override fun onJoin(player: ServerPlayer) = edges.forgetPlayer(player.uuid)

    override fun onLeave(player: ServerPlayer) = edges.forgetPlayer(player.uuid)

    // ── The engine's life ──

    /**
     * The server runs (M4 §4.6): the creatures already loaded (the spawn chunks, which had no load hook) lose the
     * marks of the rules no longer in the state, and the active `mob_spawn` rules change those they match. The
     * rules past their time end at the first second.
     */
    override fun started(server: MinecraftServer, now: Long) {
        this.server = server
        val rules = store.get().rules
        known += rules.map { it.id }
        for (entity in creatures(server)) {
            val stale = marksOn(entity).filter { id -> rules.none { it.id == id } }
            if (stale.isNotEmpty()) unmark(entity, stale)
        }
        rules.filter { it.spec.trigger == RuleTrigger.MOB_SPAWN && live(it, now) }.forEach { sweep(server, it, now) }
    }

    /** The reconciliation took rules [ids] out of the state: their marks come off now. Server thread. */
    override fun dropped(ids: Set<Long>) {
        known += ids
        left(ids)
    }

    override fun situation(now: Long): List<String> = listOfNotNull(situation(store.snapshot.rules, now, clock.zone))

    /** `SERVER_STOPPING`: the firings waiting and in progress are dropped, and counted in a decision. */
    override fun stopping() {
        if (stopped) return
        stopped = true
        val waiting = candidates.size + mobFirings.size
        val inProgress = running.values.sum()
        candidates.clear()
        mobFirings.clear()
        job.cancel()
        waits.clear()
        if (waiting + inProgress > 0) {
            decision(SKIPPED, null, "the server stops: $inProgress firings in progress and $waiting waiting dropped", Log.Level.INFO)
        }
    }

    // ── Helpers ──

    /** Rule [id] changed by [change] in the state; the new rule, or null when it is no longer there. Server thread. */
    private fun update(id: Long, change: (RuleState) -> RuleState): RuleState? {
        var changed: RuleState? = null
        store.update { state ->
            state.copy(rules = state.rules.map { rule -> if (rule.id == id) change(rule).also { changed = it } else rule })
        }
        return changed
    }

    private fun decision(what: String, ref: Ref?, reason: String, level: Log.Level) {
        decisions.record(log, DecisionRecord.Kind.MECHANIC, what, ref?.toString(), reason, conversationId = EngineActions.conversationOf(ref), level = level)
    }

    /** The `fired` decision's detail: the trigger, what happened, the effects. */
    private fun detail(rule: RuleState, fact: RuleFact): JsonObject = buildJsonObject {
        put("trigger", rule.spec.trigger.id)
        put("fact", fact.what)
        putJsonArray("effects") { rule.spec.effects.forEach { add("${it.tool} ${it.at.id}" + if (it.delaySeconds > 0) " +${it.delaySeconds}s" else "") } }
    }

    /**
     * A tool's dry checks ([EngineActions.check]), here and now on the server thread: they never suspend (no
     * player, no world, no wait: M4-03), so nothing waits; a check that suspended would be a bug, and fails.
     */
    private fun dryChecks(tool: String, args: JsonObject, caller: Caller): ToolResult? {
        var outcome: Result<ToolResult?>? = null
        suspend { actions.check(tool, args, caller) }.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
        return (outcome ?: error("the dry checks of $tool suspended")).getOrThrow()
    }

    private fun <A : Any> decoded(serializer: KSerializer<A>, args: JsonObject): A = when (val decoded = ToolArgs.decode(serializer, args.toString())) {
        is ToolArgs.Decoded.Ok -> decoded.args
        is ToolArgs.Decoded.Invalid -> throw IllegalArgumentException(decoded.problem)
    }

    companion object {
        /** The decisions of the rules' firings (M4 §19); their ends are [Ending]'s. */
        const val FIRED = "fired"
        const val SKIPPED = "skipped"
        const val FAILED = "failed"
        const val THROTTLED = "throttled"

        /** The tools whose effect the engine treats apart. */
        private const val PUNISH = "punish"
        private const val EXPLOSION = "explosion"
        private const val LIGHTNING = "lightning"
        private const val MOB_EFFECT = "mob_effect"
        private const val MOB_ATTRIBUTE = "mob_attribute"

        /** Arguments the engine fills. */
        private const val FACT = "fact"
        private const val CREATURE = "creature"
        private const val HURTS = "hurts"
        private const val DAMAGE_HEARTS = "damage_hearts"

        private const val SECOND_MS = 1_000L
        private const val SERVER_TIMEOUT_MS = 5_000L

        /** Longest reason of a `skipped` or `failed` decision. */
        private const val REASON_MAX = 1_000

        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)

        /** The attributes a rule may change on a creature, and their holders (registered once the game booted). */
        private val ATTRIBUTES: List<Pair<MobAttributeKey, Holder<Attribute>>> by lazy {
            MobAttributeKey.entries.mapNotNull { key -> BuiltInRegistries.ATTRIBUTE.get(Identifier.withDefaultNamespace(key.id)).orElse(null)?.let { key to it } }
        }

        /**
         * The `Rules:` line of the situation (M4 §4.5), [MechanicsLimits.SITUATION_LINE] characters at most: each rule
         * ([RuleText.line]), oldest first, then the last ones left out (`(+2 more)`). Null without rules.
         */
        fun situation(rules: List<RuleState>, now: Long, zone: ZoneId): String? {
            if (rules.isEmpty()) return null
            val items = rules.map { RuleText.line(it, now, zone) }
            val max = MechanicsLimits.SITUATION_LINE
            for (kept in items.size downTo 1) {
                val more = items.size - kept
                val text = "Rules: " + (items.take(kept) + listOfNotNull("(+$more more)".takeIf { more > 0 })).joinToString(" · ")
                if (text.length <= max) return text
            }
            val more = if (items.size > 1) " · (+${items.size - 1} more)" else ""
            return "Rules: " + ModelText.cut(items.first(), max - "Rules: ".length - more.length) + more
        }

        /** The rules whose marks [entity] bears (`narrator2_rule_<id>`). */
        fun marksOn(entity: Entity): List<Long> =
            entity.entityTags().mapNotNull { tag -> if (tag.startsWith(RuleMarks.TAG_PREFIX)) tag.removePrefix(RuleMarks.TAG_PREFIX).toLongOrNull() else null }

        /**
         * Takes the marks of rules [ids] off [entity] (M4 §8.6): their tags and attribute modifiers; then the
         * Narrator's tag, once no rule's is left on a creature the Narrator did not summon. A health above the
         * maximum left falls back to it. Server thread.
         */
        fun unmark(entity: Entity, ids: Collection<Long>) {
            for (id in ids) {
                entity.removeTag(RuleMarks.tag(id))
                if (entity is LivingEntity) for ((key, holder) in ATTRIBUTES) entity.getAttribute(holder)?.removeModifier(RuleMarks.modifier(id, key))
            }
            if (entity is LivingEntity && entity.health > entity.maxHealth) entity.health = entity.maxHealth
            if (marksOn(entity).isEmpty() && !entity.hasAttached(NarratorMob.TYPE)) entity.removeTag(NarratorMobs.TAG)
        }

        private fun outcomeOf(status: ToolStatus): EffectOutcome = when (status) {
            ToolStatus.OK -> EffectOutcome.DONE
            ToolStatus.DENIED -> EffectOutcome.REFUSED
            else -> EffectOutcome.FAILED
        }

        private fun firstLine(result: ToolResult): String = ModelText.cut(result.text.lineSequence().first(), EngineActions.FIRST_LINE_MAX)

        /** `punish refused: DENIED (same_punishment): …; actionbar done: ok: …`. */
        private fun summary(runs: List<EffectRun>): String = runs.joinToString("; ") { "${it.tool} ${it.outcome.id}: ${it.text}" }

        private fun short(id: String): String = id.removePrefix("minecraft:")
    }
}

/** What came of one effect of a firing (M4 §8.6). */
enum class EffectOutcome {
    DONE,

    /** A guard refused it (`DENIED`). */
    REFUSED,

    /** Its target was gone, or not of the kind its tool acts on. */
    SKIPPED,

    /** `FAILED`, `INVALID`, or a world tool that did not finish. */
    FAILED,
    ;

    val id: String get() = name.lowercase()
}

/** One effect of a firing: its [tool], what came of it, and the first line of its answer (or why it was skipped). */
data class EffectRun(val tool: String, val outcome: EffectOutcome, val text: String)

/** What came of a firing (M4 §8.6): `fired` (an effect done), `failed` (none done, one failed), `skipped` (all refused or skipped). */
enum class FiringOutcome {
    FIRED,
    SKIPPED,
    FAILED,
    ;

    val id: String get() = name.lowercase()

    companion object {
        fun of(runs: List<EffectRun>): FiringOutcome = when {
            runs.any { it.outcome == EffectOutcome.DONE } -> FIRED
            runs.any { it.outcome == EffectOutcome.FAILED } -> FAILED
            else -> SKIPPED
        }
    }
}
