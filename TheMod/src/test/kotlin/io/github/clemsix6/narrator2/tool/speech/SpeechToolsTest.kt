package io.github.clemsix6.narrator2.tool.speech

import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.guard.Verdict
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.observer.LivePlayer
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.ToolArgs
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolStatus
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * What the speech tools decide before touching the world: arguments, players online, limits. The unit
 * tests have no Minecraft server, so a call that got past these checks would fail on `onServer`; the
 * in-game tests (`SpeechGameTests`) cover what the players get.
 */
class SpeechToolsTest {
    private val services = TestServices()
    private val limiter = SpeechLimiter(services.clock)
    private val caller = Caller(Role.DIRECTOR, "director-1", turnId = 3, taskId = null)
    private val context = ToolContext(services, caller, services.clock, 5_000)

    /** Players who join in this order. */
    private fun online(vararg names: String) = names.forEachIndexed { i, name ->
        services.players.put(
            LivePlayer(
                ref = PlayerRef(UUID.randomUUID(), name), sessionId = 1, joinedAt = i.toLong(), isOp = false, gameMode = "survival",
                pos = Pos("minecraft:overworld", 0, 64, 0), biome = null, structure = null, underground = false,
                health = 20f, maxHealth = 20f, food = 20, vehicle = null, lastDeathAt = null, lastMoveAt = 0, idle = false,
            ),
        )
    }

    private suspend fun <A : Any> NarratorTool<A>.call(json: String): ToolResult {
        val decoded = ToolArgs.decode(args, json)
        assertIs<ToolArgs.Decoded.Ok<A>>(decoded, "arguments: $decoded")
        return run(context, decoded.args)
    }

    private fun assertResult(status: ToolStatus, text: String, result: ToolResult) {
        assertEquals(status to text, result.status to result.text)
    }

    @Test
    fun `a player who is not online is refused with the players who are`() = runTest {
        online("Alice", "Carl")

        assertResult(
            ToolStatus.INVALID,
            "Invalid arguments: Bob is not online; online: Alice, Carl",
            SayTool(limiter).call("""{"text":"Salut.","to":["alice","Bob"]}"""),
        )
        assertResult(
            ToolStatus.INVALID,
            "Invalid arguments: Bob, Zed are not online; online: Alice, Carl",
            ShowTitleTool(limiter).call("""{"title":"Hop","to":["Bob","Zed","Bob"]}"""),
        )
    }

    @Test
    fun `nobody online, nobody to talk to`() = runTest {
        assertResult(ToolStatus.FAILED, "FAILED: nobody is online", SayTool(limiter).call("""{"text":"Il y a quelqu'un ?"}"""))
        assertResult(
            ToolStatus.INVALID,
            "Invalid arguments: Bob is not online; online: nobody",
            GiveBookTool(limiter).call("""{"to":"Bob","title":"T","pages":["p"]}"""),
        )
    }

    @Test
    fun `a text with nothing left once cleaned is refused`() = runTest {
        online("Alice")
        val nothing = "has nothing to show once formatting codes and invisible characters are removed; write plain text"

        assertResult(ToolStatus.INVALID, "Invalid arguments: text $nothing", SayTool(limiter).call("""{"text":"§c§l ​"}"""))
        assertResult(ToolStatus.INVALID, "Invalid arguments: title $nothing", ShowTitleTool(limiter).call("""{"title":"§r"}"""))
        assertResult(ToolStatus.INVALID, "Invalid arguments: text $nothing", ActionbarTool(limiter).call("""{"text":"\n"}"""))
        assertResult(
            ToolStatus.INVALID,
            "Invalid arguments: pages $nothing",
            GiveBookTool(limiter).call("""{"to":"Alice","title":"Titre","pages":["  ","§k"]}"""),
        )
    }

    /**
     * The dry checks (`check`, M4 §4.3) of the speech tools a rule may use need nobody online, and count nothing
     * against the limits: the engine runs them when a rule is created. The sound's id is checked in game
     * (`SpeechGameTests`): unit tests do not load the game's registries.
     */
    @Test
    fun `the dry checks need nobody online and count nothing`() = runTest {
        suspend fun <A : Any> NarratorTool<A>.dry(json: String): ToolResult? {
            val decoded = ToolArgs.decode(args, json)
            assertIs<ToolArgs.Decoded.Ok<A>>(decoded, "arguments: $decoded")
            return check(context, decoded.args)
        }
        val nothing = "has nothing to show once formatting codes and invisible characters are removed; write plain text"

        assertEquals("Invalid arguments: text $nothing", SayTool(limiter).dry("""{"text":"§c "}""")?.text)
        assertEquals("Invalid arguments: title $nothing", ShowTitleTool(limiter).dry("""{"title":"§r"}""")?.text)
        assertEquals("Invalid arguments: text $nothing", ActionbarTool(limiter).dry("""{"text":"\n"}""")?.text)
        repeat(SpeechLimiter.LINES_PER_TURN + 2) { assertEquals(null, SayTool(limiter).dry("""{"text":"La loi est la loi, {actor}.","to":["Bob"]}""")) }
        assertEquals(null, ShowTitleTool(limiter).dry("""{"title":"Châtiment","to":["Bob"]}"""))
        assertEquals(null, ActionbarTool(limiter).dry("""{"text":"Psst."}"""))
        assertEquals(Verdict.Allowed, limiter.line(caller), "no line was counted")
    }

    @Test
    fun `a line past the limit is denied before anything is shown`() = runTest {
        online("Alice")
        repeat(SpeechLimiter.LINES_PER_TURN) { limiter.line(caller) }

        val result = SayTool(limiter).call("""{"text":"Encore un mot."}""")
        assertEquals(ToolStatus.DENIED, result.status)
        assertEquals("DENIED (speech_limit): 4 lines already this turn", result.text.substringBefore(", the most"))
    }

    @Test
    fun `the speech tools of every conversation share the server's limits`() = runTest {
        online("<engine>")
        val engine = services.players.byName("<engine>")!!.ref
        // Lines and a title given in an earlier chapter, through the server's limiter (ToolLimits.speech).
        repeat(SpeechLimiter.LINES_PER_TURN) { services.toolLimits.speech.line(caller) }
        services.toolLimits.speech.titles(listOf(engine))
        val tools = speechTools(services)

        val say = tools.filterIsInstance<SayTool>().single().call("""{"text":"Encore un mot."}""")
        assertEquals("DENIED (speech_limit): 4 lines already this turn", say.text.substringBefore(", the most"))
        assertResult(
            ToolStatus.DENIED,
            "DENIED (speech_limit): one title per player every 30s: ‹engine› got one 0s ago (next in 30s)",
            tools.filterIsInstance<ShowTitleTool>().single().call("""{"title":"Encore ?"}"""),
        )
    }

    @Test
    fun `a title, a line above the hotbar or a book everyone waits for is denied`() = runTest {
        online("Alice")
        val alice = services.players.byName("Alice")!!.ref
        limiter.titles(listOf(alice))
        limiter.actionbars(listOf(alice))
        limiter.books(listOf(alice))

        assertResult(
            ToolStatus.DENIED,
            "DENIED (speech_limit): one title per player every 30s: Alice got one 0s ago (next in 30s)",
            ShowTitleTool(limiter).call("""{"title":"Encore ?"}"""),
        )
        assertResult(
            ToolStatus.DENIED,
            "DENIED (speech_limit): one line above the hotbar per player every 5s: Alice got one 0s ago (next in 5s)",
            ActionbarTool(limiter).call("""{"text":"Psst.","to":["Alice"]}"""),
        )
        assertResult(
            ToolStatus.DENIED,
            "DENIED (speech_limit): one book per player every 5m: Alice got one 0s ago (next in 5m)",
            GiveBookTool(limiter).call("""{"to":"alice","title":"Amende","pages":["Payez."]}"""),
        )
    }

    @Test
    fun `sound suggestions share the most words, then are the closest`() {
        val ids = listOf(
            "entity.lightning_bolt.thunder", "entity.lightning_bolt.impact", "item.trident.thunder",
            "block.bell.use", "entity.ghast.scream", "entity.villager.no",
        )

        assertEquals(listOf("entity.lightning_bolt.thunder", "entity.lightning_bolt.impact"), closestIds("entity.lightning_bolt.thuner", ids, 2))
        assertEquals(listOf("item.trident.thunder", "entity.lightning_bolt.thunder"), closestIds("thunder", ids, 2))
        assertEquals("entity.ghast.scream", closestIds("Ghast scream", ids).first())
        assertEquals("block.bell.use", closestIds("minecraft:block.bel.use", ids, 1).single(), "the namespace is optional")
    }

    @Test
    fun `every argument has its documented bounds`() {
        val tooLong = ToolArgs.decode(SayArgs.serializer(), """{"text":"${"a".repeat(301)}"}""")
        assertEquals(ToolArgs.Decoded.Invalid("text is longer than 300 characters (got 301)"), tooLong)
        val sound = ToolArgs.decode(PlaySoundArgs.serializer(), """{"sound":"block.bell.use","private":false,"volume":0.05}""")
        assertEquals(ToolArgs.Decoded.Invalid("volume must be between 0.1 and 2 (got 0.05)"), sound)
        val book = ToolArgs.decode(GiveBookArgs.serializer(), """{"to":"Bob","title":"T","pages":[]}""")
        assertEquals(ToolArgs.Decoded.Invalid("pages must have 1 to 10 items (got 0)"), book)
        val title = ToolArgs.decode(ShowTitleArgs.serializer(), """{"title":"T","seconds":11}""")
        assertEquals(ToolArgs.Decoded.Invalid("seconds must be between 1 and 10 (got 11)"), title)
    }
}
