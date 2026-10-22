package io.github.clemsix6.narrator2.difficulty

import io.github.clemsix6.narrator2.config.ConfigStore
import net.minecraft.SharedConstants
import net.minecraft.resources.Identifier
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DifficultyTest {
    @TempDir
    lateinit var dir: Path

    private val defaults = DifficultyConfig()

    private fun factor(feature: String, config: DifficultyConfig = defaults) = Difficulty.oreFactor(config, Identifier.withDefaultNamespace(feature))

    @Test
    fun `each ore feature has the share of blobs the supervisor chose`() {
        val expected = mapOf(
            "ore_diamond_small" to 0.5, "ore_diamond_medium" to 0.5, "ore_diamond_large" to 0.5, "ore_diamond_buried" to 0.5,
            "ore_emerald" to 0.5,
            "ore_gold_buried" to 0.5, "ore_gold" to 0.5, "ore_nether_gold" to 0.5,
            "ore_iron" to 0.75, "ore_iron_small" to 0.75, "ore_copper_small" to 0.75, "ore_copper_large" to 0.75,
            "ore_coal" to 1.0, "ore_coal_buried" to 1.0, "ore_redstone" to 1.0, "ore_lapis" to 1.0, "ore_lapis_buried" to 1.0,
            "ore_quartz" to 1.0, "ore_ancient_debris_large" to 1.0, "ore_ancient_debris_small" to 1.0,
        )
        assertEquals(expected.keys, Difficulty.ORE_FEATURES.keys, "every vanilla ore feature, nothing else")
        expected.forEach { (feature, share) -> assertEquals(share, factor(feature), feature) }
        assertTrue(Difficulty.ORE_FEATURES.values.all { it in DifficultyConfig.DEFAULT_ORES }, "each ore has its setting")
        assertNull(factor("ore_tuff"), "stone blobs are not ores")
        assertNull(Difficulty.oreFactor(defaults, Identifier.fromNamespaceAndPath("othermod", "ore_diamond_small")), "only vanilla features")
    }

    @Test
    fun `the draw keeps the share of blobs asked`() {
        val draws = (0 until 20_000).map { Difficulty.draw(20261022, it * 16L, -40, it * 7L, "ore_diamond_small".hashCode().toLong()) }
        assertTrue(draws.all { it >= 0.0 && it < 1.0 })
        val kept = draws.count { it < 0.5 } / 20_000.0
        assertTrue(kept in 0.48..0.52, "kept $kept")
    }

    @Test
    fun `the briefing says what changes, nothing when all is vanilla`() {
        assertEquals(
            "hostile mobs health x1.5, damage to players x1.25 (bosses vanilla); " +
                "health by type, over vanilla: skeleton/stray/bogged/parched x0.25; movement speed creeper x2; " +
                "bow fire rate skeleton/stray/bogged/parched x2; " +
                "ores diamond/emerald/gold x0.5, iron/copper x0.75; natural regeneration x0.5; chests diamonds x0.5, enchanted golden apples x0.5",
            Difficulty.summary(defaults),
        )
        val vanilla = DifficultyConfig(
            mobHealth = 1.0, mobDamage = 1.0, mobHealthByType = emptyMap(), mobSpeed = mapOf("creeper" to 1.0),
            mobFireRate = DifficultyConfig.DEFAULT_MOB_FIRE_RATE.mapValues { 1.0 }, ores = DifficultyConfig.DEFAULT_ORES.mapValues { 1.0 },
            regenerationSlowdown = 1.0, chestDiamonds = 1.0, chestEnchantedGoldenApples = 1.0,
        )
        assertNull(Difficulty.summary(vanilla))
        assertEquals("", Difficulty.briefingAttribute(), "not loaded (no server started): no attribute")
    }

    @Test
    fun `the section merges ore by ore and is validated`() {
        dir.resolve(ConfigStore.FILE_NAME).writeText(
            """{ "difficulty": { "ores": { "diamond": 0.3 }, "mobHealth": 2, "mobSpeed": { "zombie": 1.2 },
                "mobHealthByType": { "skeleton": 0.5, "zombie": 1 }, "mobFireRate": { "stray": 1 } } }""",
        )
        val config = ConfigStore.load(dir).difficulty
        assertEquals(0.3, config.ores["diamond"])
        assertEquals(0.75, config.ores["iron"], "the other ores keep their default")
        assertEquals(2.0, config.mobHealth)
        assertEquals(mapOf("creeper" to 2.0, "zombie" to 1.2), config.mobSpeed, "a type added, the default kept")
        assertEquals(
            mapOf("skeleton" to 0.5, "stray" to 0.25, "bogged" to 0.25, "parched" to 0.25, "zombie" to 1.0),
            config.mobHealthByType,
            "a default changed, a type added, the other defaults kept",
        )
        assertEquals(mapOf("skeleton" to 2.0, "stray" to 1.0, "bogged" to 2.0, "parched" to 2.0), config.mobFireRate)

        assertEquals(emptyList<String>(), defaults.problems())
        assertEquals(
            listOf("difficulty.ores.diamond must be between 0 and 1", "difficulty.regenerationSlowdown must be between 1 and 10"),
            defaults.copy(ores = defaults.ores + ("diamond" to 1.5), regenerationSlowdown = 0.5).problems(),
        )
        assertEquals(
            listOf(
                "difficulty.mobSpeed.creeper must be between 0.5 and 3",
                "difficulty.mobSpeed.creepr is not an entity type of the game: use its path, such as creeper or zombie",
            ),
            defaults.copy(mobSpeed = mapOf("creeper" to 4.0, "creepr" to 2.0, "zombie" to 1.2)).problems(),
        )
        assertEquals(
            listOf(
                "difficulty.mobHealthByType.skeleton must be between 0.1 and 10",
                "difficulty.mobFireRate.skeleton must be between 0.5 and 4",
                "difficulty.mobFireRate.skeletn is not an entity type of the game: use its path, such as creeper or zombie",
            ),
            defaults.copy(
                mobHealthByType = mapOf("skeleton" to 0.0, "zombie" to 2.0),
                mobFireRate = mapOf("skeleton" to 5.0, "skeletn" to 2.0, "illusioner" to 2.0),
            ).problems(),
        )
    }

    private companion object {
        /** The types of `mobSpeed` are looked up in the registry. */
        @JvmStatic
        @BeforeAll
        fun bootstrap() {
            SharedConstants.tryDetectVersion()
            Bootstrap.bootStrap()
        }
    }
}
