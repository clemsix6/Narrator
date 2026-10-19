package io.github.clemsix6.narrator2.forge

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.runtime.NarratorServices

private val log = Log.forge

/**
 * Creates the server's Forge (M5 §11), after the memory and before the mechanics, on the server thread at
 * `SERVER_STARTED`. Until the assembly (task M5-21) it is always a [DisabledForge]: `forge.enabled is false`, or
 * `not wired yet`; the Director sees none of the tools of M5 and no module runs. M5-21 builds the real Forge here,
 * and a [DisabledForge] with its reason when ECJ is missing or the Forge cannot start.
 */
fun createForge(services: NarratorServices): Forge {
    if (!services.settings.get().config.forge.enabled) {
        log.info("the Forge is off: forge.enabled is false")
        return DisabledForge("forge.enabled is false")
    }
    log.info("the Forge is off: not wired yet")
    return DisabledForge("not wired yet")
}
