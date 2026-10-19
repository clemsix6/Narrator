package io.github.clemsix6.narrator2.forge.arsenal

import io.github.clemsix6.narrator2.forge.ForgeRejected
import io.github.clemsix6.narrator2.forge.ModuleStatus
import io.github.clemsix6.narrator2.forge.ParamSpec
import io.github.clemsix6.narrator2.forge.api.ParamType
import io.github.clemsix6.narrator2.forge.api.Sdk
import io.github.clemsix6.narrator2.testing.ForgeSamples
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The manifest (M5 §5.2, §6.1): read from a module's bytecode — its invisible annotations and its `Scenarios` — and
 * checked against the format of a module, without loading nor running it; saved as `v<k>.json`.
 */
class ManifestTest {
    @Test
    fun `the witness's manifest is read from its bytecode`() {
        val manifest = Manifest.read(ForgeSamples.NAME, ForgeSamples.classes)

        assertEquals(ForgeSamples.CLASS, manifest.mainClass)
        assertEquals(ForgeSamples.manifest.summary, manifest.summary)
        assertEquals(ForgeSamples.manifest.params, manifest.params)
        assertEquals(listOf("theWolfPassesOnByTouch", "theLeastWolfWins"), manifest.scenarios, "in the order of the source, lambdas of the scenarios left out")
        assertEquals(ForgeSamples.classes.size, manifest.classes)
        assertEquals(ForgeSamples.classes.values.sumOf { it.size }, manifest.bytes)
        val entry = manifest.entry(
            1, ModuleStatus.READY, builtIn = false, savedAt = ForgeSamples.manifest.savedAt, by = "forger T-31",
            note = ForgeSamples.manifest.note, hash = ForgeSamples.hash,
        )
        assertEquals(ForgeSamples.manifest, entry)
        assertEquals(Sdk.VERSION, entry.sdk)
    }

    @Test
    fun `the manifest is read without loading nor running the module`() {
        val source = module(
            body = """
                static { if (Boolean.TRUE) throw new IllegalStateException("never run me"); }
                public DancingCows() { throw new IllegalStateException("never make me"); }
                public void start(ModuleContext ctx) {}
            """,
        )

        val manifest = Manifest.read(NAME, compile(source))

        assertEquals("Cows dance around a player.", manifest.summary)
        assertFailsWith<ClassNotFoundException>("nothing was defined") { Class.forName(CLASS) }
    }

    @Test
    fun `one @Param alone is read like several, and a module may have none`() {
        val one = Manifest.read(NAME, compile(module(annotations = INFO + """@Param(name = "player", type = ParamType.PLAYER, doc = "Whom they circle.")""")))
        assertEquals(listOf(ParamSpec("player", ParamType.PLAYER, "Whom they circle.", true, null, null, null, emptyList())), one.params)

        val none = Manifest.read(NAME, compile(module()))
        assertEquals(emptyList(), none.params)
        assertEquals(emptyList(), none.scenarios)
    }

    @Test
    fun `every kind of parameter is read with its bounds, fallback and choices`() {
        val manifest = Manifest.read(
            NAME,
            compile(
                module(
                    annotations = INFO + """
                        @Param(name = "who", type = ParamType.PLAYERS, doc = "Who plays.", required = false, fallback = "Bartok, Mimosa", min = 1, max = 8)
                        @Param(name = "cows", type = ParamType.INTEGER, doc = "How many.", required = false, fallback = "6", min = 3, max = 8)
                        @Param(name = "speed", type = ParamType.NUMBER, doc = "How fast.", required = false, fallback = "-0.5", min = -2.5)
                        @Param(name = "loud", type = ParamType.BOOLEAN, doc = "Whether they moo.", required = false, fallback = "false")
                        @Param(name = "mood", type = ParamType.TEXT, doc = "How the Narrator speaks.", required = false, fallback = "sévère", choices = {"taquin", "sévère"})
                        @Param(name = "where", type = ParamType.PLACE, doc = "Where.", required = false, fallback = "120 64.5 -30 the_nether")
                        @Param(name = "near", type = ParamType.PLACE, doc = "Near whom.", required = false, fallback = "Josiane")
                        @Param(name = "song", type = ParamType.TEXT, doc = "What they sing.", required = false, max = 40)
                    """,
                ),
            ),
        )

        assertEquals(
            listOf(
                ParamSpec("who", ParamType.PLAYERS, "Who plays.", false, "Bartok, Mimosa", 1.0, 8.0, emptyList()),
                ParamSpec("cows", ParamType.INTEGER, "How many.", false, "6", 3.0, 8.0, emptyList()),
                ParamSpec("speed", ParamType.NUMBER, "How fast.", false, "-0.5", -2.5, null, emptyList()),
                ParamSpec("loud", ParamType.BOOLEAN, "Whether they moo.", false, "false", null, null, emptyList()),
                ParamSpec("mood", ParamType.TEXT, "How the Narrator speaks.", false, "sévère", null, null, listOf("taquin", "sévère")),
                ParamSpec("where", ParamType.PLACE, "Where.", false, "120 64.5 -30 the_nether", null, null, emptyList()),
                ParamSpec("near", ParamType.PLACE, "Near whom.", false, "Josiane", null, null, emptyList()),
                ParamSpec("song", ParamType.TEXT, "What they sing.", false, null, null, 40.0, emptyList()),
            ),
            manifest.params,
        )
    }

    @Test
    fun `the class must be the module's, public, concrete, a ForgeModule with a public constructor`() {
        assertProblems(
            module(head = "public final class Dancing implements ForgeModule"),
            "the module's class must be DancingCows (dancing_cows in UpperCamelCase), public, at the top of the file (the file declares Dancing)",
            type = "Dancing",
        )
        assertProblems(module(head = "final class DancingCows implements ForgeModule"), "DancingCows must be public: public final class DancingCows implements ForgeModule")
        assertProblems(module(head = "public final class DancingCows", body = ""), "DancingCows must implement ForgeModule")
        assertProblems(module(head = "public abstract class DancingCows implements ForgeModule"), "DancingCows must not be abstract: the engine makes an instance of it")
        assertProblems(module(head = "public interface DancingCows extends ForgeModule", body = ""), "DancingCows must be a class, not an interface")
        assertProblems(
            module(head = "public enum DancingCows implements ForgeModule", body = "ONE; public void start(ModuleContext ctx) {}"),
            "DancingCows must be a class, not an enum",
        )
        assertProblems(
            module(body = "public DancingCows(int cows) {} public void start(ModuleContext ctx) {}"),
            "DancingCows needs a public constructor without arguments (or no constructor at all)",
        )
        assertProblems(module(body = "private DancingCows() {} public void start(ModuleContext ctx) {}"), "DancingCows needs a public constructor without arguments (or no constructor at all)")

        val throughItsOwnBase = """
            import io.github.clemsix6.narrator2.forge.api.*;
            abstract class Base implements ForgeModule {}
            $INFO
            public final class DancingCows extends Base { public void start(ModuleContext ctx) {} }
        """.trimIndent()
        assertEquals(NAME, Manifest.read(NAME, compile(throughItsOwnBase)).name, "a ForgeModule through a class of the module")
    }

    @Test
    fun `@ModuleInfo names the module and says what the players see, in one line`() {
        assertProblems(module(annotations = ""), "@ModuleInfo is missing: @ModuleInfo(name = \"dancing_cows\", summary = \"what the players see, one English line\")")
        assertProblems(
            module(annotations = """@ModuleInfo(name = "dancing_cow", summary = "Cows dance.")"""),
            "@ModuleInfo name is \"dancing_cow\" but the module is dancing_cows: they must be the same",
        )
        assertProblems(module(annotations = """@ModuleInfo(name = "dancing_cows", summary = "  ")"""), "@ModuleInfo summary is empty: say what the players see, one English line")
        assertProblems(
            module(annotations = """@ModuleInfo(name = "dancing_cows", summary = "${"m".repeat(121)}")"""),
            "@ModuleInfo summary is 121 characters long, at most 120",
        )
        assertProblems(module(annotations = """@ModuleInfo(name = "dancing_cows", summary = "Cows.\nThey dance.")"""), "@ModuleInfo summary must be one line")
    }

    @Test
    fun `each @Param has a snake_case name of its own and a doc`() {
        assertProblems(
            withParams(
                """@Param(name = "Cows", type = ParamType.INTEGER, doc = "How many.", required = false, fallback = "3")""",
                """@Param(name = "speed", type = ParamType.NUMBER, doc = "", required = false, fallback = "1")""",
                """@Param(name = "speed", type = ParamType.NUMBER, doc = "How fast.", required = false, fallback = "1")""",
                """@Param(name = "song", type = ParamType.TEXT, doc = "${"d".repeat(121)}")""",
                """@Param(name = "tune", type = ParamType.TEXT, doc = "What.\nWhy.")""",
            ),
            "@Param Cows: a parameter's name is snake_case, 1 to 32 characters, a to z, 0 to 9 and _, starting with a letter",
            "@Param speed: doc is empty: say what it is, one English line for the Director",
            "@Param song: doc is 121 characters long, at most 120",
            "@Param tune: doc must be one line",
        )
        assertProblems(
            withParams(
                """@Param(name = "speed", type = ParamType.NUMBER, doc = "How fast.", required = false, fallback = "1")""",
                """@Param(name = "speed", type = ParamType.NUMBER, doc = "How fast again.", required = false, fallback = "2")""",
            ),
            "@Param speed: two parameters have this name",
        )
        assertProblems(
            withParams(*(1..9).map { """@Param(name = "p$it", type = ParamType.PLAYER, doc = "A player.")""" }.toTypedArray()),
            "9 @Param, at most 8",
        )
    }

    @Test
    fun `bounds fit the parameter's type`() {
        assertProblems(
            withParams(
                """@Param(name = "who", type = ParamType.PLAYER, doc = "Who.", min = 1)""",
                """@Param(name = "loud", type = ParamType.BOOLEAN, doc = "Loud.", required = false, fallback = "true", max = 1)""",
                """@Param(name = "where", type = ParamType.PLACE, doc = "Where.", min = 0, max = 10)""",
            ),
            "@Param who: min and max bound only an integer, a number, how many players, or how long a text is",
            "@Param loud: min and max bound only an integer, a number, how many players, or how long a text is",
            "@Param where: min and max bound only an integer, a number, how many players, or how long a text is",
        )
        assertProblems(
            withParams(
                """@Param(name = "cows", type = ParamType.INTEGER, doc = "How many.", required = false, fallback = "3", min = 2.5)""",
                """@Param(name = "herd", type = ParamType.INTEGER, doc = "How many.", required = false, fallback = "3", max = 1e10)""",
                """@Param(name = "players", type = ParamType.PLAYERS, doc = "Who plays.", min = 2, max = 9)""",
                """@Param(name = "song", type = ParamType.TEXT, doc = "A song.", max = 301)""",
                """@Param(name = "speed", type = ParamType.NUMBER, doc = "How fast.", min = 3, max = 2)""",
                """@Param(name = "drift", type = ParamType.NUMBER, doc = "How far.", required = false, fallback = "1", min = Double.NaN)""",
            ),
            "@Param cows: min must be a whole number (got 2.5)",
            "@Param herd: max must fit an int, from -2147483648 to 2147483647 (got 10000000000)",
            "@Param players: max is how many players, from 0 to 8 (got 9)",
            "@Param song: max is how many characters, from 0 to 300 (got 301)",
            "@Param speed: min 3 is above max 2",
            "@Param drift: min and max must be numbers",
        )
    }

    @Test
    fun `a fallback is written in its type's text form, within its bounds and choices`() {
        assertProblems(
            withParams(
                """@Param(name = "cows", type = ParamType.INTEGER, doc = "How many.", required = false)""",
                """@Param(name = "speed", type = ParamType.NUMBER, doc = "How fast.", required = false)""",
                """@Param(name = "loud", type = ParamType.BOOLEAN, doc = "Loud.", required = false)""",
                """@Param(name = "who", type = ParamType.PLAYER, doc = "Who.", fallback = "Bartok")""",
            ),
            "@Param cows: an integer that is not required needs a fallback, the value it takes when left out",
            "@Param speed: a number that is not required needs a fallback, the value it takes when left out",
            "@Param loud: a boolean that is not required needs a fallback, the value it takes when left out",
            "@Param who: a required parameter has no fallback (write required = false to give it one)",
        )
        assertProblems(
            withParams(
                """@Param(name = "cows", type = ParamType.INTEGER, doc = "How many.", required = false, fallback = "six")""",
                """@Param(name = "herd", type = ParamType.INTEGER, doc = "How many.", required = false, fallback = "0x10")""",
                """@Param(name = "speed", type = ParamType.NUMBER, doc = "How fast.", required = false, fallback = "1.5d")""",
                """@Param(name = "drift", type = ParamType.NUMBER, doc = "How far.", required = false, fallback = "NaN")""",
                """@Param(name = "loud", type = ParamType.BOOLEAN, doc = "Loud.", required = false, fallback = "yes")""",
                """@Param(name = "who", type = ParamType.PLAYER, doc = "Who.", required = false, fallback = "Bar tok")""",
                """@Param(name = "where", type = ParamType.PLACE, doc = "Where.", required = false, fallback = "120 64")""",
                """@Param(name = "there", type = ParamType.PLACE, doc = "There.", required = false, fallback = "1 2 3 The Nether")""",
            ),
            "@Param cows: fallback \"six\" is not a whole number, e.g. \"6\"",
            "@Param herd: fallback \"0x10\" is not a whole number, e.g. \"6\"",
            "@Param speed: fallback \"1.5d\" is not a number, e.g. \"2.5\"",
            "@Param drift: fallback \"NaN\" is not a number, e.g. \"2.5\"",
            "@Param loud: fallback \"yes\" is not \"true\" nor \"false\"",
            "@Param who: fallback \"Bar tok\" is not a player's name",
            "@Param where: fallback \"120 64\" is not a place: \"120 64 -30\", \"120 64 -30 the_nether\" or a player's name",
            "@Param there: fallback \"1 2 3 The Nether\" is not a place: \"120 64 -30\", \"120 64 -30 the_nether\" or a player's name",
        )
        assertProblems(
            withParams(
                """@Param(name = "cows", type = ParamType.INTEGER, doc = "How many.", required = false, fallback = "12", min = 3, max = 8)""",
                """@Param(name = "speed", type = ParamType.NUMBER, doc = "How fast.", required = false, fallback = "0.1", min = 0.3)""",
                """@Param(name = "who", type = ParamType.PLAYERS, doc = "Who.", required = false, fallback = "Bartok,bartok")""",
                """@Param(name = "team", type = ParamType.PLAYERS, doc = "Team.", required = false, fallback = "Bartok", min = 2)""",
                """@Param(name = "crowd", type = ParamType.PLAYERS, doc = "Crowd.", required = false, fallback = "A,B,C,D,E,F,G,H,I")""",
                """@Param(name = "mood", type = ParamType.TEXT, doc = "Mood.", required = false, fallback = "gai", choices = {"taquin", "sévère"})""",
                """@Param(name = "song", type = ParamType.TEXT, doc = "Song.", required = false, fallback = "la la la", max = 5)""",
            ),
            "@Param cows: fallback \"12\" is above max 8",
            "@Param speed: fallback \"0.1\" is below min 0.3",
            "@Param who: fallback \"Bartok,bartok\" names a player twice",
            "@Param team: fallback \"Bartok\" has 1 players, below min 2",
            "@Param crowd: fallback \"A,B,C,D,E,F,G,H,I\" has 9 players, above 8",
            "@Param mood: fallback \"gai\" is not one of its choices (taquin, sévère)",
            "@Param song: fallback \"la la la\" is 8 characters long, above 5",
        )
    }

    @Test
    fun `choices are a text's, each once`() {
        assertProblems(
            withParams(
                """@Param(name = "cows", type = ParamType.INTEGER, doc = "How many.", required = false, fallback = "3", choices = {"3", "6"})""",
                """@Param(name = "mood", type = ParamType.TEXT, doc = "Mood.", choices = {"taquin", "taquin", ""})""",
            ),
            "@Param cows: choices are only for a text",
            "@Param mood: the choice \"taquin\" is there twice",
            "@Param mood: the choice \"\" is empty",
        )
    }

    @Test
    fun `Scenarios is a public static class of methods public void x(Bench b), eight at most`() {
        val helpers = Manifest.read(
            NAME,
            compile(
                module(
                    body = """
                        public void start(ModuleContext ctx) {}
                        public static final class Scenarios {
                            public void cowsDance(Bench b) { Runnable r = () -> b.run(1); r.run(); }
                            private void helper(Bench b) {}
                            public static void util(Bench b) {}
                            void notPublic(Bench b) {}
                            public void cowsStop(Bench b) { helper(b); }
                        }
                    """,
                ),
            ),
        )
        assertEquals(listOf("cowsDance", "cowsStop"), helpers.scenarios, "helpers, static methods and lambdas are not scenarios")

        assertProblems(
            module(body = "public void start(ModuleContext ctx) {} public final class Scenarios { public void a(Bench b) {} }"),
            "Scenarios must be a public static nested class: public static final class Scenarios { public void x(Bench b) { … } }",
        )
        assertProblems(
            module(body = "public void start(ModuleContext ctx) {} public static abstract class Scenarios { public void a(Bench b) {} }"),
            "Scenarios must be a concrete class: the bench makes an instance of it",
        )
        assertProblems(
            module(body = "public void start(ModuleContext ctx) {} static final class Scenarios { public Scenarios() {} public void a(Bench b) {} }"),
            "Scenarios must be a public static nested class: public static final class Scenarios { public void x(Bench b) { … } }",
        )
        assertProblems(
            module(body = "public void start(ModuleContext ctx) {} public static final class Scenarios { public Scenarios(int x) {} public void a(Bench b) {} }"),
            "Scenarios needs a public constructor without arguments (or no constructor at all)",
        )
        assertProblems(
            module(
                body = """
                    public void start(ModuleContext ctx) {}
                    public static final class Scenarios {
                        public void twoArgs(Bench b, int x) {}
                        public int answers(Bench b) { return 1; }
                        public void noBench() {}
                    }
                """,
            ),
            "Scenarios.twoArgs: a scenario is public void twoArgs(Bench b); make a helper private or static",
            "Scenarios.answers: a scenario is public void answers(Bench b); make a helper private or static",
            "Scenarios.noBench: a scenario is public void noBench(Bench b); make a helper private or static",
        )
        assertProblems(
            module(body = "public void start(ModuleContext ctx) {} public static final class Scenarios { ${(1..9).joinToString(" ") { "public void s$it(Bench b) {}" }} }"),
            "9 scenarios, at most 8",
        )
    }

    @Test
    fun `@Param and a hand-written @ParamList together are refused`() {
        assertProblems(
            module(
                annotations = INFO + """
                    @Param(name = "a", type = ParamType.PLAYER, doc = "A.")
                    @ParamList({@Param(name = "b", type = ParamType.PLAYER, doc = "B.")})
                """,
            ),
            "write @Param once per parameter, and never @ParamList yourself",
        )
    }

    @Test
    fun `every problem is told at once, as Invalid arguments`() {
        val rejected = assertFailsWith<ForgeRejected> {
            Manifest.read(
                NAME,
                compile(
                    module(
                        annotations = """
                            @ModuleInfo(name = "dancing_cows", summary = "")
                            @Param(name = "cows", type = ParamType.INTEGER, doc = "How many.", required = false)
                        """,
                        head = "final class DancingCows implements ForgeModule",
                    ),
                ),
            )
        }
        assertEquals(3, rejected.message.orEmpty().lines().size, rejected.message)
        assertTrue(rejected.toResult().text.startsWith("Invalid arguments: DancingCows must be public"), rejected.toResult().text)
    }

    @Test
    fun `a module's name is snake_case in ASCII and never a device of Windows`() {
        listOf("tag", "orbiting_chickens", "a_2", "x".repeat(32), "con_game", "nul_", "comx", "lpt10", "auxiliary").forEach {
            assertNull(Manifest.nameProblem(it), it)
        }
        val bad = listOf(
            "", "ab", "x".repeat(33), "Tag", "TAG", "1tag", "_tag", "tag-game", "tag game", "tag.java", "tag:x", "tag/x", "tag\\x",
            "../tag", "..\\tag", "..", ".", "/tag", "C:\\tag", "c:tag", "tag\u0000", "tag\n", "tag\t", "été", "tag\u200b", "ｔａｇ", "tıg",
            "tag$", "tag~1", "tag*", "tag?", "tag\"", "tag<", "tag>", "tag|", " tag", "tag ",
        )
        bad.forEach { assertTrue(Manifest.nameProblem(it)!!.startsWith("a module's name is snake_case"), it) }
        val devices = listOf("con", "prn", "aux", "nul") + (0..9).flatMap { listOf("com$it", "lpt$it") }
        assertEquals(24, devices.size)
        devices.forEach {
            assertEquals("\"$it\" is a name Windows keeps for a device: choose another", Manifest.nameProblem(it))
        }
        assertFailsWith<ForgeRejected> { Manifest.read("../tag", ForgeSamples.classes) }
    }

    @Test
    fun `the class is the name in UpperCamelCase`() {
        assertEquals("OrbitingChickens", Manifest.mainClass("orbiting_chickens"))
        assertEquals("Tag", Manifest.mainClass("tag"))
        assertEquals("FloorIsLava2", Manifest.mainClass("floor_is_lava_2"))
    }

    @Test
    fun `the hash is sha256 of the source's UTF-8 bytes`() {
        assertEquals(ForgeSamples.hash, Manifest.hash(ForgeSamples.SOURCE))
        assertEquals(Manifest.hash(ForgeSamples.SOURCE.toByteArray(Charsets.UTF_8)), Manifest.hash(ForgeSamples.SOURCE))
        assertTrue(Regex("sha256:[0-9a-f]{64}").matches(Manifest.hash("")))
    }

    @Test
    fun `the manifest file is the design's, and reads back the same version`() {
        val text = Manifest.encode(ForgeSamples.manifest)
        val json = Json.parseToJsonElement(text).jsonObject

        assertEquals(
            listOf("name", "version", "sdk", "hash", "saved_at", "by", "status", "summary", "params", "scenarios", "classes", "bytes", "note"),
            json.keys.toList(),
        )
        assertEquals("ready", json.getValue("status").jsonPrimitive.content)
        assertEquals(ForgeSamples.manifest.savedAt.toString(), json.getValue("saved_at").jsonPrimitive.content)
        val players = json.getValue("params").jsonArray[0].jsonObject
        assertEquals("players", players.getValue("type").jsonPrimitive.content)
        assertFalse("fallback" in players, "absent rather than null: $players")
        assertFalse("choices" in players)
        assertEquals(2, json.getValue("params").jsonArray[2].jsonObject.getValue("choices").jsonArray.size)

        assertEquals(ForgeSamples.manifest, Manifest.decode(text))
        val pending = ForgeSamples.manifest.copy(status = ModuleStatus.PENDING, note = null, savedAt = Instant.parse("2026-10-19T21:06:12Z"))
        assertEquals(pending, Manifest.decode(Manifest.encode(pending)))
        assertFalse("note" in Json.parseToJsonElement(Manifest.encode(pending)).jsonObject)
    }

    @Test
    fun `a file that is not a manifest is refused`() {
        val good = Manifest.encode(ForgeSamples.manifest)
        listOf(
            good.replace("\"ready\"", "\"broken\""),
            good.replace("\"ready\"", "\"READY\""),
            good.replace(ForgeSamples.hash, "sha256:abc"),
            good.replace("\"type\": \"players\"", "\"type\": \"crowd\""),
            good.replace("\"saved_at\": \"${ForgeSamples.manifest.savedAt}\"", "\"saved_at\": \"yesterday\""),
            good.replace("\"version\": 1", "\"version\": 0"),
            good.replace("\"summary\"", "\"summery\""),
            good.dropLast(10),
            "",
            "[]",
        ).forEach { text ->
            assertNotEquals(good, text)
            assertFailsWith<Exception>(text) { Manifest.decode(text) }
        }
    }

    private fun assertProblems(source: String, vararg expected: String, type: String = CLASS) {
        val rejected = assertFailsWith<ForgeRejected>(source) { Manifest.read(NAME, compile(source, type)) }
        assertEquals(expected.toList(), rejected.message.orEmpty().lines(), source)
        assertContains(rejected.toResult().text, "Invalid arguments: ")
    }

    private fun compile(source: String, type: String = CLASS): Map<String, ByteArray> {
        val compiled = ForgeSamples.compile(type, source)
        assertTrue(compiled.ok, "${compiled.problems}\n$source")
        return compiled.classes
    }

    private fun withParams(vararg params: String): String = module(annotations = INFO + params.joinToString("\n"))

    private fun module(
        annotations: String = INFO,
        head: String = "public final class DancingCows implements ForgeModule",
        body: String = "public void start(ModuleContext ctx) {}",
    ): String = "import io.github.clemsix6.narrator2.forge.api.*;\n" +
        "import io.github.clemsix6.narrator2.forge.api.test.Bench;\n" +
        "$annotations\n$head {\n$body\n}\n"

    private companion object {
        const val NAME = "dancing_cows"
        const val CLASS = "DancingCows"
        const val INFO = "@ModuleInfo(name = \"dancing_cows\", summary = \"Cows dance around a player.\")\n"
    }
}
