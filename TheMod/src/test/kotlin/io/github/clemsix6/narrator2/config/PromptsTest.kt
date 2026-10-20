package io.github.clemsix6.narrator2.config

import io.github.clemsix6.narrator2.testing.LogCapture
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PromptsTest {
    @TempDir
    lateinit var dir: Path

    private val overrides: Path get() = dir.resolve("prompts")

    private fun bundled(name: String): String =
        Prompts::class.java.getResourceAsStream(Prompts.resource(name))!!.use { String(it.readAllBytes(), Charsets.UTF_8) }

    @Test
    fun `ships the prompts of the Director, the Analyst, the scout, the forger and its SDK, the chapter summary, the Chronicler, the Dreamer, the ping and the reflexes`() {
        assertEquals(listOf("director", "analyst", "scout", "forger", "sdk", "chapter_summary", "chronicler", "dreamer", "ping", "reflex"), Prompts.NAMES)
        assertEquals("/narrator2/forge/sdk.md", Prompts.resource(Prompts.SDK), "the SDK's reference ships with the Forge (M5 §8.2)")
        assertEquals("/narrator2/prompts/forger.md", Prompts.resource("forger"))
    }

    /**
     * What enters a prompt has a budget (M3 §2, decision 11). The system prompts are sent with every request
     * of their conversations: the Director's at each turn, the Chronicler's every ten minutes, the Dreamer's at each
     * step of a dream, the forger's (its prompt, then the SDK's reference, M5 §8.2) at each step of a task. The Forge
     * gave the Director 2,000 characters and the Chronicler 200 (M5 §9.4).
     */
    @Test
    fun `the bundled prompts stay within their budgets`() {
        val budgets = mapOf(
            "director" to 36_000, "analyst" to 8_000, "scout" to 6_000, "forger" to 9_000, "sdk" to 30_000, "chapter_summary" to 4_000, "chronicler" to 11_700,
            "dreamer" to 15_000, "ping" to 1_000, "reflex" to 6_000,
        )
        assertEquals(Prompts.NAMES.toSet(), budgets.keys, "every prompt has a budget")
        budgets.forEach { (name, max) ->
            val length = bundled(name).length
            assertTrue(length <= max, "$name.md has $length characters, over its budget of $max")
        }
    }

    @Test
    fun `the jar's prompts can be read without the override folder`() {
        Files.createDirectories(overrides)
        overrides.resolve("analyst.md").writeText("Ignored by bundled().")

        val prompts = Prompts.bundled()
        Prompts.NAMES.forEach { name -> assertEquals(bundled(name), prompts.get(name)) }
    }

    @Test
    fun `reads every prompt from the jar and copies nothing`() {
        val prompts = Prompts.load(overrides)

        Prompts.NAMES.forEach { name ->
            assertEquals(bundled(name), prompts.get(name))
            assertTrue(prompts.get(name).isNotBlank(), "$name is not empty")
        }
        assertEquals(listOf(Prompts.README), Files.list(overrides).use { files -> files.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun `creates the override folder with a README explaining it`() {
        Prompts.load(overrides)
        val readme = overrides.resolve(Prompts.README).readText()
        assertContains(readme, "/narrator reload")
        Prompts.NAMES.forEach { assertContains(readme, "`$it.md`") }
    }

    @Test
    fun `an override file replaces its prompt, read as UTF-8`() {
        Files.createDirectories(overrides)
        overrides.resolve("ping.md").writeText("Answer “pong”, é à ü.")

        val prompts = Prompts.load(overrides)
        assertEquals("Answer “pong”, é à ü.", prompts.get("ping"))
        assertEquals(bundled("director"), prompts.get("director"))
    }

    /** The SDK's reference lives with the Forge in the jar, but an operator replaces it like any prompt. */
    @Test
    fun `the SDK's reference is read from the Forge's folder and replaced like a prompt`() {
        val jar = Prompts.bundled()
        assertTrue(jar.get(Prompts.SDK).startsWith("# The Forge SDK"), jar.get(Prompts.SDK).take(80))
        assertTrue(jar.get("forger").startsWith("# Le Forgeron"), jar.get("forger").take(80))

        Files.createDirectories(overrides)
        overrides.resolve("sdk.md").writeText("A shorter SDK.")
        assertEquals("A shorter SDK.", Prompts.load(overrides).get(Prompts.SDK))
    }

    @Test
    fun `logs the source of each prompt and warns about unknown files`() {
        Files.createDirectories(overrides)
        overrides.resolve("ping.md").writeText("Pong.")
        overrides.resolve("directr.md").writeText("Typo in the name.")

        val logs = LogCapture()
        logs.use { Prompts.load(overrides) }
        assertContains(logs.text, "prompt director: bundled")
        assertContains(logs.text, "prompt ping: override ${overrides.resolve("ping.md").toAbsolutePath()}")
        assertContains(logs.text, "directr.md is ignored: no prompt is named directr")
    }

    @Test
    fun `an unknown prompt fails clearly`() {
        val error = assertFailsWith<IllegalStateException> { Prompts.load(overrides).get("nope") }
        assertEquals("unknown prompt: nope", error.message)
    }
}
