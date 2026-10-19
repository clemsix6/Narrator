package io.github.clemsix6.narrator2.director

import io.github.clemsix6.narrator2.guard.Confiscation
import io.github.clemsix6.narrator2.guard.LedgerPurpose
import io.github.clemsix6.narrator2.guard.PlayerPacing
import io.github.clemsix6.narrator2.guard.PunishmentCategory
import io.github.clemsix6.narrator2.llm.ClaudeClient
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.testing.MechanicsSamples
import io.github.clemsix6.narrator2.testing.TEST_API_KEY
import io.github.clemsix6.narrator2.testing.reply
import io.github.clemsix6.narrator2.testing.testSettings
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.TurnRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Director with the mechanics engine (M4 §4.4, §4.5, §15.1; task M4-10), against the strict fake Anthropic
 * server, a fake engine and fake guards, in virtual time: the engine's news woke it like salient events with
 * their exact lines and the trigger `mechanic`; its lines and the guards' pacing in the situation and the
 * briefing; its tools only when it runs; and the reminders, kept in the world: read back by a Director created
 * from it, told late when they came due while the server was down, dropped past a day.
 */
class DirectorMechanicsScenariosTest {
    @TempDir
    lateinit var dir: Path

    private val caller = Caller(Role.DIRECTOR, "director-test", 1, null)

    private fun DirectorHarness.decisions(kind: DecisionRecord.Kind, decision: String) =
        traces.decisions.filter { it.kind == kind && it.decision == decision }

    // ── Signals (M4 §4.4) ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `a mechanic's news wakes the Director like a salient event, with the trigger mechanic`() = DirectorHarness(dir).use { h ->
        h.online("Bartok")
        h.fake.enqueue(reply { text("…") })

        h.director.post(MechanicsSamples.jokerPlayed.copy(at = h.clock.millis()))
        h.advance(2_999)
        assertEquals(0, h.fake.requests.size, "grouped like a salient event: 3 s after the last one")
        h.advance(1)
        h.awaitTurns(1)

        assertContains(h.wakeText(0), "\n[-3s] JOKER Bartok played a joker: slowness II and scale ×0.4 lifted, 2 zombies dismissed\n</events>\n")
        assertEquals(TurnRecord.Trigger.MECHANIC, h.endedTurns().single().trigger)
        assertEquals("mechanic JOKER", h.decisions(DecisionRecord.Kind.WAKE, "woke").single().reason)
        h.assertInvariants()
    }

    @Test
    fun `the six news of the engine, line by line in the events of a wake-up`() = DirectorHarness(dir).use { h ->
        h.online("Bartok")
        h.fake.enqueue(reply { text("…") })
        val now = h.clock.millis()
        val signals = with(MechanicsSamples) { listOf(challengeWon, judge, duelStarted, ruleFired, offering, jokerPlayed) }

        signals.forEach { h.director.post(it.copy(at = now)) }
        h.advance(3_000)
        h.awaitTurns(1)

        val events = h.wakeText(0).substringAfter("\">\n").substringBefore("</events>").lines().filter { it.isNotEmpty() }
        assertEquals(
            listOf(
                "[-3s] CHALLENGE C-50 won by Mimosa in 9m12s (turtle_egg 1/1) · reward: Mimosa got 1x golden_apple · penalty: Bartok tiny (2) until 20:13 · " +
                    "bets: Josiane got back 12x iron_ingot (nobody else bet)",
                "[-3s] JUDGE C-53 « La plus belle statue » ended: judge it now with end_challenge C-53 (winners); participants: Bartok, Mimosa, Josiane; you have 15m",
                "[-3s] DUEL C-52 started: Bartok and Mimosa accepted; they fight until 21:27 (in 5m)",
                "[-3s] RULE C-61 fired: Bartok entered the_nether (-12 70 40); your note: « comité d'accueil prêt »",
                "[-3s] OFFERING O-812 Mimosa offers 10x diamond: take it by granting her a favor (favor, offering O-812) within 9m58s, or let it go back",
                "[-3s] JOKER Bartok played a joker: slowness II and scale ×0.4 lifted, 2 zombies dismissed",
            ),
            events,
        )
        assertEquals(TurnRecord.Trigger.MECHANIC, h.endedTurns().single().trigger)
        assertEquals("mechanic CHALLENGE C-50 + 5 items", h.decisions(DecisionRecord.Kind.WAKE, "woke").single().reason)
        h.assertInvariants()
    }

    @Test
    fun `an addressed chat is still the trigger when the engine's news come with it`() = DirectorHarness(dir).use { h ->
        h.online("Bartok")
        h.fake.enqueue(reply { text("…") })

        h.director.post(MechanicsSamples.challengeWon.copy(at = h.clock.millis()))
        h.chat("Bartok", "narrateur, j'ai perdu ?")
        h.advance(1_500)
        h.awaitTurns(1)

        assertEquals(TurnRecord.Trigger.CHAT, h.endedTurns().single().trigger)
        assertContains(h.wakeText(0), "CHALLENGE C-50 won by Mimosa")
        h.assertInvariants()
    }

    // ── Situation, briefing, tools (M4 §4.5, §12.1) ─────────────────────────────────────────────────

    @Test
    fun `the engine's lines and the guards' pacing end the situation and the briefing's engine state`() = DirectorHarness(dir).use { h ->
        h.online("Bartok")
        h.advance(1_000) // Mimosa arrives after Bartok: the order of the players is fixed
        h.online("Mimosa")
        val now = h.clock.millis() // 21:32:01
        h.mechanics.lines = listOf(
            "Challenges: C-50 obtain « Œuf de tortue » → all · Mimosa 0/1, Bartok 0/1 · ends in 12m · bets open 3m (2 bets) · next challenge possible in 6m",
            "Rules: C-60 law block_break grief → punish (2 fired, until 10-02 20:15)",
        )
        val bartok = DirectorHarness.ref("Bartok").uuid
        val mimosa = DirectorHarness.ref("Mimosa").uuid
        h.guards.pacingOf[bartok] = PlayerPacing(bartok, 2, 4, mapOf(PunishmentCategory.SIZE to now + 7 * 60_000L), null, emptyList())
        h.guards.pacingOf[mimosa] = PlayerPacing(
            mimosa, 0, 4, emptyMap(), now + 13 * 60_000L,
            listOf(Confiscation(812, emptyList(), "10x minecraft:diamond", now, now + 10 * 60_000L, null, null, LedgerPurpose.OFFERING)),
        )
        h.fake.enqueue(reply { text("…") })

        h.director.post(MechanicsSamples.offering.copy(at = now))
        h.advance(3_000)
        h.awaitTurns(1)

        val lines = listOf(
            "Challenges: C-50 obtain « Œuf de tortue » → all · Mimosa 0/1, Bartok 0/1 · ends in 12m · bets open 3m (2 bets) · next challenge possible in 6m",
            "Rules: C-60 law block_break grief → punish (2 fired, until 10-02 20:15)",
            "Pacing: punished this hour Bartok 2/4, Mimosa 0/4 · recent kinds Bartok size (again at 21:39) · grace Mimosa until 21:45",
            "Offerings: O-812 Mimosa 10x diamond (answer by 21:42)",
        )
        val situation = h.wakeText(0).substringAfter("<situation ")
        assertContains(situation, lines.joinToString("\n", postfix = "\n</situation>"))
        val state = h.userText(0).substringAfter("<engine_state>\n").substringBefore("</engine_state>")
        assertContains(state, lines.joinToString("\n", postfix = "\n"))
        h.assertInvariants()
    }

    @Test
    fun `a chapter has the engine's tools when it runs at the opening, not otherwise`() {
        DirectorHarness(dir.resolve("on").also(Files::createDirectories)).use { h ->
            h.online("Bartok")
            h.fake.enqueue(reply { text("…") })
            h.chat("Bartok", "narrateur ?")
            h.advance(1_500)
            h.awaitTurns(1)
            assertTrue(h.toolEnvs.single().mechanicsAvailable)
        }
        DirectorHarness(dir.resolve("off").also(Files::createDirectories), mechanicsOff = "mechanics.enabled is false").use { h ->
            h.online("Bartok")
            h.fake.enqueue(reply { text("…") })
            h.chat("Bartok", "narrateur ?")
            h.advance(1_500)
            h.awaitTurns(1)
            assertFalse(h.toolEnvs.single().mechanicsAvailable)
            assertFalse("Challenges:" in h.userText(0), "no engine, no line of its own")
            h.assertInvariants()
        }
    }

    // ── Reminders (M4 §15.1) ────────────────────────────────────────────────────────────────────────

    @Test
    fun `a reminder set is saved in the world, and the Director of the next run finds it and wakes with it`() {
        val world = InMemoryReminderStore()
        DirectorHarness(dir.resolve("first").also(Files::createDirectories), reminders = world).use { h ->
            val id = h.director.scheduleReminder(600, "vérifier que Bartok a rendu le coffre", caller).getOrThrow()
            assertEquals(1, id)
            assertEquals(
                ReminderState(2, listOf(SavedReminder(1, "vérifier que Bartok a rendu le coffre", h.clock.millis(), h.clock.millis() + 600_000, 1))),
                world.state,
                "saved as soon as it is set",
            )
        }
        // The server restarts within the ten minutes: the next Director reads the world.
        DirectorHarness(dir.resolve("second").also(Files::createDirectories), reminders = world).use { h ->
            h.online("Bartok")
            assertEquals(listOf(PendingReminder(1, "vérifier que Bartok a rendu le coffre", h.clock.millis() + 600_000)), h.director.status().reminders)
            assertEquals(2, h.director.scheduleReminder(1_800, "et Mimosa ?", caller).getOrThrow(), "the numbers go on")
            h.fake.enqueue(reply { text("…") })

            h.advance(600_000 + 2_999)
            assertEquals(0, h.fake.requests.size)
            h.advance(1)
            h.awaitTurns(1)

            assertContains(h.wakeText(0), "[-3s] REMINDER R-1 set 10m ago: \"vérifier que Bartok a rendu le coffre\"")
            assertEquals(TurnRecord.Trigger.REMINDER, h.endedTurns().single().trigger)
            assertEquals(listOf(2L), world.state.reminders.map { it.id }, "given back: no longer in the world")
            assertEquals(3, world.state.nextId)
            h.assertInvariants()
        }
    }

    @Test
    fun `the reminders saved with the world are read back with their numbers`() = DirectorHarness(dir, reminders = InMemoryReminderStore(MechanicsSamples.reminders)).use { h ->
        // R-3, due at 20:30 on 10-19, set by turn 42: still to come when this server starts (09-25).
        val saved = MechanicsSamples.reminders.reminders.single()
        assertEquals(listOf(PendingReminder(3, "revenir sur la statue de Mimosa", saved.at)), h.director.status().reminders)
        assertEquals(4, h.director.scheduleReminder(60, "plus tard", caller).getOrThrow())
        assertEquals(listOf(3L, 4L), h.game.reminders.state.reminders.map { it.id }.sorted())
    }

    @Test
    fun `a reminder that came due while the server was down wakes the Director at once, marked late`() {
        val start = DirectorHarness.START.toEpochMilli()
        val world = InMemoryReminderStore(
            ReminderState(4, listOf(SavedReminder(3, "revenir sur la statue de Mimosa", start - 12_600_000, start - 10_800_000, 42))),
        )
        DirectorHarness(dir, reminders = world).use { h ->
            h.online("Bartok")
            h.fake.enqueue(reply { text("…") })
            h.advance(3_000)
            h.awaitTurns(1)

            assertContains(
                h.wakeText(0),
                "\n[-3s] REMINDER R-3 set 3h30m ago, due 3h00m ago (late: the server was down): \"revenir sur la statue de Mimosa\"\n",
            )
            assertEquals(TurnRecord.Trigger.REMINDER, h.endedTurns().single().trigger)
            assertEquals(ReminderState(4, emptyList()), world.state)
            h.assertInvariants()
        }
    }

    @Test
    fun `a reminder more than a day late is dropped, with a decision, and wakes nobody`() {
        val start = DirectorHarness.START.toEpochMilli()
        val world = InMemoryReminderStore(
            ReminderState(
                6,
                listOf(
                    SavedReminder(4, "le défi de la veille", start - 26 * 3_600_000L, start - 25 * 3_600_000L, 40),
                    SavedReminder(5, "toujours à venir", start - 60_000, start + 3_600_000, 41),
                ),
            ),
        )
        DirectorHarness(dir, reminders = world).use { h ->
            h.online("Bartok")
            h.advance(60_000)

            assertEquals(0, h.fake.requests.size, "nothing woke the Director")
            val dropped = h.decisions(DecisionRecord.Kind.WAKE, "dropped").single()
            assertEquals("R-4", dropped.subject)
            assertEquals(
                "reminder R-4 came due 1d1h ago, while the server was down: more than 1d0h late, it is dropped (\"le défi de la veille\")",
                dropped.reason,
            )
            assertEquals(40, dropped.turnId)
            assertEquals(listOf(5L), h.director.status().reminders.map { it.id })
            assertEquals(ReminderState(6, listOf(SavedReminder(5, "toujours à venir", start - 60_000, start + 3_600_000, 41))), world.state)
        }
    }

    @Test
    fun `a disabled Director keeps its reminders in the world, and tells them once it runs again`() = DirectorHarness(dir).use { h ->
        h.online("Bartok")
        val config = h.services.settings.get().config
        h.director.scheduleReminder(60, "le coffre de Bartok", caller).getOrThrow()
        val withoutKey = testSettings(config, null)
        h.director.post(DirectorSignal.SettingsChanged(h.services.settings.swap(withoutKey), withoutKey))
        h.runCurrent()
        assertEquals(DirectorState.DISABLED, h.director.status().state)

        h.advance(120_000)
        assertEquals(listOf(1L), h.director.status().reminders.map { it.id }, "due, but nobody listens: it waits")
        assertEquals(listOf(1L), h.game.reminders.state.reminders.map { it.id })

        h.fake.enqueue(reply { text("…") })
        val withKey = testSettings(config, ClaudeClient(config, TEST_API_KEY))
        try {
            h.director.post(DirectorSignal.SettingsChanged(h.services.settings.swap(withKey), withKey))
            h.runCurrent()
            h.awaitTurns(1)
            assertContains(h.wakeText(0), "REMINDER R-1 set 1m ago: \"le coffre de Bartok\"")
            assertTrue(h.game.reminders.state.reminders.isEmpty())
            h.assertInvariants()
        } finally {
            withKey.close()
        }
    }
}
