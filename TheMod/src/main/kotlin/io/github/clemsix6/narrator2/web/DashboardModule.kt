package io.github.clemsix6.narrator2.web

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.runtime.NarratorServices
import java.net.BindException

private val log = Log.web

/**
 * Creates the server's dashboard (M6 §8.2), the last piece of the runtime's wiring, on the server thread at
 * `SERVER_STARTED`: a [DashboardServer] on `127.0.0.1:<dashboard.port>` when `dashboard.enabled` (false by default:
 * then no port, no thread). A dashboard that cannot start (the port is taken, the Java runtime has no `jdk.httpserver`)
 * is a `WARN` and a [DisabledDashboard] saying why: it never fails the start of the Narrator.
 */
fun createDashboard(services: NarratorServices): Dashboard {
    val config = services.settings.get().config.dashboard
    if (!config.enabled) {
        log.info("no dashboard: ${DisabledDashboard.SWITCHED_OFF}")
        return DisabledDashboard(DisabledDashboard.SWITCHED_OFF)
    }
    val reason = try {
        val dashboard = DashboardServer.start(services, config.port)
        log.info("dashboard on ${dashboard.url} · /narrator dashboard gives operators the login link")
        return dashboard
    } catch (e: BindException) {
        "port ${config.port} of 127.0.0.1 is taken (${e.message})"
    } catch (_: NoClassDefFoundError) {
        // The fallback would be Netty, which the dedicated server ships (M6 §8.2): not needed on a full JDK.
        "the Java runtime has no jdk.httpserver module"
    } catch (e: Exception) {
        "cannot listen on 127.0.0.1:${config.port}: ${e.javaClass.simpleName}: ${e.message}"
    }
    log.warn("no dashboard: $reason")
    return DisabledDashboard(reason)
}

/** The dashboard in the `ready` line: `on http://127.0.0.1:8770/`, or `off (<reason>)`. */
fun dashboardState(dashboard: Dashboard): String = dashboard.url?.let { "on $it" } ?: "off (${dashboard.unavailableReason})"
