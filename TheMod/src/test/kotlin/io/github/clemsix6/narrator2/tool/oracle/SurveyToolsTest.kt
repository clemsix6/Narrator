package io.github.clemsix6.narrator2.tool.oracle

import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.oracle.SurveyGrid
import io.github.clemsix6.narrator2.oracle.SurveySpot
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolArgs
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/** `survey` without a game server: its arguments, a player offline, and its answer's text. The search is `SurveyTest`; the world, `SurveyGameTests`. */
class SurveyToolsTest {
    private fun problem(input: String): String = when (val decoded = ToolArgs.decode(SurveyArgs.serializer(), input)) {
        is ToolArgs.Decoded.Ok -> fail("expected invalid arguments, got ${decoded.args}")
        is ToolArgs.Decoded.Invalid -> decoded.problem
    }

    /** An empty grid that says how many of its [chunks] were [loaded]: the text reads only those two. */
    private fun grid(loaded: Int, chunks: Int = 25) = SurveyGrid(0, 0, 1, IntArray(1), ByteArray(1), IntArray(1), emptyList(), chunks, loaded)

    @Test
    fun `survey is a read, centred on a player or on a point, within its bounds`() {
        val tool = surveyTools(TestServices()).single()
        assertEquals("survey", tool.name)
        assertEquals(ToolEffect.READ, tool.effect)
        assertEquals("give either player, or x, y and z", problem("{}"))
        assertEquals("give x, y and z together", problem("""{"x": 10, "z": 20}"""))
        assertEquals("give either player, or x, y and z", problem("""{"player": "Bartok", "x": 1, "y": 2, "z": 3}"""))
        assertEquals("radius must be between 8 and 64 (got 100)", problem("""{"player": "Bartok", "radius": 100}"""))
        assertEquals("size must be between 5 and 21 (got 3)", problem("""{"player": "Bartok", "size": 3}"""))
        val ok = ToolArgs.decode(SurveyArgs.serializer(), """{"x": 100, "y": 64, "z": -30, "dimension": "the_end"}""")
        assertEquals(SurveyArgs(x = 100, y = 64, z = -30, dimension = "the_end", radius = 32, size = 9), (ok as ToolArgs.Decoded.Ok).args)
    }

    @Test
    fun `a player offline is refused before the world is read`() {
        val services = TestServices()
        val ctx = ToolContext(services, Caller(Role.SCOUT, "scout-test", turnId = null, taskId = 3), services.clock, 5_000)
        val result = runBlocking { SurveyTool().run(ctx, SurveyArgs(player = "Kelvin")) }
        assertEquals(ToolStatus.INVALID, result.status)
        assertEquals("Invalid arguments: Kelvin is not online; online: nobody", result.text)
    }

    @Test
    fun `the answer gives each spot, nearest first, from a player`() {
        val centre = SurveyCentre(100, 64, -30, "overworld", "Bartok")
        val spots = listOf(
            SurveySpot(100, 65, -30, 0, true, "grass_block"),
            SurveySpot(112, 70, -40, 0, true, "grass_block"),
            SurveySpot(131, 71, -55, 1, false, "sand"),
        )
        assertEquals(
            """
            survey around Bartok (overworld 100 64 -30), radius 32, square 9: 3 spots
            1. 100 65 -30 · flat 9x9 (±0) · open sky · grass_block · right where Bartok stands
            2. 112 70 -40 · flat 9x9 (±0) · open sky · grass_block · 16 blocks NE of Bartok
            3. 131 71 -55 · flat 9x9 (±1) · under leaves · sand · 40 blocks NE of Bartok
            """.trimIndent(),
            surveyText(centre, 32, 9, grid(loaded = 25), spots),
        )
    }

    @Test
    fun `the answer from a point says which chunks were not read, and when nothing fits`() {
        val point = SurveyCentre(0, 70, 0, "the_end", null)
        assertEquals(
            """
            survey around the_end 0 70 0, radius 16, square 11: 1 spot (20 of 25 chunks loaded; the others were not read)
            1. 0 71 0 · flat 11x11 (±0) · open sky · end_stone · at the point
            """.trimIndent(),
            surveyText(point, 16, 11, grid(loaded = 20), listOf(SurveySpot(0, 71, 0, 0, true, "end_stone"))),
        )
        assertEquals(
            "survey around the_end 0 70 0, radius 16, square 11: 1 spot\n1. -8 60 12 · flat 11x11 (±1) · open sky · end_stone · 14 blocks SW of the point",
            surveyText(point, 16, 11, grid(loaded = 25), listOf(SurveySpot(-8, 60, 12, 1, true, "end_stone"))),
        )
        assertEquals(
            "survey around the_end 0 70 0, radius 16, square 11: no flat open square (3 of 25 chunks loaded; the others were not read); " +
                "the ground there is uneven, wet, dangerous or built on: try a smaller square, or elsewhere",
            surveyText(point, 16, 11, grid(loaded = 3), emptyList()),
        )
    }
}
