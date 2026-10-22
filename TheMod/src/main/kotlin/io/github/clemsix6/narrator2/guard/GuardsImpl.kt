package io.github.clemsix6.narrator2.guard

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.core.runOnServerThread
import io.github.clemsix6.narrator2.mechanics.MechanicsLimits
import io.github.clemsix6.narrator2.mechanics.PunishmentId
import io.github.clemsix6.narrator2.observer.ref
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
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
 * The engine's rules of a running server (M1_M2 §10, M4 §13): protection, chaos 0, the player's grace, a
 * duel, no piling on (a death, a rescue's shield), fairness and the categories' rest before any hostile
 * action, the death guard and its rescue, the ledgers that undo what the Narrator does to players, and the
 * favors it keeps (pardon, grace). Built once per server by `createGuards`; the hooks of `registerGuardHooks`
 * drive it: joins, respawns, deaths, entity loads, and a check once a second of what is due.
 *
 * @param clock the engine's wall clock; in-game tests give their own to move time.
 */
class GuardsImpl(private val services: NarratorServices, private val clock: Clock = services.clock) : Guards {
    private val log = Log.guard
    private val decisions = Decisions(services.trace, clock)

    /** The guards' state of the players, with a copy readable from any thread. */
    val states = GuardStates()

    private val episodes = Episodes(clock)
    private val shields = Shields(states, clock)
    private val targeting = TargetingPolicy(services.players, clock, ::caps)
    private val protection = ProtectedPlayers(
        services.dataDir.resolve(PROTECTED_FILE),
        { services.settings.get().config.safety.protectedPlayers },
        Util.ioPool(),
    )

    override val attributes = AttributeLedger(states, decisions, clock)
    override val effects = EffectLedger(states, clock)
    val death = DeathGuard(states, ::caps, decisions, clock, attributes, effects, shields)
    override val items = ItemLedger(states, decisions, clock) { services.settings.get().ui }
    override val mobs = NarratorMobs(items)
    override val commands = CommandPolicy(
        extraAllowed = { services.settings.get().config.safety.extraAllowedCommands },
        extra = { services.settings.get().config.safety.extraForbiddenCommands },
    )
    override val spawns: SpawnPolicy get() = SpawnPolicy

    @Volatile
    override var isDueling: (UUID) -> Boolean = { false }

    /** When [tick] last checked what is due, epoch milliseconds. Server thread. */
    private var lastCheck = 0L

    override fun isProtected(uuid: UUID): Boolean = protection.isProtected(uuid)

    override fun caps(): Caps = Caps.of(services.settings.get().config.chaos)

    /**
     * In order: protection, chaos 0, the player's grace, a duel, then [TargetingPolicy] (no piling on a recent
     * death or rescue, one punishment at a time and one `punish` per act, fairness, the categories' rest).
     * Allowed: the act's episode opens or grows, and the strike of [category] is recorded. Every refusal is a
     * `guard denied` decision.
     */
    override fun hostile(target: ServerPlayer, caller: Caller, until: Long, category: PunishmentCategory?, punishment: PunishmentId?): Verdict {
        val verdict = verdict(target, caller, category, punishment)
        if (verdict is Verdict.Denied) return verdict
        val now = clock.millis()
        episodes.open(target.uuid, caller.act, until, category, punishment)
        // Saved with the player, so that the episode can be rebuilt when they join after a restart.
        val same = states.of(target).episode?.takeIf { it.until > now && caller.act != null && it.act == caller.act }
        val episode = OpenEpisode(caller.act, maxOf(until, same?.until ?: until))
        states.update(target) { it.copy(episode = episode) }
        decisions.record(
            log, DecisionRecord.Kind.GUARD, "allowed", target.plainTextName,
            reason = "hostile action${category?.let { " (${it.id})" }.orEmpty()}${punishment?.let { ", punish ${it.id}" }.orEmpty()}; " +
                "the punishment runs until ${clockTime(clock, episode.until)}",
            conversationId = caller.conversationId, turnId = caller.turnId,
            detail = buildJsonObject {
                put("act", caller.act)
                put("category", category?.id)
                punishment?.let { put("punishment", it.id) }
            },
        )
        return verdict
    }

    override fun vet(target: ServerPlayer, caller: Caller, category: PunishmentCategory?): Verdict = verdict(target, caller, category, punishment = null)

    /** [hostile]'s verdict, changing nothing but the `guard denied` decision of a refusal. */
    private fun verdict(target: ServerPlayer, caller: Caller, category: PunishmentCategory?, punishment: PunishmentId?): Verdict {
        val name = target.plainTextName
        val now = clock.millis()
        val state = states.of(target)
        val graceUntil = state.graceUntil?.takeIf { it > now }
        val verdict = when {
            protection.isProtected(target.uuid, name) ->
                Verdict.Denied(Rules.PROTECTED, "${ModelText.name(name)} is protected by the operators; the Narrator does nothing hostile to them.")
            !caps().hostileAllowed ->
                Verdict.Denied(Rules.CHAOS_0, "the chaos level is 0: no hostile action against any player.")
            graceUntil != null ->
                Verdict.Denied(
                    Rules.GRACE,
                    "${ModelText.name(name)} is under your grace until ${clockTime(clock, graceUntil)} (in ${ToolText.wait(graceUntil - now)}); " +
                        "nothing hostile against them until then.",
                )
            isDueling(target.uuid) ->
                Verdict.Denied(Rules.DUEL, "${ModelText.name(name)} is fighting a duel; nothing hostile against a duelist while the duel runs.")
            else -> targeting.check(
                target.ref(), caller, episodes, diedAt = state.lastDeathAt, category = category, shield = shields.active(target), punishment = punishment,
            )
        }
        if (verdict is Verdict.Denied) {
            decisions.record(
                log, DecisionRecord.Kind.GUARD, "denied", name, reason = "${verdict.rule}: ${verdict.message}",
                conversationId = caller.conversationId, turnId = caller.turnId,
                detail = buildJsonObject {
                    put("rule", verdict.rule)
                    put("act", caller.act)
                    put("category", category?.id)
                },
            )
        }
        return verdict
    }

    /** Puts the player's punishments back as before the act's `punish` ([Episodes.withdraw]), the saved episode with them. */
    override fun withdraw(player: ServerPlayer, caller: Caller) {
        if (!episodes.withdraw(player.uuid, caller.act)) return
        val running = episodes.active(player.uuid)
        states.update(player) { it.copy(episode = running?.let { episode -> OpenEpisode(episode.act, episode.until) }) }
        decisions.record(
            log, DecisionRecord.Kind.GUARD, "withdrawn", player.plainTextName,
            reason = "the punish of this act did nothing at all: not counted, its categories not resting",
            conversationId = caller.conversationId, turnId = caller.turnId,
            detail = buildJsonObject { put("act", caller.act) },
        )
    }

    override fun shield(player: ServerPlayer, until: Long, reason: String) {
        val shield = shields.shield(player, until, reason)
        decisions.record(
            log, DecisionRecord.Kind.GUARD, "shielded", player.plainTextName,
            reason = "${shield.reason}: nothing hostile against them, the Narrator's creatures and explosions spare them, until ${clockTime(clock, shield.until)}",
            detail = buildJsonObject { put("until", shield.until) },
        )
    }

    override fun jokerGiven(player: ServerPlayer, at: Long) {
        states.update(player) { it.copy(jokersGivenAt = it.jokersGivenAt + at) }
    }

    override fun armDeathGuard(target: UUID, seconds: Int) = armDeathGuard(target, seconds, Peril.ANY)

    override fun armDeathGuard(target: UUID, seconds: Int, peril: Peril) {
        val server = services.server
        // From another thread, queued on the server's; dropped once the server has stopped, when nobody can die.
        val armed = server.runOnServerThread { server.playerList.getPlayer(target)?.let { death.arm(it, seconds, peril) } }
        if (!armed) log.debug { "death guard of $target not armed: the server has stopped" }
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

    /**
     * Effects, modifiers, what the ledger holds (a confiscation is harmful; a stake or what is owed is shown apart,
     * under its purpose, never harmful, with no deadline: [Escrow.active]), creatures and traps, the soonest to end
     * first. An offering is not: it is the player's gift waiting for an answer, not the Narrator's doing, and the
     * situation's `Offerings` line tells it (M4-24, finding 2: an offering among the active effects read like a
     * confiscation with a deadline).
     */
    override fun activeOn(uuid: UUID): List<ActiveEffect> {
        val state = states.snapshot(uuid)
        val now = clock.millis()
        return buildList {
            state.effects.forEach { add(ActiveEffect("effect", EffectLedger.describe(it), it.expiresAt, it.harmful)) }
            state.modifiers.forEach { add(ActiveEffect("attribute", AttributeLedger.describe(it), it.expiresAt, it.harmful)) }
            state.confiscations.filter { it.purpose != LedgerPurpose.OFFERING }.forEach { add(Escrow.active(it, now) { winner -> services.players.get(winner)?.ref?.name }) }
            addAll(mobs.activeOn(uuid))
            addAll(BlockRestorer.activeOn(uuid))
        }.sortedBy { it.until ?: Long.MAX_VALUE }
    }

    override fun deathsLeft(player: UUID): Int {
        if (isProtected(player)) return 0
        // The kills older than a played hour leave the state within a second (DeathGuard.tidy).
        return (caps().narratorKillsPerHour - states.snapshot(player).narratorKills.size).coerceAtLeast(0)
    }

    /**
     * In order: the Narrator's harmful effects lifted (a levitation lifted this way cushions the fall it
     * leaves), its harmful modifiers reset, its confiscations still held made due now and given back (what
     * does not fit waits for room as usual; stakes, offerings and what is owed are not the Narrator's doing
     * and stay), its harmful creatures summoned for the player sent away, the traps holding them taken away,
     * and the episode closed. The categories' rest and the hour's punishments are history, and stay.
     */
    override fun pardon(player: ServerPlayer, caller: Caller): List<String> {
        val now = clock.millis()
        val lifted = mutableListOf<String>()
        val effectsLifted = effects.removeHarmful(player)
        effectsLifted.forEach { lifted += "${EffectLedger.describe(it)} lifted" }
        if (effectsLifted.any { it.effect == MobEffects.LEVITATION.registeredName }) death.cushionFall(player, caller.turnId)
        attributes.resetHarmful(player).forEach { lifted += "${it.id} reset" }
        val confiscations = states.of(player).confiscations.filter { it.purpose == LedgerPurpose.CONFISCATION && it.returnAt > now }
        if (confiscations.isNotEmpty()) {
            val ids = confiscations.map { it.id }.toSet()
            states.update(player) { state -> state.copy(confiscations = state.confiscations.map { if (it.id in ids) it.copy(returnAt = now) else it }) }
            items.giveBack(player, now)
            confiscations.forEach { lifted += "${it.what} returned" }
        }
        mobs.retireFor(player.uuid, now).forEach { lifted += "$it sent away" }
        BlockRestorer.restoreFor(player.uuid).takeIf { it > 0 }?.let { lifted += "$it trap blocks taken away" }
        val episode = episodes.close(player.uuid)
        val saved = states.of(player).episode
        if (saved != null) states.update(player) { it.copy(episode = null) }
        if (lifted.isEmpty() && (episode != null || (saved?.until ?: 0L) > now)) lifted += "punishment ended"
        if (lifted.isNotEmpty()) {
            decisions.record(
                log, DecisionRecord.Kind.GUARD, "pardoned", player.plainTextName, reason = lifted.joinToString(),
                conversationId = caller.conversationId, turnId = caller.turnId,
                detail = buildJsonObject {
                    put("act", caller.act)
                    put("lifted", JsonArray(lifted.map(::JsonPrimitive)))
                },
            )
        }
        return lifted
    }

    /**
     * Refused ([Rules.FAVOR_LIMIT]) while a grace runs and until [MechanicsLimits.GRACE_GAP_MINUTES] after its
     * end; at most [MechanicsLimits.GRACE_MAX_MINUTES] long. Saved with the player.
     */
    override fun grace(player: ServerPlayer, until: Long, caller: Caller): Verdict {
        val now = clock.millis()
        val name = ModelText.name(player.plainTextName)
        val state = states.of(player)
        val last = state.lastGraceEnd ?: state.graceUntil
        val next = last?.let { it + GRACE_GAP_MS }
        val end = minOf(until, now + GRACE_MAX_MS)
        val verdict = when {
            last != null && next != null && next > now && last > now -> Verdict.Denied(
                Rules.FAVOR_LIMIT,
                "$name is under your grace until ${clockTime(clock, last)} (in ${ToolText.wait(last - now)}); " +
                    "the next grace is allowed at ${clockTime(clock, next)} (in ${ToolText.wait(next - now)}).",
            )
            last != null && next != null && next > now -> Verdict.Denied(
                Rules.FAVOR_LIMIT,
                "$name's last grace ended ${ToolText.duration(now - last)} ago; the next one is allowed at ${clockTime(clock, next)} (in ${ToolText.wait(next - now)}).",
            )
            end <= now -> Verdict.Denied(Rules.FAVOR_LIMIT, "a grace must end in the future (asked until ${clockTime(clock, until)}).")
            else -> Verdict.Allowed
        }
        if (verdict is Verdict.Denied) {
            decisions.record(
                log, DecisionRecord.Kind.GUARD, "denied", player.plainTextName, reason = "${verdict.rule}: ${verdict.message}",
                conversationId = caller.conversationId, turnId = caller.turnId,
                detail = buildJsonObject { put("rule", verdict.rule) },
            )
            return verdict
        }
        states.update(player) { it.copy(graceUntil = end, lastGraceEnd = end) }
        decisions.record(
            log, DecisionRecord.Kind.GUARD, "graced", player.plainTextName,
            reason = "under the Narrator's grace until ${clockTime(clock, end)} (${ToolText.duration(end - now)}): nothing hostile, its creatures and explosions spare them",
            conversationId = caller.conversationId, turnId = caller.turnId,
            detail = buildJsonObject {
                put("act", caller.act)
                put("until", end)
            },
        )
        return verdict
    }

    /**
     * Anything harmful of the Narrator's on [player] that a pardon lifts: an effect, a modifier, a confiscation
     * still held, a creature, a trap. A bare episode is not: what it was made of is done (a bolt, a shuffle).
     */
    override fun isPunished(player: ServerPlayer): Boolean {
        val now = clock.millis()
        val state = states.of(player)
        return effects.on(player).any { it.harmful } ||
            state.modifiers.any { it.harmful && it.expiresAt > now } ||
            state.confiscations.any { it.purpose == LedgerPurpose.CONFISCATION && it.returnAt > now } ||
            mobs.activeOn(player.uuid).any { it.harmful } ||
            BlockRestorer.activeOn(player.uuid).isNotEmpty()
    }

    override fun rescue(player: ServerPlayer, why: String, inPlace: Boolean) = death.rescue(player, why, inPlace)

    /**
     * `ALLOW_DEATH` of [player]: whether the death may happen. A blow of a punishment's creature never kills
     * (M4-POST: a punishment of the catalogue is a gag, not an execution; M4-LEFTOVERS: nor does a hostile creature
     * of `spawn`): the creatures bound to them ([CreatureShield.boundTo]) are sent away, and the death is refused and
     * turned into a rescue where they stand ([DeathGuard.rescue] in place: shielded 5 minutes, nothing lost, nothing
     * counted). Any other death goes to the death guard. A champion's fatal blow (M7-FIX) kills, as any death the
     * Narrator causes, while its quota lasts ([DeathGuard.allowDeath]: inventory kept, the death counted); beyond the
     * quota it is the rescue above. Server thread.
     */
    fun allowDeath(player: ServerPlayer, source: DamageSource): Boolean {
        val creature = CreatureShield.punishmentOf(source) ?: return death.allowDeath(player, source)
        if (CreatureShield.isChampions(source) && death.quotaLeft(player) > 0) return death.allowDeath(player, source)
        val gone = mobs.retirePunishments(player.uuid, clock.millis(), "a fatal blow refused")
        val sent = if (gone.isEmpty()) "" else ", ${gone.joinToString()} sent away"
        val act = if (creature.punishment == NarratorMob.SPAWN) NarratorMob.SPAWN else "punish ${creature.punishment}"
        death.rescue(player, "$act: a fatal blow of its creatures$sent; the Narrator's creatures never kill", inPlace = true)
        return false
    }

    override fun pacing(players: List<UUID>, now: Long): List<PlayerPacing> {
        val rest = caps().categoryCooldownMinutes * 60_000L
        return players.map { uuid ->
            val state = states.snapshot(uuid)
            PlayerPacing(
                player = uuid,
                punishedThisHour = episodes.recentPunishments(uuid, now).size,
                perHour = TargetingPolicy.MAX_PUNISHMENTS_PER_HOUR,
                resting = episodes.resting(uuid, rest, now),
                graceUntil = state.graceUntil?.takeIf { it > now },
                // Past its deadline, an offering only waits for room to go back: nobody can take it any more.
                offerings = state.confiscations.filter { it.purpose == LedgerPurpose.OFFERING && it.returnAt > now },
            )
        }
    }

    /**
     * Whether the Narrator's creatures and explosions leave [player] alone now ([CreatureShield]):
     * protected, dead, died less than [TargetingPolicy.NO_PILE_ON_MINUTES] minutes ago, under the Narrator's grace,
     * shielded after a rescue or a refused death ([shield]; all three saved with the player: they hold across a
     * reconnection and a restart), or fighting a duel ([isDueling]). Server thread.
     */
    fun spares(player: Player): Boolean {
        if (player.isDeadOrDying || protection.isProtected(player.uuid, player.plainTextName)) return true
        if (shields.active(player) != null || isDueling(player.uuid)) return true
        val state = GuardStates.read(player)
        val now = clock.millis()
        if ((state.graceUntil ?: 0L) > now) return true
        val died = state.lastDeathAt ?: return false
        return now - died < TargetingPolicy.NO_PILE_ON_MS
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
            episode.until > now -> episodes.open(player.uuid, episode.act, episode.until)
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

    /** A player left: their state stays in their file, and the creatures of their punishments go (M4-POST). */
    fun onLeave(player: ServerPlayer) {
        mobs.retirePunishments(player.uuid, clock.millis(), "left")
        states.forget(player.uuid)
    }

    /**
     * [player] died (no totem saved them), whatever killed them: the creatures of their punishments go (M4-POST),
     * and the death is saved with them, counted if the Narrator's, and explained by a `death_guard died` decision
     * ([DeathReport]). Server thread.
     */
    fun onDeath(player: ServerPlayer, source: DamageSource) {
        val now = clock.millis()
        mobs.retirePunishments(player.uuid, now, "died")
        states.update(player) { it.copy(lastDeathAt = now) }
        death.afterDeath(player, source)
        explain(player, source, now)
    }

    /**
     * Writes the `death_guard died` decision of [player]'s death at [at] (M2 review, finding 18): the
     * guard's state is read here, on the server thread; the Narrator's actions of the minute before are
     * read from the Journal off this thread, once the tool that may have killed has written its row.
     */
    private fun explain(player: ServerPlayer, source: DamageSource, at: Long) {
        val state = states.of(player)
        val playTime = DeathGuard.playTime(player)
        val cap = caps().narratorKillsPerHour
        val name = player.plainTextName
        val shielded = protection.isProtected(player.uuid, name)
        val report = DeathReport(
            player = name,
            at = at,
            cause = source.typeHolder().registeredName,
            narrator = DeathGuard.keepsInventory(player),
            keptInventory = deathAttribution(player, source).keptInventory,
            isProtected = shielded,
            playTime = playTime,
            armedUntil = state.armedUntil,
            deathsLeft = if (shielded) 0 else DeathGuard.quotaLeft(state.narratorKills, playTime, cap),
            deathsPerHour = cap,
        )
        val journal = services.journal
        services.scope.launch {
            val explained = try {
                delay(DEATH_REPORT_DELAY_MS)
                withTimeoutOrNull(DEATH_REPORT_BARRIER_MS) { journal.barrier() }
                val actions = journal.read(DEATH_REPORT_READ_MS) { DeathReport.actionsOn(it, name, at - DeathReport.WINDOW_MS, at) }
                report.copy(actions = actions)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report.copy(actionsUnknown = e.message ?: e.javaClass.simpleName)
            }
            decisions.record(log, DecisionRecord.Kind.DEATH_GUARD, "died", name, reason = explained.reason(), detail = explained.detail())
        }
    }

    /**
     * End of a server tick: the players the death guard rescued during it are put out again, and once a
     * second of wall-clock time, [check] what is due. Server thread.
     */
    fun tick(server: MinecraftServer) {
        death.afterTick()
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
                shields.tidy(player)
                tidyFavors(player, now)
            } catch (e: Exception) {
                log.error("the guards' check failed for ${player.plainTextName}", e)
            }
        }
        mobs.expire(now)
    }

    /**
     * Forgets the favors' traces that no longer limit anything: a grace that ended (its end stays in
     * `lastGraceEnd` until a new one is allowed), and the jokers given more than a day ago. The saved state
     * stays small.
     */
    private fun tidyFavors(player: ServerPlayer, now: Long) {
        val state = states.of(player)
        val tidied = state.copy(
            graceUntil = state.graceUntil?.takeIf { it > now },
            lastGraceEnd = state.lastGraceEnd?.takeIf { now - it < GRACE_GAP_MS },
            jokersGivenAt = state.jokersGivenAt.filter { now - it < DAY_MS },
        )
        if (tidied != state) states.update(player) { tidied }
    }

    companion object {
        /** `/narrator protect`'s list, in `world/narrator2/`. */
        const val PROTECTED_FILE = "protected.json"

        /** A grace lasts this long at most; a new one comes this long after the last one ended (M4 §7.1). */
        const val GRACE_MAX_MS = MechanicsLimits.GRACE_MAX_MINUTES * 60_000L
        const val GRACE_GAP_MS = MechanicsLimits.GRACE_GAP_MINUTES * 60_000L

        /** How long a joker given counts against `MechanicsLimits.JOKERS_PER_DAY`. */
        const val DAY_MS = 24 * 3_600_000L

        private const val CHECK_INTERVAL_MS = 1_000L

        /** A death is explained this long after it: the tool that killed has returned and written its row. */
        private const val DEATH_REPORT_DELAY_MS = 2_000L
        private const val DEATH_REPORT_BARRIER_MS = 1_000L
        private const val DEATH_REPORT_READ_MS = 5_000L
    }
}

private val TIME_OF_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

/** [epochMs] as a time of day in [clock]'s zone, `21:44:10`, for decisions. */
internal fun clockTime(clock: Clock, epochMs: Long): String = TIME_OF_DAY.format(Instant.ofEpochMilli(epochMs).atZone(clock.zone))
