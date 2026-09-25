package io.github.clemsix6.narrator2.observer

import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.event.StatsSnapshot
import io.github.clemsix6.narrator2.mixin.observer.StatsCounterAccessor
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet
import net.fabricmc.fabric.api.entity.FakePlayer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.stats.Stat
import net.minecraft.stats.Stats
import java.util.UUID

/**
 * The vanilla stat totals of the players online (M1_M2 §7.6), published as [StatsSnapshot]s holding only
 * the values that changed since the player's previous snapshot: every [INTERVAL_MS] per player (from
 * the first check after they joined), and when they leave. A player's first snapshot since the server
 * started holds all their stats, so the Journal's totals catch up with whatever it missed. (The game
 * counts `leave_game` after the departure event: it reaches the totals with the player's next session.)
 *
 * The copy of a player's table (a few hundred entries) and the comparison take a few microseconds on
 * the server thread, once every five minutes per player. Server thread only.
 */
class StatsTracker(private val publish: (GameEvent) -> Unit) {
    private class Tracked(var due: Long) {
        /** The table at the previous snapshot; null before the first one. */
        var previous: Map<Stat<*>, Int>? = null
    }

    private val tracked = HashMap<UUID, Tracked>()

    /**
     * Snapshots the players of [online] whose snapshot is due at [now], and starts following those who
     * just joined. Once a second is enough.
     */
    fun check(online: List<ServerPlayer>, now: Long) {
        val present = HashSet<UUID>(online.size)
        for (player in online) {
            if (player is FakePlayer) continue
            present += player.uuid
            val entry = tracked.getOrPut(player.uuid) { Tracked(now + INTERVAL_MS) }
            if (now >= entry.due) {
                snapshot(player, entry)
                entry.due = now + INTERVAL_MS
            }
        }
        tracked.keys.retainAll(present)
    }

    /** [player] leaves: a last snapshot, then they are forgotten. */
    fun left(player: ServerPlayer) {
        if (player is FakePlayer) return
        snapshot(player, tracked.remove(player.uuid) ?: Tracked(0))
    }

    private fun snapshot(player: ServerPlayer, entry: Tracked) {
        val current: Map<Stat<*>, Int> = HashMap((player.stats as StatsCounterAccessor).`narrator2$stats`())
        val changed = changes(entry.previous.orEmpty(), current) { it.name }
        entry.previous = current
        if (changed.isNotEmpty()) publish(StatsSnapshot(player.ref(), changed))
    }

    companion object {
        /** Time between two snapshots of a player online. */
        const val INTERVAL_MS = 5 * 60_000L

        /** The entries of [current] whose value differs from [previous] (a missing value is 0), keyed by [name]. */
        fun <K> changes(previous: Map<K, Int>, current: Map<K, Int>, name: (K) -> String): Map<String, Long> {
            val changed = LinkedHashMap<String, Long>()
            for ((key, value) in current) {
                if ((previous[key] ?: 0) != value) changed[name(key)] = value.toLong()
            }
            return changed
        }
    }
}

/** What the Observer does with an awarded stat (M1_M2 §7.6, OBSERVER_API.md §5.1). */
enum class StatRoute {
    /** Only the totals ([StatsTracker]): noise (movement, time), or a fact another hook tells better. */
    TOTALS,

    /** Summed by [ItemFlowAggregator] into `ItemsPickedUp`. */
    PICKUP,

    /** Published as `ItemsDropped` (awarded only for items thrown on purpose). */
    DROP,

    /** Published one by one as `StatAwarded`. */
    ONE_BY_ONE,
}

/**
 * Sorts stats on the hottest path of the Observer: `awardStat` runs 60 to 100 times a second per player,
 * nearly always for a stat that only feeds the totals. The test is a reference comparison on the stat's
 * type, then, for the custom stats only, a lookup in an identity set resolved once.
 */
object StatRoutes {
    /**
     * Custom stats that tell something no other hook does. Left out on purpose, besides the noise: the
     * stats that repeat another event, such as opening a container or a workstation (`OPEN_*`,
     * `INSPECT_*`, `INTERACT_WITH_*` of a menu, `TALKED_TO_VILLAGER`, `TRIGGER_TRAPPED_CHEST`:
     * `ContainerOpened`), sleeping (`SLEEP_IN_*`: `SleepStarted`), throwing (`DROP`: `ItemsDropped`),
     * and those of kills, deaths, damage, trades, breeding, fishing and enchanting, which have their
     * own events. Stats added by other mods only feed the totals.
     */
    private val oneByOne: Set<Stat<*>> by lazy {
        val ids = listOf(
            Stats.EAT_CAKE_SLICE, Stats.FILL_CAULDRON, Stats.USE_CAULDRON, Stats.CLEAN_ARMOR, Stats.CLEAN_BANNER,
            Stats.CLEAN_SHULKER_BOX, Stats.PLAY_NOTEBLOCK, Stats.TUNE_NOTEBLOCK, Stats.POT_FLOWER, Stats.PLAY_RECORD,
            Stats.BELL_RING, Stats.RAID_TRIGGER, Stats.RAID_WIN, Stats.TARGET_HIT, Stats.INTERACT_WITH_CAMPFIRE,
        )
        ReferenceOpenHashSet<Stat<*>>(ids.map { Stats.CUSTOM.get(it) })
    }

    fun of(stat: Stat<*>): StatRoute {
        val type = stat.type
        return when {
            type === Stats.ITEM_PICKED_UP -> StatRoute.PICKUP
            type === Stats.ITEM_DROPPED -> StatRoute.DROP
            type === Stats.ITEM_BROKEN -> StatRoute.ONE_BY_ONE
            type === Stats.CUSTOM && stat in oneByOne -> StatRoute.ONE_BY_ONE
            else -> StatRoute.TOTALS
        }
    }
}
