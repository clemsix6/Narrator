package io.github.clemsix6.narrator2.mechanics

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.director.MechanicSignal
import io.github.clemsix6.narrator2.mechanics.challenge.Ending
import io.github.clemsix6.narrator2.mechanics.challenge.Goals
import io.github.clemsix6.narrator2.mechanics.challenge.Verdict
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.CanonFilter
import io.github.clemsix6.narrator2.memory.CanonKind
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.memory.MemoryLimits
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * What the engine's start found (M4 §4.6), kept for the reconciliation, once the memory can be read.
 *
 * @property at when the engine started, epoch milliseconds: only the canon rows created before it are
 *   reconciled, never a challenge the Director starts while it runs (M4 review, finding 20).
 * @property ids the challenges, duels, rules and modules (M5 §6.7) of the state as the server starts, once the
 *   startup took out what ended while it was down: nothing else can have changed them yet. A tracked canon row
 *   whose id is not here is "lost when the world rolled back".
 * @property closings what the startup took out of the state: id → the resolution of its canon row.
 * @property unreadable the entries the state could not read back.
 * @property told what the Director hears of [closings] once the server runs (it is created after the engine):
 *   a `CHALLENGE` or `DUEL` event each, no priority (M4 review, finding 13).
 */
internal data class Startup(
    val at: Long,
    val ids: Set<Long>,
    val closings: Map<Long, String>,
    val unreadable: List<Unreadable>,
    val told: List<DirectorSignal.Mechanic> = emptyList(),
)

/**
 * Brings the engine's state and the canon back in line when the server starts (M4 §4.6). The state lives
 * in the world and the canon in the Journal: they are saved at different times, so a crash, a world rolled
 * back or an operator's change while the server was down can leave them apart.
 *
 * - [startup], on the server thread as the engine starts, without the database: a challenge or a duel whose
 *   deadline passed while the server was down is taken out of the state (no winner, no stakes over a time
 *   nobody played; its bets go back, its duelists are put back), and each entry that could not be read is
 *   reported. Two kinds stay, for the challenges' part to end at its first tick (M4 review, finding 13;
 *   `Goals.afterDowntime`): a judged challenge, judged again, and a challenge some participants made before the
 *   stop, won by them.
 * - [run], in a coroutine once the server runs: the Director hears of what the startup took out (no
 *   priority); the canon rows of what the startup took out, or could not read, are resolved; an open row the
 *   engine tracks with no state (a challenge, a duel, a rule or a module) is resolved `cancelled` (`retired` for a
 *   law); and the entries whose canon row
 *   is missing, or was closed before the server started, are named for the engine to drop from the state (with
 *   [drop], at its next tick).
 *
 * The engine resolves as [Source.ENGINE] (M4 §3.3). A read or a write that fails is logged and changes
 * nothing: an entry is never dropped on a doubt.
 */
internal class Reconcile(private val services: NarratorServices) {
    private val log = Log.mechanics
    private val decisions get() = Decisions(services.trace, services.clock)

    /**
     * The state [state] as the engine starts at [now]: without the challenges and duels whose deadline
     * passed (a `mechanic cancelled` decision for each), but for those that outlive the stop
     * ([outlivesDowntime]); its unreadable entries reported (a `mechanic unreadable` decision for each). Server
     * thread; never the database.
     */
    fun startup(state: MechanicsState, now: Long): Pair<MechanicsState, Startup> {
        for (entry in state.unreadable) {
            decisions.record(
                log, DecisionRecord.Kind.MECHANIC, UNREADABLE, entry.id?.let { "${Ref(RefKind.CANON, it)}" } ?: entry.what,
                reason = "${entry.what}: ${entry.error}",
                detail = buildJsonObject {
                    put("what", entry.what)
                    entry.id?.let { put("id", it) }
                    put("error", entry.error)
                },
                level = Log.Level.WARN,
            )
        }
        val ended = state.challenges.filter { deadline(it) <= now && !outlivesDowntime(it) }
        val kept = drop(state, ended.mapTo(HashSet()) { it.id })
        val told = ended.map { challenge ->
            val down = "$DOWN (${STAMP.format(Instant.ofEpochMilli(deadline(challenge)).atZone(services.clock.zone))})"
            val back = listOfNotNull(
                challenge.bets.size.takeIf { it > 0 }?.let { "${count(it, "bet")} given back" },
                challenge.arena.size.takeIf { it > 0 }?.let { "${count(it, "duelist")} put back at their next join" },
            ).joinToString("") { "; $it" }
            decisions.record(
                log, DecisionRecord.Kind.MECHANIC, CANCELLED, challenge.ref.toString(),
                reason = "$down; its canon is resolved once the memory runs$back",
                conversationId = EngineActions.conversationOf(challenge.ref),
            )
            val kind = if (challenge.isDuel) MechanicSignal.DUEL else MechanicSignal.CHALLENGE
            DirectorSignal.Mechanic(kind, "${challenge.ref} cancelled: $down; no stakes$back", now, priority = false)
        }
        val ids = kept.challenges.mapTo(LinkedHashSet()) { it.id } + kept.rules.map { it.id } + kept.modules.map { it.id }
        return kept to Startup(now, ids, ended.associate { it.id to DOWN }, state.unreadable, told)
    }

    /**
     * The reconciliation of [startup] (M4 §4.6), once the server runs: tells the Director what the startup took
     * out ([Startup.told]), resolves the canon rows to close and returns the ids of the entries to drop from the
     * state. Each change is a `mechanic reconciled` decision. Nothing but the Director's events without the
     * memory. Never on the server thread.
     */
    suspend fun run(startup: Startup): Set<Long> {
        startup.told.forEach { services.director.post(it) }
        val memory = services.memory
        if (!memory.available) {
            decisions.record(log, DecisionRecord.Kind.MECHANIC, RECONCILED, reason = "skipped: the memory is unavailable (${memory.unavailableReason})", level = Log.Level.WARN)
            return emptySet()
        }
        val notes = LinkedHashMap(startup.closings)
        startup.unreadable.forEach { entry -> entry.id?.let { notes.putIfAbsent(it, unreadableNote(entry.error)) } }
        val open = try {
            memory.store.canon(CanonFilter(kinds = TRACKED, open = true, limit = MAX_ROWS))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(null, "could not read the canon, nothing reconciled: ${reasonOf(e)}", e)
            return emptySet()
        }
        // A whole state unreadable: what it held is not "lost with the world", but unreadable.
        val lost = startup.unreadable.firstOrNull { it.what == WHOLE_STATE }?.let { unreadableNote(it.error) } ?: ROLLED_BACK
        open.filter { it.mechanic != null && it.createdAt.toEpochMilli() < startup.at && it.ref.id !in startup.ids }
            .forEach { notes.putIfAbsent(it.ref.id, lost) }

        var resolved = 0
        for ((id, note) in notes) if (resolve(Ref(RefKind.CANON, id), note)) resolved++
        val drop = startup.ids.filterTo(LinkedHashSet()) { closedBeforeStart(Ref(RefKind.CANON, it), startup.at) }
        decisions.record(
            log, DecisionRecord.Kind.MECHANIC, RECONCILED,
            reason = "${startup.ids.size} in the state, ${open.count { it.mechanic != null }} open in the canon: " +
                "$resolved resolved, ${drop.size} to drop from the state",
            level = if (resolved == 0 && drop.isEmpty()) Log.Level.DEBUG else Log.Level.INFO,
        )
        return drop
    }

    /** Resolves [ref] with [note] when it is still open: `cancelled`, or `retired` for a law. True when it did. */
    private suspend fun resolve(ref: Ref, note: String): Boolean = try {
        val row = services.memory.store.canon(ref)
        when {
            row == null -> {
                decisions.record(log, DecisionRecord.Kind.MECHANIC, RECONCILED, "$ref", reason = "no canon row to resolve ($note)", level = Log.Level.WARN)
                false
            }
            !row.status.open -> {
                log.debug { "reconciliation: $ref is already ${row.status.id}" }
                false
            }
            else -> {
                val status = closing(row.kind)
                services.memory.store.resolveCanon(ref, status, ModelText.cut(note, MemoryLimits.RESOLUTION), emptyList(), ENGINE)
                decisions.record(
                    log, DecisionRecord.Kind.MECHANIC, RECONCILED, "$ref", reason = "${status.id} in the canon: $note",
                    conversationId = EngineActions.conversationOf(ref),
                )
                true
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        failed(ref, "could not resolve the canon ($note): ${reasonOf(e)}", e)
        false
    }

    /** Whether the state's entry [ref] has no canon row any more, or one closed before the engine started at [at]. */
    private suspend fun closedBeforeStart(ref: Ref, at: Long): Boolean {
        val row = try {
            services.memory.store.canon(ref)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(ref, "could not read its canon row, kept in the state: ${reasonOf(e)}", e)
            return false
        }
        val why = when {
            row == null -> "its canon row is missing"
            !row.status.open && (row.resolvedAt?.toEpochMilli() ?: Long.MIN_VALUE) < at -> "its canon was closed (${row.status.id}) before the server started"
            else -> return false
        }
        decisions.record(log, DecisionRecord.Kind.MECHANIC, RECONCILED, "$ref", reason = "dropped from the state: $why", level = Log.Level.WARN)
        return true
    }

    private fun failed(ref: Ref?, reason: String, error: Exception) {
        log.warn("reconciliation${ref?.let { " of $it" }.orEmpty()}: $reason", error)
        decisions.record(log, DecisionRecord.Kind.MECHANIC, RECONCILED, ref?.toString(), reason = reason, level = Log.Level.OFF)
    }

    companion object {
        /** A challenge or a duel whose deadline passed while the server was down (M4 §4.6). */
        const val DOWN = "the server was down when it ended"

        /** An open canon row the engine tracks without any state: the world was saved before it started, then lost. */
        const val ROLLED_BACK = "lost when the world rolled back"

        // The decisions of the startup and the reconciliation (M4 §19).
        const val UNREADABLE = "unreadable"
        const val CANCELLED = "cancelled"
        const val RECONCILED = "reconciled"

        private val ENGINE = Author(Source.ENGINE)

        /** The canon kinds of the rows the engine tracks (M4 §3.3). */
        private val TRACKED = setOf(CanonKind.CHALLENGE, CanonKind.LAW, CanonKind.PLAN)

        /** [Unreadable.what] when nothing of the state could be read. */
        private const val WHOLE_STATE = "state"

        /** Open canon rows read at most: far more than the engine ever tracks. */
        private const val MAX_ROWS = 10_000

        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

        fun unreadableNote(error: String): String = "unreadable when the server started: $error"

        /**
         * [state] without the challenges, duels, rules and modules [ids]: the bets of a challenge taken out are given
         * back to their owners ([MechanicsState.settlements], applied once they are online) and the duelists
         * of an arena are put back where they were at their next join ([MechanicsState.returns]). The same
         * state when none of them is there.
         */
        fun drop(state: MechanicsState, ids: Set<Long>): MechanicsState {
            val gone = state.challenges.filter { it.id in ids }
            if (gone.isEmpty() && state.rules.none { it.id in ids } && state.modules.none { it.id in ids }) return state
            return state.copy(
                challenges = state.challenges.filterNot { it.id in ids },
                rules = state.rules.filterNot { it.id in ids },
                modules = state.modules.filterNot { it.id in ids },
            )
                .givingBack(gone.flatMap { it.bets })
                .puttingBack(gone.flatMap { it.arena })
        }

        /** When [challenge] ends: its end, or for a judged one waiting for its verdict, the end of the judging. */
        fun deadline(challenge: ChallengeState): Long =
            if (challenge.status == ChallengeStatus.JUDGING) challenge.judgeUntil ?: challenge.endsAt else challenge.endsAt

        /**
         * Whether [challenge], past its deadline as the server starts, stays in the state for the challenges' part
         * to end it ([Goals.afterDowntime]): judged again, or won by those who made it; not when it is cancelled.
         */
        private fun outlivesDowntime(challenge: ChallengeState): Boolean =
            (Goals.afterDowntime(challenge) as? Verdict.Ends)?.ending != Ending.CANCELLED

        /** How a tracked row of [kind] closes when the engine gives it up: `cancelled`, or `retired` for a law. */
        private fun closing(kind: CanonKind): CanonStatus = if (CanonStatus.CANCELLED in kind.closedBy) CanonStatus.CANCELLED else CanonStatus.RETIRED

        private fun reasonOf(e: Exception): String = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName

        private fun count(n: Int, what: String): String = if (n == 1) "1 $what" else "$n ${what}s"
    }
}
