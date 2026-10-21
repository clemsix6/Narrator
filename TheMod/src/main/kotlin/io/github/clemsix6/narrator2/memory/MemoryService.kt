package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.trace.ConversationRecord

/**
 * The Narrator's memory as it runs (M3-18): `NarratorServices.memory` over the real pieces that
 * `createMemory` assembles. It holds no logic of its own: each call goes to the piece that owns it.
 *
 * - [store], [recall], [sheets], [clock]: the DuckDB store, the hybrid recall, the sheets, the play clocks;
 * - [jobs]: the scheduler of the Chronicler's batches and naps, the Dreamer's dreams and the code's work;
 * - [briefing] and [attachments]: the memory sections of a chapter's briefing ([MemoryBriefing]) and the
 *   `<memory>` block of a wake-up ([WakeAttachments]);
 * - [situationLines] and [chapterClosed]: the jobs' cache of the situation lines, and the nap of a closed
 *   chapter;
 * - [runContext]: the run of a Chronicler's, Dreamer's or storyteller's conversation, from [MemoryAgents], the same
 *   instance the Chronicler and the Dreamer run their conversations with;
 * - [openIdeas]: the storyteller's open ideas, from a cache.
 *
 * Available from the start of the server to its stop, even through an outage of the Journal: each read or
 * write then fails with the Journal's reason, and works again once it is back.
 */
class MemoryService(
    override val store: MemoryStore,
    override val recall: Recall,
    override val sheets: PlayerSheets,
    override val clock: PlayClock,
    override val jobs: MemoryJobs,
    private val agents: MemoryAgents,
    private val sections: MemoryBriefing,
    private val wake: WakeAttachments,
) : Memory {
    override val available: Boolean get() = true

    override val unavailableReason: String? get() = null

    override suspend fun briefing(chapter: ChapterMemory, now: Long, reduced: Boolean): List<BriefingSection> = sections.sections(chapter, now, reduced)

    override suspend fun attachments(signals: List<DirectorSignal>, chapter: ChapterMemory, now: Long): String? = wake.block(signals, chapter, now)

    override fun situationLines(now: Long): List<String> = jobs.situationLines()

    /** None yet: the memory keeps no cache of the open ideas so far, and this never reads the database. */
    override fun openIdeas(): List<Idea> = emptyList()

    override fun chapterClosed(record: ConversationRecord) = jobs.chapterClosed(record)

    override fun runContext(conversationId: String): RunContext? = agents.runContext(conversationId)

    override suspend fun stop() = jobs.stop()
}
