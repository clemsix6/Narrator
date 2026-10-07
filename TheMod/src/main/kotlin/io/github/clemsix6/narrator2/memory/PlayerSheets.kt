package io.github.clemsix6.narrator2.memory

import io.github.clemsix6.narrator2.event.PlayerRef
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.util.UUID

/** The parts of a sheet `player_profile` and `/narrator sheet` can ask for (M3 §5.5). */
@Serializable
enum class SheetPart {
    @SerialName("identity") IDENTITY,
    @SerialName("recap") RECAP,
    @SerialName("personality") PERSONALITY,
    @SerialName("stats") STATS,
    @SerialName("history") HISTORY,
    @SerialName("memories") MEMORIES,
    ;

    val id: String get() = name.lowercase()

    companion object {
        val ALL: Set<SheetPart> = entries.toSet()
    }
}

/**
 * The three renderings of a sheet (M3 §11.4), with their budget: [FULL] for `player_profile` and
 * `/narrator sheet`, [CONDENSED] for the briefing and a player's join, [LINE] beyond six players online.
 */
enum class SheetDetail(val maxChars: Int) {
    FULL(7_000),
    CONDENSED(1_800),
    LINE(200),
}

/** A row of `sheet_stats`, computed by the code (M3 §6.1). */
data class SheetStat(val stat: String, val value: Double?, val detail: JsonObject?, val computedAt: Instant)

/**
 * Everything known about a player (M3 §3.5), assembled from the Journal and the memory; [PlayerSheets.render]
 * makes the text the model reads.
 *
 * @property formerNames the player's other names, oldest first.
 * @property playedSeconds closed sessions plus the running one.
 * @property nickname the active nickname (canon `nickname`), [title] the active title.
 * @property docs the written sections, current versions.
 * @property traits current traits, not retired; their confidence is the value set (decay at rendering).
 * @property favor computed at read time (M3 §6.2), null without interactions that count; [favorTrend]
 *   its change over the last 5 hours of the player's play; [respect] 0..100, null under 5 messages to
 *   the Narrator.
 * @property disrespect the latest lacks of respect, newest first.
 * @property lastAttentionAgoPlayedSeconds the player's play time since the Narrator last paid attention to
 *   them; null when never.
 * @property recent the latest interactions, newest first; [marked] those of valence ±3 or disrespect.
 * @property openCanon the open canon entries that concern the player.
 * @property memories the strongest memories about the player.
 */
data class PlayerSheet(
    val player: PlayerRef,
    val formerNames: List<String>,
    val isOp: Boolean,
    val firstSeen: Instant,
    val lastSeen: Instant,
    val sessions: Int,
    val playedSeconds: Long,
    val online: Boolean,
    val nickname: CanonEntry?,
    val title: CanonEntry?,
    val docs: Map<SheetSection, MemoryDoc>,
    val traits: List<Trait>,
    val skills: List<SkillScore>,
    val relations: List<Relation>,
    val stats: Map<String, SheetStat>,
    val favor: Double?,
    val favorTrend: Double?,
    val respect: Int?,
    val disrespect: List<Interaction>,
    val lastAttentionAgoPlayedSeconds: Long?,
    val recent: List<Interaction>,
    val marked: List<Interaction>,
    val openCanon: List<CanonEntry>,
    val memories: List<MemoryEntry>,
)

/** Player sheets: assembled from the database, rendered for the model. Implemented by `DuckPlayerSheets` and `SheetRenderer` (M3-07). */
interface PlayerSheets {
    /** The sheet of [player] at [now] (epoch milliseconds); null for a player never seen. Reads the Journal. */
    suspend fun sheet(player: UUID, now: Long): PlayerSheet?

    /**
     * [sheet] as text for the model, in English labels with the French texts escaped, within
     * [SheetDetail.maxChars]; only [parts] (all of them for the briefing and joins). Missing sections say
     * so (`Recap: (not written yet: …)`). Pure: no database.
     */
    fun render(sheet: PlayerSheet, detail: SheetDetail, now: Long, parts: Set<SheetPart> = SheetPart.ALL): String
}
