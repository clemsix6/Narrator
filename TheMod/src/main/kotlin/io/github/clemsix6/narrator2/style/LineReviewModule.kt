package io.github.clemsix6.narrator2.style

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.runtime.NarratorServices

private val log = Log.style

/**
 * Starts the line review of a server that starts (M6 §3.3), right after the style watch ([NarratorServices.style]), on
 * the server thread at `SERVER_STARTED`: the reflexes' model labels each line of the Director once it was said, for the
 * watch and `line_labels` ([LineReviewer]). Without the Journal, nothing would keep the labels: no review. Otherwise the
 * reviewer follows the bus whatever the settings, which it reads at each line (`/narrator reload` may turn the reflexes
 * or `style.lineReview` on or off).
 */
fun createLineReviewer(services: NarratorServices) {
    if (!services.journal.available) {
        log.info("the line review is off: the Journal is unavailable")
        return
    }
    LineReviewer(services).start()
    val settings = services.settings.get()
    val client = settings.reflex
    log.info(
        when {
            !settings.config.style.lineReview -> "line review: idle (style.lineReview is false)"
            client == null -> "line review: idle until the reflexes have a model (reflex.enabled, LLMGW key)"
            else -> "line review: ${client.model} labels each line of the Director once said"
        },
    )
}
