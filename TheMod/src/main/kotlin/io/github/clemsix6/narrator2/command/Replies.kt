package io.github.clemsix6.narrator2.command

import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ServerStoppedException
import io.github.clemsix6.narrator2.core.ServerThreadTimeoutException
import io.github.clemsix6.narrator2.core.onServerThread
import io.github.clemsix6.narrator2.llm.describeFailure
import io.github.clemsix6.narrator2.runtime.Phase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.util.thread.BlockableEventLoop

/*
 * What every `/narrator …` module shares: the look of the replies, the check that the Narrator runs,
 * and work done off the server thread whose answer comes back to the sender.
 */

/** `[Narrator] text`, with a gold prefix. */
fun narratorLine(text: String, color: ChatFormatting = ChatFormatting.WHITE): Component =
    Component.empty()
        .append(Component.literal("[Narrator] ").withStyle(ChatFormatting.GOLD))
        .append(Component.literal(text).withStyle(color))

/** A gray detail line under a [narratorLine]. */
fun detailLine(text: String): Component = Component.literal(text).withStyle(ChatFormatting.GRAY)

/** A red `[Narrator] text` line. */
fun errorLine(text: String): Component = narratorLine(text, ChatFormatting.RED)

/**
 * The Narrator of [source]'s server when it runs; otherwise tells [source] why not and returns null.
 * Command bodies start with `val runtime = runningNarrator(source) ?: return 0`.
 */
fun runningNarrator(source: CommandSourceStack): NarratorRuntime? {
    val runtime = NarratorRuntime.current
    when {
        runtime == null -> source.sendFailure(errorLine(notRunning()))
        runtime.phase != Phase.RUNNING -> source.sendFailure(errorLine("the server is stopping."))
        else -> return runtime
    }
    return null
}

/** Why no Narrator runs: a restart rebuilds it (M6 §9), or it could not start. */
fun notRunning(): String =
    if (NarratorRuntime.restarts.running) "a restart is running, try again once it is done." else "the Narrator is not running, see the server log."

/**
 * Runs [work] in the runtime's scope, off the server thread (network, database, files), and sends the
 * lines it returns to [source] on the server thread, and to the other operators too when [broadcast].
 * A failure becomes a red `<what> failed: …` line and an error in the log; a cancellation (the server
 * stops) sends nothing, and neither does a server that has stopped meanwhile ([replyOn]).
 */
fun replyLater(
    source: CommandSourceStack,
    runtime: NarratorRuntime,
    what: String,
    broadcast: Boolean = false,
    work: suspend () -> List<Component>,
): Job = replyOn(runtime.server, runtime.scope, what, { line -> source.sendSuccess({ line }, broadcast) }, work)

/**
 * [replyLater] on [loop] (the server's thread) in [scope]: [work] runs off the server thread, then each
 * line it returns goes to [send] on the server thread, through [onServerThread]. Never from another
 * thread: once the server has stopped, Minecraft would run a task on the caller's thread, and the lines
 * are dropped instead (M2 review, finding 17); so they are when the server thread does not get to them
 * within [REPLY_TIMEOUT_MS].
 */
internal fun replyOn(
    loop: BlockableEventLoop<*>,
    scope: CoroutineScope,
    what: String,
    send: (Component) -> Unit,
    work: suspend () -> List<Component>,
): Job =
    scope.launch(Dispatchers.IO) {
        val lines = try {
            work()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.command.error("$what failed", e)
            listOf(errorLine("$what failed: ${describeFailure(e)}"))
        }
        try {
            loop.onServerThread(REPLY_TIMEOUT_MS) { lines.forEach(send) }
        } catch (e: ServerStoppedException) {
            Log.command.debug { "$what: the answer was not sent: ${e.message}" }
        } catch (e: ServerThreadTimeoutException) {
            Log.command.warn("$what: the answer was not sent: ${e.message}")
        }
    }

/** An answer the server thread does not send within this delay is dropped. */
private const val REPLY_TIMEOUT_MS = 10_000L
