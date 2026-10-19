package io.github.clemsix6.narrator2.tool

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/** Decoding and checking the arguments of a tool call (M1_M2 §8.3). */
class ToolArgsTest {
    private fun <A : Any> ok(serializer: KSerializer<A>, input: String): A =
        when (val decoded = ToolArgs.decode(serializer, input)) {
            is ToolArgs.Decoded.Ok -> decoded.args
            is ToolArgs.Decoded.Invalid -> fail("expected valid arguments, got: ${decoded.problem}")
        }

    private fun <A : Any> problem(serializer: KSerializer<A>, input: String): String =
        when (val decoded = ToolArgs.decode(serializer, input)) {
            is ToolArgs.Decoded.Ok -> fail("expected invalid arguments, got ${decoded.args}")
            is ToolArgs.Decoded.Invalid -> decoded.problem
        }

    private fun problem(input: String) = problem(EveryTypeArgs.serializer(), input)

    @Test
    fun `valid arguments are decoded, with the defaults for what is left out`() {
        assertEquals(EveryTypeArgs(text = "hi", seconds = 30), ok(EveryTypeArgs.serializer(), """{"text":"hi","seconds":30}"""))
        assertEquals(
            EveryTypeArgs(
                text = "hi",
                to = listOf("Bob", "Mimosa"),
                seconds = 900,
                taskId = 12,
                volume = 0.5,
                particles = false,
                shape = Shape.SMALL_BALL,
                enchantments = listOf(Enchant("sharpness", 5), Enchant("unbreaking")),
                gear = Gear(head = "diamond_helmet"),
            ),
            ok(
                EveryTypeArgs.serializer(),
                """{"text":"hi","to":["Bob","Mimosa"],"seconds":900,"task_id":12,"volume":0.5,"particles":false,""" +
                    """"shape":"small_ball","enchantments":[{"id":"sharpness","level":5},{"id":"unbreaking"}],"gear":{"head":"diamond_helmet"}}""",
            ),
        )
    }

    @Test
    fun `a nullable argument may be null or left out`() {
        assertEquals(EveryTypeArgs(text = "hi", seconds = 1), ok(EveryTypeArgs.serializer(), """{"text":"hi","seconds":1,"to":null,"gear":null}"""))
    }

    @Test
    fun `unknown and missing arguments are named, with what is expected`() {
        assertEquals(
            "unknown argument \"txt\" (expected: text, to, seconds, task_id, volume, particles, shape, enchantments, gear); " +
                "text is required; seconds is required",
            problem("""{"txt":"hi"}"""),
        )
        assertEquals(
            "unknown argument \"enchantments[0].lvl\" (expected: id, level)",
            problem("""{"text":"hi","seconds":1,"enchantments":[{"id":"sharpness","lvl":2}]}"""),
        )
        assertEquals("enchantments[0].id is required", problem("""{"text":"hi","seconds":1,"enchantments":[{"level":2}]}"""))
    }

    @Test
    fun `null is refused where the argument is not nullable`() {
        assertEquals(
            "text must not be null; volume must not be null (leave it out to use the default)",
            problem("""{"text":null,"seconds":1,"volume":null}"""),
        )
        assertEquals("to[1] must not be null", problem("""{"text":"hi","seconds":1,"to":["Bob",null]}"""))
    }

    @Test
    fun `bounds say what is allowed and what was given`() {
        assertEquals("seconds must be between 1 and 900 (got 1200)", problem("""{"text":"hi","seconds":1200}"""))
        assertEquals("volume must be between 0.1 and 2 (got 0.05)", problem("""{"text":"hi","seconds":1,"volume":0.05}"""))
        assertEquals("text is longer than 300 characters (got 301)", problem("""{"text":"${"a".repeat(301)}","seconds":1}"""))
        assertEquals(
            "to must have 1 to 8 items (got 9)",
            problem("""{"text":"hi","seconds":1,"to":[${(1..9).joinToString { "\"p$it\"" }}]}"""),
        )
        assertEquals("to must have 1 to 8 items (got 0)", problem("""{"text":"hi","seconds":1,"to":[]}"""))
        assertEquals(
            "to[1] is longer than 16 characters (got 20)",
            problem("""{"text":"hi","seconds":1,"to":["Bob","${"b".repeat(20)}"]}"""),
        )
        assertEquals(
            "enchantments[1].level must be between 1 and 10 (got 11)",
            problem("""{"text":"hi","seconds":1,"enchantments":[{"id":"a"},{"id":"b","level":11}]}"""),
        )
        // Characters, not UTF-16 units: 300 emoji fit.
        ok(EveryTypeArgs.serializer(), """{"text":"${"😀".repeat(300)}","seconds":1}""")
    }

    @Test
    fun `wrong types are named with the value given`() {
        assertEquals("text must be a string (got 12)", problem("""{"text":12,"seconds":1}"""))
        assertEquals("seconds must be an integer (got \"ten\")", problem("""{"text":"hi","seconds":"ten"}"""))
        assertEquals("seconds must be an integer (got 2.5)", problem("""{"text":"hi","seconds":2.5}"""))
        assertEquals("volume must be a number (got \"loud\")", problem("""{"text":"hi","seconds":1,"volume":"loud"}"""))
        assertEquals("particles must be true or false (got \"yes\")", problem("""{"text":"hi","seconds":1,"particles":"yes"}"""))
        assertEquals("shape must be one of: small_ball, star (got \"circle\")", problem("""{"text":"hi","seconds":1,"shape":"circle"}"""))
        assertEquals("shape must be one of: small_ball, star (got \"STAR\")", problem("""{"text":"hi","seconds":1,"shape":"STAR"}"""))
        assertEquals("to must be a list (got \"Bob\")", problem("""{"text":"hi","seconds":1,"to":"Bob"}"""))
        assertEquals("gear must be an object (got 3)", problem("""{"text":"hi","seconds":1,"gear":3}"""))
        // A long value is cut to 40 characters.
        assertEquals(
            "text must be a string (got [\"${"a".repeat(37)}…)",
            problem("""{"text":["${"a".repeat(50)}"],"seconds":1}"""),
        )
    }

    private fun decoded(input: String): ToolArgs.Decoded.Ok<EveryTypeArgs> =
        when (val decoded = ToolArgs.decode(EveryTypeArgs.serializer(), input)) {
            is ToolArgs.Decoded.Ok -> decoded
            is ToolArgs.Decoded.Invalid -> fail("expected valid arguments, got: ${decoded.problem}")
        }

    @Test
    fun `a list written as the JSON text of a list is read as that list, and named`() {
        // What Opus 5.5 at effort low sometimes sends (M3-19, §21.2): "players": "[\"Mimosa\"]".
        val decoded = decoded("""{"text":"hi","seconds":1,"to":"[\"Bob\", \"Mimosa\"]"}""")
        assertEquals(EveryTypeArgs(text = "hi", seconds = 1, to = listOf("Bob", "Mimosa")), decoded.args)
        assertEquals(listOf("to"), decoded.coerced)
        // Its items are checked as any list's.
        assertEquals("to[0] is longer than 16 characters (got 20)", problem("""{"text":"hi","seconds":1,"to":"[\"${"b".repeat(20)}\"]"}"""))
        assertEquals("to must have 1 to 8 items (got 0)", problem("""{"text":"hi","seconds":1,"to":"[]"}"""))
    }

    @Test
    fun `an object written as JSON text is read as that object, inside a list too`() {
        val decoded = decoded(
            """{"text":"hi","seconds":1,"gear":"{\"head\": \"diamond_helmet\"}","enchantments":"[{\"id\":\"sharpness\",\"level\":\"5\"}]"}""",
        )
        assertEquals(
            EveryTypeArgs(text = "hi", seconds = 1, gear = Gear(head = "diamond_helmet"), enchantments = listOf(Enchant("sharpness", 5))),
            decoded.args,
        )
        assertEquals(listOf("gear", "enchantments", "enchantments[0].level"), decoded.coerced)
        assertEquals("unknown argument \"gear.hat\" (expected: head, mainhand)", problem("""{"text":"hi","seconds":1,"gear":"{\"hat\":\"x\"}"}"""))
    }

    @Test
    fun `numbers and booleans written as text are read as such`() {
        val decoded = decoded("""{"text":"hi","seconds":"30","task_id":"-12","volume":"0.5","particles":"false"}""")
        assertEquals(EveryTypeArgs(text = "hi", seconds = 30, taskId = -12, volume = 0.5, particles = false), decoded.args)
        assertEquals(listOf("seconds", "task_id", "volume", "particles"), decoded.coerced)
        assertEquals("seconds must be between 1 and 900 (got 1200)", problem("""{"text":"hi","seconds":"1200"}"""))
        // Valid arguments coerce nothing; a string stays a string, an enum value an enum value.
        assertEquals(emptyList(), decoded("""{"text":"[1, 2]","seconds":1,"shape":"star"}""").coerced)
    }

    @Test
    fun `a string that does not hold a value of the expected type is still refused, saying what was expected`() {
        assertEquals("to must be a list (got \"[\\\"Bob\\\"\")", problem("""{"text":"hi","seconds":1,"to":"[\"Bob\""}"""))
        assertEquals("to must be a list (got \"{\\\"a\\\":1}\")", problem("""{"text":"hi","seconds":1,"to":"{\"a\":1}"}"""))
        assertEquals("gear must be an object (got \"[]\")", problem("""{"text":"hi","seconds":1,"gear":"[]"}"""))
        assertEquals("seconds must be an integer (got \"2.5\")", problem("""{"text":"hi","seconds":"2.5"}"""))
        assertEquals("seconds must be an integer (got \"null\")", problem("""{"text":"hi","seconds":"null"}"""))
        assertEquals("particles must be true or false (got \"1\")", problem("""{"text":"hi","seconds":1,"particles":"1"}"""))
        assertEquals("shape must be one of: small_ball, star (got \"\\\"star\\\"\")", problem("""{"text":"hi","seconds":1,"shape":"\"star\""}"""))
    }

    @Serializable
    data class CountArgs(val count: Int)

    @Test
    fun `an integer too large for its type is refused`() {
        assertEquals("count is too large (got 3000000000)", problem(CountArgs.serializer(), """{"count":3000000000}"""))
    }

    @Test
    fun `a tool without arguments takes an empty object only`() {
        assertEquals(NoArgs, ok(NoArgs.serializer(), "{}"))
        assertEquals("unknown argument \"x\" (this tool takes no arguments)", problem(NoArgs.serializer(), """{"x":1}"""))
    }

    @Test
    fun `the arguments must be a JSON object`() {
        assertEquals("the arguments must be a JSON object", problem("[]"))
        assertEquals("the arguments must be a JSON object", problem("null"))
        assertEquals("the arguments are not valid JSON", problem("{"))
    }

    @Serializable
    data class OneOfArgs(val multiply: Double? = null, val add: Double? = null) {
        init {
            require((multiply == null) != (add == null)) { "give exactly one of multiply or add" }
        }
    }

    @Test
    fun `a rule over several arguments reaches the model from the arguments class`() {
        assertEquals(OneOfArgs(add = 2.0), ok(OneOfArgs.serializer(), """{"add":2}"""))
        assertEquals("give exactly one of multiply or add", problem(OneOfArgs.serializer(), """{"multiply":1.5,"add":2}"""))
        assertEquals("give exactly one of multiply or add", problem(OneOfArgs.serializer(), "{}"))
    }
}
