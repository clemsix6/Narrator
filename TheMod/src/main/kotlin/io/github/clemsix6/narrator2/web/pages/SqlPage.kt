package io.github.clemsix6.narrator2.web.pages

import io.github.clemsix6.narrator2.web.Page
import io.github.clemsix6.narrator2.web.PageContext
import io.github.clemsix6.narrator2.web.WebPage
import io.github.clemsix6.narrator2.web.WebRequest
import io.github.clemsix6.narrator2.web.notBuiltYet

/**
 * `/sql?q=…` (M6 §8.4): one read-only query, with the access (`SqlAccess.MODEL`: never the private messages) and the
 * limits of the Director's `sql`. Not built yet: M6-12 writes it.
 */
object SqlPage : WebPage {
    override val root = "sql"

    override val label = "SQL"

    override fun render(request: WebRequest, context: PageContext): Page? = if (request.path.isEmpty()) notBuiltYet(label) else null
}
