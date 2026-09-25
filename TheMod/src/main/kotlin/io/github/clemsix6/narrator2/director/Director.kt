package io.github.clemsix6.narrator2.director

import com.anthropic.models.messages.ContentBlockParam
import io.github.clemsix6.narrator2.agent.AgentLoop
import io.github.clemsix6.narrator2.agent.GatewayError
import io.github.clemsix6.narrator2.agent.OperatorNote
import io.github.clemsix6.narrator2.agent.Pending
import io.github.clemsix6.narrator2.agent.TurnHooks
import io.github.clemsix6.narrator2.agent.TurnLimits
import io.github.clemsix6.narrator2.agent.TurnOutcome
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.director.EventFormatter.Companion.ago
import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.journal.IdKind
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.oracle.WorldSnapshot
import io.github.clemsix6.narrator2.reflex.ReflexThresholds
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.runtime.Phase
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolEnv
import io.github.clemsix6.narrator2.tool.ToolRunner
import io.github.clemsix6.narrator2.tool.ToolSet
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.trace.ConversationRecord
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import io.github.clemsix6.narrator2.trace.ToolCallRecord
import io.github.clemsix6.narrator2.trace.TraceSink
import io.github.clemsix6.narrator2.trace.TurnRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** What the Director reads from the game and shows in it: the server in production, a fake in tests. */
interface GameView {
    /** The overworld's clock and weather, read on the server thread; fails when it does not answer in time. */
    suspend fun worldTime(): WorldTime

    /** The world and every player online, for a chapter's briefing; read on the server thread, fails when it does not answer in time. */
    suspend fun worldSnapshot(): WorldSnapshot

    /**
     * Shows [text] above the hotbar of the players online that [to] returns when it is shown: they may
     * have got their answer between the call and the display. Any thread; never waits for the server thread.
     */
    fun actionbar(text: String, to: () -> Collection<UUID>)

    /**
     * Clears the text above the hotbar of the [players] online, at once when called on the server thread:
     * a line of the Narrator shown right after in the same tick must not be erased. Any thread.
     */
    fun clearActionbar(players: Collection<UUID>)

    /** Tells the operators online, in the chat, in English (`[Narrator] …`). Any thread. */
    fun tellOperators(text: String)
}

/**
 * The Director (M1_M2 §12): the agent that watches the game, decides and speaks to the players. One
 * coroutine runs its state machine ([DirectorState]) and is the only one to touch its state; everything
 * else reaches it through its [Inbox] ([post]).
 *
 * - **Wake-ups.** The [Salience] classifies the bus's events; the [WakeScheduler] groups them into
 *   wake-ups; heartbeats come a few minutes after the last turn while players are online, when the
 *   reflexes find the activity worth it. Each wake-up is one turn of [AgentLoop] on the open chapter,
 *   opened by a message with `<events>`, `<heartbeat>` and `<situation>`.
 * - **Never blocked.** Events that arrive during a turn are given to the model between two tool calls,
 *   in a `<new_events>` block placed after the tool results; operator whispers become notes.
 * - **No quota.** A turn that ends without acting is a normal turn; the engine never prompts the model
 *   because it kept silent.
 * - **Told once.** An item leaves the inbox when it is told; it comes back only when the request that
 *   carried it was not committed. Each chapter keeps the ids of the events it committed ([Chapter.seen]).
 * - **Errors** (§12.9): a refused or cut-off answer is retried 30 s later with an engine note (at once
 *   during a turn); transient gateway failures back off (30 s, doubling up to 10 min); a spent budget
 *   or a refused key block the Director; a malformed request or a full context closes the chapter, and
 *   the chapters that close on an error reopen after a backoff (30 s, doubling up to 15 min) until a
 *   request gets through. A first message too long for the context goes again once with a reduced
 *   briefing. While paused or blocked, addressed chat, prayers, task results, reminders and whispers
 *   are kept, the rest is left to the activity digest; the inbox is bounded in every state and counts
 *   what it drops ([Inbox]); the next wake-up says how long the Narrator was silent and why.
 * - **Step limit.** Past the turn's step limit the model can no longer act: what arrives then stays in
 *   the inbox, unseen, for the next turn ([TurnHooks.afterTools]).
 * - **Chapters** ([Chapters]): the conversation is cut into chapters of bounded context. A chapter
 *   closes (`CLOSING`) with a factual summary when its context reaches the limits, when nobody has been
 *   online for a while, at a quiet moment after `/narrator chapter close` or a reload that changed its
 *   prompt, model, effort or channel, and after a runaway turn; without one after the errors that break
 *   its conversation and when the server stops. A summary request that fails on the gateway is asked
 *   again with a backoff (30 s, doubling up to 15 min) or when the block ends, the chapter staying open
 *   and closing meanwhile: its promises must reach the next chapters. The next chapter's first message
 *   starts with a [Briefing].
 *
 * @param toolSet the tools of a new chapter, given what is wired: `ToolCatalog.toolSetFor` in production.
 */
class Director(
    private val services: NarratorServices,
    private val game: GameView,
    toolSet: (ToolEnv) -> ToolSet,
    private val random: Random = Random.Default,
) : DirectorControl {
    private val log = Log.director
    private val clock = services.clock
    private val inbox = Inbox()
    private val digest = ActivityDigest(clock)
    private val formatter = EventFormatter(clock.zone) { id -> services.workers.list().firstOrNull { it.taskId == id }?.startedAt?.toEpochMilli() }
    private val situation = SituationReport(services, digest, clock.zone)
    private val hours = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).withZone(clock.zone)
    private val decisions = Decisions(services.trace, clock)
    private val indicator = ThinkingIndicator(game, ::thinkingText)
    private val actions = ArrayDeque<DirectorAction>() // guarded by itself
    private val reminders = ConcurrentHashMap<Long, Reminder>()
    private val reminderIds = AtomicLong(1)

    /** The services the turns run with: the same, with the Director's own acting tool calls remembered for its situation. */
    private val turnServices: NarratorServices = object : NarratorServices by services {
        override val trace: TraceSink = object : TraceSink by services.trace {
            override fun toolCall(r: ToolCallRecord) {
                services.trace.toolCall(r)
                if (r.role == Role.DIRECTOR && r.status == ToolStatus.OK && r.effect in ACTING) remember(r)
            }
        }
    }
    private val chapters = Chapters(turnServices, toolSet, Briefing(turnServices, { game.worldSnapshot() }) { digest.recent(it, SituationReport.RECENT_MINUTES) })
    private val agent = AgentLoop(turnServices, ToolRunner(turnServices))

    // Published for status(), any thread.
    @Volatile private var state = DirectorState.IDLE
    @Volatile private var until: Long? = null
    @Volatile private var why: String? = null
    @Volatile private var runningTurn: Long? = null
    @Volatile private var chapter: Chapter? = null

    // The coroutine's own state.
    private var paused = false
    private var resumeWake = false
    private var backoffUntil: Long? = null
    private var backoffStep = 0

    /** Chapters closed on an error since the last request that got through: the next one opens later each time. */
    private var reopenStep = 0

    /** When the summary of a chapter that is closing is asked again; null while blocked or when none waits. */
    private var summaryRetryAt: Long? = null
    private var summaryStep = 0

    /** The open chapter's first message did not fit in the context: its briefing is reduced. */
    private var reducedBriefing = false
    private var block: Block? = null
    private var silence: Silence? = null
    private var retryNote: String? = null
    private var notBefore: Long? = null
    private var carry: List<ContentBlockParam> = emptyList()
    private var closeAtQuiet: ConversationRecord.EndReason? = null
    private var closeAfterTurn: Closing? = null
    private var lastTurnEnd = clock.millis()
    private var heartbeatFrom = clock.millis()
    private var nextHeartbeat: Long? = null

    /** Subscribes to the bus and starts the Director's coroutine in `services.scope`. */
    fun start(): Job {
        Salience(services, digest, ::post).start(services.scope)
        indicator.subscribe(services.bus)
        return services.scope.launch(CoroutineName("narrator2-director")) { run() }
    }

    override fun post(signal: DirectorSignal) {
        if (services.phase == Phase.STOPPING || services.phase == Phase.STOPPED) return
        inbox.post(signal)
    }

    override fun scheduleReminder(inSeconds: Int, note: String, caller: Caller): Result<Long> {
        if (services.phase != Phase.RUNNING) return Result.failure(IllegalStateException("the server is stopping"))
        if (state == DirectorState.DISABLED || state == DirectorState.STOPPED) return Result.failure(IllegalStateException("the Director is disabled"))
        val id = reminderIds.getAndIncrement()
        val now = clock.millis()
        val reminder = Reminder(id, note, now, now + inSeconds * 1_000L)
        reminders[id] = reminder
        log.info("reminder R-$id set by turn ${caller.turnId} for ${formatter.time(reminder.at)}: ${note.take(80)}")
        inbox.changed()
        return Result.success(id)
    }

    override fun status(): DirectorStatus {
        val open = chapter
        val config = services.settings.get().config.director
        return DirectorStatus(
            state = state,
            until = until,
            reason = why,
            chapter = open?.number,
            conversationId = open?.conversation?.id,
            contextTokens = open?.conversation?.contextTokens ?: 0,
            inbox = inbox.size,
            droppedSignals = inbox.dropped,
            turnId = runningTurn,
            operatorChannel = agent.channelMode(config.channelMode),
            reminders = pendingReminders(),
        )
    }

    /** Escaped ([ModelText.escape]): the line names the players who were killed. */
    override fun recentActivity(player: UUID, minutes: Int): String? = digest.recent(player, minutes)?.let(ModelText::escape)

    // ── The loop ────────────────────────────────────────────────────────────────────────────────────

    private suspend fun run() {
        try {
            coroutineScope {
                while (true) {
                    try {
                        step(this)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.error("the Director's loop failed; it goes on in ${ago(ERROR_PAUSE_MS)}", e)
                        delay(ERROR_PAUSE_MS)
                    }
                }
            }
        } finally {
            publish(DirectorState.STOPPED, null, "the server is stopping")
            chapters.close(ConversationRecord.EndReason.SHUTDOWN, "the server is stopping")
            chapter = null
        }
    }

    /** One pass of the state machine: applies what came in, then acts or waits for the next thing to do. */
    private suspend fun step(scope: CoroutineScope) {
        applyControls()
        val now = clock.millis()
        releaseReminders(now)
        disabledReason()?.let { reason ->
            val dropped = inbox.discard { true }
            inbox.takeOverflow()
            if (dropped.isNotEmpty()) log.debug { "${dropped.size} signals dropped: the Director is disabled ($reason)" }
            publish(DirectorState.DISABLED, null, reason)
            inbox.await(null)
            return
        }
        if (paused) {
            mute()
            publish(DirectorState.PAUSED, null, "paused by an operator")
            inbox.await(nextReminderAt()?.minus(now))
            return
        }
        block?.let { current ->
            val end = current.until
            if (end == null || now < end) {
                mute()
                publish(DirectorState.BLOCKED, end, current.why)
                inbox.await(listOfNotNull(end, nextReminderAt()).minOrNull()?.minus(now))
                return
            }
            block = null
            resumeWake = resumeWake || carry.isNotEmpty()
            log.info("no longer blocked: ${current.why}")
        }
        backoffUntil?.let { end ->
            if (now < end) {
                publish(DirectorState.BACKOFF, end, silence?.why)
                inbox.await(listOfNotNull(end, nextReminderAt()).minOrNull()?.minus(now))
                return
            }
            backoffUntil = null
            resumeWake = resumeWake || carry.isNotEmpty()
        }
        if (chapters.isClosing) {
            // A chapter waits for its summary: no turn runs, the inbox keeps what comes for the next chapter.
            val at = summaryRetryAt ?: now
            if (at <= now) {
                resumeSummary()
                return
            }
            publish(DirectorState.CLOSING, at, "chapter ${chapter?.number} waits to ask for its summary again")
            inbox.await(listOfNotNull(at, nextReminderAt()).min() - now)
            return
        }
        val config = services.settings.get().config.director
        val wakeAt = if (resumeWake) now else WakeScheduler.wakeAt(inbox.items(), config.chatDebounceMs, notBefore)
        if (wakeAt != null && wakeAt <= now) {
            wake(scope, null)
            return
        }
        val quietAt = closeAtQuiet?.let { lastTurnEnd + QUIET_MS }
        if (wakeAt == null && quietAt != null && quietAt <= now) {
            closeAtQuietMoment()
            return
        }
        val emptyAt = chapters.emptyServerCloseAt(now)
        if (wakeAt == null && emptyAt != null && emptyAt <= now) {
            closeWithSummary(ConversationRecord.EndReason.SERVER_EMPTY, "no player online for ${config.emptyServerCloseMinutes} minutes")
            return
        }
        val beatAt = if (wakeAt == null) heartbeatAt(config.heartbeatRange) else null
        if (beatAt != null && beatAt <= now) {
            heartbeat(scope, now)
            return
        }
        publish(if (wakeAt != null) DirectorState.WAITING else DirectorState.IDLE, wakeAt, null)
        // A quiet moment or an empty server already due waits for the scheduled wake-up to pass: it is not a deadline then.
        val next = listOfNotNull(wakeAt, quietAt?.takeIf { it > now }, emptyAt?.takeIf { it > now }, beatAt, nextReminderAt(), now + RECHECK_MS).min()
        inbox.await(next - now)
    }

    /** Applies the control signals waiting: pause, resume, chapter close, reload. */
    private fun applyControls() {
        for (signal in inbox.takeControls()) {
            when (signal) {
                DirectorSignal.Pause -> if (!paused) {
                    paused = true
                    silence = silence ?: Silence(clock.millis(), "paused by an operator")
                    log.info("paused: the running turn, if any, ends first")
                }
                DirectorSignal.Resume -> if (paused) {
                    paused = false
                    resumeWake = true
                    log.info("resumed")
                }
                is DirectorSignal.CloseChapter -> markChapter(signal.reason, "asked by an operator")
                is DirectorSignal.SettingsChanged -> {
                    block?.let {
                        block = null
                        resumeWake = resumeWake || carry.isNotEmpty()
                        log.info("no longer blocked: the settings were reloaded")
                    }
                    chapters.reloadChange(signal.old, signal.new)?.let { markChapter(ConversationRecord.EndReason.RELOAD, it) }
                }
                else -> Unit
            }
        }
    }

    /** Why the Director cannot run, or null when it can. */
    private fun disabledReason(): String? {
        val settings = services.settings.get()
        return when {
            !settings.config.director.enabled -> "director.enabled is false"
            settings.claude == null -> settings.loadError?.let { "the configuration could not be loaded: $it" } ?: "no LLMGW key"
            else -> null
        }
    }

    /** While muted, only what someone waits for is kept: addressed chat, prayers, tasks, reminders, whispers. */
    private fun mute() {
        val gone = inbox.discard { it.signal is DirectorSignal.Event && it.urgency != Urgency.IMMEDIATE }
        if (gone.isNotEmpty()) log.debug { "${gone.size} events left to the activity digest while silent" }
    }

    private suspend fun closeAtQuietMoment() {
        val reason = closeAtQuiet ?: return
        closeWithSummary(reason, "closed at a quiet moment")
    }

    /** Marks the open chapter to close at the next quiet moment; the first reason stays. */
    private fun markChapter(reason: ConversationRecord.EndReason, why: String) {
        if (chapters.current == null || chapters.isClosing) {
            log.info("no chapter to close (${reason.id}: $why)${if (chapters.isClosing) ": the open one is already closing" else ""}")
            return
        }
        if (closeAtQuiet != null) return
        closeAtQuiet = reason
        chapters.mark(reason, why)
    }

    /** Closes the open chapter with its summary (`CLOSING`); [carried] are the tool results its history waits for. */
    private suspend fun closeWithSummary(reason: ConversationRecord.EndReason, why: String, carried: List<ContentBlockParam> = carry) {
        publish(DirectorState.CLOSING, null, "chapter closing (${reason.id})")
        carry = emptyList()
        closeAtQuiet = null
        afterSummary(chapters.closeWithSummary(reason, why, carried))
    }

    /** Asks again for the summary of the chapter that is closing. */
    private suspend fun resumeSummary() {
        publish(DirectorState.CLOSING, null, "chapter ${chapter?.number} closing: asking for its summary again")
        afterSummary(chapters.resumeSummary())
    }

    /**
     * After a summary request: the chapter is closed ([error] is null), or the gateway failed and the
     * chapter stays open and closing (M2 review, finding 23). The request goes again after a backoff
     * (30 s, doubling up to [CHAPTER_RETRY_MAX_MS]), or once a spent budget resets or a reload fixes the key.
     */
    private fun afterSummary(error: GatewayError?) {
        if (error == null) {
            chapter = null
            closeAtQuiet = null
            reducedBriefing = false
            summaryRetryAt = null
            summaryStep = 0
            return
        }
        val now = clock.millis()
        val subject = "chapter ${chapter?.number}"
        val next = when (error) {
            is GatewayError.Budget, GatewayError.Auth, is GatewayError.Unavailable -> {
                summaryRetryAt = null
                "once the Director is no longer blocked (${blockOn(error, now)})"
            }
            else -> {
                val delay = retryDelay(summaryStep++)
                summaryRetryAt = now + delay
                silence = silence ?: Silence(now, "the gateway failed: ${(error as? GatewayError.Transient)?.kind ?: error.description}")
                "in ${ago(delay)}"
            }
        }
        decisions.record(
            log, DecisionRecord.Kind.BACKOFF, "backed off", subject = subject,
            reason = "the summary request failed (${error.description}); the chapter stays open, its summary is asked again $next",
            conversationId = chapter?.conversation?.id, level = Log.Level.WARN,
        )
    }

    /**
     * Blocks the Director on a spent budget (until it resets) or a refused or missing key (until a
     * reload), telling the operators once; returns `until 21:34` or `until /narrator reload`, for the logs.
     */
    private fun blockOn(error: GatewayError, now: Long): String = when (error) {
        is GatewayError.Budget -> {
            val resetAt = error.resetAt?.toEpochMilli() ?: (now + BUDGET_UNKNOWN_RESET_MS)
            val reset = hours.format(Instant.ofEpochMilli(resetAt))
            blockUntil(resetAt + BUDGET_MARGIN_MS, "the LLMGW budget is spent until $reset", "LLMGW budget exceeded until $reset", now)
            "until $reset"
        }
        else -> {
            blockUntil(null, error.description, "the Narrator is blocked: ${error.description}; fix it, then /narrator reload", now)
            "until /narrator reload"
        }
    }

    /**
     * A chapter closed on an error ([why]): the next one opens after a backoff, 30 s doubling up to
     * [CHAPTER_RETRY_MAX_MS], until a request gets through (M2 review, finding 22). A request rejected
     * every time thus costs a few chapters an hour, not one every 30 s.
     */
    private fun reopenLater(reason: ConversationRecord.EndReason, why: String) {
        val now = clock.millis()
        val delay = retryDelay(reopenStep++)
        backoffUntil = maxOf(backoffUntil ?: now, now + delay)
        silence = silence ?: Silence(now, "your previous chapter closed on an error (${reason.id})")
        decisions.record(
            log, DecisionRecord.Kind.BACKOFF, "backed off", subject = "chapter",
            reason = "a chapter closed on an error (${reason.id}: $why); the next one opens in ${ago(delay)}", level = Log.Level.WARN,
        )
    }

    /**
     * After a turn: the closing it asked for (hard step limit), or the context limits (M1_M2 §12.10). A
     * turn whose request failed keeps its chapter until a turn gets through.
     */
    private suspend fun afterTurn() {
        closeAfterTurn?.let { closing ->
            closeAfterTurn = null
            closeWithSummary(closing.reason, closing.why, closing.carry)
            return
        }
        val open = chapters.current ?: return
        if (carry.isNotEmpty() || backoffUntil != null || block != null) return
        val limit = chapters.limit(open) ?: return
        if (limit.reason == ConversationRecord.EndReason.TOKEN_LIMIT) closeWithSummary(limit.reason, limit.why) else markChapter(limit.reason, limit.why)
    }

    // ── Heartbeats ──────────────────────────────────────────────────────────────────────────────────

    /** When the next heartbeat is due while players are online; null when nobody is. */
    private fun heartbeatAt(minutes: IntRange): Long? {
        if (services.players.online.isEmpty()) return null
        return nextHeartbeat ?: WakeScheduler.nextHeartbeat(heartbeatFrom, minutes, random).also { nextHeartbeat = it }
    }

    private suspend fun heartbeat(scope: CoroutineScope, now: Long) {
        val windowStart = digest.windowStart
        val summary = digest.takeWindow()
        heartbeatFrom = now
        nextHeartbeat = null
        if (summary.isEmpty()) {
            decisions.record(log, DecisionRecord.Kind.HEARTBEAT, "skipped", HEARTBEAT, "nobody did anything since ${formatter.time(windowStart)}")
            return
        }
        val window = now - windowStart
        val online = services.players.online.map { it.ref.name }
        val verdict = services.reflexes.heartbeat(((window + 30_000) / 60_000).toInt().coerceAtLeast(1), summary, online)
        val score = "worth ${p(verdict.worth)} by ${verdict.source}"
        if (verdict.worth < ReflexThresholds.HEARTBEAT_WORTH) {
            decisions.record(log, DecisionRecord.Kind.HEARTBEAT, "skipped", HEARTBEAT, "$score < ${p(ReflexThresholds.HEARTBEAT_WORTH)}")
            return
        }
        decisions.record(log, DecisionRecord.Kind.HEARTBEAT, "woke", HEARTBEAT, "$score, hook ${verdict.hook ?: "none"}")
        wake(scope, Beat(summary, window, verdict.hook))
    }

    // ── Wake-ups and turns ──────────────────────────────────────────────────────────────────────────

    /** Opens a turn with everything waiting, and [beat] when a heartbeat woke the Director. */
    private suspend fun wake(scope: CoroutineScope, beat: Beat?) {
        val resumed = resumeWake
        resumeWake = false
        val taken = inbox.takeAll()
        if (taken.isEmpty() && beat == null && !resumed && carry.isEmpty()) return
        val open = try {
            chapters.current()
        } catch (e: Exception) {
            inbox.putBack(taken)
            throw e
        }
        chapter = open
        val items = unseen(taken, open)
        if (items.isEmpty() && beat == null && !resumed && carry.isEmpty()) return
        val skipped = inbox.takeOverflow()
        val now = clock.millis()
        val shown = beat ?: silence?.let { digest.takeWindow().takeIf { it.isNotEmpty() }?.let { lines -> Beat(lines, now - it.since, null) } }
        val text = buildString {
            val events = items.filter { it.signal !is DirectorSignal.OperatorWhisper }
            if (events.isNotEmpty() || skipped.isNotEmpty()) append(formatter.events(events, now, skipped)).append('\n')
            shown?.let { append(formatter.heartbeat(it.summary, it.windowMs, it.hint, now)).append('\n') }
            append(situation.render(now, worldTime(), pendingReminders(), recentActions()))
        }
        val notes = whispers(items) + listOfNotNull(silence?.let { OperatorNote.engine(it.note(now)) }, retryNote?.let(OperatorNote::engine))
        // A new chapter's first message starts with its briefing, which tells the chat up to these events.
        val briefing = chapters.briefing(open, now, items.minOfOrNull { it.at } ?: now, pendingReminders(), reducedBriefing)
        val opening = Pending(carry + listOfNotNull(briefing).map(ContentBlockParam::ofText) + ContentBlockParam.ofText(text), notes)
        carry = emptyList()
        val trigger = triggerOf(items, beat, resumed)
        Turn(scope, open, trigger, items, skipped, beat).run(opening)
        afterTurn()
    }

    /**
     * One turn, from its wake-up message to its end: the requests of [AgentLoop.runTurn], with the
     * events injected between tool calls, a single immediate resend after a refused or cut-off answer
     * during the turn, and the reactions to failures (M1_M2 §12.9).
     */
    private inner class Turn(
        private val scope: CoroutineScope,
        private val chapter: Chapter,
        private val trigger: TurnRecord.Trigger,
        private val openingItems: List<Item>,
        openingSkipped: Map<String, Int>,
        private val beat: Beat?,
    ) {
        private val id = services.trace.nextId(IdKind.TURN)
        private val caller = Caller(Role.DIRECTOR, chapter.conversation.id, id, taskId = null)
        private val startedAt = clock.instant()
        private val triggerEvents = openingItems.mapNotNull { it.eventId }
        private val injected = mutableListOf<Long>()
        private var calls = 0
        private var toolCalls = 0
        private var acted = false
        private var openingCommitted = false

        /** The items of the last request sent: committed with a usable reply, back to the inbox otherwise. */
        private var sent: List<Item> = openingItems

        /** The counts of dropped items told by the last request sent ([Inbox.takeOverflow]), counted again when it fails. */
        private var sentSkipped: Map<String, Int> = openingSkipped

        /** [sent] was committed: the next request starts a new list. */
        private var sentCommitted = false

        private val hooks = object : TurnHooks {
            override suspend fun afterTools(canAct: Boolean): Pending {
                commit(sent)
                // Past the step limit the model can no longer answer: what arrived waits for the next turn, unseen.
                return inject(take = canAct)
            }
        }

        suspend fun run(opening: Pending) {
            runningTurn = id
            publish(DirectorState.RUNNING, null, null)
            services.trace.turn(record(null))
            val what = describe()
            decisions.record(
                log, DecisionRecord.Kind.WAKE, "woke", subject = "turn $id", reason = what,
                eventId = triggerEvents.firstOrNull(), conversationId = chapter.conversation.id, turnId = id,
            )
            services.transcripts.note(chapter.conversation, "turn $id · trigger ${trigger.id} · $what")
            indicator.show(openingItems.mapNotNull { it.waitingPlayer }, scope)
            var outcome = TurnRecord.Outcome.CANCELLED
            try {
                outcome = requests(opening)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("turn $id failed in the engine; its chapter closes", e)
                closeChapter(ConversationRecord.EndReason.INTERRUPTED, "the engine failed during turn $id: ${e.message ?: e.javaClass.simpleName}")
                putBack(sent, failed = true, sentSkipped)
                outcome = TurnRecord.Outcome.FAILED
            } finally {
                indicator.stop()
                val end = clock.millis()
                services.trace.turn(record(outcome, Instant.ofEpochMilli(end)))
                runningTurn = null
                lastTurnEnd = end
                heartbeatFrom = end
                nextHeartbeat = null
                digest.mark()
                val seconds = String.format(Locale.ROOT, "%.1f", (end - startedAt.toEpochMilli()) / 1_000.0)
                log.info(
                    "turn $id ${outcome.id}: $calls calls, $toolCalls tools, $seconds s, " +
                        "context ${chapter.conversation.contextTokens / 1_000}k",
                )
            }
        }

        private suspend fun requests(opening: Pending): TurnRecord.Outcome {
            val limits = TurnLimits(services.settings.get().config.director.maxStepsPerTurn)
            var pending = opening
            var resent = false
            while (true) {
                val outcome = agent.runTurn(chapter.conversation, pending, caller, limits, hooks)
                calls += outcome.calls
                toolCalls += outcome.toolCalls
                acted = acted || outcome.acted
                when (outcome) {
                    is TurnOutcome.Ended -> {
                        commit(sent)
                        return if (acted) TurnRecord.Outcome.ACTED else TurnRecord.Outcome.IDLE
                    }
                    is TurnOutcome.StepLimit -> {
                        commit(sent)
                        // Closed with its summary once the turn is over, the refused tool results first.
                        closeAfterTurn = Closing(ConversationRecord.EndReason.RUNAWAY_TURN, "the turn reached its hard step limit", outcome.carry)
                        return TurnRecord.Outcome.STEP_LIMIT
                    }
                    is TurnOutcome.Refused, is TurnOutcome.Truncated, is TurnOutcome.Empty -> {
                        val failed = failedPending(outcome)
                        val note = noteFor(outcome)
                        val result = outcomeOf(outcome)
                        if (failed.blocks.none { it.isToolResult() }) {
                            refusedWake(outcome, note)
                            return result
                        }
                        if (!resent) {
                            resent = true
                            decisions.record(
                                log, DecisionRecord.Kind.REFUSAL, "resent", subject = "turn $id", reason = "${what(outcome)}; the same tool results go again with a note",
                                conversationId = chapter.conversation.id, turnId = id, level = Log.Level.WARN,
                            )
                            pending = failed + inject() + OperatorNote.engine(note)
                            continue
                        }
                        decisions.record(
                            log, DecisionRecord.Kind.REFUSAL, "closed", subject = "turn $id", reason = "${what(outcome)} again after a resend",
                            conversationId = chapter.conversation.id, turnId = id, level = Log.Level.WARN,
                        )
                        closeChapter(ConversationRecord.EndReason.REFUSAL, "${what(outcome)} twice during a turn")
                        putBack(sent, failed = true, sentSkipped)
                        return result
                    }
                    is TurnOutcome.Failed -> return failed(outcome)
                    is TurnOutcome.Cancelled -> return TurnRecord.Outcome.CANCELLED
                }
            }
        }

        /**
         * The items that arrived since the last request, as a `<new_events>` block and operator notes; none
         * when the model can no longer act in this turn ([take] false): they stay in the inbox, unseen.
         */
        private suspend fun inject(take: Boolean = true): Pending {
            applyControls()
            val now = clock.millis()
            releaseReminders(now)
            val items = if (take) unseen(inbox.takeAll(), chapter) else emptyList()
            if (!take) log.debug { "turn $id is past its step limit: ${inbox.items().size} items wait for the next turn" }
            val skipped = if (items.isEmpty()) emptyMap() else inbox.takeOverflow()
            sent = if (sentCommitted) items else sent + items
            sentSkipped = if (sentCommitted) skipped else merged(sentSkipped, skipped)
            sentCommitted = false
            if (items.isEmpty()) return Pending.EMPTY
            injected += items.mapNotNull { it.eventId }
            indicator.show(items.mapNotNull { it.waitingPlayer }, scope)
            val events = items.filter { it.signal !is DirectorSignal.OperatorWhisper }
            val blocks = if (events.isEmpty() && skipped.isEmpty()) emptyList() else listOf(ContentBlockParam.ofText(formatter.newEvents(events, now, skipped)))
            return Pending(blocks, whispers(items))
        }

        /** The request that carried [items] got a usable reply: they are in the history now. */
        private fun commit(items: List<Item>) {
            chapter.seen += items.mapNotNull { it.eventId }
            sentCommitted = true
            if (openingCommitted) return
            openingCommitted = true
            silence = null
            retryNote = null
            notBefore = null
            backoffStep = 0
            reopenStep = 0
            reducedBriefing = false
        }

        /** A refused or cut-off answer to the wake-up message: its items come back in 30 s, with a note. */
        private fun refusedWake(outcome: TurnOutcome, note: String) {
            retryNote = note
            notBefore = clock.millis() + RETRY_DELAY_MS
            decisions.record(
                log, DecisionRecord.Kind.REFUSAL, "refused", subject = "turn $id", reason = "${what(outcome)}; the events wait ${ago(RETRY_DELAY_MS)}",
                eventId = triggerEvents.firstOrNull(), conversationId = chapter.conversation.id, turnId = id, level = Log.Level.WARN,
            )
            putBack(sent, failed = true, sentSkipped)
        }

        private fun failed(outcome: TurnOutcome.Failed): TurnRecord.Outcome {
            val now = clock.millis()
            val error = outcome.error
            return when (error) {
                is GatewayError.Transient -> {
                    keepForLater(outcome.pending)
                    val delay = (BACKOFF_FIRST_MS shl backoffStep.coerceAtMost(8)).coerceAtMost(BACKOFF_MAX_MS)
                    backoffStep++
                    backoffUntil = now + delay
                    silence = silence ?: Silence(now, "the gateway failed: ${error.kind}")
                    decisions.record(
                        log, DecisionRecord.Kind.BACKOFF, "backed off", subject = "turn $id", reason = "${error.description}; next try in ${ago(delay)}",
                        conversationId = chapter.conversation.id, turnId = id, level = Log.Level.WARN,
                    )
                    TurnRecord.Outcome.FAILED
                }
                is GatewayError.Budget -> {
                    keepForLater(outcome.pending)
                    val until = blockOn(error, now)
                    decisions.record(
                        log, DecisionRecord.Kind.BUDGET, "blocked", subject = "turn $id", reason = "${error.description}; silent $until",
                        conversationId = chapter.conversation.id, turnId = id, level = Log.Level.WARN,
                    )
                    TurnRecord.Outcome.BUDGET
                }
                is GatewayError.Auth, is GatewayError.Unavailable -> {
                    keepForLater(outcome.pending)
                    log.error("turn $id: ${error.description}; the Director is blocked until /narrator reload")
                    blockOn(error, now)
                    TurnRecord.Outcome.FAILED
                }
                is GatewayError.BadRequest, is GatewayError.SystemRoleRejected -> {
                    log.error("turn $id: the gateway rejected the request (request ${outcome.requestId ?: "without id"}): ${error.description}")
                    closeChapter(ConversationRecord.EndReason.BAD_REQUEST, "the gateway rejected the request: ${error.description}")
                    putBack(sent, failed = true, sentSkipped)
                    TurnRecord.Outcome.FAILED
                }
                GatewayError.ContextExceeded -> {
                    if (chapter.conversation.size == 0 && !reducedBriefing) {
                        // The chapter's very first message: nothing is committed, so it goes again at once with a reduced briefing.
                        reducedBriefing = true
                        log.warn("turn $id: the first message of chapter ${chapter.number} does not fit in the context; it goes again with a reduced briefing")
                        services.transcripts.note(chapter.conversation, "the first message does not fit in the context; sent again with a reduced briefing")
                        putBack(sent, failed = false, sentSkipped)
                        return TurnRecord.Outcome.FAILED
                    }
                    closeChapter(ConversationRecord.EndReason.CONTEXT_EXCEEDED, error.description)
                    putBack(sent, failed = false, sentSkipped)
                    TurnRecord.Outcome.FAILED
                }
            }
        }

        /** Keeps the tool results of [pending] to send first next time, and its items for the next wake-up. */
        private fun keepForLater(pending: Pending) {
            carry = pending.blocks.filter { it.isToolResult() }
            putBack(sent, failed = false, sentSkipped)
        }

        /** Closes the chapter on an error, without a summary: the next one opens after a backoff. */
        private fun closeChapter(reason: ConversationRecord.EndReason, why: String) {
            chapters.close(reason, why)
            this@Director.chapter = null
            carry = emptyList()
            closeAtQuiet = null
            reducedBriefing = false
            reopenLater(reason, why)
        }

        private fun record(outcome: TurnRecord.Outcome?, endedAt: Instant? = null) = TurnRecord(
            turnId = id,
            conversationId = chapter.conversation.id,
            startedAt = startedAt,
            endedAt = endedAt,
            trigger = trigger,
            triggerEvents = triggerEvents,
            injectedEvents = injected.toList(),
            llmCalls = outcome?.let { calls },
            toolCalls = outcome?.let { toolCalls },
            outcome = outcome,
        )

        /** `chat Bartok + 2 items`, for the log and the wake decision. */
        private fun describe(): String {
            val main = when (trigger) {
                TurnRecord.Trigger.PRAY -> openingItems.firstOrNull { it.chat?.channel == ChatChannel.PRAY }
                TurnRecord.Trigger.CHAT -> openingItems.firstOrNull { it.isAddressedChat } ?: openingItems.firstOrNull { it.chat != null }
                else -> null
            }
            val head = when {
                main?.chat != null -> "${trigger.id} ${main.chat?.player?.name}"
                beat != null -> "${trigger.id} (${beat.summary.size} active ${if (beat.summary.size == 1) "player" else "players"})"
                else -> trigger.id
            }
            val more = openingItems.size - if (main == null) 0 else 1
            return head + if (more > 0) " + $more ${if (more == 1) "item" else "items"}" else ""
        }
    }

    // ── Helpers of the coroutine ────────────────────────────────────────────────────────────────────

    /** Goes silent until [end] (a reload when null), telling the operators once per silence. */
    private fun blockUntil(end: Long?, why: String, tell: String, now: Long) {
        if (block == null) game.tellOperators(tell)
        block = Block(end, why)
        silence = silence ?: Silence(now, why)
    }

    /**
     * Puts [items] back at the head of the inbox, one failure more each when their request [failed]
     * for a reason that may be theirs; a chat line that failed twice, and anything that failed three
     * times, is dropped instead. [skipped], the counts of dropped items that request told, count again.
     */
    private fun putBack(items: List<Item>, failed: Boolean, skipped: Map<String, Int>) {
        inbox.restoreOverflow(skipped)
        val back = if (failed) items.map { it.copy(failures = it.failures + 1) } else items
        val (dropped, kept) = back.partition { it.failures >= Item.DROPPED || (it.failures >= Item.DEMOTED && it.chat != null) }
        if (dropped.isNotEmpty()) {
            decisions.record(
                log, DecisionRecord.Kind.REFUSAL, "dropped",
                subject = dropped.mapNotNull { it.chat?.player?.name }.distinct().joinToString().ifEmpty { null },
                reason = "${dropped.size} items got no usable answer ${Item.DEMOTED} times or more",
                eventId = dropped.firstNotNullOfOrNull { it.eventId }, level = Log.Level.WARN,
            )
        }
        inbox.putBack(kept)
    }

    /** [items] without the events already committed in [chapter], nor any told twice in the same batch. */
    private fun unseen(items: List<Item>, chapter: Chapter): List<Item> {
        val ids = HashSet<Long>()
        val (fresh, stale) = items.partition { item -> item.eventId.let { it == null || (it !in chapter.seen && ids.add(it)) } }
        if (stale.isNotEmpty()) log.debug { "${stale.size} events already told in chapter ${chapter.number} are not told again" }
        return fresh
    }

    private fun releaseReminders(now: Long) {
        reminders.values.filter { it.at <= now }.sortedBy { it.at }.forEach { reminder ->
            if (reminders.remove(reminder.id) != null) inbox.post(DirectorSignal.ReminderDue(reminder.id, reminder.note, reminder.setAt, reminder.at))
        }
    }

    private fun nextReminderAt(): Long? = reminders.values.minOfOrNull { it.at }

    private fun pendingReminders(): List<PendingReminder> = reminders.values.map { PendingReminder(it.id, it.note, it.at) }.sortedBy { it.at }

    private suspend fun worldTime(): Result<WorldTime> = try {
        Result.success(game.worldTime())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("the world's clock and weather cannot be read: ${e.message}")
        Result.failure(e)
    }

    private fun remember(call: ToolCallRecord) = synchronized(actions) {
        actions.addLast(DirectorAction(call.ts.toEpochMilli(), call.tool, call.targets))
        while (actions.size > LAST_ACTIONS) actions.removeFirst()
    }

    private fun recentActions(): List<DirectorAction> = synchronized(actions) { actions.toList() }

    private fun thinkingText(): String? {
        val settings = services.settings.get()
        return if (settings.config.director.thinkingIndicator) settings.ui["narrator.thinking"] else null
    }

    private fun publish(state: DirectorState, until: Long?, why: String?) {
        if (state != this.state) {
            val line = "state ${this.state.name.lowercase()} → ${state.name.lowercase()}${why?.let { ": $it" }.orEmpty()}"
            if (state in NOTABLE_STATES) log.info(line) else log.debug { line }
        }
        this.state = state
        this.until = until
        this.why = why
    }

    private data class Reminder(val id: Long, val note: String, val setAt: Long, val at: Long)

    /** Silent until [until] (a reload when null), because [why]. */
    private data class Block(val until: Long?, val why: String)

    /** The Narrator has been unable to act since [since], because [why]. */
    private data class Silence(val since: Long, val why: String) {
        fun note(now: Long) =
            "You could not act for ${ago(now - since)} ($why). The events below include what is still waiting; " +
                "answer what still matters now, do not try to catch up on everything."
    }

    /** A heartbeat: what the players did during [windowMs], and the reflexes' [hint]. */
    private data class Beat(val summary: List<String>, val windowMs: Long, val hint: String?)

    /** A chapter to close with its summary once the turn is over; [carry] are the tool results its history waits for. */
    private class Closing(val reason: ConversationRecord.EndReason, val why: String, val carry: List<ContentBlockParam>)

    companion object {
        /** A refused or cut-off answer to a wake-up waits this long before the events go again. */
        const val RETRY_DELAY_MS = 30_000L

        /** First backoff after a transient gateway failure; it doubles up to [BACKOFF_MAX_MS]. */
        const val BACKOFF_FIRST_MS = 30_000L
        const val BACKOFF_MAX_MS = 600_000L

        /**
         * First wait before a chapter reopens after closing on an error, or before a failed summary
         * request goes again; it doubles up to [CHAPTER_RETRY_MAX_MS] (M2 review, findings 22 and 23).
         */
        const val CHAPTER_RETRY_FIRST_MS = 30_000L
        const val CHAPTER_RETRY_MAX_MS = 15 * 60_000L

        /** A spent budget is retried this long after its reset time. */
        const val BUDGET_MARGIN_MS = 5_000L

        /** How long to wait when the gateway does not say when the budget resets. */
        const val BUDGET_UNKNOWN_RESET_MS = 15 * 60_000L

        /** A chapter marked for closing closes once this long has passed since the last turn, with nothing waking the Director. */
        const val QUIET_MS = 20_000L

        /** The Director's acting tool calls shown in its situation. */
        const val LAST_ACTIONS = 5

        /**
         * Longest wait of an idle Director before it looks again: players who came online without an event
         * it saw (a join dropped from a full queue) still get their heartbeats.
         */
        const val RECHECK_MS = 60_000L

        /** Pause of the loop after an unexpected exception, so that a bug never spins. */
        const val ERROR_PAUSE_MS = 5_000L

        private val ACTING = setOf(ToolEffect.SPEECH, ToolEffect.WORLD, ToolEffect.TASK)
        private const val HEARTBEAT = "heartbeat"
        private val NOTABLE_STATES = setOf(DirectorState.DISABLED, DirectorState.PAUSED, DirectorState.BACKOFF, DirectorState.BLOCKED, DirectorState.STOPPED)

        /** The wait after [step] failures in a row: [CHAPTER_RETRY_FIRST_MS] doubling, at most [CHAPTER_RETRY_MAX_MS]. */
        private fun retryDelay(step: Int): Long = (CHAPTER_RETRY_FIRST_MS shl step.coerceIn(0, 10)).coerceAtMost(CHAPTER_RETRY_MAX_MS)

        /** [a] and [b] added kind by kind. */
        private fun merged(a: Map<String, Int>, b: Map<String, Int>): Map<String, Int> =
            if (b.isEmpty()) a else LinkedHashMap(a).apply { b.forEach { (kind, count) -> merge(kind, count, Int::plus) } }

        /** The operator whispers among [items], as notes. */
        private fun whispers(items: List<Item>): List<OperatorNote> =
            items.mapNotNull { (it.signal as? DirectorSignal.OperatorWhisper)?.let { whisper -> OperatorNote.operator(whisper.from, whisper.text) } }

        /** What woke the Director, most pressing first. */
        private fun triggerOf(items: List<Item>, beat: Beat?, resumed: Boolean): TurnRecord.Trigger = when {
            items.any { it.chat?.channel == ChatChannel.PRAY } -> TurnRecord.Trigger.PRAY
            items.any { it.isAddressedChat } -> TurnRecord.Trigger.CHAT
            items.any { it.signal is DirectorSignal.OperatorWhisper } -> TurnRecord.Trigger.OPERATOR
            items.any { it.signal is DirectorSignal.TaskDone } -> TurnRecord.Trigger.TASK
            items.any { it.signal is DirectorSignal.ReminderDue } -> TurnRecord.Trigger.REMINDER
            items.any { it.urgency == Urgency.SOON } -> TurnRecord.Trigger.SALIENT
            beat != null -> TurnRecord.Trigger.HEARTBEAT
            resumed -> TurnRecord.Trigger.RESUME
            items.any { it.isUnaddressedChat } -> TurnRecord.Trigger.CHAT
            else -> TurnRecord.Trigger.SALIENT
        }

        private fun failedPending(outcome: TurnOutcome): Pending = when (outcome) {
            is TurnOutcome.Refused -> outcome.pending
            is TurnOutcome.Truncated -> outcome.pending
            is TurnOutcome.Empty -> outcome.pending
            else -> error("not a failed answer: $outcome")
        }

        private fun outcomeOf(outcome: TurnOutcome): TurnRecord.Outcome =
            if (outcome is TurnOutcome.Refused) TurnRecord.Outcome.REFUSED else TurnRecord.Outcome.TRUNCATED

        private fun what(outcome: TurnOutcome): String = when (outcome) {
            is TurnOutcome.Refused -> "refused (${outcome.category ?: "no category"})"
            is TurnOutcome.Truncated -> "cut off (${outcome.reason})"
            else -> "empty answer"
        }

        /** The engine note sent with the events after a refused, cut-off or empty answer (M1_M2 §12.9). */
        private fun noteFor(outcome: TurnOutcome): String = when (outcome) {
            is TurnOutcome.Refused ->
                "Your previous reply to these events was stopped by a safety filter (category ${outcome.category ?: "unknown"}). " +
                    "Answer differently or stay silent."
            is TurnOutcome.Truncated -> "Your previous reply was cut off (max_tokens). Be much shorter."
            else -> "Your previous replies were empty. Reply with a short text, call a tool, or end your turn."
        }

        private fun p(value: Double): String = String.format(Locale.ROOT, "%.2f", value)
    }
}
