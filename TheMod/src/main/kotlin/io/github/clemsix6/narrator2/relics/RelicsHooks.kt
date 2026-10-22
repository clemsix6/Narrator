package io.github.clemsix6.narrator2.relics

import io.github.clemsix6.narrator2.NarratorRuntime
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents
import net.fabricmc.fabric.api.loot.v3.LootTableEvents

/**
 * The game's events the armorer's work listens to (M7-RELICS), registered once per JVM: the loot of structure chests
 * (`MODIFY_DROPS`, at each draw), the death, the damage and the conversion of creatures, and their removal. Each reaches
 * the running Narrator's [RelicsService] (`NarratorRuntime.current`), and does nothing without one. A hook never throws
 * into the game: a failure is logged.
 */
fun registerRelicsHooks() {
    LootTableEvents.MODIFY_DROPS.register { table, context, drops -> guarded("loot") { it.onChestLoot(table, context, drops) } }
    ServerLivingEntityEvents.AFTER_DEATH.register { entity, source -> guarded("death") { it.onDeath(entity, source) } }
    ServerLivingEntityEvents.AFTER_DAMAGE.register { entity, source, _, taken, _ -> guarded("damage") { it.onDamage(entity, source, taken) } }
    ServerLivingEntityEvents.MOB_CONVERSION.register { previous, converted, _ -> guarded("conversion") { it.onConverted(previous, converted) } }
    ServerEntityEvents.ENTITY_UNLOAD.register { entity, _ -> guarded("unload") { it.onUnload(entity) } }
}

private inline fun guarded(what: String, block: (RelicsService) -> Unit) {
    val relics = NarratorRuntime.current?.relics ?: return
    try {
        block(relics)
    } catch (e: Exception) {
        RelicsService.log.error("the $what hook failed", e)
    }
}
