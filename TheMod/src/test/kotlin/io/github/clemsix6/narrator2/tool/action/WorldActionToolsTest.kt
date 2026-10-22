package io.github.clemsix6.narrator2.tool.action

import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.guard.Rules
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.observer.LivePlayer
import io.github.clemsix6.narrator2.testing.FakeGuards
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.ToolArgs
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the world action tools decide without a world: their arguments, the players online, the caps of
 * the chaos level checked before the server thread, and the weather and time limits. Everything else
 * runs on the server thread and is covered in game (`WorldActionGameTests`).
 */
class WorldActionToolsTest {
    private val guards = FakeGuards()
    private val services = TestServices(guards = guards)
    private val context = ToolContext(services, Caller(Role.DIRECTOR, "director-1", turnId = 3, taskId = null), services.clock, 5_000)

    private fun online(vararg names: String) = names.forEachIndexed { i, name ->
        services.players.put(
            LivePlayer(
                ref = PlayerRef(UUID.randomUUID(), name), sessionId = 1, joinedAt = i.toLong(), isOp = false, gameMode = "survival",
                pos = Pos("minecraft:overworld", 0, 64, 0), biome = null, structure = null, underground = false,
                health = 20f, maxHealth = 20f, food = 20, vehicle = null, lastDeathAt = null, lastMoveAt = 0, idle = false,
            ),
        )
    }

    private fun <A : Any> invalid(serializer: KSerializer<A>, json: String): String {
        val decoded = ToolArgs.decode(serializer, json)
        assertIs<ToolArgs.Decoded.Invalid>(decoded, "arguments: $decoded")
        return decoded.problem
    }

    private fun <A : Any> decode(serializer: KSerializer<A>, json: String): A {
        val decoded = ToolArgs.decode(serializer, json)
        assertIs<ToolArgs.Decoded.Ok<A>>(decoded, "arguments: $decoded")
        return decoded.args
    }

    private suspend fun <A : Any> NarratorTool<A>.call(json: String): ToolResult = run(context, decode(args, json))

    @Test
    fun `the family has the seven world tools`() {
        assertEquals(
            listOf("spawn", "lightning", "explosion", "fireworks", "particles", "set_weather", "set_time"),
            worldActionTools(services).map { it.name },
        )
    }

    @Test
    fun `a world effect happens at a player or at coordinates`() {
        val oneOf = "give either player, or x, y and z"
        assertEquals(oneOf, invalid(ParticlesArgs.serializer(), """{"particle":"heart"}"""))
        assertEquals(oneOf, invalid(ParticlesArgs.serializer(), """{"particle":"heart","player":"Bob","x":1,"y":2,"z":3}"""))
        assertEquals(oneOf, invalid(ExplosionArgs.serializer(), """{"power":1,"x":1,"z":3}"""))
        assertEquals(
            "dimension goes with x, y and z; a player's is their own",
            invalid(FireworksArgs.serializer(), """{"player":"Bob","dimension":"the_nether"}"""),
        )
        val there = decode(LightningArgs.serializer(), """{"x":10,"y":64,"z":-3,"dimension":"the_nether"}""")
        assertEquals(listOf(10, 64, -3), listOf(there.x, there.y, there.z))
        assertEquals(
            "damage_hearts needs a player struck directly (offset 0)",
            invalid(LightningArgs.serializer(), """{"player":"Bob","offset":3,"damage_hearts":1}"""),
        )
        assertEquals(
            "damage_hearts needs a player struck directly (offset 0)",
            invalid(LightningArgs.serializer(), """{"x":1,"y":2,"z":3,"damage_hearts":1}"""),
        )
        assertTrue(invalid(ParticlesArgs.serializer(), """{"player":"Bob","particle":"dust"}""").startsWith("particle must be one of: heart, angry_villager"))
    }

    @Test
    fun `spawn reads its nested specs and its defaults`() {
        val args = decode(
            SpawnArgs.serializer(),
            """{"entity":"creeper","count":4,"near":"Bob","target":true,"equipment":{"head":"carved_pumpkin",
               "chest":{"item":"leather_chestplate","color":"#F9D71C"}},
               "attributes":[{"attribute":"movement_speed","multiply":2.5}],"explode":{"after_seconds":3}}""",
        )
        assertEquals(8, args.distance)
        assertEquals(5, args.lifetimeMinutes)
        // An item id alone stands for the item (the equipment as written before M7-CREATURES).
        assertEquals(EquipmentSpec(head = WornItem("carved_pumpkin"), chest = WornItem("leather_chestplate", color = "#F9D71C")), args.equipment)
        assertEquals(0.0, args.dropChance)
        assertEquals(listOf(MobAttribute(MobAttributeKey.MOVEMENT_SPEED, 2.5)), args.attributes)
        assertEquals(ExplodeSpec(afterSeconds = 3, power = 2.0, hurts = true), args.explode)
        assertEquals(
            "attributes: one change per attribute (repeated: scale)",
            invalid(
                SpawnArgs.serializer(),
                """{"entity":"cow","near":"Bob","attributes":[{"attribute":"scale","multiply":2},{"attribute":"scale","multiply":3}]}""",
            ),
        )
        assertEquals("explode.after_seconds must be between 1 and 60 (got 90)", invalid(SpawnArgs.serializer(), """{"entity":"cow","near":"Bob","explode":{"after_seconds":90}}"""))
        assertEquals("launch must be between 0 and 3 (got 5)", invalid(SpawnArgs.serializer(), """{"entity":"cow","near":"Bob","launch":5}"""))
    }

    /**
     * After its dry checks (`check`, which needs no player), each tool refuses a player who is not online. The
     * check of `spawn` reads the game's registries (the creature, its equipment), which unit tests do not load:
     * it is covered in game (`WorldActionGameTests.theDryChecksTouchNothing`).
     */
    @Test
    fun `a player who is not online is refused before the world is touched`() = runTest {
        online("Alice")
        val expected = "Invalid arguments: Bob is not online; online: Alice"
        assertEquals(expected, LightningTool().call("""{"player":"Bob"}""").text)
        assertEquals(expected, ExplosionTool().call("""{"player":"Bob","power":1}""").text)
        assertEquals(expected, FireworksTool().call("""{"player":"Bob"}""").text)
        assertEquals(expected, ParticlesTool().call("""{"player":"Bob","particle":"heart"}""").text)
    }

    @Test
    fun `the caps of the chaos level come before anything else`() = runTest {
        online("Bob")
        guards.chaos = 3
        assertEquals(
            "Invalid arguments: at chaos 3 one call summons at most 6 creatures (asked 7)",
            SpawnTool().call("""{"entity":"zombie","near":"Bob","count":7}""").text,
        )
        assertEquals(
            "Invalid arguments: at chaos 3 an explosion has a power of at most 3 (asked 3.5)",
            SpawnTool().call("""{"entity":"cow","near":"Bob","explode":{"after_seconds":2,"power":3.5}}""").text,
        )
        assertEquals(
            "Invalid arguments: movement_speed multiplies by 0.25 to 3 (asked 4)",
            SpawnTool().call("""{"entity":"creeper","near":"Bob","attributes":[{"attribute":"movement_speed","multiply":4}]}""").text,
        )
        assertEquals(
            "Invalid arguments: name has nothing to show once formatting codes are removed; write plain text",
            SpawnTool().call("""{"entity":"cow","near":"Bob","name":"§6"}""").text,
        )
        assertEquals(
            "Invalid arguments: at chaos 3 an explosion has a power of at most 3 (asked 4)",
            ExplosionTool().call("""{"player":"Bob","power":4}""").text,
        )
        assertEquals(
            "Invalid arguments: at chaos 3 lightning does at most 3 hearts of damage (asked 4)",
            LightningTool().call("""{"player":"Bob","damage_hearts":4}""").text,
        )

        guards.chaos = 0
        val spawn = SpawnTool().call("""{"entity":"cow","near":"Bob"}""")
        assertEquals(ToolStatus.DENIED, spawn.status)
        assertTrue(spawn.text.startsWith("DENIED (${Rules.CHAOS_0}):"), spawn.text)
        assertTrue(ExplosionTool().call("""{"player":"Bob","power":1,"hurts":false}""").text.startsWith("DENIED (${Rules.CHAOS_0}):"))
        assertTrue(LightningTool().call("""{"player":"Bob","damage_hearts":1}""").text.startsWith("DENIED (${Rules.CHAOS_0}):"))
    }

    @Test
    fun `weather and time change at most once in their cooldown`() = runTest {
        val clock = TestClock()
        val limits = WorldLimits(clock)
        assertNull(limits.weather.check())
        limits.weather.record()
        clock.advance(Duration.ofMinutes(4))
        assertEquals("changed 4m ago; the next change is possible in 6m (one every 10m)", limits.weather.check())
        assertNull(limits.time.check(), "the time has its own cooldown")
        clock.advance(Duration.ofMinutes(6))
        assertNull(limits.weather.check())

        limits.time.record()
        clock.advance(Duration.ofMinutes(19))
        assertEquals("changed 19m ago; the next change is possible in 1m (one every 20m)", limits.time.check())

        // The tools refuse before touching the server (these services have none).
        val refused = SetTimeTool(limits).call("""{"time":"night"}""")
        assertEquals("DENIED (${WorldLimits.TIME_LIMIT}): the time changed 19m ago; the next change is possible in 1m (one every 20m)", refused.text)
        limits.weather.record()
        assertTrue(SetWeatherTool(limits).call("""{"weather":"rain"}""").text.startsWith("DENIED (${WorldLimits.WEATHER_LIMIT}): the weather changed 0s ago"))
    }

    @Test
    fun `there is no thunderstorm to set, only clear and rain`() {
        assertEquals("weather must be one of: clear, rain (got \"thunder\")", invalid(SetWeatherArgs.serializer(), """{"weather":"thunder"}"""))
        assertEquals(WeatherKind.RAIN, decode(SetWeatherArgs.serializer(), """{"weather":"rain"}""").weather)
        assertTrue("lightning" in SetWeatherTool(WorldLimits(TestClock())).description, "a storm is rain and the lightning tool")
    }

    @Test
    fun `one set of limits per server, shared by every conversation`() = runTest {
        val tools = worldActionTools(services)
        val again = worldActionTools(services)
        assertTrue(tools.zip(again).all { (a, b) -> a !== b }, "new tools for each conversation")
        // The weather changed in the chapter of `tools`: the next chapter's tool knows it (ToolLimits.world).
        services.toolLimits.world.weather.record()
        val weather = again.filterIsInstance<SetWeatherTool>().single()
        assertTrue(weather.call("""{"weather":"rain"}""").text.startsWith("DENIED (${WorldLimits.WEATHER_LIMIT}): the weather changed 0s ago"))
    }

    @Test
    fun `a player's name in a refusal is escaped`() = runTest {
        online("<engine>")
        assertEquals("Invalid arguments: ‹b› is not online; online: ‹engine›", ExplosionTool().call("""{"player":"<b>","power":1}""").text)
    }

    /**
     * The dry checks (`check`, M4 §4.3) of the tools a rule may use, without any player online: what the engine
     * answers when a rule's effect is created. `run` starts with them, so the Director gets the same answers.
     */
    @Test
    fun `the dry checks need no player and give the answers of run`() = runTest {
        guards.chaos = 3
        suspend fun <A : Any> NarratorTool<A>.dry(json: String): ToolResult? = check(context, decode(args, json))

        assertEquals(
            "Invalid arguments: at chaos 3 one call summons at most 6 creatures (asked 7)",
            SpawnTool().dry("""{"entity":"zombie","near":"Bob","count":7}""")?.text,
        )
        assertEquals(
            "Invalid arguments: movement_speed multiplies by 0.25 to 3 (asked 4)",
            SpawnTool().dry("""{"entity":"creeper","near":"Bob","attributes":[{"attribute":"movement_speed","multiply":4}]}""")?.text,
        )
        assertEquals(
            "Invalid arguments: at chaos 3 an explosion has a power of at most 3 (asked 3.5)",
            ExplosionTool().dry("""{"x":0,"y":64,"z":0,"power":3.5}""")?.text,
        )
        assertNull(ExplosionTool().dry("""{"x":0,"y":64,"z":0,"power":3}"""))
        assertEquals(
            "Invalid arguments: at chaos 3 lightning does at most 3 hearts of damage (asked 4)",
            LightningTool().dry("""{"player":"Bob","damage_hearts":4}""")?.text,
        )
        assertNull(LightningTool().dry("""{"player":"Bob","damage_hearts":3}"""))
        assertNull(LightningTool().dry("""{"x":0,"y":64,"z":0}"""), "a harmless bolt")
        assertNull(FireworksTool().dry("""{"x":0,"y":64,"z":0}"""), "the default colors")
        assertNull(ParticlesTool().dry("""{"x":0,"y":64,"z":0,"particle":"heart"}"""), "nothing to check: decoding said it all")

        guards.chaos = 0
        assertTrue(SpawnTool().dry("""{"entity":"cow","near":"Bob"}""")!!.text.startsWith("DENIED (${Rules.CHAOS_0}):"))
        assertTrue(ExplosionTool().dry("""{"x":0,"y":64,"z":0,"power":1,"hurts":false}""")!!.text.startsWith("DENIED (${Rules.CHAOS_0}):"))
        assertTrue(LightningTool().dry("""{"player":"Bob","damage_hearts":1}""")!!.text.startsWith("DENIED (${Rules.CHAOS_0}):"))
        assertNull(LightningTool().dry("""{"player":"Bob"}"""), "a harmless bolt, even at chaos 0")
    }
}
