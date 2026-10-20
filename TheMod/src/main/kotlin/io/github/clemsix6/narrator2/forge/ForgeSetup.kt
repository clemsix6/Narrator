package io.github.clemsix6.narrator2.forge

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.mechanics.ForgeHalt
import io.github.clemsix6.narrator2.runtime.NarratorServices

private val log = Log.forge

/**
 * Creates the server's Forge (M5 §11, task M5-21), after the memory and before the mechanics, on the server thread at
 * `SERVER_STARTED`: a [ForgeService], started off the server thread (it reads the arsenal, loads the modules' runtime,
 * builds the built-in modules, then checks the forged versions; every call waits for the first three). It is a
 * [DisabledForge] with its reason when `forge.enabled` is false, when ECJ is missing from the class path (the service's
 * constructor throws `NoClassDefFoundError`), or when the Forge cannot start: the Director then sees none of the tools of
 * M5, the forger is not offered, and the deployments of the world's state end `restart_failed` (the mechanics keep their
 * modules' part whatever the Forge).
 *
 * Once it runs, the operators online are told, once, when it falls while the server runs ([fell]). [make] builds the
 * service with what to do then: the unit tests give their own.
 */
fun createForge(
    services: NarratorServices,
    tellOperators: (String) -> Unit = {},
    make: (NarratorServices, onUnavailable: (String) -> Unit) -> ForgeService = { s, onUnavailable -> ForgeService(s, onUnavailable = onUnavailable) },
): Forge {
    if (!services.settings.get().config.forge.enabled) {
        log.info("the Forge is off: forge.enabled is false")
        return DisabledForge("forge.enabled is false")
    }
    val forge = try {
        make(services) { reason -> fell(reason, tellOperators) }
    } catch (e: LinkageError) {
        log.error("the Forge is off: ECJ is missing from the class path", e)
        return DisabledForge("ECJ is missing: ${e.message ?: e.javaClass.simpleName}")
    } catch (e: Exception) {
        return couldNotStart(e)
    }
    try {
        forge.start()
    } catch (e: Exception) {
        forge.stop()
        return couldNotStart(e)
    }
    return forge
}

private fun couldNotStart(e: Exception): Forge {
    log.error("the Forge is off: it could not start", e)
    return DisabledForge("it could not start: ${e.message ?: e.javaClass.simpleName}")
}

/**
 * The Forge fell while the server runs (`ForgeService.onUnavailable`, told once, never at the server's stop): its
 * compiler stuck, too many module threads lost (then the modules' part also disables every deployment at its next
 * second), or its arsenal unreadable at its start. It stays unavailable until the server restarts: deployments and the
 * forger's tests are refused, and the Director and the forger lose its tools at their next chapter or task. The operators
 * online are told ([tellOperators]: a yellow line, on the server thread).
 */
private fun fell(reason: String, tellOperators: (String) -> Unit) {
    val text = "the Forge is unavailable until the server restarts: $reason"
    log.warn("telling the operators online: $text")
    tellOperators(text)
}

/**
 * The Forge in the `ready` line of the server's start: `on`; `switched off by Clement` while an operator's switch is set
 * ([halt]: nothing is deployed nor restarted until `/narrator forge on`); `off (<why>)` when it does not run.
 */
fun forgeState(forge: Forge, halt: ForgeHalt?): String {
    val switch = halt?.let { "switched off by ${it.by}" }
    if (forge.available) return switch ?: "on"
    return "off (${forge.unavailableReason})" + switch?.let { ", $it" }.orEmpty()
}
