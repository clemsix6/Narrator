package io.github.clemsix6.narrator2.mechanics.challenge

import io.github.clemsix6.narrator2.mechanics.ChallengeStatus
import io.github.clemsix6.narrator2.mechanics.ChallengeView
import io.github.clemsix6.narrator2.mechanics.MechanicsLimits
import io.github.clemsix6.narrator2.mechanics.Participant
import io.github.clemsix6.narrator2.mechanics.SavedPosition
import io.github.clemsix6.narrator2.memory.MemoryLimits
import io.github.clemsix6.narrator2.testing.MechanicsSamples
import io.github.clemsix6.narrator2.testing.TestClock
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The duels' lines for the model (M4 §4.4, §12.3): the invitation, the proposal `propose_duel` answers, the start,
 * and how a duel ended, which is both the `DUEL` line after its reference and the canon's resolution.
 */
class DuelLinesTest {
    private val minute = 60_000L
    private val evening = MechanicsSamples.evening
    private val clock = TestClock(Instant.ofEpochMilli(evening), ZoneId.of("Europe/Paris"))
    private val invited = MechanicsSamples.invitedDuel
    private val bartok = invited.participants[0]
    private val mimosa = invited.participants[1]

    /** C-52 as it runs: started at 20:10, until 20:15, both in the arena. */
    private val running = invited.copy(
        status = ChallengeStatus.RUNNING,
        startedAt = evening + 10 * minute,
        endsAt = evening + 15 * minute,
        participants = invited.participants.map { it.copy(accepted = true) },
        betsCloseAt = evening + 11 * minute,
        arena = invited.participants.map { SavedPosition(it.uuid, "minecraft:overworld", 1.5, 64.0, 2.5, 0f, 0f) },
    )

    @Test
    fun `the invitation and the proposal say who, where and until when`() {
        assertEquals(
            "C-52 invited: Bartok and Mimosa « Duel au soleil », in the arena x 112 y 70 z -40; invitations end 20:06 (in 2m)",
            DuelLines.invited(invited, clock),
        )
        val view = ChallengeView(invited.ref, null, invited.duel, invited.status, invited.endsAt, invited.participants, 0, "")
        assertEquals(
            "duel C-52 proposed to Bartok and Mimosa (« Duel au soleil », in the arena x 112 y 70 z -40 · reward 1x golden_apple · bets); " +
                "invitations end 20:06 (in 2m); a DUEL event tells you whether it starts",
            DuelLines.proposed(view, evening + 4 * minute, clock),
        )
        val here = view.copy(duel = invited.duel!!.copy(arena = null, reward = null, bets = false))
        assertEquals(
            "duel C-52 proposed to Bartok and Mimosa (« Duel au soleil », where they stand); invitations end 20:06 (in 1m); a DUEL event tells you whether it starts",
            DuelLines.proposed(here, evening + 5 * minute, clock),
        )
    }

    @Test
    fun `the start says until when they fight, where, and when the bets close`() {
        assertEquals(
            "C-52 started: Bartok and Mimosa accepted; they fight until 20:15 (in 5m), in the arena x 112 y 70 z -40 · bets open until 20:11",
            DuelLines.started(running, clock),
        )
    }

    @Test
    fun `each end says who won and how, and that nobody died`() {
        val now = evening + 12 * minute + 10_000
        assertEquals(
            "won by Mimosa in 2m10s: Mimosa's fatal blow on Bartok was stopped at 1 hp; both put back where they were",
            DuelLines.blow(running, mimosa, bartok, now),
        )
        assertEquals(
            "won by Mimosa in 2m10s: Bartok would have died (lava) and was rescued instead; both put back where they were",
            DuelLines.rescued(running, mimosa, bartok, "lava", now),
        )
        assertEquals("won by Bartok in 2m10s: Mimosa left the server; both put back where they were", DuelLines.left(running, bartok, mimosa, now))
        assertEquals("lost: a draw, nobody won within 5m; both put back where they were", DuelLines.draw(running))
        assertEquals("lost: a draw, nobody won within 5m", DuelLines.draw(running.copy(arena = emptyList())), "nobody to put back without an arena")
    }

    @Test
    fun `a death decided from outside the duel, or nobody back after a restart, cancels it`() {
        assertEquals(
            "cancelled: Josiane interfered; Bartok would have died (player_attack by Josiane) and was rescued instead; both put back where they were",
            DuelLines.outside(running, bartok, "player_attack by Josiane", "Josiane"),
        )
        assertEquals(
            "cancelled: the Narrator's zombie interfered; Bartok would have died (mob_attack by zombie) and was rescued instead",
            DuelLines.outside(running.copy(arena = emptyList()), bartok, "mob_attack by zombie", "the Narrator's zombie"),
        )
        assertEquals("cancelled: neither Bartok nor Mimosa came back after the restart; both put back where they were", DuelLines.notBack(running))
    }

    @Test
    fun `a duelist is in a duel only while it runs`() {
        val view = ChallengeView(running.ref, null, running.duel, running.status, running.endsAt, running.participants, 0, "")
        val challenge = MechanicsSamples.runningChallenge
        val notADuel = ChallengeView(challenge.ref, challenge.spec, null, challenge.status, challenge.endsAt, challenge.participants, 1, "")
        assertEquals(view, Duels.runningDuelOf(listOf(notADuel, view), bartok.playerId))
        assertEquals(view, Duels.runningDuelOf(listOf(view), mimosa.playerId))
        assertEquals(null, Duels.runningDuelOf(listOf(view.copy(status = ChallengeStatus.INVITED)), bartok.playerId), "an invitation is no fight yet")
        assertEquals(null, Duels.runningDuelOf(listOf(view), MechanicsSamples.josiane.uuid), "not a duelist")
        assertEquals(null, Duels.runningDuelOf(listOf(notADuel), bartok.playerId), "a challenge is no duel")
    }

    @Test
    fun `a duel that never started is cancelled, and says why`() {
        assertEquals("cancelled: Mimosa declined", DuelLines.declined(mimosa))
        assertEquals("cancelled: Mimosa did not accept within 2m (Bartok did)", DuelLines.expired(invited))
        assertEquals(
            "cancelled: nobody accepted within 2m",
            DuelLines.expired(invited.copy(participants = invited.participants.map { it.copy(accepted = false) })),
        )
        assertEquals("cancelled: Bartok left before it started", DuelLines.leftEarly(bartok))
        assertEquals("cancelled: Bartok was killed by an operator (/kill)", DuelLines.killed(bartok))
    }

    @Test
    fun `names are escaped, and the longest end fits the canon's resolution and the event line`() {
        val long = "Abcdefghijklmnop"
        val sly = Participant(bartok.uuid, "<b>Bart\nok")
        val them = running.copy(participants = listOf(sly, Participant(mimosa.uuid, long, accepted = true)))
        val blow = DuelLines.blow(them, them.participants[1], sly, running.endsAt)
        assertTrue("<b>" !in blow && "\n" !in blow, "escaped: $blow")
        val longest = listOf(
            DuelLines.blow(them, them.participants[1], them.participants[1], running.endsAt),
            DuelLines.rescued(them, them.participants[1], them.participants[1], "mob_attack by $long", running.endsAt),
            DuelLines.outside(them, them.participants[1], "mob_projectile by zombified_piglin", "the Narrator's zombified_piglin"),
            DuelLines.notBack(them.copy(participants = listOf(Participant(bartok.uuid, long), Participant(mimosa.uuid, long)))),
            DuelLines.expired(invited.copy(participants = listOf(Participant(bartok.uuid, long, accepted = true), Participant(mimosa.uuid, long)))),
        )
        for (line in longest) {
            assertTrue(line.length <= MemoryLimits.RESOLUTION, "${line.length}: $line")
            assertTrue("C-52 $line".length <= MechanicsLimits.EVENT_LINE, line)
        }
    }
}
