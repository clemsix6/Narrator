package io.github.clemsix6.narrator2.director

import io.github.clemsix6.narrator2.event.EventBus
import io.github.clemsix6.narrator2.event.NarratorSpoke
import io.github.clemsix6.narrator2.event.Subscription
import io.github.clemsix6.narrator2.runtime.ShutdownSequence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * "The Narrator is thinking…" (M1_M2 §12.12): while a turn prepares an answer to an addressed message or
 * a prayer, its author sees [text] above the hotbar, renewed every [REFRESH_MS]. It is cleared as soon
 * as a line of the Narrator reaches that player ([spoke], from a synchronous subscriber of
 * [NarratorSpoke]) or when the turn ends ([stop]).
 *
 * [show] and [stop] are called by the Director's coroutine; [spoke] from the server thread.
 *
 * @param text the text to show, or null when `director.thinkingIndicator` is off; read at each use.
 */
class ThinkingIndicator(private val game: GameView, private val text: () -> String?) {
    private val waiting: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    private var refresher: Job? = null // Director coroutine only

    /** The players who see the indicator now. */
    val showing: Set<UUID> get() = waiting.toSet()

    /** Shows the indicator to [players] until they get an answer or the turn ends; the refresh runs in [scope]. */
    fun show(players: Collection<UUID>, scope: CoroutineScope) {
        val shown = text() ?: return
        val added = players.filter { waiting.add(it) }
        if (added.isEmpty()) return
        game.actionbar(added, shown)
        if (refresher?.isActive == true) return
        refresher = scope.launch {
            while (true) {
                delay(REFRESH_MS)
                val still = waiting.toList()
                if (still.isEmpty()) break
                text()?.let { game.actionbar(still, it) }
            }
        }
    }

    /** A line of the Narrator reached [recipients] (everyone when null): their indicator goes. Any thread. */
    fun spoke(recipients: List<UUID>?) {
        val answered = if (recipients == null) waiting.toList() else recipients.filter { it in waiting }
        if (answered.isEmpty()) return
        waiting.removeAll(answered.toSet())
        game.clearActionbar(answered)
    }

    /** The turn ended: every indicator goes. */
    fun stop() {
        refresher?.cancel()
        refresher = null
        val left = waiting.toList()
        waiting.removeAll(left.toSet())
        if (left.isNotEmpty()) game.clearActionbar(left)
    }

    /** Follows the Narrator's lines on [bus] (owner `ShutdownSequence.DIRECTOR_OWNER`). */
    fun subscribe(bus: EventBus): Subscription =
        bus.subscribeSync(SUBSCRIBER, ShutdownSequence.DIRECTOR_OWNER, { it is NarratorSpoke }) { envelope ->
            spoke((envelope.event as NarratorSpoke).recipients?.map { it.uuid })
        }

    companion object {
        const val SUBSCRIBER = "thinking_indicator"

        /** The action bar fades after about 3 s: the text is sent again before. */
        const val REFRESH_MS = 2_000L
    }
}
