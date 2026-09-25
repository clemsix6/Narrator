package io.github.clemsix6.narrator2.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PromptsTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `copies every bundled prompt on first load`() {
        val prompts = Prompts.load(dir)
        Prompts.NAMES.forEach { name ->
            assertTrue(Files.exists(dir.resolve("$name.md")), "$name.md is copied")
            assertTrue(prompts.get(name).isNotBlank(), "$name is not empty")
        }
    }

    @Test
    fun `edited prompts are kept and reloaded as UTF-8`() {
        Prompts.load(dir)
        dir.resolve("ping.md").writeText("Answer “pong”, é à ü.")
        assertEquals("Answer “pong”, é à ü.", Prompts.load(dir).get("ping"))
    }

    @Test
    fun `an unknown prompt fails clearly`() {
        val error = assertFailsWith<IllegalStateException> { Prompts.load(dir).get("nope") }
        assertEquals("unknown prompt: nope", error.message)
    }
}
