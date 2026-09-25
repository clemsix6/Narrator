package io.github.clemsix6.narrator2.oracle

import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel

/**
 * The world as a whole, read on the server thread in one go ([capture]), readable from any thread
 * afterwards: the Overworld's day, hour and weather, and a [PlayerSnapshot] of each player online. The
 * briefing's `<world …/>` line and players come from here.
 *
 * @property day days since the world began (the Overworld's clock / 24 000).
 * @property timeOfDay ticks into the day, 0-23 999 (0 is 06:00, 6 000 noon, 18 000 midnight).
 * @property weather `clear`, `rain` or `thunder`.
 */
data class WorldSnapshot(val day: Long, val timeOfDay: Int, val weather: String, val players: List<PlayerSnapshot>) {
    /** The hour on a 24-hour clock, `18:40`. */
    val clock: String
        get() {
            val hours = (timeOfDay / TICKS_PER_HOUR + 6) % 24
            val minutes = (timeOfDay % TICKS_PER_HOUR) * 60 / TICKS_PER_HOUR
            return "%02d:%02d".format(hours, minutes)
        }

    /** `day`, `dusk`, `night` (monsters spawn in the open) or `dawn`. */
    val time: String
        get() = when (timeOfDay) {
            in 0 until DUSK -> "day"
            in DUSK until NIGHT -> "dusk"
            in NIGHT until DAWN -> "night"
            else -> "dawn"
        }

    /** `day 37 · night 18:40 · clear`. */
    fun describe(): String = "day $day · $time $clock · $weather"

    companion object {
        private const val TICKS_PER_DAY = 24_000
        private const val TICKS_PER_HOUR = 1_000
        private const val DUSK = 12_000
        private const val NIGHT = 13_000
        private const val DAWN = 23_000

        /**
         * Reads the world and, with [players], every player online (about 0.1 to 0.3 ms each). Server
         * thread only; reads loaded chunks only.
         */
        fun capture(server: MinecraftServer, players: Boolean = true): WorldSnapshot {
            val overworld = server.overworld()
            val clock = overworld.defaultClockTime
            val readers = HashMap<ServerLevel, WorldReader>()
            val snapshots = if (!players) {
                emptyList()
            } else {
                server.playerList.players.map { player ->
                    PlayerSnapshot.capture(player, readers.getOrPut(player.level()) { WorldReader(player.level()) })
                }
            }
            return WorldSnapshot(clock / TICKS_PER_DAY, (clock % TICKS_PER_DAY).toInt(), weather(overworld), snapshots)
        }

        /** `thunder`, `rain` or `clear`: the weather is the same in every dimension that has one. */
        fun weather(level: ServerLevel): String = when {
            level.isThundering -> "thunder"
            level.isRaining -> "rain"
            else -> "clear"
        }
    }
}
