package io.github.clemsix6.narrator2.runtime

import io.github.clemsix6.narrator2.llm.ClaudeClient
import io.github.clemsix6.narrator2.llm.ClientClosedException
import io.github.clemsix6.narrator2.llm.SendResult
import io.github.clemsix6.narrator2.testing.FakeAnthropic
import io.github.clemsix6.narrator2.testing.Hold
import io.github.clemsix6.narrator2.testing.client
import io.github.clemsix6.narrator2.testing.reply
import io.github.clemsix6.narrator2.testing.testConversation
import io.github.clemsix6.narrator2.testing.testSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** `/narrator reload` swaps the settings at once; a replaced client finishes its calls, then closes. */
class SettingsHolderTest {
    private val fake = FakeAnthropic()
    private val clients = mutableListOf<ClaudeClient>()

    @AfterEach
    fun cleanUp() {
        clients.forEach { it.close() }
        fake.close()
    }

    private fun newClient(): ClaudeClient = fake.client().also { clients += it }

    @Test
    fun `swap installs the new settings at once and returns the ones it replaced`() {
        val first = testSettings(claude = newClient())
        val second = testSettings(claude = newClient())
        val holder = SettingsHolder(first)

        assertSame(first, holder.swap(second))
        assertSame(second, holder.get())
        assertFalse(second.claude!!.isClosed, "the new client stays open")
    }

    @Test
    fun `a replaced client without calls in flight closes at once`() {
        val old = newClient()
        val holder = SettingsHolder(testSettings(claude = old))

        holder.swap(testSettings(claude = newClient()))

        assertTrue(old.isClosed)
        assertFailsWith<ClientClosedException> { runBlocking { old.send(testConversation()) } }
        assertTrue(fake.requests.isEmpty(), "a closed client sends nothing")
    }

    @Test
    fun `a replaced client finishes its call in flight, then closes`() {
        val hold = Hold()
        fake.enqueue(reply { hold(hold); text("Still here.") })
        val old = newClient()
        val holder = SettingsHolder(testSettings(claude = old))
        val call = CoroutineScope(Dispatchers.Default).async { old.send(testConversation()) }
        assertTrue(hold.awaitReached(), "the call reached the gateway")

        holder.swap(testSettings(claude = newClient()))
        assertFalse(old.isClosed, "the call in flight keeps its client")
        assertEquals(1, old.inFlight)

        hold.release()
        val result = runBlocking { withTimeout(10_000) { call.await() } }
        assertIs<SendResult.Reply>(result)
        assertEquals("Still here.", result.text)
        assertTrue(old.isClosed, "closed once its call was over")
        assertFalse(hold.clientGone, "the reply was read to the end")
    }

    @Test
    fun `settings without a client can be swapped`() {
        val holder = SettingsHolder(testSettings())
        val next = testSettings()
        holder.swap(next)
        assertSame(next, holder.get())
    }

    @Test
    fun `concurrent swaps close every replaced client exactly once and keep the last one open`() {
        val initial = testSettings(claude = newClient())
        val holder = SettingsHolder(initial)
        val candidates = List(64) { testSettings(claude = newClient()) }
        val replaced = Collections.synchronizedList(mutableListOf<Settings>())
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        candidates.forEach { next ->
            pool.execute {
                start.await()
                replaced += holder.swap(next)
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))

        val current = holder.get()
        val all = listOf(initial) + candidates
        val seen = Collections.newSetFromMap(IdentityHashMap<Settings, Boolean>()).apply { addAll(replaced) }
        assertEquals(candidates.size, replaced.size)
        assertEquals(candidates.size, seen.size, "each settings was replaced once")
        assertEquals(all.toSet() - setOf(current), seen, "every settings but the last was replaced")
        all.forEach { settings -> assertEquals(settings !== current, settings.claude!!.isClosed) }
    }
}
