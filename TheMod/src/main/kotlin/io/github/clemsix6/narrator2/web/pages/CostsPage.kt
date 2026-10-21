package io.github.clemsix6.narrator2.web.pages

import io.github.clemsix6.narrator2.web.Page
import io.github.clemsix6.narrator2.web.PageContext
import io.github.clemsix6.narrator2.web.WebPage
import io.github.clemsix6.narrator2.web.WebRequest
import io.github.clemsix6.narrator2.web.notBuiltYet

/**
 * `/costs` (M6 §8.4, §8.7): calls, tokens, cache and dollars by day and hour, by role, purpose and model; latencies.
 * Not built yet: M6-11 writes it.
 */
object CostsPage : WebPage {
    override val root = "costs"

    override val label = "Costs"

    override fun render(request: WebRequest, context: PageContext): Page? = if (request.path.isEmpty()) notBuiltYet(label) else null
}
