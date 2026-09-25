@file:OptIn(ExperimentalSerializationApi::class)

package io.github.clemsix6.narrator2.tool

import com.anthropic.core.JsonValue
import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolUnion
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.math.abs

/**
 * The JSON schema of a tool's arguments, generated from their serializer and annotations (M1_M2 §8.2):
 * the schema and the decoding ([ToolArgs]) read the same descriptor, so they cannot drift apart.
 *
 * - `String` → `string`, `Int`/`Long` → `integer`, `Double`/`Float` → `number`, `Boolean` → `boolean`,
 *   an enum → `string` with `enum` (its serial names), `List<T>` → `array`, a nested `@Serializable`
 *   class → `object`.
 * - Every object lists its properties in declaration order, `required` = the properties without a
 *   default value that are not nullable, and `additionalProperties: false`.
 * - [Doc] → `description`, [Range] → `minimum`/`maximum`, [MaxLen] → `maxLength`, [Items] →
 *   `minItems`/`maxItems`. On a list, [Range] and [MaxLen] bound each item.
 * - Anything else (maps, polymorphism, contextual or inline types, recursion, a misplaced annotation,
 *   a name that is not snake_case) fails when the tool set is built, naming the argument.
 *
 * No `strict: true`: strict tools are capped per request, refuse `minimum`/`maximum`/`maxLength`, and
 * their passage through LLMGW is not verified. The server checks every argument anyway.
 *
 * The output is deterministic: two builds of the same tool give the same bytes, so the cached prefix
 * of a conversation stays valid.
 */
object ToolSchema {
    private val mapper = jsonMapper()

    /** The definition of [tool] sent to the model: its name, description and input schema. */
    fun definition(tool: NarratorTool<*>): ToolUnion {
        require(tool.description.isNotBlank()) { "${tool.name}: the description is empty" }
        val schema = of(tool.args.descriptor, tool.name)
        val properties = Tool.InputSchema.Properties.builder()
        schema.getValue("properties").jsonObject.forEach { (name, property) -> properties.putAdditionalProperty(name, sdk(property)) }
        val input = Tool.InputSchema.builder().properties(properties.build())
        schema["required"]?.let { required -> input.required(required.jsonArray.map { it.jsonPrimitive.content }) }
        input.putAdditionalProperty("additionalProperties", JsonValue.from(false))
        return ToolUnion.ofTool(Tool.builder().name(tool.name).description(tool.description).inputSchema(input.build()).build())
    }

    /**
     * The input schema of the arguments described by [descriptor], always an object. Fails on a type
     * the schema cannot describe; [where] names the tool in the message.
     */
    fun of(descriptor: SerialDescriptor, where: String = descriptor.serialName): JsonObject {
        require(ArgType.of(descriptor, where) == ArgType.OBJECT && !descriptor.isNullable) {
            "$where: the arguments must be a non-null @Serializable class or object (got ${descriptor.serialName})"
        }
        return value(descriptor, ArgAnnotations.NONE, where, emptySet())
    }

    private fun value(d: SerialDescriptor, a: ArgAnnotations, where: String, open: Set<String>): JsonObject {
        val type = ArgType.of(d, where)
        // On a list, the bounds of values go to its items.
        val range = a.range.takeIf { type != ArgType.LIST }
        val maxLen = a.maxLen.takeIf { type != ArgType.LIST }
        range?.let {
            require(type == ArgType.INTEGER || type == ArgType.NUMBER) { "$where: @Range only applies to numbers (this is ${type.schemaType})" }
            require(it.min <= it.max) { "$where: @Range min is above max" }
        }
        maxLen?.let {
            require(type == ArgType.STRING) { "$where: @MaxLen only applies to strings (this is ${type.schemaType})" }
            require(it.chars > 0) { "$where: @MaxLen must be positive" }
        }
        a.items?.let {
            require(type == ArgType.LIST) { "$where: @Items only applies to lists (this is ${type.schemaType})" }
            require(it.min in 0..it.max) { "$where: @Items needs 0 <= min <= max" }
        }
        return buildJsonObject {
            put("type", type.schemaType)
            a.doc?.let { put("description", it) }
            if (type == ArgType.ENUM) {
                d.elementNames.forEach { requireSnakeCase(it, "$where: enum value") }
                putJsonArray("enum") { d.elementNames.forEach { add(JsonPrimitive(it)) } }
            }
            range?.let {
                put("minimum", number(it.min))
                put("maximum", number(it.max))
            }
            maxLen?.let { put("maxLength", it.chars) }
            a.items?.let {
                put("minItems", it.min)
                put("maxItems", it.max)
            }
            when (type) {
                ArgType.LIST -> put("items", value(d.getElementDescriptor(0), a.forItems(), "$where[]", open))
                ArgType.OBJECT -> objectParts(d, where, open)
                else -> Unit
            }
        }
    }

    private fun JsonObjectBuilder.objectParts(d: SerialDescriptor, where: String, open: Set<String>) {
        val name = d.serialName.removeSuffix("?")
        require(name !in open) { "$where: recursive type $name" }
        val inside = open + name
        put(
            "properties",
            buildJsonObject {
                for (i in 0 until d.elementsCount) {
                    val property = d.getElementName(i)
                    requireSnakeCase(property, "$where: argument")
                    put(property, value(d.getElementDescriptor(i), ArgAnnotations.of(d, i), "$where.$property", inside))
                }
            },
        )
        val required = (0 until d.elementsCount).filter { isRequired(d, it) }.map { d.getElementName(it) }
        if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
        put("additionalProperties", false)
    }

    private fun requireSnakeCase(name: String, what: String) =
        require(SNAKE_CASE.matches(name)) { "$what \"$name\" is not snake_case (use @SerialName)" }

    /** Whole numbers are written without a decimal point: `1`, not `1.0`. */
    private fun number(value: Double): JsonPrimitive = if (isWhole(value)) JsonPrimitive(value.toLong()) else JsonPrimitive(value)

    /** [element] as an SDK value, keeping the order of its keys. */
    private fun sdk(element: JsonElement): JsonValue = JsonValue.fromJsonNode(mapper.readTree(element.toString()))

    private val SNAKE_CASE = Regex("[a-z][a-z0-9_]*")
}

/** The JSON types a tool argument can have (M1_M2 §8.2). Shared by [ToolSchema] and [ToolArgs]. */
internal enum class ArgType(val schemaType: String) {
    STRING("string"),
    INTEGER("integer"),
    NUMBER("number"),
    BOOLEAN("boolean"),
    ENUM("string"),
    LIST("array"),
    OBJECT("object"),
    ;

    companion object {
        /** The type of [d]; fails on a type the schema cannot describe, naming [where]. */
        fun of(d: SerialDescriptor, where: String): ArgType {
            require(!d.isInline) { "$where: inline classes are not supported (${d.serialName})" }
            return when (d.kind) {
                PrimitiveKind.STRING -> STRING
                PrimitiveKind.INT, PrimitiveKind.LONG -> INTEGER
                PrimitiveKind.DOUBLE, PrimitiveKind.FLOAT -> NUMBER
                PrimitiveKind.BOOLEAN -> BOOLEAN
                SerialKind.ENUM -> ENUM
                StructureKind.LIST -> LIST
                StructureKind.CLASS, StructureKind.OBJECT -> OBJECT
                else -> throw IllegalArgumentException(
                    "$where: unsupported type ${d.serialName} (${d.kind}); use strings, integers, numbers, booleans, " +
                        "enums, lists or @Serializable classes",
                )
            }
        }
    }
}

/** The annotations of one argument. On a list, [range] and [maxLen] apply to each item. */
internal class ArgAnnotations(val doc: String?, val range: Range?, val maxLen: MaxLen?, val items: Items?) {
    /** What applies to each item of a list. */
    fun forItems() = ArgAnnotations(null, range, maxLen, null)

    companion object {
        val NONE = ArgAnnotations(null, null, null, null)

        /** The annotations of the argument [index] of [d]. */
        fun of(d: SerialDescriptor, index: Int): ArgAnnotations {
            val all = d.getElementAnnotations(index)
            return ArgAnnotations(
                doc = all.filterIsInstance<Doc>().firstOrNull()?.text,
                range = all.filterIsInstance<Range>().firstOrNull(),
                maxLen = all.filterIsInstance<MaxLen>().firstOrNull(),
                items = all.filterIsInstance<Items>().firstOrNull(),
            )
        }
    }
}

/** Arguments without a default value must be given, unless they are nullable (absent = null). */
internal fun isRequired(d: SerialDescriptor, index: Int): Boolean =
    !d.isElementOptional(index) && !d.getElementDescriptor(index).isNullable

internal fun isWhole(value: Double): Boolean = value % 1.0 == 0.0 && abs(value) < 1e15

/** A bound as the model reads it: `1`, `0.5`. */
internal fun formatBound(value: Double): String = if (isWhole(value)) value.toLong().toString() else value.toString()
