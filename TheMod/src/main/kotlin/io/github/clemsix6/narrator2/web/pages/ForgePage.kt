package io.github.clemsix6.narrator2.web.pages

import io.github.clemsix6.narrator2.web.Page
import io.github.clemsix6.narrator2.web.PageContext
import io.github.clemsix6.narrator2.web.WebPage
import io.github.clemsix6.narrator2.web.WebRequest
import io.github.clemsix6.narrator2.web.notBuiltYet

/** `/forge`, `/forge/<module>/<version>` (M6 §8.4): the arsenal; the source of a module. Not built yet: M6-12 writes it. */
object ForgePage : WebPage {
    override val root = "forge"

    override val label = "Forge"

    override fun render(request: WebRequest, context: PageContext): Page? = if (request.path.isEmpty()) notBuiltYet(label) else null
}
