package io.github.clemsix6.narrator2.mechanics.rule

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.core.onServerThread
import io.github.clemsix6.narrator2.mechanics.EffectTarget
import io.github.clemsix6.narrator2.mechanics.EngineActions
import io.github.clemsix6.narrator2.mechanics.MechanicRejected
import io.github.clemsix6.narrator2.mechanics.RuleControl
import io.github.clemsix6.narrator2.mechanics.RuleEffect
import io.github.clemsix6.narrator2.mechanics.RuleKind
import io.github.clemsix6.narrator2.mechanics.RuleSpec
import io.github.clemsix6.narrator2.mechanics.RuleState
import io.github.clemsix6.narrator2.mechanics.RuleView
import io.github.clemsix6.narrator2.mechanics.StateStore
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
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The laws and plans (M4 §8): the engine's [RuleControl], used by `create_rule`, `remove_rule` and the
 * operators' commands. A rule is a row of the canon (`law` or `plan`, mechanic `rule`) and an entry of the
 * engine's state ([store]); the rule engine (M4-18) sets them off and ends them through [end].
 *
 * - [create]: validated as the Director ([RuleValidator], M4 §8.5), then the limit of active rules
 *   (`mechanics.maxRules`), then the canon row (written as the Director: the engine's tools write its rows,
 *   M4-07), then the state, on the server thread.
 * - [remove], [end]: out of the state first (server thread), then the canon row resolved by the engine
 *   (`Source.ENGINE`, whoever asked: M4 §3.3), with the note given.
 *
 * Creations and ends go one at a time (a lock): the limit and the state never race. Each one is a `mechanic`
 * decision (`started`, `removed`, or the rule engine's own).
 */
class RuleBook(
    private val services: NarratorServices,
    private val store: StateStore,
    private val validator: RuleValidator,
) : RuleControl {
    private val log = Log.mechanics
    private val lock = Mutex()

    override suspend fun create(spec: RuleSpec, caller: Caller): RuleView = lock.withLock {
        val valid = validator.validate(spec, caller)
        val active = store.snapshot.rules
        val max = services.settings.get().config.mechanics.maxRules
        if (active.size >= max) {
            throw MechanicRejected(RULE_LIMIT, "$max rules are active (${active.joinToString { it.ref.toString() }}); remove one first")
        }
        val draft = CanonDraft(
            kind = if (spec.kind == RuleKind.LAW) CanonKind.LAW else CanonKind.PLAN,
            statement = spec.statement,
            quote = spec.quote,
            players = valid.players.map { it.uuid },
            importance = spec.importance,
            status = CanonStatus.ACTIVE,
            mechanic = CanonMechanic.RULE,
        )
        val row = try {
            services.memory.store.addCanon(draft, Author(Source.DIRECTOR, turnId = caller.turnId, conversationId = caller.conversationId)).row
        } catch (e: MemoryRejected) {
            throw MechanicRejected(e.rule, e.message.orEmpty())
        }
        val now = services.clock.millis()
        val rule = RuleState(
            id = row.ref.id,
            spec = valid.spec,
            createdAt = now,
            endsAt = now + spec.minutes * MINUTE_MS,
            turnId = caller.turnId,
            conversationId = caller.conversationId,
            playerIds = valid.players.map { it.uuid.toString() },
        )
        try {
            onServer { store.update { it.copy(rules = it.rules + rule) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("${rule.ref} could not take effect; its canon row is cancelled", e)
            resolve(rule, CanonStatus.CANCELLED, NOT_STARTED, null)
            throw e
        }
        decision(STARTED, rule, "${rule.ref} ${spec.kind.id} active until ${RuleText.time(rule.endsAt, now, services.clock.zone)}: ${RuleText.summary(rule.spec)}", caller.turnId)
        view(rule, now)
    }

    override suspend fun remove(ref: Ref, note: String, author: Author): RuleView = lock.withLock {
        val rule = store.snapshot.rules.firstOrNull { ref.kind == RefKind.CANON && it.id == ref.id }
            ?: throw MechanicRejected(null, "$ref is not an active rule; active: ${store.snapshot.rules.joinToString { it.ref.toString() }.ifEmpty { "none" }}")
        finish(rule.id, if (rule.spec.kind == RuleKind.LAW) CanonStatus.RETIRED else CanonStatus.CANCELLED, note, author, REMOVED)
            ?: throw MechanicRejected(null, "$ref is not an active rule any more: it just ended")
    }

    override fun list(): List<RuleView> {
        val now = services.clock.millis()
        return store.snapshot.rules.map { view(it, now) }
    }

    /**
     * Ends rule [id] now (M4 §8.6), for the rule engine (M4-18): fired once, past its end, or disabled. Out of the
     * state, then its canon row resolved [status] (`fulfilled`, `expired`, `retired`, `cancelled`) with [note]
     * (French, the canon's resolution), by the engine; [decision] names the `mechanic` decision. Null when the
     * rule is no longer active. Never on the server thread.
     */
    suspend fun end(id: Long, status: CanonStatus, note: String, decision: String): RuleView? = lock.withLock {
        finish(id, status, note, null, decision)
    }

    /** [end] under the lock; [by] who asked, when someone did. */
    private suspend fun finish(id: Long, status: CanonStatus, note: String, by: Author?, decision: String): RuleView? {
        val rule = onServer {
            store.get().rule(id)?.also { gone -> store.update { state -> state.copy(rules = state.rules.filterNot { it.id == gone.id }) } }
        } ?: return null
        resolve(rule, status, note, by)
        val asked = by?.let { " by ${who(it)}" }.orEmpty()
        decision(decision, rule, "${rule.ref} ${rule.spec.kind.id} ${status.name.lowercase()}$asked: ${counts(rule)}; « $note »", by?.turnId)
        return view(rule, services.clock.millis())
    }

    /** The canon row of [rule] resolved by the engine; a refusal (already closed) or a failure is logged. */
    private suspend fun resolve(rule: RuleState, status: CanonStatus, note: String, by: Author?) {
        try {
            services.memory.store.resolveCanon(rule.ref, status, note, emptyList(), Author(Source.ENGINE, turnId = by?.turnId, conversationId = by?.conversationId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: MemoryRejected) {
            log.warn("the canon row of ${rule.ref} was not resolved ${status.name.lowercase()}: ${e.message}")
        } catch (e: Exception) {
            log.error("the canon row of ${rule.ref} could not be resolved ${status.name.lowercase()}; the reconciliation will close it", e)
        }
    }

    private fun decision(what: String, rule: RuleState, reason: String, turnId: Long?) {
        Decisions(services.trace, services.clock).record(
            log, DecisionRecord.Kind.MECHANIC, what, subject = rule.ref.toString(), reason = reason,
            conversationId = EngineActions.conversationOf(rule.ref), turnId = turnId,
        )
    }

    /** [block] on the server thread for the world's state; anywhere for a state in memory (tests). */
    private suspend fun <T> onServer(block: () -> T): T = if (store.inWorld) services.server.onServerThread(SERVER_TIMEOUT_MS, block) else block()

    private fun view(rule: RuleState, now: Long) =
        RuleView(rule.ref, rule.spec, rule.endsAt, rule.fired, rule.skipped, rule.failures, RuleText.line(rule, now, services.clock.zone))

    companion object {
        /** The rule of the limit of active rules: `DENIED (rule_limit): …`. */
        const val RULE_LIMIT = "rule_limit"

        /** The decision of a rule created. */
        const val STARTED = "started"

        /** The decision of a rule removed by the Director or an operator. */
        const val REMOVED = "removed"

        /** The canon's resolution of a rule whose state could not be written (the server stopping). */
        const val NOT_STARTED = "it never took effect: the server was stopping"

        private const val MINUTE_MS = 60_000L
        private const val SERVER_TIMEOUT_MS = 5_000L

        /** `12 fired, 3 skipped`. */
        fun counts(rule: RuleState): String = "${rule.fired} fired" + if (rule.skipped > 0) ", ${rule.skipped} skipped" else ""

        private fun who(author: Author): String = when (author.source) {
            Source.DIRECTOR -> "the director" + (author.turnId?.let { " (turn $it)" } ?: "")
            Source.OPERATOR -> "operator " + (author.operator ?: "?")
            else -> author.source.id
        }
    }
}

/** How a rule reads for the model: in the tools' answers and in the situation (M4 §4.5, §12.3). English, compact. */
object RuleText {
    /**
     * A rule in one line: `block_break where grief → punish tiny (2) on the actor, actionbar to the actor`, its
     * conditions and its effects with their target and delay.
     */
    fun summary(spec: RuleSpec): String {
        val conditions = conditions(spec)
        val where = if (conditions.isEmpty()) "" else " where " + conditions.joinToString(", ")
        return "${spec.trigger.id}$where → ${spec.effects.joinToString(", ") { effect(it) }}"
    }

    /**
     * The rule's line of the situation (M4 §4.5): `C-60 law block_break grief → punish (2 fired, until 10-25 20:15)`;
     * `C-61 plan once dimension_change Bartok the_nether → spawn, notify (until 21:40)`.
     */
    fun line(rule: RuleState, now: Long, zone: ZoneId): String {
        val spec = rule.spec
        val conditions = conditions(spec)
        val counters = listOfNotNull(
            "${rule.fired} fired".takeIf { rule.fired > 0 },
            "${rule.failures} failed in a row".takeIf { rule.failures > 0 },
            "until ${time(rule.endsAt, now, zone)}",
        )
        return "${rule.ref} ${spec.kind.id}${if (spec.once) " once" else ""} ${spec.trigger.id}" +
            (if (conditions.isEmpty()) "" else " " + conditions.joinToString(" ")) +
            " → ${spec.effects.joinToString(", ") { it.tool }} (${counters.joinToString(", ")})"
    }

    /** [at] as the model reads an end: `21:40` today, `10-25 20:15` another day. */
    fun time(at: Long, now: Long, zone: ZoneId): String {
        val day = Instant.ofEpochMilli(at).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return (if (day.toLocalDate() == today) TIME else DAY_TIME).format(day)
    }

    /** The conditions given, short: `grief`, `Bartok`, `skeleton`, `y ≤ 20`, `30%`. */
    private fun conditions(spec: RuleSpec): List<String> {
        val c = spec.conditions
        return listOfNotNull(
            c.players?.joinToString(" or ") { ModelText.name(it) },
            c.entity?.let(::short),
            c.block?.let(::short),
            c.item?.let(::short),
            c.grief?.let { if (it) "grief" else "not grief" },
            c.dimension?.let(::short),
            c.structure?.let(::short),
            c.biome?.let(::short),
            c.advancement?.let(::short),
            c.yBelow?.let { "y ≤ $it" },
            c.yAbove?.let { "y ≥ $it" },
            c.near?.let { "within ${it.radius} of ${it.x} ${it.y} ${it.z}" + (it.dimension?.let { d -> " in ${short(d)}" } ?: "") },
            c.words?.joinToString(" or ") { "« ${ModelText.escape(it)} »" },
            c.chance.takeIf { it < 1.0 }?.let { "${ToolText.number(it * 100)}%" },
        )
    }

    /** `punish tiny (2) on the actor`, `explosion (power 2) at the position`, `fireworks on the subject after 3s`. */
    private fun effect(effect: RuleEffect): String {
        val args = effect.args
        fun text(key: String): String? = (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun number(key: String): String? = (args[key] as? JsonPrimitive)?.takeUnless { it.isString }?.content
        val detail = when (effect.tool) {
            "punish" -> listOfNotNull(text("punishment"), number("severity")?.let { "($it)" }).joinToString(" ")
            "apply_effect", "mob_effect" -> listOfNotNull(text("effect")?.let(::short), number("level")?.let { "level $it" }).joinToString(" ")
            "mob_attribute" -> listOfNotNull(text("attribute"), number("multiply")?.let { "×$it" }).joinToString(" ")
            "set_attribute" -> text("attribute").orEmpty()
            "spawn" -> listOfNotNull(number("count")?.let { "${it}x" }, text("entity")?.let(::short)).joinToString(" ")
            "explosion" -> number("power")?.let { "(power $it)" }.orEmpty()
            else -> ""
        }
        val target = when (effect.at) {
            EffectTarget.ACTOR -> if (RuleTargets.ALLOWED[effect.tool] == Aiming.AUDIENCE) "to the actor" else "on the actor"
            EffectTarget.SUBJECT -> "on the subject"
            EffectTarget.POSITION -> "at the position"
            EffectTarget.EVERYONE -> "to everyone"
        }
        val delay = if (effect.delaySeconds > 0) " after ${effect.delaySeconds}s" else ""
        return listOf(effect.tool, detail, target).filter { it.isNotEmpty() }.joinToString(" ") + delay
    }

    /** An id as the model reads it: without `minecraft:`, a tag's `#` kept. */
    private fun short(id: String): String = if (id.startsWith("#")) "#" + id.removePrefix("#").removePrefix("minecraft:") else id.removePrefix("minecraft:")

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private val DAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT)
}
