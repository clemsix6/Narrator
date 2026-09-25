package io.github.clemsix6.narrator2.observer

import io.github.clemsix6.narrator2.event.ExplosionHappened
import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.event.ItemCrafted
import io.github.clemsix6.narrator2.event.ItemsPickedUp
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.testing.SampleEvents.alex
import io.github.clemsix6.narrator2.testing.SampleEvents.alexEntity
import io.github.clemsix6.narrator2.testing.SampleEvents.here
import io.github.clemsix6.narrator2.testing.SampleEvents.nether
import io.github.clemsix6.narrator2.testing.SampleEvents.steve
import io.github.clemsix6.narrator2.testing.SampleEvents.zombie
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Observer's aggregators of T06: crafts per tick, pickups per 30-second window, explosions per tick and zone. */
class ObserverAggregatorsTest {
    private val published = mutableListOf<GameEvent>()

    @Test
    fun `crafts of one tick are merged per player, item and station`() {
        val crafts = CraftCoalescer { published += it }
        repeat(16) { crafts.add(steve, here, "minecraft:oak_planks", 4, "minecraft:inventory") }
        crafts.add(steve, here, "minecraft:oak_planks", 4, "minecraft:crafting")
        crafts.add(alex, here, "minecraft:oak_planks", 4, "minecraft:inventory")
        assertTrue(published.isEmpty(), "nothing before the end of the tick")

        crafts.flush()
        val expected: List<GameEvent> = listOf(
            ItemCrafted(steve, here, "minecraft:oak_planks", 64, "minecraft:inventory"),
            ItemCrafted(steve, here, "minecraft:oak_planks", 4, "minecraft:crafting"),
            ItemCrafted(alex, here, "minecraft:oak_planks", 4, "minecraft:inventory"),
        )
        assertEquals(expected, published)
        crafts.flush()
        assertEquals(3, published.size, "a flush empties the coalescer")
    }

    @Test
    fun `a player's crafts can be published before their departure`() {
        val crafts = CraftCoalescer { published += it }
        crafts.add(steve, here, "minecraft:stick", 4, "minecraft:inventory")
        crafts.add(alex, here, "minecraft:stick", 4, "minecraft:inventory")
        crafts.flush(steve.uuid)
        assertEquals(listOf(steve), published.map { it.player })
        crafts.flush()
        assertEquals(listOf(steve, alex), published.map { it.player })
    }

    @Test
    fun `pickups are summed per item over a 30-second window`() {
        var now = 0L
        val pickups = ItemFlowAggregator({ published += it }, { now })
        pickups.pickedUp(steve, here, "minecraft:cobblestone", 1)
        now = 10_000
        pickups.pickedUp(steve, nether, "minecraft:coal", 2)
        pickups.pickedUp(steve, nether, "minecraft:cobblestone", 3)
        now = 29_950
        pickups.tick()
        assertTrue(published.isEmpty(), "the window is still open")

        now = 30_000
        pickups.tick()
        val expected: List<GameEvent> = listOf(
            ItemsPickedUp(steve, here, "minecraft:cobblestone", 4, 30),
            ItemsPickedUp(steve, here, "minecraft:coal", 2, 30),
        )
        assertEquals(expected, published, "at the window's first position, items in the order first picked up")

        now = 31_000
        pickups.pickedUp(steve, nether, "minecraft:dirt", 1)
        pickups.tick()
        assertEquals(2, published.size, "a new window opens")
    }

    @Test
    fun `a departure or a dimension change cuts one player's window short`() {
        var now = 0L
        val pickups = ItemFlowAggregator({ published += it }, { now })
        pickups.pickedUp(steve, here, "minecraft:dirt", 1)
        pickups.pickedUp(alex, here, "minecraft:dirt", 5)
        now = 4_200
        pickups.flush(steve.uuid)
        assertEquals<List<GameEvent>>(listOf(ItemsPickedUp(steve, here, "minecraft:dirt", 1, 5)), published, "4.2 s rounds up to 5")

        pickups.flush()
        assertEquals(ItemsPickedUp(alex, here, "minecraft:dirt", 5, 5), published.last(), "the stop flushes the rest")
        assertEquals(1, ItemFlowAggregator.windowSeconds(0), "pickups of a single moment")
    }

    @Test
    fun `explosions of one tick within 16 blocks are merged`() {
        val explosions = ExplosionCoalescer { published += it }
        val tnt = ExplosionHappened(here, "minecraft:tnt", alexEntity, 4.0, "destroy_with_decay", 30, 2, listOf(alex), narrator = false)
        val near = here.copy(x = here.x + 10, z = here.z + 10)
        val creeper = ExplosionHappened(near, "minecraft:creeper", zombie, 3.0, "destroy", 12, 1, listOf(steve, alex), narrator = true)
        val far = here.copy(x = here.x + 40)
        val elsewhere = ExplosionHappened(far, "minecraft:tnt", null, 4.0, "destroy", 5, 0, listOf(steve), narrator = false)
        val otherDimension = elsewhere.copy(pos = Pos(nether.dim, here.x, here.y, here.z))

        explosions.add(tnt)
        explosions.add(creeper)
        explosions.add(elsewhere)
        explosions.add(otherDimension)
        assertTrue(published.isEmpty(), "nothing before the end of the tick")
        explosions.flush()

        val merged = ExplosionHappened(here, "minecraft:tnt", alexEntity, 4.0, "destroy_with_decay", 42, 3, listOf(alex, steve), narrator = true)
        assertEquals<List<GameEvent>>(listOf(merged, elsewhere, otherDimension), published)
        assertEquals(alex, (published[0] as ExplosionHappened).player, "the player is the author")
    }

    @Test
    fun `a player author is preferred to a mob when merging`() {
        val mob = ExplosionHappened(here, "minecraft:creeper", zombie, 3.0, "destroy", 1, 0, emptyList(), narrator = false)
        val byAlex = mob.copy(author = alexEntity)
        assertEquals(alexEntity, ExplosionCoalescer.merge(mob, byAlex).author)
        assertEquals(alexEntity, ExplosionCoalescer.merge(byAlex, mob).author)
    }
}
