package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.core.onServerThread
import io.github.clemsix6.narrator2.testing.FakeAnthropic
import io.github.clemsix6.narrator2.testing.reply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
import java.util.concurrent.atomic.AtomicReference

/** Tick budget of the tests that use [awaitAsync]: at least 10 s of wall-clock time. */
private const val ASYNC_MAX_TICKS = 10_000

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
 * In-game tests, run headless by `gradlew runGameTest`. Tests of one batch run side by side, so only
 * [pingGoesThroughTheGateway] reloads the Narrator, and the other tests never depend on its config.
 */
class NarratorGameTests {

    @GameTest
    fun runtimeStartsWithTheServer(helper: GameTestHelper) {
        val runtime = NarratorRuntime.current
        helper.assertTrue(runtime != null, "the Narrator did not start with the server")
        helper.assertTrue(runtime!!.server === helper.level.server, "the Narrator runs on another server")
        helper.assertTrue(Files.exists(NarratorRuntime.configFile), "no ${NarratorRuntime.configFile}")
        helper.assertTrue(Files.exists(NarratorRuntime.configDir.resolve("prompts/director.md")), "prompts not copied")
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
     * The whole `/narrator` chain against the fake Anthropic server: point the config at it, `reload`,
     * check `status`, then `ping` and wait for Claude's answer to come back to the sender.
     */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun pingGoesThroughTheGateway(helper: GameTestHelper) {
        val fake = FakeAnthropic()
        fake.enqueue(reply { thinking("An operator is checking the line."); text("Pong. I hear you, alas."); usage(input = 80, output = 12) })
        val key = "gametest-key"
        Files.writeString(
            NarratorRuntime.configFile,
            """{ "gateway": { "baseUrl": "${fake.baseUrl}", "apiKey": "$key", "maxRetries": 0 } }""",
        )

        val output = Output()
        val source = helper.level.server.createCommandSourceStack().withSource(output)
        val commands = helper.level.server.commands
        commands.performPrefixedCommand(source, "narrator reload")
        helper.assertTrue(output.lines.any { "reloaded (key present)" in it }, "reload: ${output.lines}")

        output.lines.clear()
        commands.performPrefixedCommand(source, "narrator status")
        helper.assertTrue(output.lines.any { fake.baseUrl in it && "key: present" in it }, "status: ${output.lines}")
        helper.assertTrue(output.lines.none { key in it }, "status must never show the key")

        output.lines.clear()
        commands.performPrefixedCommand(source, "narrator ping Are you there?")
        helper.assertTrue(output.lines.any { "ping sent to Claude" in it }, "ping: ${output.lines}")
        helper.awaitAsync {
            helper.assertTrue(output.lines.any { "Pong. I hear you, alas." in it }, "ping answer: ${output.lines}")
            helper.assertTrue(output.lines.any { "input 80 (+0 cache read, 0 cache write) · output 12" in it }, "ping stats: ${output.lines}")
            val request = fake.requests.single()
            helper.assertValueEqual("Are you there?", request.json.path("messages").path(0).path("content").asText(), "ping message")
            helper.assertValueEqual(key, request.header("X-Api-Key").orEmpty(), "API key header")
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
