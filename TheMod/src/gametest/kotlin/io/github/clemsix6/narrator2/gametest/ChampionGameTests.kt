package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.relics.ChampionDraft
import io.github.clemsix6.narrator2.relics.ChampionGear
import io.github.clemsix6.narrator2.relics.ChampionMob
import io.github.clemsix6.narrator2.relics.GearPiece
import io.github.clemsix6.narrator2.relics.LiveChampion
import io.github.clemsix6.narrator2.relics.RelicsService
import io.github.clemsix6.narrator2.relics.Threat
import io.github.clemsix6.narrator2.tool.Caller
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.Mob
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Blocks

/**
 * M7-BALANCE, the owner's evening: a champion sent at Thawyn never went for him, and fell to a few blows of a wooden axe.
 * It had gone for him, then a rescue shielded him, and a shielded player is no target for the Narrator's creatures: the
 * champion stood harmless, and its killer earned a relic for nothing. Now a champion goes for its player from the moment
 * it stands, is sized to their gear, and leaves when the Narrator spares them. Owned by M7-BALANCE.
 */
class ChampionGameTests {

    @OptIn(ExperimentalCoroutinesApi::class)
    @GameTest(structure = FIELD, skyAccess = true, maxTicks = ASYNC_MAX_TICKS)
    fun aChampionGoesForItsPlayerUntilItEnds(helper: GameTestHelper) {
        val runtime = helper.narrator()
        val relics = runtime.relics
        helper.assertTrue(relics != null, "the armorer's service runs (the Journal is there)")
        for (x in 0 until FIELD_SIZE) for (z in 0 until FIELD_SIZE) helper.setBlock(x, 0, z, Blocks.STONE)
        val player = TestPlayer(helper, "ch_")
        player.makeVulnerable()
        player.standAt(BlockPos(FIELD_SIZE / 2, 1, FIELD_SIZE / 2))
        player.live.setItemSlot(EquipmentSlot.MAINHAND, ItemStack(Items.WOODEN_AXE))
        var lowest = player.live.health
        var before = player.live.health
        var hardest = 0f
        helper.onEachTick {
            player.live.doTick() // what a client's ticks do: the cooldown between two blows
            val now = player.live.health
            lowest = minOf(lowest, now)
            hardest = maxOf(hardest, before - now)
            if (now < HEAL_BELOW) player.live.health = player.live.maxHealth // the test is about blows, not a death
            before = player.live.health
        }
        val caller = Caller(Role.ARMORER, "armorer-gametest", turnId = null, taskId = null)
        lateinit var standing: kotlinx.coroutines.Deferred<LiveChampion>
        lateinit var champion: Mob
        helper.startSequence()
            .thenAwait { helper.assertTrue(relics!!.available, "the reserves are loaded: ${relics.unavailableReason}") }
            .thenExecute { standing = runtime.scope.async { relics!!.stand(caller, player.ref, DRAFT) } }
            .thenAwait { helper.assertTrue(standing.isCompleted, "the champion stands") }
            .thenExecute {
                val live = standing.getCompleted()
                champion = helper.level.getEntity(live.entity) as Mob
                helper.assertTrue(RelicsService.TAG in champion.entityTags(), "tagged a champion")
                helper.assertTrue(champion.target === player.live, "it goes for its player from the start: ${champion.target}")
                // A wooden axe: 7 damage, 0.8 blows a second; twenty seconds of them.
                helper.assertTrue(champion.maxHealth >= 7f * 0.8f * Threat.FLOOR_SECONDS.toFloat() - 0.01f, "sized to the wooden axe: ${champion.maxHealth} (${live.summary})")
                helper.assertTrue(champion.health == champion.maxHealth, "full health: ${champion.health}/${champion.maxHealth}")
            }
            .thenWaitUntil { helper.assertTrue(lowest < player.live.maxHealth, "its player takes its blows: lowest health $lowest") }
            // Its stone sword would take 8.75 (more on hard): a twenty-second fight stays winnable.
            .thenExecute { helper.assertTrue(hardest <= Threat.MAX_BLOW + 0.01, "a blow takes ${Threat.MAX_BLOW} at most: $hardest") }
            .thenExecute { champion.target = null }
            .thenWaitUntil { helper.assertTrue(champion.target === player.live, "it goes for its player again: ${champion.target}") }
            .thenExecute { runtime.guards.rescue(player.live, "a test's rescue", inPlace = true) }
            .thenWaitUntil { helper.assertTrue(champion.isRemoved, "its player shielded, it leaves rather than stand harmless") }
            .thenExecute { player.close() }
            .thenSucceed()
    }

    private companion object {
        const val FIELD = "narrator2-gametest:oracle_field"
        const val FIELD_SIZE = 32

        /** The player is healed below this: the test watches blows land, not a death. */
        const val HEAL_BELOW = 10f

        /** A husk (it does not burn by day) with the smallest factors the schema allows and a stone sword: the code does the rest. */
        val DRAFT = ChampionDraft(
            mob = ChampionMob.HUSK,
            name = "Lumignon, Syndic des Torches",
            lore = "La cendre des torches cassées a pris un avocat.",
            health = 1.5,
            gear = ChampionGear(mainhand = GearPiece("stone_sword")),
        )
    }
}
