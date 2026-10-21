package io.github.clemsix6.narrator2.web.pages

import io.github.clemsix6.narrator2.web.Page
import io.github.clemsix6.narrator2.web.PageContext
import io.github.clemsix6.narrator2.web.WebPage
import io.github.clemsix6.narrator2.web.WebRequest
import io.github.clemsix6.narrator2.web.notBuiltYet

/**
 * `/memory`, `/memory/<ref>` (M6 §8.4): canon, memories, interactions, notes, ideas, versioned documents and jobs; one
 * row and its audit. Not built yet: M6-12 writes it.
 */
object MemoryPage : WebPage {
    override val root = "memory"

    override val label = "Memory"

    override fun render(request: WebRequest, context: PageContext): Page? = if (request.path.isEmpty()) notBuiltYet(label) else null
}
