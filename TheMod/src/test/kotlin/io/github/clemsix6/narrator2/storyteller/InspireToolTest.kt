package io.github.clemsix6.narrator2.storyteller

import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.journal.DisabledJournal
import io.github.clemsix6.narrator2.journal.JournalFixture
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.memory.RecallChannel
import io.github.clemsix6.narrator2.memory.RecallContext
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.memory.update
import io.github.clemsix6.narrator2.observer.LivePlayer
import io.github.clemsix6.narrator2.observer.PlayerStates
import io.github.clemsix6.narrator2.style.AtRest
import io.github.clemsix6.narrator2.style.ComicRegister
import io.github.clemsix6.narrator2.testing.FakeForge
import io.github.clemsix6.narrator2.testing.FakeMechanics
import io.github.clemsix6.narrator2.testing.FakeMemory
import io.github.clemsix6.narrator2.testing.FakeStyle
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.tool.storyteller.InspireArgs
import io.github.clemsix6.narrator2.tool.storyteller.InspireKind
import io.github.clemsix6.narrator2.tool.storyteller.InspireTool
import io.github.clemsix6.narrator2.trace.ToolCallRecord
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `inspire` (M6 §5) on a realistic evening: a real Journal (sessions, the Director's calls), the fake memory with two
 * sheets and the storyteller's programme, the arsenal, a style watch tired of the divine bureaucracy.
 */
class InspireToolTest {
    private val now: Instant = DeckSamples.now
    private val clock = TestClock(now)
    private val fixture = JournalFixture(clock = clock)
    private val memory = FakeMemory(clock)
    private val style = FakeStyle().apply {
        leastUsed = listOf(ComicRegister.FAIRY_TALE, ComicRegister.FOOD_CRITIC, ComicRegister.GAME_SHOW)
        last = List(5) { ComicRegister.DIVINE_BUREAUCRACY } + List(2) { ComicRegister.SPORTS_COMMENTARY } + ComicRegister.COURTROOM
        atRest = AtRest(emptyList(), emptyList(), listOf("céleste"))
    }
    private val forge = FakeForge(clock).apply { arsenal.add(DeckSamples.tag); arsenal.add(DeckSamples.chickens) }
    private val online = PlayerStates().apply {
        put(live(DeckSamples.bartok, 1))
        put(live(DeckSamples.mimosa, 2))
    }
    private val director = Caller(Role.DIRECTOR, "director-c13", turnId = 412, taskId = null)
    private var ids = 1L

    init {
        memory.sheets.sheets[DeckSamples.bartok.uuid] = DeckSamples.bartokSheet
        memory.sheets.sheets[DeckSamples.mimosa.uuid] = DeckSamples.mimosaSheet
        memory.clock.world = 1_000
        DeckSamples.ideas().forEach { memory.store.rows[it.ref] = it }
        listOf(DeckSamples.canopy, DeckSamples.gag).forEach { memory.store.rows[it.ref] = it }
        // Yesterday evening, then tonight: Bartok since 19:30, Mimosa since 19:45.
        player(DeckSamples.bartok)
        player(DeckSamples.mimosa)
        session(DeckSamples.bartok, Instant.parse("2026-10-20T19:00:00Z"), Instant.parse("2026-10-20T23:00:00Z"))
        session(DeckSamples.bartok, Instant.parse("2026-10-21T19:30:00Z"), null)
        session(DeckSamples.mimosa, Instant.parse("2026-10-21T19:45:00Z"), null)
        // The Director: a joker for Mimosa yesterday at 22:00, a challenge at 19:40 tonight, a tiny Bartok at 19:50.
        call("favor", """{"player":"Mimosa","favor":"joker"}""", Instant.parse("2026-10-20T22:00:00Z"), listOf("Mimosa"))
        call("start_challenge", """{"goal":"obtain","item":"turtle_egg","minutes":15}""", Instant.parse("2026-10-21T19:40:00Z"), listOf("Bartok", "Mimosa"))
        call("punish", """{"player":"Bartok","punishment":"tiny","severity":2}""", Instant.parse("2026-10-21T19:50:00Z"), listOf("Bartok"))
    }

    @AfterEach
    fun close() = fixture.close()

    /** The draw joined to the hand-off of M6-05: printed, so that the test report keeps it. */
    @Test
    fun `a draw on a real evening deals the programme first, then seeds, and says what rests`() = runBlocking<Unit> {
        val result = inspire(InspireArgs())
        println("── inspire, a real draw ──\n${result.text}")
        assertEquals(ToolStatus.OK, result.status, result.text)
        val lines = result.text.lines()
        assertEquals("ok: 3 ideas for Bartok, Mimosa (least used lately: fairy_tale, food_critic, game_show)", lines[0])
        assertTrue(lines[1].startsWith("- P-41 challenge · Bartok, Mimosa · « La course au pic »: "), lines[1])
        assertTrue(lines[2].startsWith("- P-44 favor · Mimosa · « Monument à la nageuse »: "), lines[2])
        assertTrue(lines[3].startsWith("- seed: "), lines[3])
        assertEquals(
            "At rest (you did them lately): punish tiny (Bartok, 40m ago) · challenge obtain (Bartok, Mimosa, 50m ago) · " +
                "favor joker (Mimosa, 2h00m ago) · divine_bureaucracy · « céleste »",
            lines[4],
            "a joker 22 hours ago on the clock, but only 2 hours ago in play",
        )
        assertEquals(5, lines.size)
        assertTrue(result.text.length <= Deck.TEXT_MAX)
        assertFalse(Regex("punish tiny ×|challenge obtain ×|favor joker ×").containsMatchIn(result.text), "what rests is not drawn")
    }

    /** M6 §5.4: the ideas dealt (`dealt`, `recalls`), the hooks' rows shown, a `style inspired` decision with every key. */
    @Test
    fun `what a draw dealt the Director is recorded`() = runBlocking<Unit> {
        val result = inspire(InspireArgs())
        val context = RecallContext("director-c13", 412, null)
        val recalls = memory.store.recalls
        assertEquals(listOf(Ref(RefKind.IDEA, 41), Ref(RefKind.IDEA, 44)), recalls.filter { it.first.kind == RefKind.IDEA }.map { it.first })
        assertTrue(recalls.all { it.second == RecallChannel.INSPIRE && it.third == context })
        assertEquals(1, (memory.store.rows[Ref(RefKind.IDEA, 41)] as io.github.clemsix6.narrator2.memory.Idea).dealt)
        val detail = fixture.rows("SELECT CAST(detail AS VARCHAR) FROM engine_decisions WHERE kind = 'style' AND decision = 'inspired'") { getString(1) }
            .single().let { Json.parseToJsonElement(it).jsonObject }
        assertEquals("director", detail["role"]!!.jsonPrimitive.content)
        val keys = detail["keys"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("P-41", "P-44"), keys.take(2))
        val seedRefs = recalls.map { it.first }.filter { it.kind != RefKind.IDEA }.map { it.toString() }
        assertTrue(keys.containsAll(seedRefs), "$seedRefs among $keys")
        assertContains(result.text, "- seed: ${Interventions.label(keys[2])}")
    }

    /** S-S3 (M6 §15.3): two draws in a row repeat no idea, no seed, no lever, no hook, no twist. */
    @Test
    fun `two draws in a row repeat nothing`() = runBlocking<Unit> {
        val first = inspire(InspireArgs())
        val second = inspire(InspireArgs())
        println("── inspire, the next draw ──\n${second.text}")
        val dealt = fixture.rows("SELECT CAST(detail AS VARCHAR) FROM engine_decisions WHERE decision = 'inspired' ORDER BY id") { getString(1) }
            .map { text -> Json.parseToJsonElement(text).jsonObject["keys"]!!.jsonArray.map { it.jsonPrimitive.content } }
        assertEquals(2, dealt.size, "${first.text}\n${second.text}")
        assertEquals(emptySet(), dealt[0].toSet() intersect dealt[1].toSet(), "${first.text}\n${second.text}")
        assertTrue(dealt[1].none { it.startsWith("P-") }, "both fitting ideas were dealt a moment ago")
        assertEquals(3, dealt[1].count { it.startsWith("twist:") })
    }

    @Test
    fun `without the memory, seeds hang on no hook and the draw says why`() = runBlocking<Unit> {
        memory.available = false
        val result = inspire(InspireArgs())
        assertEquals(ToolStatus.OK, result.status)
        val seeds = result.text.lines().filter { it.startsWith("- seed: ") }
        assertEquals(3, seeds.size, result.text)
        assertTrue(seeds.all { it.matches(Regex("- seed: [^×]+ × \"[^\"]+\" · [a-z_]+")) }, result.text)
        assertEquals("Not now: hooks and programme (the memory is unavailable: fake memory off)", result.text.lines().last())
        assertEquals(emptyList(), memory.store.recalls, "nothing is written in a memory that is off")
    }

    @Test
    fun `without the mechanics, only the raw tools are levers`() = runBlocking<Unit> {
        val services = services(mechanics = FakeMechanics(clock).apply { available = false; reason = "engine down" })
        val result = InspireTool().run(ToolContext(services, director, clock, 5_000), InspireArgs())
        val levers = result.text.lines().filter { it.startsWith("- seed: ") }.map { it.removePrefix("- seed: ").substringBefore(" × ") }
        assertTrue(levers.isNotEmpty() && levers.all { it in setOf("named item", "book", "prophecy") }, result.text)
        assertContains(result.text, "Not now: challenges, duels, punishments, favors, laws, plans and modules (the mechanics are unavailable: engine down)")
    }

    @Test
    fun `a named player centers the draw, and a name nobody played under is refused`() = runBlocking<Unit> {
        val result = inspire(InspireArgs(player = "mimosa", kind = InspireKind.FAVOR))
        val lines = result.text.lines()
        assertEquals("ok: 3 favor ideas for Mimosa (least used lately: fairy_tale, food_critic, game_show)", lines[0])
        assertTrue(lines[1].startsWith("- P-44 favor · Mimosa"), lines[1])
        assertTrue(lines.filter { it.startsWith("- seed: ") }.none { it.contains("Bartok") }, result.text)
        assertTrue(lines.any { it.startsWith("At rest") && it.contains("favor joker (Mimosa, 2h00m ago)") }, result.text)
        assertTrue(lines.filter { it.startsWith("- seed: ") }.none { it.startsWith("- seed: favor joker") }, "the joker rests on Mimosa for 6h")

        val unknown = inspire(InspireArgs(player = "Zorro"))
        assertEquals(ToolResult.invalid("player: no player named Zorro has ever played here; online: Bartok, Mimosa"), unknown)
    }

    @Test
    fun `the card of an idea is whole, and only an idea has one`() = runBlocking<Unit> {
        val card = inspire(InspireArgs(idea = "p41"))
        assertEquals(ToolStatus.OK, card.status)
        assertTrue(card.text.startsWith("ok: P-41 challenge · open · for Bartok, Mimosa · never dealt · expires in 1d3h of play\n"), card.text)
        assertContains(card.text, "Pitch: Course au sommet du pic enneigé au nord de leur base ; enjeu : le premier en haut gagne un joker, le second porte une citrouille.")
        assertContains(card.text, "- C-60 (gone)")
        assertEquals(emptyList(), memory.store.recalls, "reading a card deals nothing")
        assertEquals(
            ToolResult.invalid("idea: P-99 is not an idea of your programme; open ideas: P-46, P-45, P-44, P-41, P-40"),
            inspire(InspireArgs(idea = "P-99")),
        )
        assertEquals(
            ToolResult.invalid("idea: \"C-60\" is not an idea: write P- and its number, as your briefing or a draw shows it"),
            inspire(InspireArgs(idea = "C-60")),
        )
    }

    /** M6 §5.4: the storyteller's own draws reach no player: recorded, never dealt, and never at rest for the Director. */
    @Test
    fun `the storyteller's draws are recorded, never dealt`() = runBlocking<Unit> {
        val storyteller = Caller(Role.STORYTELLER, "storyteller-run9", turnId = null, taskId = null)
        val result = InspireTool().run(ToolContext(services(), storyteller, clock, 5_000), InspireArgs())
        assertEquals(ToolStatus.OK, result.status)
        assertEquals(emptyList(), memory.store.recalls)
        assertEquals(0, (memory.store.rows[Ref(RefKind.IDEA, 41)] as io.github.clemsix6.narrator2.memory.Idea).dealt)
        val roles = fixture.rows("SELECT CAST(detail AS VARCHAR) FROM engine_decisions WHERE decision = 'inspired'") { getString(1) }
            .map { Json.parseToJsonElement(it).jsonObject["role"]!!.jsonPrimitive.content }
        assertEquals(listOf("storyteller"), roles)
        assertTrue(inspire(InspireArgs()).text.lines()[1].startsWith("- P-41"), "the Director still gets P-41")
    }

    @Test
    fun `a Journal that cannot be read leaves nothing out, and says so`() = runBlocking<Unit> {
        val services = services(journal = DisabledJournal("broken disk", clock))
        val result = InspireTool().run(ToolContext(services, director, clock, 5_000), InspireArgs())
        assertEquals(ToolStatus.OK, result.status)
        assertContains(result.text, "Not now: what you did lately (unreadable: the Journal is unavailable: broken disk): nothing is left out")
    }

    // ── Setup ───────────────────────────────────────────────────────────────────────────────────────

    private fun services(
        journal: io.github.clemsix6.narrator2.journal.Journal = fixture.journal,
        mechanics: FakeMechanics = FakeMechanics(clock),
    ) = TestServices(
        clock = clock, journal = journal, trace = fixture.journal, memory = memory, style = style, forge = forge, mechanics = mechanics, players = online,
    )

    private suspend fun inspire(args: InspireArgs): ToolResult = InspireTool().run(ToolContext(services(), director, clock, 5_000), args)

    private fun live(player: PlayerRef, session: Long) = LivePlayer(
        player, session, now.toEpochMilli() - (10 - session) * 60_000, false, "survival", Pos("minecraft:overworld", 0, 64, 0), null, null, false, 20f, 20f, 20, null, null, 0, false,
    )

    private fun player(player: PlayerRef) = write {
        it.update(
            "INSERT INTO players (player_uuid, name, first_seen, last_seen, sessions, playtime_s, is_op) VALUES (?, ?, ?, ?, 3, 36000, false)",
            player.uuid, player.name, now.minus(Duration.ofDays(20)), now,
        )
    }

    private fun session(player: PlayerRef, joined: Instant, left: Instant?) = write {
        it.update(
            "INSERT INTO sessions (session_id, player_uuid, player, joined_at, left_at) VALUES (?, ?, ?, ?, ?)",
            ids++, player.uuid, player.name, joined, left,
        )
    }

    private fun call(tool: String, args: String, at: Instant, targets: List<String>) {
        val id = ids++
        fixture.journal.toolCall(ToolCallRecord(id, at, "director-c12", 300 + id, id, "toolu_$id", Role.DIRECTOR, tool, args, ToolStatus.OK, "ok", 20, targets, ToolEffect.WORLD))
    }

    private fun write(block: (java.sql.Connection) -> Unit) = runBlocking { fixture.journal.write(5_000) { block(it) } }
}
