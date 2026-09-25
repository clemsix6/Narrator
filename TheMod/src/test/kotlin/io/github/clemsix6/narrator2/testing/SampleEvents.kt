package io.github.clemsix6.narrator2.testing

import io.github.clemsix6.narrator2.event.AdvancementEarned
import io.github.clemsix6.narrator2.event.AnimalTamed
import io.github.clemsix6.narrator2.event.AnimalsBred
import io.github.clemsix6.narrator2.event.AnvilUsed
import io.github.clemsix6.narrator2.event.BiomeEntered
import io.github.clemsix6.narrator2.event.BlockBroken
import io.github.clemsix6.narrator2.event.BlockPlaced
import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.event.ChatMessage
import io.github.clemsix6.narrator2.event.CommandIssued
import io.github.clemsix6.narrator2.event.ContainerOpened
import io.github.clemsix6.narrator2.event.DamageDealt
import io.github.clemsix6.narrator2.event.DamageTaken
import io.github.clemsix6.narrator2.event.DimensionChanged
import io.github.clemsix6.narrator2.event.EndPortalActivated
import io.github.clemsix6.narrator2.event.EntityKilled
import io.github.clemsix6.narrator2.event.EntityRef
import io.github.clemsix6.narrator2.event.ExplosionHappened
import io.github.clemsix6.narrator2.event.FishCaught
import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.event.GrindstoneUsed
import io.github.clemsix6.narrator2.event.HealthCritical
import io.github.clemsix6.narrator2.event.IdleChanged
import io.github.clemsix6.narrator2.event.ItemConsumed
import io.github.clemsix6.narrator2.event.ItemCount
import io.github.clemsix6.narrator2.event.ItemCrafted
import io.github.clemsix6.narrator2.event.ItemEnchanted
import io.github.clemsix6.narrator2.event.ItemsDropped
import io.github.clemsix6.narrator2.event.ItemsPickedUp
import io.github.clemsix6.narrator2.event.LeaveReason
import io.github.clemsix6.narrator2.event.LootChestOpened
import io.github.clemsix6.narrator2.event.MountChanged
import io.github.clemsix6.narrator2.event.NarratorChannel
import io.github.clemsix6.narrator2.event.NarratorSpoke
import io.github.clemsix6.narrator2.event.PlaceKind
import io.github.clemsix6.narrator2.event.PlayerDied
import io.github.clemsix6.narrator2.event.PlayerJoined
import io.github.clemsix6.narrator2.event.PlayerLeft
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.PlayerRespawned
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.event.PositionSampled
import io.github.clemsix6.narrator2.event.ProjectileHit
import io.github.clemsix6.narrator2.event.RespawnPointSet
import io.github.clemsix6.narrator2.event.SampleReason
import io.github.clemsix6.narrator2.event.SleepEnded
import io.github.clemsix6.narrator2.event.SleepStarted
import io.github.clemsix6.narrator2.event.StatAwarded
import io.github.clemsix6.narrator2.event.StatsSnapshot
import io.github.clemsix6.narrator2.event.StructureEntered
import io.github.clemsix6.narrator2.event.StructureLeft
import io.github.clemsix6.narrator2.event.Teleported
import io.github.clemsix6.narrator2.event.Traded
import io.github.clemsix6.narrator2.event.UndergroundChanged
import java.util.UUID

/**
 * Plausible events for tests: at least one of every journal type, hence of every event class. Tests
 * that walk the catalog (the Journal's mapping, the salience table) can start from [all].
 */
object SampleEvents {
    val steve = PlayerRef(UUID.fromString("00000000-0000-4000-8000-000000000001"), "Steve")
    val alex = PlayerRef(UUID.fromString("00000000-0000-4000-8000-000000000002"), "Alex")
    val here = Pos("minecraft:overworld", 120, 64, -330)
    val nether = Pos("minecraft:the_nether", 15, 70, -41)
    val zombie = EntityRef("minecraft:zombie", UUID.fromString("00000000-0000-4000-8000-00000000000a"), null)
    val alexEntity = EntityRef("minecraft:player", alex.uuid, alex.name, alex)

    val all: List<GameEvent> = listOf(
        BlockBroken(steve, here, "minecraft:diamond_ore", "minecraft:iron_pickaxe", creative = false, correctTool = true, placedBy = null),
        BlockBroken(steve, here, "minecraft:oak_planks", null, creative = false, correctTool = true, placedBy = alex.uuid),
        BlockPlaced(steve, here, "minecraft:cobblestone", "minecraft:cobblestone", PlaceKind.BLOCK),
        BlockPlaced(steve, here, "minecraft:water", "minecraft:water_bucket", PlaceKind.FLUID),
        BlockPlaced(steve, here, "minecraft:fire", "minecraft:flint_and_steel", PlaceKind.FIRE),
        BlockPlaced(steve, here, "minecraft:nether_portal", "minecraft:flint_and_steel", PlaceKind.PORTAL),
        EndPortalActivated(steve, here),
        ContainerOpened(steve, here, "minecraft:generic_9x3", "Loot", "minecraft:chest", spectator = false),
        LootChestOpened(steve, here, "minecraft:chests/simple_dungeon"),
        ExplosionHappened(
            here, "minecraft:tnt", EntityRef("minecraft:player", steve.uuid, steve.name, steve), radius = 4.0,
            interaction = "destroy", blocksHit = 30, playerBlocksHit = 12, nearby = listOf(steve, alex), narrator = false,
        ),
        DamageTaken(steve, here, "minecraft:mob_attack", amount = 7.5, hits = 3, attacker = zombie, blocked = false, healthAfter = 12.5),
        DamageDealt(steve, here, zombie, amount = 6.0, weapon = "minecraft:iron_sword", damageType = "minecraft:player_attack", victimHealth = 14.0),
        PlayerDied(
            steve, here, "minecraft:mob_attack", zombie, weapon = null, message = "Steve was slain by Zombie",
            xpLevel = 12, keptInventory = false, narrator = false,
        ),
        EntityKilled(steve, here, zombie, "minecraft:iron_sword", pvp = false, tamed = false),
        EntityKilled(steve, here, alexEntity, "minecraft:bow", pvp = true, tamed = false),
        ProjectileHit(steve, here, "minecraft:arrow", EntityRef("minecraft:player", steve.uuid, steve.name, steve), zombie, null, "minecraft:bow"),
        ItemConsumed(steve, here, "minecraft:cooked_beef", 12, nutrition = 8, saturation = 12.8, effects = emptyList(), foodBefore = 10, healthBefore = 20.0),
        ItemCrafted(steve, here, "minecraft:torch", 16, "minecraft:crafting"),
        AnvilUsed(steve, here, "minecraft:diamond_sword", "minecraft:diamond_sword", null, cost = 1, renamedTo = "Excalibur"),
        GrindstoneUsed(steve, here, "minecraft:bow"),
        ItemEnchanted(steve, here, "minecraft:diamond_pickaxe", mapOf("minecraft:efficiency" to 3), cost = 3),
        Traded(
            steve, here, ItemCount("minecraft:emerald", 1), ItemCount("minecraft:paper", 24), null, zombie.copy(type = "minecraft:villager"),
            profession = "minecraft:librarian", level = 2, uses = 3, maxUses = 16,
        ),
        ItemsPickedUp(steve, here, "minecraft:cobblestone", 64, 30),
        ItemsDropped(steve, here, "minecraft:rotten_flesh", 5),
        FishCaught(steve, here, listOf(ItemCount("minecraft:cod", 1)), null),
        AnimalsBred(steve, here, "minecraft:cow"),
        AnimalTamed(steve, here, "minecraft:wolf", "Rex"),
        StatAwarded(steve, here, "minecraft.custom:minecraft.bell_ring", 1),
        AdvancementEarned(steve, here, "minecraft:story/mine_diamond", "Diamonds!", "task"),
        DimensionChanged(steve, nether, here, "nether_portal"),
        Teleported(steve, here.copy(x = 900), here, 780.0),
        SleepStarted(steve, here, "minecraft:red_bed"),
        SleepEnded(steve, here, nightSkipped = true),
        RespawnPointSet(steve, here, here, forced = false, cleared = false),
        MountChanged(steve, here, EntityRef("minecraft:horse", UUID.fromString("00000000-0000-4000-8000-00000000000b"), null), mounted = true),
        MountChanged(steve, here, EntityRef("minecraft:horse", UUID.fromString("00000000-0000-4000-8000-00000000000b"), null), mounted = false),
        BiomeEntered(steve, here, "minecraft:cherry_grove", firstVisit = true),
        StructureEntered(steve, here, "minecraft:village_plains", firstVisit = true),
        StructureLeft(steve, here, "minecraft:village_plains"),
        UndergroundChanged(steve, here.copy(y = -40), underground = true),
        UndergroundChanged(steve, here, underground = false),
        HealthCritical(steve, here, 3.0),
        IdleChanged(steve, here, idle = true, idleSeconds = 300),
        IdleChanged(steve, here, idle = false, idleSeconds = 540),
        PlayerJoined(steve, here, sessionId = 7, firstTime = false, isOp = true),
        PlayerLeft(steve, here, sessionId = 7, sessionSeconds = 3_600, reason = LeaveReason.LEAVE),
        PlayerRespawned(steve, here, alive = false),
        CommandIssued(steve, here, "tp", "tp Steve 0 64 0"),
        CommandIssued(steve, here, "msg", null),
        ChatMessage(steve, ChatChannel.PUBLIC, "narrateur, t'es nul", emptyList()),
        ChatMessage(steve, ChatChannel.PRAY, "rends-moi mes diamants", emptyList()),
        ChatMessage(steve, ChatChannel.WHISPER, "la base est derrière la cascade", listOf("Alex")),
        NarratorSpoke(NarratorChannel.NARRATOR, "Steve, ta pioche pleure.", null, turnId = 42, operator = null),
        NarratorSpoke(NarratorChannel.OPERATOR, "Silence.", listOf(alex), turnId = null, operator = "Clement"),
        PositionSampled(steve, here, "minecraft:plains", null, false, 15, 0, 20.0, 20, null, SampleReason.MOVED),
        StatsSnapshot(steve, mapOf("minecraft.mined:minecraft.stone" to 1_204L)),
    )
}
