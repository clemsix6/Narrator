package io.github.clemsix6.narrator2.llm

import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ConversationTest {
    private fun conversation() = Conversation("director", "Test", system = emptyList())

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
    fun `ids are unique and start with the role`() {
        val ids = List(100) { Conversation("forgeron", "Test", system = emptyList()).id }
        assertEquals(100, ids.toSet().size)
        assertTrue(ids.all { it.startsWith("forgeron-") })
        assertNotEquals(ids[0], ids[1])
    }

    @Test
    fun `concurrent appends are all kept, each writer's in order`() {
        val conversation = conversation()
        val writers = 8
        val perWriter = 500
        val pool = Executors.newFixedThreadPool(writers)
        val start = CountDownLatch(1)
        val tasks = (0 until writers).map { writer ->
            pool.submit {
                start.await()
                repeat(perWriter) { i -> conversation.appendUser("$writer-$i") }
            }
        }
        start.countDown()
        tasks.forEach { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()

        val texts = conversation.texts()
        assertEquals(writers * perWriter, texts.size)
        assertEquals(writers * perWriter, texts.toSet().size)
        (0 until writers).forEach { writer ->
            assertEquals((0 until perWriter).map { "$writer-$it" }, texts.filter { it.startsWith("$writer-") })
        }
    }

    @Test
    fun `reading while another thread appends always sees a consistent prefix`() {
        val conversation = conversation()
        val total = 5_000
        val writer = Thread { repeat(total) { conversation.appendUser("m$it") } }
        writer.start()
        var lastSize = 0
        while (writer.isAlive || lastSize < total) {
            val texts = conversation.texts()
            assertTrue(texts.size >= lastSize, "the history never shrinks")
            assertEquals(List(texts.size) { "m$it" }, texts, "a snapshot is always a prefix of the history")
            lastSize = texts.size
        }
        writer.join()
        assertEquals(total, conversation.size)
    }
}
