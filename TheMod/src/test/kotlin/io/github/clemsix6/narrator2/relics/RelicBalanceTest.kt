package io.github.clemsix6.narrator2.relics

import io.github.clemsix6.narrator2.tool.ToolArgs
import io.github.clemsix6.narrator2.tool.action.EnchantmentSpec
import net.minecraft.SharedConstants
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M7-BALANCE: a player's tier, the caps of the relic they earn, the threat a champion stands for, and the reserves by tier.
 * The owner's case: Thawyn, with a wooden axe, an iron helmet and iron leggings, slew a champion of 60 health in a few
 * blows and got an iron axe with sharpness IV, unbreaking III and efficiency III.
 */
class RelicBalanceTest {
    private val hacheDuNonLieu = RelicDraft(
        "iron_axe", "La Hache du Non-Lieu", listOf("Arrachée à Lumignon, syndic des torches, à coups de hache en bois."),
        listOf(EnchantmentSpec("sharpness", 4), EnchantmentSpec("unbreaking", 3), EnchantmentSpec("efficiency", 3)),
        listOf(RelicAttribute(RelicAttributeKey.ATTACK_DAMAGE, 2.0), RelicAttribute(RelicAttributeKey.BLOCK_BREAK_SPEED, -0.1)),
    )

    @Test
    fun `a player's tier is their best tool or weapon, or one below their best armor`() {
        assertEquals(Tier.STONE, Tier.ofPlayer(listOf("minecraft:wooden_axe"), listOf("minecraft:iron_helmet", "minecraft:iron_leggings")), "Thawyn")
        assertEquals(Tier.WOOD, Tier.ofPlayer(emptyList(), emptyList()), "empty hands")
        assertEquals(Tier.WOOD, Tier.ofPlayer(listOf("wooden_sword"), listOf("leather_boots")))
        assertEquals(Tier.IRON, Tier.ofPlayer(listOf("stone_pickaxe", "iron_sword"), emptyList()))
        assertEquals(Tier.DIAMOND, Tier.ofPlayer(listOf("diamond_pickaxe"), listOf("iron_chestplate")))
        assertEquals(Tier.DIAMOND, Tier.ofPlayer(listOf("iron_sword"), listOf("netherite_boots")))
        assertEquals(Tier.STONE, Tier.ofMaterial("golden_sword"))
        assertEquals(Tier.IRON, Tier.ofMaterial("chainmail_helmet"))
        assertEquals(Tier.DIAMOND, Tier.ofMaterial("mace"))
        assertEquals(null, Tier.ofMaterial("turtle_helmet"))
    }

    @Test
    fun `every tool, weapon and piece of armor of the game has a tier`() {
        val suffixes = listOf("_sword", "_axe", "_pickaxe", "_shovel", "_hoe", "_spear", "_helmet", "_chestplate", "_leggings", "_boots")
        val gear = BuiltInRegistries.ITEM.keySet().map { it.path }.filter { id -> suffixes.any { id.endsWith(it) } }
        assertTrue(gear.size > 40, "$gear")
        assertEquals(listOf("turtle_helmet"), gear.filter { Tier.ofMaterial(it) == null }, "an unknown base counts as diamond")
    }

    @Test
    fun `a relic's caps are one step above its player's tier, one less after a fight too easy`() {
        val wood = RelicCaps(Tier.WOOD)
        assertEquals(listOf(Tier.STONE, 2), listOf(wood.material, wood.level))
        val stone = RelicCaps(Tier.STONE)
        assertEquals(listOf(Tier.IRON, 2, false), listOf(stone.material, stone.level, stone.unbreakable))
        val easy = RelicCaps(Tier.STONE, trivial = true)
        assertEquals(listOf(Tier.STONE, 2), listOf(easy.material, easy.level))
        assertEquals(listOf(Tier.WOOD, 1), RelicCaps(Tier.WOOD, trivial = true).let { listOf(it.material, it.level) })
        assertEquals(listOf(Tier.DIAMOND, 3, false), RelicCaps(Tier.IRON).let { listOf(it.material, it.level, it.unbreakable) })
        assertEquals(listOf(Tier.DIAMOND, 5, true), RelicCaps(Tier.NETHERITE).let { listOf(it.material, it.level, it.unbreakable) }, "never netherite")
        assertEquals(
            "base iron at most (wooden_, leather_, bow, stone_, golden_, copper_, shield, iron_, chainmail_, crossbow); 2 enchantments at most, " +
                "level II at most; attribute bonuses up to 50 % of their cap; never unbreakable",
            stone.describe(),
        )
    }

    @Test
    fun `Thawyn's axe is refused at his tier, with every cap it breaks`() {
        val problems = RelicRules.withinCaps(hacheDuNonLieu, RelicCaps(Tier.STONE, trivial = true)).joinToString()
        assertTrue(problems.startsWith("too strong for a stone-tier player, after a fight that was too easy: "), problems)
        assertTrue("base: stone at most (iron_axe is iron)" in problems, problems)
        assertTrue("3 enchantments: 2 at most" in problems, problems)
        assertTrue("enchantments up to level 2 (sharpness 4, unbreaking 3, efficiency 3)" in problems, problems)
        assertTrue("attack_damage" !in problems, "+2 is half the cap: $problems")
        assertEquals(Tier.DIAMOND, RelicRules.fittingTier(hacheDuNonLieu), "what it was: a diamond player's relic")
        val fair = hacheDuNonLieu.copy(item = "stone_axe", enchantments = listOf(EnchantmentSpec("sharpness", 2), EnchantmentSpec("unbreaking", 1)))
        assertEquals(emptyList(), RelicRules.withinCaps(fair, RelicCaps(Tier.STONE, trivial = true)))
        assertEquals(Tier.WOOD, RelicRules.fittingTier(fair))
        val strong = fair.copy(attributes = listOf(RelicAttribute(RelicAttributeKey.ATTACK_DAMAGE, 3.0)), unbreakable = true)
        val tooStrong = RelicRules.withinCaps(strong, RelicCaps(Tier.STONE)).joinToString()
        assertTrue("attack_damage: a bonus up to 2 here (got 3)" in tooStrong && "unbreakable: not at this tier" in tooStrong, tooStrong)
    }

    @Test
    fun `a relic of the reserves names its tier`() {
        val json = """{"item": "stone_sword", "name": "L'Épée du Débutant", "lore": ["Un début."], "enchantments": [{"id": "sharpness", "level": 1}], "tier": "wood"}"""
        val decoded = ToolArgs.decode(RelicDraft.serializer(), json)
        assertTrue(decoded is ToolArgs.Decoded.Ok, "$decoded")
        assertEquals(Tier.WOOD, decoded.args.tier)
        assertTrue(ToolArgs.decode(RelicDraft.serializer(), json.replace("\"wood\"", "\"gold\"")) is ToolArgs.Decoded.Invalid)
    }

    @Test
    fun `the reserves keep relics for every tier, the lowest first`() {
        assertEquals(List(5) { 1 }, RelicsService.tierTargets(5).values.toList())
        assertEquals(listOf(1, 1, 1, 0, 0), RelicsService.tierTargets(3).values.toList())
        assertEquals(listOf(2, 2, 1, 1, 1), RelicsService.tierTargets(7).values.toList())
        assertEquals(0, RelicsService.tierTargets(0).values.sum())
    }

    @Test
    fun `a champion lasts twenty seconds against the best weapon, a minute at most`() {
        val woodenAxe = 7.0 * 0.8
        assertEquals(112.0, Threat.health(60.0, woodenAxe), 1e-9, "Thawyn's husk: 60 health, 112 now")
        assertEquals(150.0, Threat.health(150.0, woodenAxe), 1e-9, "what the armorer and the layer give is kept")
        assertEquals(336.0, Threat.health(600.0, woodenAxe), 1e-9, "a minute of blows at most: the layer at x10 makes no wall")
        val sharpDiamond = (7.0 + 3.0) * 1.6
        assertEquals(320.0, Threat.health(90.0, sharpDiamond), 1e-9)
        assertEquals(Threat.MAX_HEALTH, Threat.health(90.0, 40.0), 1e-9, "400 at most")
    }

    @Test
    fun `a champion's bare blow takes three health through its player's armor`() {
        // The game's own numbers (CombatRules): 10 damage through full diamond (20 armor, 8 toughness) leaves 3.
        assertEquals(3.0, Threat.afterArmor(10.0, 20.0, 8.0), 1e-9)
        assertEquals(5.0, Threat.afterArmor(5.0, 0.0, 0.0), 1e-9)
        assertEquals(2.5, Threat.bareDamage(0.0, 0.0, 1.25), 1e-9, "a bare player")
        assertEquals(3.5, Threat.bareDamage(7.0, 0.0, 1.25), 1e-9, "Thawyn: iron helmet and leggings")
        assertEquals(8.0, Threat.bareDamage(20.0, 8.0, 1.25), 1e-9, "full diamond")
        assertEquals(Threat.MAX_BARE_DAMAGE, Threat.bareDamage(20.0, 12.0, 0.1), 1e-9, "never above the cap")
        assertTrue(Threat.bareDamage(20.0, 8.0, 3.0) < Threat.bareDamage(20.0, 8.0, 1.0), "the layer's factor counts")
    }

    @Test
    fun `a fight is too easy when quick or nearly harmless`() {
        assertTrue(RelicRules.trivial(8_000, 12.0), "killed in 8 s")
        assertTrue(RelicRules.trivial(40_000, 2.0), "a single scratch")
        assertTrue(!RelicRules.trivial(25_000, 9.0), "a real fight")
    }

    private companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrap() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }
}
