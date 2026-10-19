package io.github.clemsix6.narrator2.mechanics.rule

import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.director.MechanicSignal
import io.github.clemsix6.narrator2.mechanics.MechanicRejected
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.testing.MechanicsSamples
import io.github.clemsix6.narrator2.tool.ToolRunner
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.tool.mechanics.CreateRuleTool
import io.github.clemsix6.narrator2.tool.mechanics.RemoveRuleTool
import io.github.clemsix6.narrator2.trace.DecisionRecord
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The rule book (M4 §8.5, §8.6, §12): canon and state, removal and end, the tools' answers, the rules' own tools. */
class RuleBookTest {
    private val f = RuleFixture()

    @AfterEach
    fun close() = f.close()

    @Test
    fun `create_rule and remove_rule answer as the design says`() = runBlocking<Unit> {
        val runner = ToolRunner(f.services)
        val created = runner.invoke(CreateRuleTool(), f.caller, MechanicsSamples.ruleExamples.getValue("grief_law"))
        assertEquals(
            "ok: rule C-1000 (law) active until 10-08 19:40 (in 7d0h): block_break where grief → punish tiny (2) on the actor, actionbar to the actor; " +
                "announce it if you like",
            created.text,
        )
        val plan = runner.invoke(CreateRuleTool(), f.caller, MechanicsSamples.ruleExamples.getValue("welcome_plan"))
        assertEquals(
            "ok: rule C-1001 (plan) active until 10-02 19:40 (in 1d0h): dimension_change where Bartok, the_nether → spawn 3x zombified_piglin on the actor, " +
                "notify on the actor; it stays secret",
            plan.text,
        )
        val refused = runner.invoke(CreateRuleTool(), f.caller, """{"kind":"law","statement":"Le Narrateur a décrété une loi.","trigger":"chat","effects":[{"tool":"give_item","at":"actor"}],"minutes":60}""")
        assertEquals(ToolStatus.INVALID, refused.status)
        assertTrue(refused.text.startsWith("Invalid arguments: effect 1: give_item cannot be used in a rule"), refused.text)

        f.store.update { state -> state.copy(rules = state.rules.map { if (it.id == 1000L) it.copy(fired = 12, skipped = 3) else it }) }
        assertEquals("ok: rule C-1000 (law) retired (12 fired, 3 skipped)", runner.invoke(RemoveRuleTool(), f.caller, """{"id":"C-1000","note":"Le Narrateur a levé la loi."}""").text)
        assertEquals("ok: rule C-1001 (plan) cancelled (0 fired)", runner.invoke(RemoveRuleTool(), f.caller, """{"id":"c1001","note":"Le Narrateur a renoncé à son plan."}""").text)
        assertEquals(
            "Invalid arguments: C-1000 is not an active rule; active: none",
            runner.invoke(RemoveRuleTool(), f.caller, """{"id":"C-1000","note":"Le Narrateur insiste."}""").text,
        )
        assertEquals("Invalid arguments: O-812 is not a rule; active: none", runner.invoke(RemoveRuleTool(), f.caller, """{"id":"O-812","note":"x"}""").text)
    }

    @Test
    fun `a rule removed leaves the state, then its canon is resolved by the engine`() = runBlocking<Unit> {
        val law = f.book.create(f.spec(MechanicsSamples.ruleExamples.getValue("explosive_diamonds")), f.caller)
        val plan = f.book.create(f.spec(MechanicsSamples.ruleExamples.getValue("welcome_plan")), f.caller)
        val operator = Author(Source.OPERATOR, operator = "Clement")

        val removed = f.book.remove(law.ref, "Le Narrateur a levé la loi des diamants.", operator)
        assertEquals(law.ref, removed.ref)
        assertEquals(listOf(plan.ref.id), f.store.get().rules.map { it.id })
        val canon = assertNotNull(f.base.store.canon(law.ref))
        assertEquals(CanonStatus.RETIRED, canon.status, "a law is retired")
        assertEquals("Le Narrateur a levé la loi des diamants.", canon.resolution)
        f.director.signals.clear()

        f.book.remove(plan.ref, "Le Narrateur a renoncé au comité d'accueil.", MechanicsSamples.director.let { Author(Source.DIRECTOR, turnId = it.turnId, conversationId = it.conversationId) })
        assertEquals(CanonStatus.CANCELLED, f.base.store.canon(plan.ref)!!.status, "a plan is cancelled")
        assertEquals(emptyList(), f.book.list())

        val decisions = f.services.traces.decisions.filter { it.kind == DecisionRecord.Kind.MECHANIC }
        assertEquals(listOf("started", "started", "removed", "removed"), decisions.map { it.decision })
        assertEquals(listOf("C-1000", "C-1001", "C-1000", "C-1001"), decisions.map { it.subject })
        assertEquals("C-1000 law retired by operator Clement: 0 fired; « Le Narrateur a levé la loi des diamants. »", decisions[2].reason)
        assertEquals("C-1001 plan cancelled by the director (turn 42): 0 fired; « Le Narrateur a renoncé au comité d'accueil. »", decisions[3].reason)
        assertEquals("mechanics:C-1001", decisions[3].conversationId)

        assertEquals("C-99 is not an active rule; active: none", assertFailsWith<MechanicRejected> { f.book.remove(Ref(RefKind.CANON, 99), "x", operator) }.message)
    }

    @Test
    fun `the rule engine ends a rule through the book`() = runBlocking<Unit> {
        val plan = f.book.create(f.spec(MechanicsSamples.ruleExamples.getValue("welcome_plan")), f.caller)
        val ended = assertNotNull(f.book.end(plan.ref.id, CanonStatus.FULFILLED, "Le comité d'accueil a reçu Bartok.", "fulfilled"))
        assertEquals(plan.ref, ended.ref)
        assertEquals(CanonStatus.FULFILLED, f.base.store.canon(plan.ref)!!.status)
        assertNull(f.book.end(plan.ref.id, CanonStatus.FULFILLED, "deux fois", "fulfilled"), "a rule ends once")
    }

    @Test
    fun `a rule reads in one line in the situation`() {
        val zone = ZoneOffset.UTC
        val now = MechanicsSamples.evening
        assertEquals(
            "C-60 law block_break grief → punish, actionbar (2 fired, until 10-25 18:15)",
            RuleText.line(MechanicsSamples.griefLaw, now, zone),
        )
        assertEquals(
            "C-61 plan once dimension_change Bartok the_nether → spawn, notify (until 10-20 18:02)",
            RuleText.line(MechanicsSamples.welcomePlan, now, zone),
        )
        assertEquals(
            "C-61 plan once dimension_change Bartok the_nether → spawn, notify (3 failed in a row, until 18:30)",
            RuleText.line(MechanicsSamples.welcomePlan.copy(failures = 3, endsAt = now + 30 * 60_000L), now, zone),
        )
        assertEquals(
            "damage_dealt where cow → mob_effect levitation level 2 on the subject, explosion (power 2) on the subject after 3s, fireworks on the subject after 3s",
            RuleText.summary(MechanicsSamples.rules.getValue("flying_cows")),
        )
    }

    @Test
    fun `notify wakes the Director once in five minutes, and counts the others in the next`() = runBlocking<Unit> {
        val firing = f.actions.open("fired", Ref(RefKind.CANON, 61), "Bartok")
        val args = buildJsonObject {
            put("note", "le comité d'accueil de Bartok est arrivé")
            put("fact", "Bartok entered the_nether (-12 70 40)")
        }
        val start = f.clock.millis()
        val first = f.actions.run(firing, "notify", args)
        assertEquals("ok: you will be woken with: C-61 fired: Bartok entered the_nether (-12 70 40); your note: « le comité d'accueil de Bartok est arrivé »", first.text)
        f.clock.advance(Duration.ofSeconds(30))
        val held = f.actions.run(f.actions.open("fired", Ref(RefKind.CANON, 61), "Bartok"), "notify", args)
        assertEquals("ok: held: you were told about C-61 30s ago; this one is counted in the next, from 19:45:00 (1 held)", held.text)
        f.clock.advance(Duration.ofMinutes(5))
        f.actions.run(f.actions.open("fired", Ref(RefKind.CANON, 61), "Bartok"), "notify", args)

        assertEquals(
            listOf<DirectorSignal>(
                DirectorSignal.Mechanic(
                    MechanicSignal.RULE,
                    "C-61 fired: Bartok entered the_nether (-12 70 40); your note: « le comité d'accueil de Bartok est arrivé »",
                    start,
                    priority = false,
                ),
                DirectorSignal.Mechanic(
                    MechanicSignal.RULE,
                    "C-61 fired: Bartok entered the_nether (-12 70 40); your note: « le comité d'accueil de Bartok est arrivé » (+1 more since 19:40:30)",
                    f.clock.millis(),
                    priority = false,
                ),
            ),
            f.director.signals.toList(),
        )
        val told = f.services.traces.decisions.filter { it.decision == NotifyTool.NOTIFIED }
        assertEquals(listOf("C-61", "C-61"), told.map { it.subject }, "a notified decision per line told, for the Chronicler")
        assertEquals(f.director.signals.map { (it as DirectorSignal.Mechanic).line }, told.map { it.reason })

        val outside = ToolRunner(f.services).invoke(NotifyTool(), f.caller, """{"note":"x"}""")
        assertEquals("Invalid arguments: notify runs only in a rule's act (create_rule)", outside.text)
    }

    @Test
    fun `the creatures' tools check their effect and attribute without the world`() = runBlocking<Unit> {
        suspend fun check(tool: String, json: String) = f.actions.check(tool, Json.parseToJsonElement(json).jsonObject, f.caller)?.text
        assertNull(check("mob_effect", """{"effect":"levitation","seconds":3,"level":2}"""))
        assertEquals(
            "Invalid arguments: at chaos 3 an effect on a creature lasts at most 300 s and goes up to level 2 (asked 600 s, level 1)",
            check("mob_effect", """{"effect":"speed","seconds":600}"""),
        )
        assertEquals("DENIED (forbidden_effect): resistance is never put on a creature by a rule: it makes the creature hard to kill.", check("mob_effect", """{"effect":"resistance","seconds":60}"""))
        assertEquals("DENIED (forbidden_effect): bad_omen is never put on a creature by a rule: it acts on the world.", check("mob_effect", """{"effect":"bad_omen","seconds":60}"""))
        assertTrue(check("mob_effect", """{"effect":"levitaton","seconds":3}""")!!.startsWith("Invalid arguments: unknown effect \"levitaton\"; closest ids: levitation"))
        assertNull(check("mob_attribute", """{"attribute":"movement_speed","multiply":3}"""))
        assertNull(check("mob_attribute", """{"attribute":"max_health","multiply":2}"""))
        assertEquals("Invalid arguments: scale multiplies by 0.25 to 4 (asked 5)", check("mob_attribute", """{"attribute":"scale","multiply":5}"""))
        f.guards.chaos = 0
        assertEquals("DENIED (chaos_0): the chaos level is 0: no rule changes a creature.", check("mob_attribute", """{"attribute":"scale","multiply":2}"""))
    }
}
