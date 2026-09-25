@file:OptIn(ExperimentalSerializationApi::class)

package io.github.clemsix6.narrator2.tool

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Decodes and checks the arguments of a tool call (M1_M2 §8.3), before the tool runs.
 *
 * The input is checked against the same descriptor as the schema ([ToolSchema]): unknown or missing
 * arguments, wrong types, enum values and the bounds of [Range], [MaxLen] and [Items]. Every problem
 * is reported at once, in words the model can act on, e.g. `seconds must be between 1 and 900 (got
 * 1200)`. Then the strict [json] decodes the arguments; an `init { require(…) }` in the arguments class
 * can check rules that span several arguments, its message reaching the model the same way.
 *
 * Checks that need the game (registry ids, online players, caps) belong to the tool's `run`.
 */
object ToolArgs {
    /** Strict decoding: no unknown key, no lenient literal, no silent default for a bad value; absent nullable = null. */
    val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        explicitNulls = false
    }

    /** Decoded arguments, or what is wrong with them. */
    sealed interface Decoded<out A : Any> {
        data class Ok<A : Any>(val args: A) : Decoded<A>

        /** [problem] follows `Invalid arguments: ` in the result sent to the model. */
        data class Invalid(val problem: String) : Decoded<Nothing>
    }

    /** Decodes [input], the JSON object of a `tool_use` block, into the arguments of [serializer]. */
    fun <A : Any> decode(serializer: KSerializer<A>, input: String): Decoded<A> {
        val element = try {
            json.parseToJsonElement(input)
        } catch (e: SerializationException) {
            return Decoded.Invalid("the arguments are not valid JSON")
        }
        if (element !is JsonObject) return Decoded.Invalid("the arguments must be a JSON object")
        val problems = mutableListOf<String>()
        checkObject(serializer.descriptor, element, "", problems)
        if (problems.isNotEmpty()) return Decoded.Invalid(problems.joinToString("; "))
        return try {
            Decoded.Ok(json.decodeFromJsonElement(serializer, element))
        } catch (e: IllegalArgumentException) { // SerializationException too, and require() in the arguments class
            Decoded.Invalid(e.message?.lineSequence()?.first()?.takeIf { it.isNotBlank() } ?: "the arguments could not be read")
        }
    }

    private fun checkObject(d: SerialDescriptor, value: JsonObject, path: String, problems: MutableList<String>) {
        val names = d.elementNames.toList()
        value.keys.filter { it !in names }.forEach { key ->
            val expected = if (names.isEmpty()) "this tool takes no arguments" else "expected: ${names.joinToString()}"
            problems += "unknown argument \"${join(path, key)}\" ($expected)"
        }
        for (i in 0 until d.elementsCount) {
            val property = join(path, d.getElementName(i))
            val element = value[d.getElementName(i)]
            when {
                element == null -> if (isRequired(d, i)) problems += "$property is required"
                element is JsonNull -> if (!d.getElementDescriptor(i).isNullable) {
                    problems += if (d.isElementOptional(i)) "$property must not be null (leave it out to use the default)" else "$property must not be null"
                }
                else -> check(d.getElementDescriptor(i), ArgAnnotations.of(d, i), element, property, problems)
            }
        }
    }

    /** Checks a value that is not null against its descriptor [d] and annotations [a]. */
    private fun check(d: SerialDescriptor, a: ArgAnnotations, value: JsonElement, path: String, problems: MutableList<String>) {
        val literal = (value as? JsonPrimitive)?.takeUnless { it.isString } // a number or a boolean
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        when (ArgType.of(d, path)) {
            ArgType.STRING -> when {
                text == null -> problems += "$path must be a string (got ${shown(value)})"
                a.maxLen != null -> {
                    val length = text.codePointCount(0, text.length)
                    if (length > a.maxLen.chars) problems += "$path is longer than ${a.maxLen.chars} characters (got $length)"
                }
            }
            ArgType.INTEGER -> {
                val number = literal?.longOrNull
                when {
                    number == null -> problems += "$path must be an integer (got ${shown(value)})"
                    d.kind == PrimitiveKind.INT && number.toInt().toLong() != number -> problems += "$path is too large (got $number)"
                    else -> checkRange(number.toDouble(), a.range, path, value, problems)
                }
            }
            ArgType.NUMBER -> {
                val number = literal?.doubleOrNull
                if (number == null) problems += "$path must be a number (got ${shown(value)})"
                else checkRange(number, a.range, path, value, problems)
            }
            ArgType.BOOLEAN -> if (literal?.booleanOrNull == null) problems += "$path must be true or false (got ${shown(value)})"
            ArgType.ENUM -> if (text == null || text !in d.elementNames) {
                problems += "$path must be one of: ${d.elementNames.joinToString()} (got ${shown(value)})"
            }
            ArgType.LIST -> if (value !is JsonArray) {
                problems += "$path must be a list (got ${shown(value)})"
            } else {
                checkList(d, a, value, path, problems)
            }
            ArgType.OBJECT -> if (value !is JsonObject) {
                problems += "$path must be an object (got ${shown(value)})"
            } else {
                checkObject(d, value, path, problems)
            }
        }
    }

    private fun checkList(d: SerialDescriptor, a: ArgAnnotations, value: JsonArray, path: String, problems: MutableList<String>) {
        a.items?.let { items ->
            if (value.size !in items.min..items.max) problems += "$path must have ${items.min} to ${items.max} items (got ${value.size})"
        }
        val item = d.getElementDescriptor(0)
        value.forEachIndexed { i, element ->
            when {
                element !is JsonNull -> check(item, a.forItems(), element, "$path[$i]", problems)
                !item.isNullable -> problems += "$path[$i] must not be null"
            }
        }
    }

    private fun checkRange(number: Double, range: Range?, path: String, value: JsonElement, problems: MutableList<String>) {
        if (range != null && (number < range.min || number > range.max)) {
            problems += "$path must be between ${formatBound(range.min)} and ${formatBound(range.max)} (got ${shown(value)})"
        }
    }

    private fun join(path: String, name: String) = if (path.isEmpty()) name else "$path.$name"

    /** [value] as the model wrote it, cut to 40 characters. */
    private fun shown(value: JsonElement): String {
        val text = value.toString()
        return if (text.length <= 40) text else text.take(39) + "…"
    }
}
