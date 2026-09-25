package io.github.clemsix6.narrator2

import io.github.clemsix6.narrator2.command.NarratorCommand
import io.github.clemsix6.narrator2.core.Log
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents

object Narrator2 : ModInitializer {
    override fun onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register { server -> NarratorRuntime.start(server) }
        ServerLifecycleEvents.SERVER_STOPPING.register { NarratorRuntime.stop() }
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ -> NarratorCommand.register(dispatcher) }
        Log.main.info("mod initialized")
    }
}
