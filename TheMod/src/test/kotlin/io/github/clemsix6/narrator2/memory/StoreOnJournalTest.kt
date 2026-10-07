package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.event.NarratorChannel
import io.github.clemsix6.narrator2.event.NarratorSpoke
import io.github.clemsix6.narrator2.journal.JournalFixture
import io.github.clemsix6.narrator2.testing.FakeMemory
import io.github.clemsix6.narrator2.testing.MemorySamples
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** The store on the real Journal (M3-02): writes on its writer thread, reads on its pool, facts just published seen. */
class StoreOnJournalTest {
    @Test
    fun `the store writes through the Journal's writer, sees what was just said and rolls a refused write back`() {
        JournalFixture().use { fixture ->
            val journal = fixture.journal
            val store = DuckMemoryStore(journal, FakeIndexer(), FakeMemory.FakePlayClock(), fixture.clock)
            val mimosa = MemorySamples.mimosa
            runBlocking<Unit> {
                journal.write(10_000) { connection ->
                    connection.update("INSERT INTO players VALUES (?, ?, ?, ?, 1, 0, false)", mimosa.uuid, mimosa.name, fixture.clock.instant(), fixture.clock.instant())
                    connection.update(
                        "INSERT INTO memory_runs (run_id, kind, trigger, status, started_at, window_from, window_to, conversations) " +
                            "VALUES (7, 'batch', 'timer', 'running', ?, ?, ?, [])",
                        fixture.clock.instant(), fixture.clock.instant().minusSeconds(600), fixture.clock.instant().plusSeconds(600),
                    )
                }
                fixture.publish(NarratorSpoke(NarratorChannel.NARRATOR, "Mimosa, je te rends tes bottes dans une heure, promis.", null, 42, null))
                val chronicler = Author(Source.CHRONICLER, runId = 7)
                val draft = CanonDraft(
                    CanonKind.PROMISE, "Le Narrateur a promis à Mimosa de lui rendre ses bottes dans l'heure.",
                    quote = "je te rends tes bottes dans une heure, promis", players = listOf(mimosa.uuid),
                    dueAt = fixture.clock.instant().plus(Duration.ofHours(1)),
                )
                val added = store.addCanon(draft, chronicler)
                assertIs<Added.New<CanonEntry>>(added)
                assertEquals(added.row, store.canon(added.row.ref))
                assertEquals(listOf(LogOp.INSERT), store.log(added.row.ref).map { it.op })

                val refused = assertFailsWith<MemoryRejected> {
                    store.addCanon(draft.copy(kind = CanonKind.THREAT, quote = "je garde les bottes"), chronicler)
                }
                assertContains(refused.message.orEmpty(), "quote must be words the Narrator said")
                assertEquals(1, fixture.count("canon"))

                store.resolveCanon(added.row.ref, CanonStatus.FULFILLED, "Le Narrateur a rendu les bottes.", emptyList(), MemorySamples.director)
                assertEquals(CanonStatus.FULFILLED, store.canon(added.row.ref)!!.status)
                assertEquals(1, store.canon(CanonFilter(open = false)).size)
            }
        }
    }
}
