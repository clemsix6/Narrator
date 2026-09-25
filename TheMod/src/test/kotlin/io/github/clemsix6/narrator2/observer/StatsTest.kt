package io.github.clemsix6.narrator2.observer

import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.StatsSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Stat snapshots (only what changed) and the import of an existing world's stats files. */
class StatsTest {
    @TempDir
    lateinit var dir: Path

    private val bartok = UUID.fromString("5f0d8a9e-2c4b-4e1f-9a7d-3b6c1e2f4a01")
    private val mimosa = UUID.fromString("5f0d8a9e-2c4b-4e1f-9a7d-3b6c1e2f4a02")
    private val unnamed = UUID.fromString("5f0d8a9e-2c4b-4e1f-9a7d-3b6c1e2f4a03")

    /** The shape of a vanilla stats file (`ServerStatsCounter.toJson`). */
    private val bartokStats = """
        {"stats": {
            "minecraft:mined": {"minecraft:stone": 123, "minecraft:diamond_ore": 4},
            "minecraft:custom": {"minecraft:play_time": 72000, "minecraft:deaths": 2},
            "minecraft:killed_by": {"minecraft:creeper": 1}
        }, "DataVersion": 4700}
    """.trimIndent()

    @Test
    fun `a snapshot holds the values that changed, a missing value being 0`() {
        val previous = mapOf("a" to 1, "b" to 2, "c" to 0)
        val current = mapOf("a" to 1, "b" to 5, "c" to 0, "d" to 7, "e" to 0)
        assertEquals(mapOf("B" to 5L, "D" to 7L), StatsTracker.changes(previous, current) { it.uppercase() })
        assertEquals(mapOf("a" to 1L, "b" to 2L), StatsTracker.changes(emptyMap(), mapOf("a" to 1, "b" to 2, "z" to 0)) { it }, "a first snapshot holds every stat")
    }

    @Test
    fun `a stats file gives the names of the live stats`() {
        val expected = mapOf(
            "minecraft.mined:minecraft.stone" to 123L,
            "minecraft.mined:minecraft.diamond_ore" to 4L,
            "minecraft.custom:minecraft.play_time" to 72_000L,
            "minecraft.custom:minecraft.deaths" to 2L,
            "minecraft.killed_by:minecraft.creeper" to 1L,
        )
        assertEquals(expected, StatsImport.parseStats(bartokStats))
        assertFailsWith<IllegalArgumentException> { StatsImport.parseStats("""{"DataVersion": 4700}""") }
    }

    @Test
    fun `the user cache names players`() {
        val cache = """[{"name":"Bartok","uuid":"$bartok","expiresOn":"2026-10-25 20:00:00 +0200"},{"name":"Broken","uuid":"nope"}]"""
        assertEquals(mapOf(bartok to "Bartok"), StatsImport.parseNames(cache))
    }

    @Test
    fun `players without totals in the Journal are imported in full`() = runTest {
        val stats = dir.resolve("world/stats").createDirectories()
        stats.resolve("$bartok.json").writeText(bartokStats)
        stats.resolve("$mimosa.json").writeText(bartokStats)
        stats.resolve("$unnamed.json").writeText("""{"stats": {"minecraft:custom": {"minecraft:jump": 9}}}""")
        stats.resolve("${UUID.randomUUID()}.json").writeText("not json")
        stats.resolve("notes.txt").writeText("ignored")
        val cache = dir.resolve("usercache.json")
        cache.writeText("""[{"name":"Bartok","uuid":"$bartok","expiresOn":"x"},{"name":"Mimosa","uuid":"$mimosa","expiresOn":"x"}]""")
        val published = mutableListOf<GameEvent>()

        val imported = StatsImport.run(stats, cache, known = { setOf(mimosa) }) { published += it }

        assertEquals(2, imported, "Mimosa has totals already; the unreadable file is skipped")
        val snapshots = published.filterIsInstance<StatsSnapshot>().associateBy { it.player.uuid }
        assertEquals(setOf(bartok, unnamed), snapshots.keys)
        assertEquals(PlayerRef(bartok, "Bartok"), snapshots.getValue(bartok).player)
        assertEquals(123L, snapshots.getValue(bartok).changed["minecraft.mined:minecraft.stone"])
        assertEquals(PlayerRef(unnamed, unnamed.toString()), snapshots.getValue(unnamed).player, "not in the user cache: named by UUID")
    }

    @Test
    fun `nothing is read from the Journal without stats files`() = runTest {
        var asked = false
        val imported = StatsImport.run(dir.resolve("missing"), dir.resolve("usercache.json"), known = { asked = true; emptySet() }) {}
        assertEquals(0, imported)
        assertTrue(!asked, "the Journal is not queried")
    }
}
