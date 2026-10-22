package io.github.clemsix6.narrator2.style

import io.github.clemsix6.narrator2.memory.stats.TicsTest
import java.time.Instant

/** Labels and lines for the tests of the watch of the style (M6-03). */
object StyleSamples {
    val START: Instant = Instant.parse("2026-10-20T20:00:00Z")

    /** The players of the M4 acceptance. */
    val M4_PLAYERS = listOf("Bartok", "Mimosa", "Pistache", "Opaline", "Lisette", "Gaspard", "Firmin")

    /** The labels of the line [id], said at [START] plus [id] seconds. */
    fun labels(
        id: Long,
        register: ComicRegister = ComicRegister.PLAIN,
        crutches: List<String> = emptyList(),
        dark: Double = 0.0,
        cruel: Double = 0.0,
        flat: Double = 0.0,
        outOfCharacter: Double = 0.0,
        repeats: Double = 0.0,
        obscure: Double = 0.0,
        text: String = "La ligne numéro $id du Narrateur.",
    ) = LineLabels(id, START.plusSeconds(id), text, "reflex:claude-haiku-5-5", register, dark, cruel, flat, outOfCharacter, obscure, repeats, crutches, null, 900)

    /** A watch holding [texts] as the lines 1, 2, 3… of the Director, with [names] known. */
    fun watch(texts: List<String>, names: Collection<String> = emptyList(), tone: ToneWatch? = null): StyleWatchImpl =
        StyleWatchImpl(tone).also { watch ->
            watch.named(names)
            texts.forEachIndexed { i, text -> watch.said(i + 1L, START.plusSeconds(i + 1L), text) }
        }

    /** The 66 lines of the M4 acceptance (`M6.md`, « Essais »). */
    fun m4(): List<String> = TicsTest.corpus("m4-recipe-lines.txt")

    /** The 17 lines of the M5 acceptance (`M5-22`), the kind before a line above the hotbar. */
    fun m5(): List<String> = TicsTest.corpus("m5-recipe-lines.txt")
}
