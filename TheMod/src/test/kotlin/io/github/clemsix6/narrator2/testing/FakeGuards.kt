package io.github.clemsix6.narrator2.testing

import io.github.clemsix6.narrator2.guard.ActiveEffect
import io.github.clemsix6.narrator2.guard.DeathAttribution
import io.github.clemsix6.narrator2.guard.Guards
import io.github.clemsix6.narrator2.guard.Verdict
import io.github.clemsix6.narrator2.tool.Caller
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Guards for tests: every hostile action gets [verdict], the protected players are [protectedPlayers],
 * and death guards and effects are recorded. Whoever extends the `Guards` contract (T09a) extends this
 * fake with it.
 */
class FakeGuards(@Volatile var verdict: Verdict = Verdict.Allowed) : Guards {
    val protectedPlayers: MutableSet<UUID> = ConcurrentHashMap.newKeySet()
    val armed = CopyOnWriteArrayList<Pair<UUID, Int>>()
    val effects = ConcurrentHashMap<UUID, List<ActiveEffect>>()

    override fun isProtected(uuid: UUID): Boolean = uuid in protectedPlayers

    override fun hostile(target: ServerPlayer, caller: Caller, until: Long): Verdict = verdict

    override fun armDeathGuard(target: UUID, seconds: Int) {
        armed += target to seconds
    }

    override fun deathAttribution(victim: ServerPlayer, source: DamageSource) =
        DeathAttribution(narrator = victim.uuid in armed.map { it.first }, keptInventory = false)

    override fun activeOn(uuid: UUID): List<ActiveEffect> = effects[uuid].orEmpty()

    override fun setProtected(uuid: UUID, name: String, on: Boolean) {
        if (on) protectedPlayers += uuid else protectedPlayers -= uuid
    }
}
