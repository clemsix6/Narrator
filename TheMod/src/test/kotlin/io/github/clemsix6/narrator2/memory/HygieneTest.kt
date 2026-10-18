package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.journal.JournalFixture
import io.github.clemsix6.narrator2.observer.PlayerStates
import io.github.clemsix6.narrator2.testing.FakeMemoryState
import io.github.clemsix6.narrator2.testing.FakeMemoryStore
import io.github.clemsix6.narrator2.testing.MemoryTraceSink
import io.github.clemsix6.narrator2.trace.Decisions
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.AfterEach
import io.github.clemsix6.narrator2.testing.MemorySamples
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The memory's housekeeping (M3 §6.6, §8.6, §9.3): expirations, worn gags, archival, notes, merge candidates. */
class HygieneTest {
    private val fixture = JournalFixture()
    private val time = fixture.clock
    private val start: Instant = time.now
    private val fake = FakeMemoryStore(time).apply {
        names[bartok.uuid] = bartok.name
        names[mimosa.uuid] = mimosa.name
    }
    private val store = AuditedStore(fake)
    private val players = PlayerStates()
    private val state = FakeMemoryState()
    private val playClock = DuckPlayClock(state, fixture.journal, players)
    private val indexer = WordIndexer()
    private val trace = MemoryTraceSink()
    private val hygiene = Hygiene(store, fixture.journal, playClock, indexer, Decisions(trace, time), ZoneOffset.UTC)
    private val engine = Author(Source.ENGINE)

    @AfterEach
    fun close() = fixture.close()

    private fun at(hours: Double): Instant = start + Duration.ofSeconds((hours * 3_600).toLong())

    private fun sql(statement: String) = fixture.db.writer.createStatement().use { it.execute(statement) }

    private fun session(id: Long, player: PlayerRef, fromHours: Double, toHours: Double) = sql(
        "INSERT INTO sessions (session_id, player_uuid, player, joined_at, left_at, end_reason) " +
            "VALUES ($id, '${player.uuid}', '${player.name}', '${at(fromHours)}', '${at(toHours)}', 'leave')",
    )

    private fun decisions(name: String) = trace.decisions.filter { it.decision == name }.map { it.subject }

    private suspend fun canon(kind: CanonKind, statement: String, player: PlayerRef, due: Instant? = null): Ref =
        fake.addCanon(CanonDraft(kind, statement, players = listOf(player.uuid), dueAt = due), Author(Source.DIRECTOR)).row.ref

    private fun status(ref: Ref) = (fake.rows.getValue(ref) as CanonEntry).status

    @Test
    fun `canon expires 24 hours after its due time, threats and prophecies after 100 hours of play without mention`() = runBlocking {
        session(1, bartok, 0.0, 103.0)
        playClock.load(start.toEpochMilli())
        val late = canon(CanonKind.PROMISE, "Le Narrateur a promis à Mimosa de lui rendre ses bottes.", mimosa, at(1.0))
        val pending = canon(CanonKind.CHALLENGE, "Bartok doit tuer le Wither.", bartok, at(80.0))
        val arc = canon(CanonKind.ARC, "La vengeance des moutons.", bartok, at(1.0))
        val threat = canon(CanonKind.THREAT, "Le Narrateur a menacé Bartok de pluie de grenouilles.", bartok)
        val law = canon(CanonKind.LAW, "Nul ne creuse sous le spawn.", bartok)
        time.now = at(5.0)
        val young = canon(CanonKind.PROPHECY, "Bartok mourra dans la lave.", bartok)
        val mentioned = canon(CanonKind.THREAT, "Le Narrateur a menacé Bartok d'un orage.", bartok)
        fake.useCanon(mentioned, at(10.0), Author(Source.CHRONICLER)) // 93 h of play since
        time.now = at(103.0)

        val report = hygiene.tidy(time.now)

        assertEquals(listOf(late, threat), report.expired)
        assertEquals(CanonStatus.EXPIRED, status(late))
        assertEquals(Hygiene.NO_RESOLUTION, (fake.rows.getValue(late) as CanonEntry).resolution)
        assertEquals(CanonStatus.EXPIRED, status(threat))
        listOf(pending, arc, law, young, mentioned).forEach { assertEquals(CanonStatus.ACTIVE, status(it), "$it stays open") }
        val notes = fake.rows.values.filterIsInstance<Note>().sortedBy { it.ref.id }
        assertEquals(
            listOf(
                "$late promise (« Mimosa ») came due 09-25 20:30 with no resolution recorded: « Le Narrateur a promis à Mimosa de lui rendre ses bottes. »",
                "$threat threat (« Bartok ») went 103h of world play without mention: « Le Narrateur a menacé Bartok de pluie de grenouilles. »",
            ),
            notes.map { it.text },
        )
        assertEquals(listOf(NoteKind.EXPIRED, NoteKind.EXPIRED), notes.map { it.kind })
        assertEquals(listOf(listOf(late), listOf(threat)), notes.map { it.refs })
        assertEquals(listOf(mimosa), notes.first().players)
        assertTrue(fake.writes.contains("resolveCanon $late by engine"))
        assertEquals(listOf(late.toString(), threat.toString()), decisions("canon_expired"))

        assertEquals(HygieneReport(), hygiene.tidy(time.now), "nothing more to do")
    }

    @Test
    fun `a long statement is cut to fit the note`() = runBlocking {
        playClock.load(start.toEpochMilli())
        val promise = canon(CanonKind.PROMISE, "Le Narrateur a promis ".repeat(13).trim() + ".", mimosa, at(1.0))
        time.now = at(30.0)
        hygiene.tidy(time.now)
        val note = fake.rows.values.filterIsInstance<Note>().single()
        assertTrue(note.text.length <= MemoryLimits.NOTE_TEXT, "${note.text.length} characters")
        assertTrue(note.text.endsWith("… »"), note.text)
        assertTrue(note.text.startsWith("$promise promise (« Mimosa ») came due"), note.text)
    }

    @Test
    fun `the names of the note are quoted, so a player called Moi does not read as the Narrator's voice`() = runBlocking<Unit> {
        val moi = PlayerRef(UUID.fromString("00000000-0000-4000-8000-0000000000d1"), "Moi")
        val toi = PlayerRef(UUID.fromString("00000000-0000-4000-8000-0000000000d2"), "Toi")
        fake.names[moi.uuid] = moi.name
        fake.names[toi.uuid] = toi.name
        playClock.load(start.toEpochMilli())
        val bet = fake.addCanon(
            CanonDraft(CanonKind.CHALLENGE, "Le Narrateur a parié contre Bartok.", players = listOf(moi.uuid, toi.uuid), dueAt = at(1.0)),
            Author(Source.DIRECTOR),
        ).row.ref
        time.now = at(30.0)
        hygiene.tidy(time.now)
        val note = fake.rows.values.filterIsInstance<Note>().single()
        assertEquals("$bet challenge (« Moi », « Toi ») came due 09-25 20:30 with no resolution recorded: « Le Narrateur a parié contre Bartok. »", note.text)
        assertNull(VoiceGuard.check(note.text, "text", MemoryLimits.NOTE_TEXT))
    }

    private fun use(ref: Ref, uses: Int, at: Instant) = MemoryLogRow(
        at + Duration.ofMinutes(5), "chronicler", null, null, "canon", ref.id.toString(), LogOp.UPDATE,
        buildJsonObject {
            put("uses", JsonPrimitive(uses - 1))
            put("freshness", JsonPrimitive("fresh"))
        },
        buildJsonObject {
            put("uses", JsonPrimitive(uses))
            put("freshness", JsonPrimitive("fresh"))
            put("last_used_at", JsonPrimitive(at.toString()))
        },
        null,
    )

    private fun freshness(ref: Ref, from: GagState, to: GagState, at: Instant) = MemoryLogRow(
        at, "dreamer", null, null, "canon", ref.id.toString(), LogOp.UPDATE,
        buildJsonObject {
            put("uses", JsonPrimitive(2))
            put("freshness", JsonPrimitive(from.id))
        },
        buildJsonObject {
            put("uses", JsonPrimitive(2))
            put("freshness", JsonPrimitive(to.id))
        },
        null,
    )

    private suspend fun gag(statement: String, vararg usedAt: Double): Ref {
        val ref = canon(CanonKind.RUNNING_GAG, statement, bartok)
        usedAt.forEach { fake.useCanon(ref, at(it), Author(Source.CHRONICLER)) }
        store.logs[ref] = usedAt.mapIndexed { i, hours -> use(ref, i + 1, at(hours)) }
        return ref
    }

    @Test
    fun `a gag served three times within 20 hours of world play is worn`() = runBlocking {
        session(1, bartok, 0.0, 10.0)
        session(2, bartok, 30.0, 60.0)
        playClock.load(start.toEpochMilli())
        val tired = gag("Bartok et sa pioche en or.", 1.0, 2.0, 3.0)
        val spaced = gag("Bartok et les moutons.", 1.0, 15.0, 35.0) // 9 h + 5 h of play: 14 h < 20 h
        val slow = gag("Bartok et les creepers.", 1.0, 31.0, 55.0) // 9 h + 25 h = 34 h of play
        val few = gag("Bartok et la lave.", 1.0, 2.0)
        val refreshed = gag("Bartok et son chapeau.", 1.0, 2.0, 3.0)
        store.logs[refreshed] = store.logs.getValue(refreshed).take(2) +
            freshness(refreshed, GagState.FRESH, GagState.WORN, at(2.2)) +
            freshness(refreshed, GagState.WORN, GagState.FRESH, at(2.5)) +
            use(refreshed, 3, at(3.0))
        time.now = at(60.0)

        val report = hygiene.tidy(time.now)

        assertEquals(listOf(tired, spaced), report.worn)
        assertEquals(GagState.WORN, (fake.rows.getValue(tired) as CanonEntry).freshness)
        listOf(slow, few, refreshed).forEach { assertEquals(GagState.FRESH, (fake.rows.getValue(it) as CanonEntry).freshness, "$it") }
        assertEquals(listOf(tired.toString(), spaced.toString()), decisions("gag_worn"))
        assertTrue(fake.writes.contains("setGag $tired by engine"))
        assertEquals(emptyList(), hygiene.tidy(time.now).worn, "a worn gag stays worn")
    }

    @Test
    fun `uses are read from the audit, and counted again from the last refresh`() {
        val ref = Ref(RefKind.CANON, 27)
        val rows = listOf(
            use(ref, 1, at(1.0)), use(ref, 2, at(2.0)), freshness(ref, GagState.WORN, GagState.FRESH, at(2.5)), use(ref, 3, at(3.0)),
        )
        assertEquals(listOf(at(3.0)), Hygiene.usesSinceFresh(rows))
        assertEquals(listOf(at(1.0), at(2.0)), Hygiene.usesSinceFresh(rows.take(2).reversed()), "in time order")
        val unparsable = use(ref, 1, at(1.0)).let { it.copy(after = buildJsonObject { put("uses", JsonPrimitive(1)); put("last_used_at", JsonPrimitive("soon")) }) }
        assertEquals(listOf(unparsable.ts), Hygiene.usesSinceFresh(listOf(unparsable)), "the audit row's time otherwise")
    }

    private fun memory(
        id: Long,
        title: String,
        content: String,
        player: PlayerRef = bartok,
        importance: Int = 5,
        stability: Double = 38.0,
        reinforcedHours: Double = 0.0,
        occurredHours: Double = 0.0,
        createdHours: Double = occurredHours,
        archived: Boolean = false,
    ): Ref {
        val occurred = at(occurredHours)
        sql(
            "INSERT INTO memories (id, kind, created_at, occurred_from, occurred_to, title, content, players, entities, tags, " +
                "importance, valence, stability_h, reinforced_play_s, source, evidence_events, evidence_tools, archived) VALUES (" +
                "$id, 'episode', '${at(createdHours)}', '$occurred', '${occurred + Duration.ofMinutes(10)}', '$title', '$content', " +
                "['${player.uuid}'::UUID], []::VARCHAR[], []::VARCHAR[], $importance, 0, $stability, ${(reinforcedHours * 3_600).toLong()}, " +
                "'chronicler', []::BIGINT[], []::BIGINT[], $archived)",
        )
        val ref = Ref(RefKind.MEMORY, id)
        fake.rows[ref] = MemorySamples.memory.copy(ref = ref, title = title, content = content, importance = importance, archived = archived)
        indexer.texts[ref] = "$title $content"
        return ref
    }

    @Test
    fun `trivial memories at the floor for over 50 hours of play are archived`() = runBlocking {
        state.values[DuckPlayClock.WORLD_KEY] = (100 * 3_600).toString()
        state.values[DuckPlayClock.SAVED_AT_KEY] = start.toEpochMilli().toString()
        playClock.load(start.toEpochMilli())
        val faded = memory(1, "Une poule", "Bartok a croisé une poule.", importance = 1, stability = 4.0)
        val recent = memory(2, "Un cochon", "Bartok a croisé un cochon.", importance = 2, stability = 4.0, reinforcedHours = 28.0)
        val notTrivial = memory(3, "Une vache", "Bartok a croisé une vache.", importance = 3, stability = 4.0)
        memory(4, "Un mouton", "Bartok a croisé un mouton.", importance = 1, stability = 4.0, archived = true)

        val report = hygiene.beforeDream(time.now, null)

        assertEquals(listOf(faded), report.archived)
        assertTrue((fake.rows.getValue(faded) as MemoryEntry).archived)
        listOf(recent, notTrivial).forEach { assertTrue(!(fake.rows.getValue(it) as MemoryEntry).archived, "$it is kept") }
        assertEquals(listOf("archive $faded by engine"), fake.writes.filter { it.startsWith("archive") })
    }

    @Test
    fun `every 10 minutes, notes shown as often as the briefing shows them and dream hints shown or 7 days old are done`() = runBlocking<Unit> {
        playClock.load(start.toEpochMilli())
        suspend fun note(kind: NoteKind, shown: Int): Ref {
            val ref = fake.addNote(NoteDraft(kind, "Bartok a été vu deux fois au même endroit."), engine).ref
            repeat(shown) { fake.markNote(ref, NoteStatus.SHOWN, engine) }
            return ref
        }
        val twice = note(NoteKind.COHERENCE, 2)
        val once = note(NoteKind.COHERENCE, 1)
        val oldHint = note(NoteKind.DREAM_HINT, 0)
        val operator = note(NoteKind.OPERATOR, 5)
        val operatorOnce = note(NoteKind.OPERATOR, 1)
        val expired = note(NoteKind.EXPIRED, 2)
        val expiredNew = note(NoteKind.EXPIRED, 0)
        time.now = start + Duration.ofDays(2)
        val youngHint = note(NoteKind.DREAM_HINT, 0)
        val shownHint = note(NoteKind.DREAM_HINT, 1)
        time.now = start + Duration.ofDays(8)
        val before = fake.writes.size

        val report = hygiene.tidy(time.now)

        assertEquals(listOf(twice, oldHint, operator, expired, shownHint), report.notesDone)
        assertEquals(listOf(once, operatorOnce, expiredNew, youngHint), fake.openNotes().map { it.ref })
        assertEquals(report.notesDone.map { "markNote $it by engine" }, fake.writes.drop(before))
        assertEquals(emptyList(), hygiene.beforeDream(time.now, null).notesDone, "the dream finds nothing more to close")
    }

    @Test
    fun `memories of a player close in time and in words are merge candidates`() = runBlocking {
        playClock.load(start.toEpochMilli())
        val lava = "Bartok tombe dans la lave"
        val text = "Bartok est tombé dans la lave près de la base"
        val first = memory(1, lava, text, occurredHours = 0.0)
        val second = memory(2, lava, text, occurredHours = 1.0)
        memory(3, lava, text, player = mimosa, occurredHours = 0.5) // nobody in common
        memory(4, lava, text, occurredHours = 5.0) // hours later
        memory(5, "Bartok construit", "Bartok construit une tour de bois", occurredHours = 0.5) // other words
        memory(6, lava, text, occurredHours = 0.2, archived = true)

        assertEquals(listOf(MergeCandidate(first, second, 1.0)), hygiene.mergeCandidates(null))
        assertEquals(listOf(MergeCandidate(first, second, 1.0)), hygiene.mergeCandidates(at(0.5)), "the second is recent")
        assertEquals(emptyList(), hygiene.mergeCandidates(at(2.0)), "nothing written since")
        assertEquals(listOf(MergeCandidate(first, second, 1.0)), hygiene.beforeDream(time.now, null).mergeCandidates)
    }

    @Test
    fun `at most 20 merge candidates, the most similar first`() = runBlocking {
        playClock.load(start.toEpochMilli())
        (1L..25L).forEach { memory(it, "Bartok tombe", "Bartok est tombé dans la lave numéro ${it % 3}", occurredHours = it / 60.0) }
        val candidates = hygiene.mergeCandidates(null)
        assertEquals(Hygiene.MAX_MERGE_CANDIDATES, candidates.size)
        assertEquals(candidates.sortedByDescending { it.similarity }, candidates)
        assertTrue(candidates.all { it.similarity >= Hygiene.MERGE_SIMILARITY && it.a.id < it.b.id })
    }

    /** A store whose audit ([logs]) the test writes. */
    private class AuditedStore(private val inner: FakeMemoryStore) : MemoryStore by inner {
        val logs = HashMap<Ref, List<MemoryLogRow>>()

        override suspend fun log(ref: Ref): List<MemoryLogRow> = logs[ref].orEmpty()
    }

    /** Similarity as the share of the text's words found in the candidate's [texts]. */
    private class WordIndexer : Indexer {
        val texts = HashMap<Ref, String>()

        private fun words(text: String) = text.lowercase().split(' ').filter { it.isNotBlank() }.toSet()

        override fun index(connection: Connection, ref: Ref, text: String) = Unit

        override fun remove(connection: Connection, ref: Ref) = Unit

        override fun similarity(connection: Connection, text: String, candidates: Collection<Ref>): Map<Ref, Double> {
            val query = words(text)
            return candidates.associateWith { ref -> query.count { it in words(texts[ref].orEmpty()) }.toDouble() / query.size }
        }
    }

    private companion object {
        val bartok = MemorySamples.bartok
        val mimosa = MemorySamples.mimosa
    }
}
