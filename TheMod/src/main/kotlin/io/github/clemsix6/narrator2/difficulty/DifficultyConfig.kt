package io.github.clemsix6.narrator2.difficulty

import kotlinx.serialization.Serializable
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier

/**
 * The permanent difficulty layer (`difficulty` in `narrator2.json`): the ground the Narrator's whims add to. It works
 * without Claude. Read at `SERVER_STARTING`, before the worlds load, and again by `/narrator reload`, which applies a
 * change at once: the loaded creatures are reconciled, the rest counts from the next blow, tick or chest. The ores
 * only change in chunks generated afterwards.
 */
@Serializable
data class DifficultyConfig(
    /** False: the world plays as vanilla, and the creatures lose their extra health and speed at the reload or when loaded again. */
    val enabled: Boolean = true,
    /** Maximum health of hostile creatures (`Enemy`, endermen and piglins included), bosses excepted. */
    val mobHealth: Double = 1.5,
    /**
     * Maximum health by type (path in `entity_type`), over its vanilla health: it replaces [mobHealth] for the types
     * listed, bosses and animals included if listed. Free-form, like [mobSpeed].
     */
    val mobHealthByType: Map<String, Double> = DEFAULT_MOB_HEALTH_BY_TYPE,
    /** Damage players take from a hostile creature (bosses excepted), its projectiles and explosions included; not its effects. */
    val mobDamage: Double = 1.25,
    /**
     * Movement speed of creatures, by type: its path in the vanilla `entity_type` registry (`creeper`). Free-form: any
     * type may be added, and a default type keeps its factor unless the file sets it (1 gives back its vanilla speed).
     */
    val mobSpeed: Map<String, Double> = DEFAULT_MOB_SPEED,
    /**
     * Fire rate of bow archers (`RangedBowAttackGoal`), by type: 2 draws the bow twice as fast and waits half as long
     * between shots, each arrow still at full power. Free-form, like [mobSpeed].
     */
    val mobFireRate: Map<String, Double> = DEFAULT_MOB_FIRE_RATE,
    /**
     * Share of the ore blobs that are placed, by ore: 0 none, 1 vanilla. Each blob kept has its vanilla size and place.
     * `gold` covers the badlands' bonus and the Nether's gold. The large underground veins stay vanilla.
     */
    val ores: Map<String, Double> = DEFAULT_ORES,
    /** Natural regeneration waits this many times longer between heals, for the same food per heal; starvation is untouched. */
    val regenerationSlowdown: Double = 2.0,
    /** Chance that each diamond of a chest (`chests/` loot tables, trial chambers included) is kept. */
    val chestDiamonds: Double = 0.5,
    /** Chance that each enchanted golden apple of a chest is kept. */
    val chestEnchantedGoldenApples: Double = 0.5,
) {
    /** Every invalid value, one English sentence each (see `ConfigStore.problems`). */
    fun problems(): List<String> = buildList {
        fun check(setting: String, value: Double, min: Double, max: Double) {
            if (!(value >= min && value <= max)) add("difficulty.$setting must be between ${number(min)} and ${number(max)}")
        }
        check("mobHealth", mobHealth, 0.1, 10.0)
        check("mobDamage", mobDamage, 0.0, 10.0)
        fun byType(setting: String, table: Map<String, Double>, defaults: Map<String, Double>, min: Double, max: Double) {
            table.forEach { (type, factor) ->
                check("$setting.$type", factor, min, max)
                // The default types are vanilla ones: only the others are looked up, which keeps the registry (it
                // needs Minecraft's bootstrap) out of the unit tests that load a configuration.
                if (type !in defaults && !isEntityType(type)) {
                    add("difficulty.$setting.$type is not an entity type of the game: use its path, such as creeper or zombie")
                }
            }
        }
        byType("mobHealthByType", mobHealthByType, DEFAULT_MOB_HEALTH_BY_TYPE, 0.1, 10.0)
        byType("mobSpeed", mobSpeed, DEFAULT_MOB_SPEED, 0.5, 3.0)
        byType("mobFireRate", mobFireRate, DEFAULT_MOB_FIRE_RATE, 0.5, 4.0)
        ores.forEach { (ore, factor) -> check("ores.$ore", factor, 0.0, 1.0) }
        check("regenerationSlowdown", regenerationSlowdown, 1.0, 10.0)
        check("chestDiamonds", chestDiamonds, 0.0, 1.0)
        check("chestEnchantedGoldenApples", chestEnchantedGoldenApples, 0.0, 1.0)
    }

    companion object {
        /** The bow skeletons, glass cannons by default (M7-SKELETONS): a quarter of their vanilla health, twice the fire rate. */
        private val ARCHERS = listOf("skeleton", "stray", "bogged", "parched")

        /** The creatures with their own health by default. */
        val DEFAULT_MOB_HEALTH_BY_TYPE: Map<String, Double> = ARCHERS.associateWithTo(linkedMapOf()) { 0.25 }

        /** The creatures faster by default. */
        val DEFAULT_MOB_SPEED: Map<String, Double> = linkedMapOf("creeper" to 2.0)

        /** The archers shooting faster by default. */
        val DEFAULT_MOB_FIRE_RATE: Map<String, Double> = ARCHERS.associateWithTo(linkedMapOf()) { 2.0 }

        /** Every ore the layer knows ([Difficulty.ORE_FEATURES]), with its default share of blobs, the rarest first. */
        val DEFAULT_ORES: Map<String, Double> = linkedMapOf(
            "diamond" to 0.5,
            "emerald" to 0.5,
            "gold" to 0.5,
            "iron" to 0.75,
            "copper" to 0.75,
            "coal" to 1.0,
            "redstone" to 1.0,
            "lapis" to 1.0,
            "quartz" to 1.0,
            "ancient_debris" to 1.0,
        )

        private fun isEntityType(path: String): Boolean =
            Identifier.tryBuild(Identifier.DEFAULT_NAMESPACE, path)?.let { BuiltInRegistries.ENTITY_TYPE.containsKey(it) } == true
    }
}

/** [value] without a useless decimal part: `2`, `0.5`, `0.75`. */
internal fun number(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else "%.2f".format(java.util.Locale.ROOT, value).trimEnd('0')
