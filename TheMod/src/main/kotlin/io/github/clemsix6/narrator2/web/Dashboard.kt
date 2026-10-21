package io.github.clemsix6.narrator2.web

/**
 * The web dashboard (M6 §8): a read-only HTTP server bound to `127.0.0.1`, without JavaScript, behind a token drawn at
 * each start. The runtime builds it at the end of its wiring (`createDashboard`) and stops it first at `SERVER_STOPPING`;
 * `/narrator dashboard` gives the operators its link. Nothing in the Narrator depends on it.
 */
interface Dashboard {
    /** Where it serves, e.g. `http://127.0.0.1:8770/`; null when there is no dashboard. */
    val url: String?

    /** Why there is no dashboard (`dashboard.enabled is false`, the port is taken…); null when it serves. */
    val unavailableReason: String?

    /** Stops serving at once; a second call does nothing. Server thread; never waits. */
    fun stop()
}

/** No dashboard, and why ([reason]): nothing listens, nothing to stop. */
class DisabledDashboard(private val reason: String) : Dashboard {
    override val url: String? get() = null

    override val unavailableReason: String get() = reason

    override fun stop() = Unit
}
