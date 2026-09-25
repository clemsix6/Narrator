package io.github.clemsix6.narrator2.config

import io.github.clemsix6.narrator2.core.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class NarratorConfig(
    val gateway: GatewayConfig = GatewayConfig(),
    val jev: JevConfig = JevConfig(),
    /** Language the Narrator speaks to players. */
    val language: String = "fr",
    /** 0 (calm) to 5 (chaos): scales punishment severity caps and cooldowns. */
    val chaos: Int = 3,
    /** Model and effort per role: director, forgeron, analyste, eclaireur, scenariste, chroniqueur, reveur. */
    val roles: Map<String, RoleConfig> = DEFAULT_ROLES,
    val director: DirectorConfig = DirectorConfig(),
    val workers: WorkersConfig = WorkersConfig(),
    val safety: SafetyConfig = SafetyConfig(),
    val logging: LoggingConfig = LoggingConfig(),
) {
    fun role(name: String): RoleConfig = roles[name] ?: roles[DIRECTOR] ?: RoleConfig()

    companion object {
        const val DIRECTOR = "director"

        val DEFAULT_ROLES = mapOf(
            DIRECTOR to RoleConfig(effort = "medium"),
            "forgeron" to RoleConfig(effort = "high", maxTokens = 64_000),
            "analyste" to RoleConfig(effort = "low"),
            "eclaireur" to RoleConfig(effort = "low"),
            "scenariste" to RoleConfig(effort = "medium"),
            "chroniqueur" to RoleConfig(effort = "low"),
            "reveur" to RoleConfig(effort = "high", maxTokens = 64_000),
        )
    }
}

@Serializable
data class GatewayConfig(
    /** LLMGW base URL (Anthropic Messages API). */
    val baseUrl: String = "http://127.0.0.1:8088",
    /** LLMGW project key. Prefer [apiKeyEnv] so the key stays out of this file. */
    val apiKey: String = "",
    val apiKeyEnv: String = "NARRATOR_LLMGW_KEY",
    val timeoutSeconds: Long = 600,
    val maxRetries: Int = 2,
) {
    fun resolveApiKey(): String? = apiKey.ifBlank { System.getenv(apiKeyEnv).orEmpty() }.ifBlank { null }
}

@Serializable
data class JevConfig(
    val enabled: Boolean = false,
    val baseUrl: String = "https://api.typesafe.ai",
    val apiKey: String = "",
    val apiKeyEnv: String = "TYPESAFE_API_KEY",
    /** Pinned once thresholds are tuned: `jev-latest` moves when TypeSafe ships a new version. */
    val model: String = "jev-1.13.0",
) {
    fun resolveApiKey(): String? = apiKey.ifBlank { System.getenv(apiKeyEnv).orEmpty() }.ifBlank { null }
}

@Serializable
data class RoleConfig(
    val model: String = "claude-opus-5-5",
    /** low | medium | high | xhigh | max. Opus 5.5 always thinks: effort is the only dial. */
    val effort: String = "medium",
    /** Covers thinking as well as the visible reply. */
    val maxTokens: Long = 32_000,
)

@Serializable
data class DirectorConfig(
    /** Random interval between heartbeats while players are online, in minutes: [min, max]. */
    val heartbeatMinutes: List<Int> = listOf(3, 8),
    /** A chapter closes past this many context tokens; the next one starts from a fresh briefing. */
    val chapterTokenLimit: Long = 150_000,
    /** Chat bursts are gathered for this long before waking the Director. */
    val chatDebounceMs: Long = 1_500,
)

@Serializable
data class WorkersConfig(
    val maxConcurrent: Int = 3,
)

@Serializable
data class SafetyConfig(
    val maxExplosionPower: Float = 4f,
    val maxSpawnPerAction: Int = 12,
    val protectedPlayers: List<String> = emptyList(),
    val forbiddenCommands: List<String> = listOf(
        "stop", "op", "deop", "ban", "ban-ip", "pardon", "pardon-ip", "whitelist",
        "save-off", "save-all", "kick", "reload", "debug", "jfr", "perf", "transfer",
    ),
)

@Serializable
data class LoggingConfig(
    /** Writes full conversation transcripts (JSONL + Markdown) under world/narrator2/logs. */
    val transcripts: Boolean = true,
    /** Subsystem tag → DEBUG | INFO | WARN | ERROR | OFF, e.g. {"LLM": "DEBUG"}. */
    val levels: Map<String, String> = emptyMap(),
)

/** Reads and writes `config/narrator2/narrator2.json`. */
object ConfigStore {
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /**
     * Loads the configuration, creating it with defaults on first run. The file is rewritten after a
     * successful load so that settings added in newer versions appear with their default value. A file
     * that fails to parse is left untouched and the error propagates.
     */
    fun load(dir: Path): NarratorConfig {
        val file = dir.resolve("narrator2.json")
        val config = if (Files.exists(file)) {
            json.decodeFromString(NarratorConfig.serializer(), Files.readString(file))
        } else {
            Log.config.info("création de ${file.toAbsolutePath()} avec les valeurs par défaut")
            NarratorConfig()
        }
        Files.createDirectories(dir)
        Files.writeString(file, json.encodeToString(NarratorConfig.serializer(), config) + "\n")
        return config
    }
}
