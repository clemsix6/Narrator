package io.github.clemsix6.narrator2.difficulty

import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.core.Log
import net.fabricmc.fabric.api.event.Event
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.loot.v3.LootTableEvents
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.Mob

private val log = Log.difficulty

/** `ENTITY_LOAD` phase of the layer, before the default one: the rules' `mob_spawn` sees the creature already hardened. */
private val BEFORE_RULES: Identifier = Identifier.fromNamespaceAndPath("narrator2", "difficulty_before_rules")

/**
 * Registers the difficulty layer's hooks, once, at mod initialization. They never go through `NarratorRuntime.current`
 * (null before `SERVER_STARTED`, while the spawn chunks load):
 * - `SERVER_STARTING` → [Difficulty.load], before the worlds load;
 * - `ENTITY_LOAD` of a creature, before the other listeners → [Difficulty.onMobLoad];
 * - `MODIFY_DROPS` of a loot table → [Difficulty.thinChest].
 * The damage, the regeneration and the ores go through the mixins of `mixin/difficulty`.
 */
fun registerDifficultyHooks() {
    ServerLifecycleEvents.SERVER_STARTING.register { Difficulty.load(NarratorRuntime.configDir) }

    ServerEntityEvents.ENTITY_LOAD.addPhaseOrdering(BEFORE_RULES, Event.DEFAULT_PHASE)
    ServerEntityEvents.ENTITY_LOAD.register(BEFORE_RULES) { entity, _ ->
        if (entity is Mob) hook("mob_load") { Difficulty.onMobLoad(entity) }
    }
    LootTableEvents.MODIFY_DROPS.register { table, context, drops ->
        val path = table.unwrapKey().orElse(null)?.identifier()?.path ?: return@register
        hook("chest_loot") { Difficulty.thinChest(path, context.random, drops) }
    }
}

/** Runs a hook's [body]: a failure is logged, never thrown into the game. */
private inline fun hook(name: String, body: () -> Unit) {
    try {
        body()
    } catch (e: Exception) {
        log.error("difficulty hook $name failed", e)
    }
}
