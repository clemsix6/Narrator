package io.github.clemsix6.narrator2.tool.action

import io.github.clemsix6.narrator2.guard.Caps
import io.github.clemsix6.narrator2.guard.ExplosionGuard
import io.github.clemsix6.narrator2.guard.Guards
import io.github.clemsix6.narrator2.guard.PunishmentCategory
import io.github.clemsix6.narrator2.guard.Verdict
import io.github.clemsix6.narrator2.tool.Caller
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ExplosionParticleInfo
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.util.random.WeightedList
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Explosion
import net.minecraft.world.level.ExplosionDamageCalculator
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3

/**
 * The damage calculator of the Narrator's own explosions: the `explosion` tool and the creatures of
 * `spawn` that explode. It never lets a block explode, and hurts nothing but living entities; a
 * [HARMLESS] explosion hurts and pushes nobody at all (a show of fire and noise).
 *
 * The rules that do not depend on the tool are `ExplosionGuard`'s (T09b): every explosion that goes off
 * in `ExplosionGuard.narrators` touches no block, starts no fire, and spares items, decorations, animals
 * and protected players. This calculator is a second line behind it for blocks and objects, and the only
 * way to make an explosion harmless.
 */
class NarratorExplosionDamage private constructor(private val hurts: Boolean) : ExplosionDamageCalculator() {
    override fun shouldBlockExplode(explosion: Explosion, level: BlockGetter, pos: BlockPos, state: BlockState, power: Float) = false

    override fun shouldDamageEntity(explosion: Explosion, entity: Entity) = hurts && entity is LivingEntity

    override fun getKnockbackMultiplier(entity: Entity) = if (hurts) 1f else 0f

    companion object {
        val HURTS = NarratorExplosionDamage(hurts = true)
        val HARMLESS = NarratorExplosionDamage(hurts = false)

        /** Seconds a hurting explosion arms the death guard of the players around it (M1_M2 §10.3). */
        const val GUARD_SECONDS = 5

        /**
         * Sets off one of the Narrator's explosions of [power] at [center]: inside `ExplosionGuard.narrators`,
         * without fire, with no block interaction. `ExplosionGuard` makes every such explosion "small" (it
         * touches no block), which would show a single puff of smoke: from power 2 up, it shows the big
         * explosion instead. Server thread.
         */
        fun explode(level: ServerLevel, center: Vec3, power: Double, hurts: Boolean) {
            val look = if (power >= BIG_LOOK_POWER) ParticleTypes.EXPLOSION_EMITTER else ParticleTypes.EXPLOSION
            ExplosionGuard.narrators {
                level.explode(
                    null, null, if (hurts) HURTS else HARMLESS, center.x, center.y, center.z, power.toFloat(), false,
                    Level.ExplosionInteraction.NONE, look, ParticleTypes.EXPLOSION_EMITTER, WeightedList.of<ExplosionParticleInfo>(),
                    SoundEvents.GENERIC_EXPLODE,
                )
            }
        }

        /**
         * The players an explosion at [center] counts as hostile against (M1_M2 §10.3): within
         * [Caps.HOSTILE_EXPLOSION_BLOCKS], not spectating, and not spared by `ExplosionGuard` (a protected
         * player, or one dead less than 5 minutes ago: the blast leaves them alone anyway). Server thread.
         */
        fun bystanders(level: ServerLevel, center: Vec3): List<ServerPlayer> =
            level.players().filter { player ->
                !player.isSpectator && !ExplosionGuard.spares(player) && player.position().distanceTo(center) < Caps.HOSTILE_EXPLOSION_BLOCKS
            }

        /**
         * Checks a hurting explosion against each of [bystanders], for [caller] until [until] (epoch
         * milliseconds), in two passes (M4 review, finding 11). First every bystander is vetted, changing
         * nothing: the first refusal is returned (the whole explosion must then be refused or made harmless,
         * else a player who just died, standing next to the target, would be hit), and nobody counts as
         * punished by an explosion that never hurt them. Then, all allowed, each one's punishment opens and
         * their death guard is armed, and [Verdict.Allowed] is returned. Nothing between the two passes can
         * refuse a bystander the first allowed: time only lifts the guards' rules. [category]: the kind of
         * punishment (M4 §6.1), `explosion` for an explosion that hurts. Server thread.
         */
        fun allow(bystanders: List<ServerPlayer>, guards: Guards, caller: Caller, until: Long, category: PunishmentCategory? = null): Verdict {
            for (player in bystanders) {
                val verdict = guards.vet(player, caller, category)
                if (verdict is Verdict.Denied) return verdict
            }
            for (player in bystanders) {
                val verdict = guards.hostile(player, caller, until, category)
                if (verdict is Verdict.Denied) return verdict
            }
            bystanders.forEach { guards.armDeathGuard(it.uuid, GUARD_SECONDS) }
            return Verdict.Allowed
        }

        /** From this power up, a Narrator's explosion shows the big explosion. */
        private const val BIG_LOOK_POWER = 2.0
    }
}
