package io.github.clemsix6.narrator2.director

import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.mechanics.ForgeHalt
import io.github.clemsix6.narrator2.mechanics.RuleKind
import io.github.clemsix6.narrator2.testing.ForgeSamples
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.testing.reply
import io.github.clemsix6.narrator2.tool.ToolCatalog
import io.github.clemsix6.narrator2.tool.mechanics.moduleTools
import io.github.clemsix6.narrator2.trace.ConversationRecord.EndReason
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.TaskRecord
import io.github.clemsix6.narrator2.trace.TurnRecord
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Director with the Forge (M5 §9, §16.4, task M5-12), against the strict fake Anthropic server, fake workers, a
 * fake Forge and a fake engine, in virtual time, with the real tools of the Forge:
 * - S-F1: a prayer for a new game → `forge_module` → the forger (scripted: its test and save) → `TASK` →
 *   `deploy_module` → the next situation shows `Modules:` → its end → `MODULE`;
 * - S-F2: a disabled module's `MODULE` has no priority: past the budget of turns, it waits;
 * - S-F3: a module that talks (`tellDirector`, once every 5 minutes) wakes the Director, 6 times an hour at most;
 * - S-F4: the Forge switched off by an operator: the three tools refuse `DENIED (forge_off)`, and the next chapter
 *   shows neither them nor the arsenal.
 */
class DirectorForgeScenariosTest {
    @TempDir
    lateinit var dir: Path

    private val forgeRoles = setOf(Role.ANALYST, Role.FORGER)

    private fun DirectorHarness.decisions(kind: DecisionRecord.Kind, decision: String) =
        traces.decisions.filter { it.kind == kind && it.decision == decision }

    /** The turns started so far, ended or not. */
    private fun DirectorHarness.startedTurns(): Int = traces.turns.map { it.turnId }.distinct().size

    /** The tool results of the last `user` message of request [request], in order. */
    private fun DirectorHarness.toolResults(request: Int): List<String> =
        lastUser(request).path("content").filter { it.path("type").asText() == "tool_result" }.map { it.path("content").asText() }

    private fun DirectorHarness.lastWake(): String = wakeText(fake.requests.size - 1)

    private fun module(line: String, at: Long) = DirectorSignal.Mechanic(MechanicSignal.MODULE, line, at, priority = false)

    // ── S-F1 ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `S-F1 a prayer for a new game - the forger is commissioned, its report comes back, the module runs, shows and ends`() =
        DirectorHarness(dir, workerRoles = forgeRoles, tools = moduleTools(TestServices())).use { h ->
            h.online("Mimosa")
            h.advance(1_000)
            h.online("Bartok")
            val brief = "Un 1, 2, 3, soleil pour Mimosa et Bartok : qui bouge quand le Narrateur regarde recule de dix blocs ; dix minutes ; " +
                "le premier arrivé gagne ; jamais de dégâts."
            h.fake.enqueue(reply { toolUse("forge_module", """{"brief": "$brief"}""") })
            h.fake.enqueue(reply { text("…") })

            h.chat("Mimosa", "narrateur, invente-nous un jeu", channel = ChatChannel.PRAY)
            h.advance(1_500)
            h.awaitTurns(1)

            assertContains(h.userText(0), "\nArsenal: none\n", message = "the arsenal is in the briefing, empty")
            assertEquals(listOf("ok: task T-1 forger started; its report will arrive as a TASK event"), h.toolResults(1))
            val task = h.workers.submitted.single()
            assertEquals(Role.FORGER to brief, task.role to task.brief)

            // The forger, scripted: it tests, saves, and reports.
            runBlocking {
                h.forge.test("statues", ForgeSamples.SOURCE)
                h.forge.save("statues", ForgeSamples.SOURCE, "Red light, green light.", "forger T-1")
            }
            h.fake.enqueue(
                reply {
                    toolUse(
                        "deploy_module",
                        """{"module": "statues", "params": {"players": ["Mimosa", "Bartok"]}, "kind": "law",
                           "statement": "Le Narrateur a lancé un 1, 2, 3, soleil entre Mimosa et Bartok.", "quote": "Un, deux, trois… soleil !", "minutes": 10}""",
                    )
                },
            )
            h.fake.enqueue(reply { text("…") })
            val report = "Module statues v1 (ready). Paramètres : players (2-8). Ce que voient les joueurs : un 1, 2, 3, soleil."
            h.director.post(DirectorSignal.TaskDone(1, Role.FORGER, TaskRecord.Status.DONE, report, h.clock.millis()))
            h.advance(3_000)
            h.awaitTurns(2)

            assertContains(h.wakeText(2), "[-3s] TASK T-1 forger done: \"$report\"\n")
            assertEquals(TurnRecord.Trigger.TASK, h.endedTurns()[1].trigger)
            assertEquals(
                listOf("ok: module statues v1 deployed as C-1 (law) until 21:42 (in 10m): players Mimosa,Bartok; announce it if you like"),
                h.toolResults(3),
            )
            val (spec, caller) = h.mechanics.modules.deployed.single()
            assertEquals("statues" to RuleKind.LAW, spec.module to spec.kind)
            assertEquals(h.endedTurns()[1].turnId, caller.turnId)

            // The next situation shows the module, from the engine's lines.
            h.mechanics.lines = listOf("Modules: C-1 statues law Mimosa, Bartok (until 21:42)")
            h.fake.enqueue(reply { text("…") })
            h.chat("Bartok", "narrateur, c'est quoi ce jeu ?")
            h.advance(1_500)
            h.awaitTurns(3)
            assertContains(h.lastWake(), "\nModules: C-1 statues law Mimosa, Bartok (until 21:42)\n</situation>")

            // Its end is a MODULE event, without priority: it wakes the Director like a salient event.
            h.mechanics.lines = emptyList()
            h.fake.enqueue(reply { text("…") })
            h.director.post(module("C-1 statues ended by itself: « Mimosa a gagné, Bartok a bougé »", h.clock.millis()))
            h.advance(3_000)
            h.awaitTurns(4)

            assertContains(h.lastWake(), "\n[-3s] MODULE C-1 statues ended by itself: « Mimosa a gagné, Bartok a bougé »\n</events>")
            assertEquals(TurnRecord.Trigger.MECHANIC, h.endedTurns()[3].trigger)
            assertEquals("mechanic MODULE C-1; for the modules' news (1 of 6 this hour)", h.decisions(DecisionRecord.Kind.WAKE, "woke").last().reason)
            // The real director.md, its section on the Forge included (M5-20), is the system prompt of every request.
            h.fake.requests.forEach { assertContains(it.json.path("system").toString(), "### La Forge") }
            h.assertInvariants()
        }

    // ── S-F2 ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `S-F2 a disabled module's news has no priority - past the budget of turns it waits for the next wake-up`() =
        DirectorHarness(dir, turnsPerHour = 1).use { h ->
            h.online("Bartok")
            repeat(2) { h.fake.enqueue(reply { text("…") }) }
            h.chat("Bartok", "narrateur ?")
            h.advance(1_500)
            h.awaitTurns(1)

            val disabled = "C-72 gravity_storm disabled: a timer ran past 20 ms twice in 10 minutes (GravityStorm.java:41); ask the forger to fix it"
            h.director.post(module(disabled, h.clock.millis()))
            h.advance(600_000)
            assertEquals(1, h.startedTurns(), "the budget of the hour holds: the module's news waits")

            h.chat("Bartok", "narrateur, il se passe quoi ?", channel = ChatChannel.PRAY)
            h.advance(1_500)
            h.awaitTurns(2)
            assertContains(h.lastWake(), "] MODULE $disabled\n")
            assertEquals(TurnRecord.Trigger.PRAY, h.endedTurns()[1].trigger)
            h.assertInvariants()
        }

    // ── S-F3 ────────────────────────────────────────────────────────────────────────────────────────

    /**
     * A module tells the Director something at most once every 5 minutes (`tellDirector`, the engine's limit, M5-11):
     * each line wakes it, but the modules' news alone wake it 6 times an hour at most, all modules together; beyond,
     * a line waits for the next wake-up (a decision `salience throttled` says so), and the hour slides.
     */
    @Test
    fun `S-F3 a module that talks wakes the Director once every 5 minutes, 6 times an hour at most, then its lines wait`() =
        DirectorHarness(dir, heartbeatMinutes = listOf(60, 60)).use { h ->
            h.online("Bartok")
            repeat(10) { h.fake.enqueue(reply { text("…") }) }
            fun says(n: Int) = h.director.post(module("C-73 simon_says says: « Bartok est éliminé, manche $n »", h.clock.millis()))

            repeat(WakeScheduler.MODULE_NEWS_PER_HOUR) { i ->
                says(i + 1)
                h.advance(3_000)
                h.awaitTurns(i + 1)
                h.advance(297_000)
            }
            assertContains(h.wakeText(0), "\n[-3s] MODULE C-73 simon_says says: « Bartok est éliminé, manche 1 »\n</events>")
            assertEquals(
                (1..6).map { "mechanic MODULE C-73; for the modules' news ($it of 6 this hour)" },
                h.decisions(DecisionRecord.Kind.WAKE, "woke").map { it.reason },
            )

            says(7)
            h.advance(600_000)
            assertEquals(6, h.startedTurns(), "the seventh line of the hour waits")
            assertEquals(
                "the 6 wake-ups for the modules' news this hour are spent: it waits for the next wake-up",
                h.decisions(DecisionRecord.Kind.SALIENCE, "throttled").single { it.subject == "MODULE C-73" }.reason,
            )

            // The next wake-up takes it along, and spends nothing of the modules' wake-ups.
            h.chat("Bartok", "narrateur, qui a gagné ?")
            h.advance(1_500)
            h.awaitTurns(7)
            assertContains(h.lastWake(), "MODULE C-73 simon_says says: « Bartok est éliminé, manche 7 »\n")
            assertEquals(TurnRecord.Trigger.CHAT, h.endedTurns()[6].trigger)

            // An hour after the first, the oldest wake-up has left the hour: a line wakes the Director again.
            h.advance(Duration.ofMinutes(21).toMillis())
            says(8)
            h.advance(3_000)
            h.awaitTurns(8)
            assertEquals("mechanic MODULE C-73; for the modules' news (6 of 6 this hour)", h.decisions(DecisionRecord.Kind.WAKE, "woke").last().reason)
            assertEquals(7, h.director.status().turnsThisHour, "they are turns of the budget too: all but the first of the hour")
            h.assertInvariants()
        }

    // ── S-F4 ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `S-F4 the Forge switched off - the three tools refuse, and the next chapter shows neither them nor the arsenal`() =
        DirectorHarness(dir, workerRoles = forgeRoles, tools = moduleTools(TestServices())).use { h ->
            h.online("Bartok")
            h.forge.arsenal.add(ForgeSamples.manifest.copy(savedAt = DirectorHarness.START.minus(Duration.ofDays(2))))
            h.fake.enqueue(reply { text("…") })
            h.chat("Bartok", "narrateur ?")
            h.advance(1_500)
            h.awaitTurns(1)

            assertTrue(h.toolEnvs.single().forgeAvailable, "the first chapter opened with the Forge")
            assertContains(h.userText(0), "\nArsenal (1): tag_game v1 — Tag: the wolf passes on by touch; whoever was the wolf the least wins. (players 2-8; minutes 2-15 =5; mood taquin|sévère =taquin)\n")

            // An operator switches the Forge off: the chapter keeps its tools, which refuse.
            h.mechanics.modules.halted = ForgeHalt("trop de poules", "Clément", h.clock.millis())
            h.fake.enqueue(
                reply {
                    toolUse("forge_module", """{"brief": "Un jeu de chat perché pour Bartok, dix minutes, le moins longtemps loup gagne."}""")
                    toolUse("deploy_module", """{"module": "tag_game", "kind": "law", "statement": "Le Narrateur a lancé une partie de chat.", "minutes": 10}""")
                    toolUse("stop_module", """{"id": "C-70", "note": "Le Narrateur a arrêté le jeu."}""")
                },
            )
            h.fake.enqueue(reply { text("…") })
            h.chat("Bartok", "narrateur, un jeu !")
            h.advance(1_500)
            h.awaitTurns(2)

            assertEquals(List(3) { "DENIED (forge_off): the Forge was switched off by Clément: trop de poules" }, h.toolResults(2))
            assertTrue(h.workers.submitted.isEmpty() && h.mechanics.modules.deployed.isEmpty() && h.mechanics.modules.stopped.isEmpty())

            // The next chapter opens without them, and without the arsenal.
            h.fake.enqueue(reply { text("Résumé : Bartok a demandé un jeu ; la Forge était coupée.") })
            h.director.post(DirectorSignal.CloseChapter(EndReason.OPERATOR))
            h.advance(20_000)
            h.awaitUntil("the chapter is closed") { h.director.status().chapter == null }
            h.fake.enqueue(reply { text("…") })
            h.chat("Bartok", "narrateur, et maintenant ?")
            h.advance(1_500)
            h.awaitTurns(3)

            val second = h.toolEnvs.last()
            assertEquals(2, h.toolEnvs.size)
            assertFalse(second.forgeAvailable)
            assertTrue(second.mechanicsAvailable)
            val names = ToolCatalog.toolSetFor(Role.DIRECTOR, second, h.services).names
            assertTrue(names.none { it in setOf("forge_module", "deploy_module", "stop_module") }, "$names")
            assertFalse("Arsenal" in h.userText(h.fake.requests.size - 1), "no arsenal without the Forge's tools")
            h.assertInvariants()
        }
}
