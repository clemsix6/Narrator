package io.github.clemsix6.narrator2.tool.mechanics

import io.github.clemsix6.narrator2.mechanics.MechanicRejected
import io.github.clemsix6.narrator2.mechanics.challenge.Duels
import io.github.clemsix6.narrator2.testing.FakeMechanics
import io.github.clemsix6.narrator2.testing.MechanicsSamples
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.tool.MechanicsTool
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolEnv
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `propose_duel` without a world (M4 §5.5, §12): a tool of the engine, which hands the duel to the engine and
 * answers with what the engine did or refused. The duel itself (invitations, start, nobody dies) runs in game:
 * `DuelGameTests`.
 */
class DuelToolsTest {
    private val mechanics = FakeMechanics()
    private val services = TestServices(mechanics = mechanics)
    private val context = ToolContext(services, MechanicsSamples.director, services.clock, 15_000)
    private val tool = duelTools(services).single() as ProposeDuelTool

    private fun run(): ToolResult = runBlocking { tool.run(context, MechanicsSamples.duel) }

    @Test
    fun `propose_duel is a tool of the engine that changes the world, never forcing anyone`() {
        assertIs<MechanicsTool<*>>(tool)
        assertEquals("propose_duel", tool.name)
        assertEquals(ToolEffect.WORLD, tool.effect)
        assertTrue(!tool.isAvailable(ToolEnv(journalAvailable = true, workerRoles = emptySet())), "hidden without the engine")
        assertTrue(tool.isAvailable(ToolEnv(journalAvailable = true, workerRoles = emptySet(), mechanicsAvailable = true)))
        assertTrue("nobody is ever made to fight" in tool.description && "Nobody dies in a duel" in tool.description, tool.description)
        assertTrue("minute" !in tool.description, "a static description, without the invitation's time (configuration): ${tool.description}")
    }

    @Test
    fun `the duel goes to the engine with the Director's caller, and the answer says what follows`() {
        val result = run()
        assertEquals(ToolStatus.OK, result.status, result.text)
        assertEquals(listOf(MechanicsSamples.duel to MechanicsSamples.director), mechanics.challenges.proposed.toList())
        assertEquals(
            "ok: duel C-1 proposed to Bartok and Mimosa (« Duel au soleil », in the arena x 112 y 70 z -40 · reward 1x golden_apple · bets); " +
                "invitations end 19:32 (in 2m); a DUEL event tells you whether it starts",
            result.text,
        )
    }

    @Test
    fun `the engine's refusals come back as they are`() {
        mechanics.challenges.rejection = MechanicRejected(Duels.PVP_OFF, "PvP is off in this world (game rule pvp): no duel")
        assertEquals("DENIED (pvp_off): PvP is off in this world (game rule pvp): no duel", run().text)
        mechanics.challenges.rejection = MechanicRejected(null, "Mimosa is not online; online: Bartok")
        assertEquals("Invalid arguments: Mimosa is not online; online: Bartok", run().text)
    }

    @Test
    fun `without the engine it fails and asks nothing`() {
        mechanics.available = false
        mechanics.reason = "not wired yet"
        assertEquals("FAILED: the mechanics are unavailable: not wired yet", run().text)
        assertTrue(mechanics.challenges.proposed.isEmpty())
    }
}
