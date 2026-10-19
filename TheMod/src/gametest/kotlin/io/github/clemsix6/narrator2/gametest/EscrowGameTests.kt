package io.github.clemsix6.narrator2.gametest

import com.mojang.authlib.GameProfile
import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.command.OfferCommand
import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.config.UiTexts
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.director.MechanicSignal
import io.github.clemsix6.narrator2.gametest.GuardGameTests.Companion.count
import io.github.clemsix6.narrator2.gametest.GuardGameTests.Companion.find
import io.github.clemsix6.narrator2.gametest.GuardGameTests.Companion.guards
import io.github.clemsix6.narrator2.gametest.GuardGameTests.Companion.namedSword
import io.github.clemsix6.narrator2.gametest.GuardGameTests.Companion.reconnect
import io.github.clemsix6.narrator2.guard.Confiscation
import io.github.clemsix6.narrator2.guard.Escrow
import io.github.clemsix6.narrator2.guard.GuardState
import io.github.clemsix6.narrator2.guard.GuardStates
import io.github.clemsix6.narrator2.guard.ItemLedger
import io.github.clemsix6.narrator2.guard.LedgerPurpose
import io.github.clemsix6.narrator2.testing.FakeDirector
import io.github.clemsix6.narrator2.testing.FakeMechanics
import io.github.clemsix6.narrator2.testing.MemoryTraceSink
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import io.netty.channel.embedded.EmbeddedChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.component.DataComponents
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.StringTag
import net.minecraft.network.Connection
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.server.level.ClientInformation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.server.network.Filterable
import net.minecraft.server.players.NameAndId
import net.minecraft.util.ProblemReporter
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.BundleContents
import net.minecraft.world.item.component.CustomData
import net.minecraft.world.item.component.ItemContainerContents
import net.minecraft.world.item.component.WritableBookContent
import net.minecraft.world.item.component.WrittenBookContent
import net.minecraft.world.level.storage.TagValueInput
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import java.util.stream.Stream

/**
 * In-game tests of the escrow (M4 §5.6, §7.2, M4-15): stakes held from the hand, released, ceded and handed over
 * to a winner, offerings made with `/offer`, taken for a favor or given back, and what is owed waiting for room.
 * Whatever happens, every item goes back to its owner or reaches its winner, with a trace: the player offline, the
 * server restarting (the player saved, then loaded back from their file), a full inventory (it waits in the ledger,
 * never on the ground), and the fallback deadline.
 *
 * Most tests drive a ledger of their own ([Escrow]): its decisions are kept in memory, its news for the Director are
 * recorded, and it reads the same player attachment as the guards of the server, whose once-a-second check never
 * runs within a test's synchronous body. Two tests go through the server's own guards and hooks, and read their
 * trace in the Journal. Owned by M4-15.
 */
class EscrowGameTests {

    /** A stake leaves the hand in the same tick as it is written, and a release gives it back whole, once. */
    @GameTest
    fun aStakeIsHeldFromTheHandAndGivenBackWholeWhenReleased(helper: GameTestHelper) = withPlayers(helper, 1) { (bettor) ->
        val escrow = Escrowed()
        val player = bettor.player
        val now = escrow.clock.millis()
        val deadline = Escrow.stakeDeadline(now + 15 * MINUTE)
        val hold = { escrow.ledger.hold(player, InteractionHand.MAIN_HAND, LedgerPurpose.STAKE, deadline, 50, "bet on Mimosa") }

        helper.assertEquals(null, hold(), "an empty hand stakes nothing")
        player.setItemInHand(InteractionHand.MAIN_HAND, joker())
        helper.assertEquals(null, hold(), "a joker is not staked")
        helper.assertTrue(ItemLedger.isJoker(player.mainHandItem), "the joker stays in hand")

        val sword = namedSword(helper)
        player.setItemInHand(InteractionHand.MAIN_HAND, sword.copy())
        val stake = hold()!!
        helper.assertTrue(player.mainHandItem.isEmpty, "the hand is emptied")
        helper.assertEquals(listOf(stake), GuardStates.read(player).confiscations, "written in the player's state in the same tick")
        helper.assertEquals(LedgerPurpose.STAKE, stake.purpose, "a stake")
        helper.assertEquals(50L, stake.canon, "on C-50")
        helper.assertEquals(deadline, stake.returnAt, "its fallback deadline, never for ever")

        helper.assertTrue(escrow.ledger.release(player, stake.id, now + 15 * MINUTE), "released at the end of its challenge")
        helper.assertTrue(ItemStack.matches(sword, find(player, Items.IRON_SWORD)), "back at once, as it was: ${find(player, Items.IRON_SWORD)}")
        helper.assertEquals(emptyList<Confiscation>(), escrow.ledger.held(player), "the ledger is empty")
        helper.assertFalse(escrow.ledger.release(player, stake.id, now + 16 * MINUTE), "released twice: nothing more")
        helper.assertEquals(1, count(player, Items.IRON_SWORD), "given back once")
        helper.assertEquals(listOf("held", "released", "returned"), escrow.decisionsOf(stake.id), "traced")
        helper.assertTrue(bettor.told("bet.returned"), "the bettor is told: ${bettor.lines()}")
    }

    /** A stake whose settlement never comes goes back to its owner a week after its challenge; one ceded to a winner who never shows up, after 30 days. */
    @GameTest
    fun aStakeGoesBackToItsOwnerAtItsFallbackDeadline(helper: GameTestHelper) = withPlayers(helper, 1) { (bettor) ->
        val escrow = Escrowed()
        val player = bettor.player
        val now = escrow.clock.millis()
        val end = now + 15 * MINUTE

        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.IRON_INGOT, 12))
        val lost = escrow.ledger.hold(player, InteractionHand.MAIN_HAND, LedgerPurpose.STAKE, Escrow.stakeDeadline(end), 50, "bet on Mimosa")!!
        escrow.ledger.giveBack(player, end + DAY)
        helper.assertEquals(0, count(player, Items.IRON_INGOT), "a day later, still waiting for its settlement")
        escrow.ledger.giveBack(player, lost.returnAt - 1)
        helper.assertEquals(0, count(player, Items.IRON_INGOT), "not before its deadline")
        escrow.ledger.giveBack(player, lost.returnAt)
        helper.assertEquals(12, count(player, Items.IRON_INGOT), "back to its owner at its deadline")
        helper.assertEquals(listOf("held", "returned"), escrow.decisionsOf(lost.id), "traced")
        helper.assertTrue(escrow.reasonOf(lost.id, "returned").contains("fallback deadline"), "the trace says why: ${escrow.reasonOf(lost.id, "returned")}")

        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.GOLD_INGOT, 5))
        val ceded = escrow.ledger.hold(player, InteractionHand.MAIN_HAND, LedgerPurpose.STAKE, Escrow.stakeDeadline(end), 51, "bet on nobody")!!
        helper.assertTrue(escrow.ledger.forfeit(player, ceded.id, UUID.randomUUID(), end), "ceded to a winner who never comes")
        escrow.ledger.giveBack(player, end + 20 * DAY)
        helper.assertEquals(0, count(player, Items.GOLD_INGOT), "20 days later, still for the winner")
        escrow.ledger.giveBack(player, Escrow.cededDeadline(end))
        helper.assertEquals(5, count(player, Items.GOLD_INGOT), "30 days later, back to its owner")
        helper.assertEquals(listOf("held", "forfeited", "returned"), escrow.decisionsOf(ceded.id), "traced")
        helper.assertEquals(emptyList<Confiscation>(), escrow.ledger.held(player), "the ledger is empty")
    }

    /** A stake lost while the winner is away stays with its owner, and goes to the winner once both are online. */
    @GameTest
    fun aLostStakeGoesToItsWinnerOnceBothAreOnline(helper: GameTestHelper) = withPlayers(helper, 2) { (loser, away) ->
        val escrow = Escrowed()
        val now = escrow.clock.millis()
        val player = loser.player
        val winnerId = away.uuid
        val saved = away.player.nameAndId()
        away.close() // the winner leaves: the game saves them

        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.IRON_INGOT, 12))
        val stake = escrow.ledger.hold(player, InteractionHand.MAIN_HAND, LedgerPurpose.STAKE, Escrow.stakeDeadline(now), 50, "bet on Bartok")!!
        helper.assertTrue(escrow.ledger.forfeit(player, stake.id, winnerId, now), "ceded to the winner")
        helper.assertFalse(escrow.ledger.forfeit(player, stake.id, winnerId, now), "ceded twice: nothing more")
        escrow.ledger.giveBack(player, now + MINUTE)
        helper.assertEquals(listOf(winnerId), escrow.ledger.held(player).map { it.owedTo }, "the winner is away: it waits in the loser's ledger")

        val winner = comeBack(helper, saved)
        escrow.ledger.giveBack(player, now + 2 * MINUTE)
        helper.assertEquals(emptyList<Confiscation>(), escrow.ledger.held(player), "out of the loser's ledger")
        helper.assertEquals(12, count(winner, Items.IRON_INGOT), "in the winner's inventory")
        helper.assertEquals(emptyList<Confiscation>(), escrow.ledger.held(winner), "nothing left in the winner's ledger")
        helper.assertEquals(listOf("held", "forfeited", "transferred", "delivered"), escrow.decisionsOf(stake.id), "traced")
        helper.assertTrue(loser.told("bet.lost"), "the loser is told: ${loser.lines()}")
        helper.assertFalse(escrow.ledger.forfeit(player, stake.id, winnerId, now), "handed over: nothing left to cede")
    }

    /** The winner's inventory is full: the winnings wait in their own ledger, never on the ground, and come once there is room. */
    @GameTest
    fun aWinnerWithAFullInventoryGetsTheWinningsOnceThereIsRoom(helper: GameTestHelper) = withPlayers(helper, 2) { (loser, winner) ->
        val escrow = Escrowed()
        val now = escrow.clock.millis()
        fill(winner.player)
        loser.player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.IRON_INGOT, 12))
        val stake = escrow.ledger.hold(loser.player, InteractionHand.MAIN_HAND, LedgerPurpose.STAKE, Escrow.stakeDeadline(now), 50, "bet on Bartok")!!

        helper.assertTrue(escrow.ledger.forfeit(loser.player, stake.id, winner.uuid, now), "ceded, both online: handed over at once")
        helper.assertEquals(emptyList<Confiscation>(), escrow.ledger.held(loser.player), "out of the loser's ledger")
        val owed = escrow.ledger.held(winner.player).single()
        helper.assertEquals(stake.id, owed.id, "under the stake's own id")
        helper.assertEquals(LedgerPurpose.OWED, owed.purpose, "owed to the winner")
        helper.assertEquals("12x minecraft:iron_ingot", owed.what, "all of it")
        helper.assertEquals(0, count(winner.player, Items.IRON_INGOT), "no room")
        helper.assertTrue(winner.told("items.no_room"), "the winner is told it waits: ${winner.lines()}")

        escrow.ledger.giveBack(winner.player, now + 1_000)
        helper.assertEquals(listOf(owed), escrow.ledger.held(winner.player), "still no room: it waits")
        winner.player.inventory.setItem(7, ItemStack.EMPTY)
        escrow.ledger.giveBack(winner.player, now + 2_000)
        helper.assertEquals(12, count(winner.player, Items.IRON_INGOT), "delivered once there is room")
        helper.assertEquals(emptyList<Confiscation>(), escrow.ledger.held(winner.player), "the winner's ledger is empty")
        helper.assertTrue(winner.told("bet.won"), "the winner is told: ${winner.lines()}")
        helper.assertEquals(listOf("held", "forfeited", "transferred", "delivered"), escrow.decisionsOf(stake.id), "traced")
    }

    /**
     * A crash between the two saves of a hand-over: the winner's file already holds the winnings, the loser's still the
     * stake. Replayed, the hand-over finds them there and gives nothing twice.
     */
    @GameTest
    fun aHandOverReplayedAfterACrashGivesNothingTwice(helper: GameTestHelper) = withPlayers(helper, 2) { (loser, winner) ->
        val escrow = Escrowed()
        val now = escrow.clock.millis()
        val stake = Confiscation(
            7_001, listOf("""{id:"minecraft:iron_ingot",count:12}"""), "12x minecraft:iron_ingot", now, Escrow.cededDeadline(now), "bet on Bartok", null,
            LedgerPurpose.STAKE, canon = 50, owedTo = winner.uuid,
        )
        escrow.states.update(loser.player) { it.copy(confiscations = listOf(stake)) }
        escrow.states.update(winner.player) { it.copy(confiscations = listOf(Escrow.winnings(stake, loser.name, now))) }

        escrow.ledger.giveBack(loser.player, now + 1_000)
        helper.assertEquals(emptyList<Confiscation>(), escrow.ledger.held(loser.player), "out of the loser's ledger")
        helper.assertEquals(12, count(winner.player, Items.IRON_INGOT), "the winner gets them once")
        escrow.ledger.giveBack(loser.player, now + 2_000)
        escrow.ledger.giveBack(winner.player, now + 2_000)
        helper.assertEquals(12, count(winner.player, Items.IRON_INGOT), "and never twice")
        helper.assertEquals(emptyList<Confiscation>(), escrow.ledger.held(winner.player), "the winner's ledger is empty")
        helper.assertEquals(listOf("transferred", "delivered"), escrow.decisionsOf(stake.id), "traced")
        helper.assertTrue(escrow.reasonOf(stake.id, "transferred").contains("replayed"), "the trace says it: ${escrow.reasonOf(stake.id, "transferred")}")
    }

    /**
     * A restart (the player saved, then loaded back from their file): stakes and offerings are in the player's file,
     * with the inventory they left. The offering's deadline passed while they were away: it comes back, the Director
     * is told without priority; the stake, released, comes back whole.
     */
    @GameTest
    fun theEscrowSurvivesARestartAndPaysBackAfterIt(helper: GameTestHelper) = GuardGameTests.withPlayer(helper) { player ->
        val escrow = Escrowed()
        val now = escrow.clock.millis()
        val sword = namedSword(helper)
        player.setItemInHand(InteractionHand.MAIN_HAND, sword.copy())
        val stake = escrow.ledger.hold(player, InteractionHand.MAIN_HAND, LedgerPurpose.STAKE, Escrow.stakeDeadline(now + 15 * MINUTE), 50, "bet on Mimosa")!!
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.DIAMOND, 10))
        val offering = escrow.ledger.hold(player, InteractionHand.MAIN_HAND, LedgerPurpose.OFFERING, now + 10 * MINUTE, null, "offered by the player")!!

        val back = reconnect(helper, player)
        helper.assertEquals(listOf(stake, offering), GuardStates.read(back).confiscations, "both read back from the player's file")
        helper.assertEquals(offering.takenAt, GuardStates.read(back).lastOfferAt, "and when they last offered")
        helper.assertEquals(0, count(back, Items.DIAMOND) + count(back, Items.IRON_SWORD), "not in the inventory")

        escrow.ledger.giveBack(back, now + 10 * MINUTE)
        helper.assertEquals(10, count(back, Items.DIAMOND), "the offering comes back, not taken")
        val news = escrow.told.single() as DirectorSignal.Mechanic
        helper.assertEquals(
            DirectorSignal.Mechanic(MechanicSignal.OFFERING, Escrow.offeringBackLine(offering, back.plainTextName), now + 10 * MINUTE, priority = false),
            news, "the Director is told, without priority",
        )
        helper.assertTrue(escrow.ledger.release(back, stake.id, now + 15 * MINUTE), "the stake released")
        helper.assertTrue(ItemStack.matches(sword, find(back, Items.IRON_SWORD)), "given back whole: ${find(back, Items.IRON_SWORD)}")
        helper.assertEquals(emptyList<Confiscation>(), escrow.ledger.held(back), "the ledger is empty")
        helper.assertEquals(listOf("held", "returned"), escrow.decisionsOf(offering.id), "the offering, traced")
        helper.assertEquals(listOf("held", "released", "returned"), escrow.decisionsOf(stake.id), "the stake, traced")
    }

    /**
     * Through the server's own guards and hooks: a stake ceded to a winner online while its owner was away (saved in
     * their file, due before they came back) is handed over at the owner's join; another, due while they were away,
     * comes back to them. The Journal has the trace.
     */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun whatFellDueWhileItsOwnerWasAwayIsSettledAtTheirJoin(helper: GameTestHelper) {
        val runtime = helper.narrator()
        val ledger = guards(helper).items
        val players = helper.level.server.playerList
        val owner = TestPlayer(helper, "eo_")
        val winner = TestPlayer(helper, "ew_")
        val ids = try {
            val now = System.currentTimeMillis()
            owner.player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.IRON_INGOT, 12))
            val lost = ledger.hold(owner.player, InteractionHand.MAIN_HAND, LedgerPurpose.STAKE, Escrow.stakeDeadline(now), 50, "bet on Bartok")!!
            // Ceded while the winner is away (a settlement written for later).
            val winnerSaved = winner.player.nameAndId()
            winner.close()
            helper.assertTrue(ledger.forfeit(owner.player, lost.id, winnerSaved.id(), now), "ceded")
            // Another stake, due as the owner leaves: its deadline passes while they are away.
            owner.player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.EMERALD, 7))
            val due = ledger.hold(owner.player, InteractionHand.MAIN_HAND, LedgerPurpose.STAKE, now - 1, 50, "bet on nobody")!!
            val ownerSaved = owner.player.nameAndId()
            owner.close()

            val winnerBack = comeBack(helper, winnerSaved)
            val ownerBack = comeBack(helper, ownerSaved)
            helper.assertEquals(7, count(ownerBack, Items.EMERALD), "the stake due while away comes back at the join")
            helper.assertEquals(0, count(ownerBack, Items.IRON_INGOT), "the lost one is not theirs any more")
            helper.assertEquals(12, count(winnerBack, Items.IRON_INGOT), "handed over to the winner at the owner's join")
            helper.assertEquals(emptyList<Confiscation>(), ledger.held(ownerBack) + ledger.held(winnerBack), "both ledgers are empty")
            listOf(due.id, lost.id)
        } finally {
            listOf(owner.uuid, winner.uuid).forEach { uuid -> players.getPlayer(uuid)?.let { players.remove(it) } }
        }
        val decisions = AtomicReference<Any>()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runtime.journal.barrier()
                decisions.set(ids.map { id -> journalDecisions(runtime, id) })
            } catch (e: Exception) {
                decisions.set("read failed: $e")
            }
        }
        helper.awaitAsync {
            helper.assertEquals(listOf(listOf("held", "returned"), listOf("held", "forfeited", "transferred", "delivered")), decisions.get(), "the Journal's trace")
        }
    }

    /** `/offer`: the offering goes into escrow and the Director is told with priority; a second waits for the first; it goes back untaken. */
    @GameTest
    fun anOfferingIsHeldTheDirectorToldAndItGoesBackUntaken(helper: GameTestHelper) = withPlayers(helper, 1) { (offerer) ->
        val offers = Offers(helper)
        val player = offerer.player
        val now = offers.clock.millis()

        helper.assertEquals(UI["offer.no_item"], offers.offer(player).reply.string, "an empty hand offers nothing")
        player.setItemInHand(InteractionHand.MAIN_HAND, joker())
        helper.assertEquals(UI["offer.no_item"], offers.offer(player).reply.string, "nor does a joker")
        helper.assertTrue(ItemLedger.isJoker(player.mainHandItem), "the joker stays in hand")

        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.DIAMOND, 10))
        val made = offers.offer(player)
        val offering = made.held!!
        helper.assertTrue(UI["offer.sent"].substringBefore("{items}") in made.reply.string, "the player reads it: ${made.reply.string}")
        helper.assertTrue(player.mainHandItem.isEmpty, "the hand is emptied")
        helper.assertEquals(LedgerPurpose.OFFERING, offering.purpose, "an offering")
        helper.assertEquals(now + 10 * MINUTE, offering.returnAt, "back after mechanics.offeringMinutes")
        helper.assertEquals(listOf(offering), GuardStates.read(player).confiscations, "written in the player's state")
        helper.assertEquals(
            listOf(DirectorSignal.Mechanic(MechanicSignal.OFFERING, Escrow.offeringLine(offering, player.plainTextName, 10 * MINUTE), now, priority = true)),
            offers.director.signals.toList(), "the Director is told, with priority",
        )

        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.EMERALD, 5))
        helper.assertEquals(UI["offer.pending"], offers.offer(player).reply.string, "one offering waits at a time")
        offers.mechanics.available = false
        helper.assertEquals(UI["mechanics.unavailable"], offers.offer(player).reply.string, "refused while the mechanics are off")
        offers.mechanics.available = true
        helper.assertEquals(5, count(player, Items.EMERALD), "nothing taken by a refusal")
        helper.assertEquals(1, offers.director.signals.size, "and the Director is not woken")

        offers.clock.advance(Duration.ofMinutes(10))
        offers.escrow.ledger.giveBack(player, offers.clock.millis())
        helper.assertEquals(10, count(player, Items.DIAMOND), "not taken: it goes back at its deadline")
        helper.assertTrue(offerer.told("offer.returned"), "the player is told: ${offerer.lines()}")
        helper.assertEquals(
            listOf(DirectorSignal.Mechanic(MechanicSignal.OFFERING, Escrow.offeringBackLine(offering, player.plainTextName), offers.clock.millis(), priority = false)),
            offers.escrow.told.toList(), "the Director is told it went back, without priority",
        )
        helper.assertEquals(listOf("held", "returned"), offers.escrow.decisionsOf(offering.id), "traced")
        helper.assertTrue(offers.offer(player).held != null, "10 minutes later, the player may offer again")
    }

    /** An offering taken for a favor is the Narrator's for good, traced; the next `/offer` comes 10 minutes after the last; an offering past its deadline is no longer to take. */
    @GameTest
    fun anAcceptedOfferingIsTheNarratorsAndTheNextComesTenMinutesLater(helper: GameTestHelper) = withPlayers(helper, 1) { (offerer) ->
        val offers = Offers(helper)
        val player = offerer.player
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.DIAMOND, 10))
        val offering = offers.offer(player).held!!

        val taken = offers.escrow.ledger.consume(player, offering.id, "a joker granted")
        helper.assertEquals(offering, taken, "taken")
        helper.assertEquals(emptyList<Confiscation>(), offers.escrow.ledger.held(player), "gone from the ledger, for good")
        helper.assertEquals(0, count(player, Items.DIAMOND), "and from the inventory")
        helper.assertTrue(offerer.told("offer.accepted"), "the player is told: ${offerer.lines()}")
        helper.assertEquals(null, offers.escrow.ledger.consume(player, offering.id, "again"), "taken once")
        helper.assertEquals(listOf("held", "offered"), offers.escrow.decisionsOf(offering.id), "traced")

        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.EMERALD, 5))
        helper.assertEquals(UI.format("offer.too_soon", "time" to "10m"), offers.offer(player).reply.string, "one /offer per 10 minutes, taken or not")
        offers.clock.advance(Duration.ofMinutes(10))
        val next = offers.offer(player).held!!
        offers.clock.advance(Duration.ofMinutes(10))
        helper.assertEquals(null, offers.escrow.ledger.consume(player, next.id, "too late"), "past its deadline, no longer to take")
        offers.escrow.ledger.giveBack(player, offers.clock.millis())
        helper.assertEquals(5, count(player, Items.EMERALD), "it goes back instead")
    }

    /** `/offer` is a player command: the dispatcher runs it with the player's own permission. */
    @GameTest
    fun offerIsACommandForEveryPlayer(helper: GameTestHelper) = withPlayers(helper, 1) { (offerer) ->
        val runtime = helper.narrator()
        helper.level.server.commands.performPrefixedCommand(offerer.player.createCommandSourceStack(), "offer")
        val ui = runtime.settings.get().ui
        val expected = if (runtime.mechanics.available) ui["offer.no_item"] else ui["mechanics.unavailable"]
        helper.assertEquals(listOf(expected), offerer.lines(), "what the player reads, with an empty hand")
    }

    /**
     * The scenario of the M4 review (finding 1): a book and quill full to the brim, and a shulker box full of written
     * books, held in escrow. Each one's SNBT is far past the 65,535 bytes of an NBT string, which the game writes `""`
     * instead: through the player's file, they must come back whole.
     */
    @GameTest
    fun aFullBookAndAShulkerBoxOfBooksComeBackWholeThroughThePlayersFile(helper: GameTestHelper) = GuardGameTests.withPlayer(helper) { player ->
        val escrow = Escrowed()
        val now = escrow.clock.millis()
        val book = fullBook()
        val box = ItemStack(Items.SHULKER_BOX)
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List(27) { i -> writtenBook("Tome $i", pages = 20) }))
        player.setItemInHand(InteractionHand.MAIN_HAND, book.copy())
        val stake = escrow.ledger.hold(player, InteractionHand.MAIN_HAND, LedgerPurpose.STAKE, Escrow.stakeDeadline(now), 50, "bet on Mimosa")!!
        player.setItemInHand(InteractionHand.MAIN_HAND, box.copy())
        val offering = escrow.ledger.hold(player, InteractionHand.MAIN_HAND, LedgerPurpose.OFFERING, now + 10 * MINUTE, null, "offered by the player")!!
        helper.assertTrue(stake.items.single().length > 100_000 && offering.items.single().length > 500_000, "far past an NBT string")

        // The review's loop: the state through the codec, the game's NBT writer, and back.
        val ops = player.registryAccess().createSerializationContext(NbtOps.INSTANCE)
        val state = GuardStates.read(player)
        val bytes = ByteArrayOutputStream().also { NbtIo.write(GuardState.CODEC.encodeStart(ops, state).getOrThrow() as CompoundTag, DataOutputStream(it)) }.toByteArray()
        val read = GuardState.CODEC.parse(ops, NbtIo.read(DataInputStream(ByteArrayInputStream(bytes)))).getOrThrow()
        val lengths = { s: GuardState -> s.confiscations.map { entry -> entry.items.map { it.length } } }
        helper.assertTrue(state == read, "the state reads back as it was written, no stack written as \"\": ${lengths(read)} characters, not ${lengths(state)}")

        // And the player's own file, through a reconnection.
        val back = reconnect(helper, player)
        helper.assertTrue(listOf(stake, offering) == GuardStates.read(back).confiscations, "both read back from the player's file: ${lengths(GuardStates.read(back))} characters")
        helper.assertTrue(escrow.ledger.release(back, stake.id, now + MINUTE), "the stake released")
        escrow.ledger.giveBack(back, now + 10 * MINUTE)
        helper.assertTrue(ItemStack.matches(book, find(back, Items.WRITABLE_BOOK)), "the book comes back whole")
        helper.assertTrue(ItemStack.matches(box, find(back, Items.SHULKER_BOX)), "the shulker box and its 27 books come back whole")
        helper.assertEquals(emptyList<Confiscation>(), escrow.ledger.held(back), "the ledger is empty")
    }

    /**
     * A hand-over writes the winner's file, then the loser's, at once, and nobody else's (M4 review, finding 4: no
     * `saveAll`, which wrote every player online twice per stake on the server thread). Each file holds its side of
     * the hand-over as soon as it is done.
     */
    @GameTest
    fun aHandOverWritesTheTwoPlayersFilesAndNobodyElses(helper: GameTestHelper) = withPlayers(helper, 3) { (loser, winner, bystander) ->
        val escrow = Escrowed()
        val now = escrow.clock.millis()
        loser.player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.IRON_INGOT, 12))
        val stake = escrow.ledger.hold(loser.player, InteractionHand.MAIN_HAND, LedgerPurpose.STAKE, Escrow.stakeDeadline(now), 50, "bet on Bartok")!!
        bystander.player.inventory.add(ItemStack(Items.EMERALD, 3)) // never saved: nobody wrote the bystander's file since

        helper.assertTrue(escrow.ledger.forfeit(loser.player, stake.id, winner.uuid, now), "ceded, both online: handed over at once")
        helper.assertEquals(12, count(winner.player, Items.IRON_INGOT), "delivered")
        val winnerFile = fromFile(helper, winner.player)!!
        helper.assertEquals(listOf(stake.id), winnerFile.receivedStakes.map { it.stake }, "the winner's file knows the stake was received")
        helper.assertEquals(listOf(LedgerPurpose.OWED), winnerFile.confiscations.map { it.purpose }, "and holds the winnings, written before the loser's")
        helper.assertEquals(emptyList<Confiscation>(), fromFile(helper, loser.player)!!.confiscations, "the loser's file no longer holds the stake")
        val bystanderFile = helper.level.server.playerList.loadPlayerData(bystander.player.nameAndId())
        helper.assertFalse(bystanderFile.isPresent && "minecraft:emerald" in bystanderFile.get().toString(), "nobody else's file is written")
    }

    /**
     * The M4 review's scenario (finding 28): a crash after the winner's file was written, before the loser's. At the
     * restart the winner is paid first; then the loser's file, still holding the stake, replays the hand-over. The
     * winner's ledger remembers the stake: 12 ingots, not 24.
     */
    @GameTest
    fun aHandOverReplayedAfterTheWinnerWasPaidGivesNothingTwice(helper: GameTestHelper) = withPlayers(helper, 2) { (loser, away) ->
        val escrow = Escrowed()
        val now = escrow.clock.millis()
        val saved = away.player.nameAndId()
        away.close()
        loser.player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.IRON_INGOT, 12))
        val stake = escrow.ledger.hold(loser.player, InteractionHand.MAIN_HAND, LedgerPurpose.STAKE, Escrow.stakeDeadline(now), 50, "bet on Bartok")!!
        helper.assertTrue(escrow.ledger.forfeit(loser.player, stake.id, saved.id(), now), "ceded to the winner, away")
        val lostFile = GuardStates.read(loser.player) // what the loser's file holds if the crash comes before it is written again

        val winner = comeBack(helper, saved)
        escrow.ledger.giveBack(loser.player, now + MINUTE)
        helper.assertEquals(12, count(winner, Items.IRON_INGOT), "the winner is paid")
        helper.assertEquals(emptyList<Confiscation>(), escrow.ledger.held(winner), "delivered: nothing waits in the winner's ledger")

        escrow.states.update(loser.player) { lostFile } // the loser's file was never written: the stake is back in it
        escrow.ledger.giveBack(loser.player, now + 2 * MINUTE)
        helper.assertEquals(12, count(winner, Items.IRON_INGOT), "12 ingots, never 24")
        helper.assertEquals(emptyList<Confiscation>(), escrow.ledger.held(loser.player), "the stake left the loser's ledger again")
        helper.assertEquals(listOf(false, true), escrow.transfers(stake.id).map { it.detail?.get("replayed")?.jsonPrimitive?.booleanOrNull }, "the replay is traced")
    }

    /** A joker hidden in a shulker box or a bundle is neither staked nor offered (M4 review, finding 40): it would be lost with an offering taken. */
    @GameTest
    fun aJokerHiddenInAShulkerBoxOrABundleIsNeitherStakedNorOffered(helper: GameTestHelper) = withPlayers(helper, 1) { (offerer) ->
        val offers = Offers(helper)
        val player = offerer.player
        val box = ItemStack(Items.SHULKER_BOX)
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(listOf(ItemStack(Items.DIAMOND, 3), joker())))
        val bundle = ItemStack(Items.BUNDLE)
        bundle.set(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY.copyWithContents(Stream.of(ItemStack(Items.STRING), joker())))
        val nested = ItemStack(Items.SHULKER_BOX)
        nested.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(listOf(bundle.copy())))
        val now = offers.clock.millis()

        for ((what, stack) in listOf("a shulker box" to box, "a bundle" to bundle, "a bundle in a shulker box" to nested)) {
            player.setItemInHand(InteractionHand.MAIN_HAND, stack.copy())
            helper.assertEquals(UI["offer.no_item"], offers.offer(player).reply.string, "$what with a joker is not offered")
            helper.assertEquals(null, offers.escrow.ledger.hold(player, InteractionHand.MAIN_HAND, LedgerPurpose.STAKE, Escrow.stakeDeadline(now), 50, "bet"), "nor staked: $what")
            helper.assertTrue(ItemStack.matches(stack, player.mainHandItem), "$what stays in hand")
        }
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(listOf(ItemStack(Items.DIAMOND, 3))))
        player.setItemInHand(InteractionHand.MAIN_HAND, box.copy())
        helper.assertTrue(offers.offer(player).held != null, "without the joker, it is an offering like any other")
    }

    /**
     * A stack held across an update of the game (M4 review, finding 40): written at an older data version, it comes
     * back brought up to date by the game's data fixer, not read as the new format would and stripped.
     */
    @GameTest
    fun aStackHeldAcrossAnUpdateOfTheGameComesBackUpToDate(helper: GameTestHelper) = withPlayers(helper, 1) { (owner) ->
        val escrow = Escrowed()
        val player = owner.player
        val now = escrow.clock.millis()
        // A sword confiscated under Minecraft 1.20.4 (data version 3700), saved in that version's format.
        val entry = CompoundTag()
        entry.putLong("id", 7_001)
        entry.put("items", ListTag().also { it.add(StringTag.valueOf("""{id:"minecraft:diamond_sword",Count:1b,tag:{Damage:5,display:{Name:'{"text":"Excalibur"}'}}}""")) })
        entry.putString("what", "1x minecraft:diamond_sword")
        entry.putLong("taken_at", now)
        entry.putLong("return_at", now)
        entry.putString("reason", "greed")
        entry.putInt("data_version", 3_700)
        val saved = CompoundTag().also { it.put("confiscations", ListTag().also { list -> list.add(entry) }) }
        val state = GuardState.CODEC.parse(player.registryAccess().createSerializationContext(NbtOps.INSTANCE), saved).getOrThrow()
        escrow.states.update(player) { state }

        escrow.ledger.giveBack(player, now)
        val sword = find(player, Items.DIAMOND_SWORD)
        helper.assertEquals(5, sword.get(DataComponents.DAMAGE), "its wear kept: $sword")
        helper.assertEquals("Excalibur", sword.get(DataComponents.CUSTOM_NAME)?.string, "its name kept: $sword")
        helper.assertEquals(emptyList<Confiscation>(), escrow.ledger.held(player), "the ledger is empty")
    }

    /** A joker owed to a player whose inventory is full waits in their ledger, never on the ground, and comes once there is room. */
    @GameTest
    fun aJokerOwedWaitsInTheLedgerForRoom(helper: GameTestHelper) = withPlayers(helper, 1) { (favored) ->
        val escrow = Escrowed()
        val player = favored.player
        val now = escrow.clock.millis()

        helper.assertEquals(emptyList<ItemStack>(), escrow.ledger.owe(player, listOf(joker()), "favor joker"), "room: given at once")
        helper.assertEquals(1, jokers(player), "in the inventory")
        helper.assertEquals(emptyList<Confiscation>(), escrow.ledger.held(player), "nothing waits")
        helper.assertEquals(0, escrow.traces.decisions.size, "nothing to trace in the ledger: the favor is traced by its tool")

        fill(player)
        val waiting = escrow.ledger.owe(player, listOf(joker()), "favor joker")
        helper.assertEquals(1, waiting.size, "no room: it waits")
        val owed = escrow.ledger.held(player).single()
        helper.assertEquals(LedgerPurpose.OWED, owed.purpose, "owed")
        helper.assertEquals(now, owed.returnAt, "due at once")
        helper.assertTrue(favored.told("items.no_room"), "the player is told: ${favored.lines()}")
        escrow.ledger.giveBack(player, now + 1_000)
        helper.assertEquals(listOf(owed), escrow.ledger.held(player), "still no room")

        player.inventory.setItem(3, ItemStack.EMPTY)
        escrow.ledger.giveBack(player, now + 2_000)
        helper.assertEquals(1, jokers(player), "delivered once there is room, still a joker")
        helper.assertEquals(emptyList<Confiscation>(), escrow.ledger.held(player), "the ledger is empty")
        helper.assertTrue(favored.told("items.delivered"), "the player is told: ${favored.lines()}")
        helper.assertEquals(listOf("owed", "delivered"), escrow.decisionsOf(owed.id), "traced")
    }

    /** A ledger of a test's own: its decisions in memory, its news for the Director recorded, its clock moved by the test. */
    private class Escrowed(val clock: TestClock = TestClock(Instant.now())) {
        val traces = MemoryTraceSink()
        val told = CopyOnWriteArrayList<DirectorSignal>()
        val states = GuardStates()
        val ledger = ItemLedger(states, Decisions(traces, clock), clock, tell = { _, signal -> told += signal }) { UI }

        /** The `ledger` decisions about entry [id], in order: by their id, or the entry named in their detail. */
        fun decisionsOf(id: Long): List<String> = ledgerDecisions(id).map { it.decision }

        /** The reason of the [decision] about entry [id]. */
        fun reasonOf(id: Long, decision: String): String = ledgerDecisions(id).first { it.decision == decision }.reason.orEmpty()

        /** The `ledger transferred` decisions of stake [id], in order. */
        fun transfers(id: Long): List<DecisionRecord> = ledgerDecisions(id).filter { it.decision == "transferred" }

        private fun ledgerDecisions(id: Long): List<DecisionRecord> = traces.decisions.filter { record ->
            record.kind == DecisionRecord.Kind.LEDGER &&
                (record.id == id || ENTRY_KEYS.any { key -> record.detail?.get(key)?.jsonPrimitive?.longOrNull == id })
        }
    }

    /** `/offer` with services of a test's own: a fake Director, a fake engine that is available, and an [Escrowed] ledger on the same clock. */
    private class Offers(helper: GameTestHelper) {
        val escrow = Escrowed()
        val clock = escrow.clock
        val director = FakeDirector(clock)
        val mechanics = FakeMechanics(clock)
        private val services = TestServices(minecraft = helper.level.server, clock = clock, director = director, mechanics = mechanics)

        fun offer(player: ServerPlayer): OfferCommand.Offer = OfferCommand.offer(services, escrow.ledger, player)
    }

    private companion object {
        const val MINUTE = 60_000L
        const val DAY = 24 * 60 * MINUTE

        /** The French texts, as the test ledgers and services read them. */
        val UI: UiTexts = UiTexts.load(NarratorConfig().language)

        /** The detail keys that name a ledger entry: a return or delivery, a stake's settlement, an offering taken. */
        val ENTRY_KEYS = listOf("confiscation", "stake", "offering")

        /** One of the Narrator's jokers (M4 §7.1): paper marked `{narrator2: {joker: 1b}}`. */
        fun joker(): ItemStack = ItemStack(Items.PAPER).also { stack ->
            val mark = CompoundTag()
            mark.putBoolean(ItemLedger.JOKER_KEY, true)
            val data = CompoundTag()
            data.put("narrator2", mark)
            CustomData.set(DataComponents.CUSTOM_DATA, stack, data)
        }

        fun jokers(player: ServerPlayer): Int =
            (0 until player.inventory.containerSize).sumOf { slot -> player.inventory.getItem(slot).let { if (ItemLedger.isJoker(it)) it.count else 0 } }

        /** Fills the 36 slots of [player]'s inventory: nothing more fits. */
        fun fill(player: ServerPlayer) {
            for (slot in 0 until 36) player.inventory.setItem(slot, ItemStack(Items.DIRT, 64))
        }

        /** A book and quill full to the brim: 100 pages of 1,024 characters. */
        fun fullBook(): ItemStack = ItemStack(Items.WRITABLE_BOOK).also { book ->
            book.set(DataComponents.WRITABLE_BOOK_CONTENT, WritableBookContent(List(100) { page -> Filterable.passThrough("Page $page ".padEnd(1_024, 'é')) }))
        }

        /** A written book titled [title], of [pages] pages of 1,024 characters. */
        fun writtenBook(title: String, pages: Int): ItemStack = ItemStack(Items.WRITTEN_BOOK).also { book ->
            val text = List(pages) { page -> Filterable.passThrough<Component>(Component.literal("$title, page $page ".padEnd(1_024, 'a'))) }
            book.set(DataComponents.WRITTEN_BOOK_CONTENT, WrittenBookContent(Filterable.passThrough(title), "Mimosa", 0, text, true))
        }

        /** [player]'s guards' state as their file holds it now; null when they have no file yet. */
        fun fromFile(helper: GameTestHelper, player: ServerPlayer): GuardState? {
            val data = helper.level.server.playerList.loadPlayerData(player.nameAndId()).orElse(null) ?: return null
            val saved = data.getCompoundOrEmpty(AttachmentTarget.NBT_ATTACHMENT_KEY).get("narrator2:guard_state") ?: return GuardState.EMPTY
            return GuardState.CODEC.parse(player.registryAccess().createSerializationContext(NbtOps.INSTANCE), saved).getOrThrow()
        }

        /** Runs [test] with [count] new players, removed however it ends (those who came back too), then succeeds. */
        fun withPlayers(helper: GameTestHelper, count: Int, test: (List<TestPlayer>) -> Unit) {
            val players = List(count) { TestPlayer(helper, "es_") }
            players.forEach { it.forget() }
            try {
                test(players)
            } finally {
                val list = helper.level.server.playerList
                players.forEach { player -> list.getPlayer(player.uuid)?.let { list.remove(it) } }
            }
            helper.succeed()
        }

        /** The player saved as [saved] joins again, loaded from their file into a new player object: what a restart does. */
        fun comeBack(helper: GameTestHelper, saved: NameAndId): ServerPlayer {
            val server = helper.level.server
            val players = server.playerList
            val data = players.loadPlayerData(saved).orElseThrow { AssertionError("${saved.name()} was not saved") }
            val profile = GameProfile(saved.id(), saved.name())
            val back = ServerPlayer(server, helper.level, profile, ClientInformation.createDefault())
            back.load(TagValueInput.create(ProblemReporter.DISCARDING, server.registryAccess(), data))
            val connection = Connection(PacketFlow.SERVERBOUND)
            EmbeddedChannel(connection)
            players.placeNewPlayer(connection, back, CommonListenerCookie.createInitial(profile, false))
            return back
        }

        /** The `ledger` decisions about entry [id] in the Journal, in order. */
        suspend fun journalDecisions(runtime: NarratorRuntime, id: Long): List<String> = runtime.journal.read(5_000) { connection ->
            connection.prepareStatement(
                "SELECT decision FROM engine_decisions WHERE kind = 'ledger' AND (id = ? OR json_extract_string(detail, '\$.confiscation') = ? " +
                    "OR json_extract_string(detail, '\$.stake') = ?) ORDER BY id",
            ).use { statement ->
                statement.setLong(1, id)
                statement.setString(2, id.toString())
                statement.setString(3, id.toString())
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
            }
        }
    }
}

/** The chat lines [this] player received, as text. */
private fun TestPlayer.lines(): List<String> = chat().map { it.content().string }

/** Whether [this] player read the UI text [key] (its part before `{items}`). */
private fun TestPlayer.told(key: String): Boolean {
    val text = UiTexts.load(NarratorConfig().language)[key].substringBefore("{items}")
    return lines().any { text in it }
}
