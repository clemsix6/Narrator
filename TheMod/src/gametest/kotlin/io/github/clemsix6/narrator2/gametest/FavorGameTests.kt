package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.command.OfferCommand
import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.config.UiTexts
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.director.MechanicSignal
import io.github.clemsix6.narrator2.gametest.GuardGameTests.Companion.count
import io.github.clemsix6.narrator2.guard.AttributeKey
import io.github.clemsix6.narrator2.guard.Escrow
import io.github.clemsix6.narrator2.guard.GuardStates
import io.github.clemsix6.narrator2.guard.Guards
import io.github.clemsix6.narrator2.guard.GuardsImpl
import io.github.clemsix6.narrator2.guard.ItemLedger
import io.github.clemsix6.narrator2.guard.LedgerPurpose
import io.github.clemsix6.narrator2.guard.PunishmentCategory
import io.github.clemsix6.narrator2.guard.Rules
import io.github.clemsix6.narrator2.guard.Verdict
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.mechanics.Jokers
import io.github.clemsix6.narrator2.memory.Interaction
import io.github.clemsix6.narrator2.memory.InteractionKind
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.testing.FakeDirector
import io.github.clemsix6.narrator2.testing.FakeMechanics
import io.github.clemsix6.narrator2.testing.FakeMemory
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.testing.testSettings
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolArgs
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.tool.action.FavorArgs
import io.github.clemsix6.narrator2.tool.action.FavorTool
import io.github.clemsix6.narrator2.trace.DecisionRecord
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.component.DataComponents
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * In-game tests of the favors (M4-16, `design/M4.md` §7, §21.2, scenario S-M2 of §21.3): `favor` grants a pardon, a
 * grace or a joker, within their limits; with an offering, the favor first and the offering only once it is granted,
 * left in escrow when it is refused; a joker played with a right click (`UseItemCallback`) lifts a punishment, and
 * only then.
 *
 * Each test has players of its own (unique names: the tools address players by name) and services of its own
 * ([Favors]): real guards on a test clock, a fake Director, engine and memory. Their bodies stay synchronous: the
 * server's guards check the same player attachment once a second, between ticks.
 */
class FavorGameTests {

    /** `favor` without an offering: a pardon (or nothing to pardon), a grace and its hour, a joker and its day. */
    @GameTest
    fun favorGrantsAPardonAGraceAndAJokerWithinTheirLimits(helper: GameTestHelper) = withPlayers(helper, 1) { (favored) ->
        val favors = Favors(helper)
        val player = favored.player
        val name = favored.name
        val now = favors.clock.millis()

        val nothing = favors.favor("""{"player": "$name", "favor": "pardon"}""")
        helper.assertEquals("ok: nothing to pardon: you inflict nothing on $name", nothing.text, "nothing to pardon")
        helper.assertEquals(emptyList<Any>(), nothing.targets, "no favor, no target: the Chronicler sees no benefit")
        favors.punish(player, turn = 1)
        val pardon = favors.favor("""{"player": "$name", "favor": "pardon"}""")
        helper.assertEquals("ok: $name pardoned: slowness II lifted, scale reset", pardon.text, "pardoned")
        helper.assertEquals(listOf(favored.ref), pardon.targets, "the player, a benefit for the Chronicler")
        helper.assertFalse(player.hasEffect(MobEffects.SLOWNESS), "the slowness is lifted")
        helper.assertValueEqual(1.0, player.getAttributeValue(Attributes.SCALE), "the scale is back")

        val until = now + Duration.ofMinutes(20).toMillis()
        val grace = favors.favor("""{"player": "$name", "favor": "grace", "minutes": 20}""")
        helper.assertEquals("ok: $name is under your grace until ${timeOf(until)} (in 20m)", grace.text, "graced")
        helper.assertEquals(listOf(favored.ref), grace.targets, "the player")
        helper.assertEquals(until, GuardStates.read(player).graceUntil, "saved with the player")
        val hostile = favors.guards.hostile(player, turn(2), now + 60_000, PunishmentCategory.HARM)
        helper.assertTrue(hostile is Verdict.Denied && hostile.rule == Rules.GRACE, "nothing hostile now: $hostile")
        val again = favors.favor("""{"player": "$name", "favor": "grace", "minutes": 10}""")
        helper.assertTrue(again.status == ToolStatus.DENIED && again.text.startsWith("DENIED (favor_limit): $name is under your grace until"), "no second grace: ${again.text}")

        val given = favors.favor("""{"player": "$name", "favor": "joker"}""")
        helper.assertEquals("ok: $name got a joker; they can play it themselves to lift one of your punishments", given.text, "a joker")
        helper.assertEquals(listOf(favored.ref), given.targets, "the player")
        val joker = (0 until player.inventory.containerSize).map { player.inventory.getItem(it) }.single { ItemLedger.isJoker(it) }
        helper.assertEquals(UI["joker.name"], joker.hoverName.string, "its name")
        helper.assertEquals(listOf(UI["joker.lore"]), joker.get(DataComponents.LORE)?.lines()?.map { it.string }, "its lore")
        helper.assertTrue(joker.`is`(Items.PAPER) && joker.count == 1, "one paper: $joker")
        helper.assertEquals(listOf(now), GuardStates.read(player).jokersGivenAt, "dated with the player")
        helper.assertTrue(favored.told("joker.given"), "the player is told: ${favored.lines()}")
        val second = favors.favor("""{"player": "$name", "favor": "joker"}""")
        helper.assertEquals(
            "DENIED (favor_limit): $name got a joker 0s ago, and a player gets 1 a day at most; the next one is allowed at " +
                "${dayTimeOf(now + Jokers.DAY_MS)} (in 1d0h)",
            second.text, "one joker a day",
        )
        helper.assertEquals(1, jokers(player), "no second joker")

        favors.clock.advance(Duration.ofDays(1))
        fill(player)
        val third = favors.favor("""{"player": "$name", "favor": "joker"}""")
        helper.assertTrue(third.text.endsWith("(their inventory is full: it waits there for room)"), "a day later, waiting for room: ${third.text}")
        helper.assertEquals(listOf(LedgerPurpose.OWED), favors.guards.items.held(player).map { it.purpose }, "in the ledger, never on the ground")
        // The hostile action refused by the grace, the second grace, the second joker: each refusal is a decision.
        helper.assertEquals(
            listOf("pardoned", "graced", "denied", "denied", "joker_given", "denied", "joker_given"),
            favors.decisions(DecisionRecord.Kind.GUARD).map { it.decision }.filter { it != "allowed" },
            "the guards' decisions",
        )
        helper.assertEquals(
            listOf("grace", "favor_limit", "favor_limit"),
            favors.decisions(DecisionRecord.Kind.GUARD).filter { it.decision == "denied" }.map { it.detail?.get("rule")?.jsonPrimitive?.content },
            "their rules",
        )
    }

    /**
     * Scenario S-M2 (M4 §21.3): `/offer` → `OFFERING` → `favor` with the offering, as the model writes it from the
     * event's line: the joker is given, then the offering taken for good; the player's history keeps it.
     */
    @GameTest
    fun anOfferingIsTakenOnceItsJokerIsGiven(helper: GameTestHelper) = withPlayers(helper, 1) { (offerer) ->
        val favors = Favors(helper)
        val player = offerer.player
        val name = offerer.name
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.DIAMOND, 10))
        val offering = favors.offer(player).held!!
        val signal = favors.director.signals.single() as DirectorSignal.Mechanic
        helper.assertTrue(signal.kind == MechanicSignal.OFFERING && signal.priority, "the Director is woken: $signal")
        val ref = Regex("""O-\d+""").find(signal.line)!!.value
        helper.assertEquals(Escrow.ref(offering.id), ref, "the offering the line names")
        helper.assertTrue("(favor, offering $ref)" in signal.line, "and how to take it: ${signal.line}")

        val note = "$name a offert dix diamants au Narrateur, qui lui a accordé un joker."
        val result = favors.favor("""{"player": "$name", "favor": "joker", "offering": "$ref", "note": "$note"}""")
        helper.assertEquals(
            "ok: $name got a joker; they can play it themselves to lift one of your punishments; their offering $ref (10x diamond) is yours for good",
            result.text, "the answer",
        )
        helper.assertEquals(1, jokers(player), "the joker is given")
        helper.assertEquals(0, count(player, Items.DIAMOND), "the diamonds are the Narrator's")
        helper.assertTrue(favors.guards.items.held(player).isEmpty(), "out of the ledger, for good")
        val order = favors.traces.decisions.map { it.kind to it.decision }.filter { it.first != DecisionRecord.Kind.GUARD || it.second == "joker_given" }
        helper.assertEquals(
            listOf(DecisionRecord.Kind.LEDGER to "held", DecisionRecord.Kind.GUARD to "joker_given", DecisionRecord.Kind.LEDGER to "offered"),
            order, "the favor first, then the offering",
        )
        val lines = offerer.lines()
        helper.assertTrue(
            lines.indexOfFirst { UI["joker.given"] in it } in 0 until lines.indexOfFirst { UI["offer.accepted"].substringBefore("{items}") in it },
            "the player reads the joker, then the offering taken: $lines",
        )
        val kept = favors.memory.store.rows.values.filterIsInstance<Interaction>().single()
        helper.assertTrue(
            kept.player.uuid == player.uuid && kept.kind == InteractionKind.OFFERING && kept.valence == 1 && kept.summary == note && kept.source == Source.DIRECTOR,
            "kept in their history: $kept",
        )

        val twice = favors.favor("""{"player": "$name", "favor": "grace", "offering": "$ref", "note": "$note"}""")
        helper.assertTrue(twice.text.startsWith("Invalid arguments: $ref is not a waiting offering of $name; waiting: "), "taken once: ${twice.text}")
    }

    /**
     * A favor refused leaves the offering in escrow (M4 §7.2), and the answer says so: a second joker within the day,
     * nothing to pardon. Another player's offering is not theirs to pay for. A favor granted takes it.
     */
    @GameTest
    fun aFavorRefusedLeavesTheOfferingInEscrow(helper: GameTestHelper) = withPlayers(helper, 2) { (offerer, other) ->
        val favors = Favors(helper)
        val player = offerer.player
        val name = offerer.name
        helper.assertEquals(ToolStatus.OK, favors.favor("""{"player": "$name", "favor": "joker"}""").status, "a joker today")
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.DIAMOND, 10))
        val offering = favors.offer(player).held!!
        val ref = Escrow.ref(offering.id)
        val note = "$name a offert dix diamants au Narrateur."
        val untaken = "; their offering $ref is not taken: grant another favor, or let it go back"

        val joker = favors.favor("""{"player": "$name", "favor": "joker", "offering": "$ref", "note": "$note"}""")
        helper.assertTrue(joker.status == ToolStatus.DENIED && joker.text.startsWith("DENIED (favor_limit): $name got a joker") && joker.text.endsWith(untaken), joker.text)
        val pardon = favors.favor("""{"player": "$name", "favor": "pardon", "offering": "$ref", "note": "$note"}""")
        helper.assertEquals("Invalid arguments: nothing to pardon on $name$untaken", pardon.text, "nothing to pardon")
        val elsewhere = favors.favor("""{"player": "${other.name}", "favor": "grace", "offering": "$ref", "note": "$note"}""")
        helper.assertTrue(
            elsewhere.text.startsWith("Invalid arguments: $ref is not a waiting offering of ${other.name}; waiting: ") && "$ref ($name)" in elsewhere.text,
            "another player's: ${elsewhere.text}",
        )
        helper.assertEquals(listOf(offering), favors.guards.items.held(player), "still in escrow")
        helper.assertEquals(null, GuardStates.read(other.player).graceUntil, "no grace for the other player")
        helper.assertTrue(favors.memory.store.rows.isEmpty(), "nothing in the history")
        helper.assertFalse(favors.traces.decisions.any { it.decision == "offered" }, "nothing taken")

        val grace = favors.favor("""{"player": "$name", "favor": "grace", "minutes": 5, "offering": "$ref", "note": "$note"}""")
        helper.assertTrue(grace.text.startsWith("ok: $name is under your grace until ") && grace.text.endsWith("$ref (10x diamond) is yours for good"), grace.text)
        helper.assertTrue(favors.guards.items.held(player).isEmpty(), "taken for the grace")
    }

    /**
     * A joker played (`Jokers.onUse`, M4 §7.1): kept when the Narrator inflicts nothing; otherwise the punishment is
     * lifted as an act of its own, one joker leaves the stack, the Director is told without priority. It works with the
     * mechanics off: it is a guard.
     */
    @GameTest
    fun aJokerIsPlayedOnlyAgainstAPunishment(helper: GameTestHelper) = withPlayers(helper, 1) { (favored) ->
        val favors = Favors(helper)
        val player = favored.player
        val name = favored.name
        player.setItemInHand(InteractionHand.MAIN_HAND, Jokers.stack(UI).copyWithCount(2))

        helper.assertEquals(InteractionResult.PASS, Jokers.onUse(favors.services, player, InteractionHand.MAIN_HAND), "nothing to lift")
        helper.assertEquals(2, player.mainHandItem.count, "the joker is kept")
        helper.assertTrue(favored.told("joker.nothing"), "the player is told: ${favored.lines()}")
        helper.assertTrue(favors.director.signals.isEmpty(), "the Director is not told")

        favors.punish(player, turn = 3)
        helper.assertEquals(InteractionResult.SUCCESS, Jokers.onUse(favors.services, player, InteractionHand.MAIN_HAND), "played")
        helper.assertEquals(1, player.mainHandItem.count, "one joker less")
        helper.assertFalse(player.hasEffect(MobEffects.SLOWNESS), "the slowness is lifted")
        helper.assertValueEqual(1.0, player.getAttributeValue(Attributes.SCALE), "the scale is back")
        helper.assertTrue(favored.told("joker.played"), "the player is told: ${favored.lines()}")
        val played = favors.traces.decisions.single { it.kind == DecisionRecord.Kind.MECHANIC && it.decision == "joker" }
        val pardoned = favors.decisions(DecisionRecord.Kind.GUARD).single { it.decision == "pardoned" }
        helper.assertEquals(-played.id, pardoned.detail?.get("act")?.jsonPrimitive?.long, "the pardon is the joker's own act")
        helper.assertEquals(Jokers.CONVERSATION, pardoned.conversationId, "named so")
        helper.assertEquals(name, played.subject, "the joker's decision is about the player")
        helper.assertEquals("$name played a joker: slowness II lifted, scale reset", played.reason, "a line that stands alone, for the Chronicler")
        helper.assertEquals(
            listOf(DirectorSignal.Mechanic(MechanicSignal.JOKER, "$name played a joker: slowness II lifted, scale reset", favors.clock.millis(), priority = false)),
            favors.director.signals.toList(), "the Director is told, without priority",
        )

        favors.mechanics.available = false
        helper.assertEquals(Verdict.Allowed, favors.guards.hostile(player, turn(4), favors.clock.millis() + 60_000), "punished again")
        helper.assertEquals(InteractionResult.SUCCESS, Jokers.onUse(favors.services, player, InteractionHand.MAIN_HAND), "played with the mechanics off")
        helper.assertTrue(player.mainHandItem.isEmpty, "the last joker is gone")
        helper.assertFalse(favors.guards.isPunished(player), "nothing left")
    }

    /** The server's `UseItemCallback` reaches [Jokers.onUse] (`registerMechanicsHooks`), with the running Narrator's guards. */
    @GameTest
    fun theUseItemHookPlaysAJoker(helper: GameTestHelper) = withPlayers(helper, 1) { (favored) ->
        val guards = helper.narrator().guards as GuardsImpl
        val player = favored.player
        val use = { UseItemCallback.EVENT.invoker().interact(player, helper.level, InteractionHand.MAIN_HAND) }
        player.setItemInHand(InteractionHand.MAIN_HAND, Jokers.stack(UI))
        helper.assertEquals(InteractionResult.PASS, use(), "nothing to lift")
        helper.assertTrue(favored.told("joker.nothing"), "the player is told: ${favored.lines()}")

        helper.assertEquals(Verdict.Allowed, guards.hostile(player, turn(16_001), System.currentTimeMillis() + 600_000, PunishmentCategory.MOVEMENT), "punished")
        guards.effects.apply(player, MobEffects.SLOWNESS, 2, 600, particles = false, turnId = 16_001)
        helper.assertEquals(InteractionResult.SUCCESS, use(), "played")
        helper.assertTrue(player.mainHandItem.isEmpty, "the joker is gone")
        helper.assertFalse(player.hasEffect(MobEffects.SLOWNESS) || guards.isPunished(player), "the punishment is lifted")
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.PAPER))
        helper.assertEquals(InteractionResult.PASS, use(), "plain paper is no joker")
    }

    /** Services of a test's own: the server's guards rebuilt on a test clock (it starts now), a fake Director, engine and memory, `favor` to call. */
    private class Favors(helper: GameTestHelper) {
        val clock = TestClock(Instant.now())
        val director = FakeDirector(clock)
        val mechanics = FakeMechanics(clock)
        val memory = FakeMemory(clock)
        private val base = TestServices(
            minecraft = helper.level.server, clock = clock, settings = testSettings(NarratorConfig()), players = helper.narrator().players,
            director = director, mechanics = mechanics, memory = memory,
        )
        val guards = GuardsImpl(base)

        /** The services the tool, `/offer` and the jokers get: [base], with [guards]. */
        val services: NarratorServices = object : NarratorServices by base {
            override val guards: Guards get() = this@Favors.guards
        }

        /** Every decision, in order. */
        val traces get() = base.traces
        private val tool = FavorTool()

        /** `favor` called by the Director's turn 42 with [json], the arguments as the model writes them. Server thread. */
        fun favor(json: String): ToolResult {
            val decoded = ToolArgs.decode(FavorArgs.serializer(), json)
            val args = (decoded as? ToolArgs.Decoded.Ok)?.args ?: return ToolResult.invalid((decoded as ToolArgs.Decoded.Invalid).problem)
            return runBlocking { tool.run(ToolContext(services, turn(42), clock, tool.timeoutMs), args) }
        }

        /** `/offer` of the stack in [player]'s hand. */
        fun offer(player: ServerPlayer): OfferCommand.Offer = OfferCommand.offer(services, guards.items, player)

        /** A punishment of [turn] on [player]: its episode, slowness II and a smaller scale. */
        fun punish(player: ServerPlayer, turn: Long) {
            val until = clock.millis() + 600_000
            guards.hostile(player, turn(turn), until, PunishmentCategory.MOVEMENT)
            guards.effects.apply(player, MobEffects.SLOWNESS, 2, 600, particles = false, turnId = turn)
            guards.attributes.apply(player, AttributeKey.SCALE, -0.5, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, until, harmful = true, turnId = turn)
        }

        /** The decisions of [kind], in order. */
        fun decisions(kind: DecisionRecord.Kind): List<DecisionRecord> = traces.decisions.filter { it.kind == kind }
    }

    private companion object {
        /** The French texts, as the test services read them. */
        val UI: UiTexts = UiTexts.load(NarratorConfig().language)

        fun turn(id: Long) = Caller(Role.DIRECTOR, "favor-gametest", turnId = id, taskId = null)

        /** [at] as the tools write a time of day, in the test clock's zone (UTC). */
        fun timeOf(at: Long): String = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(java.time.ZoneOffset.UTC).format(Instant.ofEpochMilli(at))

        /** [at] as a joker's refusal writes its date, in the test clock's zone (UTC). */
        fun dayTimeOf(at: Long): String = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(java.time.ZoneOffset.UTC).format(Instant.ofEpochMilli(at))

        fun jokers(player: ServerPlayer): Int =
            (0 until player.inventory.containerSize).sumOf { slot -> player.inventory.getItem(slot).let { if (ItemLedger.isJoker(it)) it.count else 0 } }

        /** Fills the 36 slots of [player]'s inventory: nothing more fits. */
        fun fill(player: ServerPlayer) {
            for (slot in 0 until 36) player.inventory.setItem(slot, ItemStack(Items.DIRT, 64))
        }

        /** Runs [test] with [count] players of unique names, removed however it ends, then succeeds. */
        fun withPlayers(helper: GameTestHelper, count: Int, test: (List<TestPlayer>) -> Unit) {
            val players = List(count) { TestPlayer(helper, "fv_") }
            players.forEach { it.forget() }
            try {
                test(players)
            } finally {
                players.forEach { it.close() }
            }
            helper.succeed()
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
