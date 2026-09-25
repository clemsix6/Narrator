package io.github.clemsix6.narrator2.guard

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Caller
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.level.gamerules.GameRules
import java.util.UUID

/**
 * Registers the guards' hooks: the death guard (`ALLOW_DEATH`), the ledgers on join and respawn, the
 * Narrator's mobs (entity load and unload), the once-a-second expirations, and the guards' persistent
 * attachment types (guard state, mob expiry). Once, at mod initialization; the handlers find the
 * running server's guards through `NarratorRuntime.current`.
 *
 * Provisional body (T02): nothing to register. T09a writes it.
 */
fun registerGuardHooks() = Unit

/**
 * Creates the guards of a server that starts (M1_M2 §10).
 *
 * Provisional body (T02): guards that refuse every action, so that no tool can act on a player before
 * the engine's rules exist. T09a replaces it.
 */
@Suppress("UNUSED_PARAMETER")
fun createGuards(services: NarratorServices): Guards = RefuseEverything

/** Guards that let nothing through: every player is protected, every hostile action is refused. */
private object RefuseEverything : Guards {
    private const val RULE = "guards_not_ready"

    override fun isProtected(uuid: UUID): Boolean = true

    override fun hostile(target: ServerPlayer, caller: Caller, until: Long): Verdict =
        Verdict.Denied(RULE, "the engine's guards are not in place yet, so no action against players is allowed.")

    override fun armDeathGuard(target: UUID, seconds: Int) = Unit

    override fun deathAttribution(victim: ServerPlayer, source: DamageSource): DeathAttribution =
        DeathAttribution(narrator = false, keptInventory = victim.level().gameRules.get(GameRules.KEEP_INVENTORY))

    override fun activeOn(uuid: UUID): List<ActiveEffect> = emptyList()

    override fun setProtected(uuid: UUID, name: String, on: Boolean) {
        Log.guard.warn("protection of $name not changed: the guards are not in place yet")
    }
}
