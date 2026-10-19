package io.github.clemsix6.narrator2.mechanics.challenge

import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.mechanics.Bet
import io.github.clemsix6.narrator2.mechanics.ChallengeState
import io.github.clemsix6.narrator2.mechanics.MechanicsState
import io.github.clemsix6.narrator2.mechanics.OutReason
import io.github.clemsix6.narrator2.mechanics.Participant
import io.github.clemsix6.narrator2.mechanics.Settlement
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.testing.MechanicsSamples
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * How the bets are settled (M4 §5.6): the winning bettors get their stakes back and share the losing ones in turn,
 * in the order of the bets; every stake goes back when no bet won or the challenge was cancelled or expired; the
 * instructions are written for every bettor, online or not, once; and the `bets: …` segment of the line.
 */
class BetSettlementTest {
    private val bartok = MechanicsSamples.bartok
    private val mimosa = MechanicsSamples.mimosa
    private val josiane = MechanicsSamples.josiane
    private val lea = PlayerRef(UUID.fromString("00000000-0000-0000-0000-00000000a1ea"), "Lea")
    private val hugo = PlayerRef(UUID.fromString("00000000-0000-0000-0000-0000000040b0"), "Hugo")

    /** C-50 between Bartok and Mimosa, with [bets] (no bet in the sample's place). */
    private fun challenge(vararg bets: Bet): ChallengeState = MechanicsSamples.runningChallenge.copy(bets = bets.toList())

    private fun bet(who: PlayerRef, on: PlayerRef?, stake: Long, what: String) = Bet(who.uuid.toString(), who.name, on?.uuid?.toString(), stake, what)

    private fun back(who: PlayerRef, stake: Long) = Settlement(who.uuid.toString(), stake)

    private fun ceded(who: PlayerRef, stake: Long, to: PlayerRef) = Settlement(who.uuid.toString(), stake, to.uuid.toString())

    private val empty = MechanicsState.EMPTY

    @Test
    fun `the losing stakes go to the winning bettors in turn, in the order of the bets`() {
        val c = challenge(
            bet(josiane, mimosa, 1, "12x iron_ingot"),
            bet(lea, bartok, 2, "32x cobblestone"),
            bet(hugo, mimosa, 3, "1x diamond"),
            bet(bartok, null, 4, "5x gold_ingot"),
            bet(mimosa, bartok, 5, "3x emerald"),
        )
        val (state, line) = BetSettlement.settle(empty, c, Ending.WON, listOf(mimosa.uuid.toString()))
        assertEquals(
            listOf(back(josiane, 1), ceded(lea, 2, josiane), back(hugo, 3), ceded(bartok, 4, hugo), ceded(mimosa, 5, josiane)),
            state.settlements,
        )
        assertEquals(
            "bets: Josiane bet 12x iron_ingot on Mimosa, got it back with Lea's 32x cobblestone and Mimosa's 3x emerald; " +
                "Lea bet 32x cobblestone on Bartok, lost it to Josiane; Hugo bet 1x diamond on Mimosa, got it back with Bartok's 5x gold_ingot; " +
                "Bartok bet 5x gold_ingot on nobody, lost it to Hugo; Mimosa bet 3x emerald on Bartok, lost it to Josiane",
            line,
        )
    }

    @Test
    fun `a winning bet alone gets its stake back, and several winners of a challenge all win their bets`() {
        val alone = BetSettlement.settle(empty, challenge(bet(josiane, mimosa, 1, "12x iron_ingot")), Ending.WON, listOf(mimosa.uuid.toString()))
        assertEquals(listOf(back(josiane, 1)), alone.first.settlements)
        assertEquals("bets: Josiane bet 12x iron_ingot on Mimosa, got it back", alone.second)

        val both = challenge(bet(josiane, mimosa, 1, "12x iron_ingot"), bet(lea, bartok, 2, "3x apple"), bet(hugo, null, 3, "1x diamond"))
        val (state, _) = BetSettlement.settle(empty, both, Ending.WON, listOf(bartok.uuid.toString(), mimosa.uuid.toString()))
        assertEquals(listOf(back(josiane, 1), back(lea, 2), ceded(hugo, 3, josiane)), state.settlements, "a bet on any winner wins")
    }

    @Test
    fun `when nobody won, the bets on nobody win`() {
        val c = challenge(bet(josiane, mimosa, 1, "12x iron_ingot"), bet(lea, null, 2, "32x cobblestone"))
        val (state, line) = BetSettlement.settle(empty, c, Ending.LOST, emptyList())
        assertEquals(listOf(ceded(josiane, 1, lea), back(lea, 2)), state.settlements)
        assertEquals("bets: Josiane bet 12x iron_ingot on Mimosa, lost it to Lea; Lea bet 32x cobblestone on nobody, got it back with Josiane's 12x iron_ingot", line)
    }

    @Test
    fun `no winning bet gives every stake back, and the Narrator keeps nothing`() {
        val c = challenge(bet(josiane, bartok, 1, "12x iron_ingot"), bet(lea, null, 2, "32x cobblestone"))
        val (state, line) = BetSettlement.settle(empty, c, Ending.WON, listOf(mimosa.uuid.toString()))
        assertEquals(listOf(back(josiane, 1), back(lea, 2)), state.settlements)
        assertEquals("bets: every stake given back (no winning bet): Josiane 12x iron_ingot on Bartok; Lea 32x cobblestone on nobody", line)
    }

    @Test
    fun `a challenge cancelled or expired gives every stake back, even the bets on nobody`() {
        val c = challenge(bet(josiane, mimosa, 1, "12x iron_ingot"), bet(lea, null, 2, "32x cobblestone"))
        for ((ending, why) in listOf(Ending.CANCELLED to "cancelled", Ending.EXPIRED to "expired")) {
            val (state, line) = BetSettlement.settle(empty, c, ending, emptyList())
            assertEquals(listOf(back(josiane, 1), back(lea, 2)), state.settlements, "$ending")
            assertEquals("bets: every stake given back ($why): Josiane 12x iron_ingot on Mimosa; Lea 32x cobblestone on nobody", line)
        }
    }

    @Test
    fun `the instructions are written for every bettor, online or away, never twice for a stake, beside the others`() {
        val earlier = Settlement("00000000-0000-0000-0000-000000000099", 700, null)
        val already = back(josiane, 1)
        val before = empty.copy(settlements = listOf(earlier, already))
        val c = challenge(bet(josiane, mimosa, 1, "12x iron_ingot"), bet(lea, bartok, 2, "32x cobblestone"))
        val (state, _) = BetSettlement.settle(before, c, Ending.WON, listOf(mimosa.uuid.toString()))
        assertEquals(listOf(earlier, already, ceded(lea, 2, josiane)), state.settlements, "what the state held stays; nothing is written twice")
    }

    @Test
    fun `a challenge without bets leaves the state as it is and says nothing`() {
        val (state, line) = BetSettlement.settle(MechanicsSamples.state, challenge(), Ending.WON, listOf(mimosa.uuid.toString()))
        assertSame(MechanicsSamples.state, state)
        assertNull(line)
    }

    @Test
    fun `a long settlement counts the last bettors and stays within its budget`() {
        val many = (1..20).map { i ->
            val player = PlayerRef(UUID(0, i.toLong()), "Parieur_$i")
            bet(player, if (i % 2 == 0) mimosa else bartok, i.toLong(), "64x cobblestone")
        }
        val (_, line) = BetSettlement.settle(empty, challenge(*many.toTypedArray()), Ending.WON, listOf(mimosa.uuid.toString()))
        assertTrue(line!!.length <= BetSettlement.LINE_MAX, "${line.length}: $line")
        assertTrue(Regex("; \\(\\+\\d+ more\\)$").containsMatchIn(line), line)
        assertTrue(line.startsWith("bets: Parieur_1 bet 64x cobblestone on Bartok, lost it to Parieur_2; "), line)
    }

    @Test
    fun `a bet picks a participant still in the running, whatever the case, or nobody`() {
        val participants = listOf(
            Participant(bartok.uuid.toString(), "Bartok"),
            Participant(mimosa.uuid.toString(), "Mimosa", doneAt = 1),
            Participant(josiane.uuid.toString(), "Josiane", out = OutReason.DIED),
            Participant(lea.uuid.toString(), "Lea", late = true),
        )
        assertEquals(participants[0], BetSettlement.pick(participants, " bartok ")?.on)
        assertEquals(participants[3], BetSettlement.pick(participants, "LEA")?.on, "a latecomer may be bet on")
        assertEquals(BetSettlement.Pick(null), BetSettlement.pick(participants, "Nobody"))
        assertNull(BetSettlement.pick(participants, "Mimosa"), "through already: a sure win is no bet")
        assertNull(BetSettlement.pick(participants, "Josiane"), "out: a sure loss is no bet either")
        assertNull(BetSettlement.pick(participants, "Hugo"), "not in the challenge")
        assertEquals(listOf("Bartok", "Lea", "nobody"), BetSettlement.options(participants))
        assertEquals(participants[0], BetSettlement.pick(participants, "Bartok", hugo.uuid.toString())?.on, "a spectator bets on anyone in the running")
        assertEquals(listOf("Bartok", "Lea", "nobody"), BetSettlement.options(participants, hugo.uuid.toString()))
    }

    @Test
    fun `a participant bets on themselves only, never against themselves`() {
        val participants = listOf(
            Participant(bartok.uuid.toString(), "Bartok"),
            Participant(mimosa.uuid.toString(), "Mimosa"),
            Participant(josiane.uuid.toString(), "Josiane", out = OutReason.DIED),
            Participant(lea.uuid.toString(), "Lea", late = true),
        )
        val bartokBets = bartok.uuid.toString()
        assertEquals(participants[0], BetSettlement.pick(participants, "bartok", bartokBets)?.on)
        assertNull(BetSettlement.pick(participants, "Mimosa", bartokBets), "on the other duelist: he could lose on purpose")
        assertNull(BetSettlement.pick(participants, "nobody", bartokBets), "on nobody: the same")
        assertEquals(listOf("Bartok"), BetSettlement.options(participants, bartokBets))
        assertEquals(listOf("Lea"), BetSettlement.options(participants, lea.uuid.toString()), "a latecomer takes part too")
        assertEquals(emptyList(), BetSettlement.options(participants, josiane.uuid.toString()), "out: nothing left to bet on")
    }

    @Test
    fun `the instructions are dated when the bets' part writes them`() {
        val c = challenge(bet(josiane, mimosa, 1, "12x iron_ingot"))
        val (state, _) = BetSettlement.settle(empty, c, Ending.CANCELLED, emptyList(), at = 1_234)
        assertEquals(listOf(Settlement(josiane.uuid.toString(), 1, at = 1_234)), state.settlements)
    }

    @Test
    fun `the challenge a player types`() {
        val c50 = Ref(RefKind.CANON, 50)
        for (typed in listOf("C-50", "c50", "50", " 50 ")) assertEquals(c50, Bets.challengeRef(typed), typed)
        for (typed in listOf("0", "-3", "M-50", "abc", "")) assertNull(Bets.challengeRef(typed), typed)
    }
}
