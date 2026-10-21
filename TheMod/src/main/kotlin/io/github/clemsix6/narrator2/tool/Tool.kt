@file:OptIn(ExperimentalSerializationApi::class)

package io.github.clemsix6.narrator2.tool

import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUnion
import com.anthropic.models.messages.ToolUseBlock
import io.github.clemsix6.narrator2.core.onServerThread
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.guard.Guards
import io.github.clemsix6.narrator2.guard.Verdict
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.observer.PlayerStates
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.runtime.Settings
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialInfo
import kotlinx.serialization.Serializable
import net.minecraft.server.MinecraftServer
import java.time.Clock
import java.util.UUID

/*
 * Argument annotations. They document the JSON schema sent to the model (`description`, `minimum`,
 * `maximum`, `maxLength`, `minItems`, `maxItems`) and are enforced by the code when arguments are
 * decoded: the model may ignore a schema, never the checks.
 */

/** Description of a tool argument, in English. */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class Doc(val text: String)

/** Bounds of a number argument, inclusive. */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class Range(val min: Double, val max: Double)

/** Longest a string argument may be, in characters. */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class MaxLen(val chars: Int)

/** Number of items a list argument must have, inclusive. */
@SerialInfo
@Target(AnnotationTarget.PROPERTY)
annotation class Items(val min: Int, val max: Int)

/** Arguments of a tool that takes none. */
@Serializable
object NoArgs

/** What a tool does, for the audit (`tool_calls.effect`) and for deciding whether a turn acted. */
enum class ToolEffect {
    /** Reads the world or the Journal. */
    READ,

    /** Speaks to players (chat, titles, sounds, books). */
    SPEECH,

    /** Changes the world or a player. */
    WORLD,

    /** Delegates work to a worker. */
    TASK,

    /** Acts on the engine itself (a reminder). */
    ENGINE,
    ;

    /** Value stored in the Journal, e.g. `world`. */
    val id: String get() = name.lowercase()
}

/** How a tool call ended (`tool_calls.status`). */
enum class ToolStatus {
    OK,

    /** Bad arguments: the model should fix them and retry. */
    INVALID,

    /** An engine rule refused the action. Final for this turn. */
    DENIED,

    /** The action could not be done (server stopping, component down, exception). */
    FAILED,

    /** A read tool did not finish in time. */
    TIMEOUT,

    /** A world tool did not finish in time: the action may or may not have happened. */
    UNKNOWN,
    ;

    /** Value stored in the Journal, e.g. `denied`. */
    val id: String get() = name.lowercase()
}

/**
 * What a tool returns to the model: English, compact, first line = verdict. Action tools start with
 * `ok: …`; read tools return one fact per line, without prose. Anything but [ToolStatus.OK] is sent
 * with `is_error: true`.
 *
 * @property targets players the call affected, for the audit.
 * @property entities the creatures the call brought into the world (`spawn`'s), by UUID: what a module's act makes its
 *   own (M5 §4.4). Never sent to the model.
 */
data class ToolResult(
    val status: ToolStatus,
    val text: String,
    val targets: List<PlayerRef> = emptyList(),
    val entities: List<UUID> = emptyList(),
) {
    val isError: Boolean get() = status != ToolStatus.OK

    companion object {
        /** Success; [text] is returned as is. */
        fun ok(text: String, targets: List<PlayerRef> = emptyList(), entities: List<UUID> = emptyList()) = ToolResult(ToolStatus.OK, text, targets, entities)

        /** Bad arguments: `Invalid arguments: <problem>`. Say how to fix it (valid values, online players, the cap in force). */
        fun invalid(problem: String) = ToolResult(ToolStatus.INVALID, "Invalid arguments: $problem")

        /** An engine rule refused: `DENIED (<rule>): <text>`. */
        fun denied(rule: String, text: String) = ToolResult(ToolStatus.DENIED, "DENIED ($rule): $text")

        fun denied(verdict: Verdict.Denied) = denied(verdict.rule, verdict.message)

        /** `FAILED: <reason>`. */
        fun failed(reason: String) = ToolResult(ToolStatus.FAILED, "FAILED: $reason")

        /** `TIMEOUT: <text>`, for a read tool that did not finish in time. */
        fun timeout(text: String) = ToolResult(ToolStatus.TIMEOUT, "TIMEOUT: $text")

        /** `UNKNOWN: <text>`, for a world tool that did not finish in time. */
        fun unknown(text: String) = ToolResult(ToolStatus.UNKNOWN, "UNKNOWN: $text")
    }
}

/**
 * Who calls a tool.
 *
 * @property conversationId the conversation's id (`Conversation.id`); `mechanics:C-60` for an act of the
 *   mechanics engine (M4 §4.3).
 * @property turnId the Director turn, for the Director.
 * @property taskId the task, for a worker.
 * @property firingId an act of the mechanics engine (a rule's firing, a challenge's stakes): the id of its
 *   `mechanic` decision (`EngineActions.open`, M4 §4.3); null for the Director and the workers.
 */
data class Caller(val role: Role, val conversationId: String, val turnId: Long?, val taskId: Long?, val firingId: Long? = null) {
    /**
     * The act this call belongs to, for the guards (episodes, `already_punished`, the punishment
     * categories) and the per-turn limits: the Director's turn, else minus the engine's firing. Negative
     * for the engine because turns and decisions are two sequences of ids (`IdKind.TURN`,
     * `IdKind.DECISION`): firing 812 must never pass for turn 812 (M4 review, finding 9). Null for a
     * caller with neither (a worker's task): each of its hostile actions is an act of its own.
     */
    val act: Long? get() = turnId ?: firingId?.let { -it }
}

/**
 * What is wired when a conversation's tools are chosen: a tool that needs something missing is not
 * shown to the model at all.
 *
 * @property journalAvailable the Journal opened (`sql`, and `delegate` to the Analyst, need it).
 * @property workerRoles worker roles that accept tasks (the `delegate` tool lists only these).
 * @property memoryAvailable the memory runs (`Memory.available`): the memory tools need it (M3 §5).
 * @property mechanicsAvailable the mechanics engine runs (`Mechanics.available`): every tool of M4 needs it,
 *   `punish` and `favor` included (M4 §12.1).
 * @property forgeAvailable the Forge runs (`Forge.available`) and no operator switched it off
 *   (`ModuleControl.halted`): every tool of M5 needs it, with the engine (M5 §9.1).
 * @property storytellerAvailable the storyteller can work (`Role.STORYTELLER` among the workers' roles, for a chapter
 *   of the Director), or the conversation is the storyteller's own: `inspire` needs it, with the Journal, and nothing
 *   else does (M6 §12.2). The role `storyteller` of `delegate` is in a static enum: it is refused with its reason,
 *   never hidden.
 */
data class ToolEnv(
    val journalAvailable: Boolean,
    val workerRoles: Set<Role>,
    val memoryAvailable: Boolean = false,
    val mechanicsAvailable: Boolean = false,
    val forgeAvailable: Boolean = false,
    val storytellerAvailable: Boolean = false,
)

/**
 * A tool the model can call.
 *
 * Arguments are a `@Serializable` class [A] (or [NoArgs]): optional arguments have a Kotlin default
 * value, nullable ones `= null`; supported types are strings, numbers, booleans, enums (`@SerialName`
 * in snake_case), lists, nested `@Serializable` classes, and a free `JsonObject` (the arguments of another
 * tool, M4 §8.1). The JSON schema is generated from [args] and its annotations, so the schema and the
 * decoding cannot drift apart.
 *
 * @param name snake_case, at most 32 characters (the gateway prefixes tool names).
 * @param description English and static: no number that depends on the chaos level or the config
 *   (the tools of a conversation never change; caps appear in error messages instead).
 * @param timeoutMs the call fails after this delay: `TIMEOUT` for a read, `UNKNOWN` for a world action.
 *   Tools are bounded by construction (radius, result count) so they finish well before.
 */
abstract class NarratorTool<A : Any>(
    val name: String,
    val description: String,
    val args: KSerializer<A>,
    val effect: ToolEffect,
    val timeoutMs: Long = 5_000,
) {
    init {
        require(NAME.matches(name)) { "tool name must be snake_case, at most 32 characters: $name" }
    }

    /** Whether the model may see this tool at all, given what is wired right now. */
    open fun isAvailable(env: ToolEnv): Boolean = true

    /**
     * The dry checks of a call (M4 §4.3): those that depend neither on the players online nor on the
     * world (registry ids, the caps of the chaos level, arguments that contradict each other). Null when
     * they pass; else what [run] would answer, [ToolResult.invalid] or [ToolResult.denied]. It never
     * changes anything and never goes to the server thread.
     *
     * [run] starts with it, so every path to the tool gets the same answers. The mechanics engine calls
     * it alone to refuse a rule's effect or a challenge's stake when they are created, with the
     * Director's [ToolContext.caller] (`EngineActions.check`). A tool without such checks keeps this
     * default.
     */
    open suspend fun check(ctx: ToolContext, args: A): ToolResult? = null

    /**
     * Runs the call with decoded, bound-checked [args]. Tool-specific checks (registry ids, online
     * players, caps) happen here and answer with [ToolResult.invalid] or [ToolResult.denied]; it starts
     * with [check].
     */
    abstract suspend fun run(ctx: ToolContext, args: A): ToolResult

    override fun toString() = "tool $name"

    private companion object {
        val NAME = Regex("[a-z][a-z0-9_]{0,31}")
    }
}

/**
 * A tool of M4 (M4 §12.1): shown only while the mechanics engine runs ([ToolEnv.mechanicsAvailable]), every
 * one of them, `punish` and `favor` included (decision J): with `mechanics.enabled` false, the Director keeps
 * the raw tools of M2 only. A chapter opened while the engine ran keeps the tool declared if the engine falls
 * later: the tool then answers `FAILED: the mechanics are unavailable: …`.
 */
abstract class MechanicsTool<A : Any>(
    name: String,
    description: String,
    args: KSerializer<A>,
    effect: ToolEffect,
    timeoutMs: Long = 5_000,
) : NarratorTool<A>(name, description, args, effect, timeoutMs) {
    final override fun isAvailable(env: ToolEnv): Boolean = env.mechanicsAvailable
}

/**
 * A tool of M5 (M5 §8.3, §9.1): shown only while the mechanics engine and the Forge run and no operator switched the
 * Forge off ([ToolEnv.mechanicsAvailable] and [ToolEnv.forgeAvailable]): the Director's `forge_module`,
 * `deploy_module`, `stop_module`, and the forger's own. A chapter opened while they ran keeps the tool declared if
 * the Forge falls later, or is switched off: the tool then answers `DENIED (forge_off): …`.
 */
abstract class ForgeTool<A : Any>(
    name: String,
    description: String,
    args: KSerializer<A>,
    effect: ToolEffect,
    timeoutMs: Long = 5_000,
) : NarratorTool<A>(name, description, args, effect, timeoutMs) {
    final override fun isAvailable(env: ToolEnv): Boolean = env.mechanicsAvailable && env.forgeAvailable
}

/** What a running tool can use. */
class ToolContext(
    /** Implemented by the runtime; faked in unit tests. */
    val services: NarratorServices,
    val caller: Caller,
    val clock: Clock,
    private val timeoutMs: Long,
) {
    val guards: Guards get() = services.guards
    val players: PlayerStates get() = services.players

    /** The settings in force now (read at each use: `/narrator reload` may replace them). */
    val settings: Settings get() = services.settings.get()

    /**
     * Runs [block] on the server thread, where every access to the world must happen, within this
     * tool's timeout. Fails with `ServerThreadTimeoutException` or `ServerStoppedException`
     * (`core/ServerThread.kt`).
     */
    suspend fun <T> onServer(block: (MinecraftServer) -> T): T {
        val server = services.server
        return server.onServerThread(timeoutMs) { block(server) }
    }
}

/**
 * The tools of one conversation, in the order sent to the model, with their SDK definitions. Built
 * once per conversation (`ToolCatalog.toolSetFor`) and never changed afterwards: the prompt cache and
 * the model's thinking depend on it.
 */
class ToolSet(val tools: List<NarratorTool<*>>, val definitions: List<ToolUnion>) {
    private val byName: Map<String, NarratorTool<*>> = tools.associateBy { it.name }

    init {
        require(tools.size == definitions.size) { "${tools.size} tools but ${definitions.size} definitions" }
        require(byName.size == tools.size) { "duplicate tool names: ${tools.map { it.name }}" }
    }

    val names: List<String> get() = tools.map { it.name }

    /** The tool called [name]; null when this conversation does not have it. */
    operator fun get(name: String): NarratorTool<*>? = byName[name]

    companion object {
        val EMPTY = ToolSet(emptyList(), emptyList())
    }
}

/** Runs the tool calls of a reply. Implemented by `ToolRunner`; faked to test the conversation engine. */
interface ToolExecutor {
    /**
     * Runs the `tool_use` blocks of one assistant message, in order, and returns one `tool_result`
     * per block, in the same order.
     *
     * @param llmCallId the `llm_calls` row of the reply, for the audit.
     */
    suspend fun runAll(calls: List<ToolUseBlock>, tools: ToolSet, caller: Caller, llmCallId: Long): List<ToolResultBlockParam>
}
