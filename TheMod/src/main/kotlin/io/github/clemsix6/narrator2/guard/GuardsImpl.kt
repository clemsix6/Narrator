package io.github.clemsix6.narrator2.guard

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.observer.ref
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Util
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.gamerules.GameRules
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * The engine's rules of a running server (M1_M2 §10): protection, chaos 0 and no piling on before any
 * hostile action, the death guard, and the ledgers that undo what the Narrator does to players. Built
 * once per server by `createGuards`; the hooks of `registerGuardHooks` drive it: joins, respawns,
 * deaths, entity loads, and a check once a second of what is due.
 *
 * @param clock the engine's wall clock; in-game tests give their own to move time.
 */
class GuardsImpl(private val services: NarratorServices, private val clock: Clock = services.clock) : Guards {
    private val log = Log.guard
    private val decisions = Decisions(services.trace, clock)

    /** The guards' state of the players, with a copy readable from any thread. */
    val states = GuardStates()

    private val episodes = Episodes(clock)
    private val targeting = TargetingPolicy(services.players, clock)
    private val protection = ProtectedPlayers(
        services.dataDir.resolve(PROTECTED_FILE),
        { services.settings.get().config.safety.protectedPlayers },
        Util.ioPool(),
    )

    override val attributes = AttributeLedger(states, decisions, clock)
    val death = DeathGuard(states, ::caps, decisions, clock, attributes)
    override val effects = EffectLedger(states, clock)
    override val items = ItemLedger(states, decisions, clock) { services.settings.get().ui }
    override val mobs = NarratorMobs(items)
    override val commands = CommandPolicy(
        extraAllowed = { services.settings.get().config.safety.extraAllowedCommands },
        extra = { services.settings.get().config.safety.extraForbiddenCommands },
    )
    override val spawns: SpawnPolicy get() = SpawnPolicy

    /** When [tick] last checked what is due, epoch milliseconds. Server thread. */
    private var lastCheck = 0L

    override fun isProtected(uuid: UUID): Boolean = protection.isProtected(uuid)

    override fun caps(): Caps = Caps.of(services.settings.get().config.chaos)

    override fun hostile(target: ServerPlayer, caller: Caller, until: Long): Verdict {
        val name = target.plainTextName
        val verdict = when {
            protection.isProtected(target.uuid, name) ->
                Verdict.Denied(Rules.PROTECTED, "$name is protected by the operators; the Narrator does nothing hostile to them.")
            !caps().hostileAllowed ->
                Verdict.Denied(Rules.CHAOS_0, "the chaos level is 0: no hostile action against any player.")
            else -> targeting.check(target.ref(), caller, episodes, diedAt = states.of(target).lastDeathAt)
        }
        if (verdict is Verdict.Denied) {
            decisions.record(
                log, DecisionRecord.Kind.GUARD, "denied", name, reason = "${verdict.rule}: ${verdict.message}",
                conversationId = caller.conversationId, turnId = caller.turnId,
                detail = buildJsonObject { put("rule", verdict.rule) },
            )
            return verdict
        }
        episodes.open(target.uuid, caller.turnId, until)
        // Saved with the player, so that the episode can be rebuilt when they join after a restart.
        val now = clock.millis()
        val same = states.of(target).episode?.takeIf { it.until > now && it.turnId == caller.turnId }
        val episode = OpenEpisode(caller.turnId, maxOf(until, same?.until ?: until))
        states.update(target) { it.copy(episode = episode) }
        decisions.record(
            log, DecisionRecord.Kind.GUARD, "allowed", name, reason = "hostile action; the punishment runs until ${clockTime(clock, episode.until)}",
            conversationId = caller.conversationId, turnId = caller.turnId, level = Log.Level.DEBUG,
        )
        return verdict
    }

    override fun armDeathGuard(target: UUID, seconds: Int) = armDeathGuard(target, seconds, Peril.ANY)

    override fun armDeathGuard(target: UUID, seconds: Int, peril: Peril) {
        val server = services.server
        if (!server.isSameThread) {
            server.execute { armDeathGuard(target, seconds, peril) }
            return
        }
        server.playerList.getPlayer(target)?.let { death.arm(it, seconds, peril) }
    }

    /**
     * An effect left [player] (it ran out, milk, a removal): the end of the Narrator's levitation leaves
     * them in the air, so their fall is cushioned and covered by the death guard (M2 review, finding 1).
     * `ServerMobEffectEvents.AFTER_REMOVE`; server thread.
     */
    fun onEffectEnded(player: ServerPlayer, effect: MobEffectInstance) {
        if (player.isDeadOrDying || !effect.`is`(MobEffects.LEVITATION)) return
        val mine = effects.entryOf(player, effect.effect) ?: return
        death.cushionFall(player, mine.turnId)
    }

    override fun deathAttribution(victim: ServerPlayer, source: DamageSource): DeathAttribution = DeathAttribution(
        narrator = death.attributed(victim, source),
        keptInventory = DeathGuard.keepsInventory(victim) || victim.level().gameRules.get(GameRules.KEEP_INVENTORY),
    )

    override fun activeOn(uuid: UUID): List<ActiveEffect> {
        val state = states.snapshot(uuid)
        return buildList {
            state.effects.forEach { add(ActiveEffect("effect", EffectLedger.describe(it), it.expiresAt, it.harmful)) }
            state.modifiers.forEach { add(ActiveEffect("attribute", AttributeLedger.describe(it), it.expiresAt, it.harmful)) }
            state.confiscations.forEach { add(ActiveEffect("confiscation", it.what, it.returnAt, harmful = true)) }
            addAll(mobs.activeOn(uuid))
        }.sortedBy { it.until }
    }

    override fun deathsLeft(player: UUID): Int {
        if (isProtected(player)) return 0
        // The kills older than a played hour leave the state within a second (DeathGuard.tidy).
        return (caps().narratorKillsPerHour - states.snapshot(player).narratorKills.size).coerceAtLeast(0)
    }

    /**
     * Whether the Narrator's creatures and explosions leave [player] alone now ([CreatureShield]):
     * protected, dead, or died less than [TargetingPolicy.NO_PILE_ON_MINUTES] minutes ago (the death is
     * saved with the player: it holds across a reconnection). Server thread.
     */
    fun spares(player: Player): Boolean {
        if (player.isDeadOrDying || protection.isProtected(player.uuid, player.plainTextName)) return true
        val died = GuardStates.read(player).lastDeathAt ?: return false
        return clock.millis() - died < TargetingPolicy.NO_PILE_ON_MS
    }

    override fun setProtected(uuid: UUID, name: String, on: Boolean) {
        val changed = protection.set(uuid, name, on)
        decisions.record(
            log, DecisionRecord.Kind.OPERATOR, if (on) "protected" else "unprotected", name,
            reason = when {
                !changed -> "no change"
                on -> "the Narrator no longer acts against $name"
                else -> "$name is no longer protected by /narrator protect"
            },
        )
    }

    /** A player joined: their state comes back from their file and is put to work. Server thread. */
    fun onJoin(player: ServerPlayer) {
        val now = clock.millis()
        protection.seen(player.uuid, player.plainTextName)
        death.onJoin(player)
        attributes.reapply(player, now)
        effects.prune(player)
        val episode = states.of(player).episode
        when {
            episode == null -> Unit
            episode.until > now -> episodes.open(player.uuid, episode.turnId, episode.until)
            else -> states.update(player) { it.copy(episode = null) }
        }
        items.giveBack(player, now)
        states.refresh(player)
    }

    /** [player] is the new object of a player who respawned (or left the End), with the state copied from the old one. Server thread. */
    fun onRespawn(player: ServerPlayer) {
        val now = clock.millis()
        death.onRespawn(player)
        attributes.reapply(player, now)
        effects.prune(player)
        items.giveBack(player, now)
        states.refresh(player)
    }

    /** A player left: their state stays in their file. */
    fun onLeave(player: ServerPlayer) {
        states.forget(player.uuid)
    }

    /** [player] died (no totem saved them), whatever killed them: the death is saved with them, and counted if the Narrator's. Server thread. */
    fun onDeath(player: ServerPlayer, source: DamageSource) {
        states.update(player) { it.copy(lastDeathAt = clock.millis()) }
        death.afterDeath(player, source)
    }

    /** End of a server tick: once a second of wall-clock time, [check] what is due. Server thread. */
    fun tick(server: MinecraftServer) {
        val now = clock.millis()
        if (now - lastCheck < CHECK_INTERVAL_MS) return
        lastCheck = now
        check(server.playerList.players, now)
    }

    /**
     * Undoes what is due at [now] (epoch milliseconds): modifiers, confiscations, finished punishments and
     * death guards of [players], and the Narrator's creatures. Server thread.
     */
    fun check(players: List<ServerPlayer>, now: Long) {
        for (player in players) {
            try {
                attributes.expire(player, now)
                effects.prune(player)
                items.giveBack(player, now)
                death.tidy(player)
                if ((states.of(player).episode?.until ?: Long.MAX_VALUE) <= now) states.update(player) { it.copy(episode = null) }
                val died = states.of(player).lastDeathAt
                if (died != null && now - died >= TargetingPolicy.NO_PILE_ON_MS) states.update(player) { it.copy(lastDeathAt = null) }
            } catch (e: Exception) {
                log.error("the guards' check failed for ${player.plainTextName}", e)
            }
        }
        mobs.expire(now)
    }

    companion object {
        /** `/narrator protect`'s list, in `world/narrator2/`. */
        const val PROTECTED_FILE = "protected.json"

        private const val CHECK_INTERVAL_MS = 1_000L
    }
}

private val TIME_OF_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

/** [epochMs] as a time of day in [clock]'s zone, `21:44:10`, for decisions. */
internal fun clockTime(clock: Clock, epochMs: Long): String = TIME_OF_DAY.format(Instant.ofEpochMilli(epochMs).atZone(clock.zone))
