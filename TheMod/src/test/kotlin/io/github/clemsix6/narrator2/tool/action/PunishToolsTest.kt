package io.github.clemsix6.narrator2.tool.action

import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.guard.Rules
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.mechanics.PunishmentId
import io.github.clemsix6.narrator2.testing.FakeGuards
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.testing.testSettings
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolArgs
import io.github.clemsix6.narrator2.tool.ToolCatalog
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolEnv
import io.github.clemsix6.narrator2.tool.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What `punish` decides without a world (M4 §6.3, §12): its dry checks (chaos 0, the severity the chaos level
 * allows, a reason and a name to read), the player online, and its place among the tools. Its punishments run on
 * the server thread and are covered in game (`PunishGameTests`).
 */
class PunishToolsTest {
    private fun context(chaos: Int): ToolContext {
        val services = TestServices(guards = FakeGuards(chaos = chaos), settings = testSettings(NarratorConfig(chaos = chaos)))
        return ToolContext(services, Caller(Role.DIRECTOR, "director-1", turnId = 3, taskId = null), services.clock, 10_000)
    }

    private fun args(json: String): PunishArgs {
        val decoded = ToolArgs.decode(PunishArgs.serializer(), json)
        assertIs<ToolArgs.Decoded.Ok<PunishArgs>>(decoded, "arguments: $decoded")
        return decoded.args
    }

    @Test
    fun `punish is the family's one tool, a world action shown only with the mechanics engine`() {
        val services = TestServices()
        val family = punishTools(services)
        assertEquals(listOf("punish"), family.map { it.name })
        assertEquals(ToolEffect.WORLD, family.single().effect)
        val wired = ToolEnv(journalAvailable = true, workerRoles = setOf(Role.ANALYST), memoryAvailable = true)
        assertTrue("punish" !in ToolCatalog.toolSetFor(Role.DIRECTOR, wired, services).names, "hidden without the engine")
        assertTrue("punish" in ToolCatalog.toolSetFor(Role.DIRECTOR, wired.copy(mechanicsAvailable = true), services).names, "the Director's with it")
        assertTrue("punish" !in ToolCatalog.toolSetFor(Role.ANALYST, wired.copy(mechanicsAvailable = true), services).names, "never the Analyst's")
    }

    @Test
    fun `its description is static and names every punishment of the catalogue`() {
        val description = PunishTool().description
        assertTrue(PunishmentId.entries.all { it.id in description }, description)
        assertTrue(description.none { it.isDigit() && it !in "15" }, "no number that depends on the chaos level: $description")
    }

    @Test
    fun `the arguments decode as the model writes them`() {
        val decoded = args("""{"player":"Bartok","punishment":"moon_gravity","severity":2,"reason":"a traité le Narrateur de dieu de pacotille"}""")
        assertEquals(PunishmentId.MOON_GRAVITY, decoded.punishment)
        val problem = (ToolArgs.decode(PunishArgs.serializer(), """{"player":"Bartok","punishment":"tiny","severity":6,"reason":"x"}""") as ToolArgs.Decoded.Invalid).problem
        assertEquals("severity must be between 1 and 5 (got 6)", problem)
    }

    @Test
    fun `a severity beyond the chaos level is refused, with the cap`() = runBlocking<Unit> {
        val tool = PunishTool()
        val five = args("""{"player":"Bartok","punishment":"tiny","severity":5,"reason":"insolent"}""")
        assertEquals(ToolResult.invalid("at chaos 3 a punishment has a severity of at most 4 (asked 5)"), tool.check(context(3), five))
        assertEquals(ToolResult.invalid("at chaos 1 a punishment has a severity of at most 2 (asked 5)"), tool.check(context(1), five))
        assertNull(tool.check(context(4), five), "chaos 4 allows the full wrath")
        assertEquals(ToolResult.invalid("at chaos 3 a punishment has a severity of at most 4 (asked 5)"), tool.run(context(3), five), "run starts with the dry checks")
    }

    @Test
    fun `at chaos 0 nobody is punished`() = runBlocking<Unit> {
        val result = PunishTool().check(context(0), args("""{"player":"Bartok","punishment":"cage","severity":1,"reason":"insolent"}"""))
        assertEquals(ToolResult.denied(Rules.CHAOS_0, "the chaos level is 0: no hostile action against any player."), result)
    }

    @Test
    fun `a reason and a name for the guests must have something to read`() = runBlocking<Unit> {
        val tool = PunishTool()
        assertEquals(
            ToolResult.invalid("reason has nothing to read once cleaned: say why, one factual line"),
            tool.check(context(3), args("""{"player":"Bartok","punishment":"tiny","severity":1,"reason":"§c  "}""")),
        )
        assertEquals(
            ToolResult.invalid("name has nothing to show once formatting codes are removed; write plain text"),
            tool.check(context(3), args("""{"player":"Bartok","punishment":"guests","severity":1,"reason":"insolent","name":"§a§l"}""")),
        )
        assertNull(
            tool.check(context(3), args("""{"player":"Bartok","punishment":"tiny","severity":1,"reason":"insolent","name":"§a§l"}""")),
            "a name is only read for the guests",
        )
    }

    @Test
    fun `a player who is not online is said, with who is`() = runBlocking<Unit> {
        val result = PunishTool().run(context(3), args("""{"player":"Bartok","punishment":"smite","severity":2,"reason":"insolent"}"""))
        assertEquals(ToolResult.invalid("Bartok is not online; online: nobody"), result)
    }
}
