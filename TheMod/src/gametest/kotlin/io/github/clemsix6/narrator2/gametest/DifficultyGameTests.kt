package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.difficulty.Difficulty
import io.github.clemsix6.narrator2.gametest.GuardPolicyGameTests.Companion.mockPlayer
import io.github.clemsix6.narrator2.gametest.GuardPolicyGameTests.Companion.removePlayers
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.phys.Vec3

/** The permanent difficulty layer (M7-BASE) on a real server: its section read at `SERVER_STARTING`, with its defaults. */
class DifficultyGameTests {
    /**
     * A hostile creature is born with its health raised and full; an animal and a boss keep theirs. A hostile creature's
     * blow hurts a player more than the same blow from an animal (the `hurtServer` mixin).
     */
    @GameTest
    fun hostileCreaturesHaveMoreHealthAndHitHarder(helper: GameTestHelper) {
        val config = Difficulty.config
        helper.assertTrue(config != null && config.enabled, "the layer was read when the server started: $config")
        val zombie = spawn(helper, EntityTypes.ZOMBIE, Vec3(1.5, 1.0, 1.5))
        val cow = spawn(helper, EntityTypes.COW, Vec3(3.5, 1.0, 1.5))
        val warden = spawn(helper, EntityTypes.WARDEN, Vec3(5.5, 1.0, 1.5))
        val expected = (20 * config!!.mobHealth).toFloat()
        helper.assertValueEqual(expected, zombie.maxHealth, "the zombie's maximum health")
        helper.assertValueEqual(expected, zombie.health, "a fresh zombie is at its new maximum")
        helper.assertTrue(zombie.getAttribute(Attributes.MAX_HEALTH)!!.hasModifier(Difficulty.HEALTH_ID), "through the layer's own modifier")
        helper.assertValueEqual(10f, cow.maxHealth, "an animal keeps its health")
        helper.assertValueEqual(500f, warden.maxHealth, "a boss keeps its health")
        Difficulty.onMobLoad(zombie)
        helper.assertValueEqual(expected, zombie.maxHealth, "loaded again: unchanged")

        val bitten = mockPlayer(helper, BlockPos(1, 1, 4))
        val kicked = mockPlayer(helper, BlockPos(3, 1, 4))
        try {
            val sources = helper.level.damageSources()
            bitten.hurtServer(helper.level, sources.mobAttack(zombie), 4f)
            kicked.hurtServer(helper.level, sources.mobAttack(cow), 4f)
            val ratio = (20f - bitten.health) / (20f - kicked.health)
            helper.assertTrue(
                kotlin.math.abs(ratio - config.mobDamage) < 0.01,
                "the zombie's blow x${config.mobDamage}: lost ${20f - bitten.health} against ${20f - kicked.health} (${helper.level.difficulty})",
            )
        } finally {
            removePlayers(helper, bitten, kicked)
        }
        listOf(zombie, cow, warden).forEach { it.discard() }
        helper.succeed()
    }

    private fun <T : Mob> spawn(helper: GameTestHelper, type: EntityType<T>, at: Vec3): T {
        val mob = type.create(helper.level, EntitySpawnReason.COMMAND)!!
        mob.snapTo(helper.absoluteVec(at))
        mob.isNoAi = true
        helper.assertTrue(helper.level.addFreshEntity(mob), "${type.toShortString()} added")
        return mob
    }
}
