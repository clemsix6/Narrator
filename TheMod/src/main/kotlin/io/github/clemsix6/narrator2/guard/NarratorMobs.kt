package io.github.clemsix6.narrator2.guard

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.observer.typeId
import io.github.clemsix6.narrator2.tool.action.Teleports
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry
import net.fabricmc.fabric.api.attachment.v1.AttachmentType
import net.minecraft.core.UUIDUtil
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.phys.Vec3
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.floor

/**
 * What makes an entity one of the Narrator's creatures, saved with it (persistent entity attachment
 * `narrator2:mob`).
 *
 * @property expiresAt when it disappears, epoch milliseconds (real time: the tool says "5 minutes").
 * @property player the player it was summoned for, if any.
 * @property harmful part of a punishment.
 * @property ownSlots the equipment slots ([net.minecraft.world.entity.EquipmentSlot] names) whose items the
 *   game already kept for good (guaranteed drop) when it was marked: its own gear, never taken for a
 *   player's ([Belongings]).
 */
data class NarratorMob(val expiresAt: Long, val player: UUID?, val harmful: Boolean, val ownSlots: List<String> = emptyList()) {
    companion object {
        val CODEC: Codec<NarratorMob> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.LONG.fieldOf("expires_at").forGetter(NarratorMob::expiresAt),
                UUIDUtil.CODEC.optionalFieldOf("player").forGetter { Optional.ofNullable(it.player) },
                Codec.BOOL.optionalFieldOf("harmful", false).forGetter(NarratorMob::harmful),
                Codec.STRING.listOf().optionalFieldOf("own_slots", emptyList()).forGetter(NarratorMob::ownSlots),
            ).apply(instance) { expiresAt, player, harmful, ownSlots -> NarratorMob(expiresAt, player.orElse(null), harmful, ownSlots) }
        }

        /** Registered by `registerGuardHooks`, before any world loads. */
        val TYPE: AttachmentType<NarratorMob> =
            AttachmentRegistry.create(Identifier.fromNamespaceAndPath("narrator2", "mob")) { builder -> builder.persistent(CODEC) }
    }
}

/**
 * The Narrator's creatures (M1_M2 §10.2): tagged `narrator2`, never despawning on their own, and
 * removed when due, even after a restart (the due ones are removed as soon as their chunk loads). The
 * registry holds the creatures loaded now, fed by the entity load and unload events: the cap on living
 * creatures counts them. A creature that converts or splits hands its mark down ([inherit]); one that
 * goes ([retire]) first sets down the players riding it, then gives back what players entrusted to it
 * ([Belongings]), through [items] when its owner is online, else on the ground.
 *
 * A creature a player rides is never pulled from under them: it stays past its time while ridden,
 * [MAX_OVERTIME_MS] at most, then its riders are set down on safe ground near it, with the death guard
 * armed and no fall damage for [LANDING_GUARD_SECONDS] (the running Narrator's guards). With no safe
 * ground in reach (an ocean, the void of the End), it stays, and the landing is tried again every
 * [LANDING_RETRY_MS].
 */
class NarratorMobs(private val items: ItemLedger? = null) {
    private val log = Log.guard

    /** A creature loaded now. [type] is its entity type id. */
    private class Loaded(val entity: Entity, val type: String, val mob: NarratorMob)

    private val loaded = ConcurrentHashMap<UUID, Loaded>()

    /** The creatures kept past their time under a rider, with when their riders' landing may next be tried (epoch milliseconds). Server thread. */
    private val overtime = HashMap<UUID, Long>()

    /**
     * Makes [entity] one of the Narrator's creatures until [expiresAt] (epoch milliseconds), summoned
     * for [player]: tagged, persistent (a mob no longer despawns on its own), given its expiry, and
     * calling no reinforcements (a zombie's would be ordinary zombies, outside every rule). Call it last,
     * once the entity is equipped and `finalizeSpawn` has run, and before adding it to the level: the gear
     * it holds then is its own, and the load event registers it. Server thread.
     */
    fun mark(entity: Entity, expiresAt: Long, player: UUID?, harmful: Boolean) {
        entity.addTag(TAG)
        (entity as? Mob)?.setPersistenceRequired()
        noReinforcements(entity)
        entity.setAttached(NarratorMob.TYPE, NarratorMob(expiresAt, player, harmful, Belongings.keptSlots(entity)))
    }

    /** The Narrator's creatures loaded now. Any thread. */
    fun count(): Int = loaded.size

    /** The Narrator's creatures loaded now that were summoned for [player], by type (`4 minecraft:zombie`). Any thread. */
    fun activeOn(player: UUID): List<ActiveEffect> =
        loaded.values.filter { it.mob.player == player }.groupBy { it.type }.map { (type, mobs) ->
            ActiveEffect("mob", "${mobs.size} $type", mobs.maxOf { it.mob.expiresAt }, mobs.any { it.mob.harmful })
        }

    /** An entity was loaded (spawned, or its chunk loaded): registered when it is one of the Narrator's. Server thread. */
    fun onLoad(entity: Entity) {
        val mob = entity.getAttached(NarratorMob.TYPE) ?: return
        loaded[entity.uuid] = Loaded(entity, entity.typeId, mob)
    }

    /** An entity was unloaded (removed, or its chunk unloaded). Server thread. */
    fun onUnload(entity: Entity) {
        loaded.remove(entity.uuid)
        overtime.remove(entity.uuid)
    }

    /** Registers the Narrator's creatures already loaded (spawn chunks load before the Narrator starts). Server thread. */
    fun registerLoaded(server: MinecraftServer) {
        for (level in server.allLevels) {
            for (entity in level.allEntities) if (isNarrators(entity)) onLoad(entity)
        }
    }

    /**
     * Removes the creatures due at [now] (epoch milliseconds) and returns how many ([retire]). One that a
     * player rides stays past its time ([mayGo]). Server thread, once a second.
     */
    fun expire(now: Long): Int {
        val gone = mutableListOf<Loaded>()
        for (mob in loaded.values.filter { it.mob.expiresAt <= now }) {
            if (mob.entity.isRemoved) {
                loaded.remove(mob.entity.uuid)
                overtime.remove(mob.entity.uuid)
                continue
            }
            if (!mayGo(mob, now)) continue
            retire(mob.entity, now)
            gone += mob
        }
        if (gone.isNotEmpty()) log.info("the Narrator's creatures expired: ${gone.groupBy { it.type }.entries.joinToString { (type, mobs) -> "${mobs.size} $type" }}")
        return gone.size
    }

    /**
     * Takes [entity], one of the Narrator's creatures, out of the world at [now] (epoch milliseconds): its
     * time is up, or it went off in its explosion. The players riding it are set down on safe ground
     * first, or at least protected when there is none ([land]); then what players entrusted to it is given
     * back, and it is discarded. Server thread.
     */
    fun retire(entity: Entity, now: Long) {
        loaded.remove(entity.uuid)
        overtime.remove(entity.uuid)
        for (rider in ridersOf(entity)) land(rider, entity, safeGround(rider, entity), now)
        release(entity)
        entity.discard()
    }

    /**
     * Whether [mob], due at [now], may go: nobody rides it, or its riders were just set down on safe
     * ground. Ridden, it stays until [MAX_OVERTIME_MS] past its time; then each rider is landed when safe
     * ground is in reach, and it stays while a rider is left on it (tried again every [LANDING_RETRY_MS]).
     */
    private fun mayGo(mob: Loaded, now: Long): Boolean {
        val entity = mob.entity
        val riders = ridersOf(entity)
        if (riders.isEmpty()) {
            overtime.remove(entity.uuid)
            return true
        }
        val names = riders.joinToString { it.plainTextName }
        if (now < mob.mob.expiresAt + MAX_OVERTIME_MS) {
            if (overtime.put(entity.uuid, 0L) == null) {
                log.info("${mob.type} ${entity.uuid} stays past its time: $names rides it (${MAX_OVERTIME_MS / 60_000} min more at most)")
            }
            return false
        }
        if (now < (overtime[entity.uuid] ?: 0L)) return false
        val stranded = riders.filter { rider ->
            val spot = safeGround(rider, entity)
            if (spot != null) land(rider, entity, spot, now)
            spot == null
        }
        if (stranded.isEmpty()) return true
        overtime[entity.uuid] = now + LANDING_RETRY_MS
        log.warn(
            "${mob.type} ${entity.uuid} is ${(now - mob.mob.expiresAt) / 60_000} min past its time, but no safe ground is in reach of " +
                "${stranded.joinToString { it.plainTextName }}, who ride it: it stays, tried again in ${LANDING_RETRY_MS / 1_000} s",
        )
        return false
    }

    /**
     * Sets [player] down from [mount] at [spot] (safe ground), or lets them off where they are when there
     * is none; either way, whatever follows is on the Narrator: the death guard is armed and they take no
     * fall damage for [LANDING_GUARD_SECONDS] (a lasting protection of the same kind is kept).
     */
    private fun land(player: ServerPlayer, mount: Entity, spot: Vec3?, now: Long) {
        val guards = NarratorRuntime.current?.takeIf { it.server === player.level().server }?.guards
        if (guards != null) {
            guards.armDeathGuard(player.uuid, LANDING_GUARD_SECONDS)
            val until = now + LANDING_GUARD_SECONDS * 1_000L
            val kept = guards.attributes.on(player).any {
                it.attribute == AttributeKey.FALL_DAMAGE_MULTIPLIER.id && it.amount <= -1.0 &&
                    it.operation == AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL.serializedName && it.expiresAt >= until
            }
            if (!kept) {
                guards.attributes.apply(player, AttributeKey.FALL_DAMAGE_MULTIPLIER, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, until, harmful = false, turnId = null)
            }
        }
        if (spot != null && Teleports.move(player, spot)) {
            log.info("set ${player.plainTextName} down from ${mount.typeId} ${mount.uuid} at ${spot.x.toInt()} ${spot.y.toInt()} ${spot.z.toInt()}")
        } else {
            player.stopRiding()
            log.warn("${player.plainTextName} left ${mount.typeId} ${mount.uuid} where they were: no safe ground in reach")
        }
    }

    /** Gives back what players entrusted to [entity]: to its owner through the ledger when they are online, else on the ground. */
    private fun release(entity: Entity) {
        val type = entity.typeId
        // Before taking the lead off: the one holding it is the owner of a creature nobody tamed.
        val owner = Belongings.ownerOf(entity)?.let { entity.level().server?.playerList?.getPlayer(it) }
        val stacks = try {
            Belongings.takeFrom(entity, entity.getAttached(NarratorMob.TYPE)?.ownSlots.orEmpty())
        } catch (e: Exception) {
            log.error("could not take back what players entrusted to $type ${entity.uuid}", e)
            return
        }
        if (stacks.isEmpty()) return
        try {
            if (items != null) items.recover(stacks, entity, owner) else ItemLedger.drop(stacks, entity)
        } catch (e: Exception) {
            // Taken from the creature, so they must land somewhere: at worst twice, never nowhere.
            log.error("could not hand back ${ItemLedger.describe(stacks)} of $type ${entity.uuid}; dropped where it stood", e)
            ItemLedger.drop(stacks, entity)
        }
    }

    companion object {
        /** The tag of every entity the Narrator summons (`/tag`, entity `Tags`). */
        const val TAG = "narrator2"

        /** How long a creature a player rides may stay past its time, in milliseconds. */
        const val MAX_OVERTIME_MS = 10 * 60_000L

        /** How long a rider set down stays under the death guard and takes no fall damage, in seconds. */
        const val LANDING_GUARD_SECONDS = 30

        /** How often the landing of riders with no safe ground in reach is tried again, in milliseconds. */
        const val LANDING_RETRY_MS = 10_000L

        /** How far from the mount, in blocks, safe ground is looked for. */
        private const val LANDING_REACH = 8

        /** Ground within this many blocks of the mount's height is at its level (a shore beside a lava lake). */
        private const val MOUNT_LEVEL_BLOCKS = 3

        /** The columns around a mount, nearest first. */
        private val LANDING_COLUMNS: List<Pair<Int, Int>> =
            (-LANDING_REACH..LANDING_REACH).flatMap { dx -> (-LANDING_REACH..LANDING_REACH).map { dz -> dx to dz } }
                .filter { (dx, dz) -> dx * dx + dz * dz <= LANDING_REACH * LANDING_REACH }
                .sortedBy { (dx, dz) -> dx * dx + dz * dz }

        /** Whether [entity] is one of the Narrator's (it carries [TAG]). */
        fun isNarrators(entity: Entity?): Boolean = entity != null && TAG in entity.entityTags()

        /**
         * [previous], one of the Narrator's creatures, converted into [converted] (a zombie drowned, a
         * piglin zombified) or split into it (a slime, a magma cube): the new creature gets its mark, its
         * expiry, the player it was summoned for and its target, so that nothing the Narrator summons
         * outlives its due time; and no reinforcements, which the conversion has just rolled again.
         * `MOB_CONVERSION`, before the new creature enters the level (its load event then registers it);
         * works without a running Narrator. Server thread.
         */
        fun inherit(previous: Mob, converted: Mob) {
            val mob = previous.getAttached(NarratorMob.TYPE) ?: return
            converted.addTag(TAG)
            converted.setPersistenceRequired()
            noReinforcements(converted)
            converted.setAttached(NarratorMob.TYPE, mob)
            previous.target?.let { converted.target = it }
        }

        /**
         * A zombie of the Narrator's never calls reinforcements (hard difficulty): its chance is 0 for
         * good, base value and bonuses alike (`Zombie.handleAttributes` rolls a base up to 0.1 and gives a
         * leader zombie a bonus). Saved with the creature. Nothing for a creature without the attribute.
         */
        private fun noReinforcements(entity: Entity) {
            val chance = (entity as? LivingEntity)?.getAttribute(Attributes.SPAWN_REINFORCEMENTS_CHANCE) ?: return
            chance.removeModifiers()
            chance.baseValue = 0.0
        }

        /** The players riding [entity], directly or on another rider. */
        private fun ridersOf(entity: Entity): List<ServerPlayer> = entity.indirectPassengers.filterIsInstance<ServerPlayer>()

        /**
         * Where [player] may be set down from [mount] (`Teleports.landing`, from the mount's height down,
         * then up; loaded chunks only): the nearest safe ground at the level of the mount (a shore of the
         * lava lake a strider crosses), else the nearest safe ground at all (the ground far below a happy
         * ghast), within [LANDING_REACH] blocks. Null when there is none.
         */
        private fun safeGround(player: ServerPlayer, mount: Entity): Vec3? {
            val level = player.level()
            val root = mount.rootVehicle
            val fromY = floor(root.y).toInt() + 1
            var nearest: Vec3? = null
            for ((dx, dz) in LANDING_COLUMNS) {
                val spot = Teleports.landing(level, player, root.blockX + dx, root.blockZ + dz, fromY) ?: continue
                if (abs(spot.y - root.y) <= MOUNT_LEVEL_BLOCKS) return spot
                if (nearest == null) nearest = spot
            }
            return nearest
        }
    }
}
