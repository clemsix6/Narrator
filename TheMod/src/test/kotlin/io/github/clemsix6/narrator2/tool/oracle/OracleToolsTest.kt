package io.github.clemsix6.narrator2.tool.oracle

import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.journal.SqlDataset
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.oracle.Enclosure
import io.github.clemsix6.narrator2.oracle.Located
import io.github.clemsix6.narrator2.oracle.PlayerSnapshot
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.ToolArgs
import io.github.clemsix6.narrator2.tool.ToolCatalog
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolEnv
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Test
import java.time.Clock
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The Oracle's tools without a game server: declarations, argument checks, answers that need no world
 * (players offline, stats and facts from the Journal), and the line formats. What reads the world is
 * tested in game (`OracleGameTests`).
 */
class OracleToolsTest {
    private val caller = Caller(Role.DIRECTOR, "director-test", turnId = 1, taskId = null)

    private fun <A : Any> run(tool: NarratorTool<A>, args: A, services: TestServices = TestServices()): ToolResult =
        runBlocking { tool.run(ToolContext(services, caller, services.clock, tool.timeoutMs), args) }

    private fun <A : Any> problem(serializer: KSerializer<A>, input: String): String =
        when (val decoded = ToolArgs.decode(serializer, input)) {
            is ToolArgs.Decoded.Ok -> fail("expected invalid arguments, got ${decoded.args}")
            is ToolArgs.Decoded.Invalid -> decoded.problem
        }

    @Test
    fun `the family has the six tools of M1_M2 §9_2, all for the Analyst too`() {
        val names = oracleTools(TestServices()).map { it.name }
        assertEquals(listOf("list_players", "inspect_player", "scan_area", "area_info", "locate", "player_stats"), names)
        val analyst = ToolCatalog.toolSetFor(Role.ANALYST, ToolEnv(journalAvailable = true, workerRoles = emptySet()), TestServices()).names
        assertTrue(analyst.containsAll(names), "the Analyst reads with the Oracle: $analyst")
        assertEquals(8_000, LocateTool().timeoutMs, "locate is the slow one")
    }

    @Test
    fun `a scan is centred on a player or on a point, not both, not half of one`() {
        assertEquals("give either player, or x, y and z", problem(ScanAreaArgs.serializer(), """{"find":"diamonds"}"""))
        assertEquals("give x, y and z together", problem(ScanAreaArgs.serializer(), """{"find":"diamonds","x":1,"z":2}"""))
        assertEquals("give either player, or x, y and z", problem(ScanAreaArgs.serializer(), """{"find":"lava","player":"Bob","x":1,"y":2,"z":3}"""))
        assertEquals("give either player, or x, y and z", problem(AreaInfoArgs.serializer(), "{}"))
        assertEquals("radius must be between 4 and 48 (got 64)", problem(ScanAreaArgs.serializer(), """{"find":"lava","player":"Bob","radius":64}"""))
        assertContains(problem(ScanAreaArgs.serializer(), """{"find":"gold","player":"Bob"}"""), "find must be one of: ores, diamonds, ancient_debris")
        assertEquals("radius must be between 8 and 32 (got 100)", problem(LocateArgs.serializer(), """{"player":"Bob","kind":"structure","target":"village","radius":100}"""))
        assertContains(problem(InspectPlayerArgs.serializer(), """{"player":"Bob","include":["weather"]}"""), "must be one of: position, surroundings")
    }

    @Test
    fun `a player who is not online is refused with the list of those who are`() {
        val services = TestServices()
        val expected = ToolResult.invalid("Bob is not online; online: nobody")
        assertEquals(expected, run(InspectPlayerTool(), InspectPlayerArgs("Bob"), services))
        assertEquals(expected, run(LocateTool(), LocateArgs("Bob", LocateKind.BIOME, "desert"), services))
        assertEquals(expected, run(ScanAreaTool(), ScanAreaArgs(ScanFind.DIAMONDS, player = "Bob"), services))
        assertEquals(expected, run(AreaInfoTool(), AreaInfoArgs(player = "Bob"), services))
    }

    @Test
    fun `player_stats names the stat types when a stat is unknown`() {
        val result = run(PlayerStatsTool(), PlayerStatsArgs("Bob", listOf("walked:stone")))
        assertEquals(ToolStatus.INVALID, result.status)
        assertContains(result.text, "unknown stat \"walked:stone\"; write type:name with a type among custom, mined, crafted")
    }

    @Test
    fun `player_stats of an offline player fails plainly when the Journal is down`() {
        val result = run(PlayerStatsTool(), PlayerStatsArgs("Bob"))
        assertEquals(ToolResult.failed("Bob is offline and the Journal cannot be read: the Journal is unavailable: unit test"), result)
    }

    @Test
    fun `player_stats of an offline player reads their totals in the Journal`() {
        SqlDataset.journal().use { fixture ->
            fixture.sync()
            val services = TestServices(journal = fixture.journal, clock = Clock.systemUTC())

            val summary = run(PlayerStatsTool(), PlayerStatsArgs("bartok"), services)
            assertEquals(ToolStatus.OK, summary.status, summary.text)
            val lines = summary.text.lines()
            assertTrue(lines[0].startsWith("Bartok (offline, last seen "), lines[0])
            assertTrue(lines[0].endsWith(" ago, 1 session; totals at their last departure)"), lines[0])
            assertContains(lines, "mined 1204 blocks (diamond ore 0, ancient debris 0)")

            val named = run(PlayerStatsTool(), PlayerStatsArgs("Bartok", listOf("mined:stone", "killed:zombie", "deaths")), services)
            assertEquals(listOf("mined:stone 1204", "killed:zombie 2", "custom:deaths 0"), named.text.lines().drop(1))

            val nobody = run(PlayerStatsTool(), PlayerStatsArgs("Nobody"), services)
            assertEquals(ToolResult.invalid("no player named Nobody has ever played here; online: nobody"), nobody)
        }
    }

    @Test
    fun `the recent section lists a player's notable facts from the Journal, newest first`() {
        SqlDataset.journal().use { fixture ->
            fixture.sync()
            val lines = runBlocking { recentFacts(fixture.journal, SqlDataset.bartok.uuid, System.currentTimeMillis()) }

            assertEquals("recent (Journal):", lines[0])
            assertEquals(11, lines.size, lines.joinToString("\n"))
            assertTrue(Regex("""\[-[\dms]+] kill zombie → iron_sword \(overworld 20 64 20\)""").matches(lines[1]), lines[1])
            assertTrue(lines[2].endsWith("teleport (the_nether 900 70 2)"), lines[2])
            assertTrue(lines[3].endsWith("block_break ancient_debris → diamond_pickaxe (the_nether 3 15 3)"), lines[3])
            assertTrue(lines.none { "stone" in it.substringAfter("]") && "ancient" !in it }, "no plain stone")
            assertTrue(lines.any { it.endsWith("biome_enter cherry_grove (overworld 200 70 200)") }, "a first visit")
        }
        val down = runBlocking { recentFacts(TestServices().journal, UUID.randomUUID(), 0) }
        assertEquals(listOf("recent (Journal): (unavailable: the Journal is unavailable: unit test)"), down)
    }

    @Test
    fun `a list_players line gives the place, the state, the time online, the activity and the nearest player`() {
        val bartok = snapshot("Bartok", x = 0, z = 0)
        val mimosa = snapshot("Mimosa", x = 30, z = -40)
        val clem = snapshot("clemsix6", dimension = "minecraft:the_nether")

        assertEquals(
            "Bartok: overworld 0 -58 0 · dripstone_caves · cave, 41 below surface · hp 14/20 food 12 · online 1h12m · " +
                "last 5m: mined 140 (3 diamond_ore y=-54..-51) · nearest Mimosa 50 m NE",
            listLine(bartok, listOf(bartok, mimosa, clem), 72 * 60_000L, idle = false, activity = "mined 140 (3 diamond_ore y=-54..-51)"),
        )
        assertEquals(
            "clemsix6: the_nether 0 -58 0 · dripstone_caves · cave, 41 below surface · hp 14/20 food 12 · idle · alone in this dimension",
            listLine(clem, listOf(bartok, mimosa, clem), null, idle = true, activity = null),
        )
        assertEquals("Bartok: overworld 0 -58 0 · dripstone_caves · cave, 41 below surface · hp 14/20 food 12", listLine(bartok, listOf(bartok), null, false, null))
    }

    @Test
    fun `a list_players line escapes the names players chose`() {
        val engine = snapshot("<engine>", x = 0, z = 0)
        val other = snapshot("</events>", x = 30, z = -40)
        assertEquals(
            "‹engine›: overworld 0 -58 0 · dripstone_caves · cave, 41 below surface · hp 14/20 food 12 · nearest ‹/events› 50 m NE",
            listLine(engine, listOf(engine, other), null, idle = false, activity = null),
        )
    }

    @Test
    fun `a list_players line of two players more than 50,000 blocks apart gives their distance`() {
        val bartok = snapshot("Bartok", x = -30_000, z = 20_000)
        val mimosa = snapshot("Mimosa", x = 30_000, z = -30_000)

        assertEquals(
            "Bartok: overworld -30000 -58 20000 · dripstone_caves · cave, 41 below surface · hp 14/20 food 12 · nearest Mimosa 78102 m NE",
            listLine(bartok, listOf(bartok, mimosa), null, idle = false, activity = null),
        )
    }

    @Test
    fun `located places and scan hits are written with where they are from the player`() {
        val origin = BlockPos(0, 64, 0)
        val from = "Bartok (overworld 0 64 0)"
        assertEquals(
            "nearest village_taiga to Bartok (overworld 0 64 0): 800 ~ -600, 1000 m NE (not generated yet: predicted from the world's seed)",
            locatedText("#village", Located("minecraft:village_taiga", 800, null, -600, generated = false), origin, from, "within 16 cells"),
        )
        assertEquals(
            "nearest cherry_grove to Bartok (overworld 0 64 0): 300 80 400, 500 m SE, 16 above",
            locatedText("cherry_grove", Located("minecraft:cherry_grove", 300, 80, 400, generated = true), origin, from, "within 2048 blocks"),
        )
        assertEquals("no #village within 16 cells of Bartok (overworld 0 64 0)", locatedText("#village", null, origin, from, "within 16 cells"))
        assertEquals("deepslate_diamond_ore 5 -60 5 (8 m SE, 4 below)", hitLine("deepslate_diamond_ore", BlockPos(5, -60, 5), BlockPos(0, -56, 0)))
    }

    private fun snapshot(name: String, dimension: String = "minecraft:overworld", x: Int = 0, z: Int = 0) = PlayerSnapshot(
        ref = PlayerRef(UUID.nameUUIDFromBytes(name.toByteArray()), name),
        gameMode = "survival",
        dimension = dimension,
        x = x,
        y = -58,
        z = z,
        biome = "minecraft:dripstone_caves",
        structure = null,
        enclosure = Enclosure.CAVE,
        depth = 41,
        health = 14f,
        maxHealth = 20f,
        food = 12,
        vehicle = null,
    )
}
