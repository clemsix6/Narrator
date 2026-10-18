package io.github.clemsix6.narrator2.memory

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.time.Instant
import kotlin.test.assertEquals

/** The memory's one JDBC plumbing (M3 review, finding 25), on an in-memory database. */
class JdbcTest {
    private val f = StoreFixture()

    @AfterEach
    fun close() = f.close()

    @Test
    fun `every statement runs within a time limit, the plumbing's unless its caller gives another`() {
        val timeouts = mutableListOf<Int>()
        val connection = recordingTimeouts(f.db.writer, timeouts)
        connection.query("SELECT 1") { getInt(1) }
        connection.query("SELECT 1", timeoutSeconds = 30) { getInt(1) }
        connection.update("DELETE FROM memory_state WHERE key = ?", "none")
        assertEquals(listOf(STATEMENT_TIMEOUT_SECONDS, 30, STATEMENT_TIMEOUT_SECONDS), timeouts)
    }

    @Test
    fun `an instant is bound as UTC, a list as a typed array, null as null, and read back as they were`() {
        val at = Instant.parse("2026-10-01T20:00:00Z")
        val row = f.db.writer.query("SELECT ?::TIMESTAMPTZ, ?::BIGINT[], ?::VARCHAR", at, longArray(listOf(3L, 1L)), null) {
            Triple(instant(1), longs(2), getString(3))
        }.single()
        assertEquals(Triple(at, listOf(3L, 1L), null), row)
    }

    /** [connection], whose prepared statements add the time limit they are given to [timeouts]. */
    private fun recordingTimeouts(connection: Connection, timeouts: MutableList<Int>): Connection = proxy(connection) { method, args ->
        val result = method.invoke(connection, *args)
        if (result !is PreparedStatement) return@proxy result
        proxy<PreparedStatement>(result) { call, callArgs ->
            if (call.name == "setQueryTimeout") timeouts += callArgs.single() as Int
            call.invoke(result, *callArgs)
        }
    }

    private inline fun <reified T : Any> proxy(target: T, crossinline handle: (java.lang.reflect.Method, Array<Any?>) -> Any?): T =
        Proxy.newProxyInstance(JdbcTest::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            try {
                handle(method, args ?: emptyArray())
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as T
}
