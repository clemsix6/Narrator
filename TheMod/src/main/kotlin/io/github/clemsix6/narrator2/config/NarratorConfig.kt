package io.github.clemsix6.narrator2.config

import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.OutputConfig
import io.github.clemsix6.narrator2.core.Log
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

@Serializable
data class NarratorConfig(
    val gateway: GatewayConfig = GatewayConfig(),
    /** Model, effort, token budget and timeouts of every role. */
    val roles: Map<Role, RoleConfig> = DEFAULT_ROLES,
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

        /** Prompt-cache lifetimes the API accepts (`CacheControlEphemeral.Ttl`). */
        val CACHE_TTLS: List<String> =
            listOf(CacheControlEphemeral.Ttl.TTL_5M, CacheControlEphemeral.Ttl.TTL_1H).map { it.asString() }

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
     * Lifetime of the prompt-cache marker sent with every call: `1h` or `5m`. LLMGW already marks the
     * system prompt with `1h`, and the API rejects (HTTP 400) a marker with another TTL on that block.
     */
    val cacheTtl: String = "1h",
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
                keyPath == "gateway.apiKey" -> log.warn(
                    "$file: gateway.apiKey is ignored, remove it from the file: the LLMGW key is read only " +
                        "from the environment variable named by gateway.apiKeyEnv",
                )
                default == null -> log.warn("$file: unknown setting $keyPath is ignored")
                default is JsonObject && value is JsonObject -> merged[key] = merge(default, value, keyPath, file)
                else -> merged[key] = value
            }
        }
        return JsonObject(merged)
    }

    private fun validate(config: NarratorConfig, file: Path) {
        val gateway = config.gateway
        val url = runCatching { URI(gateway.baseUrl) }.getOrNull()
        val problems = buildList {
            if (url == null || url.scheme !in setOf("http", "https") || url.host.isNullOrEmpty()) {
                add("gateway.baseUrl must be an http:// or https:// URL with a host")
            }
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
        }
        if (problems.isNotEmpty()) {
            throw ConfigException("invalid configuration in $file:\n - ${problems.joinToString("\n - ")}")
        }
        if (url!!.scheme == "http" && !isLoopback(url.host)) {
            log.warn(
                "gateway.baseUrl sends the LLMGW key unencrypted (http://) to ${url.host}, which is not " +
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
