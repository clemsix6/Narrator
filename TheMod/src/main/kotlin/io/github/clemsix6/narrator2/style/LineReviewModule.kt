package io.github.clemsix6.narrator2.style

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.runtime.NarratorServices

private val log = Log.style

/**
 * Starts the line review of a server that starts (M6 §3.3), right after the style watch ([NarratorServices.style]), on
 * the server thread at `SERVER_STARTED`: the small model labels each line of the Director once it was said, for the
 * watch and `line_labels`. Not wired yet: no line is reviewed.
 */
@Suppress("UNUSED_PARAMETER") // the review will follow the bus and call the reflexes' client
fun createLineReviewer(services: NarratorServices) {
    log.info("the line review is off: not wired yet")
}
