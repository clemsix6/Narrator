package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.difficulty.Difficulty
import io.github.clemsix6.narrator2.gametest.GuardPolicyGameTests.Companion.mockPlayer
import io.github.clemsix6.narrator2.gametest.GuardPolicyGameTests.Companion.removePlayers
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.BowItem
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import java.util.UUID

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
        val skeleton = spawn(helper, EntityTypes.SKELETON, Vec3(5.5, 1.0, 1.5))
        zombie.health = zombie.maxHealth / 2
        val changed = before.copy(mobHealth = 3.0, mobSpeed = emptyMap(), mobHealthByType = emptyMap())
        try {
            val reconciled = Difficulty.reload(server, changed)
            helper.assertTrue(reconciled >= 3, "the zombie, the creeper and the skeleton are among the reconciled creatures: $reconciled")
            helper.assertValueEqual(60f, zombie.maxHealth, "the zombie's maximum health at x3")
            helper.assertValueEqual(30f, zombie.health, "the same share of its health")
            helper.assertValueEqual(1.0, speedFactor(creeper), "a type out of the table: vanilla speed")
            helper.assertValueEqual(60f, skeleton.maxHealth, "a skeleton out of mobHealthByType: the hostile factor")
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
        helper.assertValueEqual(5f, skeleton.maxHealth, "the skeleton frail again")
        listOf(zombie, creeper, skeleton).forEach { it.discard() }
        helper.succeed()
    }

    /**
     * Skeletons are glass cannons (M7-SKELETONS): a fresh skeleton has a quarter of its vanilla health whatever
     * `mobHealth`, a wither skeleton (out of the tables) the usual hostile factor. Each against a still iron golem in
     * its own walled lane, a skeleton shoots about twice as often as a wither skeleton given a bow (same goal, same
     * intervals); the shortest time between two arrows of each is compared, so a moment out of sight does not count.
     * Its bow, drawn half as long, still gives full power.
     */
    @GameTest(maxTicks = FIRE_RATE_TICKS + 20)
    fun skeletonsAreFrailAndShootTwiceAsFast(helper: GameTestHelper) {
        val config = Difficulty.config!!
        helper.assertValueEqual(1.5, config.mobHealth, "the hostile factor of the defaults")
        for (x in 0..7) for (y in 1..4) for (z in 3..4) helper.setBlock(x, y, z, Blocks.STONE)
        val skeleton = archer(helper, EntityTypes.SKELETON, Vec3(1.5, 1.0, 1.5), target = Vec3(6.5, 1.0, 1.5))
        val witherSkeleton = archer(helper, EntityTypes.WITHER_SKELETON, Vec3(1.5, 1.0, 6.5), target = Vec3(6.5, 1.0, 6.5))
        helper.assertValueEqual(5f, skeleton.maxHealth, "a skeleton at a quarter of its vanilla health")
        helper.assertValueEqual(5f, skeleton.health, "a fresh skeleton is full")
        helper.assertValueEqual(30f, witherSkeleton.maxHealth, "a wither skeleton at the hostile factor")
        helper.assertValueEqual(1f, BowItem.getPowerForTime(Difficulty.drawTicks(skeleton, 10)), "drawn 10 ticks: full power")

        val shots = HashMap<Entity, MutableList<Long>>()
        val seen = HashSet<UUID>()
        helper.onEachTick {
            for (arrow in helper.getEntities(EntityTypes.ARROW)) {
                if (seen.add(arrow.uuid)) arrow.owner?.let { shots.getOrPut(it, ::mutableListOf).add(helper.tick) }
            }
        }
        helper.runAfterDelay(FIRE_RATE_TICKS.toLong()) {
            val fast = shortestGap(shots[skeleton])
            val vanilla = shortestGap(shots[witherSkeleton])
            helper.assertTrue(
                fast != null && vanilla != null && fast.toDouble() / vanilla in 0.4..0.6,
                "a skeleton shoots every $fast ticks, a wither skeleton every $vanilla (${helper.level.difficulty}): " +
                    shots.mapKeys { it.key.type.toShortString() },
            )
            println("[M7-SKELETONS] a skeleton shoots every $fast ticks, a wither skeleton every $vanilla (${helper.level.difficulty})")
            listOf(skeleton, witherSkeleton).forEach { it.target?.discard(); it.discard() }
            helper.getEntities(EntityTypes.ARROW).forEach(Entity::discard)
            helper.succeed()
        }
    }

    /** A persistent [type] holding a bow and a helmet against the sun, aiming at a still iron golem at [target]. */
    private fun <T : Mob> archer(helper: GameTestHelper, type: EntityType<T>, at: Vec3, target: Vec3): T {
        val golem = spawn(helper, EntityTypes.IRON_GOLEM, target).apply { setPersistenceRequired() }
        return spawn(helper, type, at).apply {
            setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.BOW))
            setItemSlot(EquipmentSlot.HEAD, ItemStack(Items.DIAMOND_HELMET))
            setPersistenceRequired()
            isNoAi = false
            this.target = golem
        }
    }

    /** The shortest time between two consecutive arrows of [ticks]; null under two arrows. */
    private fun shortestGap(ticks: List<Long>?): Long? = ticks?.zipWithNext { a, b -> b - a }?.minOrNull()

    /** [mob]'s movement speed over its base one. */
    private fun speedFactor(mob: Mob): Double = mob.getAttributeValue(Attributes.MOVEMENT_SPEED) / mob.getAttributeBaseValue(Attributes.MOVEMENT_SPEED)

    private fun <T : Mob> spawn(helper: GameTestHelper, type: EntityType<T>, at: Vec3): T {
        val mob = type.create(helper.level, EntitySpawnReason.COMMAND)!!
        mob.snapTo(helper.absoluteVec(at))
        mob.isNoAi = true
        helper.assertTrue(helper.level.addFreshEntity(mob), "${type.toShortString()} added")
        return mob
    }

    private companion object {
        /** How long the archers shoot: three or four arrows of the slowest. */
        const val FIRE_RATE_TICKS = 240
    }
}
