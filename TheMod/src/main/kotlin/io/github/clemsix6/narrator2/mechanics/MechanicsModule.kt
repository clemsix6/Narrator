package io.github.clemsix6.narrator2.mechanics

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.mechanics.rule.ServerRuleIds
import io.github.clemsix6.narrator2.runtime.NarratorServices

private val log = Log.mechanics

/**
 * Creates the server's mechanics engine (M4 §4.6), after the memory and before the Director, on the server
 * thread at `SERVER_STARTED`: a [MechanicsService] on the world's state, started. It is a [DisabledMechanics],
 * which hides every tool of M4, when `mechanics.enabled` is false, when the Journal or the memory is unavailable
 * (the mechanics live in the canon), or when the engine cannot start; its [EngineGuardian] then keeps what the
 * engine left in the world from staying frozen there (M4 review, finding 9). It does not touch the Director,
 * created after it.
 */
fun createMechanics(services: NarratorServices): Mechanics {
    if (!services.settings.get().config.mechanics.enabled) {
        log.info("the mechanics are off: mechanics.enabled is false")
        return off(services, "mechanics.enabled is false")
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
    return off(services, reason)
}

/** The engine off for [reason], with its guardian on the world's state when it can start. */
private fun off(services: NarratorServices, reason: String): Mechanics = DisabledMechanics(reason, guardian(services)) { services.settings.get().ui }

/** The guardian of the world's state, which starts at its first tick; null, logged, when it cannot be built (no server: the unit tests). */
private fun guardian(services: NarratorServices): EngineGuardian? = try {
    EngineGuardian(services, StateStore(services.server))
} catch (e: Exception) {
    log.error("the engine's guardian could not start: what the engine left in the world stays as it is until the engine runs", e)
    null
}
