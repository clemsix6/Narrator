package io.github.clemsix6.narrator2.tool

import io.github.clemsix6.narrator2.tool.action.GiftLimiter
import io.github.clemsix6.narrator2.tool.action.WorldLimits
import io.github.clemsix6.narrator2.tool.command.RunCommandTool
import io.github.clemsix6.narrator2.tool.speech.SpeechLimiter
import java.time.Clock

/**
 * The limits of the tools that hold whatever the conversation (M2 review, finding 16): one set per
 * server, `NarratorServices.toolLimits`, so that a new chapter, which builds new tools, starts none of
 * them afresh. In memory only: a restart does. Any thread.
 */
class ToolLimits(clock: Clock) {
    /** `say`, `show_title`, `actionbar`, `play_sound`, `give_book`. */
    val speech = SpeechLimiter(clock)

    /** `give_item`: gifts and precious items per player and per hour. */
    val gifts = GiftLimiter(clock)

    /** `set_weather` and `set_time`. */
    val world = WorldLimits(clock)

    /** `run_command`: commands per turn. */
    val commands = TurnCounter(RunCommandTool.COMMANDS_PER_TURN)
}

/**
 * At most [max] uses in one turn: the turn is the caller's conversation and turn ([Caller.conversationId],
 * [Caller.turnId]); the counts of the last [KEPT] turns are kept, so that two conversations that use the
 * same tool in turn never reset each other's. Any thread.
 */
class TurnCounter(val max: Int) {
    private val used = object : LinkedHashMap<Pair<String, Long?>, Int>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, Long?>, Int>) = size > KEPT
    }

    /** Whether [max] uses were already made in [caller]'s turn. */
    @Synchronized
    fun full(caller: Caller): Boolean = (used[caller.turn] ?: 0) >= max

    /** One more use in [caller]'s turn: true, and counted, when it fits; false when [max] were already used. */
    @Synchronized
    fun take(caller: Caller): Boolean {
        if (full(caller)) return false
        used[caller.turn] = (used[caller.turn] ?: 0) + 1
        return true
    }

    private val Caller.turn: Pair<String, Long?> get() = conversationId to turnId

    private companion object {
        const val KEPT = 16
    }
}
