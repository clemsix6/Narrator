package io.github.clemsix6.narrator2

import io.github.clemsix6.narrator2.command.NarratorCommand
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.difficulty.registerDifficultyHooks
import io.github.clemsix6.narrator2.guard.registerGuardHooks
import io.github.clemsix6.narrator2.mechanics.registerMechanicsHooks
import io.github.clemsix6.narrator2.observer.registerObserverHooks
import io.github.clemsix6.narrator2.relics.registerRelicsHooks
import io.github.clemsix6.narrator2.runtime.Attachments
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents

/**
 * The mod's entry point. Everything here is registered once per JVM (M1_M2 §2.1): attachment types,
 * hooks and commands. Hooks reach the running server's Narrator through `NarratorRuntime.current` and
 * do nothing without one; the Narrator itself lives from `SERVER_STARTED` to `SERVER_STOPPED`.
 */
object Narrator2 : ModInitializer {
    override fun onInitialize() {
        Attachments.registerAll()
        registerObserverHooks()
        registerGuardHooks()
        registerMechanicsHooks()
        registerRelicsHooks()
        registerDifficultyHooks()

        ServerLifecycleEvents.SERVER_STARTED.register { server -> NarratorRuntime.start(server) }
        ServerLifecycleEvents.SERVER_STOPPING.register { NarratorRuntime.current?.beginShutdown() }
        ServerLifecycleEvents.SERVER_STOPPED.register { NarratorRuntime.current?.finishShutdown() }
        // The world was saved: the Journal checkpoints too, so a copy of the world folder is consistent.
        ServerLifecycleEvents.AFTER_SAVE.register { _, _, _ -> NarratorRuntime.current?.journal?.checkpointAsync() }
        ServerTickEvents.END_SERVER_TICK.register { server -> NarratorRuntime.current?.onTick(server) }
        // While `/narrator restart` runs, a login waits for its end: nobody enters a world without a Narrator (M6 §9).
        ServerLoginConnectionEvents.QUERY_START.register { _, _, _, synchronizer -> NarratorRuntime.restarts.holdLogin(synchronizer) }
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ -> NarratorCommand.register(dispatcher) }
        Log.main.info("mod initialized")
    }
}
