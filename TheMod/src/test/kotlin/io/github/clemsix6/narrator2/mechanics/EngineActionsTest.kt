package io.github.clemsix6.narrator2.mechanics

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.runtime.Phase
import io.github.clemsix6.narrator2.testing.MechanicsSamples
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.MaxLen
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.NoArgs
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolRunner
import io.github.clemsix6.narrator2.tool.ToolSet
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.tool.of
import io.github.clemsix6.narrator2.tool.toolUse
import io.github.clemsix6.narrator2.trace.DecisionRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The acts of the mechanics engine (M4 §4.3): a real tool, run through the Director's own path, as the act. */
class EngineActionsTest {
    private val services = TestServices()
    private val bob = PlayerRef(UUID.fromString("00000000-0000-0000-0000-000000000b0b"), "Bob")
    private val ran = CopyOnWriteArrayList<Pair<Caller, String>>()
    private val c60 = MechanicsSamples.canon(60)

    @Serializable
    data class BlastArgs(@MaxLen(16) val player: String, val power: Double = 1.0)

    /** A tool with dry checks: a power above 3 is denied, the player `nobody` is not online. */
    private inner class BlastTool : NarratorTool<BlastArgs>("blast", "A test explosion.", BlastArgs.serializer(), ToolEffect.WORLD) {
        override suspend fun check(ctx: ToolContext, args: BlastArgs): ToolResult? =
            if (args.power > 3.0) ToolResult.denied("chaos_cap", "power ${args.power} is above 3") else null

        override suspend fun run(ctx: ToolContext, args: BlastArgs): ToolResult {
            check(ctx, args)?.let { return it }
            if (args.player == "nobody") return ToolResult.invalid("nobody is not online")
            ran += ctx.caller to args.player
            return ToolResult.ok("ok: blast of power ${args.power} on ${args.player}\nsecond line", listOf(bob))
        }
    }

    private val actions = EngineActions(services, listOf(BlastTool()))

    private fun args(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    private val decisions: List<DecisionRecord> get() = services.traces.decisions

    @Test
    fun `an act is a mechanic decision, and its caller is its own act, never a turn`() {
        val firing = actions.open("fired", c60, "Bartok", reason = "Bartok broke Mimosa's wall")

        val decision = decisions.single()
        assertEquals(DecisionRecord.Kind.MECHANIC, decision.kind)
        assertEquals(listOf("fired", "Bartok", "Bartok broke Mimosa's wall", "mechanics:C-60"), listOf(decision.decision, decision.subject, decision.reason, decision.conversationId))
        assertNull(decision.turnId)
        assertEquals(Firing(decision.id, c60, Caller(Role.DIRECTOR, "mechanics:C-60", null, null, firingId = decision.id)), firing)
        assertEquals(-decision.id, firing.caller.act, "negative: firing 812 never passes for turn 812")
        assertEquals("mechanics:engine", actions.open("stake", null, null).caller.conversationId)
    }

    @Test
    fun `a call runs the real tool as the act, with an action decision and no tool_calls row`() = runTest {
        val firing = actions.open("fired", c60, "Bartok")

        val result = actions.run(firing, "blast", args("""{"player":"Bob","power":2}"""))

        assertEquals(ToolResult.ok("ok: blast of power 2.0 on Bob\nsecond line", listOf(bob)), result)
        assertEquals(listOf(firing.caller to "Bob"), ran)
        assertTrue(services.traces.toolCalls.isEmpty(), "the Director's last actions stay his own")
        val action = decisions.last()
        assertEquals(
            listOf("action", "Bob", "blast ok: ok: blast of power 2.0 on Bob", "mechanics:C-60"),
            listOf(action.decision, action.subject, action.reason, action.conversationId),
        )
        val detail = action.detail!!
        assertEquals(firing.id, detail.getValue("firing").jsonPrimitive.long)
        assertEquals(-firing.id, detail.getValue("act").jsonPrimitive.long)
        assertEquals(listOf("C-60", "blast", "world", "ok"), listOf("ref", "tool", "effect", "status").map { detail.getValue(it).jsonPrimitive.content })
        assertEquals(listOf("Bob"), detail.getValue("targets").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(args("""{"player":"Bob","power":2}"""), detail.getValue("args"))
        assertEquals("ok: blast of power 2.0 on Bob\nsecond line", detail.getValue("result").jsonPrimitive.content)
    }

    @Test
    fun `the engine gets the very answers the Director gets for the same call`() = runTest {
        val director = MechanicsSamples.director
        val calls = listOf(
            """{"player":"Bob","power":9}""", // out of the tool's checks
            """{"player":"Bob","power":2,"loud":true}""", // an unknown argument
            """{"player":"${"x".repeat(17)}"}""", // too long
            """{"player":"nobody"}""", // refused by the run
        )
        val runner = ToolRunner(services)
        for (input in calls) {
            val byDirector = runner.runAll(listOf(toolUse("toolu_1", "blast", input)), ToolSet.of(listOf(BlastTool())), director, 1).single()
            val byEngine = actions.run(actions.open("fired", c60, null), "blast", args(input))
            assertEquals(byDirector.content().get().asString(), byEngine.text, input)
            assertTrue(byEngine.isError, input)
        }
        assertTrue(ran.isEmpty())
        val refused = decisions.filter { it.decision == "action" }
        assertEquals(4, refused.size)
        assertEquals(null, refused.first().subject, "a refused call affected nobody")
        assertTrue(refused.first().reason!!.startsWith("blast denied: DENIED (chaos_cap): power 9.0 is above 3"), refused.first().reason)
    }

    @Test
    fun `an action that did not happen is logged at INFO, one that did at DEBUG`() = runTest {
        val firing = actions.open("fired", c60, null)
        io.github.clemsix6.narrator2.testing.LogCapture().use { logs ->
            actions.run(firing, "blast", args("""{"player":"Bob"}"""))
            actions.run(firing, "blast", args("""{"player":"nobody"}"""))
            val info = logs.at(org.apache.logging.log4j.Level.INFO)
            assertTrue(info.any { it == "[Mechanics] action: blast invalid: Invalid arguments: nobody is not online" }, logs.text)
            assertTrue(info.none { "blast ok" in it }, "a success is a DEBUG line: ${logs.text}")
        }
    }

    @Test
    fun `nothing runs while the server is stopping, and an unknown tool is invalid`() = runTest {
        val firing = actions.open("fired", c60, null)
        services.phase = Phase.STOPPING

        assertEquals(ToolResult.failed("server is stopping"), actions.run(firing, "blast", args("""{"player":"Bob"}""")))
        assertEquals(ToolStatus.INVALID, actions.run(firing, "fly", args("{}")).status)
        assertTrue(ran.isEmpty())
        assertEquals(listOf("blast failed: FAILED: server is stopping", "fly invalid: Invalid arguments: unknown tool \"fly\" for the engine"),
            decisions.filter { it.decision == "action" }.map { it.reason })
    }

    @Test
    fun `a cancelled act records its call as unknown and lets the cancellation through`() = runTest {
        val started = CompletableDeferred<Unit>()
        val stuck = object : NarratorTool<NoArgs>("stuck", "Never ends.", NoArgs.serializer(), ToolEffect.WORLD, timeoutMs = 60_000) {
            override suspend fun run(ctx: ToolContext, args: NoArgs): ToolResult {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val engine = EngineActions(services, listOf(stuck))
        val job = launch { engine.run(engine.open("fired", c60, null), "stuck", args("{}")) }
        runCurrent()
        assertTrue(started.isCompleted)

        job.cancelAndJoin()

        val action = decisions.last()
        assertEquals("action", action.decision)
        assertEquals("stuck unknown: UNKNOWN: cancelled while running, the server is stopping; the action may or may not have happened", action.reason)
    }

    @Test
    fun `the dry checks decode the arguments and ask the tool, without running nor recording anything`() = runTest {
        val director = MechanicsSamples.director

        assertNull(actions.check("blast", args("""{"player":"Bob","power":2}"""), director))
        assertEquals(ToolResult.denied("chaos_cap", "power 9.0 is above 3"), actions.check("blast", args("""{"player":"Bob","power":9}"""), director))
        assertEquals(
            ToolResult.invalid("unknown argument \"loud\" (expected: player, power)"),
            actions.check("blast", args("""{"player":"Bob","loud":true}"""), director),
        )
        assertEquals(ToolResult.invalid("unknown tool \"fly\" for the engine"), actions.check("fly", args("{}"), director))
        assertNull(actions.check("blast", args("""{"player":"nobody"}"""), director), "who is online is the run's business")
        assertTrue(ran.isEmpty())
        assertTrue(decisions.isEmpty())
    }

    @Test
    fun `the first line of a result is cut in the decision, never inside a character`() = runTest {
        val long = object : NarratorTool<NoArgs>("long", "Says a lot.", NoArgs.serializer(), ToolEffect.WORLD) {
            override suspend fun run(ctx: ToolContext, args: NoArgs): ToolResult = ToolResult.ok("ok: " + "😀".repeat(250))
        }
        val engine = EngineActions(services, listOf(long))

        engine.run(engine.open("fired", c60, null), "long", args("{}"))

        val reason = decisions.last().reason!!
        assertTrue(reason.startsWith("long ok: ok: 😀") && reason.endsWith("…"), reason)
        val characters = reason.codePointCount(0, reason.length)
        assertTrue(characters <= "long ok: ".length + EngineActions.FIRST_LINE_MAX, "$characters")
        assertTrue(!reason.dropLast(1).last().isHighSurrogate())
    }

    @Test
    fun `an action is stamped at the start of its call, like a tool_calls row, so an act that kills comes before the death`() = runTest {
        val clock = services.clock as TestClock
        val slow = object : NarratorTool<NoArgs>("slow", "Takes a while.", NoArgs.serializer(), ToolEffect.WORLD) {
            override suspend fun run(ctx: ToolContext, args: NoArgs): ToolResult {
                clock.advance(Duration.ofSeconds(3))
                return ToolResult.ok("ok: done")
            }
        }
        val engine = EngineActions(services, listOf(slow))
        val firing = engine.open("fired", c60, null)
        val begun = clock.instant()

        engine.run(firing, "slow", args("{}"))

        assertEquals(begun, decisions.last().ts)
        assertEquals(begun + Duration.ofSeconds(3), clock.instant())
    }

    @Test
    fun `two tools of one name are refused`() {
        assertFailsWith<IllegalArgumentException> { EngineActions(services, listOf(BlastTool(), BlastTool())) }
    }

    @Test
    fun `the log level of an act is the caller's choice`() {
        io.github.clemsix6.narrator2.testing.LogCapture().use { logs ->
            actions.open("stake", c60, "Mimosa", reason = "reward 1x golden_apple", level = Log.Level.INFO)
            assertTrue(logs.at(org.apache.logging.log4j.Level.INFO).contains("[Mechanics] stake Mimosa: reward 1x golden_apple"), logs.text)
        }
    }
}
