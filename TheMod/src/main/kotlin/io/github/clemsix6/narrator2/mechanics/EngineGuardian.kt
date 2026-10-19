package io.github.clemsix6.narrator2.mechanics

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.guard.ItemLedger
import io.github.clemsix6.narrator2.mechanics.challenge.BetInstructions
import io.github.clemsix6.narrator2.mechanics.challenge.DuelReturns
import io.github.clemsix6.narrator2.mechanics.rule.RuleEngine
import io.github.clemsix6.narrator2.observer.typeId
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.speech.Speech
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Mob
import java.time.Clock
import java.util.UUID

/**
 * What still runs of the engine when the engine does not (M4 review, finding 9): the mechanics are off
 * (`mechanics.enabled` false, the Journal or the memory unavailable, a start that failed), but the world keeps the
 * engine's state, and what the engine left in it must not stay frozen there. On the server thread, without the
 * database:
 *
 * - **Duels** (at its first tick, [start]): every duel of the state is over for its players ([release]). Its duelists go back where
 *   they stood before its arena ([DuelReturns], each second, once online), its bets go back to their owners, and each
 *   duelist is told at their next join that it is cancelled (`duel.cancelled`). The duel stays in the state, its end
 *   set to now: once the engine runs again, its start cancels it and the reconciliation resolves its canon.
 * - **Bets**: the instructions the world kept are applied as their owners come online ([BetInstructions], each tick,
 *   each second, at a join before the guards'); the bets of a challenge whose end passed go back to their owners at
 *   that end ([release]), not a week later. The challenge stays in the state for the engine to close.
 * - **Rules**: each creature loaded at its first tick, and each one that loads, loses the marks of the rules
 *   ([RuleEngine.unmark]): a law nobody enforces leaves no creature three times faster nor the Narrator's.
 *
 * The rules themselves, the challenges and the canon wait for the engine: its start and its reconciliation (M4 §4.6)
 * close what ended meanwhile. Each duel or challenge released is a decision `mechanic released`.
 *
 * @param ledger the escrow of the stakes: the guards' ledger, or a test's own.
 */
class EngineGuardian(
    private val services: NarratorServices,
    private val store: StateStore,
    ledger: ItemLedger = services.guards.items,
    private val clock: Clock = services.clock,
) {
    private val log = Log.mechanics
    private val decisions get() = Decisions(services.trace, clock)
    private val instructions = BetInstructions(services, store, ledger, clock)
    private val returns = DuelReturns(store, Decisions(services.trace, clock), clock)

    /** The duelists of a duel released, told at their next join that it is cancelled. Server thread. */
    private val toTell = HashSet<UUID>()

    /** When the last second of wall clock ran; 0 before the first one. Server thread. */
    private var lastSecondAt = 0L

    /** When a failure was last logged: once a minute at most. */
    private var failedAt = Long.MIN_VALUE

    private var started = false

    /** As the engine would start, at the guardian's first tick: the duels released, the creatures loaded unmarked. Server thread. */
    private fun start(server: MinecraftServer) {
        val now = clock.millis()
        val duels = store.get().challenges.filter { it.isDuel }
        duels.forEach { duel -> duel.participants.forEach { toTell += it.playerId } }
        releaseNow(now)
        val unmarked = server.allLevels.sumOf { level -> level.allEntities.count { unmark(it) } }
        log.info(
            "the engine's guardian runs while the mechanics are off: ${duels.size} duels released" +
                (if (unmarked == 0) "" else ", $unmarked creatures freed of the rules' marks") +
                "; ${store.get().settlements.size} bet settlements and ${store.get().returns.size} returns to apply",
        )
    }

    /**
     * End of a server tick: at the first one, the guardian starts ([start]); then the bet instructions of the owners
     * online, and once a second what came due. Server thread.
     */
    fun tick(server: MinecraftServer) {
        val now = clock.millis()
        safely("tick", now) {
            if (!started) {
                started = true
                start(server)
            }
            instructions.applyOnline(server, now)
            if (now - lastSecondAt < SECOND_MS && now >= lastSecondAt) return@safely
            lastSecondAt = now
            releaseNow(now)
            instructions.everySecond(server, now)
            returns.putBack(server)
        }
    }

    /** [player] joined (before the guards' own join): their bet instructions; a duelist of a duel released is told. Server thread. */
    fun onJoin(player: ServerPlayer) {
        val now = clock.millis()
        safely("join", now) {
            instructions.onJoin(player, now)
            if (toTell.remove(player.uuid)) {
                val ui = services.settings.get().ui
                player.sendSystemMessage(Speech.chatLine(ui, ui["duel.cancelled"], private = true))
            }
        }
    }

    /** A creature loaded: the marks of the rules come off it. Server thread. */
    fun onMobLoad(entity: Entity) {
        safely("creature load", clock.millis()) { unmark(entity) }
    }

    /** Releases what [release] finds in the state at [now], with a decision for each. */
    private fun releaseNow(now: Long) {
        val before = store.get()
        val after = store.update { release(it, now) }
        if (after === before) return
        for (challenge in before.challenges) {
            val released = after.challenge(challenge.id) ?: continue
            if (released == challenge) continue
            val what = if (challenge.isDuel) "duel" else "challenge"
            val freed = listOfNotNull(
                challenge.arena.size.takeIf { it > 0 }?.let { "$it duelists to put back where they stood, once online" },
                challenge.bets.size.takeIf { it > 0 }?.let { if (it == 1) "1 bet given back" else "$it bets given back" },
            ).ifEmpty { listOf("nothing to give back") }
            decisions.record(
                log, DecisionRecord.Kind.MECHANIC, RELEASED, "${challenge.ref}",
                reason = "${challenge.ref} $what released while the mechanics are off: ${freed.joinToString()}; " +
                    "the engine closes it and resolves its canon when it runs again",
                conversationId = EngineActions.conversationOf(challenge.ref),
            )
        }
    }

    /** Takes the rules' marks off [entity], if it is a creature with some; whether it had any. */
    private fun unmark(entity: Entity): Boolean {
        if (entity !is Mob) return false
        val marks = RuleEngine.marksOn(entity)
        if (marks.isEmpty()) return false
        RuleEngine.unmark(entity, marks)
        log.debug { "${entity.typeId} ${entity.uuid} freed of the marks of ${marks.joinToString { "C-$it" }}: the mechanics are off" }
        return true
    }

    /** [body], a failure logged once a minute at most, never thrown into the game. */
    private inline fun safely(what: String, now: Long, body: () -> Unit) {
        try {
            body()
        } catch (e: Exception) {
            if (failedAt != Long.MIN_VALUE && now - failedAt in 0 until FAILURE_GAP_MS) return
            failedAt = now
            log.error("the engine's guardian failed ($what)", e)
        }
    }

    companion object {
        /** The decision of a duel or a challenge the guardian released. */
        const val RELEASED = "released"

        private const val SECOND_MS = 1_000L
        private const val FAILURE_GAP_MS = 60_000L

        /**
         * [state] with what the engine, off, can no longer follow released at [now]: each duel's duelists to put back
         * ([MechanicsState.returns]), its bets given back, its end set to [now]; the bets of each challenge past its end
         * ([Reconcile.deadline]) given back. What is released keeps its place in the state, without its bets nor its
         * arena, for the engine to close when it runs again. [state] itself when there is nothing to release. Pure.
         */
        fun release(state: MechanicsState, now: Long): MechanicsState {
            val freed = state.challenges.filter { challenge ->
                if (challenge.isDuel) challenge.arena.isNotEmpty() || challenge.bets.isNotEmpty() || challenge.endsAt > now
                else challenge.bets.isNotEmpty() && Reconcile.deadline(challenge) <= now
            }
            if (freed.isEmpty()) return state
            val ids = freed.mapTo(HashSet()) { it.id }
            val settlements = freed.flatMap { it.bets }.map { Settlement(owner = it.uuid, stake = it.stake) }
                .filter { new -> state.settlements.none { it.owner == new.owner && it.stake == new.stake } }
            val returns = freed.flatMap { it.arena }.filter { new -> state.returns.none { it.uuid == new.uuid } }
            return state.copy(
                challenges = state.challenges.map { challenge ->
                    when {
                        challenge.id !in ids -> challenge
                        challenge.isDuel -> challenge.copy(arena = emptyList(), bets = emptyList(), endsAt = minOf(challenge.endsAt, now))
                        else -> challenge.copy(bets = emptyList())
                    }
                },
                settlements = state.settlements + settlements,
                returns = state.returns + returns,
            )
        }
    }
}
