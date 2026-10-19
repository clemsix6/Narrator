package io.github.clemsix6.narrator2.testing

import io.github.clemsix6.narrator2.mechanics.ChallengeControl
import io.github.clemsix6.narrator2.mechanics.ChallengeOutcome
import io.github.clemsix6.narrator2.mechanics.ChallengeSpec
import io.github.clemsix6.narrator2.mechanics.ChallengeState
import io.github.clemsix6.narrator2.mechanics.ChallengeStatus
import io.github.clemsix6.narrator2.mechanics.ChallengeView
import io.github.clemsix6.narrator2.mechanics.DuelSpec
import io.github.clemsix6.narrator2.mechanics.MechanicRejected
import io.github.clemsix6.narrator2.mechanics.Mechanics
import io.github.clemsix6.narrator2.mechanics.Participant
import io.github.clemsix6.narrator2.mechanics.RuleControl
import io.github.clemsix6.narrator2.mechanics.RuleSpec
import io.github.clemsix6.narrator2.mechanics.RuleView
import io.github.clemsix6.narrator2.mechanics.challenge.ChallengeLines
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.observer.typeId
import io.github.clemsix6.narrator2.tool.Caller
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * A mechanics engine for tests (design M4 §4.1): it records the calls and gives back what the test set.
 * [situationLines] answers [lines]; [allowDeath] answers [allowDeaths]; the hooks are recorded in [calls]
 * (`tick`, `join Bartok`, `stop`…). Its controls ([FakeChallenges], [FakeRules]) record what they are given
 * and answer views numbered from `C-1` (one sequence for both), or throw their [FakeChallenges.rejection].
 * Set [available] false (and [reason]) to test what happens without the engine.
 */
class FakeMechanics(private val clock: Clock = TestClock()) : Mechanics {
    @Volatile
    override var available: Boolean = true

    @Volatile
    var reason: String = "fake mechanics off"

    override val unavailableReason: String? get() = if (available) null else reason

    /** What [situationLines] answers, whatever the time. */
    @Volatile
    var lines: List<String> = emptyList()

    /** What [allowDeath] answers. */
    @Volatile
    var allowDeaths: Boolean = true

    /** The hooks called, in order: `tick`, `join Bartok`, `leave Bartok`, `respawn Bartok`, `mob_load minecraft:creeper`, `death Bartok`, `stop`. */
    val calls = CopyOnWriteArrayList<String>()

    private val ids = AtomicLong(1)

    override val challenges = FakeChallenges(clock, ids)
    override val rules = FakeRules(clock, ids)

    override fun situationLines(now: Long): List<String> = lines

    override fun tick(server: MinecraftServer) {
        calls += "tick"
    }

    override fun onJoin(player: ServerPlayer) {
        calls += "join ${player.plainTextName}"
    }

    override fun onLeave(player: ServerPlayer) {
        calls += "leave ${player.plainTextName}"
    }

    override fun onRespawn(old: ServerPlayer, new: ServerPlayer) {
        calls += "respawn ${new.plainTextName}"
    }

    override fun onMobLoad(entity: Entity, level: ServerLevel) {
        calls += "mob_load ${entity.typeId}"
    }

    override fun allowDeath(player: ServerPlayer, source: DamageSource): Boolean {
        calls += "death ${player.plainTextName}"
        return allowDeaths
    }

    override fun stop() {
        calls += "stop"
    }
}

/** The challenges of [FakeMechanics]: what the tools and commands asked, and what they get back. */
class FakeChallenges(private val clock: Clock, private val ids: AtomicLong) : ChallengeControl {
    /** One call to [end]. */
    data class Ended(val ref: Ref, val outcome: ChallengeOutcome, val winners: List<UUID>, val note: String, val author: Author)

    /** One `/duel` or `/bet`: the player, the challenge, and `accept`/`decline` or what they bet on. */
    data class Answer(val player: UUID, val ref: Ref, val what: String)

    /** Thrown by [start], [proposeDuel] and [end] when set. */
    @Volatile
    var rejection: MechanicRejected? = null

    /** What [answerDuel] and [bet] answer. */
    @Volatile
    var reply: Component = Component.literal("ok")

    val started = CopyOnWriteArrayList<Pair<ChallengeSpec, Caller>>()
    val proposed = CopyOnWriteArrayList<Pair<DuelSpec, Caller>>()
    val ended = CopyOnWriteArrayList<Ended>()
    val answers = CopyOnWriteArrayList<Answer>()

    /** What [list] answers: the views [start] and [proposeDuel] gave, and any the test adds; [end] removes its own. */
    val views = CopyOnWriteArrayList<ChallengeView>()

    override suspend fun start(spec: ChallengeSpec, caller: Caller): ChallengeView {
        rejection?.let { throw it }
        started += spec to caller
        val ref = Ref(RefKind.CANON, ids.getAndIncrement())
        val participants = spec.players.orEmpty().map { Participant(playerId(it).toString(), it) }
        val now = clock.millis()
        val state = ChallengeState(
            ref.id, spec = spec, status = ChallengeStatus.RUNNING, createdAt = now, startedAt = now, endsAt = now + spec.minutes * 60_000L,
            participants = participants, turnId = caller.turnId, conversationId = caller.conversationId,
        )
        // The line of its start, as the engine says it (without the bets' window, the engine's to set).
        val view = ChallengeView(ref, spec, null, ChallengeStatus.RUNNING, state.endsAt, participants, 0, ChallengeLines.started(state, clock))
        views += view
        return view
    }

    override suspend fun proposeDuel(spec: DuelSpec, caller: Caller): ChallengeView {
        rejection?.let { throw it }
        proposed += spec to caller
        val ref = Ref(RefKind.CANON, ids.getAndIncrement())
        val participants = spec.players.map { Participant(playerId(it).toString(), it) }
        val view = ChallengeView(ref, null, spec, ChallengeStatus.INVITED, clock.millis() + 120_000L, participants, 0, "$ref duel « ${spec.title} »")
        views += view
        return view
    }

    override suspend fun end(ref: Ref, outcome: ChallengeOutcome, winners: List<UUID>, note: String, author: Author): ChallengeView {
        rejection?.let { throw it }
        ended += Ended(ref, outcome, winners, note, author)
        val view = views.firstOrNull { it.ref == ref } ?: throw MechanicRejected(null, "$ref is not one of your running challenges")
        views.remove(view)
        return view
    }

    override fun answerDuel(player: ServerPlayer, ref: Ref, accept: Boolean): Component {
        answers += Answer(player.uuid, ref, if (accept) "accept" else "decline")
        return reply
    }

    override fun bet(player: ServerPlayer, ref: Ref, on: String): Component {
        answers += Answer(player.uuid, ref, on)
        return reply
    }

    override fun list(): List<ChallengeView> = views.toList()

    companion object {
        /** The UUID the fakes give a player named [name] (the offline-mode UUID of the name's bytes). */
        fun playerId(name: String): UUID = UUID.nameUUIDFromBytes(name.toByteArray())
    }
}

/** The rules of [FakeMechanics]: what the tools and commands asked, and what they get back. */
class FakeRules(private val clock: Clock, private val ids: AtomicLong) : RuleControl {
    /** One call to [remove]. */
    data class Removed(val ref: Ref, val note: String, val author: Author)

    /** Thrown by [create] and [remove] when set. */
    @Volatile
    var rejection: MechanicRejected? = null

    val created = CopyOnWriteArrayList<Pair<RuleSpec, Caller>>()
    val removed = CopyOnWriteArrayList<Removed>()

    /** What [list] answers: the views [create] gave, and any the test adds; [remove] removes its own. */
    val views = CopyOnWriteArrayList<RuleView>()

    override suspend fun create(spec: RuleSpec, caller: Caller): RuleView {
        rejection?.let { throw it }
        created += spec to caller
        val ref = Ref(RefKind.CANON, ids.getAndIncrement())
        val view = RuleView(ref, spec, clock.millis() + spec.minutes * 60_000L, 0, 0, 0, "$ref ${spec.kind.id} ${spec.trigger.id}")
        views += view
        return view
    }

    override suspend fun remove(ref: Ref, note: String, author: Author): RuleView {
        rejection?.let { throw it }
        removed += Removed(ref, note, author)
        val view = views.firstOrNull { it.ref == ref } ?: throw MechanicRejected(null, "$ref is not an active rule")
        views.remove(view)
        return view
    }

    override fun list(): List<RuleView> = views.toList()
}
