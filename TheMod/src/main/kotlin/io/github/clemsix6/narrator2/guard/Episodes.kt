package io.github.clemsix6.narrator2.guard

import java.time.Clock
import java.util.UUID

/**
 * One punishment: the hostile actions of one Director turn against one player, until all their effects
 * are over (M1_M2 §10.3).
 *
 * @property turnId the turn that opened it; the hostile actions of that turn extend it.
 * @property until when its last effect ends, epoch milliseconds.
 */
data class Episode(val target: UUID, val turnId: Long?, val until: Long)

/**
 * The punishments running against each player. An episode opens with the first hostile action of a
 * turn against a player, and the hostile actions of the same turn extend it; it is rebuilt from the
 * player's guard state when they join.
 *
 * Written by T02 as the contract between T09a (consumer) and T09b (owner). Provisional body: no
 * episode is ever tracked. T09b writes it.
 */
class Episodes(@Suppress("unused") private val clock: Clock) {
    /** The episode still running against [target], if any. Any thread. */
    @Suppress("UNUSED_PARAMETER")
    fun active(target: UUID): Episode? = null

    /** Opens the episode of [turnId] against [target], or extends it, until [until] (epoch milliseconds). */
    @Suppress("UNUSED_PARAMETER")
    fun open(target: UUID, turnId: Long?, until: Long) = Unit
}
