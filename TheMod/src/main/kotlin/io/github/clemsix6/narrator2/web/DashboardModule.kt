package io.github.clemsix6.narrator2.web

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.runtime.NarratorServices

private val log = Log.web

/**
 * Creates the server's dashboard (M6 §8.2), the last piece of the runtime's wiring, on the server thread at
 * `SERVER_STARTED`. Not wired yet: always a [DisabledDashboard], with `dashboard.enabled is false` or `not wired yet`.
 * A dashboard that cannot start never fails the start of the Narrator.
 */
fun createDashboard(services: NarratorServices): Dashboard {
    if (!services.settings.get().config.dashboard.enabled) {
        log.info("no dashboard: dashboard.enabled is false")
        return DisabledDashboard("dashboard.enabled is false")
    }
    log.info("no dashboard: not wired yet")
    return DisabledDashboard("not wired yet")
}

/** The dashboard in the `ready` line: `on http://127.0.0.1:8770/`, or `off (<reason>)`. */
fun dashboardState(dashboard: Dashboard): String = dashboard.url?.let { "on $it" } ?: "off (${dashboard.unavailableReason})"
