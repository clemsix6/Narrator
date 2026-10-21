package io.github.clemsix6.narrator2.forge.compile

import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.forge.api.ForgeModule
import io.github.clemsix6.narrator2.forge.compile.Compilation.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The diagnostics the forger reads (task M5-02, M5 §5.3): place, line, underline, hints from the SDK and the rules. */
class DiagnosticsTest {
    private val compiler = EcjCompiler()

    @AfterEach
    fun stopCompiler() = compiler.stop()

    private fun errors(typeName: String, source: String): List<Diagnostic> = runBlocking {
        val compilation = compiler.compile(typeName, source)
        assertEquals(Outcome.ERRORS, compilation.outcome, "$compilation")
        Diagnostics.compilation(typeName, source, compilation.problems)
    }

    @Test
    fun `an error shows its place, its line underlined, and the SDK's overloads with the factories of their arguments`() {
        val source = """
            import io.github.clemsix6.narrator2.forge.api.*;

            public final class Chickens implements ForgeModule {
                public void start(ModuleContext ctx) {
                    ctx.acts().spawn("chicken", "Bartok");
                }
            }
        """.trimIndent()

        assertEquals(
            """
            [1] error Chickens.java:5:20: The method spawn(String, String, Spawn) in the type Acts is not applicable for the arguments (String, String)
              5 | ctx.acts().spawn("chicken", "Bartok");
                |            ^^^^^
              hint: Acts (ctx.acts()): Pending spawn(String type, String near, Spawn how) — Spawn.of(int)
            """.trimIndent(),
            Diagnostics.render(errors("Chickens", source), source),
        )
    }

    @Test
    fun `the hints point to the SDK - close names, members elsewhere, factories, constants, imports`() {
        val source = """
            import io.github.clemsix6.narrator2.forge.api.*;

            public final class Mistakes implements ForgeModule {
                public void start(ModuleContext ctx) {
                    ctx.acts().spwan("chicken", "Bartok", Spawn.of(6));
                    ctx.acts().teleportPlayer("Bartok");
                    ctx.spawn("cow", "Bartok", Spawn.of(1));
                    say("Bonjour");
                    Spawn many = new Spawn(6);
                    Place here = new Place(1, 2);
                    PlayerInf someone = null;
                    List<String> names = null;
                    int most = Limits.MAX_ACT;
                    ctx.wrld();
                    ctx.world().player(3);
                    String name = ctx.world().players().get(0).nam;
                }
            }
        """.trimIndent()
        val diagnostics = errors("Mistakes", source)
        fun hintsAt(snippet: String): List<String> {
            val line = source.lines().indexOfFirst { snippet in it } + 1
            return diagnostics.filter { it.line == line }.flatMap { it.hints }
        }

        assertEquals(listOf("Acts (ctx.acts()): Pending spawn(String type, String near, Spawn how) — Spawn.of(int)"), hintsAt("spwan"))
        assertEquals(listOf("Acts (ctx.acts()): Pending teleport(String player, Place to)"), hintsAt("teleportPlayer"))
        val onContext = hintsAt("ctx.spawn")
        assertEquals("Acts (ctx.acts()): Pending spawn(String type, String near, Spawn how) — Spawn.of(int)", onContext.first(), "$onContext")
        assertTrue(onContext.size == 2 && onContext[1].startsWith("methods of ModuleContext (ctx): acts(), args(), "), "$onContext")
        assertEquals(listOf("Acts (ctx.acts()): Pending say(String text)"), hintsAt("say(\"Bonjour\")"))
        assertEquals(
            listOf("Spawn: static Spawn of(int count)", "Spawn: Spawn(int count, int distance, String name, boolean target, int lifetimeMinutes, int height, boolean baby)"),
            hintsAt("new Spawn(6)"),
        )
        assertEquals(listOf("Place: Place(String dimension, double x, double y, double z)"), hintsAt("new Place(1, 2)"))
        assertEquals(listOf("did you mean PlayerInfo? (${Diagnostics.SDK_IMPORT})"), hintsAt("PlayerInf someone"))
        assertEquals(listOf("import java.util.List;"), hintsAt("List<String> names"))
        assertEquals(listOf("Limits: static int MAX_ACTS_PER_CALL = 4", "Limits: static int MAX_ACTS_PER_MINUTE = 30"), hintsAt("MAX_ACT;"))
        assertEquals(listOf("ModuleContext (ctx): World world()"), hintsAt("wrld"))
        assertEquals(listOf("World (ctx.world()): PlayerInfo player(String name)"), hintsAt("player(3)"))
        assertTrue(hintsAt(".nam;").isNotEmpty() && hintsAt(".nam;").all { " name(" in it }, "${hintsAt(".nam;")}")

        // A hint about a member is written at each entry that needs it.
        val report = Diagnostics.render(diagnostics, source)
        assertEquals(2, Regex(Regex.escape("hint: Acts (ctx.acts()): Pending spawn(String type, String near, Spawn how)")).findAll(report).count(), report)
    }

    @Test
    fun `a method unknown on an SDK type, with no close name, gets what the SDK calls it and every method of the type, listed once`() {
        val source = """
            import io.github.clemsix6.narrator2.forge.api.*;

            public final class Beats implements ForgeModule {
                public void start(ModuleContext ctx) {
                    ctx.acts().actionbar("Bartok", "tic");
                    ctx.acts().shoutAt("Bartok", "tac");
                    PlayerInfo info = ctx.world().find("Bartok");
                    ctx.acts().ground();
                }
            }
        """.trimIndent()
        val diagnostics = errors("Beats", source)
        fun hintsAt(snippet: String): List<String> {
            val line = source.lines().indexOfFirst { snippet in it } + 1
            return diagnostics.filter { it.line == line }.flatMap { it.hints }
        }

        // The action bar is hud in the SDK; then every method of Acts, with its parameters' names, no return type.
        val actionbar = hintsAt("actionbar")
        assertEquals("Acts (ctx.acts()): Pending hud(String player, String text)", actionbar.first(), "$actionbar")
        val acts = actionbar[1]
        assertTrue(acts.startsWith("methods of Acts (ctx.acts()): attribute(String player, String attribute, double multiply, int seconds), effect("), acts)
        assertTrue("hud(String player, String text)" in acts && "spawn(String type, String near, Spawn how)" in acts && "Pending" !in acts, acts)
        assertTrue(acts.length <= "methods of Acts (ctx.acts()): ".length + 800, "${acts.length}")
        assertEquals(2, actionbar.size, "$actionbar")
        // No name of the SDK for it: the list alone.
        assertEquals(listOf(acts), hintsAt("shoutAt"))
        // find: the World calls it player.
        val find = hintsAt("find")
        assertEquals("World (ctx.world()): PlayerInfo player(String name)", find.first(), "$find")
        assertTrue(find.last().startsWith("methods of World (ctx.world()): biome(Place at), block(Cell cell), count(String player, String item), "), "$find")

        // A close name on the type (sound) comes after the very name on another type (seen in F1 of M5-22).
        assertEquals("ModuleContext (ctx): Ground ground()", hintsAt("ctx.acts().ground()").first(), "${hintsAt("ctx.acts().ground()")}")

        // The list of a type's methods is written once in a report, at its first entry.
        val report = Diagnostics.render(diagnostics, source)
        assertEquals(1, Regex(Regex.escape("hint: methods of Acts (ctx.acts()): ")).findAll(report).count(), report)
        assertEquals(1, Regex(Regex.escape("hint: methods of World (ctx.world()): ")).findAll(report).count(), report)
        assertTrue(report.lines().first { it.startsWith("[1] ") }.contains("actionbar"), report)
        assertTrue(report.length < 2_500, "${report.length}: $report")
    }

    @Test
    fun `the rules - neither the game, nor the mod, nor the JDK past java_base - and what the game's classes are in the SDK`() {
        val source = """
            import net.minecraft.server.level.ServerPlayer;
            import io.github.clemsix6.narrator2.forge.rt.Meter;
            import java.sql.Connection;
            import io.github.clemsix6.narrator2.forge.api.*;

            public final class Outlaw implements ForgeModule {
                public void start(ModuleContext ctx) {
                    BlockPos pos = null;
                    Vec3 point = null;
                    net.minecraft.world.level.Level level = null;
                    jdk.internal.misc.Unsafe unsafe = null;
                }
            }
        """.trimIndent()
        val diagnostics = errors("Outlaw", source)
        fun hintsAt(snippet: String): List<String> {
            val line = source.lines().indexOfFirst { snippet in it } + 1
            return diagnostics.filter { it.line == line }.flatMap { it.hints }.distinct()
        }

        assertEquals(listOf(Diagnostics.GAME_RULE, "ServerPlayer → a player is a name (String); ctx.world().player(name) gives a PlayerInfo"), hintsAt("ServerPlayer;"))
        assertEquals(listOf(Diagnostics.MOD_RULE), hintsAt("Meter;"))
        assertEquals(listOf(Diagnostics.JDK_RULE), hintsAt("Connection;"))
        assertEquals(listOf(Diagnostics.GAME_RULE, "BlockPos → a block's position is a Cell(dimension, x, y, z)"), hintsAt("BlockPos"))
        assertEquals(listOf(Diagnostics.GAME_RULE, "Vec3 → a point is a Place(dimension, x, y, z)"), hintsAt("Vec3"))
        assertEquals(Diagnostics.GAME_RULE, hintsAt("Level level").first())
        assertEquals(listOf(Diagnostics.JDK_RULE), hintsAt("Unsafe"))

        // A rule is written once in a report.
        val report = Diagnostics.render(diagnostics, source)
        assertEquals(1, Regex(Regex.escape(Diagnostics.GAME_RULE)).findAll(report).count(), report)
        assertEquals(1, Regex(Regex.escape(Diagnostics.JDK_RULE)).findAll(report).count(), report)
    }

    @Test
    fun `a package line and a class that is not the module's have their hints`() {
        val packaged = errors("Packaged", "package games;\n\npublic class Packaged {}\n")
        assertEquals(listOf(Diagnostics.NO_PACKAGE), packaged.single().hints)

        val misnamed = errors("Statues", "public class RedLight {}\n")
        assertEquals(listOf("a module is one file: its public class is Statues, the module's name in UpperCamelCase"), misnamed.single().hints)
    }

    @Test
    fun `the same error in several places is one entry with its other places`() {
        val source = """
            public class Twice {
                Lst a;
                Lst b;
                Lst c;
            }
        """.trimIndent()
        val diagnostics = errors("Twice", source)

        val entry = diagnostics.single()
        assertEquals("Twice.java:2:5", entry.where)
        assertEquals(listOf("3:5", "4:5"), entry.also)
        assertTrue("  also at 3:5, 4:5\n" in Diagnostics.render(diagnostics, source) + "\n", Diagnostics.render(diagnostics, source))
    }

    @Test
    fun `the guard's refusals and the bench's faults share the form, with their line quoted`() {
        val source = "public class Tag {\n    void loop() {\n        new Thread(this::loop).start();\n    }\n}\n"
        val report = Diagnostics.render(
            listOf(
                Diagnostics.refused("Tag", 3, "java.lang.Thread: threads are not allowed in a module (use ctx.timers())"),
                Diagnostics.fault("scenario theWolfPasses", "ForgeLimit: a collection over 10000 elements at Tag.java:2", line = 2),
                Diagnostics.fault("smoke run", "IllegalStateException: no wolf"),
            ),
            source,
        )

        assertEquals(
            """
            [1] refused Tag.java:3: java.lang.Thread: threads are not allowed in a module (use ctx.timers())
              3 | new Thread(this::loop).start();
            [2] fault scenario theWolfPasses: ForgeLimit: a collection over 10000 elements at Tag.java:2
              2 | void loop() {
            [3] fault smoke run: IllegalStateException: no wolf
            """.trimIndent(),
            report,
        )
    }

    @Test
    fun `a report holds twenty entries at most and fits its length, the rest counted`() {
        val many = (1..30).map { Diagnostics.fault("scenario s$it", "IllegalStateException: number $it") }
        val twenty = Diagnostics.render(many, null)
        assertEquals(ForgeLimits.DIAGNOSTICS, Regex("""^\[\d+]""", RegexOption.MULTILINE).findAll(twenty).count())
        assertTrue(twenty.endsWith("… 10 more not shown"), twenty)

        val short = Diagnostics.render(many, null, maxChars = 300)
        assertTrue(short.length <= 300, "${short.length}")
        assertTrue(Regex("""… \d+ more not shown$""").containsMatchIn(short), short)

        val huge = Diagnostics.render(listOf(Diagnostics.fault("smoke run", "x".repeat(10_000))), null, maxChars = 200)
        assertTrue(huge.length <= 200 && huge.startsWith("[1] fault smoke run: xxx"), huge)
    }

    @Test
    fun `a long line is quoted around its underline`() {
        val padding = "x".repeat(150)
        val source = "public class Wide {\n    String s = \"$padding\" + undefinedThing;\n}\n"
        val report = Diagnostics.render(errors("Wide", source), source)

        val (code, underline) = report.lines().drop(1).take(2)
        assertTrue(code.startsWith("  2 | …") && code.endsWith("…").not() && "undefinedThing" in code, report)
        assertEquals(code.indexOf("undefinedThing"), underline.indexOf('^'), report)
        assertTrue(code.length < 120, report)
    }

    @Test
    fun `close names - a transposition counts one, a contained name counts, a far one does not`() {
        assertEquals(1, Diagnostics.distance("spwan", "spawn"))
        assertEquals(3, Diagnostics.distance("kitten", "sitting"))
        assertEquals(listOf("spawn"), Diagnostics.closest("spwan", listOf("spawn", "say", "smite")))
        assertEquals(listOf("teleport"), Diagnostics.closest("teleportPlayer", listOf("teleport", "teleportRandom", "title")))
        assertEquals(emptyList(), Diagnostics.closest("explode", listOf("say", "title")))
    }

    @Test
    fun `the hints know every public type of the SDK's two packages`() {
        val packaged = listOf("io/github/clemsix6/narrator2/forge/api", "io/github/clemsix6/narrator2/forge/api/test").flatMap { dir ->
            val folder = Path.of(requireNotNull(ForgeModule::class.java.classLoader.getResource(dir)) { dir }.toURI())
            Files.list(folder).use { files -> files.map { it.fileName.toString() }.filter { it.endsWith(".class") }.toList() }
                .map { Class.forName(dir.replace('/', '.') + "." + it.removeSuffix(".class")) }
        }.filter { Modifier.isPublic(it.modifiers) }

        assertEquals(packaged.map { it.name }.sorted(), Diagnostics.sdkTypes.map { it.name }.sorted())
    }
}
