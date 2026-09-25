package io.github.clemsix6.narrator2.runtime

import io.github.clemsix6.narrator2.config.ConfigException
import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.config.Prompts
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsLoaderTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `the LLMGW key comes from the injected environment`() {
        val settings = SettingsLoader.load(dir) { name -> if (name == "NARRATOR_LLMGW_KEY") "  injected-key  " else null }
        try {
            assertNotNull(settings.claude, "a client is built when the key is set")
            assertTrue(settings.claude!!.isConfigured)
            assertNull(settings.loadError)
            Prompts.NAMES.forEach { assertTrue(settings.prompts.get(it).isNotBlank(), "prompt $it") }
            assertEquals("Narrateur", settings.ui["narrator.name"])
        } finally {
            settings.close()
        }
    }

    @Test
    fun `without a key there is no client, so the Director sleeps`() {
        val settings = SettingsLoader.load(dir) { null }
        assertNull(settings.claude)
        assertNull(settings.jev)
    }

    @Test
    fun `a blank key counts as missing`() {
        assertNull(SettingsLoader.load(dir) { "   " }.claude)
    }

    @Test
    fun `an invalid configuration fails and builds no client`() {
        Files.createDirectories(dir)
        dir.resolve("narrator2.json").writeText("""{ "chaos": 9 }""")
        val error = assertFailsWith<ConfigException> { SettingsLoader.load(dir) { "key" } }
        assertTrue("chaos" in error.message.orEmpty())
    }

    @Test
    fun `the fallback runs on defaults and the jar's prompts, without clients, and says why`() {
        val settings = SettingsLoader.fallback("invalid configuration: chaos")
        assertEquals(NarratorConfig(), settings.config)
        assertEquals(Prompts.bundled().get("director"), settings.prompts.get("director"))
        assertNull(settings.claude)
        assertNull(settings.jev)
        assertEquals("invalid configuration: chaos", settings.loadError)
    }
}
