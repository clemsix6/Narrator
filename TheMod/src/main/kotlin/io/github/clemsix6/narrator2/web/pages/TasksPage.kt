package io.github.clemsix6.narrator2.web.pages

import io.github.clemsix6.narrator2.command.TraceText
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.trace.TaskDetail
import io.github.clemsix6.narrator2.trace.TaskRecord
import io.github.clemsix6.narrator2.trace.TraceQueries
import io.github.clemsix6.narrator2.web.DashboardQueries
import io.github.clemsix6.narrator2.web.Filter
import io.github.clemsix6.narrator2.web.Page
import io.github.clemsix6.narrator2.web.PageContext
import io.github.clemsix6.narrator2.web.Paging
import io.github.clemsix6.narrator2.web.TaskRow
import io.github.clemsix6.narrator2.web.WebPage
import io.github.clemsix6.narrator2.web.WebRequest
import io.github.clemsix6.narrator2.web.filters
import io.github.clemsix6.narrator2.web.link
import io.github.clemsix6.narrator2.web.pager
import java.time.Instant

/**
 * `/tasks`, `/tasks/<id>` (M6 §8.4): the tasks the Director delegated to its assistants, a page at a time, filtered by
 * role and status; one task in full: its brief, its requests and tool calls, its report or its error. What
 * `/narrator trace task` shows, with nothing cut.
 */
object TasksPage : WebPage {
    override val root = "tasks"

    override val label = "Tasks"

    /** The roles that take tasks: not the Director, which gives them, nor the memory's, which run jobs. */
    private val ROLES = (Role.entries - setOf(Role.DIRECTOR, Role.CHRONICLER, Role.DREAMER)).map { it.id }

    private val FILTERS = listOf(
        Filter.Choice("role", "Role", ROLES),
        Filter.Choice("status", "Status", TaskRecord.Status.entries.map { it.id }),
    )

    /** Statuses of a task that did not end well. */
    private val FAILED = setOf(TaskRecord.Status.FAILED.id, TaskRecord.Status.TIMEOUT.id, TaskRecord.Status.CANCELLED.id)

    override fun render(request: WebRequest, context: PageContext): Page? = when (request.path.size) {
        0 -> list(request, context)
        1 -> request.path.single().removePrefix("T-").toLongOrNull()?.let { task(it, context) }
        else -> null
    }

    private fun list(request: WebRequest, context: PageContext): Page {
        val paging = Paging.of(request)
        val (tasks, more) = paging.rows(context.read { DashboardQueries(it).tasks(request.param("role"), request.param("status"), paging) })
        return Page("Tasks") {
            filters(link(root), request, FILTERS)
            if (tasks.isEmpty()) p("No task here.", "muted")
            else table(listOf("#Task", "Role", "Status", "Queued", "#Took", "Asked by", "Brief", "#Requests")) {
                tasks.forEach { task ->
                    row(if (task.status in FAILED) "failure" else null) {
                        cell("num") { a(link(root, task.taskId), "T-${task.taskId}") }
                        cell(task.role)
                        cell(task.status)
                        cell(context.time(task.createdAt))
                        number(took(task, context.now))
                        cell { task.requestedByTurn?.let { a(link("turns", it), "turn #$it") } }
                        cell(TraceText.abbreviate(task.brief, BRIEF_CHARS))
                        number(task.calls)
                    }
                }
            }
            pager(request, paging, more)
        }
    }

    private fun task(id: Long, context: PageContext): Page? {
        val task = context.read { TraceQueries(it).task(id) } ?: return null
        return Page("Task T-$id") {
            dl {
                item("Role", task.role)
                item("Status") { span(task.status, if (task.status in FAILED) "failure" else null) }
                item("Queued", context.time(task.createdAt))
                item("Started", task.startedAt?.let { context.time(it) } ?: "not yet")
                item("Ended", ended(task, context))
                item("Asked by") { task.requestedByTurn?.let { a(link("turns", it), "turn #$it") } ?: text("no turn") }
                item("Conversation") { task.conversationId?.let { a(link("conversations", it), "$it (transcript)") } ?: text("none yet") }
                item("Cost") { text("${context.costOfCalls(task.calls).text} for ${task.calls.size} ${TraceText.plural(task.calls.size, "request")}") }
            }
            h2("Brief")
            pre(task.brief)
            h2("Requests")
            requests(task.calls, TraceText.refusedTask(task.error), context)
            task.result?.let {
                h2("Report")
                pre(it)
            }
            task.error?.let {
                h2("Error")
                p(it, "failure")
            }
        }
    }

    private fun ended(task: TaskDetail, context: PageContext): String {
        val end = task.finishedAt ?: return task.startedAt?.let { "running for ${TraceText.duration(context.now - it.toEpochMilli())}" } ?: "queued"
        return context.time(end) + (task.startedAt?.let { ", after ${TraceText.duration(end.toEpochMilli() - it.toEpochMilli())}" } ?: "")
    }

    /** How long the task ran, or has been running; nothing while it waits. */
    private fun took(task: TaskRow, now: Long): String? {
        val start = task.startedAt ?: return null
        return TraceText.duration((task.finishedAt ?: Instant.ofEpochMilli(now)).toEpochMilli() - start.toEpochMilli())
    }

    private const val BRIEF_CHARS = 120
}
