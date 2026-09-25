package io.github.clemsix6.narrator2.tool.speech

import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.guard.Verdict
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Caller
import java.time.Clock
import java.util.UUID
import java.util.WeakHashMap

/**
 * The anti-spam limits of the speech tools (M1_M2 §9.1), enforced whatever the model asks:
 * - `say`: [LINES_PER_TURN] lines per turn and [LINES_PER_MINUTE] per sliding minute;
 * - `show_title`: one title per player every [TITLE_COOLDOWN_MS];
 * - `actionbar`: one line per player every [ACTIONBAR_COOLDOWN_MS];
 * - `give_book`: one book per player every [BOOK_COOLDOWN_MS];
 * - `play_sound`: [SOUNDS_PER_MINUTE] sounds per sliding minute.
 *
 * What is allowed is counted at once: a tool asks just before it acts. A refusal counts nothing.
 * One limiter per server ([of]), so a new chapter (new tools) does not reset the counts. In memory
 * only: a restart starts afresh. Any thread.
 */
class SpeechLimiter(private val clock: Clock) {
    private val lines = Window(LINES_PER_MINUTE)
    private var turn: Pair<String, Long?>? = null
    private var linesThisTurn = 0
    private val sounds = Window(SOUNDS_PER_MINUTE)
    private val titles = Cooldown(TITLE_COOLDOWN_MS)
    private val actionbars = Cooldown(ACTIONBAR_COOLDOWN_MS)
    private val books = Cooldown(BOOK_COOLDOWN_MS)

    /** One `say` line in [caller]'s turn: allowed and counted, or refused with when to try again. */
    @Synchronized
    fun line(caller: Caller): Verdict {
        val now = clock.millis()
        val key = caller.conversationId to caller.turnId
        if (key != turn) {
            turn = key
            linesThisTurn = 0
        }
        if (linesThisTurn >= LINES_PER_TURN) {
            return Verdict.Denied(
                RULE,
                "$LINES_PER_TURN lines already this turn, the most a turn may say; " +
                    "put the rest in fewer, fuller lines next turn, or end the turn",
            )
        }
        val wait = lines.waitMs(now)
        if (wait > 0) {
            return Verdict.Denied(RULE, "$LINES_PER_MINUTE lines already in the last minute; the next is possible in ${describe(wait)}")
        }
        lines.add(now)
        linesThisTurn++
        return Verdict.Allowed
    }

    /** One `play_sound` call: allowed and counted, or refused with when to try again. */
    @Synchronized
    fun sound(): Verdict {
        val now = clock.millis()
        val wait = sounds.waitMs(now)
        if (wait > 0) {
            return Verdict.Denied(RULE, "$SOUNDS_PER_MINUTE sounds already in the last minute; the next is possible in ${describe(wait)}")
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
        /** e.g. `Bob got one 12 s ago (next in 18 s)`. */
        fun describe(): String = "${player.name} got one ${SpeechLimiter.describe(agoMs)} ago (next in ${SpeechLimiter.describe(leftMs)})"
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
        const val SOUNDS_PER_MINUTE = 10
        const val TITLE_COOLDOWN_MS = 30_000L
        const val ACTIONBAR_COOLDOWN_MS = 5_000L
        const val BOOK_COOLDOWN_MS = 5 * 60_000L
        private const val MINUTE_MS = 60_000L

        private val byServer = WeakHashMap<NarratorServices, SpeechLimiter>()

        /** The limiter of the server [services] belong to: the same for every conversation, dropped with the server. */
        @Synchronized
        fun of(services: NarratorServices): SpeechLimiter = byServer.getOrPut(services) { SpeechLimiter(services.clock) }

        /** A duration for the model, rounded up to the second: `18 s`, `3 min 10 s`. */
        fun describe(ms: Long): String {
            val seconds = (ms + 999) / 1_000
            return when {
                seconds < 60 -> "$seconds s"
                seconds % 60 == 0L -> "${seconds / 60} min"
                else -> "${seconds / 60} min ${seconds % 60} s"
            }
        }
    }
}
