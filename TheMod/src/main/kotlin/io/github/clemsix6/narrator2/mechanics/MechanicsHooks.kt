package io.github.clemsix6.narrator2.mechanics

import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.director.ReminderState
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.fabricmc.fabric.api.event.Event
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Mob

private val log = Log.mechanics

/**
 * Phase of the engine's `ALLOW_DEATH` and `JOIN` listeners, before the default one (the guards'):
 * - a duelist's death is refused before the death guard counts it (M4 §5.5); `ALLOW_DEATH` stops at the
 *   first refusal;
 * - the settlements of a player's bets are applied before the guards give back what is due at their join
 *   (M4 §5.6).
 */
private val BEFORE_GUARDS: Identifier = Identifier.fromNamespaceAndPath("narrator2", "mechanics_before_guards")

/** `AFTER_RESPAWN` phase of the engine, after the default one: Fabric copies the attachments to the new player there. */
private val AFTER_ATTACHMENTS: Identifier = Identifier.fromNamespaceAndPath("narrator2", "mechanics_after_attachments")

/**
 * Registers the mechanics engine's hooks (M4 §4.2), once, at mod initialization, right after the guards':
 * - the engine's persistent global attachment types ([MechanicsState.TYPE], [ReminderState.TYPE]), touched
 *   so they exist before the first world loads;
 * - `END_SERVER_TICK` → [Mechanics.tick];
 * - `ENTITY_LOAD` of a creature ([Mob]: born, or loaded from a chunk) → [Mechanics.onMobLoad];
 * - `JOIN` (before the guards) → [Mechanics.onJoin]; `LEAVE` → [Mechanics.onLeave]; `AFTER_RESPAWN` (after
 *   the attachments are copied) → [Mechanics.onRespawn];
 * - `ALLOW_DEATH` of a player (before the guards) → [Mechanics.allowDeath];
 * - `UseItemCallback`, server side → [Jokers.onUse].
 *
 * Each handler reaches the running server's engine through `NarratorRuntime.current` and does nothing
 * without one; a failure is logged, never thrown into the game (a death goes on as the game decides, an
 * item use passes). Creatures loaded before `SERVER_STARTED` (the spawn chunks) are not seen here: the
 * runtime is not set yet. The rules' part walks them at its start (M4 §4.6).
 */
fun registerMechanicsHooks() {
    // Touching a type registers it: persistent types must exist before the first world loads.
    val types = listOf(MechanicsState.TYPE, ReminderState.TYPE)
    log.info("attachments: ${types.joinToString { it.identifier().toString() }}")

    ServerTickEvents.END_SERVER_TICK.register { server ->
        hook("tick") { running()?.takeIf { NarratorRuntime.current?.server === server }?.tick(server) }
    }
    ServerEntityEvents.ENTITY_LOAD.register { entity, level ->
        if (entity is Mob) hook("mob_load") { running()?.onMobLoad(entity, level) }
    }

    ServerPlayerEvents.JOIN.addPhaseOrdering(BEFORE_GUARDS, Event.DEFAULT_PHASE)
    ServerPlayerEvents.JOIN.register(BEFORE_GUARDS) { player -> hook("join") { running()?.onJoin(player) } }
    ServerPlayerEvents.LEAVE.register { player -> hook("leave") { running()?.onLeave(player) } }
    ServerPlayerEvents.AFTER_RESPAWN.addPhaseOrdering(Event.DEFAULT_PHASE, AFTER_ATTACHMENTS)
    ServerPlayerEvents.AFTER_RESPAWN.register(AFTER_ATTACHMENTS) { old, new, _ -> hook("respawn") { running()?.onRespawn(old, new) } }

    ServerLivingEntityEvents.ALLOW_DEATH.addPhaseOrdering(BEFORE_GUARDS, Event.DEFAULT_PHASE)
    ServerLivingEntityEvents.ALLOW_DEATH.register(BEFORE_GUARDS) { entity, source, _ ->
        if (entity !is ServerPlayer) return@register true
        try {
            running()?.allowDeath(entity, source) ?: true
        } catch (e: Exception) {
            log.error("the engine's death check failed for ${entity.plainTextName}; the death goes on as the guards decide", e)
            true
        }
    }

    UseItemCallback.EVENT.register { player, level, hand ->
        if (level.isClientSide || player !is ServerPlayer) return@register InteractionResult.PASS
        try {
            NarratorRuntime.current?.let { Jokers.onUse(it, player, hand) } ?: InteractionResult.PASS
        } catch (e: Exception) {
            log.error("playing a joker failed for ${player.plainTextName}; the item is used as the game decides", e)
            InteractionResult.PASS
        }
    }
}

/** The running server's engine; null when the Narrator does not run. */
private fun running(): Mechanics? = NarratorRuntime.current?.mechanics

/** Runs a hook's [body]: a failure is logged, never thrown into the game. */
private inline fun hook(name: String, body: () -> Unit) {
    try {
        body()
    } catch (e: Exception) {
        log.error("mechanics hook $name failed", e)
    }
}
