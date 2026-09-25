package io.github.clemsix6.narrator2.agent

import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.trace.TaskRecord

/**
 * The workers the Director delegates long work to (the Analyst in M2). Tasks run in parallel up to
 * `workers.maxConcurrent`, the others wait in line; each ends with a `DirectorSignal.TaskDone`.
 * Implemented by `Workers`; faked in tests.
 */
interface WorkersControl {
    /** Worker roles that accept tasks right now; empty while none is wired or the server stops. */
    val roles: Set<Role>

    /**
     * Queues a task for [role] with [brief], asked by [caller]; returns its id at once, without
     * waiting for the task. Fails (with an English message for the model) for a role not in [roles]
     * or while the server stops.
     */
    fun submit(role: Role, brief: String, caller: Caller): Result<Long>

    /** Running and queued tasks, and those that ended in the last 30 minutes, oldest first. */
    fun list(): List<TaskRecord>

    /**
     * Cancels a queued or running task; its conversation is left unchanged. Fails (with an English
     * message for the model) for an unknown or already finished task.
     */
    fun cancel(taskId: Long): Result<Unit>
}
