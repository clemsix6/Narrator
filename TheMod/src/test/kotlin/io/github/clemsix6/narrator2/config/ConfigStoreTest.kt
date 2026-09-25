package io.github.clemsix6.narrator2.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigStoreTest {
    @TempDir
    lateinit var dir: Path

    private val file: Path get() = dir.resolve(ConfigStore.FILE_NAME)

    @Test
    fun `creates the file with every default on first run`() {
        val config = ConfigStore.load(dir)

        assertEquals(NarratorConfig(), config)
        assertTrue(Files.exists(file))
        val written = file.readText()
        listOf("\"baseUrl\"", "\"apiKeyEnv\": \"NARRATOR_LLMGW_KEY\"", "\"forgeron\"", "\"heartbeatMinutes\"", "\"forbiddenCommands\"")
            .forEach { assertContains(written, it) }
        assertEquals(config, ConfigStore.load(dir), "the written file loads back to the same configuration")
    }

    @Test
    fun `unknown keys are ignored and missing or null values take their default`() {
        file.writeText(
            """
            {
              "gateway": { "baseUrl": "https://llmgw.example.com", "apiKey": null, "futureOption": 42 },
              "chaos": 5,
              "roles": { "director": { "effort": "high" } },
              "notInventedYet": { "a": 1 }
            }
            """.trimIndent(),
        )

        val config = ConfigStore.load(dir)
        assertEquals("https://llmgw.example.com", config.gateway.baseUrl)
        assertEquals("", config.gateway.apiKey)
        assertEquals("NARRATOR_LLMGW_KEY", config.gateway.apiKeyEnv)
        assertEquals(5, config.chaos)
        assertEquals(DirectorConfig(), config.director)
        assertEquals(RoleConfig(effort = "high"), config.role("director"))
        assertEquals(NarratorConfig.DEFAULT_ROLES.keys, config.roles.keys, "missing roles get their default")
        assertEquals("high", config.role("forgeron").effort)

        val rewritten = file.readText()
        assertFalse("futureOption" in rewritten)
        assertFalse("notInventedYet" in rewritten)
        assertContains(rewritten, "\"heartbeatMinutes\"")
    }

    @Test
    fun `an unknown role falls back to the director's settings`() {
        file.writeText("""{ "roles": { "director": { "model": "claude-test", "effort": "low" } } }""")
        assertEquals(RoleConfig(model = "claude-test", effort = "low"), ConfigStore.load(dir).role("nobody"))
    }

    @Test
    fun `a malformed file is left untouched and the error propagates`() {
        file.writeText("{ not json")
        assertFails { ConfigStore.load(dir) }
        assertEquals("{ not json", file.readText())
    }

    @Test
    fun `the key source is reported without the key`() {
        assertEquals(ConfigStore.FILE_NAME, GatewayConfig(apiKey = "secret").apiKeySource())
        assertEquals(null, GatewayConfig(apiKey = "", apiKeyEnv = "NARRATOR2_TEST_UNSET_VARIABLE").apiKeySource())
    }
}
