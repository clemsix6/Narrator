package io.github.clemsix6.narrator2.guard

import com.mojang.brigadier.Command
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType.integer
import com.mojang.brigadier.arguments.StringArgumentType.greedyString
import com.mojang.brigadier.arguments.StringArgumentType.word
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `run_command`'s policy on a small dispatcher shaped like the game's: `execute` forks back to itself
 * and `run` redirects to the root, `tp` redirects to `teleport`, `stop` is invisible to the source. The
 * real dispatcher is tested in game (`GuardPolicyGameTests`).
 */
class CommandPolicyTest {
    private val dispatcher = CommandDispatcher<Any>().apply {
        fun literal(name: String) = LiteralArgumentBuilder.literal<Any>(name)
        fun <T> argument(name: String, type: com.mojang.brigadier.arguments.ArgumentType<T>) = RequiredArgumentBuilder.argument<Any, T>(name, type)
        val ok = Command<Any> { 1 }

        register(literal("fill").then(argument("size", integer()).executes(ok)))
        register(literal("say").then(argument("text", greedyString()).executes(ok)))
        register(literal("scoreboard").then(literal("objectives").then(literal("list").executes(ok))))
        register(literal("stopsound").then(argument("sound", word()).executes(ok)))
        register(literal("playsound").then(argument("sound", word()).executes(ok)))
        register(literal("particle").then(argument("name", word()).then(argument("count", integer()).executes(ok))))
        register(
            literal("random")
                .then(literal("value").then(argument("range", word()).executes(ok)))
                .then(literal("roll").then(argument("range", word()).executes(ok))),
        )
        register(literal("stop").requires { false }.executes(ok))
        // Another mod's command, and its alias.
        val sethome = register(literal("sethome").then(argument("name", word()).executes(ok)))
        register(literal("home").redirect(sethome))
        val teleport = register(literal("teleport").then(argument("x", integer()).executes(ok)))
        register(literal("tp").redirect(teleport))
        val execute = register(literal("execute"))
        register(
            literal("execute")
                .then(literal("run").redirect(root))
                .then(literal("as").then(argument("who", word()).redirect(execute)))
                .then(literal("store").then(literal("result").then(argument("path", word()).redirect(execute))))
                .then(literal("summon").then(argument("type", word()).redirect(execute)))
                .then(
                    literal("if")
                        .then(literal("block").then(argument("pos", word()).redirect(execute)))
                        .then(literal("function").then(argument("name", word()).redirect(execute))),
                ),
        )
    }

    private var extra = listOf<String>()
    private var extraAllowed = listOf<String>()
    private val policy = CommandPolicy({ extraAllowed }) { extra }

    private fun check(command: String): Verdict = policy.verdict(dispatcher.parse(command, Any()))

    private fun refused(command: String, name: String): String {
        val verdict = check(command)
        assertIs<Verdict.Denied>(verdict, command)
        assertEquals(Rules.FORBIDDEN_COMMAND, verdict.rule)
        assertTrue(verdict.message.startsWith("`$name` is forbidden: "), "$command: ${verdict.message}")
        return verdict.message
    }

    private fun allowed(command: String) = assertEquals(Verdict.Allowed, check(command), command)

    @Test
    fun `a forbidden command is refused, alone or behind execute`() {
        val message = refused("fill 3", "fill")
        assertEquals("`fill` is forbidden: it changes blocks, and the players' constructions are never modified.", message)
        refused("execute run fill 3", "fill")
        refused("execute as all run fill 3", "fill")
        refused("execute as all run execute as self run fill 3", "fill")
        refused("execute if block here run fill 3", "fill")
    }

    @Test
    fun `execute store, execute summon and execute if function are refused`() {
        refused("execute store result x run scoreboard objectives list", "execute store")
        refused("execute as all store result x run scoreboard objectives list", "execute store")
        assertEquals(
            "`execute summon` is forbidden: it acts on players or creatures outside the guarded tools; use the spawn tool instead.",
            refused("execute summon creeper run scoreboard objectives list", "execute summon"),
        )
        refused("execute if function foo run scoreboard objectives list", "execute if function")
    }

    @Test
    fun `an alias is caught by its own name and by where it leads`() {
        assertEquals(
            "`tp` is forbidden: it acts on players or creatures outside the guarded tools; use the teleport tool instead.",
            refused("tp 100", "tp"),
        )
        refused("execute run tp 100", "tp")
        refused("teleport 100", "teleport")
    }

    @Test
    fun `a command the source cannot see is refused rather than unknown`() {
        refused("stop", "stop")
        refused("execute run stop", "stop")
        refused("/fill 3", "fill")
    }

    @Test
    fun `a malformed forbidden command is still refused as forbidden`() {
        refused("fill three", "fill")
        refused("execute as all run say", "say")
    }

    @Test
    fun `the reviewed commands run, and a word that is no command is left to run_command`() {
        allowed("scoreboard objectives list")
        allowed("execute as all run scoreboard objectives list")
        allowed("execute if block here run stopsound pig")
        allowed("stopsound pig")
        allowed("random value 1..6")
        allowed("execute as all run random value 1..6")
        allowed("fil 3")
        allowed("execute run fil 3")
    }

    /** M2 review, finding 7: bounded tools do the same, and `random roll` tells every player. */
    @Test
    fun `particle, playsound and random roll are refused, alone or behind execute`() {
        assertEquals(
            "`particle` is forbidden: a tool with its own limits does it; use the particles tool instead.",
            refused("particle heart 2000000000", "particle"),
        )
        refused("execute as all run particle heart 2000000000", "particle")
        assertEquals(
            "`playsound` is forbidden: a tool with its own limits does it; use the play_sound tool instead.",
            refused("playsound pig", "playsound"),
        )
        refused("execute if block here run playsound pig", "playsound")
        assertEquals(
            "`random roll` is forbidden: the Narrator speaks through its speech tools (say, show_title, actionbar, give_book), " +
                "which are recorded; use random value (its result reaches you alone) instead.",
            refused("random roll 1..6", "random roll"),
        )
        refused("execute as all run random roll 1..6", "random roll")
        refused("random roll", "random roll")
        extraAllowed = listOf("particle", "playsound")
        refused("particle heart 1", "particle")
        refused("playsound pig", "playsound")
    }

    @Test
    fun `a command nobody reviewed is refused, unless the operators allow it`() {
        assertEquals(
            "`sethome` is forbidden: only the commands reviewed for the Narrator run, and this one was not (the operators can allow it " +
                "with safety.extraAllowedCommands); command_help with no command lists those run_command accepts.",
            refused("sethome base", "sethome"),
        )
        refused("execute as all run sethome base", "sethome")
        refused("sethome", "sethome")
        refused("home base", "home")

        extraAllowed = listOf(" SetHome ")
        allowed("sethome base")
        allowed("execute as all run sethome base")
        refused("home base", "home")

        // An allowed alias that lands in a command nobody allowed is caught where it lands.
        extraAllowed = listOf("home")
        refused("home base", "sethome")

        // The forbidden lists win over the allowed one.
        extraAllowed = listOf("sethome", "fill")
        extra = listOf("sethome")
        refused("sethome base", "sethome")
        refused("fill 3", "fill")
    }

    @Test
    fun `the configuration adds roots, read at each check`() {
        allowed("stopsound pig")
        extra = listOf(" StopSound ")
        assertEquals(
            "`stopsound` is forbidden: the operators forbid it (safety.extraForbiddenCommands).",
            refused("stopsound pig", "stopsound"),
        )
        refused("execute as all run stopsound pig", "stopsound")
        extra = emptyList()
        allowed("stopsound pig")
    }

    @Test
    fun `the configuration cannot allow a built-in root`() {
        extra = listOf("")
        refused("say hello", "say")
    }

    @Test
    fun `the built-in list names the design's roots`() {
        val design = listOf(
            "stop", "op", "deop", "ban", "whitelist", "save-off",
            "gamerule", "difficulty", "defaultgamemode", "gamemode", "worldborder", "forceload", "tick", "function", "schedule",
            "datapack", "setworldspawn", "spawnpoint", "seed", "fetchprofile",
            "fill", "fillbiome", "setblock", "clone", "place", "data",
            "clear", "item", "loot", "give", "enchant", "xp", "experience", "advancement",
            "kill", "summon", "damage", "tp", "teleport", "spreadplayers", "ride", "effect", "attribute", "tag", "team",
            "say", "tellraw", "tell", "msg", "w", "me", "teammsg", "tm", "title",
            "weather", "time", "locate", "bossbar",
        )
        assertEquals(emptyList(), design.filterNot { it in CommandPolicy.FORBIDDEN })
        listOf("execute", "scoreboard", "stopsound", "waypoint", "random").forEach {
            assertTrue(it !in CommandPolicy.FORBIDDEN && it in CommandPolicy.ALLOWED, "$it stays allowed")
        }
        listOf("particle", "playsound").forEach {
            assertEquals(CommandPolicy.Reason.TOOL, CommandPolicy.FORBIDDEN[it]?.reason, "$it: a bounded tool does it")
        }
        assertEquals(CommandPolicy.Reason.ITEMS, CommandPolicy.FORBIDDEN["recipe"]?.reason, "recipes are progress: never handed out")
        assertEquals(emptySet(), CommandPolicy.ALLOWED intersect CommandPolicy.FORBIDDEN.keys, "allowed and forbidden at once")
    }
}
