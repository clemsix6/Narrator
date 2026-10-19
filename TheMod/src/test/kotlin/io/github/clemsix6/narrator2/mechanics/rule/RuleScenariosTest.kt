package io.github.clemsix6.narrator2.mechanics.rule

import io.github.clemsix6.narrator2.director.DirectorHarness
import io.github.clemsix6.narrator2.event.ItemCrafted
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.mechanics.EffectTarget
import io.github.clemsix6.narrator2.mechanics.Engine
import io.github.clemsix6.narrator2.mechanics.EngineActions
import io.github.clemsix6.narrator2.mechanics.MechanicsState
import io.github.clemsix6.narrator2.mechanics.RuleEffect
import io.github.clemsix6.narrator2.mechanics.RuleKind
import io.github.clemsix6.narrator2.mechanics.RuleSpec
import io.github.clemsix6.narrator2.mechanics.RuleState
import io.github.clemsix6.narrator2.mechanics.RuleTrigger
import io.github.clemsix6.narrator2.mechanics.StateStore
import io.github.clemsix6.narrator2.testing.reply
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.TurnRecord
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * Scenario S-M3 (M4 §21.3; task M4-18): a rule's `notify` wakes the Director like any salient event, with the fact
 * and the note in `<events>` and the trigger `mechanic`; it is no priority: past the hour's budget of turns, it waits.
 * The whole Director against the strict fake Anthropic server ([DirectorHarness]), the rule engine in the engine's
 * host on a state in memory, the facts on the harness's bus, in virtual time.
 */
class RuleScenariosTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `a rule's notify wakes the Director without priority`() = DirectorHarness(dir, turnsPerHour = 1).use { h ->
        val bartok = h.online("Bartok")
        h.fake.enqueue(reply { text("…") })
        val now = h.clock.millis()
        val spec = RuleSpec(
            kind = RuleKind.PLAN,
            statement = "Le Narrateur guette les artisans.",
            trigger = RuleTrigger.CRAFT,
            effects = listOf(RuleEffect("notify", EffectTarget.ACTOR, buildJsonObject { put("note", JsonPrimitive("Bartok s'est remis au travail")) })),
            minutes = 120,
        )
        val store = StateStore.inMemory(MechanicsState(rules = listOf(RuleState(61, spec, now, now + 120 * 60_000L, null, null))))
        val actions = EngineActions(h.services, ruleOnlyTools())
        val book = RuleBook(h.services, store, RuleValidator(h.services, actions, FakeRuleIds()))
        val rules = RuleEngine(h.services, store, book, actions, tags = { _, _ -> emptySet() }, clock = h.clock, random = Random(1))
        val engine = Engine(h.services, store, listOf(rules), h.clock)
        engine.begin(now) {}
        try {
            fun craft() {
                h.publish(ItemCrafted(bartok, Pos("minecraft:overworld", 12, -58, 40), "minecraft:oak_planks", 4, "minecraft:crafting"))
                engine.beat(h.clock.millis(), { rules.tick(h.clock.millis()) }, {})
                h.runCurrent()
            }

            craft()
            h.advance(3_000)
            h.awaitTurns(1)
            assertContains(h.wakeText(0), "] RULE C-61 fired: Bartok crafted 4x oak_planks; your note: « Bartok s'est remis au travail »\n")
            assertEquals(TurnRecord.Trigger.MECHANIC, h.endedTurns().single().trigger)
            assertEquals(
                "mechanic RULE C-61",
                h.traces.decisions.single { it.kind == DecisionRecord.Kind.WAKE && it.decision == "woke" }.reason,
            )

            // The hour's only turn is spent: a notify told 5 minutes later waits for a turn to leave the hour.
            h.advance(300_000)
            craft()
            h.advance(240_000)
            assertEquals(1, h.fake.requests.size, "no priority: the notify waits past the budget")
            assertEquals(2, h.traces.decisions.count { it.decision == NotifyTool.NOTIFIED }, "it was told all the same")
            h.assertInvariants()
        } finally {
            engine.stop()
        }
    }
}
