package io.github.clemsix6.narrator2.runtime

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.observer.PlacedBlocks

/**
 * The Narrator's state that must stay consistent with the world lives in the world, as persistent Data
 * Attachments saved with their chunk, player or entity (M1_M2 §5.2).
 *
 * A persistent attachment type must be registered before any world loads, or the saved data would be
 * dropped: [registerAll] runs once, at mod initialization. Types live in Kotlin objects, created on
 * first use, so touching a type registers it. The types of the Observer's Fabric module (visited
 * places) and of the guards (guard state, mob expiry) are touched by their own hook registration
 * (`FabricObserver.register`, `registerGuardHooks`), which runs right after, at mod initialization too.
 */
object Attachments {
    fun registerAll() {
        val types = listOf(PlacedBlocks.TYPE)
        Log.main.debug { "attachments: ${types.joinToString { it.identifier().toString() }}" }
    }
}
