package io.github.clemsix6.narrator2.forge.sdk

import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.forge.ParamSpec
import io.github.clemsix6.narrator2.forge.api.ForgeRefusal
import io.github.clemsix6.narrator2.forge.api.ParamType
import io.github.clemsix6.narrator2.forge.api.Place
import io.github.clemsix6.narrator2.mechanics.MechanicRejected
import io.github.clemsix6.narrator2.testing.ForgeSamples
import io.github.clemsix6.narrator2.testing.MechanicsSamples
import io.github.clemsix6.narrator2.tool.ToolStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * A deployment's parameters (M5 §4.2, §6.4 step 3; task M5-06): checked against the module's manifest and resolved
 * when the Director deploys it (`ParamCheck`), then read by the module (`ArgsView`), in every format of `ParamType`.
 */
class ArgsViewTest {
    private val bartok = MechanicsSamples.bartok
    private val mimosa = MechanicsSamples.mimosa
    private val josiane = PlayerRef(UUID.fromString("00000000-0000-0000-0000-0000000105a0"), "Josiane")

    /** The server as the check sees it: three players known, Bartok online at the edge of the Nether's lava lake. */
    private val lookup = object : ParamLookup {
        override val dimensions = setOf("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end")

        override suspend fun players(names: List<String>, field: String): List<PlayerRef> = names.map { name ->
            listOf(bartok, mimosa, josiane).firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: throw MechanicRejected(null, "$field: no player named $name has ever played here; online: Bartok")
        }

        override suspend fun standing(name: String): Place? = if (name.equals("bartok", ignoreCase = true)) Place("the_nether", 12.25, 31.0, -7.5) else null
    }

    private fun spec(name: String, type: ParamType, required: Boolean = true, fallback: String? = null, min: Double? = null, max: Double? = null, choices: List<String> = emptyList()) =
        ParamSpec(name, type, "The $name.", required, fallback, min, max, choices)

    /** One parameter of each type, the optional ones with a fallback. */
    private val specs = listOf(
        spec("wolf", ParamType.PLAYER),
        spec("hunters", ParamType.PLAYERS, required = false, min = 1.0),
        spec("chickens", ParamType.INTEGER, min = 3.0, max = 8.0),
        spec("speed", ParamType.NUMBER, required = false, fallback = "1.5", min = 0.5),
        spec("mood", ParamType.TEXT, required = false, fallback = "taquin", choices = listOf("taquin", "sévère")),
        spec("loud", ParamType.BOOLEAN, required = false, fallback = "false"),
        spec("arena", ParamType.PLACE, required = false),
    )

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun check(params: String, specs: List<ParamSpec> = this.specs): CheckedParams = runBlocking { ParamCheck.check(specs, json(params), lookup) }

    private fun refusal(params: String, specs: List<ParamSpec> = this.specs): String {
        val rejected = assertFailsWith<MechanicRejected> { check(params, specs) }
        assertEquals(ToolStatus.INVALID, rejected.toResult().status, "an invalid argument: ${rejected.message}")
        return rejected.message.orEmpty()
    }

    @Test
    fun `the witness's parameters are checked against its manifest and kept resolved, fallbacks included`() {
        val checked = runBlocking { ParamCheck.check(ForgeSamples.manifest.params, ForgeSamples.params, lookup) }
        assertEquals(json("""{"players": ["Bartok", "Mimosa"], "minutes": 5, "mood": "taquin"}"""), checked.params)
        assertEquals(listOf(bartok, mimosa), checked.players)

        val args = ArgsView(ForgeSamples.manifest.params, ForgeSamples.run.params)
        assertEquals(listOf("Bartok", "Mimosa"), args.players("players"))
        assertEquals(5, args.integer("minutes"))
        assertEquals("taquin", args.text("mood"), "missing from the state: its fallback")
    }

    @Test
    fun `every type in its JSON and its text form, names as the server knows them, a place given by a player where they stand`() {
        val checked = check(
            """{"wolf": "bartok", "hunters": "Mimosa, josiane", "chickens": "6", "speed": 2, "mood": "SÉVÈRE", "loud": "TRUE", "arena": "BARTOK"}""",
        )
        assertEquals(
            json(
                """{"wolf": "Bartok", "hunters": ["Mimosa", "Josiane"], "chickens": 6, "speed": 2.0, "mood": "sévère", "loud": true,
                   "arena": {"x": 12.25, "y": 31.0, "z": -7.5, "dimension": "the_nether"}}""",
            ),
            checked.params,
        )
        assertEquals(listOf(bartok, mimosa, josiane), checked.players)

        val args = ArgsView(specs, checked.params)
        assertEquals("Bartok", args.player("wolf"))
        assertEquals(listOf("Mimosa", "Josiane"), args.players("hunters"))
        assertEquals(6, args.integer("chickens"))
        assertEquals(6.0, args.number("chickens"), "a number reads an integer too")
        assertEquals(2.0, args.number("speed"))
        assertEquals("sévère", args.text("mood"))
        assertEquals(true, args.bool("loud"))
        assertEquals(Place("nether", 12.25, 31.0, -7.5), args.place("arena"))
        assertFailsWith<UnsupportedOperationException> { (args.players("hunters") as MutableList<String>).add("Bob") }

        val places = listOf(
            """"120 64 -30"""" to Place("overworld", 120.0, 64.0, -30.0),
            """"120.5 64 -30 end"""" to Place("the_end", 120.5, 64.0, -30.0),
            """{"x": 1, "y": 2, "z": 3}""" to Place("overworld", 1.0, 2.0, 3.0),
            """{"x": "1", "y": 2.5, "z": -3, "dimension": "minecraft:the_nether"}""" to Place("the_nether", 1.0, 2.5, -3.0),
        )
        for ((given, place) in places) {
            val params = check("""{"wolf": "Mimosa", "chickens": 3, "arena": $given}""").params
            assertEquals(place, ArgsView(specs, params).place("arena"), given)
        }
    }

    @Test
    fun `left out, a parameter reads its fallback, or nothing`() {
        val checked = check("""{"wolf": "Mimosa", "chickens": 8.0, "hunters": [], "arena": null}""")
        assertEquals(
            json("""{"wolf": "Mimosa", "hunters": null, "chickens": 8, "speed": 1.5, "mood": "taquin", "loud": false, "arena": null}"""),
            checked.params,
        )
        val args = ArgsView(specs, checked.params)
        assertEquals(emptyList(), args.players("hunters"))
        assertNull(args.place("arena"))
        assertEquals(1.5, args.number("speed"))
        assertEquals(false, args.bool("loud"))

        val missing = ArgsView(specs, json("""{"wolf": "Mimosa", "chickens": 4}"""))
        assertEquals(listOf("taquin", "1.5", "false"), listOf(missing.text("mood"), missing.number("speed").toString(), missing.bool("loud").toString()))
        assertEquals(emptyList(), missing.players("hunters"))
    }

    @Test
    fun `each problem is refused for the Director, with the field and how to fix it`() {
        val ok = """"wolf": "Mimosa", "chickens": 4"""
        assertEquals(
            "params.ducks is not a parameter of this module; its parameters: wolf, hunters, chickens, speed, mood, loud, arena",
            refusal("""{$ok, "ducks": 3}"""),
        )
        assertEquals("params.wolf is required: The wolf.", refusal("""{"chickens": 4}"""))
        assertEquals("params.chickens must be between 3 and 8 (got 12)", refusal("""{"wolf": "Mimosa", "chickens": 12}"""))
        assertEquals("params.chickens must be a whole number (got 4.5)", refusal("""{"wolf": "Mimosa", "chickens": 4.5}"""))
        assertEquals("params.chickens must be a whole number (got beaucoup)", refusal("""{"wolf": "Mimosa", "chickens": "beaucoup"}"""))
        assertEquals("params.speed must be at least 0.5 (got 0.1)", refusal("""{$ok, "speed": 0.1}"""))
        assertEquals("params.wolf: no player named Bob has ever played here; online: Bartok", refusal("""{"wolf": "Bob", "chickens": 4}"""))
        assertEquals("params.hunters names Mimosa twice", refusal("""{$ok, "hunters": ["Mimosa", "mimosa"]}"""))
        assertEquals("params.hunters must name between 1 and 8 players (got 9)", refusal("""{$ok, "hunters": "a,b,c,d,e,f,g,h,i"}"""))
        assertEquals("params.mood must be one of: taquin, sévère (got \"gentil\")", refusal("""{$ok, "mood": "gentil"}"""))
        assertEquals("params.loud must be true or false (got \"oui\")", refusal("""{$ok, "loud": "oui"}"""))
        assertEquals(
            "params.arena: Mimosa is not online (a place given by a player's name is where they stand now); give x, y and z instead",
            refusal("""{$ok, "arena": "Mimosa"}"""),
        )
        assertEquals(
            "params.arena: unknown dimension \"moon\"; dimensions: overworld, the_nether, the_end",
            refusal("""{$ok, "arena": "1 2 3 moon"}"""),
        )
        assertEquals("params.arena takes x, y, z and dimension (got dim)", refusal("""{$ok, "arena": {"x": 1, "y": 2, "z": 3, "dim": "end"}}"""))
        assertEquals("params.arena needs z", refusal("""{$ok, "arena": {"x": 1, "y": 2}}"""))
        assertContains(refusal("""{$ok, "arena": "1 2"}"""), "params.arena must be x, y, z and an optional dimension")
        assertEquals("params.wolf must be a single value, not [\"Mimosa\"]", refusal("""{"wolf": ["Mimosa"], "chickens": 4}"""))

        val text = listOf(spec("note", ParamType.TEXT, min = 3.0), spec("players", ParamType.PLAYERS, min = 2.0, max = 3.0))
        assertEquals("params.note must have between 3 and 300 characters (got 2)", refusal("""{"note": "ab", "players": "Bartok,Mimosa"}""", text))
        assertEquals("params.note must have between 3 and 300 characters (got 301)", refusal("""{"note": "${"a".repeat(301)}", "players": "Bartok,Mimosa"}""", text))
        assertEquals("params.note must not be empty", refusal("""{"note": "  ", "players": "Bartok,Mimosa"}""", text))
        assertEquals("params.players must name between 2 and 3 players (got 1)", refusal("""{"note": "abc", "players": ["Bartok"]}""", text))

        val fallback = listOf(spec("victim", ParamType.PLAYER, required = false, fallback = "Bob"))
        assertEquals("params.victim (its fallback): no player named Bob has ever played here; online: Bartok", refusal("{}", fallback))
    }

    @Test
    fun `the module reads its parameters with their own methods only, and nothing once it ended`() {
        val args = ArgsView(specs, check("""{"wolf": "Mimosa", "chickens": 4}""").params)
        assertEquals(
            "Args.integer: \"wolf\" is a PLAYER parameter: read it with args().player(\"wolf\")",
            assertFailsWith<ForgeRefusal> { args.integer("wolf") }.message,
        )
        assertEquals(
            "Args.text: \"loud\" is a BOOLEAN parameter: read it with args().bool(\"loud\")",
            assertFailsWith<ForgeRefusal> { args.text("loud") }.message,
        )
        assertFailsWith<ForgeRefusal> { args.integer("speed") }
        assertEquals(
            "Args: no parameter named \"ducks\"; its parameters: wolf, hunters, chickens, speed, mood, loud, arena",
            assertFailsWith<ForgeRefusal> { args.player("ducks") }.message,
        )
        assertFailsWith<ForgeRefusal> { args.player(null) }
        assertContains(assertFailsWith<ForgeRefusal> { ArgsView(emptyList(), JsonObject(emptyMap())).text("x") }.message.orEmpty(), "declares none")

        val none = ArgsView(listOf(spec("count", ParamType.INTEGER, required = false)), JsonObject(mapOf("count" to JsonNull)))
        assertContains(assertFailsWith<ForgeRefusal> { none.integer("count") }.message.orEmpty(), "has no value")

        args.close()
        assertContains(assertFailsWith<ForgeRefusal> { args.player("wolf") }.message.orEmpty(), "has ended")
    }

    @Test
    fun `a state that is not of its manifest's types cannot be read`() {
        assertFailsWith<IllegalArgumentException> { ArgsView(specs, json("""{"wolf": "Mimosa", "chickens": "many"}""")) }
        assertFailsWith<IllegalArgumentException> { ArgsView(specs, json("""{"wolf": "Mimosa", "chickens": 4, "arena": "Bartok"}""")) }
    }
}
