package io.github.clemsix6.narrator2.director

import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlock
import io.github.clemsix6.narrator2.agent.AgentLoop
import io.github.clemsix6.narrator2.agent.OperatorNote
import io.github.clemsix6.narrator2.agent.Pending
import io.github.clemsix6.narrator2.agent.TurnHooks
import io.github.clemsix6.narrator2.agent.TurnLimits
import io.github.clemsix6.narrator2.agent.TurnOutcome
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.llm.Conversation
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.runtime.Settings
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolEnv
import io.github.clemsix6.narrator2.tool.ToolExecutor
import io.github.clemsix6.narrator2.tool.ToolSet
import io.github.clemsix6.narrator2.trace.CallStats
import io.github.clemsix6.narrator2.trace.ConversationRecord
import io.github.clemsix6.narrator2.trace.ConversationRecord.EndReason
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import io.github.clemsix6.narrator2.trace.LlmCallRecord
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * An open chapter: one conversation of the Director with Claude, whose system prompt and tools never
 * change (M1_M2 §12.10).
 *
 * @property seen ids of the events told to the model in this chapter and committed with its reply: the
 *   conversation's cursor. An event already seen is never told again as new.
 */
class Chapter(val number: Int, val conversation: Conversation, val startedAt: Instant, private val transcript: String?) {
    val seen = HashSet<Long>()

    /** The row of `conversations`, ended with [endedAt], [endReason] and [summary] when given. */
    fun record(endedAt: Instant? = null, endReason: EndReason? = null, summary: String? = null) = ConversationRecord(
        conversationId = conversation.id,
        role = Role.DIRECTOR,
        label = conversation.label,
        chapter = number,
        taskId = null,
        startedAt = startedAt,
        endedAt = endedAt,
        endReason = endReason,
        summary = summary,
        transcript = transcript,
    )
}

/** A context limit a chapter reached: [EndReason.TOKEN_LIMIT] (close now) or [EndReason.QUIET_LIMIT] (close at a quiet moment). */
data class ChapterLimit(val reason: EndReason, val why: String)

/**
 * The Director's chapters (M1_M2 §12.10): a bounded context that holds for hundreds of hours, because
 * each chapter ends with a factual summary and the next one starts from a [Briefing] rebuilt from the
 * Journal, never from the old conversation.
 *
 * - **Opening**, at the first wake-up without a chapter: the `director` prompt and the tools wired at
 *   that moment, frozen until the chapter closes. Its first message starts with the briefing ([briefing]),
 *   built again as long as nothing of the conversation was committed.
 * - **Limits**: the context of the last request reaching `director.chapterTokenLimit` marks the chapter
 *   for closing at the next quiet moment; reaching `director.chapterHardLimit` closes it at the end of
 *   the turn ([limit]). So do `/narrator chapter close` and a `/narrator reload` that changed the prompt
 *   or the operator channel ([reloadChange]), and a server empty for `director.emptyServerCloseMinutes`
 *   ([emptyServerCloseAt]).
 * - **Closing with a summary** ([closeWithSummary]): one more request on the chapter's own conversation
 *   (its cache is warm: only the closing message is new), whose engine note is the `chapter_summary`
 *   prompt. The summary is factual, in the third person, never in the Narrator's voice (MEMOIRE.md §1.7);
 *   it goes to `conversations.summary`. Tool calls get `tools are disabled while the chapter closes` and
 *   the request goes again, twice at most. Without a usable answer, the chapter closes all the same,
 *   without a summary.
 * - **Closing without a summary** ([close]): after the errors that leave the conversation unusable
 *   (refusals, a malformed request, a full context, an engine failure) and when the server stops.
 *
 * Used by the Director's coroutine only.
 *
 * @param toolSet the tools of a new chapter, given what is wired.
 */
class Chapters(private val services: NarratorServices, private val toolSet: (ToolEnv) -> ToolSet, private val briefing: Briefing) {
    private val log = Log.director
    private val decisions = Decisions(services.trace, services.clock)
    private val closer = AgentLoop(services, ToolsDisabled)
    private val hours = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT).withZone(services.clock.zone)
    private var lastNumber: Int? = null
    private var emptySince: Long? = null

    /** The chapters closed since the server started, newest last: the briefing reads them when the Journal lags or fails. */
    private val closed = ArrayDeque<ConversationRecord>()

    /** The open chapter; null between two chapters. */
    var current: Chapter? = null
        private set

    /** The open chapter, opened now when there is none. */
    suspend fun current(): Chapter = current ?: open()

    private suspend fun open(): Chapter {
        val number = (lastNumber ?: previousNumber()) + 1
        lastNumber = number
        val env = ToolEnv(journalAvailable = services.journal.available, workerRoles = services.workers.roles)
        val tools = toolSet(env)
        val prompt = services.settings.get().prompts.get(Role.DIRECTOR.id)
        val conversation = Conversation(
            Role.DIRECTOR,
            "Chapter $number",
            listOf(TextBlockParam.builder().text(prompt).build()),
            tools,
            services.transcripts,
        )
        services.transcripts.note(conversation, "chapter $number opened with ${tools.tools.size} tools: ${tools.names.joinToString()}")
        val chapter = Chapter(number, conversation, services.clock.instant(), transcriptOf(conversation))
        services.trace.conversation(chapter.record())
        decisions.record(
            log, DecisionRecord.Kind.CHAPTER, "opened", subject = conversation.id,
            reason = "chapter $number with ${tools.tools.size} tools", conversationId = conversation.id,
        )
        current = chapter
        return chapter
    }

    /**
     * The briefing that starts [chapter]'s first message (M1_M2 §12.11); null once its conversation has
     * begun. A first message that got no usable answer committed nothing: the next one gets a fresh briefing.
     *
     * @param chatBefore chat from this time on is told by the wake-up message itself (epoch milliseconds).
     */
    suspend fun briefing(chapter: Chapter, now: Long, chatBefore: Long, reminders: List<PendingReminder>): String? {
        if (chapter.conversation.size > 0) return null
        val text = try {
            briefing.render(chapter.number, chapter.conversation.id, now, chatBefore, reminders, closed.toList())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Each source already answers for itself: this is a bug, and the turn goes on without the briefing's content.
            log.error("the briefing of chapter ${chapter.number} failed", e)
            "<briefing chapter=\"${chapter.number}\">\n(unavailable: ${e.message ?: e.javaClass.simpleName})\n</briefing>"
        }
        services.transcripts.note(chapter.conversation, "briefing of chapter ${chapter.number}: ${text.length} characters")
        return text
    }

    /** The context limit [chapter] reached with its last request, or null. */
    fun limit(chapter: Chapter): ChapterLimit? {
        val config = services.settings.get().config.director
        val tokens = chapter.conversation.contextTokens
        return when {
            tokens >= config.chapterHardLimit -> ChapterLimit(EndReason.TOKEN_LIMIT, "the context reached ${tokens / 1_000}k tokens, the hard limit is ${config.chapterHardLimit / 1_000}k")
            tokens >= config.chapterTokenLimit -> ChapterLimit(EndReason.QUIET_LIMIT, "the context reached ${tokens / 1_000}k tokens, the limit is ${config.chapterTokenLimit / 1_000}k")
            else -> null
        }
    }

    /**
     * Why the reload from [old] to [new] must close the open chapter (at a quiet moment): its prompt or
     * the operator channel changed. Null when there is no chapter or nothing of the sort changed.
     */
    fun reloadChange(old: Settings, new: Settings): String? {
        val chapter = current ?: return null
        val prompt = chapter.conversation.system.joinToString("") { it.text() }
        val changes = buildList {
            if (prompt != new.prompts.get(Role.DIRECTOR.id)) add("the director prompt changed")
            if (old.config.director.operatorChannel != new.config.director.operatorChannel) {
                add("director.operatorChannel went from ${old.config.director.operatorChannel} to ${new.config.director.operatorChannel}")
            }
        }
        return changes.joinToString(" and ").ifEmpty { null }
    }

    /**
     * When the open chapter must close because nobody has been online for `director.emptyServerCloseMinutes`
     * (epoch milliseconds, counted from the chapter's start at the earliest); null while someone is online
     * or no chapter is open. Called at each pass of the Director's loop, which notices the server empty.
     */
    fun emptyServerCloseAt(now: Long): Long? {
        if (services.players.online.isNotEmpty()) {
            emptySince = null
            return null
        }
        val since = emptySince ?: now.also { emptySince = it }
        val chapter = current ?: return null
        val minutes = services.settings.get().config.director.emptyServerCloseMinutes
        return maxOf(since, chapter.startedAt.toEpochMilli()) + minutes * 60_000L
    }

    /** Records that the open chapter will close at the next quiet moment, for [reason]. */
    fun mark(reason: EndReason, why: String) {
        val chapter = current ?: return
        services.transcripts.note(chapter.conversation, "chapter ${chapter.number} marked to close at the next quiet moment (${reason.id}): $why")
        decisions.record(
            log, DecisionRecord.Kind.CHAPTER, "marked", subject = chapter.conversation.id,
            reason = "chapter ${chapter.number}, ${reason.id}: $why", conversationId = chapter.conversation.id,
        )
    }

    /**
     * Closes the open chapter with its summary (the Director is `CLOSING`): asks the model for it on the
     * chapter's own conversation, starting with [carry] (the tool results its history waits for), then
     * closes the chapter whatever the answer. A cancellation (the server stops) leaves the chapter open,
     * for the Director to close it `shutdown`.
     */
    suspend fun closeWithSummary(reason: EndReason, why: String, carry: List<ContentBlockParam> = emptyList()) {
        val chapter = current ?: return
        val conversation = chapter.conversation
        services.transcripts.note(conversation, "chapter ${chapter.number} closing (${reason.id}): $why; asking for its summary")
        val texts = mutableListOf<String>()
        val hooks = object : TurnHooks {
            override suspend fun afterTools(): Pending = Pending(emptyList(), listOf(OperatorNote.engine(REPEAT_NOTE)))

            override fun onReply(reply: Message, stats: CallStats, llmCallId: Long) {
                if (reply.stopReason().orElse(null) == StopReason.REFUSAL) return
                val text = reply.content().mapNotNull { block -> block.text().map { it.text() }.orElse(null) }.joinToString("\n").trim()
                if (text.isNotEmpty()) texts += text
            }
        }
        val closing = ContentBlockParam.ofText("<chapter_end chapter=\"${chapter.number}\" now=\"${hours.format(services.clock.instant())}\"/>")
        val opening = Pending(carry + closing, listOf(OperatorNote.engine(services.settings.get().prompts.get(SUMMARY_PROMPT))))
        val caller = Caller(Role.DIRECTOR, conversation.id, turnId = null, taskId = null)
        val outcome = closer.runTurn(conversation, opening, caller, SUMMARY_LIMITS, hooks, LlmCallRecord.Purpose.CHAPTER_SUMMARY)
        if (outcome is TurnOutcome.Cancelled) throw CancellationException("the server is stopping while chapter ${chapter.number} closes")
        // The longest answer is the summary: a reply that also called a tool may be followed by a mere acknowledgement.
        val summary = texts.maxByOrNull { it.length }?.let { Briefing.text(it, Briefing.SUMMARY_MAX) }
        val missing = if (summary != null) null else "no usable answer (${describe(outcome)})"
        finish(chapter, reason, why, summary, missing, outcome.calls)
    }

    /**
     * Closes the open chapter now, without a summary, for [reason]: the next wake-up opens a new one.
     * Its conversation is dropped whatever its state (it may end with unanswered tool calls).
     */
    fun close(reason: EndReason, why: String) {
        val chapter = current ?: return
        finish(chapter, reason, why, summary = null, missing = null, calls = 0)
    }

    /**
     * Ends [chapter]: its `conversations` row, a transcript note and a `chapter closed` decision.
     *
     * @param missing why a summary that was asked for is missing; null when none was asked.
     */
    private fun finish(chapter: Chapter, reason: EndReason, why: String, summary: String?, missing: String?, calls: Int) {
        current = null
        val record = chapter.record(services.clock.instant(), reason, summary)
        services.trace.conversation(record)
        closed.addLast(record)
        while (closed.size > Briefing.PREVIOUS_CHAPTERS) closed.removeFirst()
        val outcome = when {
            summary != null -> "summary of ${summary.length} characters"
            missing != null -> "no summary: $missing"
            else -> "no summary"
        }
        services.transcripts.note(chapter.conversation, "chapter ${chapter.number} closed (${reason.id}): $why; $outcome")
        decisions.record(
            log, DecisionRecord.Kind.CHAPTER, "closed", subject = chapter.conversation.id,
            reason = "chapter ${chapter.number}, ${reason.id}: $why; $outcome", conversationId = chapter.conversation.id,
            detail = buildJsonObject {
                put("summary_chars", summary?.length ?: 0)
                put("summary_calls", calls)
            },
            level = if (reason in USUAL && missing == null) Log.Level.INFO else Log.Level.WARN,
        )
        chapter.conversation.close()
    }

    /** The number of the Director's last chapter in the Journal; 0 when there is none or it cannot be read. */
    private suspend fun previousNumber(): Int = try {
        services.journal.read(READ_TIMEOUT_MS) { connection ->
            connection.createStatement().use { statement ->
                statement.queryTimeout = (READ_TIMEOUT_MS / 1_000).toInt()
                statement.executeQuery("SELECT coalesce(max(chapter), 0) FROM conversations WHERE role = 'director'").use { rows ->
                    if (rows.next()) rows.getInt(1) else 0
                }
            }
        }
    } catch (e: Exception) {
        log.warn("the last chapter number cannot be read, chapters start from 1: ${e.message}")
        0
    }

    /** Where the conversation's Markdown transcript is, relative to the data folder; null without transcripts. */
    private fun transcriptOf(conversation: Conversation): String? {
        val transcripts = services.transcripts
        if (!transcripts.enabled) return null
        val file = transcripts.root.resolve(LocalDate.now().toString()).resolve("${conversation.id}.md")
        return if (file.startsWith(services.dataDir)) services.dataDir.relativize(file).toString().replace('\\', '/') else file.toString()
    }

    /** Answers every tool call of the closing requests with an error: the chapter is closing. */
    private object ToolsDisabled : ToolExecutor {
        override suspend fun runAll(calls: List<ToolUseBlock>, tools: ToolSet, caller: Caller, llmCallId: Long): List<ToolResultBlockParam> =
            calls.map { ToolResultBlockParam.builder().toolUseId(it.id()).content(TOOLS_DISABLED).isError(true).build() }
    }

    companion object {
        /** The prompt whose text is the engine note of the closing request. */
        const val SUMMARY_PROMPT = "chapter_summary"

        /** The result of a tool called while the chapter closes. */
        const val TOOLS_DISABLED = "tools are disabled while the chapter closes"

        /** The engine note that asks again after such a call. */
        const val REPEAT_NOTE = "The chapter is closing and tools are disabled: reply with the chapter summary only, as plain text."

        /** The closing request and two repeats at most: a third reply that still calls tools ends it. */
        val SUMMARY_LIMITS = TurnLimits(maxSteps = 3, hardSteps = 3)

        private const val READ_TIMEOUT_MS = 2_000L

        /** The reasons a chapter closes in the normal course of play. */
        private val USUAL = setOf(EndReason.TOKEN_LIMIT, EndReason.QUIET_LIMIT, EndReason.SERVER_EMPTY, EndReason.RELOAD, EndReason.OPERATOR, EndReason.SHUTDOWN)

        private fun describe(outcome: TurnOutcome): String = when (outcome) {
            is TurnOutcome.Ended -> "an empty answer"
            is TurnOutcome.StepLimit -> "it kept calling tools"
            is TurnOutcome.Refused -> "refused (${outcome.category ?: "no category"})"
            is TurnOutcome.Truncated -> "cut off (${outcome.reason})"
            is TurnOutcome.Empty -> "empty answers"
            is TurnOutcome.Failed -> outcome.error.description
            is TurnOutcome.Cancelled -> "cancelled"
        }
    }
}
