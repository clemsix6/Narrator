package io.github.clemsix6.narrator2.tool.speech

import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.guard.Verdict
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.tool.TurnCounter
import java.time.Clock
import java.util.UUID

/**
 * The anti-spam limits of the speech tools (M1_M2 §9.1), enforced whatever the model asks:
 * - `say`: [LINES_PER_TURN] lines per turn and [LINES_PER_MINUTE] per sliding minute, whoever speaks; each act
 *   of the mechanics engine (a rule's firing) is a turn of its own ([Caller.act], [TurnCounter]), and its lines
 *   count in the minute like the Director's. The engine's acts together say at most [ENGINE_LINES_PER_MINUTE]
 *   of them (M4 review, finding 33): a chatty rule never silences the Director, who keeps the rest;
 * - `show_title`: one title per player every [TITLE_COOLDOWN_MS];
 * - `actionbar`: one line per player every [ACTIONBAR_COOLDOWN_MS];
 * - `give_book`: one book per player every [BOOK_COOLDOWN_MS];
 * - `play_sound`: [SOUNDS_PER_MINUTE] sounds per sliding minute.
 *
 * What is allowed is counted at once: a tool asks just before it acts. A refusal counts nothing.
 * The server's own is `ToolLimits.speech`, so a new chapter (new tools) does not reset the counts. In
 * memory only: a restart starts afresh. Any thread.
 */
class SpeechLimiter(private val clock: Clock) {
    private val lines = Window(LINES_PER_MINUTE)
    private val engineLines = Window(ENGINE_LINES_PER_MINUTE)
    private val linesThisTurn = TurnCounter(LINES_PER_TURN)
    private val sounds = Window(SOUNDS_PER_MINUTE)
    private val titles = Cooldown(TITLE_COOLDOWN_MS)
    private val actionbars = Cooldown(ACTIONBAR_COOLDOWN_MS)
    private val books = Cooldown(BOOK_COOLDOWN_MS)

    /** One `say` line in [caller]'s turn (or act of the engine): allowed and counted, or refused with when to try again. */
    @Synchronized
    fun line(caller: Caller): Verdict {
        val now = clock.millis()
        if (linesThisTurn.full(caller)) {
            return Verdict.Denied(
                RULE,
                "$LINES_PER_TURN lines already this turn, the most a turn may say; " +
                    "put the rest in fewer, fuller lines next turn, or end the turn",
            )
        }
        val engine = isEngine(caller)
        val engineWait = if (engine) engineLines.waitMs(now) else 0
        if (engineWait > 0) {
            return Verdict.Denied(
                RULE,
                "the engine's acts already said $ENGINE_LINES_PER_MINUTE lines in the last minute, their share (the rest is the Director's); " +
                    "the next is possible in ${ToolText.wait(engineWait)}",
            )
        }
        val wait = lines.waitMs(now)
        if (wait > 0) {
            return Verdict.Denied(RULE, "$LINES_PER_MINUTE lines already in the last minute; the next is possible in ${ToolText.wait(wait)}")
        }
        lines.add(now)
        if (engine) engineLines.add(now)
        linesThisTurn.take(caller)
        return Verdict.Allowed
    }

    /** An act of the mechanics engine (a rule's firing, a challenge's stake), not a turn of the Director. */
    private fun isEngine(caller: Caller): Boolean = caller.turnId == null && caller.firingId != null

    /** One `play_sound` call: allowed and counted, or refused with when to try again. */
    @Synchronized
    fun sound(): Verdict {
        val now = clock.millis()
        val wait = sounds.waitMs(now)
        if (wait > 0) {
            return Verdict.Denied(RULE, "$SOUNDS_PER_MINUTE sounds already in the last minute; the next is possible in ${ToolText.wait(wait)}")
        }
        sounds.add(now)
        return Verdict.Allowed
    }

    /** Who among [players] may get a title now (counted), and who must wait. */
    @Synchronized
    fun titles(players: List<PlayerRef>): Split = titles.take(players, clock.millis())

    /** Who among [players] may get a line above the hotbar now (counted), and who must wait. */
    @Synchronized
    fun actionbars(players: List<PlayerRef>): Split = actionbars.take(players, clock.millis())

    /** Who among [players] may get a book now (counted), and who must wait. */
    @Synchronized
    fun books(players: List<PlayerRef>): Split = books.take(players, clock.millis())

    /**
     * The answer of a per-player limit.
     *
     * @property allowed the players who get it; their cooldown starts now.
     * @property waiting the players who must wait, in the order asked.
     */
    data class Split(val allowed: List<PlayerRef>, val waiting: List<Waiting>)

    /** A player whose cooldown runs: the last one was [agoMs] ago, the next is possible in [leftMs]. */
    data class Waiting(val player: PlayerRef, val agoMs: Long, val leftMs: Long) {
        /** e.g. `Bob got one 12s ago (next in 18s)`. */
        fun describe(): String = "${ModelText.name(player.name)} got one ${ToolText.duration(agoMs)} ago (next in ${ToolText.wait(leftMs)})"
    }

    /** At most [max] uses in any minute. */
    private class Window(private val max: Int) {
        private val uses = ArrayDeque<Long>()

        /** 0 when one more use fits now, else how long until it does. */
        fun waitMs(now: Long): Long {
            while (uses.isNotEmpty() && now - uses.first() >= MINUTE_MS) uses.removeFirst()
            return if (uses.size < max) 0 else uses.first() + MINUTE_MS - now
        }

        fun add(now: Long) = uses.addLast(now)
    }

    /** At most one use per player every [ms]. */
    private class Cooldown(private val ms: Long) {
        private val last = HashMap<UUID, Long>()

        fun take(players: List<PlayerRef>, now: Long): Split {
            last.values.removeIf { now - it >= ms }
            val (waiting, allowed) = players.distinctBy { it.uuid }.partition { it.uuid in last }
            allowed.forEach { last[it.uuid] = now }
            return Split(allowed, waiting.map { player -> last.getValue(player.uuid).let { Waiting(player, now - it, it + ms - now) } })
        }
    }

    companion object {
        /** The rule in `DENIED (speech_limit): …`. */
        const val RULE = "speech_limit"

        const val LINES_PER_TURN = 4
        const val LINES_PER_MINUTE = 8

        /** The engine's share of [LINES_PER_MINUTE]: half, the other half kept for the Director. */
        const val ENGINE_LINES_PER_MINUTE = LINES_PER_MINUTE / 2
        const val SOUNDS_PER_MINUTE = 10
        const val TITLE_COOLDOWN_MS = 30_000L
        const val ACTIONBAR_COOLDOWN_MS = 5_000L
        const val BOOK_COOLDOWN_MS = 5 * 60_000L
        private const val MINUTE_MS = 60_000L
    }
}
