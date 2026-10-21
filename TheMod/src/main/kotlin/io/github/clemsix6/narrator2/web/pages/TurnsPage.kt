package io.github.clemsix6.narrator2.web.pages

import io.github.clemsix6.narrator2.web.Page
import io.github.clemsix6.narrator2.web.PageContext
import io.github.clemsix6.narrator2.web.WebPage
import io.github.clemsix6.narrator2.web.WebRequest
import io.github.clemsix6.narrator2.web.notBuiltYet

/** `/turns`, `/turns/<id>` (M6 §8.4): the Director's turns, filtered; one turn in full. Not built yet: M6-11 writes it. */
object TurnsPage : WebPage {
    override val root = "turns"

    override val label = "Turns"

    override fun render(request: WebRequest, context: PageContext): Page? = if (request.path.isEmpty()) notBuiltYet(label) else null
}
