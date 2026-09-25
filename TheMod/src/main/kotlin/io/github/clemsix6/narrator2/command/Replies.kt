package io.github.clemsix6.narrator2.command

import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.llm.describeFailure
import io.github.clemsix6.narrator2.runtime.Phase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component

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
        runtime == null -> source.sendFailure(errorLine("the Narrator is not running, see the server log."))
        runtime.phase != Phase.RUNNING -> source.sendFailure(errorLine("the server is stopping."))
        else -> return runtime
    }
    return null
}

/**
 * Runs [work] in the runtime's scope, off the server thread (network, database, files), and sends the
 * lines it returns to [source] on the server thread, and to the other operators too when [broadcast].
 * A failure becomes a red `<what> failed: …` line and an error in the log; a cancellation (the server
 * stops) sends nothing.
 */
fun replyLater(
    source: CommandSourceStack,
    runtime: NarratorRuntime,
    what: String,
    broadcast: Boolean = false,
    work: suspend () -> List<Component>,
): Job =
    runtime.scope.launch(Dispatchers.IO) {
        val lines = try {
            work()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.command.error("$what failed", e)
            listOf(errorLine("$what failed: ${describeFailure(e)}"))
        }
        runtime.server.execute { lines.forEach { line -> source.sendSuccess({ line }, broadcast) } }
    }
