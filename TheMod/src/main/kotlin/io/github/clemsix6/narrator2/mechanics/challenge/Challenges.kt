package io.github.clemsix6.narrator2.mechanics.challenge

import io.github.clemsix6.narrator2.config.MechanicsConfig
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.core.onServerThread
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.director.MechanicSignal
import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.event.ChatMessage
import io.github.clemsix6.narrator2.event.EntityKilled
import io.github.clemsix6.narrator2.event.Envelope
import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.event.PlayerDied
import io.github.clemsix6.narrator2.mechanics.Bossbars
import io.github.clemsix6.narrator2.mechanics.ChallengeOutcome
import io.github.clemsix6.narrator2.mechanics.ChallengeSpec
import io.github.clemsix6.narrator2.mechanics.ChallengeState
import io.github.clemsix6.narrator2.mechanics.ChallengeStatus
import io.github.clemsix6.narrator2.mechanics.ChallengeView
import io.github.clemsix6.narrator2.mechanics.EngineActions
import io.github.clemsix6.narrator2.mechanics.ForfeitSpec
import io.github.clemsix6.narrator2.mechanics.GoalKind
import io.github.clemsix6.narrator2.mechanics.MechanicPart
import io.github.clemsix6.narrator2.mechanics.MechanicRejected
import io.github.clemsix6.narrator2.mechanics.MechanicsLimits
import io.github.clemsix6.narrator2.mechanics.MechanicsState
import io.github.clemsix6.narrator2.mechanics.OutReason
import io.github.clemsix6.narrator2.mechanics.Participant
import io.github.clemsix6.narrator2.mechanics.RewardKind
import io.github.clemsix6.narrator2.mechanics.RewardSpec
import io.github.clemsix6.narrator2.mechanics.Settlement
import io.github.clemsix6.narrator2.mechanics.StateStore
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.CanonDraft
import io.github.clemsix6.narrator2.memory.CanonKind
import io.github.clemsix6.narrator2.memory.CanonMechanic
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.memory.InteractionDraft
import io.github.clemsix6.narrator2.memory.InteractionKind
import io.github.clemsix6.narrator2.memory.MemoryLimits
import io.github.clemsix6.narrator2.memory.MemoryRejected
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.memory.VoiceGuard
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolText
import io.github.clemsix6.narrator2.tool.speech.Speech
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.fabricmc.fabric.api.entity.FakePlayer
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.reflect.KClass

/**
 * Pays the stakes of a challenge or a duel (M4 §5.3), through the engine's acts ([EngineActions]): implemented
 * by M4-17 (`Stakes.kt`). The defaults check and pay nothing.
 */
interface StakePayer {
    /**
     * When a challenge starts: [reward] and [penalty] pass the dry checks of the tools that pay them, as [caller]
     * (the Director): an unknown item, a harmful effect as a reward, a severity the chaos level forbids are refused
     * now. Throws [MechanicRejected] with the fix (`reward: give_item refuses …`). Never on the server thread.
     */
    suspend fun check(reward: RewardSpec?, penalty: ForfeitSpec?, caller: Caller) {}

    /**
     * When [challenge] ends: its reward to each of [winners], its penalty to each of [losers] (never a latecomer:
     * [Challenges] leaves them out), one act of the engine per player, to those online only. Writes each stake's
     * interaction (`gift`, `blessing`, `punishment`, canon = the challenge, `Source.ENGINE`). Returns the segments
     * of the `CHALLENGE` or `DUEL` line, in order (`reward: Mimosa got 1x golden_apple`, `penalty: Bartok refused
     * (no_pile_on: …)`), none without stakes. A stake refused is said in a segment, never thrown. Never on the
     * server thread.
     */
    suspend fun pay(challenge: ChallengeState, winners: List<Participant>, losers: List<Participant>): List<String> = emptyList()

    companion object {
        /** Checks and pays nothing: the engine until M4-17. */
        val NONE: StakePayer = object : StakePayer {}
    }
}

/**
 * Settles the bets of a challenge or a duel that ends (M4 §5.6): implemented by M4-20 (`Bets.kt`). The default
 * gives every stake back to its owner ([giveBack]).
 */
interface BetBook {
    /**
     * The bets of [challenge], which ends [ending] with [winners] (UUIDs as text, in order): [state], from which
     * the challenge is already taken out, with the instructions that settle them
     * ([MechanicsState.settlements]), in the same update: the end and the instructions are saved together. Pure,
     * on the server thread: the stakes move once their owners are online. Returns the new state and the
     * `bets: …` segment of the line, null without bets. An exception gives every stake back.
     */
    fun settle(state: MechanicsState, challenge: ChallengeState, ending: Ending, winners: List<String>): Pair<MechanicsState, String?> =
        giveBack(state, challenge)

    companion object {
        /** Gives every stake back: the engine until M4-20, and the fallback of a settlement that fails. */
        val RETURN_ALL: BetBook = object : BetBook {}

        /** [state] with an instruction to give each stake of [challenge] back (never twice the same stake), and its segment. */
        fun giveBack(state: MechanicsState, challenge: ChallengeState): Pair<MechanicsState, String?> {
            if (challenge.bets.isEmpty()) return state to null
            val back = challenge.bets.map { Settlement(owner = it.uuid, stake = it.stake) }
                .filter { new -> state.settlements.none { it.owner == new.owner && it.stake == new.stake } }
            val n = challenge.bets.size
            return state.copy(settlements = state.settlements + back) to "bets: ${if (n == 1) "1 stake" else "$n stakes"} given back"
        }
    }
}

/**
 * The challenges (M4 §5): their start ([start], the share of `ChallengeControl.start`), their detection once a
 * second and at the facts of the bus (a [MechanicPart]), their early end ([end]) and the common end of the
 * challenges and the duels ([finish]): bets, bars, stakes, canon, interactions, the `mechanic` decision and the
 * Director's event.
 *
 * - **Start**: the goal's fields and ids ([Goals.shape], [Goals.target]), the participants (named and online,
 *   else everyone online who may take part — neither a spectator nor a player in creative — and whoever joins),
 *   the pace ([room]), the stakes' dry checks ([StakePayer.check]); then the canon row (`challenge`, mechanic
 *   `challenge`, by the Director), then the state with the participants as they are then ([Goals.atStart]), in
 *   the order of the starts ([Mutex]). Once the canon row is written, nothing cancels the start: the state
 *   follows, or the row is cancelled (M4 review, finding 25).
 * - **Detection** (M4 §5.2): `obtain` and `reach` each second, for each participant online; `kill`, `survive`
 *   (death) and `answer` at the facts, matched on the bus and applied at the next tick; `survive` with
 *   `no_armor` each second, departures at `LEAVE`. A spectator makes no progress; a tamed or named creature is no
 *   kill; a message is one attempt at a quiz every [Answers.ATTEMPT_GAP_MS] at most (M4 review, findings 14, 15,
 *   26). The bars draw themselves from the state (M4-09).
 * - **End** ([finish]): on the server thread, the challenge leaves the state with its bets settled
 *   ([BetBook.settle]) and its duelists to put back ([MechanicsState.returns]), and its bars are taken down in
 *   the same tick; then a coroutine resolves the canon (`Source.ENGINE`, before anything else and whatever
 *   cancels it: M4 review, finding 24), pays the stakes ([StakePayer.pay]), writes a `challenge_result`
 *   interaction per participant (never "lost" for a latecomer), records the decision `mechanic won|lost|expired|
 *   cancelled` and tells the Director (`CHALLENGE` or `DUEL`), unless the Director asked for it himself.
 * - **Restart** (M4 review, finding 13): at the engine's first tick, the Director being there by then, a judged
 *   challenge waiting for its verdict, or whose end passed while the server was down, is judged again (a whole
 *   window, a new `JUDGE`); a challenge whose end passed while the server was down and that some participants
 *   made (`Reconcile` kept it) is won by them, without penalty for the others, each winner's reward paid once
 *   they are back online.
 *
 * Duels (M4-19) live in the same state: this part leaves them alone but for their situation line and their end,
 * which goes through [finish] like a challenge's.
 *
 * Server thread for the part's hooks and [finish]; [start] and [end] from coroutines; [list] and [situation]
 * from any thread (the snapshot).
 *
 * @param clock the engine's clock: a GameTest moves it on.
 */
class Challenges(
    private val services: NarratorServices,
    private val store: StateStore,
    private val bars: Bossbars,
    private val stakes: StakePayer = StakePayer.NONE,
    private val bets: BetBook = BetBook.RETURN_ALL,
    private val clock: Clock = services.clock,
) : MechanicPart {
    private val log = Log.mechanics
    private val decisions get() = Decisions(services.trace, clock)
    private val config: MechanicsConfig get() = services.settings.get().config.mechanics

    /** One start at a time: the pace is checked again under it, just before the state is written. */
    private val starting = Mutex()

    /** The facts matched on the bus (any thread), applied at the next tick (server thread). */
    private val facts = ConcurrentLinkedQueue<Fact>()

    /** Each challenge's target, resolved once (server thread); null when it could not be (logged once). */
    private val targets = HashMap<Long, Target?>()

    /** The quizzes' attempts (any thread). */
    private val attempts = Attempts()

    /** When the engine started, until its first tick takes up what the stop left ([resume]). Server thread. */
    private var restartedAt: Long? = null

    /** The rewards of the winners of a challenge that ended while the server was down, until they are back. Server thread. */
    private val owed = ArrayList<Owed>()

    override val listensTo: Set<KClass<out GameEvent>> = setOf(EntityKilled::class, PlayerDied::class, ChatMessage::class)

    // ── Start (ChallengeControl.start) ──

    /**
     * Starts [spec] for [caller] (M4 §5.1): validates it, writes its canon row, then starts tracking it. Throws
     * [MechanicRejected] with the fix: a field the goal needs, an unknown id, a player not online, the pace
     * (`challenge_limit`, `challenge_pace`), a stake its tool refuses, a text the memory refuses. Never on the
     * server thread.
     */
    suspend fun start(spec: ChallengeSpec, caller: Caller): ChallengeView {
        Goals.shape(spec)?.let { throw invalid(it) }
        if (Speech.clean(spec.title).isEmpty()) throw invalid("title has nothing to show once formatting codes and invisible characters are removed")
        return starting.withLock {
            val server = services.server
            val participants = server.onServerThread { prepare(spec, server) }
            stakes.check(spec.reward, spec.penalty, caller)
            services.journal.barrier() // the players' join rows, which the canon checks
            val ref = writeCanon(spec, participants, caller)
            // The canon row is written: from here on, the state follows or the row is cancelled, whatever cancels the caller.
            withContext(NonCancellable) { track(ref, spec, participants, caller) }
        }
    }

    /**
     * Puts challenge [ref], whose canon row is written, in the state ([insert]); when it cannot (the pace refuses it
     * now, the server thread does not answer, an error), its canon row is cancelled and the refusal thrown, so that
     * no open row is left without a state (M4 review, finding 25). Its view once in the state.
     */
    private suspend fun track(ref: Ref, spec: ChallengeSpec, participants: List<Participant>, caller: Caller): ChallengeView {
        val refusal = try {
            services.server.onServerThread { insert(ref, spec, participants, caller) }
        } catch (e: Exception) {
            if (store.snapshot.challenge(ref.id) == null) {
                log.error("$ref could not start once its canon row was written: its row is cancelled", e)
                MechanicRejected(null, "$ref could not start: ${e.message ?: e.javaClass.simpleName}")
            } else {
                log.warn("$ref started, but its start ended with an error", e)
                null
            }
        }
        refusal?.let { cancelRefused(ref, it) }
        val challenge = store.snapshot.challenge(ref.id) ?: throw IllegalStateException("$ref left the state as it started")
        return view(challenge, clock.millis())
    }

    /** The participants of [spec], its target checked and the pace too. Throws [MechanicRejected]. Server thread. */
    private fun prepare(spec: ChallengeSpec, server: MinecraftServer): List<Participant> {
        Goals.target(spec, server).onFailure { throw invalid(it.message.orEmpty()) }
        val online = server.playerList.players.filter { it !is FakePlayer }
        val players = when (val names = spec.players) {
            null -> online.filter(::enrolls).ifEmpty { throw invalid(NOBODY) }
            else -> {
                val found = names.map { name -> name to online.firstOrNull { it.plainTextName.equals(name.trim(), ignoreCase = true) } }
                val missing = found.filter { it.second == null }.map { ModelText.name(it.first) }.distinct()
                if (missing.isNotEmpty()) {
                    val who = if (missing.size == 1) "${missing.single()} is not online" else "${missing.joinToString()} are not online"
                    throw invalid("$who; online: ${online.joinToString { ModelText.name(it.plainTextName) }.ifEmpty { "nobody" }}")
                }
                found.mapNotNull { it.second }.distinctBy { it.uuid }
            }
        }
        room(store.get(), clock.millis(), duel = false)?.let { throw it }
        return players.map(::participantOf)
    }

    /**
     * Whether [player] takes part in a challenge open to everyone, at its start or joining late: neither a spectator
     * nor a player in creative, who would win without playing (M4 review, finding 26).
     */
    private fun enrolls(player: ServerPlayer): Boolean = player !is FakePlayer && !player.isSpectator && !player.isCreative

    private fun participantOf(player: ServerPlayer) = Participant(player.uuid.toString(), player.plainTextName)

    /** Writes [spec]'s canon row as the Director (M4 §3.3): its ref. A text the memory refuses is [MechanicRejected]. */
    private suspend fun writeCanon(spec: ChallengeSpec, participants: List<Participant>, caller: Caller): Ref {
        val draft = CanonDraft(
            kind = CanonKind.CHALLENGE,
            statement = spec.statement,
            quote = spec.quote,
            players = if (spec.players == null) emptyList() else participants.map { it.playerId },
            stakes = ChallengeLines.stakes(spec.reward, spec.penalty, spec.bets),
            importance = spec.importance,
            mechanic = CanonMechanic.CHALLENGE,
        )
        return try {
            services.memory.store.addCanon(draft, Author(Source.DIRECTOR, turnId = caller.turnId, conversationId = caller.conversationId)).row.ref
        } catch (e: MemoryRejected) {
            throw MechanicRejected(e.rule, e.message.orEmpty())
        }
    }

    /**
     * Puts challenge [ref] in the state now, with the participants [prepared] as they are now ([Goals.atStart]: who
     * left or came while its canon row was written), unless the pace refuses it now (a duel came first), or nobody is
     * left to take up a challenge open to everyone: the refusal. Server thread.
     */
    private fun insert(ref: Ref, spec: ChallengeSpec, prepared: List<Participant>, caller: Caller): MechanicRejected? {
        val now = clock.millis()
        room(store.get(), now, duel = false)?.let { return it }
        val online = services.server.playerList.players.filter { it !is FakePlayer }
        val participants = Goals.atStart(spec, prepared, online.filter(::enrolls).map(::participantOf), online.mapTo(HashSet()) { it.uuid.toString() })
        if (participants.isEmpty()) return invalid(NOBODY)
        val challenge = ChallengeState(
            id = ref.id,
            spec = spec,
            status = ChallengeStatus.RUNNING,
            createdAt = now,
            startedAt = now,
            endsAt = now + spec.minutes * MINUTE,
            participants = participants,
            betsCloseAt = if (spec.bets) now + betWindow(spec.minutes) else null,
            turnId = caller.turnId,
            conversationId = caller.conversationId,
        )
        store.update { it.copy(challenges = it.challenges + challenge, lastChallengeAt = now) }
        val line = ChallengeLines.started(challenge, clock)
        decisions.record(
            log, DecisionRecord.Kind.MECHANIC, STARTED, "${challenge.ref}", reason = line,
            conversationId = EngineActions.conversationOf(challenge.ref), turnId = caller.turnId,
        )
        return null
    }

    /** The canon row of a challenge the pace refused once written: cancelled by the engine, and the refusal thrown. */
    private suspend fun cancelRefused(ref: Ref, refusal: MechanicRejected): Nothing {
        try {
            services.memory.store.resolveCanon(ref, CanonStatus.CANCELLED, "refused as it started: ${refusal.message}".take(MemoryLimits.RESOLUTION), emptyList(), engine(ref))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("$ref was refused as it started, and its canon row could not be cancelled", e)
        }
        throw refusal
    }

    /** How long the bets stay open: `mechanics.betWindowMinutes`, a third of the challenge at most, a minute at least. */
    private fun betWindow(minutes: Int): Long =
        maxOf(MechanicsLimits.MIN_BET_WINDOW_MINUTES * MINUTE, minOf(config.betWindowMinutes * MINUTE, minutes * MINUTE / 3))

    /**
     * The pace (M4 §11) of a new challenge, or a new duel when [duel], in [state] at [now]: `challenge_limit`
     * when `mechanics.maxChallenges` challenges and duels run (invitations included), `challenge_pace` when the
     * last challenge started less than `mechanics.challengeGapMinutes` ago (not for a duel). Null when there is
     * room. Pure; M4-19 checks its duels with it.
     */
    fun room(state: MechanicsState, now: Long, duel: Boolean): MechanicRejected? {
        val settings = config
        val running = state.challenges
        if (running.size >= settings.maxChallenges) {
            val listed = running.joinToString { "${it.ref} ${if (it.isDuel) "duel" else "challenge"}" }
            return MechanicRejected(CHALLENGE_LIMIT, "${running.size} are running ($listed); at most ${settings.maxChallenges} challenges and duels at once")
        }
        val last = state.lastChallengeAt
        val gap = settings.challengeGapMinutes * MINUTE
        if (!duel && last != null && now - last < gap) {
            val next = last + gap
            return MechanicRejected(
                CHALLENGE_PACE,
                "the last challenge started ${ToolText.duration(now - last)} ago; the next may start at ${hourMinute(next)} (in ${ToolText.wait(next - now)})",
            )
        }
        return null
    }

    // ── Early end (ChallengeControl.end) ──

    /**
     * Ends challenge or duel [ref] now (M4 §5.2): [ChallengeOutcome.WON] with [winners] among its participants
     * (the reward to them, the penalty to the others enrolled at the start), [ChallengeOutcome.LOST] (the
     * penalty to everyone enrolled at the start) or [ChallengeOutcome.CANCELLED] (no stakes, bets back). [note]
     * is the canon's resolution (French); [author] who asked: the engine resolves the canon itself. Returns the
     * view of the challenge as it ended, its [ChallengeView.line] the line of its end (stakes and bets
     * included), once everything is done. Throws [MechanicRejected]. Never on the server thread.
     */
    suspend fun end(ref: Ref, outcome: ChallengeOutcome, winners: List<UUID>, note: String, author: Author): ChallengeView {
        val running = store.snapshot.challenges
        val challenge = running.firstOrNull { it.id == ref.id } ?: throw notRunning(ref, running)
        val ending = Ending.of(outcome)
        if (ending == Ending.WON) {
            if (winners.isEmpty() || winners.any { challenge.participant(it) == null }) {
                throw invalid("won needs winners among the participants: ${challenge.participants.joinToString { ModelText.name(it.name) }}")
            }
        } else if (winners.isNotEmpty()) {
            throw invalid("winners only go with won: leave them out")
        }
        VoiceGuard.check(note, "note", MemoryLimits.RESOLUTION, challenge.participants.map { it.name }, boundQuotes = false)?.let { throw invalid(it) }
        val ended = services.server.onServerThread { finish(ref.id, ending, winners, note = note, author = author) }
            ?: throw notRunning(ref, store.snapshot.challenges)
        val line = ended.await()
        return view(challenge, clock.millis()).copy(line = line)
    }

    private fun notRunning(ref: Ref, running: List<ChallengeState>) =
        invalid("$ref is not one of your running challenges; running: ${running.joinToString { it.ref.toString() }.ifEmpty { "none" }}")

    /** The challenges and duels in progress, invitations included, in the order they started. Any thread. */
    fun list(): List<ChallengeView> {
        val now = clock.millis()
        return store.snapshot.challenges.map { view(it, now) }
    }

    private fun view(challenge: ChallengeState, now: Long) = ChallengeView(
        challenge.ref, challenge.spec, challenge.duel, challenge.status, challenge.endsAt, challenge.participants, challenge.bets.size,
        ChallengeLines.item(challenge, now, clock),
    )

    // ── The common end ──

    /**
     * Ends challenge or duel [id] now (M4 §5.2), [ending] with [winners] (participants, in order; only for
     * [Ending.WON]): it leaves the state, its bets settled ([BetBook.settle]) and its arena's duelists to put back
     * ([MechanicsState.returns]) in the same update, and its bars are taken down at once. Then, in a coroutine:
     * the stakes ([StakePayer.pay]), the canon resolved by the engine (with [winners]), a `challenge_result`
     * interaction per participant (won or lost only), the decision `mechanic <ending>` and, unless the Director
     * asked for it himself, the event `CHALLENGE` or `DUEL` (a priority one, but for a duel that never started).
     *
     * [how] says how it ended, after its reference (`won by Mimosa: Bartok's fatal blow was stopped at 1 hp`); the
     * engine writes it when null. [note]: the canon's resolution (else [how]); [author]: who asked, null when the
     * engine saw it end. Returns the job, whose result is the whole line (stakes and bets included); null when [id]
     * is not in progress (it ended already). Server thread.
     */
    fun finish(id: Long, ending: Ending, winners: List<UUID>, how: String? = null, note: String? = null, author: Author? = null): Deferred<String>? {
        val challenge = store.get().challenge(id) ?: return null
        val ordered = winners.mapNotNull { challenge.participant(it) }.distinctBy { it.uuid }
        return close(challenge, ending, if (ending == Ending.WON) ordered else emptyList(), how, note, author)
    }

    /**
     * The server thread's share of [finish]: out of the state, bets settled, bars down; then the coroutine's.
     * [downtime]: it ended while the server was down ([resume]): no penalty, and the rewards of the winners offline
     * now are paid once they are back ([owed]).
     */
    private fun close(
        challenge: ChallengeState,
        ending: Ending,
        winners: List<Participant>,
        how: String?,
        note: String?,
        author: Author?,
        downtime: Boolean = false,
    ): Deferred<String> {
        val now = clock.millis()
        val state = store.get()
        val rest = state.copy(
            challenges = state.challenges.filterNot { it.id == challenge.id },
            returns = state.returns + challenge.arena.filter { new -> state.returns.none { it.uuid == new.uuid } },
        )
        val (settled, betLine) = try {
            bets.settle(rest, challenge, ending, winners.map { it.uuid })
        } catch (e: Exception) {
            log.error("the bets of ${challenge.ref} could not be settled: every stake goes back", e)
            BetBook.giveBack(rest, challenge)
        }
        store.update { settled }
        bars.hideAll(Bossbars.key(challenge.id))
        targets.remove(challenge.id)
        attempts.forget(challenge.id)
        // A winner of a challenge that ended while the server was down is paid once back (M4 review, finding 13).
        val later = if (downtime && challenge.spec?.reward != null) winners.filter { services.server.playerList.getPlayer(it.playerId) == null } else emptyList()
        later.forEach { owed += Owed(challenge, it) }
        log.info("${challenge.ref} ${ending.id}${if (winners.isEmpty()) "" else " by ${winners.joinToString { it.name }}"}; its stakes and canon follow")
        val ended = Ended(challenge, ending, winners, now, how, note, author, betLine, downtime, later)
        return services.scope.async { conclude(ended) }
    }

    /**
     * A challenge that left the state at [at], to conclude. [downtime]: it ended while the server was down (no
     * penalty); [later]: its winners whose reward waits until they are back.
     */
    private class Ended(
        val challenge: ChallengeState,
        val ending: Ending,
        val winners: List<Participant>,
        val at: Long,
        val how: String?,
        val note: String?,
        val author: Author?,
        val betLine: String?,
        val downtime: Boolean,
        val later: List<Participant>,
    ) {
        val ref: Ref get() = challenge.ref
    }

    /**
     * The coroutine's share of [finish]: canon, stakes, interactions, decision, event. The canon is resolved first,
     * whatever cancels the rest: an end cut short is never taken for a world rolled back (M4 review, finding 24).
     * Each step's failure is logged and the next ones go on.
     */
    private suspend fun conclude(ended: Ended): String {
        val challenge = ended.challenge
        val summary = ended.how ?: (ChallengeLines.summary(challenge, ended.ending, ended.winners, ended.at, ended.author) + if (ended.downtime) DOWNTIME else "")
        withContext(NonCancellable) { step(ended.ref, "resolve the canon", Unit) { resolve(ended, ended.note ?: summary) } }
        val winnerIds = ended.winners.mapTo(HashSet()) { it.uuid }
        val losers = when {
            ended.downtime -> emptyList()
            ended.ending == Ending.WON -> challenge.participants.filter { it.uuid !in winnerIds && !it.late }
            ended.ending == Ending.LOST -> challenge.participants.filter { !it.late }
            else -> emptyList()
        }
        val paid = if (ended.ending == Ending.WON || ended.ending == Ending.LOST) {
            val online = ended.winners - ended.later.toSet()
            val later = if (ended.later.isEmpty()) null else "reward: " + ended.later.joinToString { "${ModelText.name(it.name)} paid once back online" }
            step(ended.ref, "pay the stakes", listOf("stakes: not paid (an error)")) { stakes.pay(challenge, online, losers) } + listOfNotNull(later)
        } else {
            emptyList()
        }
        val noted = ended.note?.let { "$summary; note: « ${ModelText.escape(it)} »" } ?: summary
        val line = ModelText.cut((listOf("${ended.ref} $noted") + paid + listOfNotNull(ended.betLine)).joinToString(" · "), MechanicsLimits.EVENT_LINE)
        if (ended.ending == Ending.WON || ended.ending == Ending.LOST) {
            step(ended.ref, "write the interactions", Unit) { interactions(ended, winnerIds) }
        }
        step(ended.ref, "record the decision", Unit) {
            decisions.record(
                log, DecisionRecord.Kind.MECHANIC, ended.ending.id, "${ended.ref}", reason = line,
                conversationId = EngineActions.conversationOf(ended.ref), turnId = ended.author?.turnId,
                detail = buildJsonObject {
                    put("ending", ended.ending.id)
                    put("what", if (challenge.isDuel) "duel" else "challenge")
                    putJsonArray("winners") { ended.winners.forEach { add(it.name) } }
                    ended.author?.let { put("by", it.actor) }
                },
            )
        }
        if (ended.author?.source != Source.DIRECTOR) {
            val kind = if (challenge.isDuel) MechanicSignal.DUEL else MechanicSignal.CHALLENGE
            // A duel that never started (declined, invitation over) wakes nobody before its time (M4 §4.4).
            val priority = !(challenge.isDuel && challenge.status == ChallengeStatus.INVITED)
            step(ended.ref, "tell the Director", Unit) { services.director.post(DirectorSignal.Mechanic(kind, line, ended.at, priority)) }
        }
        return line
    }

    /** The canon row resolved by the engine (M4 §3.3); a resolution the memory refuses is replaced by the engine's own words. */
    private suspend fun resolve(ended: Ended, resolution: String) {
        if (!services.memory.available) {
            log.warn("${ended.ref} ${ended.ending.id}, but the memory is unavailable: its canon stays open until the next reconciliation")
            return
        }
        val winners = ended.winners.take(MAX_CANON_WINNERS).map { it.playerId }
        try {
            services.memory.store.resolveCanon(ended.ref, ended.ending.status, ModelText.cut(resolution, MemoryLimits.RESOLUTION), winners, engine(ended.ref))
        } catch (e: MemoryRejected) {
            val plain = "${ended.ending.id} (${if (ended.challenge.isDuel) "duel" else "challenge"} ${ended.ref})"
            log.warn("${ended.ref}: the memory refused the resolution (${e.message}); resolved with « $plain »")
            services.memory.store.resolveCanon(ended.ref, ended.ending.status, plain, winners, engine(ended.ref))
        }
    }

    /**
     * A `challenge_result` per participant (M4 §5.2): +1 for a winner, 0 for the others; canon = the challenge, by the
     * engine. None for a latecomer who did not win: they may win, never lose (M4 review, finding 27).
     */
    private suspend fun interactions(ended: Ended, winners: Set<String>) {
        if (!services.memory.available) return
        val what = "${if (ended.challenge.isDuel) "duel" else "challenge"} ${ended.ref}" +
            (ended.challenge.spec?.let { " (${Goals.describe(it)})" } ?: "")
        for (participant in ended.challenge.participants) {
            val won = participant.uuid in winners
            if (participant.late && !won) continue
            val summary = ModelText.cut(if (won) "won the $what" else "lost the $what", MemoryLimits.INTERACTION_SUMMARY)
            step(ended.ref, "write ${participant.name}'s result", Unit) {
                services.memory.store.addInteraction(
                    InteractionDraft(participant.playerId, InteractionKind.CHALLENGE_RESULT, if (won) 1 else 0, summary, canon = ended.ref),
                    engine(ended.ref),
                )
            }
        }
    }

    private inline fun <T> step(ref: Ref, what: String, fallback: T, body: () -> T): T = try {
        body()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.error("$ref: could not $what", e)
        fallback
    }

    private fun engine(ref: Ref) = Author(Source.ENGINE, conversationId = EngineActions.conversationOf(ref))

    // ── The engine's part ──

    /** A fact matched on the bus: [uuid] for challenge [id]. */
    private sealed interface Fact {
        val id: Long
        val uuid: UUID

        data class Kill(override val id: Long, override val uuid: UUID, val type: String) : Fact

        data class Death(override val id: Long, override val uuid: UUID) : Fact

        data class Answer(override val id: Long, override val uuid: UUID) : Fact
    }

    /**
     * Matches a fact of the bus against the challenges running (the snapshot) and queues what it means; never a
     * change. A kill counts only a wild creature without a name: never another player's pet nor anyone's named
     * creature (M4 review, finding 15). A message is an attempt at a quiz once every [Answers.ATTEMPT_GAP_MS] at
     * most ([Attempts]). Any thread.
     */
    override fun onEvent(envelope: Envelope) {
        val running = store.snapshot.challenges.filter { it.spec != null && it.status == ChallengeStatus.RUNNING }
        if (running.isEmpty()) return
        when (val event = envelope.event) {
            is EntityKilled -> if (!event.pvp && !event.tamed && event.victim.name == null) {
                running.forEach { c -> if (c.spec!!.goal == GoalKind.KILL && c.participant(event.player.uuid) != null) facts += Fact.Kill(c.id, event.player.uuid, event.victim.type) }
            }
            is PlayerDied -> running.forEach { c -> if (c.spec!!.goal == GoalKind.SURVIVE && c.participant(event.player.uuid) != null) facts += Fact.Death(c.id, event.player.uuid) }
            is ChatMessage -> if (event.channel == ChatChannel.PUBLIC || event.channel == ChatChannel.PRAY) {
                val uuid = event.player.uuid
                running.forEach { c ->
                    val spec = c.spec!!
                    if (spec.goal == GoalKind.ANSWER && c.participant(uuid) != null && attempts.take(c.id, uuid, clock.millis()) &&
                        Answers.matches(event.text, spec.answers.orEmpty())
                    ) {
                        facts += Fact.Answer(c.id, uuid)
                    }
                }
            }
            else -> Unit
        }
    }

    override fun everyTick(server: MinecraftServer, now: Long) {
        resume(now)
        if (applyFacts(server, now)) decide(now)
    }

    override fun everySecond(server: MinecraftServer, now: Long) {
        payOwed()
        applyFacts(server, now)
        for (challenge in store.get().challenges) {
            if (challenge.spec == null || challenge.status != ChallengeStatus.RUNNING) continue
            val seen = detect(server, challenge, now)
            if (seen !== challenge) replace(seen)
        }
        decide(now)
    }

    /**
     * Applies the facts queued since the last tick; true when one changed a challenge. A kill or an answer of a
     * participant who is a spectator now counts for nothing (M4 review, finding 26). Server thread.
     */
    private fun applyFacts(server: MinecraftServer, now: Long): Boolean {
        var changed = false
        while (true) {
            val fact = facts.poll() ?: break
            val challenge = store.get().challenge(fact.id) ?: continue
            if (fact !is Fact.Death && server.playerList.getPlayer(fact.uuid)?.isSpectator == true) continue
            val next = when (fact) {
                is Fact.Kill -> if ((target(challenge) as? Target.Creatures)?.matches?.invoke(fact.type) == true) Goals.killed(challenge, fact.uuid, now) else challenge
                is Fact.Death -> Goals.out(challenge, fact.uuid, OutReason.DIED)
                is Fact.Answer -> Goals.answered(challenge, fact.uuid, now)
            }
            if (next === challenge) continue
            replace(next)
            changed = true
            log.debug { "${challenge.ref}: ${fact::class.simpleName?.lowercase()} of ${fact.uuid}" }
        }
        return changed
    }

    /**
     * What the second sees of [challenge]'s participants online: items held, places reached, armor worn; nothing of
     * a spectator, who goes through walls (M4 review, finding 26). Server thread.
     */
    private fun detect(server: MinecraftServer, challenge: ChallengeState, now: Long): ChallengeState {
        val spec = challenge.spec!!
        if (spec.goal != GoalKind.OBTAIN && spec.goal != GoalKind.REACH && !(spec.goal == GoalKind.SURVIVE && spec.noArmor)) return challenge
        val target = target(challenge)
        var seen = challenge
        for (participant in challenge.participants) {
            if (participant.out != null || participant.doneAt != null) continue
            val player = server.playerList.getPlayer(participant.playerId)?.takeUnless { it.isSpectator } ?: continue
            seen = when (spec.goal) {
                GoalKind.OBTAIN -> (target as? Target.Items)?.let { Goals.held(seen, player.uuid, Goals.count(player, it), now) } ?: seen
                GoalKind.REACH -> {
                    val place = target as? Target.Place
                    if (place != null && place.holds(Goals.spot(player, services.players.get(player.uuid)?.structure))) Goals.reached(seen, player.uuid, now) else seen
                }
                else -> if (Goals.wearsArmor(player)) Goals.out(seen, player.uuid, OutReason.ARMOR) else seen
            }
        }
        return seen
    }

    /** The verdict of every challenge at [now]: the ends finished, the judged ones past their end put to judgment. Server thread. */
    private fun decide(now: Long) {
        for (challenge in store.get().challenges) {
            when (val verdict = Goals.verdict(challenge, now)) {
                null -> Unit
                is Verdict.Ends -> close(challenge, verdict.ending, verdict.winners, null, null, null)
                Verdict.Judge -> judge(challenge, now)
            }
        }
    }

    /**
     * The engine's first tick (M4 review, finding 13), the Director being there by then: what the stop left. A judged
     * challenge waiting for its verdict, or whose end passed while the server was down, is judged again ([judge]: a
     * whole window, a new `JUDGE`, the one before was lost with the stop). A challenge whose end passed while the
     * server was down, kept by the startup for some participants made it ([Goals.afterDowntime]), is won by them:
     * no penalty for the others, each winner's reward once they are back ([owed]). Server thread.
     */
    private fun resume(now: Long) {
        val since = restartedAt ?: return
        restartedAt = null
        for (challenge in store.get().challenges) {
            if (challenge.spec == null) continue
            val waiting = challenge.status == ChallengeStatus.JUDGING
            if (!waiting && (challenge.status != ChallengeStatus.RUNNING || challenge.endsAt > since)) continue
            when (val verdict = Goals.afterDowntime(challenge)) {
                Verdict.Judge -> judge(challenge, now)
                is Verdict.Ends -> close(challenge, verdict.ending, verdict.winners, null, null, null, downtime = true)
            }
        }
    }

    /** Pays the rewards [owed] to the winners back online, each in a coroutine of its own. Server thread. */
    private fun payOwed() {
        if (owed.isEmpty()) return
        val back = owed.filter { services.players.get(it.winner.playerId) != null }
        if (back.isEmpty()) return
        owed.removeAll(back.toSet())
        for (due in back) {
            log.info("${due.challenge.ref}: ${due.winner.name} is back, their reward is paid")
            services.scope.launch {
                val paid = step(due.challenge.ref, "pay ${due.winner.name}'s reward", emptyList()) { stakes.pay(due.challenge, listOf(due.winner), emptyList()) }
                log.info("${due.challenge.ref}: ${paid.joinToString(" · ").ifEmpty { "nothing paid" }}")
            }
        }
    }

    /** The reward of [winner] of [challenge], which ended while the server was down: paid once they are back online. */
    private class Owed(val challenge: ChallengeState, val winner: Participant)

    /** A judged challenge reached its end (M4 §5.2): `JUDGING` for `mechanics.judgeMinutes`, a `JUDGE` event (priority). */
    private fun judge(challenge: ChallengeState, now: Long) {
        val until = now + config.judgeMinutes * MINUTE
        val judging = challenge.copy(status = ChallengeStatus.JUDGING, judgeUntil = until)
        replace(judging)
        val line = ChallengeLines.judge(judging, now)
        decisions.record(log, DecisionRecord.Kind.MECHANIC, JUDGING, "${challenge.ref}", reason = line, conversationId = EngineActions.conversationOf(challenge.ref))
        services.director.post(DirectorSignal.Mechanic(MechanicSignal.JUDGE, line, now, priority = true))
    }

    /** [challenge]'s target, resolved at its first need; null when its ids no longer exist (logged once). Server thread. */
    private fun target(challenge: ChallengeState): Target? {
        if (challenge.id in targets) return targets[challenge.id]
        val target = Goals.target(challenge.spec!!, services.server).getOrElse {
            log.error("${challenge.ref}: its goal cannot be resolved any more, nothing will be detected: ${it.message}")
            null
        }
        targets[challenge.id] = target
        return target
    }

    /** Puts [challenge] in the state in place of its former self. Server thread. */
    private fun replace(challenge: ChallengeState) {
        store.update { state -> state.copy(challenges = state.challenges.map { if (it.id == challenge.id) challenge else it }) }
    }

    /**
     * A player joined: a latecomer of every challenge open to everyone (M4 §5.2), whose bar shows at the next second;
     * not a spectator nor a player in creative ([enrolls]).
     */
    override fun onJoin(player: ServerPlayer) {
        if (!enrolls(player)) return
        for (challenge in store.get().challenges) {
            val joined = Goals.joined(challenge, player.uuid, player.plainTextName)
            if (joined === challenge) continue
            replace(joined)
            log.info("${player.plainTextName} joins ${challenge.ref} late: may win, never lose")
        }
    }

    /** A player left: out of every `survive` they were still in. */
    override fun onLeave(player: ServerPlayer) {
        for (challenge in store.get().challenges) {
            if (challenge.spec?.goal != GoalKind.SURVIVE) continue
            val left = Goals.out(challenge, player.uuid, OutReason.LEFT)
            if (left !== challenge) replace(left)
        }
    }

    override fun situation(now: Long): List<String> = listOfNotNull(ChallengeLines.situation(store.snapshot, now, config, clock))

    override fun started(server: MinecraftServer, now: Long) {
        targets.clear()
        facts.clear()
        attempts.clear()
        restartedAt = now
    }

    override fun dropped(ids: Set<Long>) {
        ids.forEach {
            targets.remove(it)
            attempts.forget(it)
        }
    }

    override fun stopping() {
        facts.clear()
        // In memory only: a reward still owed is lost with the stop, and said so.
        owed.forEach { log.warn("${it.challenge.ref}: ${it.winner.name}'s reward was never paid, they did not come back before the stop") }
        owed.clear()
    }

    private fun hourMinute(epochMs: Long): String = ChallengeLines.hourMinute(epochMs, clock)

    companion object {
        /** The refusals of the pace (M4 §11). */
        const val CHALLENGE_LIMIT = "challenge_limit"
        const val CHALLENGE_PACE = "challenge_pace"

        /** The decisions of a challenge (M4 §19), besides the endings ([Ending.id]). */
        const val STARTED = "started"
        const val JUDGING = "judging"

        /** Winners written in the canon at most (the memory's bound of players). */
        private const val MAX_CANON_WINNERS = 8

        private const val NOBODY = "nobody is online to take up a challenge"

        /** After the summary of a challenge that ended while the server was down (M4 review, finding 13). */
        private const val DOWNTIME = "; the server was down when it ended: no penalty"

        private const val MINUTE = 60_000L

        private fun invalid(message: String) = MechanicRejected(null, message)
    }
}

/**
 * The challenges' lines for the model (M4 §4.4, §4.5, §12.3), in English, names and the model's texts escaped:
 * the `Challenges:` line of the situation, the start, the end and the judgment, and the stakes of the canon.
 * Pure, any thread.
 */
object ChallengeLines {
    private val HOUR_MINUTE: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** [epochMs] as `20:15` in [clock]'s zone. */
    fun hourMinute(epochMs: Long, clock: Clock): String = HOUR_MINUTE.format(Instant.ofEpochMilli(epochMs).atZone(clock.zone))

    /** The title as the model reads it: `« Œuf de tortue »`, cleaned and escaped. */
    fun title(challenge: ChallengeState): String = "« ${ModelText.escape(Speech.clean(challenge.title).ifEmpty { challenge.ref.toString() })} »"

    private fun name(participant: Participant) = ModelText.name(participant.name)

    /**
     * The `stakes` of a canon row, written by the code (M4 §3.3): `reward 1x golden_apple · penalty tiny (2) ·
     * bets`; null without any. An item's French name is left out: the memory checks the voice of this text.
     */
    fun stakes(reward: RewardSpec?, penalty: ForfeitSpec?, bets: Boolean): String? {
        val parts = listOfNotNull(
            reward?.let { "reward ${reward(it)}" },
            penalty?.let { "penalty ${it.punishment.id} (${it.severity})" },
            if (bets) "bets" else null,
        )
        return if (parts.isEmpty()) null else ModelText.cut(parts.joinToString(" · "), MechanicsLimits.STAKES)
    }

    /** A reward in a few words: `1x golden_apple (named)`, `speed II for 5m`, `grace for 15m`, `a joker`. */
    fun reward(reward: RewardSpec): String = when (reward.kind) {
        RewardKind.ITEM -> "${reward.count}x ${Goals.label(reward.item.orEmpty())}" + if (reward.name != null) " (named)" else ""
        RewardKind.EFFECT -> "${Goals.label(reward.effect.orEmpty())} ${ToolText.roman(reward.level)} for ${ToolText.duration(reward.seconds * 1_000L)}"
        RewardKind.GRACE -> "grace for ${ToolText.duration(reward.minutes * 60_000L)}"
        RewardKind.JOKER -> "a joker"
    }

    /**
     * The start (the `mechanic started` decision; `start_challenge` may answer with it): `C-50 started: obtain 1
     * turtle_egg « Œuf de tortue » · Mimosa, Bartok · ends 20:15 (in 15m) · reward 1x golden_apple · penalty tiny
     * (2) · bets open until 20:05`.
     */
    fun started(challenge: ChallengeState, clock: Clock): String {
        val spec = challenge.spec ?: return "${challenge.ref} started"
        val start = challenge.startedAt ?: challenge.createdAt
        val who = challenge.participants.joinToString { name(it) }.let { if (spec.players == null) "everyone online ($it) and whoever joins" else it }
        return listOfNotNull(
            "${challenge.ref} started: ${Goals.describe(spec)} ${title(challenge)}",
            who,
            "ends ${hourMinute(challenge.endsAt, clock)} (in ${ToolText.wait(challenge.endsAt - start)})",
            spec.reward?.let { "reward ${reward(it)}" },
            spec.penalty?.let { "penalty ${it.punishment.id} (${it.severity})" },
            challenge.betsCloseAt?.let { "bets open until ${hourMinute(it, clock)}" },
        ).joinToString(" · ")
    }

    /**
     * How [challenge] ended at [at], after its reference (M4 §4.4, §5.2), also the canon's resolution when nobody
     * wrote one: `won by Mimosa in 9m12s (turtle_egg 1/1)`, `lost: nobody reached the_nether in 20m`, `expired:
     * not judged within 15m; no stakes`, `cancelled by the operator Clement`.
     */
    fun summary(challenge: ChallengeState, ending: Ending, winners: List<Participant>, at: Long, author: Author?): String {
        val spec = challenge.spec
        val start = challenge.startedAt ?: challenge.createdAt
        val names = winners.joinToString { name(it) }
        val by = author?.let { by(it) }
        return when (ending) {
            Ending.WON -> when {
                by != null -> "won by $names ($by)"
                spec == null -> "won by $names"
                spec.goal == GoalKind.SURVIVE -> "won by $names: still in after ${ToolText.duration(challenge.endsAt - start)}" + outs(challenge)
                else -> "won by " + winners.joinToString { p -> name(p) + (p.doneAt?.let { " in ${ToolText.duration(it - start)}" } ?: "") } +
                    (winners.firstOrNull()?.let { " (${achieved(spec, it)})" } ?: "")
            }
            Ending.LOST -> when {
                by != null -> "lost: nobody won ($by)"
                spec == null -> "lost: nobody won"
                spec.goal == GoalKind.SURVIVE -> "lost: nobody is still in after ${ToolText.duration(at - start)}" + outs(challenge)
                else -> "lost: nobody ${failed(spec)} in ${ToolText.duration(minOf(at, challenge.endsAt) - start)}"
            }
            Ending.EXPIRED -> "expired: not judged within ${ToolText.duration((challenge.judgeUntil ?: at) - challenge.endsAt)}; no stakes"
            Ending.CANCELLED -> if (by != null) "cancelled ($by)" else "cancelled"
        }
    }

    private fun by(author: Author): String = when (author.source) {
        Source.DIRECTOR -> "ended by the Director"
        Source.OPERATOR -> "ended by the operator${author.operator?.let { " ${ModelText.name(it)}" }.orEmpty()}"
        else -> "ended by the ${author.source.id}"
    }

    /** `; out: Bartok (died), Mimosa (armor)`, or nothing. */
    private fun outs(challenge: ChallengeState): String {
        val out = challenge.participants.filter { it.out != null }
        return if (out.isEmpty()) "" else "; out: " + out.joinToString { "${name(it)} (${it.out!!.id})" }
    }

    /** What the first winner achieved: `turtle_egg 1/1`, `reached the_nether`, `answered`. */
    private fun achieved(spec: ChallengeSpec, winner: Participant): String = Goals.measure(spec, winner) ?: when (spec.goal) {
        GoalKind.REACH -> "reached ${spec.place?.let(Goals::describe) ?: "the place"}"
        GoalKind.ANSWER -> "answered"
        else -> Goals.describe(spec)
    }

    /** What nobody did: `held 1 turtle_egg`, `reached the_nether`, `killed 10 spider`, `answered`. */
    private fun failed(spec: ChallengeSpec): String = when (spec.goal) {
        GoalKind.OBTAIN -> "held ${spec.count} ${Goals.label(spec.item.orEmpty())}"
        GoalKind.REACH -> "reached ${spec.place?.let(Goals::describe) ?: "the place"}"
        GoalKind.KILL -> "killed ${spec.count} ${Goals.label(spec.entity.orEmpty())}"
        GoalKind.ANSWER -> "answered"
        GoalKind.SURVIVE -> "survived"
        GoalKind.JUDGED -> "was judged"
    }

    /**
     * The `JUDGE` line (M4 §4.4): `C-53 « La plus belle statue » ended: judge it now with end_challenge C-53
     * (winners); participants: Bartok, Mimosa, Josiane; you have 15m`.
     */
    fun judge(challenge: ChallengeState, now: Long): String =
        "${challenge.ref} ${title(challenge)} ended: judge it now with end_challenge ${challenge.ref} (winners); " +
            "participants: ${challenge.participants.joinToString { name(it) }}; you have ${ToolText.wait((challenge.judgeUntil ?: now) - now)}"

    /**
     * One challenge or duel of the situation, also [ChallengeView.line]: `C-50 obtain 1 turtle_egg « Œuf de
     * tortue » → all · Mimosa 0/1, Bartok 1/1 (done) · ends in 12m · bets open 3m (2 bets)`. [compact]: the
     * participants counted instead of listed.
     */
    fun item(challenge: ChallengeState, now: Long, clock: Clock, compact: Boolean = false): String {
        val duel = challenge.duel
        if (duel != null) {
            val who = challenge.participants.joinToString(" vs ") { name(it) }
            val where = when (challenge.status) {
                ChallengeStatus.INVITED -> {
                    val accepted = challenge.participants.filter { it.accepted }
                    val waiting = challenge.participants.filter { !it.accepted }
                    "invited" + (if (accepted.isEmpty()) "" else ", ${accepted.joinToString { name(it) }} accepted") +
                        (if (waiting.isEmpty()) "" else ", waiting for ${waiting.joinToString { name(it) }}") +
                        " · answer by ${hourMinute(challenge.endsAt, clock)} (in ${ToolText.wait(challenge.endsAt - now)})"
                }
                else -> "ends in ${ToolText.wait(challenge.endsAt - now)}"
            }
            return "${challenge.ref} duel ${title(challenge)} $who · $where" + bets(challenge, now)
        }
        val spec = challenge.spec!!
        val head = "${challenge.ref} ${Goals.describe(spec)} ${title(challenge)}" + if (spec.players == null) " → all" else ""
        val who = if (compact) {
            val done = challenge.participants.count { it.doneAt != null }
            val out = challenge.participants.count { it.out != null }
            "${challenge.participants.size} players" + (if (done > 0) ", $done done" else "") + (if (out > 0) ", $out out" else "")
        } else {
            challenge.participants.joinToString { progress(spec, it) }
        }
        val time = if (challenge.status == ChallengeStatus.JUDGING) {
            val until = challenge.judgeUntil ?: challenge.endsAt
            "judge it with end_challenge by ${hourMinute(until, clock)} (in ${ToolText.wait(until - now)})"
        } else {
            "ends in ${ToolText.wait(challenge.endsAt - now)}"
        }
        return "$head · $who · $time" + bets(challenge, now)
    }

    /** `Mimosa 0/1`, `Bartok 1/1 (done)`, `Josiane (late)`, `Bartok (out: died)`. */
    private fun progress(spec: ChallengeSpec, participant: Participant): String {
        val counted = spec.goal == GoalKind.OBTAIN || spec.goal == GoalKind.KILL
        val marks = listOfNotNull(
            if (participant.doneAt != null) "done" else null,
            participant.out?.let { "out: ${it.id}" },
            if (participant.late) "late" else null,
        )
        return name(participant) + (if (counted) " ${participant.progress.coerceAtMost(spec.count)}/${spec.count}" else "") +
            (if (marks.isEmpty()) "" else " (${marks.joinToString()})")
    }

    private fun bets(challenge: ChallengeState, now: Long): String {
        val count = challenge.bets.size
        val placed = if (count == 1) "1 bet" else "$count bets"
        val close = challenge.betsCloseAt
        return when {
            close != null && now < close -> " · bets open ${ToolText.wait(close - now)} ($placed)"
            count > 0 -> " · $placed"
            else -> ""
        }
    }

    /**
     * The `Challenges:` line of the situation (M4 §4.5), [MechanicsLimits.SITUATION_LINE] characters at most: each
     * challenge and duel ([item]), then what the pace allows. Too long, the participants are counted instead of
     * listed, then the last challenges left out (`(+1 more)`). Null when nothing runs and the pace allows a new
     * challenge.
     */
    fun situation(state: MechanicsState, now: Long, config: MechanicsConfig, clock: Clock): String? {
        val pace = pace(state, now, config, clock)
        if (state.challenges.isEmpty() && pace == null) return null
        val max = MechanicsLimits.SITUATION_LINE
        fun render(items: List<String>, more: Int): String {
            val listed = items.ifEmpty { if (more == 0) listOf("none running") else emptyList() } + listOfNotNull(if (more > 0) "(+$more more)" else null)
            return "Challenges: " + listed.joinToString("; ") + (pace?.let { " · $it" } ?: "")
        }
        val full = render(state.challenges.map { item(it, now, clock) }, 0)
        if (full.length <= max) return full
        val compact = state.challenges.map { item(it, now, clock, compact = true) }
        for (kept in compact.size downTo 1) {
            val text = render(compact.take(kept), compact.size - kept)
            if (text.length <= max) return text
        }
        return ModelText.cut(render(emptyList(), compact.size), max)
    }

    /** What the pace allows: `next challenge possible in 6m`, `no new challenge until one ends (2/2)`; null when one may start now. */
    private fun pace(state: MechanicsState, now: Long, config: MechanicsConfig, clock: Clock): String? {
        if (state.challenges.size >= config.maxChallenges) return "no new challenge until one ends (${state.challenges.size}/${config.maxChallenges})"
        val last = state.lastChallengeAt ?: return null
        val next = last + config.challengeGapMinutes * 60_000L
        return if (next > now) "next challenge possible at ${hourMinute(next, clock)} (in ${ToolText.wait(next - now)})" else null
    }
}
