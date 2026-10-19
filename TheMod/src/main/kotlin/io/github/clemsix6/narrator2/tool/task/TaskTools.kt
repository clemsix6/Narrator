package io.github.clemsix6.narrator2.tool.task

import io.github.clemsix6.narrator2.agent.Workers
import io.github.clemsix6.narrator2.tool.ToolText.duration
import io.github.clemsix6.narrator2.director.EventFormatter.Companion.quote
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Doc
import io.github.clemsix6.narrator2.tool.MaxLen
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolEnv
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.trace.TaskRecord
import io.github.clemsix6.narrator2.trace.TaskRecord.Status
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Delegation to the workers (M1_M2 §9.6): `delegate`, `tasks`. `ToolCatalog` builds the family once
 * per conversation; both tools are shown only when a worker role is wired ([ToolEnv.workerRoles]).
 */
@Suppress("UNUSED_PARAMETER")
fun taskTools(services: NarratorServices): List<NarratorTool<*>> = listOf(DelegateTool(), TasksTool())

/**
 * The worker roles `delegate` offers: those wired, the Analyst (M2) and the scout (M4 §14). Both need the Journal
 * (`Workers.roles`): without it, `delegate` is not shown at all.
 */
@Serializable
enum class WorkerRole(val role: Role) {
    @SerialName("analyst")
    ANALYST(Role.ANALYST),

    @SerialName("scout")
    SCOUT(Role.SCOUT),
}

/** Whether a worker role that `delegate` offers is wired. */
private fun anyWorker(env: ToolEnv): Boolean = WorkerRole.entries.any { it.role in env.workerRoles }

@Serializable
data class DelegateArgs(
    @Doc(
        "analyst: questions on what the players did and are doing, answered from the Journal and the Oracle. " +
            "scout: places in the world, for a duel arena, a challenge or a plan. Both read only.",
    )
    val role: WorkerRole,
    @Doc(
        "Everything the worker needs, as it knows nothing else: the precise question, the players, the period, " +
            "and what you will do with the answer.",
    )
    @MaxLen(DelegateTool.MAX_BRIEF)
    val brief: String,
) {
    init {
        require(brief.trim().length >= DelegateTool.MIN_BRIEF) {
            "brief is too short (at least ${DelegateTool.MIN_BRIEF} characters): say what to find out, about whom, over which period"
        }
    }
}

/**
 * `delegate`: queues a task for a worker and returns at once, with the task's id; the Director's turn
 * goes on. The worker's report comes back later as a `TASK` event.
 */
class DelegateTool : NarratorTool<DelegateArgs>(
    name = "delegate",
    description = "Hands a question to a worker that works in the background, with its own conversation and tools. " +
        "Returns at once with a task id: keep playing, or end your turn. The worker's report arrives later as a TASK " +
        "event, or why it failed. The analyst answers questions on the players' history and present state with dated " +
        "figures and the queries behind them. The scout finds exact places: flat open ground for an arena or a scene " +
        "(which only it can survey), the structures, biomes and named places near the players, where they go. Neither " +
        "speaks to players nor acts. What a single call of your own tools answers, ask it yourself.",
    args = DelegateArgs.serializer(),
    effect = ToolEffect.TASK,
) {
    override fun isAvailable(env: ToolEnv): Boolean = anyWorker(env)

    override suspend fun run(ctx: ToolContext, args: DelegateArgs): ToolResult {
        val workers = ctx.services.workers
        val role = args.role.role
        val id = workers.submit(role, args.brief.trim(), ctx.caller).getOrElse { return ToolResult.failed(it.message ?: "the task was refused") }
        val queued = workers.list().firstOrNull { it.taskId == id }?.status == Status.QUEUED
        val state = if (queued) "queued (the workers are busy: it starts when a running task ends)" else "started"
        return ToolResult.ok("ok: task T-$id ${role.id} $state; its report will arrive as a TASK event")
    }

    companion object {
        const val MIN_BRIEF = 20
        const val MAX_BRIEF = 2_000
    }
}

@Serializable
enum class TasksAction {
    @SerialName("list")
    LIST,

    @SerialName("cancel")
    CANCEL,
}

@Serializable
data class TasksArgs(
    @Doc("list: your tasks running, queued or ended in the last 30 minutes. cancel: stop task_id; its report will never come.")
    val action: TasksAction,
    @Doc("The task to cancel: 12 for T-12.")
    @SerialName("task_id")
    val taskId: Long? = null,
) {
    init {
        require(action != TasksAction.CANCEL || taskId != null) { "task_id is required to cancel a task" }
    }
}

/** `tasks`: lists the Director's tasks, or cancels one. */
class TasksTool : NarratorTool<TasksArgs>(
    name = "tasks",
    description = "Lists the tasks you delegated (running, queued, or ended in the last 30 minutes, with their brief or " +
        "the start of their report), or cancels one that has not ended.",
    args = TasksArgs.serializer(),
    effect = ToolEffect.TASK,
) {
    override fun isAvailable(env: ToolEnv): Boolean = anyWorker(env)

    override suspend fun run(ctx: ToolContext, args: TasksArgs): ToolResult {
        val workers = ctx.services.workers
        return when (args.action) {
            TasksAction.LIST -> ToolResult.ok(render(workers.list(), ctx.clock.millis()))
            TasksAction.CANCEL -> {
                val id = checkNotNull(args.taskId)
                val wasQueued = workers.list().firstOrNull { it.taskId == id }?.status == Status.QUEUED
                workers.cancel(id).fold(
                    onSuccess = { ToolResult.ok("ok: task T-$id cancelled${if (wasQueued) " before it started" else ""}; its report will never come") },
                    onFailure = { ToolResult.invalid(it.message ?: "task T-$id cannot be cancelled") },
                )
            }
        }
    }

    companion object {
        /** Longest brief or report excerpt in a listing, in characters. */
        const val EXCERPT_MAX = 160

        /** The listing of [tasks] at [now]: a count line, then one line per task, oldest first. */
        fun render(tasks: List<TaskRecord>, now: Long): String {
            if (tasks.isEmpty()) return "no task running, queued or ended in the last ${Workers.KEEP_ENDED_MS / 60_000} minutes"
            val running = tasks.count { it.status == Status.RUNNING }
            val queued = tasks.count { it.status == Status.QUEUED }
            val ended = tasks.size - running - queued
            val head = "tasks: $running running, $queued queued, $ended ended in the last ${Workers.KEEP_ENDED_MS / 60_000} minutes"
            return (listOf(head) + tasks.map { line(it, now) }).joinToString("\n")
        }

        private fun line(task: TaskRecord, now: Long): String {
            val head = "T-${task.taskId} ${task.role.id}"
            val took = task.startedAt?.let { start -> task.finishedAt?.let { ", in ${duration(it.toEpochMilli() - start.toEpochMilli())}" } }.orEmpty()
            val ended = task.finishedAt?.let { "${duration(now - it.toEpochMilli())} ago$took" }
            return when (task.status) {
                Status.QUEUED -> "$head queued for ${duration(now - task.createdAt.toEpochMilli())}: ${quote(task.brief, EXCERPT_MAX)}"
                Status.RUNNING -> "$head running for ${duration(now - (task.startedAt ?: task.createdAt).toEpochMilli())}: ${quote(task.brief, EXCERPT_MAX)}"
                Status.DONE -> "$head done $ended: ${quote(task.result.orEmpty(), EXCERPT_MAX)}"
                Status.FAILED -> "$head failed $ended: ${quote(task.error.orEmpty(), EXCERPT_MAX)}"
                Status.TIMEOUT -> "$head timed out $ended"
                Status.CANCELLED -> "$head cancelled $ended"
            }
        }
    }
}
