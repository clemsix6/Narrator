package io.github.clemsix6.narrator2.storyteller

import io.github.clemsix6.narrator2.journal.JournalFixture
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.memory.stringArray
import io.github.clemsix6.narrator2.memory.update
import io.github.clemsix6.narrator2.testing.TestClock
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.LineLabelsRecord
import io.github.clemsix6.narrator2.trace.ToolCallRecord
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What the Narrator did lately, by lever (M6 §5.4), read from a real Journal. */
class InterventionsTest {
    private val now: Instant = Instant.parse("2026-10-21T20:30:00Z")
    private val fixture = JournalFixture(clock = TestClock(now))
    private var ids = 1L

    @AfterEach
    fun close() = fixture.close()

    @Test
    fun `a call's lever comes from its tool and the sort its arguments name`() {
        fun key(tool: String, args: String?) = Interventions.key(tool, args?.let { Json.parseToJsonElement(it).jsonObject })
        assertEquals("punish:tiny", key("punish", """{"player":"Bartok","punishment":"tiny","severity":2}"""))
        assertEquals("challenge:reach", key("start_challenge", """{"goal":"reach","minutes":10}"""))
        assertEquals("duel", key("propose_duel", "{}"))
        assertEquals("favor:joker", key("favor", """{"player":"Mimosa","favor":"joker"}"""))
        assertEquals("item", key("give_item", null))
        assertEquals("book", key("give_book", "{}"))
        assertEquals("law", key("create_rule", """{"kind":"law"}"""))
        assertEquals("plan", key("create_rule", """{"kind":"plan"}"""))
        assertEquals("module:tag", key("deploy_module", """{"module":"tag"}"""))
        assertEquals("effect:slowness", key("apply_effect", """{"effect":"minecraft:Slowness"}"""))
        assertEquals("attribute:scale", key("set_attribute", """{"attribute":"scale"}"""))
        assertEquals("spawn", key("spawn", "{}"))
        assertEquals("teleport", key("teleport", "{}"))
        assertEquals("prophecy", key("remember", """{"kind":"prophecy"}"""))
        assertNull(key("remember", """{"kind":"promise"}"""), "only a prophecy is a lever")
        assertNull(key("punish", """{"punishment":3}"""), "an argument that is not a text")
        assertNull(key("create_rule", """{"kind":"other"}"""))
        assertNull(key("say", """{"text":"Bonsoir"}"""))
        assertEquals("named item", Interventions.label("item"))
        assertEquals("punish tiny", Interventions.label("punish:tiny"))
    }

    @Test
    fun `the Director's successful levers and the reviewed lines' registers, the latest first, with their targets`() {
        call("punish", """{"player":"Bartok","punishment":"tiny","severity":2}""", ago(hours = 2), listOf("Bartok"))
        call("start_challenge", """{"goal":"obtain","minutes":15}""", ago(hours = 1), listOf("Bartok", "Mimosa"))
        call("favor", """{"player":"Bartok","favor":"pardon"}""", ago(minutes = 30), listOf("Bartok"), status = ToolStatus.DENIED)
        call("say", """{"text":"Bonsoir"}""", ago(minutes = 20), emptyList())
        call("spawn", """{"entity":"zombie","near":"Bartok"}""", ago(minutes = 10), listOf("Bartok"), role = Role.SCOUT)
        call("deploy_module", """{"module":"tag"}""", ago(days = 3), listOf("Bartok"))
        call("punish", """{"player":"Bartok","punishment":3}""", ago(minutes = 12), listOf("Bartok"))
        call("remember", """{"kind":"prophecy","what":"…"}""", ago(minutes = 5), emptyList())
        call("apply_effect", """{"players":["Mimosa"],"effect":"minecraft:Slowness","seconds":30}""", ago(minutes = 15), listOf("Mimosa"))
        line(900, ago(minutes = 25), listOf("Mimosa"))
        label(900, ago(minutes = 25), "divine_bureaucracy")
        label(900, ago(minutes = 24), "fairy_tale")
        line(901, ago(minutes = 50), null)
        label(901, ago(minutes = 50), "game_show")
        line(902, ago(minutes = 40), null)

        val read = read { Interventions.recent(it, now.minus(Duration.ofDays(1))) }
        assertEquals(
            listOf(
                Intervention("prophecy", ago(minutes = 5), emptyList()),
                Intervention("effect:slowness", ago(minutes = 15), listOf("Mimosa")),
                Intervention("say:fairy_tale", ago(minutes = 25), listOf("Mimosa")),
                Intervention("say:game_show", ago(minutes = 50), emptyList()),
                Intervention("challenge:obtain", ago(hours = 1), listOf("Bartok", "Mimosa")),
                Intervention("punish:tiny", ago(hours = 2), listOf("Bartok")),
            ),
            read,
        )
    }

    @Test
    fun `the draws dealt to the Director are read back, never the storyteller's`() {
        decision(ago(minutes = 20), inspired("director", "P-41", "favor:joker", "M-120"))
        decision(ago(minutes = 10), inspired("storyteller", "punish:tiny"))
        decision(ago(minutes = 5), buildJsonObject { put("role", "director") }) // no keys
        decision(ago(minutes = 4), inspired("director", "law"), decision = "opener_refused")
        decision(ago(days = 20), inspired("director", "duel"))
        val dealt = read { Interventions.dealt(it, now.minus(Duration.ofDays(14))) }
        assertEquals(listOf(Dealt(ago(minutes = 20), setOf("P-41", "favor:joker", "M-120"))), dealt)
    }

    /** M3 §3.2: the world plays while at least one player is online; an open session runs until now. */
    @Test
    fun `the play timeline is the union of the sessions, an open one running until now`() {
        val from = now.minus(Duration.ofDays(1))
        session(from.minus(Duration.ofHours(1)), from.plus(Duration.ofHours(1)))
        session(ago(hours = 10), ago(hours = 8))
        session(ago(hours = 9), ago(hours = 7))
        session(ago(hours = 1), null)
        val timeline = read { Interventions.timeline(it, from, now) }
        val hour = 3_600L
        assertEquals(5 * hour, timeline.playedSince(from.minus(Duration.ofHours(5))), "1h after from, 3h of two sessions, 1h now")
        assertEquals(4 * hour, timeline.playedSince(ago(hours = 12)))
        assertEquals(hour * 5 / 2, timeline.playedSince(ago(hours = 8, minutes = 30)))
        assertEquals(30 * 60L, timeline.playedSince(ago(minutes = 30)))
        assertEquals(0, timeline.playedSince(now.plusSeconds(60)))
        assertEquals(0, PlayTimeline.none(now).playedSince(ago(days = 3)))
    }

    // ── Rows ────────────────────────────────────────────────────────────────────────────────────────

    private fun ago(days: Long = 0, hours: Long = 0, minutes: Long = 0): Instant =
        now.minus(Duration.ofDays(days)).minus(Duration.ofHours(hours)).minus(Duration.ofMinutes(minutes))

    private fun call(tool: String, args: String, at: Instant, targets: List<String>, status: ToolStatus = ToolStatus.OK, role: Role = Role.DIRECTOR) {
        val id = ids++
        fixture.journal.toolCall(
            ToolCallRecord(id, at, "director-c1", 7, 3, "toolu_$id", role, tool, args, status, "ok", 12, targets, ToolEffect.WORLD),
        )
    }

    private fun line(id: Long, at: Instant, recipients: List<String>?) = write {
        it.update(
            "INSERT INTO chat (id, ts, channel, player_uuid, player, recipients, text, turn_id, operator) VALUES (?, ?, 'narrator', NULL, NULL, ?, 'Une réplique.', 7, NULL)",
            id, at, recipients?.let(::stringArray),
        )
    }

    private fun label(id: Long, at: Instant, register: String) =
        fixture.journal.lineLabels(LineLabelsRecord(id, at, "reflex:claude-haiku-5-5", register, 0.0, 0.0, 0.1, 0.0, 0.0, emptyList(), "Une raison.", 900))

    private fun decision(at: Instant, detail: JsonObject, decision: String = Interventions.INSPIRED) =
        fixture.journal.decision(DecisionRecord(ids++, at, DecisionRecord.Kind.STYLE, decision, "Bartok", "a draw", null, "director-c1", 7, detail))

    private fun inspired(role: String, vararg keys: String) = buildJsonObject {
        put("role", role)
        putJsonArray("keys") { keys.forEach { add(it) } }
    }

    private fun session(joined: Instant, left: Instant?) = write {
        it.update(
            "INSERT INTO sessions (session_id, player_uuid, player, joined_at, left_at) VALUES (?, ?, 'Bartok', ?, ?)",
            ids++, UUID.fromString("00000000-0000-0000-0000-00000000b471"), joined, left,
        )
    }

    private fun write(block: (java.sql.Connection) -> Unit) = runBlocking { fixture.journal.write(5_000) { block(it) } }

    private fun <T> read(query: (java.sql.Connection) -> T): T {
        fixture.sync()
        return runBlocking { fixture.journal.read(5_000, query) }
    }
}
