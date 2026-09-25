package io.github.clemsix6.narrator2.testing

import io.github.clemsix6.narrator2.agent.WorkersControl
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.trace.TaskRecord
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** Workers for tests: they accept tasks for [roles] and record them, without running anything. */
class FakeWorkers(override val roles: Set<Role> = setOf(Role.ANALYST)) : WorkersControl {
    /** Tasks submitted: id, role, brief and caller. */
    val submitted = CopyOnWriteArrayList<Submitted>()
    val cancelled = CopyOnWriteArrayList<Long>()
    private val ids = AtomicLong(1)

    data class Submitted(val taskId: Long, val role: Role, val brief: String, val caller: Caller)

    override fun submit(role: Role, brief: String, caller: Caller): Result<Long> {
        if (role !in roles) return Result.failure(IllegalArgumentException("no worker for role ${role.id}"))
        val id = ids.getAndIncrement()
        submitted += Submitted(id, role, brief, caller)
        return Result.success(id)
    }

    override fun list(): List<TaskRecord> = emptyList()

    override fun cancel(taskId: Long): Result<Unit> {
        if (submitted.none { it.taskId == taskId }) return Result.failure(IllegalArgumentException("unknown task T-$taskId"))
        cancelled += taskId
        return Result.success(Unit)
    }
}
