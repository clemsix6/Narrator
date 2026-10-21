package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.agent.TurnLimits
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.memory.chronicle.ChroniclerFixture
import io.github.clemsix6.narrator2.memory.chronicle.ChroniclerFixture.Companion.at
import io.github.clemsix6.narrator2.testing.Hold
import io.github.clemsix6.narrator2.testing.reply
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The conversations of the memory jobs (M3 §7.6): how they end when they run too long or the server stops; the
 * storyteller's among them (M6 §6.3).
 */
class MemoryAgentsTest {
    private val f = ChroniclerFixture()
    private val hold = Hold()
    private val context = RunContext(7, RunKind.BATCH, at("20:00"), at("20:10"), "director-c13")

    @AfterEach
    fun close() {
        hold.release()
        f.close()
    }

    private fun endReasons() = f.rows("SELECT end_reason FROM conversations WHERE role = 'chronicler'") { getString(1) }

    @Test
    fun `a conversation that runs past its timeout fails, recorded as timed out`() = runBlocking<Unit> {
        f.fake.enqueue(reply { hold(hold) })

        val run = f.agents.run(Role.CHRONICLER, context, "Test", "<job kind=\"batch\"/>", TurnLimits(2), Duration.ofSeconds(1))

        assertEquals(RunStatus.FAILED, run.status)
        assertEquals("no end within 1s", run.error)
        assertEquals(listOf("timeout"), endReasons())
        assertNull(f.agents.runContext(run.conversationId))
    }

    @Test
    fun `a conversation whose job is cancelled is recorded as cancelled, and the cancellation goes on`() = runBlocking<Unit> {
        f.fake.enqueue(reply { hold(hold) })
        val job = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
            f.agents.run(Role.CHRONICLER, context, "Test", "<job kind=\"batch\"/>", TurnLimits(2), Duration.ofMinutes(1))
        }
        assertTrue(hold.awaitReached())
        val id = f.fake.requests.single().conversation!!
        assertEquals(context.copy(role = Role.CHRONICLER), f.agents.runContext(id))

        job.cancel()
        assertFailsWith<CancellationException> { job.await() }

        assertEquals(listOf("cancelled"), endReasons())
        assertNull(f.agents.runContext(id))
    }

    /**
     * M6 §6.3, §12.2: the storyteller's step of a dream is a memory conversation too. Its run carries its role, so that
     * what it writes is the storyteller's, not the Dreamer's; its calls are the programme's; it sees `inspire`.
     */
    @Test
    fun `the storyteller's conversation writes as the storyteller, calls for the programme, and sees inspire`() = runBlocking<Unit> {
        val dream = RunContext(9, RunKind.DREAM, null, null, null)
        f.fake.enqueue(
            reply {
                hold(hold)
                text("Cinq idées écrites.")
            },
        )
        val job = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) {
            f.agents.run(Role.STORYTELLER, dream, "Programme", "<job kind=\"dream\" step=\"5\" task=\"programme\"/>", TurnLimits(2), Duration.ofMinutes(1))
        }
        assertTrue(hold.awaitReached())
        val request = f.fake.requests.single()
        val id = request.conversation!!
        assertTrue(id.startsWith("storyteller-"), id)
        val context = f.agents.runContext(id)!!
        assertEquals(Role.STORYTELLER, context.role)
        assertEquals(Author(Source.STORYTELLER, runId = 9, conversationId = id), context.author(id))
        assertEquals(Source.DREAMER, dream.copy(role = Role.DREAMER).author("dreamer-1").source, "the dream's other steps stay the Dreamer's")
        assertEquals(
            listOf("drop_idea", "inspire", "player_profile", "propose_idea", "recall", "remember", "resolve", "sql"),
            request.json.path("tools").map { it.path("name").asText() },
        )

        hold.release()
        assertEquals(RunStatus.DONE, job.await().status)
        assertEquals(listOf("programme"), f.rows("SELECT purpose FROM llm_calls WHERE role = 'storyteller'") { getString(1) })
        assertEquals(listOf("done"), f.rows("SELECT end_reason FROM conversations WHERE role = 'storyteller'") { getString(1) })
    }

    @Test
    fun `only the memory's roles run here`() = runBlocking<Unit> {
        val error = assertFailsWith<IllegalArgumentException> {
            f.agents.run(Role.DIRECTOR, context, "Test", "…", TurnLimits(2), Duration.ofSeconds(1))
        }
        assertEquals("director is not a memory role", error.message)
        assertEquals(0, f.fake.requests.size)
    }
}
