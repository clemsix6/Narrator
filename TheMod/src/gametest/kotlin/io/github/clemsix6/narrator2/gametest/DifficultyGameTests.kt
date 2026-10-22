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

/** The permanent difficulty layer (M7-BASE) on a real server: its section read at `SERVER_STARTING`, with its defaults, then reloaded (M7-RELOAD). */
class DifficultyGameTests {
    /**
     * A hostile creature is born with its health raised and full; an animal and a boss keep theirs. A creeper is born
     * faster, a zombie is not. A hostile creature's blow hurts a player more than the same blow from an animal (the
     * `hurtServer` mixin).
     */
    @GameTest
    fun hostileCreaturesHaveMoreHealthAndHitHarder(helper: GameTestHelper) {
        val config = Difficulty.config
        helper.assertTrue(config != null && config.enabled, "the layer was read when the server started: $config")
        val zombie = spawn(helper, EntityTypes.ZOMBIE, Vec3(1.5, 1.0, 1.5))
        val cow = spawn(helper, EntityTypes.COW, Vec3(3.5, 1.0, 1.5))
        val warden = spawn(helper, EntityTypes.WARDEN, Vec3(5.5, 1.0, 1.5))
        val creeper = spawn(helper, EntityTypes.CREEPER, Vec3(7.5, 1.0, 1.5))
        val expected = (20 * config!!.mobHealth).toFloat()
        helper.assertValueEqual(expected, zombie.maxHealth, "the zombie's maximum health")
        helper.assertValueEqual(expected, zombie.health, "a fresh zombie is at its new maximum")
        helper.assertTrue(zombie.getAttribute(Attributes.MAX_HEALTH)!!.hasModifier(Difficulty.HEALTH_ID), "through the layer's own modifier")
        helper.assertValueEqual(10f, cow.maxHealth, "an animal keeps its health")
        helper.assertValueEqual(500f, warden.maxHealth, "a boss keeps its health")
        helper.assertValueEqual(2.0, config.mobSpeed.getValue("creeper"), "the default speed of creepers")
        helper.assertTrue(kotlin.math.abs(speedFactor(creeper) - 2.0) < 1e-6, "a fresh creeper is twice as fast: x${speedFactor(creeper)}")
        helper.assertValueEqual(1.0, speedFactor(zombie), "a zombie keeps its speed")
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
        listOf(zombie, cow, warden, creeper).forEach { it.discard() }
        helper.succeed()
    }

    /**
     * A reload (M7-RELOAD) reconciles the creatures already loaded: a zombie from x1.5 to x3 with the same share of its
     * health, a creeper back to its vanilla speed once out of `mobSpeed`; then both vanilla when the layer is off.
     * Everything runs in this one call on the server thread and the section in force comes back before it returns, so
     * the other tests never see the change: their creatures go and come back.
     */
    @GameTest
    fun aReloadReconcilesTheLoadedCreatures(helper: GameTestHelper) {
        val server = helper.level.server
        val before = Difficulty.config!!
        val zombie = spawn(helper, EntityTypes.ZOMBIE, Vec3(1.5, 1.0, 1.5))
        val creeper = spawn(helper, EntityTypes.CREEPER, Vec3(3.5, 1.0, 1.5))
        zombie.health = zombie.maxHealth / 2
        val changed = before.copy(mobHealth = 3.0, mobSpeed = emptyMap())
        try {
            val reconciled = Difficulty.reload(server, changed)
            helper.assertTrue(reconciled >= 2, "the zombie and the creeper are among the reconciled creatures: $reconciled")
            helper.assertValueEqual(60f, zombie.maxHealth, "the zombie's maximum health at x3")
            helper.assertValueEqual(30f, zombie.health, "the same share of its health")
            helper.assertValueEqual(1.0, speedFactor(creeper), "a type out of the table: vanilla speed")
            helper.assertValueEqual(0, Difficulty.reload(server, changed), "reconciled again: nothing changes")

            Difficulty.reload(server, before.copy(enabled = false))
            helper.assertValueEqual(20f, zombie.maxHealth, "the layer off: vanilla health")
            helper.assertValueEqual(10f, zombie.health, "still the same share")
            helper.assertFalse(zombie.getAttribute(Attributes.MAX_HEALTH)!!.hasModifier(Difficulty.HEALTH_ID), "without the layer's modifier")
            helper.assertFalse(creeper.getAttribute(Attributes.MOVEMENT_SPEED)!!.hasModifier(Difficulty.SPEED_ID), "nor its speed modifier")
        } finally {
            Difficulty.reload(server, before)
        }
        helper.assertValueEqual((20 * before.mobHealth).toFloat(), zombie.maxHealth, "the section in force again")
        helper.assertTrue(creeper.getAttribute(Attributes.MOVEMENT_SPEED)!!.hasModifier(Difficulty.SPEED_ID), "the creeper fast again")
        listOf(zombie, creeper).forEach { it.discard() }
        helper.succeed()
    }

    /** [mob]'s movement speed over its base one. */
    private fun speedFactor(mob: Mob): Double = mob.getAttributeValue(Attributes.MOVEMENT_SPEED) / mob.getAttributeBaseValue(Attributes.MOVEMENT_SPEED)

    private fun <T : Mob> spawn(helper: GameTestHelper, type: EntityType<T>, at: Vec3): T {
        val mob = type.create(helper.level, EntitySpawnReason.COMMAND)!!
        mob.snapTo(helper.absoluteVec(at))
        mob.isNoAi = true
        helper.assertTrue(helper.level.addFreshEntity(mob), "${type.toShortString()} added")
        return mob
    }
}
