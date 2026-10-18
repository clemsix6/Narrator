package io.github.clemsix6.narrator2.memory.dream

import io.github.clemsix6.narrator2.event.NarratorChannel
import io.github.clemsix6.narrator2.event.NarratorSpoke
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.CanonDraft
import io.github.clemsix6.narrator2.memory.CanonKind
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.memory.ChronicleDraft
import io.github.clemsix6.narrator2.memory.ChronicleLevel
import io.github.clemsix6.narrator2.memory.DocKey
import io.github.clemsix6.narrator2.memory.InteractionDraft
import io.github.clemsix6.narrator2.memory.InteractionKind
import io.github.clemsix6.narrator2.memory.MemoryDraft
import io.github.clemsix6.narrator2.memory.MemoryKind
import io.github.clemsix6.narrator2.memory.MergeCandidate
import io.github.clemsix6.narrator2.memory.NoteDraft
import io.github.clemsix6.narrator2.memory.NoteKind
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RelationKind
import io.github.clemsix6.narrator2.memory.SheetSection
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.memory.TraitAxis
import io.github.clemsix6.narrator2.memory.TraitDraft
import io.github.clemsix6.narrator2.memory.dream.DreamerFixture.Companion.at
import io.github.clemsix6.narrator2.memory.stats.PairStats
import io.github.clemsix6.narrator2.memory.stats.StatsRefresher
import io.github.clemsix6.narrator2.reflex.ChatIntent
import io.github.clemsix6.narrator2.reflex.Tone
import io.github.clemsix6.narrator2.trace.ChatSignalsRecord
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What each step of a dream reads (M3 §8.2): every block within its budget at its maximum, the players picked, the stats before and after, the sessions. */
class DreamInputsTest {
    private val f = DreamerFixture()

    @AfterEach
    fun close() = f.close()

    private val director = Author(Source.DIRECTOR, turnId = 1, conversationId = "director-c1")
    private val dreamer = Author(Source.DREAMER, runId = 1)

    /** French text of [length] characters exactly, factual and in the third person; [seed] makes it differ. */
    private fun text(length: Int, seed: Int = 0): String =
        "Bartok$seed a creusé une galerie sous la falaise $seed et a trouvé du fer avant la pluie, puis il est reparti. ".repeat(length / 50 + 2).take(length).trimEnd().padEnd(length, '.')

    /** The blocks of an opening, by tag: `<tag …>` … `</tag>`, or `<tag>(…)</tag>`. */
    private fun blocks(opening: String): Map<String, String> =
        Regex("(?s)<([a-z_]+)(?: [^>]*)?>.*?</\\1>").findAll(opening).associate { it.groupValues[1] to it.value }

    /** Every block of [opening] within its budget, and filled up but for the [small] ones (a few lines whatever the data). */
    private fun assertWithin(opening: String, step: Int, budgets: Map<String, Int>, small: Set<String> = emptySet()) {
        val blocks = blocks(opening)
        assertEquals(budgets.keys, blocks.keys, "the blocks of step $step")
        budgets.forEach { (tag, max) -> assertTrue(blocks.getValue(tag).length <= max, "step $step <$tag>: ${blocks.getValue(tag).length} > $max") }
        assertTrue(opening.length <= DreamInputs.STEP_MAX.getValue(step), "step $step: ${opening.length} > ${DreamInputs.STEP_MAX.getValue(step)}")
        val thin = (budgets.keys - small).filter { tag -> blocks.getValue(tag).length <= maxOf(200, budgets.getValue(tag) / 3) }
        assertEquals(emptyList(), thin, "step $step: every block is filled up: ${blocks.mapValues { it.value.length }}")
    }

    @Test
    fun `the opening of each step stays within its budget, every block filled to the brim`() = runBlocking<Unit> {
        val e = f.evening()
        val store = f.store
        val bartok = f.bartok.uuid
        val others = (1..30).map { PlayerRef(UUID(0, 1_000L + it), "JoueurNumero${it.toString().padStart(4, '0')}") }
        others.forEachIndexed { i, other ->
            f.join("21:00:00", other, 100L + i)
            f.leave("21:01:00", other, 100L + i, 60)
        }
        f.journal.sync()
        f.clock.now = at("21:52")
        f.journal.journal.write(5_000) { connection ->
            PairStats.write(connection, others.map { PairStats.PlayerPair(minOf(bartok, it.uuid), maxOf(bartok, it.uuid), 600.0, 60.0, 3, 2, 10, 4, at("21:52")) })
        }

        // Step 1: the most there can be about Bartok.
        val memories = (1..50).map { i ->
            store.addMemory(
                MemoryDraft(MemoryKind.EPISODE, text(80, i), text(400, i), listOf(bartok, others[i % 30].uuid), tags = listOf("death", "lava", "nether", "mining", "fear", "luck", "build", "first"), importance = 1 + i % 10),
                director,
            ).row.ref
        }
        repeat(80) { i ->
            store.addInteraction(
                InteractionDraft(bartok, InteractionKind.INSULT, -2, text(160, i), "x".repeat(120), at = at("19:00") - Duration.ofMinutes(11L * i)),
                director,
            )
        }
        repeat(40) { i ->
            val id = f.chat("20:4${i / 10}:${(10 + i).toString().take(2)}", f.bartok, "m".repeat(300))
            f.journal.journal.chatSignals(ChatSignalsRecord(id, f.clock.instant(), bartok, "jev:jev-1.13.0", 0.9, Tone.HOSTILE, 0.9, ChatIntent.PROVOCATION, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 3))
        }
        f.clock.now = at("21:55")
        TraitAxis.entries.forEach { axis -> store.setTrait(TraitDraft(bartok, axis.id, 1, null, 5, 0.85, memories.take(10), text(160, axis.ordinal)), dreamer) }
        (1..3).forEach { i -> store.setTrait(TraitDraft(bartok, "free:trait_$i", 0, text(40, i), 5, 0.85, memories.take(10), text(160, i)), dreamer) }
        SheetSection.entries.forEach { section -> store.writeDoc(DocKey.Sheet(bartok, section), text(section.maxChars, section.ordinal), memories.take(5), dreamer) }
        others.forEach { other -> store.setRelation(bartok, other.uuid, RelationKind.RIVAL, text(160), memories.take(3), dreamer) }
        StatsRefresher(f.journal.journal, f.clock).refresh(listOf(bartok) + others.map { it.uuid }, "dream")
        f.playClock.players[bartok] = 3_600_000 // 1 000 hours since the traits: they all faded
        store.setState(DreamInputs.reflectedKey(bartok), DreamInputs.Reflection(at("19:00").toEpochMilli(), mapOf("deaths" to 0.0, "playtime" to 60.0)).encode())

        // Step 2: the most lines, gags, and the longest review.
        f.clock.now = at("21:56")
        repeat(160) { f.journal.publish(NarratorSpoke(NarratorChannel.NARRATOR, "Bartok, " + text(1_990, it), null, 40, null)) }
        f.journal.sync()
        f.clock.now = at("21:57")
        others.forEach { other ->
            store.addCanon(CanonDraft(CanonKind.RUNNING_GAG, text(300, 1), "q".repeat(200), listOf(other.uuid)), director)
            store.addCanon(CanonDraft(CanonKind.NICKNAME, text(300, 2), "n".repeat(200), listOf(other.uuid)), director)
        }
        store.writeDoc(DocKey.Review, text(3_000), emptyList(), dreamer)

        // Step 3: twelve long chapters in the session over, a season and a saga at their budget, forty closed seasons.
        repeat(12) { i ->
            store.addChronicle(ChronicleDraft(ChronicleLevel.CHAPTER, at("20:00"), at("21:40"), text(80, i), text(4_000, i), conversationId = "director-x$i"), director)
        }
        val old = at("19:00") - Duration.ofDays(100)
        val sessions = (0 until 42).map { i ->
            val from = old + Duration.ofDays(i.toLong())
            val chapter = store.addChronicle(ChronicleDraft(ChronicleLevel.CHAPTER, from, from + Duration.ofHours(1), "Chapter $i", text(200, i), conversationId = "director-old$i"), director).ref
            store.addChronicle(ChronicleDraft(ChronicleLevel.SESSION, from, from + Duration.ofHours(1), text(80, i), text(1_500, i), listOf(chapter)), dreamer).ref
        }
        val closed = (0 until 40).map { i ->
            store.addChronicle(ChronicleDraft(ChronicleLevel.SEASON, old + Duration.ofDays(i.toLong()), old + Duration.ofDays(i + 1L), text(80, i), text(3_000, i), listOf(sessions[i])), dreamer).ref
        }
        store.addChronicle(ChronicleDraft(ChronicleLevel.SEASON, old + Duration.ofDays(40), old + Duration.ofDays(42), text(80), text(3_000), sessions.drop(40), open = true), dreamer)
        store.addChronicle(ChronicleDraft(ChronicleLevel.SAGA, old, old + Duration.ofDays(40), text(80), text(4_500), closed, open = true), dreamer)

        // Step 4: the longest Grimoire, sixty open entries, ten arcs, thirty notes.
        store.writeDoc(DocKey.Grimoire, GrimoireFormat.SECTIONS.joinToString("\n") { "## $it\n- ${text(1_400, it.length)}" }, emptyList(), dreamer)
        others.take(10).forEach { other -> store.addCanon(CanonDraft(CanonKind.ARC, text(300, 3), players = listOf(other.uuid), status = CanonStatus.PLANNED), dreamer) }
        repeat(30) { store.addNote(NoteDraft(NoteKind.COHERENCE, text(300, it), listOf(bartok)), director) }

        val now = at("22:10")
        f.clock.now = now
        f.journal.sync()
        val candidate = f.inputs.candidates(now, 8).first { it.player == f.bartok }
        val player = f.inputs.player(12, candidate, now).text
        assertWithin(
            player, 1,
            mapOf(
                "sheet" to DreamInputs.SHEET_MAX, "hidden_traits" to DreamInputs.HIDDEN_TRAITS_MAX, "new_memories" to DreamInputs.MEMORIES_MAX,
                "new_interactions" to DreamInputs.INTERACTIONS_MAX, "stats_change" to DreamInputs.STATS_MAX, "signals" to DreamInputs.SIGNALS_MAX,
                "pairs" to DreamInputs.PAIRS_MAX, "chat" to DreamInputs.CHAT_MAX,
            ),
            small = setOf("signals", "stats_change"),
        )
        assertTrue("<new_memories since=\"10-01 19:00\" count=\"52\" shown=\"the 40 most important\">" in player, player.take(2_000))
        assertTrue("- politeness: recent 0.20, mean 0.20 (40 messages)" in player, player)

        val review = f.inputs.review(12, now)!!
        assertWithin(
            review, 2, mapOf("tics" to DreamInputs.TICS_MAX, "lines" to DreamInputs.LINES_MAX, "gags" to DreamInputs.GAGS_MAX, "review" to DreamInputs.REVIEW_MAX),
            small = setOf("tics"),
        )

        val chronicle = f.inputs.chronicle(12, now)!!
        assertWithin(
            chronicle, 3,
            mapOf("sessions" to DreamInputs.SESSIONS_MAX, "season" to DreamInputs.SEASON_MAX, "closed_seasons" to DreamInputs.CLOSED_SEASONS_MAX, "saga" to DreamInputs.SAGA_MAX),
        )
        assertTrue("<sessions count=\"1\">\n<session from=\"10-01 19:55\" to=\"10-01 21:45\" chapters=\"${e.chapterEntry}, " in chronicle, chronicle.take(500))

        val grimoire = f.inputs.grimoire(12, now)
        assertWithin(
            grimoire, 4,
            mapOf(
                "grimoire" to DreamInputs.GRIMOIRE_MAX, "players" to DreamInputs.PLAYERS_MAX, "review" to DreamInputs.REVIEW_MAX, "session" to DreamInputs.SESSION_MAX,
                "canon" to DreamInputs.CANON_MAX, "arcs" to DreamInputs.ARCS_MAX, "notes" to DreamInputs.NOTES_MAX,
            ),
        )

        val pairs = memories.take(40).chunked(2).map { (a, b) -> MergeCandidate(a, b, 0.9) }
        val merges = f.inputs.merges(12, pairs)!!
        assertWithin(merges, 5, mapOf("candidates" to DreamInputs.CANDIDATES_MAX))
        assertNull(f.inputs.merges(12, emptyList()))
        println("Openings at their maximum, in characters: step 1 ${player.length}, step 2 ${review.length}, step 3 ${chronicle.length}, step 4 ${grimoire.length}, step 5 ${merges.length}")
    }

    @Test
    fun `the players with something new since their last reflection, the most active first, within the limit`() = runBlocking<Unit> {
        f.evening()
        val now = at("22:10")
        assertEquals(listOf("Bartok" to 6_600L, "Mimosa" to 6_303L), f.inputs.candidates(now, 8).map { it.player.name to it.playedSeconds })
        assertEquals(listOf("Bartok"), f.inputs.candidates(now, 1).map { it.player.name })
        val bartok = f.inputs.candidates(now, 8).first()
        assertEquals(listOf(2, 2), listOf(bartok.memories, bartok.interactions))

        f.store.setState(DreamInputs.reflectedKey(f.bartok.uuid), DreamInputs.Reflection(now.toEpochMilli(), emptyMap()).encode())
        assertEquals(listOf("Mimosa"), f.inputs.candidates(now, 8).map { it.player.name }, "nothing new about Bartok since")

        f.clock.now = at("22:20")
        f.store.addInteraction(InteractionDraft(f.bartok.uuid, InteractionKind.APOLOGY, 1, "s'est excusé auprès du Narrateur"), Author(Source.CHRONICLER))
        val again = f.inputs.candidates(at("22:20"), 8)
        assertEquals(listOf("Mimosa", "Bartok"), again.map { it.player.name }, "Mimosa played, Bartok has an interaction")
        assertEquals(listOf(0L, 1L), listOf(again[1].playedSeconds, again[1].interactions.toLong()))
    }

    @Test
    fun `the stats before and after the last reflection, and only what is new since`() = runBlocking<Unit> {
        val e = f.evening()
        val now = at("22:10")
        f.store.setState(
            DreamInputs.reflectedKey(f.bartok.uuid),
            DreamInputs.Reflection(at("20:30").toEpochMilli(), mapOf("playtime" to 3_600.0, "deaths" to 0.0, "kills" to 0.0, "luck" to 50.0)).encode(),
        )
        val candidate = f.inputs.candidates(now, 8).first { it.player == f.bartok }
        assertEquals(6_600L - 2_100, candidate.playedSeconds)
        val input = f.inputs.player(12, candidate, now)
        val opening = input.text
        assertTrue("<job kind=\"dream\" run=\"12\" step=\"1\" of=\"5\" task=\"players\" player=\"Bartok\" last_reflection=\"10-01 20:30\"/>" in opening, opening)
        val stats = blocks(opening).getValue("stats_change")
        assertTrue(stats.startsWith("<stats_change since=\"10-01 20:30\">\n- playtime 1h00m → 1h50m (+50m)\n- deaths 0 → 1 (+1)\n"), stats)
        assertTrue("\n- unchanged: kills, luck" in stats, stats)
        assertTrue("<new_memories since=\"10-01 20:30\" count=\"2\">" in opening, opening)
        assertTrue("<new_interactions since=\"10-01 20:30\" count=\"2\">" in opening, opening)
        assertEquals(now.toEpochMilli(), input.reflection.at)
        assertEquals(1.0, input.reflection.stats["deaths"])
        assertTrue(e.lava.toString() in blocks(opening).getValue("new_memories"))
    }

    @Test
    fun `step 3 groups the chapters by server session over, and keeps the session still running for later`() = runBlocking<Unit> {
        val e = f.evening()
        assertNull(f.inputs.chronicle(12, at("22:00")), "the server has been empty for 15 minutes only")
        f.join("22:55:00", f.mimosa, 3)
        f.leave("23:40:00", f.mimosa, 3, 2_700)
        f.journal.sync()
        val late = f.store.addChronicle(
            ChronicleDraft(ChronicleLevel.CHAPTER, at("23:00"), at("23:30"), "Chapter 14", "Mimosa a joué seule.", conversationId = "director-c14"),
            Author(Source.DIRECTOR),
        ).ref
        val opening = f.inputs.chronicle(12, at("23:50"))!!
        assertTrue(opening.startsWith("<job kind=\"dream\" run=\"12\" step=\"3\" of=\"5\" task=\"chronicle\"/>\n<sessions count=\"1\">\n<session from=\"10-01 19:55\" to=\"10-01 21:45\" chapters=\"${e.chapterEntry}\">"), opening)
        assertTrue(late.toString() !in opening, "its session is not over")
        assertTrue("<season>(none open: the sessions above start a new season)</season>" in opening, opening)
        assertTrue("<saga>(none yet: written when the first season closes)</saga>" in opening, opening)
        val midnight = Instant.parse("2026-10-02T00:05:00Z")
        val both = f.inputs.chronicle(12, midnight)!!
        assertTrue("<sessions count=\"2\">" in both && "<session from=\"10-01 22:55\" to=\"10-01 23:40\" chapters=\"$late\">" in both, both)
        f.store.addChronicle(ChronicleDraft(ChronicleLevel.SESSION, at("19:55"), at("21:45"), "La soirée", "Une soirée.", listOf(e.chapterEntry, late)), Author(Source.DREAMER))
        assertNull(f.inputs.chronicle(12, midnight), "every chapter is in a session")
    }
}
