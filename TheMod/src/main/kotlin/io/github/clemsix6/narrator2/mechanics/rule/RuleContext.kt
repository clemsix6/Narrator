package io.github.clemsix6.narrator2.mechanics.rule

import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.event.AdvancementEarned
import io.github.clemsix6.narrator2.event.BiomeEntered
import io.github.clemsix6.narrator2.event.BlockBroken
import io.github.clemsix6.narrator2.event.BlockPlaced
import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.event.ChatMessage
import io.github.clemsix6.narrator2.event.DamageDealt
import io.github.clemsix6.narrator2.event.DimensionChanged
import io.github.clemsix6.narrator2.event.EntityKilled
import io.github.clemsix6.narrator2.event.EntityRef
import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.event.ItemConsumed
import io.github.clemsix6.narrator2.event.ItemCrafted
import io.github.clemsix6.narrator2.event.PlaceKind
import io.github.clemsix6.narrator2.event.PlayerDied
import io.github.clemsix6.narrator2.event.PlayerJoined
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.event.ProjectileHit
import io.github.clemsix6.narrator2.event.SleepStarted
import io.github.clemsix6.narrator2.event.StructureEntered
import io.github.clemsix6.narrator2.mechanics.MechanicsLimits
import io.github.clemsix6.narrator2.mechanics.RuleConditions
import io.github.clemsix6.narrator2.mechanics.RuleState
import io.github.clemsix6.narrator2.mechanics.RuleTrigger
import io.github.clemsix6.narrator2.mechanics.challenge.Answers
import io.github.clemsix6.narrator2.observer.PlayerStates
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.tool.action.parseId
import net.minecraft.core.Registry
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.tags.TagKey
import java.util.UUID
import kotlin.random.Random
import kotlin.reflect.KClass

/*
 * What a fact is to the rules, and whether a rule matches it (M4 §8.2, §8.3, §8.6), pure: the rule engine
 * ([RuleEngine]) feeds it the facts of the bus, the players' positions each second and the creatures that
 * appear, then fires what matches within the limits.
 *
 * - [RuleFact]: a fact with its actor (the player of the trigger), its subject (its creature, or the player
 *   hurt), its position, and what its conditions read (creature, block, item, grief, structure, biome,
 *   advancement, chat text); [RuleFacts] builds it from a fact of the bus.
 * - [RuleMatcher]: a rule's conditions against a fact, its ids and tags resolved once ([RuleTags]).
 * - [RuleLimits]: one firing per rule and per actor every 3 s, 30 per rule in 10 min, 200 a minute for
 *   `mob_spawn`.
 * - [PositionEdges]: the rising edge of a `position` rule, per player.
 */

/** The subject of a fact (M4 §8.2): the player hurt, or a creature (the victim, the shooter, the killer, the new one). */
sealed interface RuleSubject {
    data class Player(val ref: PlayerRef) : RuleSubject

    /** [type]: its full id, `minecraft:cow`. */
    data class Creature(val uuid: UUID, val type: String) : RuleSubject
}

/**
 * A fact as the rules see it (M4 §8.2).
 *
 * @property what what happened, English, names escaped, never a chat text: the act's decision, the `fact` of a
 *   `notify` (`Bartok entered the_nether at -12 70 40`).
 * @property actor the player of the trigger; null for `mob_spawn`.
 * @property position where it happened (a block), dimension in full; null when unknown (a chat whose writer the
 *   sampler has not seen yet).
 * @property creature the full id of the trigger's creature, for `when.entity`: the victim, the shooter, the
 *   killer, the new creature.
 * @property grief `block_break`: a block another player placed.
 * @property text `chat`: what was written, for `when.words`; never logged nor shown.
 */
data class RuleFact(
    val trigger: RuleTrigger,
    val what: String,
    val actor: PlayerRef? = null,
    val subject: RuleSubject? = null,
    val position: Pos? = null,
    val creature: String? = null,
    val block: String? = null,
    val item: String? = null,
    val grief: Boolean? = null,
    val structure: String? = null,
    val biome: String? = null,
    val advancement: String? = null,
    val text: String? = null,
)

/** The facts of the bus as the rules see them (M4 §8.2). Pure, any thread. */
object RuleFacts {
    /** The facts of the bus the rules listen to: every trigger but `position` and `mob_spawn`, the engine's own. */
    val EVENTS: Set<KClass<out GameEvent>> = setOf(
        BlockBroken::class, BlockPlaced::class, EntityKilled::class, DamageDealt::class, ProjectileHit::class, PlayerDied::class,
        ChatMessage::class, DimensionChanged::class, StructureEntered::class, BiomeEntered::class, AdvancementEarned::class,
        ItemCrafted::class, ItemConsumed::class, SleepStarted::class, PlayerJoined::class,
    )

    /** The trigger [event] may set off, before it is looked at closer ([of] may still say no). */
    fun triggerOf(event: GameEvent): RuleTrigger? = when (event) {
        is BlockBroken -> RuleTrigger.BLOCK_BREAK
        is BlockPlaced -> RuleTrigger.BLOCK_PLACE
        is EntityKilled -> RuleTrigger.KILL
        is DamageDealt -> RuleTrigger.DAMAGE_DEALT
        is ProjectileHit -> RuleTrigger.PROJECTILE_HIT
        is PlayerDied -> RuleTrigger.DEATH
        is ChatMessage -> RuleTrigger.CHAT
        is DimensionChanged -> RuleTrigger.DIMENSION_CHANGE
        is StructureEntered -> RuleTrigger.STRUCTURE_ENTER
        is BiomeEntered -> RuleTrigger.BIOME_ENTER
        is AdvancementEarned -> RuleTrigger.ADVANCEMENT
        is ItemCrafted -> RuleTrigger.CRAFT
        is ItemConsumed -> RuleTrigger.CONSUME
        is SleepStarted -> RuleTrigger.SLEEP
        is PlayerJoined -> RuleTrigger.JOIN
        else -> null
    }

    /**
     * [event] as a rule fact; null for what no rule sees: a fluid poured or a fire lit, a kill in PvP (a fight
     * between players is a duel's), a prayer or a whisper. A chat's position is its writer's, read in [players]
     * (`ChatMessage` has none).
     */
    fun of(event: GameEvent, players: PlayerStates): RuleFact? = when (event) {
        is BlockBroken -> {
            val grief = event.placedBy != null && event.placedBy != event.player.uuid
            RuleFact(
                RuleTrigger.BLOCK_BREAK, "${name(event.player)} broke ${short(event.block)}${if (grief) " that another player placed" else ""} ${at(event.pos)}",
                actor = event.player, position = event.pos, block = event.block, grief = grief,
            )
        }
        is BlockPlaced -> if (event.kind != PlaceKind.BLOCK) null else RuleFact(
            RuleTrigger.BLOCK_PLACE, "${name(event.player)} placed ${short(event.block)} ${at(event.pos)}",
            actor = event.player, position = event.pos, block = event.block,
        )
        is EntityKilled -> if (event.pvp) null else RuleFact(
            RuleTrigger.KILL, "${name(event.player)} killed ${creature(event.victim)} ${at(event.pos)}",
            actor = event.player, position = event.pos, creature = event.victim.type,
        )
        is DamageDealt -> RuleFact(
            RuleTrigger.DAMAGE_DEALT, "${name(event.player)} hit ${creature(event.victim)} ${at(event.pos)}",
            actor = event.player, subject = subject(event.victim), position = event.pos, creature = event.victim.type,
        )
        is ProjectileHit -> {
            val shooter = event.shooter?.takeIf { it.player == null }
            val hit = event.target?.let { if (it.player?.uuid == event.player.uuid) name(event.player) else creature(it) }
            val by = shooter?.let { "${creature(it)}'s ${short(event.projectile)}" } ?: "${name(event.player)}'s ${short(event.projectile)}"
            RuleFact(
                RuleTrigger.PROJECTILE_HIT, "$by hit ${hit ?: "the ground"} ${at(event.pos)}",
                actor = event.player, subject = shooter?.let(::subject), position = event.pos, creature = shooter?.type,
            )
        }
        is PlayerDied -> {
            val killer = event.killer?.takeIf { it.player == null }
            RuleFact(
                RuleTrigger.DEATH, "${name(event.player)} died${killer?.let { " killed by ${creature(it)}" } ?: " (${short(event.cause)})"} ${at(event.pos)}",
                actor = event.player, subject = killer?.let(::subject), position = event.pos, creature = killer?.type,
            )
        }
        is ChatMessage -> if (event.channel != ChatChannel.PUBLIC) null else RuleFact(
            RuleTrigger.CHAT, "${name(event.player)} spoke in the public chat",
            actor = event.player, position = players.get(event.player.uuid)?.pos, text = event.text,
        )
        is DimensionChanged -> RuleFact(
            RuleTrigger.DIMENSION_CHANGE, "${name(event.player)} entered ${ToolText.dimension(event.pos.dim)} (${event.pos.x} ${event.pos.y} ${event.pos.z})",
            actor = event.player, position = event.pos,
        )
        is StructureEntered -> RuleFact(
            RuleTrigger.STRUCTURE_ENTER, "${name(event.player)} entered ${short(event.structure)} ${at(event.pos)}",
            actor = event.player, position = event.pos, structure = event.structure,
        )
        is BiomeEntered -> RuleFact(
            RuleTrigger.BIOME_ENTER, "${name(event.player)} entered ${short(event.biome)} ${at(event.pos)}",
            actor = event.player, position = event.pos, biome = event.biome,
        )
        is AdvancementEarned -> RuleFact(
            RuleTrigger.ADVANCEMENT, "${name(event.player)} earned the advancement ${short(event.advancement)}",
            actor = event.player, position = event.pos, advancement = event.advancement,
        )
        is ItemCrafted -> RuleFact(
            RuleTrigger.CRAFT, "${name(event.player)} crafted ${event.count}x ${short(event.item)}",
            actor = event.player, position = event.pos, item = event.item,
        )
        is ItemConsumed -> RuleFact(
            RuleTrigger.CONSUME, "${name(event.player)} consumed ${short(event.item)}",
            actor = event.player, position = event.pos, item = event.item,
        )
        is SleepStarted -> RuleFact(RuleTrigger.SLEEP, "${name(event.player)} went to bed ${at(event.pos)}", actor = event.player, position = event.pos)
        is PlayerJoined -> RuleFact(RuleTrigger.JOIN, "${name(event.player)} joined ${at(event.pos)}", actor = event.player, position = event.pos)
        else -> null
    }

    /** A player online where a `position` rule may watch: [pos], in [structure] and [biome] (full ids, or null). */
    fun position(player: PlayerRef, pos: Pos, structure: String?, biome: String?): RuleFact = RuleFact(
        RuleTrigger.POSITION, "${name(player)} came ${at(pos)}${structure?.let { " in ${short(it)}" }.orEmpty()}",
        actor = player, position = pos, structure = structure, biome = biome,
    )

    /** A creature [uuid] of [type] that appeared or loaded at [pos] (`mob_spawn`). */
    fun spawned(uuid: UUID, type: String, pos: Pos): RuleFact =
        RuleFact(RuleTrigger.MOB_SPAWN, "a ${short(type)} appeared ${at(pos)}", subject = RuleSubject.Creature(uuid, type), position = pos, creature = type)

    /** `at 12 -50 30`, then ` in the_nether` out of the overworld. */
    fun at(pos: Pos): String = "at ${pos.x} ${pos.y} ${pos.z}" + if (pos.dim == OVERWORLD) "" else " in ${ToolText.dimension(pos.dim)}"

    private fun subject(entity: EntityRef): RuleSubject? = entity.player?.let { RuleSubject.Player(it) }
        ?: entity.uuid?.let { RuleSubject.Creature(it, entity.type) }

    private fun name(player: PlayerRef): String = ModelText.name(player.name)

    /** `a cow`, `Mimosa`, `a zombie named « Gérard »`. */
    private fun creature(entity: EntityRef): String = entity.player?.let { name(it) }
        ?: ("a ${short(entity.type)}" + (entity.name?.let { " named « ${ModelText.escape(ModelText.cut(it, NAME_MAX))} »" } ?: ""))

    private fun short(id: String): String = id.removePrefix("minecraft:")

    private const val OVERWORLD = "minecraft:overworld"
    private const val NAME_MAX = 32
}

/**
 * The tags of the server's registries, for a rule's conditions (`#minecraft:diamond_ores`): [ServerRuleTags] in
 * the game, a fake in unit tests (they have no tags).
 */
fun interface RuleTags {
    /** The full ids [tag] (`#minecraft:skeletons`) holds among [kind]'s; empty when it is unknown. Any thread. */
    fun members(kind: RuleIdKind, tag: String): Set<String>
}

/** The tags of the running server's registries (frozen once it runs: any thread). */
class ServerRuleTags(private val services: NarratorServices) : RuleTags {
    override fun members(kind: RuleIdKind, tag: String): Set<String> {
        val id = parseId(tag.removePrefix("#")) ?: return emptySet()
        return with(services.server.registryAccess()) {
            when (kind) {
                RuleIdKind.ENTITY -> members(lookupOrThrow(Registries.ENTITY_TYPE), id)
                RuleIdKind.BLOCK -> members(lookupOrThrow(Registries.BLOCK), id)
                RuleIdKind.ITEM -> members(lookupOrThrow(Registries.ITEM), id)
                RuleIdKind.STRUCTURE -> members(lookupOrThrow(Registries.STRUCTURE), id)
                RuleIdKind.BIOME -> members(lookupOrThrow(Registries.BIOME), id)
                RuleIdKind.ADVANCEMENT, RuleIdKind.DIMENSION -> emptySet()
            }
        }
    }

    private fun <T : Any> members(registry: Registry<T>, id: Identifier): Set<String> =
        registry.getTagOrEmpty(TagKey.create(registry.key(), id)).mapTo(HashSet()) { it.registeredName }
}

/**
 * The conditions of [rule] (M4 §8.3), its ids and tags resolved once through [tags]: all the given ones must hold
 * ([matches]), then [chance] is drawn ([drawn]). The conditions were checked against the trigger and normalized
 * when the rule was created (M4-14): full ids, `#namespace:tag`, `when.players` as the UUIDs of its state. Pure,
 * any thread.
 */
class RuleMatcher(val rule: RuleState, tags: RuleTags) {
    val id: Long get() = rule.id
    val trigger: RuleTrigger get() = rule.spec.trigger
    private val conditions: RuleConditions = rule.spec.conditions
    private val players: Set<UUID>? = conditions.players?.let { rule.playerIds.map(UUID::fromString).toSet() }
    private val entity = conditions.entity?.let { ids(RuleIdKind.ENTITY, it, tags) }
    private val block = conditions.block?.let { ids(RuleIdKind.BLOCK, it, tags) }
    private val item = conditions.item?.let { ids(RuleIdKind.ITEM, it, tags) }
    private val structure = conditions.structure?.let { ids(RuleIdKind.STRUCTURE, it, tags) }
    private val biome = conditions.biome?.let { ids(RuleIdKind.BIOME, it, tags) }
    private val nearDimension = conditions.near?.let { it.dimension ?: OVERWORLD }

    /** The odds it fires once its conditions hold. */
    val chance: Double get() = conditions.chance

    /** Whether every condition given holds for [fact] (the trigger being the rule's). */
    fun matches(fact: RuleFact): Boolean {
        if (fact.trigger != trigger) return false
        val c = conditions
        if (players != null && fact.actor?.uuid !in players) return false
        if (entity != null && !entity.has(fact.creature)) return false
        if (block != null && !block.has(fact.block)) return false
        if (item != null && !item.has(fact.item)) return false
        if (c.grief != null && fact.grief != c.grief) return false
        if (structure != null && !structure.has(fact.structure)) return false
        if (biome != null && !biome.has(fact.biome)) return false
        if (c.advancement != null && fact.advancement != c.advancement) return false
        if (c.words != null && (fact.text == null || !Answers.matches(fact.text, c.words))) return false
        if (c.dimension == null && c.yBelow == null && c.yAbove == null && c.near == null) return true
        val pos = fact.position ?: return false
        if (c.dimension != null && pos.dim != c.dimension) return false
        if (c.yBelow != null && pos.y > c.yBelow) return false
        if (c.yAbove != null && pos.y < c.yAbove) return false
        val near = c.near ?: return true
        if (pos.dim != nearDimension) return false
        val dx = (pos.x - near.x).toLong()
        val dy = (pos.y - near.y).toLong()
        val dz = (pos.z - near.z).toLong()
        return dx * dx + dy * dy + dz * dz <= near.radius.toLong() * near.radius
    }

    /** Draws [chance]: true at once when it is 1. */
    fun drawn(random: Random): Boolean = chance >= 1.0 || random.nextDouble() < chance

    /** An id condition: the id itself, or the members of a tag. */
    private class Ids(private val one: String?, private val many: Set<String>) {
        fun has(id: String?): Boolean = id != null && (id == one || id in many)
    }

    private companion object {
        const val OVERWORLD = "minecraft:overworld"

        fun ids(kind: RuleIdKind, condition: String, tags: RuleTags): Ids =
            if (condition.startsWith("#")) Ids(null, tags.members(kind, condition)) else Ids(condition, emptySet())
    }
}

/**
 * How often the rules may fire (M4 §8.6): once per rule and per actor every [MechanicsLimits.RULE_ACTOR_GAP_MS],
 * [MechanicsLimits.RULE_FIRINGS_PER_WINDOW] per rule in [MechanicsLimits.RULE_WINDOW_MS];
 * [MechanicsLimits.MOB_SPAWN_FIRINGS_PER_MINUTE] a minute for `mob_spawn`, which has no actor. In memory: a restart
 * forgets them (at worst a rule fires a little more). One thread (the server's).
 */
class RuleLimits {
    private class Rule {
        val actors = HashMap<UUID, Long>()
        val window = ArrayDeque<Long>()
    }

    private val rules = HashMap<Long, Rule>()

    /** Whether rule [rule] set off by [trigger] for [actor] may fire at [now]; if so, the firing is counted. */
    fun allow(rule: Long, trigger: RuleTrigger, actor: UUID?, now: Long): Boolean {
        val limits = rules.getOrPut(rule) { Rule() }
        if (actor != null) {
            val last = limits.actors[actor]
            if (last != null && now - last in 0 until MechanicsLimits.RULE_ACTOR_GAP_MS) return false
        }
        val (span, most) = if (trigger == RuleTrigger.MOB_SPAWN) MINUTE_MS to MechanicsLimits.MOB_SPAWN_FIRINGS_PER_MINUTE
        else MechanicsLimits.RULE_WINDOW_MS to MechanicsLimits.RULE_FIRINGS_PER_WINDOW
        while (limits.window.isNotEmpty() && now - limits.window.first() >= span) limits.window.removeFirst()
        if (limits.window.size >= most) return false
        limits.window.addLast(now)
        if (actor != null) {
            if (limits.actors.size > ACTORS_KEPT) limits.actors.entries.removeIf { now - it.value >= MechanicsLimits.RULE_ACTOR_GAP_MS }
            limits.actors[actor] = now
        }
        return true
    }

    /** Forgets rule [rule] (it ended). */
    fun forget(rule: Long) {
        rules.remove(rule)
    }

    private companion object {
        const val MINUTE_MS = 60_000L

        /** Past this many actors, those whose gap is over are forgotten. */
        const val ACTORS_KEPT = 64
    }
}

/**
 * The rising edge of the `position` rules (M4 §8.2): a rule fires for a player when its place becomes true for
 * them, and is armed again once it is false. The first look at a player for a rule (the rule is new, the player
 * joined, the server started) only sets where they are: being there already never fires. One thread (the
 * server's).
 */
class PositionEdges {
    private val inside = HashMap<Long, HashMap<UUID, Boolean>>()

    /** Whether [player] just came where rule [rule] watches: [holds] now, and did not at the last look. */
    fun rose(rule: Long, player: UUID, holds: Boolean): Boolean = inside.getOrPut(rule) { HashMap() }.put(player, holds) == false && holds

    /** Forgets [player] (they joined or left): their next look sets where they are. */
    fun forgetPlayer(player: UUID) {
        inside.values.forEach { it.remove(player) }
    }

    /** Forgets rule [rule] (it ended). */
    fun forgetRule(rule: Long) {
        inside.remove(rule)
    }
}
