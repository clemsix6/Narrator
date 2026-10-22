package io.github.clemsix6.narrator2.tool.action

import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.guard.AttributeKey
import io.github.clemsix6.narrator2.guard.Verdict
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.observer.LivePlayer
import io.github.clemsix6.narrator2.testing.FakeGuards
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.testing.testSettings
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.ToolArgs
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import net.minecraft.util.RandomSource
import net.minecraft.world.phys.Vec3
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import kotlin.math.hypot
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What the player action tools decide without a world: their arguments, the players online, the caps
 * checked before the server thread, the launch vectors and the gift limit. Everything else runs on the
 * server thread and is covered in game (`PlayerActionGameTests`).
 */
class PlayerActionToolsTest {
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

    /** The chaos level of the guards' caps and of the settings, which the messages quote. */
    private fun chaos(level: Int) {
        guards.chaos = level
        services.settings.swap(testSettings(NarratorConfig(chaos = level)))
    }

    private fun <A : Any> decode(serializer: KSerializer<A>, json: String): A {
        val decoded = ToolArgs.decode(serializer, json)
        assertIs<ToolArgs.Decoded.Ok<A>>(decoded, "arguments: $decoded")
        return decoded.args
    }

    private suspend fun <A : Any> NarratorTool<A>.call(json: String): ToolResult = run(context, decode(args, json))

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
    fun `teleport takes a player, a column or a random radius`() {
        val serializer = TeleportArgs.serializer()
        val oneOf = "give to_player, x and z, or random_radius"
        assertEquals(oneOf, invalid(serializer, """{"player":"Bob"}"""))
        assertEquals(oneOf, invalid(serializer, """{"player":"Bob","to_player":"Alice","x":1,"z":2}"""))
        assertEquals(oneOf, invalid(serializer, """{"player":"Bob","x":1,"z":2,"random_radius":50}"""))
        assertEquals(oneOf, invalid(serializer, """{"player":"Bob","to_player":"Alice","random_radius":50}"""))
        assertEquals("give both x and z", invalid(serializer, """{"player":"Bob","x":1}"""))
        assertEquals("y goes with x and z", invalid(serializer, """{"player":"Bob","to_player":"Alice","y":70}"""))
        assertEquals("y goes with x and z", invalid(serializer, """{"player":"Bob","random_radius":50,"y":70}"""))
        assertEquals("random_radius must be between 10 and 500 (got 600)", invalid(serializer, """{"player":"Bob","random_radius":600}"""))
        assertIs<ToolArgs.Decoded.Ok<TeleportArgs>>(ToolArgs.decode(serializer, """{"player":"Bob","x":1,"z":2,"y":-30}"""))
        val random = ToolArgs.decode(serializer, """{"player":"Bob","random_radius":300}""")
        assertIs<ToolArgs.Decoded.Ok<TeleportArgs>>(random)
        assertEquals(300, random.args.randomRadius)
    }

    /**
     * After its dry checks (`check`, which needs no player), each tool refuses a player who is not online. The
     * checks of `apply_effect` and `give_item` read the game's registries, which unit tests do not load: they are
     * covered in game (`PlayerActionGameTests.theDryChecksTouchNothing`).
     */
    @Test
    fun `a player who is not online is refused before the world is touched`() = runTest {
        online("Alice")
        val expected = "Invalid arguments: Bob is not online; online: Alice"
        assertEquals(expected, RemoveEffectTool().call("""{"players":["Bob"],"effect":"all_narrator"}""").text)
        assertEquals(expected, SetAttributeTool().call("""{"player":"Bob","attribute":"scale","multiply":2,"seconds":60}""").text)
        assertEquals(expected, ResetAttributeTool().call("""{"player":"Bob"}""").text)
        assertEquals(expected, TeleportTool().call("""{"player":"Alice","to_player":"Bob"}""").text)
        assertEquals(expected, TeleportTool().call("""{"player":"Bob","random_radius":100}""").text)
        assertEquals(expected, LaunchTool().call("""{"player":"Bob","direction":"up","strength":1}""").text)
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
            RemoveEffectTool().call("""{"players":["<engine>","<x>"],"effect":"speed"}""").text,
        )
        assertEquals(
            "Invalid arguments: ‹x› cannot be teleported to themselves",
            TeleportTool().check(context, decode(TeleportArgs.serializer(), """{"player":"<x>","to_player":"<X>"}"""))?.text,
        )
    }

    /**
     * The dry checks (`check`, M4 §4.3) of the tools a rule may use, without any player online: what the engine
     * answers when a rule's effect is created. `run` starts with them, so the Director gets the same answers.
     */
    @Test
    fun `the dry checks need no player and give the answers of run`() = runTest {
        chaos(3)
        suspend fun <A : Any> NarratorTool<A>.dry(json: String): ToolResult? = check(context, decode(args, json))

        // set_attribute: a change that is in the player's disfavor whatever their attribute now.
        assertEquals(
            "Invalid arguments: this change of scale is in Bob's disfavor: at chaos 3 it lasts at most 600 s (asked 900 s)",
            SetAttributeTool().dry("""{"player":"Bob","attribute":"scale","multiply":0.5,"seconds":900}""")?.text,
        )
        assertEquals(
            "Invalid arguments: this change of movement_speed is in Bob's disfavor: at chaos 3 it lasts at most 600 s (asked 700 s)",
            SetAttributeTool().dry("""{"player":"Bob","attribute":"movement_speed","add":-0.05,"seconds":700}""")?.text,
        )
        assertEquals(
            "Invalid arguments: this change of fall_damage_multiplier is in Bob's disfavor: at chaos 3 it lasts at most 600 s (asked 1800 s)",
            SetAttributeTool().dry("""{"player":"Bob","attribute":"fall_damage_multiplier","add":1,"seconds":1800}""")?.text,
        )
        assertNull(SetAttributeTool().dry("""{"player":"Bob","attribute":"scale","multiply":0.5,"seconds":600}"""), "within the cap")
        assertNull(SetAttributeTool().dry("""{"player":"Bob","attribute":"movement_speed","add":0.05,"seconds":900}"""), "a gift")
        // A factor depends on the player's value (a luck of 0 stays 0): run tells.
        assertNull(SetAttributeTool().dry("""{"player":"Bob","attribute":"luck","multiply":0.5,"seconds":900}"""))

        assertEquals(
            "Invalid arguments: at chaos 3 a launch has a strength of at most 2.5 (asked 3)",
            LaunchTool().dry("""{"player":"Bob","direction":"up","strength":3}""")?.text,
        )
        assertNull(LaunchTool().dry("""{"player":"Bob","direction":"up","strength":2.5}"""))

        assertNull(TeleportTool().dry("""{"player":"Bob","random_radius":500}"""), "3000 blocks at chaos 3")
        assertNull(TeleportTool().dry("""{"player":"Bob","x":100000,"z":0}"""), "the distance of a column depends on where the player is")

        chaos(1)
        assertEquals(
            "Invalid arguments: at chaos 1 a teleport goes at most 50 blocks (random_radius 100)",
            TeleportTool().dry("""{"player":"Bob","random_radius":100}""")?.text,
        )
        assertEquals(
            "Invalid arguments: this change of movement_speed is in Bob's disfavor: at chaos 1 it lasts at most 60 s (asked 61 s)",
            SetAttributeTool().dry("""{"player":"Bob","attribute":"movement_speed","add":-0.01,"seconds":61}""")?.text,
        )

        // At chaos 0 the guards refuse every hostile action with their own rule, on the player.
        chaos(0)
        assertNull(LaunchTool().dry("""{"player":"Bob","direction":"up","strength":3}"""))
        assertNull(TeleportTool().dry("""{"player":"Bob","random_radius":500}"""))
        assertNull(SetAttributeTool().dry("""{"player":"Bob","attribute":"scale","multiply":0.5,"seconds":900}"""))
    }

    @Test
    fun `a random teleport aims between half the radius and the radius`() {
        val random = RandomSource.create(12)
        repeat(500) {
            val column = Teleports.randomColumn(random, 100.5, -40.5, 300)
            val distance = hypot(column.x + 0.5 - 100.5, column.z + 0.5 + 40.5)
            assertTrue(distance >= 150 - 1 && distance <= 300 + 1, "column $column is $distance blocks away")
        }
    }

    @Test
    fun `a random teleport tries eight columns of one chunk, within the radius`() {
        val random = RandomSource.create(7)
        val first = Teleports.Column(-20, 37)
        val columns = Teleports.columnsAround(random, first, 0.5, 0.5, 60)
        assertEquals(Teleports.RANDOM_COLUMNS, columns.size)
        assertEquals(first, columns.first(), "the column drawn first")
        assertEquals(columns.size, columns.distinct().size, "each column once")
        assertTrue(columns.all { it.x in -32..-17 && it.z in 32..47 }, "all in the chunk of the first: $columns")
        assertTrue(columns.drop(1).all { hypot(it.x + 0.5 - 0.5, it.z + 0.5 - 0.5) <= 60 }, "within the radius: $columns")

        // Near the edge of the radius, only the columns within it are tried.
        val edge = Teleports.columnsAround(random, Teleports.Column(15, 0), 0.5, 0.5, 15)
        assertTrue(edge.drop(1).all { hypot(it.x.toDouble(), it.z.toDouble()) <= 15 }, "$edge")
        assertEquals(Teleports.Column(15, 0), edge.first())
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
        assertEquals(ToolStatus.INVALID to "Invalid arguments: at chaos 3 a launch has a strength of at most 2.5 (asked 3)", result.status to result.text)
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
