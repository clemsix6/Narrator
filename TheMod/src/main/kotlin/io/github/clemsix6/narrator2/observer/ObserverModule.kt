package io.github.clemsix6.narrator2.observer

import io.github.clemsix6.narrator2.runtime.NarratorServices

/*
 * Wiring of the Observer, which turns what players do into events on the bus (M1_M2 §7). Two modules
 * share the work and no file: FabricObserver (Fabric API events and the position sampler, T05) and
 * ObserverHooks (the entry points of the mixins and the statistics, T06).
 */

/** Registers the Observer's hooks. Once, at mod initialization. */
fun registerObserverHooks() {
    FabricObserver.register()
    ObserverHooks.register()
}

/** Creates the Observer's state for a server that starts, and attaches both modules to it. */
fun installObserver(services: NarratorServices): ObserverState {
    val state = ObserverState(services)
    FabricObserver.attach(state)
    ObserverHooks.attach(state)
    return state
}
