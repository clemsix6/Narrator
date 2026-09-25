package io.github.clemsix6.narrator2.guard

import io.github.clemsix6.narrator2.tool.ToolText
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.sql.Connection

/**
 * What the death guard knew when a player died (M2 review, finding 18): the cause, whether the death was
 * the Narrator's, the guard's arming, the deaths the Narrator may still cause, and the Narrator's world
 * actions on the victim in the [WINDOW_MS] before. Every death writes one as its `death_guard died`
 * decision ([reason], and [detail] as the row's `detail`), so that a death the guard did not blame on the
 * Narrator still shows what the Narrator did just before; `/narrator why <player>` reads it back ([parse]).
 *
 * @property at when the player died, epoch milliseconds.
 * @property cause the damage type, e.g. `minecraft:fall`.
 * @property narrator the death was the Narrator's: counted against its quota, inventory kept.
 * @property keptInventory the player kept their inventory (the Narrator's death, or the game rule).
 * @property isProtected the operators protect the player from the Narrator.
 * @property playTime the player's play time at death, in ticks (the `PLAY_TIME` stat).
 * @property armedUntil play time up to which the guard was armed, in ticks; 0 when it was not.
 * @property deathsLeft deaths the Narrator may still cause to the player in this played hour, after this one.
 * @property actions the Narrator's world actions on the player that started in the [WINDOW_MS] before
 *   the death, done or maybe done, oldest first; null when they could not be read ([actionsUnknown] says why).
 */
@Serializable
data class DeathReport(
    val player: String,
    val at: Long,
    val cause: String,
    val narrator: Boolean,
    @SerialName("kept_inventory") val keptInventory: Boolean,
    @SerialName("protected") val isProtected: Boolean,
    @SerialName("play_time") val playTime: Long,
    @SerialName("armed_until") val armedUntil: Long,
    @SerialName("deaths_left") val deathsLeft: Int,
    @SerialName("deaths_per_hour") val deathsPerHour: Int,
    val actions: List<NarratorAction>? = null,
    @SerialName("actions_unknown") val actionsUnknown: String? = null,
) {
    /**
     * One sentence for the decision and the log, e.g. `minecraft:fall, not the Narrator's (guard ran out
     * 3 s of play before), inventory dropped; 1 of 1 Narrator deaths left this played hour; the Narrator
     * acted on Bartok in the last 60 s: apply_effect 40 s before (turn 42)`.
     */
    fun reason(): String = buildString {
        append(cause)
        append(if (narrator) ", caused by the Narrator" else ", not the Narrator's")
        append(" (guard ").append(guard()).append(")")
        append(if (keptInventory) ", inventory kept" else ", inventory dropped")
        append("; ").append(quota())
        append("; ").append(actionsBefore())
    }

    /** The guard's arming at the death: `armed for 12s of play more`, `ran out 3s of play before`, `not armed`. */
    fun guard(): String = when {
        armedUntil > playTime -> "armed for ${ToolText.duration(seconds(armedUntil - playTime) * 1_000)} of play more"
        armedUntil > 0 -> "ran out ${ToolText.duration(seconds(playTime - armedUntil) * 1_000)} of play before"
        else -> "not armed"
    }

    /** `1 of 1 Narrator deaths left this played hour` (the deaths the Narrator may still cause), or the protection. */
    fun quota(): String =
        if (isProtected) "protected by the operators" else "$deathsLeft of $deathsPerHour Narrator deaths left this played hour"

    /** What the Narrator did to the player in the [WINDOW_MS] before the death. */
    fun actionsBefore(): String {
        val window = "in the last ${ToolText.duration(WINDOW_MS)}"
        val done = actions ?: return "the Narrator's actions $window are unknown (${actionsUnknown ?: "not read"})"
        if (done.isEmpty()) return "no world action of the Narrator on $player $window"
        return "the Narrator acted on $player $window: " + done.joinToString { "${it.tool} ${ToolText.duration(secondsBefore(it) * 1_000)} before${it.turnId?.let { turn -> " (turn $turn)" }.orEmpty()}" }
    }

    /** Seconds between [action]'s start and the death. */
    fun secondsBefore(action: NarratorAction): Long = ((at - action.at) / 1_000).coerceAtLeast(0)

    /** The report as the `detail` of its decision. */
    fun detail(): JsonObject = JSON.encodeToJsonElement(serializer(), this).jsonObject

    companion object {
        /** How far before a death the Narrator's actions are looked for. */
        const val WINDOW_MS = 60_000L

        private const val TICKS_PER_SECOND = 20
        private const val RESULT_CHARS = 200

        private val JSON = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }

        /** The report written as a decision's `detail`; null when [detail] is not one. */
        fun parse(detail: String): DeathReport? = try {
            JSON.decodeFromString(serializer(), detail)
        } catch (e: IllegalArgumentException) {
            null
        }

        /**
         * The Narrator's world actions on [player] (exact name) that started from [from] to [to], epoch
         * milliseconds, oldest first: the `tool_calls` of effect `world` with the player among their
         * targets, done (`ok`) or maybe done (`unknown`). Runs on a read connection of the Journal.
         */
        fun actionsOn(connection: Connection, player: String, from: Long, to: Long): List<NarratorAction> =
            connection.prepareStatement(
                """
                SELECT id, epoch_ms(ts), tool, turn_id, status, result FROM tool_calls
                WHERE effect = 'world' AND status IN ('ok', 'unknown') AND list_contains(targets, ?)
                  AND ts >= to_timestamp(CAST(? AS DOUBLE) / 1000) AND ts <= to_timestamp(CAST(? AS DOUBLE) / 1000)
                ORDER BY ts, id
                """.trimIndent(),
            ).use { statement ->
                statement.queryTimeout = QUERY_TIMEOUT_SECONDS
                statement.setString(1, player)
                statement.setLong(2, from)
                statement.setLong(3, to)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            val turn = rows.getLong(4).takeUnless { rows.wasNull() }
                            val result = rows.getString(6)?.let { if (it.length <= RESULT_CHARS) it else it.take(RESULT_CHARS - 1) + "…" }
                            add(NarratorAction(rows.getLong(1), rows.getLong(2), rows.getString(3), turn, rows.getString(5), result))
                        }
                    }
                }
            }

        private const val QUERY_TIMEOUT_SECONDS = 5

        private fun seconds(ticks: Long): Long = ticks / TICKS_PER_SECOND
    }
}

/** A world action of the Narrator on a player: one `tool_calls` row; [at] is when it started, epoch milliseconds. */
@Serializable
data class NarratorAction(
    val id: Long,
    val at: Long,
    val tool: String,
    @SerialName("turn_id") val turnId: Long?,
    val status: String,
    val result: String?,
)
