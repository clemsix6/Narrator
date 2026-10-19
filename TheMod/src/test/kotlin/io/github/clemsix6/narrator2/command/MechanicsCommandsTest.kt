package io.github.clemsix6.narrator2.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.github.clemsix6.narrator2.guard.ActiveEffect
import io.github.clemsix6.narrator2.guard.Confiscation
import io.github.clemsix6.narrator2.guard.GuardState
import io.github.clemsix6.narrator2.guard.LedgerPurpose
import io.github.clemsix6.narrator2.guard.PlayerPacing
import io.github.clemsix6.narrator2.guard.PunishmentCategory
import io.github.clemsix6.narrator2.mechanics.ChallengeOutcome
import io.github.clemsix6.narrator2.mechanics.ChallengeStatus
import io.github.clemsix6.narrator2.mechanics.ChallengeView
import io.github.clemsix6.narrator2.mechanics.DisabledMechanics
import io.github.clemsix6.narrator2.mechanics.MechanicRejected
import io.github.clemsix6.narrator2.mechanics.RuleView
import io.github.clemsix6.narrator2.mechanics.rule.RuleText
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.testing.FakeMechanics
import io.github.clemsix6.narrator2.testing.MechanicsSamples
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.trace.DecisionRecord
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import net.minecraft.commands.CommandSourceStack
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `/narrator challenge …`, `/narrator rule …`, `/narrator traps …` (M4 §18.1) against [FakeMechanics]: what
 * each command renders, what the cancel and the removal ask of the engine (as the operator) and the `operator`
 * decisions they write; the refusal without the engine; the `Mechanics:` line of `/narrator status`; the lines
 * `/narrator guards <player>` gained (pace, grace, jokers, the ledger's entries); and the command tree.
 */
class MechanicsCommandsTest {
    private val clock = TestClock(Instant.ofEpochMilli(MechanicsSamples.evening), ZoneOffset.UTC)
    private val now = clock.millis()
    private val mechanics = FakeMechanics(clock)
    private val services = TestServices(clock = clock, mechanics = mechanics)

    private fun DirectorCommands.Reply.text() = lines.map { it.string }

    private fun operatorDecisions() =
        services.traces.decisions.filter { it.kind == DecisionRecord.Kind.OPERATOR }.map { "${it.decision} ${it.subject}: ${it.reason}" }

    private fun challenge(id: Long, line: String, duel: Boolean = false) = ChallengeView(
        Ref(RefKind.CANON, id), if (duel) null else MechanicsSamples.turtleEgg, if (duel) MechanicsSamples.duel else null,
        if (duel) ChallengeStatus.INVITED else ChallengeStatus.RUNNING, now + 600_000, emptyList(), 0, line,
    )

    private fun rule(name: String, id: Long, fired: Int = 0, skipped: Int = 0, failures: Int = 0): RuleView {
        val spec = MechanicsSamples.rules.getValue(name)
        return RuleView(Ref(RefKind.CANON, id), spec, now + spec.minutes * 60_000L, fired, skipped, failures, "C-$id ${spec.kind.id} ${spec.trigger.id}")
    }

    // ── Without the engine ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `without the engine challenge and rule are refused with the reason, and status says it`() = runBlocking<Unit> {
        val off = TestServices(clock = clock, mechanics = DisabledMechanics("not wired yet"))
        val refusal = "[Narrator] the mechanics are unavailable (not wired yet): no challenge nor rule to show or change."
        for (reply in listOf(
            MechanicsCommands.challengeList(off, 1),
            MechanicsCommands.ruleList(off, 1),
            MechanicsCommands.cancel(off, "Clement", "C-50", null),
            MechanicsCommands.removeRule(off, "Clement", "C-60", null),
        )) {
            assertFalse(reply.done)
            assertEquals(listOf(refusal), reply.text())
        }
        assertNull(MechanicsCommands.refusal(mechanics), "an engine that runs refuses nothing")
        assertTrue(off.traces.decisions.isEmpty(), "nothing done, nothing recorded")
        assertEquals("Mechanics: off (not wired yet) · 3 trap blocks pending", MechanicsCommands.statusLine(off.mechanics, 3))
    }

    // ── /narrator status ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the status line counts the challenges, the duels, the rules and the trap blocks`() {
        assertEquals("Mechanics: on · 0 challenges · 0 rules · 0 trap blocks pending", MechanicsCommands.statusLine(mechanics, 0))
        mechanics.challenges.views += challenge(50, "C-50 …")
        mechanics.rules.views += rule("grief_law", 60)
        assertEquals("Mechanics: on · 1 challenge · 1 rule · 1 trap block pending", MechanicsCommands.statusLine(mechanics, 1))
        mechanics.challenges.views += challenge(51, "C-51 …")
        mechanics.challenges.views += challenge(52, "C-52 …", duel = true)
        mechanics.rules.views += rule("welcome_plan", 61)
        mechanics.rules.views += rule("silverfish", 62)
        assertEquals("Mechanics: on · 2 challenges, 1 duel · 3 rules · 12 trap blocks pending", MechanicsCommands.statusLine(mechanics, 12))
    }

    // ── challenge ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `challenge list shows each challenge and duel by its line, in pages when long`() {
        assertEquals(listOf("[Narrator] challenges and duels · none in progress"), MechanicsCommands.challengeList(services, 1).text())

        mechanics.challenges.views += challenge(50, "C-50 obtain 1 turtle_egg « Œuf de tortue » → all · Mimosa 0/1, Bartok 1/1 (done) · ends in 12m")
        mechanics.challenges.views += challenge(52, "C-52 duel « Duel au soleil » Bartok vs Mimosa · invited, Bartok accepted, waiting for Mimosa", duel = true)
        val list = MechanicsCommands.challengeList(services, 1)
        assertTrue(list.done)
        assertEquals(
            listOf(
                "[Narrator] challenges and duels · 2 in progress",
                "C-50 obtain 1 turtle_egg « Œuf de tortue » → all · Mimosa 0/1, Bartok 1/1 (done) · ends in 12m",
                "C-52 duel « Duel au soleil » Bartok vs Mimosa · invited, Bartok accepted, waiting for Mimosa",
                "Cancel one (no stakes, the bets go back): /narrator challenge cancel <id> [note]",
            ),
            list.text(),
        )

        mechanics.challenges.views.clear()
        (1L..5L).forEach { id -> mechanics.challenges.views += challenge(id, "C-$id " + "obtain 1 turtle_egg · Bartok 0/1 · ".repeat(12) + "ends in 12m") }
        val first = MechanicsCommands.challengeList(services, 1).text()
        assertTrue(first.first().startsWith("[Narrator] challenges and duels · 5 in progress · page 1/"), first.first())
        assertTrue(first.size <= 20, "a page fits the open chat: ${first.size} lines")
        assertTrue(first.last().startsWith("page 1/") && "/narrator challenge list 2" in first.last(), first.last())
    }

    @Test
    fun `challenge cancel ends it as the operator, without stakes, and records it`() = runBlocking<Unit> {
        mechanics.challenges.views += challenge(50, "C-50 cancelled (ended by the operator Clement); note: « … » · bets: 1 stake given back")
        val reply = MechanicsCommands.cancel(services, "Clement", "c50", "Le défi a été annulé pour une panne du serveur.")
        assertTrue(reply.done, reply.text().toString())
        assertEquals(
            listOf("[Narrator] C-50 cancelled by Clement: no stakes, its bets go back.", "C-50 cancelled (ended by the operator Clement); note: « … » · bets: 1 stake given back"),
            reply.text(),
        )
        val ended = mechanics.challenges.ended.single()
        assertEquals(Ref(RefKind.CANON, 50), ended.ref)
        assertEquals(ChallengeOutcome.CANCELLED, ended.outcome)
        assertEquals(emptyList(), ended.winners)
        assertEquals("Le défi a été annulé pour une panne du serveur.", ended.note)
        assertEquals(Author(Source.OPERATOR, operator = "Clement"), ended.author)
        assertEquals(listOf("challenge_cancel Clement: C-50 cancelled: « Le défi a été annulé pour une panne du serveur. »"), operatorDecisions())
        assertEquals("C-50", services.traces.decisions.single().detail!!.getValue("ref").jsonPrimitive.content)

        mechanics.challenges.views += challenge(51, "C-51 cancelled (ended by the operator Clement); note: « cancelled by an operator »")
        MechanicsCommands.cancel(services, "Clement", "51", "  ")
        assertEquals(MechanicsCommands.CANCEL_NOTE, mechanics.challenges.ended.last().note, "a cancel without a note")
    }

    @Test
    fun `challenge cancel refuses what is not a challenge in progress, and the engine's refusals`() = runBlocking<Unit> {
        mechanics.challenges.views += challenge(52, "C-52 duel …", duel = true)
        assertEquals(
            listOf("[Narrator] M-12 is not a challenge's reference (C-50, for instance)."),
            MechanicsCommands.cancel(services, "Clement", "M-12", null).text(),
        )
        assertEquals(
            listOf("[Narrator] C-50 is not a challenge in progress; in progress: C-52."),
            MechanicsCommands.cancel(services, "Clement", "C-50", null).text(),
        )
        mechanics.challenges.rejection = MechanicRejected(null, "note must not be empty")
        val refused = MechanicsCommands.cancel(services, "Clement", "C-52", null)
        assertFalse(refused.done)
        assertEquals(listOf("[Narrator] refused: note must not be empty"), refused.text())
        assertTrue(operatorDecisions().isEmpty(), "nothing cancelled, nothing recorded")
    }

    // ── rule ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `rule list shows each rule's kind, end, counters, trigger, effects and statement`() {
        assertEquals(listOf("[Narrator] rules · none active (8 at most)"), MechanicsCommands.ruleList(services, 1).text())

        mechanics.rules.views += rule("grief_law", 60, fired = 12, skipped = 3, failures = 1)
        mechanics.rules.views += rule("welcome_plan", 61)
        val grief = MechanicsSamples.rules.getValue("grief_law")
        assertEquals(
            listOf(
                "[Narrator] rules · 2 active (8 at most)",
                "C-60 law · until 10-26 18:00 (in 7d0h) · 12 fired, 3 skipped, 1 failed in a row",
                "  block_break where grief → punish tiny (2) on the actor, actionbar to the actor",
                "  « Le Narrateur a décrété que quiconque casse la construction d'un autre joueur est rapetiss… »",
                "C-61 plan, once · until 10-20 18:00 (in 1d0h) · 0 fired, 0 skipped, 0 failed in a row",
                "  dimension_change where Bartok, the_nether → spawn 3x zombified_piglin on the actor, notify on",
                "      the actor",
                "  « Le Narrateur a prévu un comité d'accueil pour Bartok à sa prochaine entrée dans le Nether. »",
                "Remove one: /narrator rule remove <id> [note]",
            ),
            MechanicsCommands.ruleList(services, 1).text(),
        )
        assertEquals("block_break where grief → punish tiny (2) on the actor, actionbar to the actor", RuleText.summary(grief), "the summary of the rules' own text")
    }

    @Test
    fun `rule remove ends it as the operator, after the note's voice is checked`() = runBlocking<Unit> {
        mechanics.rules.views += rule("grief_law", 60, fired = 12, skipped = 3)
        val firstPerson = MechanicsCommands.removeRule(services, "Clement", "C-60", "Je retire cette loi.")
        assertFalse(firstPerson.done)
        assertTrue(firstPerson.text().single().startsWith("[Narrator] refused: note "), firstPerson.text().toString())
        assertTrue(mechanics.rules.removed.isEmpty(), "a note the canon would refuse removes nothing")

        val reply = MechanicsCommands.removeRule(services, "Clement", "C-60", "La loi a été levée par un opérateur.")
        assertTrue(reply.done, reply.text().toString())
        assertEquals(
            listOf(
                "[Narrator] C-60 law retired by Clement: it fires no more.",
                "12 fired, 3 skipped · block_break where grief → punish tiny (2) on the actor, actionbar to the actor",
            ),
            reply.text(),
        )
        assertEquals(listOf(Ref(RefKind.CANON, 60)), mechanics.rules.removed.map { it.ref })
        assertEquals("La loi a été levée par un opérateur.", mechanics.rules.removed.single().note)
        assertEquals(Author(Source.OPERATOR, operator = "Clement"), mechanics.rules.removed.single().author)
        assertEquals(listOf("rule_remove Clement: C-60 law retired: « La loi a été levée par un opérateur. »"), operatorDecisions())

        mechanics.rules.views += rule("welcome_plan", 61)
        assertEquals(listOf("[Narrator] C-61 plan cancelled by Clement: it fires no more."), MechanicsCommands.removeRule(services, "Clement", "61", null).text().take(1))
        assertEquals(MechanicsCommands.REMOVE_NOTE, mechanics.rules.removed.last().note, "a removal without a note")
        assertEquals(
            listOf("[Narrator] C-60 is not an active rule; active: none."),
            MechanicsCommands.removeRule(services, "Clement", "C-60", null).text(),
        )
    }

    // ── traps ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `traps lists the blocks of each player, and restore says what went`() {
        val bartok = MechanicsSamples.bartok
        val stranger = UUID.fromString("00000000-0000-4000-8000-00000000c0de")
        val names = { uuid: UUID -> if (uuid == bartok.uuid) "Bartok" else uuid.toString() }
        assertEquals(listOf("[Narrator] traps · no block pending in the loaded chunks"), MechanicsCommands.trapList(emptyMap(), 0, names, now, ZoneOffset.UTC).map { it.string })
        val traps = mapOf(
            stranger to listOf(ActiveEffect("trap", "3 minecraft:cobweb", now - 500, harmful = true)),
            bartok.uuid to listOf(ActiveEffect("trap", "22 minecraft:glass", now + 8_000, harmful = true), ActiveEffect("trap", "1 minecraft:cobweb", now + 20_000, harmful = true)),
        )
        assertEquals(
            listOf(
                "[Narrator] traps · 26 blocks pending in the loaded chunks",
                "$stranger: 3 minecraft:cobweb until 17:59:59 (due)",
                "Bartok: 22 minecraft:glass until 18:00:08 (8s left), 1 minecraft:cobweb until 18:00:20 (20s left)",
                "/narrator traps restore takes them all away now.",
            ),
            MechanicsCommands.trapList(traps, 26, names, now, ZoneOffset.UTC).map { it.string },
        )

        assertEquals(listOf("[Narrator] no trap block pending in the loaded chunks: nothing to take away."), MechanicsCommands.restoreReply(0, 0, 0).text())
        assertEquals(listOf("[Narrator] 26 trap blocks taken away now."), MechanicsCommands.restoreReply(26, 26, 0).text())
        assertEquals(
            listOf("[Narrator] 24 trap blocks taken away now; 1 changed by a player since set, left as it is; 1 in a chunk still loading, taken away at the next pass."),
            MechanicsCommands.restoreReply(26, 24, 1).text(),
        )
    }

    // ── guards ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `guards shows the pace, the grace and the jokers`() {
        val quiet = GuardCommands.favorLines(GuardState.EMPTY, null, 0, now, ZoneOffset.UTC)
        assertEquals(listOf("Grace: none", "Jokers: none given in the last 24 h · 0 in their inventory"), quiet)

        val offering = Confiscation(812, emptyList(), "10x minecraft:diamond", now - 60_000, now + 540_000, null, null, LedgerPurpose.OFFERING)
        val pacing = PlayerPacing(
            MechanicsSamples.bartok.uuid, punishedThisHour = 2, perHour = 4,
            resting = mapOf(PunishmentCategory.AIR to now + 1_800_000, PunishmentCategory.SIZE to now + 600_000),
            graceUntil = now + 900_000, offerings = listOf(offering),
        )
        val state = GuardState(graceUntil = now + 900_000, lastGraceEnd = now + 900_000, jokersGivenAt = listOf(now - Duration.ofHours(30).toMillis(), now - 3_600_000))
        assertEquals(
            listOf(
                "Pace: punished 2 of 4 this hour · categories resting: size until 18:10:00, air until 18:30:00 · offerings waiting: O-812 until 18:09:00",
                "Grace: until 18:15:00 (15m left): the Narrator does nothing hostile to them; the next from 19:15:00",
                "Jokers: 1 given in the last 24 h (17:00:00); the next is allowed from 10-20 17:00 · 2 in their inventory",
            ),
            GuardCommands.favorLines(state, pacing, 2, now, ZoneOffset.UTC),
        )
        val ended = GuardState(graceUntil = now - 600_000, lastGraceEnd = now - 600_000)
        assertEquals("Grace: none; the last ended at 17:50:00, the next is allowed from 18:50:00", GuardCommands.favorLines(ended, null, 0, now, ZoneOffset.UTC).first())
    }

    @Test
    fun `guards names each entry of the ledger and when it comes back`() {
        val mimosa = MechanicsSamples.mimosa
        val names = { uuid: UUID -> if (uuid == mimosa.uuid) "Mimosa" else uuid.toString() }
        fun line(entry: Confiscation) = GuardCommands.ledgerLine(entry, now, ZoneOffset.UTC, names)
        val week = Duration.ofDays(7).toMillis()
        assertEquals(
            "Confiscation #7: 3x minecraft:diamond, back at 18:12:00 (12m left) · reason \"a volé\" · turn #42",
            line(Confiscation(7, emptyList(), "3x minecraft:diamond", now, now + 720_000, "a volé", 42)),
        )
        assertEquals(
            "Confiscation #8: 1x minecraft:bow, due, waiting for room in the inventory",
            line(Confiscation(8, emptyList(), "1x minecraft:bow", now - 900_000, now - 1, null, null)),
        )
        assertEquals(
            "Stake #812 on C-50: 12x minecraft:iron_ingot, settled when C-50 ends; back at 10-26 18:15 at the latest",
            line(Confiscation(812, emptyList(), "12x minecraft:iron_ingot", now, now + week + 900_000, "bet", null, LedgerPurpose.STAKE, canon = 50)),
        )
        assertEquals(
            "Stake #813 on C-50: 4x minecraft:gold_ingot, lost to Mimosa, handed over once both are online; back to its owner at 11-18 18:00 otherwise",
            line(Confiscation(813, emptyList(), "4x minecraft:gold_ingot", now, now + Duration.ofDays(30).toMillis(), "bet", null, LedgerPurpose.STAKE, canon = 50, owedTo = mimosa.uuid)),
        )
        assertEquals(
            "Offering O-814: 10x minecraft:diamond, waiting for the Director's answer until 18:10:00 (10m left)",
            line(Confiscation(814, emptyList(), "10x minecraft:diamond", now, now + 600_000, "offering", null, LedgerPurpose.OFFERING)),
        )
        assertEquals(
            "Offering O-815: 10x minecraft:diamond, not taken, going back as soon as there is room",
            line(Confiscation(815, emptyList(), "10x minecraft:diamond", now - 600_000, now, "offering", null, LedgerPurpose.OFFERING)),
        )
        assertEquals(
            "Owed #816: 12x minecraft:iron_ingot, won on C-50, waiting for room in the inventory",
            line(Confiscation(816, emptyList(), "12x minecraft:iron_ingot", now, now, "won", null, LedgerPurpose.OWED, canon = 50)),
        )
    }

    // ── The command tree ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `every documented syntax parses to a command`() {
        val dispatcher = CommandDispatcher<CommandSourceStack>()
        dispatcher.register(LiteralArgumentBuilder.literal<CommandSourceStack>("narrator").also(MechanicsCommands::attach))
        fun parsed(command: String): List<String> {
            val result = dispatcher.parse(command, null)
            assertTrue(!result.reader.canRead() && result.exceptions.isEmpty() && result.context.command != null, "$command does not parse: ${result.exceptions}")
            return result.context.nodes.map { it.node.name }
        }
        assertEquals(listOf("narrator", "challenge"), parsed("narrator challenge"))
        assertEquals(listOf("narrator", "challenge", "list"), parsed("narrator challenge list"))
        assertEquals(listOf("narrator", "challenge", "list", "page"), parsed("narrator challenge list 2"))
        assertEquals(listOf("narrator", "challenge", "cancel", "id"), parsed("narrator challenge cancel C-50"))
        assertEquals(listOf("narrator", "challenge", "cancel", "id", "note"), parsed("narrator challenge cancel C-50 Le défi a été annulé."))
        assertEquals(listOf("narrator", "rule"), parsed("narrator rule"))
        assertEquals(listOf("narrator", "rule", "list"), parsed("narrator rule list"))
        assertEquals(listOf("narrator", "rule", "list", "page"), parsed("narrator rule list 2"))
        assertEquals(listOf("narrator", "rule", "remove", "id"), parsed("narrator rule remove C-60"))
        assertEquals(listOf("narrator", "rule", "remove", "id", "note"), parsed("narrator rule remove 60 La loi a été levée."))
        assertEquals(listOf("narrator", "traps"), parsed("narrator traps"))
        assertEquals(listOf("narrator", "traps", "restore"), parsed("narrator traps restore"))
    }

    @Test
    fun `a challenge or a rule is named by its canon reference, or its number`() {
        assertEquals(Ref(RefKind.CANON, 50), MechanicsCommands.refOf("C-50"))
        assertEquals(Ref(RefKind.CANON, 50), MechanicsCommands.refOf("c50"))
        assertEquals(Ref(RefKind.CANON, 50), MechanicsCommands.refOf(" 50 "))
        assertNull(MechanicsCommands.refOf("M-50"), "a memory is not a challenge")
        assertNull(MechanicsCommands.refOf("O-812"))
        assertNull(MechanicsCommands.refOf("0"))
        assertNull(MechanicsCommands.refOf("fifty"))
    }
}
