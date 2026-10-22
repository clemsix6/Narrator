package io.github.clemsix6.narrator2.gametest

import com.anthropic.models.messages.ToolResultBlockParam
import io.github.clemsix6.narrator2.guard.GuardStates
import io.github.clemsix6.narrator2.guard.GuardsImpl
import io.github.clemsix6.narrator2.guard.NarratorMob
import io.github.clemsix6.narrator2.guard.NarratorMobs
import io.github.clemsix6.narrator2.guard.PunishmentCategory
import io.github.clemsix6.narrator2.guard.Rules
import io.github.clemsix6.narrator2.guard.SpawnPolicy
import io.github.clemsix6.narrator2.guard.Verdict
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.testing.FakeGuards
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolResult
import io.github.clemsix6.narrator2.tool.ToolRunner
import io.github.clemsix6.narrator2.tool.ToolSet
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.tool.action.EquipmentSpec
import io.github.clemsix6.narrator2.tool.action.ExplodeSpec
import io.github.clemsix6.narrator2.tool.action.ExplosionArgs
import io.github.clemsix6.narrator2.tool.action.ExplosionTool
import io.github.clemsix6.narrator2.tool.action.FireworksArgs
import io.github.clemsix6.narrator2.tool.action.FireworksTool
import io.github.clemsix6.narrator2.tool.action.ItemSpecs
import io.github.clemsix6.narrator2.tool.action.LightningArgs
import io.github.clemsix6.narrator2.tool.action.LightningTool
import io.github.clemsix6.narrator2.tool.action.MobAttribute
import io.github.clemsix6.narrator2.tool.action.MobAttributeKey
import io.github.clemsix6.narrator2.tool.action.ParticleKey
import io.github.clemsix6.narrator2.tool.action.ParticlesArgs
import io.github.clemsix6.narrator2.tool.action.ParticlesTool
import io.github.clemsix6.narrator2.tool.action.SetTimeArgs
import io.github.clemsix6.narrator2.tool.action.SetTimeTool
import io.github.clemsix6.narrator2.tool.action.SetWeatherArgs
import io.github.clemsix6.narrator2.tool.action.SetWeatherTool
import io.github.clemsix6.narrator2.tool.action.SpawnArgs
import io.github.clemsix6.narrator2.tool.action.SpawnTool
import io.github.clemsix6.narrator2.tool.action.TimeOfDay
import io.github.clemsix6.narrator2.tool.action.WeatherKind
import io.github.clemsix6.narrator2.tool.action.WorldLimits
import io.github.clemsix6.narrator2.tool.action.WornItem
import io.github.clemsix6.narrator2.tool.of
import io.github.clemsix6.narrator2.tool.toolUse
import kotlinx.coroutines.runBlocking
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.resources.Identifier
import net.minecraft.util.ProblemReporter
import net.minecraft.world.InteractionHand
import net.minecraft.world.clock.ClockTimeMarkers
import net.minecraft.world.entity.ConversionParams
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.monster.zombie.Zombie
import net.minecraft.world.entity.projectile.FireworkRocketEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LightningRodBlock
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.storage.TagValueOutput
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.time.Clock
import java.util.UUID
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min

/**
 * In-game tests of the actions on the world (M1_M2 §9.4, §17.3): creatures summoned, marked, equipped,
 * reshaped, raining or flying, exploding without breaking anything, with the mounts and riders the game
 * gives them; lightning that never touches copper nor starts a fire; explosions that break nothing and ask
 * the guards about each player near; fireworks that burst away from the players; particles; weather and
 * time with their limits.
 *
 * Each test builds on its own floor (y = 0) inside its 8 × 8 area, with its own [TestPlayer]s, and removes
 * what it summoned. Explosions go off in the middle of the area: the nearest other test is more than
 * 8 blocks away (5 blocks between areas), out of reach of the hostile check. Weather and time are put back
 * within the same tick, so the tests running side by side never see them change. The caps are read from
 * the guards, whatever the chaos level of the test server.
 */
class WorldActionGameTests {

    // ── spawn ───────────────────────────────────────────────────────────────────────────────────

    @GameTest
    fun spawnSummonsMarkedEquippedCreaturesAroundThePlayer(helper: GameTestHelper) = withPlayer(helper) { bob ->
        val result = call(
            helper, SpawnTool(),
            SpawnArgs(
                entity = "zombie", count = 3, near = bob.name, distance = 3, name = "§6Gérard", lifetimeMinutes = 2,
                equipment = EquipmentSpec(head = WornItem("golden_helmet"), mainhand = WornItem("minecraft:diamond_sword")),
                attributes = listOf(MobAttribute(MobAttributeKey.MOVEMENT_SPEED, 2.0)),
            ),
        )
        val zombies = summonedFor(helper, bob.uuid)
        try {
            helper.assertTrue(result.text.startsWith("ok: 3 minecraft:zombie summoned around ${bob.name}, until "), result.text)
            helper.assertTrue("named \"Gérard\", movement_speed x2, equipped (head golden_helmet, mainhand minecraft:diamond_sword)" in result.text, result.text)
            helper.assertTrue(result.text.endsWith("a punishment of ${bob.name}"), "a zombie is a monster: ${result.text}")
            helper.assertEquals(3, zombies.size, "zombies summoned for ${bob.name}")
            val now = helper.narrator().clock.millis()
            for (zombie in zombies) {
                helper.assertTrue(NarratorMobs.isNarrators(zombie) && zombie.isPersistenceRequired, "tagged and persistent")
                val mark = zombie.getAttached(NarratorMob.TYPE)!!
                helper.assertTrue(mark.harmful && mark.expiresAt in now + 110_000..now + 120_000, "due in 2 minutes, a punishment: $mark")
                helper.assertEquals("Gérard", zombie.customName?.string, "the name, without its § code")
                helper.assertTrue(zombie.mainHandItem.`is`(Items.DIAMOND_SWORD) && zombie.getItemBySlot(EquipmentSlot.HEAD).`is`(Items.GOLDEN_HELMET), "equipped")
                helper.assertEquals(0f, zombie.dropChances.byEquipment(EquipmentSlot.MAINHAND), "the sword never drops")
                helper.assertTrue(
                    zombie.getAttribute(Attributes.MOVEMENT_SPEED)!!.hasModifier(Identifier.fromNamespaceAndPath("narrator2", "spawn/movement_speed")),
                    "twice as fast",
                )
                helper.assertTrue(hypot(zombie.x - bob.live.x, zombie.z - bob.live.z) <= 3.9, "within 3 blocks of ${bob.name}: ${zombie.position()}")
                helper.assertEquals(1.0, zombie.y - helper.absolutePos(BlockPos.ZERO).y, "standing on the floor")
            }
            helper.assertTrue(guards(helper).activeOn(bob.uuid).any { it.what == "3 minecraft:zombie" && it.harmful }, "counted for ${bob.name}")
            helper.assertEquals(CALLER.turnId, GuardStates.read(bob.live).episode?.act, "the turn's punishment is open")
            helper.assertTrue(guards(helper).death.isArmed(bob.live), "the death guard is armed")
        } finally {
            zombies.forEach { it.discard() }
        }
    }

    /**
     * A character in costume (M7-CREATURES), as every vanilla client shows it: its name, dyed leather, a player's face,
     * its size, its glow. Killed by a player, it drops none of its costume, unless a drop chance is asked.
     */
    @GameTest
    fun aCostumedCreatureWearsItsCostumeAndDropsNoneOfIt(helper: GameTestHelper) = withPlayer(helper) { bob ->
        fun huissier(dropChance: Double) = SpawnArgs(
            entity = "zombie", near = bob.name, distance = 3, name = "Huissier du Lit Jaune", lifetimeMinutes = 1, glowing = true,
            attributes = listOf(MobAttribute(MobAttributeKey.SCALE, 1.5)), dropChance = dropChance,
            equipment = EquipmentSpec(
                head = WornItem("player_head", skin = "clemsix6"),
                chest = WornItem("leather_chestplate", color = "#F9D71C"),
                mainhand = WornItem("paper", name = "Mandat de saisie", glint = true),
            ),
        )

        /** The costume pieces lying around the test's area. */
        fun dropped(): List<ItemEntity> = helper.level.getEntitiesOfClass(ItemEntity::class.java, AABB(helper.absolutePos(BlockPos.ZERO)).inflate(16.0)) {
            it.item.`is`(Items.LEATHER_CHESTPLATE) || it.item.`is`(Items.PLAYER_HEAD) || it.item.`is`(Items.PAPER)
        }

        /** Kills [mob] as [bob] would. */
        fun slay(mob: Mob) = mob.hurtServer(helper.level, helper.level.damageSources().playerAttack(bob.live), 1_000f)

        val result = call(helper, SpawnTool(), huissier(dropChance = 0.0))
        try {
            helper.assertTrue(
                "equipped (head player_head of clemsix6, chest leather_chestplate #F9D71C, mainhand paper \"Mandat de saisie\" shimmering), glowing;" in result.text,
                result.text,
            )
            val one = summonedFor(helper, bob.uuid).single()
            helper.assertEquals("Huissier du Lit Jaune", one.customName?.string, "named")
            helper.assertEquals(0xF9D71C, one.getItemBySlot(EquipmentSlot.CHEST).get(DataComponents.DYED_COLOR)?.rgb(), "yellow leather")
            helper.assertEquals("clemsix6", one.getItemBySlot(EquipmentSlot.HEAD).get(DataComponents.PROFILE)?.name()?.orElse(null), "clemsix6's face")
            helper.assertEquals(1.5f, one.scale, "half as big again")
            helper.assertTrue(one.hasGlowingTag(), "glowing")
            slay(one)
            helper.assertTrue(one.isDeadOrDying && dropped().isEmpty(), "killed by ${bob.name}, it drops none of its costume: ${dropped().map { it.item }}")

            helper.assertTrue("each piece drops at 100% when a player kills one" in call(helper, SpawnTool(), huissier(dropChance = 1.0)).text, "a trophy asked")
            slay(summonedFor(helper, bob.uuid).single { it.isAlive })
            helper.assertEquals(3, dropped().size, "with a drop chance of 1, the whole costume drops: ${dropped().map { it.item }}")
        } finally {
            summonedFor(helper, bob.uuid).forEach { it.discard() }
            dropped().forEach { it.discard() }
        }
    }

    /** "Ultra-fast creepers": three times as fast and after the player; their own blast is capped by the chaos level. */
    @GameTest
    fun aCreepersBlastIsCappedByTheChaosLevel(helper: GameTestHelper) = withPlayer(helper) { bob ->
        val result = call(
            helper, SpawnTool(),
            SpawnArgs(entity = "creeper", near = bob.name, distance = 3, target = true, attributes = listOf(MobAttribute(MobAttributeKey.MOVEMENT_SPEED, 3.0))),
        )
        val creeper = summonedFor(helper, bob.uuid).singleOrNull()
        try {
            helper.assertEquals(ToolStatus.OK, result.status, result.text)
            helper.assertTrue(creeper != null, "one creeper: ${result.text}")
            val saved = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.level.registryAccess())
            creeper!!.saveWithoutId(saved)
            val cap = floor(min(guards(helper).caps().explosionPower, 3.0)).toInt()
            helper.assertEquals(cap.toByte(), saved.buildResult().getByteOr("ExplosionRadius", (-1).toByte()), "the creeper's blast radius at this chaos level")
            val speed = creeper.getAttribute(Attributes.MOVEMENT_SPEED)!!
            helper.assertTrue(speed.value >= speed.baseValue * 2.9, "three times as fast: ${speed.value} for ${speed.baseValue}")
        } finally {
            creeper?.discard()
        }
    }

    @GameTest
    fun spawnRefusesWhatItMayNotSummon(helper: GameTestHelper) = withPlayer(helper) { bob ->
        val tool = SpawnTool()
        val enderman = call(helper, tool, SpawnArgs(entity = "enderman", near = bob.name))
        helper.assertTrue(enderman.text.startsWith("DENIED (${SpawnPolicy.FORBIDDEN_CREATURE}): minecraft:enderman is not summoned"), enderman.text)
        val tnt = call(helper, tool, SpawnArgs(entity = "tnt", near = bob.name))
        helper.assertTrue(tnt.status == ToolStatus.DENIED && "not a creature" in tnt.text, tnt.text)
        val typo = call(helper, tool, SpawnArgs(entity = "zombi", near = bob.name))
        helper.assertTrue(typo.text.startsWith("Invalid arguments: unknown creature \"zombi\"; closest ids: zombie"), typo.text)
        val block = call(helper, tool, SpawnArgs(entity = "zombie", near = bob.name, equipment = EquipmentSpec(mainhand = WornItem("command_block"))))
        helper.assertTrue(block.text.startsWith("DENIED (${ItemSpecs.FORBIDDEN_ITEM}): command_block is never given"), block.text)
        val cow = call(helper, tool, SpawnArgs(entity = "cow", near = bob.name, attributes = listOf(MobAttribute(MobAttributeKey.ATTACK_DAMAGE, 2.0))))
        helper.assertEquals("Invalid arguments: minecraft:cow has no attack_damage; leave that change out", cow.text, "a cow cannot hit harder")
        helper.assertTrue(summonedFor(helper, bob.uuid).isEmpty(), "nothing was summoned")
        helper.assertEquals(null, GuardStates.read(bob.live).episode, "no punishment opened")
    }

    /** A rain of chickens: they appear high above the ground, with no fall damage, and summoning them is no punishment. */
    @GameTest
    fun chickensRainFromTheSky(helper: GameTestHelper) = withPlayer(helper) { bob ->
        val result = call(helper, SpawnTool(), SpawnArgs(entity = "chicken", count = 4, near = bob.name, distance = 2, height = 5, lifetimeMinutes = 1))
        val chickens = summonedFor(helper, bob.uuid)
        try {
            helper.assertEquals(ToolStatus.OK, result.status, result.text)
            helper.assertTrue("up to 5 blocks in the air" in result.text && "punishment" !in result.text, result.text)
            helper.assertEquals(4, chickens.size, "chickens")
            val floor = helper.absolutePos(BlockPos.ZERO).y
            for (chicken in chickens) {
                helper.assertTrue(chicken.y >= floor + 4.0, "in the air: ${chicken.y - floor} blocks above the floor")
                helper.assertEquals(0.0, chicken.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER), "no fall damage")
            }
            helper.assertEquals(null, GuardStates.read(bob.live).episode, "not a punishment")
        } finally {
            chickens.forEach { it.discard() }
        }
    }

    /**
     * The owner's scene, "cows that fly up and explode", through the tool runner with the model's JSON: the
     * cows are thrown up, then each vanishes in a harmless blast in the air. Nothing is broken, nobody is hurt,
     * and nothing counts as a punishment.
     */
    @GameTest(maxTicks = 200)
    fun cowsFlyUpAndExplodeHarmlessly(helper: GameTestHelper) {
        val scene = Scene(helper)
        val bob = TestPlayer(helper)
        helper.runAfterDelay(199) { bob.close() }
        bob.standAt(BlockPos(1, 1, 1))
        bob.makeVulnerable()
        val runtime = helper.narrator()
        val input = """{"entity":"cow","count":2,"near":"${bob.name}","distance":2,"name":"Marguerite","launch":1.5,""" +
            """"explode":{"after_seconds":1,"power":2,"hurts":false}}"""
        val result = runBlocking {
            ToolRunner(runtime).runAll(listOf(toolUse("toolu_cows", "spawn", input)), ToolSet.of(listOf(SpawnTool())), CALLER, 0)
        }.single().text()
        val cows = summonedFor(helper, bob.uuid)
        helper.assertTrue(result.startsWith("ok: 2 minecraft:cow summoned around ${bob.name}"), result)
        helper.assertTrue("thrown up at 1.5, exploding after 1s (power 2, harmless)" in result, result)
        helper.assertEquals(2, cows.size, "cows")
        val startY = cows.map { it.y }
        helper.assertTrue(cows.all { it.deltaMovement.y == 1.5 }, "thrown up")
        helper.startSequence()
            .thenWaitUntil { helper.assertTrue(cows.zip(startY).all { (cow, y) -> cow.isRemoved || cow.y > y + 3 }, "the cows fly up") }
            .thenWaitUntil { helper.assertTrue(cows.all { it.isRemoved }, "the cows exploded") }
            .thenExecute {
                scene.assertUntouched("the cows' blasts", monsterHurt = false)
                helper.assertEquals(20f, bob.live.health, "${bob.name}'s health: a harmless blast")
                helper.assertEquals(null, GuardStates.read(bob.live).episode, "no punishment")
                bob.close()
                scene.clear()
            }
            .thenSucceed()
    }

    /** A creature on its fuse goes off like the Narrator's explosions: it hurts a monster next to it, and breaks nothing. */
    @GameTest(maxTicks = 200)
    fun aCreatureOnItsFuseBreaksNothing(helper: GameTestHelper) {
        val scene = Scene(helper)
        val bob = TestPlayer(helper)
        helper.runAfterDelay(199) { bob.close() }
        bob.standAt(BlockPos(4, 1, 4))
        val guards = guards(helper)
        guards.setProtected(bob.uuid, bob.name, on = true) // no hostile check: the blast spares him anyway
        val result = call(helper, SpawnTool(), SpawnArgs(entity = "pig", near = bob.name, distance = 2, explode = ExplodeSpec(afterSeconds = 1, power = 2.0)))
        val pig = summonedFor(helper, bob.uuid).single()
        helper.assertTrue("exploding after 1s (power 2)" in result.text, result.text)
        helper.startSequence()
            .thenWaitUntil { helper.assertTrue(pig.isRemoved, "the pig went off") }
            .thenExecute {
                scene.assertUntouched("the pig's blast", monsterHurt = true)
                helper.assertEquals(20f, bob.live.health, "the protected player is spared")
                guards.setProtected(bob.uuid, bob.name, on = false)
                bob.close()
                scene.clear()
            }
            .thenSucceed()
    }

    /**
     * A creature on its fuse goes like any creature of the Narrator's (M2 review, finding 4): Bob, who rides
     * it, is set down first, under the death guard and with no fall damage, and what he gave it (a saddle, and
     * the lead he holds it by) comes back to him.
     */
    @GameTest(maxTicks = 200)
    fun aCreatureOnItsFuseSetsDownItsRiderAndGivesBackWhatItWasGiven(helper: GameTestHelper) {
        floor(helper)
        val bob = TestPlayer(helper)
        helper.runAfterDelay(199) { bob.close() }
        bob.standAt(BlockPos(4, 1, 4))
        bob.makeVulnerable()
        val guards = guards(helper)
        val result = call(helper, SpawnTool(), SpawnArgs(entity = "pig", near = bob.name, distance = 2, explode = ExplodeSpec(afterSeconds = 1, power = 1.0, hurts = false)))
        val pig = summonedFor(helper, bob.uuid).single()
        helper.assertEquals(ToolStatus.OK, result.status, result.text)
        bob.live.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.SADDLE))
        helper.assertTrue(bob.live.interactOn(pig, InteractionHand.MAIN_HAND, Vec3.ZERO).consumesAction(), "Bob saddles the pig")
        pig.setLeashedTo(bob.live, true)
        helper.assertTrue(bob.live.startRiding(pig, true, true), "Bob rides the pig")
        helper.startSequence()
            .thenWaitUntil { helper.assertTrue(pig.isRemoved, "the pig went off") }
            .thenExecute {
                helper.assertEquals(null, bob.live.vehicle, "Bob is off the pig")
                helper.assertTrue(guards.death.isArmed(bob.live), "under the death guard")
                helper.assertEquals(0.0, bob.live.getAttributeValue(Attributes.FALL_DAMAGE_MULTIPLIER), "no fall damage")
                helper.assertEquals(1, GuardGameTests.count(bob.live, Items.SADDLE), "the saddle is back in Bob's inventory")
                helper.assertEquals(1, GuardGameTests.count(bob.live, Items.LEAD), "and the lead")
                guards.attributes.resetAll(bob.live)
                bob.close()
            }
            .thenSucceed()
    }

    /**
     * Summoning a creature that can hurt is a punishment, monster or not (M2 review, finding 9): a polar bear
     * opens one against Bob, bees summoned by another turn are refused (`already_punished`), and cows, harmless,
     * are no punishment even then. Every harmless creature exists, may be summoned, and is not forbidden.
     */
    @GameTest
    fun everyCreatureThatCanHurtIsAPunishment(helper: GameTestHelper) = withPlayer(helper) { bob ->
        val tool = SpawnTool()
        try {
            val bear = call(helper, tool, SpawnArgs(entity = "polar_bear", near = bob.name, distance = 3))
            helper.assertTrue(bear.status == ToolStatus.OK && bear.text.endsWith("a punishment of ${bob.name}"), bear.text)
            helper.assertEquals(CALLER.turnId, GuardStates.read(bob.live).episode?.act, "the turn's punishment is open")
            val bees = call(helper, tool, SpawnArgs(entity = "bee", count = 2, near = bob.name, distance = 3), OTHER_TURN)
            helper.assertTrue(bees.text.startsWith("DENIED (${Rules.ALREADY_PUNISHED}):"), bees.text)
            val cows = call(helper, tool, SpawnArgs(entity = "cow", near = bob.name, distance = 3), OTHER_TURN)
            helper.assertTrue(cows.status == ToolStatus.OK && "punishment" !in cows.text, cows.text)
            for (id in SpawnPolicy.HARMLESS) {
                val type = BuiltInRegistries.ENTITY_TYPE.get(Identifier.parse(id)).orElse(null)?.value()
                helper.assertTrue(type != null && SpawnPolicy.isHarmless(type), "$id exists")
                helper.assertEquals(Verdict.Allowed, SpawnPolicy.check(type!!, helper.level), "$id may be summoned")
            }
        } finally {
            summonedFor(helper, bob.uuid).forEach { it.discard() }
        }
    }

    /**
     * The Narrator's zombies call no reinforcements (M2 review, finding 9): their chance is 0, and stays 0 when
     * one converts, although the conversion rolls it again. The game's own zombies roll one.
     */
    @GameTest
    fun theNarratorsZombiesCallNoReinforcements(helper: GameTestHelper) = withPlayer(helper) { bob ->
        val result = call(helper, SpawnTool(), SpawnArgs(entity = "zombie", count = 2, near = bob.name, distance = 3))
        val zombies = summonedFor(helper, bob.uuid)
        try {
            helper.assertEquals(ToolStatus.OK, result.status, result.text)
            helper.assertEquals(2, zombies.size, "zombies summoned")
            zombies.forEach { assertNoReinforcements(helper, it) }
            // As Zombie.convertToZombieType does: the new creature's chance is rolled, then it enters the level.
            val drowned = zombies[0].convertTo(EntityTypes.DROWNED, ConversionParams.single(zombies[0], true, true)) { converted ->
                val chance = converted.getAttribute(Attributes.SPAWN_REINFORCEMENTS_CHANCE)!!
                chance.baseValue = 0.1
                chance.addPermanentModifier(AttributeModifier(Identifier.withDefaultNamespace("leader_zombie_bonus"), 0.5, AttributeModifier.Operation.ADD_VALUE))
            }!!
            assertNoReinforcements(helper, drowned)

            val ordinary = EntityTypes.ZOMBIE.create(helper.level, EntitySpawnReason.COMMAND)!!
            val adult = Zombie.ZombieGroupData(false, false) // no chicken jockey: the game would add a chicken to the world
            ordinary.finalizeSpawn(helper.level, helper.level.getCurrentDifficultyAt(bob.live.blockPosition()), EntitySpawnReason.COMMAND, adult)
            helper.assertTrue(ordinary.getAttributeValue(Attributes.SPAWN_REINFORCEMENTS_CHANCE) > 0.0, "the game's zombies roll a chance")
        } finally {
            summonedFor(helper, bob.uuid).forEach { it.discard() }
        }
    }

    /**
     * The mounts and riders the game gives a creature (a zombie's new chicken, already in the world as
     * `Zombie.finalizeSpawn` leaves it; a spider jockey's skeleton, not yet in it) are the Narrator's too:
     * marked with the same expiry, registered, counted for the player.
     */
    @GameTest
    fun jockeysAreTheNarratorsToo(helper: GameTestHelper) = withPlayer(helper) { bob ->
        val level = helper.level
        val at = helper.absoluteVec(Vec3(4.5, 1.0, 4.5))
        val chicken = EntityTypes.CHICKEN.create(level, EntitySpawnReason.JOCKEY)!!.apply { snapTo(at) }
        level.addFreshEntity(chicken)
        val zombie = EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND)!!.apply { snapTo(at) }
        zombie.startRiding(chicken, true, true)
        val spider = EntityTypes.SPIDER.create(level, EntitySpawnReason.COMMAND)!!.apply { snapTo(at.add(2.0, 0.0, 0.0)) }
        val skeleton = EntityTypes.SKELETON.create(level, EntitySpawnReason.JOCKEY)!!.apply { snapTo(at.add(2.0, 0.0, 0.0)) }
        skeleton.startRiding(spider, true, true)
        val all = listOf<Entity>(chicken, zombie, spider, skeleton)
        try {
            val guards = guards(helper)
            val expiresAt = helper.narrator().clock.millis() + 60_000
            helper.assertTrue(SpawnTool.enter(guards, level, zombie, expiresAt, bob.uuid, harmful = true), "the zombie enters")
            helper.assertTrue(SpawnTool.enter(guards, level, spider, expiresAt, bob.uuid, harmful = true), "the spider enters")
            for (entity in all) {
                helper.assertTrue(level.getEntity(entity.uuid) === entity, "${entity.type.toShortString()} is in the world")
                helper.assertTrue(NarratorMobs.isNarrators(entity), "${entity.type.toShortString()} is tagged")
                helper.assertEquals(expiresAt, entity.getAttached(NarratorMob.TYPE)?.expiresAt, "${entity.type.toShortString()}'s expiry")
            }
            helper.assertTrue(zombie.vehicle === chicken && skeleton.vehicle === spider, "still riding")
            val counted = guards.activeOn(bob.uuid).map { it.what }.toSet()
            helper.assertEquals(
                setOf("1 minecraft:chicken", "1 minecraft:zombie", "1 minecraft:spider", "1 minecraft:skeleton"),
                counted,
                "registered and counted, the mount the game had already added included",
            )
        } finally {
            all.forEach { it.discard() }
        }
    }

    // ── lightning ───────────────────────────────────────────────────────────────────────────────

    /**
     * A bolt aimed at copper or at a lightning rod strikes the air above them: even a visual bolt scrapes
     * copper and powers a rod. It starts no fire on a wooden floor. Then, as a counter-proof, a visual bolt
     * of the game's own lands on the copper and scrapes it.
     */
    @GameTest(maxTicks = 200)
    fun lightningNeverChangesCopperNorStartsFires(helper: GameTestHelper) {
        for (x in 0..7) for (z in 0..7) helper.setBlock(x, 0, z, Blocks.OAK_PLANKS)
        val copper = BlockPos(4, 0, 4)
        val exposed = Blocks.COPPER_BLOCK.weathering().exposed()
        helper.setBlock(copper, exposed)
        val rod = BlockPos(2, 1, 2)
        helper.setBlock(rod, Blocks.LIGHTNING_ROD.weathering().unaffected())
        val tool = LightningTool()
        for (target in listOf(copper, rod)) {
            val at = helper.absolutePos(target)
            val result = call(helper, tool, LightningArgs(x = at.x, y = at.y, z = at.z))
            helper.assertEquals("ok: lightning struck at x ${at.x}, y ${at.y}, z ${at.z}, harmless", result.text, "the tool's answer")
        }
        helper.startSequence()
            .thenIdle(20)
            .thenExecute {
                helper.assertBlockPresent(exposed, copper)
                helper.assertFalse(helper.getBlockState(rod).getValue(LightningRodBlock.POWERED), "the rod was never powered")
                for (x in 0..7) for (y in 1..3) for (z in 0..7) {
                    helper.assertFalse(helper.getBlockState(BlockPos(x, y, z)).`is`(Blocks.FIRE), "no fire at $x $y $z")
                }
                val bolt = EntityTypes.LIGHTNING_BOLT.create(helper.level, EntitySpawnReason.TRIGGERED)!!
                bolt.snapTo(helper.absoluteVec(Vec3(4.5, 1.0, 4.5)))
                bolt.setVisualOnly(true)
                helper.level.addFreshEntity(bolt)
            }
            .thenIdle(20)
            .thenExecute { helper.assertBlockNotPresent(exposed, copper) }
            .thenSucceed()
    }

    @GameTest
    fun lightningHurtsTheStruckPlayerAsAPunishment(helper: GameTestHelper) = withPlayer(helper) { bob ->
        bob.makeVulnerable()
        val tool = LightningTool()
        val caps = guards(helper).caps()
        val result = call(helper, tool, LightningArgs(player = bob.name, damageHearts = 1.0))
        helper.assertEquals("ok: lightning struck ${bob.name} for 1 hearts (health 20 → 18)", result.text, "the tool's answer")
        helper.assertEquals(18f, bob.live.health, "1 heart of damage")
        helper.assertEquals(CALLER.turnId, GuardStates.read(bob.live).episode?.act, "a punishment")
        helper.assertTrue(guards(helper).death.isArmed(bob.live), "the death guard is armed")
        val tooMuch = call(helper, tool, LightningArgs(player = bob.name, damageHearts = caps.lightningHearts + 1))
        helper.assertEquals(ToolStatus.INVALID, tooMuch.status, tooMuch.text)
        val warning = call(helper, tool, LightningArgs(player = bob.name, offset = 3))
        helper.assertEquals("ok: lightning struck 3 blocks from ${bob.name}, harmless", warning.text, "a warning shot")
        helper.assertEquals(18f, bob.live.health, "a warning does not hurt")
    }

    // ── explosion ───────────────────────────────────────────────────────────────────────────────

    /**
     * An explosion in the middle of a scene breaks nothing and hurts the monster and the player near it, a
     * punishment of that player. Next to a player punished by another turn, it is refused; a harmless one
     * goes off and hurts nobody.
     */
    @GameTest
    fun theExplosionBreaksNothingAndAsksTheGuardsAboutEachPlayer(helper: GameTestHelper) {
        val scene = Scene(helper)
        withTestPlayers(helper, 2) { (bob, alice) ->
            bob.standAt(BlockPos(1, 1, 4))
            alice.standAt(BlockPos(7, 1, 4))
            bob.makeVulnerable()
            alice.makeVulnerable()
            val center = helper.absolutePos(BlockPos(4, 1, 4))
            val tool = ExplosionTool()
            val result = call(helper, tool, ExplosionArgs(x = center.x, y = center.y, z = center.z, power = 2.0))
            helper.assertTrue(result.text.startsWith("ok: explosion of power 2, at x ${center.x}, y ${center.y}, z ${center.z}: hurt: "), result.text)
            helper.assertTrue(result.text.endsWith("; no block touched") && "hurt: " in result.text, result.text)
            scene.assertUntouched("the explosion", monsterHurt = true)
            for (player in listOf(bob, alice)) {
                helper.assertTrue(player.live.health < 20f, "${player.name} is hurt: ${player.live.health}")
                helper.assertEquals(CALLER.turnId, GuardStates.read(player.live).episode?.act, "a punishment of ${player.name}")
                helper.assertTrue(guards(helper).death.isArmed(player.live), "${player.name}'s death guard is armed")
            }

            // Another turn: Alice is already being punished by this one.
            val health = listOf(bob.live.health, alice.live.health, scene.spider.health)
            val refused = call(helper, tool, ExplosionArgs(x = center.x, y = center.y, z = center.z, power = 2.0), OTHER_TURN)
            helper.assertTrue(refused.text.startsWith("DENIED (${Rules.ALREADY_PUNISHED}):"), refused.text)
            val harmless = call(helper, tool, ExplosionArgs(x = center.x, y = center.y, z = center.z, power = 2.0, hurts = false), OTHER_TURN)
            helper.assertTrue(harmless.text.startsWith("ok: ") && ": harmless; no block touched" in harmless.text, harmless.text)
            helper.assertEquals(health, listOf(bob.live.health, alice.live.health, scene.spider.health), "nobody hurt by the refused or the harmless one")
            scene.clear()
        }
    }

    // ── fireworks ───────────────────────────────────────────────────────────────────────────────

    @GameTest
    fun fireworksGoUpAwayFromEveryPlayer(helper: GameTestHelper) = withPlayer(helper) { bob ->
        floor(helper)
        bob.standAt(BlockPos(0, 1, 0))
        val tool = FireworksTool()
        val far = helper.absolutePos(BlockPos(7, 0, 7))
        val result = call(helper, tool, FireworksArgs(x = far.x, y = far.y, z = far.z, count = 4, colors = listOf("red", "#FFD700"), twinkle = true))
        val rockets = helper.level.getEntitiesOfClass(FireworkRocketEntity::class.java, AABB(helper.absolutePos(BlockPos.ZERO)).inflate(20.0)) { NarratorMobs.isNarrators(it) }
        try {
            helper.assertTrue(result.text.startsWith("ok: ") && "fireworks (large_ball; red, #FFD700) launched around x ${far.x}" in result.text, result.text)
            helper.assertTrue(rockets.isNotEmpty(), "rockets in the air")
            for (rocket in rockets) {
                val away = hypot(rocket.x - bob.live.x, rocket.z - bob.live.z)
                helper.assertTrue(away >= FireworksTool.CLEARANCE, "a rocket ${"%.1f".format(away)} blocks from ${bob.name}")
            }
            bob.standAt(BlockPos(4, 1, 4))
            val middle = helper.absolutePos(BlockPos(4, 0, 4))
            val crowded = call(helper, tool, FireworksArgs(x = middle.x, y = middle.y, z = middle.z))
            helper.assertTrue(crowded.text.startsWith("FAILED: no open ground at least 6 blocks from every player"), crowded.text)
            val color = call(helper, tool, FireworksArgs(player = bob.name, colors = listOf("sparkly")))
            helper.assertTrue(color.text.startsWith("Invalid arguments: unknown color \"sparkly\"; use #rrggbb or a dye name: white, orange"), color.text)
        } finally {
            rockets.forEach { it.discard() }
        }
    }

    // ── particles ───────────────────────────────────────────────────────────────────────────────

    @GameTest
    fun particlesReachThePlayersAround(helper: GameTestHelper) {
        val bob = TestPlayer(helper, keep = { it is ClientboundLevelParticlesPacket })
        try {
            bob.standAt(BlockPos(4, 1, 4))
            val result = call(helper, ParticlesTool(), ParticlesArgs(player = bob.name, particle = ParticleKey.HEART, count = 12, spread = 0.5))
            helper.assertTrue(result.text.startsWith("ok: 12 heart particles around ${bob.name}; seen by "), result.text)
            val packet = bob.packets.filterIsInstance<ClientboundLevelParticlesPacket>().single()
            helper.assertEquals(12, packet.count(), "particles sent")
            // A dimension in any form the model writes, the Oracle's `nether` included; always read back as the_nether.
            for (dimension in listOf("nether", "the_nether", "minecraft:the_nether", "Nether")) {
                val there = call(helper, ParticlesTool(), ParticlesArgs(x = 0, y = 64, z = 0, dimension = dimension, particle = ParticleKey.HEART))
                helper.assertTrue(there.status != ToolStatus.INVALID && "x 0, y 64, z 0 in the_nether" in there.text, "$dimension: ${there.text}")
            }
            val unknown = call(helper, ParticlesTool(), ParticlesArgs(x = 0, y = 64, z = 0, dimension = "moon", particle = ParticleKey.HEART))
            val listed = unknown.text.substringAfter("Invalid arguments: unknown dimension \"moon\"; dimensions: ").split(", ").toSet()
            helper.assertTrue(listed.containsAll(setOf("overworld", "the_nether", "the_end")), "unknown: ${unknown.text}")
            // Every particle of the list is a simple one of 26.3, and every attribute of spawn exists.
            ParticleKey.entries.forEach { it.type() }
            val missing = MobAttributeKey.entries.filterNot { BuiltInRegistries.ATTRIBUTE.containsKey(Identifier.withDefaultNamespace(it.id)) }
            helper.assertTrue(missing.isEmpty(), "attributes missing in 26.3: $missing")
        } finally {
            bob.close()
        }
        helper.succeed()
    }

    // ── set_weather, set_time ───────────────────────────────────────────────────────────────────

    /**
     * There is no thunderstorm to set (M2 review, finding 5: its bolts are real): the model's `thunder` is refused
     * and changes nothing, not even the cooldown. Weather and time change once in their cooldown; both are put back
     * at once, for the other tests.
     */
    @GameTest
    fun weatherAndTimeChangeOnceInTheirCooldown(helper: GameTestHelper) {
        val server = helper.level.server
        val limits = WorldLimits(helper.narrator().clock)
        val weather = server.weatherData
        val saved = listOf(weather.clearWeatherTime, weather.rainTime, weather.thunderTime)
        val (wasRaining, wasThundering) = weather.isRaining to weather.isThundering
        val clock = server.overworld().dimensionType().defaultClock().get()
        val ticks = server.clockManager().getInstance(clock).totalTicks()
        try {
            val thunder = runBlocking {
                ToolRunner(helper.narrator()).runAll(
                    listOf(toolUse("toolu_storm", "set_weather", """{"weather":"thunder","minutes":5}""")),
                    ToolSet.of(listOf(SetWeatherTool(limits))), CALLER, 0,
                )
            }.single().text()
            helper.assertEquals("Invalid arguments: weather must be one of: clear, rain (got \"thunder\")", thunder, "the model's thunder")
            helper.assertEquals(
                listOf(saved, wasRaining to wasThundering),
                listOf(listOf(weather.clearWeatherTime, weather.rainTime, weather.thunderTime), weather.isRaining to weather.isThundering),
                "the weather, untouched",
            )

            val rain = call(helper, SetWeatherTool(limits), SetWeatherArgs(WeatherKind.RAIN, minutes = 5))
            helper.assertTrue(rain.text.startsWith("ok: rain for 5m (until "), rain.text)
            helper.assertTrue(weather.isRaining && !weather.isThundering && weather.rainTime == 6_000, "5 minutes of rain, no storm")
            val again = call(helper, SetWeatherTool(limits), SetWeatherArgs(WeatherKind.CLEAR))
            helper.assertTrue(again.text.startsWith("DENIED (${WorldLimits.WEATHER_LIMIT}): the weather changed "), again.text)

            val night = call(helper, SetTimeTool(limits), SetTimeArgs(TimeOfDay.NIGHT))
            helper.assertTrue(night.text == "ok: it is now night" || night.text == "ok: it is already night; nothing changed", night.text)
            helper.assertTrue(server.clockManager().isAtTimeMarker(clock, ClockTimeMarkers.NIGHT), "it is night")
            if (night.text == "ok: it is now night") {
                val noon = call(helper, SetTimeTool(limits), SetTimeArgs(TimeOfDay.NOON))
                helper.assertTrue(noon.text.startsWith("DENIED (${WorldLimits.TIME_LIMIT}): the time changed "), noon.text)
            }
        } finally {
            server.setWeatherParameters(saved[0], if (wasThundering) saved[2] else saved[1], wasRaining, wasThundering)
            weather.rainTime = saved[1]
            weather.thunderTime = saved[2]
            server.clockManager().setTotalTicks(clock, ticks)
        }
        helper.succeed()
    }

    /**
     * A floor with things the Narrator's explosions must leave alone, around (4, 1, 4): planks, a chest with 3
     * diamonds, a diamond on the ground, an armor stand, a sheep; and a spider next to the middle.
     */
    private class Scene(private val helper: GameTestHelper) {
        val planks = listOf(BlockPos(2, 1, 4), BlockPos(4, 1, 6), BlockPos(6, 1, 5), BlockPos(6, 2, 5))
        private val chest = BlockPos(3, 1, 6)
        private val diamond = run {
            floor(helper)
            planks.forEach { helper.setBlock(it, Blocks.OAK_PLANKS) }
            helper.setBlock(chest, Blocks.CHEST)
            (helper.level.getBlockEntity(helper.absolutePos(chest)) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 3))
            helper.spawnItem(Items.DIAMOND, BlockPos(5, 1, 3))
        }
        private val stand = helper.spawn(EntityTypes.ARMOR_STAND, BlockPos(3, 1, 3))
        private val sheep = helper.spawnWithNoFreeWill(EntityTypes.SHEEP, BlockPos(2, 1, 6))
        val spider = helper.spawnWithNoFreeWill(EntityTypes.SPIDER, BlockPos(5, 1, 4))

        fun assertUntouched(by: String, monsterHurt: Boolean) {
            planks.forEach { helper.assertBlockPresent(Blocks.OAK_PLANKS, it) }
            helper.assertBlockPresent(Blocks.CHEST, chest)
            val stack = (helper.level.getBlockEntity(helper.absolutePos(chest)) as ChestBlockEntity).getItem(0)
            helper.assertTrue(stack.`is`(Items.DIAMOND) && stack.count == 3, "the chest keeps its diamonds after $by: $stack")
            helper.assertFalse(diamond.isRemoved, "the diamond on the ground survives $by")
            helper.assertFalse(stand.isRemoved, "the armor stand survives $by")
            helper.assertValueEqual(sheep.health, sheep.maxHealth, "the sheep's health after $by")
            if (monsterHurt) helper.assertTrue(spider.isDeadOrDying || spider.health < spider.maxHealth, "the spider is hurt by $by")
            for (x in 0..7) for (y in 1..3) for (z in 0..7) {
                helper.assertFalse(helper.getBlockState(BlockPos(x, y, z)).`is`(Blocks.FIRE), "no fire at $x $y $z after $by")
            }
        }

        /** Removes the scene's creatures and items. */
        fun clear() = helper.killAllEntities()
    }

    // ── For the mechanics engine (M4-03) ────────────────────────────────────────────────────────

    /**
     * The dry checks (`check`, M4 §4.3) with the game's registries: the answers `run` gives, for players who
     * need not be online, and nothing summoned, struck or blown up. The engine runs them when a rule is created.
     */
    @GameTest
    fun theDryChecksTouchNothing(helper: GameTestHelper) = withPlayer(helper) { bob ->
        val caps = guards(helper).caps()
        val spawn = SpawnTool()
        val typo = check(helper, spawn, SpawnArgs(entity = "zombi", near = "nobody_here"))
        helper.assertTrue(typo?.text?.startsWith("Invalid arguments: unknown creature \"zombi\"; closest ids: zombie") == true, "$typo")
        val enderman = check(helper, spawn, SpawnArgs(entity = "enderman", near = "nobody_here"))
        helper.assertTrue(enderman?.text?.startsWith("DENIED (${SpawnPolicy.FORBIDDEN_CREATURE}): minecraft:enderman is not summoned: ") == true, "$enderman")
        val block = check(helper, spawn, SpawnArgs(entity = "zombie", near = "nobody_here", equipment = EquipmentSpec(mainhand = WornItem("command_block"))))
        helper.assertTrue(block?.text?.startsWith("DENIED (${ItemSpecs.FORBIDDEN_ITEM}): command_block is never given") == true, "$block")
        val sword = check(helper, spawn, SpawnArgs(entity = "zombie", near = "nobody_here", equipment = EquipmentSpec(mainhand = WornItem("diamant_sword"))))
        helper.assertTrue(sword?.text?.startsWith("Invalid arguments: unknown item \"diamant_sword\"") == true, "$sword")
        helper.assertEquals(null, check(helper, spawn, SpawnArgs(entity = "zombie", near = bob.name, equipment = EquipmentSpec(head = WornItem("golden_helmet")))), "a zombie")

        val color = check(helper, FireworksTool(), FireworksArgs(x = 0, y = 64, z = 0, colors = listOf("chartreuse")))
        helper.assertTrue(color?.text?.startsWith("Invalid arguments: unknown color \"chartreuse\"; use #rrggbb or a dye name: ") == true, "$color")
        helper.assertEquals(null, check(helper, FireworksTool(), FireworksArgs(player = bob.name, colors = listOf("red", "#ffaa00"))), "colors")
        val power = check(helper, ExplosionTool(), ExplosionArgs(player = bob.name, power = caps.explosionPower + 0.5))
        helper.assertTrue(power?.text?.contains("an explosion has a power of at most") == true, "$power")
        val hearts = check(helper, LightningTool(), LightningArgs(player = bob.name, damageHearts = caps.lightningHearts + 1))
        helper.assertTrue(hearts?.text?.contains("lightning does at most") == true, "$hearts")

        helper.assertTrue(summonedFor(helper, bob.uuid).isEmpty(), "nothing was summoned")
        helper.assertEquals(20f, bob.live.health, "nothing struck")
        helper.assertEquals(null, GuardStates.read(bob.live).episode, "no punishment opened")
    }

    /**
     * Each hostile action tells the guards its category of punishment (M4 §6.1), with the caller of the act: here
     * an act of the engine (a rule's firing). Guards of their own ([FakeGuards]) allow and record every call; a
     * creature's fuse, later, goes off for the same act.
     */
    @GameTest(maxTicks = 200)
    fun hostileActionsTellTheirCategory(helper: GameTestHelper) {
        floor(helper)
        val fake = FakeGuards(clock = Clock.systemUTC())
        val services = TestServices(minecraft = helper.level.server, clock = Clock.systemUTC(), players = helper.narrator().players, guards = fake)
        val bob = TestPlayer(helper)
        helper.runAfterDelay(199) {
            summonedFor(helper, bob.uuid).forEach { it.discard() }
            bob.close()
        }
        bob.standAt(BlockPos(4, 1, 4))
        fun <A : Any> act(tool: NarratorTool<A>, args: A) {
            val result = runBlocking { tool.run(ToolContext(services, FIRING, services.clock, tool.timeoutMs), args) }
            helper.assertEquals(ToolStatus.OK, result.status, "${tool.name}: ${result.text}")
        }
        fun bobs() = fake.hostiles.filter { it.target == bob.uuid }
        act(SpawnTool(), SpawnArgs(entity = "zombie", near = bob.name, lifetimeMinutes = 1))
        act(LightningTool(), LightningArgs(player = bob.name, damageHearts = 1.0))
        act(ExplosionTool(), ExplosionArgs(player = bob.name, power = 1.0, offset = 0))
        // A harmless creature on its fuse: its summoning is no punishment, its blast is.
        act(SpawnTool(), SpawnArgs(entity = "pig", near = bob.name, distance = 2, lifetimeMinutes = 1, explode = ExplodeSpec(afterSeconds = 1, power = 1.0)))
        helper.assertEquals(
            listOf(PunishmentCategory.CREATURES, PunishmentCategory.LIGHTNING, PunishmentCategory.EXPLOSION),
            bobs().map { it.category },
            "the categories, in order",
        )
        helper.startSequence()
            .thenWaitUntil { helper.assertTrue(bobs().size == 4, "the pig went off: ${bobs()}") }
            .thenExecute {
                helper.assertEquals(PunishmentCategory.EXPLOSION, bobs().last().category, "the fuse's blast")
                helper.assertTrue(bobs().all { it.caller == FIRING }, "the act of the engine: ${bobs().map { it.caller }}")
                summonedFor(helper, bob.uuid).forEach { it.discard() }
                bob.close()
            }
            .thenSucceed()
    }

    private companion object {
        val CALLER = Caller(Role.DIRECTOR, "world-action-gametest", turnId = 8_001, taskId = null)

        /** An act of the mechanics engine: a firing of the rule C-60 (M4 §4.3). */
        val FIRING = Caller(Role.DIRECTOR, "mechanics:C-60", turnId = null, taskId = null, firingId = 9_002)

        /** The dry checks of [tool] for [args], as the engine runs them (`EngineActions.check`). */
        fun <A : Any> check(helper: GameTestHelper, tool: NarratorTool<A>, args: A): ToolResult? {
            val runtime = helper.narrator()
            return runBlocking { tool.check(ToolContext(runtime, CALLER, runtime.clock, tool.timeoutMs), args) }
        }

        /** A later turn of the same conversation: it may not pile on the punishment of [CALLER]'s turn. */
        val OTHER_TURN = CALLER.copy(turnId = 8_002)

        fun guards(helper: GameTestHelper): GuardsImpl = helper.narrator().guards as GuardsImpl

        /** Calls [tool] on the server thread, where its world access runs inline. */
        fun <A : Any> call(helper: GameTestHelper, tool: NarratorTool<A>, args: A, caller: Caller = CALLER): ToolResult {
            val runtime = helper.narrator()
            return runBlocking { tool.run(ToolContext(runtime, caller, runtime.clock, tool.timeoutMs), args) }
        }

        fun ToolResultBlockParam.text(): String = content().get().asString()

        /** Runs [body] with one new [TestPlayer] standing on a floor in the middle of the test's area; removed however it ends. */
        fun withPlayer(helper: GameTestHelper, body: (TestPlayer) -> Unit) = withTestPlayers(helper, 1) { (player) ->
            floor(helper)
            player.standAt(BlockPos(4, 1, 4))
            body(player)
        }

        fun floor(helper: GameTestHelper) {
            for (x in 0..7) for (z in 0..7) helper.setBlock(x, 0, z, Blocks.STONE)
        }

        /** The Narrator's creatures around the test's area that were summoned for [player]. */
        fun summonedFor(helper: GameTestHelper, player: UUID): List<Mob> =
            helper.level.getEntitiesOfClass(Mob::class.java, AABB(helper.absolutePos(BlockPos.ZERO)).inflate(40.0)) {
                it.getAttached(NarratorMob.TYPE)?.player == player
            }

        /** [mob]'s chance to call reinforcements is 0 for good: base value, no bonus. */
        fun assertNoReinforcements(helper: GameTestHelper, mob: Mob) {
            val chance = mob.getAttribute(Attributes.SPAWN_REINFORCEMENTS_CHANCE)!!
            helper.assertTrue(
                chance.baseValue == 0.0 && chance.modifiers.isEmpty() && chance.value == 0.0,
                "${mob.type.toShortString()} calls no reinforcements: base ${chance.baseValue}, modifiers ${chance.modifiers}",
            )
        }
    }
}
