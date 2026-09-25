package io.github.clemsix6.narrator2.guard

/**
 * The engine's caps at one chaos level (M1_M2 §10.4). Beneficial actions (beneficial effects, gifts,
 * attributes in their beneficial direction) do not depend on the chaos level.
 *
 * Written by T02 as the contract between T09a (consumer) and T09b (owner), with the design's table.
 *
 * @property hostileAllowed hostile actions are allowed at all (false at chaos 0).
 * @property harmfulSeconds longest harmful effect or attribute change, in seconds.
 * @property harmfulLevel highest level of a harmful effect.
 * @property spawnsPerAction creatures one `spawn` may summon.
 * @property livingMobs the Narrator's creatures alive at once.
 * @property explosionPower strongest `explosion`.
 * @property lightningHearts most damage of a `lightning`, in hearts.
 * @property launchStrength strongest `launch`.
 * @property teleportBlocks farthest `teleport`, in blocks.
 * @property confiscatedItems most items one `take_item` may take.
 * @property narratorKillsPerHour deaths the Narrator may cause per played hour of a player.
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
) {
    companion object {
        private val BY_CHAOS = listOf(
            Caps(false, 0, 0, 0, 0, 0.0, 0.0, 0.0, 0, 0, 0),
            Caps(true, 60, 1, 2, 4, 1.0, 1.0, 1.0, 50, 8, 0),
            Caps(true, 120, 1, 4, 8, 2.0, 2.0, 1.5, 200, 16, 0),
            Caps(true, 300, 2, 6, 16, 3.0, 3.0, 2.0, 1_000, 32, 1),
            Caps(true, 600, 3, 10, 24, 4.0, 4.0, 2.5, 3_000, 64, 1),
            Caps(true, 900, 4, 12, 32, 4.0, 5.0, 3.0, 5_000, 128, 2),
        )

        /** The caps at [chaos], 0 to 5 (the configuration is validated). */
        fun of(chaos: Int): Caps = BY_CHAOS[chaos.coerceIn(BY_CHAOS.indices)]
    }
}
