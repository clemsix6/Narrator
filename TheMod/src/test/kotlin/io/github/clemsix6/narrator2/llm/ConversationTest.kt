package io.github.clemsix6.narrator2.llm

import io.github.clemsix6.narrator2.testing.FakeAnthropic
import io.github.clemsix6.narrator2.testing.Hold
import io.github.clemsix6.narrator2.testing.client
import io.github.clemsix6.narrator2.testing.reply
import io.github.clemsix6.narrator2.testing.testConversation
import io.github.clemsix6.narrator2.trace.Transcripts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConversationTest {
    @TempDir
    lateinit var dir: Path

    private fun conversation(transcripts: Transcripts? = null) = Conversation(Role.DIRECTOR, "Test", system = emptyList(), transcripts = transcripts)

    private fun Conversation.texts(): List<String> = messages.map { it.content().asString() }

    @Test
    fun `messages is a snapshot that later appends never change`() {
        val conversation = conversation()
        conversation.appendUser("one")
        val snapshot = conversation.messages
        conversation.appendUser("two")

        assertEquals(1, snapshot.size)
        assertEquals(listOf("one", "two"), conversation.texts())
        assertEquals(2, conversation.size)
    }

    @Test
    fun `ids are unique and start with the role's id`() {
        val ids = List(100) { Conversation(Role.FORGER, "Test", system = emptyList()).id }
        assertEquals(100, ids.toSet().size)
        assertTrue(ids.all { it.startsWith("forger-") })
    }

    @Test
    fun `each message is transcribed as soon as it is appended`() {
        val conversation = conversation(Transcripts(dir, enabled = true))
        conversation.appendUser("first")
        conversation.appendUser("second")

        val file = Files.walk(dir).use { paths -> paths.filter { it.name == "${conversation.id}.jsonl" }.findFirst().orElseThrow() }
        val text = file.readText()
        assertContains(text, "\"first\"")
        assertContains(text, "\"second\"")
    }

    @Test
    fun `a conversation has a single writer while a call is in flight`() {
        FakeAnthropic().use { fake ->
            val hold = Hold()
            fake.enqueue(reply { hold(hold); text("Done.") })
            val conversation = testConversation()
            fake.client().use { client ->
                runBlocking {
                    val first = async(Dispatchers.Default) { client.send(conversation) }
                    assertTrue(withContext(Dispatchers.IO) { hold.awaitReached() })

                    val secondSend = assertFailsWith<IllegalStateException> { client.send(conversation) }
                    assertContains(secondSend.message.orEmpty(), "already in flight")
                    val append = assertFailsWith<IllegalStateException> { conversation.appendUser("Too early.") }
                    assertContains(append.message.orEmpty(), "in flight")

                    hold.release()
                    assertEquals("Done.", first.await().text)
                }
            }
            assertEquals(1, fake.requests.size, "the second send never reached the gateway")
            assertEquals(2, conversation.size)
            conversation.appendUser("Now it is my turn.")
            assertEquals(3, conversation.size)
        }
    }
}
