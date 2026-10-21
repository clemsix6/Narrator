package io.github.clemsix6.narrator2.web.pages

import io.github.clemsix6.narrator2.web.Page
import io.github.clemsix6.narrator2.web.PageContext
import io.github.clemsix6.narrator2.web.WebPage
import io.github.clemsix6.narrator2.web.WebRequest
import io.github.clemsix6.narrator2.web.notBuiltYet

/**
 * `/style` (M6 §8.4): diversity by session, registers and reactions, tics and crutch words, tone flags with their line,
 * interventions by lever. Not built yet: M6-12 writes it.
 */
object StylePage : WebPage {
    override val root = "style"

    override val label = "Style"

    override fun render(request: WebRequest, context: PageContext): Page? = if (request.path.isEmpty()) notBuiltYet(label) else null
}
