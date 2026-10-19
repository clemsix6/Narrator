package io.github.clemsix6.narrator2.testing

import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.config.UiTexts
import io.github.clemsix6.narrator2.guard.ActiveEffect
import io.github.clemsix6.narrator2.guard.AttributeLedger
import io.github.clemsix6.narrator2.guard.Caps
import io.github.clemsix6.narrator2.guard.CommandPolicy
import io.github.clemsix6.narrator2.guard.DeathAttribution
import io.github.clemsix6.narrator2.guard.EffectLedger
import io.github.clemsix6.narrator2.guard.GuardStates
import io.github.clemsix6.narrator2.guard.Guards
import io.github.clemsix6.narrator2.guard.ItemLedger
import io.github.clemsix6.narrator2.guard.NarratorMobs
import io.github.clemsix6.narrator2.guard.Peril
import io.github.clemsix6.narrator2.guard.PlayerPacing
import io.github.clemsix6.narrator2.guard.PunishmentCategory
import io.github.clemsix6.narrator2.guard.SpawnPolicy
import io.github.clemsix6.narrator2.guard.TargetingPolicy
import io.github.clemsix6.narrator2.guard.Verdict
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.trace.Decisions
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Guards for tests: every hostile action gets [verdict], the caps are those of [chaos], the protected
 * players are [protectedPlayers], `activeOn` answers [active], `deathsLeft` [deaths], and death guards are recorded. The
 * ledgers and policies are the real ones (their decisions go to [traces]); they need a live player,
 * so unit tests without Minecraft never call them.
 *
 * M4: every call to `hostile` is recorded in [hostiles], with its caller and its category; `pardon` answers
 * [pardoned] and records the player, `grace` answers [graceVerdict], `isPunished` says whether the player is in
 * [punished], `rescue` is recorded in [rescues], and `pacing` answers [pacingOf] for each player (by default:
 * nothing punished, no rest, no grace, no offering).
 */
class FakeGuards(
    @Volatile var verdict: Verdict = Verdict.Allowed,
    @Volatile var chaos: Int = NarratorConfig().chaos,
    clock: Clock = TestClock(),
    val traces: MemoryTraceSink = MemoryTraceSink(),
) : Guards {
    /** One call to `hostile`. */
    data class Hostile(val target: UUID, val caller: Caller, val until: Long, val category: PunishmentCategory?)

    /** One death guard armed with its peril. */
    data class Armed(val target: UUID, val seconds: Int, val peril: Peril)

    val protectedPlayers: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    val armed = CopyOnWriteArrayList<Pair<UUID, Int>>()

    /** [armed], with the peril of each (a death guard armed without one covers [Peril.ANY]). */
    val armedPerils = CopyOnWriteArrayList<Armed>()
    val active = ConcurrentHashMap<UUID, List<ActiveEffect>>()
    val hostiles = CopyOnWriteArrayList<Hostile>()

    /** What `pardon` answers; the players pardoned, in order. */
    @Volatile var pardoned: List<String> = emptyList()
    val pardons = CopyOnWriteArrayList<UUID>()

    /** What `grace` answers; the graces asked, player and end. */
    @Volatile var graceVerdict: Verdict = Verdict.Allowed
    val graces = CopyOnWriteArrayList<Pair<UUID, Long>>()

    /** The players `isPunished` says are punished. */
    val punished: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /** The rescues asked: the player and why. */
    val rescues = CopyOnWriteArrayList<Pair<UUID, String>>()

    /** What `pacing` answers for a player; nothing punished, no rest, no grace and no offering for the others. */
    val pacingOf = ConcurrentHashMap<UUID, PlayerPacing>()

    /** What `deathsLeft` answers for a player; the cap of [chaos] for the others (0 when protected). */
    val deaths = ConcurrentHashMap<UUID, Int>()

    private val states = GuardStates()
    private val decisions = Decisions(traces, clock)

    override val attributes = AttributeLedger(states, decisions, clock)
    override val effects = EffectLedger(states, clock)
    override val items = ItemLedger(states, decisions, clock) { UiTexts.load(NarratorConfig().language) }
    override val mobs = NarratorMobs()
    override val commands = CommandPolicy { emptyList() }

    // A getter: the policy object reads the game's registries, which unit tests do not load.
    override val spawns: SpawnPolicy get() = SpawnPolicy

    override fun isProtected(uuid: UUID): Boolean = uuid in protectedPlayers

    override fun hostile(target: ServerPlayer, caller: Caller, until: Long, category: PunishmentCategory?): Verdict {
        hostiles += Hostile(target.uuid, caller, until, category)
        return verdict
    }

    override fun armDeathGuard(target: UUID, seconds: Int) = armDeathGuard(target, seconds, Peril.ANY)

    override fun armDeathGuard(target: UUID, seconds: Int, peril: Peril) {
        armed += target to seconds
        armedPerils += Armed(target, seconds, peril)
    }

    override fun deathAttribution(victim: ServerPlayer, source: DamageSource) =
        DeathAttribution(narrator = victim.uuid in armed.map { it.first }, keptInventory = false)

    override fun activeOn(uuid: UUID): List<ActiveEffect> = active[uuid].orEmpty()

    override fun setProtected(uuid: UUID, name: String, on: Boolean) {
        if (on) protectedPlayers += uuid else protectedPlayers -= uuid
    }

    override fun caps(): Caps = Caps.of(chaos)

    override fun deathsLeft(player: UUID): Int = if (player in protectedPlayers) 0 else deaths[player] ?: caps().narratorKillsPerHour

    override fun pardon(player: ServerPlayer, caller: Caller): List<String> {
        pardons += player.uuid
        return pardoned
    }

    override fun grace(player: ServerPlayer, until: Long, caller: Caller): Verdict {
        graces += player.uuid to until
        return graceVerdict
    }

    override fun isPunished(player: ServerPlayer): Boolean = player.uuid in punished

    override fun rescue(player: ServerPlayer, why: String) {
        rescues += player.uuid to why
    }

    override fun pacing(players: List<UUID>, now: Long): List<PlayerPacing> = players.map { uuid ->
        pacingOf[uuid] ?: PlayerPacing(uuid, 0, TargetingPolicy.MAX_PUNISHMENTS_PER_HOUR, emptyMap(), null, emptyList())
    }
}
