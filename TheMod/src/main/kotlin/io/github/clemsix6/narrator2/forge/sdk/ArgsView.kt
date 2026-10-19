package io.github.clemsix6.narrator2.forge.sdk

import io.github.clemsix6.narrator2.core.onServerThread
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.forge.ParamSpec
import io.github.clemsix6.narrator2.forge.arsenal.Manifest
import io.github.clemsix6.narrator2.forge.api.Args
import io.github.clemsix6.narrator2.forge.api.ForgeRefusal
import io.github.clemsix6.narrator2.forge.api.ParamType
import io.github.clemsix6.narrator2.forge.api.Place
import io.github.clemsix6.narrator2.mechanics.MechanicRejected
import io.github.clemsix6.narrator2.observer.dimensionId
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.Refusal
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.tool.memory.knownPlayers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.fabricmc.fabric.api.entity.FakePlayer

/*
 * A deployment's parameters (M5 §4.2, §6.4 step 3). `ParamCheck` checks what the Director gave against the module's
 * manifest when he deploys it, and resolves it: its result is what `ModuleRun.params` keeps, so that a restart resolves
 * nothing again (a place given by a player's name stays where they stood). `ArgsView` is what the module reads, from
 * that form. The formats are `ParamType`'s: JSON, or the text form in a JSON string (`"6"`, `"Bartok,Mimosa"`,
 * `"120 64 -30 the_nether"`), as a fallback and the bench's `start("name=value")` write them.
 */

/** What [ParamCheck] needs of the server: the players it knows, where the online ones stand, its dimensions. */
interface ParamLookup {
    /** The full ids of the server's dimensions (`minecraft:overworld`). */
    val dimensions: Set<String>

    /**
     * The players [names] the server knows (who played on it, online or not), in order, with their current names;
     * throws [MechanicRejected] (`Invalid arguments`) naming [field] and the unknown ones otherwise.
     */
    suspend fun players(names: List<String>, field: String): List<PlayerRef>

    /** Where the online player [name] (ignoring case) stands now, their feet; null when they are not online. */
    suspend fun standing(name: String): Place?
}

/**
 * A deployment's parameters, checked: [params] in their resolved form (every parameter of the manifest, `null` for
 * none), to keep in `ModuleRun.params`; [players] the players they name, each once (their UUIDs are
 * `ModuleRun.playerIds`, the canon row's players).
 */
data class CheckedParams(val params: JsonObject, val players: List<PlayerRef>)

/** The check of a deployment's parameters against the module's manifest (M5 §6.4 step 3). */
object ParamCheck {
    /**
     * [given] (`deploy_module params`) against [specs], the manifest's parameters, in their order: no unknown name;
     * each required one given; each value of its type, within its bounds and choices; its players known to the server
     * ([lookup]); a place in a dimension the server has, a player's name resolved to where they stand now. A parameter
     * left out takes its fallback, checked and resolved alike. Throws [MechanicRejected] (`Invalid arguments:
     * params.chickens must be between 3 and 8 (got 12)`) at the first problem. Never on the server thread ([lookup]
     * may wait for it).
     */
    suspend fun check(specs: List<ParamSpec>, given: JsonObject, lookup: ParamLookup): CheckedParams {
        val names = specs.map { it.name }
        given.keys.firstOrNull { it !in names }?.let { unknown ->
            reject("params.$unknown is not a parameter of this module; its parameters: ${names.joinToString().ifEmpty { "none" }}")
        }
        val resolved = LinkedHashMap<String, JsonElement>()
        val players = ArrayList<PlayerRef>()
        for (spec in specs) {
            val field = "params.${spec.name}"
            val raw = given[spec.name]?.takeUnless { it is JsonNull || it is JsonArray && it.isEmpty() }
            val value = raw ?: spec.fallback?.let { JsonPrimitive(it) }
            resolved[spec.name] = when {
                value != null -> resolve(spec, value, if (raw == null) "$field (its fallback)" else field, lookup, players)
                spec.required -> reject("$field is required: ${spec.doc}")
                else -> JsonNull
            }
        }
        return CheckedParams(JsonObject(resolved), players.distinctBy { it.uuid })
    }

    private suspend fun resolve(spec: ParamSpec, value: JsonElement, field: String, lookup: ParamLookup, players: MutableList<PlayerRef>): JsonElement =
        parsing(::reject) {
            when (spec.type) {
                ParamType.PLAYER -> {
                    val player = lookup.players(listOf(ParamValues.name(value, field)), field).firstOrNull() ?: reject("$field names no player the server knows")
                    players += player
                    JsonPrimitive(player.name)
                }
                ParamType.PLAYERS -> {
                    val names = ParamValues.names(value, field).ifEmpty { reject("$field must name at least one player") }
                    bounded(field, "name", names.size.toDouble(), spec.min, minOf(spec.max ?: PLAYERS_MAX, PLAYERS_MAX), " players")
                    names.groupBy { it.lowercase() }.values.firstOrNull { it.size > 1 }?.let { reject("$field names ${it.first()} twice") }
                    val found = lookup.players(names, field)
                    if (found.size < names.size || found.distinctBy { it.uuid }.size < found.size) reject("$field names the same player twice")
                    players += found
                    JsonArray(found.map { JsonPrimitive(it.name) })
                }
                ParamType.INTEGER -> ParamValues.integer(value, field).also { bounded(field, "be", it.toDouble(), spec.min, spec.max, "") }.let { JsonPrimitive(it) }
                ParamType.NUMBER -> ParamValues.number(value, field).also { bounded(field, "be", it, spec.min, spec.max, "") }.let { JsonPrimitive(it) }
                ParamType.TEXT -> JsonPrimitive(text(spec, ParamValues.primitive(value, field).trim(), field))
                ParamType.BOOLEAN -> JsonPrimitive(ParamValues.bool(value, field))
                ParamType.PLACE -> json(place(ParamValues.place(value, field), field, lookup))
            }
        }

    private fun text(spec: ParamSpec, text: String, field: String): String {
        if (text.isEmpty()) reject("$field must not be empty")
        bounded(field, "have", text.length.toDouble(), spec.min, minOf(spec.max ?: TEXT_MAX, TEXT_MAX), " characters")
        if (spec.choices.isEmpty()) return text
        return spec.choices.firstOrNull { it.equals(text, ignoreCase = true) }
            ?: reject("$field must be one of: ${spec.choices.joinToString()} (got \"$text\")")
    }

    private suspend fun place(given: ParamValues.PlaceValue, field: String, lookup: ParamLookup): Place = when (given) {
        is ParamValues.PlaceValue.Named -> lookup.standing(given.player)
            ?: reject("$field: ${given.player} is not online (a place given by a player's name is where they stand now); give x, y and z instead")
        is ParamValues.PlaceValue.At -> {
            val id = ToolText.dimensionId(given.dimension)
            if (id !in lookup.dimensions) {
                reject("$field: unknown dimension \"${given.dimension}\"; dimensions: ${lookup.dimensions.joinToString { ToolText.dimension(it) }}")
            }
            Place(id, given.x, given.y, given.z)
        }
    }

    /** A place as `ModuleRun.params` keeps it: `{"x": 120.5, "y": 64.0, "z": -30.5, "dimension": "the_nether"}`. */
    fun json(place: Place): JsonObject = JsonObject(
        mapOf("x" to JsonPrimitive(place.x), "y" to JsonPrimitive(place.y), "z" to JsonPrimitive(place.z), "dimension" to JsonPrimitive(place.dimension)),
    )

    /** [got] within [min] and [max] (null: unbounded), or a refusal: `params.chickens must be between 3 and 8 (got 12)`. */
    private fun bounded(field: String, verb: String, got: Double, min: Double?, max: Double?, unit: String) {
        if ((min == null || got >= min) && (max == null || got <= max)) return
        val range = when {
            min != null && max != null -> "between ${n(min)} and ${n(max)}"
            min != null -> "at least ${n(min)}"
            else -> "at most ${n(max!!)}"
        }
        reject("$field must $verb $range$unit (got ${n(got)})")
    }

    private fun n(value: Double): String = ToolText.number(value)

    private fun reject(message: String): Nothing = throw MechanicRejected(null, message)

    /** The caps of `@Param`'s Javadoc, one source with the manifest's check: 8 players, 300 characters. */
    private const val PLAYERS_MAX = Manifest.PARAM_PLAYERS.toDouble()
    private const val TEXT_MAX = Manifest.PARAM_TEXT_CHARS.toDouble()
}

/**
 * What a module reads of its parameters ([Args], M5 §4.2): [params] in the form [ParamCheck] resolved, checked
 * against [specs] once, here. A parameter missing from [params] reads its fallback (a fallback that names a player for
 * a place cannot be resolved here: [IllegalArgumentException], like any value that is not of its type). Reading a
 * name the module does not declare, or with the method of another type, throws [ForgeRefusal]; so does any method
 * once the deployment ended ([close]). Lists come unmodifiable.
 */
class ArgsView(private val specs: List<ParamSpec>, params: JsonObject) : Args {
    private val values: Map<String, Any?> = specs.associate { spec -> spec.name to value(spec, params[spec.name]) }
    private var closed = false

    override fun player(name: String?): String? = read(name, ParamType.PLAYER) as String?

    override fun players(name: String?): List<String> {
        @Suppress("UNCHECKED_CAST")
        return read(name, ParamType.PLAYERS) as List<String>
    }

    override fun integer(name: String?): Int = read(name, ParamType.INTEGER) as Int? ?: none(name)

    override fun number(name: String?): Double = (read(name, ParamType.NUMBER, ParamType.INTEGER) as Number?)?.toDouble() ?: none(name)

    override fun text(name: String?): String? = read(name, ParamType.TEXT) as String?

    override fun bool(name: String?): Boolean = read(name, ParamType.BOOLEAN) as Boolean? ?: none(name)

    override fun place(name: String?): Place? = read(name, ParamType.PLACE) as Place?

    /** The deployment ended: every method throws [ForgeRefusal] from now on. */
    fun close() {
        closed = true
    }

    private fun read(name: String?, vararg types: ParamType): Any? {
        if (closed) throw ForgeRefusal("the deployment has ended: its SDK objects no longer work")
        val spec = specs.firstOrNull { it.name == name }
            ?: throw ForgeRefusal("Args: no parameter named \"$name\"; ${if (specs.isEmpty()) "the module declares none" else "its parameters: ${specs.joinToString { it.name }}"}")
        if (spec.type !in types) {
            throw ForgeRefusal("Args.${method(types.first())}: \"$name\" is a ${spec.type} parameter: read it with args().${method(spec.type)}(\"$name\")")
        }
        return values[spec.name]
    }

    private fun none(name: String?): Nothing = throw ForgeRefusal("Args: \"$name\" has no value (it was left out, without a fallback)")

    private fun method(type: ParamType): String = when (type) {
        ParamType.BOOLEAN -> "bool"
        ParamType.PLAYERS -> "players"
        else -> type.name.lowercase()
    }

    private companion object {
        /** [element] of [spec] as the module reads it; its fallback when missing. Throws [IllegalArgumentException]. */
        fun value(spec: ParamSpec, element: JsonElement?): Any? {
            val field = "params.${spec.name}"
            val value = when (element) {
                null -> spec.fallback?.let { JsonPrimitive(it) }
                is JsonNull -> null
                else -> element
            } ?: return if (spec.type == ParamType.PLAYERS) emptyList<String>() else null
            return parsing({ throw IllegalArgumentException(it) }) {
                when (spec.type) {
                    ParamType.PLAYER -> ParamValues.name(value, field)
                    ParamType.PLAYERS -> java.util.List.copyOf(ParamValues.names(value, field))
                    ParamType.INTEGER -> ParamValues.integer(value, field)
                    ParamType.NUMBER -> ParamValues.number(value, field)
                    ParamType.TEXT -> ParamValues.primitive(value, field)
                    ParamType.BOOLEAN -> ParamValues.bool(value, field)
                    ParamType.PLACE -> when (val place = ParamValues.place(value, field)) {
                        is ParamValues.PlaceValue.At -> Place(place.dimension, place.x, place.y, place.z)
                        is ParamValues.PlaceValue.Named -> throw IllegalArgumentException("$field: ${place.player}'s place is resolved when the module is deployed")
                    }
                }
            }
        }
    }
}

/**
 * The server's [ParamLookup] (M5 §6.4 step 3): the players the Journal knows, as `create_rule` reads them (`Caller`: the
 * Director's turn that deploys); where an online player (not a fake one) stands, read on the server thread; the
 * server's dimensions. Never on the server thread.
 */
class ServerParamLookup(private val services: NarratorServices, private val caller: Caller) : ParamLookup {
    override val dimensions: Set<String> get() = services.server.levelKeys().mapTo(LinkedHashSet()) { it.identifier().toString() }

    override suspend fun players(names: List<String>, field: String): List<PlayerRef> = try {
        ToolContext(services, caller, services.clock, TIMEOUT_MS).knownPlayers(names, field)
    } catch (e: Refusal) {
        throw MechanicRejected(null, e.result.text.removePrefix("Invalid arguments: "))
    }

    override suspend fun standing(name: String): Place? = services.server.onServerThread(TIMEOUT_MS) {
        services.server.playerList.players.firstOrNull { it !is FakePlayer && it.plainTextName.equals(name, ignoreCase = true) }
            ?.let { Place(it.level().dimensionId, it.x, it.y, it.z) }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}

/** The values of `ParamType`'s formats, before any lookup: JSON, or the text form in a JSON string. */
internal object ParamValues {
    /** A place: coordinates (dimension as given, the overworld by default), or an online player's name. */
    sealed interface PlaceValue {
        data class At(val dimension: String, val x: Double, val y: Double, val z: Double) : PlaceValue

        data class Named(val player: String) : PlaceValue
    }

    /** What went wrong with a value: [parsing] turns it into the caller's own refusal. */
    class Problem(message: String) : Exception(message)

    /** The text of a JSON string, number or boolean. */
    fun primitive(value: JsonElement, field: String): String =
        (value as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content ?: problem("$field must be a single value, not $value")

    /** A player's name. */
    fun name(value: JsonElement, field: String): String = primitive(value, field).trim().ifEmpty { problem("$field must name a player") }

    /** Players' names: a JSON array, or `Bartok,Mimosa`. */
    fun names(value: JsonElement, field: String): List<String> {
        val raw = if (value is JsonArray) value.map { primitive(it, field) } else primitive(value, field).split(',')
        return raw.map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun integer(value: JsonElement, field: String): Int {
        val text = primitive(value, field).trim()
        val number = text.toLongOrNull() ?: text.toDoubleOrNull()?.takeIf { it.isFinite() && it == Math.rint(it) }?.toLong()
        return number?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt() ?: problem("$field must be a whole number (got $text)")
    }

    fun number(value: JsonElement, field: String): Double {
        val text = primitive(value, field).trim()
        return text.toDoubleOrNull()?.takeIf { it.isFinite() } ?: problem("$field must be a number (got $text)")
    }

    fun bool(value: JsonElement, field: String): Boolean = when (primitive(value, field).trim().lowercase()) {
        "true" -> true
        "false" -> false
        else -> problem("$field must be true or false (got $value)")
    }

    /** `{"x", "y", "z", "dimension"?}`, `120 64 -30`, `120 64 -30 the_nether`, or a player's name. */
    fun place(value: JsonElement, field: String): PlaceValue {
        if (value is JsonObject) {
            val extra = value.keys - PLACE_KEYS
            if (extra.isNotEmpty()) problem("$field takes x, y, z and dimension (got ${extra.joinToString()})")
            fun coordinate(key: String): Double = number(value[key] ?: problem("$field needs $key"), "$field.$key")
            val dimension = value["dimension"]?.takeUnless { it is JsonNull }?.let { primitive(it, "$field.dimension").ifBlank { problem("$field.dimension must name a dimension") } } ?: OVERWORLD
            return PlaceValue.At(dimension, coordinate("x"), coordinate("y"), coordinate("z"))
        }
        val text = primitive(value, field).trim()
        val words = text.split(WHITESPACE)
        val numbers = words.take(3).mapNotNull { word -> word.toDoubleOrNull()?.takeIf { it.isFinite() } }
        return when {
            words.size in 3..4 && numbers.size == 3 -> PlaceValue.At(words.getOrElse(3) { OVERWORLD }, numbers[0], numbers[1], numbers[2])
            words.size == 1 && text.isNotEmpty() -> PlaceValue.Named(text)
            else -> problem("$field must be x, y, z and an optional dimension (\"120 64 -30 the_nether\"), or an online player's name (got \"$text\")")
        }
    }

    private const val OVERWORLD = "overworld"
    private val PLACE_KEYS = setOf("x", "y", "z", "dimension")
    private val WHITESPACE = Regex("\\s+")

    private fun problem(message: String): Nothing = throw Problem(message)
}

/** Runs [block], a [ParamValues.Problem] turned into [fail]. */
private inline fun <T> parsing(fail: (String) -> Nothing, block: () -> T): T = try {
    block()
} catch (e: ParamValues.Problem) {
    fail(e.message.orEmpty())
}
