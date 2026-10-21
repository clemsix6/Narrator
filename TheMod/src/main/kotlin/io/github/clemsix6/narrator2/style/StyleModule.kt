package io.github.clemsix6.narrator2.style

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.runtime.NarratorServices

private val log = Log.style

/**
 * Creates the server's style watch (M6 §3.4), after the memory and before the line review and the Director, on the
 * server thread at `SERVER_STARTED`. Not wired yet: always [DisabledStyle], which says nothing and refuses nothing.
 */
@Suppress("UNUSED_PARAMETER") // the watch will read the Journal and follow the bus
fun createStyle(services: NarratorServices): StyleWatch {
    log.info("the style watch is off: not wired yet")
    return DisabledStyle
}
