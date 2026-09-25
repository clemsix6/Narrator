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
import io.github.clemsix6.narrator2.guard.SpawnPolicy
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
 * players are [protectedPlayers], `activeOn` answers [active], and death guards are recorded. The
 * ledgers and policies are the real ones (their decisions go to [traces]); they need a live player,
 * so unit tests without Minecraft never call them.
 */
class FakeGuards(
    @Volatile var verdict: Verdict = Verdict.Allowed,
    @Volatile var chaos: Int = NarratorConfig().chaos,
    clock: Clock = TestClock(),
    val traces: MemoryTraceSink = MemoryTraceSink(),
) : Guards {
    val protectedPlayers: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    val armed = CopyOnWriteArrayList<Pair<UUID, Int>>()
    val active = ConcurrentHashMap<UUID, List<ActiveEffect>>()

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

    override fun hostile(target: ServerPlayer, caller: Caller, until: Long): Verdict = verdict

    override fun armDeathGuard(target: UUID, seconds: Int) {
        armed += target to seconds
    }

    override fun deathAttribution(victim: ServerPlayer, source: DamageSource) =
        DeathAttribution(narrator = victim.uuid in armed.map { it.first }, keptInventory = false)

    override fun activeOn(uuid: UUID): List<ActiveEffect> = active[uuid].orEmpty()

    override fun setProtected(uuid: UUID, name: String, on: Boolean) {
        if (on) protectedPlayers += uuid else protectedPlayers -= uuid
    }

    override fun caps(): Caps = Caps.of(chaos)
}
