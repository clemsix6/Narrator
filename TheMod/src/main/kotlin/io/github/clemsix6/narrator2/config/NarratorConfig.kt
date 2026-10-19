package io.github.clemsix6.narrator2.config

import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.OutputConfig
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.guard.CommandPolicy
import io.github.clemsix6.narrator2.llm.Role
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.ZoneId

@Serializable
data class NarratorConfig(
    val gateway: GatewayConfig = GatewayConfig(),
    /** Model, effort, token budget and timeouts of every role. */
    val roles: Map<Role, RoleConfig> = DEFAULT_ROLES,
    /**
     * Language of the texts players read (`ui/<language>.properties`: the Narrator's name, chat prefix,
     * command replies). What the Narrator says follows its prompts.
     */
    val language: String = "fr",
    /** How far the Narrator may go against players: 0 (no hostile action) to 5. Sets the engine's caps. */
    val chaos: Int = 3,
    val journal: JournalConfig = JournalConfig(),
    val observer: ObserverConfig = ObserverConfig(),
    val director: DirectorConfig = DirectorConfig(),
    val workers: WorkersConfig = WorkersConfig(),
    val memory: MemoryConfig = MemoryConfig(),
    val safety: SafetyConfig = SafetyConfig(),
    val reflex: ReflexConfig = ReflexConfig(),
    val logging: LoggingConfig = LoggingConfig(),
) {
    /** Settings of [role]. A loaded configuration has every role: the loader fills in the missing ones. */
    fun role(role: Role): RoleConfig = roles.getValue(role)

    companion object {
        /** Effort levels the API accepts (`OutputConfig.Effort`, SDK 2.65.0). */
        val EFFORTS: List<String> = listOf(
            OutputConfig.Effort.LOW, OutputConfig.Effort.MEDIUM, OutputConfig.Effort.HIGH,
            OutputConfig.Effort.XHIGH, OutputConfig.Effort.MAX,
        ).map { it.asString() }

        /** Effort levels of `reflex.effort`: the API refuses disabled thinking above `high`. */
        val REFLEX_EFFORTS: List<String> = listOf(OutputConfig.Effort.LOW, OutputConfig.Effort.MEDIUM, OutputConfig.Effort.HIGH).map { it.asString() }

        /** `gateway.cacheTtl` value that sends no cache marker at all. */
        const val NO_CACHE_MARKER = "off"

        /** Values of `gateway.cacheTtl`: the lifetimes the API accepts (`CacheControlEphemeral.Ttl`), or [NO_CACHE_MARKER]. */
        val CACHE_TTLS: List<String> =
            listOf(CacheControlEphemeral.Ttl.TTL_5M, CacheControlEphemeral.Ttl.TTL_1H).map { it.asString() } + NO_CACHE_MARKER

        val DEFAULT_ROLES: Map<Role, RoleConfig> = mapOf(
            Role.DIRECTOR to RoleConfig(effort = "medium", totalTimeoutSeconds = 300),
            Role.ANALYST to RoleConfig(effort = "low"),
            Role.SCOUT to RoleConfig(effort = "low"),
            Role.FORGER to RoleConfig(effort = "high", maxTokens = 64_000, totalTimeoutSeconds = 1_200),
            Role.STORYTELLER to RoleConfig(effort = "medium"),
            Role.CHRONICLER to RoleConfig(effort = "low"),
            Role.DREAMER to RoleConfig(effort = "high", maxTokens = 64_000, totalTimeoutSeconds = 1_200),
        )
    }
}

@Serializable
data class GatewayConfig(
    /** LLMGW base URL (Anthropic Messages API). */
    val baseUrl: String = "http://127.0.0.1:8088",
    /** Environment variable holding the LLMGW project key. The key itself never goes in this file. */
    val apiKeyEnv: String = "NARRATOR_LLMGW_KEY",
    /**
     * The automatic prompt-cache marker sent with every call: `off` (none), `1h` or `5m`.
     *
     * `off` suits the owner's LLMGW (verified 25/09/2026): it places its own `1h` markers, on the
     * system prompt and the latest turns, and caching works. A marker of ours is then the fifth of a
     * multi-turn conversation, which the API rejects (HTTP 400, at most 4), and `5m` also clashes with
     * the gateway's `1h` (HTTP 400). Use `1h` or `5m` behind a gateway that adds no marker.
     */
    val cacheTtl: String = NarratorConfig.NO_CACHE_MARKER,
    /** Retries after a transient failure (429, 529, 5xx, connection error, stream cut before any content). */
    val maxRetries: Int = 3,
    /** First retry delay; it doubles on each attempt (with jitter) up to [retryMaxDelayMs]. */
    val retryInitialDelayMs: Long = 1_000,
    val retryMaxDelayMs: Long = 30_000,
)

@Serializable
data class RoleConfig(
    val model: String = "claude-opus-5-5",
    /** low | medium | high | xhigh | max. Opus 5.5 always thinks: effort is the only dial. */
    val effort: String = "medium",
    /** Covers thinking as well as the visible reply. */
    val maxTokens: Long = 32_000,
    /** Longest silence tolerated from the gateway, while waiting for the answer or during the stream. */
    val readTimeoutSeconds: Long = 90,
    /** Longest a whole call may take, retries included. */
    val totalTimeoutSeconds: Long = 600,
)

/** The Journal (DuckDB, `world/narrator2/narrator.duckdb`). */
@Serializable
data class JournalConfig(
    /** How often the writer commits what is queued. */
    val flushIntervalMs: Long = 1_000,
    /** Events waiting for the writer; beyond, new events are lost (and counted). */
    val queueCapacity: Int = 65_536,
    /** DuckDB's memory limit. */
    val memoryLimitMb: Int = 512,
    /** DuckDB's worker threads. */
    val threads: Int = 2,
    /** Time zone of the timestamps read back, a named zone such as `Europe/Paris` or `UTC`; empty for the server's zone. */
    val timeZone: String = "",
    /** Longest a `sql` query of the Director may run. */
    val sqlTimeoutSeconds: Int = 5,
    /** A warning is logged at startup when the database is larger. Nothing is ever deleted. */
    val warnSizeMb: Long = 4_096,
)

/** The Observer, which turns what players do into events. */
@Serializable
data class ObserverConfig(
    /**
     * Command roots whose text is never journaled: passwords of login plugins, and `narrator` (the
     * operators' instructions and queries stay out of the model's sight). Only the roots of
     * `CommandIssued.TEXT_KEPT_ROOTS` ever keep their text; this list withholds more.
     */
    val redactedCommands: List<String> = listOf("login", "l", "register", "reg", "changepassword", "narrator"),
    /**
     * Commands that send a private message between players: the vanilla ones and those of common mods.
     * A command line that runs one of them, even behind `execute … run` or through a redirect, keeps
     * only its root.
     */
    val privateMessageCommands: List<String> = listOf("msg", "tell", "w", "teammsg", "tm", "r", "reply", "whisper", "pm", "dm", "mail"),
)

/** How the Director passes operator and engine notes to the model (`director.operatorChannel`). */
enum class OperatorChannelMode(val id: String) {
    /** A `role: system` message right after the user message of the request. */
    SYSTEM_MESSAGE("system_message"),

    /** `<operator>` / `<engine>` tags at the end of the user message (fallback). */
    USER_TAG("user_tag"),
    ;

    companion object {
        fun of(id: String): OperatorChannelMode? = entries.firstOrNull { it.id == id }
    }
}

/** The Director: when it wakes up and how long its chapters last. */
@Serializable
data class DirectorConfig(
    /** False keeps the Director asleep; the Journal and the Observer still run. */
    val enabled: Boolean = true,
    /** A heartbeat wakes the Director at a random time in this range of minutes after its last turn: `[min, max]`. */
    val heartbeatMinutes: List<Int> = listOf(3, 8),
    /** Wait after the last chat message addressed to the Narrator before waking up, to catch the follow-ups. */
    val chatDebounceMs: Long = 1_500,
    /** Context size (tokens) from which a chapter closes at the next quiet moment. */
    val chapterTokenLimit: Long = 150_000,
    /** Context size (tokens) from which a chapter closes at the end of the turn. */
    val chapterHardLimit: Long = 180_000,
    /** Model requests per turn before its tool calls are refused. */
    val maxStepsPerTurn: Int = 12,
    /** `system_message` or `user_tag`, see [OperatorChannelMode]. */
    val operatorChannel: String = OperatorChannelMode.SYSTEM_MESSAGE.id,
    /** A chapter closes after this many minutes without any player online. */
    val emptyServerCloseMinutes: Int = 10,
    /** Shows "the Narrator is thinking" to the players waiting for an answer. */
    val thinkingIndicator: Boolean = true,
    /** Turns per hour beyond which only addressed chat and prayers wake the Director: the cost's ceiling. */
    val turnsPerHour: Int = 30,
) {
    /** [heartbeatMinutes] as a range; valid once the configuration is validated. */
    val heartbeatRange: IntRange get() = heartbeatMinutes[0]..heartbeatMinutes[1]

    /** [operatorChannel] as a mode; valid once the configuration is validated. */
    val channelMode: OperatorChannelMode get() = OperatorChannelMode.of(operatorChannel) ?: error("invalid operatorChannel")
}

/** Workers: the assistants the Director delegates to (the Analyst in M2). */
@Serializable
data class WorkersConfig(
    /** Tasks running at the same time; the others wait in line. */
    val maxConcurrent: Int = 3,
    /** Model requests per task. */
    val maxSteps: Int = 15,
    /** Longest a task may run. */
    val timeoutSeconds: Int = 180,
)

/**
 * The Narrator's memory (design M3 §17): when its jobs run and how much it recalls by itself. Budgets
 * and formulas are constants of the code, not settings.
 */
@Serializable
data class MemoryConfig(
    /** False keeps the memory off: no memory tool, no memory in the briefing, no job. */
    val enabled: Boolean = true,
    /** The Chronicler reads the Journal behind the Director this often while a chapter is open. */
    val chronicleMinutes: Int = 10,
    /** A dream starts once nobody has been online for this long (a nap happened since the last dream, [dreamMinHours] passed). */
    val dreamAfterEmptyMinutes: Int = 20,
    /** A dream starts anyway when there was none for this long (and a nap happened since). */
    val dreamMaxHours: Int = 24,
    /** Players the Dreamer reflects on in one dream, the most active first. */
    val dreamMaxPlayers: Int = 8,
    /** The sheets' statistics of the players online are computed again this often. */
    val statsMinutes: Int = 10,
    /** Memories recalled by themselves at most per wake-up of the Director; 0 for none. */
    val recallPerWake: Int = 2,
    /** A memory recalled by itself is not recalled again before this much world play time. */
    val recallCooldownPlayMinutes: Int = 180,
    /** A player without the Narrator's attention for this much of their play shows in the situation. */
    val attentionMinutes: Int = 40,
    /**
     * A dream after an empty server waits this long after the end of the last one, so that several sessions a
     * day make one or two dreams; the daily dream and an operator's do not wait.
     */
    val dreamMinHours: Int = 6,
    /**
     * When the memory first starts on a world, the chapters closed up to this many days before get their nap;
     * older ones never do. Raising it later naps older chapters at the next catch-up.
     */
    val catchUpDays: Int = 2,
)

/** Limits the engine enforces whatever the model asks. */
@Serializable
data class SafetyConfig(
    /** Players the Narrator never acts against (merged with `/narrator protect`). */
    val protectedPlayers: List<String> = emptyList(),
    /** Command roots `run_command` refuses, on top of the built-in list. It can only add roots. */
    val extraForbiddenCommands: List<String> = emptyList(),
    /**
     * Command roots `run_command` may run on top of the reviewed ones (`CommandPolicy.ALLOWED`): commands
     * of other mods, refused until an operator has read them. Never one of the built-in forbidden roots.
     */
    val extraAllowedCommands: List<String> = emptyList(),
)

/**
 * The reflexes: a small Claude model, through LLMGW with the gateway's key, labels each public message,
 * prayer and heartbeat. The chat heuristic takes over without it, and for any call it fails to answer.
 */
@Serializable
data class ReflexConfig(
    /** The model labels when enabled and the LLMGW key is set; the heuristic answers otherwise. */
    val enabled: Boolean = true,
    /** A model that accepts disabled thinking and an effort, such as Haiku 5.5. */
    val model: String = "claude-haiku-5-5",
    /** low | medium | high: without thinking, the API refuses more than `high`. */
    val effort: String = "low",
    /** Lets the model think before it labels (adaptive thinking at [effort]): slower; raise [timeoutMs] with it. */
    val thinking: Boolean = false,
    /** Longest a call may take, never retried; the heuristic answers after that. */
    val timeoutMs: Long = 3_000,
)

@Serializable
data class LoggingConfig(
    /** Writes full conversation transcripts (JSONL + Markdown) under world/narrator2/logs. */
    val transcripts: Boolean = true,
    /** Subsystem tag → DEBUG | INFO | WARN | ERROR | OFF, e.g. {"LLM": "DEBUG"}. */
    val levels: Map<String, String> = emptyMap(),
)

/** A configuration file that cannot be used. The message never quotes the file, which may hold secrets. */
class ConfigException(message: String) : Exception(message)

/**
 * Reads `config/narrator2/narrator2.json`. The file is written once, with every default, when it does
 * not exist; after that it belongs to the operator and is only read.
 */
object ConfigStore {
    const val FILE_NAME = "narrator2.json"

    private val log = Log.config

    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        coerceInputValues = true
    }

    /** Settings whose keys are free-form (a map), so unknown keys there are expected. */
    private val FREE_FORM = setOf("logging.levels")

    /** Keys an operator may be tempted to add, which must never be read from the file → what they hold. */
    private val SECRETS = mapOf("gateway.apiKey" to "the LLMGW key")

    /** A command root, as players type it after `/`. */
    private val COMMAND_ROOT = Regex("[a-z0-9_.:-]+")

    /** A Minecraft player name. */
    private val PLAYER_NAME = Regex("[A-Za-z0-9_]{1,16}")

    /** A language code, e.g. `fr` or `pt_BR`. */
    private val LANGUAGE = Regex("[a-z]{2,3}(_[A-Z]{2})?")

    /**
     * Loads the configuration, creating the file with defaults on first run. Settings missing from the
     * file (null included) take their default, key by key, so a role that only sets its effort keeps
     * its own default model and timeouts. Unknown settings are logged and ignored; an unknown role or
     * an invalid value fails with a [ConfigException].
     */
    fun load(dir: Path): NarratorConfig {
        val file = dir.resolve(FILE_NAME).toAbsolutePath()
        if (!Files.exists(file)) {
            val config = NarratorConfig()
            writeAtomically(file, json.encodeToString(NarratorConfig.serializer(), config) + "\n")
            log.info("created $file with default values")
            return config
        }

        val text = Files.readString(file)
        val parsed = try {
            Json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw ConfigException(describe(file, e, text))
        }
        if (parsed !is JsonObject) throw ConfigException("$file: the configuration must be a JSON object")
        val defaults = json.encodeToJsonElement(NarratorConfig.serializer(), NarratorConfig()).jsonObject
        val config = try {
            // Decoded from text rather than from the tree: only the text decoder reports full paths.
            json.decodeFromString(NarratorConfig.serializer(), merge(defaults, parsed, "", file).toString())
        } catch (e: SerializationException) {
            throw ConfigException(describe(file, e, text = null))
        }
        validate(config, file)
        return config
    }

    /** [overrides] laid over [defaults] key by key; unknown settings are reported and dropped. */
    private fun merge(defaults: JsonObject, overrides: JsonObject, path: String, file: Path): JsonObject {
        val merged = defaults.toMutableMap()
        for ((key, value) in overrides) {
            val keyPath = if (path.isEmpty()) key else "$path.$key"
            val default = defaults[key]
            when {
                path in FREE_FORM -> merged[key] = value
                default == null && path == "roles" -> throw ConfigException(
                    "$file: unknown role '$key' in roles (known roles: ${Role.entries.joinToString { it.id }})",
                )
                keyPath in SECRETS -> log.warn(
                    "$file: $keyPath is ignored, remove it from the file: ${SECRETS.getValue(keyPath)} is read only " +
                        "from the environment variable named by ${path}.apiKeyEnv",
                )
                default == null -> log.warn("$file: unknown setting $keyPath is ignored")
                default is JsonObject && value is JsonObject -> merged[key] = merge(default, value, keyPath, file)
                else -> merged[key] = value
            }
        }
        return JsonObject(merged)
    }

    private fun validate(config: NarratorConfig, file: Path) {
        val problems = problems(config)
        if (problems.isNotEmpty()) {
            throw ConfigException("invalid configuration in $file:\n - ${problems.joinToString("\n - ")}")
        }
        warnIfUnencrypted("gateway.baseUrl", config.gateway.baseUrl, "the LLMGW key")
    }

    /**
     * Every invalid value of [config], one English sentence each, naming the setting and what it
     * accepts. Values are never quoted: the file may hold secrets.
     */
    internal fun problems(config: NarratorConfig): List<String> = buildList {
        val gateway = config.gateway
        if (!isHttpUrl(gateway.baseUrl)) add("gateway.baseUrl must be an http:// or https:// URL with a host")
        if (gateway.apiKeyEnv.isBlank()) add("gateway.apiKeyEnv must name an environment variable")
        if (gateway.cacheTtl !in NarratorConfig.CACHE_TTLS) {
            add("gateway.cacheTtl must be one of ${NarratorConfig.CACHE_TTLS.joinToString()}")
        }
        if (gateway.maxRetries < 0) add("gateway.maxRetries must not be negative")
        if (gateway.retryInitialDelayMs <= 0) add("gateway.retryInitialDelayMs must be positive")
        if (gateway.retryMaxDelayMs < gateway.retryInitialDelayMs) {
            add("gateway.retryMaxDelayMs must be at least gateway.retryInitialDelayMs")
        }
        config.roles.forEach { (role, settings) ->
            val prefix = "roles.${role.id}"
            if (settings.model.isBlank()) add("$prefix.model must not be empty")
            if (settings.effort !in NarratorConfig.EFFORTS) {
                add("$prefix.effort must be one of ${NarratorConfig.EFFORTS.joinToString()}")
            }
            if (settings.maxTokens <= 0) add("$prefix.maxTokens must be positive")
            if (settings.readTimeoutSeconds <= 0) add("$prefix.readTimeoutSeconds must be positive")
            if (settings.totalTimeoutSeconds <= 0) add("$prefix.totalTimeoutSeconds must be positive")
        }

        if (!LANGUAGE.matches(config.language) || !UiTexts.isBundled(config.language)) {
            add("language must be a language the mod ships texts for (ui/<language>.properties), such as fr")
        }
        range("chaos", config.chaos.toLong(), 0, 5)

        val journal = config.journal
        range("journal.flushIntervalMs", journal.flushIntervalMs, 200, 10_000)
        range("journal.queueCapacity", journal.queueCapacity.toLong(), 1_024, 1_048_576)
        range("journal.memoryLimitMb", journal.memoryLimitMb.toLong(), 64, 8_192)
        range("journal.threads", journal.threads.toLong(), 1, 8)
        if (journal.timeZone.isNotBlank() && !isNamedZone(journal.timeZone)) {
            add("journal.timeZone must be empty (the server's zone) or a named time zone such as Europe/Paris or UTC, not an offset")
        }
        range("journal.sqlTimeoutSeconds", journal.sqlTimeoutSeconds.toLong(), 1, 60)
        if (journal.warnSizeMb <= 0) add("journal.warnSizeMb must be positive")

        commandRoots("observer.redactedCommands", config.observer.redactedCommands)
        commandRoots("observer.privateMessageCommands", config.observer.privateMessageCommands)

        val director = config.director
        val heartbeat = director.heartbeatMinutes
        if (heartbeat.size != 2 || heartbeat[0] < 1 || heartbeat[0] > heartbeat[1] || heartbeat[1] > 60) {
            add("director.heartbeatMinutes must be [min, max] with 1 <= min <= max <= 60")
        }
        range("director.chatDebounceMs", director.chatDebounceMs, 200, 10_000)
        if (director.chapterTokenLimit < 20_000 || director.chapterTokenLimit > director.chapterHardLimit ||
            director.chapterHardLimit > 900_000
        ) {
            add("director.chapterTokenLimit and chapterHardLimit must satisfy 20000 <= chapterTokenLimit <= chapterHardLimit <= 900000")
        }
        range("director.maxStepsPerTurn", director.maxStepsPerTurn.toLong(), 3, 30)
        if (OperatorChannelMode.of(director.operatorChannel) == null) {
            add("director.operatorChannel must be one of ${OperatorChannelMode.entries.joinToString { it.id }}")
        }
        range("director.emptyServerCloseMinutes", director.emptyServerCloseMinutes.toLong(), 1, 120)
        range("director.turnsPerHour", director.turnsPerHour.toLong(), 1, 600)

        val workers = config.workers
        range("workers.maxConcurrent", workers.maxConcurrent.toLong(), 1, 8)
        range("workers.maxSteps", workers.maxSteps.toLong(), 3, 40)
        range("workers.timeoutSeconds", workers.timeoutSeconds.toLong(), 30, 1_800)

        val memory = config.memory
        range("memory.chronicleMinutes", memory.chronicleMinutes.toLong(), 2, 60)
        range("memory.dreamAfterEmptyMinutes", memory.dreamAfterEmptyMinutes.toLong(), 5, 240)
        range("memory.dreamMaxHours", memory.dreamMaxHours.toLong(), 6, 168)
        range("memory.dreamMaxPlayers", memory.dreamMaxPlayers.toLong(), 1, 20)
        range("memory.statsMinutes", memory.statsMinutes.toLong(), 1, 60)
        range("memory.recallPerWake", memory.recallPerWake.toLong(), 0, 5)
        range("memory.recallCooldownPlayMinutes", memory.recallCooldownPlayMinutes.toLong(), 10, 1_440)
        range("memory.attentionMinutes", memory.attentionMinutes.toLong(), 10, 600)
        range("memory.dreamMinHours", memory.dreamMinHours.toLong(), 0, 24)
        range("memory.catchUpDays", memory.catchUpDays.toLong(), 0, 30)

        config.safety.protectedPlayers.forEachIndexed { index, name ->
            if (!PLAYER_NAME.matches(name)) {
                add("safety.protectedPlayers[$index] must be a player name (1 to 16 letters, digits or _)")
            }
        }
        commandRoots("safety.extraForbiddenCommands", config.safety.extraForbiddenCommands)
        commandRoots("safety.extraAllowedCommands", config.safety.extraAllowedCommands)
        config.safety.extraAllowedCommands.forEachIndexed { index, root ->
            when (root) {
                in CommandPolicy.FORBIDDEN -> add("safety.extraAllowedCommands[$index]: $root is forbidden by the built-in list and cannot be allowed")
                in config.safety.extraForbiddenCommands -> add("safety.extraAllowedCommands[$index]: $root is also in safety.extraForbiddenCommands")
            }
        }

        val reflex = config.reflex
        if (reflex.model.isBlank()) add("reflex.model must not be empty")
        if (reflex.effort !in NarratorConfig.REFLEX_EFFORTS) add("reflex.effort must be one of ${NarratorConfig.REFLEX_EFFORTS.joinToString()}")
        range("reflex.timeoutMs", reflex.timeoutMs, 500, 10_000)
    }

    private fun MutableList<String>.range(setting: String, value: Long, min: Long, max: Long) {
        if (value !in min..max) add("$setting must be between $min and $max")
    }

    private fun MutableList<String>.commandRoots(setting: String, roots: List<String>) {
        roots.forEachIndexed { index, root ->
            if (!COMMAND_ROOT.matches(root)) add("$setting[$index] must be a command root in lowercase, without / or spaces")
        }
    }

    private fun isHttpUrl(value: String): Boolean {
        val url = runCatching { URI(value) }.getOrNull() ?: return false
        return url.scheme in setOf("http", "https") && !url.host.isNullOrEmpty()
    }

    /**
     * A named IANA zone such as `Europe/Paris` or `UTC`. DuckDB's `SET TimeZone` refuses offsets (`Z`,
     * `+02:00`), and an unusable zone would leave the server without its Journal. Every zone the JVM
     * names is accepted by DuckDB (checked for all 604 of JDK 25).
     */
    private fun isNamedZone(value: String): Boolean = value in ZoneId.getAvailableZoneIds()

    private fun warnIfUnencrypted(setting: String, value: String, secret: String) {
        val url = URI(value)
        if (url.scheme == "http" && !isLoopback(url.host)) {
            log.warn(
                "$setting sends $secret unencrypted (http://) to ${url.host}, which is not " +
                    "this machine: use https:// or an SSH tunnel to localhost",
            )
        }
    }

    private fun isLoopback(host: String): Boolean =
        host.equals("localhost", ignoreCase = true) || host.startsWith("127.") || host == "[::1]"

    /**
     * Where parsing failed, from [error]'s message: the JSON path and, when [text] is known, the line
     * and column. The message itself is never repeated, as it quotes the file.
     */
    private fun describe(file: Path, error: SerializationException, text: String?): String {
        val message = error.message.orEmpty()
        val where = buildList {
            val offset = Regex("""at offset (\d+)""").find(message)?.groupValues?.get(1)?.toIntOrNull()
            if (text != null && offset != null && offset <= text.length) {
                val before = text.substring(0, offset)
                add("line ${before.count { it == '\n' } + 1}, column ${offset - before.lastIndexOf('\n')}")
            }
            Regex("""at (?:path|element): (\$\S*)""").find(message)?.groupValues?.get(1)?.let { add(it) }
        }
        val position = if (where.isEmpty()) "" else " at ${where.joinToString(", ")}"
        return "cannot read $file$position: invalid JSON or a value of the wrong type"
    }

    /** Writes [text] to a temporary file next to [file], then renames it, so a crash never leaves half a file. */
    private fun writeAtomically(file: Path, text: String) {
        Files.createDirectories(file.parent)
        val temp = Files.createTempFile(file.parent, FILE_NAME, ".tmp")
        try {
            Files.writeString(temp, text)
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}
