package io.github.clemsix6.narrator2.agent

import com.anthropic.models.messages.TextBlockParam
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.tool.ToolText.duration
import io.github.clemsix6.narrator2.journal.IdKind
import io.github.clemsix6.narrator2.llm.Conversation
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.runtime.Phase
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolCatalog
import io.github.clemsix6.narrator2.tool.ToolEnv
import io.github.clemsix6.narrator2.tool.ToolRunner
import io.github.clemsix6.narrator2.tool.ToolSet
import io.github.clemsix6.narrator2.trace.ConversationRecord
import io.github.clemsix6.narrator2.trace.TaskRecord
import io.github.clemsix6.narrator2.trace.TaskRecord.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * The workers the Director delegates long work to (M1_M2 §13): the Analyst (M2) and the scout (M4 §14).
 *
 * - **Never waits.** [submit] queues the task and returns its id at once; the Director's turn goes on.
 * - **Its own conversation.** Each task is one conversation with Claude, `Task T-12 (analyst)`, with the
 *   role's prompt and read-only tools (`ToolCatalog.toolSetFor`), and its own id; its `X-Session-ID` is
 *   the role's (`narrator2-analyst`), so that each task reads the prefix the previous ones cached. It opens
 *   with `<task id="T-12" from="director">brief</task>` and a `<context …/>`
 *   line (the names of the players online through the common escape, `core/ModelText`), and runs a
 *   single turn of [AgentLoop] within `workers.maxSteps` requests and `workers.timeoutSeconds`. The text
 *   of the last reply is the report (at most [RESULT_MAX_CHARS]).
 * - **In parallel, then in line.** At most `workers.maxConcurrent` tasks run at once, each in its own
 *   coroutine of `services.scope`; the others wait, first come first served, [MAX_QUEUED] at most (one
 *   more is refused). Settings are read when used.
 * - **Back to the Director.** A task that ends posts a [DirectorSignal.TaskDone]: its report when done,
 *   else why it failed or timed out. A task the Director cancelled posts nothing (the `tasks` call
 *   already told it), nor does a task stopped by the server.
 * - **No retry.** A refusal, a cut-off or empty reply, a step limit or a gateway error fails the task
 *   with the reason; HTTP 402 fails it with `budget: …`.
 * - **Traced.** One `tasks` row at each change (queued, running, ended) and one `conversations` row per
 *   task (with its transcript); the calls and tool calls are traced by [AgentLoop] and `ToolRunner`.
 *
 * Thread-safe: the tools call it from the Director's turn, the tasks' coroutines end on any thread.
 *
 * @param toolSet the tools of a task's conversation: `ToolCatalog.toolSetFor` in production.
 */
class Workers(
    private val services: NarratorServices,
    private val toolSet: (Role, ToolEnv) -> ToolSet = { role, env -> ToolCatalog.toolSetFor(role, env, services) },
) : WorkersControl {
    private val log = Log.worker
    private val clock = services.clock
    private val agent = AgentLoop(services, ToolRunner(services))

    private val lock = Any()
    private val tasks = LinkedHashMap<Long, Task>() // guarded by lock: queued, running, and ended in the last KEEP_ENDED_MS
    private val queue = ArrayDeque<Task>() // guarded by lock
    private var running = 0 // guarded by lock: tasks whose coroutine has not completed

    /**
     * The workers are offered only when the Journal opened (the Analyst works on it first, the scout reads the
     * places players haunt in it), and never while the server stops.
     */
    override val roles: Set<Role>
        get() = if (services.phase == Phase.RUNNING && services.journal.available) WIRED else emptySet()

    override fun submit(role: Role, brief: String, caller: Caller): Result<Long> {
        if (services.phase != Phase.RUNNING) return Result.failure(IllegalStateException("the server is stopping: no task can start"))
        if (role !in roles) {
            val why = if (role in WIRED) "the Journal is unavailable" else "there is no ${role.id} yet"
            return Result.failure(IllegalArgumentException("no ${role.id} can take a task: $why"))
        }
        val now = clock.instant()
        val id = synchronized(lock) {
            if (queue.size >= MAX_QUEUED) {
                return Result.failure(
                    IllegalStateException("$MAX_QUEUED tasks already wait for a worker: wait for one to start, or cancel one with tasks; ${summary()}"),
                )
            }
            forgetOldTasks(now)
            val task = Task(TaskRecord(services.trace.nextId(IdKind.TASK), role, brief, Status.QUEUED, now, null, null, caller.turnId, null, null, null))
            tasks[task.id] = task
            queue.addLast(task)
            services.trace.task(task.record)
            task.id
        }
        log.info("T-$id ${role.id} queued by turn ${caller.turnId}: ${brief.take(LOG_BRIEF_CHARS)}")
        dispatch()
        return Result.success(id)
    }

    override fun list(): List<TaskRecord> = synchronized(lock) {
        forgetOldTasks(clock.instant())
        tasks.values.map { it.record }
    }

    /**
     * A queued task leaves the line at once. A running one is marked cancelled at once (the `tasks` row
     * and [list] say so); its coroutine is cancelled, which closes its stream and commits nothing, and
     * its place goes to the next task once the coroutine has completed.
     */
    override fun cancel(taskId: Long): Result<Unit> {
        val job: Job?
        synchronized(lock) {
            val task = tasks[taskId] ?: return Result.failure(IllegalArgumentException("no task T-$taskId; ${summary()}"))
            if (task.record.status.finished) {
                return Result.failure(IllegalArgumentException("task T-$taskId already ended (${task.record.status.id})"))
            }
            task.cancelled = true
            job = task.job
            if (job == null) {
                queue.remove(task)
                end(task, Status.CANCELLED, null, "cancelled by the Director before it started")
            } else {
                end(task, Status.CANCELLED, null, "cancelled by the Director")
            }
        }
        if (job == null) log.info("T-$taskId cancelled by the Director before it started") else job.cancel(CancellationException("cancelled by the Director"))
        return Result.success(Unit)
    }

    // ── Running ─────────────────────────────────────────────────────────────────────────────────────

    /** Starts queued tasks while there is room; while the server stops, cancels those still waiting. */
    private fun dispatch() {
        val starting = synchronized(lock) {
            if (services.phase != Phase.RUNNING) {
                while (queue.isNotEmpty()) end(queue.removeFirst(), Status.CANCELLED, null, "the server stopped before it started")
                return
            }
            val max = services.settings.get().config.workers.maxConcurrent
            buildList {
                while (running < max && queue.isNotEmpty()) {
                    val task = queue.removeFirst()
                    val conversationId = "${task.record.role.id}-${UUID.randomUUID().toString().take(8)}"
                    task.record = task.record.copy(status = Status.RUNNING, startedAt = clock.instant(), conversationId = conversationId)
                    services.trace.task(task.record)
                    task.job = services.scope.launch(CoroutineName("narrator2-task-${task.id}"), CoroutineStart.LAZY) {
                        task.ending = work(task, conversationId)
                    }
                    running++
                    add(task)
                }
            }
        }
        for (task in starting) {
            val job = task.job ?: continue
            // Runs exactly once, even for a coroutine cancelled before it started.
            job.invokeOnCompletion { cause -> completed(task, cause) }
            job.start()
        }
    }

    /** The task's single turn; returns how it ended. Throws only the cancellation of its coroutine. */
    private suspend fun work(task: Task, conversationId: String): Ending {
        val record = task.record
        val settings = services.settings.get()
        val config = settings.config.workers
        val started = record.startedAt ?: clock.instant()
        return try {
            val tools = toolSet(record.role, ToolEnv(services.journal.available, workerRoles = emptySet(), memoryAvailable = services.memory.available))
            val system = listOf(TextBlockParam.builder().text(settings.prompts.get(record.role.id)).build())
            val conversation = Conversation(record.role, "Task T-${task.id} (${record.role.id})", system, tools, services.transcripts, conversationId)
            val row = conversationRecord(task, conversation, started)
            synchronized(lock) {
                task.conversation = conversation
                task.opened = row
            }
            services.trace.conversation(row)
            services.transcripts.note(conversation, "task T-${task.id} for turn ${record.requestedByTurn}, with ${tools.tools.size} tools: ${tools.names.joinToString()}")
            log.info("T-${task.id} ${record.role.id} started: $conversationId, ${tools.tools.size} tools")
            val caller = Caller(record.role, conversationId, turnId = null, taskId = task.id)
            val opening = Pending.text(opening(task))
            val outcome = withTimeoutOrNull(config.timeoutSeconds * 1_000L) {
                agent.runTurn(conversation, opening, caller, TurnLimits(config.maxSteps))
            }
            if (outcome == null) Ending(Status.TIMEOUT, null, "no report within ${duration(config.timeoutSeconds * 1_000L)}") else endingOf(outcome)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("T-${task.id} failed on an engine error", e)
            Ending(Status.FAILED, null, "engine error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** The first message of a task: the brief, then when it is and who is online. */
    private fun opening(task: Task): String {
        val zone = services.settings.get().config.journal.timeZone.takeIf { it.isNotBlank() }?.let(ZoneId::of) ?: clock.zone
        val now = STAMP.withZone(zone).format(clock.instant())
        val online = services.players.online.joinToString(", ") { ModelText.attribute(it.ref.name) }.ifEmpty { "none" }
        return "<task id=\"T-${task.id}\" from=\"director\">\n${task.record.brief}\n</task>\n<context now=\"$now\" players_online=\"$online\"/>"
    }

    /** What a turn's [outcome] makes of the task. */
    private fun endingOf(outcome: TurnOutcome): Ending = when (outcome) {
        is TurnOutcome.Ended -> {
            val report = outcome.finalText.trim()
            if (report.isEmpty()) {
                Ending(Status.FAILED, null, "empty report: the last reply had no text")
            } else {
                Ending(Status.DONE, if (report.length <= RESULT_MAX_CHARS) report else report.take(RESULT_MAX_CHARS - 1) + "…", null)
            }
        }
        is TurnOutcome.Failed -> Ending(Status.FAILED, null, reasonOf(outcome.error))
        is TurnOutcome.Refused -> Ending(Status.FAILED, null, "refused by the safety filter" + (outcome.category?.let { " (category $it)" } ?: ""))
        is TurnOutcome.Truncated -> Ending(Status.FAILED, null, "reply cut off: ${outcome.reason}")
        is TurnOutcome.Empty -> Ending(Status.FAILED, null, "empty replies: no report")
        is TurnOutcome.StepLimit -> Ending(Status.FAILED, null, "step limit: still calling tools after ${outcome.calls} requests, no report")
        is TurnOutcome.Cancelled -> Ending(Status.CANCELLED, null, "cancelled")
    }

    private fun reasonOf(error: GatewayError): String = when (error) {
        is GatewayError.Budget -> "budget: the LLMGW budget is spent" +
            (error.resetAt?.let { " until ${HOURS.withZone(clock.zone).format(it)}" } ?: "")
        else -> error.description
    }

    /**
     * The task's coroutine completed, however it did: records its end, tells the Director, frees its
     * place and starts the next task. Never throws (a completion handler).
     */
    private fun completed(task: Task, cause: Throwable?) {
        try {
            val stopping = services.phase != Phase.RUNNING
            val ending = task.ending ?: when {
                cause == null || cause is CancellationException ->
                    Ending(Status.CANCELLED, null, if (stopping && !task.cancelled) "the server stopped" else "cancelled by the Director")
                else -> Ending(Status.FAILED, null, "engine error: ${cause.message ?: cause.javaClass.simpleName}")
            }
            val (record, conversation, opened) = synchronized(lock) {
                running--
                end(task, ending.status, ending.result, ending.error)
                Triple(task.record, task.conversation, task.opened)
            }
            if (conversation != null && opened != null) closeConversation(task, conversation, opened, record, stopping)
            report(record, told = !task.cancelled && !stopping)
            dispatch()
        } catch (e: Exception) {
            log.error("T-${task.id}: its end was not recorded", e)
        }
    }

    /** Records that [task] ended, unless it already did (a cancelled task keeps its first end). Under [lock]. */
    private fun end(task: Task, status: Status, result: String?, error: String?) {
        if (task.record.status.finished) return
        task.record = task.record.copy(status = status, finishedAt = clock.instant(), result = result, error = error)
        services.trace.task(task.record)
    }

    /** Ends the `conversations` row [opened] and the transcript of a task's [conversation]. */
    private fun closeConversation(task: Task, conversation: Conversation, opened: ConversationRecord, record: TaskRecord, stopping: Boolean) {
        val reason = when {
            stopping && !task.cancelled -> ConversationRecord.EndReason.SHUTDOWN
            record.status == Status.DONE -> ConversationRecord.EndReason.DONE
            record.status == Status.TIMEOUT -> ConversationRecord.EndReason.TIMEOUT
            record.status == Status.CANCELLED -> ConversationRecord.EndReason.CANCELLED
            else -> ConversationRecord.EndReason.FAILED
        }
        services.trace.conversation(opened.copy(endedAt = record.finishedAt, endReason = reason, summary = record.result))
        services.transcripts.note(conversation, "task T-${task.id} ${record.status.id}" + (record.error?.let { ": $it" } ?: ""))
        conversation.close()
    }

    /** Logs how [record] ended and, when [told], posts it to the Director. */
    private fun report(record: TaskRecord, told: Boolean) {
        val took = record.startedAt?.let { " in ${duration(record.finishedAt!!.toEpochMilli() - it.toEpochMilli())}" }.orEmpty()
        val head = "T-${record.taskId} ${record.role.id} ${record.status.id}$took"
        when (record.status) {
            Status.DONE -> log.info("$head: ${record.result!!.length} chars, ${record.result.lineSequence().first().take(LOG_BRIEF_CHARS)}")
            Status.CANCELLED -> log.info("$head: ${record.error}")
            else -> log.warn("$head: ${record.error}")
        }
        if (!told) return
        services.director.post(
            DirectorSignal.TaskDone(record.taskId, record.role, record.status, record.result ?: record.error.orEmpty(), record.finishedAt!!.toEpochMilli()),
        )
    }

    private fun conversationRecord(task: Task, conversation: Conversation, started: Instant) = ConversationRecord(
        conversationId = conversation.id,
        role = task.record.role,
        label = conversation.label,
        chapter = null,
        taskId = task.id,
        startedAt = started,
        endedAt = null,
        endReason = null,
        summary = null,
        transcript = transcriptOf(conversation),
    )

    /** Where the conversation's Markdown transcript is, relative to the data folder; null without transcripts. */
    private fun transcriptOf(conversation: Conversation): String? {
        val transcripts = services.transcripts
        if (!transcripts.enabled) return null
        val file = transcripts.root.resolve(LocalDate.now().toString()).resolve("${conversation.id}.md")
        return if (file.startsWith(services.dataDir)) services.dataDir.relativize(file).toString().replace('\\', '/') else file.toString()
    }

    /** Drops the tasks that ended more than [KEEP_ENDED_MS] ago. Under [lock]. */
    private fun forgetOldTasks(now: Instant) {
        tasks.values.removeIf { task -> task.record.finishedAt?.let { now.toEpochMilli() - it.toEpochMilli() > KEEP_ENDED_MS } ?: false }
    }

    /** `tasks: T-3 running, T-4 queued`, for an error message. Under [lock]. */
    private fun summary(): String {
        val open = tasks.values.filter { !it.record.status.finished }
        return if (open.isEmpty()) "no task is running or queued" else "tasks: " + open.joinToString { "T-${it.id} ${it.record.status.id}" }
    }

    /** A task and what runs it. Its fields change under [lock], except [ending], written once by its coroutine. */
    private class Task(@Volatile var record: TaskRecord) {
        val id: Long get() = record.taskId

        var job: Job? = null
        var conversation: Conversation? = null

        /** Its `conversations` row as inserted when the conversation opened. */
        var opened: ConversationRecord? = null

        /** The Director cancelled it: it posts no result. */
        @Volatile
        var cancelled = false

        @Volatile
        var ending: Ending? = null
    }

    /** How a task ended: [result] when done, else [error]. */
    private data class Ending(val status: Status, val result: String?, val error: String?)

    companion object {
        /** Longest report given to the Director, in characters. */
        const val RESULT_MAX_CHARS = 3_000

        /** Tasks waiting in line at most: beyond, [submit] refuses (M2 review, finding 28). */
        const val MAX_QUEUED = 10

        /** How long an ended task stays in [list] (and the situation's durations). */
        const val KEEP_ENDED_MS = 30 * 60_000L

        /** The worker roles wired: the Analyst (M2) and the scout (M4 §14). */
        private val WIRED = setOf(Role.ANALYST, Role.SCOUT)

        private const val LOG_BRIEF_CHARS = 120
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        private val HOURS: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    }
}
