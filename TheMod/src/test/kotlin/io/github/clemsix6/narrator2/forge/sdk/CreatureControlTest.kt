package io.github.clemsix6.narrator2.forge.sdk

import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.forge.api.Place
import io.github.clemsix6.narrator2.forge.sdk.ModuleCreatures.ClaimFacts
import io.github.clemsix6.narrator2.guard.SpawnPolicy
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.mechanics.Firing
import io.github.clemsix6.narrator2.mechanics.rule.MarkOwner
import io.github.clemsix6.narrator2.mechanics.rule.RuleMarks
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.testing.MechanicsSamples
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolResult
import kotlinx.serialization.json.JsonObject
import io.github.clemsix6.narrator2.tool.action.SpawnArgs
import io.github.clemsix6.narrator2.tool.action.SpawnTool
import io.github.clemsix6.narrator2.tool.action.MobAttributeKey
import kotlinx.coroutines.runBlocking
import net.minecraft.SharedConstants
import net.minecraft.resources.Identifier
import net.minecraft.server.Bootstrap
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The creatures of a module (M5 §4.5, task M5-07): what a module may claim (never a boss nor a creature out of scale,
 * design review finding 4), its marks beside the rules', and its room for creatures. What they do in the world (they
 * push nobody, aim at their target only, never kill a bystander: finding 3) is tried in the game,
 * `ModuleActsGameTests`.
 */
class CreatureControlTest {
    @Test
    fun `a module claims only a hostile creature near a player, nobody's yet`() {
        for (type in listOf(EntityTypes.ZOMBIE, EntityTypes.SKELETON, EntityTypes.SPIDER, EntityTypes.HUSK, EntityTypes.WITCH)) {
            assertNull(ModuleCreatures.claimRefusal(ClaimFacts(type)), "${EntityType.getKey(type)} may be claimed")
        }
        val animals = "only a hostile creature is claimed, never an animal, a villager, a trader nor a golem: summon animals"
        for (type in listOf(EntityTypes.COW, EntityTypes.WOLF, EntityTypes.CAT, EntityTypes.VILLAGER, EntityTypes.WANDERING_TRADER, EntityTypes.IRON_GOLEM, EntityTypes.CHICKEN)) {
            assertEquals(animals, ModuleCreatures.claimRefusal(ClaimFacts(type)), "${EntityType.getKey(type)}")
        }
        val zombie = ClaimFacts(EntityTypes.ZOMBIE)
        assertEquals("it is the Narrator's already (summoned, or changed by a rule or a module)", ModuleCreatures.claimRefusal(zombie.copy(narrators = true)))
        assertEquals("it is tamed", ModuleCreatures.claimRefusal(zombie.copy(tamed = true)))
        assertEquals("a player named it", ModuleCreatures.claimRefusal(zombie.copy(named = true)))
        assertEquals("it is on a lead", ModuleCreatures.claimRefusal(zombie.copy(leashed = true)))
        assertEquals("it rides or carries another", ModuleCreatures.claimRefusal(zombie.copy(riding = true)))
        assertEquals("it is on a team", ModuleCreatures.claimRefusal(zombie.copy(team = true)))
        assertEquals("it floats or glows already", ModuleCreatures.claimRefusal(zombie.copy(floating = true)))
        assertEquals("it is farther than 32 blocks from every player", ModuleCreatures.claimRefusal(zombie.copy(near = false)))
    }

    @Test
    fun `never a boss nor a creature out of scale, claimed or summoned`() {
        for (type in listOf(EntityTypes.WARDEN, EntityTypes.WITHER, EntityTypes.ENDER_DRAGON, EntityTypes.ELDER_GUARDIAN, EntityTypes.GIANT)) {
            val id = EntityType.getKey(type).toString()
            assertTrue(id in SpawnPolicy.FORBIDDEN, "$id is never summoned")
            assertEquals("$id is never claimed: ${SpawnPolicy.FORBIDDEN[id]}", ModuleCreatures.claimRefusal(ClaimFacts(type)))
            val spawn = runBlocking { SpawnTool().check(context(), SpawnArgs(entity = id.removePrefix("minecraft:"), near = "Bartok")) }
            assertEquals("DENIED (forbidden_creature): $id is not summoned: ${SpawnPolicy.FORBIDDEN[id]}.", spawn?.text, "the real spawn's check")
        }
    }

    @Test
    fun `never a creature whose blast hits everyone around`() {
        assertEquals(
            "minecraft:creeper is never a module's: its blast hits every player around, not only its target",
            ModuleCreatures.claimRefusal(ClaimFacts(EntityTypes.CREEPER)),
        )
        assertEquals(
            "minecraft:breeze is never a module's: its wind charges throw every player around, not only its target",
            ModuleCreatures.claimRefusal(ClaimFacts(EntityTypes.BREEZE)),
        )
        for (id in ModuleCreatures.FORBIDDEN.keys) assertTrue(id !in SpawnPolicy.HARMLESS, id)
    }

    @Test
    fun `a module's marks are its own, and its factors count with the rules'`() {
        val module = MarkOwner.module(70)
        assertEquals("narrator2_module_70", module.tag)
        assertEquals(Identifier.fromNamespaceAndPath("narrator2", "module/70/fall_damage_multiplier"), module.modifier(CreatureControl.FALL))
        assertEquals("narrator2_rule_60", RuleMarks.tag(60))
        assertEquals(Identifier.fromNamespaceAndPath("narrator2", "rule/60/scale"), RuleMarks.modifier(60, MobAttributeKey.SCALE))
        assertTrue(MarkOwner.isOwners(module.modifier("scale"), "scale"))
        assertTrue(MarkOwner.isOwners(RuleMarks.modifier(60, MobAttributeKey.SCALE), "scale"))
        assertFalse(MarkOwner.isOwners(Identifier.fromNamespaceAndPath("narrator2", "spawn/scale"), "scale"), "a summoning's own factor is not an owner's")
        assertFalse(MarkOwner.isOwners(module.modifier("scale"), "gravity"))
    }

    @Test
    fun `where a creature was claimed is kept in a tag`() {
        assertEquals("narrator2_origin|the_nether|12.5|64.0|-30.5", ModuleCreatures.originTag(Place("minecraft:the_nether", 12.5, 64.0, -30.5)))
    }

    @Test
    fun `a deployment has room for its own creatures, the summonings on their way counted`() {
        val services = TestServices(clock = TestClock())
        val queue = ActQueue(MechanicsSamples.canon(70), NoRunner, services.scope, services.clock, { _, body -> body() }, { it.run() })
        val control = CreatureControl(services, 70, queue)
        assertTrue(control.reserve(20))
        assertTrue(control.reserve(ForgeLimits.OWN_CREATURES - 20))
        assertFalse(control.reserve(1), "32 on their way")
        control.adopt(emptyList(), 12, near = null, targeting = false)
        assertEquals(20, control.coming)
        assertTrue(control.reserve(12), "a summoning that brought nothing gives its room back")
        assertEquals(0, control.releaseAll())
        assertFalse(control.reserve(1), "nothing once the deployment ended")
        queue.close()
    }

    /** No act of the engine: [CreatureControl] alone. */
    private object NoRunner : ActRunner {
        override fun open(ref: Ref, subject: String?, reason: String): Firing = error("no act")

        override suspend fun run(firing: Firing, tool: String, args: JsonObject): ToolResult = error("no act")
    }

    private fun context(): ToolContext {
        val services = TestServices()
        return ToolContext(services, Caller(Role.DIRECTOR, "mechanics:C-70", null, null, 1), services.clock, 5_000)
    }

    private companion object {
        @BeforeAll
        @JvmStatic
        fun boot() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }
}
