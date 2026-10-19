package io.github.clemsix6.narrator2.guard

import io.github.clemsix6.narrator2.mechanics.PunishmentId

/**
 * The kind of a hostile action (M4 §6.1): every hostile action, of any tool, rule or stake, has one, deduced by
 * the code from the tool and its arguments, never declared by the model. The guards refuse a hostile action
 * of category K against a player when another act struck them in K less than `Caps.categoryCooldownMinutes`
 * ago (`same_punishment`, M4-04); [OTHER] never rests.
 *
 * @property phrase what an action of this category did to a player, for the refusals: `Bartok was already
 *   made smaller 12m ago (size)`.
 */
enum class PunishmentCategory(val phrase: String) {
    SIZE("made smaller or bigger"),
    GRAVITY("given another gravity"),
    MOVEMENT("slowed down"),
    MINING("slowed at mining"),
    COMBAT("weakened in combat"),
    SENSES("blinded or dazed"),
    FOOD("starved"),
    HARM("hurt"),
    AIR("sent into the air"),
    TELEPORT("teleported"),
    INVENTORY("robbed or shuffled"),
    CREATURES("set upon by creatures"),
    EXPLOSION("caught in an explosion"),
    LIGHTNING("struck by lightning"),
    TRAP("trapped"),

    /** A favor taken back, an attribute without a category (luck): no rest (M4 §6.1). */
    OTHER("punished"),
    ;

    /** `size`: its name in the refusals, the decisions and the situation (`Pacing`). */
    val id: String get() = name.lowercase()

    /** Whether a punishment of this category rests for a while against the same player (`same_punishment`): all but [OTHER]. */
    val rests: Boolean get() = this != OTHER

    companion object {
        /** The category of [id] (`size`), if any. */
        fun ofId(id: String): PunishmentCategory? = entries.firstOrNull { it.id == id }

        /**
         * The category of putting a harmful effect on a player (M4 §6.1): `minecraft:slowness` (or `slowness`)
         * → [MOVEMENT]; poison, wither, instant damage and any other harmful effect → [HARM]. Taking a
         * beneficial effect away has no category of its own: the caller passes [OTHER].
         */
        fun ofEffect(effect: String): PunishmentCategory = when (effect.removePrefix("minecraft:")) {
            "slowness" -> MOVEMENT
            "mining_fatigue" -> MINING
            "weakness" -> COMBAT
            "blindness", "darkness", "nausea", "glowing" -> SENSES
            "hunger" -> FOOD
            "levitation" -> AIR
            // Bad luck changes loot, like the luck attribute: no rest either.
            "unluck" -> OTHER
            else -> HARM
        }

        /** The category of moving [key] in the player's disfavor (M4 §6.1): `scale` → [SIZE]. Every attribute has one. */
        fun ofAttribute(key: AttributeKey): PunishmentCategory = when (key) {
            AttributeKey.SCALE -> SIZE
            AttributeKey.GRAVITY, AttributeKey.JUMP_STRENGTH, AttributeKey.SAFE_FALL_DISTANCE, AttributeKey.FALL_DAMAGE_MULTIPLIER,
            AttributeKey.BOUNCINESS,
            -> GRAVITY
            AttributeKey.MOVEMENT_SPEED, AttributeKey.SNEAKING_SPEED, AttributeKey.STEP_HEIGHT, AttributeKey.FRICTION_MODIFIER,
            AttributeKey.AIR_DRAG_MODIFIER,
            -> MOVEMENT
            AttributeKey.BLOCK_BREAK_SPEED, AttributeKey.MINING_EFFICIENCY, AttributeKey.BLOCK_INTERACTION_RANGE -> MINING
            AttributeKey.ATTACK_SPEED, AttributeKey.ENTITY_INTERACTION_RANGE, AttributeKey.KNOCKBACK_RESISTANCE, AttributeKey.MAX_HEALTH -> COMBAT
            AttributeKey.OXYGEN_BONUS, AttributeKey.BURNING_TIME -> HARM
            AttributeKey.LUCK -> OTHER
        }

        /** The category of a punishment of the catalogue (M4 §6.3): `punish` opens its episode with it. */
        fun of(punishment: PunishmentId): PunishmentCategory = when (punishment) {
            PunishmentId.TINY -> SIZE
            PunishmentId.MOON_GRAVITY -> GRAVITY
            PunishmentId.SLOW_HANDS -> MINING
            PunishmentId.HEAVY_FEET -> MOVEMENT
            PunishmentId.HUNGER -> FOOD
            PunishmentId.BUTTERFINGERS -> INVENTORY
            PunishmentId.GUESTS, PunishmentId.SLIME_RAIN -> CREATURES
            PunishmentId.SKYWARD -> AIR
            PunishmentId.TREE_TOP -> TELEPORT
            PunishmentId.SMITE -> LIGHTNING
            PunishmentId.CAGE, PunishmentId.WEB -> TRAP
        }
    }
}
