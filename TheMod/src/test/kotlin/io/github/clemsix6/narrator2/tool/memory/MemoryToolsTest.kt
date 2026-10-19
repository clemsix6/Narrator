package io.github.clemsix6.narrator2.tool.memory

import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.memory.CanonDraft
import io.github.clemsix6.narrator2.memory.CanonKind
import io.github.clemsix6.narrator2.memory.CanonMechanic
import io.github.clemsix6.narrator2.memory.InteractionKind
import io.github.clemsix6.narrator2.memory.MemoryDraft
import io.github.clemsix6.narrator2.memory.MemoryKind
import io.github.clemsix6.narrator2.testing.MemorySamples
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolArgs
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolEnv
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolSchema
import io.github.clemsix6.narrator2.tool.ToolSet
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.tool.of
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The memory tools of the Director (M3 §5) on the real store, index and recall: their schemas, each result
 * and each error of §5, what they write in the tables, who sees them, and what each role may write with
 * them (M3 review, findings 1 and 4).
 */
class MemoryToolsTest {
    private val remember = RememberTool()
    private val resolve = ResolveTool()
    private val note = NoteInteractionTool()
    private val recall = RecallTool()
    private val profile = PlayerProfileTool()

    /** Runs [block] on a fresh fixture; a test method must return Unit, or JUnit skips it. */
    private fun fixture(block: (MemoryToolFixture) -> Unit) {
        MemoryToolFixture().use(block)
    }

    private fun assertOk(expected: String, result: ToolResult) {
        assertEquals(ToolStatus.OK, result.status, result.text)
        assertEquals(expected, result.text)
    }

    private fun assertInvalid(expected: String, result: ToolResult) {
        assertEquals(ToolStatus.INVALID, result.status, result.text)
        assertEquals("Invalid arguments: $expected", result.text)
    }

    // ── Schemas, availability ───────────────────────────────────────────────────────────────────────

    private fun JsonObject.property(name: String): JsonObject = getValue("properties").jsonObject.getValue(name).jsonObject

    private fun JsonObject.strings(name: String): List<String> = getValue(name).jsonArray.map { it.jsonPrimitive.content }

    private fun JsonObject.number(name: String): Double = getValue(name).jsonPrimitive.content.toDouble()

    @Test
    fun `the five tools build their schemas from their arguments`() {
        val tools = memoryTools(TestServices())
        assertEquals(listOf("remember", "resolve", "note_interaction", "recall", "player_profile"), tools.map { it.name })
        ToolSet.of(tools) // the SDK definitions
        val schemas = tools.associate { it.name to ToolSchema.of(it.args.descriptor, it.name) }

        val remember = schemas.getValue("remember")
        assertEquals(
            listOf("kind", "what", "quote", "players", "due_in_minutes", "stakes", "at_player", "theme", "importance", "evidence"),
            remember.getValue("properties").jsonObject.keys.toList(),
        )
        assertEquals(40.0, remember.property("theme").number("maxLength"))
        assertEquals(listOf("kind", "what"), remember.strings("required"))
        assertEquals(
            listOf(
                "promise", "threat", "challenge", "law", "prophecy", "nickname", "title", "place", "lore", "opinion", "running_gag",
                "arc", "claim", "episode", "observation",
            ),
            remember.property("kind").strings("enum"),
        )
        assertEquals(300.0, remember.property("what").number("maxLength"))
        assertEquals(200.0, remember.property("quote").number("maxLength"))
        assertEquals(listOf(1.0, 10_080.0), listOf(remember.property("due_in_minutes").number("minimum"), remember.property("due_in_minutes").number("maximum")))
        assertEquals(8.0, remember.property("players").number("maxItems"))
        assertEquals(16.0, remember.property("players").getValue("items").jsonObject.number("maxLength"))

        val resolve = schemas.getValue("resolve")
        assertEquals(listOf("id", "outcome", "note"), resolve.strings("required"))
        assertEquals(
            listOf("fulfilled", "broken", "expired", "cancelled", "won", "lost", "abandoned", "active", "resolved", "retired"),
            resolve.property("outcome").strings("enum"),
        )

        val note = schemas.getValue("note_interaction")
        assertEquals(listOf("player", "kind", "valence", "summary"), note.strings("required"))
        assertEquals(InteractionKind.entries.map { it.id }, note.property("kind").strings("enum"))
        assertEquals(listOf(-3.0, 3.0), listOf(note.property("valence").number("minimum"), note.property("valence").number("maximum")))
        assertEquals(160.0, note.property("summary").number("maxLength"))

        val recall = schemas.getValue("recall")
        assertEquals(listOf("query", "player", "kinds", "days", "include_closed", "limit"), recall.getValue("properties").jsonObject.keys.toList())
        assertEquals(listOf("memory", "canon", "interaction", "chronicle"), recall.property("kinds").getValue("items").jsonObject.strings("enum"))
        assertEquals(20.0, recall.property("limit").number("maximum"))

        val profile = schemas.getValue("player_profile")
        assertEquals(listOf("player"), profile.strings("required"))
        assertEquals(
            listOf("identity", "recap", "personality", "stats", "history", "memories"),
            profile.property("parts").getValue("items").jsonObject.strings("enum"),
        )
        tools.forEach { assertTrue(it.description.length <= 600, "${it.name}: a description read at every turn stays short") }
    }

    @Test
    fun `the tools are shown only while the memory runs, writes are engine actions, the rest reads`() {
        val tools = memoryTools(TestServices())
        tools.forEach { tool ->
            assertFalse(tool.isAvailable(ToolEnv(journalAvailable = true, workerRoles = emptySet(), memoryAvailable = false)), tool.name)
            assertTrue(tool.isAvailable(ToolEnv(journalAvailable = true, workerRoles = emptySet(), memoryAvailable = true)), tool.name)
        }
        assertEquals(
            mapOf(
                "remember" to ToolEffect.ENGINE, "resolve" to ToolEffect.ENGINE, "note_interaction" to ToolEffect.ENGINE,
                "recall" to ToolEffect.READ, "player_profile" to ToolEffect.READ,
            ),
            tools.associate { it.name to it.effect },
        )
    }

    // ── remember ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `remember writes a promise with its due time, and the same promise twice is one entry`() = fixture { f ->
        val input = """{"kind":"promise","what":"Le Narrateur a promis à Mimosa de lui rendre ses bottes de fer.",""" +
            """"quote":"je te rends tes bottes, promis","players":["Mimosa"],"due_in_minutes":40,"importance":6}"""

        assertOk("ok: remembered C-1000 (promise → Mimosa, due 20:20, in 40m); a DUE event will wake you then", f.call(remember, input))
        assertOk("ok: already remembered as C-1000 (promise → Mimosa, due 20:20, in 40m); nothing added", f.call(remember, input))

        val row = f.rows(
            "SELECT kind, status, quote, epoch_ms(due_at), importance, source, turn_id, conversation_id, players[1]::VARCHAR FROM canon",
        ) { listOf(getString(1), getString(2), getString(3), getLong(4), getInt(5), getString(6), getLong(7), getString(8), getString(9)) }
        assertEquals(
            listOf(
                listOf(
                    "promise", "active", "je te rends tes bottes, promis", Instant.parse("2026-10-01T20:20:00Z").toEpochMilli(), 6,
                    "director", 42L, "director-c13", f.mimosa.uuid.toString(),
                ),
            ),
            row,
        )
    }

    @Test
    fun `the Director's canon cites the lines of its turn that hold its words, its own or an operator's in its name`() = fixture { f ->
        val woke = Instant.parse("2026-10-01T19:39:00Z")
        // M3-19: C-5, said by the operator Clement in the Narrator's name, was written without evidence.
        f.base.chat(910, woke, "operator", "Mimosa, je te rends tes bottes dans une heure, promis.")
        f.base.chat(911, woke, "public", "il a dit je te rends tes bottes dans une heure, promis", f.bartok.uuid)
        f.base.sql(
            "INSERT INTO turns (turn_id, conversation_id, started_at, trigger, trigger_events, injected_events) VALUES (42, 'director-c13', ?, 'salient', [910, 911], [])",
            f.clock.instant(),
        )
        val line = "INSERT INTO chat (id, ts, channel, text, turn_id) VALUES (?, ?, 'narrator', ?, ?)"
        f.base.sql(line, 912, woke.plusSeconds(30), "Bartok, la prochaine insulte te coûtera une minute de lenteur. Pense à ta dignité.", 42)
        f.base.sql(line, 913, woke.minusSeconds(600), "Bartok, la prochaine insulte te coûtera une minute de lenteur.", 41)

        val promise = """{"kind":"promise","what":"Le Narrateur a promis à Mimosa de lui rendre ses bottes dans une heure.","players":["Mimosa"],"""
        assertEquals(ToolStatus.OK, f.call(remember, promise + """"quote":"je te rends tes bottes dans une heure, promis"}""").status)
        val threat = """{"kind":"threat","what":"Le Narrateur a menacé Bartok d'une minute de lenteur à la prochaine insulte.","players":["Bartok"]"""
        assertEquals(ToolStatus.OK, f.call(remember, threat + ""","quote":"La prochaine insulte te coûtera une minute de lenteur."}""").status)
        assertEquals(ToolStatus.OK, f.call(remember, """{"kind":"law","what":"Le Narrateur interdit de creuser sous le spawn."}""").status)
        assertEquals(ToolStatus.OK, f.call(remember, """{"kind":"lore","what":"Le Narrateur considère la lave comme sa piscine.","quote":"ma piscine de lave"}""").status)

        // The operator's line of the wake, not the player who repeats it; the Narrator's line of this turn, not of the one before;
        // nothing without a quote, nor for a quote no line of the turn holds.
        assertEquals(
            listOf("promise" to "[910]", "threat" to "[912]", "law" to "[]", "lore" to "[]"),
            f.rows("SELECT kind, evidence_events::VARCHAR FROM canon ORDER BY id") { getString(1) to getString(2) },
        )
    }

    @Test
    fun `remember refuses the Narrator's voice in what, and points to quote`() = fixture { f ->
        val result = f.call(remember, """{"kind":"promise","what":"Je promets à Mimosa de lui rendre ses bottes.","players":["Mimosa"]}""")

        assertEquals(ToolStatus.INVALID, result.status)
        assertTrue(result.text.startsWith("Invalid arguments: what must be factual and in the third person"), result.text)
        assertTrue("(found \"je\" outside quotes)" in result.text, result.text)
        assertTrue(result.text.endsWith("; your own words to the players go in quote"), result.text)
        assertEquals(0L, f.base.count("canon"))
    }

    @Test
    fun `remember refuses what does not go with the kind`() = fixture { f ->
        val law = "Quiconque casse la construction d'un autre joueur est réduit à la taille d'un lapin."
        assertInvalid("due_in_minutes does not apply to a law: leave it out", f.call(remember, """{"kind":"law","what":"$law","due_in_minutes":30}"""))
        assertInvalid(
            "due_in_minutes does not apply to an observation: leave it out; quote does not apply to an observation: put the player's " +
                "own words in what, between « »",
            f.call(remember, """{"kind":"observation","what":"Bartok a peur des creepers.","quote":"j'ai peur","due_in_minutes":5}"""),
        )
        assertInvalid(
            "stakes only go with a challenge: leave stakes out",
            f.call(remember, """{"kind":"promise","what":"Le Narrateur a promis un cadeau.","stakes":"un diamant"}"""),
        )
        assertInvalid(
            "at_player only goes with a place: name who it concerns in players",
            f.call(remember, """{"kind":"promise","what":"Le Narrateur a promis un cadeau.","at_player":"Bartok"}"""),
        )
        assertInvalid(
            "a nickname is given to exactly one player: name one",
            f.call(remember, """{"kind":"nickname","what":"Bartok et Mimosa sont surnommés « les Taupes ».","players":["Bartok","Mimosa"]}"""),
        )
        assertEquals(0L, f.base.count("canon"))
    }

    @Test
    fun `a new nickname replaces the active one`() = fixture { f ->
        assertOk(
            "ok: remembered C-1000 (nickname → Bartok)",
            f.call(remember, """{"kind":"nickname","what":"Bartok est surnommé « le Plongeur ».","quote":"le Plongeur","players":["Bartok"]}"""),
        )
        assertOk(
            "ok: remembered C-1001 (nickname → Bartok); C-1000 retired",
            f.call(remember, """{"kind":"nickname","what":"Bartok est surnommé « la Taupe ».","quote":"la Taupe","players":["Bartok"]}"""),
        )

        val rows = f.rows("SELECT id, status, replaces FROM canon ORDER BY id") { Triple(getLong(1), getString(2), getObject(3)) }
        assertEquals(listOf(Triple(1000L, "retired", null), Triple(1001L, "active", 1000L)), rows)
    }

    @Test
    fun `a place is where at_player stands now`() = fixture { f ->
        f.online(f.bartok)
        val place = """{"kind":"place","what":"Le Narrateur a nommé ce pic « la Dent du Juge ».""""

        assertInvalid("a place needs its spot: give at_player, a player online who stands on it", f.call(remember, "$place}"))
        assertInvalid("Mimosa is not online; online: Bartok", f.call(remember, """$place,"at_player":"Mimosa"}"""))
        assertOk("ok: remembered C-1000 (place at overworld 120 90 -400)", f.call(remember, """$place,"at_player":"bartok"}"""))

        val where = f.rows("SELECT kind, dim, x, y, z FROM canon") { listOf(getString(1), getString(2), getInt(3), getInt(4), getInt(5)) }
        assertEquals(listOf(listOf("place", "minecraft:overworld", 120, 90, -400)), where)
    }

    @Test
    fun `remember writes a fact about a player as a memory, players found by a former name too`() = fixture { f ->
        f.base.sql("INSERT INTO player_names VALUES (?, ?, ?)", f.bartok.uuid, "Bartok_", f.clock.instant())

        assertOk(
            "ok: remembered M-1000 (observation about Bartok)",
            f.call(remember, """{"kind":"observation","what":"Bartok a avoué avoir peur des creepers depuis toujours.","players":["bartok_"]}"""),
        )
        assertInvalid(
            "players: no player named Kelvin or Zoé has ever played here; online: nobody",
            f.call(remember, """{"kind":"observation","what":"Kelvin parle peu.","players":["Kelvin","Mimosa","Zoé"]}"""),
        )

        val row = f.rows("SELECT kind, title, content, importance, source FROM memories") { listOf(getString(1), getString(2), getString(3), getInt(4), getString(5)) }
        assertEquals(listOf(listOf("observation", "observation", "Bartok a avoué avoir peur des creepers depuis toujours.", 5, "director")), row)
    }

    @Test
    fun `the Chronicler remembers what was told to the players only with the Narrator's words, its evidence cited as the exchange log writes it`() = fixture { f ->
        val from = Instant.parse("2026-10-01T19:00:00Z")
        val chronicler = f.chronicler(7, from, f.clock.instant())
        f.base.chat(900, Instant.parse("2026-10-01T19:30:00Z"), "narrator", "Mimosa, je te rends tes bottes demain, promis.")
        val what = """"kind":"promise","what":"Le Narrateur a promis à Mimosa de lui rendre ses bottes le lendemain.","players":["Mimosa"]"""

        assertInvalid(
            "a promise needs quote: copy 3 words or more exactly from the Narrator's NARRATOR line, and cite that line in evidence",
            f.call(remember, """{$what,"evidence":["e900"]}""", chronicler),
        )
        val invented = f.call(remember, """{$what,"quote":"je te donne un diamant","evidence":["e900"]}""", chronicler)
        assertEquals(ToolStatus.INVALID, invented.status, "the store finds no such words of the Narrator: ${invented.text}")
        assertInvalid(
            "evidence: \"x9\" is not a ref of the exchange log: cite e… (events, chat) and t… (tool calls) as given",
            f.call(remember, """{$what,"quote":"je te rends tes bottes demain","evidence":["e900","x9"]}""", chronicler),
        )
        assertEquals(0L, f.base.count("canon"))
        assertOk(
            "ok: remembered C-1000 (promise → Mimosa, due 10-02 19:40, in 1d0h); the Director will get a DUE event then",
            f.call(remember, """{$what,"quote":"je te rends tes bottes demain","due_in_minutes":1440,"evidence":["E900"]}""", chronicler),
        )
        assertOk(
            "ok: remembered C-1001 (lore)",
            f.call(remember, """{"kind":"lore","what":"Le pic au nord du spawn porte le nom de Dent du Juge.","evidence":["e900"]}""", chronicler),
        )

        val row = f.rows("SELECT source, run_id, conversation_id, evidence_events FROM canon ORDER BY id") { listOf(getString(1), getLong(2), getString(3), getString(4)) }
        assertEquals(listOf(listOf("chronicler", 7L, "chronicler-7", "[900]"), listOf("chronicler", 7L, "chronicler-7", "[900]")), row)
    }

    @Test
    fun `the Dreamer remembers only arcs, lore and observations, never with a quote, and resolves only arcs`() = fixture { f ->
        val dreamer = f.dreamer(8)
        assertInvalid(
            "the Dreamer remembers only an arc (planned), lore or an observation, not a promise: what the Narrator tells the players " +
                "is recorded from its own words, by itself or the Chronicler",
            f.call(remember, """{"kind":"promise","what":"Le Narrateur a promis 64 diamants à Bartok.","quote":"je te promets 64 diamants","players":["Bartok"]}""", dreamer),
        )
        listOf("threat", "challenge", "law", "prophecy", "nickname", "title", "opinion", "running_gag", "claim", "episode").forEach { kind ->
            val result = f.call(remember, """{"kind":"$kind","what":"Bartok est surnommé « le Plongeur ».","players":["Bartok"]}""", dreamer)
            assertTrue(result.text.startsWith("Invalid arguments: the Dreamer remembers only an arc (planned), lore or an observation, not "), "$kind: ${result.text}")
        }
        assertInvalid(
            "quote does not apply to the Dreamer, who never heard the Narrator: leave it out",
            f.call(remember, """{"kind":"lore","what":"Le pic au nord du spawn porte le nom de Dent du Juge.","quote":"la Dent du Juge"}""", dreamer),
        )
        assertEquals(0L, f.base.count("canon") + f.base.count("memories"))
        assertOk("ok: remembered C-1000 (lore)", f.call(remember, """{"kind":"lore","what":"Le pic au nord du spawn porte le nom de Dent du Juge."}""", dreamer))
        assertOk(
            "ok: remembered M-1001 (observation about Bartok)",
            f.call(remember, """{"kind":"observation","what":"Bartok revient prier après chaque insulte.","players":["Bartok"]}""", dreamer),
        )

        f.call(remember, """{"kind":"promise","what":"Le Narrateur a promis à Mimosa de lui rendre ses bottes.","players":["Mimosa"]}""")
        assertInvalid(
            "the Dreamer resolves only arcs, and C-1002 is a promise: the Narrator and the Chronicler settle the rest",
            f.call(resolve, """{"id":"C-1002","outcome":"broken","note":"Le Narrateur a gardé les bottes de Mimosa."}""", dreamer),
        )
        assertEquals(listOf("active", "active"), f.rows("SELECT status FROM canon ORDER BY id") { getString(1) })
    }

    @Test
    fun `an arc the Dreamer remembers is planned, and the Director starts it`() = fixture { f ->
        assertOk(
            "ok: remembered C-1000 (arc, planned)",
            f.call(remember, """{"kind":"arc","what":"Le Narrateur prépare le procès des parieurs du pont de Mimosa.","theme":"proces_des_parieurs"}""", f.dreamer(8)),
        )
        assertOk(
            "ok: C-1000 arc is now active, due 10-08 19:40, in 7d0h; a DUE event will wake you then",
            f.call(resolve, """{"id":"C-1000","outcome":"active","note":"Le procès des parieurs commence."}"""),
        )
        assertEquals(listOf("active"), f.rows("SELECT status FROM canon") { getString(1) })
    }

    // ── Mechanics (M4 §12.3, §10) ───────────────────────────────────────────────────────────────────

    @Test
    fun `while the engine runs, the Director starts a challenge with start_challenge, and a plan is no kind of remember`() = fixture { f ->
        val challenge = """{"kind":"challenge","what":"Le Narrateur a défié Bartok de rapporter un œuf de tortue.","players":["Bartok"]}"""
        assertInvalid("a challenge is started with start_challenge: it tracks it, shows its bar and pays its stakes", f.call(remember, challenge))
        val plan = f.call(remember, """{"kind":"plan","what":"Le Narrateur prépare un comité d'accueil pour Bartok."}""")
        assertEquals(ToolStatus.INVALID, plan.status)
        assertTrue(plan.text.startsWith("Invalid arguments: kind must be one of: promise, threat, challenge"), plan.text)
        assertEquals(0L, f.base.count("canon"))

        // The Chronicler still records a challenge an operator announced in the Narrator's name.
        f.base.chat(900, Instant.parse("2026-10-01T19:30:00Z"), "operator", "Bartok, rapporte-moi un œuf de tortue avant la nuit.")
        val chronicler = f.chronicler(7, Instant.parse("2026-10-01T19:00:00Z"), f.clock.instant())
        val said = """{"kind":"challenge","what":"Le Narrateur a défié Bartok de rapporter un œuf de tortue avant la nuit.","players":["Bartok"],""" +
            """"quote":"rapporte-moi un œuf de tortue","evidence":["e900"]}"""
        assertOk("ok: remembered C-1000 (challenge → Bartok)", f.call(remember, said, chronicler))
        // Without the engine, the Director remembers its challenges as in M3.
        f.mechanics.available = false
        assertOk("ok: remembered C-1001 (challenge → Mimosa)", f.call(remember, challenge.replace("Bartok", "Mimosa")))
    }

    @Test
    fun `remember starts an arc with its theme, due within seven days, two at most`() = fixture { f ->
        assertInvalid(
            "an arc needs its theme: a slug that names what it is about, like vengeance_du_warden",
            f.call(remember, """{"kind":"arc","what":"La vengeance du Warden contre Bartok commence ce soir."}"""),
        )
        assertInvalid(
            "theme only goes with an arc: leave it out",
            f.call(remember, """{"kind":"observation","what":"Bartok a peur du Warden.","theme":"vengeance_du_warden"}"""),
        )
        assertOk(
            "ok: remembered C-1000 (arc, due 10-08 19:40, in 7d0h); a DUE event will wake you then",
            f.call(remember, """{"kind":"arc","what":"La vengeance du Warden contre Bartok commence ce soir.","theme":" Vengeance_Du_Warden "}"""),
        )
        assertOk(
            "ok: remembered C-1001 (arc, due 10-02 19:40, in 1d0h); a DUE event will wake you then",
            f.call(remember, """{"kind":"arc","what":"Mimosa doit reconstruire le pont des Soupirs avant l'hiver.","theme":"pont_des_soupirs","due_in_minutes":1440}"""),
        )
        val third = f.call(remember, """{"kind":"arc","what":"Les moutons du spawn complotent une révolte laineuse.","theme":"revolte_des_moutons"}""")
        assertEquals(ToolStatus.DENIED, third.status)
        assertEquals("DENIED (arc_limit): 2 arcs are active (C-1000, C-1001); resolve or abandon one first", third.text)
        assertEquals(listOf("vengeance_du_warden", "pont_des_soupirs"), f.rows("SELECT theme FROM canon ORDER BY id") { getString(1) })
    }

    @Test
    fun `resolve leaves a row the engine tracks to the engine, and names the tool that ends it`() = fixture { f ->
        val draft = CanonDraft(
            CanonKind.CHALLENGE, "Le Narrateur a défié Bartok et Mimosa de rapporter un œuf de tortue.", players = listOf(f.bartok.uuid, f.mimosa.uuid),
            mechanic = CanonMechanic.CHALLENGE,
        )
        runBlocking { f.store.addCanon(draft, MemorySamples.director) }
        assertInvalid(
            "C-1000 is a challenge the engine tracks: end it with end_challenge",
            f.call(resolve, """{"id":"C-1000","outcome":"won","note":"Mimosa a rapporté l'œuf la première.","winners":["Mimosa"]}"""),
        )
        assertEquals(listOf("active"), f.rows("SELECT status FROM canon") { getString(1) })
    }

    // ── resolve ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `resolve closes an entry once, with the outcomes of its kind`() = fixture { f ->
        f.mechanics.available = false // the Director remembers its own challenges
        f.call(remember, """{"kind":"promise","what":"Le Narrateur a promis à Mimosa de lui rendre ses bottes.","players":["Mimosa"]}""")
        f.call(remember, """{"kind":"promise","what":"Le Narrateur a promis une averse de cookies.","players":["Bartok"]}""")
        f.call(remember, """{"kind":"challenge","what":"Le premier à trouver un œuf de tortue gagne.","stakes":"une bénédiction / une nuit de lenteur"}""")

        assertOk(
            "ok: C-1000 promise → Mimosa is now fulfilled (19:40)",
            f.call(resolve, """{"id":"C-1000","outcome":"fulfilled","note":"Le Narrateur a rendu ses bottes à Mimosa."}"""),
        )
        assertInvalid(
            "C-1000 is already fulfilled (10-01 19:40); remember a new entry instead",
            f.call(resolve, """{"id":"c1000","outcome":"broken","note":"Le Narrateur a gardé les bottes."}"""),
        )
        assertInvalid(
            "a promise cannot be won; use fulfilled, broken, expired, cancelled",
            f.call(resolve, """{"id":"C-1001","outcome":"won","note":"Bartok a gagné.","winners":["Bartok"]}"""),
        )
        assertInvalid("won needs its winners: name them", f.call(resolve, """{"id":"C-1002","outcome":"won","note":"Bartok a trouvé l'œuf."}"""))
        assertOk(
            "ok: C-1002 challenge is now won by Bartok (19:40)",
            f.call(resolve, """{"id":"C-1002","outcome":"won","note":"Bartok a trouvé l'œuf le premier.","winners":["Bartok"]}"""),
        )
        assertInvalid(
            "id must be a canon entry, like C-45 (got \"M-812\")",
            f.call(resolve, """{"id":"M-812","outcome":"fulfilled","note":"Rien."}"""),
        )
        assertInvalid("C-99 does not exist", f.call(resolve, """{"id":"C-99","outcome":"fulfilled","note":"Rien."}"""))
        assertEquals(listOf("fulfilled", "active", "won"), f.rows("SELECT status FROM canon ORDER BY id") { getString(1) })
    }

    // ── note_interaction ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `note_interaction adds a line to the history and says how the favor moved`() = fixture { f ->
        // Bartok has played 0 s: each interaction weighs 1, and favor = 100 · Σv / (3 · (n + 3)).
        assertOk(
            "ok: noted I-1000 (Bartok, insult -2); favor of Bartok now -17",
            f.call(note, """{"player":"Bartok","kind":"insult","valence":-2,"summary":"a traité le Narrateur de « nul » dans le chat.","quote":"narrateur t'es nul"}"""),
        )
        assertOk(
            "ok: noted I-1001 (Bartok, provocation -1); favor of Bartok -17 → -20",
            f.call(note, """{"player":"Bartok","kind":"provocation","valence":-1,"summary":"a défié le Narrateur de le foudroyer."}"""),
        )

        val row = f.rows("SELECT kind, valence, counts, disrespect, quote, source, turn_id FROM interactions ORDER BY id") {
            listOf(getString(1), getInt(2), getBoolean(3), getBoolean(4), getString(5), getString(6), getLong(7))
        }
        assertEquals(
            listOf(listOf("insult", -2, true, true, "narrateur t'es nul", "director", 42L), listOf("provocation", -1, true, true, null, "director", 42L)),
            row,
        )
    }

    @Test
    fun `the same interaction noted twice is one line`() = fixture { f ->
        val input = """{"player":"Bartok","kind":"apology","valence":1,"summary":"a présenté des excuses sincères au Narrateur."}"""

        assertOk("ok: noted I-1000 (Bartok, apology +1); favor of Bartok now +8", f.call(note, input))
        assertOk("ok: already noted as I-1000 (Bartok, apology +1); nothing added", f.call(note, input))
        assertEquals(1L, f.base.count("interactions"))
    }

    @Test
    fun `the Director's note cites the player's lines that woke its turn, and the Chronicler's note of the same line is the same`() = fixture { f ->
        val said = Instant.parse("2026-10-01T19:39:00Z")
        f.base.chat(900, said, "public", "narrateur t'es nul", f.bartok.uuid)
        f.base.chat(901, said, "public", "laisse-le tranquille", f.mimosa.uuid)
        f.base.chat(902, said.plusSeconds(5), "narrator", "Bartok, guichet des insultes : plainte enregistrée.")
        f.base.chat(903, Instant.parse("2026-10-01T19:20:00Z"), "public", "salut narrateur", f.bartok.uuid)
        f.base.sql(
            "INSERT INTO turns (turn_id, conversation_id, started_at, trigger, trigger_events, injected_events) " +
                "VALUES (42, 'director-c13', ?, 'chat', [900, 901, 902], [])",
            f.clock.instant(),
        )

        assertOk(
            "ok: noted I-1000 (Bartok, insult -2); favor of Bartok now -17",
            f.call(note, """{"player":"Bartok","kind":"insult","valence":-2,"summary":"a traité le Narrateur de « nul » dans le chat."}"""),
        )
        assertEquals(listOf("[900]" to said.toEpochMilli()), f.rows("SELECT evidence_events::VARCHAR, epoch_ms(ts) FROM interactions") { getString(1) to getLong(2) })

        val chronicler = f.chronicler(7, Instant.parse("2026-10-01T19:30:00Z"), f.clock.instant())
        assertOk(
            "ok: already noted as I-1000 (Bartok, insult -2); nothing added",
            f.call(note, """{"player":"Bartok","kind":"insult","valence":-2,"summary":"a insulté le Narrateur en public.","evidence":["e900"]}""", chronicler),
        )
        assertEquals(1L, f.base.count("interactions"))
    }

    @Test
    fun `a punishment is linked to the world calls of the turn that hit the player`() = fixture { f ->
        f.toolCall(500, "apply_effect", listOf("Bartok"))
        f.toolCall(501, "say", listOf("Bartok"), effect = "speech")
        f.toolCall(502, "teleport", listOf("Mimosa"))
        f.toolCall(503, "apply_effect", listOf("Bartok"), status = "denied")
        f.toolCall(504, "launch", listOf("Bartok"), turn = 41)
        f.toolCall(505, "spawn", listOf("Mimosa", "Bartok"))

        assertOk(
            "ok: noted I-1000 (Bartok, punishment +2), with t500, t505",
            f.call(note, """{"player":"Bartok","kind":"punishment","valence":2,"summary":"a reçu une minute de lenteur pour une insulte."}"""),
        )
        assertOk(
            "ok: noted I-1001 (Mimosa, gift +1), with t502, t505",
            f.call(note, """{"player":"Mimosa","kind":"gift","valence":1,"summary":"a reçu des bottes de fer en cadeau."}"""),
        )
        assertEquals(listOf("[500, 505]", "[502, 505]"), f.rows("SELECT tool_calls FROM interactions ORDER BY id") { getString(1) })
        assertTrue(f.base.journal.barriers.get() > 0, "the calls of the turn are written before they are read")
    }

    @Test
    fun `a sanction without a call of the turn says nothing was linked, the Chronicler links its evidence`() = fixture { f ->
        assertOk(
            "ok: noted I-1000 (Mimosa, blessing +1); none of your calls of this turn acted on Mimosa, nothing linked",
            f.call(note, """{"player":"Mimosa","kind":"blessing","valence":1,"summary":"a reçu la bénédiction du Narrateur pour son pont."}"""),
        )

        f.toolCall(500, "apply_effect", listOf("Bartok"))
        val chronicler = f.chronicler(7, Instant.parse("2026-10-01T19:00:00Z"), f.clock.instant())
        assertOk(
            "ok: noted I-1001 (Bartok, punishment +2), with t500",
            f.call(note, """{"player":"Bartok","kind":"punishment","valence":2,"summary":"a reçu une minute de lenteur pour une insulte.","evidence":["t500"]}""", chronicler),
        )
        assertEquals(listOf("[]", "[500]"), f.rows("SELECT tool_calls FROM interactions ORDER BY id") { getString(1) })
    }

    // ── recall ──────────────────────────────────────────────────────────────────────────────────────

    private fun MemoryToolFixture.lavaMemory() = assertOk(
        "ok: remembered M-1000 (observation about Bartok)",
        call(remember, """{"kind":"observation","what":"Bartok est tombé dans un lac de lave du Nether en creusant.","players":["Bartok"]}"""),
    )

    @Test
    fun `recall shows its hits and records what the Director was shown`() = fixture { f ->
        f.lavaMemory()

        val result = f.call(recall, """{"query":"lave"}""")

        assertEquals(ToolStatus.OK, result.status, result.text)
        val lines = result.text.lines()
        assertEquals("recall \"lave\" · 1 found", lines[0])
        assertEquals("M-1000 · 10-01 19:40 (0m ago) · Bartok · ★5 · observation: « Bartok est tombé dans un lac de lave du Nether en creusant. »", lines[1])
        assertEquals(2, lines.size)
        val recalls = f.rows("SELECT ref_id, channel, conversation_id, turn_id FROM recalls") { listOf(getLong(1), getString(2), getString(3), getLong(4)) }
        assertEquals(listOf(listOf(1000L, "recall_tool", "director-c13", 42L)), recalls)
        assertEquals(listOf(1), f.rows("SELECT recalls FROM memories") { getInt(1) }, "the memory is reinforced")
    }

    @Test
    fun `recall says its filters, and when nothing is found`() = fixture { f ->
        f.lavaMemory()

        assertEquals(
            "recall \"lave\" · player Bartok · kinds memory · last 7 days · closed included · 1 found",
            f.call(recall, """{"query":"lave","player":"Bartok","kinds":["memory"],"days":7,"include_closed":true}""").text.lines()[0],
        )
        assertEquals("recall (latest) · 1 found", f.call(recall, "{}").text.lines()[0])
        assertOk("recall \"dragon\" · nothing found: try other words, fewer filters or include_closed", f.call(recall, """{"query":"dragon"}"""))
        assertInvalid("player: no player named Kelvin has ever played here; online: nobody", f.call(recall, """{"player":"Kelvin"}"""))
    }

    @Test
    fun `only the Narrator's own recalls reinforce, the Dreamer's leave a dream row, the Analyst's nothing`() = fixture { f ->
        f.lavaMemory()

        assertEquals("recall \"lave\" · 1 found", f.call(recall, """{"query":"lave"}""", f.analyst).text.lines()[0])
        assertEquals(0L, f.base.count("recalls"))
        assertEquals("recall \"lave\" · 1 found", f.call(recall, """{"query":"lave"}""", f.dreamer(8)).text.lines()[0])
        assertEquals(listOf(listOf("dream", "dreamer-8")), f.rows("SELECT channel, conversation_id FROM recalls") { listOf(getString(1), getString(2)) })
        assertEquals(listOf(0), f.rows("SELECT recalls FROM memories") { getInt(1) })
    }

    // ── player_profile ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `player_profile renders the full sheet, or some parts, and records the memories it shows`() = fixture { f ->
        val memory = runBlocking {
            f.store.addMemory(MemoryDraft(MemoryKind.EPISODE, "Le pont", "Bartok a fini son pont.", listOf(f.bartok.uuid)), MemorySamples.director).row
        }
        f.fake.sheets.sheets[f.bartok.uuid] = MemorySamples.sheet.copy(memories = listOf(memory))

        assertOk("SHEET Bartok (full, history stats)", f.call(profile, """{"player":"Bartok","parts":["history","stats"]}"""))
        assertEquals(0L, f.base.count("recalls"))
        assertOk("SHEET Bartok (full, identity recap personality stats history memories)", f.call(profile, """{"player":"bartok"}"""))
        assertEquals(listOf(listOf(1000L, "profile")), f.rows("SELECT ref_id, channel FROM recalls") { listOf(getLong(1), getString(2)) })

        assertInvalid("Mimosa has no sheet: the memory has never seen them", f.call(profile, """{"player":"Mimosa"}"""))
        assertInvalid("player: no player named Kelvin has ever played here; online: nobody", f.call(profile, """{"player":"Kelvin"}"""))
    }

    // ── Failures ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a Journal that cannot be read fails the call and says why`() {
        val services = TestServices() // the Journal is disabled, the fake memory available
        val caller = Caller(Role.DIRECTOR, "director-c13", turnId = 42, taskId = null)
        val args = ToolArgs.decode(recall.args, """{"player":"Bartok"}""") as ToolArgs.Decoded.Ok

        val result = runBlocking { recall.run(ToolContext(services, caller, services.clock, recall.timeoutMs), args.args) }

        assertEquals(ToolStatus.FAILED, result.status)
        assertEquals("FAILED: the Journal is unavailable: unit test", result.text)
    }
}
