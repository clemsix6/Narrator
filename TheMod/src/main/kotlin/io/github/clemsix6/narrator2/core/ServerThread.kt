package io.github.clemsix6.narrator2.core

import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import net.minecraft.server.MinecraftServer
import java.util.concurrent.CompletableFuture

/**
 * Runs [block] on the server thread and suspends until it completes. Anything that touches the
 * world must go through here: Minecraft's game state is single-threaded.
 *
 * `supplyAsync(block, server)` is exactly what `MinecraftServer.submit(Supplier)` does, but the game
 * jar is JSpecify null-marked, so `submit` would refuse a block that may return null.
 */
suspend fun <T> MinecraftServer.onServerThread(timeoutMs: Long = 5_000, block: () -> T): T =
    if (isSameThread) {
        block()
    } else {
        withTimeout(timeoutMs) { CompletableFuture.supplyAsync(block, this@onServerThread).await() }
    }
