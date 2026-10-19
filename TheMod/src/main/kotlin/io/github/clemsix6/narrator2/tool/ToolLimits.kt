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
 * At most [max] uses in one turn. The turn is the caller's conversation and act ([Caller.conversationId],
 * [Caller.act]): a Director turn, or one act of the mechanics engine (a rule's firing, a challenge's stakes),
 * which has its own count (M4 review, finding 10: with the turn alone, every firing of a rule would share one
 * count, and the rule would fall silent for good after four lines). A worker's task has no act: its count
 * is its conversation's.
 *
 * The counts of the [KEPT] turns used last are kept, so that two conversations, or a turn and the engine's
 * acts, that use the same tool in turn never reset each other's. Any thread.
 */
class TurnCounter(val max: Int) {
    // Access order: a turn that keeps using the tool keeps its count, however many acts come in between.
    private val used = object : LinkedHashMap<Pair<String, Long?>, Int>(KEPT, LOAD_FACTOR, true) {
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

    private val Caller.turn: Pair<String, Long?> get() = conversationId to act

    private companion object {
        /** Turns and acts whose count is kept: the engine may run dozens of acts while a Director turn lasts. */
        const val KEPT = 64
        const val LOAD_FACTOR = 0.75f
    }
}
