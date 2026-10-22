package io.github.clemsix6.narrator2.config

import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.testing.LogCapture
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigStoreTest {
    @TempDir
    lateinit var dir: Path

    private val file: Path get() = dir.resolve(ConfigStore.FILE_NAME)

    @Test
    fun `creates the file with every default on first run, atomically`() {
        val config = ConfigStore.load(dir)

        assertEquals(NarratorConfig(), config)
        val written = Json.parseToJsonElement(file.readText()).jsonObject
        assertEquals(
            setOf(
                "gateway", "roles", "language", "chaos", "difficulty", "journal", "observer", "director", "workers", "memory", "mechanics", "forge", "safety", "reflex",
                "style", "dashboard", "relics", "prices", "logging",
            ),
            written.keys,
            "only the settings in use",
        )
        assertEquals(setOf("claude-opus-5-5", "claude-haiku-5-5", "claude-opus-4-8"), written.getValue("prices").jsonObject.keys, "the prices of the default models and the fallback's")
        assertEquals(setOf("enabled", "model", "effort", "thinking", "timeoutMs"), written.getValue("reflex").jsonObject.keys, "no key of its own")
        assertEquals(Role.entries.map { it.id }.toSet(), written.getValue("roles").jsonObject.keys)
        assertEquals(
            setOf("baseUrl", "apiKeyEnv", "cacheTtl", "maxRetries", "retryInitialDelayMs", "retryMaxDelayMs"),
            written.getValue("gateway").jsonObject.keys,
            "no key in the file",
        )
        assertEquals(listOf(ConfigStore.FILE_NAME), Files.list(dir).use { files -> files.map { it.fileName.toString() }.toList() })
        assertEquals(config, ConfigStore.load(dir), "the written file loads back to the same configuration")
    }

    @Test
    fun `an existing file is read, never rewritten`() {
        val text = """{ "gateway": { "baseUrl": "https://llmgw.example.com" }, "notInventedYet": 1 }"""
        file.writeText(text)
        val modified = Files.getLastModifiedTime(file)

        assertEquals("https://llmgw.example.com", ConfigStore.load(dir).gateway.baseUrl)
        assertEquals(text, file.readText())
        assertEquals(modified, Files.getLastModifiedTime(file))
    }

    @Test
    fun `missing or null settings take their default, key by key and role by role`() {
        file.writeText(
            """
            {
              "gateway": { "baseUrl": "https://llmgw.example.com", "maxRetries": null },
              "roles": { "director": { "effort": "low" }, "forger": { "effort": "max" } }
            }
            """.trimIndent(),
        )

        val config = ConfigStore.load(dir)
        assertEquals("https://llmgw.example.com", config.gateway.baseUrl)
        assertEquals(GatewayConfig().maxRetries, config.gateway.maxRetries)
        assertEquals("NARRATOR_LLMGW_KEY", config.gateway.apiKeyEnv)
        assertEquals(NarratorConfig.DEFAULT_ROLES.getValue(Role.DIRECTOR).copy(effort = "low"), config.role(Role.DIRECTOR))
        val forger = config.role(Role.FORGER)
        assertEquals("max", forger.effort)
        assertEquals(64_000, forger.maxTokens, "the forger keeps its own default budget")
        assertEquals(1_200, forger.totalTimeoutSeconds, "and its own default timeout")
        assertEquals(Role.entries.toSet(), config.roles.keys, "missing roles get their default")
        assertEquals(NarratorConfig.DEFAULT_ROLES.getValue(Role.DREAMER), config.role(Role.DREAMER))
    }

    @Test
    fun `timeouts default per role`() {
        val roles = NarratorConfig().roles
        assertEquals(300, roles.getValue(Role.DIRECTOR).totalTimeoutSeconds)
        assertEquals(1_200, roles.getValue(Role.FORGER).totalTimeoutSeconds)
        assertEquals(1_200, roles.getValue(Role.DREAMER).totalTimeoutSeconds)
        listOf(Role.ANALYST, Role.SCOUT, Role.STORYTELLER, Role.CHRONICLER).forEach {
            assertEquals(600, roles.getValue(it).totalTimeoutSeconds, "$it")
        }
        assertTrue(roles.values.all { it.readTimeoutSeconds == 90L })
    }

    @Test
    fun `unknown settings are ignored with a warning, a leftover key with a specific one`() {
        file.writeText("""{ "gateway": { "apiKey": "sk-leftover-5a5a", "futureOption": 42 }, "mood": 5 }""")

        val logs = LogCapture()
        val config = logs.use { ConfigStore.load(dir) }
        assertEquals(GatewayConfig(), config.gateway)
        assertContains(logs.text, "unknown setting gateway.futureOption is ignored")
        assertContains(logs.text, "unknown setting mood is ignored")
        assertContains(logs.text, "gateway.apiKey is ignored")
        assertFalse("sk-leftover-5a5a" in logs.text)
    }

    @Test
    fun `an unknown role fails to load`() {
        file.writeText("""{ "roles": { "blacksmith": { "effort": "high" } } }""")
        val error = assertFailsWith<ConfigException> { ConfigStore.load(dir) }
        assertContains(error.message.orEmpty(), "unknown role 'blacksmith'")
        assertContains(error.message.orEmpty(), "director, analyst, scout, forger, storyteller, chronicler, dreamer")
    }

    @Test
    fun `malformed JSON gives the file, line and column, never its content`() {
        file.writeText("{\n  \"gateway\": { \"baseUrl\": \"http://secret-host-1:8088\" oops }\n}")
        val error = assertFailsWith<ConfigException> { ConfigStore.load(dir) }

        val message = error.message.orEmpty()
        assertContains(message, file.toAbsolutePath().toString())
        assertContains(message, "line 2, column")
        assertFalse("secret-host-1" in message)
        assertFalse("oops" in message)
        assertEquals(null, error.cause)
        assertEquals("{\n  \"gateway\": { \"baseUrl\": \"http://secret-host-1:8088\" oops }\n}", file.readText(), "left untouched")
    }

    @Test
    fun `a value of the wrong type gives its path, never the value`() {
        file.writeText("""{ "gateway": { "maxRetries": "secret-value-9" } }""")
        val error = assertFailsWith<ConfigException> { ConfigStore.load(dir) }

        assertContains(error.message.orEmpty(), "gateway.maxRetries")
        assertFalse("secret-value-9" in error.message.orEmpty())
    }

    @Test
    fun `values are validated on load`() {
        file.writeText(
            """
            {
              "gateway": { "baseUrl": "not a url", "cacheTtl": "10m", "maxRetries": -1 },
              "roles": { "scout": { "effort": "turbo", "readTimeoutSeconds": 0, "totalTimeoutSeconds": -5, "maxTokens": 0 } }
            }
            """.trimIndent(),
        )
        val message = assertFailsWith<ConfigException> { ConfigStore.load(dir) }.message.orEmpty()

        listOf(
            "gateway.baseUrl must be an http:// or https:// URL",
            "gateway.cacheTtl must be one of 5m, 1h, off",
            "gateway.maxRetries must not be negative",
            "roles.scout.effort must be one of low, medium, high, xhigh, max",
            "roles.scout.readTimeoutSeconds must be positive",
            "roles.scout.totalTimeoutSeconds must be positive",
            "roles.scout.maxTokens must be positive",
        ).forEach { assertContains(message, it) }
        assertFalse("turbo" in message, "values are not echoed")
    }

    @Test
    fun `the accepted efforts are the SDK's`() {
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), NarratorConfig.EFFORTS)
        assertEquals(listOf("5m", "1h", "off"), NarratorConfig.CACHE_TTLS)
    }

    @Test
    fun `plain http to another machine is accepted with a warning`() {
        file.writeText("""{ "gateway": { "baseUrl": "http://192.168.1.20:8088" } }""")
        val remote = LogCapture().use { logs -> ConfigStore.load(dir); logs.text }
        assertContains(remote, "unencrypted (http://) to 192.168.1.20")

        listOf("http://localhost:8088", "http://127.0.0.1:8088", "https://llmgw.example.com").forEach { url ->
            file.writeText("""{ "gateway": { "baseUrl": "$url" } }""")
            val local = LogCapture().use { logs -> ConfigStore.load(dir); logs.text }
            assertFalse("unencrypted" in local, url)
        }
    }

    @Test
    fun `role ids are the English identifiers of the configuration`() {
        assertEquals(
            listOf("director", "analyst", "scout", "forger", "storyteller", "chronicler", "dreamer", "armorer"),
            Role.entries.map { it.id },
        )
        Role.entries.forEach { role -> assertEquals("\"${role.id}\"", Json.encodeToString(Role.serializer(), role)) }
    }
}
