package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.config.Prompts
import io.github.clemsix6.narrator2.core.ServerThreadTimeoutException
import io.github.clemsix6.narrator2.core.onServerThread
import io.github.clemsix6.narrator2.testing.FakeAnthropic
import io.github.clemsix6.narrator2.testing.Hold
import io.github.clemsix6.narrator2.testing.LogCapture
import io.github.clemsix6.narrator2.testing.reply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.commands.CommandSource
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestAssertException
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.chat.Component
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Tick budget of the tests that use [awaitAsync]: at least 10 s of wall-clock time. */
private const val ASYNC_MAX_TICKS = 10_000

/** The fake key of the in-game tests, set in the environment of the `gameTest` run (build.gradle.kts). */
private const val KEY_ENV = "NARRATOR2_GAMETEST_KEY"

/**
 * Succeeds once [assertions] pass, waiting on work done by other threads (network, coroutines).
 *
 * The GameTest server runs its ticks back to back, thousands per second, so a tick budget alone
 * cannot bound such a wait. Each failed check therefore sleeps 1 ms, which turns the test's
 * `maxTicks` into at least as many milliseconds. A passing test still ends as soon as it succeeds.
 */
private fun GameTestHelper.awaitAsync(assertions: () -> Unit) = succeedWhen {
    try {
        assertions()
    } catch (e: GameTestAssertException) {
        Thread.sleep(1)
        throw e
    }
}

/**
 * Closes [resource] when the test ends, however it ends: right away if the synchronous part of the
 * test throws, else on success (see [awaitAsync] callers) or at the last tick before the timeout.
 */
private fun <T : AutoCloseable, R> GameTestHelper.closingOnFailure(resource: T, maxTicks: Int, block: (T) -> R): R {
    runAfterDelay((maxTicks - 1).toLong()) { resource.close() }
    return try {
        block(resource)
    } catch (e: Throwable) {
        resource.close()
        throw e
    }
}

/**
 * In-game tests, run headless by `gradlew runGameTest`. Tests of one batch run side by side, so only
 * [pingGoesThroughTheGateway] replaces the Narrator, and the other tests never depend on its config.
 */
class NarratorGameTests {

    @GameTest
    fun runtimeStartsWithTheServer(helper: GameTestHelper) {
        val runtime = NarratorRuntime.current
        helper.assertTrue(runtime != null, "the Narrator did not start with the server")
        helper.assertTrue(runtime!!.server === helper.level.server, "the Narrator runs on another server")
        helper.assertTrue(Files.exists(NarratorRuntime.configFile), "no ${NarratorRuntime.configFile}")
        helper.assertTrue(Files.exists(NarratorRuntime.promptsDir.resolve(Prompts.README)), "no README for prompt overrides")
        helper.assertTrue(
            Prompts.NAMES.none { Files.exists(NarratorRuntime.promptsDir.resolve("$it.md")) },
            "prompts must not be copied out of the jar",
        )
        helper.succeed()
    }

    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun blockPlacedIsReadBackFromAnotherThread(helper: GameTestHelper) {
        val pos = BlockPos(1, 1, 1)
        helper.setBlock(pos, Blocks.DIAMOND_BLOCK)
        helper.assertBlockPresent(Blocks.DIAMOND_BLOCK, pos)

        val seen = AtomicReference<Block>()
        val server = helper.level.server
        CoroutineScope(Dispatchers.Default).launch {
            seen.set(server.onServerThread { helper.getBlockState(pos).block })
        }
        helper.awaitAsync {
            helper.assertTrue(seen.get() == Blocks.DIAMOND_BLOCK, "block read through onServerThread: ${seen.get()}")
        }
    }

    /**
     * While the server thread is busy (here, blocked by the test itself), `onServerThread` times out
     * with an ordinary exception, and the block is skipped when the server thread gets to it.
     */
    @GameTest
    fun serverThreadTimeoutIsAnOrdinaryFailure(helper: GameTestHelper) {
        val server = helper.level.server
        val failure = AtomicReference<Throwable>()
        val ran = AtomicBoolean(false)
        val job = CoroutineScope(Dispatchers.Default).launch {
            try {
                server.onServerThread(timeoutMs = 100) { ran.set(true) }
            } catch (e: Throwable) {
                failure.set(e)
            }
        }
        runBlocking { withTimeout(5_000) { job.join() } }
        helper.assertTrue(failure.get() is ServerThreadTimeoutException, "expected a ServerThreadTimeoutException, got ${failure.get()}")
        helper.runAfterDelay(5) {
            helper.assertFalse(ran.get(), "the block ran after its timeout")
            helper.succeed()
        }
    }

    /**
     * The whole `/narrator` chain against the fake Anthropic server: stop the Narrator, point the
     * config at the fake, `reload` (which starts it), check `status`, then `ping`, check that a second
     * ping is refused while the first waits, and wait for Claude's answer to come back to the sender.
     * The key comes from the environment and must never show up in a command output or a log line.
     */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun pingGoesThroughTheGateway(helper: GameTestHelper) {
        val logs = LogCapture()
        helper.closingOnFailure(FakeAnthropic(), ASYNC_MAX_TICKS) { fake ->
            helper.closingOnFailure(logs, ASYNC_MAX_TICKS) { runPing(helper, fake, logs) }
        }
    }

    private fun runPing(helper: GameTestHelper, fake: FakeAnthropic, logs: LogCapture) {
        val key = System.getenv(KEY_ENV)
        helper.assertTrue(!key.isNullOrEmpty(), "$KEY_ENV is not set by the gameTest run")
        val hold = Hold()
        fake.enqueue(reply { hold(hold); thinking("An operator is checking the line."); text("Pong. I hear you, alas."); usage(input = 80, output = 12) })
        Files.writeString(
            NarratorRuntime.configFile,
            """{ "gateway": { "baseUrl": "${fake.baseUrl}", "apiKeyEnv": "$KEY_ENV", "maxRetries": 0 } }""",
        )

        val output = Output()
        val source = helper.level.server.createCommandSourceStack().withSource(output)
        val commands = helper.level.server.commands
        NarratorRuntime.stop()
        commands.performPrefixedCommand(source, "narrator reload")
        helper.assertTrue(output.lines.any { "the Narrator started (key present)" in it }, "reload: ${output.lines}")

        output.lines.clear()
        commands.performPrefixedCommand(source, "narrator status")
        helper.assertTrue(output.lines.any { fake.baseUrl in it && "cache marker off" in it }, "status: ${output.lines}")
        helper.assertTrue(output.lines.any { "Key: present (environment variable $KEY_ENV)" in it }, "status: ${output.lines}")
        helper.assertTrue(output.lines.any { "director: claude-opus-5-5 · effort medium" in it }, "status: ${output.lines}")

        output.lines.clear()
        commands.performPrefixedCommand(source, "narrator ping Are you there?")
        helper.assertTrue(output.lines.any { "ping sent to Claude" in it }, "ping: ${output.lines}")
        helper.assertTrue(hold.awaitReached(), "the ping reached the gateway")
        commands.performPrefixedCommand(source, "narrator ping Again?")
        helper.assertTrue(output.lines.any { "a ping is already waiting for Claude" in it }, "second ping: ${output.lines}")
        hold.release()

        helper.awaitAsync {
            helper.assertTrue(output.lines.any { "Pong. I hear you, alas." in it }, "ping answer: ${output.lines}")
            helper.assertTrue(output.lines.any { "input 80 (+0 cache read, 0 cache write) · output 12" in it }, "ping stats: ${output.lines}")
            val request = fake.requests.single()
            helper.assertValueEqual("Are you there?", request.json.path("messages").path(0).path("content").asText(), "ping message")
            helper.assertValueEqual(key, request.header("X-Api-Key").orEmpty(), "API key header")
            helper.assertTrue(output.lines.none { key in it }, "a command output shows the key")
            helper.assertTrue(key !in logs.text, "a log line shows the key")
            logs.close()
            fake.close()
        }
    }

    /** A command source that records what commands send back. */
    private class Output : CommandSource {
        val lines = CopyOnWriteArrayList<String>()

        override fun sendSystemMessage(message: Component) {
            lines += message.string
        }

        override fun acceptsSuccess() = true

        override fun acceptsFailure() = true

        override fun shouldInformAdmins() = false
    }
}
