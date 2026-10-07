package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.testing.MemorySamples
import io.github.clemsix6.narrator2.trace.DecisionRecord
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The audit of the memory (M3 §2.3): `memory_log` before and after, reverting a job, document versions, archiving. */
class MemoryAuditTest {
    private val f = StoreFixture()
    private val store = f.store
    private val evening = MemorySamples.evening

    @AfterEach
    fun close() = f.close()

    private val promise = CanonDraft(
        CanonKind.PROMISE, "Le Narrateur a promis à Mimosa de lui rendre ses bottes de fer.", players = listOf(MemorySamples.mimosa.uuid),
    )

    private val tables = listOf(
        "memories", "canon", "interactions", "chronicle", "notes", "player_traits", "player_skills", "player_relations", "memory_docs",
        "search_docs", "search_postings",
    )

    private fun state(): Map<String, List<String>> = tables.associateWith { f.dump(it) }

    @Test
    fun `each change keeps the row's columns before and after it, under their SQL names`() = runBlocking<Unit> {
        val gag = store.addCanon(CanonDraft(CanonKind.RUNNING_GAG, "Le Narrateur commente chaque chute de Bartok.", players = listOf(f.bartok.uuid)), f.director).row
        f.clock.advance(Duration.ofMinutes(5))
        store.useCanon(gag.ref, evening.plusSeconds(200), MemorySamples.chronicler)
        val log = store.log(gag.ref)
        assertEquals(listOf(LogOp.INSERT, LogOp.UPDATE), log.map { it.op })
        val (insert, use) = log
        assertNull(insert.before)
        assertEquals("fresh", insert.after!!["freshness"]!!.jsonPrimitive.content)
        assertEquals(0L, insert.after!!["uses"]!!.jsonPrimitive.content.toLong())
        assertEquals(insert.after, use.before)
        assertEquals(1L, (use.after!!["uses"] as JsonPrimitive).content.toLong())
        assertTrue(use.after!!.keys.containsAll(listOf("id", "kind", "statement", "players", "uses", "last_used_at", "freshness", "evidence_events")))
        assertEquals("chronicler", use.actor)
        assertEquals(7L, use.runId)
        assertEquals(evening.plusSeconds(300), use.ts)
        assertEquals(listOf(evening.plusSeconds(200)), Hygiene.usesSinceFresh(log), "M3-06 reads last_used_at back from the audit")
    }

    @Test
    fun `reverting a job puts every row back as it was, newest first, and only once`() = runBlocking<Unit> {
        val bartokMemory = store.addMemory(MemoryDraft(MemoryKind.EPISODE, "Plongeon", "Bartok a sauté dans la lave.", listOf(f.bartok.uuid)), f.director).row
        val nickname = store.addCanon(CanonDraft(CanonKind.NICKNAME, "Bartok est surnommé « le Plongeur ».", "le Plongeur", listOf(f.bartok.uuid)), f.director).row
        val open = store.addCanon(promise, f.director).row
        val season = store.addChronicle(ChronicleDraft(ChronicleLevel.CHAPTER, evening, evening, "Chapitre", "Un chapitre."), f.director)
        store.writeDoc(DocKey.Grimoire, "## Players\n- Bartok plonge.", emptyList(), f.dreamer)
        store.setTrait(TraitDraft(f.bartok.uuid, "boldness", 1, intensity = 3, confidence = 0.6, evidence = listOf(bartokMemory.ref)), f.dreamer)
        val merged = store.addMemory(MemoryDraft(MemoryKind.EPISODE, "Encore la lave", "Bartok a replongé dans la lave.", listOf(f.bartok.uuid)), f.director).row
        val before = state()

        val dream = Author(Source.DREAMER, runId = 80)
        f.clock.advance(Duration.ofMinutes(10))
        store.addCanon(CanonDraft(CanonKind.NICKNAME, "Bartok est surnommé « la Taupe ».", "la Taupe", listOf(f.bartok.uuid)), dream)
        store.resolveCanon(open.ref, CanonStatus.FULFILLED, "Le Narrateur a rendu les bottes.", emptyList(), dream)
        store.setTrait(TraitDraft(f.bartok.uuid, "boldness", -1, intensity = 4, confidence = 0.9, evidence = listOf(bartokMemory.ref)), dream)
        store.setTrait(TraitDraft(f.bartok.uuid, "humor", 1, intensity = 2, confidence = 0.6, evidence = listOf(bartokMemory.ref)), dream)
        store.setSkill(f.bartok.uuid, Skill.MINING, 7, "41 diamants", emptyList(), dream)
        store.writeDoc(DocKey.Grimoire, "## Players\n- Bartok creuse.", emptyList(), dream)
        store.addChronicle(ChronicleDraft(ChronicleLevel.SESSION, evening, evening, "Session", "Une session.", listOf(season.ref)), dream)
        store.merge(bartokMemory.ref, listOf(merged.ref), "Bartok a plongé deux fois dans la lave.", dream)
        store.addNote(NoteDraft(NoteKind.DREAM_HINT, "Bartok tombe souvent.", listOf(f.bartok.uuid)), dream)
        assertEquals(CanonStatus.RETIRED, store.canon(nickname.ref)!!.status)

        val undone = store.revertRun(80, f.operator)
        assertEquals(11, undone)
        val after = state()
        tables.forEach { table -> assertEquals(before.getValue(table), after.getValue(table), table) }
        assertEquals(CanonStatus.ACTIVE, store.canon(nickname.ref)!!.status)
        assertEquals(CanonStatus.ACTIVE, store.canon(open.ref)!!.status)
        assertEquals(1, store.traits(f.bartok.uuid).single().side)
        assertEquals("## Players\n- Bartok plonge.", store.doc("grimoire")!!.content)
        assertFalse(store.memory(merged.ref)!!.archived, "the merge is undone")
        assertEquals("Bartok a sauté dans la lave.", store.memory(bartokMemory.ref)!!.content)
        assertEquals(LogOp.REVERT, store.log(open.ref).last().op)
        assertEquals("operator:Clement", store.log(open.ref).last().actor)
        val reverted = f.journal.sink.decisions.single { it.kind == DecisionRecord.Kind.MEMORY && it.decision == "reverted" }
        assertContains(reverted.reason.orEmpty(), "11 writes undone")
        assertContains(
            assertFailsWith<MemoryRejected> { store.revertRun(80, f.operator) }.message.orEmpty(),
            "run 80 was reverted already",
        )
        assertEquals(0, store.revertRun(404, f.operator), "a job that wrote nothing")
    }

    @Test
    fun `reverting a job restores the index of the rows it changed`() = runBlocking<Unit> {
        val kept = store.addMemory(MemoryDraft(MemoryKind.EPISODE, "Plongeon", "Bartok a sauté dans la lave.", listOf(f.bartok.uuid)), f.director).row
        val other = store.addMemory(MemoryDraft(MemoryKind.EPISODE, "Saut", "Bartok a sauté du pont.", listOf(f.bartok.uuid)), f.director).row
        val index = f.dump("search_postings")
        store.merge(kept.ref, listOf(other.ref), "Bartok saute partout, des ponts comme des falaises.", Author(Source.DREAMER, runId = 9))
        store.addCanon(promise, Author(Source.DREAMER, runId = 9))
        store.revertRun(9, f.operator)
        assertEquals(index, f.dump("search_postings"))
    }

    @Test
    fun `a job whose rows changed since cannot be reverted`() = runBlocking<Unit> {
        val added = store.addCanon(promise, Author(Source.CHRONICLER, runId = 7)).row
        store.resolveCanon(added.ref, CanonStatus.FULFILLED, "Le Narrateur a rendu les bottes.", emptyList(), f.director)
        val error = assertFailsWith<MemoryRejected> { store.revertRun(7, f.operator) }
        assertContains(error.message.orEmpty(), "run 7 cannot be reverted: rows it wrote changed since: canon ${added.ref.id} by director")
        assertEquals(CanonStatus.FULFILLED, store.canon(added.ref)!!.status)
    }

    @Test
    fun `rolling a document back copies the old version as a new one`() = runBlocking<Unit> {
        store.writeDoc(DocKey.Review, "Les formulaires font rire Mimosa.", emptyList(), f.dreamer)
        store.writeDoc(DocKey.Review, "Les formulaires ne font plus rire.", emptyList(), f.dreamer)
        val restored = store.rollbackDoc("review", 1, f.operator)
        assertEquals(3, restored.version)
        assertEquals(1, restored.restoredFrom)
        assertEquals(Source.OPERATOR, restored.source)
        assertEquals("Les formulaires font rire Mimosa.", store.doc("review")!!.content)
        assertEquals(listOf(3, 2, 1), store.docVersions("review").map { it.version })
        assertEquals("rollback", f.rows("SELECT op FROM memory_log WHERE row_key = 'review:3'") { getString(1) }.single())
        assertContains(assertFailsWith<MemoryRejected> { store.rollbackDoc("review", 9, f.operator) }.message.orEmpty(), "review has no version 9 (latest: 3)")
        assertContains(assertFailsWith<MemoryRejected> { store.rollbackDoc("diary", 1, f.operator) }.message.orEmpty(), "diary is not a document")
    }

    @Test
    fun `archiving is reversible and audited`() = runBlocking<Unit> {
        val row = store.addCanon(promise, f.director).row
        store.archive(row.ref, true, "operator forget", f.operator)
        assertTrue(store.canon(row.ref)!!.archived)
        assertEquals(emptyList(), store.canon(CanonFilter()))
        assertContains(assertFailsWith<MemoryRejected> { store.archive(row.ref, true, "again", f.operator) }.message.orEmpty(), "archived already")
        store.archive(row.ref, false, "operator restore", f.operator)
        assertFalse(store.canon(row.ref)!!.archived)
        assertEquals(listOf(LogOp.INSERT, LogOp.ARCHIVE, LogOp.RESTORE), store.log(row.ref).map { it.op })
        assertEquals("operator restore", store.log(row.ref).last().reason)
        val note = store.addNote(NoteDraft(NoteKind.OPERATOR, "Le serveur ferme dimanche."), f.operator)
        assertContains(assertFailsWith<MemoryRejected> { store.archive(note.ref, true, "x", f.operator) }.message.orEmpty(), "cannot be archived")
    }
}
