package io.github.clemsix6.narrator2.tool.action

import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.testing.FakeMechanics
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.MechanicsTool
import io.github.clemsix6.narrator2.tool.ToolArgs
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolEnv
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What `favor` decides without a world (M4 §7.1, §7.2): it is a tool of the engine, its dry checks of an offering
 * (its id, the note required and written for the player's history), and its refusals before the server thread.
 * Granting a favor and taking an offering run on the server thread: `FavorGameTests`.
 */
class FavorToolsTest {
    private val mechanics = FakeMechanics()
    private val services = TestServices(mechanics = mechanics)
    private val context = ToolContext(services, Caller(Role.DIRECTOR, "director-1", turnId = 3, taskId = null), services.clock, 5_000)
    private val tool = favorTools(services).single() as FavorTool

    private fun check(json: String): ToolResult? = runBlocking { tool.check(context, decode(json)) }

    private fun run(json: String): ToolResult = runBlocking { tool.run(context, decode(json)) }

    private fun decode(json: String): FavorArgs {
        val decoded = ToolArgs.decode(FavorArgs.serializer(), json)
        assertIs<ToolArgs.Decoded.Ok<FavorArgs>>(decoded, "arguments: $decoded")
        return decoded.args
    }

    @Test
    fun `favor is a tool of the engine that changes the world`() {
        assertIs<MechanicsTool<*>>(tool)
        assertEquals("favor", tool.name)
        assertEquals(ToolEffect.WORLD, tool.effect)
        assertTrue(!tool.isAvailable(ToolEnv(journalAvailable = true, workerRoles = emptySet())), "hidden without the engine")
        assertTrue(tool.isAvailable(ToolEnv(journalAvailable = true, workerRoles = emptySet(), mechanicsAvailable = true)))
        assertTrue("its items become yours for good only if the favor is granted" in tool.description)
    }

    @Test
    fun `the dry checks pass a favor without an offering, and an offering with its note`() {
        assertNull(check("""{"player": "Bartok", "favor": "pardon"}"""))
        assertNull(check("""{"player": "Bartok", "favor": "grace", "minutes": 30, "note": "unused without an offering"}"""))
        assertNull(check("""{"player": "Mimosa", "favor": "joker", "offering": "O-812", "note": "Mimosa a offert dix diamants au Narrateur contre un joker."}"""))
        assertNull(check("""{"player": "Mimosa", "favor": "joker", "offering": "812", "note": "Mimosa a offert dix diamants."}"""), "the id alone")
    }

    @Test
    fun `an offering needs its id and a factual note in the third person`() {
        assertEquals(
            "Invalid arguments: offering is the id of an OFFERING event, like O-812 (got \"diamonds\")",
            check("""{"player": "Mimosa", "favor": "joker", "offering": "diamonds", "note": "Mimosa a offert des diamants."}""")?.text,
        )
        assertEquals(
            "Invalid arguments: note is required with offering: what it was and what you made of it, one factual line in French, third person",
            check("""{"player": "Mimosa", "favor": "joker", "offering": "O-812"}""")?.text,
        )
        assertEquals(ToolStatus.INVALID, check("""{"player": "Mimosa", "favor": "joker", "offering": "O-812", "note": "   "}""")?.status, "a blank note")
        val voice = check("""{"player": "Mimosa", "favor": "grace", "offering": "O-812", "note": "Tu m'as offert dix diamants."}""")
        assertEquals(ToolStatus.INVALID, voice?.status)
        assertTrue(voice!!.text.startsWith("Invalid arguments: note must be factual and in the third person"), voice.text)
        assertNull(
            check("""{"player": "Moi", "favor": "grace", "offering": "O-812", "note": "Moi a offert dix diamants au Narrateur."}"""),
            "a player called Moi is no voice",
        )
    }

    @Test
    fun `favor refuses before the server thread, for its arguments, the engine down or a player offline`() {
        assertEquals(ToolStatus.INVALID, run("""{"player": "Mimosa", "favor": "joker", "offering": "O-812"}""").status, "run starts with the dry checks")
        assertEquals("Invalid arguments: Mimosa is not online; online: nobody", run("""{"player": "Mimosa", "favor": "pardon"}""").text)
        mechanics.available = false
        mechanics.reason = "not wired yet"
        assertEquals("FAILED: the mechanics are unavailable: not wired yet", run("""{"player": "Mimosa", "favor": "pardon"}""").text)
    }
}
