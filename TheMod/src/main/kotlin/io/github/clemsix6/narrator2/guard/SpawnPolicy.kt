package io.github.clemsix6.narrator2.guard

import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Difficulty
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.Mob

/**
 * What `spawn` may summon (M1_M2 §10.7): creatures only (a `Mob`: no TNT, end crystal, falling block or
 * boat), summonable by a command, enabled in this world, allowed at the level's difficulty, and not one
 * of [FORBIDDEN], the creatures that change blocks, start fires, carry items away, or hurt everyone
 * around whatever the rules.
 *
 * What the Narrator's creatures do once in the world is guarded elsewhere, by their `narrator2` tag:
 * their explosions touch no block, no item and no bystander (`ExplosionGuard`), they never break doors
 * and never pick up items (`mixin.policy`).
 */
object SpawnPolicy {
    /** The rule of a refused creature, in `DENIED (<rule>): …`. */
    const val FORBIDDEN_CREATURE = "forbidden_creature"

    /** Creatures the Narrator never summons, by type id, with the reason. */
    val FORBIDDEN: Map<String, String> = mapOf(
        "minecraft:wither" to "it breaks blocks and withers everything around",
        "minecraft:ender_dragon" to "it breaks blocks",
        "minecraft:enderman" to "it picks up and moves blocks",
        "minecraft:ravager" to "it breaks leaves and crops",
        "minecraft:silverfish" to "it hides in stone blocks and breaks them",
        "minecraft:snow_golem" to "it covers the ground with snow",
        "minecraft:ghast" to "its fireballs break blocks and start fires",
        "minecraft:blaze" to "its fireballs start fires",
        "minecraft:warden" to "it kills in two hits and blinds every player around",
        "minecraft:elder_guardian" to "it gives mining fatigue to every player within 50 blocks, protected or not",
        "minecraft:villager" to "it harvests the players' crops and keeps the harvest",
        "minecraft:rabbit" to "it eats the carrots of the players' farms",
        "minecraft:allay" to "it collects items and would vanish with them",
        "minecraft:copper_golem" to "it takes items out of the players' chests",
    )

    /** Whether the Narrator may summon [type] in [level]. Server thread. */
    fun check(type: EntityType<*>, level: ServerLevel): Verdict {
        val id = EntityType.getKey(type).toString()
        FORBIDDEN[id]?.let { return denied("$id is not summoned: $it.") }
        if (!type.isEnabled(level.enabledFeatures())) return denied("$id is not enabled in this world.")
        if (!type.canSummon()) return denied("$id cannot be summoned.")
        if (level.difficulty == Difficulty.PEACEFUL && !type.isAllowedInPeaceful) {
            return denied("$id does not exist at the peaceful difficulty of this world.")
        }
        // The only reliable way to know the class (EntityType.getBaseClass() is Entity for every type):
        // an instance never added to the level.
        if (type.create(level, EntitySpawnReason.COMMAND) !is Mob) return denied("$id is not a creature; only creatures are summoned.")
        return Verdict.Allowed
    }

    private fun denied(message: String) = Verdict.Denied(FORBIDDEN_CREATURE, message)
}
