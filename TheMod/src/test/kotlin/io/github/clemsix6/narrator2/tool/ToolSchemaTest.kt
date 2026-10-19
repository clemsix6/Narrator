package io.github.clemsix6.narrator2.tool

import com.anthropic.core.jsonMapper
import kotlinx.serialization.Contextual
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** The JSON schemas generated from the tools' argument classes (M1_M2 §8.2). */
class ToolSchemaTest {
    private val mapper = jsonMapper()

    /** The definition of [tool] exactly as the SDK sends it to the gateway, key order included. */
    private fun sent(tool: NarratorTool<*>): String = Json.parseToJsonElement(mapper.writeValueAsString(ToolSchema.definition(tool))).toString()

    /** The expected file `schema/<name>.json`, compacted. */
    private fun expected(name: String): String {
        val text = checkNotNull(javaClass.getResourceAsStream("/narrator2/tool/schema/$name.json")) { "missing $name.json" }
            .use { it.readBytes().decodeToString() }
        return Json.parseToJsonElement(text).toString()
    }

    @Test
    fun `every supported type, annotation and nesting gives the expected schema`() {
        assertEquals(expected("every_type"), sent(everyTypeTool()))
    }

    @Test
    fun `a tool without arguments gives an empty object`() {
        assertEquals(expected("no_args"), sent(noArgsTool("no_args")))
    }

    @Test
    fun `two builds of the same tools give the same bytes, sorted by name`() {
        val first = ToolSet.of(listOf(everyTypeTool(), noArgsTool("no_args"), noArgsTool("list_players")))
        val second = ToolSet.of(listOf(noArgsTool("list_players"), noArgsTool("no_args"), everyTypeTool()))

        assertEquals(listOf("every_type", "list_players", "no_args"), first.names)
        assertContentEquals(mapper.writeValueAsBytes(first.definitions), mapper.writeValueAsBytes(second.definitions))
        assertEquals(first.names, first.definitions.map { it.asTool().name() })
    }

    @Test
    fun `no tool is strict`() {
        assertFalse("strict" in sent(everyTypeTool()))
    }

    @Test
    fun `a free JsonObject is an object without properties, in a list too (M4 §8-1)`() {
        assertEquals(
            """{"input_schema":{"type":"object","properties":{"effects":{"type":"array","minItems":1,"maxItems":4,"items":{"type":"object",""" +
                """"properties":{"tool":{"type":"string","maxLength":20},"args":{"type":"object","description":"The tool's other arguments."}},""" +
                """"required":["tool"],"additionalProperties":false}},"extra":{"type":"object"}},"required":["effects"],"additionalProperties":false},""" +
                """"name":"free_args","description":"A tool with free objects."}""",
            sent(TestTool("free_args", FreeArgs.serializer(), description = "A tool with free objects.")),
        )
    }

    @Serializable
    data class FreeEffect(@MaxLen(20) val tool: String, @Doc("The tool's other arguments.") val args: JsonObject = JsonObject(emptyMap()))

    @Serializable
    data class FreeArgs(@Items(1, 4) val effects: List<FreeEffect>, val extra: JsonObject? = null)

    @Serializable
    data class MaxLenOnFreeObjectArgs(@MaxLen(10) val args: JsonObject)

    @Serializable
    data class MapArgs(val scores: Map<String, Int>)

    @Serializable
    sealed class Target {
        @Serializable
        @SerialName("player")
        data class Player(val name: String) : Target()
    }

    @Serializable
    data class PolymorphicArgs(val target: Target)

    @Serializable
    data class ContextualArgs(@Contextual val id: UUID)

    @Serializable
    data class Node(val name: String, val children: List<Node> = emptyList())

    @Serializable
    data class CharArgs(val letter: Char)

    @Serializable
    data class CamelCaseArgs(val maxRows: Int)

    @Serializable
    enum class Loudness { LOUD }

    @Serializable
    data class LoudArgs(val loudness: Loudness)

    @Serializable
    data class MaxLenOnNumberArgs(@MaxLen(10) val count: Int)

    @Serializable
    data class RangeOnStringArgs(@Range(1.0, 2.0) val name: String)

    @Serializable
    data class ItemsOnStringArgs(@Items(1, 2) val name: String)

    @Serializable
    data class MaxLenOnObjectsArgs(@MaxLen(10) val enchantments: List<Enchant>)

    @Serializable
    data class InvertedRangeArgs(@Range(10.0, 1.0) val count: Int)

    private fun <A : Any> tool(args: KSerializer<A>, description: String = "A test tool.") =
        TestTool("broken", args, description = description)

    @Test
    fun `what the schema cannot describe fails when the tool set is built, naming the argument`() {
        val cases = listOf(
            tool(MapArgs.serializer()) to "broken.scores: unsupported type",
            tool(PolymorphicArgs.serializer()) to "broken.target: unsupported type",
            tool(ContextualArgs.serializer()) to "broken.id: unsupported type",
            tool(Node.serializer()) to "broken.children[]: recursive type",
            tool(CharArgs.serializer()) to "broken.letter: unsupported type",
            tool(CamelCaseArgs.serializer()) to "argument \"maxRows\" is not snake_case",
            tool(LoudArgs.serializer()) to "enum value \"LOUD\" is not snake_case",
            tool(MaxLenOnNumberArgs.serializer()) to "broken.count: @MaxLen only applies to strings (this is integer)",
            tool(RangeOnStringArgs.serializer()) to "broken.name: @Range only applies to numbers (this is string)",
            tool(ItemsOnStringArgs.serializer()) to "broken.name: @Items only applies to lists (this is string)",
            tool(MaxLenOnObjectsArgs.serializer()) to "broken.enchantments[]: @MaxLen only applies to strings (this is object)",
            tool(InvertedRangeArgs.serializer()) to "broken.count: @Range min is above max",
            tool(MaxLenOnFreeObjectArgs.serializer()) to "broken.args: @MaxLen only applies to strings (this is object)",
            tool(String.serializer()) to "broken: the arguments must be a non-null @Serializable class",
            tool(NoArgs.serializer(), description = " ") to "broken: the description is empty",
        )
        for ((tool, message) in cases) {
            val error = assertFailsWith<IllegalArgumentException>(message) { ToolSet.of(listOf(tool)) }
            assertContains(error.message.orEmpty(), message)
        }
    }

    @Test
    fun `two tools with the same name cannot share a set`() {
        val error = assertFailsWith<IllegalArgumentException> { ToolSet.of(listOf(noArgsTool("same"), noArgsTool("same"))) }
        assertContains(error.message.orEmpty(), "duplicate tool names")
    }
}
