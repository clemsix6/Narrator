package io.github.clemsix6.narrator2.web.pages

import io.github.clemsix6.narrator2.web.Page
import io.github.clemsix6.narrator2.web.PageContext
import io.github.clemsix6.narrator2.web.WebPage
import io.github.clemsix6.narrator2.web.WebRequest
import io.github.clemsix6.narrator2.web.notBuiltYet

/** `/players`, `/players/<name>` (M6 §8.4): the players; one sheet and its timeline. Not built yet: M6-12 writes it. */
object PlayersPage : WebPage {
    override val root = "players"

    override val label = "Players"

    override fun render(request: WebRequest, context: PageContext): Page? = if (request.path.isEmpty()) notBuiltYet(label) else null
}
