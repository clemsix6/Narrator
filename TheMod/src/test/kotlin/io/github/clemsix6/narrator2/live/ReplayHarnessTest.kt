package io.github.clemsix6.narrator2.live

import io.github.clemsix6.narrator2.llm.ClaudeClient
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.testing.FakeAnthropic
import io.github.clemsix6.narrator2.testing.TEST_API_KEY
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.testing.reply
import io.github.clemsix6.narrator2.testing.testConfig
import io.github.clemsix6.narrator2.tool.ToolCatalog
import io.github.clemsix6.narrator2.tool.ToolEffect
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The judge and the replay without any real call (M6-09): the fixtures, the transcripts' reading, the structural checks,
 * the verdict, and the replay and the judge against [FakeAnthropic]. Part of `test`: the live runs are
 * [DirectorReplayEval] and [LiveToneEvalTest].
 */
class ReplayHarnessTest {
    private val tools = ToolCatalog.toolSetFor(Role.DIRECTOR, DirectorReplayEval.directorEnv(storyteller = false), TestServices())

    @Test
    fun `the replay declares the Director's tools of a server where everything is wired`() {
        assertEquals(47, tools.names.size, "${tools.names}")
        assertEquals(48, ToolCatalog.toolSetFor(Role.DIRECTOR, DirectorReplayEval.directorEnv(storyteller = true), TestServices()).names.size)
    }

    @Test
    fun `every situation loads, with known checks on known tools and frozen reads on read tools`() {
        val situations = ReplaySituations.load()
        assertEquals(16, situations.size, situations.map { it.id }.toString())
        assertEquals(situations.size, situations.map { it.id }.toSet().size, "unique ids")
        val reads = tools.tools.filter { it.effect == ToolEffect.READ }.map { it.name }.toSet()
        for (situation in situations) {
            val spec = situation.spec
            assertTrue(spec.title.isNotBlank() && spec.context.isNotBlank() && spec.source.isNotBlank(), situation.id)
            assertTrue(situation.blocks.size == 2 && situation.blocks[0].startsWith("<briefing ") && situation.blocks[0].endsWith("</briefing>"), "${situation.id}: a briefing first")
            assertTrue("<situation " in situation.blocks[1], "${situation.id}: a wake-up with its situation")
            assertTrue(spec.checks.isNotEmpty(), "${situation.id}: at least one check")
            spec.checks.forEach { check ->
                assertTrue(check.name.isNotBlank(), "${situation.id}: a check without a name")
                assertNull(check.problem(), "${situation.id} · ${check.name}")
                assertTrue(check.toolNames().all { it in tools.names }, "${situation.id} · ${check.name}: unknown tool in ${check.toolNames()}")
            }
            assertTrue(spec.reads.keys.all { it in reads }, "${situation.id}: frozen reads only for read tools: ${spec.reads.keys}")
            assertTrue(spec.acts.keys.all { it in tools.names && it !in reads }, "${situation.id}: acts only for acting tools: ${spec.acts.keys}")
        }
    }

    @Test
    fun `an opening read from a transcript excerpt is the exact first message, with its operator's message`() {
        val situations = ReplaySituations.load().associateBy { it.id }
        val welcome = situations.getValue("welcome")
        assertTrue(welcome.blocks[0].startsWith("<briefing chapter=\"1\" now=\"2026-10-19 18:12:21\">"))
        assertTrue(welcome.blocks[1].startsWith("<events now=\"18:12:21\" since=\"18:12:16\">"))
        assertNull(welcome.operator)
        val operator = situations.getValue("operator_request")
        assertTrue(operator.operator!!.startsWith("Operator Opaline: Gaspard vient d'arriver"), operator.operator)
    }

    /** The B = A pass of M6-09 counted a slow-falling gift as hostile: the fixtures now tell a gift from a punishment. */
    @Test
    fun `nothing hostile tells a gift from a punishment`() {
        val check = ReplaySituations.load().single { it.id == "ordinary_death" }.spec.checks.single { it.name == "nothing hostile against Mimosa" }
        fun passes(vararg calls: ReplayCall) = check.passes(turn(*calls))
        assertTrue(passes(ReplayCall(1, "apply_effect", """{"players":["Mimosa"],"effect":"slow_falling","seconds":300}""")))
        assertTrue(passes(ReplayCall(1, "spawn", """{"entity":"chicken","near":"Mimosa","count":3}""")))
        assertTrue(passes(ReplayCall(1, "punish", """{"player":"Bartok","punishment":"tiny"}""")), "against another player")
        assertTrue(!passes(ReplayCall(1, "apply_effect", """{"players":["Mimosa"],"effect":"slowness","seconds":60}""")))
        assertTrue(!passes(ReplayCall(1, "spawn", """{"entity":"zombie","near":"Mimosa","count":1}""")))
        assertTrue(!passes(ReplayCall(1, "punish", """{"player":"Mimosa","punishment":"tiny"}""")))
        assertTrue(!passes(ReplayCall(1, "set_attribute", """{"player":"Mimosa","attribute":"scale","multiply":0.5}""")))
    }

    @Serializable
    private data class Calibration(val lines: List<Line>) {
        @Serializable
        data class Line(val id: String, val expected: String, val text: String, val context: String, val previous: List<String> = emptyList(), val source: String)
    }

    @Test
    fun `the calibration has twenty good real lines and twenty bad ones, each with a known defect`() {
        val text = javaClass.getResourceAsStream("/narrator2/tone/judge-calibration.json")!!.use { it.readBytes().decodeToString() }
        val lines = Json.decodeFromString(Calibration.serializer(), text).lines
        assertEquals(lines.size, lines.map { it.id }.toSet().size)
        assertEquals(20, lines.count { it.expected == "good" })
        val bad = lines.filter { it.expected != "good" }
        assertEquals(20, bad.size)
        assertTrue(bad.all { Defect.of(it.expected) != null }, bad.map { it.expected }.toString())
        assertEquals(Defect.entries.toSet(), bad.map { Defect.of(it.expected) }.toSet(), "every defect is calibrated")
        assertTrue(lines.all { it.text.isNotBlank() && it.context.isNotBlank() && it.source.isNotBlank() })
    }

    @Test
    fun `the structural checks read the order of the calls and the lines`() {
        val turn = turn(
            ReplayCall(1, "inspect_player", """{"player":"Pistache"}"""),
            ReplayCall(2, "punish", """{"player":"Pistache","punishment":"tiny"}"""),
            ReplayCall(3, "say", """{"text":"Pistache passe à mi-taille. Apporte-moi un diamant."}"""),
        )
        fun check(kind: String, tool: String? = null, tools: List<String> = emptyList(), pattern: String? = null, max: Int? = null, of: List<CheckSpec> = emptyList()) =
            CheckSpec("c", kind, tool, tools, pattern, max, of)
        assertTrue(check("speaks").passes(turn))
        assertTrue(check("act_before_announce").passes(turn))
        assertTrue(check("calls", tool = "punish", pattern = "pistache").passes(turn), "patterns ignore the case")
        assertTrue(!check("calls", tool = "punish", pattern = "Bartok").passes(turn))
        assertTrue(!check("no_call", tools = listOf("punish", "spawn"), pattern = "Pistache").passes(turn))
        assertTrue(check("no_call", tools = listOf("give_item")).passes(turn))
        assertTrue(check("at_most_lines", max = 1).passes(turn) && !check("at_most_lines", max = 0).passes(turn))
        assertTrue(!check("no_line_matches", pattern = "apporte").passes(turn))
        assertTrue(check("any_of", of = listOf(check("calls", tool = "favor"), check("speaks"))).passes(turn))
        assertTrue(!check("all_of", of = listOf(check("calls", tool = "favor"), check("speaks"))).passes(turn))
        assertTrue(check("all_of", of = listOf(check("calls", tool = "punish"), check("speaks"))).passes(turn))

        val announcedFirst = turn(ReplayCall(1, "say", """{"text":"Gare à toi !"}"""), ReplayCall(1, "punish", """{"player":"Pistache"}"""))
        assertTrue(!check("act_before_announce").passes(announcedFirst), "a line in the same request as the act did not wait for its result")
        assertTrue(check("act_before_announce", tools = listOf("end_challenge")).passes(announcedFirst), "only the given acts")
        assertTrue(check("act_before_announce").passes(turn(ReplayCall(1, "say", """{"text":"Bonsoir !"}"""))), "no act, nothing to wait for")
        assertEquals("unknown kind nope", check("nope").problem())
        assertEquals("calls needs a tool", check("calls").problem())
    }

    @Test
    fun `B passes when it keeps the checks A passed, the funny and the eliminatory failures`() {
        val situation = situation(CheckSpec("speaks", "speaks"), CheckSpec("no punish", "no_call", tools = listOf("punish")))
        val said = listOf(ReplayCall(1, "say", """{"text":"Bonsoir"}"""))
        fun sample(version: String, n: Int, calls: List<ReplayCall>) =
            ReplaySample("s", version, n, calls, calls.mapNotNull { c -> ReplaySituations.lineOf(c.name, Json.parseToJsonElement(c.input) as kotlinx.serialization.json.JsonObject)?.let { ReplayLine(c.request, c.name, it) } }, 1, TurnEnd.ENDED, null, emptyList())
        fun grade(funny: Int, dark: Boolean = false) = Grade("r", funny, Fairness.OK, dark, false, true, false, Legibility.NOT_APPLICABLE)
        val a = listOf(sample("A", 1, said), sample("A", 2, said))
        val sameB = listOf(sample("B", 1, said), sample("B", 2, said))
        fun grades(samples: List<ReplaySample>, funny: Int, dark: Boolean = false) = samples.associate { ReplayVerdict.lineId(it, 0) to grade(funny, dark) }

        val equal = ReplayVerdict.of(listOf(situation), a + sameB, "A", "B", grades(a, 2) + grades(sameB, 2), planned = 4, problems = emptyList())
        assertTrue(equal.passes, equal.reasons().toString())
        assertTrue(equal.flips.isEmpty())

        val halfSilentB = listOf(sample("B", 1, said), sample("B", 2, emptyList()))
        val weakened = ReplayVerdict.of(listOf(situation), a + halfSilentB, "A", "B", grades(a, 2) + grades(halfSilentB.take(1), 2), 4, emptyList())
        assertTrue(weakened.passes, "a check B passes on one sample of two is noise, reported only: ${weakened.reasons()}")
        assertTrue(weakened.lost.isEmpty() && weakened.tallies.single { it.check == "speaks" }.weakened && weakened.flips.size == 1)

        val silentB = listOf(sample("B", 1, emptyList()), sample("B", 2, emptyList()))
        val lost = ReplayVerdict.of(listOf(situation), a + silentB, "A", "B", grades(a, 2), 4, emptyList())
        assertTrue(!lost.passes && lost.lost.single().check == "speaks", lost.reasons().toString())

        val duller = ReplayVerdict.of(listOf(situation), a + sameB, "A", "B", grades(a, 2) + grades(sameB, 1), 4, emptyList())
        assertTrue(!duller.passes && duller.funnyDrop > ReplayVerdict.FUNNY_TOLERANCE)
        val darker = ReplayVerdict.of(listOf(situation), a + sameB, "A", "B", grades(a, 2) + grades(sameB, 2, dark = true), 4, emptyList())
        assertTrue(!darker.passes && darker.moreEliminatory == 2)
        val short = ReplayVerdict.of(listOf(situation), a + sameB, "A", "B", grades(a, 2) + grades(sameB, 2), 6, listOf("a batch not sent"))
        assertTrue(!short.passes && !short.complete, short.reasons().toString())
    }

    @Test
    fun `a replayed turn serves the frozen reads, answers ok to the acts, and stops at the fourth request`() {
        val situation = situation(
            CheckSpec("speaks", "speaks"),
            reads = mapOf("inspect_player" to listOf("Pistache · survival · hp 20/20", "inventory: 1x diamond")),
            acts = mapOf("punish" to "ok: Pistache punished, tiny (severity 1)"),
        )
        FakeAnthropic().use { fake ->
            fake.enqueue(reply { toolUse("inspect_player", """{"player":"Pistache"}"""); toolUse("sql", """{"query":"SELECT 1"}""") })
            fake.enqueue(reply { toolUse("inspect_player", """{"player":"Pistache"}"""); toolUse("punish", """{"player":"Pistache","punishment":"tiny","severity":1,"reason":"insulte"}""") })
            fake.enqueue(reply { toolUse("say", """{"text":"Pistache passe à mi-taille."}""") })
            fake.enqueue(reply { toolUse("show_title", """{"title":"MI-TAILLE","subtitle":"pour Pistache"}""") })
            val budget = LiveBudget(5.0)
            val sample = ClaudeClient(testConfig(fake.baseUrl), TEST_API_KEY).use { client ->
                ReplayRunner(client, tools, transcripts = null, budget = budget).run(situation, PromptVersion("A", "Tu es le Narrateur.", "test"), 1)
            }
            assertEquals(TurnEnd.REQUEST_CAP, sample.end)
            assertEquals(4, sample.requests)
            assertEquals(4, fake.requests.size, "the tools of the fourth reply are recorded, not answered")
            assertEquals(listOf("inspect_player", "sql", "inspect_player", "punish", "say", "show_title"), sample.calls.map { it.name })
            assertEquals(listOf("Pistache passe à mi-taille.", "MI-TAILLE / pour Pistache"), sample.lines.map { it.text })
            assertEquals(listOf(3, 4), sample.lines.map { it.request })
            fun results(request: Int) = fake.requests[request].json.path("messages").last().path("content").map { it.path("content").asText() }
            assertEquals(listOf("Pistache · survival · hp 20/20\ninventory: 1x diamond", ReplayRunner.NOTHING_MORE), results(1), "a read gets its frozen result once")
            assertEquals(listOf(ReplayRunner.NOTHING_MORE, "ok: Pistache punished, tiny (severity 1)"), results(2))
            assertEquals(listOf("ok"), results(3))
            assertEquals("narrator2-director", fake.requests[0].session)
            assertEquals(47, fake.requests[0].json.path("tools").size())
            assertTrue(budget.spent > 0.0 && sample.dollars == budget.spent)
            assertEquals(0, fake.rejections.size, fake.rejections.toString())
        }
    }

    @Test
    fun `a replayed turn ends with the Director's turn, and sends the operator's message with the opening`() {
        val situation = situation(CheckSpec("speaks", "speaks"), operator = "Operator Opaline: accueille Gaspard.")
        FakeAnthropic().use { fake ->
            fake.enqueue(reply { toolUse("say", """{"text":"Bienvenue, Gaspard."}""") })
            fake.enqueue(reply { text("Fait.") })
            val sample = ClaudeClient(testConfig(fake.baseUrl), TEST_API_KEY).use { client ->
                ReplayRunner(client, tools, null, LiveBudget(5.0)).run(situation, PromptVersion("B", "Tu es le Narrateur.", "test"), 2)
            }
            assertEquals(TurnEnd.ENDED, sample.end)
            assertEquals(2, sample.requests)
            val first = fake.requests[0].json.path("messages")
            assertEquals(listOf("user", "system"), first.map { it.path("role").asText() })
            assertEquals("Operator Opaline: accueille Gaspard.", first[1].path("content").asText())
            assertEquals(listOf("<briefing chapter=\"9\">\n</briefing>", "<events>\n</events>\n<situation now=\"21:00:00\">\n</situation>"), first[0].path("content").map { it.path("text").asText() })
        }
    }

    @Test
    fun `the budget stops a turn before a request that could cross it`() {
        FakeAnthropic().use { fake ->
            val sample = ClaudeClient(testConfig(fake.baseUrl), TEST_API_KEY).use { client ->
                ReplayRunner(client, tools, null, LiveBudget(0.1)).run(situation(CheckSpec("speaks", "speaks")), PromptVersion("A", "x", "test"), 1)
            }
            assertEquals(TurnEnd.BUDGET, sample.end)
            assertEquals(0, sample.requests)
            assertEquals(0, fake.requests.size)
        }
    }

    @Test
    fun `the judge asks for structured output and reads one grade per line`() {
        FakeAnthropic().use { fake ->
            fake.enqueue(
                reply {
                    text(
                        """{"grades":[{"id":2,"reason":"report","funny":0,"fair":"ok","dark":false,"cruel":false,"voice":true,"repeats":false,"legible":"n_a"},""" +
                            """{"id":1,"reason":"a good punchline","funny":3,"fair":"ok","dark":false,"cruel":false,"voice":true,"repeats":false,"legible":"yes"}]}""",
                    )
                    usage(input = 1_000, output = 200)
                },
            )
            val lines = listOf(JudgedLine("a", "Bonsoir, mortels !", "JOIN", listOf("x", "y")), JudgedLine("b", "Effet appliqué.", "CHAT"))
            val budget = LiveBudget(1.0)
            val judged = ToneJudge(fake.baseUrl, TEST_API_KEY, budget).use { it.grade(lines) }
            assertEquals(emptyList(), judged.problems)
            assertEquals(3, judged.grades.getValue("a").funny)
            assertEquals(setOf(Defect.FLAT), judged.grades.getValue("b").defects)
            val request = fake.requests.single()
            assertEquals("json_schema", request.json.path("output_config").path("format").path("type").asText())
            assertEquals("low", request.json.path("output_config").path("effort").asText())
            assertEquals("adaptive", request.json.path("thinking").path("type").asText())
            assertEquals("claude-opus-5-5", request.json.path("model").asText())
            assertEquals(ToneJudge.SESSION, request.session)
            assertTrue(request.json.path("tools").isMissingNode && request.json.path("tool_choice").isMissingNode, "no tool: structured output")
            assertTrue("<text>Bonsoir, mortels !</text>" in request.json.path("messages")[0].path("content").asText())
            assertTrue(budget.spent > 0.0)
        }
    }

    @Test
    fun `the judge's answer is checked line by line`() {
        val parsed = ToneJudge.parse(
            """{"grades":[{"id":1,"reason":"r","funny":4,"fair":"ok","dark":false,"cruel":false,"voice":true,"repeats":false,"legible":"n_a"},""" +
                """{"id":3,"reason":"r","funny":1,"fair":"ok","dark":false,"cruel":false,"voice":true,"repeats":false,"legible":"n_a"},""" +
                """{"id":2,"reason":"r","funny":1,"fair":"meh","dark":false,"cruel":false,"voice":true,"repeats":false,"legible":"n_a"}]}""",
            count = 2,
        )
        assertTrue(parsed.grades.isEmpty())
        assertEquals(3, parsed.problems.size, parsed.problems.toString())
        assertEquals(1, ToneJudge.parse("no json", 1).problems.size)
    }

    @Test
    fun `the lines of an evening are read from its transcripts with their situation`() {
        val dir = Files.createTempDirectory("m6-09-transcripts")
        val lines = listOf(
            """{"ts":"2026-10-19T18:12:21+02:00","kind":"note","data":{"text":"turn 1 · trigger chat · chat Lisette"}}""",
            """{"ts":"2026-10-19T18:12:21+02:00","kind":"sent","data":{"content":[{"text":"<briefing>\n</briefing>"},{"text":"<events now=\"18:12:21\" since=\"18:12:16\">\n[-5s] JOIN Lisette\n[-3s] CHAT Lisette (to you 0.90): \"Bonsoir\"\n</events>\n<situation>\n</situation>"}],"role":"user"}}""",
            """{"ts":"2026-10-19T18:12:22+02:00","kind":"request","data":{}}""",
            """{"ts":"2026-10-19T18:12:27+02:00","kind":"response","data":{"content":[{"type":"thinking","thinking":"…"},{"type":"tool_use","id":"t1","name":"say","input":{"text":"Bonsoir, Lisette."}},{"type":"tool_use","id":"t2","name":"show_title","input":{"title":"Soirée","subtitle":"mémorable"}},{"type":"tool_use","id":"t3","name":"play_sound","input":{"sound":"x"}}]}}""",
            """{"ts":"2026-10-19T18:13:00+02:00","kind":"note","data":{"text":"turn 2 · trigger operator · operator + 1 item"}}""",
            """{"ts":"2026-10-19T18:13:00+02:00","kind":"sent","data":{"content":"Operator Opaline: lance un défi.","role":"system"}}""",
            """{"ts":"2026-10-19T18:13:05+02:00","kind":"response","data":{"content":[{"type":"tool_use","id":"t4","name":"actionbar","input":{"text":"Défi !","to":["Lisette"]}}]}}""",
        )
        Files.writeString(dir.resolve("director-abc.jsonl"), lines.joinToString("\n"))
        Files.writeString(dir.resolve("chronicler-abc.jsonl"), lines.joinToString("\n"))
        val spoken = ReplaySituations.spokenLines(dir)
        assertEquals(listOf("Bonsoir, Lisette.", "Soirée / mémorable", "Défi !"), spoken.map { it.text })
        assertEquals("[-3s] CHAT Lisette (to you 0.90): \"Bonsoir\"", spoken[0].context)
        assertEquals("Operator Opaline: lance un défi.", spoken[2].context)
        assertEquals("turn 2 · trigger operator · operator + 1 item", spoken[2].turn)

        val opening = ReplaySituations.opening(lines)
        assertEquals(2, opening.blocks.size)
        assertNull(opening.operator, "the operator's message of a later turn is not the opening's")
    }

    private fun turn(vararg calls: ReplayCall): ReplayTurn = object : ReplayTurn {
        override val calls = calls.toList()
        override val lines = calls.mapNotNull { call ->
            ReplaySituations.lineOf(call.name, Json.parseToJsonElement(call.input) as kotlinx.serialization.json.JsonObject)?.let { ReplayLine(call.request, call.name, it) }
        }
    }

    private fun situation(
        vararg checks: CheckSpec,
        reads: Map<String, List<String>> = emptyMap(),
        acts: Map<String, String> = emptyMap(),
        operator: String? = null,
    ): ReplaySituation {
        val spec = SituationSpec(
            id = "s", title = "t", source = "test", context = "c",
            opening = listOf(listOf("<briefing chapter=\"9\">", "</briefing>"), listOf("<events>", "</events>", "<situation now=\"21:00:00\">", "</situation>")),
            operator = operator, reads = reads, acts = acts, checks = checks.toList(),
        )
        return ReplaySituation(spec, spec.opening.map { it.joinToString("\n") }, operator)
    }
}
