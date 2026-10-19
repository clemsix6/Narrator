package io.github.clemsix6.narrator2.forge.sdk

import io.github.clemsix6.narrator2.event.AdvancementEarned
import io.github.clemsix6.narrator2.event.BiomeEntered
import io.github.clemsix6.narrator2.event.BlockBroken
import io.github.clemsix6.narrator2.event.BlockPlaced
import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.event.ChatMessage
import io.github.clemsix6.narrator2.event.DamageDealt
import io.github.clemsix6.narrator2.event.DamageTaken
import io.github.clemsix6.narrator2.event.DimensionChanged
import io.github.clemsix6.narrator2.event.EntityKilled
import io.github.clemsix6.narrator2.event.EntityRef
import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.event.ItemConsumed
import io.github.clemsix6.narrator2.event.ItemCrafted
import io.github.clemsix6.narrator2.event.PlaceKind
import io.github.clemsix6.narrator2.event.PlayerDied
import io.github.clemsix6.narrator2.event.PlayerJoined
import io.github.clemsix6.narrator2.event.PlayerLeft
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.event.ProjectileHit
import io.github.clemsix6.narrator2.event.SleepStarted
import io.github.clemsix6.narrator2.event.StructureEntered
import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.forge.api.GameFact
import io.github.clemsix6.narrator2.forge.api.Place
import io.github.clemsix6.narrator2.forge.api.Who
import io.github.clemsix6.narrator2.guard.NarratorMob
import io.github.clemsix6.narrator2.guard.NarratorMobs
import io.github.clemsix6.narrator2.observer.PlayerStates
import io.github.clemsix6.narrator2.observer.of
import io.github.clemsix6.narrator2.observer.toPos
import io.github.clemsix6.narrator2.tool.ToolText
import net.fabricmc.fabric.api.entity.FakePlayer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.OwnableEntity
import kotlin.reflect.KClass

/**
 * The one translator of the game's facts into the SDK's (M5 §4.3; design review, finding 11): the facts of the bus
 * ([of]), a wild creature that appears ([appeared]) and one of a module's creatures that is gone ([gone]). Nothing
 * else in the mod builds a [GameFact] out of the bus (`FactsTest` reads the bytecode to be sure).
 *
 * **Never a fact from a prayer, a whisper or a private message**: a [ChatMessage] is a [GameFact.Chat] on the public
 * channel only, and no other fact carries what a player wrote (a command is no fact at all). Ids are written as the
 * SDK writes them, without `minecraft:` (`Sdk`); [GameFact.at] is the middle of the floor of the fact's block
 * ([place]). [of] is pure, cheap and any thread (the bus calls it inline); [appeared] and [gone] read live entities,
 * on the server thread.
 */
object Facts {
    /** The facts of the bus that [of] translates: what the engine's part of the modules listens to (`listensTo`). */
    val EVENTS: Set<KClass<out GameEvent>> = setOf(
        BlockBroken::class, BlockPlaced::class, DamageDealt::class, DamageTaken::class, EntityKilled::class, ProjectileHit::class,
        PlayerDied::class, ChatMessage::class, DimensionChanged::class, StructureEntered::class, BiomeEntered::class,
        AdvancementEarned::class, ItemCrafted::class, ItemConsumed::class, SleepStarted::class, PlayerJoined::class, PlayerLeft::class,
    )

    /** The SDK's records of [GameFact]: the only types `Events.on` takes. */
    val TYPES: Set<Class<out GameFact>> = GameFact::class.java.permittedSubclasses.map { it.asSubclass(GameFact::class.java) }.toSet()

    /** [GameFact.CreatureGone.why]: it died, it vanished (its lifetime ran out, or it was removed), its chunk unloaded. */
    const val DIED = "died"
    const val VANISHED = "vanished"
    const val UNLOADED = "unloaded"

    /**
     * [event] as a module sees it; null for what no module may see: a fluid poured or a fire lit, a kill between
     * players (a [GameFact.PlayerDied] tells it), **a prayer or a private message** ([ChatChannel.PRAY],
     * [ChatChannel.WHISPER]) and every fact of the bus that is not in [EVENTS]. A chat line's [GameFact.at] is where
     * its writer stood when [players] last saw them (a [ChatMessage] has no position); null when unknown.
     */
    fun of(event: GameEvent, players: PlayerStates): GameFact? = when (event) {
        is BlockBroken -> GameFact.BlockBroken(
            name(event.player), place(event.pos), short(event.block), event.placedBy != null && event.placedBy != event.player.uuid,
        )
        is BlockPlaced -> if (event.kind != PlaceKind.BLOCK) null else GameFact.BlockPlaced(name(event.player), place(event.pos), short(event.block))
        is DamageDealt -> if (isPlayer(event.victim)) {
            GameFact.PlayerHit(name(event.player), place(event.pos), event.victim.player?.name ?: event.victim.name, event.amount.toFloat())
        } else {
            GameFact.CreatureHit(name(event.player), place(event.pos), who(event.victim), event.amount.toFloat(), event.victimHealth.toFloat())
        }
        is DamageTaken -> GameFact.PlayerHurt(name(event.player), place(event.pos), short(event.damageType), event.attacker?.let(::who), event.amount.toFloat())
        is EntityKilled -> if (event.pvp) null else GameFact.CreatureKilled(name(event.player), place(event.pos), who(event.victim))
        is ProjectileHit -> GameFact.ProjectileHit(
            name(event.player), place(event.pos), short(event.projectile), event.shooter?.let(::who), event.target?.let(::who),
        )
        is PlayerDied -> GameFact.PlayerDied(name(event.player), place(event.pos), short(event.cause), event.killer?.let(::who))
        is ChatMessage -> if (event.channel != ChatChannel.PUBLIC) null else {
            GameFact.Chat(name(event.player), players.get(event.player.uuid)?.pos?.let(::place), event.text)
        }
        is DimensionChanged -> GameFact.DimensionChanged(name(event.player), place(event.pos), ToolText.dimension(event.from.dim), ToolText.dimension(event.pos.dim))
        is StructureEntered -> GameFact.StructureEntered(name(event.player), place(event.pos), short(event.structure))
        is BiomeEntered -> GameFact.BiomeEntered(name(event.player), place(event.pos), short(event.biome))
        is AdvancementEarned -> GameFact.Advancement(name(event.player), place(event.pos), short(event.advancement))
        is ItemCrafted -> GameFact.Crafted(name(event.player), place(event.pos), short(event.item), event.count)
        is ItemConsumed -> GameFact.Consumed(name(event.player), place(event.pos), short(event.item))
        is SleepStarted -> GameFact.Slept(name(event.player), place(event.pos))
        is PlayerJoined -> GameFact.Joined(name(event.player), place(event.pos))
        is PlayerLeft -> GameFact.Left(name(event.player), place(event.pos))
        else -> null
    }

    /**
     * [mob], which just appeared or loaded in [level], as a [GameFact.CreatureAppeared]: only a wild one (not the
     * Narrator's, not tamed, not named) within `ForgeLimits.APPEAR_DISTANCE` blocks of an online player (not a
     * spectator), the nearest being the fact's player; null otherwise. Server thread (the engine's `onMobLoad`).
     */
    fun appeared(mob: Mob, level: ServerLevel): GameFact.CreatureAppeared? {
        if (NarratorMobs.isNarrators(mob) || mob.hasAttached(NarratorMob.TYPE) || mob.hasCustomName()) return null
        if ((mob as? OwnableEntity)?.ownerReference != null) return null
        val reach = ForgeLimits.APPEAR_DISTANCE.toDouble() * ForgeLimits.APPEAR_DISTANCE
        val nearest = level.players()
            .filter { it !is FakePlayer && !it.isSpectator && it.isAlive }
            .map { it to it.distanceToSqr(mob) }
            .filter { it.second <= reach }
            .minByOrNull { it.second }?.first
            ?: return null
        return GameFact.CreatureAppeared(nearest.plainTextName, place(mob.blockPosition().toPos(level)), who(EntityRef.of(mob)))
    }

    /**
     * [entity], one of a module's creatures, as a [GameFact.CreatureGone] (its player null): [UNLOADED] when its chunk
     * unloaded it, [DIED] when it died, [VANISHED] otherwise (its lifetime ran out, it was discarded, it left its
     * dimension). Where it was last. Server thread (the engine's check of each second).
     */
    fun gone(entity: Entity): GameFact.CreatureGone {
        val why = when (entity.removalReason) {
            Entity.RemovalReason.UNLOADED_TO_CHUNK, Entity.RemovalReason.UNLOADED_WITH_PLAYER -> UNLOADED
            Entity.RemovalReason.KILLED -> DIED
            else -> if ((entity as? LivingEntity)?.isDeadOrDying == true) DIED else VANISHED
        }
        return GameFact.CreatureGone(null, place(entity.blockPosition().toPos(entity.level())), who(EntityRef.of(entity)), why)
    }

    /** [ref] as the SDK names someone: `player` and their name, or the creature's short type id and custom name. */
    fun who(ref: EntityRef): Who =
        if (isPlayer(ref)) Who(PLAYER, ref.uuid?.toString(), ref.player?.name ?: ref.name) else Who(short(ref.type), ref.uuid?.toString(), ref.name)

    /** The middle of the floor of the block [pos], its dimension in the SDK's form. */
    fun place(pos: Pos): Place = Place(pos.dim, pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5)

    private const val PLAYER = "player"

    private fun isPlayer(ref: EntityRef): Boolean = ref.player != null || ref.type == "minecraft:$PLAYER"

    private fun name(player: PlayerRef): String = player.name

    /** An id as the SDK writes it: `diamond_ore`, `story/mine_stone`; another mod's keeps its namespace. */
    private fun short(id: String): String = id.removePrefix("minecraft:")
}
