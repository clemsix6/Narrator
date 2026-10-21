package io.github.clemsix6.narrator2.web.pages

import io.github.clemsix6.narrator2.web.Page
import io.github.clemsix6.narrator2.web.PageContext
import io.github.clemsix6.narrator2.web.WebPage
import io.github.clemsix6.narrator2.web.WebRequest
import io.github.clemsix6.narrator2.web.notBuiltYet

/** `/decisions` (M6 §8.4): the engine's decisions, filtered by kind, decision, subject and period. Not built yet: M6-11 writes it. */
object DecisionsPage : WebPage {
    override val root = "decisions"

    override val label = "Decisions"

    override fun render(request: WebRequest, context: PageContext): Page? = if (request.path.isEmpty()) notBuiltYet(label) else null
}
