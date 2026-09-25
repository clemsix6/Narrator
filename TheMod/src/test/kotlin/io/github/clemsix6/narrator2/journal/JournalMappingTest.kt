package io.github.clemsix6.narrator2.journal

import io.github.clemsix6.narrator2.event.ChatMessage
import io.github.clemsix6.narrator2.event.Envelope
import io.github.clemsix6.narrator2.event.EventCatalog
import io.github.clemsix6.narrator2.event.EventTable
import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.event.ItemCount
import io.github.clemsix6.narrator2.event.NarratorSpoke
import io.github.clemsix6.narrator2.event.PlayerJoined
import io.github.clemsix6.narrator2.event.PlayerLeft
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.event.PositionSampled
import io.github.clemsix6.narrator2.event.StatsSnapshot
import io.github.clemsix6.narrator2.testing.SampleEvents
import io.github.clemsix6.narrator2.testing.SampleEvents.alex
import io.github.clemsix6.narrator2.testing.SampleEvents.steve
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every journal type lands in its table with the exact values of its columns (M1_M2 §3.2). The sample
 * of each type comes from [SampleEvents]; the table and the meaning of the generic columns come from
 * [EventCatalog].
 */
class JournalMappingTest {
    private val fixture = JournalFixture()

    @AfterEach
    fun close() = fixture.close()

    /** The generic columns expected for a sample of the `events` table. */
    private data class Generic(val type: String, val subject: String?, val amount: Double?, val other: String?, val detail: String?)

    private val steveId = steve.uuid.toString()
    private val here = """{"dim":"minecraft:overworld","x":120,"y":64,"z":-330}"""

    /** In the order of [SampleEvents.all], for the samples whose table is `events`. */
    private val expected = listOf(
        Generic("block_break", "minecraft:diamond_ore", null, "minecraft:iron_pickaxe", """{"creative":false,"correct_tool":true}"""),
        Generic(
            "block_break", "minecraft:oak_planks", null, null,
            """{"creative":false,"correct_tool":true,"placed_by":"${alex.uuid}"}""",
        ),
        Generic("block_place", "minecraft:cobblestone", null, "minecraft:cobblestone", null),
        Generic("fluid_place", "minecraft:water", null, "minecraft:water_bucket", null),
        Generic("fire_lit", "minecraft:fire", null, "minecraft:flint_and_steel", null),
        Generic("portal_lit", "minecraft:nether_portal", null, "minecraft:flint_and_steel", null),
        Generic("end_portal_lit", null, null, null, null),
        Generic("container_open", "minecraft:generic_9x3", null, "minecraft:chest", """{"title":"Loot","spectator":false}"""),
        Generic("loot_open", "minecraft:chests/simple_dungeon", null, null, null),
        Generic(
            "explosion", "minecraft:tnt", 4.0, "Steve",
            """{"interaction":"destroy","blocks_hit":30,"player_blocks_hit":12,"nearby":["Steve","Alex"],"narrator":false}""",
        ),
        Generic("damage_taken", "minecraft:mob_attack", 7.5, "minecraft:zombie", """{"hits":3,"blocked":false,"health_after":12.5}"""),
        Generic(
            "damage_dealt", "minecraft:zombie", 6.0, "minecraft:iron_sword",
            """{"damage_type":"minecraft:player_attack","victim_health":14.0}""",
        ),
        Generic(
            "death", "minecraft:mob_attack", 12.0, "minecraft:zombie",
            """{"message":"Steve was slain by Zombie","kept_inventory":false,"narrator":false}""",
        ),
        Generic("kill", "minecraft:zombie", null, "minecraft:iron_sword", """{"pvp":false,"tamed":false}"""),
        Generic("kill", "minecraft:player", null, "minecraft:bow", """{"pvp":true,"victim_name":"Alex","tamed":false}"""),
        Generic("projectile_hit", "minecraft:arrow", null, "minecraft:zombie", """{"shooter":"Steve","weapon":"minecraft:bow"}"""),
        Generic(
            "consume", "minecraft:cooked_beef", 12.0, null,
            """{"nutrition":8,"saturation":12.8,"effects":[],"food_before":10,"health_before":20.0}""",
        ),
        Generic("craft", "minecraft:torch", 16.0, "minecraft:crafting", null),
        Generic("anvil", "minecraft:diamond_sword", 1.0, "minecraft:diamond_sword", """{"renamed_to":"Excalibur"}"""),
        Generic("grindstone", "minecraft:bow", null, null, null),
        Generic("enchant", "minecraft:diamond_pickaxe", 3.0, null, """{"enchantments":{"minecraft:efficiency":3}}"""),
        Generic(
            "trade", "minecraft:emerald", 1.0, "minecraft:librarian",
            """{"cost_a":{"item":"minecraft:paper","count":24},"level":2,"uses":3,"max_uses":16,"villager":"${SampleEvents.zombie.uuid}"}""",
        ),
        Generic("pickup", "minecraft:cobblestone", 64.0, null, """{"window_s":30}"""),
        Generic("drop", "minecraft:rotten_flesh", 5.0, null, null),
        Generic("fish", "minecraft:cod", 1.0, null, """{"loot":[{"item":"minecraft:cod","count":1}]}"""),
        Generic("breed", "minecraft:cow", null, null, null),
        Generic("tame", "minecraft:wolf", null, null, """{"name":"Rex"}"""),
        Generic("stat", "minecraft.custom:minecraft.bell_ring", 1.0, null, null),
        Generic("advancement", "minecraft:story/mine_diamond", null, "task", """{"title":"Diamonds!"}"""),
        Generic("dimension_change", "minecraft:the_nether", null, "minecraft:overworld", """{"via":"nether_portal","from":$here}"""),
        Generic("teleport", null, 780.0, null, """{"from":$here}"""),
        Generic("sleep_start", "minecraft:red_bed", null, null, null),
        Generic("sleep_end", null, null, null, """{"night_skipped":true}"""),
        Generic("spawn_set", "minecraft:overworld", null, null, """{"forced":false,"cleared":false,"point":$here}"""),
        Generic("mount", "minecraft:horse", null, null, """{"vehicle_uuid":"00000000-0000-4000-8000-00000000000b"}"""),
        Generic("dismount", "minecraft:horse", null, null, """{"vehicle_uuid":"00000000-0000-4000-8000-00000000000b"}"""),
        Generic("biome_enter", "minecraft:cherry_grove", null, null, """{"first_visit":true}"""),
        Generic("structure_enter", "minecraft:village_plains", null, null, """{"first_visit":true}"""),
        Generic("structure_leave", "minecraft:village_plains", null, null, null),
        Generic("underground", null, -40.0, null, null),
        Generic("surface", null, 64.0, null, null),
        Generic("health_low", null, 3.0, null, null),
        Generic("idle", null, 300.0, null, null),
        Generic("active", null, 540.0, null, null),
        Generic("join", null, null, null, """{"first_time":false}"""),
        Generic("leave", null, 3600.0, "leave", null),
        Generic("respawn", null, null, null, """{"alive":false}"""),
        Generic("command", "tp", null, null, """{"text":"tp Steve 0 64 0"}"""),
        Generic("command", "msg", null, null, null),
    )

    private fun publishAll(): List<Envelope> = SampleEvents.all.map { event ->
        fixture.clock.advance(java.time.Duration.ofSeconds(1))
        fixture.gameTime += 20
        fixture.publish(event)
    }

    @Test
    fun `every sample of the events table becomes its exact row`() {
        val envelopes = publishAll().filter { EventCatalog.of(it.event).table == EventTable.EVENTS }
        assertEquals(expected.size, envelopes.size, "one expectation per sample of the events table")
        val rows = fixture.values(
            "SELECT id, epoch_ms(ts), game_time, type, player_uuid, player, session_id, dim, x, y, z, " +
                "subject, amount, other, detail::VARCHAR FROM events ORDER BY id",
        )
        assertEquals(envelopes.size, rows.size)
        // The only session of the samples: from the join to the leave, both included.
        val sessionOf = envelopes.associate { envelope ->
            envelope.id to (envelope.event as? PlayerJoined)?.sessionId.let { it ?: (envelope.event as? PlayerLeft)?.sessionId }
        }
        envelopes.zip(expected).zip(rows).forEach { (pair, row) ->
            val (envelope, want) = pair
            val event = envelope.event
            val what = "${event.type} #${envelope.id}"
            assertEquals(
                listOf(
                    envelope.id, envelope.ts, envelope.gameTime, want.type, event.player?.uuid, event.player?.name, sessionOf[envelope.id],
                    event.pos?.dim, event.pos?.x, event.pos?.y, event.pos?.z,
                    want.subject, want.amount, want.other, want.detail,
                ),
                row,
                what,
            )
        }
    }

    @Test
    fun `the generic columns follow the catalog's documentation`() {
        publishAll()
        fixture.rows("SELECT type, subject, amount, other, detail::VARCHAR FROM events ORDER BY id") {
            listOf(getString(1), getString(2), getObject(3), getString(4), getString(5))
        }.forEach { (type, subject, amount, other, detail) ->
            val entry = EventCatalog.of(type as String)!!
            assertEquals(EventTable.EVENTS, entry.table, type)
            if (entry.subject == null) assertNull(subject, "$type has no documented subject")
            if (entry.amount == null) assertNull(amount, "$type has no documented amount")
            if (entry.other == null) assertNull(other, "$type has no documented other")
            val keys = (detail as String?)?.let { Json.parseToJsonElement(it).jsonObject.keys.toList() }.orEmpty()
            assertEquals(entry.detail.keys.filter { it in keys }, keys, "detail keys of $type: documented, in the catalog's order")
        }
    }

    @Test
    fun `chat, private messages, positions and stat totals go to their own tables`() {
        val envelopes = publishAll()
        fun idOf(match: (GameEvent) -> Boolean) = envelopes.first { match(it.event) }.id
        fun tsOf(id: Long) = Instant.ofEpochMilli(envelopes.first { it.id == id }.ts)

        val public = idOf { it is ChatMessage && it.channel.id == "public" }
        val pray = idOf { it is ChatMessage && it.channel.id == "pray" }
        val narrator = idOf { it is NarratorSpoke && it.operator == null }
        val operator = idOf { it is NarratorSpoke && it.operator != null }
        assertEquals(
            listOf(
                listOf(public, tsOf(public), "public", steve.uuid, "Steve", null, "narrateur, t'es nul", null, null),
                listOf(pray, tsOf(pray), "pray", steve.uuid, "Steve", null, "rends-moi mes diamants", null, null),
                listOf(narrator, tsOf(narrator), "narrator", null, null, null, "Steve, ta pioche pleure.", 42L, null),
                listOf(operator, tsOf(operator), "operator", null, null, listOf("Alex"), "Silence.", null, "Clement"),
            ),
            fixture.values("SELECT * FROM chat ORDER BY id"),
        )

        val whisper = idOf { it is ChatMessage && it.channel.id == "whisper" }
        assertEquals(
            listOf(listOf(whisper, tsOf(whisper), steve.uuid, "Steve", listOf("Alex"), "la base est derrière la cascade")),
            fixture.values("SELECT * FROM private_messages"),
        )
        assertEquals(0, fixture.count("chat WHERE text LIKE '%cascade%'"), "a private message never goes to chat")
        assertEquals(0, fixture.count("events WHERE type IN ('chat', 'whisper', 'narrator_say', 'position', 'stats_snapshot')"))

        val position = envelopes.single { it.event is PositionSampled }
        assertEquals(
            listOf(
                listOf(
                    Instant.ofEpochMilli(position.ts), position.gameTime, steve.uuid, "Steve", null,
                    "minecraft:overworld", 120, 64, -330, "minecraft:plains", null, false,
                    15.toByte(), 0.toByte(), 20.0f, 20.toByte(), null, "moved",
                ),
            ),
            fixture.values("SELECT * FROM positions"),
        )

        val stats = envelopes.single { it.event is StatsSnapshot }
        assertEquals(
            listOf(listOf(steve.uuid, "minecraft.mined:minecraft.stone", 1_204L, Instant.ofEpochMilli(stats.ts))),
            fixture.values("SELECT * FROM stat_totals"),
        )
    }

    @Test
    fun `stat totals keep the latest value of each stat`() {
        fixture.publish(StatsSnapshot(steve, mapOf("a" to 1L, "b" to 2L)))
        fixture.clock.advance(java.time.Duration.ofMinutes(5))
        fixture.publish(StatsSnapshot(steve, mapOf("b" to 5L, "c" to 3L)))
        fixture.publish(StatsSnapshot(alex, mapOf("a" to 9L)))
        assertEquals(
            listOf(listOf("Steve", "a", 1L), listOf("Steve", "b", 5L), listOf("Steve", "c", 3L), listOf("Alex", "a", 9L)),
            fixture.rows("SELECT player_uuid, stat, value FROM stat_totals ORDER BY player_uuid, stat") {
                listOf(if (getObject(1) == steve.uuid) "Steve" else "Alex", getString(2), getLong(3))
            },
        )
    }

    @Test
    fun `detail leaves out null values and writes positions and stacks as objects`() {
        assertNull(JournalMapping.detail("a" to null, "b" to null))
        assertEquals(
            """{"b":1,"c":[{"item":"x","count":2}],"d":{"dim":"d","x":1,"y":2,"z":3},"e":"${UUID(0, 1)}"}""",
            JournalMapping.detail("a" to null, "b" to 1, "c" to listOf(ItemCount("x", 2)), "d" to Pos("d", 1, 2, 3), "e" to UUID(0, 1)),
        )
    }

    @Test
    fun `tool arguments that are not JSON are kept as a JSON string`() {
        assertEquals("""{"text":"hi"}""", JournalMapping.jsonOrString("""{"text":"hi"}"""))
        assertEquals(""""{not json"""", JournalMapping.jsonOrString("{not json"))
        assertTrue(Json.parseToJsonElement(JournalMapping.jsonOrString("a \"quoted\" \n text")).toString().isNotEmpty())
    }
}
