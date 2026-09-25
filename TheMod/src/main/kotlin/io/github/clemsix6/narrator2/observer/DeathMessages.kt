package io.github.clemsix6.narrator2.observer

import java.util.UUID

/**
 * Full death messages, handed from the mixin on `ServerPlayer.die` (T06), which reads the combat
 * tracker's message, to the death handler that builds `PlayerDied` (T05). Server thread only.
 *
 * Written by T02 so that T05 and T06 share this seam from the start; it belongs to T06, which keeps
 * [put] and [take].
 */
object DeathMessages {
    private val pending = HashMap<UUID, String>()

    /** The message of [player]'s death, about to be announced. */
    fun put(player: UUID, message: String) {
        pending[player] = message
    }

    /** The message recorded for [player]'s death, removed; null when none was recorded. */
    fun take(player: UUID): String? = pending.remove(player)
}
