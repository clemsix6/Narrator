package io.github.clemsix6.narrator2.mechanics.rule

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.director.MechanicSignal
import io.github.clemsix6.narrator2.guard.NarratorMobs
import io.github.clemsix6.narrator2.guard.Rules
import io.github.clemsix6.narrator2.mechanics.EngineActions
import io.github.clemsix6.narrator2.mechanics.MechanicsLimits
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.observer.typeId
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.Doc
import io.github.clemsix6.narrator2.tool.MaxLen
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.Range
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.tool.action.MobAttributeKey
import io.github.clemsix6.narrator2.tool.action.dryCheck
import io.github.clemsix6.narrator2.tool.action.forbiddenEffectIds
import io.github.clemsix6.narrator2.tool.action.parseId
import io.github.clemsix6.narrator2.tool.action.shortId
import io.github.clemsix6.narrator2.tool.answer
import io.github.clemsix6.narrator2.tool.at
import io.github.clemsix6.narrator2.tool.chaos
import io.github.clemsix6.narrator2.tool.refuse
import io.github.clemsix6.narrator2.tool.speech.closestIds
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import kotlinx.serialization.Serializable
import net.minecraft.core.Holder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/*
 * The three tools of the rules only (M4 §8.4): `mob_effect` and `mob_attribute` on the creature of the trigger,
 * and `notify`, which wakes the Director with his own note. They are NarratorTools that no role sees (they are
 * not in `ToolCatalog.allTools`): the engine's acts have them (`EngineActions`, built by M4-23), so that a rule's
 * effect is checked when it is created ([NarratorTool.check]) and runs like any other.
 *
 * The engine fills what the model cannot know, as it fills the target of every other tool ([RuleTargets]): the
 * creature of the trigger ([MobEffectArgs.creature], [MobAttributeArgs.creature]) and what set the rule off
 * ([NotifyArgs.fact]). The model leaves them out; the validation refuses them ([RuleValidator]).
 *
 * The description of `create_rule` gives their arguments: the model never sees these tools (M4 review,
 * finding 18).
 */

/** The tools of the rules only, for the engine's acts (M4-23 adds them to `EngineActions`). */
fun ruleOnlyTools(): List<NarratorTool<*>> = listOf(MobEffectTool(), MobAttributeTool(), NotifyTool())

/**
 * The marks a rule leaves on a creature it changed (M4 §8.6): the Narrator's tag ([NarratorMobs.TAG]), so that
 * the creature is the Narrator's while the rule lives (its explosions break nothing, the death guard counts
 * its kills), the rule's own tag, and its attribute modifiers. The rule engine (M4-18) takes them off when the
 * rule ends, and [NarratorMobs.TAG] when no rule's tag is left on a creature that is not one the Narrator
 * summoned.
 */
object RuleMarks {
    /** The start of every rule's tag: `narrator2_rule_`. */
    const val TAG_PREFIX = "${NarratorMobs.TAG}_rule_"

    /** The tag of rule [id]'s creatures: `narrator2_rule_60`. */
    fun tag(id: Long): String = TAG_PREFIX + id

    /** The modifier of rule [id] on [attribute]: `narrator2:rule/60/movement_speed`. */
    fun modifier(id: Long, attribute: MobAttributeKey): Identifier = Identifier.fromNamespaceAndPath("narrator2", "rule/$id/${attribute.id}")

    /** Whether [id] is the modifier of a rule, any rule, on [attribute]. */
    fun isRules(id: Identifier, attribute: MobAttributeKey): Boolean =
        id.namespace == "narrator2" && id.path.startsWith("rule/") && id.path.endsWith("/${attribute.id}")

    /** Marks [entity] as changed by rule [id]: the Narrator's creature while the rule lives. Server thread. */
    fun mark(entity: Entity, id: Long) {
        entity.addTag(NarratorMobs.TAG)
        entity.addTag(tag(id))
    }
}

/** The rule whose act [caller] is (`mechanics:C-60`, with a firing id), or null for any other caller. */
internal fun ruleOf(caller: Caller): Ref? {
    if (caller.firingId == null || !caller.conversationId.startsWith(EngineActions.CONVERSATION_PREFIX)) return null
    return Ref.parse(caller.conversationId.removePrefix(EngineActions.CONVERSATION_PREFIX))?.takeIf { it.kind == RefKind.CANON }
}

/** The rule of the act, or a refusal: these tools run only in a rule's act. */
private fun ToolContext.rule(tool: String): Ref = ruleOf(caller) ?: refuse(ToolResult.invalid("$tool runs only in a rule's act (create_rule)"))

/** The creature of the trigger, filled by the engine, alive and loaded; a refusal otherwise. Server thread. */
private fun creature(server: MinecraftServer, raw: String?, tool: String): Mob {
    val uuid = raw?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: refuse(ToolResult.invalid("$tool acts on the creature of the trigger: the engine fills creature (at subject)"))
    val entity = server.overworld().getEntityInAnyDimension(uuid)?.takeIf { it.isAlive }
        ?: refuse(ToolResult.failed("the creature of the trigger is gone"))
    return entity as? Mob ?: refuse(ToolResult.failed("${entity.typeId} is not a creature: $tool acts on creatures only"))
}

/** Refuses at chaos 0: a rule changes no creature then. */
private fun ToolContext.creaturesAllowed() {
    if (!guards.caps().hostileAllowed) refuse(ToolResult.denied(Rules.CHAOS_0, "the chaos level is 0: no rule changes a creature."))
}

// ── mob_effect ──────────────────────────────────────────────────────────────────────────────────

/** The arguments of `mob_effect` (M4 §8.4): an effect on the creature of the trigger. */
@Serializable
data class MobEffectArgs(
    @Doc("Effect id, e.g. speed, slowness, glowing, weakness.") @MaxLen(64) val effect: String,
    @Doc("How long, in seconds.") @Range(1.0, 900.0) val seconds: Int,
    @Doc("Level, 1 being the effect's first level.") @Range(1.0, 5.0) val level: Int = 1,
    @Doc("Filled by the engine: the creature of the trigger.") @MaxLen(36) val creature: String? = null,
)

/**
 * `mob_effect`: puts an effect on the creature of the trigger, within the caps the chaos level sets on harmful
 * effects, whatever the effect. Never an effect that makes it hard to kill or unseen, nor one that wounds or
 * kills it, by itself or by a fall (a law must not decimate the villagers or the players' cattle: M4 review
 * finding 11, M4 code review finding 8), nor one that acts on the world.
 */
class MobEffectTool : NarratorTool<MobEffectArgs>(
    name = "mob_effect",
    description = "Rules only: puts a status effect on the creature of the trigger (at subject), within the chaos level's caps.",
    args = MobEffectArgs.serializer(),
    effect = ToolEffect.WORLD,
) {
    override suspend fun check(ctx: ToolContext, args: MobEffectArgs): ToolResult? = dryCheck {
        ctx.creaturesAllowed()
        val effect = lookup(args.effect)
        val id = shortId(effect)
        forbidden(effect)?.let { refuse(ToolResult.denied(FORBIDDEN_EFFECT, "$id is never put on a creature by a rule: $it.")) }
        val caps = ctx.guards.caps()
        val instant = effect.value().isInstantaneous
        if ((!instant && args.seconds > caps.harmfulSeconds) || args.level > caps.harmfulLevel) {
            refuse(
                ToolResult.invalid(
                    "at chaos ${ctx.chaos} an effect on a creature lasts at most ${caps.harmfulSeconds} s and goes up to level ${caps.harmfulLevel} " +
                        "(asked ${args.seconds} s, level ${args.level})",
                ),
            )
        }
    }

    override suspend fun run(ctx: ToolContext, args: MobEffectArgs): ToolResult = answer {
        check(ctx, args)?.let { return@answer it }
        val rule = ctx.rule(name)
        ctx.onServer { server -> answer { apply(creature(server, args.creature, name), rule.id, args) } }
    }

    companion object {
        /** The rule of a refused effect: `DENIED (forbidden_effect): …`, as `apply_effect` says it. */
        const val FORBIDDEN_EFFECT = "forbidden_effect"

        /**
         * Effects a rule never puts on a creature, with why (M4 §8.4). Those that act on the world
         * ([forbiddenEffectIds]: omens, infested, oozing, weaving, wind charged) are refused too.
         */
        private val FORBIDDEN: Map<String, String> = mapOf(
            "minecraft:invisibility" to "an invisible creature cannot be seen coming",
            "minecraft:resistance" to "it makes the creature hard to kill",
            "minecraft:regeneration" to "it makes the creature hard to kill",
            "minecraft:absorption" to "it makes the creature hard to kill",
            "minecraft:health_boost" to "it makes the creature hard to kill",
            "minecraft:instant_health" to "it heals the living and wounds the undead",
            "minecraft:wither" to "it wounds and kills: a rule never decimates the villagers or the players' animals",
            "minecraft:poison" to "it wounds: a rule never decimates the villagers or the players' animals",
            "minecraft:instant_damage" to "it wounds the living and heals the undead",
            "minecraft:levitation" to "it ends in a fall that kills: a rule never decimates the villagers or the players' animals",
            "minecraft:jump_boost" to "a higher jump is a fall that hurts: a rule never makes a creature fall",
        )

        /** Why a rule never puts [effect] on a creature, or null. */
        fun forbidden(effect: Holder<MobEffect>): String? =
            FORBIDDEN[effect.registeredName] ?: if (effect.registeredName in forbiddenEffectIds) "it acts on the world" else null

        /** The effect called [raw]; refuses with the closest ids otherwise. Any thread. */
        private fun lookup(raw: String): Holder.Reference<MobEffect> =
            parseId(raw)?.let { BuiltInRegistries.MOB_EFFECT.get(it).orElse(null) }
                ?: refuse(ToolResult.invalid("unknown effect \"$raw\"; closest ids: ${closestIds(raw, BuiltInRegistries.MOB_EFFECT.keySet().map(::shortId)).joinToString()}"))

        /**
         * Puts [args]'s effect on [mob] for rule [rule], and marks it ([RuleMarks]): the checks passed. The rule
         * engine may call it directly on the server thread (M4 §8.6). Server thread.
         */
        fun apply(mob: Mob, rule: Long, args: MobEffectArgs): ToolResult {
            val effect = lookup(args.effect)
            val instant = effect.value().isInstantaneous
            mob.addEffect(MobEffectInstance(effect, if (instant) 1 else args.seconds * TICKS_PER_SECOND, args.level - 1, false, true))
            RuleMarks.mark(mob, rule)
            val shown = "${shortId(effect)} ${ToolText.roman(args.level)}"
            return ToolResult.ok("ok: ${mob.typeId} gets $shown" + if (instant) "" else " for ${ToolText.duration(args.seconds * 1_000L)}")
        }

        private const val TICKS_PER_SECOND = 20
    }
}

// ── mob_attribute ───────────────────────────────────────────────────────────────────────────────

/** The arguments of `mob_attribute` (M4 §8.4): an attribute of the creature of the trigger, while the rule lives. */
@Serializable
data class MobAttributeArgs(
    val attribute: MobAttributeKey,
    @Doc("Factor of the creature's normal value: 3 for three times as fast.") @Range(0.0, 5.0) val multiply: Double,
    @Doc("Filled by the engine: the creature of the trigger.") @MaxLen(36) val creature: String? = null,
)

/**
 * `mob_attribute`: changes an attribute of the creature of the trigger by a factor, within bounds of the rules
 * ([bounds]) narrower than `spawn`'s: a law runs without the Director's judgement and must never kill a creature
 * (M4 code review, finding 8). A permanent modifier of the rule ([RuleMarks.modifier]), put by
 * `addOrReplacePermanentModifier`: a creature loaded again already has it, and adding it twice would throw. The
 * rule engine takes it off when the rule ends.
 */
class MobAttributeTool : NarratorTool<MobAttributeArgs>(
    name = "mob_attribute",
    description = "Rules only: changes an attribute of the creature of the trigger (at subject) while the rule lives.",
    args = MobAttributeArgs.serializer(),
    effect = ToolEffect.WORLD,
) {
    override suspend fun check(ctx: ToolContext, args: MobAttributeArgs): ToolResult? = dryCheck {
        ctx.creaturesAllowed()
        val key = args.attribute
        val bounds = bounds(key)
        if (args.multiply !in bounds) refuse(ToolResult.invalid("${within(key)} (asked ${ToolText.number(args.multiply)})"))
    }

    override suspend fun run(ctx: ToolContext, args: MobAttributeArgs): ToolResult = answer {
        check(ctx, args)?.let { return@answer it }
        val rule = ctx.rule(name)
        ctx.onServer { server -> answer { apply(creature(server, args.creature, name), rule.id, args) } }
    }

    companion object {
        /**
         * The factors of [key] a rule may set, all its rules together on one creature: never a creature so big it
         * chokes under a low ceiling (a villager in its hall), never a lower maximum health, never a higher jump nor
         * a lighter gravity (a fall that hurts); `spawn`'s bounds otherwise.
         */
        fun bounds(key: MobAttributeKey): ClosedFloatingPointRange<Double> = when (key) {
            MobAttributeKey.SCALE -> 0.5..2.0
            MobAttributeKey.MAX_HEALTH -> 1.0..key.max
            MobAttributeKey.JUMP_STRENGTH -> key.min..1.0
            MobAttributeKey.GRAVITY -> 1.0..key.max
            MobAttributeKey.MOVEMENT_SPEED, MobAttributeKey.ATTACK_DAMAGE -> key.min..key.max
        }

        /** `scale multiplies by 0.5 to 2 in a rule: …`: [bounds] and why. */
        private fun within(key: MobAttributeKey): String {
            val bounds = bounds(key)
            val why = when (key) {
                MobAttributeKey.SCALE -> ": a creature too big chokes under a low ceiling"
                MobAttributeKey.MAX_HEALTH -> ": a rule never lowers a creature's health"
                MobAttributeKey.JUMP_STRENGTH, MobAttributeKey.GRAVITY -> ": a rule never makes a creature fall from higher"
                MobAttributeKey.MOVEMENT_SPEED, MobAttributeKey.ATTACK_DAMAGE -> ""
            }
            return "${key.id} multiplies by ${ToolText.number(bounds.start)} to ${ToolText.number(bounds.endInclusive)} in a rule$why"
        }

        /**
         * Changes [mob]'s attribute for rule [rule] and marks it ([RuleMarks]): the checks passed. A raised
         * maximum health raises the health by the same factor, once. The other rules' factors on the creature count:
         * together they stay within [bounds], or nothing changes (`FAILED`, a firing skipped). The rule engine may
         * call it directly on the server thread (M4 §8.6). Server thread.
         */
        fun apply(mob: Mob, rule: Long, args: MobAttributeArgs): ToolResult {
            val key = args.attribute
            val holder = BuiltInRegistries.ATTRIBUTE.get(Identifier.withDefaultNamespace(key.id)).orElseThrow { IllegalStateException("no attribute minecraft:${key.id}") }
            val instance = mob.getAttribute(holder) ?: refuse(ToolResult.failed("${mob.typeId} has no ${key.id}"))
            val id = RuleMarks.modifier(rule, key)
            val others = instance.modifiers.filter { RuleMarks.isRules(it.id(), key) && it.id() != id }.fold(1.0) { factor, other -> factor * (1 + other.amount()) }
            if (others * args.multiply !in bounds(key)) {
                refuse(ToolResult.failed("${mob.typeId} already has ${key.id} ×${ToolText.number(others)} from other rules, and ${within(key)}, all rules together"))
            }
            val had = instance.getModifier(id)
            instance.addOrReplacePermanentModifier(AttributeModifier(id, args.multiply - 1, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL))
            if (key == MobAttributeKey.MAX_HEALTH && had == null) mob.health = (mob.health * args.multiply).toFloat().coerceAtMost(mob.maxHealth)
            RuleMarks.mark(mob, rule)
            return ToolResult.ok("ok: ${mob.typeId} ${key.id} ×${ToolText.number(args.multiply)} while C-$rule lasts")
        }
    }
}

// ── notify ──────────────────────────────────────────────────────────────────────────────────────

/** The arguments of `notify` (M4 §8.4): the Director's note, and what set the rule off. */
@Serializable
data class NotifyArgs(
    @Doc("Your note, in French: it wakes you with what happened.") @MaxLen(MechanicsLimits.NOTIFY_NOTE) val note: String,
    @Doc("Filled by the engine: what set the rule off.") @MaxLen(MechanicsLimits.RULE_LINE) val fact: String? = null,
)

/**
 * `notify`: a `RULE` event to the Director, with what set the rule off and his note (M4 §4.4): once per rule in
 * [MechanicsLimits.NOTIFY_GAP_MS] at most; the ones in between are held, and counted in the next one. Each one
 * told is a `mechanic notified` decision (subject: the rule; reason: the line), which the Chronicler reads
 * (M4-08). Not a priority: it waits for the hour's turn budget like any event.
 */
class NotifyTool : NarratorTool<NotifyArgs>(
    name = "notify",
    description = "Rules only: wakes you with what set the rule off and your note.",
    args = NotifyArgs.serializer(),
    effect = ToolEffect.ENGINE,
) {
    /** Per rule: when the Director was last told, and the notifications held since. */
    private class Gate(var toldAt: Long = Long.MIN_VALUE, var held: Int = 0, var heldSince: Long = 0)

    private val gates = ConcurrentHashMap<Long, Gate>()

    override suspend fun check(ctx: ToolContext, args: NotifyArgs): ToolResult? =
        if (args.note.isBlank()) ToolResult.invalid("note is empty: write what you want to be reminded of") else null

    override suspend fun run(ctx: ToolContext, args: NotifyArgs): ToolResult = answer {
        check(ctx, args)?.let { return@answer it }
        val rule = ctx.rule(name)
        val now = ctx.clock.millis()
        val gate = gates.computeIfAbsent(rule.id) { Gate() }
        val held = synchronized(gate) {
            if (gate.toldAt != Long.MIN_VALUE && now - gate.toldAt < MechanicsLimits.NOTIFY_GAP_MS) {
                if (gate.held == 0) gate.heldSince = now
                gate.held++
                val next = gate.toldAt + MechanicsLimits.NOTIFY_GAP_MS
                return@answer ToolResult.ok(
                    "ok: held: you were told about $rule ${ToolText.duration(now - gate.toldAt)} ago; this one is counted in the next, " +
                        "from ${ctx.at(next)} (${gate.held} held)",
                )
            }
            val count = gate.held to gate.heldSince
            gate.toldAt = now
            gate.held = 0
            count
        }
        val more = if (held.first == 0) "" else " (+${held.first} more since ${ctx.at(held.second)})"
        val line = ModelText.cut("$rule fired: ${args.fact ?: "its trigger happened"}; your note: « ${args.note.trim()} »$more", MechanicsLimits.RULE_LINE)
        ctx.services.director.post(DirectorSignal.Mechanic(MechanicSignal.RULE, line, now, priority = false))
        Decisions(ctx.services.trace, ctx.clock).record(
            Log.mechanics, DecisionRecord.Kind.MECHANIC, NOTIFIED, subject = rule.toString(), reason = line, conversationId = ctx.caller.conversationId,
        )
        ToolResult.ok("ok: you will be woken with: $line")
    }

    companion object {
        /** The decision of a `notify` told to the Director (M4-08: the Chronicler reads it). */
        const val NOTIFIED = "notified"
    }
}
