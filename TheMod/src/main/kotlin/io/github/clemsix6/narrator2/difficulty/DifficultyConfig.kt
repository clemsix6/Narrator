package io.github.clemsix6.narrator2.difficulty

import kotlinx.serialization.Serializable

/**
 * The permanent difficulty layer (`difficulty` in `narrator2.json`): the ground the Narrator's whims add to. It works
 * without Claude. Read at `SERVER_STARTING` only, before the worlds load: a change takes a restart of the server. The
 * ores only change in chunks generated afterwards.
 */
@Serializable
data class DifficultyConfig(
    /** False: the world plays as vanilla, and the creatures loaded again lose their extra health. */
    val enabled: Boolean = true,
    /** Maximum health of hostile creatures (`Enemy`, endermen and piglins included), bosses excepted. */
    val mobHealth: Double = 1.5,
    /** Damage players take from a hostile creature (bosses excepted), its projectiles and explosions included; not its effects. */
    val mobDamage: Double = 1.25,
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
        ores.forEach { (ore, factor) -> check("ores.$ore", factor, 0.0, 1.0) }
        check("regenerationSlowdown", regenerationSlowdown, 1.0, 10.0)
        check("chestDiamonds", chestDiamonds, 0.0, 1.0)
        check("chestEnchantedGoldenApples", chestEnchantedGoldenApples, 0.0, 1.0)
    }

    companion object {
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
    }
}

/** [value] without a useless decimal part: `2`, `0.5`, `0.75`. */
internal fun number(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else "%.2f".format(java.util.Locale.ROOT, value).trimEnd('0')
