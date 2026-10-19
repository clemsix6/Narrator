package io.github.clemsix6.narrator2.guard

import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.runtime.NarratorServices
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.fabricmc.fabric.api.entity.event.v1.effect.ServerMobEffectEvents
import net.fabricmc.fabric.api.event.Event
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer

private val log = Log.guard

/**
 * `AFTER_RESPAWN` phase of the guards, after the default one: Fabric copies the attachments to the new
 * player object in the default phase, and the guards read the copy.
 */
private val AFTER_ATTACHMENTS: Identifier = Identifier.fromNamespaceAndPath("narrator2", "guards_after_attachments")

/**
 * Registers the guards' hooks: the death guard (`ALLOW_DEATH`, `AFTER_DEATH`, which also saves every
 * death for the no-piling-on rule), the creature shield (`ALLOW_DAMAGE`), the fall cushion at the end of
 * the Narrator's levitation (`AFTER_REMOVE` of an effect), the ledgers on join and
 * respawn, the Narrator's creatures (entity load and unload, conversions and splits), the check once a
 * second of what is due, the guards' persistent attachment types (guard state, creature expiry), and the
 * [BlockRestorer]'s (its chunk attachment and hooks, M4 §9.1).
 * Once, at mod initialization; the handlers find the running server's guards through `NarratorRuntime.current`.
 *
 * Without a running Narrator (it failed to start), the keep-inventory mark still works: a marked player
 * keeps their inventory at respawn, and the mark never outlives it.
 */
fun registerGuardHooks() {
    // Touching a type registers it: persistent types must exist before the first world loads.
    val types = listOf(GuardState.TYPE, NarratorMob.TYPE)
    log.info("attachments: ${types.joinToString { it.identifier().toString() }}")
    BlockRestorer.registerHooks()

    ServerLivingEntityEvents.ALLOW_DEATH.register { entity, source, _ ->
        if (entity !is ServerPlayer) return@register true
        try {
            val guards = running()
            if (guards != null) {
                guards.death.allowDeath(entity, source)
            } else {
                DeathGuard.clearMarkWithoutGuards(entity)
                true
            }
        } catch (e: Exception) {
            log.error("the death guard failed for ${entity.plainTextName}; the death goes on as the game decides", e)
            true
        }
    }
    ServerLivingEntityEvents.AFTER_DEATH.register { entity, source ->
        if (entity is ServerPlayer) hook("death") { running()?.onDeath(entity, source) }
    }
    // The Narrator's creatures never hurt a player it may not act against (CreatureShield).
    ServerLivingEntityEvents.ALLOW_DAMAGE.register { entity, source, _ ->
        if (entity !is ServerPlayer) return@register true
        try {
            CreatureShield.allowsDamage(entity, source)
        } catch (e: Exception) {
            log.error("the creature shield failed for ${entity.plainTextName}; the damage goes on as the game decides", e)
            true
        }
    }
    // The end of the Narrator's levitation (run out, milk, removed) leaves a player in the air: their fall is cushioned.
    ServerMobEffectEvents.AFTER_REMOVE.register { effect, entity, _ ->
        if (entity is ServerPlayer) hook("effect_end") { running()?.onEffectEnded(entity, effect) }
    }
    // A creature of the Narrator's that converts or splits hands its mark down, before the new one enters the level.
    ServerLivingEntityEvents.MOB_CONVERSION.register { previous, converted, _ ->
        hook("conversion") { NarratorMobs.inherit(previous, converted) }
    }
    ServerPlayerEvents.JOIN.register { player ->
        hook("join") { running()?.onJoin(player) ?: DeathGuard.clearMarkWithoutGuards(player) }
    }
    ServerPlayerEvents.LEAVE.register { player -> hook("leave") { running()?.onLeave(player) } }
    ServerPlayerEvents.AFTER_RESPAWN.addPhaseOrdering(Event.DEFAULT_PHASE, AFTER_ATTACHMENTS)
    ServerPlayerEvents.AFTER_RESPAWN.register(AFTER_ATTACHMENTS) { _, player, _ ->
        hook("respawn") { running()?.onRespawn(player) ?: DeathGuard.clearMarkWithoutGuards(player) }
    }
    ServerEntityEvents.ENTITY_LOAD.register { entity, _ ->
        if (NarratorMobs.isNarrators(entity)) hook("entity_load") { running()?.mobs?.onLoad(entity) }
    }
    ServerEntityEvents.ENTITY_UNLOAD.register { entity, _ ->
        if (NarratorMobs.isNarrators(entity)) hook("entity_unload") { running()?.mobs?.onUnload(entity) }
    }
    ServerTickEvents.END_SERVER_TICK.register { server ->
        hook("tick") { running()?.takeIf { NarratorRuntime.current?.server === server }?.tick(server) }
    }
}

/** Creates the guards of a server that starts (M1_M2 §10), with the Narrator's creatures already loaded. */
fun createGuards(services: NarratorServices): Guards =
    GuardsImpl(services).also { it.mobs.registerLoaded(services.server) }

/** The running server's guards; null when the Narrator does not run. */
private fun running(): GuardsImpl? = NarratorRuntime.current?.guards as? GuardsImpl

/** Runs a hook's [body]: a failure is logged, never thrown into the game. */
private inline fun hook(name: String, body: () -> Unit) {
    try {
        body()
    } catch (e: Exception) {
        log.error("guard hook $name failed", e)
    }
}
