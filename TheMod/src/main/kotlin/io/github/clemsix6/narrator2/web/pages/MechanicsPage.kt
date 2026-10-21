package io.github.clemsix6.narrator2.web.pages

import io.github.clemsix6.narrator2.web.Page
import io.github.clemsix6.narrator2.web.PageContext
import io.github.clemsix6.narrator2.web.WebPage
import io.github.clemsix6.narrator2.web.WebRequest
import io.github.clemsix6.narrator2.web.notBuiltYet

/**
 * `/mechanics` (M6 §8.4): challenges, duels, laws, plans and modules in progress, their history in the canon, the
 * `mechanic` decisions. Not built yet: M6-12 writes it.
 */
object MechanicsPage : WebPage {
    override val root = "mechanics"

    override val label = "Mechanics"

    override fun render(request: WebRequest, context: PageContext): Page? = if (request.path.isEmpty()) notBuiltYet(label) else null
}
