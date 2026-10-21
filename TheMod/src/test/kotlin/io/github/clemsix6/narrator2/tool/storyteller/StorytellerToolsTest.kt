package io.github.clemsix6.narrator2.tool.storyteller

import io.github.clemsix6.narrator2.forge.ArsenalEntry
import io.github.clemsix6.narrator2.forge.ModuleStatus
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.memory.MemoryDraft
import io.github.clemsix6.narrator2.memory.MemoryKind
import io.github.clemsix6.narrator2.memory.RunContext
import io.github.clemsix6.narrator2.memory.RunKind
import io.github.clemsix6.narrator2.testing.FakeForge
import io.github.clemsix6.narrator2.testing.MemorySamples
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.tool.memory.MemoryToolFixture
import io.github.clemsix6.narrator2.tool.memory.RecallTool
import io.github.clemsix6.narrator2.tool.memory.RememberTool
import io.github.clemsix6.narrator2.tool.memory.ResolveTool
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The storyteller's tools (M6 §6.2, §6.5) on the real store, index and recall: `propose_idea` and `drop_idea`, signed by
 * the storyteller in a dream's programme and in a task; what the tool checks beyond the store (refs, players, the arsenal
 * and the Forge); and what the storyteller may do with the tools it shares: remember a planned arc or lore, abandon a
 * planned arc, read without reinforcing.
 */
class StorytellerToolsTest {
    private val propose = ProposeIdeaTool()
    private val drop = DropIdeaTool()
    private val remember = RememberTool()
    private val resolve = ResolveTool()
    private val recall = RecallTool()

    /** Runs [block] on a fresh fixture; a test method must return Unit, or JUnit skips it. */
    private fun fixture(block: (MemoryToolFixture) -> Unit) {
        MemoryToolFixture().use(block)
    }

    /** The storyteller's step of the dream [runId] (`MemoryAgents.run(Role.STORYTELLER, …)`). */
    private fun MemoryToolFixture.programme(runId: Long): Caller {
        val conversation = "storyteller-$runId"
        fake.runs[conversation] = RunContext(runId, RunKind.DREAM, null, null, null, role = Role.STORYTELLER)
        return Caller(Role.STORYTELLER, conversation, turnId = null, taskId = null)
    }

    /** A task of the storyteller, which has no memory run (`delegate`, M6 §6.4). */
    private val task = Caller(Role.STORYTELLER, "storyteller-t12", turnId = null, taskId = 12)

    private fun assertOk(expected: String, result: ToolResult) {
        assertEquals(ToolStatus.OK, result.status, result.text)
        assertEquals(expected, result.text)
    }

    private fun assertInvalid(expected: String, result: ToolResult) {
        assertEquals(ToolStatus.INVALID, result.status, result.text)
        assertEquals("Invalid arguments: $expected", result.text)
    }

    private fun idea(kind: String = "challenge", title: String = "La course au pic", extra: String = ""): String =
        """{"kind":"$kind","title":"$title","pitch":"Une course au sommet du pic enneigé au nord de la base de $title.",""" +
            """"fit":"Bartok et Mimosa en ligne.","players":["Bartok","mimosa"],"novelty":4,"fun":4,"fairness":5$extra}"""

    /** The audit, in order: every row is written at the fixture's one instant, and an insert comes before an update. */
    private fun MemoryToolFixture.log(): List<List<String?>> =
        rows("SELECT actor, run_id::VARCHAR, op FROM memory_log ORDER BY ts, row_key, op") { listOf(getString(1), getString(2), getString(3)) }

    // ── propose_idea ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `propose_idea writes an idea signed by the storyteller, in the dream's programme and in a task`() = fixture { f ->
        assertOk(
            "ok: P-1000 challenge « La course au pic » for Bartok, Mimosa, open for 48h of world play; 1 idea open",
            f.call(propose, idea(), f.programme(21)),
        )
        assertOk(
            "ok: P-1001 speech « La prophétie du gâteau » for whoever is online, open for 6h of world play; 2 ideas open",
            f.call(
                propose,
                """{"kind":"speech","title":"La prophétie du gâteau","pitch":"Un livre annonce qu'un gâteau tombera du ciel au prochain orage.",""" +
                    """"fit":"Un soir d'orage.","register":"fairy_tale","novelty":3,"fun":4,"fairness":5,"hours":6}""",
                task,
            ),
        )
        assertEquals(
            listOf(listOf("storyteller", "21", "storyteller-21", "challenge"), listOf("storyteller", null, "storyteller-t12", "speech")),
            f.rows("SELECT source, run_id::VARCHAR, conversation_id, kind FROM ideas ORDER BY id") { listOf(getString(1), getString(2), getString(3), getString(4)) },
        )
        assertEquals(listOf(listOf("storyteller", "21", "insert"), listOf("storyteller", null, "insert")), f.log())
        assertEquals("fairy_tale", f.rows("SELECT register FROM ideas WHERE id = 1001") { getString(1) }.single())
    }

    @Test
    fun `propose_idea refuses refs that are not rows, unknown players, and what the store refuses`() = fixture { f ->
        val caller = f.programme(21)
        assertInvalid(
            "refs: \"X-12\" is not a row of the memory: cite C-45, M-812, H-3 as given",
            f.call(propose, idea(extra = ""","refs":["X-12"]"""), caller),
        )
        assertInvalid("players: no player named Kelvin has ever played here; online: nobody", f.call(propose, idea().replace("mimosa", "Kelvin"), caller))
        assertInvalid("refs: C-77 does not exist; cite refs you were given", f.call(propose, idea(extra = ""","refs":["C-77"]"""), caller))
        val voice = f.call(propose, idea().replace("Une course", "Je lance une course"), caller)
        assertEquals(ToolStatus.INVALID, voice.status, voice.text)
        assertTrue(voice.text.startsWith("Invalid arguments: pitch must be factual and in the third person"), voice.text)
        assertEquals(0L, f.base.count("ideas"))

        f.call(propose, idea(), caller)
        val again = f.call(propose, idea(title = "La course au pic", extra = ""","hours":12"""), caller)
        assertInvalid("too close to P-1000 « La course au pic » (open, never dealt): find something else", again)
    }

    @Test
    fun `an idea module names a ready module of the arsenal, and a module or forge idea needs the Forge`() = fixture { f ->
        val caller = f.programme(21)
        val forge = f.services.forge as FakeForge
        assertInvalid(
            "module: no module \"tag_game\" is ready in the arsenal; ready: none (a module to order is an idea forge, with its brief)",
            f.call(propose, idea("module", extra = ""","module":"tag_game""""), caller),
        )
        forge.arsenal.add(entry("tag_game", ModuleStatus.PENDING))
        assertInvalid(
            "module: no module \"tag_game\" is ready in the arsenal; ready: none (a module to order is an idea forge, with its brief)",
            f.call(propose, idea("module", extra = ""","module":"tag_game""""), caller),
        )
        forge.arsenal.add(entry("orbiting_chickens", ModuleStatus.READY))
        assertOk(
            "ok: P-1000 module « La course au pic » for Bartok, Mimosa, open for 48h of world play; 1 idea open",
            f.call(propose, idea("module", extra = ""","module":"orbiting_chickens""""), caller),
        )
        assertInvalid(
            "an idea module names the module of the arsenal it deploys: give module",
            f.call(propose, idea("module", title = "Les poules en orbite"), caller),
        )

        forge.available = false
        assertInvalid(
            "the Forge is off (fake forge off): no module can be deployed nor ordered; propose another kind",
            f.call(propose, idea("forge", title = "Le banquet des poules", extra = ""","brief":"${"b".repeat(60)}""""), caller),
        )
        assertEquals(listOf("orbiting_chickens"), f.rows("SELECT module FROM ideas") { getString(1) })
    }

    @Test
    fun `propose_idea tells the season it replaced`() = fixture { f ->
        val caller = f.programme(21)
        f.call(propose, idea("season", title = "Les Grandes Manoeuvres"), caller)
        assertOk(
            "ok: P-1001 season « Le Siège du Moulin » for Bartok, Mimosa, open for 48h of world play; " +
                "P-1000 season « Les Grandes Manoeuvres » dropped: replaced by the season P-1001; 1 idea open",
            f.call(
                propose,
                """{"kind":"season","title":"Le Siège du Moulin","pitch":"Un moulin assiégé par des moutons réclame justice.",""" +
                    """"fit":"Bartok et Mimosa en ligne.","players":["Bartok","Mimosa"],"novelty":4,"fun":4,"fairness":5}""",
                caller,
            ),
        )
    }

    // ── drop_idea ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `drop_idea closes one of the storyteller's ideas, once, with its reason`() = fixture { f ->
        val caller = f.programme(21)
        f.call(propose, idea(), caller)
        assertInvalid("id must be one of your ideas, like P-41 (got \"C-5\")", f.call(drop, """{"id":"C-5","reason":"Le pic a fondu."}""", caller))
        assertOk(
            "ok: P-1000 challenge « La course au pic » dropped; it is no longer dealt to the Narrator",
            f.call(drop, """{"id":"p1000","reason":"Le pic a fondu."}""", task),
        )
        assertInvalid("P-1000 is dropped already: nothing to drop", f.call(drop, """{"id":"P-1000","reason":"Encore."}""", caller))
        assertEquals(listOf(listOf("dropped", "Le pic a fondu.")), f.rows("SELECT status, close_reason FROM ideas") { listOf(getString(1), getString(2)) })
        assertEquals(listOf(listOf("storyteller", "21", "insert"), listOf("storyteller", null, "update")), f.log())
    }

    // ── The tools it shares ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `the storyteller remembers a planned arc or lore and nothing else, never with a quote`() = fixture { f ->
        val caller = f.programme(21)
        assertInvalid(
            "the storyteller remembers only an arc (planned) or lore, not a promise: an idea goes in propose_idea, and what the Narrator " +
                "tells the players is recorded from its own words",
            f.call(remember, """{"kind":"promise","what":"Le Narrateur a promis 64 diamants à Bartok.","players":["Bartok"]}""", caller),
        )
        listOf("law", "threat", "nickname", "running_gag", "claim", "observation", "episode").forEach { kind ->
            val result = f.call(remember, """{"kind":"$kind","what":"Bartok est surnommé « le Plongeur ».","players":["Bartok"]}""", task)
            assertEquals(ToolStatus.INVALID, result.status, "$kind: ${result.text}")
            assertTrue(result.text.startsWith("Invalid arguments: the storyteller remembers only an arc (planned) or lore, not "), "$kind: ${result.text}")
        }
        assertInvalid(
            "quote does not apply to the storyteller, who never heard the Narrator: leave it out",
            f.call(remember, """{"kind":"lore","what":"Le pic au nord du spawn porte le nom de Dent du Juge.","quote":"la Dent du Juge"}""", caller),
        )
        assertEquals(0L, f.base.count("canon") + f.base.count("memories"))

        assertOk(
            "ok: remembered C-1000 (arc, planned)",
            f.call(remember, """{"kind":"arc","what":"Le Narrateur prépare le procès des parieurs du pont de Mimosa.","theme":"proces_des_parieurs"}""", caller),
        )
        assertOk("ok: remembered C-1001 (lore)", f.call(remember, """{"kind":"lore","what":"Le pic au nord du spawn porte le nom de Dent du Juge."}""", task))
        assertEquals(
            listOf(listOf("storyteller", "21", "planned"), listOf("storyteller", null, "active")),
            f.rows("SELECT source, run_id::VARCHAR, status FROM canon ORDER BY id") { listOf(getString(1), getString(2), getString(3)) },
        )
    }

    @Test
    fun `the storyteller only abandons a planned arc`() = fixture { f ->
        val caller = f.programme(21)
        f.call(remember, """{"kind":"arc","what":"Le Narrateur prépare le procès des parieurs du pont de Mimosa.","theme":"proces_des_parieurs"}""", caller)
        f.call(remember, """{"kind":"promise","what":"Le Narrateur a promis à Mimosa de lui rendre ses bottes.","players":["Mimosa"]}""")
        assertInvalid(
            "the storyteller only abandons a planned arc that no longer holds (outcome abandoned): the Narrator starts it",
            f.call(resolve, """{"id":"C-1000","outcome":"active","note":"Le procès commence."}""", caller),
        )
        assertInvalid(
            "the storyteller resolves only a planned arc, and C-1001 is a promise, active: the Narrator settles the rest",
            f.call(resolve, """{"id":"C-1001","outcome":"broken","note":"Le Narrateur a gardé les bottes de Mimosa."}""", task),
        )
        assertOk("ok: C-1000 arc is now abandoned (19:40)", f.call(resolve, """{"id":"C-1000","outcome":"abandoned","note":"Le procès n'aura pas lieu."}""", task))
        assertEquals(listOf("abandoned", "active"), f.rows("SELECT status FROM canon ORDER BY id") { getString(1) })
    }

    @Test
    fun `what the storyteller reads leaves a programme row and reinforces nothing`() = fixture { f ->
        runBlocking {
            f.store.addMemory(
                MemoryDraft(MemoryKind.OBSERVATION, "observation", "Bartok est tombé dans un lac de lave du Nether en creusant.", listOf(f.bartok.uuid)),
                MemorySamples.director,
            )
        }
        assertEquals("recall \"lave\" · 1 found", f.call(recall, """{"query":"lave"}""", task).text.lines()[0])
        assertEquals(listOf(listOf("programme", "storyteller-t12")), f.rows("SELECT channel, conversation_id FROM recalls") { listOf(getString(1), getString(2)) })
        assertEquals(listOf(0), f.rows("SELECT recalls FROM memories") { getInt(1) })
    }

    private fun entry(name: String, status: ModuleStatus) = ArsenalEntry(
        name = name, version = 1, status = status, builtIn = false, summary = "A module.", params = emptyList(), scenarios = emptyList(),
        savedAt = Instant.EPOCH, by = "forger T-31", note = null, hash = "sha256:0", sdk = 1, classes = 1, bytes = 1, problem = null,
    )
}
