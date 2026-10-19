package io.github.clemsix6.narrator2.guard

/**
 * The engine's caps at one chaos level (M1_M2 §10.4): how hard, how long, how many. The tools check them
 * before acting and say the cap in their error message (tool descriptions never quote a number that
 * depends on the chaos level). Beneficial actions (beneficial effects, gifts, attributes in their
 * beneficial direction) do not depend on the chaos level: see [BENEFICIAL_SECONDS] and
 * [BENEFICIAL_LEVEL].
 *
 * @property hostileAllowed hostile actions are allowed at all (false at chaos 0).
 * @property harmfulSeconds longest harmful effect or attribute change, in seconds.
 * @property harmfulLevel highest level of a harmful effect (1 = level I): the severity of a punishment.
 * @property spawnsPerAction creatures one `spawn` may summon.
 * @property livingMobs the Narrator's creatures alive at once (loaded ones, `NarratorMobs.count`).
 * @property explosionPower strongest `explosion` (a creeper is 3, TNT 4).
 * @property lightningHearts most damage of a `lightning`, in hearts.
 * @property launchStrength strongest `launch`.
 * @property teleportBlocks farthest `teleport`, in blocks.
 * @property confiscatedItems most items one `take_item` may take.
 * @property narratorKillsPerHour deaths the Narrator may cause per played hour of a player (`DeathGuard`).
 * @property maxSeverity highest severity of a `punish` (M4 §6.2): asking more is an error; the values it
 *   chooses are still bounded by the caps above.
 * @property categoryCooldownMinutes how long a category of punishment rests against a player once another
 *   act struck them in it (`same_punishment`, M4 §6.1): shorter as the chaos grows.
 */
data class Caps(
    val hostileAllowed: Boolean,
    val harmfulSeconds: Int,
    val harmfulLevel: Int,
    val spawnsPerAction: Int,
    val livingMobs: Int,
    val explosionPower: Double,
    val lightningHearts: Double,
    val launchStrength: Double,
    val teleportBlocks: Int,
    val confiscatedItems: Int,
    val narratorKillsPerHour: Int,
    val maxSeverity: Int,
    val categoryCooldownMinutes: Int,
) {
    companion object {
        /** Lowest and highest chaos level (`chaos` in the configuration, validated). */
        const val MIN_CHAOS = 0
        const val MAX_CHAOS = 5

        /** Longest beneficial effect or attribute change, in seconds, at any chaos level. */
        const val BENEFICIAL_SECONDS = 900

        /** Highest level of a beneficial effect, at any chaos level. */
        const val BENEFICIAL_LEVEL = 5

        /** A `teleport` farther than this, in blocks, is a hostile action (M1_M2 §10.3). */
        const val HOSTILE_TELEPORT_BLOCKS = 32

        /** An `explosion` closer than this to a player, in blocks, is a hostile action against them (M1_M2 §10.3). */
        const val HOSTILE_EXPLOSION_BLOCKS = 8

        // The table of M1_M2 §10.4 and M4 §6.2, one row per chaos level. Every cap grows (or stays) with the
        // level; the rest of a category shortens.
        private val BY_CHAOS = listOf(
            //   hostile  secs  level  spawns  living  power  hearts  launch  teleport  items  kills  severity  rest
            Caps(false, 0, 0, 0, 0, 0.0, 0.0, 0.0, 0, 0, 0, 0, 0),
            Caps(true, 60, 1, 2, 4, 1.0, 1.0, 1.0, 50, 8, 0, 2, 60),
            Caps(true, 120, 1, 4, 8, 2.0, 2.0, 1.5, 200, 16, 0, 3, 45),
            Caps(true, 300, 2, 6, 16, 3.0, 3.0, 2.0, 1_000, 32, 1, 4, 30),
            Caps(true, 600, 3, 10, 24, 4.0, 4.0, 2.5, 3_000, 64, 1, 5, 20),
            Caps(true, 900, 4, 12, 32, 4.0, 5.0, 3.0, 5_000, 128, 2, 5, 10),
        )

        /** The caps at [chaos], 0 to 5; a level out of range (the configuration is validated) is brought back into it. */
        fun of(chaos: Int): Caps = BY_CHAOS[chaos.coerceIn(MIN_CHAOS, MAX_CHAOS)]
    }
}
