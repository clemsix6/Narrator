package io.github.clemsix6.narrator2.web.pages

import io.github.clemsix6.narrator2.web.Page
import io.github.clemsix6.narrator2.web.PageContext
import io.github.clemsix6.narrator2.web.WebPage
import io.github.clemsix6.narrator2.web.WebRequest
import io.github.clemsix6.narrator2.web.notBuiltYet

/**
 * `/conversations` (M6 §8.4): the chapters, tasks and memory jobs; `/conversations/<id>` is a transcript
 * ([TranscriptPage]). Not built yet: M6-11 writes it.
 */
object ConversationsPage : WebPage {
    override val root = "conversations"

    override val label = "Conversations"

    override fun render(request: WebRequest, context: PageContext): Page? = when (request.path.size) {
        0 -> notBuiltYet(label)
        1 -> TranscriptPage.render(request.path.single(), request, context)
        else -> null
    }
}
