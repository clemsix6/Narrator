package io.github.clemsix6.narrator2.forge

import io.github.clemsix6.narrator2.forge.api.ParamType
import io.github.clemsix6.narrator2.tool.ToolResult
import java.nio.file.Path
import java.time.Instant

/**
 * A refusal of the Forge, written for the model (M5 §11): `DENIED (rule): message` with a [rule] (`forge_off`,
 * `arsenal_full`, `awaiting_approval`…), `FAILED: message` when [failed] (a version that no longer compiles, a
 * compilation too long, a defect of the mod: not the caller's fault), else `Invalid arguments: message`, and the
 * message says how to fix the call (the diagnostics of a source that does not compile, for instance).
 */
class ForgeRejected(val rule: String?, message: String, val failed: Boolean = false) : Exception(message) {
    init {
        require(!(failed && rule != null)) { "a refusal of the Forge is denied by a rule or failed, not both" }
    }

    /** As a tool's answer: [ToolResult.denied] with a [rule], [ToolResult.failed] when [failed], else [ToolResult.invalid]. */
    fun toResult(): ToolResult = when {
        rule != null -> ToolResult.denied(rule, message.orEmpty())
        failed -> ToolResult.failed(message.orEmpty())
        else -> ToolResult.invalid(message.orEmpty())
    }
}

/**
 * The Forge (M5 §2, §5, §6.1-6.3): the arsenal of modules and the pipeline from a source to a loadable module
 * (size and name checks, ECJ, the guard, the manifest, the rewriting, the bench). `NarratorServices.forge`, built by
 * `createForge` after the memory and before the mechanics; [DisabledForge] when it does not run. The deployments
 * themselves are the mechanics engine's (`Mechanics.modules`, M5 §6.4).
 *
 * Nothing here runs on the server thread but [available], [unavailableReason], [arsenal]'s reads and [status]:
 * compilations run on the Forge's own compiler thread (`narrator2-forge-compiler`, one at a time), the bench on a
 * worker thread, the files on `Dispatchers.IO`.
 */
interface Forge {
    /** False with `forge.enabled` false, without ECJ, or when it could not start. Any thread. */
    val available: Boolean

    /** Why it is not [available]; null when it is. */
    val unavailableReason: String?

    val arsenal: Arsenal

    /**
     * `forge_test` (M5 §5.4 steps 1 to 7, §6.2): checks [source] as module [name], compiles, guards, reads the
     * manifest, rewrites, then runs it at the bench (smoke run, dry checks of its acts by the real tools, its
     * scenarios). Never throws for the module's own faults: they are in the report. Never on the server thread.
     */
    suspend fun test(name: String, source: String): TestReport

    /**
     * `forge_save` (M5 §5.4 steps 1 to 8): [test], at least one scenario, then a new version of [name] in the arsenal,
     * `ready`, or `pending` with `forge.approval = operator`. [note]: one English line, what it is or what changed;
     * [by]: who, `forger T-31`. Throws [ForgeRejected] (the report's diagnostics, `arsenal_full`…).
     */
    suspend fun save(name: String, source: String, note: String, by: String): ArsenalEntry

    /**
     * A version ready to load (M5 §5.4 steps 1 to 6): [name] at [version], or its latest `ready` version when null;
     * from the cache, else built on the compiler thread against the SDK of now. Throws [ForgeRejected]: unknown,
     * `pending` (`awaiting_approval`), `rejected`, or `broken` (failed: no longer compiles, or its source does not
     * match its hash any more).
     */
    suspend fun build(name: String, version: Int?): Built

    /**
     * `/narrator forge approve|reject` (M5 §6.3): [name] at [version] (its latest `pending` one when null) becomes
     * `ready` or `rejected`; [operator] is recorded. Throws [ForgeRejected] for a version that is not pending.
     */
    suspend fun review(name: String, version: Int?, approve: Boolean, operator: String): ArsenalEntry

    /** What `/narrator forge status` shows of the Forge itself (M5 §14). Any thread. */
    fun status(): ForgeStatus

    /**
     * `SERVER_STOPPING`, after the mechanics (their modules stopped first) and before the memory: the compiler thread
     * ends, the bench runs are cancelled, nothing new starts. Never waits more than a second.
     */
    fun stop()
}

/** The modules the Forge knows (M5 §6.1): the built-in ones (version 0, in the mod's jar) and the forged ones (`world/narrator2/forge/`). */
interface Arsenal {
    /** Every version known, the newest first, the built-in ones included. Any thread. */
    fun list(): List<ArsenalEntry>

    /** [name] at [version], or its latest `ready` version when [version] is null; null when there is none. Any thread. */
    fun entry(name: String, version: Int? = null): ArsenalEntry?

    /** The source of [name] at [version], as it was saved; null when unknown. Never on the server thread. */
    suspend fun source(name: String, version: Int): String?

    /** Where the source of a forged version is (`world/narrator2/forge/<name>/v<version>.java`); null for a built-in one or an unknown one. Any thread. */
    fun file(name: String, version: Int): Path?
}

/** Where a version of the arsenal stands (M5 §6.1). */
enum class ModuleStatus {
    /** Deployable. */
    READY,

    /** Waits for an operator (`forge.approval = operator`). */
    PENDING,

    /** Refused by an operator. */
    REJECTED,

    /** Computed when the server starts, never saved: it no longer compiles against the SDK, or its source does not match its hash. */
    BROKEN,
    ;

    /** `ready`: its name in the manifests and the texts. */
    val id: String get() = name.lowercase()
}

/**
 * A parameter of a module, read from its `@Param` in the bytecode (M5 §5.2): what the Director gives in
 * `deploy_module params` and what the deployment checks.
 *
 * @property fallback a parameter not required: its value when left out, in its text form; null for none.
 * @property min and [max]: the bounds of `@Param`, null when the annotation left them infinite.
 */
data class ParamSpec(
    val name: String,
    val type: ParamType,
    val doc: String,
    val required: Boolean,
    val fallback: String?,
    val min: Double?,
    val max: Double?,
    val choices: List<String>,
)

/**
 * One version of a module of the arsenal (M5 §6.1): its manifest and its state.
 *
 * @property version 0 for a built-in module, then 1, 2… for each save of the name.
 * @property builtIn shipped in the mod's jar (`resources/narrator2/forge/arsenal/<name>.java`), read-only.
 * @property summary its `@ModuleInfo` summary, English, for the Director.
 * @property scenarios the names of its `Scenarios` methods.
 * @property savedAt when it was saved; `Instant.EPOCH` for a built-in one.
 * @property by who saved it (`forger T-31`, `built-in`).
 * @property note what it is or what changed, one English line ([Forge.save]); null for a built-in one.
 * @property hash `sha256:` and the hex digest of its source.
 * @property sdk the `Sdk.VERSION` it was compiled against.
 * @property classes and [bytes]: the classes and bytecode its compilation produced; 0 until it is built.
 * @property problem a broken version's first diagnostic, or the last fault of one of its deployments; null otherwise.
 */
data class ArsenalEntry(
    val name: String,
    val version: Int,
    val status: ModuleStatus,
    val builtIn: Boolean,
    val summary: String,
    val params: List<ParamSpec>,
    val scenarios: List<String>,
    val savedAt: Instant,
    val by: String,
    val note: String?,
    val hash: String,
    val sdk: Int,
    val classes: Int,
    val bytes: Int,
    val problem: String?,
)

/**
 * What `forge_test` found (M5 §6.2).
 *
 * @property ok compiled, guard clean, smoke run and every scenario passed.
 * @property text the report for the forger, at most `ForgeLimits.TEST_REPORT_CHARS`: the steps passed, or the
 *   diagnostics (M5 §5.3).
 * @property scenariosPassed the names of the scenarios that passed.
 * @property manifest the manifest read from the bytecode; null when it did not compile, or the guard refused it.
 */
data class TestReport(val ok: Boolean, val text: String, val scenariosPassed: List<String>, val manifest: ArsenalEntry?)

/**
 * A version compiled, guarded and rewritten against the SDK of now (M5 §5.4): the classes a `ModuleLoader` defines.
 *
 * @property mainClass the module's top-level class, `OrbitingChickens` (no package).
 * @property classes binary name (`OrbitingChickens`, `OrbitingChickens$Phase`, `OrbitingChickens$-meter`) → rewritten
 *   bytecode, the meter's holder class ([meterHolder]) included.
 */
class Built(val entry: ArsenalEntry, val mainClass: String, val classes: Map<String, ByteArray>) {
    /** The class whose static field [METER_FIELD] holds the module's `Meter`: a name no Java source can write. */
    val meterHolder: String get() = mainClass + METER_SUFFIX

    companion object {
        /** What the rewriting appends to the main class's name for the meter's holder (M5 §3.4). */
        const val METER_SUFFIX = "\$-meter"

        /** The holder's one static field: the module's `Meter`, set by the host before any code of the module runs. */
        const val METER_FIELD = "m"
    }
}

/**
 * What `/narrator forge status` shows of the Forge itself (M5 §14).
 *
 * @property ecj the version of the compiler (`3.46.100`); null when it is not there.
 * @property compiler `idle`, `compiling statues (2s)`, or `stuck since 21:04:10` (a compilation that ignored its
 *   cancellation, M5 §3.8): the Forge is then unavailable until the server restarts.
 */
data class ForgeStatus(val ecj: String?, val compiler: String)

/**
 * The Forge when it does not run (M5 §11): [reason] says why (`forge.enabled is false`, `not wired yet`, `ECJ is
 * missing: …`, `it could not start: …`). Every call throws `ForgeRejected("forge_off", "the Forge is unavailable:
 * <reason>")`; the arsenal is empty.
 */
class DisabledForge(private val reason: String) : Forge {
    override val available = false
    override val unavailableReason: String = reason

    override val arsenal: Arsenal = object : Arsenal {
        override fun list(): List<ArsenalEntry> = emptyList()

        override fun entry(name: String, version: Int?): ArsenalEntry? = null

        override suspend fun source(name: String, version: Int): String? = null

        override fun file(name: String, version: Int): Path? = null
    }

    override suspend fun test(name: String, source: String): TestReport = throw rejected()

    override suspend fun save(name: String, source: String, note: String, by: String): ArsenalEntry = throw rejected()

    override suspend fun build(name: String, version: Int?): Built = throw rejected()

    override suspend fun review(name: String, version: Int?, approve: Boolean, operator: String): ArsenalEntry = throw rejected()

    override fun status(): ForgeStatus = ForgeStatus(ecj = null, compiler = "off")

    override fun stop() {}

    private fun rejected() = ForgeRejected(FORGE_OFF, "the Forge is unavailable: $reason")

    companion object {
        /** The rule of every refusal of a Forge that does not run, or that an operator switched off: `DENIED (forge_off): …`. */
        const val FORGE_OFF = "forge_off"
    }
}
