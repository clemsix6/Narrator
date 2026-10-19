package io.github.clemsix6.narrator2.trace

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.event.ChatMessage
import io.github.clemsix6.narrator2.event.NarratorChannel
import io.github.clemsix6.narrator2.event.NarratorSpoke
import io.github.clemsix6.narrator2.event.PlayerDied
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.journal.IdKind
import io.github.clemsix6.narrator2.journal.JournalFixture
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.reflex.ChatIntent
import io.github.clemsix6.narrator2.reflex.Tone
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolStatus
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * A small history of the Director, written through the real Journal (bus, mapping, writer), for the
 * trace queries and their rendering. Times are those of the fixture's clock, from [T0]:
 *
 * - Bartok insults the Narrator (addressed) and Mimosa dies: turn 42 wakes up. Its first request slows
 *   Bartok down and is refused against Mimosa (`no_pile_on`); Mimosa prays meanwhile (injected); its
 *   second request says a line to everyone; its third ends the turn. Outcome `acted`.
 * - Task 7 of the Analyst, asked by turn 42, runs one `sql` and answers.
 * - Steve chats between players: nothing wakes up. A heartbeat turn 43 finds nothing to do (`idle`).
 * - An operator makes the Narrator say a line; the reflex model triages twice, once in error.
 */
internal class TraceDataset : AutoCloseable {
    val fixture = JournalFixture()
    private val journal = fixture.journal
    private val clock = fixture.clock
    private val decisions = Decisions(journal, clock)

    var insult = 0L
    var death = 0L
    var prayer = 0L
    var smallTalk = 0L

    init {
        fill()
        fixture.sync()
    }

    /** Runs [block] on a read connection of the Journal. */
    fun <T> read(block: (TraceQueries) -> T): T = runBlocking { journal.read(10_000) { block(TraceQueries(it)) } }

    private fun at(seconds: Long) {
        clock.now = T0.plusSeconds(seconds)
    }

    private fun fill() {
        journal.conversation(ConversationRecord(CHAPTER, Role.DIRECTOR, "Chapter 3", 3, null, T0, null, null, null, TRANSCRIPT))

        at(0)
        insult = fixture.publish(ChatMessage(BARTOK, ChatChannel.PUBLIC, "narrateur, t'es nul", emptyList())).id
        journal.chatSignals(
            ChatSignalsRecord(
                insult, clock.instant(), BARTOK.uuid, "heuristic", 0.92, Tone.HOSTILE, 0.8, ChatIntent.PROVOCATION, 0.1,
                null, null, null, null, null, 2,
            ),
        )
        decisions.record(Log.director, DecisionRecord.Kind.SALIENCE, "immediate", "Bartok", "addressed: to you 0.92", eventId = insult)
        at(1)
        death = fixture.publish(PlayerDied(MIMOSA, Pos("minecraft:overworld", 5, 64, 5), "minecraft:fall", null, null, null, 3, false, false)).id
        decisions.record(Log.director, DecisionRecord.Kind.SALIENCE, "soon", "Mimosa", "death", eventId = death)

        // Turn 42.
        at(3)
        val turn = TurnRecord(42, CHAPTER, clock.instant(), null, TurnRecord.Trigger.CHAT, listOf(insult, death), emptyList(), null, null, null)
        journal.turn(turn)
        decisions.record(Log.director, DecisionRecord.Kind.WAKE, "woke", "turn 42", "chat Bartok + 1 item", eventId = insult, conversationId = CHAPTER, turnId = 42)
        val first = call(42, CHAPTER, "Bartok mocks me again; a slowness will teach him. Mimosa just died: leave her be.", 1_200, 60_000, 300, "tool_use", 5_200)
        at(8)
        tool(42, CHAPTER, first, "apply_effect", """{"player":"Bartok","effect":"slowness","level":2,"seconds":60}""", ToolStatus.OK, "ok: slowness II on Bartok for 60 s", listOf("Bartok"), ToolEffect.WORLD)
        tool(42, CHAPTER, first, "apply_effect", """{"player":"Mimosa","effect":"weakness"}""", ToolStatus.DENIED, "DENIED (no_pile_on): Mimosa died 7s ago", emptyList(), ToolEffect.WORLD)
        decisions.record(Log.guard, DecisionRecord.Kind.GUARD, "denied", "Mimosa", "no_pile_on: Mimosa died 7s ago", conversationId = CHAPTER, turnId = 42)
        prayer = fixture.publish(ChatMessage(MIMOSA, ChatChannel.PRAY, "pitié, Narrateur", emptyList())).id
        decisions.record(Log.director, DecisionRecord.Kind.SALIENCE, "immediate", "Mimosa", "prayer: to you 0.99", eventId = prayer)
        at(9)
        val second = call(42, CHAPTER, "Now a jab for everyone.", 150, 61_300, 80, "tool_use", 2_100)
        at(11)
        tool(42, CHAPTER, second, "say", """{"text":"Bartok, ta pioche pleure."}""", ToolStatus.OK, "ok: said to everyone (2 online)", listOf("Bartok", "Mimosa"), ToolEffect.SPEECH)
        fixture.publish(NarratorSpoke(NarratorChannel.NARRATOR, "Bartok, ta pioche pleure.", null, turnId = 42, operator = null))
        call(42, CHAPTER, null, 100, 61_500, 12, "end_turn", 900)
        at(14)
        journal.turn(turn.copy(endedAt = clock.instant().minusMillis(900), injectedEvents = listOf(prayer), llmCalls = 3, toolCalls = 3, outcome = TurnRecord.Outcome.ACTED))

        // Task 7 of the Analyst, asked by turn 42.
        at(20)
        val task = TaskRecord(7, Role.ANALYST, "Where does Bartok mine?", TaskRecord.Status.RUNNING, clock.instant(), clock.instant(), null, 42, ANALYST, null, null)
        journal.task(task)
        journal.conversation(ConversationRecord(ANALYST, Role.ANALYST, "Task T-7", null, 7, clock.instant(), null, null, null, "logs/conversations/2026-09-25/$ANALYST.md"))
        val work = call(null, ANALYST, "A hotspot query answers this.", 3_000, 0, 200, "tool_use", 3_000, Role.ANALYST, LlmCallRecord.Purpose.WORKER)
        at(24)
        tool(null, ANALYST, work, "sql", """{"query":"SELECT * FROM hotspots('Bartok')"}""", ToolStatus.OK, "dim|x|z|n\noverworld|-1400|3100|72", emptyList(), ToolEffect.READ, Role.ANALYST)
        at(41)
        journal.task(task.copy(status = TaskRecord.Status.DONE, finishedAt = clock.instant(), result = "Bartok mines around -1400 -40 3100 (72% of his ore)."))

        // Small talk and a quiet heartbeat.
        at(60)
        smallTalk = fixture.publish(ChatMessage(STEVE, ChatChannel.PUBLIC, "salut tout le monde", emptyList())).id
        decisions.record(Log.director, DecisionRecord.Kind.SALIENCE, "next", "Steve", "between players: greeting", eventId = smallTalk)
        at(300)
        val beat = TurnRecord(43, CHAPTER, clock.instant(), null, TurnRecord.Trigger.HEARTBEAT, emptyList(), emptyList(), null, null, null)
        journal.turn(beat)
        decisions.record(Log.director, DecisionRecord.Kind.WAKE, "woke", "turn 43", "heartbeat (1 active player)", conversationId = CHAPTER, turnId = 43)
        call(43, CHAPTER, "Nothing worth a word.", 400, 62_000, 20, "end_turn", 1_500)
        at(302)
        journal.turn(beat.copy(endedAt = clock.instant(), llmCalls = 1, toolCalls = 0, outcome = TurnRecord.Outcome.IDLE))

        // An operator's line and the reflexes.
        at(310)
        fixture.publish(NarratorSpoke(NarratorChannel.OPERATOR, "Silence.", null, turnId = null, operator = "Clement"))
        reflex(null)
        reflex("timeout after 3000 ms")
    }

    /** One Anthropic call; returns its id. Its start is [latencyMs] before the clock. */
    private fun call(
        turnId: Long?,
        conversation: String,
        thinking: String?,
        input: Int,
        cacheRead: Int,
        output: Int,
        stop: String,
        latencyMs: Int,
        role: Role = Role.DIRECTOR,
        purpose: LlmCallRecord.Purpose = LlmCallRecord.Purpose.TURN,
    ): Long {
        val id = journal.nextId(IdKind.LLM_CALL)
        journal.llmCall(
            LlmCallRecord(
                id = id, provider = LlmCallRecord.Provider.ANTHROPIC, purpose = purpose, conversationId = conversation, turnId = turnId,
                role = role.id, model = "claude-opus-5-5", effort = "medium", startedAt = clock.instant().minusMillis(latencyMs.toLong()),
                endedAt = clock.instant(), latencyMs = latencyMs, ttftMs = latencyMs - 100, attempts = 1, requestId = "req_$id", messageId = "msg_$id",
                inputTokens = input, cacheReadTokens = cacheRead, cacheWriteTokens = 0, outputTokens = output, stopReason = stop,
                refusalCategory = null, error = null, httpStatus = null, thinking = thinking,
            ),
        )
        return id
    }

    private fun tool(
        turnId: Long?,
        conversation: String,
        llmCallId: Long,
        tool: String,
        args: String,
        status: ToolStatus,
        result: String,
        targets: List<String>,
        effect: ToolEffect,
        role: Role = Role.DIRECTOR,
    ) {
        val id = journal.nextId(IdKind.TOOL_CALL)
        journal.toolCall(ToolCallRecord(id, clock.instant(), conversation, turnId, llmCallId, "toolu_$id", role, tool, args, status, result, 40, targets, effect))
    }

    private fun reflex(error: String?) {
        journal.llmCall(
            LlmCallRecord(
                id = journal.nextId(IdKind.LLM_CALL), provider = LlmCallRecord.Provider.ANTHROPIC, purpose = LlmCallRecord.Purpose.CHAT_TRIAGE,
                conversationId = null, turnId = null, role = "reflex", model = "claude-haiku-5-5", effort = "low",
                startedAt = clock.instant(), endedAt = clock.instant().plusMillis(800), latencyMs = 800, ttftMs = null, attempts = 1,
                requestId = null, messageId = null, inputTokens = if (error == null) 90 else null, cacheReadTokens = null, cacheWriteTokens = null,
                outputTokens = if (error == null) 30 else null, stopReason = if (error == null) "end_turn" else null, refusalCategory = null, error = error, httpStatus = null, thinking = null,
            ),
        )
    }

    override fun close() = fixture.close()

    companion object {
        val T0: Instant = Instant.parse("2026-09-25T19:30:00Z")

        /** A minute after the last row. */
        val NOW: Instant = T0.plus(Duration.ofSeconds(370))

        const val CHAPTER = "director-aaaa"
        const val ANALYST = "analyst-bbbb"
        const val TRANSCRIPT = "logs/conversations/2026-09-25/director-aaaa.md"

        val BARTOK = PlayerRef(UUID.fromString("00000000-0000-4000-8000-0000000000b1"), "Bartok")
        val MIMOSA = PlayerRef(UUID.fromString("00000000-0000-4000-8000-0000000000b2"), "Mimosa")
        val STEVE = PlayerRef(UUID.fromString("00000000-0000-4000-8000-0000000000b3"), "Steve")
    }
}
