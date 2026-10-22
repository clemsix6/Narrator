package io.github.clemsix6.narrator2.gametest

import com.mojang.brigadier.context.ContextChain
import io.github.clemsix6.narrator2.guard.AttributeKey
import io.github.clemsix6.narrator2.guard.AttributeLedger
import io.github.clemsix6.narrator2.guard.CommandPolicy
import io.github.clemsix6.narrator2.guard.ExplosionGuard
import io.github.clemsix6.narrator2.guard.GuardStates
import io.github.clemsix6.narrator2.guard.GuardsImpl
import io.github.clemsix6.narrator2.guard.NarratorMobs
import io.github.clemsix6.narrator2.guard.OpenEpisode
import io.github.clemsix6.narrator2.guard.PunishmentCategory
import io.github.clemsix6.narrator2.guard.Rules
import io.github.clemsix6.narrator2.guard.SpawnPolicy
import io.github.clemsix6.narrator2.guard.Verdict
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.action.NarratorExplosionDamage
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.commands.Commands
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.permissions.LevelBasedPermissionSet
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.Mob
import net.minecraft.world.entity.ai.attributes.RangedAttribute
import net.minecraft.world.entity.ai.goal.BreakDoorGoal
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.GameType
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.phys.Vec3

/**
 * In-game tests of the guards' policies (M1_M2 §17.3): `CommandPolicy` on the real dispatcher, the
 * Narrator's creeper, zombie and explosions, what `spawn` may summon, the attributes' bounds against the
 * game's, and the targeting rules through the guards.
 *
 * Tests run side by side: each builds in its own area (a stone floor at y = 0), removes its mock players,
 * and none changes the difficulty or a game rule. The Narrator's creatures are marked with a long expiry,
 * so that the guards' check once a second leaves them alone.
 */
class GuardPolicyGameTests {

    @GameTest
    fun theRealDispatcherRefusesWhatThePolicyForbids(helper: GameTestHelper) {
        val server = helper.level.server
        val source = server.createCommandSourceStack().withPermission(LevelBasedPermissionSet.GAMEMASTER)
        val dispatcher = server.commands.dispatcher
        fun check(policy: CommandPolicy, command: String) = policy.check(dispatcher.parse(command, source))
        val policy = CommandPolicy { emptyList() }

        val refused = linkedMapOf(
            "execute as @a run fill ~ ~ ~ ~1 ~1 ~1 minecraft:stone" to "fill",
            "fill ~ ~ ~ ~ ~ ~ minecraft:air destroy" to "fill",
            "setblock ~ ~ ~ minecraft:stone" to "setblock",
            "clone ~ ~ ~ ~1 ~1 ~1 ~5 ~ ~ replace" to "clone",
            "return run fill ~ ~ ~ ~ ~ ~ minecraft:stone" to "fill",
            "execute store result block ~ ~ ~ Items[0].count int 1 run list" to "execute store",
            "execute as @a store result score @s x run list" to "execute store",
            "execute summon minecraft:creeper run list" to "execute summon",
            "execute if function minecraft:nothing run list" to "execute if function",
            "data merge block ~ ~ ~ {}" to "data",
            "tp @a 0 100 0" to "tp",
            "execute as @a at @s run teleport ~ ~10 ~" to "teleport",
            "summon minecraft:zombie ~ ~ ~" to "summon",
            "kill @e" to "kill",
            "effect give @a minecraft:wither 60 5" to "effect",
            "attribute @a minecraft:scale base set 16" to "attribute",
            "give @a minecraft:diamond 64" to "give",
            "clear @a" to "clear",
            "item replace entity @a weapon.mainhand with minecraft:air" to "item",
            "loot replace block ~ ~ ~ container.0 loot minecraft:empty" to "loot",
            "team modify red friendlyFire true" to "team",
            "gamerule keep_inventory false" to "gamerule",
            "function minecraft:nothing" to "function",
            "say hello" to "say",
            "tellraw @a \"hello\"" to "tellraw",
            "weather clear" to "weather",
            "time set day" to "time",
            // M2 review, finding 7: bounded tools do it, and a roll is told to every player.
            "particle minecraft:heart ~ ~1 ~ 0 0 0 1 2000000000 force" to "particle",
            "execute as @a at @s run particle minecraft:heart ~ ~1 ~" to "particle",
            "playsound minecraft:entity.pig.ambient master @a" to "playsound",
            "execute as @a at @s run playsound minecraft:entity.pig.ambient master @s" to "playsound",
            "random roll 1..6" to "random roll",
            "execute as @a run random roll 1..6" to "random roll",
            // Invisible to a gamemaster, refused by name all the same.
            "stop" to "stop",
            "execute run op Steve" to "op",
            "save-off" to "save-off",
            // The Narrator's own commands.
            "narrator chaos 5" to "narrator",
            "pray please" to "pray",
            // The players' own commands (M4 review, finding 15): never a duel forced behind `execute as`.
            "duel accept C-52" to "duel",
            "execute as @a run duel accept C-52" to "duel",
            "execute as @a at @s run bet C-50 nobody" to "bet",
            "offer" to "offer",
            "execute as @r run offer" to "offer",
            // Recipes are progress: `recipe give @a *` would unlock them all for good.
            "recipe give @a *" to "recipe",
            "execute as @a run recipe give @s *" to "recipe",
        )
        val wrong = refused.mapNotNull { (command, name) ->
            val verdict = check(policy, command)
            val ok = verdict is Verdict.Denied && verdict.rule == Rules.FORBIDDEN_COMMAND && verdict.message.startsWith("`$name` is forbidden")
            if (ok) null else "$command -> $verdict"
        }
        helper.assertTrue(wrong.isEmpty(), "not refused as expected: $wrong")

        val allowed = listOf(
            "scoreboard objectives list",
            "execute if block ~ ~ ~ minecraft:air run scoreboard objectives list",
            "execute as @a at @s run stopsound @s master",
            "execute if entity @e[type=minecraft:zombie,limit=1] run list",
            "random value 1..6",
            "list",
        )
        val blocked = allowed.mapNotNull { command ->
            val parse = dispatcher.parse(command, source)
            val complete = Commands.getParseException(parse) == null && ContextChain.tryFlatten(parse.context.build(command)).isPresent
            val verdict = policy.check(parse)
            if (complete && verdict == Verdict.Allowed) null else "$command -> complete=$complete, $verdict"
        }
        helper.assertTrue(blocked.isEmpty(), "valid and allowed commands: $blocked")

        val strict = CommandPolicy { listOf("stopsound") }
        val extra = check(strict, "execute as @a at @s run stopsound @s master")
        helper.assertTrue(extra is Verdict.Denied && "safety.extraForbiddenCommands" in extra.message, "the configuration adds roots: $extra")
        val lax = CommandPolicy({ listOf("give", "recipe", "particle", "playsound", "duel", "bet", "offer") }) { emptyList() }
        val stillForbidden = listOf(
            "give @a minecraft:diamond 64", "recipe give @a *", "particle minecraft:heart ~ ~1 ~", "playsound minecraft:entity.pig.ambient master @a",
            "execute as @a run duel accept C-52", "bet C-50 nobody", "execute as @r run offer",
        ).map { check(lax, it) }
        helper.assertTrue(stillForbidden.all { it is Verdict.Denied }, "safety.extraAllowedCommands never allows a built-in root: $stillForbidden")
        helper.succeed()
    }

    /**
     * Every command of this server is either forbidden or known to be allowed: a command that a new
     * version of the game (or another mod) adds fails here until someone decides. And every forbidden
     * root names a real command, except those registered only in some environments.
     */
    @GameTest
    fun everyCommandIsForbiddenOrReviewed(helper: GameTestHelper) {
        val root = helper.level.server.commands.dispatcher.root
        val names = root.children.map { it.name }
        val unreviewed = names.filterNot { it in CommandPolicy.FORBIDDEN || it in CommandPolicy.ALLOWED }.sorted()
        helper.assertTrue(unreviewed.isEmpty(), "commands neither forbidden nor reviewed (refused by default, but decide): $unreviewed")
        // Development commands, LAN publishing (integrated servers), the JFR profiler (when available), and
        // the players' own commands of this mod (`/pray` of T21; `/offer`, `/duel` and `/bet`, which M4-15, M4-19
        // and M4-20 register).
        val sometimes = CommandPolicy.FORBIDDEN.filterValues { it.reason == CommandPolicy.Reason.DEV || it.reason == CommandPolicy.Reason.PLAYER }.keys +
            setOf("publish", "unpublish", "jfr")
        val unknown = CommandPolicy.FORBIDDEN.keys.filterNot { it in names || it in sometimes }
        helper.assertTrue(unknown.isEmpty(), "forbidden roots that no command has: $unknown")
        val unused = CommandPolicy.ALLOWED.filterNot { it in names }
        helper.assertTrue(unused.isEmpty(), "reviewed roots that no command has: $unused")
        helper.succeed()
    }

    /** A creeper of the Narrator's explodes: no block, no container, no item, no decoration, no animal is touched; a monster is. */
    @GameTest(maxTicks = 300)
    fun theNarratorsCreeperBreaksNothingButHurtsMonsters(helper: GameTestHelper) {
        val scene = Scene(helper)
        val creeper = summonAsNarrator(helper, EntityTypes.CREEPER, Vec3(4.5, 1.0, 4.5), noAi = true)
        creeper.ignite()
        // The same creeper without the tag, afterwards: the scene was well within its reach.
        val ordinary = EntityTypes.CREEPER.create(helper.level, EntitySpawnReason.COMMAND)!!
        helper.startSequence()
            .thenWaitUntil { helper.assertTrue(creeper.isRemoved, "the creeper went off") }
            .thenExecute {
                scene.assertUntouched("the Narrator's creeper")
                ordinary.snapTo(helper.absoluteVec(Vec3(4.5, 1.0, 4.5)))
                ordinary.isNoAi = true
                // Or it despawns at its first tick whenever the mock player of another test is more than 128
                // blocks away, and never goes off: the planks were left whole once (M2-fix-A2). The Narrator's is
                // persistent already (NarratorMobs.mark).
                ordinary.setPersistenceRequired()
                helper.level.addFreshEntity(ordinary)
                ordinary.ignite()
            }
            .thenWaitUntil { helper.assertTrue(ordinary.isRemoved, "the ordinary creeper went off") }
            .thenExecute {
                helper.assertTrue(scene.planks.any { !helper.getBlockState(it).`is`(Blocks.OAK_PLANKS) }, "an ordinary creeper breaks the planks")
                helper.killAllEntities()
            }
            .thenSucceed()
    }

    /**
     * The `explosion` tool's call goes off inside `ExplosionGuard.narrators`: even as a burning TNT blast,
     * it breaks no block, lights no fire, spares items, animals and protected players, and hurts the others.
     */
    @GameTest
    fun theExplosionToolTouchesNoBlockAndNoProtectedPlayer(helper: GameTestHelper) {
        val scene = Scene(helper)
        val guards = guards(helper)
        val shielded = mockPlayer(helper, BlockPos(4, 1, 2))
        val exposed = mockPlayer(helper, BlockPos(4, 1, 0))
        try {
            guards.setProtected(shielded.uuid, shielded.plainTextName, on = true)
            val center = helper.absoluteVec(Vec3(4.5, 1.5, 4.5))
            ExplosionGuard.narrators { helper.level.explode(null, center.x, center.y, center.z, 3f, true, Level.ExplosionInteraction.TNT) }
            scene.assertUntouched("the explosion tool")
            helper.assertValueEqual(shielded.health, 20f, "the protected player's health")
            helper.assertTrue(exposed.health < 20f, "the other player is hurt: ${exposed.health}")
        } finally {
            guards.setProtected(shielded.uuid, shielded.plainTextName, on = false)
            helper.level.server.playerList.remove(shielded)
            helper.level.server.playerList.remove(exposed)
        }
        // The same blast outside the tool: the scene was well within its reach.
        val center = helper.absoluteVec(Vec3(4.5, 1.5, 4.5))
        helper.level.explode(null, center.x, center.y, center.z, 3f, false, Level.ExplosionInteraction.TNT)
        helper.assertTrue(scene.planks.any { !helper.getBlockState(it).`is`(Blocks.OAK_PLANKS) }, "an ordinary blast breaks the planks")
        helper.killAllEntities()
        helper.succeed()
    }

    /**
     * Two zombies go for a villager behind a wooden door, allowed to break doors at any difficulty: the
     * ordinary one breaks its door, the Narrator's never does. The targets are villagers without AI (they
     * cannot open the door): GameTest's mock players answer CREATIVE to `gameMode()` whatever their game
     * mode, so `Mob.asValidTarget` turns them away, and zombies set on one only broke their door when
     * they happened to wander into it (the ordinary one took over 1 200 ticks once in four runs). The test
     * also waits on conditions, not on a number of ticks: the ordinary zombie's door is broken (within a
     * wide budget), and the Narrator's zombie has stood within reach of its own door (the 2 blocks
     * `BreakDoorGoal` works from) for twice the time a door takes to break.
     *
     * The Narrator's zombie here is one a rule marked (the `narrator2` tag, which the door guard reads): a zombie the
     * Narrator summoned never goes for a villager at all (M4 review, finding 17).
     */
    @GameTest(maxTicks = DOOR_TICKS)
    fun theNarratorsZombieNeverBreaksADoor(helper: GameTestHelper) {
        floor(helper)
        val narratorDoor = corridor(helper, z = 1)
        val ordinaryDoor = corridor(helper, z = 5)
        val narratorTarget = helper.spawnWithNoFreeWill(EntityTypes.VILLAGER, BlockPos(6, 1, 1))
        val ordinaryTarget = helper.spawnWithNoFreeWill(EntityTypes.VILLAGER, BlockPos(6, 1, 5))

        val narrators = EntityTypes.ZOMBIE.create(helper.level, EntitySpawnReason.COMMAND)!!
        narrators.snapTo(helper.absoluteVec(Vec3(1.5, 1.0, 1.5)))
        narrators.addTag(NarratorMobs.TAG)
        narrators.setPersistenceRequired()
        helper.level.addFreshEntity(narrators)
        val ordinary = EntityTypes.ZOMBIE.create(helper.level, EntitySpawnReason.COMMAND)!!
        ordinary.snapTo(helper.absoluteVec(Vec3(1.5, 1.0, 5.5)))
        ordinary.setPersistenceRequired()
        helper.level.addFreshEntity(ordinary)
        for ((zombie, target) in listOf(narrators to narratorTarget, ordinary to ordinaryTarget)) {
            zombie.setCanBreakDoors(true)
            zombie.getGoalSelector().addGoal(1, BreakDoorGoal(zombie) { true })
            zombie.target = target
            helper.assertTrue(zombie.target === target, "the zombie goes for its villager")
        }
        fun isDoor(pos: BlockPos) = helper.getBlockState(pos).`is`(Blocks.OAK_DOOR) && helper.getBlockState(pos.above()).`is`(Blocks.OAK_DOOR)
        val narratorDoorCenter = Vec3.atCenterOf(helper.absolutePos(narratorDoor))
        var atItsDoor = 0
        helper.onEachTick { if (narrators.position().closerThan(narratorDoorCenter, 2.0)) atItsDoor++ }

        helper.startSequence()
            .thenWaitUntil { helper.assertFalse(isDoor(ordinaryDoor), "the ordinary zombie breaks its door") }
            .thenWaitUntil {
                helper.assertTrue(isDoor(narratorDoor), "the Narrator's zombie left its door alone")
                helper.assertTrue(atItsDoor >= DOOR_TRIAL_TICKS, "the Narrator's zombie stood $atItsDoor ticks at its door")
            }
            .thenExecute {
                helper.assertTrue(isDoor(narratorDoor), "the Narrator's zombie left its door alone")
                helper.assertTrue(narrators.isAlive, "the Narrator's zombie is still there")
                helper.killAllEntities()
            }
            .thenSucceed()
    }

    /** Two zombies stand on a sword, both allowed to pick up loot: the ordinary one takes it, the Narrator's never does. */
    @GameTest(maxTicks = 400)
    fun theNarratorsCreaturesNeverPickUpItems(helper: GameTestHelper) {
        floor(helper)
        val narrators = summonAsNarrator(helper, EntityTypes.ZOMBIE, Vec3(1.5, 1.0, 1.5), noAi = false)
        val ordinary = EntityTypes.ZOMBIE.create(helper.level, EntitySpawnReason.COMMAND)!!
        ordinary.snapTo(helper.absoluteVec(Vec3(5.5, 1.0, 5.5)))
        ordinary.setPersistenceRequired()
        helper.level.addFreshEntity(ordinary)
        val swords = listOf(narrators to BlockPos(1, 1, 1), ordinary to BlockPos(5, 1, 5)).map { (zombie, at) ->
            pen(helper, at)
            zombie.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY)
            zombie.setCanPickUpLoot(true)
            helper.spawnItem(Items.IRON_SWORD, at).apply { setNoPickUpDelay() }
        }
        helper.startSequence()
            .thenWaitUntil { helper.assertTrue(ordinary.mainHandItem.`is`(Items.IRON_SWORD), "the ordinary zombie picks up its sword") }
            .thenIdle(20)
            .thenExecute {
                helper.assertTrue(narrators.mainHandItem.isEmpty, "the Narrator's zombie holds nothing: ${narrators.mainHandItem}")
                helper.assertFalse(swords[0].isRemoved, "its sword is still on the ground")
                helper.killAllEntities()
            }
            .thenSucceed()
    }

    @GameTest
    fun theSpawnPolicyRefusesWhatChangesTheWorld(helper: GameTestHelper) {
        val level = helper.level
        fun check(id: String) = SpawnPolicy.check(BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(id)), level)
        val unknown = SpawnPolicy.FORBIDDEN.keys.filterNot { BuiltInRegistries.ENTITY_TYPE.containsKey(Identifier.parse(it)) }
        helper.assertTrue(unknown.isEmpty(), "forbidden creatures that do not exist: $unknown")

        val allowed = listOf("minecraft:zombie", "minecraft:creeper", "minecraft:skeleton", "minecraft:slime", "minecraft:chicken", "minecraft:breeze")
        val refusedAllowed = allowed.associateWith(::check).filterValues { it != Verdict.Allowed }
        helper.assertTrue(refusedAllowed.isEmpty(), "creatures the Narrator may summon: $refusedAllowed")

        val refused = SpawnPolicy.FORBIDDEN.keys + listOf("minecraft:tnt", "minecraft:end_crystal", "minecraft:lightning_bolt", "minecraft:item", "minecraft:player")
        val summoned = refused.associateWith(::check).filterValues { it !is Verdict.Denied || it.rule != SpawnPolicy.FORBIDDEN_CREATURE }
        helper.assertTrue(summoned.isEmpty(), "refused: $summoned")
        val tnt = check("minecraft:tnt")
        helper.assertTrue(tnt is Verdict.Denied && "not a creature" in tnt.message, "a TNT is not a creature: $tnt")
        helper.succeed()
    }

    /** The bounds of each attribute lie within the game's own range, and contain a player's default value. */
    @GameTest
    fun everyAttributeFitsTheGamesRange(helper: GameTestHelper) {
        @Suppress("DEPRECATION")
        val player = helper.makeMockServerPlayerInLevel()
        try {
            val wrong = AttributeKey.entries.mapNotNull { key ->
                val holder = AttributeLedger.holder(key)
                val range = holder.value() as? RangedAttribute ?: return@mapNotNull "${key.id} is not a ranged attribute"
                val value = player.getAttribute(holder)?.value ?: return@mapNotNull "a player has no ${key.id}"
                when {
                    key.min < range.minValue || key.max > range.maxValue -> "${key.id}: ${key.min}..${key.max} leaves ${range.minValue}..${range.maxValue}"
                    !key.allows(value) -> "${key.id}: a player's ${value} is out of ${key.min}..${key.max}"
                    else -> null
                }
            }
            helper.assertTrue(wrong.isEmpty(), "attributes: $wrong")
        } finally {
            helper.level.server.playerList.remove(player)
        }
        helper.succeed()
    }

    /**
     * Through the guards: one turn's actions form one punishment, saved with the player; another turn's stacks on it
     * (M7-LOOSEN), saved until the later end, and both count.
     */
    @GameTest
    fun oneTurnOnePunishmentAndAnotherStacksThroughTheGuards(helper: GameTestHelper) {
        val guards = guards(helper)
        val player = mockPlayer(helper, BlockPos(1, 1, 1))
        try {
            val now = System.currentTimeMillis()
            fun turn(id: Long) = Caller(Role.DIRECTOR, "policy-test", turnId = id, taskId = null)
            helper.assertEquals(Verdict.Allowed, guards.hostile(player, turn(101), now + 60_000), "the punishment opens")
            helper.assertEquals(Verdict.Allowed, guards.hostile(player, turn(101), now + 90_000), "the same turn adds to it")
            helper.assertEquals(OpenEpisode(101, now + 90_000), GuardStates.read(player).episode, "saved with the player")
            helper.assertEquals(Verdict.Allowed, guards.hostile(player, turn(102), now + 60_000), "another turn's punishment stacks")
            helper.assertEquals(OpenEpisode(102, now + 90_000), GuardStates.read(player).episode, "saved until the later end")
            helper.assertEquals(2, guards.pacing(listOf(player.uuid), now).single().punishedThisHour, "two punishments this hour")
        } finally {
            helper.level.server.playerList.remove(player)
        }
        helper.succeed()
    }

    /**
     * An explosion refused for one bystander punishes none of the others (M4 review, finding 11): Bob, next to
     * Mimosa whom another act just caught in an explosion, is not counted punished by a blast that never hurt him.
     */
    @GameTest
    fun aRefusedExplosionPunishesNoBystander(helper: GameTestHelper) {
        val guards = guards(helper)
        val bob = mockPlayer(helper, BlockPos(1, 1, 1))
        val mimosa = mockPlayer(helper, BlockPos(2, 1, 1))
        try {
            val now = System.currentTimeMillis()
            fun turn(id: Long) = Caller(Role.DIRECTOR, "policy-test", turnId = id, taskId = null)
            helper.assertEquals(Verdict.Allowed, guards.hostile(mimosa, turn(201), now + 60_000, PunishmentCategory.EXPLOSION), "another act blasts Mimosa")
            val verdict = NarratorExplosionDamage.allow(listOf(bob, mimosa), guards, turn(202), now + 5_000, PunishmentCategory.EXPLOSION)
            helper.assertTrue(verdict is Verdict.Denied && verdict.rule == Rules.SAME_PUNISHMENT, "the explosion is refused: $verdict")
            helper.assertEquals(null, GuardStates.read(bob).episode, "no punishment opened on Bob")
            val pace = guards.pacing(listOf(bob.uuid), now).single()
            helper.assertEquals(0 to emptySet<PunishmentCategory>(), pace.punishedThisHour to pace.resting.keys, "nothing counted against Bob, explosion not resting")
            helper.assertEquals(Verdict.Allowed, guards.hostile(bob, turn(203), now + 60_000, PunishmentCategory.EXPLOSION), "Bob is fair game for another act")
        } finally {
            removePlayers(helper, bob, mimosa)
        }
        helper.succeed()
    }

    /**
     * The creatures the Narrator summons go for players only (M4 review, finding 17): never a villager nor an animal,
     * whom the game would let a zombie hunt. A villager left beside the Narrator's zombie is never hurt.
     */
    @GameTest(maxTicks = 400)
    fun theNarratorsCreaturesLeaveVillagersAndAnimalsAlone(helper: GameTestHelper) {
        floor(helper)
        val villager = helper.spawnWithNoFreeWill(EntityTypes.VILLAGER, BlockPos(4, 1, 4))
        val pig = helper.spawnWithNoFreeWill(EntityTypes.PIG, BlockPos(6, 1, 6))
        val zombie = summonAsNarrator(helper, EntityTypes.ZOMBIE, Vec3(2.5, 1.0, 2.5), noAi = false)
        zombie.target = villager
        helper.assertTrue(zombie.target == null, "the villager is no target: ${zombie.target}")
        zombie.target = pig
        helper.assertTrue(zombie.target == null, "nor the pig: ${zombie.target}")
        helper.startSequence()
            .thenIdle(300)
            .thenExecute {
                helper.assertTrue(zombie.target == null, "the zombie found no target: ${zombie.target}")
                helper.assertValueEqual(villager.maxHealth, villager.health, "the villager's health")
                helper.assertFalse(villager.isRemoved, "the villager is still there")
                helper.killAllEntities()
            }
            .thenSucceed()
    }

    /**
     * A stone floor with things an explosion at (4.5, 1, 4.5) must leave alone: planks, a chest with 3
     * diamonds, a diamond on the ground, an armor stand and a pig; and a spider it must hurt.
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
        private val pig = helper.spawnWithNoFreeWill(EntityTypes.PIG, BlockPos(2, 1, 6))
        private val spider = helper.spawnWithNoFreeWill(EntityTypes.SPIDER, BlockPos(6, 1, 3))

        fun assertUntouched(by: String) {
            planks.forEach { helper.assertBlockPresent(Blocks.OAK_PLANKS, it) }
            helper.assertBlockPresent(Blocks.CHEST, chest)
            val stack = (helper.level.getBlockEntity(helper.absolutePos(chest)) as ChestBlockEntity).getItem(0)
            helper.assertTrue(stack.`is`(Items.DIAMOND) && stack.count == 3, "the chest keeps its diamonds after $by: $stack")
            helper.assertFalse(diamond.isRemoved, "the diamond on the ground survives $by")
            helper.assertFalse(stand.isRemoved, "the armor stand survives $by")
            helper.assertValueEqual(pig.health, pig.maxHealth, "the pig's health after $by")
            helper.assertTrue(spider.isDeadOrDying || spider.health < spider.maxHealth, "the spider is hurt by $by")
            for (x in 0..7) for (y in 1..3) for (z in 0..7) {
                helper.assertFalse(helper.getBlockState(BlockPos(x, y, z)).`is`(Blocks.FIRE), "no fire at $x $y $z after $by")
            }
        }
    }

    /** Shared with `GuardGapGameTests`. */
    internal companion object {
        /**
         * Budget of the door test. The ordinary zombie usually breaks its door within 500 ticks, but its AI
         * sometimes wanders or loses its path and took over 1 200 ticks once in four runs; the tick budget
         * costs little (the GameTest server runs its ticks back to back), the conditions decide.
         */
        const val DOOR_TICKS = 6_000

        /** A door takes 240 ticks to break (`BreakDoorGoal`, javap). */
        private const val DOOR_BREAK_TICKS = 240

        /** How long the Narrator's zombie must have stood within reach of its door before its door is judged: twice a break. */
        const val DOOR_TRIAL_TICKS = 2 * DOOR_BREAK_TICKS

        fun guards(helper: GameTestHelper): GuardsImpl = helper.narrator().guards as GuardsImpl

        fun floor(helper: GameTestHelper) {
            for (x in 0..7) for (z in 0..7) helper.setBlock(x, 0, z, Blocks.STONE)
        }

        /** One of the Narrator's creatures, marked (tag and expiry) before it enters the level, as the spawn tool does. */
        fun <T : Mob> summonAsNarrator(helper: GameTestHelper, type: EntityType<T>, at: Vec3, noAi: Boolean): T {
            val mob = type.create(helper.level, EntitySpawnReason.COMMAND)!!
            mob.snapTo(helper.absoluteVec(at))
            mob.isNoAi = noAi
            guards(helper).mobs.mark(mob, System.currentTimeMillis() + 600_000, player = null, harmful = true)
            helper.assertTrue(helper.level.addFreshEntity(mob), "${type.toShortString()} added")
            return mob
        }

        /** A survival player whose client has loaded (the only kind that takes damage), standing at [at]. */
        fun mockPlayer(helper: GameTestHelper, at: BlockPos): ServerPlayer {
            @Suppress("DEPRECATION")
            val player = helper.makeMockServerPlayerInLevel()
            val pos = helper.absolutePos(at)
            player.teleportTo(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5)
            player.setGameMode(GameType.SURVIVAL)
            player.connection.handleAcceptPlayerLoad(ServerboundPlayerLoadedPacket())
            return player
        }

        fun removePlayers(helper: GameTestHelper, vararg players: ServerPlayer) {
            val list = helper.level.server.playerList
            players.forEach { player -> list.getPlayer(player.uuid)?.let(list::remove) }
        }

        /** A roofed corridor along x at [z], walled at both ends, with a closed oak door across it at x = 4. */
        fun corridor(helper: GameTestHelper, z: Int): BlockPos {
            for (x in 0..7) {
                for (y in 1..2) {
                    helper.setBlock(x, y, z - 1, Blocks.STONE)
                    helper.setBlock(x, y, z + 1, Blocks.STONE)
                }
                for (dz in -1..1) helper.setBlock(x, 3, z + dz, Blocks.STONE)
            }
            for (y in 1..2) {
                helper.setBlock(0, y, z, Blocks.STONE)
                helper.setBlock(7, y, z, Blocks.STONE)
            }
            val door = BlockPos(4, 1, z)
            val lower = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.EAST).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
            helper.setBlock(door, lower)
            helper.setBlock(door.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER))
            return door
        }

        /** A roofed 1×1 pen around [at]. */
        fun pen(helper: GameTestHelper, at: BlockPos) {
            for (dx in -1..1) for (dz in -1..1) {
                if (dx == 0 && dz == 0) continue
                for (y in 1..2) helper.setBlock(at.x + dx, y, at.z + dz, Blocks.STONE)
            }
            helper.setBlock(at.x, 3, at.z, Blocks.STONE)
        }
    }
}
