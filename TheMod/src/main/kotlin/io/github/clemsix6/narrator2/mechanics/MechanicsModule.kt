package io.github.clemsix6.narrator2.mechanics

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.mechanics.rule.ServerRuleIds
import io.github.clemsix6.narrator2.runtime.NarratorServices

private val log = Log.mechanics

/**
 * Creates the server's mechanics engine (M4 §4.6), after the memory and before the Director, on the server
 * thread at `SERVER_STARTED`: a [MechanicsService] on the world's state, started. It is a [DisabledMechanics],
 * which hides every tool of M4, when `mechanics.enabled` is false, when the Journal or the memory is unavailable
 * (the mechanics live in the canon), or when the engine cannot start. It does not touch the Director, created
 * after it.
 */
fun createMechanics(services: NarratorServices): Mechanics {
    if (!services.settings.get().config.mechanics.enabled) {
        log.info("the mechanics are off: mechanics.enabled is false")
        return DisabledMechanics("mechanics.enabled is false") { services.settings.get().ui }
    }
    if (!services.journal.available) return disabled(services, "the Journal is unavailable: ${services.journal.health().lastError ?: "no reason given"}")
    if (!services.memory.available) return disabled(services, "the memory is unavailable: ${services.memory.unavailableReason}")
    return try {
        val server = services.server
        MechanicsService(services, StateStore(server), ServerRuleIds(server)).also { it.start(server) }
    } catch (e: Exception) {
        log.error("the mechanics engine could not start: ${e.message}", e)
        disabled(services, "it could not start: ${e.message ?: e.javaClass.simpleName}")
    }
}

private fun disabled(services: NarratorServices, reason: String): Mechanics {
    log.warn("the mechanics are off: $reason")
    return DisabledMechanics(reason) { services.settings.get().ui }
}
