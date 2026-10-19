package io.github.clemsix6.narrator2.forge.sim

import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.forge.api.Acts
import io.github.clemsix6.narrator2.forge.api.Cell
import io.github.clemsix6.narrator2.forge.api.Game
import io.github.clemsix6.narrator2.forge.api.Ground
import io.github.clemsix6.narrator2.forge.api.Pending
import io.github.clemsix6.narrator2.forge.api.Place
import io.github.clemsix6.narrator2.forge.api.Spawn
import io.github.clemsix6.narrator2.forge.sdk.Act
import io.github.clemsix6.narrator2.forge.sdk.ActQueue
import io.github.clemsix6.narrator2.forge.sdk.ActRunner
import io.github.clemsix6.narrator2.forge.sdk.ModuleActs
import io.github.clemsix6.narrator2.forge.sdk.ModuleCreatures
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.mechanics.EngineActions
import io.github.clemsix6.narrator2.mechanics.Firing
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.tool.ToolText
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * An act as the bench shows it (`Bench.acts`): [line], then how it ended once the queue answered it, when that is not
 * `ok` (`punish Bartok tiny 2 → denied`).
 */
internal class ActRecord(private val text: String) {
    @Volatile
    private var status: String? = null

    /** [result], the act's answer: the first one stays (a late `dropped` after a refusal changes nothing). */
    fun settled(result: ToolResult) {
        if (status == null) status = statusOf(result)
    }

    val line: String get() = text + (status?.takeIf { it != ToolStatus.OK.id }?.let { " → $it" } ?: "")

    companion object {
        /** The tool name of a judged challenge's end, for `Bench.refuseNext` and the counts. */
        const val GAME = "game"

        /** The status of [result] as the module reads it; `dropped` for the queue's own drop (`FAILED: dropped: …`). */
        fun statusOf(result: ToolResult): String =
            if (result.status == ToolStatus.FAILED && result.text.startsWith("FAILED: ${ActQueue.DROPPED}:")) ActQueue.DROPPED else ActQueue.outcome(result).status

        /** The status of an answer a scenario imposed, read from its start (`DENIED (…)`, `Invalid arguments: …`, `FAILED: …`, `ok: …`). */
        fun statusOf(text: String): ToolStatus = when {
            text.startsWith("ok") -> ToolStatus.OK
            text.startsWith("Invalid arguments") -> ToolStatus.INVALID
            text.startsWith("FAILED") -> ToolStatus.FAILED
            text.startsWith("UNKNOWN") -> ToolStatus.UNKNOWN
            else -> ToolStatus.DENIED
        }

        /** Whether the SDK's own [refusal] of an act is the module's to fix (a forbidden effect…), not a limit of the moment. */
        fun lasting(refusal: ToolResult): Boolean =
            refusal.status != ToolStatus.FAILED && !refusal.text.startsWith("DENIED (${ModuleCreatures.OWN_LIMIT})")
    }
}

/**
 * A call of the module that the bench checks dry by one of the Narrator's real tools (M5 §6.2): an act through a tool,
 * or the steering of a creature that a law's tool bounds (`mob_effect`, `mob_attribute`).
 *
 * @property line what the forger reads: the act's line.
 * @property known the SDK's own lasting refusal of the act: it is the answer, no check is needed.
 * @property moduleLine the module's line that asked it (0 when unknown).
 */
internal class DryCall(val tool: String, val args: JsonObject, val line: String, val known: ToolResult?) {
    var moduleLine = 0

    /** Two calls with the same tool and arguments are one to check. */
    val key: String get() = "$tool $args"
}

/**
 * The module's [Acts] at the bench: each act written as the game's SDK writes it (M5-07's `ModuleActs`: the same tool,
 * the same arguments, the same kind, the same refusals of the SDK itself), asked to the deployment's real act queue,
 * and recorded with a line (`spawn chicken x6 near Bartok`).
 */
internal class BenchActs(private val ctx: SimContext) : Acts {
    override fun say(text: String?): Pending = ctx.ask("say: $text", "say", json { put("text", text) }, null, Act.Kind.LINE)

    override fun sayTo(player: String?, text: String?): Pending =
        ctx.ask("say to $player: $text", "say", json { put("text", text); to(player) }, player, Act.Kind.LINE)

    override fun title(player: String?, title: String?, subtitle: String?): Pending {
        val args = json {
            put("title", title)
            subtitle?.let { put("subtitle", it) }
            player?.let { to(it) }
        }
        return ctx.ask("title ${player ?: EVERYONE}: $title" + (subtitle?.let { " / $it" } ?: ""), "show_title", args, player, Act.Kind.LINE)
    }

    override fun hud(player: String?, text: String?): Pending =
        ctx.ask("hud ${player ?: EVERYONE}: $text", "hud", json { put("text", text); player?.let { to(it) } }, player, Act.Kind.HUD)

    override fun sound(player: String?, sound: String?): Pending =
        ctx.ask("sound ${player ?: EVERYONE}: ${SimContext.id(sound)}", "play_sound", json { put("sound", sound); player?.let { to(it) } }, player)

    override fun particles(at: Place?, particle: String?, count: Int): Pending =
        ctx.ask("particles ${SimContext.id(particle)} x$count at ${shown(at)}", "particles", json { at(at); put("particle", particle); put("count", count) }, null, refusal = noPlace("particles", at))

    override fun fireworks(at: Place?, count: Int): Pending =
        ctx.ask("fireworks x$count at ${shown(at)}", "fireworks", json { at(at); put("count", count) }, null, refusal = noPlace("fireworks", at))

    override fun lightning(at: Place?): Pending = ctx.ask("lightning at ${shown(at)}", "lightning", json { at(at) }, null, refusal = noPlace("lightning", at))

    override fun smite(player: String?, hearts: Int): Pending {
        val refusal = if (hearts < 1) ToolResult.invalid("smite hurts 1 to 5 hearts (asked $hearts); lightning(at) is the harmless one") else null
        return ctx.ask("smite $player $hearts", "lightning", json { put("player", player); put("damage_hearts", hearts); put("offset", 0) }, player, refusal = refusal)
    }

    override fun explosion(at: Place?, power: Double, hurts: Boolean): Pending =
        ctx.ask(
            "explosion ${ToolText.number(power)} at ${shown(at)}" + if (hurts) "" else " harmless",
            "explosion",
            json { at(at); put("power", power); put("hurts", hurts); put("offset", 0) },
            null,
            refusal = noPlace("explosion", at),
        )

    override fun effect(player: String?, effect: String?, seconds: Int, level: Int): Pending {
        val refusal = effect?.let(ModuleActs::forbiddenEffect)?.let { ToolResult.denied(ModuleActs.FORBIDDEN_EFFECT, it) }
        val args = json {
            putJsonArray("players") { add(player) }
            put("effect", effect)
            put("seconds", seconds)
            put("level", level)
        }
        return ctx.ask("effect $player ${SimContext.id(effect)} ${seconds}s level $level", "apply_effect", args, player, refusal = refusal)
    }

    override fun attribute(player: String?, attribute: String?, multiply: Double, seconds: Int): Pending {
        val args = json {
            put("player", player)
            put("attribute", attribute)
            put("multiply", multiply)
            put("seconds", seconds)
        }
        return ctx.ask("attribute $player ${SimContext.id(attribute)} x${ToolText.number(multiply)} ${seconds}s", "set_attribute", args, player)
    }

    override fun launch(player: String?, direction: String?, strength: Double): Pending {
        val args = json {
            put("player", player)
            put("direction", direction)
            put("strength", strength)
        }
        return ctx.ask("launch $player $direction ${ToolText.number(strength)}", "launch", args, player)
    }

    override fun teleport(player: String?, to: Place?): Pending {
        val where = player?.let { ctx.live().world.online(it)?.at?.dimension() }
        val refusal = when {
            to == null -> ToolResult.invalid("teleport needs a place to go")
            where != null && where != to.dimension() ->
                ToolResult.invalid("a module moves a player within their own dimension: ${ModelText.name(player)} is in $where, not ${to.dimension()}")
            else -> null
        }
        val args = json {
            put("player", player)
            if (to != null) {
                put("x", block(to.x()))
                put("y", block(to.y()))
                put("z", block(to.z()))
            }
        }
        return ctx.ask("teleport $player to ${shown(to)}", "teleport", args, player, refusal = refusal)
    }

    override fun teleportRandom(player: String?, radius: Int): Pending =
        ctx.ask("teleport $player at random within $radius", "teleport", json { put("player", player); put("random_radius", radius) }, player)

    override fun spawn(type: String?, near: String?, how: Spawn?): Pending {
        val deployment = ctx.live()
        val spawn = how ?: Spawn.of(1)
        val forbidden = SimContext.forbidden(type)?.let { "${SimContext.id(type)} is never a module's: $it" }
        val room = deployment.own.size + deployment.reserved + spawn.count() <= ForgeLimits.OWN_CREATURES
        val reserved = forbidden == null && room
        val refusal = when {
            forbidden != null -> ToolResult.denied(ModuleCreatures.FORBIDDEN_CREATURE, forbidden)
            !room -> ToolResult.denied(
                ModuleCreatures.OWN_LIMIT,
                "${deployment.own.size} creatures of its own, ${deployment.reserved} on their way, and ${spawn.count()} more asked: " +
                    "${ForgeLimits.OWN_CREATURES} at most; release some first",
            )
            else -> null
        }
        if (reserved) deployment.reserved += spawn.count()
        val args = json {
            put("entity", type)
            put("count", spawn.count())
            put("near", near)
            put("distance", spawn.distance())
            spawn.name()?.let { put("name", it) }
            put("target", spawn.target())
            put("lifetime_minutes", spawn.lifetimeMinutes())
            put("height", spawn.height())
            put("baby", spawn.baby())
        }
        val adopt: ((ToolResult) -> Unit)? = if (!reserved) null else { result ->
            deployment.reserved -= spawn.count()
            for (uuid in result.entities.map(UUID::toString)) {
                val creature = deployment.world.creatures[uuid] ?: continue
                creature.own = true
                deployment.own += uuid
            }
        }
        return ctx.ask("spawn ${SimContext.id(type)} x${spawn.count()} near $near", "spawn", args, near, refusal = refusal, settle = adopt)
    }

    override fun punish(player: String?, punishment: String?, severity: Int, reason: String?): Pending {
        val args = json {
            put("player", player)
            put("punishment", punishment)
            put("severity", severity)
            put("reason", reason)
        }
        return ctx.ask("punish $player ${SimContext.id(punishment)} $severity", "punish", args, player)
    }

    private fun noPlace(tool: String, at: Place?): ToolResult? = if (at == null) ToolResult.invalid("$tool needs a place") else null
}

/** The module's [Ground] at the bench: `place_blocks` as the game's SDK asks it (M5-07's `GroundView`). */
internal class BenchGround(private val ctx: SimContext) : Ground {
    override fun place(cells: List<Cell>?, block: String?, seconds: Int): Pending {
        // Copied here, in the module's own call: a module may implement List.
        val copied = cells?.let { ArrayList<Any?>(it) }.orEmpty()
        val line = "place ${SimContext.id(block)} x${copied.size} for ${seconds}s"
        if (copied.size > ForgeLimits.PLACE_CELLS) {
            val refusal = ToolResult.invalid("place sets ${ForgeLimits.PLACE_CELLS} cells at most (asked ${copied.size})")
            return ctx.ask(line, PLACE_BLOCKS, buildJsonObject { put("cells", copied.size) }, null, refusal = refusal)
        }
        val given = copied.filterIsInstance<Cell>()
        val dimensions = given.map { it.dimension() }.distinct()
        val refusal = when {
            given.size < copied.size -> ToolResult.invalid("place takes cells, never null nor anything else")
            dimensions.size > 1 -> ToolResult.invalid("place sets blocks in one dimension at a time (asked ${dimensions.joinToString()})")
            else -> null
        }
        val args = buildJsonObject {
            put("dimension", dimensions.firstOrNull())
            putJsonArray("cells") {
                for (cell in given) {
                    addJsonObject {
                        put("x", cell.x())
                        put("y", cell.y())
                        put("z", cell.z())
                    }
                }
            }
            put("block", block)
            put("seconds", seconds)
        }
        return ctx.ask(line, PLACE_BLOCKS, args, null, refusal = refusal)
    }

    companion object {
        const val PLACE_BLOCKS = "place_blocks"
    }
}

/**
 * The judged challenge a module referees at the bench (`Bench.challenge`), as M5-07's `ModuleGame`: its end asked like
 * an act, winners named among its participants; done by the bench (`game won: Mimosa`).
 */
internal class BenchGame(private val ctx: SimContext, private val game: SimGame) : Game {
    override fun id(): String = ctx.live().let { game.id }

    override fun players(): List<String> = ctx.live().let { game.players }

    override fun running(): Boolean = ctx.live().let { game.running }

    override fun win(winners: List<String>?, note: String?): Pending {
        // Copied here, in the module's own call: a module may implement List.
        val names = winners?.let { ArrayList<Any?>(it) }.orEmpty()
        val strangers = names.filter { name -> game.players.none { name is String && it.equals(name, ignoreCase = true) } }
        val refusal = when {
            names.isEmpty() -> ToolResult.invalid("win names one winner at least; lose when nobody won")
            strangers.isNotEmpty() -> ToolResult.invalid(
                "${strangers.joinToString { ModelText.name(it.toString()) }} not among the participants of ${game.id}: ${game.players.joinToString()}",
            )
            else -> null
        }
        val shown = names.joinToString { it.toString() }
        return ctx.ask("game won: $shown", null, gameArgs("won", note), names.firstOrNull()?.toString(), refusal = refusal, run = { end("won by $shown") })
    }

    override fun lose(note: String?): Pending = ctx.ask("game lost", null, gameArgs("lost", note), null, run = { end("lost") })

    /** The end done at the bench: refused when a scenario said so, or when the challenge is over already. */
    private fun end(how: String): ToolResult {
        ctx.live().imposed(ActRecord.GAME)?.let { return it }
        if (!game.running) return ToolResult.invalid("${game.id} is over already")
        game.running = false
        return ToolResult.ok("ok (simulated): ${game.id} $how")
    }

    private fun gameArgs(outcome: String, note: String?) = buildJsonObject {
        put("challenge", game.id)
        put("outcome", outcome)
        put("note", note)
    }
}

/**
 * The bench's stand-in for the Narrator's tools (M5 §7): an act is recorded, never done, and answers `ok (simulated)`
 * but for a refusal the scenario imposed (`Bench.refuseNext`), a player who is not online, and what the bench knows of
 * its world: `spawn` makes the module's creatures, `place_blocks` needs thin air, `creature_attack` a creature that is
 * there, `hud` one line a second per player (the tool's own limit). The guards are not simulated.
 */
internal class BenchActRunner(private val deployment: SimDeployment) : ActRunner {
    private var firings = 0L

    override fun open(ref: Ref, subject: String?, reason: String): Firing {
        firings++
        return Firing(firings, ref, Caller(Role.DIRECTOR, EngineActions.conversationOf(ref), null, null, firings))
    }

    override suspend fun run(firing: Firing, tool: String, args: JsonObject): ToolResult {
        deployment.imposed(tool)?.let { return it }
        val world = deployment.world
        named(args).firstOrNull { world.online(it) == null }?.let { return ToolResult.invalid("${ModelText.name(it)} is not online") }
        return when (tool) {
            "spawn" -> summon(args)
            BenchGround.PLACE_BLOCKS -> place(args)
            "creature_attack" -> attack(args)
            "hud" -> hud(args)
            else -> SIMULATED
        }
    }

    /** The players an act names: `player`, `near`, `to`, `players`. */
    private fun named(args: JsonObject): List<String> = listOf("player", "near").mapNotNull { args[it]?.jsonPrimitive?.contentOrNull } +
        listOf("to", "players").flatMap { field -> (args[field] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull } }

    /** Its creatures around the player, evenly, `distance` away and `height` up, for their lifetime. */
    private fun summon(args: JsonObject): ToolResult {
        val world = deployment.world
        val near = world.online(args["near"]?.jsonPrimitive?.contentOrNull) ?: return ToolResult.invalid("spawn needs a player online")
        val count = args.integer("count", 1)
        if (count !in 1..MAX_SPAWN) return ToolResult.invalid("count must be between 1 and $MAX_SPAWN (got $count)")
        val distance = args.integer("distance", DEFAULT_DISTANCE).coerceIn(MIN_DISTANCE, MAX_DISTANCE).toDouble()
        val height = args.integer("height", 0).coerceAtLeast(0).toDouble()
        val lifetime = args.integer("lifetime_minutes", DEFAULT_LIFETIME).coerceIn(1, MAX_LIFETIME_MINUTES)
        val name = args["name"]?.jsonPrimitive?.contentOrNull?.let { ModelText.cut(it, ForgeLimits.CREATURE_NAME_CHARS) }
        val type = SimContext.id(args["entity"]?.jsonPrimitive?.contentOrNull)
        val made = (0 until count).map { i ->
            val angle = 2 * PI * i / count
            val creature = world.addCreature(type, near.at.offset(cos(angle) * distance, height, sin(angle) * distance), name, own = false)
            creature.summoned = true
            creature.vanishesAt = world.now + lifetime * SimDeployment.MINUTE_MS
            UUID.fromString(creature.uuid)
        }
        return ToolResult.ok(SIMULATED.text, entities = made)
    }

    /** All or nothing: every cell must be thin air in the bench's world. */
    private fun place(args: JsonObject): ToolResult {
        val world = deployment.world
        val dimension = args["dimension"]?.jsonPrimitive?.contentOrNull ?: SimWorld.OVERWORLD
        for (cell in args["cells"]?.jsonArray.orEmpty()) {
            val at = cell.jsonObject
            val x = at["x"]?.jsonPrimitive?.int ?: continue
            val y = at["y"]?.jsonPrimitive?.int ?: continue
            val z = at["z"]?.jsonPrimitive?.int ?: continue
            val block = world.block(Cell(dimension, x, y, z))
            if (!SimWorld.isAir(block)) return ToolResult.failed("$x $y $z is not air ($block): blocks go in thin air only; nothing was placed")
        }
        return SIMULATED
    }

    private fun attack(args: JsonObject): ToolResult {
        val uuid = args["creature"]?.jsonPrimitive?.contentOrNull
        val creature = uuid?.let { deployment.world.creatures[it] }?.takeIf { it.own }
            ?: return ToolResult.failed("the creature $uuid is gone")
        creature.target = args["player"]?.jsonPrimitive?.contentOrNull
        return SIMULATED
    }

    /** One line above the hotbar per player and second, as the `hud` tool keeps it for the whole server. */
    private fun hud(args: JsonObject): ToolResult {
        val world = deployment.world
        val now = world.now
        val audience = (args["to"] as? JsonArray)?.mapNotNull { world.online(it.jsonPrimitive.contentOrNull)?.name } ?: world.online().map { it.name }
        if (audience.isEmpty()) return ToolResult.failed("nobody is online")
        val (allowed, waiting) = audience.partition { now - (shown[it] ?: Long.MIN_VALUE / 2) >= ForgeLimits.HUD_GAP_MS }
        if (allowed.isEmpty()) return ActQueue.denied("one line above the hotbar per player and second: ${waiting.joinToString()} got one less than a second ago")
        allowed.forEach { shown[it] = now }
        return SIMULATED
    }

    private val shown = HashMap<String, Long>()

    private fun JsonObject.integer(field: String, fallback: Int): Int = this[field]?.jsonPrimitive?.intOrNull ?: fallback

    private companion object {
        val SIMULATED = ToolResult.ok("ok (simulated)")
        const val MAX_SPAWN = 12
        const val MIN_DISTANCE = 2
        const val MAX_DISTANCE = 24
        const val DEFAULT_DISTANCE = 8
        const val DEFAULT_LIFETIME = 5
        const val MAX_LIFETIME_MINUTES = 10
    }
}

private const val EVERYONE = "everyone"

private fun json(build: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(build)

/** `to`: one player, as the speech tools take it. */
private fun JsonObjectBuilder.to(player: String?) {
    putJsonArray("to") { add(player) }
}

/** A place as the world tools take it: block coordinates and a dimension; nothing for null (the act is refused). */
private fun JsonObjectBuilder.at(at: Place?) {
    if (at == null) return
    put("x", block(at.x()))
    put("y", block(at.y()))
    put("z", block(at.z()))
    put("dimension", at.dimension())
}

private fun block(coordinate: Double): Int = floor(coordinate).toInt()

/** A place in an act's line: `0.5 66 0`, its dimension after it when it is not the overworld; `nowhere` for none. */
private fun shown(at: Place?): String {
    if (at == null) return "nowhere"
    return "${ToolText.number(at.x())} ${ToolText.number(at.y())} ${ToolText.number(at.z())}" + if (at.dimension() == SimWorld.OVERWORLD) "" else " ${at.dimension()}"
}
