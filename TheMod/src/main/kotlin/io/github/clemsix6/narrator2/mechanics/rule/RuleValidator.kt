package io.github.clemsix6.narrator2.mechanics.rule

import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.mechanics.EffectTarget
import io.github.clemsix6.narrator2.mechanics.EngineActions
import io.github.clemsix6.narrator2.mechanics.MechanicRejected
import io.github.clemsix6.narrator2.mechanics.RuleConditions
import io.github.clemsix6.narrator2.mechanics.RuleEffect
import io.github.clemsix6.narrator2.mechanics.RuleSpec
import io.github.clemsix6.narrator2.mechanics.RuleTrigger
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.Refusal
import io.github.clemsix6.narrator2.tool.ToolArgs
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.tool.action.parseId
import io.github.clemsix6.narrator2.tool.action.shortId
import io.github.clemsix6.narrator2.tool.memory.knownPlayers
import io.github.clemsix6.narrator2.tool.speech.closestIds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.minecraft.core.Registry
import net.minecraft.core.registries.Registries
import net.minecraft.server.MinecraftServer
import java.util.UUID

/*
 * The validation of a rule when it is created (M4 §8.5), in order, the first problem given back with its fix
 * (`Invalid arguments: …`, or `DENIED (rule): …` for what a guard refuses):
 *
 * 1. the specification decoded within its bounds (ToolArgs);
 * 2. each condition applies to the trigger (M4 §8.2), names ids the server knows (with the closest ones
 *    otherwise), and the players named have played here;
 * 3. each effect: a tool a rule may use, a target (`at`) it can take for this trigger, its target left out of
 *    `args` (the engine fills it), and its arguments, the target filled with a stand-in, pass the tool's own
 *    decoding and dry checks with the Director's caller (`EngineActions.check`): ids, the chaos level's caps,
 *    arguments that contradict each other. A rule cannot do what the Director may not.
 *
 * The limit of active rules (step 4) is the rule book's ([RuleBook]): it needs the state.
 */

/** The kinds of ids a rule's conditions name (M4 §8.3). [tags]: whether `#tag` is allowed. */
enum class RuleIdKind(val tags: Boolean) {
    ENTITY(true),
    BLOCK(true),
    ITEM(true),
    STRUCTURE(true),
    BIOME(true),
    ADVANCEMENT(false),
    DIMENSION(false),
}

/** The ids the server knows, for a rule's conditions (M4 §8.3). [ServerRuleIds] in the game; a fake in unit tests. */
interface RuleIds {
    /**
     * The full id [raw] names: `minecraft:skeleton`, `#minecraft:diamond_ores`, `minecraft:the_nether`; null when
     * the server knows none. An id may leave out `minecraft:`; a dimension may be `nether` or `end`. Any thread.
     */
    fun resolve(kind: RuleIdKind, raw: String): String?

    /** Every id of [kind] and its tags (`#…`), short (without `minecraft:`): the closest ones go in an error. Any thread. */
    fun known(kind: RuleIdKind): Collection<String>
}

/**
 * The ids of the running [server]: the registries of its world (creatures, blocks, items, structures, biomes,
 * and their tags), its advancements and its dimensions. The registries are frozen once the server runs: any
 * thread.
 */
class ServerRuleIds(private val server: MinecraftServer) : RuleIds {
    override fun resolve(kind: RuleIdKind, raw: String): String? {
        val text = raw.trim().lowercase()
        return when (kind) {
            RuleIdKind.DIMENSION -> ToolText.dimensionId(text).takeIf { id -> server.levelKeys().any { it.identifier().toString() == id } }
            RuleIdKind.ADVANCEMENT -> parseId(text)?.takeIf { server.advancements.get(it) != null }?.toString()
            else -> {
                val registry = registry(kind)
                if (text.startsWith("#")) {
                    val id = parseId(text.removePrefix("#")) ?: return null
                    if (registry.tags.anyMatch { it.key().location() == id }) "#$id" else null
                } else {
                    parseId(text)?.takeIf { registry.containsKey(it) }?.toString()
                }
            }
        }
    }

    override fun known(kind: RuleIdKind): Collection<String> = when (kind) {
        RuleIdKind.DIMENSION -> server.levelKeys().map { ToolText.dimension(it.identifier().toString()) }
        RuleIdKind.ADVANCEMENT -> server.advancements.allAdvancements.map { shortId(it.id()) }
        else -> registry(kind).let { registry -> registry.keySet().map(::shortId) + registry.tags.map { "#" + shortId(it.key().location()) }.toList() }
    }

    private fun registry(kind: RuleIdKind): Registry<*> = with(server.registryAccess()) {
        when (kind) {
            RuleIdKind.ENTITY -> lookupOrThrow(Registries.ENTITY_TYPE)
            RuleIdKind.BLOCK -> lookupOrThrow(Registries.BLOCK)
            RuleIdKind.ITEM -> lookupOrThrow(Registries.ITEM)
            RuleIdKind.STRUCTURE -> lookupOrThrow(Registries.STRUCTURE)
            RuleIdKind.BIOME -> lookupOrThrow(Registries.BIOME)
            RuleIdKind.ADVANCEMENT, RuleIdKind.DIMENSION -> error("$kind has no registry here")
        }
    }
}

/** What the subject of a trigger can be (M4 §8.2): a player (the player hurt), a creature. */
enum class SubjectKind { PLAYER, CREATURE }

/**
 * Where an effect acts, once the engine knows (M4 §8.4): the player of the trigger or the player hurt, a point
 * (where it happened, or where the creature stands), the creature of the trigger, or every player online.
 */
sealed interface Aim {
    data class Player(val name: String) : Aim

    /** [dimension]: as the tools read it, `overworld`, `the_nether`. */
    data class Point(val x: Int, val y: Int, val z: Int, val dimension: String) : Aim

    data class Creature(val uuid: UUID) : Aim

    data object Everyone : Aim
}

/**
 * How a tool of a rule is aimed (M4 §8.4): the arguments the engine fills ([fields], which the model leaves
 * out), the targets (`at`) it takes, and the subjects it acts on.
 */
enum class Aiming(val fields: List<String>, val targets: Set<EffectTarget>, val subjects: Set<SubjectKind>) {
    /** `to` (left out for everyone): `say`, `actionbar`, `show_title`, `play_sound`. */
    AUDIENCE(listOf("to"), setOf(EffectTarget.ACTOR, EffectTarget.SUBJECT, EffectTarget.EVERYONE), setOf(SubjectKind.PLAYER)),

    /** `player`, or `x`, `y`, `z`, `dimension`: `particles`, `fireworks`, `lightning`, `explosion`. A creature is where it stands. */
    PLACE(
        listOf("player", "x", "y", "z", "dimension"),
        setOf(EffectTarget.ACTOR, EffectTarget.SUBJECT, EffectTarget.POSITION),
        setOf(SubjectKind.PLAYER, SubjectKind.CREATURE),
    ),

    /** `players`: `apply_effect`. */
    PLAYERS(listOf("players"), setOf(EffectTarget.ACTOR, EffectTarget.SUBJECT), setOf(SubjectKind.PLAYER)),

    /** `player`: `set_attribute`, `launch`, `teleport`, `punish`. */
    PLAYER(listOf("player"), setOf(EffectTarget.ACTOR, EffectTarget.SUBJECT), setOf(SubjectKind.PLAYER)),

    /** `near`: `spawn`. */
    NEAR(listOf("near"), setOf(EffectTarget.ACTOR, EffectTarget.SUBJECT), setOf(SubjectKind.PLAYER)),

    /** `creature`: `mob_effect`, `mob_attribute`. */
    CREATURE(listOf("creature"), setOf(EffectTarget.SUBJECT), setOf(SubjectKind.CREATURE)),

    /** `fact`, what happened: `notify`, whatever the target. */
    NEWS(listOf("fact"), EffectTarget.entries.toSet(), setOf(SubjectKind.PLAYER, SubjectKind.CREATURE)),
}

/**
 * The tools of the rules and how the engine aims them (M4 §8.4): shared by the validation, which fills a
 * stand-in target, and the execution (M4-18), which fills the real one.
 */
object RuleTargets {
    /**
     * The tools a rule may use, in the order an error lists them. Never a gift, a confiscation, a command, the
     * weather or the time, a reading, the memory, a delegation, nor a mechanic (no rule makes a rule or a
     * challenge): M4 §8.4, §8.7.
     */
    val ALLOWED: Map<String, Aiming> = linkedMapOf(
        "say" to Aiming.AUDIENCE,
        "actionbar" to Aiming.AUDIENCE,
        "show_title" to Aiming.AUDIENCE,
        "play_sound" to Aiming.AUDIENCE,
        "particles" to Aiming.PLACE,
        "fireworks" to Aiming.PLACE,
        "lightning" to Aiming.PLACE,
        "explosion" to Aiming.PLACE,
        "apply_effect" to Aiming.PLAYERS,
        "set_attribute" to Aiming.PLAYER,
        "launch" to Aiming.PLAYER,
        "teleport" to Aiming.PLAYER,
        "spawn" to Aiming.NEAR,
        "punish" to Aiming.PLAYER,
        "mob_effect" to Aiming.CREATURE,
        "mob_attribute" to Aiming.CREATURE,
        "notify" to Aiming.NEWS,
    )

    /** The id of the player as a creature: a `when.entity` that names it sees only a player hurt. */
    const val PLAYER_ENTITY = "minecraft:player"

    /** What the subject of [trigger] can be, given its `when.entity` [entity] (a full id, or null): M4 §8.2. */
    fun subjects(trigger: RuleTrigger, entity: String?): Set<SubjectKind> = when (trigger) {
        RuleTrigger.DAMAGE_DEALT -> when (entity) {
            null -> setOf(SubjectKind.PLAYER, SubjectKind.CREATURE)
            PLAYER_ENTITY -> setOf(SubjectKind.PLAYER)
            else -> setOf(SubjectKind.CREATURE)
        }
        RuleTrigger.PROJECTILE_HIT, RuleTrigger.DEATH, RuleTrigger.MOB_SPAWN -> setOf(SubjectKind.CREATURE)
        else -> emptySet()
    }

    /**
     * [args] of [tool] with its target filled for [aim]; null when [tool] cannot act on it, and the effect is
     * skipped (a creature for `apply_effect`). For a tool of [Aiming.PLACE], give a creature as the [Aim.Point]
     * where it stands. `notify` gets [args] as they are: its `fact` is the engine's to add.
     */
    fun aimed(tool: String, args: JsonObject, aim: Aim): JsonObject? {
        val aiming = ALLOWED[tool] ?: return null
        val target: Map<String, JsonElement> = when (aiming) {
            Aiming.AUDIENCE -> when (aim) {
                is Aim.Player -> mapOf("to" to JsonArray(listOf(JsonPrimitive(aim.name))))
                Aim.Everyone -> emptyMap()
                else -> return null
            }
            Aiming.PLACE -> when (aim) {
                is Aim.Player -> mapOf("player" to JsonPrimitive(aim.name))
                is Aim.Point -> mapOf("x" to JsonPrimitive(aim.x), "y" to JsonPrimitive(aim.y), "z" to JsonPrimitive(aim.z), "dimension" to JsonPrimitive(aim.dimension))
                else -> return null
            }
            Aiming.PLAYERS -> mapOf("players" to JsonArray(listOf(JsonPrimitive((aim as? Aim.Player ?: return null).name))))
            Aiming.PLAYER -> mapOf("player" to JsonPrimitive((aim as? Aim.Player ?: return null).name))
            Aiming.NEAR -> mapOf("near" to JsonPrimitive((aim as? Aim.Player ?: return null).name))
            Aiming.CREATURE -> mapOf("creature" to JsonPrimitive((aim as? Aim.Creature ?: return null).uuid.toString()))
            Aiming.NEWS -> emptyMap()
        }
        return JsonObject(args + target)
    }

    /** [args] with `{actor}` in each of its texts replaced by [name] (M4 §8.1). */
    fun withActor(args: JsonObject, name: String): JsonObject = JsonObject(args.mapValues { (_, value) -> withActor(value, name) })

    private fun withActor(value: JsonElement, name: String): JsonElement = when (value) {
        is JsonObject -> withActor(value, name)
        is JsonArray -> JsonArray(value.map { withActor(it, name) })
        is JsonPrimitive -> if (value.isString && ACTOR in value.content) JsonPrimitive(value.content.replace(ACTOR, name)) else value
    }

    /** The player's name in a text of an effect. */
    const val ACTOR = "{actor}"
}

/** A rule that passed the validation: its specification with its ids in full, and the players its conditions name. */
data class ValidRule(val spec: RuleSpec, val players: List<PlayerRef>)

/**
 * Validates a rule when it is created (M4 §8.5): see the steps above. [actions] runs the tools' dry checks;
 * [ids] knows the server's ids. Throws [MechanicRejected] with the fix; the Journal's own exceptions when the
 * players cannot be read.
 */
class RuleValidator(private val services: NarratorServices, private val actions: EngineActions, private val ids: RuleIds) {
    /** [spec], valid for [caller] (the Director), with its ids in full and its players' current names; or a refusal. */
    suspend fun validate(spec: RuleSpec, caller: Caller): ValidRule {
        decode(spec)
        val conditions = conditions(spec.trigger, spec.conditions)
        val players = players(conditions.players, caller)
        spec.effects.forEachIndexed { index, effect -> effect(index + 1, effect, spec.trigger, conditions, caller) }
        return ValidRule(spec.copy(conditions = conditions.copy(players = players.map { it.name }.ifEmpty { null })), players)
    }

    /** Step 1: the bounds of the specification, as `create_rule` decodes it (a specification built by the code too). */
    private fun decode(spec: RuleSpec) {
        val decoded = ToolArgs.decode(RuleSpec.serializer(), ToolArgs.json.encodeToString(RuleSpec.serializer(), spec))
        if (decoded is ToolArgs.Decoded.Invalid) throw MechanicRejected(null, decoded.problem)
    }

    /** Step 2: each condition applies to [trigger], and its ids are known; the conditions with their ids in full. */
    private fun conditions(trigger: RuleTrigger, given: RuleConditions): RuleConditions {
        for (field in FIELDS) {
            if (field.given(given) && trigger !in field.triggers) {
                val where = RuleTrigger.entries.filter { it in field.triggers }.joinToString { it.id }
                reject("when.${field.name} does not apply to ${trigger.id}; it applies to $where")
            }
        }
        if (trigger == RuleTrigger.MOB_SPAWN && given.entity == null) reject("mob_spawn needs when.entity: the creature it changes (an id or a #tag)")
        if (trigger == RuleTrigger.POSITION && POSITION_FIELDS.none { it.given(given) }) {
            reject("position needs one of when.y_below, y_above, near, dimension, structure or biome: where the players must be")
        }
        if (given.yAbove != null && given.yBelow != null && given.yAbove > given.yBelow) {
            reject("when.y_above (${given.yAbove}) is above when.y_below (${given.yBelow}): nothing is ever at both")
        }
        given.words?.firstOrNull { word -> word.none { it.isLetterOrDigit() } }?.let { reject("when.words: \"$it\" has no letter nor digit: write words") }
        val entity = given.entity?.let { id("when.entity", RuleIdKind.ENTITY, it) }
        if (entity == RuleTargets.PLAYER_ENTITY && trigger != RuleTrigger.DAMAGE_DEALT) {
            reject(
                "when.entity player never matches ${trigger.id}: its creature is never a player, and a fight between players is a duel's, " +
                    "never a rule's (only damage_dealt sees a player hurt)",
            )
        }
        return given.copy(
            entity = entity,
            block = given.block?.let { id("when.block", RuleIdKind.BLOCK, it) },
            item = given.item?.let { id("when.item", RuleIdKind.ITEM, it) },
            dimension = given.dimension?.let { id("when.dimension", RuleIdKind.DIMENSION, it) },
            structure = given.structure?.let { id("when.structure", RuleIdKind.STRUCTURE, it) },
            biome = given.biome?.let { id("when.biome", RuleIdKind.BIOME, it) },
            advancement = given.advancement?.let { id("when.advancement", RuleIdKind.ADVANCEMENT, it) },
            near = given.near?.let { near -> near.copy(dimension = near.dimension?.let { id("when.near.dimension", RuleIdKind.DIMENSION, it) }) },
        )
    }

    /** The full id [raw] names as [field]; a refusal with the closest ids (or tags) otherwise. */
    private fun id(field: String, kind: RuleIdKind, raw: String): String {
        ids.resolve(kind, raw)?.let { return it }
        val text = raw.trim()
        val tag = kind.tags && text.startsWith("#")
        val pool = ids.known(kind).filter { it.startsWith("#") == tag }.map { it.removePrefix("#") }
        val closest = closestIds(text.removePrefix("#"), pool).map { if (tag) "#$it" else it }
        reject("$field \"$text\" is unknown; closest ${if (tag) "tags" else "ids"}: ${closest.joinToString()}")
    }

    /** Step 2: the players [names], as the Journal knows them (online or not). */
    private suspend fun players(names: List<String>?, caller: Caller): List<PlayerRef> {
        if (names == null) return emptyList()
        return try {
            ToolContext(services, caller, services.clock, PLAYERS_TIMEOUT_MS).knownPlayers(names, "when.players")
        } catch (e: Refusal) {
            throw rejected(null, e.result)
        }
    }

    /** Step 3: effect number [index] of a rule set off by [trigger] under [conditions]. */
    private suspend fun effect(index: Int, effect: RuleEffect, trigger: RuleTrigger, conditions: RuleConditions, caller: Caller) {
        val aiming = RuleTargets.ALLOWED[effect.tool]
            ?: reject("effect $index: ${effect.tool} cannot be used in a rule; allowed: ${RuleTargets.ALLOWED.keys.joinToString()}")
        val prefix = "effect $index (${effect.tool})"
        if (trigger == RuleTrigger.MOB_SPAWN && aiming != Aiming.CREATURE) reject("$prefix: mob_spawn rules may only use mob_effect and mob_attribute")
        if (effect.at !in aiming.targets) {
            reject("$prefix: at ${effect.at.id} is not allowed; ${effect.tool} takes at ${or(EffectTarget.entries.filter { it in aiming.targets }.map { it.id })}")
        }
        val subjects = RuleTargets.subjects(trigger, conditions.entity)
        if (effect.at == EffectTarget.SUBJECT) subject(prefix, effect.tool, aiming, trigger, conditions, subjects)
        val given = aiming.fields.filter { it in effect.args }
        if (given.isNotEmpty()) {
            val why = when {
                aiming == Aiming.NEWS -> "the engine writes what happened"
                effect.at == EffectTarget.POSITION -> "at (position) says where"
                else -> "at (${effect.at.id}) says who"
            }
            reject("$prefix: leave ${given.joinToString()} out: $why")
        }
        if (aiming == Aiming.PLAYERS) wounding(prefix, effect.args)
        val args = RuleTargets.withActor(effect.args, STAND_IN_PLAYER)
        for (aim in standIns(effect.at, aiming, subjects, conditions)) {
            val filled = RuleTargets.aimed(effect.tool, args, aim) ?: continue
            actions.check(effect.tool, filled, caller)?.let { throw rejected(prefix, it) }
        }
    }

    /** `at subject`: [trigger] has a subject that [aiming]'s tool acts on. */
    private fun subject(prefix: String, tool: String, aiming: Aiming, trigger: RuleTrigger, conditions: RuleConditions, subjects: Set<SubjectKind>) {
        if (subjects.isEmpty()) {
            val with = RuleTrigger.entries.filter { RuleTargets.subjects(it, null).isNotEmpty() }.joinToString { it.id }
            reject("$prefix: at subject: ${trigger.id} has no subject; these triggers have one: $with")
        }
        if (subjects.none { it in aiming.subjects }) {
            val entity = conditions.entity?.let { " (when.entity ${short(it)})" }.orEmpty()
            if (SubjectKind.PLAYER in aiming.subjects) {
                reject("$prefix: at subject is a creature for ${trigger.id}$entity, and $tool acts on players; mob_effect and mob_attribute act on the creature")
            }
            reject("$prefix: at subject is a player for ${trigger.id}$entity, and $tool acts on creatures")
        }
    }

    /** `apply_effect` in a rule never gives an effect that wounds: the Director gives it himself, call by call. */
    private fun wounding(prefix: String, args: JsonObject) {
        val raw = (args["effect"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return
        val id = parseId(raw)?.toString() ?: return
        if (id in WOUNDING) {
            throw MechanicRejected(
                MobEffectTool.FORBIDDEN_EFFECT,
                "$prefix: a rule never gives ${short(id)}: an effect that wounds is yours to give, one call at a time, never a law's",
            )
        }
    }

    /** The stand-in targets of an effect at [at]: one per kind of target it may get when the rule fires. */
    private fun standIns(at: EffectTarget, aiming: Aiming, subjects: Set<SubjectKind>, conditions: RuleConditions): List<Aim> {
        val point = Aim.Point(0, STAND_IN_Y, 0, ToolText.dimension(conditions.dimension ?: conditions.near?.dimension ?: OVERWORLD))
        return when (at) {
            EffectTarget.ACTOR -> listOf(Aim.Player(STAND_IN_PLAYER))
            EffectTarget.POSITION -> listOf(point)
            EffectTarget.EVERYONE -> listOf(Aim.Everyone)
            EffectTarget.SUBJECT -> subjects.filter { it in aiming.subjects }.map { kind ->
                when {
                    kind == SubjectKind.PLAYER -> Aim.Player(STAND_IN_PLAYER)
                    aiming == Aiming.CREATURE -> Aim.Creature(STAND_IN_CREATURE)
                    else -> point
                }
            }
        }
    }

    private companion object {
        /** A condition, and the triggers it applies to (M4 §8.2). */
        class Field(val name: String, val triggers: Set<RuleTrigger>, val given: (RuleConditions) -> Boolean)

        /** Triggers with a player ([EffectTarget.ACTOR]): every one but `mob_spawn`. */
        val ACTORS = RuleTrigger.entries.toSet() - RuleTrigger.MOB_SPAWN

        /** Triggers whose place counts: `dimension`, `near`, `y_below`, `y_above` (M4 §8.2, « lieu »). */
        val PLACES = setOf(
            RuleTrigger.BLOCK_BREAK, RuleTrigger.BLOCK_PLACE, RuleTrigger.KILL, RuleTrigger.DAMAGE_DEALT, RuleTrigger.PROJECTILE_HIT,
            RuleTrigger.DEATH, RuleTrigger.CHAT, RuleTrigger.SLEEP, RuleTrigger.POSITION,
        )

        val FIELDS = listOf(
            Field("players", ACTORS) { it.players != null },
            Field("entity", setOf(RuleTrigger.KILL, RuleTrigger.DAMAGE_DEALT, RuleTrigger.PROJECTILE_HIT, RuleTrigger.DEATH, RuleTrigger.MOB_SPAWN)) { it.entity != null },
            Field("block", setOf(RuleTrigger.BLOCK_BREAK, RuleTrigger.BLOCK_PLACE)) { it.block != null },
            Field("item", setOf(RuleTrigger.CRAFT, RuleTrigger.CONSUME)) { it.item != null },
            Field("grief", setOf(RuleTrigger.BLOCK_BREAK)) { it.grief != null },
            Field("dimension", PLACES + RuleTrigger.DIMENSION_CHANGE + RuleTrigger.MOB_SPAWN) { it.dimension != null },
            Field("structure", setOf(RuleTrigger.STRUCTURE_ENTER, RuleTrigger.POSITION)) { it.structure != null },
            Field("biome", setOf(RuleTrigger.BIOME_ENTER, RuleTrigger.POSITION)) { it.biome != null },
            Field("advancement", setOf(RuleTrigger.ADVANCEMENT)) { it.advancement != null },
            Field("y_below", PLACES) { it.yBelow != null },
            Field("y_above", PLACES) { it.yAbove != null },
            Field("near", PLACES + RuleTrigger.MOB_SPAWN) { it.near != null },
            Field("words", setOf(RuleTrigger.CHAT)) { it.words != null },
        )

        /** The conditions of which `position` needs one. */
        val POSITION_FIELDS = FIELDS.filter { it.name in setOf("y_below", "y_above", "near", "dimension", "structure", "biome") }

        /** Effects `apply_effect` never gives in a rule: they wound (the death guard would be a rule's only brake). */
        val WOUNDING = setOf("minecraft:wither", "minecraft:poison", "minecraft:instant_damage")

        /**
         * The stand-in player of the checks: as long as the longest name, so that a text with `{actor}` that fits
         * once filled with any name fits here.
         */
        const val STAND_IN_PLAYER = "RuleTargetPlayer"
        val STAND_IN_CREATURE = UUID(0, 0)
        const val STAND_IN_Y = 64
        const val OVERWORLD = "minecraft:overworld"

        /** The Journal's read of the players named, at most. */
        const val PLAYERS_TIMEOUT_MS = 5_000L

        private val DENIED = Regex("""DENIED \(([^)]+)\): (.*)""", RegexOption.DOT_MATCHES_ALL)

        fun reject(message: String): Nothing = throw MechanicRejected(null, message)

        /** [result], a refusal of a tool, as a refusal of the rule, its message after [prefix]. */
        fun rejected(prefix: String?, result: ToolResult): MechanicRejected {
            val denied = DENIED.matchEntire(result.text).takeIf { result.status == ToolStatus.DENIED }
            val message = denied?.groupValues?.get(2) ?: result.text.removePrefix("Invalid arguments: ").removePrefix("FAILED: ")
            return MechanicRejected(denied?.groupValues?.get(1), prefix?.let { "$it: $message" } ?: message)
        }

        fun short(id: String): String = id.removePrefix("#").removePrefix("minecraft:").let { if (id.startsWith("#")) "#$it" else it }

        /** `actor`, `actor or subject`, `actor, subject or position`. */
        fun or(words: List<String>): String = if (words.size < 2) words.joinToString() else words.dropLast(1).joinToString() + " or " + words.last()
    }
}
