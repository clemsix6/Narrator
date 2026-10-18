package io.github.clemsix6.narrator2.memory.chronicle

import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.event.EntityRef
import io.github.clemsix6.narrator2.event.BlockBroken
import io.github.clemsix6.narrator2.event.EntityKilled
import io.github.clemsix6.narrator2.event.PlayerJoined
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.event.StructureEntered
import io.github.clemsix6.narrator2.memory.chronicle.ChroniclerFixture.Companion.at
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The exchange log (M3 §7.2): its exact format, what it never shows, its cut into blocks, and `<known>`. */
class ExchangeLogTest {
    private val f = ChroniclerFixture()
    private val log = ExchangeLog(f.services, Sanctions(f.services))

    @AfterEach
    fun close() = f.close()

    @Test
    fun `the log of the reference evening, line by line, then what the memory knows`() = runBlocking<Unit> {
        val e = f.scenario()
        val lines = log.lines(f.chapter, at("20:00"), at("20:10"))
        val blocks = log.blocks(lines, "13", at("20:00"), at("20:10"), at("20:10:05"))
        assertEquals(1, blocks.size)
        val expected = """
            <exchange chapter="13" day="2026-10-01" from="20:00:00" to="20:10:00" now="20:10:05">
            [20:00:01] e${e.joinBartok} JOIN Bartok (overworld 0 64 0)
            [20:00:02] e${e.joinMimosa} JOIN Mimosa (overworld -120 64 330)
            [20:01:05] e${e.insult} CHAT Bartok (addressed 0.92, hostile, provocation): "narrateur t'es nul"
            [20:01:09] e${e.sanction} NARRATOR→all: "Ministère de l'Aviation Divine, guichet des insultes : plainte enregistrée. Sanction : une minute de lenteur."
            [20:01:09] t${e.slowness} apply_effect → Bartok (hostile): ok: slowness II on Bartok for 1m
            [20:02:00] e${e.death} DEATH Mimosa: fall (overworld -120 64 330), inventory dropped
            [20:03:12] e${e.prayer} PRAY Mimosa (addressed 0.97, respectful, request): "rends-moi mes affaires stp"
            [20:03:20] e${e.granted} NARRATOR→Mimosa: "Accordé. Mais la prochaine fois, je garde les bottes. Promis."
            [20:03:21] t${e.boots} give_item → Mimosa (benefit): ok: gave 1x iron_boots to Mimosa
            [20:04:00] e${e.slimes} NARRATOR→all: "Bartok, je te promets une pluie de slimes si tu recommences."
            [20:05:00] e${e.plongeur} NARRATOR→all: "Salut, le Plongeur ! La lave est chaude aujourd'hui."
            [20:06:40] t${e.remembered} remember: ok: remembered ${e.threat} (threat → Mimosa)
            </exchange>
        """.trimIndent()
        assertEquals(expected, blocks.single().text)
        assertEquals(setOf("Bartok", "Mimosa"), blocks.single().players)

        val known = log.known(f.chapter, at("20:00"), at("20:10:05"), blocks.single().players)
        val expectedKnown = """
            <known>
            Players: Bartok, Mimosa
            Open canon (2):
            - ${e.nickname} nickname → Bartok: Le Narrateur a surnommé Bartok « le Plongeur ». — « le Plongeur »
            - ${e.threat} threat → Mimosa (by the director at 20:06): Le Narrateur a menacé Mimosa de garder ses bottes la prochaine fois. — « la prochaine fois, je garde les bottes »
            Open challenges: none
            Already noted in this window (1):
            - ${e.prayed} Mimosa prayer +1 at 20:03 (by the director): a prié le Narrateur de lui rendre ses affaires
            Memories shown to the Narrator in this window (1):
            - ${e.lava} at 20:02 (reminiscence, for e${e.death})
            </known>
        """.trimIndent()
        assertEquals(expectedKnown, known)
    }

    @Test
    fun `private messages never show, and successful lines, reads and calls refused for their arguments are left out`() = runBlocking<Unit> {
        f.scenario()
        val text = log.lines(f.chapter, at("20:00"), at("20:10")).joinToString("\n") { it.text }
        assertEquals(1L, f.count("private_messages"))
        assertFalse("privé" in text, text)
        assertFalse(" say" in text, "a say that succeeded is the NARRATOR line already")
        assertFalse("teleport" in text, "a call refused for its arguments did nothing")
    }

    @Test
    fun `the notable facts follow the salience, pvp, griefs counted, rare ores by run, notable structures, nothing routine`() = runBlocking<Unit> {
        f.chapterRow()
        f.publish("20:00:01", PlayerJoined(f.bartok, ChroniclerFixture.OW, 1, firstTime = true, isOp = false))
        f.publish("20:00:02", PlayerJoined(f.mimosa, ChroniclerFixture.OW, 2, firstTime = false, isOp = false))
        val pvp = f.publish("20:01:00", EntityKilled(f.bartok, Pos("minecraft:overworld", 5, 64, 5), EntityRef("minecraft:player", f.mimosa.uuid, "Mimosa", f.mimosa), "minecraft:iron_sword", pvp = true, tamed = false))
        f.publish("20:01:10", EntityKilled(f.bartok, Pos("minecraft:overworld", 5, 64, 5), EntityRef("minecraft:zombie", null, null), "minecraft:iron_sword", pvp = false, tamed = false))
        val ore = f.publish("20:02:00", BlockBroken(f.bartok, Pos("minecraft:overworld", 10, -50, 10), "minecraft:diamond_ore", "minecraft:iron_pickaxe", false, true, null))
        f.publish("20:02:30", BlockBroken(f.bartok, Pos("minecraft:overworld", 11, -50, 10), "minecraft:diamond_ore", "minecraft:iron_pickaxe", false, true, null))
        f.publish("20:02:40", BlockBroken(f.bartok, Pos("minecraft:overworld", 12, -50, 10), "minecraft:stone", "minecraft:iron_pickaxe", false, true, null))
        val grief = f.publish("20:03:00", BlockBroken(f.mimosa, Pos("minecraft:overworld", 0, 64, 0), "minecraft:oak_planks", null, false, true, f.bartok.uuid))
        repeat(2) { f.publish("20:03:0${it + 1}", BlockBroken(f.mimosa, Pos("minecraft:overworld", it, 64, 0), "minecraft:glass", null, false, true, f.bartok.uuid)) }
        f.publish("20:03:30", BlockBroken(f.mimosa, Pos("minecraft:overworld", 9, 64, 9), "minecraft:dirt", null, false, true, f.mimosa.uuid))
        val chambers = f.publish("20:04:00", StructureEntered(f.bartok, Pos("minecraft:overworld", 300, -20, 200), "minecraft:trial_chambers", firstVisit = false))
        f.publish("20:04:10", StructureEntered(f.bartok, Pos("minecraft:overworld", 300, 70, 200), "minecraft:village_plains", firstVisit = false))
        f.journal.sync()

        val facts = log.lines(f.chapter, at("20:00"), at("20:10"), chat = false).map { it.text }.drop(2)
        assertEquals(
            listOf(
                "[20:01:00] e$pvp PVP Bartok killed Mimosa with iron_sword (overworld 5 64 5)",
                "[20:02:00] e$ore MINED Bartok diamond_ore ×2 (overworld 10 -50 10)",
                "[20:03:00] e$grief GRIEF Mimosa broke 3 blocks placed by Bartok: glass 2, oak_planks 1 (overworld 0 64 0)",
                "[20:04:00] e$chambers STRUCTURE Bartok entered trial_chambers (overworld 300 -20 200)",
            ),
            facts,
        )
    }

    @Test
    fun `a window longer than 24 000 characters is cut into blocks that chain and lose nothing`() = runBlocking<Unit> {
        f.chapterRow()
        f.publish("20:00:01", PlayerJoined(f.bartok, ChroniclerFixture.OW, 1, firstTime = false, isOp = false))
        val sentence = "Le pont de Mimosa tient encore, mais pour combien de temps, personne ne le sait vraiment ici. "
        repeat(150) { i ->
            val time = at("20:01").plusSeconds(2L * i).toString().substring(11, 19)
            f.chat(time, f.bartok, "$i " + sentence.repeat(3))
        }
        f.journal.sync()
        val lines = log.lines(f.chapter, at("20:00"), at("20:10"))
        val blocks = log.blocks(lines, "13", at("20:00"), at("20:10"), at("20:10:05"))

        assertEquals(3, blocks.size)
        blocks.forEach { assertTrue(it.text.length <= ExchangeLog.MAX_CHARS, "${it.text.length} characters") }
        assertEquals(lines.map { it.text }, blocks.flatMap { it.text.lines().drop(1).dropLast(1) })
        assertTrue(blocks[0].text.startsWith("<exchange chapter=\"13\" day=\"2026-10-01\" from=\"20:00:00\" to=\"${ExchangeLog.seconds(blocks[0].to, f.clock.zone)}\""))
        assertTrue("part=\"2 of 3\"" in blocks[1].text.lines().first())
        assertEquals(blocks[0].to, blocks[1].from)
        assertEquals(blocks[1].to, blocks[2].from)
        assertEquals(at("20:10"), blocks[2].to)
    }

    @Test
    fun `a chat line that nobody triaged says so, a message between players too, and an operator's line is the Narrator's`() = runBlocking<Unit> {
        f.chapterRow()
        f.publish("20:00:01", PlayerJoined(f.bartok, ChroniclerFixture.OW, 1, firstTime = false, isOp = false))
        val loose = f.chat("20:01:00", f.bartok, "quelqu'un a du fer ?")
        val between = f.chat("20:01:10", f.bartok, "mimosa t'es où")
        f.signal(between, "20:01:10", f.bartok, 0.1, io.github.clemsix6.narrator2.reflex.Tone.NEUTRAL_NO_TARGET, 0.9, io.github.clemsix6.narrator2.reflex.ChatIntent.BANTER_BETWEEN_PLAYERS)
        val heuristic = f.chat("20:01:20", f.bartok, "narrateur aide moi", ChatChannel.PRAY)
        f.signal(heuristic, "20:01:20", f.bartok, 0.9, io.github.clemsix6.narrator2.reflex.Tone.RESPECTFUL, 0.4, null, source = "heuristic")
        val operator = f.narrator("20:01:30", "Bartok, l'opérateur te salue.", operator = "Clement", turn = null)
        f.journal.sync()
        val text = log.lines(f.chapter, at("20:00"), at("20:10")).map { it.text }
        assertEquals("[20:01:00] e$loose CHAT Bartok (not triaged): \"quelqu'un a du fer ?\"", text[1])
        assertEquals("[20:01:10] e$between CHAT Bartok (between players, banter_between_players): \"mimosa t'es où\"", text[2])
        assertEquals("[20:01:20] e$heuristic PRAY Bartok (addressed 0.9, heuristic): \"narrateur aide moi\"", text[3])
        assertEquals("[20:01:30] e$operator NARRATOR (said by operator Clement)→all: \"Bartok, l'opérateur te salue.\"", text[4])
    }
}
