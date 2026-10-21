package io.github.clemsix6.narrator2.memory.dream

import io.github.clemsix6.narrator2.event.NarratorChannel
import io.github.clemsix6.narrator2.event.NarratorSpoke
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.forge.ArsenalEntry
import io.github.clemsix6.narrator2.forge.ModuleStatus
import io.github.clemsix6.narrator2.forge.ParamSpec
import io.github.clemsix6.narrator2.forge.api.ParamType
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.CanonDraft
import io.github.clemsix6.narrator2.memory.CanonKind
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.memory.ChronicleDraft
import io.github.clemsix6.narrator2.memory.ChronicleLevel
import io.github.clemsix6.narrator2.memory.DocKey
import io.github.clemsix6.narrator2.memory.IdeaDraft
import io.github.clemsix6.narrator2.memory.IdeaKind
import io.github.clemsix6.narrator2.memory.Memory
import io.github.clemsix6.narrator2.memory.PlayerSheets
import io.github.clemsix6.narrator2.memory.RecallChannel
import io.github.clemsix6.narrator2.memory.RecallContext
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.SheetSamples
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.memory.dream.DreamerFixture.Companion.at
import io.github.clemsix6.narrator2.memory.longArray
import io.github.clemsix6.narrator2.memory.stringArray
import io.github.clemsix6.narrator2.memory.update
import io.github.clemsix6.narrator2.memory.uuidArray
import io.github.clemsix6.narrator2.style.ComicRegister
import io.github.clemsix6.narrator2.testing.FakeForge
import io.github.clemsix6.narrator2.testing.FakeMemory
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.trace.LineLabelsRecord
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the programme reads (M6 §6.3, `ProgrammeInputs`): every block within its budget at its maximum, the players active in
 * the last seven days, the ideas with what followed each time one was dealt, the history of the latest three sessions,
 * the canon with the themes at rest, the arsenal and the season.
 */
class ProgrammeInputsTest {
    private val f = DreamerFixture()

    @AfterEach
    fun close() = f.close()

    private val director = Author(Source.DIRECTOR, turnId = 1, conversationId = "director-c1")
    private val dreamer = Author(Source.DREAMER, runId = 1)
    private val storyteller = Author(Source.STORYTELLER, runId = 1, conversationId = "storyteller-1")

    /** The programme's inputs on the fixture's Journal and store, with [sheets] in place of the real ones when given, and [forge]. */
    private fun inputs(sheets: PlayerSheets? = null, forge: FakeForge = FakeForge(f.clock)): ProgrammeInputs {
        val memory = sheets?.let { replaced -> object : Memory by f.memory { override val sheets: PlayerSheets get() = replaced } } ?: f.memory
        val services = TestServices(
            clock = f.clock, settings = f.services.settings.get(), journal = f.journal.journal, trace = f.journal.journal,
            players = f.players, memory = memory, forge = forge,
        )
        return ProgrammeInputs(services, DreamInputs(services))
    }

    /** French text of [length] characters exactly, factual and in the third person; [seed] makes it differ. */
    private fun text(length: Int, seed: Int = 0): String =
        "Bartok$seed a creusé une galerie sous la falaise $seed et a trouvé du fer avant la pluie, puis il est reparti. ".repeat(length / 50 + 2).take(length).trimEnd().padEnd(length, '.')

    /** The blocks of an opening, by tag: `<tag …>` … `</tag>`, or `<tag>(…)</tag>`. */
    private fun blocks(opening: String): Map<String, String> =
        Regex("(?s)<([a-z_]+)(?: [^>]*)?>.*?</\\1>").findAll(opening).associate { it.groupValues[1] to it.value }

    /** A successful call of the Director's [tool] with [args] at [at], aimed at [targets]: what `Interventions` reads. */
    private suspend fun call(id: Long, at: Instant, tool: String, args: String, vararg targets: String) = f.journal.journal.write(5_000) { connection ->
        connection.update(
            "INSERT INTO tool_calls (id, ts, conversation_id, turn_id, tool_use_id, role, tool, args, status, duration_ms, targets, effect) " +
                "VALUES (?, ?, 'director-c14', 50, 'tu', 'director', ?, ?::JSON, 'ok', 5, ?, 'world')",
            id, at, tool, args, stringArray(targets.toList()),
        )
    }

    /** An idea written as the store writes one (its rules are `IdeasStoreTest`'s), [dealt] times, its title and pitch [title] and [pitch] long. */
    private suspend fun idea(
        id: Long,
        kind: IdeaKind,
        created: Instant,
        players: List<PlayerRef>,
        status: String = "open",
        closed: Instant? = null,
        dealt: Int = 0,
        title: Int = 60,
        pitch: Int = 500,
    ) =
        f.journal.journal.write(5_000) { connection ->
            connection.update(
                "INSERT INTO ideas (id, created_at, kind, title, pitch, fit, players, register, novelty, fun, fairness, refs, status, dealt, last_dealt_at, " +
                    "expires_play_s, closed_at, close_reason, source, run_id, conversation_id) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, 'game_show', 3, 4, 5, ?, ?, ?, ?, 999999, ?, ?, 'storyteller', 1, 'storyteller-1')",
                id, created, kind.id, text(title, id.toInt()), text(pitch, id.toInt()), text(200, id.toInt()), uuidArray(players.map { it.uuid }), longArray(emptyList()),
                status, dealt, if (dealt > 0) created else null, closed, closed?.let { "replaced by the season P-${id + 1}" },
            )
            repeat(dealt) { i ->
                connection.update("INSERT INTO recalls VALUES (?, ?, 'inspire', 'director-c14', 50, NULL)", created.plusSeconds(60L * (i + 1)), id)
            }
        }

    @Test
    fun `the opening stays within its budget, every block filled to the brim`() = runBlocking<Unit> {
        val store = f.store
        val players = (1..10).map { PlayerRef(UUID(0, 2_000L + it), "Joueur${it.toString().padStart(2, '0')}") }
        players.forEachIndexed { i, player ->
            f.join("20:00:${(10 + i)}", player, 100L + i)
            f.leave("21:00:${(10 + i)}", player, 100L + i, 3_600)
        }
        f.journal.sync()
        val sheets = FakeMemory.FakeSheets()
        players.forEach { sheets.sheets[it.uuid] = SheetSamples.maximal.copy(player = SheetSamples.maximal.player.copy(uuid = it.uuid)) }

        // The Grimoire and the review at their longest.
        f.clock.now = at("21:10")
        store.writeDoc(DocKey.Grimoire, GrimoireFormat.SECTIONS.joinToString("\n") { "## $it\n- ${text(1_640, it.length)}" }, emptyList(), dreamer)
        store.writeDoc(DocKey.Review, text(3_000), emptyList(), dreamer)

        // The canon: ten planned arcs, ten themes at rest, twenty gags, ten laws.
        players.forEachIndexed { i, player ->
            store.addCanon(CanonDraft(CanonKind.ARC, text(300, i), players = listOf(player.uuid), status = CanonStatus.PLANNED, theme = "arc_prevu_$i"), storyteller)
            val closed = store.addCanon(CanonDraft(CanonKind.ARC, text(300, 20 + i), status = CanonStatus.PLANNED, theme = "theme_au_repos_$i".padEnd(40, 'x')), storyteller).row.ref
            store.resolveCanon(closed, CanonStatus.ABANDONED, text(100, i), emptyList(), director)
            store.addCanon(CanonDraft(CanonKind.RUNNING_GAG, text(300, 40 + i), players = listOf(player.uuid)), director)
            store.addCanon(CanonDraft(CanonKind.RUNNING_GAG, text(300, 60 + i), players = listOf(player.uuid, players[(i + 1) % 10].uuid)), director)
            store.addCanon(CanonDraft(CanonKind.LAW, text(300, 80 + i), players = listOf(player.uuid)), director)
        }

        // The history: thirty levers pulled, sixty-four lines in every register.
        repeat(30) { i -> call(9_000L + i, at("20:10").plusSeconds(60L * i), "punish", """{"punishment": "chatiment_numero_$i"}""", players[i % 10].name) }
        f.clock.now = at("20:30")
        val said = (0 until 64).map { f.journal.publish(NarratorSpoke(NarratorChannel.NARRATOR, text(300, it), listOf(players[it % 10]), 40, null)).id }
        f.journal.sync()
        said.forEachIndexed { i, id ->
            f.journal.journal.lineLabels(LineLabelsRecord(id, at("20:31"), "reflex:claude-haiku-5-5", ComicRegister.VOICE[i % 16].id, 0.0, 0.0, 0.1, 0.0, 0.1, emptyList(), null, 900))
        }

        // Twelve open ideas, ten closed since the last dream, each dealt five times.
        (0 until 22).forEach { i ->
            idea(5_000L + i, IdeaKind.CHALLENGE, at("21:20"), players.take(8), status = if (i < 12) "open" else "dropped", closed = if (i < 12) null else at("22:00"), dealt = 5)
        }

        // The arsenal: fifteen modules with their parameters.
        val forge = FakeForge(f.clock)
        repeat(15) { i ->
            forge.arsenal.add(
                ArsenalEntry(
                    "module_numero_$i", 1, ModuleStatus.READY, false, text(120, i),
                    listOf(
                        ParamSpec("players", ParamType.PLAYERS, "Who plays.", true, null, 2.0, 8.0, emptyList()),
                        ParamSpec("minutes", ParamType.INTEGER, "How long.", false, "5", 2.0, 15.0, emptyList()),
                    ),
                    emptyList(), at("21:00"), "forger T-$i", null, "sha256:0", 1, 1, 1, null,
                ),
            )
        }

        // The season: a chapter, its session, the season in progress at its longest.
        val chapter = store.addChronicle(ChronicleDraft(ChronicleLevel.CHAPTER, at("20:00"), at("21:00"), "Chapter 1", text(400), conversationId = "director-c1"), director).ref
        val session = store.addChronicle(ChronicleDraft(ChronicleLevel.SESSION, at("20:00"), at("21:00"), text(80), text(1_500), listOf(chapter)), dreamer).ref
        store.addChronicle(ChronicleDraft(ChronicleLevel.SEASON, at("20:00"), at("21:00"), text(80, 1), text(3_000, 1), listOf(session), open = true), dreamer)
        idea(6_000, IdeaKind.SEASON, at("19:00"), emptyList())

        val now = at("22:10")
        f.clock.now = now
        f.journal.sync()
        val opening = inputs(sheets, forge).opening(12, now, since = at("21:30"))!!
        val budgets = mapOf(
            "grimoire" to ProgrammeInputs.GRIMOIRE_MAX, "review" to ProgrammeInputs.REVIEW_MAX, "players" to ProgrammeInputs.PLAYERS_MAX,
            "canon" to ProgrammeInputs.CANON_MAX, "history" to ProgrammeInputs.HISTORY_MAX, "ideas" to ProgrammeInputs.IDEAS_MAX,
            "arsenal" to ProgrammeInputs.ARSENAL_MAX, "season" to ProgrammeInputs.SEASON_MAX, "draws" to ProgrammeInputs.DRAWS_MAX,
        )
        val blocks = blocks(opening)
        assertEquals(budgets.keys, blocks.keys)
        budgets.forEach { (tag, max) -> assertTrue(blocks.getValue(tag).length <= max, "<$tag>: ${blocks.getValue(tag).length} > $max") }
        assertTrue(opening.length <= ProgrammeInputs.STEP_MAX, "${opening.length} > ${ProgrammeInputs.STEP_MAX}")
        val thin = budgets.keys.filter { tag -> blocks.getValue(tag).length <= maxOf(200, budgets.getValue(tag) / 3) }
        assertEquals(emptyList(), thin, "every block is filled up: ${blocks.mapValues { it.value.length }}")
        assertEquals(DreamInputs.STEP_MAX.getValue(5), ProgrammeInputs.STEP_MAX)
        println("The programme's opening at its maximum: ${opening.length} characters (${blocks.mapValues { it.value.length }})")

        assertTrue(opening.startsWith("<job kind=\"dream\" run=\"12\" step=\"5\" of=\"6\" task=\"programme\"/>\n<grimoire version=\"1\""), opening.take(200))
        assertTrue("<players count=\"8\" days=\"7\" order=\"the most active first\">" in opening, "eight players at most")
        assertEquals(8, blocks.getValue("players").lines().count { it.startsWith("- ") })
        val canon = blocks.getValue("canon")
        val resting = canon.lines().single { it.startsWith("Themes at rest") }
        assertTrue(resting.startsWith("Themes at rest (20h of world play once their arc closed): theme_au_repos_"), resting)
        assertEquals(ProgrammeInputs.RESTING_COUNT, resting.split(" · ").size, resting)
        // M6 review 2, finding 1: every open idea is shown, and four closed ones at least, each part in its room.
        val ideas = blocks.getValue("ideas").lines()
        assertEquals("<ideas open=\"13\" closed=\"10\" closed_since=\"10-01 21:30\">", ideas.first(), "twelve challenges and the season, ten closed")
        val closedAt = ideas.indexOfFirst { it.startsWith("Closed since ") }
        assertEquals(13, ideas.subList(1, closedAt).count { it.startsWith("- P-") }, "every open idea: $ideas")
        assertTrue(ideas.drop(closedAt).count { it.startsWith("- P-") } >= 4, "four closed ideas at least: $ideas")
        // What a player or a model wrote is escaped: no tag of theirs reaches the model.
        listOf("<x>", "<a filé>", "<o>").forEach { assertTrue(it !in opening, "$it escaped") }
    }

    @Test
    fun `the players active in the last seven days, the most active first, and without anybody no programme`() = runBlocking<Unit> {
        assertNull(inputs().opening(12, at("22:10"), null), "nobody ever played")
        f.evening()
        val week = inputs().opening(12, at("22:10"), null)!!
        val players = blocks(week).getValue("players").lines()
        assertTrue(players[1].startsWith("- Bartok « le Plongeur » · 1h50m played in 7 days (1h50m in all) · last seen 10-01 21:45 · favor "), players[1])
        assertTrue(players.any { it.startsWith("- Mimosa · 1h45m played in 7 days") }, "$players")
        assertTrue(players.any { it.startsWith("  traits (sure): ") } && players.any { it.startsWith("  progress: ") }, "$players")
        assertNull(inputs().opening(12, at("21:00").plus(Duration.ofDays(8)), null), "eight days later, nobody played in the week")
    }

    @Test
    fun `the ideas say each time one was dealt and what followed within fifteen minutes`() = runBlocking<Unit> {
        f.evening()
        val store = f.store
        f.clock.now = at("22:10")
        val race = store.addIdea(
            IdeaDraft(
                IdeaKind.CHALLENGE, "La course au pic", "Course au sommet du pic enneigé au nord du spawn ; le premier arrivé gagne un joker.",
                "Bartok et Mimosa en ligne", listOf(f.bartok.uuid, f.mimosa.uuid), ComicRegister.SPORTS_COMMENTARY, 4, 4, 4,
            ),
            storyteller,
        ).row.ref
        val diploma = store.addIdea(
            IdeaDraft(IdeaKind.FAVOR, "Le diplôme du plongeur", "Un livre-diplôme de plongeur de l'année remis à Bartok après sa quatrième chute.", "Bartok en ligne", listOf(f.bartok.uuid), null, 3, 4, 5),
            storyteller,
        ).row.ref
        val old = store.addIdea(IdeaDraft(IdeaKind.LAW, "La loi des bottes", "Pendant deux heures, toute botte perdue revient à Mimosa.", "Mimosa en ligne", listOf(f.mimosa.uuid), null, 2, 2, 3), storyteller).row.ref
        f.clock.now = at("22:12")
        store.dropIdea(old, "Mimosa ne perd plus ses bottes.", storyteller)
        val rain = store.addIdea(IdeaDraft(IdeaKind.PUNISHMENT, "La pluie de grenouilles", "Une averse de slimes suit Bartok pendant trois minutes après une insulte.", "Bartok insolent", listOf(f.bartok.uuid), null, 3, 3, 3), storyteller).row.ref
        f.clock.now = at("22:20")
        store.dropIdea(rain, "Bartok ne provoque plus le Narrateur.", storyteller)

        // P-race dealt at 23:00, a challenge four minutes later; dealt again at 23:30, only a punishment within the quarter hour.
        suspend fun dealt(ref: Ref, at: Instant, channel: RecallChannel) {
            f.clock.now = at
            store.dealt(listOf(ref), channel, RecallContext("director-c14", 50, null))
        }
        dealt(race, at("23:00"), RecallChannel.INSPIRE)
        call(9_001, at("23:04"), "start_challenge", """{"goal": "reach"}""", "Bartok", "Mimosa")
        dealt(race, at("23:30"), RecallChannel.HEARTBEAT)
        call(9_002, at("23:35"), "punish", """{"punishment": "tiny"}""", "Bartok")
        call(9_003, at("23:50"), "start_challenge", """{"goal": "obtain"}""", "Bartok")
        dealt(diploma, at("23:38"), RecallChannel.INSPIRE)
        call(9_004, at("23:43"), "favor", """{"favor": "joker"}""", "Bartok")

        val now = at("23:59")
        f.clock.now = now
        val ideas = blocks(inputs().opening(12, now, since = at("22:15"))!!).getValue("ideas").lines()
        assertEquals(listOf("<ideas open=\"2\" closed=\"1\" closed_since=\"10-01 22:15\">", "Open, the latest first:"), ideas.take(2))
        val open = ideas.subList(2, 4)
        assertEquals(listOf("- $diploma favor ", "- $race challenge "), open.map { it.substringBefore("·") }, "$ideas")
        assertTrue(" · dealt 2: 10-01 23:00 inspire → challenge reach 4m later; 10-01 23:30 heartbeat → nothing: " in open[1], open[1])
        assertTrue(" of play left · Bartok, Mimosa · « La course au pic » · n4 f4 e4 sports_commentary · " in open[1], open[1])
        assertTrue(open[1].endsWith(": Course au sommet du pic enneigé au nord du spawn ; le premier arrivé gagne un joker."), "the pitch, short: ${open[1]}")
        assertTrue(" · dealt 1: 10-01 23:38 inspire → favor joker 5m later: " in open[0], open[0])
        // The closed ones without their pitch: what became of them, and what followed.
        assertEquals(
            listOf(
                "Closed since 10-01 22:15, those dealt first:",
                "- $rain punishment « La pluie de grenouilles » · dropped 10-01 22:20 (Bartok ne provoque plus le Narrateur.) · never dealt",
                "</ideas>",
            ),
            ideas.drop(4),
        )
        assertTrue(ideas.none { "$old " in it }, "closed before the last dream: $ideas")
    }

    /**
     * M6 review 2, finding 1 (D5): the open ideas and those closed since the last dream each have their part of the block.
     * Twelve open ideas dealt twice each and four closed since the last dream are all shown (it showed eight of the
     * sixteen); an idea written ten days ago and closed since is among them, though sixty ideas were written after it.
     */
    @Test
    fun `every open idea is shown, and the ideas closed since the last dream however old they are`() = runBlocking<Unit> {
        f.evening()
        val two = listOf(f.bartok, f.mimosa)
        (0 until 12).forEach { i -> idea(5_000L + i, IdeaKind.CHALLENGE, at("21:20"), two, dealt = 2, title = 40, pitch = 350) }
        (0 until 3).forEach { i -> idea(5_100L + i, IdeaKind.FAVOR, at("21:00"), two, status = "dropped", closed = at("22:00"), dealt = 1, title = 40, pitch = 350) }
        val old = at("21:00").minus(Duration.ofDays(10))
        idea(4_000, IdeaKind.TWIST, old, two, status = "expired", closed = at("22:05"), title = 40, pitch = 350)
        (0 until 60).forEach { i -> idea(4_100L + i, IdeaKind.LAW, old.plus(Duration.ofHours(1L + i)), two, status = "dropped", closed = at("20:00"), title = 40, pitch = 350) }

        val now = at("22:10")
        f.clock.now = now
        val block = blocks(inputs().opening(12, now, since = at("21:30"))!!).getValue("ideas")
        val lines = block.lines()
        println("<ideas> of twelve open ideas and four closed: ${block.length} characters; open lines ${lines.filter { it.startsWith("- P-50") }.map { it.length }}")
        assertTrue(block.length <= ProgrammeInputs.IDEAS_MAX, "${block.length}")
        assertEquals("<ideas open=\"12\" closed=\"4\" closed_since=\"10-01 21:30\">", lines.first())
        val closedAt = lines.indexOf("Closed since 10-01 21:30, those dealt first:")
        assertEquals((5_000L until 5_012L).map { "P-$it" }.toSet(), lines.subList(1, closedAt).filter { it.startsWith("- P-") }.map { it.split(" ")[1] }.toSet())
        assertEquals(listOf("P-5102", "P-5101", "P-5100", "P-4000"), lines.drop(closedAt).filter { it.startsWith("- P-") }.map { it.split(" ")[1] }, "those dealt first, then the latest")
        assertTrue(lines.none { "(+" in it && "more closed" in it }, block)
        val expired = lines.single { it.startsWith("- P-4000 ") }
        assertTrue(expired.startsWith("- P-4000 twist « Bartok4000 a creusé une galerie sous la. » · expired 10-01 22:05 (") && expired.endsWith(" · never dealt"), expired)
        val opened = lines.first { it.startsWith("- P-5011 ") }
        assertTrue(" · dealt 2: 10-01 21:21 inspire → nothing; 10-01 21:22 inspire → nothing: Bartok5011 a creusé" in opened, "the pitch after what was dealt: $opened")
    }

    /**
     * M6 review 2, finding 2: for a twist, a season, an arc step or a forge, any lever pulled within the quarter hour follows
     * it; a line said does not (the Narrator speaks within the minute of almost anything).
     */
    @Test
    fun `a line said follows no idea, not even a twist`() = runBlocking<Unit> {
        f.evening()
        f.clock.now = at("22:10")
        val twist = f.store.addIdea(
            IdeaDraft(IdeaKind.TWIST, "Le coffre inversé", "Le coffre de Bartok rend ce que les autres y ont déposé, pendant une heure.", "Bartok en ligne", listOf(f.bartok.uuid), null, 3, 3, 3),
            storyteller,
        ).row.ref
        f.clock.now = at("23:00")
        f.store.dealt(listOf(twist), RecallChannel.HEARTBEAT, RecallContext("director-c14", 50, null))
        val said = f.narrator("23:01:00", "La cour du ciel déclare Bartok coupable de plongeon artistique.", listOf(f.bartok))
        f.journal.sync()
        f.journal.journal.lineLabels(LineLabelsRecord(said, at("23:01:30"), "reflex:claude-haiku-5-5", ComicRegister.COURTROOM.id, 0.0, 0.0, 0.1, 0.0, 0.0, emptyList(), null, 900))
        f.clock.now = at("23:30")
        f.store.dealt(listOf(twist), RecallChannel.INSPIRE, RecallContext("director-c14", 51, null))
        call(9_001, at("23:35"), "punish", """{"punishment": "tiny"}""", "Bartok")

        val now = at("23:59")
        f.clock.now = now
        f.journal.sync()
        val opening = inputs().opening(12, now, since = at("22:15"))!!
        assertTrue("Lines: 3 said, 1 reviewed;" in opening, "the line said is reviewed: ${blocks(opening).getValue("history")}")
        val line = blocks(opening).getValue("ideas").lines().single { it.startsWith("- $twist ") }
        assertTrue(" · dealt 2: 10-01 23:00 heartbeat → nothing; 10-01 23:30 inspire → punish tiny 5m later: " in line, line)
    }

    @Test
    fun `the history covers the latest three sessions, by lever and by register, and the canon names the themes at rest`() = runBlocking<Unit> {
        val e = f.evening()
        /** A server session of [player] on another day, closed. */
        suspend fun session(id: Int, player: PlayerRef, from: String, to: String) = f.journal.journal.write(5_000) { connection ->
            connection.update(
                "INSERT INTO sessions (session_id, player_uuid, player, joined_at, left_at, duration_s, end_reason) VALUES (?, ?, ?, ?, ?, ?, 'leave')",
                id, player.uuid, player.name, Instant.parse(from), Instant.parse(to), Duration.between(Instant.parse(from), Instant.parse(to)).seconds,
            )
        }
        session(10, f.bartok, "2026-09-20T20:00:00Z", "2026-09-20T21:00:00Z")
        session(11, f.mimosa, "2026-09-28T20:00:00Z", "2026-09-28T21:00:00Z")
        session(12, f.bartok, "2026-09-29T20:00:00Z", "2026-09-29T21:00:00Z")
        call(9_001, Instant.parse("2026-09-20T20:30:00Z"), "punish", """{"punishment": "tiny"}""", "Bartok") // four sessions ago
        call(9_002, Instant.parse("2026-09-28T20:30:00Z"), "start_challenge", """{"goal": "obtain"}""", "Mimosa")
        call(9_003, at("20:05"), "punish", """{"punishment": "tiny"}""", "Bartok")
        call(9_004, at("20:40"), "punish", """{"punishment": "tiny"}""", "Mimosa")
        f.journal.journal.lineLabels(LineLabelsRecord(e.sanction, at("20:02"), "reflex:claude-haiku-5-5", "divine_bureaucracy", 0.0, 0.0, 0.1, 0.0, 0.0, emptyList(), null, 900))
        // M6 review 2, finding 8: a title and a short aside are no lines the review reads; they are not counted.
        f.narrator("20:50:00", "[title] DÉFI : LA COURSE AU PIC / Premier au sommet")
        f.narrator("20:51:00", "Bien joué, Bartok.")

        // The canon: an arc planned, a theme at rest, the evening's gag, a law.
        f.clock.now = at("22:10")
        val store = f.store
        val trial = store.addCanon(CanonDraft(CanonKind.ARC, "Le Narrateur prépare le procès des parieurs.", status = CanonStatus.PLANNED, theme = "proces_des_parieurs"), storyteller).row.ref
        val warden = store.addCanon(CanonDraft(CanonKind.ARC, "Le Narrateur prépare la vengeance du Warden.", status = CanonStatus.PLANNED, theme = "vengeance_du_warden"), storyteller).row.ref
        store.resolveCanon(warden, CanonStatus.ABANDONED, "Bartok a quitté les cités antiques.", emptyList(), director)
        val law = store.addCanon(CanonDraft(CanonKind.LAW, "Quiconque casse la construction d'un autre est rapetissé.", players = listOf(f.mimosa.uuid)), director).row.ref
        f.journal.sync()

        val opening = blocks(inputs().opening(12, at("22:10"), null)!!)
        val history = opening.getValue("history").lines()
        assertEquals("<history from=\"09-28 20:00\" sessions=\"3\">", history.first())
        assertEquals(
            listOf("Levers pulled, the latest first:", "- punish tiny ×2 (Bartok 1, Mimosa 1), last 10-01 20:40", "- challenge obtain ×1 (Mimosa), last 09-28 20:30"),
            history.subList(1, 4),
        )
        assertEquals("Lines: 2 said, 1 reviewed; by register, and how players took them:", history[4])
        assertTrue(history[5].startsWith("- divine_bureaucracy: 1 line · laughed 1 (Bartok 1)"), history[5])

        val canon = opening.getValue("canon").lines()
        assertEquals("<canon arcs=\"1\" gags=\"1\" laws_and_plans=\"1\">", canon[0])
        assertEquals("- $trial arc (planned, theme proces_des_parieurs, since 10-01): Le Narrateur prépare le procès des parieurs.", canon[1])
        assertEquals("Themes at rest (20h of world play once their arc closed): vengeance_du_warden until about 10-02 18:10 ($warden closed 10-01 22:10)", canon[2])
        assertTrue(canon[3].startsWith("- ${e.gag} running_gag → Bartok (fresh, 0 uses): Le Narrateur commente chaque chute de Bartok"), canon[3])
        assertEquals("- $law law → Mimosa (since 10-01): Quiconque casse la construction d'un autre est rapetissé.", canon[4])
    }

    @Test
    fun `the arsenal or why there is none, the season and whether the Chronicle's began after its idea`() = runBlocking<Unit> {
        val e = f.evening()
        val forge = FakeForge(f.clock)
        forge.arsenal.add(
            ArsenalEntry(
                "tag_game", 0, ModuleStatus.READY, true, "Tag: the wolf passes on by touch.",
                listOf(ParamSpec("players", ParamType.PLAYERS, "Who plays.", true, null, 2.0, 8.0, emptyList())), emptyList(), at("19:00"), "built in", null, "sha256:0", 1, 1, 1, null,
            ),
        )
        f.clock.now = at("19:00")
        val season = f.store.addIdea(
            IdeaDraft(IdeaKind.SEASON, "Les débuts", "Une saison de premiers pas : le Nether pour Bartok, les affaires perdues pour Mimosa.", "toute la saison", novelty = 3, funScore = 3, fairness = 4, playHours = 168),
            storyteller,
        ).row.ref
        f.clock.now = at("22:10")
        val session = f.store.addChronicle(ChronicleDraft(ChronicleLevel.SESSION, at("19:55"), at("21:45"), "La soirée", "Bartok a insulté le Narrateur.", listOf(e.chapterEntry)), dreamer).ref
        val chronicle = f.store.addChronicle(ChronicleDraft(ChronicleLevel.SEASON, at("19:55"), at("21:45"), "Les débuts", "La première saison commence.", listOf(session), open = true), dreamer).ref

        val on = blocks(inputs(forge = forge).opening(12, at("22:10"), null)!!)
        assertEquals("<arsenal>\nArsenal (1): tag_game — Tag: the wolf passes on by touch. (players 2-8)\n</arsenal>", on.getValue("arsenal"))
        val lines = on.getValue("season").lines()
        assertEquals(5, lines.size, "$lines")
        assertTrue(lines[1].startsWith("Chronicle: $chronicle « Les débuts » 10-01 ") && lines[1].endsWith(", 1 session (it closes at 12 sessions or 30 days)"), lines[1])
        assertEquals("La première saison commence.", lines[2])
        assertTrue(lines[3].startsWith("Season idea: $season « Les débuts », written 10-01 19:00, "), lines[3])
        assertTrue(
            lines[3].endsWith(
                " of play left (written before this season of the Chronicle began): Une saison de premiers pas : le Nether pour Bartok, les affaires perdues pour Mimosa.",
            ),
            lines[3],
        )
        assertEquals(3, on.getValue("draws").lines().count { it.startsWith("- seed: ") }, on.getValue("draws"))

        forge.available = false
        val off = blocks(inputs(forge = forge).opening(12, at("22:10"), null)!!)
        assertEquals("<arsenal>\n(the Forge is off now: no idea of kind module nor forge)\n</arsenal>", off.getValue("arsenal"))
    }
}
