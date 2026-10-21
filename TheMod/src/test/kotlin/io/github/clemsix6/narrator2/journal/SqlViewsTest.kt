package io.github.clemsix6.narrator2.journal

import io.github.clemsix6.narrator2.event.BlockBroken
import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.event.ChatMessage
import io.github.clemsix6.narrator2.event.NarratorChannel
import io.github.clemsix6.narrator2.event.NarratorSpoke
import io.github.clemsix6.narrator2.event.PlayerJoined
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.PositionSampled
import io.github.clemsix6.narrator2.event.SampleReason
import io.github.clemsix6.narrator2.journal.SqlDataset.SECRET
import io.github.clemsix6.narrator2.memory.Decay
import io.github.clemsix6.narrator2.memory.MemoryDataset
import io.github.clemsix6.narrator2.memory.stats.Favor
import io.github.clemsix6.narrator2.testing.TestClock
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.abs
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every view and macro of `views.sql` (M1_M2 §6.4, and the memory's views of M3 §13) on the rows the Journal
 * writes for [SqlDataset]; the memory's strength and favor also against `Decay` and `Favor` on known values.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqlViewsTest {
    private val fixture = SqlDataset.journal()

    @AfterAll
    fun close() = fixture.close()

    private fun values(sql: String) = fixture.values(sql)

    // ── Views ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `timeline has events and chat, without pickups, stats or private messages`() {
        val kinds = values("SELECT DISTINCT kind FROM timeline").map { it.single() as String }.toSet()
        assertTrue(kinds.containsAll(listOf("join", "block_break", "kill", "death", "chat:public", "chat:pray", "chat:narrator")), "$kinds")
        assertTrue(kinds.none { it in setOf("pickup", "stat", "whisper", "chat:whisper") }, "$kinds")
        assertEquals(
            listOf(listOf<Any?>("Bartok", "block_break", "diamond_ore → iron_pickaxe", "overworld", 100, -40, 200)),
            values("SELECT player, kind, what, dim, x, y, z FROM timeline WHERE kind = 'block_break' ORDER BY ts LIMIT 1"),
        )
        assertEquals(
            listOf(listOf<Any?>(null, "Bartok, ta pioche pleure.", null)),
            values("SELECT player, what, dim FROM timeline WHERE kind = 'chat:narrator'"),
        )
        assertFalse(values("SELECT * FROM timeline").flatten().any { it == SECRET })
    }

    @Test
    fun `chat_log has public chat, prayers and the Narrator, never private messages`() {
        assertEquals(
            listOf(
                listOf<Any?>("public", "player", "Bartok", null, "narrateur, t'es nul"),
                listOf<Any?>("pray", "player", "Bartok", null, "rends-moi mes diamants"),
                listOf<Any?>("narrator", "narrator", null, null, "Bartok, ta pioche pleure."),
            ),
            values("SELECT channel, speaker, player, recipients, text FROM chat_log ORDER BY ts"),
        )
    }

    /** M2 review, finding 15: the Narrator's lines have no player name, so a player called NARRATOR is only himself. */
    @Test
    fun `a player called NARRATOR is not the Narrator`() {
        JournalFixture(clock = TestClock(Instant.now())).use { journal ->
            val impostor = PlayerRef(UUID.fromString("00000000-0000-4000-8000-0000000000e1"), "NARRATOR")
            journal.publish(PlayerJoined(impostor, SqlDataset.ow(0, 64, 0), sessionId = 1, firstTime = true, isOp = false))
            journal.publish(ChatMessage(impostor, ChatChannel.PUBLIC, "je suis le vrai narrateur", emptyList()))
            journal.publish(NarratorSpoke(NarratorChannel.NARRATOR, "Imposteur.", null, turnId = 7, operator = null))
            journal.publish(NarratorSpoke(NarratorChannel.OPERATOR, "Silence.", listOf(impostor), turnId = null, operator = "Clement"))
            assertEquals(
                listOf(
                    listOf<Any?>("player", "NARRATOR", "je suis le vrai narrateur"),
                    listOf<Any?>("narrator", null, "Imposteur."),
                    listOf<Any?>("operator", null, "Silence."),
                ),
                journal.values("SELECT speaker, player, text FROM chat_log ORDER BY ts, id"),
            )
            assertEquals(listOf(listOf<Any?>("je suis le vrai narrateur")), journal.values("SELECT text FROM chat_log WHERE player = 'NARRATOR'"))
            assertEquals(listOf(listOf<Any?>("Imposteur.")), journal.values("SELECT text FROM chat_log WHERE speaker = 'narrator'"))
            assertEquals(
                listOf(listOf<Any?>("join"), listOf<Any?>("chat:public")),
                journal.values("SELECT kind FROM recent('narrator') ORDER BY kind DESC"),
                "the impostor's timeline, without the Narrator's lines",
            )
            assertEquals(
                listOf(listOf<Any?>("chat:narrator", null), listOf<Any?>("chat:operator", null)),
                journal.values("SELECT kind, player FROM timeline WHERE kind IN ('chat:narrator', 'chat:operator') ORDER BY kind"),
            )
        }
    }

    @Test
    fun `narrator_actions keeps speech and world calls only`() {
        assertEquals(
            listOf(listOf<Any?>("say", listOf("Bartok"), "ok", 42L), listOf<Any?>("strike_lightning", listOf("Bartok"), "ok", 42L)),
            values("SELECT tool, targets, status, turn_id FROM narrator_actions ORDER BY tool"),
        )
    }

    @Test
    fun `mining flags ores and ancient debris`() {
        assertEquals(
            listOf(
                listOf<Any?>("diamond_ore", true, "iron_pickaxe", "overworld", -40),
                listOf<Any?>("deepslate_iron_ore", true, "iron_pickaxe", "overworld", -41),
                listOf<Any?>("stone", false, "iron_pickaxe", "overworld", -41),
                listOf<Any?>("stone", false, "iron_pickaxe", "overworld", -41),
                listOf<Any?>("stone", false, "iron_pickaxe", "overworld", -41),
                listOf<Any?>("oak_planks", false, null, "overworld", 64),
                listOf<Any?>("dirt", false, null, "overworld", 64),
                listOf<Any?>("ancient_debris", true, "diamond_pickaxe", "the_nether", 15),
            ),
            values("SELECT block, ore, tool, dim, y FROM mining WHERE player = 'Bartok' ORDER BY ts"),
        )
    }

    @Test
    fun `building, deaths, kills and damage`() {
        assertEquals(
            listOf(listOf<Any?>("Mimosa", "oak_planks", 10), listOf<Any?>("Bartok", "cobblestone", 1)),
            values("SELECT player, block, x FROM building ORDER BY ts"),
        )
        assertEquals(
            listOf(listOf<Any?>("Bartok", "arrow", "skeleton", "Bartok was shot by Skeleton", false, "overworld", 25)),
            values("SELECT player, cause, killer, message, narrator, dim, x FROM deaths"),
        )
        assertEquals(
            listOf(
                listOf<Any?>("zombie", null, "iron_sword", false, false),
                listOf<Any?>("zombie", null, "iron_sword", false, false),
                listOf<Any?>("skeleton", null, "bow", false, false),
                listOf<Any?>("player", "Mimosa", "iron_sword", true, false),
                listOf<Any?>("zombie", null, "iron_sword", false, false),
            ),
            values("SELECT victim, victim_name, weapon, pvp, tamed FROM kills ORDER BY ts"),
        )
        assertEquals(
            listOf(listOf<Any?>("taken", "arrow", 6.5, "skeleton"), listOf<Any?>("dealt", "player_attack", 4.0, "skeleton")),
            values("SELECT direction, damage_type, amount, other FROM damage ORDER BY direction DESC"),
        )
    }

    @Test
    fun `trades, meals, crafts and advancements`() {
        assertEquals(listOf(listOf<Any?>("emerald", 1, "24 paper", null, "librarian")), values("SELECT item, count, cost_a, cost_b, profession FROM trades"))
        assertEquals(listOf(listOf<Any?>("cooked_beef", 8)), values("SELECT item, nutrition FROM meals"))
        assertEquals(listOf(listOf<Any?>("torch", 16, "crafting")), values("SELECT item, count, station FROM crafts"))
        assertEquals(listOf(listOf<Any?>("story/mine_diamond", "Diamonds!", "task")), values("SELECT advancement, title, frame FROM advancements"))
    }

    @Test
    fun `visits and travels`() {
        assertEquals(
            listOf(listOf<Any?>("biome", "cherry_grove", true, 200), listOf<Any?>("structure", "village_plains", false, 210)),
            values("SELECT kind, place, first_visit, x FROM visits ORDER BY ts"),
        )
        assertEquals(
            listOf(
                listOf<Any?>("nether_portal", "overworld", 16, 64, 16, "the_nether", 2, 70, 2, null),
                listOf<Any?>("teleport", "the_nether", 3, 70, 3, "the_nether", 900, 70, 2, 897.0),
            ),
            values("SELECT via, from_dim, from_x, from_y, from_z, dim, x, y, z, distance FROM travels ORDER BY ts"),
        )
    }

    @Test
    fun `griefs are blocks another player had placed`() {
        assertEquals(
            listOf(listOf<Any?>("Bartok", "oak_planks", "Mimosa", "overworld", 10, 64, 10)),
            values("SELECT player, block, owner, dim, x, y, z FROM griefs"),
        )
    }

    @Test
    fun `sessions, play time and stats`() {
        assertEquals(
            listOf(listOf<Any?>("Bartok", true, null, "overworld", null), listOf<Any?>("Mimosa", false, "leave", "overworld", "overworld")),
            values("SELECT player, online, end_reason, join_dim, leave_dim FROM play_sessions ORDER BY player"),
        )
        assertEquals(listOf(listOf<Any?>(60.0)), values("SELECT minutes FROM play_sessions WHERE player = 'Mimosa'"))
        assertEquals(
            listOf(listOf<Any?>("Bartok", 1L), listOf<Any?>("Mimosa", 1L)),
            values("SELECT player, CAST(sum(sessions) AS BIGINT) FROM playtime GROUP BY player ORDER BY player"),
        )
        assertEquals(listOf(listOf<Any?>(1.0)), values("SELECT sum(hours) FROM playtime WHERE player = 'Mimosa'"))
        assertEquals(
            listOf(listOf<Any?>("Bartok", "killed:zombie", 2L), listOf<Any?>("Bartok", "mined:stone", 1_204L)),
            values("SELECT player, stat, value FROM player_stats ORDER BY stat"),
        )
    }

    // ── Macros ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `recent is a player's timeline over the last minutes, whatever the case of the name`() {
        val expected = listOf(listOf<Any?>("chat:public"), listOf<Any?>("chat:pray"), listOf<Any?>("kill"))
        assertEquals(expected, values("SELECT kind FROM recent('bartok', 15)"))
        assertEquals(expected, values("SELECT kind FROM recent('BARTOK', 15)"))
        assertTrue(values("SELECT kind FROM recent('Bartok')").size >= 3, "30 minutes by default")
    }

    @Test
    fun `hotspots finds where a player mines, or does anything else`() {
        assertEquals(
            listOf(
                listOf<Any?>("overworld", 112L, 208L, 5L, -41, -40),
                listOf<Any?>("overworld", 16L, 16L, 2L, 64, 64),
                listOf<Any?>("the_nether", 16L, 16L, 1L, 15, 15),
            ),
            values("SELECT dim, x, z, n, y_min, y_max FROM hotspots('Bartok')"),
        )
        assertEquals(listOf(listOf<Any?>("overworld", 24L, 24L, 5L)), values("SELECT dim, x, z, n FROM hotspots('bartok', 'kill', 16)"))
        assertEquals(listOf(listOf<Any?>(7L), listOf<Any?>(1L)), values("SELECT n FROM hotspots('Bartok', 'block_break', 1000)"))
    }

    @Test
    fun `kill_stats counts kills by victim, since a time`() {
        assertEquals(
            listOf(listOf<Any?>("zombie", 3L, false), listOf<Any?>("player", 1L, true), listOf<Any?>("skeleton", 1L, false)),
            values("SELECT victim, kills, pvp FROM kill_stats('Bartok')"),
        )
        assertEquals(listOf(listOf<Any?>("zombie", 1L)), values("SELECT victim, kills FROM kill_stats('Bartok', now() - INTERVAL 15 MINUTE)"))
        assertEquals(emptyList(), values("SELECT * FROM kill_stats('Mimosa')"))
    }

    @Test
    fun `sleep_spots lists beds and respawn points`() {
        assertEquals(
            listOf(listOf<Any?>("bed", "overworld", 12, 64, 12, 2L), listOf<Any?>("respawn_point", "overworld", 12, 64, 12, 1L)),
            values("SELECT kind, dim, x, y, z, times FROM sleep_spots('bartok')"),
        )
    }

    @Test
    fun `trail follows a player's sampled positions`() {
        assertEquals(
            listOf(
                listOf<Any?>("overworld", 0, 0, "plains", "join", null),
                listOf<Any?>("overworld", 30, 40, "plains", "moved", 50L),
                listOf<Any?>("the_nether", 2, 2, "nether_wastes", "dimension", null),
            ),
            values("SELECT dim, x, z, biome, reason, moved FROM trail('Bartok', 120)"),
        )
        assertEquals(emptyList(), values("SELECT * FROM trail('Bartok', 1)"))
    }

    @Test
    fun `columns_of describes a table or a view, never a hidden table`() {
        assertEquals(
            listOf("id", "ts", "player", "block", "ore", "y", "dim", "x", "z", "tool"),
            values("SELECT name FROM columns_of('Mining')").map { it.single() },
        )
        assertEquals(listOf(listOf<Any?>("ore", "BOOLEAN", "true for ores and ancient debris")), values("SELECT * FROM columns_of('mining') WHERE name = 'ore'"))
        assertEquals(listOf(listOf<Any?>("players", "latest known name")), values("SELECT 'players', comment FROM columns_of('players') WHERE name = 'name'"))
        Journal.HIDDEN_FROM_MODEL.forEach { table ->
            assertEquals(emptyList(), values("SELECT * FROM columns_of('$table')"), table)
            assertEquals(emptyList(), values("SELECT * FROM columns_of(upper('$table'))"), table)
        }
    }

    @Test
    fun `ago and bare`() {
        assertEquals(
            listOf(listOf<Any?>("42s ago", "5m ago", "3h ago", "4d ago", null, "0s ago")),
            values(
                "SELECT ago(now() - INTERVAL 42 SECOND), ago(now() - INTERVAL 5 MINUTE), ago(now() - INTERVAL 3 HOUR), " +
                    "ago(now() - INTERVAL 4 DAY), ago(NULL), ago(now() + INTERVAL 1 MINUTE)",
            ),
        )
        assertEquals(listOf(listOf<Any?>("stone", "create:brass_ore", null)), values("SELECT bare('minecraft:stone'), bare('create:brass_ore'), bare(NULL)"))
    }

    // ── The memory (M3 §13) ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `mem_memories gives refs, current names and strength, without archived or merged memories`() {
        assertEquals(
            listOf(
                listOf<Any?>("M-1", "episode", "Tué par un squelette", listOf("Bartok"), listOf("death", "mob", "insult"), 5.toByte(), (-2).toByte(), 0.5),
                listOf<Any?>(
                    "M-2", "observation", "Mimosa bâtit à côté de Bartok", listOf("Mimosa", "Bartok", "00000000-0000-4000-8000-0000000000ff"),
                    listOf("build"), 2.toByte(), 1.toByte(), Decay.FLOOR,
                ),
            ),
            values("SELECT ref, kind, title, players, tags, importance, valence, strength FROM mem_memories ORDER BY ref"),
        )
        assertEquals(
            listOf(listOf<Any?>("Bartok est mort sous les flèches d'un squelette, puis a insulté le Narrateur.", true)),
            values("SELECT content, occurred < now() - INTERVAL 39 MINUTE FROM mem_memories WHERE list_contains(players, 'Bartok') AND ref = 'M-1'"),
        )
    }

    @Test
    fun `mem_canon gives what the Narrator said, open or closed, without archived entries`() {
        assertEquals(
            listOf(
                listOf<Any?>(
                    "C-10", "promise", null, "active", "Le Narrateur a promis à Mimosa de lui rendre ses planches.", "tu auras tes planches",
                    listOf("Mimosa"), true, null, null,
                ),
                listOf<Any?>(
                    "C-11", "nickname", null, "retired", "Bartok est surnommé « le Pleureur ».", "le Pleureur", listOf("Bartok"), null, true,
                    "remplacé par un autre surnom",
                ),
            ),
            values(
                "SELECT ref, kind, mechanic, status, statement, quote, players, due_at > now(), resolved_at < now(), resolution FROM mem_canon ORDER BY ref",
            ),
        )
        assertEquals(1L, values("SELECT count(*) FROM mem_canon WHERE created_at > now() - INTERVAL 25 MINUTE").single().single())
    }

    /** M4 §3.3, §19: a row the mechanics engine tracks says what it is (challenge, duel or rule); a plan is a kind of its own. */
    @Test
    fun `mem_canon says what the mechanics engine tracks`() {
        JournalFixture(clock = TestClock(Instant.now())).use { journal ->
            runBlocking {
                journal.journal.write(10_000) { connection ->
                    connection.createStatement().use {
                        it.execute(
                            """
                            INSERT INTO canon (id, kind, mechanic, created_at, statement, players, importance, status, resolved_at, resolution, source,
                                               evidence_events, evidence_tools) VALUES
                                (50, 'challenge', 'challenge', now(), 'Le Narrateur a défié tous les joueurs de rapporter un œuf de tortue.', [], 6,
                                 'active', NULL, NULL, 'director', [], []),
                                (52, 'challenge', 'duel', now(), 'Le Narrateur a fait s''affronter Bartok et Mimosa en duel.', [], 6, 'won', now(),
                                 'won by Mimosa', 'engine', [], []),
                                (60, 'law', 'rule', now(), 'Quiconque casse la construction d''un autre est rapetissé.', [], 7, 'active', NULL, NULL,
                                 'director', [], []),
                                (61, 'plan', 'rule', now(), 'Un comité d''accueil attend Bartok à sa prochaine entrée dans le Nether.', [], 5, 'active',
                                 NULL, NULL, 'director', [], []),
                                (62, 'law', NULL, now(), 'Nul ne creuse sous le village.', [], 5, 'active', NULL, NULL, 'director', [], []);
                            """.trimIndent(),
                        )
                    }
                }
            }
            assertEquals(
                listOf(
                    listOf<Any?>("C-50", "challenge", "challenge", "active"),
                    listOf<Any?>("C-52", "challenge", "duel", "won"),
                    listOf<Any?>("C-60", "law", "rule", "active"),
                    listOf<Any?>("C-61", "plan", "rule", "active"),
                    listOf<Any?>("C-62", "law", null, "active"),
                ),
                journal.values("SELECT ref, kind, mechanic, status FROM mem_canon ORDER BY ref"),
            )
            assertEquals(
                listOf(listOf<Any?>("C-62")),
                journal.values("SELECT ref FROM mem_canon WHERE kind = 'law' AND mechanic IS NULL AND status = 'active'"),
                "a law the Director keeps himself, the engine does not enforce it",
            )
        }
    }

    @Test
    fun `mem_interactions names players by their current name, without archived interactions`() {
        assertEquals(
            listOf(
                listOf<Any?>("I-22", "Mimosa", "compliment", 2.toByte(), false, "a remercié le Narrateur", null),
                listOf<Any?>("I-20", "Bartok", "insult", (-2).toByte(), true, "a traité le Narrateur de nul", "narrateur, t'es nul"),
                listOf<Any?>("I-21", "Bartok", "punishment", 0.toByte(), false, "foudroyé pour son insulte", null),
            ),
            values("SELECT ref, player, kind, valence, disrespect, summary, quote FROM mem_interactions ORDER BY ts"),
        )
    }

    @Test
    fun `mem_stats and mem_chronicle`() {
        assertEquals(
            listOf(listOf<Any?>("Bartok", "deaths", 1.0, "0", "arrow")),
            values("SELECT player, stat, value, detail ->> 'absurd', detail -> 'top' ->> '$[0][0]' FROM mem_stats"),
        )
        assertEquals(
            listOf(listOf<Any?>("H-31", "chapter", "La pioche qui pleure", "Bartok mine, meurt sous les flèches et insulte le Narrateur.", true)),
            values("SELECT ref, level, title, summary, period_from < period_to FROM mem_chronicle"),
        )
    }

    /** M3-05 handed over the formula; the view must give what `Favor.read` gives, at the same instant. */
    @Test
    fun `mem_favor gives Favor's favor, trend and lacks of respect`() {
        checkFavor(fixture, listOf(SqlDataset.bartok.uuid, SqlDataset.mimosa.uuid))
        MemoryDataset.journal().use { journal ->
            checkFavor(journal, listOf(MemoryDataset.bartok.uuid, MemoryDataset.mimosa.uuid, MemoryDataset.kelvin.uuid))
            // Kelvin has no interaction: a row, without favor nor trend.
            assertEquals(listOf(listOf<Any?>(null, null, 0L)), journal.values("SELECT favor, trend, disrespect FROM mem_favor WHERE player = 'Kelvin'"))
        }
    }

    /** [players]' row of `mem_favor` against `Favor.read` at the view's `now()`; each has a row, a null favor without interactions. */
    private fun checkFavor(journal: JournalFixture, players: List<UUID>) {
        journal.sync()
        val (view, kotlin) = runBlocking {
            journal.journal.read(10_000) { connection ->
                val view = connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT p.player_uuid, f.favor, f.trend, f.disrespect, epoch_ms(now()) FROM mem_favor f JOIN players p ON p.name = f.player",
                    ).use { rows ->
                        buildMap {
                            while (rows.next()) {
                                put(rows.getObject(1) as UUID, listOf(rows.getObject(2) as Double?, rows.getObject(3) as Double?, rows.getLong(4).toDouble(), rows.getLong(5).toDouble()))
                            }
                        }
                    }
                }
                val now = Instant.ofEpochMilli(view.values.first()[3]!!.toLong())
                view to players.associateWith { Favor.read(connection, it, now)!! }
            }
        }
        assertEquals(players.toSet(), view.keys intersect players.toSet())
        assertTrue(kotlin.values.any { it.favor != null }, "$kotlin")
        kotlin.forEach { (player, reading) ->
            val (favor, trend, disrespect) = view.getValue(player)
            // An open session's clock moves between the view's now() and Favor.read's millisecond: far less than 1e-3.
            if (reading.favor == null) assertEquals(null, favor, "$player") else assertEquals(reading.favor!!, favor!!, 1e-3, "$player favor")
            if (reading.trend == null) assertEquals(null, trend, "$player") else assertEquals(reading.trend!!, trend!!, 1e-3, "$player trend")
            assertEquals(reading.disrespect.toDouble(), disrespect, "$player disrespect")
        }
    }

    /** `Decay.strength` and `Favor`, on clocks and interactions the test sets: the view is checked on known values, not only on the dataset. */
    @Test
    fun `strength and favor follow Decay and Favor on known values`() = JournalFixture().use { journal ->
        val world = 2_000_000L
        val memories = listOf(
            40.0 to world, // just reinforced: 1
            40.0 to world - 40 * 3_600, // one half-life: 0.5
            40.0 to world - 80 * 3_600, // two: 0.25
            4.0 to 0L, // long faded: the floor
            Decay.initialStability(7) to world - 50 * 3_600, // an importance-7 memory, 50 h on
            Decay.initialStability(10) to world - 1_000 * 3_600,
            12.0 to world + 3_600, // reinforced after the saved clock (it is saved every 5 min): 1, never above
        )
        val favorPlayer = UUID.fromString("00000000-0000-4000-8000-0000000000f1")
        val quietPlayer = UUID.fromString("00000000-0000-4000-8000-0000000000f2")
        val played = 40 * 3_600L
        // One interaction every 1 h 40 min of play, valences -3..3 in turn; a punishment that does not count; an archived insult.
        val rated = (0 until 24).map { Favor.Rated(it * 6_000L, it % 7 - 3) }
        runBlocking<Unit> {
            journal.journal.write(10_000) { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        buildString {
                            append("INSERT INTO memory_state VALUES ('world_play_s', '$world', now());\n")
                            append("INSERT INTO players VALUES ('$favorPlayer', 'Favori', now(), now(), 3, $played, false), ('$quietPlayer', 'Discret', now(), now(), 1, 600, false);\n")
                            memories.forEachIndexed { index, (stability, reinforced) ->
                                append(
                                    "INSERT INTO memories (id, kind, created_at, occurred_from, occurred_to, title, content, players, entities, tags, importance, " +
                                        "valence, stability_h, reinforced_play_s, source, evidence_events, evidence_tools) VALUES (${index + 1}, 'episode', now(), " +
                                        "now(), now(), 't', 'c', [], [], [], 5, 0, $stability, $reinforced, 'chronicler', [], []);\n",
                                )
                            }
                            (rated.map { it to true } + (Favor.Rated(39 * 3_600L, 0) to false)).forEachIndexed { index, (interaction, counts) ->
                                append(
                                    "INSERT INTO interactions (id, ts, player_uuid, player, kind, valence, counts, disrespect, summary, played_s, tool_calls, " +
                                        "source, evidence_events) VALUES (${index + 1}, now(), '$favorPlayer', 'Favori', '${if (counts) "conversation" else "punishment"}', " +
                                        "${interaction.valence}, $counts, false, 's', ${interaction.playedSeconds}, [], 'director', []);\n",
                                )
                            }
                            append(
                                "INSERT INTO interactions (id, ts, player_uuid, player, kind, valence, counts, disrespect, summary, played_s, tool_calls, source, " +
                                    "evidence_events, archived) VALUES (99, now(), '$favorPlayer', 'Favori', 'insult', -3, true, true, 's', ${played - 60}, [], 'director', [], true);\n",
                            )
                        },
                    )
                }
            }
        }
        val strengths = journal.values("SELECT strength FROM mem_memories ORDER BY ref").map { it.single() as Double }
        assertEquals(listOf(1.0, 0.5, 0.25, Decay.FLOOR), strengths.take(4))
        memories.zip(strengths).forEach { (memory, strength) ->
            assertEquals(Decay.strength(memory.first, memory.second, world), strength, 1e-12, "$memory")
        }
        assertEquals(listOf(listOf<Any?>(null, null, 0L)), journal.values("SELECT favor, trend, disrespect FROM mem_favor WHERE player = 'Discret'"))
        val (favor, trend, disrespect) = journal.values("SELECT favor, trend, disrespect FROM mem_favor WHERE player = 'Favori'").single()
        assertEquals(Favor.favor(rated, played), favor as Double, 1e-9)
        assertEquals(Favor.trend(rated, played), trend as Double, 1e-9)
        assertTrue(abs(trend) > 1, "a trend worth checking: $trend")
        assertEquals(0L, disrespect, "the archived insult is left out")

        // Without the world clock (the memory never ran), strength is unknown rather than full.
        runBlocking<Unit> { journal.journal.write(10_000) { c -> c.createStatement().use { it.execute("DELETE FROM memory_state") } } }
        assertEquals(List(memories.size) { listOf<Any?>(null) }, journal.values("SELECT strength FROM mem_memories"))
    }

    // ── Order ───────────────────────────────────────────────────────────────────────────────────────

    /** Facts of one tick share their millisecond (T07): `ts, id` keeps the order they happened in, in views and macros. */
    @Test
    fun `facts that share a timestamp keep their order`() =
        JournalFixture(clock = TestClock(Instant.now().truncatedTo(ChronoUnit.MILLIS))).use { journal ->
            // The clock never moves: every fact below has the same ts.
            val bartok = SqlDataset.bartok
            journal.publish(PlayerJoined(bartok, SqlDataset.ow(0, 64, 0), sessionId = 1, firstTime = false, isOp = false))
            val blocks = listOf("stone", "dirt", "gravel", "sand", "clay", "andesite")
            blocks.forEach { block ->
                journal.publish(BlockBroken(bartok, SqlDataset.ow(0, 64, 0), "minecraft:$block", null, creative = false, correctTool = true, placedBy = null))
            }
            listOf("one", "two", "three").forEach { text -> journal.publish(ChatMessage(bartok, ChatChannel.PUBLIC, text, emptyList())) }
            // A straight walk, 50 blocks a step.
            val steps = 20
            repeat(steps) { step ->
                val pos = SqlDataset.ow(30 * step, 64, 40 * step)
                journal.publish(PositionSampled(bartok, pos, "minecraft:plains", null, false, 15, 0, 20.0, 20, null, SampleReason.MOVED))
            }

            val order = listOf(listOf<Any?>("join", null)) + blocks.map { listOf<Any?>("block_break", it) } +
                listOf("one", "two", "three").map { listOf<Any?>("chat:public", it) }
            assertEquals(order, journal.values("SELECT kind, what FROM timeline ORDER BY ts, id"))
            assertEquals(order.map { it[1] }, journal.values("SELECT what FROM recent('Bartok')").map { it.single() })
            assertEquals(listOf(listOf<Any?>("andesite")), journal.values("SELECT block FROM mining ORDER BY ts DESC, id DESC LIMIT 1"))
            assertEquals(listOf(listOf<Any?>("three")), journal.values("SELECT text FROM chat_log ORDER BY ts DESC, id DESC LIMIT 1"))
            assertEquals(
                (0 until steps).map { listOf<Any?>(30 * it, if (it == 0) null else 50L) },
                journal.values("SELECT x, moved FROM trail('Bartok')"),
            )
        }

    // ── Privacy ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `no view reads a hidden table, and only columns_of names one, to leave it out`() {
        val views = values("SELECT view_name, sql FROM duckdb_views() WHERE NOT internal")
        assertEquals(26, views.size, "$views")
        views.forEach { (name, sql) ->
            Journal.HIDDEN_FROM_MODEL.forEach { table -> assertFalse(table in (sql as String).lowercase(), "view $name reads $table") }
        }
        val macros = values(
            "SELECT function_name, macro_definition FROM duckdb_functions() WHERE NOT internal AND function_type IN ('macro', 'table_macro')",
        )
        assertEquals(
            setOf("bare", "ago", "recent", "hotspots", "kill_stats", "sleep_spots", "trail", "columns_of"),
            macros.map { it[0] }.toSet(),
        )
        val naming = macros.filter { (_, definition) -> Journal.HIDDEN_FROM_MODEL.any { it in (definition as String).lowercase() } }
        assertEquals(listOf("columns_of"), naming.map { it[0] })
        assertContains(naming.single()[1] as String, "NOT IN ('private_messages')")
    }

    /**
     * The plan check of model queries cannot see a setting read by a view (DuckDB folds it into a
     * constant): the definitions themselves must read no file and no setting.
     */
    @Test
    fun `no view or macro reads a file or a setting`() {
        val definitions = values("SELECT view_name, sql FROM duckdb_views() WHERE NOT internal") + values(
            "SELECT function_name, macro_definition FROM duckdb_functions() WHERE NOT internal AND function_type IN ('macro', 'table_macro')",
        )
        val words = SqlRunner.FILE_FUNCTION_PREFIXES + SqlRunner.FILE_FUNCTIONS
        definitions.forEach { (name, sql) ->
            words.forEach { word -> assertFalse(word in (sql as String).lowercase(), "$name uses $word") }
        }
    }
}
