package io.github.clemsix6.narrator2.agent

import com.anthropic.models.messages.TextBlockParam
import io.github.clemsix6.narrator2.config.Prompts
import io.github.clemsix6.narrator2.config.WorldNotes
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.tool.ToolText.duration
import io.github.clemsix6.narrator2.journal.IdKind
import io.github.clemsix6.narrator2.llm.Conversation
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.memory.dream.ProgrammeInputs
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
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * The workers the Director delegates long work to (M1_M2 §13): the Analyst (M2), the scout (M4 §14), the storyteller
 * (M6 §6.4) and the forger (M5 §8.1), whom the Director commissions through `forge_module`.
 *
 * - **Never waits.** [submit] queues the task and returns its id at once; the Director's turn goes on.
 * - **Its own conversation.** Each task is one conversation with Claude, `Task T-12 (analyst)`, with the
 *   role's prompt and tools (`ToolCatalog.toolSetFor`), and its own id; its `X-Session-ID` is
 *   the role's (`narrator2-analyst`), so that each task reads the prefix the previous ones cached. It opens
 *   with `<task id="T-12" from="director">brief</task>` and a `<context …/>`
 *   line (the names of the players online through the common escape, `core/ModelText`), and runs a
 *   single turn of [AgentLoop] within `workers.maxSteps` requests and `workers.timeoutSeconds`. The text
 *   of the last reply is the report (at most [RESULT_MAX_CHARS]).
 * - **The forger** (M5 §8.1) works only while the Forge and the mechanics engine run and no operator switched the
 *   Forge off; its system prompt is two blocks, `forger.md` then the SDK's reference (`Prompts.SDK`); its task runs
 *   within `forge.taskSteps` requests and `forge.taskMinutes`, and each request that answers its tool calls counts the
 *   messages it has left of `forge.taskSteps` (`TurnLimits.countdown`, M6-FIX-15); one forger task runs at a time, the
 *   next ones wait in the common line while the other roles' tasks go past them.
 * - **The storyteller** (M6 §6.4) works only while the memory and the Journal run, where its ideas go; its conversation sees `inspire`
 *   (`ToolEnv.storytellerAvailable`), and it runs within [STORYTELLER_STEPS] requests at most (fewer when
 *   `workers.maxSteps` says so): three ideas and a report take three to five. Its opening shows its open ideas in short,
 *   and each request that answers its tool calls counts the messages it has left (`TurnLimits.countdown`, M6-FIX-14).
 * - **In parallel, then in line.** At most `workers.maxConcurrent` tasks run at once, each in its own
 *   coroutine of `services.scope`; the others wait, first come first served, [MAX_QUEUED] at most (one
 *   more is refused). Settings are read when used.
 * - **Back to the Director.** A task that ends posts a [DirectorSignal.TaskDone]: its report when done,
 *   else why it failed or timed out. A task the Director cancelled posts nothing (the `tasks` call
 *   already told it), nor does a task stopped by the server; a forger's task cancelled by the Forge's switch
 *   (`/narrator forge off`, M5-14) is posted, `cancelled`, since the Director did not ask for it.
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
    private var forging = 0 // guarded by lock: the forger's tasks among them

    /**
     * The Analyst and the scout are offered only when the Journal opened (the Analyst works on it first, the scout
     * reads the places players haunt in it); the storyteller only while the memory and the Journal run (its ideas are
     * rows of the memory, written through the Journal, which may fall while the memory still says it runs); the forger
     * only while it can work ([forgeAbsence]); none while the server stops.
     */
    override val roles: Set<Role>
        get() {
            if (services.phase != Phase.RUNNING) return emptySet()
            return buildSet {
                if (services.journal.available) addAll(JOURNAL_ROLES)
                if (services.journal.available && services.memory.available) add(Role.STORYTELLER)
                if (forgeAbsence() == null) add(Role.FORGER)
            }
        }

    override fun submit(role: Role, brief: String, caller: Caller): Result<Long> {
        if (services.phase != Phase.RUNNING) return Result.failure(IllegalStateException("the server is stopping: no task can start"))
        if (role !in roles) {
            val why = when (role) {
                in JOURNAL_ROLES -> "the Journal is unavailable"
                Role.STORYTELLER -> if (services.journal.available) "the memory is unavailable" else "the Journal is unavailable"
                Role.FORGER -> forgeAbsence() ?: "the Forge is unavailable"
                else -> "there is no ${role.id} yet"
            }
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
     *
     * The Director cancels with `tasks`, and is not told again. While an operator's switch holds the Forge off, a
     * forger's task is cancelled by that switch (`/narrator forge off` cancels them, M5-14): it says so, and the
     * Director is told, since he did not ask for it.
     */
    override fun cancel(taskId: Long): Result<Unit> {
        val job: Job?
        val record: TaskRecord
        val switch: String?
        synchronized(lock) {
            val task = tasks[taskId] ?: return Result.failure(IllegalArgumentException("no task T-$taskId; ${summary()}"))
            if (task.record.status.finished) {
                return Result.failure(IllegalArgumentException("task T-$taskId already ended (${task.record.status.id})"))
            }
            switch = services.mechanics.modules.halted?.takeIf { task.record.role == Role.FORGER }?.let { "the Forge was switched off by ${it.by}" }
            task.cancelled = true
            task.toldWhenCancelled = switch != null
            job = task.job
            if (job == null) {
                queue.remove(task)
                end(task, Status.CANCELLED, null, if (switch != null) "cancelled before it started: $switch" else "cancelled by the Director before it started")
            } else {
                end(task, Status.CANCELLED, null, if (switch != null) "cancelled: $switch" else "cancelled by the Director")
            }
            record = task.record
        }
        if (job == null) report(record, told = switch != null) else job.cancel(CancellationException(record.error))
        return Result.success(Unit)
    }

    // ── Running ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Starts queued tasks, first come first served, while there is room; a forger's task waits while another one
     * runs, and the tasks behind it go past it. While the server stops, cancels those still waiting.
     */
    private fun dispatch() {
        val starting = synchronized(lock) {
            if (services.phase != Phase.RUNNING) {
                while (queue.isNotEmpty()) end(queue.removeFirst(), Status.CANCELLED, null, "the server stopped before it started")
                return
            }
            val max = services.settings.get().config.workers.maxConcurrent
            buildList {
                val line = queue.iterator()
                while (running < max && line.hasNext()) {
                    val task = line.next()
                    val forger = task.record.role == Role.FORGER
                    if (forger && forging >= MAX_FORGING) continue
                    line.remove()
                    val conversationId = "${task.record.role.id}-${UUID.randomUUID().toString().take(8)}"
                    task.record = task.record.copy(status = Status.RUNNING, startedAt = clock.instant(), conversationId = conversationId)
                    services.trace.task(task.record)
                    task.job = services.scope.launch(CoroutineName("narrator2-task-${task.id}"), CoroutineStart.LAZY) {
                        task.ending = work(task, conversationId)
                    }
                    running++
                    if (forger) forging++
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

    /**
     * The task's single turn; returns how it ended. Throws only the cancellation of its coroutine. A forger's task
     * that starts once the Forge cannot work any more (it waited in line) fails at once, without calling Claude.
     */
    private suspend fun work(task: Task, conversationId: String): Ending {
        val record = task.record
        val settings = services.settings.get()
        val forger = record.role == Role.FORGER
        val storyteller = record.role == Role.STORYTELLER
        if (forger) forgeAbsence()?.let { return Ending(Status.FAILED, null, "no forger can work: $it") }
        val steps = when {
            forger -> settings.config.forge.taskSteps
            storyteller -> minOf(settings.config.workers.maxSteps, STORYTELLER_STEPS)
            else -> settings.config.workers.maxSteps
        }
        val timeoutMs = if (forger) settings.config.forge.taskMinutes * 60_000L else settings.config.workers.timeoutSeconds * 1_000L
        val started = record.startedAt ?: clock.instant()
        return try {
            val env = ToolEnv(
                services.journal.available, workerRoles = emptySet(), memoryAvailable = services.memory.available,
                mechanicsAvailable = services.mechanics.available, forgeAvailable = forgeAbsence() == null,
                storytellerAvailable = storyteller,
            )
            val tools = toolSet(record.role, env)
            val prompts = if (forger) listOf(record.role.id, Prompts.SDK) else listOf(record.role.id)
            val system = prompts.map { TextBlockParam.builder().text(settings.prompts.get(it)).build() }
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
            val outcome = withTimeoutOrNull(timeoutMs) {
                agent.runTurn(conversation, opening, caller, TurnLimits(steps, countdown = storyteller || forger))
            }
            if (outcome == null) Ending(Status.TIMEOUT, null, "no report within ${duration(timeoutMs)}") else endingOf(outcome)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("T-${task.id} failed on an engine error", e)
            Ending(Status.FAILED, null, "engine error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * The first message of a task: the brief, then when it is and who is online; a storyteller's, then the operator's
     * `<server_notes>` (M7-NOTES) and its open ideas in short ([ProgrammeInputs.taskIdeas], from the memory's cache), so
     * that a new one takes neither the title nor the substance of an open one (M6-FIX-14).
     */
    private fun opening(task: Task): String {
        val config = services.settings.get().config
        val zone = config.journal.zone(clock.zone)
        val now = STAMP.withZone(zone).format(clock.instant())
        val online = services.players.online.joinToString(", ") { ModelText.attribute(it.ref.name) }.ifEmpty { "none" }
        val head = "<task id=\"T-${task.id}\" from=\"director\">\n${task.record.brief}\n</task>\n<context now=\"$now\" players_online=\"$online\"/>"
        if (task.record.role != Role.STORYTELLER) return head
        return listOfNotNull(head, WorldNotes.render(config.worldNotes), ProgrammeInputs.taskIdeas(services.memory.openIdeas())).joinToString("\n")
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
                if (task.record.role == Role.FORGER) forging--
                end(task, ending.status, ending.result, ending.error)
                Triple(task.record, task.conversation, task.opened)
            }
            if (conversation != null && opened != null) closeConversation(task, conversation, opened, record, stopping)
            report(record, told = (!task.cancelled || task.toldWhenCancelled) && !stopping)
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

    /**
     * Why the forger cannot work now (M5 §8.1), in the order of the Director's `forge_module` (`forgeAvailable`): the
     * Forge does not run, an operator switched it off, or the mechanics engine does not run; null when it can. Any
     * thread.
     */
    private fun forgeAbsence(): String? {
        val forge = services.forge
        val mechanics = services.mechanics
        val halt = mechanics.modules.halted
        return when {
            !forge.available -> "the Forge is unavailable: ${forge.unavailableReason}"
            halt != null -> "the Forge was switched off by ${halt.by}"
            !mechanics.available -> "the mechanics are unavailable: ${mechanics.unavailableReason}"
            else -> null
        }
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

        /** It was cancelled: it posts no result, unless [toldWhenCancelled]. */
        @Volatile
        var cancelled = false

        /** The Forge's switch cancelled it, not the Director: its end is posted. */
        @Volatile
        var toldWhenCancelled = false

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

        /** The worker roles that need the Journal: the Analyst (M2) and the scout (M4 §14). */
        private val JOURNAL_ROLES = setOf(Role.ANALYST, Role.SCOUT)

        /** The forger's tasks running at once (M5 §8.1): the others wait in line. */
        private const val MAX_FORGING = 1

        /**
         * Requests of a storyteller's task at most (M6 §6.4: three to six), whatever `workers.maxSteps` allows beyond: its
         * prefix (prompt, seven tools) is long, and each request reads it again.
         */
        const val STORYTELLER_STEPS = 6

        private const val LOG_BRIEF_CHARS = 120
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        private val HOURS: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    }
}
