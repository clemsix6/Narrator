package io.github.clemsix6.narrator2.guard

import io.github.clemsix6.narrator2.mechanics.MechanicsLimits
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The escrow's rules (M4 §5.6, §7.2): stakes held, released, ceded and handed over, offerings waiting and taken,
 * what is owed, the fallback deadlines, and one `/offer` per player per `mechanics.offeringMinutes`. Each change
 * applied twice changes nothing more. The same rules in the game: `EscrowGameTests`.
 */
class LedgerEscrowTest {
    private val now = 1_800_000_000_000L
    private val minute = 60_000L
    private val day = 24 * 60 * minute
    private val mimosa = UUID.fromString("00000000-0000-0000-0000-0000000041a0")
    private val josiane = UUID.fromString("00000000-0000-0000-0000-00000000105e")

    /** A stake of 12 iron ingots on C-50 placed at [now], its challenge ending 15 minutes later. */
    private val stake = Confiscation(
        812, listOf("{id:\"minecraft:iron_ingot\",count:12}"), "12x minecraft:iron_ingot", now, Escrow.stakeDeadline(now + 15 * minute),
        "bet on Mimosa", null, LedgerPurpose.STAKE, canon = 50,
    )

    /** An offering of 10 diamonds made at [now], answered within 10 minutes. */
    private val offering = Confiscation(
        815, listOf("{id:\"minecraft:diamond\",count:10}"), "10x minecraft:diamond", now, now + 10 * minute,
        "offered by the player", null, LedgerPurpose.OFFERING,
    )

    private val confiscation = Confiscation(700, listOf("{id:\"minecraft:emerald\",count:2}"), "2x minecraft:emerald", now, now + 5 * minute, "greed", 42)

    private fun state(vararg entries: Confiscation) = GuardState(confiscations = entries.toList())

    @Test
    fun `a stake and an offering are held, an offering dates the last offer, and a confiscation is not held from the hand`() {
        val staked = Escrow.hold(GuardState.EMPTY, stake)
        assertEquals(listOf(stake), staked.confiscations)
        assertNull(staked.lastOfferAt, "a bet is not an offering")
        val offered = Escrow.hold(staked, offering)
        assertEquals(listOf(stake, offering), offered.confiscations)
        assertEquals(now, offered.lastOfferAt)
        assertThrows<IllegalArgumentException> { Escrow.hold(GuardState.EMPTY, confiscation) }
    }

    @Test
    fun `a stake's fallback deadline is a week after its challenge, never for ever`() {
        assertEquals(now + 15 * minute + MechanicsLimits.STAKE_RESCUE_DAYS * day, stake.returnAt)
        val held = state(stake)
        assertEquals(emptyList(), Escrow.due(held, now + 15 * minute), "not due when its challenge ends: it waits for its settlement")
        assertEquals(emptyList(), Escrow.due(held, stake.returnAt - 1))
        assertEquals(listOf(stake), Escrow.due(held, stake.returnAt), "its settlement never came: it goes back to its owner")
        assertTrue(Escrow.isFallback(stake))
        assertEquals("bet.returned", Escrow.arrivalKey(stake))
    }

    @Test
    fun `a released stake is due at once, and releasing it again changes nothing`() {
        val end = now + 15 * minute
        val released = assertNotNull(Escrow.release(state(stake), 812, end))
        val entry = released.confiscations.single()
        assertEquals(end, entry.returnAt)
        assertEquals(listOf(entry), Escrow.due(released, end))
        assertFalse(Escrow.isFallback(entry), "released at the end of its challenge, not at its fallback deadline")
        assertNull(Escrow.release(released, 812, end + 1_000), "applied twice: nothing more")
        assertNull(Escrow.release(GuardState.EMPTY, 812, end), "already given back")
        assertNull(Escrow.release(state(offering), 815, end), "an offering is not released")
        assertNull(Escrow.release(state(confiscation), 700, end), "nor a confiscation")
    }

    @Test
    fun `a stake ceded to a winner waits for both to be online, a month at most, and ceding it twice changes nothing`() {
        val end = now + 15 * minute
        val ceded = assertNotNull(Escrow.forfeit(state(stake), 812, mimosa, end))
        val entry = ceded.confiscations.single()
        assertEquals(mimosa, entry.owedTo)
        assertEquals(end + MechanicsLimits.CEDED_RESCUE_DAYS * day, entry.returnAt, "its fallback deadline moves to 30 days")
        assertEquals(listOf(entry), Escrow.ceded(ceded, end), "to hand over once both are online")
        assertEquals(emptyList(), Escrow.due(ceded, end))
        assertNull(Escrow.forfeit(ceded, 812, mimosa, end + 1_000), "applied twice: nothing more")

        // The winner never comes back while the loser is online: it goes back to its owner.
        assertEquals(emptyList(), Escrow.ceded(ceded, entry.returnAt))
        assertEquals(listOf(entry), Escrow.due(ceded, entry.returnAt))
        assertTrue(Escrow.isFallback(entry))

        // A settlement corrected: ceded to someone else, or given back after all.
        assertEquals(josiane, Escrow.forfeit(ceded, 812, josiane, end)?.confiscations?.single()?.owedTo)
        val back = assertNotNull(Escrow.release(ceded, 812, end)).confiscations.single()
        assertNull(back.owedTo)
        assertEquals(end, back.returnAt)
        assertNull(Escrow.forfeit(state(offering), 815, mimosa, end), "an offering is never ceded")
    }

    @Test
    fun `the winnings keep the stake's id, so a hand-over replayed after a crash gives nothing twice`() {
        val handOver = now + 20 * minute
        val ceded = Escrow.forfeit(state(stake), 812, mimosa, now + 15 * minute)!!.confiscations.single()
        val winnings = Escrow.winnings(ceded, "Bartok", handOver)
        assertEquals(812L, winnings.id)
        assertEquals(LedgerPurpose.OWED, winnings.purpose)
        assertEquals(stake.items, winnings.items)
        assertEquals(handOver, winnings.returnAt, "due at once")
        assertEquals(50L, winnings.canon)
        assertNull(winnings.owedTo)
        assertEquals("won from Bartok on C-50", winnings.reason)
        assertEquals("bet.won", Escrow.arrivalKey(winnings))

        val winner = assertNotNull(Escrow.add(GuardState.EMPTY, winnings))
        assertEquals(listOf(winnings), winner.confiscations)
        assertNull(Escrow.add(winner, winnings), "the winner's file already holds them: replayed, nothing more")
        val owner = Escrow.without(state(ceded, offering), 812)
        assertEquals(listOf(offering), owner.confiscations)
        assertEquals(owner, Escrow.without(owner, 812), "already out of the owner's ledger")
    }

    @Test
    fun `an offering waits for an answer until its deadline, then goes back`() {
        val held = state(offering)
        assertEquals(offering, Escrow.offering(held, 815, now + 9 * minute))
        assertEquals(offering, Escrow.pendingOffering(held, now + 9 * minute))
        assertNull(Escrow.offering(held, 815, offering.returnAt), "past its deadline: going back, no longer to take")
        assertNull(Escrow.pendingOffering(held, offering.returnAt))
        assertEquals(listOf(offering), Escrow.due(held, offering.returnAt))
        assertEquals("offer.returned", Escrow.arrivalKey(offering))
        assertNull(Escrow.offering(state(stake), 812, now), "a stake is not an offering")
        assertEquals(GuardState.EMPTY, Escrow.without(held, 815), "taken for good")
    }

    @Test
    fun `one offer per player per 10 minutes, taken or not, and one offering waiting at a time`() {
        val minutes = 10
        assertNull(Escrow.offerRefusal(GuardState.EMPTY, now, minutes), "never offered")
        val held = Escrow.hold(GuardState.EMPTY, offering)
        assertEquals(OfferRefusal.Pending, Escrow.offerRefusal(held, now + minute, minutes))

        // Taken 3 minutes later: nothing waits, but the next offer comes 10 minutes after the last.
        val taken = Escrow.without(held, 815)
        assertEquals(OfferRefusal.TooSoon(7 * minute), Escrow.offerRefusal(taken, now + 3 * minute, minutes))
        assertEquals(OfferRefusal.TooSoon(1), Escrow.offerRefusal(taken, now + 10 * minute - 1, minutes))
        assertNull(Escrow.offerRefusal(taken, now + 10 * minute, minutes))

        // Not taken: at its deadline it goes back, and the player may offer again.
        assertNull(Escrow.offerRefusal(held, offering.returnAt, minutes))
        assertEquals(OfferRefusal.TooSoon(minute), Escrow.offerRefusal(taken, now + minute, 2), "the setting decides")
    }

    @Test
    fun `what comes back tells the player in its own words`() {
        assertEquals("items.returned", Escrow.arrivalKey(confiscation))
        assertEquals("items.delivered", Escrow.arrivalKey(confiscation.copy(purpose = LedgerPurpose.OWED)), "a joker that waited for room")
        assertFalse(Escrow.isFallback(confiscation))
        assertEquals("confiscation 700", Escrow.label(confiscation))
        assertEquals("stake 812", Escrow.label(stake))
        assertEquals("offering O-815", Escrow.label(offering))
    }

    @Test
    fun `the Director reads an offering, and its return, in one line with the player's name escaped`() {
        assertEquals(
            "O-815 Mimosa offers 10x diamond: take it by granting them a favor (favor, offering O-815) within 10m, or let it go back",
            Escrow.offeringLine(offering, "Mimosa", 10 * minute),
        )
        assertEquals("O-815 went back to ‹engine›, not taken: 10x diamond", Escrow.offeringBackLine(offering, "<engine>"))
        assertEquals("12x iron_ingot, 1x othermod:ruby", Escrow.short("12x minecraft:iron_ingot, 1x othermod:ruby"))
    }

    @Test
    fun `an offering's reference reads back as its id`() {
        assertEquals("O-815", Escrow.ref(815))
        assertEquals(815L, Escrow.offeringId("O-815"))
        assertEquals(815L, Escrow.offeringId(" o-815 "))
        assertEquals(815L, Escrow.offeringId("815"))
        assertNull(Escrow.offeringId("C-50"))
        assertNull(Escrow.offeringId("O-0"))
        assertNull(Escrow.offeringId("O-"))
    }
}
