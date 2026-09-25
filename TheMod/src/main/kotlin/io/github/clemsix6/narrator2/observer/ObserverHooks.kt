package io.github.clemsix6.narrator2.observer

/**
 * The part of the Observer fed by mixins (blocks placed, consumption, crafting, trades, containers,
 * advancements, commands, projectiles, explosions, mounts…) and the statistics (M1_M2 §7.2, §7.6).
 * The mixins of `narrator2.observer.mixins.json` call its `@JvmStatic` entry points.
 *
 * Provisional body (T02): nothing is observed yet. T06 writes it.
 */
object ObserverHooks {
    /**
     * Registers the Fabric callbacks this module needs (`UseBlockCallback`, the statistics snapshot
     * timer…). Once, at mod initialization. Entry points find the running server's state with
     * [ObserverState.current] and run their body in [ObserverState.hook].
     */
    fun register() = Unit

    /** Creates the per-server parts (aggregators, statistics) for [state]. When a server starts. */
    @Suppress("UNUSED_PARAMETER")
    fun attach(state: ObserverState) = Unit
}
