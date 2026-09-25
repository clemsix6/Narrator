package io.github.clemsix6.narrator2.guard

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.EntityType

/**
 * What `spawn` may summon (M1_M2 §10.7): mobs only, summonable, enabled, allowed at the level's
 * difficulty, and not one of the creatures that change blocks, start fires or kill in two hits.
 *
 * Written by T02 as the contract between T09a (consumer) and T09b (owner). Provisional body: every
 * creature is refused. T09b writes it.
 */
object SpawnPolicy {
    private const val NOT_READY = "not_ready"

    /** Whether the Narrator may summon [type] in [level]. Server thread. */
    @Suppress("UNUSED_PARAMETER")
    fun check(type: EntityType<*>, level: ServerLevel): Verdict =
        Verdict.Denied(NOT_READY, "summoning is not allowed until the engine's rules are in place.")
}
