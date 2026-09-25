package io.github.clemsix6.narrator2.guard

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.observer.typeId
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry
import net.fabricmc.fabric.api.attachment.v1.AttachmentType
import net.minecraft.core.UUIDUtil
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Mob
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
 * expires first gives back what players entrusted to it ([Belongings]), through [items] when its owner
 * is online, else on the ground.
 */
class NarratorMobs(private val items: ItemLedger? = null) {
    private val log = Log.guard

    /** A creature loaded now. [type] is its entity type id. */
    private class Loaded(val entity: Entity, val type: String, val mob: NarratorMob)

    private val loaded = ConcurrentHashMap<UUID, Loaded>()

    /**
     * Makes [entity] one of the Narrator's creatures until [expiresAt] (epoch milliseconds), summoned
     * for [player]: tagged, persistent (a mob no longer despawns on its own), given its expiry. Call it
     * last, once the entity is equipped and `finalizeSpawn` has run, and before adding it to the level:
     * the gear it holds then is its own, and the load event registers it. Server thread.
     */
    fun mark(entity: Entity, expiresAt: Long, player: UUID?, harmful: Boolean) {
        entity.addTag(TAG)
        (entity as? Mob)?.setPersistenceRequired()
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
    }

    /** Registers the Narrator's creatures already loaded (spawn chunks load before the Narrator starts). Server thread. */
    fun registerLoaded(server: MinecraftServer) {
        for (level in server.allLevels) {
            for (entity in level.allEntities) if (isNarrators(entity)) onLoad(entity)
        }
    }

    /**
     * Removes the creatures due at [now] (epoch milliseconds) and returns how many; what players
     * entrusted to them is given back first. Server thread, once a second.
     */
    fun expire(now: Long): Int {
        val due = loaded.values.filter { it.mob.expiresAt <= now }
        for (mob in due) {
            loaded.remove(mob.entity.uuid)
            if (mob.entity.isRemoved) continue
            release(mob)
            mob.entity.discard()
        }
        if (due.isNotEmpty()) log.info("the Narrator's creatures expired: ${due.groupBy { it.type }.entries.joinToString { (type, mobs) -> "${mobs.size} $type" }}")
        return due.size
    }

    /** Gives back what players entrusted to [mob]: to its owner through the ledger when they are online, else on the ground. */
    private fun release(mob: Loaded) {
        val entity = mob.entity
        val stacks = try {
            Belongings.takeFrom(entity, mob.mob.ownSlots)
        } catch (e: Exception) {
            log.error("could not take back what players entrusted to ${mob.type} ${entity.uuid}", e)
            return
        }
        if (stacks.isEmpty()) return
        val owner = Belongings.ownerOf(entity)?.let { entity.level().server?.playerList?.getPlayer(it) }
        try {
            if (items != null) items.recover(stacks, entity, owner) else ItemLedger.drop(stacks, entity)
        } catch (e: Exception) {
            // Taken from the creature, so they must land somewhere: at worst twice, never nowhere.
            log.error("could not hand back ${ItemLedger.describe(stacks)} of ${mob.type} ${entity.uuid}; dropped where it stood", e)
            ItemLedger.drop(stacks, entity)
        }
    }

    companion object {
        /** The tag of every entity the Narrator summons (`/tag`, entity `Tags`). */
        const val TAG = "narrator2"

        /** Whether [entity] is one of the Narrator's (it carries [TAG]). */
        fun isNarrators(entity: Entity?): Boolean = entity != null && TAG in entity.entityTags()

        /**
         * [previous], one of the Narrator's creatures, converted into [converted] (a zombie drowned, a
         * piglin zombified) or split into it (a slime, a magma cube): the new creature gets its mark, its
         * expiry, the player it was summoned for and its target, so that nothing the Narrator summons
         * outlives its due time. `MOB_CONVERSION`, before the new creature enters the level (its load
         * event then registers it); works without a running Narrator. Server thread.
         */
        fun inherit(previous: Mob, converted: Mob) {
            val mob = previous.getAttached(NarratorMob.TYPE) ?: return
            converted.addTag(TAG)
            converted.setPersistenceRequired()
            converted.setAttached(NarratorMob.TYPE, mob)
            previous.target?.let { converted.target = it }
        }
    }
}
