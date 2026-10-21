package io.github.clemsix6.narrator2.director

import io.github.clemsix6.narrator2.event.BlockBroken
import io.github.clemsix6.narrator2.event.PlayerJoined
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.journal.JournalFixture
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.memory.Idea
import io.github.clemsix6.narrator2.memory.IdeaKind
import io.github.clemsix6.narrator2.memory.IdeaStatus
import io.github.clemsix6.narrator2.memory.RecallChannel
import io.github.clemsix6.narrator2.memory.RecallContext
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.memory.update
import io.github.clemsix6.narrator2.reflex.HeartbeatVerdict
import io.github.clemsix6.narrator2.testing.FakeResponse
import io.github.clemsix6.narrator2.testing.httpError
import io.github.clemsix6.narrator2.testing.reply
import io.github.clemsix6.narrator2.tool.ToolCatalog
import io.github.clemsix6.narrator2.tool.mechanics.StartChallengeTool
import io.github.clemsix6.narrator2.tool.storyteller.InspireTool
import io.github.clemsix6.narrator2.tool.task.DelegateTool
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.TaskRecord
import io.github.clemsix6.narrator2.trace.TurnRecord
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The storyteller seen from the Director (M6 §15.3, M6-08), in virtual time against the strict fake Anthropic server:
 * S-S1 the storyteller on demand (`delegate`), with the real workers and its real tools; S-S2 the idea a heartbeat
 * carries; S-S3 two draws of `inspire` in a row; S-S4 a Director without the memory.
 */
class DirectorStorytellerScenariosTest {
    @TempDir
    lateinit var dir: Path

    private val bartok = DirectorHarness.ref("Bartok")
    private val mimosa = DirectorHarness.ref("Mimosa")
    private val kelvin = DirectorHarness.ref("Kelvin")
    private val here = Pos("minecraft:overworld", 1, 64, 3)

    /** [players] have played here (the `players` table): the storyteller's tools find them by name. */
    private fun JournalFixture.known(vararg players: PlayerRef) = runBlocking {
        journal.write(5_000) { connection ->
            players.forEach {
                connection.update(
                    "INSERT INTO players (player_uuid, name, first_seen, last_seen, sessions, playtime_s, is_op) VALUES (?, ?, ?, ?, 3, 36000, false)",
                    it.uuid, it.name, DirectorHarness.START.minus(Duration.ofDays(20)), DirectorHarness.START.minus(Duration.ofDays(7)),
                )
            }
        }
    }

    /** An open idea of the storyteller, P-[id], never dealt, far from its deadline. */
    private fun idea(id: Long, kind: IdeaKind, title: String, pitch: String, fit: String, players: List<PlayerRef>, notes: Int) = Idea(
        Ref(RefKind.IDEA, id), DirectorHarness.START.minus(Duration.ofHours(10)), kind, title, pitch, fit, players, null, notes / 100, notes / 10 % 10,
        notes % 10, emptyList(), null, null, IdeaStatus.OPEN, 0, null, 100_000, null, null, Source.STORYTELLER,
    )

    private fun DirectorHarness.idea(id: Long): Idea = memory.store.rows.getValue(Ref(RefKind.IDEA, id)) as Idea

    /** The tool results of the last `user` message of request [request], in order. */
    private fun DirectorHarness.toolResults(request: Int): List<String> =
        lastUser(request).path("content").filter { it.path("type").asText() == "tool_result" }.map { it.path("content").asText() }

    /** Some activity, and reflexes that find it worth a heartbeat. */
    private fun DirectorHarness.busy() {
        reflexes.heartbeat = { HeartbeatVerdict(worth = 0.8, hook = "idle", source = "fake", latencyMs = 0) }
        repeat(3) { publish(BlockBroken(bartok, here, "minecraft:stone", null, false, true, null)) }
    }

    // ── S-S1 ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `S-S1 a player back after a week - the storyteller writes three ideas, its ranked report wakes the Director, who starts one`() {
        JournalFixture().use { fixture ->
            fixture.known(bartok, mimosa)
            DirectorHarness(dir, journal = fixture.journal, realWorkers = true, tools = listOf(DelegateTool(), InspireTool(), StartChallengeTool())).use { h ->
                h.online("Mimosa")
                h.online("Bartok")
                h.memory.attach = { signals ->
                    val join = signals.any { (it as? DirectorSignal.Event)?.envelope?.event is PlayerJoined }
                    if (join) "<memory>\nSHEET Bartok (joined; away 7d 02h, last session 09-18 20:10–21:52):\n  Recap: explorateur tête baissée.\n</memory>" else null
                }

                // Each conversation has its own script: the Director's and the storyteller's requests interleave.
                val directorScript = ConcurrentLinkedQueue<FakeResponse>()
                val storytellerScript = ConcurrentLinkedQueue<FakeResponse>()
                repeat(10) {
                    h.fake.enqueue { request ->
                        val script = if (request.conversation.orEmpty().startsWith("storyteller-")) storytellerScript else directorScript
                        script.poll() ?: httpError(400, message = "unscripted request of ${request.conversation}")
                    }
                }
                val brief = "Bartok revient après une semaine d'absence, Mimosa est en ligne : trois idées pour fêter son retour ce soir, sans le punir d'emblée."
                directorScript += reply { thinking("Un retour mérite mieux qu'une pique."); toolUse("delegate", """{"role": "storyteller", "brief": "$brief"}""") }
                directorScript += reply { text("Bon retour parmi les mortels, Bartok.") }
                storytellerScript += reply { thinking("Ce qui sert ce retour."); toolUse("inspire", """{"player": "Bartok"}""") }
                storytellerScript += reply {
                    toolUse(
                        "propose_idea",
                        """{"kind": "challenge", "title": "La pêche du revenant", "pitch": "Bartok et Mimosa doivent rapporter un œuf de tortue en quinze minutes ; le premier gagne une pomme dorée.",
                           "fit": "Bartok et Mimosa en ligne, juste après son retour", "players": ["Bartok", "Mimosa"], "register": "sports_commentary",
                           "novelty": 4, "fun": 4, "fairness": 4, "hours": 6}""",
                    )
                    toolUse(
                        "propose_idea",
                        """{"kind": "favor", "title": "Le registre des absents", "pitch": "Bartok reçoit un livre où sont consignées toutes les choses qu'il a manquées pendant sa semaine.",
                           "fit": "Bartok en ligne", "players": ["Bartok"], "register": "classified_ads", "novelty": 3, "fun": 4, "fairness": 5, "hours": 6}""",
                    )
                    toolUse(
                        "propose_idea",
                        """{"kind": "speech", "title": "La prophétie du retour", "pitch": "Une prophétie annonce que le revenant trouvera ce qu'il a perdu dans le Nether.",
                           "fit": "n'importe quand ce soir", "novelty": 3, "fun": 3, "fairness": 4, "hours": 6}""",
                    )
                }
                val report = "1. P-1 challenge « La pêche du revenant » : un œuf de tortue en quinze minutes ; 4/4/4.\n" +
                    "2. P-2 favor « Le registre des absents » : un livre de ce qu'il a manqué ; 3/4/5.\n3. P-3 speech « La prophétie du retour » ; 3/3/4."
                storytellerScript += reply { text(report) }
                directorScript += reply {
                    toolUse(
                        "start_challenge",
                        """{"title": "La pêche du revenant", "statement": "Le Narrateur a défié Bartok et Mimosa de rapporter un œuf de tortue en quinze minutes.",
                           "goal": "obtain", "item": "turtle_egg", "players": ["Bartok", "Mimosa"], "minutes": 15,
                           "reward": {"kind": "item", "item": "golden_apple"}, "penalty": {"punishment": "tiny", "severity": 2}}""",
                    )
                }
                directorScript += reply { text("Que la pêche commence.") }

                h.publish(PlayerJoined(bartok, here, 1, firstTime = false, isOp = false))
                h.advance(3_000)
                h.awaitTurns(1)

                // The Director delegated and went on: the storyteller is a worker like the others.
                assertTrue(h.toolEnvs.single().storytellerAvailable, "the storyteller can work: inspire is in the chapter")
                assertTrue(Role.STORYTELLER in h.toolEnvs.single().workerRoles)
                val directorSession = h.sessionOf(0)
                val directorRequests = { h.fake.requests.filter { it.conversation == directorSession } }
                assertEquals("ok: task T-1 storyteller started; its report will arrive as a TASK event", h.toolResults(h.fake.requests.indexOf(directorRequests()[1])).single())

                // The storyteller: its own conversation, prompt and tools; a draw, three ideas written in its name, a ranked report.
                h.awaitUntil("the storyteller's report") { h.traces.latestTask(1)?.status == TaskRecord.Status.DONE }
                val session = h.taskWorkers!!.list().single().conversationId!!
                assertTrue(session.startsWith("storyteller-"), session)
                val requests = h.fake.requests.filter { it.conversation == session }
                assertEquals(3, requests.size)
                assertEquals(
                    setOf("propose_idea", "drop_idea", "inspire", "remember", "resolve", "recall", "player_profile"),
                    requests[0].json.path("tools").map { it.path("name").asText() }.toSet(),
                )
                assertContains(requests[0].json.path("system")[0].path("text").asText(), "Tu es la salle d'écriture du Narrateur")
                val opening = requests[0].json.path("messages")[0].path("content")[0].path("text").asText()
                assertTrue(opening.startsWith("<task id=\"T-1\" from=\"director\">\n$brief\n</task>\n<context now=\"2026-09-25 21:32:0"), opening)
                val draw = requests[1].json.path("messages").last().path("content")[0].path("content").asText()
                assertTrue(draw.startsWith("ok: ") && "for Bartok" in draw.lineSequence().first(), draw)
                val written = requests[2].json.path("messages").last().path("content").map { it.path("content").asText() }
                assertEquals(3, written.size)
                assertTrue(written.all { it.startsWith("ok: P-") }, "$written")
                val ideas = runBlocking { h.memory.store.ideas(null, 10) }
                assertEquals(setOf("La pêche du revenant", "Le registre des absents", "La prophétie du retour"), ideas.map { it.title }.toSet())
                assertTrue(ideas.all { it.source == Source.STORYTELLER && it.status == IdeaStatus.OPEN && it.expiresPlaySeconds == 6 * 3_600L }, "$ideas")
                assertEquals(listOf(bartok.uuid, mimosa.uuid), ideas.single { it.kind == IdeaKind.CHALLENGE }.players.map { it.uuid })
                assertTrue(h.memory.store.recalls.isEmpty(), "the storyteller's draw deals nothing to the Director")
                val inspired = h.traces.decisions.single { it.kind == DecisionRecord.Kind.STYLE && it.decision == "inspired" }
                assertEquals("storyteller", inspired.detail!!["role"]!!.jsonPrimitive.content)
                assertEquals(report, h.traces.latestTask(1)!!.result)
                assertEquals(Role.STORYTELLER, h.traces.latestConversation(session)!!.role)

                // What the storyteller's conversation costs: printed for the hand-off (M6-08).
                requests.forEachIndexed { i, request ->
                    println(
                        "storyteller request ${i + 1}: system ${request.json.path("system").toString().length} characters, " +
                            "tools ${request.json.path("tools").toString().length}, messages ${request.json.path("messages").toString().length}",
                    )
                }

                // The report wakes the Director with a TASK event; it starts the first idea.
                h.advance(3_000)
                h.awaitTurns(2)
                val wake = h.wakeText(h.fake.requests.indexOf(directorRequests()[2]))
                assertContains(wake, "] TASK T-1 storyteller done in ")
                assertContains(wake, "\n    1. P-1 challenge « La pêche du revenant » : un œuf de tortue en quinze minutes ; 4/4/4.\n    2. P-2 favor")
                assertEquals(listOf(TurnRecord.Trigger.SALIENT, TurnRecord.Trigger.TASK), h.endedTurns().map { it.trigger })
                val (spec, caller) = h.mechanics.challenges.started.single()
                assertEquals(listOf("La pêche du revenant", "turtle_egg"), listOf(spec.title, spec.item))
                assertEquals(h.endedTurns()[1].turnId, caller.turnId)
                assertTrue(h.toolResults(h.fake.requests.indexOf(directorRequests()[3])).single().startsWith("ok: challenge C-1 started: obtain 1 turtle_egg"))
                assertEquals(0, h.fake.unscripted)
                h.assertInvariants()
            }
        }
    }

    // ── S-S2 ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `S-S2 a heartbeat carries one idea, the Director uses it, and the next heartbeat carries none within two hours`() =
        DirectorHarness(dir, tools = listOf(StartChallengeTool())).use { h ->
            h.online("Bartok")
            h.online("Mimosa")
            h.memory.clock.world = 1_000
            listOf(
                idea(40, IdeaKind.SEASON, "Les Grandes Manœuvres", "Une saison de manœuvres.", "toujours", emptyList(), 555),
                idea(
                    41, IdeaKind.CHALLENGE, "La course au pic", "Course au sommet de la Dent du Juge ; le premier en haut gagne un joker.",
                    "les deux en ligne, en début de soirée", listOf(bartok, mimosa), 445,
                ),
                idea(42, IdeaKind.FAVOR, "Le diplôme", "Mimosa reçoit un livre-diplôme d'architecte.", "Mimosa en ligne", listOf(mimosa), 344),
                idea(43, IdeaKind.PUNISHMENT, "Le bain forcé", "Une bouée de laine bleue pour Kelvin.", "Kelvin en ligne", listOf(kelvin), 555),
                idea(44, IdeaKind.TWIST, "Le pari inversé", "Le perdant du prochain pari devient l'arbitre.", "Bartok en ligne", listOf(bartok), 555)
                    .copy(dealt = 1, lastDealtAt = DirectorHarness.START.minus(Duration.ofMinutes(30))),
                idea(45, IdeaKind.FAVOR, "Le gâteau échu", "Un gâteau.", "Bartok en ligne", listOf(bartok), 555).copy(expiresPlaySeconds = 500),
            ).forEach { h.memory.store.rows[it.ref] = it }

            // The first heartbeat: the best idea that fits, neither the season, nor Kelvin's (offline), nor P-44 (dealt 30 minutes ago), nor P-45 (due).
            h.busy()
            h.fake.enqueue(
                reply {
                    toolUse(
                        "start_challenge",
                        """{"title": "La course au pic", "statement": "Le Narrateur a défié Bartok et Mimosa de rapporter un œuf de tortue en quinze minutes.",
                           "goal": "obtain", "item": "turtle_egg", "players": ["Bartok", "Mimosa"], "minutes": 15,
                           "reward": {"kind": "item", "item": "golden_apple"}, "penalty": {"punishment": "tiny", "severity": 2}}""",
                    )
                },
            )
            h.fake.enqueue(reply { text("Défi lancé.") })
            h.advance(180_000)
            h.awaitTurns(1)

            val first = h.endedTurns().single()
            assertEquals(TurnRecord.Trigger.HEARTBEAT, first.trigger)
            assertContains(
                h.wakeText(0),
                "\nIdea: P-41 challenge (Bartok, Mimosa) « La course au pic »: les deux en ligne, en début de soirée — Course au sommet de la Dent du Juge ; " +
                    "le premier en haut gagne un joker.\n</heartbeat>\n",
            )
            assertEquals(1, Regex("^Idea: ", RegexOption.MULTILINE).findAll(h.wakeText(0)).count(), "one idea at most")
            val woke = h.traces.decisions.single { it.kind == DecisionRecord.Kind.HEARTBEAT && it.decision == "woke" }
            assertTrue(woke.reason!!.endsWith(", hook idle, idea P-41 challenge"), woke.reason)
            assertEquals(first.turnId, h.mechanics.challenges.started.single().second.turnId, "the Director used it")

            // Dealt once the request that carried it was accepted: its count, its time, a recalls row on the heartbeat's channel.
            h.awaitUntil("P-41 dealt") { h.idea(41).dealt == 1 }
            assertEquals(listOf(Triple(Ref(RefKind.IDEA, 41), RecallChannel.HEARTBEAT, RecallContext(h.sessionOf(0), first.turnId, null))), h.memory.store.recalls)
            assertEquals(0, h.idea(42).dealt)

            // The next heartbeat carries none, although P-42 fits and was never dealt.
            h.busy()
            h.fake.enqueue(reply { text("…") })
            h.advance(180_000)
            h.awaitTurns(2)
            assertEquals(TurnRecord.Trigger.HEARTBEAT, h.endedTurns()[1].trigger)
            assertContains(h.wakeText(2), "<heartbeat ")
            assertFalse("Idea:" in h.wakeText(2), h.wakeText(2))
            assertEquals(1, h.memory.store.recalls.size)

            // Two hours later, the heartbeat carries one again: P-44, whose own rest is over and which is noted best.
            h.advance(2 * 3_600_000L)
            h.busy()
            h.fake.enqueue(reply { text("…") })
            h.advance(180_000)
            h.awaitTurns(3)
            assertContains(h.wakeText(3), "\nIdea: P-44 twist (Bartok) « Le pari inversé »: Bartok en ligne — Le perdant du prochain pari devient l'arbitre.\n")
            h.awaitUntil("P-44 dealt") { h.idea(44).dealt == 2 }
            assertEquals(3, h.endedTurns().size)
            h.assertInvariants()
        }

    // ── S-S3 ────────────────────────────────────────────────────────────────────────────────────────

    /**
     * S-S3: `inspire` twice in a row deals no idea and no seed twice. The harness keeps its traces in memory; what
     * production's trace writes to the Journal (the `style inspired` decision a draw reads back) is copied there.
     */
    @Test
    fun `S-S3 two draws of inspire in a row repeat no idea and no seed`() {
        JournalFixture().use { fixture ->
            fixture.known(bartok, mimosa)
            DirectorHarness(dir, journal = fixture.journal, realWorkers = true, tools = listOf(InspireTool())).use { h ->
                h.online("Bartok")
                h.online("Mimosa")
                h.memory.clock.world = 1_000
                listOf(
                    idea(41, IdeaKind.CHALLENGE, "La course au pic", "Course au sommet de la Dent du Juge.", "les deux en ligne", listOf(bartok, mimosa), 445),
                    idea(42, IdeaKind.FAVOR, "Le diplôme", "Mimosa reçoit un livre-diplôme d'architecte.", "Mimosa en ligne", listOf(mimosa), 344),
                    idea(43, IdeaKind.TWIST, "Le pari inversé", "Le perdant du prochain pari devient l'arbitre.", "Bartok en ligne", listOf(bartok), 333),
                ).forEach { h.memory.store.rows[it.ref] = it }
                assertTrue(h.toolEnvs.isEmpty())

                h.fake.enqueue(reply { toolUse("inspire", "{}") })
                h.fake.enqueue(reply { text("…") })
                h.chat("Bartok", "narrateur, on s'ennuie, invente quelque chose")
                h.advance(1_500)
                h.awaitTurns(1)
                assertTrue(h.toolEnvs.single().storytellerAvailable)
                h.traces.decisions.filter { it.kind == DecisionRecord.Kind.STYLE }.forEach(fixture.journal::decision)
                fixture.sync()

                h.fake.enqueue(reply { toolUse("inspire", "{}") })
                h.fake.enqueue(reply { text("…") })
                h.chat("Mimosa", "narrateur, encore une idée ?")
                h.advance(1_500)
                h.awaitTurns(2)

                val draws = h.traces.decisions.filter { it.kind == DecisionRecord.Kind.STYLE && it.decision == "inspired" }
                    .map { decision -> decision.detail!!["keys"]!!.jsonArray.map { it.jsonPrimitive.content } }
                println("── the two draws ──\n${h.toolResults(1).single()}\n${h.toolResults(3).single()}")
                assertEquals(2, draws.size)
                assertEquals(listOf("P-41", "P-42"), draws[0].take(2), "the programme first, the best noted first")
                assertEquals(emptySet(), draws[0].toSet() intersect draws[1].toSet(), "nothing dealt twice: ${draws[0]} then ${draws[1]}")
                assertEquals("P-43", draws[1].first(), "the idea left over")
                assertEquals(listOf(1, 1, 1), listOf(41L, 42L, 43L).map { h.idea(it).dealt })
                assertTrue(h.memory.store.recalls.filter { it.first.kind == RefKind.IDEA }.all { it.second == RecallChannel.INSPIRE })
                h.assertInvariants()
            }
        }
    }

    // ── S-S4 ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `S-S4 without the memory there is no inspire in the chapter, and delegate refuses the storyteller with its reason`() {
        JournalFixture().use { fixture ->
            DirectorHarness(dir, memoryOff = "memory.enabled is false", journal = fixture.journal, realWorkers = true, tools = listOf(DelegateTool())).use { h ->
                h.online("Bartok")
                h.fake.enqueue(reply { toolUse("delegate", """{"role": "storyteller", "brief": "Trois idées pour une soirée creuse avec Bartok."}""") })
                h.fake.enqueue(reply { text("…") })
                h.chat("Bartok", "narrateur, il se passe rien ce soir")
                h.advance(1_500)
                h.awaitTurns(1)

                val env = h.toolEnvs.single()
                assertFalse(env.storytellerAvailable, "the storyteller cannot work without the memory")
                assertFalse(Role.STORYTELLER in env.workerRoles)
                val tools = ToolCatalog.toolSetFor(Role.DIRECTOR, env, h.services).names
                assertFalse("inspire" in tools, "$tools")
                assertTrue("delegate" in tools, "the Analyst and the scout still take tasks")
                val delegate = h.fake.requests[0].json.path("tools").single { it.path("name").asText() == "delegate" }
                assertEquals(listOf("analyst", "scout", "storyteller"), delegate.path("input_schema").path("properties").path("role").path("enum").map { it.asText() }, "the role stays in the enum")
                assertEquals(listOf("FAILED: no storyteller can take a task: the memory is unavailable"), h.toolResults(1))
                assertTrue(h.taskWorkers!!.list().isEmpty())
                h.assertInvariants()
            }
        }
    }
}
