package io.github.clemsix6.narrator2.mechanics

import io.github.clemsix6.narrator2.memory.CanonEntry
import io.github.clemsix6.narrator2.memory.CanonKind
import io.github.clemsix6.narrator2.memory.CanonMechanic
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.testing.FakeMemory
import io.github.clemsix6.narrator2.testing.MechanicsSamples
import io.github.clemsix6.narrator2.testing.MechanicsSamples.evening
import io.github.clemsix6.narrator2.testing.MemorySamples
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.trace.DecisionRecord
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The engine's state and the canon brought back in line as the server starts (M4 §4.6). */
class ReconcileTest {
    private val clock = TestClock(Instant.ofEpochMilli(evening))
    private val memory = FakeMemory(clock)
    private val services = TestServices(clock = clock, memory = memory)
    private val reconcile = Reconcile(services)
    private val minute = 60_000L

    /** The server starts at 20:30 of the evening (UTC 18:30): C-50 (until 20:15) and the duel C-52 (until 20:06) ended while it was down. */
    private val startedAt = evening + 30 * minute

    private fun row(
        id: Long,
        kind: CanonKind,
        mechanic: CanonMechanic?,
        createdAt: Long = evening,
        status: CanonStatus = CanonStatus.ACTIVE,
        resolvedAt: Long? = null,
    ): CanonEntry = MemorySamples.canon.copy(
        ref = MechanicsSamples.canon(id), kind = kind, mechanic = mechanic, createdAt = Instant.ofEpochMilli(createdAt),
        status = status, resolvedAt = resolvedAt?.let(Instant::ofEpochMilli), dueAt = null, quote = null, players = emptyList(),
    )

    private fun put(vararg rows: CanonEntry) = rows.forEach { memory.store.rows[it.ref] = it }

    private fun canon(id: Long): CanonEntry = memory.store.rows.getValue(MechanicsSamples.canon(id)) as CanonEntry

    private fun reconciled(): List<Pair<String?, String?>> =
        services.traces.decisions.filter { it.decision == Reconcile.RECONCILED && it.subject != null }.map { it.subject to it.reason }

    /** The evening's tracked rows, all open: C-50 the challenge, C-52 the duel, C-60 the law, C-61 the plan. */
    private fun eveningCanon() = put(
        row(50, CanonKind.CHALLENGE, CanonMechanic.CHALLENGE),
        row(52, CanonKind.CHALLENGE, CanonMechanic.DUEL),
        row(60, CanonKind.LAW, CanonMechanic.RULE, createdAt = MechanicsSamples.griefLaw.createdAt),
        row(61, CanonKind.PLAN, CanonMechanic.RULE),
    )

    @Test
    fun `what ended while the server was down is taken out at the start, its bets given back, its canon cancelled later`() = runBlocking<Unit> {
        val (kept, startup) = reconcile.startup(MechanicsSamples.state, startedAt)

        assertEquals(emptyList(), kept.challenges)
        assertEquals(MechanicsSamples.state.rules, kept.rules)
        assertEquals(
            MechanicsSamples.state.settlements + Settlement(owner = MechanicsSamples.josiane.uuid.toString(), stake = 812),
            kept.settlements,
            "Josiane's stake on C-50 goes back to her",
        )
        assertEquals(MechanicsSamples.state.returns, kept.returns)
        assertEquals(Startup(startedAt, setOf(60L, 61L), mapOf(50L to Reconcile.DOWN, 52L to Reconcile.DOWN), emptyList()), startup)
        val cancelled = services.traces.decisions.filter { it.decision == Reconcile.CANCELLED }
        assertEquals(listOf("C-50", "C-52"), cancelled.map { it.subject })
        assertEquals(
            "the server was down when it ended (10-19 18:15); its canon is resolved once the memory runs; 1 bet given back",
            cancelled.first().reason,
        )
        assertTrue(memory.store.writes.isEmpty(), "never the database at the start")

        eveningCanon()
        val drop = reconcile.run(startup)

        assertEquals(emptySet(), drop)
        for (id in listOf(50L, 52L)) {
            assertEquals(CanonStatus.CANCELLED to Reconcile.DOWN, canon(id).status to canon(id).resolution)
        }
        assertEquals(CanonStatus.ACTIVE, canon(60).status)
        assertEquals(listOf("resolveCanon C-50 by engine", "resolveCanon C-52 by engine"), memory.store.writes)
        assertEquals(listOf("C-50" to "cancelled in the canon: ${Reconcile.DOWN}", "C-52" to "cancelled in the canon: ${Reconcile.DOWN}"), reconciled())
    }

    @Test
    fun `a judged challenge waiting for its verdict ends with its judging time`() {
        val judging = MechanicsSamples.runningChallenge.copy(status = ChallengeStatus.JUDGING, judgeUntil = startedAt + minute)
        val state = MechanicsState(challenges = listOf(judging))

        val (kept, startup) = reconcile.startup(state, startedAt)

        assertSame(state, kept, "the Director may still judge it")
        assertEquals(setOf(50L), startup.ids)
        assertEquals(emptyMap(), startup.closings)
    }

    @Test
    fun `an open tracked row without state is cancelled, a law retired, and a row born after the start, or not tracked, is left alone`() = runBlocking<Unit> {
        val state = MechanicsState(rules = listOf(MechanicsSamples.griefLaw))
        val (_, startup) = reconcile.startup(state, startedAt)
        put(
            row(60, CanonKind.LAW, CanonMechanic.RULE),
            row(70, CanonKind.CHALLENGE, CanonMechanic.CHALLENGE),
            row(71, CanonKind.LAW, CanonMechanic.RULE),
            row(72, CanonKind.PLAN, CanonMechanic.RULE),
            row(73, CanonKind.CHALLENGE, CanonMechanic.CHALLENGE, createdAt = startedAt + 1), // started while the reconciliation runs
            row(74, CanonKind.CHALLENGE, mechanic = null), // the Director's own challenge, which the engine does not track
        )

        val drop = reconcile.run(startup)

        assertEquals(emptySet(), drop)
        assertEquals(CanonStatus.CANCELLED to Reconcile.ROLLED_BACK, canon(70).status to canon(70).resolution)
        assertEquals(CanonStatus.RETIRED to Reconcile.ROLLED_BACK, canon(71).status to canon(71).resolution)
        assertEquals(CanonStatus.CANCELLED, canon(72).status)
        for (id in listOf(60L, 73L, 74L)) assertEquals(CanonStatus.ACTIVE, canon(id).status, "C-$id")
        assertEquals(listOf("C-70", "C-71", "C-72"), reconciled().map { it.first })
        val summary = services.traces.decisions.last()
        assertEquals(Reconcile.RECONCILED to "1 in the state, 5 open in the canon: 3 resolved, 0 to drop from the state", summary.decision to summary.reason)
    }

    @Test
    fun `an entry whose canon row is gone, or was closed before the start, is dropped, and one closed since is left to its part`() = runBlocking<Unit> {
        val running = MechanicsSamples.runningChallenge.copy(endsAt = startedAt + 10 * minute)
        val state = MechanicsSamples.state.copy(challenges = listOf(running))
        val (_, startup) = reconcile.startup(state, startedAt)
        put(
            row(50, CanonKind.CHALLENGE, CanonMechanic.CHALLENGE, status = CanonStatus.WON, resolvedAt = startedAt - 5 * minute), // an operator, while down
            // C-60 has no row any more (a Journal rolled back)
            row(61, CanonKind.PLAN, CanonMechanic.RULE, status = CanonStatus.FULFILLED, resolvedAt = startedAt + 2_000), // its part is finishing it
        )

        val drop = reconcile.run(startup)

        assertEquals(setOf(50L, 60L), drop)
        assertEquals(
            listOf(
                "C-50" to "dropped from the state: its canon was closed (won) before the server started",
                "C-60" to "dropped from the state: its canon row is missing",
            ),
            reconciled(),
        )
        assertTrue(memory.store.writes.isEmpty(), "nothing to resolve")

        val dropped = Reconcile.drop(state, drop)
        assertEquals(emptyList(), dropped.challenges)
        assertEquals(listOf(MechanicsSamples.welcomePlan), dropped.rules)
        assertTrue(Settlement(owner = MechanicsSamples.josiane.uuid.toString(), stake = 812) in dropped.settlements, "Josiane's stake goes back")
    }

    @Test
    fun `an unreadable entry has its canon row cancelled with the decoder's words`() = runBlocking<Unit> {
        val text = MechanicsState.encode(MechanicsState(rules = listOf(MechanicsSamples.griefLaw)))
            .replace("\"challenges\":[]", "\"challenges\":[{\"id\":55,\"status\":\"running\"}]")
        val state = MechanicsState.decode(text)
        assertEquals(listOf(55L), state.unreadable.map { it.id })

        val (_, startup) = reconcile.startup(state, startedAt)
        val unreadable = services.traces.decisions.single { it.decision == Reconcile.UNREADABLE }
        assertEquals("C-55", unreadable.subject)
        assertTrue(unreadable.reason!!.startsWith("challenge: "), unreadable.reason)
        put(row(55, CanonKind.CHALLENGE, CanonMechanic.CHALLENGE), row(60, CanonKind.LAW, CanonMechanic.RULE))

        reconcile.run(startup)

        assertEquals(CanonStatus.CANCELLED, canon(55).status)
        assertTrue(canon(55).resolution!!.startsWith("unreadable when the server started: "), canon(55).resolution)
        assertTrue(canon(55).resolution!!.length <= io.github.clemsix6.narrator2.memory.MemoryLimits.RESOLUTION)
        assertEquals(CanonStatus.ACTIVE, canon(60).status)
    }

    @Test
    fun `a state that could not be read at all leaves its rows cancelled as unreadable`() = runBlocking<Unit> {
        val (_, startup) = reconcile.startup(MechanicsState.decode("not json"), startedAt)
        put(row(70, CanonKind.CHALLENGE, CanonMechanic.CHALLENGE))

        reconcile.run(startup)

        assertTrue(canon(70).resolution!!.startsWith("unreadable when the server started: "), canon(70).resolution)
        assertEquals("state", services.traces.decisions.first { it.decision == Reconcile.UNREADABLE }.subject)
    }

    @Test
    fun `without the memory, nothing is reconciled`() = runBlocking<Unit> {
        val (_, startup) = reconcile.startup(MechanicsSamples.state, startedAt)
        eveningCanon()
        memory.available = false

        assertEquals(emptySet(), reconcile.run(startup))

        assertTrue(memory.store.writes.isEmpty())
        val skipped = services.traces.decisions.last()
        assertEquals(DecisionRecord.Kind.MECHANIC to Reconcile.RECONCILED, skipped.kind to skipped.decision)
        assertTrue(skipped.reason!!.startsWith("skipped: the memory is unavailable"), skipped.reason)
    }

    @Test
    fun `dropping a duel puts its duelists back at their next join, once, and leaves the state alone when nothing matches`() {
        val there = SavedPosition(MechanicsSamples.bartok.uuid.toString(), "minecraft:overworld", 1.0, 64.0, 1.0, 0f, 0f)
        val arenaDuel = MechanicsSamples.invitedDuel.copy(
            status = ChallengeStatus.RUNNING,
            arena = listOf(there, MechanicsSamples.state.returns.single().copy(x = 7.0)),
        )
        val state = MechanicsSamples.state.copy(challenges = listOf(arenaDuel))

        val dropped = Reconcile.drop(state, setOf(52L))

        assertEquals(emptyList(), dropped.challenges)
        assertEquals(MechanicsSamples.state.returns + there, dropped.returns, "Mimosa keeps her older return")
        assertSame(state, Reconcile.drop(state, setOf(99L)))
        assertEquals(listOf(MechanicsSamples.welcomePlan), Reconcile.drop(state, setOf(60L)).rules)
        assertEquals(
            listOf(Settlement(MechanicsSamples.josiane.uuid.toString(), 812)),
            Reconcile.drop(MechanicsSamples.state.copy(settlements = listOf(Settlement(MechanicsSamples.josiane.uuid.toString(), 812))), setOf(50L)).settlements,
            "a settlement already there is not doubled",
        )
    }

    @Test
    fun `a canon row the reconciliation must resolve but cannot find is reported`() = runBlocking<Unit> {
        val (_, startup) = reconcile.startup(MechanicsSamples.state, startedAt)

        reconcile.run(startup)

        assertEquals(
            listOf("C-50" to "no canon row to resolve (${Reconcile.DOWN})", "C-52" to "no canon row to resolve (${Reconcile.DOWN})"),
            reconciled().take(2),
        )
        assertTrue(memory.store.writes.isEmpty())
    }
}
