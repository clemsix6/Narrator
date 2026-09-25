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
 * The bounds stay inside the game's own range for each attribute, and contain a player's default value
 * (checked in game by `GuardPolicyGameTests`). Whether a change is hostile is decided here, from the
 * values before and after, never declared by the model.
 *
 * @property peril what a change in the player's disfavor, or a favor taken back, can kill with: the death
 *   guard of that change covers it; null when it cannot kill (luck, reach, digging speed).
 * @property favorableUpTo for [BeneficialWhen.HIGHER], the highest value still in the player's favor:
 *   above it, the change is hostile too.
 */
@Serializable
enum class AttributeKey(
    val id: String,
    val min: Double,
    val max: Double,
    val beneficial: BeneficialWhen,
    val peril: Peril?,
    val favorableUpTo: Double = Double.MAX_VALUE,
) {
    // NEVER: size, weight, bounce and slipperiness reshape how the player moves, fun but never a gift
    // (a tiny player drowns in a puddle, a giant suffocates under a ceiling, low gravity turns a jump
    // into a long fall). HIGHER: more is better. LOWER: multipliers of what hurts, less is better.
    @SerialName("scale") SCALE("scale", 0.25, 3.0, BeneficialWhen.NEVER, Peril.ANY),
    @SerialName("gravity") GRAVITY("gravity", 0.01, 0.2, BeneficialWhen.NEVER, Peril.FALL),
    // Above 0.6, every jump lands from more than 3 blocks and hurts (M2 review, finding 10).
    @SerialName("jump_strength") JUMP_STRENGTH("jump_strength", 0.0, 1.5, BeneficialWhen.HIGHER, Peril.FALL, favorableUpTo = 0.6),
    @SerialName("movement_speed") MOVEMENT_SPEED("movement_speed", 0.02, 0.3, BeneficialWhen.HIGHER, Peril.ANY),
    @SerialName("block_break_speed") BLOCK_BREAK_SPEED("block_break_speed", 0.1, 4.0, BeneficialWhen.HIGHER, null),
    @SerialName("safe_fall_distance") SAFE_FALL_DISTANCE("safe_fall_distance", 0.0, 30.0, BeneficialWhen.HIGHER, Peril.FALL),
    @SerialName("step_height") STEP_HEIGHT("step_height", 0.0, 3.0, BeneficialWhen.HIGHER, null),
    @SerialName("block_interaction_range") BLOCK_INTERACTION_RANGE("block_interaction_range", 2.0, 10.0, BeneficialWhen.HIGHER, null),
    @SerialName("entity_interaction_range") ENTITY_INTERACTION_RANGE("entity_interaction_range", 1.0, 8.0, BeneficialWhen.HIGHER, Peril.ANY),
    @SerialName("mining_efficiency") MINING_EFFICIENCY("mining_efficiency", 0.0, 20.0, BeneficialWhen.HIGHER, null),
    @SerialName("luck") LUCK("luck", -5.0, 5.0, BeneficialWhen.HIGHER, null),
    @SerialName("max_health") MAX_HEALTH("max_health", 4.0, 60.0, BeneficialWhen.HIGHER, Peril.ANY),
    @SerialName("attack_speed") ATTACK_SPEED("attack_speed", 1.0, 16.0, BeneficialWhen.HIGHER, Peril.ANY),
    // Below 0, knockback is amplified (new in 26.2); the game's own floor is -2.
    @SerialName("knockback_resistance") KNOCKBACK_RESISTANCE("knockback_resistance", -1.0, 1.0, BeneficialWhen.HIGHER, Peril.ANY),
    @SerialName("oxygen_bonus") OXYGEN_BONUS("oxygen_bonus", 0.0, 10.0, BeneficialWhen.HIGHER, Peril.DROWNING),
    @SerialName("burning_time") BURNING_TIME("burning_time", 0.0, 4.0, BeneficialWhen.LOWER, Peril.FIRE),
    @SerialName("fall_damage_multiplier") FALL_DAMAGE_MULTIPLIER("fall_damage_multiplier", 0.0, 3.0, BeneficialWhen.LOWER, Peril.FALL),
    @SerialName("bounciness") BOUNCINESS("bounciness", 0.0, 1.0, BeneficialWhen.NEVER, Peril.FALL),
    @SerialName("friction_modifier") FRICTION_MODIFIER("friction_modifier", 0.2, 4.0, BeneficialWhen.NEVER, Peril.ANY),
    @SerialName("air_drag_modifier") AIR_DRAG_MODIFIER("air_drag_modifier", 0.2, 4.0, BeneficialWhen.NEVER, Peril.FALL),
    @SerialName("sneaking_speed") SNEAKING_SPEED("sneaking_speed", 0.1, 1.0, BeneficialWhen.HIGHER, null),
    ;

    /** Whether [value] lies within the bounds. */
    fun allows(value: Double): Boolean = value in min..max

    /** Why [value] is refused (`scale must stay between 0.25 and 3 (would be 5)`), or null when it lies within the bounds. */
    fun outOfBounds(value: Double): String? =
        if (allows(value)) null else "$id must stay between ${plain(min)} and ${plain(max)} (would be ${plain(value)})"

    /**
     * Whether changing the attribute from [before] (its value without the Narrator's modifier) to [after]
     * is a hostile action: always for [BeneficialWhen.NEVER], else when it moves the wrong way, or goes
     * past [favorableUpTo].
     */
    fun harmful(before: Double, after: Double): Boolean = when (beneficial) {
        BeneficialWhen.NEVER -> true
        BeneficialWhen.HIGHER -> after < before || after > favorableUpTo
        BeneficialWhen.LOWER -> after > before
    }

    companion object {
        /** The attribute named [id] in the tool arguments, if the Narrator may change it. */
        fun of(id: String): AttributeKey? = entries.firstOrNull { it.id == id }

        /** [value] without a useless fraction: `3`, `0.25`, `0.333`. */
        private fun plain(value: Double): String = "%.3f".format(java.util.Locale.ROOT, value).trimEnd('0').trimEnd('.')
    }
}
