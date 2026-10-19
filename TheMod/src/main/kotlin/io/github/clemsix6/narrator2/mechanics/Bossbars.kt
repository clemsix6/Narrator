package io.github.clemsix6.narrator2.mechanics

import io.github.clemsix6.narrator2.config.UiTexts
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.BossEvent.BossBarColor
import net.minecraft.world.BossEvent.BossBarOverlay
import java.util.UUID

/**
 * The engine's boss bars (M4 §5.4): one [ServerBossEvent] per (key, player), that only this player sees, in
 * memory. Never `CustomBossEvents` (those of `/bossbar`): `/bossbar` would list and change them, and the world
 * would save them.
 *
 * Nothing is saved: the bars are drawn again from the engine's state, which lives in the world ([render],
 * each second). After a reconnection or a restart, the next drawing shows them again; a bar whose challenge
 * left the state is taken down at the same drawing.
 *
 * A key is a challenge's or a duel's reference ([key]: `C-50`). The client keeps its bars across a respawn and
 * a change of dimension, and drops them all when it leaves the server: a bar follows the new player object of a
 * respawn ([onRespawn]) and is forgotten when its player leaves ([onLeave]). Server thread.
 */
class Bossbars {
    /** The bars shown, by key, then by player. */
    private val shown = LinkedHashMap<String, MutableMap<UUID, ServerBossEvent>>()

    /**
     * Shows [player] their bar [key] as given: created at the first call, updated after (the game sends only
     * what changed). [progress] is kept within [0, 1].
     */
    fun show(key: String, player: ServerPlayer, name: Component, progress: Float, color: BossBarColor, overlay: BossBarOverlay) {
        val bars = shown.getOrPut(key) { LinkedHashMap() }
        val bar = bars.getOrPut(player.uuid) {
            log.debug { "bar $key shown to ${player.plainTextName}" }
            ServerBossEvent(UUID.randomUUID(), name, color, overlay)
        }
        bar.setName(name)
        bar.setProgress(if (progress.isNaN()) 0f else progress.coerceIn(0f, 1f))
        bar.setColor(color)
        bar.setOverlay(overlay)
        if (player !in bar.players) {
            // An older object of the same player (a respawn nobody reported): the client gets the bar again, now followed.
            bar.removeAllPlayers()
            bar.addPlayer(player)
        }
    }

    /** Takes down [player]'s bar [key], if shown. */
    fun hide(key: String, player: UUID) {
        val bars = shown[key] ?: return
        val bar = bars.remove(player) ?: return
        bar.removeAllPlayers()
        if (bars.isEmpty()) shown.remove(key)
        log.debug { "bar $key of $player taken down" }
    }

    /** Takes down every bar of [key]: its challenge or duel ended. */
    fun hideAll(key: String) {
        val bars = shown.remove(key) ?: return
        bars.values.forEach { it.removeAllPlayers() }
        log.debug { "the ${bars.size} bar(s) of $key taken down" }
    }

    /** Takes down every bar (the engine stops). */
    fun hideEverything() {
        if (shown.isEmpty()) return
        val count = shown.values.sumOf { it.size }
        shown.keys.toList().forEach(::hideAll)
        log.info("$count boss bar(s) taken down")
    }

    /** [old] respawned as [new] (`PlayerList.respawn`, leaving the End too): its bars follow the new object. */
    fun onRespawn(old: ServerPlayer, new: ServerPlayer) {
        val followed = shown.values.mapNotNull { it[new.uuid] }
        for (bar in followed) {
            // The same connection: the client drops the bar and gets it back in the same tick.
            bar.removeAllPlayers()
            bar.addPlayer(new)
        }
        if (followed.isNotEmpty()) log.debug { "${followed.size} bar(s) of ${old.plainTextName} follow their respawn" }
    }

    /** [player] leaves: their client drops every bar; they are shown again at the next drawing after they come back. */
    fun onLeave(player: ServerPlayer) {
        shown.keys.toList().forEach { key -> hide(key, player.uuid) }
    }

    /**
     * Draws the bars of [state] at [now]: each online participant of each challenge and running duel sees their
     * own bar ([BarText]); every other bar shown is taken down (a challenge that ended, a participant no longer
     * in it). Each second, and when the server starts.
     */
    fun render(server: MinecraftServer, state: MechanicsState, ui: UiTexts, now: Long) {
        val drawn = HashSet<Pair<String, UUID>>()
        for (challenge in state.challenges) {
            val key = key(challenge.id)
            for (participant in challenge.participants) {
                val player = server.playerList.getPlayer(participant.playerId) ?: continue
                val bar = BarText.of(challenge, participant, now, ui) ?: continue
                show(key, player, Component.literal(bar.name), bar.progress, bar.color, bar.overlay)
                drawn += key to player.uuid
            }
        }
        for ((key, bars) in shown.entries.toList()) {
            bars.keys.filter { (key to it) !in drawn }.forEach { hide(key, it) }
        }
    }

    companion object {
        private val log = Log.mechanics

        /** The key of the bars of challenge or duel [id]: its reference, `C-50`. */
        fun key(id: Long): String = Ref(RefKind.CANON, id).toString()
    }
}

/**
 * The boss bars as a part of the engine (M4 §5.4): drawn from the engine's [state] when the server starts and
 * each second, taken down when it stops. Put it after the parts that change the challenges (challenges, duels):
 * their changes of the second are drawn in the same tick, a challenge's end included.
 */
internal class ChallengeBars(
    private val bars: Bossbars,
    private val state: () -> MechanicsState,
    private val ui: () -> UiTexts,
) : MechanicPart {
    override fun started(server: MinecraftServer, now: Long) = bars.render(server, state(), ui(), now)

    override fun everySecond(server: MinecraftServer, now: Long) = bars.render(server, state(), ui(), now)

    override fun onLeave(player: ServerPlayer) = bars.onLeave(player)

    override fun onRespawn(old: ServerPlayer, new: ServerPlayer) = bars.onRespawn(old, new)

    override fun stopping() = bars.hideEverything()
}
