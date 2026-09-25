package io.github.clemsix6.narrator2.guard

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** In which direction a change of an attribute is beneficial; the other direction is hostile. */
enum class BeneficialWhen {
    /** Any change is hostile. */
    NEVER,

    /** A higher value helps the player. */
    HIGHER,

    /** A lower value helps the player. */
    LOWER,
}

/**
 * The attributes the Narrator may change (M1_M2 §10.5), with the bounds of the resulting value: a
 * change that would leave them is refused with the bounds, never silently clipped. [id] is the
 * attribute's registry path (`minecraft:<id>`) and its name in the tool arguments.
 *
 * Written by T02 as the contract between T09a, T15 (consumers) and T09b (owner), with the design's
 * table.
 */
@Serializable
enum class AttributeKey(val id: String, val min: Double, val max: Double, val beneficial: BeneficialWhen) {
    @SerialName("scale") SCALE("scale", 0.25, 3.0, BeneficialWhen.NEVER),
    @SerialName("gravity") GRAVITY("gravity", 0.01, 0.2, BeneficialWhen.NEVER),
    @SerialName("jump_strength") JUMP_STRENGTH("jump_strength", 0.0, 1.5, BeneficialWhen.HIGHER),
    @SerialName("movement_speed") MOVEMENT_SPEED("movement_speed", 0.02, 0.3, BeneficialWhen.HIGHER),
    @SerialName("block_break_speed") BLOCK_BREAK_SPEED("block_break_speed", 0.1, 4.0, BeneficialWhen.HIGHER),
    @SerialName("safe_fall_distance") SAFE_FALL_DISTANCE("safe_fall_distance", 0.0, 30.0, BeneficialWhen.HIGHER),
    @SerialName("step_height") STEP_HEIGHT("step_height", 0.0, 3.0, BeneficialWhen.HIGHER),
    @SerialName("block_interaction_range") BLOCK_INTERACTION_RANGE("block_interaction_range", 2.0, 10.0, BeneficialWhen.HIGHER),
    @SerialName("entity_interaction_range") ENTITY_INTERACTION_RANGE("entity_interaction_range", 1.0, 8.0, BeneficialWhen.HIGHER),
    @SerialName("mining_efficiency") MINING_EFFICIENCY("mining_efficiency", 0.0, 20.0, BeneficialWhen.HIGHER),
    @SerialName("luck") LUCK("luck", -5.0, 5.0, BeneficialWhen.HIGHER),
    @SerialName("max_health") MAX_HEALTH("max_health", 4.0, 60.0, BeneficialWhen.HIGHER),
    @SerialName("attack_speed") ATTACK_SPEED("attack_speed", 1.0, 16.0, BeneficialWhen.HIGHER),
    @SerialName("knockback_resistance") KNOCKBACK_RESISTANCE("knockback_resistance", -1.0, 1.0, BeneficialWhen.HIGHER),
    @SerialName("oxygen_bonus") OXYGEN_BONUS("oxygen_bonus", 0.0, 10.0, BeneficialWhen.HIGHER),
    @SerialName("burning_time") BURNING_TIME("burning_time", 0.0, 4.0, BeneficialWhen.LOWER),
    @SerialName("fall_damage_multiplier") FALL_DAMAGE_MULTIPLIER("fall_damage_multiplier", 0.0, 3.0, BeneficialWhen.LOWER),
    @SerialName("bounciness") BOUNCINESS("bounciness", 0.0, 1.0, BeneficialWhen.NEVER),
    @SerialName("friction_modifier") FRICTION_MODIFIER("friction_modifier", 0.2, 4.0, BeneficialWhen.NEVER),
    @SerialName("air_drag_modifier") AIR_DRAG_MODIFIER("air_drag_modifier", 0.2, 4.0, BeneficialWhen.NEVER),
    @SerialName("sneaking_speed") SNEAKING_SPEED("sneaking_speed", 0.1, 1.0, BeneficialWhen.HIGHER),
}
