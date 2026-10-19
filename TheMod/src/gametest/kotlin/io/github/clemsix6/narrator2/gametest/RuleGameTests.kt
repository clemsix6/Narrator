package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.mechanics.EngineActions
import io.github.clemsix6.narrator2.mechanics.Mechanics
import io.github.clemsix6.narrator2.mechanics.RuleControl
import io.github.clemsix6.narrator2.mechanics.RuleKind
import io.github.clemsix6.narrator2.mechanics.RuleSpec
import io.github.clemsix6.narrator2.mechanics.StateStore
import io.github.clemsix6.narrator2.mechanics.rule.MobAttributeTool
import io.github.clemsix6.narrator2.mechanics.rule.PunishStandIn
import io.github.clemsix6.narrator2.mechanics.rule.RuleBook
import io.github.clemsix6.narrator2.mechanics.rule.RuleMarks
import io.github.clemsix6.narrator2.mechanics.rule.RuleValidator
import io.github.clemsix6.narrator2.mechanics.rule.ServerRuleIds
import io.github.clemsix6.narrator2.mechanics.rule.ruleOnlyTools
import io.github.clemsix6.narrator2.memory.CanonKind
import io.github.clemsix6.narrator2.memory.CanonMechanic
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.runtime.NarratorRuntime
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.testing.MechanicsSamples
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.ToolArgs
import io.github.clemsix6.narrator2.tool.ToolCatalog
import io.github.clemsix6.narrator2.tool.ToolRunner
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.tool.action.MobAttributeKey
import io.github.clemsix6.narrator2.tool.mechanics.CreateRuleTool
import io.github.clemsix6.narrator2.tool.mechanics.RemoveRuleTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.ai.attributes.Attributes
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * In-game tests of the rules' format, validation and tools (M4-14, M4 §21.2): the seven rules of §8.8 through
 * `create_rule` with the server's real tools, ids and canon (a law or a plan the engine tracks, an entry of the
 * state), refusals with the real registries, `remove_rule`; and `mob_effect` and `mob_attribute` on real
 * creatures, which they mark as the rule's.
 *
 * The rules live in a rule book on a state in memory, never the world's; their canon rows are the server's,
 * and each test removes the rules it made (their rows closed). No explosion and no Director: the default batch.
 */
class RuleGameTests {

    /**
     * The seven rules of the specification through the real `create_rule` (the plan aimed at a player who joined):
     * each one a row of the canon (`law` or `plan`, mechanic `rule`, active) and an entry of the state, its ids in
     * full; a rule against the guards refused; then `remove_rule` closes each row (a law retired, a plan
     * cancelled) and empties the state.
     */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun theRulesOfTheSpecificationComeAndGo(helper: GameTestHelper) {
        val runtime = helper.narrator()
        val player = TestPlayer(helper, "rl_")
        val rules = Rules(runtime)
        val caller = Caller(Role.DIRECTOR, "gametest-rules", turnId = 1, taskId = null)
        val runner = ToolRunner(rules.services)
        async(helper, cleanup = { player.close() }) {
            val made = MechanicsSamples.ruleExamples.mapValues { (_, json) ->
                val input = json.replace("\"Bartok\"", "\"${player.name}\"")
                val result = runner.invoke(CreateRuleTool(), caller, input)
                check(result.status == ToolStatus.OK) { "refused: ${result.text}" }
                val spec = (ToolArgs.decode(RuleSpec.serializer(), input) as ToolArgs.Decoded.Ok).args
                Ref.parse(result.text.substringAfter("rule ").substringBefore(' '))!! to spec
            }
            for ((name, made) in made) {
                val (ref, spec) = made
                val canon = runtime.memory.store.canon(ref) ?: error("$name: no canon row $ref")
                check(canon.kind == (if (spec.kind == RuleKind.LAW) CanonKind.LAW else CanonKind.PLAN)) { "$name: ${canon.kind}" }
                check(canon.mechanic == CanonMechanic.RULE && canon.status == CanonStatus.ACTIVE) { "$name: ${canon.mechanic} ${canon.status}" }
            }
            val state = rules.store.get().rules.associateBy { it.id }
            fun conditions(name: String) = state.getValue(made.getValue(name).first.id).spec.conditions
            check(conditions("explosive_diamonds").block == "#minecraft:diamond_ores") { "the real tag: ${conditions("explosive_diamonds").block}" }
            check(conditions("skeleton_arrows").entity == "minecraft:skeleton") { conditions("skeleton_arrows").toString() }
            check(conditions("welcome_plan").dimension == "minecraft:the_nether") { conditions("welcome_plan").toString() }
            val plan = state.getValue(made.getValue("welcome_plan").first.id)
            check(plan.playerIds == listOf(player.uuid.toString())) { "the plan aims at the player: ${plan.playerIds}" }

            val refused = runner.invoke(
                CreateRuleTool(), caller,
                """{"kind":"law","statement":"Le Narrateur a décrété une loi d'essai.","trigger":"kill","when":{"entity":"zombi"},""" +
                    """"effects":[{"tool":"say","at":"actor","args":{"text":"Non."}}],"minutes":60}""",
            )
            check(refused.text.startsWith("Invalid arguments: when.entity \"zombi\" is unknown; closest ids: zombie")) { refused.text }

            for ((name, made) in made) {
                val ref = made.first
                val removed = runner.invoke(RemoveRuleTool(), caller, """{"id":"$ref","note":"Le Narrateur a levé la règle d'essai."}""")
                check(removed.status == ToolStatus.OK) { "$name: ${removed.text}" }
                val closed = runtime.memory.store.canon(ref)!!.status
                check(closed == (if (made.second.kind == RuleKind.LAW) CanonStatus.RETIRED else CanonStatus.CANCELLED)) { "$name: $closed" }
            }
            check(rules.store.get().rules.isEmpty()) { "the state is empty" }
        }
    }

    /** The server's ids: a tag, a structure tag, a biome tag, an advancement and a dimension written short come out in full. */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun theServersIdsAreTheRulesIds(helper: GameTestHelper) {
        val rules = Rules(helper.narrator())
        val caller = Caller(Role.DIRECTOR, "gametest-rules", turnId = 1, taskId = null)
        fun spec(trigger: String, conditions: String) = (
            ToolArgs.decode(
                RuleSpec.serializer(),
                """{"kind":"law","statement":"Le Narrateur a décrété une loi d'essai.","trigger":"$trigger","when":$conditions,""" +
                    """"effects":[{"tool":"actionbar","at":"actor","args":{"text":"Vu, {actor}."}}],"minutes":60}""",
            ) as ToolArgs.Decoded.Ok
            ).args
        async(helper) {
            val validator = rules.validator
            check(validator.validate(spec("position", """{"structure":"#village"}"""), caller).spec.conditions.structure == "#minecraft:village")
            check(validator.validate(spec("biome_enter", """{"biome":"#is_ocean"}"""), caller).spec.conditions.biome == "#minecraft:is_ocean")
            check(validator.validate(spec("advancement", """{"advancement":"story/mine_diamond"}"""), caller).spec.conditions.advancement == "minecraft:story/mine_diamond")
            check(validator.validate(spec("dimension_change", """{"dimension":"nether"}"""), caller).spec.conditions.dimension == "minecraft:the_nether")
            check(validator.validate(spec("craft", """{"item":"#logs"}"""), caller).spec.conditions.item == "#minecraft:logs")
            val unknown = runCatching { validator.validate(spec("block_break", """{"block":"#diamond_ore"}"""), caller) }.exceptionOrNull()?.message.orEmpty()
            check(unknown.startsWith("when.block \"#diamond_ore\" is unknown; closest tags: #diamond_ores")) { unknown }
        }
    }

    /**
     * `mob_effect` and `mob_attribute` run by an act of a rule on real creatures: the effect is on, the modifier of
     * the rule is there (put again without trouble, as on a creature loaded again), a raised maximum health raises the
     * health once, and each creature bears the Narrator's tag and the rule's.
     */
    @GameTest
    fun theCreaturesToolsMarkTheCreatureAsTheRules(helper: GameTestHelper) {
        val runtime = helper.narrator()
        val actions = EngineActions(runtime, ruleOnlyTools())
        val rule = Ref(RefKind.CANON, 990_101)
        val cow = helper.spawnWithNoFreeWill(EntityTypes.COW, BlockPos(2, 1, 2))
        val creeper = helper.spawnWithNoFreeWill(EntityTypes.CREEPER, BlockPos(5, 1, 5))
        try {
            fun run(tool: String, creature: UUID, vararg args: Pair<String, Any>) = runBlocking {
                actions.run(
                    actions.open("fired", rule, null),
                    tool,
                    buildJsonObject {
                        args.forEach { (key, value) -> if (value is Number) put(key, value) else put(key, value.toString()) }
                        put("creature", creature.toString())
                    },
                )
            }
            val levitating = run("mob_effect", cow.uuid, "effect" to "levitation", "seconds" to 3, "level" to 2)
            helper.assertTrue(levitating.status == ToolStatus.OK, levitating.text)
            helper.assertTrue(cow.hasEffect(MobEffects.LEVITATION), "the cow levitates")

            val fast = run("mob_attribute", creeper.uuid, "attribute" to "movement_speed", "multiply" to 3)
            helper.assertEquals("ok: minecraft:creeper movement_speed ×3 while C-990101 lasts", fast.text, "the answer")
            val modifier = RuleMarks.modifier(rule.id, MobAttributeKey.MOVEMENT_SPEED)
            helper.assertEquals(2.0, creeper.getAttribute(Attributes.MOVEMENT_SPEED)!!.getModifier(modifier)?.amount(), "the rule's modifier")
            val again = run("mob_attribute", creeper.uuid, "attribute" to "movement_speed", "multiply" to 3)
            helper.assertTrue(again.status == ToolStatus.OK, "put again: ${again.text}")

            cow.health = 5f
            run("mob_attribute", cow.uuid, "attribute" to "max_health", "multiply" to 2)
            helper.assertEquals(20f, cow.maxHealth, "the maximum health doubled")
            helper.assertEquals(10f, cow.health, "the health doubled")
            run("mob_attribute", cow.uuid, "attribute" to "max_health", "multiply" to 2)
            helper.assertEquals(10f, cow.health, "the health doubled once")
            helper.assertEquals(1.0, MobAttributeTool.min(MobAttributeKey.MAX_HEALTH), "never lowered")

            for (creature in listOf(cow, creeper)) {
                helper.assertTrue(
                    creature.entityTags().containsAll(listOf("narrator2", RuleMarks.tag(rule.id))),
                    "${creature.type} marked as the rule's: ${creature.entityTags()}",
                )
            }
            val gone = run("mob_effect", UUID.randomUUID(), "effect" to "glowing", "seconds" to 3)
            helper.assertEquals("FAILED: the creature of the trigger is gone", gone.text, "a creature that left")
        } finally {
            cow.discard()
            creeper.discard()
        }
        helper.succeed()
    }

    /** A rule book of the tests: the server's tools (`punish` stood in until M4-13), ids and canon, a state in memory. */
    private class Rules(runtime: NarratorRuntime) {
        val store = StateStore.inMemory()
        private val tools: List<NarratorTool<*>> = ToolCatalog.allTools(runtime).let { tools ->
            if (tools.none { it.name == "punish" }) tools + PunishStandIn() else tools
        } + ruleOnlyTools()
        val validator = RuleValidator(runtime, EngineActions(runtime, tools), ServerRuleIds(runtime.server))
        val book = RuleBook(runtime, store, validator)

        /** The server's services, with an engine whose rules are [book], for the tools. */
        val services: NarratorServices = object : NarratorServices by runtime {
            override val mechanics: Mechanics = object : Mechanics by runtime.mechanics {
                override val available = true
                override val rules: RuleControl = book
            }
        }
    }

    private companion object {
        /** Runs [block] off the server thread (it waits for the Journal); the test succeeds once it is done, fails with what it threw. */
        fun async(helper: GameTestHelper, cleanup: () -> Unit = {}, block: suspend () -> Unit) {
            val done = AtomicBoolean()
            val failure = AtomicReference<Throwable?>()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    block()
                } catch (e: Throwable) {
                    failure.set(e)
                } finally {
                    done.set(true)
                }
            }
            helper.awaitAsync {
                helper.assertTrue(done.get(), "the work is done")
                cleanup()
                helper.assertTrue(failure.get() == null, "it failed: ${failure.get()}")
            }
        }
    }
}
