package io.github.clemsix6.narrator2.tool.action

import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.guard.AttributeKey
import io.github.clemsix6.narrator2.guard.Verdict
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.observer.LivePlayer
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
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What the player action tools decide without a world: their arguments, the players online, the caps
 * checked before the server thread, the launch vectors and the gift limit. Everything else runs on the
 * server thread and is covered in game (`PlayerActionGameTests`).
 */
class PlayerActionToolsTest {
    private val services = TestServices()
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

    private suspend fun <A : Any> NarratorTool<A>.call(json: String): ToolResult {
        val decoded = ToolArgs.decode(args, json)
        assertIs<ToolArgs.Decoded.Ok<A>>(decoded, "arguments: $decoded")
        return run(context, decoded.args)
    }

    @Test
    fun `set_attribute takes exactly one of multiply or add`() {
        assertEquals("give exactly one of multiply or add", invalid(SetAttributeArgs.serializer(), """{"player":"Bob","attribute":"scale","multiply":0.5,"add":1,"seconds":60}"""))
        assertEquals("give exactly one of multiply or add", invalid(SetAttributeArgs.serializer(), """{"player":"Bob","attribute":"scale","seconds":60}"""))
        assertTrue(invalid(SetAttributeArgs.serializer(), """{"player":"Bob","attribute":"size","add":1,"seconds":60}""").startsWith("attribute must be one of: scale, gravity"))
        val ok = ToolArgs.decode(SetAttributeArgs.serializer(), """{"player":"Bob","attribute":"fall_damage_multiplier","add":-1,"seconds":10}""")
        assertIs<ToolArgs.Decoded.Ok<SetAttributeArgs>>(ok)
        assertEquals(AttributeKey.FALL_DAMAGE_MULTIPLIER, ok.args.attribute)
    }

    @Test
    fun `teleport takes a player or a column`() {
        val serializer = TeleportArgs.serializer()
        assertEquals("give to_player, or x and z", invalid(serializer, """{"player":"Bob"}"""))
        assertEquals("give to_player, or x and z", invalid(serializer, """{"player":"Bob","to_player":"Alice","x":1,"z":2}"""))
        assertEquals("give both x and z", invalid(serializer, """{"player":"Bob","x":1}"""))
        assertEquals("y goes with x and z", invalid(serializer, """{"player":"Bob","to_player":"Alice","y":70}"""))
        assertIs<ToolArgs.Decoded.Ok<TeleportArgs>>(ToolArgs.decode(serializer, """{"player":"Bob","x":1,"z":2,"y":-30}"""))
    }

    @Test
    fun `a player who is not online is refused before anything else`() = runTest {
        online("Alice")
        val expected = "Invalid arguments: Bob is not online; online: Alice"
        assertEquals(expected, ApplyEffectTool().call("""{"players":["alice","Bob"],"effect":"speed","seconds":10}""").text)
        assertEquals(expected, RemoveEffectTool().call("""{"players":["Bob"],"effect":"all_narrator"}""").text)
        assertEquals(expected, SetAttributeTool().call("""{"player":"Bob","attribute":"scale","multiply":2,"seconds":60}""").text)
        assertEquals(expected, ResetAttributeTool().call("""{"player":"Bob"}""").text)
        assertEquals(expected, TeleportTool().call("""{"player":"Alice","to_player":"Bob"}""").text)
        assertEquals(expected, LaunchTool().call("""{"player":"Bob","direction":"up","strength":1}""").text)
        assertEquals(expected, GiveItemTool(GiftLimiter(services.clock)).call("""{"player":"Bob","item":"diamond"}""").text)
        assertEquals(expected, TakeItemTool().call("""{"player":"Bob","item":"diamond","count":1,"minutes":1}""").text)
        assertEquals(
            "Invalid arguments: Alice cannot be teleported to themselves",
            TeleportTool().call("""{"player":"Alice","to_player":"ALICE"}""").text,
        )
    }

    @Test
    fun `a player's name is escaped in a refusal`() = runTest {
        online("<engine>")
        assertEquals(
            "Invalid arguments: ‹x› is not online; online: ‹engine›",
            ApplyEffectTool().call("""{"players":["<engine>","<x>"],"effect":"speed","seconds":10}""").text,
        )
    }

    @Test
    fun `the gifts of every conversation count against the server's limiter`() {
        val first = playerActionTools(services).filterIsInstance<GiveItemTool>().single()
        val next = playerActionTools(services).filterIsInstance<GiveItemTool>().single()
        assertTrue(first !== next, "new tools for each conversation")
        assertSame(services.toolLimits.gifts, first.limiter)
        assertSame(services.toolLimits.gifts, next.limiter)
    }

    @Test
    fun `a launch stronger than the chaos level allows is refused with the cap`() = runTest {
        online("Bob")
        val result = LaunchTool().call("""{"player":"Bob","direction":"forward","strength":3}""")
        assertEquals(ToolStatus.INVALID to "Invalid arguments: at chaos 3 a launch has a strength of at most 2 (asked 3)", result.status to result.text)
    }

    @Test
    fun `launch vectors follow where the player looks`() {
        // Yaw 0 faces south (+z); the player's left is then east (+x).
        assertVec(Vec3(0.0, 2.0, 0.0), launchVelocity(LaunchDirection.UP, 2.0, 0f, 0.0))
        assertVec(Vec3(0.0, 1.0, 2.0), launchVelocity(LaunchDirection.FORWARD, 2.0, 0f, 0.0))
        assertVec(Vec3(0.0, 1.0, -2.0), launchVelocity(LaunchDirection.BACKWARD, 2.0, 0f, 0.0))
        assertVec(Vec3(2.0, 1.0, 0.0), launchVelocity(LaunchDirection.LEFT, 2.0, 0f, 0.0))
        assertVec(Vec3(-2.0, 1.0, 0.0), launchVelocity(LaunchDirection.RIGHT, 2.0, 0f, 0.0))
        // Yaw 90 faces west (-x): forward is -x, right is north (-z).
        assertVec(Vec3(-1.0, 0.5, 0.0), launchVelocity(LaunchDirection.FORWARD, 1.0, 90f, 0.0))
        assertVec(Vec3(0.0, 0.5, -1.0), launchVelocity(LaunchDirection.RIGHT, 1.0, 90f, 0.0))
        assertVec(Vec3(0.0, 0.5, 1.0), launchVelocity(LaunchDirection.RANDOM, 1.0, 0f, Math.PI / 2))
    }

    private fun assertVec(expected: Vec3, actual: Vec3) =
        assertTrue(expected.distanceTo(actual) < 1e-9, "expected $expected, got $actual")

    @Test
    fun `twenty gifts per player in any hour`() {
        val clock = TestClock()
        val limiter = GiftLimiter(clock)
        val bob = PlayerRef(UUID.randomUUID(), "Bob")
        val alice = PlayerRef(UUID.randomUUID(), "Alice")
        repeat(GiftLimiter.GIFTS_PER_HOUR) {
            assertEquals(Verdict.Allowed, limiter.take(bob))
            clock.advance(Duration.ofMinutes(1))
        }
        val refused = limiter.take(bob)
        assertIs<Verdict.Denied>(refused)
        assertEquals(GiftLimiter.RULE, refused.rule)
        assertEquals("Bob got 20 gifts in the last hour, the most a player may get; the next is possible at 20:30:00 (in 40m)", refused.message)
        assertEquals(Verdict.Allowed, limiter.take(alice), "another player has their own count")

        clock.advance(Duration.ofMinutes(40))
        assertEquals(Verdict.Allowed, limiter.take(bob), "the first gift left the hour")
        assertIs<Verdict.Denied>(limiter.take(bob), "full again until the second gift leaves the hour")
    }

    @Test
    fun `four points of precious items per player in any hour`() {
        val clock = TestClock()
        val limiter = GiftLimiter(clock)
        val bob = PlayerRef(UUID.randomUUID(), "Bob")
        assertEquals(Verdict.Allowed, limiter.take(bob, 3, "3x diamond"))
        clock.advance(Duration.ofMinutes(10))
        assertEquals(Verdict.Allowed, limiter.take(bob, 0, "1x cookie"), "an ordinary gift costs nothing")
        clock.advance(Duration.ofMinutes(10))
        val refused = limiter.take(bob, 4, "1x elytra")
        assertIs<Verdict.Denied>(refused)
        assertEquals(GiftLimiter.PRECIOUS_RULE, refused.rule)
        assertEquals(
            "Bob got 3 of the 4 precious points a player may get in an hour, and 1x elytra is worth 4; it is possible at 20:30:00 " +
                "(in 40m). The Narrator slows progression down: a diamond or a golden apple is 1 point; netherite, diamond gear, elytra, " +
                "totems, enchanted golden apples, beacons, shulker boxes and the like are 4.",
            refused.message,
        )
        assertEquals(Verdict.Allowed, limiter.take(bob, 1, "1x diamond"), "one point is left")
        assertIs<Verdict.Denied>(limiter.take(bob, 1, "1x diamond"), "none now")
        val tooMuch = limiter.take(PlayerRef(UUID.randomUUID(), "Alice"), 8, "8x diamond")
        assertIs<Verdict.Denied>(tooMuch)
        assertTrue(tooMuch.message.startsWith("8x diamond is worth 8 precious points, more than the 4 a player may get in an hour."), tooMuch.message)

        clock.advance(Duration.ofMinutes(40))
        assertEquals(Verdict.Allowed, limiter.take(bob, 3, "3x diamond"), "the first three points left the hour")
    }
}
