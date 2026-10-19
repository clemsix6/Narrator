package io.github.clemsix6.narrator2.mechanics

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.runtime.NarratorServices

private val log = Log.mechanics

/**
 * Creates the server's mechanics engine (M4 §4.6), after the memory and before the Director, on the server
 * thread at `SERVER_STARTED`. Until the assembly (M4-23) it is a [DisabledMechanics] (`not wired yet`), which
 * hides every tool of M4: M4-23 builds the real engine here, and keeps [DisabledMechanics] when
 * `mechanics.enabled` is false, when the memory is unavailable, or when the engine cannot start. It must not
 * touch the Director, created after it.
 */
fun createMechanics(services: NarratorServices): Mechanics {
    log.info("the mechanics are off: not wired yet")
    return DisabledMechanics("not wired yet") { services.settings.get().ui }
}
