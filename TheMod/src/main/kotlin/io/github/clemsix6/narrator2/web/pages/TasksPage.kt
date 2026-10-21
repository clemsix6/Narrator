package io.github.clemsix6.narrator2.web.pages

import io.github.clemsix6.narrator2.web.Page
import io.github.clemsix6.narrator2.web.PageContext
import io.github.clemsix6.narrator2.web.WebPage
import io.github.clemsix6.narrator2.web.WebRequest
import io.github.clemsix6.narrator2.web.notBuiltYet

/** `/tasks`, `/tasks/<id>` (M6 §8.4): the assistants' tasks; one task, its brief, report and requests. Not built yet: M6-11 writes it. */
object TasksPage : WebPage {
    override val root = "tasks"

    override val label = "Tasks"

    override fun render(request: WebRequest, context: PageContext): Page? = if (request.path.isEmpty()) notBuiltYet(label) else null
}
