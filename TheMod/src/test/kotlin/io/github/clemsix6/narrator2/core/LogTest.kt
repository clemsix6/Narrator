package io.github.clemsix6.narrator2.core

import io.github.clemsix6.narrator2.testing.LogCapture
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LogTest {
    @AfterEach
    fun resetLevels() = Log.applyLevels(emptyMap())

    @Test
    fun `applies a level per subsystem, case-insensitively`() {
        Log.applyLevels(mapOf("LLM" to "debug", "Trace" to "OFF"))
        assertEquals(Log.Level.DEBUG, Log.llm.level)
        assertEquals(Log.Level.OFF, Log.trace.level)
        assertEquals(Log.Level.INFO, Log.main.level)

        Log.applyLevels(emptyMap())
        assertEquals(Log.Level.INFO, Log.llm.level, "levels left out go back to INFO")
    }

    @Test
    fun `warns about an unknown subsystem or level`() {
        val logs = LogCapture()
        logs.use { Log.applyLevels(mapOf("Directr" to "DEBUG", "LLM" to "VERBOSE")) }

        assertContains(logs.text, "unknown subsystem 'Directr'")
        assertContains(logs.text, "known: Bus, Command, Config, Director, Guard, Jev, Journal, LLM, Mechanics, Memory, Narrator, Observer, Sql, Tool, Trace, Worker")
        assertContains(logs.text, "logging.levels.LLM: unknown level 'VERBOSE'")
        assertEquals(Log.Level.INFO, Log.llm.level)
    }

    @Test
    fun `a level filters the lines below it`() {
        val logs = LogCapture()
        logs.use {
            Log.applyLevels(mapOf("Command" to "WARN"))
            Log.command.info("hidden info")
            Log.command.warn("shown warning")
        }
        assertFalse("hidden info" in logs.text)
        assertContains(logs.text, "[Command] shown warning")
    }
}
