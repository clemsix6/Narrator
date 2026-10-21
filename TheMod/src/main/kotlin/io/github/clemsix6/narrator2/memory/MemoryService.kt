package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.trace.ConversationRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * - [openIdeas]: the storyteller's open ideas, from the cache [ideas]. [store] is the store [ideas] watches, the one
 *   every piece of the memory writes through (the hygiene and the jobs included): the cache is read again after each
 *   write of an idea, each expiry and each revert. The coroutine `narrator2-ideas` (in [scope]) reads it again every
 *   [OpenIdeas.REFRESH_MS] as well, a safety net for a read that failed.
 *
 * Available from the start of the server to its stop, even through an outage of the Journal: each read or
 * write then fails with the Journal's reason, and works again once it is back.
 */
class MemoryService internal constructor(
    override val store: MemoryStore,
    private val ideas: OpenIdeas,
    override val recall: Recall,
    override val sheets: PlayerSheets,
    override val clock: PlayClock,
    override val jobs: MemoryJobs,
    private val agents: MemoryAgents,
    private val sections: MemoryBriefing,
    private val wake: WakeAttachments,
    scope: CoroutineScope,
) : Memory {
    private val refresher = scope.launch(CoroutineName(OpenIdeas.COROUTINE)) { ideas.refreshEvery(OpenIdeas.REFRESH_MS) }

    override val available: Boolean get() = true

    override val unavailableReason: String? get() = null

    override suspend fun briefing(chapter: ChapterMemory, now: Long, reduced: Boolean): List<BriefingSection> = sections.sections(chapter, now, reduced)

    override suspend fun attachments(signals: List<DirectorSignal>, chapter: ChapterMemory, now: Long): String? = wake.block(signals, chapter, now)

    override fun situationLines(now: Long): List<String> = jobs.situationLines()

    override fun openIdeas(): List<Idea> = ideas.current

    override fun chapterClosed(record: ConversationRecord) = jobs.chapterClosed(record)

    override fun runContext(conversationId: String): RunContext? = agents.runContext(conversationId)

    override suspend fun stop() {
        refresher.cancel()
        jobs.stop()
    }
}

/**
 * The cache of the storyteller's open ideas (M6 §6.2, §12.4): what [current] gives the briefing's `<programme>`, the
 * heartbeat and `inspire` without waiting for the database. [refresh] reads them again ([MemoryStore.ideas], newest
 * first); a store [watching] does after each write that changes them, and [refreshEvery] on a timer. A read that
 * fails keeps the last list (one `WARN` until a read works again). The cache only follows the writes made through
 * its [watching] store: `createMemory` gives that one store to every piece of the memory.
 */
internal class OpenIdeas(private val store: MemoryStore) {
    private val log = Log.memory
    private val lock = Mutex()

    @Volatile
    var current: List<Idea> = emptyList()
        private set

    @Volatile
    private var failing = false

    /** Reads the open ideas again; one read at a time, so the last one to end holds the latest list. */
    suspend fun refresh(why: String) {
        lock.withLock {
            try {
                current = store.ideas(IdeaStatus.OPEN, LIMIT)
                if (failing) log.info("the open ideas can be read again (${current.size})")
                failing = false
                log.debug { "open ideas: ${current.joinToString { it.ref.toString() }.ifEmpty { "none" }} ($why)" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!failing) log.warn("the open ideas could not be read ($why), the last ${current.size} kept: ${e.message ?: e.javaClass.simpleName}")
                failing = true
            }
        }
    }

    /** Reads them now, then every [periodMs], until cancelled. */
    suspend fun refreshEvery(periodMs: Long) {
        while (true) {
            refresh("timer")
            delay(periodMs)
        }
    }

    /** [store], whose writes of ideas (and the reverts, which may remove some) [refresh] the cache once they are done. */
    fun watching(store: MemoryStore): MemoryStore = object : MemoryStore by store {
        override suspend fun addIdea(draft: IdeaDraft, author: Author): IdeaAdded = store.addIdea(draft, author).also { refresh("${it.row.ref} written") }

        override suspend fun dropIdea(ref: Ref, reason: String, author: Author): Idea = store.dropIdea(ref, reason, author).also { refresh("$ref dropped") }

        override suspend fun expireIdeas(author: Author): List<Idea> = store.expireIdeas(author).also { if (it.isNotEmpty()) refresh("ideas expired") }

        override suspend fun dealt(refs: List<Ref>, channel: RecallChannel, context: RecallContext) {
            store.dealt(refs, channel, context)
            if (refs.isNotEmpty()) refresh("dealt (${channel.id})")
        }

        override suspend fun revertRun(runId: Long, author: Author): Int = store.revertRun(runId, author).also { refresh("run $runId reverted") }
    }

    companion object {
        /** The coroutine of [refreshEvery]. */
        const val COROUTINE = "narrator2-ideas"

        /** How often the cache is read again whatever was written: a safety net, when the read after a write failed. */
        const val REFRESH_MS = 10 * 60_000L

        /** Open ideas read at most: the store keeps [MemoryLimits.OPEN_IDEAS] open, this leaves room for a revert. */
        private const val LIMIT = 50
    }
}
