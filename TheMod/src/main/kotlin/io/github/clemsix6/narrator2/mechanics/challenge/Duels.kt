package io.github.clemsix6.narrator2.mechanics.challenge

import io.github.clemsix6.narrator2.config.MechanicsConfig
import io.github.clemsix6.narrator2.config.UiTexts
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.core.onServerThread
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.director.MechanicSignal
import io.github.clemsix6.narrator2.mechanics.ArenaSpec
import io.github.clemsix6.narrator2.mechanics.ChallengeOutcome
import io.github.clemsix6.narrator2.mechanics.ChallengeState
import io.github.clemsix6.narrator2.mechanics.ChallengeStatus
import io.github.clemsix6.narrator2.mechanics.ChallengeView
import io.github.clemsix6.narrator2.mechanics.DuelSpec
import io.github.clemsix6.narrator2.mechanics.EngineActions
import io.github.clemsix6.narrator2.mechanics.MechanicPart
import io.github.clemsix6.narrator2.mechanics.MechanicRejected
import io.github.clemsix6.narrator2.mechanics.MechanicsLimits
import io.github.clemsix6.narrator2.mechanics.MechanicsState
import io.github.clemsix6.narrator2.mechanics.Participant
import io.github.clemsix6.narrator2.mechanics.SavedPosition
import io.github.clemsix6.narrator2.mechanics.StateStore
import io.github.clemsix6.narrator2.observer.typeId
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.CanonDraft
import io.github.clemsix6.narrator2.memory.CanonKind
import io.github.clemsix6.narrator2.memory.CanonMechanic
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.memory.MemoryLimits
import io.github.clemsix6.narrator2.memory.MemoryRejected
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.tool.action.Teleports
import io.github.clemsix6.narrator2.tool.speech.Speech
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.fabricmc.fabric.api.entity.FakePlayer
import net.minecraft.ChatFormatting
import net.minecraft.core.Holder
import net.minecraft.core.SectionPos
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.damagesource.DamageTypes
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.effect.MobEffectCategory
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Relative
import net.minecraft.world.phys.Vec3
import java.time.Clock
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor

/**
 * The duels (M4 §5.5): the only fight between players the engine organizes, and only once both accepted. A
 * [MechanicPart] that lives in the same state as the challenges ([Challenges] renders their situation line and
 * ends them through its common [Challenges.finish]).
 *
 * - **Proposal** ([propose], `propose_duel`): two different players online, PvP on in their world (the game rule
 *   `pvp`, which the Narrator never changes), neither already in a duel, room for one more ([Challenges.room]); an
 *   arena in their own dimension with safe ground on both sides. Then the canon row (`challenge`, mechanic
 *   `duel`), the state (`INVITED`) and a clickable invitation to each.
 * - **Answers** ([answer], `/duel accept|decline`): an acceptance is acknowledged and wakes nobody; a refusal, or
 *   an invitation that runs out, cancels it (a `DUEL` event without priority).
 * - **Start** (both accepted): with an arena, where each stood is kept and both are taken face to face
 *   [MechanicsLimits.ARENA_DISTANCE] blocks from its point (its chunks loaded off the server thread first); a
 *   title, the boss bars (drawn from the state), a `DUEL` event without priority.
 * - **Nobody dies in a duel** ([allowDeath], before the guards' own `ALLOW_DEATH`): every death of a duelist is
 *   refused, but an operator's `/kill`. A blow of the opponent (or of what they shot) leaves the loser at 1
 *   health where they stand, put out, freed of the harmful effects of the fight, under resistance V for
 *   [MechanicsLimits.DUEL_RESISTANCE_SECONDS]; any other cause is the death guard's rescue (`Guards.rescue`). The
 *   duelist loses. Nothing is dropped, nothing counted. For [MechanicsLimits.DUEL_RESISTANCE_SECONDS] after the
 *   end, however it ended, a death of either duelist is refused the same way: what the fight left behind (a fire,
 *   a last blow) kills nobody either.
 * - **End**: the opponent's blow, a rescue or a departure (won), the deadline (lost: a draw), a refusal, an
 *   invitation run out, an operator's `/kill` (cancelled); or the Director or an operator ([end]). However the
 *   duel left the state, both duelists are put out and freed of the fight's harmful effects in the next tick
 *   (`aftercare`), and each one with a place to go back to ([MechanicsState.returns]: the arena's) is put back
 *   there on safe ground once alive and online, within a second.
 *
 * Server thread for the hooks and [answer]; [propose] and [end] from coroutines.
 *
 * @param stakes checks the duel's reward when it is proposed (M4-17); [Challenges.finish] pays it.
 * @param clock the engine's clock: a GameTest moves it on.
 */
class Duels(
    private val services: NarratorServices,
    private val store: StateStore,
    private val challenges: Challenges,
    private val stakes: StakePayer = StakePayer.NONE,
    private val clock: Clock = services.clock,
) : MechanicPart {
    private val log = Log.mechanics
    private val decisions get() = Decisions(services.trace, clock)
    private val config: MechanicsConfig get() = services.settings.get().config.mechanics
    private val ui: UiTexts get() = services.settings.get().ui

    /** One proposal at a time: the pace and the duelists are checked again under it, just before the state is written. */
    private val proposing = Mutex()

    /** Duels whose two duelists accepted and whose arena loads before they start. Server thread. */
    private val starting = HashSet<Long>()

    /** Each duelist of a duel seen running, until its aftercare is over. Server thread. */
    private val fighting = HashMap<UUID, Fight>()

    @Volatile
    private var stopped = false

    /** A duelist of duel [duel] against [opponent]; [until]: the end of the aftercare, null while the duel runs. */
    private class Fight(val duel: Long, val opponent: UUID, var until: Long? = null)

    /** Where a duelist starts in the arena, and where they look. */
    private class Spot(val at: Vec3, val yaw: Float)

    // ── Proposal (ChallengeControl.proposeDuel) ──

    /**
     * Proposes [spec] for [caller] (M4 §5.5): validates it, writes its canon row, then invites both players.
     * Throws [MechanicRejected] with the fix: two players online and different, PvP off (`pvp_off`), a duelist
     * already in a duel, the pace (`challenge_limit`), an arena out of their dimension or without safe ground, a
     * reward its tool refuses, a text the memory refuses. Never on the server thread.
     */
    suspend fun propose(spec: DuelSpec, caller: Caller): ChallengeView {
        shape(spec)?.let { throw invalid(it) }
        return proposing.withLock {
            val server = services.server
            val (duelists, settled) = server.onServerThread { prepare(spec, server) }
            settled.arena?.let { checkArena(it, duelists) }
            stakes.check(settled.reward, null, caller)
            services.journal.barrier() // the players' join rows, which the canon checks
            val ref = writeCanon(settled, duelists, caller)
            val refusal = server.onServerThread { insert(ref, settled, duelists, caller) }
            refusal?.let { cancelRefused(ref, it) }
            val duel = store.snapshot.challenge(ref.id) ?: throw IllegalStateException("$ref left the state as it was proposed")
            view(duel)
        }
    }

    /** What [spec] gets wrong before anything is looked up: the same player twice, an empty title. */
    private fun shape(spec: DuelSpec): String? {
        val (first, second) = spec.players.map { it.trim() }
        return when {
            first.equals(second, ignoreCase = true) -> "a duel needs two different players (got ${ModelText.name(first)} twice)"
            Speech.clean(spec.title).isEmpty() -> "title has nothing to show once formatting codes and invisible characters are removed"
            else -> null
        }
    }

    /**
     * The duelists of [spec], online, and [spec] with its arena's dimension set to theirs; throws
     * [MechanicRejected] for a player not online or already in a duel, PvP off, the pace, an arena outside
     * their dimension or the world. Server thread.
     */
    private fun prepare(spec: DuelSpec, server: MinecraftServer): Pair<List<Participant>, DuelSpec> {
        val online = server.playerList.players.filter { it !is FakePlayer }
        val found = spec.players.map { name -> name to online.firstOrNull { it.plainTextName.equals(name.trim(), ignoreCase = true) } }
        val missing = found.filter { it.second == null }.map { ModelText.name(it.first) }
        if (missing.isNotEmpty()) {
            val who = if (missing.size == 1) "${missing.single()} is not online" else "${missing.joinToString(" and ")} are not online"
            throw invalid("$who; online: ${online.joinToString { ModelText.name(it.plainTextName) }.ifEmpty { "nobody" }}")
        }
        val players = found.map { it.second!! }
        val duelists = players.map { Participant(it.uuid.toString(), it.plainTextName) }
        val state = store.get()
        busy(state, duelists)?.let { throw it }
        if (players.any { !it.level().isPvpAllowed }) throw MechanicRejected(PVP_OFF, "PvP is off in this world (game rule pvp): no duel")
        challenges.room(state, clock.millis(), duel = true)?.let { throw it }
        val arena = spec.arena ?: return duelists to spec
        return duelists to spec.copy(arena = arena(arena, players))
    }

    /** A duelist of [duelists] already in a duel of [state], invited or fighting: the refusal. */
    private fun busy(state: MechanicsState, duelists: List<Participant>): MechanicRejected? {
        for (duelist in duelists) {
            val other = state.challenges.firstOrNull { it.isDuel && it.participant(duelist.playerId) != null } ?: continue
            return invalid("${name(duelist)} is already in duel ${other.ref}; one duel at a time")
        }
        return null
    }

    /** [arena] checked against [players]' dimension (both in it, the arena too, inside the world), with that dimension. Server thread. */
    private fun arena(arena: ArenaSpec, players: List<ServerPlayer>): ArenaSpec {
        val level = players[0].level()
        val dimension = level.dimension().identifier().toString()
        val shown = ToolText.dimension(dimension)
        players.firstOrNull { it.level() !== level }?.let { other ->
            throw invalid(
                "${players[0].shownName} is in $shown and ${other.shownName} in ${ToolText.dimension(other.level().dimension().identifier().toString())}: " +
                    "an arena needs both in the same dimension (no journey is forced on anyone); leave arena out to let them fight where they stand",
            )
        }
        arena.dimension?.let { asked ->
            if (ToolText.dimensionId(asked) != dimension) throw invalid("the arena must be in the duelists' dimension, $shown (got ${ModelText.escape(asked)})")
        }
        if (!level.isInsideBuildHeight(arena.y) || !level.worldBorder.isWithinBounds(arena.x.toDouble(), arena.z.toDouble())) {
            throw invalid("the arena ${where(arena)} is outside the world (height ${level.minY} to ${level.maxY}, inside the world border)")
        }
        return arena.copy(dimension = dimension)
    }

    /** [arena]'s chunks loaded (off the server thread), then safe ground on both sides of its point; throws [MechanicRejected] without. */
    private suspend fun checkArena(arena: ArenaSpec, duelists: List<Participant>) {
        val server = services.server
        val loading = server.onServerThread {
            val level = levelOf(server, arena) ?: throw invalid("the arena's dimension ${arena.dimension} no longer exists")
            if (loaded(level, arena)) null else Teleports.requestLoad(level, arena.x, arena.z)
        }
        if (loading != null && !Teleports.awaitLoaded(loading)) throw invalid("the arena ${where(arena)} took too long to load; try again, or nearer the players")
        val found = server.onServerThread {
            val level = levelOf(server, arena)
            val players = duelists.map { server.playerList.getPlayer(it.playerId) ?: throw invalid("${name(it)} left the game") }
            level != null && loaded(level, arena) && spots(level, arena, players) != null
        }
        if (!found) {
            throw invalid(
                "no safe ground ${MechanicsLimits.ARENA_DISTANCE} blocks west and east of the arena ${where(arena)}: pick a flat open spot (the scout's survey finds one)",
            )
        }
    }

    /** Writes [spec]'s canon row as the Director (M4 §3.3): its ref. A text the memory refuses is [MechanicRejected]. */
    private suspend fun writeCanon(spec: DuelSpec, duelists: List<Participant>, caller: Caller): Ref {
        val draft = CanonDraft(
            kind = CanonKind.CHALLENGE,
            statement = spec.statement,
            quote = spec.quote,
            players = duelists.map { it.playerId },
            stakes = ChallengeLines.stakes(spec.reward, null, spec.bets),
            importance = spec.importance,
            mechanic = CanonMechanic.DUEL,
        )
        return try {
            services.memory.store.addCanon(draft, Author(Source.DIRECTOR, turnId = caller.turnId, conversationId = caller.conversationId)).row.ref
        } catch (e: MemoryRejected) {
            throw MechanicRejected(e.rule, e.message.orEmpty())
        }
    }

    /** Puts duel [ref] in the state and invites both, unless the pace or a duelist refuses it now: the refusal. Server thread. */
    private fun insert(ref: Ref, spec: DuelSpec, duelists: List<Participant>, caller: Caller): MechanicRejected? {
        val now = clock.millis()
        val state = store.get()
        challenges.room(state, now, duel = true)?.let { return it }
        busy(state, duelists)?.let { return it }
        val players = duelists.map { services.server.playerList.getPlayer(it.playerId) ?: return invalid("${name(it)} left the game") }
        val duel = ChallengeState(
            id = ref.id,
            duel = spec,
            status = ChallengeStatus.INVITED,
            createdAt = now,
            startedAt = null,
            endsAt = now + config.duelInviteSeconds * 1_000L,
            participants = duelists,
            turnId = caller.turnId,
            conversationId = caller.conversationId,
        )
        store.update { it.copy(challenges = it.challenges + duel) }
        decisions.record(
            log, DecisionRecord.Kind.MECHANIC, INVITED, "$ref", reason = DuelLines.invited(duel, clock),
            conversationId = EngineActions.conversationOf(ref), turnId = caller.turnId,
        )
        players.forEachIndexed { i, player -> invite(player, duel, duelists[1 - i]) }
        return null
    }

    /** The canon row of a duel refused once written: cancelled by the engine, and the refusal thrown. */
    private suspend fun cancelRefused(ref: Ref, refusal: MechanicRejected): Nothing {
        try {
            val note = ModelText.cut("refused as it was proposed: ${refusal.message}", MemoryLimits.RESOLUTION)
            services.memory.store.resolveCanon(ref, CanonStatus.CANCELLED, note, emptyList(), Author(Source.ENGINE, conversationId = EngineActions.conversationOf(ref)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("$ref was refused as it was proposed, and its canon row could not be cancelled", e)
        }
        throw refusal
    }

    /** `duel.invite` to [player], with its two buttons, `/duel accept C-52` and `/duel decline C-52`. */
    private fun invite(player: ServerPlayer, duel: ChallengeState, opponent: Participant) {
        val texts = ui
        val spec = duel.duel!!
        val where = spec.arena?.let { texts.format("duel.where.arena", "x" to it.x, "y" to it.y, "z" to it.z) } ?: texts["duel.where.here"]
        val text = texts.format("duel.invite", "opponent" to opponent.name, "title" to Speech.clean(spec.title), "seconds" to config.duelInviteSeconds, "where" to where)
        val line = Component.empty()
            .append(Speech.chatLine(texts, text, private = true))
            .append(" ").append(button(texts["duel.accept_button"], "/duel accept ${duel.ref}", ChatFormatting.GREEN))
            .append(" ").append(button(texts["duel.decline_button"], "/duel decline ${duel.ref}", ChatFormatting.RED))
        player.sendSystemMessage(line)
    }

    private fun button(text: String, command: String, color: ChatFormatting): Component =
        Component.literal(text).withStyle { it.withColor(color).withBold(true).withClickEvent(ClickEvent.RunCommand(command)) }

    // ── Answers (ChallengeControl.answerDuel) ──

    /**
     * `/duel accept|decline <id>` of [player] (M4 §5.5): what they are told. An acceptance is acknowledged (to
     * the opponent too) and wakes nobody; once both accepted, the duel starts. A refusal cancels it at once. A
     * duel that is not one of their invitations: `duel.not_invited`; no duel [ref]: `command.unknown_challenge`.
     * Server thread.
     */
    fun answer(player: ServerPlayer, ref: Ref, accept: Boolean): Component {
        val texts = ui
        val duel = store.get().challenge(ref.id)?.takeIf { it.isDuel && ref.kind == RefKind.CANON }
            ?: return private(texts.format("command.unknown_challenge", "id" to ref))
        val me = duel.participant(player.uuid)
        if (duel.status != ChallengeStatus.INVITED || me == null) return private(texts["duel.not_invited"])
        val other = opponentOf(duel, me)
        if (!accept) {
            close(duel, Ending.CANCELLED, emptyList(), DuelLines.declined(me))
            tell(other, texts.format("duel.declined", "player" to me.name))
            return private(texts.format("duel.declined", "player" to me.name))
        }
        if (!me.accepted) {
            val accepted = duel.copy(participants = duel.participants.map { if (it.uuid == me.uuid) it.copy(accepted = true) else it })
            replace(accepted)
            val waiting = accepted.participants.filterNot { it.accepted }
            decisions.record(
                log, DecisionRecord.Kind.MECHANIC, ACCEPTED, "${duel.ref}",
                reason = "${name(me)} accepted" + (if (waiting.isEmpty()) "; both did: it starts" else "; waiting for ${waiting.joinToString { name(it) }}"),
                conversationId = EngineActions.conversationOf(duel.ref),
            )
            tell(other, texts.format("duel.accepted", "player" to me.name))
            if (waiting.isEmpty()) ready(accepted, afterLoading = false)
        }
        return private(texts.format("duel.accepted", "player" to me.name))
    }

    // ── Start ──

    /**
     * Both duelists of [duel] accepted: it starts now, once its arena is loaded, or it is cancelled when a duelist
     * cannot fight any more (gone, dead, PvP off, out of the arena's dimension) or the arena has no safe ground.
     * [afterLoading]: the arena was just loaded. Server thread.
     */
    private fun ready(duel: ChallengeState, afterLoading: Boolean) {
        val players = duel.participants.map { services.server.playerList.getPlayer(it.playerId) }
        unfit(duel, players)?.let { return cancel(duel, it) }
        val fighters = players.map { it!! }
        val arena = duel.duel!!.arena ?: return begin(duel, fighters, null)
        val level = fighters[0].level()
        if (loaded(level, arena)) {
            val spots = spots(level, arena, fighters) ?: return cancel(duel, "no safe ground for both at the arena ${where(arena)}")
            return begin(duel, fighters, spots)
        }
        if (afterLoading) return cancel(duel, "the arena ${where(arena)} could not be loaded")
        if (!starting.add(duel.id)) return
        val loading = Teleports.requestLoad(level, arena.x, arena.z)
        log.debug { "${duel.ref}: both accepted; loading the arena ${where(arena)} before it starts" }
        services.scope.launch {
            val loaded = Teleports.awaitLoaded(loading)
            try {
                services.server.onServerThread {
                    starting.remove(duel.id)
                    val now = store.get().challenge(duel.id)?.takeIf { it.status == ChallengeStatus.INVITED }
                    if (now != null && !stopped) {
                        if (loaded) ready(now, afterLoading = true) else cancel(now, "the arena ${where(arena)} took too long to load")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("${duel.ref}: its start after the arena loaded failed; the next second tries again", e)
            }
        }
    }

    /** Why [duel], both accepted, cannot start with [players] (its duelists, null when offline); null when it can. */
    private fun unfit(duel: ChallengeState, players: List<ServerPlayer?>): String? {
        duel.participants.forEachIndexed { i, duelist ->
            val player = players[i] ?: return "${name(duelist)} is not online"
            if (player.isDeadOrDying || player.isRemoved) return "${name(duelist)} was not alive when it would have started"
        }
        val fighters = players.map { it!! }
        if (fighters.any { !it.level().isPvpAllowed }) return "PvP is off in this world (game rule pvp)"
        val arena = duel.duel!!.arena ?: return null
        fighters.firstOrNull { it.level().dimension().identifier().toString() != arena.dimension }?.let {
            return "${it.shownName} is no longer in ${ToolText.dimension(arena.dimension.orEmpty())}, the arena's dimension"
        }
        return null
    }

    /** [duel] starts now (M4 §5.5): arena positions kept, duelists in place, `RUNNING`, a title, a `DUEL` event. Server thread. */
    private fun begin(duel: ChallengeState, players: List<ServerPlayer>, spots: List<Spot>?) {
        val now = clock.millis()
        val spec = duel.duel!!
        val arena = if (spots == null) emptyList() else players.map(::saved)
        spots?.forEachIndexed { i, spot -> move(players[i], players[i].level(), spot.at, spot.yaw, 0f) }
        val running = duel.copy(
            status = ChallengeStatus.RUNNING,
            startedAt = now,
            endsAt = now + spec.minutes * MINUTE,
            arena = arena,
            betsCloseAt = if (spec.bets) now + betWindow(spec.minutes) else null,
        )
        replace(running)
        for (player in players) {
            val other = opponentOf(running, running.participant(player.uuid)!!)
            fighting[player.uuid] = Fight(duel.id, other.playerId)
            title(player, ui.format("duel.start", "opponent" to other.name))
        }
        val line = DuelLines.started(running, clock)
        decisions.record(log, DecisionRecord.Kind.MECHANIC, Challenges.STARTED, "${duel.ref}", reason = line, conversationId = EngineActions.conversationOf(duel.ref))
        services.director.post(DirectorSignal.Mechanic(MechanicSignal.DUEL, line, now, priority = false))
    }

    /** How long the bets stay open: `mechanics.betWindowMinutes`, a third of the duel at most, a minute at least (M4 §5.6). */
    private fun betWindow(minutes: Int): Long =
        maxOf(MechanicsLimits.MIN_BET_WINDOW_MINUTES * MINUTE, minOf(config.betWindowMinutes * MINUTE, minutes * MINUTE / 3))

    /**
     * Both duelists' spots in [arena], face to face [MechanicsLimits.ARENA_DISTANCE] blocks west and east of its
     * point, on safe ground near its height; null when one has none. Loaded columns only. Server thread.
     */
    private fun spots(level: ServerLevel, arena: ArenaSpec, players: List<ServerPlayer>): List<Spot>? = players.mapIndexed { i, player ->
        val west = i == 0
        val x = arena.x + if (west) -MechanicsLimits.ARENA_DISTANCE else MechanicsLimits.ARENA_DISTANCE
        val at = Teleports.landing(level, player, x, arena.z, arena.y)?.takeIf { abs(it.y - arena.y) <= ARENA_HEIGHT_SLACK } ?: return null
        Spot(at, if (west) FACING_EAST else FACING_WEST)
    }

    /** Whether both columns of [arena]'s spots are loaded. */
    private fun loaded(level: ServerLevel, arena: ArenaSpec): Boolean =
        listOf(-1, 1).all { side ->
            val x = arena.x + side * MechanicsLimits.ARENA_DISTANCE
            level.chunkSource.getChunkNow(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(arena.z)) != null
        }

    private fun levelOf(server: MinecraftServer, arena: ArenaSpec): ServerLevel? = arena.dimension?.let { levelOf(server, it) }

    // ── Nobody dies in a duel ──

    /**
     * `ALLOW_DEATH` of [player] (M4 §5.5, before the guards'): a duelist's death is refused, health given back
     * first, and they lose the duel; for [MechanicsLimits.DUEL_RESISTANCE_SECONDS] after the end, a death of
     * either duelist is refused the same way. An operator's `/kill` (`generic_kill`) is allowed: a duel in
     * progress is then cancelled. Server thread.
     */
    override fun allowDeath(player: ServerPlayer, source: DamageSource): Boolean {
        val duel = store.get().challenges.firstOrNull { it.isDuel && it.status == ChallengeStatus.RUNNING && it.participant(player.uuid) != null }
        val fight = fighting[player.uuid]
        if (duel == null && fight == null) return true
        if (source.`is`(DamageTypes.GENERIC_KILL)) {
            duel?.let { close(it, Ending.CANCELLED, emptyList(), DuelLines.killed(it.participant(player.uuid)!!)) }
            return true
        }
        val now = clock.millis()
        if (duel == null && fight!!.until?.let { now > it } == true) {
            fighting.remove(player.uuid)
            return true
        }
        val loser = duel?.participant(player.uuid)
        val opponent = loser?.let { opponentOf(duel, it).playerId } ?: fight!!.opponent
        val ref = Ref(RefKind.CANON, duel?.id ?: fight!!.duel)
        val byOpponent = source.entity?.uuid == opponent
        if (byOpponent) spare(player) else services.guards.rescue(player, "duel $ref: ${DuelLines.cause(source, player)}")
        log.info("$ref: the death of ${player.plainTextName} (${DuelLines.cause(source, player)}) is refused${if (duel == null) " (just after the duel)" else ""}")
        if (duel != null && loser != null) {
            val winner = opponentOf(duel, loser)
            val how = if (byOpponent) DuelLines.blow(duel, winner, loser, now) else DuelLines.rescued(duel, winner, loser, DuelLines.cause(source, player), now)
            close(duel, Ending.WON, listOf(winner), how)
            tell(winner, ui["duel.won"])
            tell(loser, ui["duel.lost"])
        }
        return false
    }

    /** The opponent's fatal blow, stopped (M4 §5.5): 1 health, put out, the fight's harmful effects lifted, resistance V a few seconds. */
    private fun spare(player: ServerPlayer) {
        player.health = 1f
        aftercare(player)
        player.resetFallDistance()
        player.addEffect(MobEffectInstance(MobEffects.RESISTANCE, MechanicsLimits.DUEL_RESISTANCE_SECONDS * TICKS_PER_SECOND, RESISTANCE_V, false, false, true))
    }

    /**
     * What a duel leaves on [player] once over: no fire, air, and none of the harmful effects of the fight (a
     * poisoned arrow, a wither); the Narrator's own effects (its ledger) stay, under the death guard. Server thread.
     */
    private fun aftercare(player: ServerPlayer) {
        if (player.isRemoved) return
        player.clearFire()
        player.airSupply = player.maxAirSupply
        harmful(player).forEach { player.removeEffect(it) }
    }

    private fun harmful(player: ServerPlayer): List<Holder<MobEffect>> = player.activeEffects.map { it.effect }
        .filter { it.value().category == MobEffectCategory.HARMFUL && services.guards.effects.entryOf(player, it) == null }

    // ── End ──

    /**
     * Ends [duel] now through the common end ([Challenges.finish]): [how] after its reference. Its duelists'
     * aftercare starts at once (the next tick would come after a world tick). Server thread.
     */
    private fun close(duel: ChallengeState, ending: Ending, winners: List<Participant>, how: String) {
        challenges.finish(duel.id, ending, winners.map { it.playerId }, how = how) ?: return
        log.info("${duel.ref} ${ending.id}: $how")
        watch(clock.millis())
    }

    /** [duel] did not start: cancelled with [why], both told. Server thread. */
    private fun cancel(duel: ChallengeState, why: String) {
        close(duel, Ending.CANCELLED, emptyList(), "cancelled: $why")
        duel.participants.forEach { tell(it, ui["duel.cancelled"]) }
    }

    /**
     * Ends challenge or duel [ref] for the Director or an operator (`ChallengeControl.end`, M4 §5.2): [Challenges.end],
     * then a duel's players are told how it ended. Throws [MechanicRejected]. Never on the server thread.
     */
    suspend fun end(ref: Ref, outcome: ChallengeOutcome, winners: List<UUID>, note: String, author: Author): ChallengeView {
        val duel = store.snapshot.challenge(ref.id)?.takeIf { it.isDuel }
        val view = challenges.end(ref, outcome, winners, note, author)
        if (duel != null) {
            services.server.onServerThread {
                for (duelist in duel.participants) {
                    val key = when {
                        outcome == ChallengeOutcome.WON && duelist.playerId in winners -> "duel.won"
                        outcome == ChallengeOutcome.WON -> "duel.lost"
                        outcome == ChallengeOutcome.LOST && duel.status == ChallengeStatus.RUNNING -> "duel.draw"
                        else -> "duel.cancelled"
                    }
                    tell(duelist, ui[key])
                }
            }
        }
        return view
    }

    // ── The engine's part ──

    override fun everyTick(server: MinecraftServer, now: Long) = watch(now)

    /**
     * Follows the duelists: each one of a duel running is fighting; once their duel left the state, however it
     * ended, their aftercare is done and they stay protected [MechanicsLimits.DUEL_RESISTANCE_SECONDS]. Server thread.
     */
    private fun watch(now: Long) {
        val running = store.get().challenges.filter { it.isDuel && it.status == ChallengeStatus.RUNNING }
        for (duel in running) {
            for (duelist in duel.participants) {
                val fight = fighting[duelist.playerId]
                if (fight == null || fight.duel != duel.id || fight.until != null) fighting[duelist.playerId] = Fight(duel.id, opponentOf(duel, duelist).playerId)
            }
        }
        val each = fighting.entries.iterator()
        while (each.hasNext()) {
            val (uuid, fight) = each.next()
            val until = fight.until
            when {
                until == null && running.none { it.id == fight.duel } -> {
                    services.server.playerList.getPlayer(uuid)?.let(::aftercare)
                    fight.until = now + MechanicsLimits.DUEL_RESISTANCE_SECONDS * 1_000L
                }
                until != null && now > until -> each.remove()
            }
        }
    }

    /** Each second: invitations run out, duels reach their deadline (a draw), duelists go back where they were. */
    override fun everySecond(server: MinecraftServer, now: Long) {
        for (duel in store.get().challenges) {
            if (!duel.isDuel) continue
            when {
                duel.status == ChallengeStatus.INVITED && duel.participants.all { it.accepted } -> if (duel.id !in starting) ready(duel, afterLoading = false)
                duel.status == ChallengeStatus.INVITED && now >= duel.endsAt -> {
                    close(duel, Ending.CANCELLED, emptyList(), DuelLines.expired(duel))
                    duel.participants.forEach { tell(it, ui["duel.expired"]) }
                }
                duel.status == ChallengeStatus.RUNNING && now >= duel.endsAt -> {
                    close(duel, Ending.LOST, emptyList(), DuelLines.draw(duel))
                    duel.participants.forEach { tell(it, ui["duel.draw"]) }
                }
            }
        }
        putBack(server)
    }

    /**
     * Puts each duelist with a place to go back to ([MechanicsState.returns]) there, once online, alive and out of
     * any duel: on safe ground in that column, else exactly where they stood; its chunk is loaded first (off the
     * server thread), and they go at the next second. Server thread.
     */
    private fun putBack(server: MinecraftServer) {
        val returns = store.get().returns
        if (returns.isEmpty()) return
        val done = mutableListOf<SavedPosition>()
        for (saved in returns) {
            val player = server.playerList.getPlayer(UUID.fromString(saved.uuid)) ?: continue
            val fight = fighting[player.uuid]
            if (player.isDeadOrDying || player.isRemoved || fight != null && fight.until == null) continue // dead, or fighting again
            val level = levelOf(server, saved.dimension) ?: server.overworld()
            val x = floor(saved.x).toInt()
            val z = floor(saved.z).toInt()
            if (level.chunkSource.getChunkNow(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z)) == null) {
                Teleports.requestLoad(level, x, z)
                continue
            }
            val spot = Teleports.landing(level, player, x, z, floor(saved.y).toInt()) ?: Vec3(saved.x, saved.y, saved.z)
            if (move(player, level, spot, saved.yaw, saved.pitch)) {
                log.info("${player.plainTextName} is put back where they were before their duel (${DuelLines.at(spot)})")
            } else {
                log.warn("${player.plainTextName} could not be put back where they were before their duel (${DuelLines.at(spot)}); left where they are")
            }
            done += saved
        }
        if (done.isNotEmpty()) store.update { it.copy(returns = it.returns - done.toSet()) }
    }

    /** A player left: out of their duel, which they lose; an invitation is cancelled. */
    override fun onLeave(player: ServerPlayer) {
        val duel = store.get().challenges.firstOrNull { it.isDuel && it.participant(player.uuid) != null } ?: return
        val me = duel.participant(player.uuid)!!
        val other = opponentOf(duel, me)
        when (duel.status) {
            ChallengeStatus.INVITED -> {
                close(duel, Ending.CANCELLED, emptyList(), DuelLines.leftEarly(me))
                tell(other, ui["duel.cancelled"])
            }
            ChallengeStatus.RUNNING -> {
                close(duel, Ending.WON, listOf(other), DuelLines.left(duel, other, me, clock.millis()))
                tell(other, ui["duel.won"])
            }
            ChallengeStatus.JUDGING -> Unit
        }
    }

    override fun started(server: MinecraftServer, now: Long) {
        stopped = false
        fighting.clear()
        starting.clear()
        watch(now)
    }

    override fun stopping() {
        stopped = true
        starting.clear()
    }

    // ── Helpers ──

    /** The duel and its players, as the tools see it ([ChallengeView.line]: its situation line). */
    private fun view(duel: ChallengeState) = ChallengeView(
        duel.ref, null, duel.duel, duel.status, duel.endsAt, duel.participants, duel.bets.size, ChallengeLines.item(duel, clock.millis(), clock),
    )

    private fun replace(duel: ChallengeState) {
        store.update { state -> state.copy(challenges = state.challenges.map { if (it.id == duel.id) duel else it }) }
    }

    private fun opponentOf(duel: ChallengeState, duelist: Participant): Participant = duel.participants.first { it.uuid != duelist.uuid }

    /** Says [text] privately to [duelist], if online. */
    private fun tell(duelist: Participant, text: String) {
        services.server.playerList.getPlayer(duelist.playerId)?.sendSystemMessage(private(text))
    }

    private fun private(text: String): Component = Speech.chatLine(ui, text, private = true)

    private fun title(player: ServerPlayer, text: String) {
        player.connection.send(ClientboundSetTitlesAnimationPacket(TITLE_FADE_IN, TITLE_STAY, TITLE_FADE_OUT))
        player.connection.send(ClientboundSetTitleTextPacket(Speech.colored(ui, text)))
    }

    private fun saved(player: ServerPlayer) = SavedPosition(
        player.uuid.toString(), player.level().dimension().identifier().toString(), player.x, player.y, player.z, player.yRot, player.xRot,
    )

    /** Moves [player] to [at] in [level], looking [yaw] and [pitch], off their mount, with no fall to finish. */
    private fun move(player: ServerPlayer, level: ServerLevel, at: Vec3, yaw: Float, pitch: Float): Boolean {
        player.stopRiding()
        val moved = player.teleportTo(level, at.x, at.y, at.z, emptySet<Relative>(), yaw, pitch, true)
        if (moved) player.resetFallDistance()
        return moved
    }

    companion object {
        /** The refusal of a duel while PvP is off (M4 §5.5). */
        const val PVP_OFF = "pvp_off"

        /** The decisions of a duel (M4 §19), besides `started` and the endings. */
        const val INVITED = "invited"
        const val ACCEPTED = "accepted"

        private const val MINUTE = 60_000L
        private const val TICKS_PER_SECOND = 20

        /** Resistance V: no damage but the void's and an operator's. */
        private const val RESISTANCE_V = 4

        /** A duelist's spot in the arena is at most this many blocks above or below its point. */
        private const val ARENA_HEIGHT_SLACK = 8

        /** Yaw looking east (+x) and west (−x): the two duelists face each other. */
        private const val FACING_EAST = -90f
        private const val FACING_WEST = 90f

        private const val TITLE_FADE_IN = 10
        private const val TITLE_STAY = 40
        private const val TITLE_FADE_OUT = 20

        private fun invalid(message: String) = MechanicRejected(null, message)

        private fun name(participant: Participant) = ModelText.name(participant.name)

        private val ServerPlayer.shownName: String get() = ModelText.name(plainTextName)

        private fun where(arena: ArenaSpec) = "(x ${arena.x} y ${arena.y} z ${arena.z})"

        /** The level of dimension [id] (`minecraft:overworld`); null when the server has none. */
        private fun levelOf(server: MinecraftServer, id: String): ServerLevel? =
            Identifier.tryParse(id)?.let { server.getLevel(ResourceKey.create(Registries.DIMENSION, it)) }
    }
}

/**
 * The duels' lines for the model (M4 §4.4, §12.3), in English, names escaped: the proposal, the start, and how a
 * duel ended after its reference (the `DUEL` line and the canon's resolution). Pure, any thread.
 */
object DuelLines {
    private fun name(participant: Participant) = ModelText.name(participant.name)

    private fun both(duel: ChallengeState) = duel.participants.joinToString(" and ") { name(it) }

    private fun arena(duel: ChallengeState): String = duel.duel?.arena?.let { "in the arena x ${it.x} y ${it.y} z ${it.z}" } ?: "where they stand"

    /** `C-52 invited: Bartok and Mimosa « Duel au soleil », in the arena x 10 y 64 z -3; invitations end 21:22 (in 2m)`. */
    fun invited(duel: ChallengeState, clock: Clock): String =
        "${duel.ref} invited: ${both(duel)} ${ChallengeLines.title(duel)}, ${arena(duel)}; invitations end ${ChallengeLines.hourMinute(duel.endsAt, clock)} " +
            "(in ${ToolText.wait(duel.endsAt - duel.createdAt)})"

    /**
     * What `propose_duel` answers: `duel C-52 proposed to Bartok and Mimosa (« Duel au soleil », where they stand ·
     * reward 1x golden_apple · bets); invitations end 21:22 (in 2m); a DUEL event tells you whether it starts`.
     */
    fun proposed(view: ChallengeView, now: Long, clock: Clock): String {
        val spec = view.duel ?: return "${view.ref} proposed"
        val details = listOfNotNull(
            "« ${ModelText.escape(Speech.clean(spec.title))} », " + (spec.arena?.let { "in the arena x ${it.x} y ${it.y} z ${it.z}" } ?: "where they stand"),
            spec.reward?.let { "reward ${ChallengeLines.reward(it)}" },
            if (spec.bets) "bets" else null,
        )
        return "duel ${view.ref} proposed to ${view.participants.joinToString(" and ") { name(it) }} (${details.joinToString(" · ")}); " +
            "invitations end ${ChallengeLines.hourMinute(view.endsAt, clock)} (in ${ToolText.wait(view.endsAt - now)}); a DUEL event tells you whether it starts"
    }

    /** `C-52 started: Bartok and Mimosa accepted; they fight until 21:27 (in 5m), in the arena x 10 y 64 z -3 · bets open until 21:24`. */
    fun started(duel: ChallengeState, clock: Clock): String {
        val start = duel.startedAt ?: duel.createdAt
        return "${duel.ref} started: ${both(duel)} accepted; they fight until ${ChallengeLines.hourMinute(duel.endsAt, clock)} " +
            "(in ${ToolText.wait(duel.endsAt - start)}), ${arena(duel)}" +
            (duel.betsCloseAt?.let { " · bets open until ${ChallengeLines.hourMinute(it, clock)}" } ?: "")
    }

    /** `won by Mimosa in 2m10s: Mimosa's fatal blow on Bartok was stopped at 1 hp; both put back where they were`. */
    fun blow(duel: ChallengeState, winner: Participant, loser: Participant, now: Long): String =
        "${wonBy(duel, winner, now)}: ${name(winner)}'s fatal blow on ${name(loser)} was stopped at 1 hp${back(duel)}"

    /** `won by Mimosa in 1m05s: Bartok would have died (lava) and was rescued instead`. */
    fun rescued(duel: ChallengeState, winner: Participant, loser: Participant, cause: String, now: Long): String =
        "${wonBy(duel, winner, now)}: ${name(loser)} would have died ($cause) and was rescued instead${back(duel)}"

    /** `won by Mimosa in 3m: Bartok left the server`. */
    fun left(duel: ChallengeState, winner: Participant, leaver: Participant, now: Long): String =
        "${wonBy(duel, winner, now)}: ${name(leaver)} left the server${back(duel)}"

    /** `lost: a draw, nobody won within 5m`. */
    fun draw(duel: ChallengeState): String =
        "lost: a draw, nobody won within ${ToolText.duration(duel.endsAt - (duel.startedAt ?: duel.createdAt))}${back(duel)}"

    /** `cancelled: Mimosa declined`. */
    fun declined(decliner: Participant): String = "cancelled: ${name(decliner)} declined"

    /** `cancelled: nobody accepted within 2m`, or `cancelled: Mimosa did not accept within 2m (Bartok did)`. */
    fun expired(duel: ChallengeState): String {
        val within = ToolText.duration(duel.endsAt - duel.createdAt)
        val accepted = duel.participants.filter { it.accepted }
        if (accepted.isEmpty()) return "cancelled: nobody accepted within $within"
        val waiting = duel.participants.filterNot { it.accepted }
        return "cancelled: ${waiting.joinToString(" and ") { name(it) }} did not accept within $within (${accepted.joinToString(" and ") { name(it) }} did)"
    }

    /** `cancelled: Bartok left before it started`. */
    fun leftEarly(leaver: Participant): String = "cancelled: ${name(leaver)} left before it started"

    /** `cancelled: Bartok was killed by an operator (/kill)`. */
    fun killed(victim: Participant): String = "cancelled: ${name(victim)} was killed by an operator (/kill)"

    /** What would have killed [player]: the damage type (`lava`, `fall`, `out_of_world`), and by whom when another entity dealt it (`mob_attack by zombie`). */
    fun cause(source: DamageSource, player: ServerPlayer): String {
        val type = Goals.label(source.typeHolder().registeredName)
        val by = source.entity?.takeIf { it !== player }?.let { entity ->
            if (entity is ServerPlayer) ModelText.name(entity.plainTextName) else Goals.label(entity.typeId)
        }
        return if (by == null) type else "$type by $by"
    }

    /** `x 12.5, y 64, z -7.5`. */
    fun at(spot: Vec3): String = "x %.1f, y %.1f, z %.1f".format(java.util.Locale.ROOT, spot.x, spot.y, spot.z)

    private fun wonBy(duel: ChallengeState, winner: Participant, now: Long): String =
        "won by ${name(winner)}" + (duel.startedAt?.let { " in ${ToolText.duration(now - it)}" } ?: "")

    private fun back(duel: ChallengeState): String = if (duel.arena.isEmpty()) "" else "; both put back where they were"
}
