package io.github.clemsix6.narrator2.tool.forge

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.forge.ArsenalEntry
import io.github.clemsix6.narrator2.forge.DisabledForge
import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.forge.ForgeRejected
import io.github.clemsix6.narrator2.forge.ModuleStatus
import io.github.clemsix6.narrator2.forge.ParamSpec
import io.github.clemsix6.narrator2.guard.AttributeKey
import io.github.clemsix6.narrator2.guard.SpawnPolicy
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.mechanics.PunishmentId
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Doc
import io.github.clemsix6.narrator2.tool.ForgeTool
import io.github.clemsix6.narrator2.tool.MaxLen
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.NoArgs
import io.github.clemsix6.narrator2.tool.Range
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.tool.action.LaunchDirection
import io.github.clemsix6.narrator2.tool.action.MobAttributeKey
import io.github.clemsix6.narrator2.tool.action.ParticleKey
import io.github.clemsix6.narrator2.tool.action.shortId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier

/**
 * The forger's tools (M5 §8.3): `module_list`, `module_read`, `game_ids`, `forge_test`, `forge_save`; the forger's
 * alone (`ToolCatalog`), each a `ForgeTool` (shown only while the engine and the Forge run). The work is the Forge's
 * (`NarratorServices.forge`, `ForgeService`): the tools hand it what the model wrote and answer what it says. While the
 * Forge does not run they answer `DENIED (forge_off): …`; `forge_test` and `forge_save` also while an operator's switch
 * holds (`/narrator forge off`).
 */
@Suppress("UNUSED_PARAMETER")
fun forgerTools(services: NarratorServices): List<NarratorTool<*>> = listOf(ModuleListTool(), ModuleReadTool(), GameIdsTool(), ForgeTestTool(), ForgeSaveTool())

/** The arguments of `module_read`. */
@Serializable
data class ModuleReadArgs(
    @Doc("A module of the arsenal.") @MaxLen(32) val name: String,
    @Doc("Its version; the latest when left out.") @Range(0.0, 99.0) val version: Int? = null,
)

/** The registries `game_ids` lists (M5 §8.3): the ids a module may write, frozen once the server runs. */
@Serializable
enum class GameIdKind {
    @SerialName("entity") ENTITY,
    @SerialName("item") ITEM,
    @SerialName("block") BLOCK,
    @SerialName("particle") PARTICLE,
    @SerialName("sound") SOUND,
    @SerialName("effect") EFFECT,
    @SerialName("attribute") ATTRIBUTE,
    @SerialName("biome") BIOME,
    @SerialName("structure") STRUCTURE,
    @SerialName("dimension") DIMENSION,
    @SerialName("punishment") PUNISHMENT,
    @SerialName("launch_direction") LAUNCH_DIRECTION,
    ;

    val id: String get() = name.lowercase()
}

/** The arguments of `game_ids`. */
@Serializable
data class GameIdsArgs(
    val kind: GameIdKind,
    @Doc("Only the ids that contain this.") @MaxLen(40) val contains: String? = null,
)

/** The arguments of `forge_test`. */
@Serializable
data class ForgeTestArgs(
    @Doc("The module's name, snake_case.") @MaxLen(32) val name: String,
    @Doc("The whole Java file.") @MaxLen(ForgeLimits.SOURCE_CHARS) val source: String,
)

/** The arguments of `forge_save`: the test again, then a new version. */
@Serializable
data class ForgeSaveArgs(
    @Doc("The module's name, snake_case.") @MaxLen(32) val name: String,
    @Doc("The whole Java file.") @MaxLen(ForgeLimits.SOURCE_CHARS) val source: String,
    @Doc("What it is or what changed, one English line for the Director and the operators.") @MaxLen(300) val note: String,
)

/**
 * `module_list` (M5 §8.3): the arsenal, one line per module, the newest first and the built-in ones last: its latest
 * version, status and author, its summary, parameters and scenarios, then its problem (`broken: …`, the first
 * diagnostic of a version that no longer compiles, or `last fault: …`, the last fault of one of its deployments), and
 * its other versions.
 *
 * ```
 * arsenal: 2 modules (1 built-in, 1 forged)
 * statues v2 pending (forger T-32) · Red light, green light… · params: players (players 2-8) · scenarios 2 · last fault: none · also v1 ready
 * orbiting_chickens v0 ready (built-in) · Chickens circle a player… · params: player (player), chickens (integer 3-8, default 6) · scenarios 2 · last fault: none
 * ```
 */
class ModuleListTool : ForgeTool<NoArgs>(
    name = "module_list",
    description = "Lists the arsenal, one line per module: its latest version and status (ready, pending: an operator must " +
        "approve it, rejected, broken: it no longer compiles), who saved it, its summary, parameters and scenarios, its last " +
        "problem, and its other versions. Version 0 is built-in: read those for examples.",
    args = NoArgs.serializer(),
    effect = ToolEffect.READ,
) {
    override suspend fun run(ctx: ToolContext, args: NoArgs): ToolResult {
        unavailable(ctx.services)?.let { return it }
        val entries = ctx.services.forge.arsenal.list()
        if (entries.isEmpty()) return ToolResult.ok("arsenal: empty")
        val modules = entries.groupBy { it.name }
        val builtIn = modules.count { (_, versions) -> versions.any { it.builtIn } }
        val lines = modules.values.map { versions ->
            val latest = versions.maxBy { it.version }
            val others = versions.filter { it !== latest }.sortedBy { it.version }
            line(latest) + if (others.isEmpty()) "" else " · also " + others.joinToString(", ") { "v${it.version} ${it.status.id}" }
        }
        return ToolResult.ok("arsenal: ${count(modules.size, "module")} ($builtIn built-in, ${modules.size - builtIn} forged)\n" + lines.joinToString("\n"))
    }

    private fun line(entry: ArsenalEntry): String = buildString {
        append(entry.name).append(" v").append(entry.version).append(' ').append(entry.status.id).append(" (").append(entry.by).append(')')
        append(" · ").append(ModelText.escape(entry.summary))
        append(" · params: ").append(params(entry.params))
        append(" · scenarios ").append(entry.scenarios.size)
        val problem = entry.problem?.let { ModelText.cut(ModelText.escape(it), PROBLEM_CHARS) }
        append(if (entry.status == ModuleStatus.BROKEN) " · broken: ${problem ?: "unknown"}" else " · last fault: ${problem ?: "none"}")
    }

    private companion object {
        /** A problem in a line, at most. */
        const val PROBLEM_CHARS = 300
    }
}

/**
 * `module_read` (M5 §8.3): the source of a version of the arsenal, the latest one (whatever its status) when the version
 * is left out: `orbiting_chickens v0 (built-in, ready), 142 lines:` then the source as it was saved.
 */
class ModuleReadTool : ForgeTool<ModuleReadArgs>(
    name = "module_read",
    description = "Reads the Java source of a module of the arsenal, its latest version when version is left out: to start " +
        "from a module close to what you want, or to fix one. A fix is saved as the next version of the same name.",
    args = ModuleReadArgs.serializer(),
    effect = ToolEffect.READ,
) {
    override suspend fun run(ctx: ToolContext, args: ModuleReadArgs): ToolResult {
        unavailable(ctx.services)?.let { return it }
        val arsenal = ctx.services.forge.arsenal
        val name = args.name.trim()
        val all = arsenal.list()
        val versions = all.filter { it.name == name }
        if (versions.isEmpty()) {
            val names = all.map { it.name }.distinct().sorted()
            return ToolResult.invalid("no module \"${ModelText.escape(name)}\" in the arsenal; its modules: ${names.joinToString().ifEmpty { "none" }}")
        }
        val entry = if (args.version == null) {
            versions.maxBy { it.version }
        } else {
            versions.firstOrNull { it.version == args.version }
                ?: return ToolResult.invalid("$name has no version ${args.version}; its versions: ${versions.map { it.version }.sorted().joinToString()}")
        }
        val source = arsenal.source(name, entry.version) ?: return ToolResult.failed("the source of $name v${entry.version} cannot be read")
        val status = if (entry.status == ModuleStatus.BROKEN) "broken: ${ModelText.escape(entry.problem ?: "unknown")}" else entry.status.id
        return ToolResult.ok("$name v${entry.version} (${entry.by}, $status), ${count(source.trimEnd('\r', '\n').lines().size, "line")}:\n$source")
    }
}

/**
 * `game_ids` (M5 §8.3): the ids of a registry of the game a module may write, without `minecraft:`, sorted, at most
 * [ForgeLimits.GAME_IDS]: `entity "pig": pig, piglin, piglin_brute, zombified_piglin`. Each list is the one the SDK's
 * acts are checked against: the particles of the `particles` tool, the players' attributes of `set_attribute` and the
 * creatures' of `mob_attribute`, the punishments of the catalogue, the directions of `launch`. The registries are
 * frozen once the server runs (M4-03): read off the server thread; the dimensions on it.
 */
class GameIdsTool : ForgeTool<GameIdsArgs>(
    name = "game_ids",
    description = "Lists the ids a module may write, as the SDK takes them (without minecraft:), sorted, 150 at most: " +
        "creature types (entity), items, blocks, the particles the particles act knows, sounds, effects, attributes (the " +
        "players' and the creatures'), biomes, structures, dimensions, punishments, launch directions. Narrow with contains.",
    args = GameIdsArgs.serializer(),
    effect = ToolEffect.READ,
) {
    override suspend fun run(ctx: ToolContext, args: GameIdsArgs): ToolResult {
        val wanted = args.contains?.trim()?.lowercase()?.removePrefix(VANILLA)?.ifEmpty { null }
        val groups = groups(ctx, args.kind).mapValues { (_, ids) -> ids.filter { wanted == null || wanted in it }.distinct().sorted() }
        val head = args.kind.id + (wanted?.let { " \"${ModelText.escape(it)}\"" } ?: "") + ": "
        val total = groups.values.sumOf { it.size }
        if (total == 0) return ToolResult.ok(head + "none")
        var room = ForgeLimits.GAME_IDS
        val shown = groups.filterValues { it.isNotEmpty() }.map { (label, ids) ->
            val taken = ids.take(room)
            room -= taken.size
            (label?.let { "$it: " } ?: "") + taken.joinToString(", ").ifEmpty { "…" }
        }
        val more = total - ForgeLimits.GAME_IDS
        val text = head + shown.joinToString("; ") + if (more > 0) " … and $more more: narrow it with contains" else ""
        val note = if (args.kind == GameIdKind.ENTITY) "\nspawn never summons: ${SpawnPolicy.FORBIDDEN.keys.map { it.removePrefix(VANILLA) }.sorted().joinToString()}" else ""
        return ToolResult.ok(text + note)
    }

    /** The ids of [kind], by group (null: the only one). */
    private suspend fun groups(ctx: ToolContext, kind: GameIdKind): Map<String?, List<String>> = when (kind) {
        GameIdKind.ENTITY -> one(BuiltInRegistries.ENTITY_TYPE.keySet().map(::shortId))
        GameIdKind.ITEM -> one(BuiltInRegistries.ITEM.keySet().map(::shortId))
        GameIdKind.BLOCK -> one(BuiltInRegistries.BLOCK.keySet().map(::shortId))
        GameIdKind.PARTICLE -> one(ParticleKey.entries.map { it.id })
        GameIdKind.SOUND -> one(BuiltInRegistries.SOUND_EVENT.keySet().map(::shortId))
        GameIdKind.EFFECT -> one(BuiltInRegistries.MOB_EFFECT.keySet().map(::shortId))
        GameIdKind.ATTRIBUTE -> linkedMapOf(
            "players (Acts.attribute)" to AttributeKey.entries.map { it.id },
            "creatures (Creature.attribute)" to MobAttributeKey.entries.map { it.id },
        )
        GameIdKind.BIOME -> one(ctx.services.server.registryAccess().lookupOrThrow(Registries.BIOME).keySet().map(::shortId))
        GameIdKind.STRUCTURE -> one(ctx.services.server.registryAccess().lookupOrThrow(Registries.STRUCTURE).keySet().map(::shortId))
        GameIdKind.DIMENSION -> one(ctx.onServer { server -> server.levelKeys().map { ToolText.dimension(it.identifier().toString()) } })
        GameIdKind.PUNISHMENT -> one(PunishmentId.entries.map { it.id })
        GameIdKind.LAUNCH_DIRECTION -> one(LaunchDirection.entries.map { it.name.lowercase() })
    }

    private fun one(ids: List<String>): Map<String?, List<String>> = mapOf(null to ids)

    private companion object {
        val VANILLA = Identifier.DEFAULT_NAMESPACE + ":"
    }
}

/**
 * `forge_test` (M5 §5.4 steps 1 to 7, §6.2): `Forge.test`. Answers `ok: compiled (12 ms), guard clean, smoke: start ok,
 * 3 facts, 1,200 ticks, acts say ×2 spawn ×1 (checked), slowest call 0.4 ms, retained 0.2 MiB; scenarios 2/2`, or
 * `Invalid arguments:` and the report (where it stopped, then the diagnostics with their lines and hints, M5 §5.3),
 * 6,000 characters at most.
 */
class ForgeTestTool : ForgeTool<ForgeTestArgs>(
    name = "forge_test",
    description = "Tests a module without saving it. Compiles the whole file against the SDK, checks it against the sandbox, " +
        "then runs it at the bench, a simulated world: a smoke run of one simulated minute with sample parameters and facts, " +
        "the acts it asked checked dry by the real tools, then each method of its Scenarios. Answers ok with what ran, or " +
        "where it stopped and each problem with its line and hints: fix them and test again. Nothing touches the world.",
    args = ForgeTestArgs.serializer(),
    effect = ToolEffect.READ,
    timeoutMs = FORGE_TIMEOUT_MS,
) {
    override suspend fun run(ctx: ToolContext, args: ForgeTestArgs): ToolResult {
        (unavailable(ctx.services) ?: halted(ctx.services))?.let { return it }
        return forge(name) {
            val report = ctx.services.forge.test(args.name.trim(), args.source)
            if (report.ok) ToolResult.ok(report.text) else ToolResult.invalid(report.text)
        }
    }
}

/**
 * `forge_save` (M5 §5.4 steps 1 to 8, §6.3): `Forge.save`, by `forger T-31`. Answers `ok: saved statues v1 (ready):
 * params players (players 2-8); the Director can deploy it` (`(pending: an operator must approve it)` with
 * `forge.approval = operator`), or the refusal: the test's report, `Invalid arguments: … save needs at least one
 * scenario …`, `DENIED (arsenal_full): …`.
 */
class ForgeSaveTool : ForgeTool<ForgeSaveArgs>(
    name = "forge_save",
    description = "Saves a module as the next version of its name in the arsenal, after the same tests as forge_test, which " +
        "must all pass, with one scenario at least. The version is ready for the Director, or waits for an operator's " +
        "approval when the server asks for one. A saved version never changes: a fix is the next version.",
    args = ForgeSaveArgs.serializer(),
    effect = ToolEffect.ENGINE,
    timeoutMs = FORGE_TIMEOUT_MS,
) {
    override suspend fun check(ctx: ToolContext, args: ForgeSaveArgs): ToolResult? =
        if (args.note.isBlank()) ToolResult.invalid("note is empty: say in one English line what the module is or what changed") else null

    override suspend fun run(ctx: ToolContext, args: ForgeSaveArgs): ToolResult {
        check(ctx, args)?.let { return it }
        (unavailable(ctx.services) ?: halted(ctx.services))?.let { return it }
        val by = ctx.caller.taskId?.let { "${Role.FORGER.id} T-$it" } ?: ctx.caller.role.id
        val note = ModelText.escape(args.note.trim())
        return forge(name) {
            val entry = ctx.services.forge.save(args.name.trim(), args.source, note, by)
            val ready = entry.status == ModuleStatus.READY
            ToolResult.ok(
                "ok: saved ${entry.name} v${entry.version} (${if (ready) "ready" else "pending: an operator must approve it"}): " +
                    "params ${params(entry.params)}; ${if (ready) "the Director can deploy it" else "the Director sees it once approved"}",
            )
        }
    }
}

/** `players (players 2-8), chickens (integer 3-8, default 6), creature (text: chicken|pig, default chicken)`; `none`. */
internal fun params(params: List<ParamSpec>): String = if (params.isEmpty()) "none" else params.joinToString(", ") { param(it) }

private fun param(p: ParamSpec): String {
    var type = p.type.name.lowercase()
    bounds(p.min, p.max)?.let { type += " $it" }
    if (p.choices.isNotEmpty()) type += ": " + p.choices.joinToString("|") { ModelText.escape(it) }
    val use = when {
        p.fallback != null -> ", default ${ModelText.escape(p.fallback)}"
        !p.required -> ", optional"
        else -> ""
    }
    return "${p.name} ($type$use)"
}

/** `2-8`, `-5 to 5`, `at least 1`, `at most 8`; null without bounds. */
private fun bounds(min: Double?, max: Double?): String? = when {
    min != null && max != null -> if (min < 0) "${ToolText.number(min)} to ${ToolText.number(max)}" else "${ToolText.number(min)}-${ToolText.number(max)}"
    min != null -> "at least ${ToolText.number(min)}"
    max != null -> "at most ${ToolText.number(max)}"
    else -> null
}

private fun count(n: Int, what: String) = "$n $what${if (n == 1) "" else "s"}"

/** `DENIED (forge_off): the Forge is unavailable: …` while the Forge does not run; null when it does. */
private fun unavailable(services: NarratorServices): ToolResult? {
    val forge = services.forge
    return if (forge.available) null else ToolResult.denied(DisabledForge.FORGE_OFF, "the Forge is unavailable: ${forge.unavailableReason}")
}

/** `DENIED (forge_off): the Forge was switched off by <operator>: <reason>` while an operator's switch holds (M5 §3.7); null otherwise. */
private fun halted(services: NarratorServices): ToolResult? =
    services.mechanics.modules.halted?.let { ToolResult.denied(DisabledForge.FORGE_OFF, "the Forge was switched off by ${it.by}: ${it.reason}") }

/** Runs [block], a call of the Forge: its refusals become the answer. */
private suspend fun forge(tool: String, block: suspend () -> ToolResult): ToolResult = try {
    block()
} catch (e: ForgeRejected) {
    Log.forge.debug { "$tool: ${e.toResult().text.lineSequence().first()}" }
    e.toResult()
}

/** A test or a save: the compilation (10 s at most) and the bench (30 s at most), with a margin. */
private const val FORGE_TIMEOUT_MS = 45_000L
