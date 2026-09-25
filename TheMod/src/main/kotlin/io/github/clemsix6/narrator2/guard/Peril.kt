package io.github.clemsix6.narrator2.guard

import net.minecraft.resources.ResourceKey
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageType
import net.minecraft.world.damagesource.DamageTypes

/**
 * What an action of the Narrator can plausibly kill a player with. The death guard an action arms
 * covers the deaths of those causes only (M2 review, finding 11): a player lifted by a levitation dies
 * of the fall, not of a zombie's blow; one whose fire resistance was taken away dies in lava, not under
 * water. Without it, an armed guard made any death the Narrator's for as long as it lasted.
 *
 * [ANY] is for what weakens a player against everything (slowness, blindness, less health, a teleport
 * to an unknown place). A blow from another player and an operator's `/kill` are never the Narrator's,
 * whatever the peril ([DeathGuard.attributed]).
 */
enum class Peril(private val causes: Set<ResourceKey<DamageType>>?) {
    /** A fall, and what a fall ends in: the void, lava and fire, cactus and berry bushes, a stalagmite, a wall at speed. */
    FALL(
        setOf(
            DamageTypes.FALL, DamageTypes.FELL_OUT_OF_WORLD, DamageTypes.FLY_INTO_WALL, DamageTypes.STALAGMITE,
            DamageTypes.LAVA, DamageTypes.IN_FIRE, DamageTypes.ON_FIRE, DamageTypes.HOT_FLOOR, DamageTypes.CAMPFIRE,
            DamageTypes.CACTUS, DamageTypes.SWEET_BERRY_BUSH,
        ),
    ),

    /** Fire and lava. */
    FIRE(setOf(DamageTypes.LAVA, DamageTypes.IN_FIRE, DamageTypes.ON_FIRE, DamageTypes.HOT_FLOOR, DamageTypes.CAMPFIRE)),

    /** Drowning. */
    DROWNING(setOf(DamageTypes.DROWN)),

    /** Magic: poison, wither, instant damage. */
    MAGIC(setOf(DamageTypes.MAGIC, DamageTypes.INDIRECT_MAGIC, DamageTypes.WITHER)),

    /** Starving. */
    HUNGER(setOf(DamageTypes.STARVE)),

    /** Any cause. */
    ANY(null),
    ;

    /** The peril's name in the guard state: `fall`, `any`. */
    val id: String get() = name.lowercase()

    /** Whether a death by [source] is one this peril could cause. */
    fun covers(source: DamageSource): Boolean = causes == null || causes.any { source.`is`(it) }

    companion object {
        /** The peril called [id] in the guard state; null when unknown. */
        fun of(id: String): Peril? = entries.firstOrNull { it.id == id }
    }
}
