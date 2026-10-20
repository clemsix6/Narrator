package io.github.clemsix6.narrator2.forge

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.mechanics.ForgeHalt
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.runtime.Phase
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val log = Log.forge

/** How often [watch] looks whether the Forge fell. */
private const val WATCH_MS = 1_000L

/**
 * Creates the server's Forge (M5 §11, task M5-21), after the memory and before the mechanics, on the server thread at
 * `SERVER_STARTED`: a [ForgeService], started off the server thread (it reads the arsenal, loads the modules' runtime,
 * builds the built-in modules, then checks the forged versions; every call waits for the first three). It is a
 * [DisabledForge] with its reason when `forge.enabled` is false, when ECJ is missing from the class path (the service's
 * constructor throws `NoClassDefFoundError`), or when the Forge cannot start: the Director then sees none of the tools of
 * M5, the forger is not offered, and the deployments of the world's state end `restart_failed` (the mechanics keep their
 * modules' part whatever the Forge).
 *
 * Once it runs, the operators online are told when it falls ([watch]). [make] builds the service: the unit tests give
 * their own.
 */
fun createForge(
    services: NarratorServices,
    tellOperators: (String) -> Unit = {},
    make: (NarratorServices) -> ForgeService = { ForgeService(it) },
): Forge {
    if (!services.settings.get().config.forge.enabled) {
        log.info("the Forge is off: forge.enabled is false")
        return DisabledForge("forge.enabled is false")
    }
    val forge = try {
        make(services)
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
    watch(forge, services, tellOperators)
    return forge
}

private fun couldNotStart(e: Exception): Forge {
    log.error("the Forge is off: it could not start", e)
    return DisabledForge("it could not start: ${e.message ?: e.javaClass.simpleName}")
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

/**
 * Tells the operators online, once, that [forge] fell while the server runs (M5-05, M5-10): its compiler stuck, or too
 * many module threads lost (then the modules' part also disables every deployment at its next second). It stays
 * unavailable until the server restarts: deployments and the forger's tests are refused, and the Director and the forger
 * lose its tools at their next chapter or task. Looks once a second, in the services' scope (cancelled when the server
 * stops); a Forge that stops with the server is not told.
 */
private fun watch(forge: Forge, services: NarratorServices, tellOperators: (String) -> Unit) {
    services.scope.launch {
        while (forge.available) delay(WATCH_MS)
        if (services.phase == Phase.STOPPING || services.phase == Phase.STOPPED) return@launch
        val text = "the Forge is unavailable until the server restarts: ${forge.unavailableReason}"
        log.warn("telling the operators online: $text")
        tellOperators(text)
    }
}
