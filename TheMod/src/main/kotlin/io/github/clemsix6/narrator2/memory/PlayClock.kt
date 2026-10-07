package io.github.clemsix6.narrator2.memory

import java.util.UUID

/**
 * The play clocks (M3 §3.2): what decays and fades is measured in time played, not in calendar time, so
 * that holidays make nothing forgotten. Rows keep the clock's value when written (`reinforced_play_s`,
 * `played_s`, `set_played_s`): a row's age is a subtraction. Never waits for the database: any thread.
 * Implemented by `DuckPlayClock` (M3-06).
 */
interface PlayClock {
    /** Seconds during which at least one player was online, since the mod first ran on this world, at [atMillis]. */
    fun worldPlaySeconds(atMillis: Long): Long

    /** Seconds [player] has played at [atMillis]: closed sessions plus the running one. */
    fun playerPlaySeconds(player: UUID, atMillis: Long): Long
}
