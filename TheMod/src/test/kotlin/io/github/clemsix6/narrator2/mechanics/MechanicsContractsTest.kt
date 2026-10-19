package io.github.clemsix6.narrator2.mechanics

import io.github.clemsix6.narrator2.config.UiTexts
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.testing.MechanicsSamples
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolArgs
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolSchema
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.tool.action.FavorArgs
import io.github.clemsix6.narrator2.tool.action.PunishArgs
import io.github.clemsix6.narrator2.tool.mechanics.EndChallengeArgs
import io.github.clemsix6.narrator2.tool.mechanics.RemoveRuleArgs
import io.github.clemsix6.narrator2.tool.oracle.SurveyArgs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The contracts of M4 that the tasks share (M4 §4.1, §4.3, §5.1, §8.1, §12). */
class MechanicsContractsTest {
    @Test
    fun `an act of the engine is negative, so a firing never passes for the turn of the same number`() {
        val turn = Caller(Role.DIRECTOR, "director-c13", turnId = 812, taskId = null)
        val firing = Caller(Role.DIRECTOR, "mechanics:C-60", turnId = null, taskId = null, firingId = 812)
        assertEquals(812L, turn.act)
        assertEquals(-812L, firing.act)
        assertTrue(turn.act != firing.act)
        assertNull(Caller(Role.ANALYST, "analyst-t1", turnId = null, taskId = 7).act, "a worker's task has no act")
        assertEquals(-5L, MechanicsSamples.firing(5, MechanicsSamples.canon(60)).caller.act)
    }

    @Test
    fun `the disabled engine refuses every mechanic with its reason, and does nothing else`() = runBlocking<Unit> {
        val off = DisabledMechanics("mechanics.enabled is false")
        assertFalse(off.available)
        assertEquals("mechanics.enabled is false", off.unavailableReason)
        val operator = Author(Source.OPERATOR, operator = "Clement")
        val refusals = listOf(
            assertFailsWith<MechanicRejected> { off.challenges.start(MechanicsSamples.turtleEgg, MechanicsSamples.director) },
            assertFailsWith<MechanicRejected> { off.challenges.proposeDuel(MechanicsSamples.duel, MechanicsSamples.director) },
            assertFailsWith<MechanicRejected> { off.challenges.end(MechanicsSamples.canon(50), ChallengeOutcome.CANCELLED, emptyList(), "annulé", operator) },
            assertFailsWith<MechanicRejected> { off.rules.create(MechanicsSamples.rules.getValue("grief_law"), MechanicsSamples.director) },
            assertFailsWith<MechanicRejected> { off.rules.remove(MechanicsSamples.canon(60), "retirée", operator) },
        )
        refusals.forEach {
            assertEquals(DisabledMechanics.MECHANICS_OFF, it.rule)
            assertEquals(ToolResult.denied("mechanics_off", "the mechanics are unavailable: mechanics.enabled is false"), it.toResult())
        }
        assertEquals(emptyList(), off.challenges.list())
        assertEquals(emptyList(), off.rules.list())
        assertEquals(emptyList(), off.situationLines(0))
    }

    @Test
    fun `a refusal without a rule is an invalid call, with a rule a denial`() {
        assertEquals(ToolStatus.INVALID, MechanicRejected(null, "goal obtain needs item").toResult().status)
        assertEquals("Invalid arguments: goal obtain needs item", MechanicRejected(null, "goal obtain needs item").toResult().text)
        assertEquals("DENIED (rule_limit): 8 rules are active", MechanicRejected("rule_limit", "8 rules are active").toResult().text)
    }

    @Test
    fun `the players' commands get their text when the engine is off`() {
        val ui = UiTexts.load("fr")
        assertTrue(ui["mechanics.unavailable"].isNotBlank())
    }

    @Test
    fun `the specifications are the tools' arguments - every sample decodes as the model writes it`() {
        assertEquals(7, MechanicsSamples.rules.size)
        val law = MechanicsSamples.rules.getValue("grief_law")
        assertEquals(RuleKind.LAW, law.kind)
        assertEquals(RuleTrigger.BLOCK_BREAK, law.trigger)
        assertEquals(true, law.conditions.grief)
        assertEquals(listOf("punish", "actionbar"), law.effects.map { it.tool })
        assertEquals(EffectTarget.ACTOR, law.effects[0].at)
        val cows = MechanicsSamples.rules.getValue("flying_cows")
        assertEquals(listOf(0, 3, 3), cows.effects.map { it.delaySeconds })
        assertEquals(listOf("Bartok"), MechanicsSamples.rules.getValue("welcome_plan").conditions.players)
        assertTrue(MechanicsSamples.rules.getValue("welcome_plan").once)

        val decoded = ToolArgs.decode(
            ChallengeSpec.serializer(),
            """{"title":"Œuf de tortue","statement":"Le Narrateur a défié Bartok.","goal":"obtain","item":"turtle_egg","minutes":15,""" +
                """"no_armor":false,"reward":{"kind":"item","item":"golden_apple"},"penalty":{"punishment":"tiny","severity":2}}""",
        )
        val spec = when (decoded) {
            is ToolArgs.Decoded.Ok -> decoded.args
            is ToolArgs.Decoded.Invalid -> error(decoded.problem)
        }
        assertEquals(PunishmentId.TINY, spec.penalty?.punishment)
        assertEquals(
            "minutes must be between 5 and 10080 (got 2)",
            (ToolArgs.decode(RuleSpec.serializer(), """{"kind":"law","statement":"x","trigger":"chat","effects":[{"tool":"say","at":"actor"}],"minutes":2}""") as ToolArgs.Decoded.Invalid).problem,
        )
    }

    @Test
    fun `a reward says which tool pays it, and a forfeit is never more than severity 2`() {
        assertEquals(listOf("give_item", "apply_effect", "favor", "favor"), RewardKind.entries.map { it.tool })
        assertEquals(
            "penalty.severity must be between 1 and 2 (got 3)",
            (
                ToolArgs.decode(
                    ChallengeSpec.serializer(),
                    """{"title":"t","statement":"s","goal":"judged","minutes":5,"penalty":{"punishment":"tiny","severity":3}}""",
                ) as ToolArgs.Decoded.Invalid
                ).problem,
        )
        assertTrue(PlaceSpec().isEmpty)
        assertFalse(PlaceSpec(yBelow = 0).isEmpty)
    }

    /**
     * The seven new tools of the Director cost at most 14 000 characters of definitions (M4 §12.1, checked by
     * `ToolCatalogTest` once they exist, M4-22); their arguments take at most this share (9 261 when written, 9 327
     * once `when.entity`, `block` and `item` said they take a `#tag`, M4-POST), leaving about 4 600 characters for
     * their names and descriptions (about 3 800 in §12.2, 4 150 with the names and the keys of the definitions).
     */
    @Test
    fun `the arguments of the seven new tools build a schema, and fit their share of the budget`() {
        val arguments: List<KSerializer<*>> = listOf(
            ChallengeSpec.serializer(), EndChallengeArgs.serializer(), DuelSpec.serializer(), RuleSpec.serializer(),
            RemoveRuleArgs.serializer(), PunishArgs.serializer(), FavorArgs.serializer(),
        )
        val sizes = arguments.associate { it.descriptor.serialName.substringAfterLast('.') to ToolSchema.of(it.descriptor).toString().length }
        assertTrue(sizes.values.sum() <= ARGUMENTS_BUDGET, "the arguments take ${sizes.values.sum()} characters: $sizes")
        ToolSchema.of(SurveyArgs.serializer().descriptor)
    }

    private companion object {
        const val ARGUMENTS_BUDGET = 9_400
    }
}
