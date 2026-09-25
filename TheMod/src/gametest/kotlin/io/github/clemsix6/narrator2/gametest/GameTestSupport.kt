package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.NarratorRuntime
import net.minecraft.commands.CommandSource
import net.minecraft.gametest.framework.GameTestAssertException
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.gametest.framework.GameTestSequence
import net.minecraft.network.chat.Component
import java.util.concurrent.CopyOnWriteArrayList

/*
 * Helpers shared by every in-game test class. Each task has its own test class, listed in
 * `src/gametest/resources/fabric.mod.json`; tests of one batch run side by side, so a test that listens
 * to the bus subscribes with its own owner and filters on its own mock player, and only
 * `NarratorGameTests.reloadKeepsTheServicesAndSwapsTheSettings` replaces the settings.
 */

/** Tick budget of the tests that use [awaitAsync] or [thenAwait]: at least 10 s of wall-clock time. */
const val ASYNC_MAX_TICKS = 10_000

/** The fake LLMGW key of the in-game tests, set in the environment of the `gameTest` run (build.gradle.kts). */
const val KEY_ENV = "NARRATOR2_GAMETEST_KEY"

/** The running Narrator of this test's server; fails the test when there is none. */
fun GameTestHelper.narrator(): NarratorRuntime {
    val runtime = NarratorRuntime.current
    assertTrue(runtime != null && runtime.server === level.server, "the Narrator does not run on this server")
    return runtime!!
}

/** Like `assertValueEqual`, for values that may be null (which `assertValueEqual` does not accept from Kotlin). */
fun GameTestHelper.assertEquals(expected: Any?, actual: Any?, what: String) =
    assertTrue(expected == actual, "$what: expected $expected, got $actual")

/**
 * Succeeds once [assertions] pass, waiting on work done by other threads (network, coroutines).
 *
 * The GameTest server runs its ticks back to back, thousands per second, so a tick budget alone
 * cannot bound such a wait. Each failed check therefore sleeps 1 ms, which turns the test's
 * `maxTicks` into at least as many milliseconds. A passing test still ends as soon as it succeeds.
 */
fun GameTestHelper.awaitAsync(assertions: () -> Unit) = succeedWhen(slowRetries(assertions))

/** Like [awaitAsync], as a step of a sequence: the next step runs once [assertions] pass. */
fun GameTestSequence.thenAwait(assertions: () -> Unit): GameTestSequence = thenWaitUntil(slowRetries(assertions))

private fun slowRetries(assertions: () -> Unit) = Runnable {
    try {
        assertions()
    } catch (e: GameTestAssertException) {
        Thread.sleep(1)
        throw e
    }
}

/**
 * Closes [resource] when the test ends, however it ends: right away if the synchronous part of the
 * test throws, else at the last tick before the timeout (a test that succeeds closes it itself).
 */
fun <T : AutoCloseable, R> GameTestHelper.closingOnFailure(resource: T, maxTicks: Int, block: (T) -> R): R {
    runAfterDelay((maxTicks - 1).toLong()) { resource.close() }
    return try {
        block(resource)
    } catch (e: Throwable) {
        resource.close()
        throw e
    }
}

/** A command source that records what commands send back, to run `/narrator …` in a test. */
class CommandOutput : CommandSource {
    val lines = CopyOnWriteArrayList<String>()

    override fun sendSystemMessage(message: Component) {
        lines += message.string
    }

    override fun acceptsSuccess() = true

    override fun acceptsFailure() = true

    override fun shouldInformAdmins() = false
}
