package io.github.clemsix6.narrator2.tool.task

import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.tool.ToolArgs
import io.github.clemsix6.narrator2.tool.ToolCatalog
import io.github.clemsix6.narrator2.tool.ToolEnv
import io.github.clemsix6.narrator2.tool.ToolSchema
import io.github.clemsix6.narrator2.trace.TaskRecord
import io.github.clemsix6.narrator2.trace.TaskRecord.Status
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The delegation tools (M1_M2 §9.6): what they accept, when they are shown, how `tasks` lists. */
class TaskToolsTest {
    @Test
    fun `delegate and tasks are shown only when a worker role is wired`() {
        val services = TestServices()
        val wired = ToolCatalog.toolSetFor(Role.DIRECTOR, ToolEnv(journalAvailable = true, workerRoles = setOf(Role.ANALYST)), services)
        assertTrue(wired.names.containsAll(listOf("delegate", "tasks")), "${wired.names}")
        val none = ToolCatalog.toolSetFor(Role.DIRECTOR, ToolEnv(journalAvailable = true, workerRoles = emptySet()), services)
        assertFalse(none.names.any { it == "delegate" || it == "tasks" }, "${none.names}")
        val analyst = ToolCatalog.toolSetFor(Role.ANALYST, ToolEnv(journalAvailable = true, workerRoles = setOf(Role.ANALYST)), services)
        assertFalse("delegate" in analyst.names, "a worker never delegates")
    }

    @Test
    fun `the role enum of delegate lists only the wired roles`() {
        val schema = ToolSchema.of(DelegateArgs.serializer().descriptor, "delegate").toString()
        assertContains(schema, "\"enum\":[\"analyst\"]")
        assertContains(schema, "\"maxLength\":2000")
    }

    @Test
    fun `delegate wants a brief worth the name, tasks a task_id to cancel`() {
        val short = assertIs<ToolArgs.Decoded.Invalid>(ToolArgs.decode(DelegateArgs.serializer(), """{"role": "analyst", "brief": "Bartok?"}"""))
        assertEquals("brief is too short (at least 20 characters): say what to find out, about whom, over which period", short.problem)
        val scout = assertIs<ToolArgs.Decoded.Invalid>(ToolArgs.decode(DelegateArgs.serializer(), """{"role": "scout", "brief": "Find an arena near Bartok, flat and lit."}"""))
        assertContains(scout.problem, "role must be one of: analyst")
        val cancel = assertIs<ToolArgs.Decoded.Invalid>(ToolArgs.decode(TasksArgs.serializer(), """{"action": "cancel"}"""))
        assertEquals("task_id is required to cancel a task", cancel.problem)
        val ok = assertIs<ToolArgs.Decoded.Ok<TasksArgs>>(ToolArgs.decode(TasksArgs.serializer(), """{"action": "cancel", "task_id": 12}"""))
        assertEquals(TasksArgs(TasksAction.CANCEL, 12), ok.args)
    }

    @Test
    fun `tasks lists what runs, what waits and what ended, oldest first`() {
        val now = Instant.parse("2026-09-25T21:40:00Z")
        fun task(id: Long, status: Status, created: Long, started: Long?, finished: Long?, result: String? = null, error: String? = null) = TaskRecord(
            id, Role.ANALYST, "Where does Bartok mine these days? Coordinates and ores, over the last three days.", status,
            now.minusSeconds(created), started?.let { now.minusSeconds(it) }, finished?.let { now.minusSeconds(it) }, 41, null, result, error,
        )
        val tasks = listOf(
            task(10, Status.DONE, 600, 600, 559, result = "Réponse : Bartok mine vers -1400 -40 3100.\nFaits :\n- 72 % de ses minerais [R1]."),
            task(11, Status.FAILED, 300, 300, 297, error = "budget: the LLMGW budget is spent until 22:00"),
            task(12, Status.TIMEOUT, 400, 380, 200),
            task(13, Status.CANCELLED, 100, null, 90, error = "cancelled by the Director before it started"),
            task(14, Status.RUNNING, 12, 12, null),
            task(15, Status.QUEUED, 3, null, null),
        )

        assertEquals(
            """
            tasks: 1 running, 1 queued, 4 ended in the last 30 minutes
            T-10 analyst done 9m19s ago, in 41s: "Réponse : Bartok mine vers -1400 -40 3100. Faits : - 72 % de ses minerais [R1]."
            T-11 analyst failed 4m57s ago, in 3s: "budget: the LLMGW budget is spent until 22:00"
            T-12 analyst timed out 3m20s ago, in 3m
            T-13 analyst cancelled 1m30s ago
            T-14 analyst running for 12s: "Where does Bartok mine these days? Coordinates and ores, over the last three days."
            T-15 analyst queued for 3s: "Where does Bartok mine these days? Coordinates and ores, over the last three days."
            """.trimIndent(),
            TasksTool.render(tasks, now.toEpochMilli()),
        )
        assertEquals("no task running, queued or ended in the last 30 minutes", TasksTool.render(emptyList(), now.toEpochMilli()))
    }
}
