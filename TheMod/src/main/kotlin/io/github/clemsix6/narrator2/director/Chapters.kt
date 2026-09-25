package io.github.clemsix6.narrator2.director

import com.anthropic.models.messages.TextBlockParam
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.llm.Conversation
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.ToolEnv
import io.github.clemsix6.narrator2.tool.ToolSet
import io.github.clemsix6.narrator2.trace.ConversationRecord
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import java.time.Instant
import java.time.LocalDate

/**
 * An open chapter: one conversation of the Director with Claude, whose system prompt and tools never
 * change (M1_M2 §12.10).
 *
 * @property seen ids of the events told to the model in this chapter and committed with its reply: the
 *   conversation's cursor. An event already seen is never told again as new.
 */
class Chapter(val number: Int, val conversation: Conversation, val startedAt: Instant, private val transcript: String?) {
    val seen = HashSet<Long>()

    /** The row of `conversations`, ended with [endedAt] and [endReason] when given. */
    fun record(endedAt: Instant? = null, endReason: ConversationRecord.EndReason? = null) = ConversationRecord(
        conversationId = conversation.id,
        role = Role.DIRECTOR,
        label = conversation.label,
        chapter = number,
        taskId = null,
        startedAt = startedAt,
        endedAt = endedAt,
        endReason = endReason,
        summary = null,
        transcript = transcript,
    )
}

/**
 * The Director's chapters (M1_M2 §12.10). A chapter opens at the first wake-up without one, with the
 * `director` prompt and the tools wired at that moment ([toolSet]), frozen until it closes.
 *
 * T18 writes the opening, and the closing without a summary that the errors of §12.9 and the server's
 * stop need. The closing with a summary (`CLOSING`), the context limits, the empty server, the reload
 * that marks a chapter and the briefing that opens the next one are T19's.
 *
 * Used by the Director's coroutine only.
 */
class Chapters(private val services: NarratorServices, private val toolSet: (ToolEnv) -> ToolSet) {
    private val log = Log.director
    private val decisions = Decisions(services.trace, services.clock)
    private var lastNumber: Int? = null

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
     * Closes the open chapter now, without a summary, for [reason]: the next wake-up opens a new one.
     * Its conversation is dropped whatever its state (it may end with unanswered tool calls).
     */
    fun close(reason: ConversationRecord.EndReason, why: String) {
        val chapter = current ?: return
        current = null
        services.trace.conversation(chapter.record(services.clock.instant(), reason))
        services.transcripts.note(chapter.conversation, "chapter ${chapter.number} closed (${reason.id}): $why")
        decisions.record(
            log, DecisionRecord.Kind.CHAPTER, "closed", subject = chapter.conversation.id,
            reason = "chapter ${chapter.number}, ${reason.id}: $why; no summary", conversationId = chapter.conversation.id,
            level = if (reason == ConversationRecord.EndReason.SHUTDOWN) Log.Level.INFO else Log.Level.WARN,
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

    private companion object {
        const val READ_TIMEOUT_MS = 2_000L
    }
}
