package io.github.clemsix6.narrator2.director

import io.github.clemsix6.narrator2.journal.Journal
import io.github.clemsix6.narrator2.journal.JournalFixture
import io.github.clemsix6.narrator2.journal.JournalTimeoutException
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.oracle.WorldSnapshot
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.tool.ToolSet
import io.github.clemsix6.narrator2.tool.of
import io.github.clemsix6.narrator2.trace.ConversationRecord
import io.github.clemsix6.narrator2.trace.ConversationRecord.EndReason
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

/** How chapters are numbered (M2 review, finding 28): from the Journal's last one, read again after a failed read. */
class ChaptersTest {
    private val start = Instant.parse("2026-09-25T21:00:00Z")
    private val clock = TestClock(start)
    private val fixture = JournalFixture(clock = clock)

    @AfterEach
    fun close() = fixture.close()

    @Test
    fun `a chapter number that cannot be read at startup is read again at the next chapter`() {
        fixture.journal.conversation(
            ConversationRecord("director-old", Role.DIRECTOR, "Chapter 41", 41, null, start.minus(Duration.ofHours(2)), start.minus(Duration.ofHours(1)), EndReason.SHUTDOWN, null, null),
        )
        fixture.sync()
        val journal = FirstReadFails(fixture.journal)
        val services = TestServices(clock = clock, journal = journal, trace = fixture.journal)
        val chapters = Chapters(services, { ToolSet.of(emptyList()) }, Briefing(services, { WorldSnapshot(37, 14_000, "clear", emptyList()) }) { null })

        val numbers = runBlocking {
            List(3) {
                val chapter = chapters.current()
                chapters.close(EndReason.SHUTDOWN, "test")
                chapter.number
            }
        }

        // The first chapter could only count on this run; the next ones go on from the Journal's 41.
        assertEquals(listOf(1, 42, 43), numbers)
        assertEquals(2, journal.reads.get(), "read until one read gets through, then no more")
    }

    /** The Journal, whose first read times out (a Journal still busy at startup). */
    private class FirstReadFails(private val journal: Journal) : Journal by journal {
        val reads = AtomicInteger()

        override suspend fun <T> read(timeoutMs: Long, query: (Connection) -> T): T {
            if (reads.incrementAndGet() == 1) throw JournalTimeoutException(timeoutMs)
            return journal.read(timeoutMs, query)
        }
    }
}
