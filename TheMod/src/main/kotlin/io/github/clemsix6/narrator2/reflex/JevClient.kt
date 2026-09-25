package io.github.clemsix6.narrator2.reflex

import io.github.clemsix6.narrator2.config.JevConfig

/**
 * Client of Jev (TypeSafe), the fast classifier behind the reflexes (M1_M2 §11). It is part of the
 * settings: `/narrator reload` builds a new one and closes the old one once its calls are over.
 *
 * Provisional body (T02): Jev is not wired yet, [create] gives no client and the reflexes fall back
 * to their heuristic. T10 writes the client and keeps [create], [closeWhenIdle] and [close].
 */
class JevClient private constructor() : AutoCloseable {
    /** Closes the client once its calls in flight are over: the settings were replaced. */
    fun closeWhenIdle() = close()

    /** Closes the client now: the server is stopping and its callers are cancelled. */
    override fun close() = Unit

    companion object {
        /**
         * A client when Jev is enabled and the environment variable named by `jev.apiKeyEnv` is set,
         * looked up with [env]; null otherwise.
         */
        @Suppress("UNUSED_PARAMETER")
        fun create(config: JevConfig, env: (String) -> String?): JevClient? = null
    }
}
