package io.github.clemsix6.narrator2.observer

/**
 * The part of the Observer fed by Fabric API events: blocks broken, damage, deaths and kills,
 * dimension changes, sleep, public chat, joins, leaves and respawns, and the position sampler
 * (M1_M2 §7.2, §7.4). Its persistent attachment types (visited places) are touched in [register].
 *
 * Provisional body (T02): nothing is observed yet. T05 writes it.
 */
object FabricObserver {
    /**
     * Registers the Fabric event handlers. Once, at mod initialization. Handlers find the running
     * server's state with [ObserverState.current] and run their body in [ObserverState.hook].
     */
    fun register() = Unit

    /** Creates the per-server parts (coalescers, sampler) for [state]. When a server starts. */
    @Suppress("UNUSED_PARAMETER")
    fun attach(state: ObserverState) = Unit
}
