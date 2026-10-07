package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.runtime.NarratorServices

/**
 * Creates the server's memory, after the workers and before the Director. Until the real pieces are
 * assembled (M3-18), the server runs with [DisabledMemory], as M2 ran with its seams.
 */
fun createMemory(services: NarratorServices): Memory {
    val memory = DisabledMemory("not wired yet")
    Log.memory.info("${memory.unavailableReason}: the memory is off (Journal ${if (services.journal.available) "available" else "unavailable"})")
    return memory
}
