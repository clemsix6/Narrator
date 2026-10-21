package io.github.clemsix6.narrator2.tool.speech

import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.config.StyleConfig
import io.github.clemsix6.narrator2.event.NarratorSpoke
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.guard.Verdict
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.observer.LivePlayer
import io.github.clemsix6.narrator2.style.AtRest
import io.github.clemsix6.narrator2.style.RestingOpener
import io.github.clemsix6.narrator2.testing.FakeStyle
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.testing.testSettings
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.trace.DecisionRecord
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `say` refuses a line of the Director that opens like too many of its latest (M6 §3.5): `Invalid arguments` with every
 * opener at rest, nothing said nor counted, a decision; once a turn at most; never for the engine, a rule or a module;
 * not with `style.refuseRestingOpeners` off. The unit tests have no Minecraft server: a line that passes the check fails
 * on the server, after the limiter counted it.
 */
class SayRestingOpenerTest {
    private val style = FakeStyle()
    private val services = TestServices(style = style)
    private val limiter = SpeechLimiter(services.clock)
    private val spoken = CopyOnWriteArrayList<NarratorSpoke>()

    private val vingt = "Vingt secondes ! Bartok remporte encore le Grand Prix."
    private val secondes = RestingOpener("Vingt secondes", 5, listOf("Douze secondes", "Onze secondes", "Seize secondes"))
    private val bonsoir = RestingOpener("Bonsoir, mortels", 4, listOf("Bonsoir, Mimosa"))

    init {
        services.players.put(
            LivePlayer(
                ref = PlayerRef(UUID.randomUUID(), "Bartok"), sessionId = 1, joinedAt = 0, isOp = false, gameMode = "survival",
                pos = Pos("minecraft:overworld", 0, 64, 0), biome = null, structure = null, underground = false,
                health = 20f, maxHealth = 20f, food = 20, vehicle = null, lastDeathAt = null, lastMoveAt = 0, idle = false,
            ),
        )
        services.bus.subscribeSync("test", "test", { it is NarratorSpoke }) { spoken += it.event as NarratorSpoke }
        style.openers[vingt] = secondes
        style.atRest = AtRest(listOf(secondes, bonsoir), emptyList(), emptyList())
    }

    private fun director(turn: Long) = Caller(Role.DIRECTOR, "director-c7", turnId = turn, taskId = null)

    private suspend fun SayTool.say(caller: Caller, text: String): ToolResult = run(ToolContext(services, caller, services.clock, 5_000), SayArgs(text))

    /** The line got past the check: the limiter counted it, then the unit test's missing server stopped it. */
    private suspend fun SayTool.passes(caller: Caller, text: String) {
        val error = assertFailsWith<IllegalStateException> { say(caller, text) }
        assertEquals("no Minecraft server in unit tests", error.message)
    }

    /** How many more lines the limiter lets [caller] say in its turn. */
    private fun linesLeft(caller: Caller): Int = generateSequence { limiter.line(caller) }.takeWhile { it == Verdict.Allowed }.take(10).count()

    @Test
    fun `a line of the Director that opens at rest is refused with every opener at rest, nothing said nor counted`() = runTest {
        val result = SayTool(limiter).say(director(3), vingt)
        assertEquals(ToolStatus.INVALID, result.status)
        assertEquals(
            "Invalid arguments: this line opens like 5 of your latest lines (« Vingt secondes », « Douze secondes », « Onze secondes »): " +
                "open another way; the other openers at rest: « Bonsoir, mortels » ×4",
            result.text,
        )
        assertEquals(emptyList(), spoken, "nothing said")
        assertEquals(SpeechLimiter.LINES_PER_TURN, linesLeft(director(3)), "nothing counted")

        val decision = services.traces.decisions.single()
        assertEquals(DecisionRecord.Kind.STYLE to "opener_refused", decision.kind to decision.decision)
        assertEquals(listOf<Any?>(3L, "director-c7", "« Vingt secondes »"), listOf(decision.turnId, decision.conversationId, decision.subject))
        assertEquals(
            """{"opener":"Vingt secondes","count":5,"text":"$vingt","at_rest":["Bonsoir, mortels"]}""",
            decision.detail.toString(),
        )
    }

    @Test
    fun `once a turn, the next line of the turn passes and the next turn is checked again`() = runTest {
        val say = SayTool(limiter)
        assertEquals(ToolStatus.INVALID, say.say(director(3), vingt).status)
        say.passes(director(3), vingt)
        assertEquals(SpeechLimiter.LINES_PER_TURN - 1, linesLeft(director(3)), "the second line was counted")
        assertEquals(ToolStatus.INVALID, say.say(director(4), vingt).status, "a new turn")
        say.passes(director(4), "Une autre amorce, libre celle-là, pour changer.")
        say.passes(director(4), vingt)
        assertEquals(2, services.traces.decisions.size)
    }

    @Test
    fun `the engine's acts, a rule's or a module's lines and the workers are never checked`() = runTest {
        val say = SayTool(limiter)
        say.passes(Caller(Role.DIRECTOR, "mechanics:C-60", turnId = null, taskId = null, firingId = 812), vingt)
        say.passes(Caller(Role.DIRECTOR, "mechanics:module", turnId = null, taskId = null), vingt)
        say.passes(Caller(Role.ANALYST, "analyst-1", turnId = 9, taskId = 4), vingt)
        assertEquals(emptyList(), services.traces.decisions)
    }

    @Test
    fun `a line that opens freely passes, and the check can be switched off`() = runTest {
        SayTool(limiter).passes(director(5), "Bonsoir tout le monde, le Narrateur est de retour.")
        val off = TestServices(style = style, settings = testSettings(NarratorConfig(style = StyleConfig(refuseRestingOpeners = false))))
        off.players.put(services.players.online.single())
        val error = assertFailsWith<IllegalStateException> {
            SayTool(SpeechLimiter(off.clock)).run(ToolContext(off, director(6), off.clock, 5_000), SayArgs(vingt))
        }
        assertEquals("no Minecraft server in unit tests", error.message)
        assertTrue(off.traces.decisions.isEmpty())
    }

    @Test
    fun `the refusal cites five openers at rest at most, the one refused first`() = runTest {
        val others = (1..6).map { RestingOpener("Amorce $it", 10 - it, emptyList()) }
        style.atRest = AtRest(others.take(2) + secondes + others.drop(2), emptyList(), emptyList())
        val text = SayTool(limiter).say(director(7), vingt).text
        assertTrue(text.endsWith("open another way; the other openers at rest: « Amorce 1 » ×9, « Amorce 2 » ×8, « Amorce 3 » ×7, « Amorce 4 » ×6"), text)
    }
}
