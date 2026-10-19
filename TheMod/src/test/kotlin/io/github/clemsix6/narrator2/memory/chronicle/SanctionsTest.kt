package io.github.clemsix6.narrator2.memory.chronicle

import io.github.clemsix6.narrator2.event.PlayerJoined
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.InteractionDraft
import io.github.clemsix6.narrator2.memory.InteractionKind
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.memory.chronicle.ChroniclerFixture.Companion.at
import io.github.clemsix6.narrator2.tool.ToolStatus
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** The punishments and benefits of the Director (M3 §6.5) with the favors of the mechanics (M4 §7.1, §7.2). */
class SanctionsTest {
    private val f = ChroniclerFixture()
    private val sanctions = Sanctions(f.services)
    private val log = ExchangeLog(f.services, sanctions)

    @AfterEach
    fun close() = f.close()

    private fun covered(): List<Pair<String, String>> =
        f.rows("SELECT player, kind FROM interactions WHERE source = 'engine' ORDER BY id") { getString(1) to getString(2) }

    @Test
    fun `a favor is a blessing, an offering taken by it included, and the exchange log marks it a benefit`() = runBlocking<Unit> {
        f.chapterRow()
        f.publish("20:00:01", PlayerJoined(f.bartok, ChroniclerFixture.OW, 1, firstTime = false, isOp = false))
        f.publish("20:00:02", PlayerJoined(f.mimosa, ChroniclerFixture.OW, 2, firstTime = false, isOp = false))
        val pardon = f.toolCall("20:01:00", "favor", listOf("Bartok"), "ok: Bartok pardoned: slowness II lifted, scale back to 1", turn = 50)
        // Mimosa offered 10 diamonds; the Director took them with a joker. Her offering is her side: linked to the call or not, the favor is still to cover.
        val joker = f.toolCall("20:02:00", "favor", listOf("Mimosa"), "ok: Mimosa got a joker; offering O-4020 (10x diamond) is yours", turn = 51)
        f.journal.sync()
        f.clock.now = at("20:02:30")
        f.store.addInteraction(
            InteractionDraft(f.mimosa.uuid, InteractionKind.OFFERING, 1, "a offert dix diamants au Narrateur contre un joker", toolCalls = listOf(joker)),
            Author(Source.DIRECTOR, turnId = 51, conversationId = f.chapter),
        )
        // A refused favor did nothing.
        val refused = f.toolCall("20:03:00", "favor", listOf("Bartok"), "DENIED (favor_limit): Bartok had a joker 2h ago", turn = 52, status = ToolStatus.DENIED)
        f.clock.now = at("20:10:05")
        f.journal.sync()

        val found = f.journal.journal.read(5_000) { sanctions.find(it, f.chapter, at("20:00"), at("20:10")) }
        assertEquals(listOf(InteractionKind.BLESSING to "Bartok", InteractionKind.BLESSING to "Mimosa"), found.map { it.kind to it.player.name })

        assertEquals(2, sanctions.cover(f.chapter, at("20:00"), at("20:10"), runId = 7))
        assertEquals(listOf("Bartok" to "blessing", "Mimosa" to "blessing"), covered())
        val summaries = f.rows("SELECT summary FROM interactions WHERE source = 'engine' ORDER BY id") { getString(1) }
        assertEquals("favor « Bartok pardoned: slowness II lifted, scale back to 1 » (t$pardon)", summaries.first())

        assertEquals(0, sanctions.cover(f.chapter, at("20:00"), at("20:10"), runId = 8), "covered once")
        val tools = log.lines(f.chapter, at("20:00"), at("20:10")).filter { it.kind == ExchangeLog.Kind.TOOL }.map { it.text }
        assertEquals(
            listOf(
                "[20:01:00] t$pardon favor → Bartok (benefit): ok: Bartok pardoned: slowness II lifted, scale back to 1",
                "[20:02:00] t$joker favor → Mimosa (benefit): ok: Mimosa got a joker; offering O-4020 (10x diamond) is yours",
                "[20:03:00] t$refused favor → Bartok: DENIED (favor_limit): Bartok had a joker 2h ago",
            ),
            tools,
        )
    }

    @Test
    fun `a favor the Chronicler noted, or one in a turn that punished the player, is not covered as a blessing`() = runBlocking<Unit> {
        f.chapterRow()
        f.publish("20:00:01", PlayerJoined(f.bartok, ChroniclerFixture.OW, 1, firstTime = false, isOp = false))
        f.publish("20:00:02", PlayerJoined(f.mimosa, ChroniclerFixture.OW, 2, firstTime = false, isOp = false))
        val grace = f.toolCall("20:01:00", "favor", listOf("Mimosa"), "ok: Mimosa is under your grace until 20:16 (in 15m)", turn = 50)
        f.guardAllowed("20:02:00", f.bartok, 51)
        f.toolCall("20:02:00", "apply_effect", listOf("Bartok"), "ok: slowness II on Bartok for 1m", turn = 51)
        f.toolCall("20:02:01", "favor", listOf("Bartok"), "ok: Bartok got a joker", turn = 51)
        f.journal.sync()
        f.clock.now = at("20:05")
        f.store.addInteraction(
            InteractionDraft(f.mimosa.uuid, InteractionKind.BLESSING, 0, "a reçu la grâce du Narrateur pour un quart d'heure", toolCalls = listOf(grace)),
            Author(Source.CHRONICLER, runId = 6),
        )
        f.clock.now = at("20:10:05")

        assertEquals(1, sanctions.cover(f.chapter, at("20:00"), at("20:10"), runId = 7))
        assertEquals(listOf("Bartok" to "punishment"), covered(), "the turn punished Bartok: its calls on him are the punishment")
    }
}
