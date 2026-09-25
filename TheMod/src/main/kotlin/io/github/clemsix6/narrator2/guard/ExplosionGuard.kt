package io.github.clemsix6.narrator2.guard

import io.github.clemsix6.narrator2.NarratorRuntime
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Explosion

/**
 * The Narrator's explosions (M1_M2 §10.7), applied by `mixin.policy.ServerExplosionGuardMixin`: they
 * touch no block (whatever their interaction), start no fire, and hurt, push or destroy nothing but
 * players (never a protected one) and monsters. Items on the ground, item frames, paintings, armor
 * stands, vehicles and their contents, animals, pets and villagers are left alone.
 *
 * An explosion is the Narrator's when it goes off inside [narrators] (the `explosion` tool, which has no
 * source entity), or when its direct or indirect source carries the `narrator2` tag (a creeper, a
 * breeze, a TNT of the Narrator's). Anyone may ask [isNarrators], e.g. the Observer for
 * `ExplosionHappened.narrator`, while the explosion goes off. Server thread only.
 */
object ExplosionGuard {
    /** How deep the server thread is in [narrators] calls. */
    private var depth = 0

    /** Runs [explode], the `explosion` tool's call to `ServerLevel.explode`: whatever goes off meanwhile is the Narrator's. */
    fun <T> narrators(explode: () -> T): T {
        depth++
        try {
            return explode()
        } finally {
            depth--
        }
    }

    /** Whether [explosion] is the Narrator's. */
    @JvmStatic
    fun isNarrators(explosion: Explosion): Boolean =
        depth > 0 || NarratorMobs.isNarrators(explosion.directSourceEntity) || NarratorMobs.isNarrators(explosion.indirectSourceEntity)

    /** Whether one of the Narrator's explosions leaves [entity] alone. */
    @JvmStatic
    fun spares(entity: Entity): Boolean = when (entity) {
        is Player -> NarratorRuntime.current?.guards?.isProtected(entity.uuid) == true
        is Enemy -> false
        else -> true
    }
}
