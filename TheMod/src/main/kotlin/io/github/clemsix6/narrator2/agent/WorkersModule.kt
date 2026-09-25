package io.github.clemsix6.narrator2.agent

import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.trace.TaskRecord

/**
 * Creates the workers of a server that starts (M1_M2 §13). Their coroutines run in `services.scope`,
 * which the runtime cancels when the server stops; they take no task once `services.phase` is no
 * longer `RUNNING`.
 *
 * Provisional body (T02): no worker role is wired, every task is refused. T20 replaces it.
 */
@Suppress("UNUSED_PARAMETER")
fun createWorkers(services: NarratorServices): WorkersControl = NoWorkers

/** No worker at all. */
private object NoWorkers : WorkersControl {
    override val roles: Set<Role> = emptySet()

    override fun submit(role: Role, brief: String, caller: Caller): Result<Long> =
        Result.failure(IllegalStateException("no worker role is available"))

    override fun list(): List<TaskRecord> = emptyList()

    override fun cancel(taskId: Long): Result<Unit> = Result.failure(IllegalStateException("unknown task T-$taskId"))
}
