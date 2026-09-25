package io.github.clemsix6.narrator2.runtime

import net.minecraft.server.MinecraftServer

/**
 * The overworld's game time, readable from any thread. The event bus stamps every envelope with it,
 * and some events are published off the server thread (the Narrator's lines, for one): reading the
 * level from there would race with the server, so this copy is refreshed at the end of every tick.
 */
class TickClock {
    @Volatile
    var gameTime: Long = 0
        private set

    /** Copies the overworld's game time. Server thread only. */
    fun update(server: MinecraftServer) {
        gameTime = server.overworld().gameTime
    }
}
