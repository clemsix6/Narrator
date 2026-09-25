package io.github.clemsix6.narrator2.tool.sql

import io.github.clemsix6.narrator2.config.JournalConfig
import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.journal.JournalFixture
import io.github.clemsix6.narrator2.journal.SchemaDoc
import io.github.clemsix6.narrator2.journal.SqlLimits
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.testing.testSettings
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolArgs
import io.github.clemsix6.narrator2.tool.ToolCatalog
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolEnv
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The `sql` tool (M1_M2 §9.5): its description is the schema, its outcomes, its delays; on a real Journal. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqlToolTest {
    private val fixture = JournalFixture()
    private val services = TestServices(clock = fixture.clock, journal = fixture.journal)
    private val director = Caller(Role.DIRECTOR, "director-1", turnId = 7, taskId = null)

    @AfterAll
    fun close() = fixture.close()

    private fun query(query: String, maxRows: Int = SqlLimits.MODEL_DEFAULT_ROWS, services: TestServices = this.services): ToolResult {
        val tool = sqlTools(services).single()
        return runBlocking { (tool as SqlTool).run(ToolContext(services, director, services.clock, tool.timeoutMs), SqlArgs(query, maxRows)) }
    }

    @Test
    fun `its description is the generated schema, as is`() {
        val tool = sqlTools(services).single()

        assertEquals("sql", tool.name)
        assertEquals(SchemaDoc.text, tool.description)
    }

    @Test
    fun `it builds without a Journal, and the model sees it only when the Journal opened`() {
        val tool = sqlTools(TestServices()).single()

        assertTrue(tool.isAvailable(ToolEnv(journalAvailable = true, workerRoles = emptySet())))
        assertFalse(tool.isAvailable(ToolEnv(journalAvailable = false, workerRoles = emptySet())))
        val sets = listOf(true, false).associateWith { journal ->
            ToolCatalog.toolSetFor(Role.ANALYST, ToolEnv(journal, emptySet()), TestServices()).names
        }
        assertTrue("sql" in sets.getValue(true))
        assertFalse("sql" in sets.getValue(false))
    }

    @Test
    fun `the tool waits at least 3 s longer than the query, for the Director and the Analyst`() {
        val default = sqlTools(services).single()
        assertEquals((SqlLimits.ANALYST_TIMEOUT_SECONDS + 3) * 1_000L, default.timeoutMs)
        assertTrue(default.timeoutMs >= (JournalConfig().sqlTimeoutSeconds + 3) * 1_000L)

        val slow = TestServices(settings = testSettings(NarratorConfig(journal = JournalConfig(sqlTimeoutSeconds = 40))))
        assertEquals(43_000L, sqlTools(slow).single().timeoutMs)
    }

    @Test
    fun `rows come back in the compact format`() {
        val result = query("SELECT 42 AS answer, 'Bartok' AS player")

        assertEquals(ToolStatus.OK, result.status, result.text)
        assertTrue(result.text.startsWith("now "), result.text)
        assertContains(result.text, "\nanswer|player\n42|Bartok")
    }

    @Test
    fun `max_rows caps the rows shown, and the others are counted`() {
        val result = query("SELECT * FROM range(10) t(n) ORDER BY n", maxRows = 3)

        assertEquals(ToolStatus.OK, result.status, result.text)
        assertContains(result.text, "\nn\n0\n1\n2\n")
        assertContains(result.text, "3 of 10 rows shown")
    }

    @Test
    fun `a refused or wrong query is the model's to fix`() {
        val write = query("DELETE FROM events")
        assertEquals(ToolStatus.INVALID, write.status, write.text)
        assertContains(write.text, "Invalid arguments: only a single read-only SELECT is allowed")

        val hidden = query("SELECT text FROM private_messages")
        assertEquals(ToolStatus.INVALID, hidden.status, hidden.text)

        val column = query("SELECT nope FROM chat_log")
        assertEquals(ToolStatus.INVALID, column.status, column.text)
        assertContains(column.text, "Binder Error")
    }

    @Test
    fun `a Journal that is down is a failure, not the query's fault`() {
        val result = query("SELECT 1", services = TestServices())

        assertEquals(ToolStatus.FAILED, result.status, result.text)
        assertContains(result.text, "FAILED: the Journal is unavailable")
    }

    @Test
    fun `the bounds of the arguments are checked before the query`() {
        val decoded = ToolArgs.decode(SqlArgs.serializer(), """{"query":"SELECT 1","max_rows":500}""")

        assertEquals(ToolArgs.Decoded.Invalid("max_rows must be between 1 and 200 (got 500)"), decoded)
        assertEquals(ToolArgs.Decoded.Ok(SqlArgs("SELECT 1")), ToolArgs.decode(SqlArgs.serializer(), """{"query":"SELECT 1"}"""))
    }
}
