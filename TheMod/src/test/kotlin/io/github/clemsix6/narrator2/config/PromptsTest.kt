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
        Prompts::class.java.getResourceAsStream("/narrator2/prompts/$name.md")!!.use { String(it.readAllBytes(), Charsets.UTF_8) }

    @Test
    fun `ships the prompts of the Director, the Analyst, the chapter summary, the Chronicler and the ping`() {
        assertEquals(listOf("director", "analyst", "chapter_summary", "chronicler", "ping"), Prompts.NAMES)
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
