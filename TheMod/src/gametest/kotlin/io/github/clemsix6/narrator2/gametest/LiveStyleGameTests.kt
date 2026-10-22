package io.github.clemsix6.narrator2.gametest

import com.mojang.authlib.GameProfile
import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.core.onServerThread
import io.github.clemsix6.narrator2.director.DirectorState
import io.github.clemsix6.narrator2.director.StyleNoteKind
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.journal.IdKind
import io.github.clemsix6.narrator2.live.GridSummary
import io.github.clemsix6.narrator2.live.IdeaGrade
import io.github.clemsix6.narrator2.live.IdeaJudge
import io.github.clemsix6.narrator2.live.JudgedIdea
import io.github.clemsix6.narrator2.live.LiveBudget
import io.github.clemsix6.narrator2.live.ReplaySituations
import io.github.clemsix6.narrator2.live.ToneJudge
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.mechanics.ChallengeOutcome
import io.github.clemsix6.narrator2.mechanics.ChallengeStatus
import io.github.clemsix6.narrator2.mechanics.ChallengeView
import io.github.clemsix6.narrator2.mechanics.GoalKind
import io.github.clemsix6.narrator2.mechanics.StateStore
import io.github.clemsix6.narrator2.mechanics.challenge.Goals
import io.github.clemsix6.narrator2.mechanics.challenge.Target
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.CanonDraft
import io.github.clemsix6.narrator2.memory.CanonKind
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.memory.ChronicleDraft
import io.github.clemsix6.narrator2.memory.ChronicleLevel
import io.github.clemsix6.narrator2.memory.DocKey
import io.github.clemsix6.narrator2.memory.DuckMemoryState
import io.github.clemsix6.narrator2.memory.InteractionDraft
import io.github.clemsix6.narrator2.memory.InteractionKind
import io.github.clemsix6.narrator2.memory.MemoryDraft
import io.github.clemsix6.narrator2.memory.MemoryJobs
import io.github.clemsix6.narrator2.memory.MemoryKind
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.RefKind
import io.github.clemsix6.narrator2.memory.RelationKind
import io.github.clemsix6.narrator2.memory.SheetSection
import io.github.clemsix6.narrator2.memory.Skill
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.memory.TraitDraft
import io.github.clemsix6.narrator2.memory.enumOfId
import io.github.clemsix6.narrator2.memory.stats.StatsRefresher
import io.github.clemsix6.narrator2.memory.stats.Tics
import io.github.clemsix6.narrator2.runtime.RestartReport
import io.github.clemsix6.narrator2.style.LineLength
import io.github.clemsix6.narrator2.style.StyleReport
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.trace.LineLabelsRecord
import io.github.clemsix6.narrator2.trace.Prices
import io.netty.channel.ChannelFutureListener
import io.netty.channel.embedded.EmbeddedChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.Connection
import net.minecraft.network.chat.ChatType
import net.minecraft.network.chat.PlayerChatMessage
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.server.permissions.LevelBasedPermissionSet
import net.minecraft.server.players.NameAndId
import net.minecraft.stats.Stats
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.GameType
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.portal.TeleportTransition
import net.minecraft.world.phys.Vec3
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.Logger
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Property
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection as JdbcConnection
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.name
import kotlin.random.Random

/**
 * The live evals T4 to T6 and the acceptance of M6 (M6 §15.4, M6-18) against the owner's **real** LLMGW server: the real
 * storyteller (`storyteller.md`, its seven tools, Opus 5.5 at medium), the real Director (`director.md`, its 48 tools), the
 * real reflexes and line review (Haiku 5.5), and the real memory, mechanics and Forge of a GameTest server, with scripted
 * mock players. Costs real tokens.
 *
 * It plays on the world of the acceptance runs ([WORLD]): the evenings of M4-24 and M5-22 written into the fresh world as
 * two past sessions of Gaspard, Lisette, Firmin and the operator Opaline (sheets, canon, Grimoire, review, Chronicle, past
 * chapters, the Narrator's lines and their labels, his levers, the players' events), then the Narrator restarted on it, as
 * a server starts every evening on its world. Then, in this order:
 *
 * - **T4, the programme**: the dream's step 5 alone (the storyteller's writing room), resumed as `MemoryJobs` resumes a
 *   dream cut after its step 4; its ideas graded by the ideas' judge ([IdeaJudge]). They stay: they are T6's programme.
 * - **T5, on demand**: three commissions to the storyteller as the Director writes them (a return after a week, a first
 *   Nether, a quiet evening), through `Workers`, the Director asleep: each report ranks three feasible ideas. Their ideas
 *   are dropped afterwards, so that T6's programme stays T4's.
 * - **T6, the evening**: about 45 minutes of varied mock play with the production settings and the prepared programme,
 *   then its measures (M6-18): silence on heartbeats, the end of turns, the chapter summaries, muted openings on an
 *   arrival, `remember` of intentions, the storyteller commissioned, the programme's ideas dealt then used, the refused
 *   openers, the diversity (§3.7), the tone judge's grid (§4.2) over every line, the modules' faults and times.
 * - **T6q, a quiet evening** (part `QUIET`): about 25 minutes of two players who mine and get bored, the same measures:
 *   what a busy evening never shows, the heartbeats (3 to 8 minutes after the last turn) and the ideas they carry.
 *
 * `-Pnarrator2.live.parts=T4,T5,T6,QUIET` plays some of them (T6 and QUIET play T4 first: their programme). Budgets: each part stops at
 * its own ([T4_BUDGET], [T5_BUDGET], [T6_BUDGET]), the whole run at [RUN_BUDGET] (`-Pnarrator2.live.budget`); past a
 * budget, a task still running is cancelled and the evening's Director put to sleep. `-Pnarrator2.live.dry` plays the
 * seeding, the restart and the players' script for free.
 *
 * The report goes to `build/run/liveGameTest/live-report/style.md`, the transcripts to the world's
 * `narrator2/logs/conversations/`. Run by `gradlew liveGameTest -Pnarrator2.live.filter=narrator2-gametest:live_style_*`
 * only (with the key), never by `runGameTest`, where it does nothing. Owned by M6-18.
 */
class LiveStyleGameTests {

    @GameTest(environment = ENVIRONMENT, structure = FIELD, skyAccess = true, maxTicks = MAX_TICKS)
    fun acceptance(helper: GameTestHelper) {
        if (System.getProperty("narrator2.live") != "true") {
            helper.succeed()
            return
        }
        helper.onEachTick { Thread.sleep(TICK_SLEEP_MS) }
        helper.runScript(MAX_TICKS) { StyleAcceptance(StyleRun(helper)).play(this) }
    }
}

/** The batch of this test: it replaces the settings and restarts the Narrator. */
private const val ENVIRONMENT = "narrator2-gametest:live_style"

/** The test's area, 32 × 32 and open to the sky; the run lays a stone floor at its bottom. */
private const val FIELD = "narrator2-gametest:oracle_field"
private const val FIELD_SIZE = 32

/** A tick sleeps this long: about 20 ticks per second, the pace of a real server. */
private const val TICK_SLEEP_MS = 45L

/** Two hours at 20 ticks per second: the seeding, T4, T5, a 45-minute evening and its judge. */
private const val MAX_TICKS = 144_000

/** Dollars (M6-18: 8 $ for the whole task, the replay and the smoke test included). */
private const val T4_BUDGET = 0.8
private const val T5_BUDGET = 1.2
private const val T6_BUDGET = 4.0
private const val RUN_BUDGET = 6.0

/** T4 and T5: the Director asleep (a task's report then reaches nobody), as F1 of M5-22; production otherwise. */
private const val QUIET_CONFIG = """{ "dashboard": { "enabled": false }, "director": { "enabled": false } }"""

/**
 * The evening: production. The Forge's log at DEBUG only, to count the calls resumed then given up again
 * (FORGE-ABANDON review, finding 4(a)); nothing it does changes.
 */
private const val EVENING_CONFIG = """{ "dashboard": { "enabled": false }, "logging": { "levels": { "Forge": "DEBUG" } } }"""

/** The world of the acceptance runs, in the test's resources. */
private const val WORLD = "/narrator2-live/style-world.json"

/** The part of the quiet evening (`T6q`): `-Pnarrator2.live.parts=QUIET`. */
private const val QUIET = "QUIET"

/** The ids of the seeded facts that the bus also hands out (`events`, `chat`): far above any of a test server's. */
private const val SEED_ID_BASE = 7_000_000_000L

/** Who writes the seeded memory, restarts the Narrator and commissions the storyteller. */
private const val BY = "live-acceptance"
private val SEEDER = Author(Source.OPERATOR, operator = BY)
private val RUN_CALLER = Caller(Role.DIRECTOR, "live-style-run", turnId = null, taskId = null)

/** Who drops T5's ideas once measured: ideas are the storyteller's own. */
private val T5_DROPPER = Author(Source.STORYTELLER, conversationId = "live-acceptance-t5")

/** The Narrator's tools that the players see or hear. */
private val SPEECH_TOOLS = setOf("say", "show_title", "actionbar", "give_book")

private val REF_IN_RESULT = Regex("""\bC-\d+\b""")
private val IDEA_REF = Regex("""\bP-(\d+)\b""")

/** A reference of the memory or of the ledger, which the players must never read. */
private val REF_SHOWN = Regex("""\b[CMIHNOTP]-\d+\b""")

/** The turns' tails that are no report: « Fin. », or nothing. */
private val END_OF_TURN = Regex("""(?i)^\s*fin\s*\.?\s*$""")

private val JSON = Json { ignoreUnknownKeys = false }

// ── The world of the acceptance runs ────────────────────────────────────────────────────────────────

@Serializable
private data class World(
    val comment: String = "",
    val sessions: List<WorldSession>,
    val season: WorldSeason,
    val players: List<WorldPlayer>,
    val relations: List<WorldRelation>,
    val canon: List<WorldCanon>,
    val grimoire: String,
    val review: String,
    val lines: List<WorldLine>,
    val levers: List<WorldLever>,
)

@Serializable
private data class WorldSession(
    val id: String,
    @SerialName("days_ago") val daysAgo: Long,
    val from: String,
    val to: String,
    val chapter: Int,
    @SerialName("chapter_summary") val chapterSummary: String,
    @SerialName("session_summary") val sessionSummary: String,
)

@Serializable
private data class WorldSeason(val title: String, val summary: String)

@Serializable
private data class WorldPlayer(
    val name: String,
    val operator: Boolean = false,
    val sessions: List<String>,
    val events: List<WorldEvent>,
    val memories: List<WorldMemory>,
    val interactions: List<WorldInteraction>,
    val traits: List<WorldTrait>,
    val skills: List<WorldSkill>,
    val docs: Map<String, String>,
)

@Serializable
private data class WorldEvent(
    val session: String,
    val type: String,
    val subject: String,
    val other: String? = null,
    val count: Int = 1,
    val at: String? = null,
    val title: String? = null,
)

@Serializable
private data class WorldMemory(
    val key: String,
    val session: String,
    val at: String,
    val title: String,
    val content: String,
    val importance: Int,
    val valence: Int,
    val with: List<String> = emptyList(),
)

@Serializable
private data class WorldInteraction(
    val key: String,
    val session: String,
    val at: String,
    val kind: String,
    val valence: Int,
    val summary: String,
    val quote: String? = null,
)

@Serializable
private data class WorldTrait(val trait: String, val side: Int, val intensity: Int, val confidence: Double, val evidence: List<String>)

@Serializable
private data class WorldSkill(val skill: String, val score: Int, val rationale: String, val evidence: List<String>)

@Serializable
private data class WorldRelation(val player: String, val other: String, val kind: String, val note: String, val evidence: List<String>)

@Serializable
private data class WorldCanon(
    val kind: String,
    val players: List<String>,
    val statement: String,
    val quote: String? = null,
    val at: List<Int>? = null,
    val importance: Int = 5,
)

@Serializable
private data class WorldLine(
    val session: String,
    val at: String,
    val text: String,
    val register: String,
    val crutches: List<String> = emptyList(),
    val dark: Double = 0.0,
    val cruel: Double = 0.0,
    val flat: Double = 0.0,
    @SerialName("out_of_character") val outOfCharacter: Double = 0.0,
    val repeats: Double = 0.0,
)

@Serializable
private data class WorldLever(val session: String, val at: String, val tool: String, val targets: List<String>, val args: JsonObject)

/** The UUID of a mock player of the live runs: the same as [StylePlayer]'s, so that the seeded history is theirs. */
private fun uuidOf(name: String): UUID = UUID.nameUUIDFromBytes("narrator2-live:$name".toByteArray())

/**
 * Writes [world] into the running Narrator's world, as if its sessions had been played: the Journal's facts with their
 * past times (players, sessions, events, the Narrator's lines and his levers, the past chapters), the line review's labels,
 * the memory through its store (memories, interactions, traits, skills, relations, sheets, canon, Grimoire, review,
 * Chronicle), then the sheets' stats computed by the real code. The run restarts the Narrator afterwards.
 */
private class WorldSeeder(private val run: StyleRun, private val world: World) {
    private val zone: ZoneId get() = run.runtime.clock.zone
    private val today: LocalDate = LocalDate.now(ZoneId.systemDefault())
    private val sessions = world.sessions.associateBy { it.id }
    private var nextSeedId = SEED_ID_BASE
    private val refs = mutableMapOf<String, Ref>()

    /** [at] (`HH:mm`) on the day of [session]. */
    fun instant(session: String, at: String): Instant =
        today.minusDays(sessions.getValue(session).daysAgo).atTime(LocalTime.parse(at)).atZone(zone).toInstant()

    private fun from(session: String) = instant(session, sessions.getValue(session).from)
    private fun to(session: String) = instant(session, sessions.getValue(session).to)

    suspend fun seed(): String {
        val journal = run.runtime.journal
        val sessionIds = world.players.associate { player -> player.name to player.sessions.associateWith { journal.nextId(IdKind.SESSION) } }
        val leverIds = world.levers.map { journal.nextId(IdKind.TOOL_CALL) }
        val napIds = world.sessions.map { journal.nextId(IdKind.MEMORY_RUN) }
        val counts = journal.write(60_000) { connection -> facts(connection, sessionIds, leverIds, napIds) }
        world.lines.forEachIndexed { i, line ->
            journal.lineLabels(
                LineLabelsRecord(
                    lineId(i), instant(line.session, line.at).plusSeconds(2), "reflex:claude-haiku-5-5", line.register, line.dark, line.cruel, line.flat,
                    line.outOfCharacter, line.repeats, line.crutches, "seeded from the acceptance runs", 1_700,
                ),
            )
        }
        journal.barrier()
        val memory = memory()
        val stats = StatsRefresher(journal, run.runtime.clock).refresh(world.players.map { uuidOf(it.name) }, "seed")
        // A dream ended an hour ago, as on a server that dreams every night: none starts by itself during the run (T4 asks for its own).
        DuckMemoryState(journal, run.runtime.clock).setState(MemoryJobs.DREAM_END_KEY, (run.runtime.clock.millis() - 3_600_000L).toString())
        return "$counts · $memory · stats of ${stats.players} players (${stats.failed} failed)"
    }

    private fun lineId(index: Int) = SEED_ID_BASE + 1_000_000 + index

    /**
     * The Journal's facts, in one write job; what was written. The past chapters come with their nap, done: the memory's
     * catch-up at the start would otherwise nap them again ([napIds]).
     */
    private fun facts(connection: JdbcConnection, sessionIds: Map<String, Map<String, Long>>, leverIds: List<Long>, napIds: List<Long>): String {
        var events = 0
        for (player in world.players) {
            val uuid = uuidOf(player.name)
            val played = player.sessions.sumOf { Duration.between(from(it), to(it)).seconds }
            connection.prepareStatement("INSERT INTO players VALUES (?, ?, ?, ?, ?, ?, ?)").use { s ->
                s.setObject(1, uuid); s.setString(2, player.name); s.setObject(3, time(from(player.sessions.first())))
                s.setObject(4, time(to(player.sessions.last()))); s.setInt(5, player.sessions.size); s.setLong(6, played); s.setBoolean(7, player.operator)
                s.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO player_names VALUES (?, ?, ?)").use { s ->
                s.setObject(1, uuid); s.setString(2, player.name); s.setObject(3, time(from(player.sessions.first()))); s.executeUpdate()
            }
            for (session in player.sessions) {
                connection.prepareStatement(
                    "INSERT INTO sessions (session_id, player_uuid, player, joined_at, left_at, duration_s, join_dim, join_x, join_y, join_z, end_reason) " +
                        "VALUES (?, ?, ?, ?, ?, ?, 'minecraft:overworld', 12, 64, -18, 'leave')",
                ).use { s ->
                    s.setLong(1, sessionIds.getValue(player.name).getValue(session)); s.setObject(2, uuid); s.setString(3, player.name)
                    s.setObject(4, time(from(session))); s.setObject(5, time(to(session))); s.setInt(6, Duration.between(from(session), to(session)).seconds.toInt())
                    s.executeUpdate()
                }
            }
            connection.prepareStatement(
                "INSERT INTO events (id, ts, game_time, type, player_uuid, player, session_id, dim, x, y, z, subject, amount, other, detail) " +
                    "VALUES (?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::JSON)",
            ).use { s ->
                for (event in player.events) {
                    val span = Duration.between(from(event.session), to(event.session)).seconds
                    repeat(event.count) { i ->
                        val at = event.at?.let { instant(event.session, it).plusSeconds(i.toLong()) } ?: from(event.session).plusSeconds(span * (i + 1) / (event.count + 1))
                        val dim = if (event.type == "dimension_change") event.subject else "minecraft:overworld"
                        s.setLong(1, nextSeedId++); s.setObject(2, time(at)); s.setString(3, event.type); s.setObject(4, uuid); s.setString(5, player.name)
                        s.setLong(6, sessionIds.getValue(player.name).getValue(event.session)); s.setString(7, dim)
                        s.setInt(8, 12 + i % 9); s.setInt(9, if (event.type == "block_break") 30 else 64); s.setInt(10, -18 - i % 7)
                        s.setString(11, event.subject)
                        if (event.type == "block_place" || event.type == "craft") s.setDouble(12, 1.0) else s.setNull(12, java.sql.Types.DOUBLE)
                        s.setString(13, event.other); s.setString(14, event.title?.let { """{"title": ${JsonPrimitive(it)}}""" })
                        s.executeUpdate() // one by one: a batch opens a transaction, and a write job already runs in one
                        events++
                    }
                }
            }
        }
        world.lines.forEachIndexed { i, line ->
            connection.prepareStatement("INSERT INTO chat (id, ts, channel, player_uuid, player, recipients, text, turn_id, operator) VALUES (?, ?, 'narrator', NULL, NULL, NULL, ?, ?, NULL)").use { s ->
                s.setLong(1, lineId(i)); s.setObject(2, time(instant(line.session, line.at))); s.setString(3, line.text); s.setLong(4, SEED_ID_BASE + 2_000_000 + i)
                s.executeUpdate()
            }
        }
        world.levers.forEachIndexed { i, lever ->
            connection.prepareStatement(
                "INSERT INTO tool_calls (id, ts, conversation_id, turn_id, tool_use_id, role, tool, args, status, result, duration_ms, targets, effect) " +
                    "VALUES (?, ?, ?, ?, ?, 'director', ?, ?::JSON, 'ok', 'ok', 5, ${sqlList(lever.targets)}, 'world')",
            ).use { s ->
                s.setLong(1, leverIds[i]); s.setObject(2, time(instant(lever.session, lever.at))); s.setString(3, chapterId(lever.session))
                s.setLong(4, SEED_ID_BASE + 3_000_000 + i); s.setString(5, "seed-$i"); s.setString(6, lever.tool); s.setString(7, lever.args.toString())
                s.executeUpdate()
            }
        }
        for (session in world.sessions) {
            connection.prepareStatement(
                "INSERT INTO conversations (conversation_id, role, label, chapter, started_at, ended_at, end_reason, summary) VALUES (?, 'director', ?, ?, ?, ?, 'server_empty', ?)",
            ).use { s ->
                s.setString(1, chapterId(session.id)); s.setString(2, "Chapter ${session.chapter}"); s.setInt(3, session.chapter)
                s.setObject(4, time(from(session.id))); s.setObject(5, time(to(session.id))); s.setString(6, session.chapterSummary)
                s.executeUpdate()
            }
        }
        world.sessions.forEachIndexed { i, session ->
            connection.prepareStatement(
                "INSERT INTO memory_runs (run_id, kind, trigger, status, started_at, ended_at, window_from, window_to, chapter, conversations, report) " +
                    "VALUES (?, 'nap', 'chapter_closed', 'done', ?, ?, ?, ?, ?, []::VARCHAR[], 'seeded: the nap of a past chapter')",
            ).use { s ->
                s.setLong(1, napIds[i]); s.setObject(2, time(to(session.id))); s.setObject(3, time(to(session.id).plusSeconds(60)))
                s.setObject(4, time(from(session.id))); s.setObject(5, time(to(session.id))); s.setString(6, chapterId(session.id))
                s.executeUpdate()
            }
        }
        return "${world.players.size} players, ${sessionIds.values.sumOf { it.size }} sessions, $events events, ${world.lines.size} lines, ${world.levers.size} levers, ${world.sessions.size} past chapters"
    }

    /** The memory, through its store, as an operator: everything checked by the store's rules. */
    private suspend fun memory(): String {
        val store = run.runtime.memory.store
        var written = 0
        for (player in world.players) {
            val uuid = uuidOf(player.name)
            for (m in player.memories) {
                val at = instant(m.session, m.at)
                refs[m.key] = store.addMemory(
                    MemoryDraft(
                        MemoryKind.EPISODE, m.title, m.content, players = listOf(uuid) + m.with.map(::uuidOf), importance = m.importance, valence = m.valence,
                        occurredFrom = at, occurredTo = at,
                    ),
                    SEEDER,
                ).row.ref
                written++
            }
            for (i in player.interactions) {
                val kind = enumOfId<InteractionKind>(i.kind) ?: error("unknown interaction kind ${i.kind}")
                refs[i.key] = store.addInteraction(InteractionDraft(uuid, kind, i.valence, i.summary, i.quote, at = instant(i.session, i.at)), SEEDER).row.ref
                written++
            }
        }
        for (player in world.players) {
            val uuid = uuidOf(player.name)
            for (t in player.traits) {
                store.setTrait(TraitDraft(uuid, t.trait, t.side, null, t.intensity, t.confidence, t.evidence.map(refs::getValue)), SEEDER)
                written++
            }
            for (s in player.skills) {
                store.setSkill(uuid, enumOfId<Skill>(s.skill) ?: error("unknown skill ${s.skill}"), s.score, s.rationale, s.evidence.map(refs::getValue), SEEDER)
                written++
            }
            for ((section, text) in player.docs) {
                store.writeDoc(DocKey.Sheet(uuid, enumOfId<SheetSection>(section) ?: error("unknown section $section")), text, emptyList(), SEEDER)
                written++
            }
        }
        for (r in world.relations) {
            store.setRelation(uuidOf(r.player), uuidOf(r.other), enumOfId<RelationKind>(r.kind) ?: error("unknown relation ${r.kind}"), r.note, r.evidence.map(refs::getValue), SEEDER)
            written++
        }
        for (c in world.canon) {
            val kind = enumOfId<CanonKind>(c.kind) ?: error("unknown canon kind ${c.kind}")
            store.addCanon(
                CanonDraft(kind, c.statement, c.quote, c.players.map(::uuidOf), importance = c.importance, place = c.at?.let { Pos("minecraft:overworld", it[0], it[1], it[2]) }),
                SEEDER,
            )
            written++
        }
        store.writeDoc(DocKey.Grimoire, world.grimoire, emptyList(), SEEDER)
        store.writeDoc(DocKey.Review, world.review, emptyList(), SEEDER)
        val sessionRefs = world.sessions.map { session ->
            val chapter = store.addChronicle(
                ChronicleDraft(ChronicleLevel.CHAPTER, from(session.id), to(session.id), "Chapter ${session.chapter}", session.chapterSummary, conversationId = chapterId(session.id)),
                SEEDER,
            ).ref
            store.addChronicle(ChronicleDraft(ChronicleLevel.SESSION, from(session.id), to(session.id), "Soirée ${session.chapter}", session.sessionSummary, listOf(chapter)), SEEDER).ref
        }
        store.addChronicle(
            ChronicleDraft(ChronicleLevel.SEASON, from(world.sessions.first().id), to(world.sessions.last().id), world.season.title, world.season.summary, sessionRefs, open = true),
            SEEDER,
        )
        written += 3 + world.sessions.size * 2
        return "$written rows of the memory"
    }

    private fun chapterId(session: String) = "director-recipe-${sessions.getValue(session).chapter}"

    private fun time(at: Instant): OffsetDateTime = OffsetDateTime.ofInstant(at, ZoneOffset.UTC)

    /** A `VARCHAR[]` literal of [values] (or NULL), quotes doubled. */
    private fun sqlList(values: List<String>): String =
        if (values.isEmpty()) "NULL" else values.joinToString(prefix = "[", postfix = "]::VARCHAR[]") { "'" + it.replace("'", "''") + "'" }
}

// ── The run ─────────────────────────────────────────────────────────────────────────────────────────

/** The ids of the Journal's tables at one moment: what came after belongs to what followed. */
private data class StyleMarks(val toolCall: Long, val chat: Long, val turn: Long, val llmCall: Long, val decision: Long, val millis: Long)

/** What the Director did, read from the Journal. */
private data class StyleCall(val id: Long, val time: String, val turn: Long?, val tool: String, val status: String, val args: String, val result: String, val millis: Long) {
    val ok: Boolean get() = status == "ok"
    val json: JsonObject? get() = runCatching { Json.parseToJsonElement(args) as? JsonObject }.getOrNull()
    fun text(key: String): String? = (json?.get(key) as? JsonPrimitive)?.contentOrNull
    override fun toString(): String = "`$time` turn $turn **$tool** $status · `${args.take(500)}` → ${result.lineSequence().firstOrNull().orEmpty().take(300)}"
}

/** Tokens and dollars of some Claude calls. */
private data class StyleSpend(val calls: Long, val input: Long, val cacheRead: Long, val cacheWrite: Long, val output: Long, val dollars: Double) {
    override fun toString() = "$calls requests, tokens in $input / cache read $cacheRead / cache write $cacheWrite / out $output, ${dollars(dollars)}"
}

/** One eval's or measure's line of the report. */
private data class Verdict(val id: String, val title: String, val threshold: String, val measured: String, val passed: Boolean?)

/**
 * The run: its players, steps and waits, its marks in the Journal, its costs and budgets, its report. The Narrator is
 * read through [runtime] each time: the run restarts it once the world is written.
 */
private class StyleRun(val helper: GameTestHelper) {
    val runtime: NarratorRuntime get() = NarratorRuntime.current ?: error("no Narrator runs")
    val server = helper.level.server
    private val log = LoggerFactory.getLogger("narrator2-live")
    val logLines = StyleLog()
    private val timeline = StringBuilder()
    private val commandAnswers = StringBuilder()
    val started = System.currentTimeMillis()
    val players = mutableListOf<StylePlayer>()
    var live = false
        private set
    private val runBudget = System.getProperty("narrator2.live.budget")?.toDoubleOrNull() ?: RUN_BUDGET

    suspend fun start(config: String) {
        configure(config)
        onServer {
            for (x in 0 until FIELD_SIZE) for (z in 0 until FIELD_SIZE) helper.level.setBlockAndUpdate(helper.absolutePos(BlockPos(x, 0, z)), Blocks.STONE.defaultBlockState())
        }
        note(
            "LLMGW key ${if (live) "present: the real storyteller and Director play" else "missing: dry run, nothing calls Claude"} · gateway ${runtime.settings.get().config.gateway.baseUrl} · " +
                "budgets T4 ${dollars(T4_BUDGET)}, T5 ${dollars(T5_BUDGET)}, T6 ${dollars(T6_BUDGET)}, the run ${dollars(runBudget)}",
        )
    }

    suspend fun configure(config: String) {
        Files.writeString(NarratorRuntime.configFile, config)
        runtime.reload()
        delay(500)
        live = runtime.settings.get().claude != null
    }

    suspend fun <T> onServer(block: () -> T): T = server.onServerThread(10_000, block)

    /**
     * A restart of the Narrator, as `/narrator restart` runs it, asked by [BY] (no player online): the server starts on
     * the written world.
     */
    suspend fun restart() {
        val report = CompletableFuture<RestartReport>()
        check(onServer { NarratorRuntime.restart(server, BY, { report.complete(it) }) }) { "a restart already runs" }
        val done = withTimeout(180_000) { report.await() }
        check(done.ok) { "the restart: ${done.text()}" }
        note("restarted: ${done.text()}")
        live = runtime.settings.get().claude != null
    }

    suspend fun join(name: String, at: BlockPos, operator: Boolean = false): StylePlayer {
        val player = onServer { StylePlayer(helper, name, helper.absolutePos(at), operator) }
        players += player
        note("$name joined")
        return player
    }

    suspend fun leave(player: StylePlayer) {
        onServer { player.close() }
        players.remove(player)
        note("${player.name} left")
    }

    fun online(name: String): StylePlayer? = players.firstOrNull { it.name == name }

    /** Whether the part that started at [partFrom] and the run may still spend: below both budgets. */
    suspend fun affordable(partFrom: StyleMarks, partBudget: Double): Boolean = spendOf("id > ${partFrom.llmCall}").dollars < partBudget && spent() < runBudget

    /** One step of the story: [action], then the Director's answer. */
    suspend fun step(title: String, settleMs: Long = 150_000, action: suspend () -> Unit) {
        note("**$title** (the run so far ${dollars(spent())})")
        action()
        settle(settleMs)
    }

    /** Waits until the Director has answered; a player who died meanwhile respawns, as a real one would. */
    suspend fun settle(maxMs: Long = 150_000) {
        awaitQuiet(maxMs)
        for (player in players.toList()) if (player.respawnIfDead()) note("${player.name} died; respawned")
    }

    /** Waits until the Director has nothing to do: no turn running or due, no task running, twice a moment apart. */
    private suspend fun awaitQuiet(maxMs: Long) {
        delay(3_500)
        if (!live) return
        fun quiet(): Boolean {
            val status = runtime.director.status()
            return status.turnId == null && status.state in QUIET_STATES && runtime.workers.list().none { !it.status.finished }
        }
        val end = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < end) {
            if (quiet()) {
                delay(2_000)
                if (quiet()) return
            }
            delay(250)
        }
        note("(still busy after ${maxMs / 1000} s: ${runtime.director.status().state})")
    }

    fun note(text: String) {
        val line = "- `+${(System.currentTimeMillis() - started) / 1000}s` $text"
        timeline.append(line).append('\n')
        log.info("[live] {}", line)
    }

    suspend fun consoleCommands(vararg commands: String) {
        val output = operatorCommands(helper, *commands)
        delay(4_000)
        commandAnswers.append("```\n")
        commands.forEach { commandAnswers.append("> /").append(it).append('\n') }
        output.lines.forEach { commandAnswers.append(it).append('\n') }
        commandAnswers.append("```\n")
    }

    /** The console closes the chapter; waits for its summary (or its closing without one). */
    suspend fun closeChapter() {
        if (runtime.director.status().chapter == null || runtime.director.status().state == DirectorState.DISABLED) return
        val before = closedChapters()
        note("**the console closes the chapter**")
        operatorCommands(helper, "narrator chapter close")
        settle(150_000)
        if (live && !poll(150_000) { closedChapters() > before }) note("(the chapter still open after 150 s)")
    }

    suspend fun closedChapters(): Long = rows("SELECT count(*) FROM conversations WHERE role = 'director' AND end_reason IS NOT NULL AND conversation_id NOT LIKE 'director-recipe-%'")
        .single()[0] as Long

    // ── The Journal ─────────────────────────────────────────────────────────────────────────────────

    /**
     * The marks of now. The written world's ids count: once the Narrator restarts on it, its counters go on above them
     * (a turn of the evening is 7 003 000 010, a line 7 001 000 033), so that « after the mark » is always « later ».
     */
    suspend fun marks(): StyleMarks {
        fun max(table: String, column: String = "id") = "(SELECT coalesce(max($column), 0) FROM $table)"
        val row = rows("SELECT ${max("tool_calls")}, ${max("chat")}, ${max("turns", "turn_id")}, ${max("llm_calls")}, ${max("engine_decisions")}").single().map { (it as Long?) ?: 0L }
        return StyleMarks(row[0], row[1], row[2], row[3], row[4], System.currentTimeMillis())
    }

    /** The Director's own tool calls after [after] (not the engine's acts, not the seeded levers). */
    suspend fun directorCalls(after: Long): List<StyleCall> = rows(
        "SELECT id, strftime(ts, '%H:%M:%S'), turn_id, tool, status, CAST(args AS VARCHAR), result, epoch_ms(ts) FROM tool_calls " +
            "WHERE role = 'director' AND conversation_id LIKE 'director-%' AND conversation_id NOT LIKE 'director-recipe-%' AND id > $after ORDER BY id",
    ).map { StyleCall(it[0] as Long, it[1].toString(), it[2] as Long?, it[3].toString(), it[4].toString(), it[5]?.toString().orEmpty(), it[6]?.toString().orEmpty(), it[7] as Long) }

    /** The trigger of each turn after [after]. */
    suspend fun triggers(after: Long): Map<Long, String> =
        rows("SELECT turn_id, trigger FROM turns WHERE turn_id > $after ORDER BY turn_id").associate { it[0] as Long to it[1].toString() }

    /** The Narrator's chat lines after [after], from a turn: (time, to, text). */
    suspend fun narratorLines(after: Long): List<Triple<String, String, String>> = rows(
        "SELECT strftime(ts, '%H:%M:%S'), coalesce(array_to_string(recipients, ','), 'all'), text FROM chat " +
            "WHERE channel = 'narrator' AND id > $after ORDER BY ts, id",
    ).map { Triple(it[0].toString(), it[1].toString(), it[2].toString()) }

    /** Dollars spent by every Claude call of the run so far. */
    suspend fun spent(): Double = if (!live) 0.0 else spendOf("TRUE").dollars

    /** Tokens and dollars of the Claude calls that [where] picks, each at the price of the model that answered. */
    suspend fun spendOf(where: String): StyleSpend {
        if (!live) return StyleSpend(0, 0, 0, 0, 0, 0.0)
        val rows = rows(
            "SELECT model, count(*), coalesce(sum(input_tokens), 0), coalesce(sum(cache_read_tokens), 0), coalesce(sum(cache_write_tokens), 0), coalesce(sum(output_tokens), 0) " +
                "FROM llm_calls WHERE provider = 'anthropic' AND ($where) GROUP BY model",
        )
        fun sum(i: Int) = rows.sumOf { (it[i] as Long?) ?: 0L }
        return StyleSpend(sum(1), sum(2), sum(3), sum(4), sum(5), rows.sumOf { cost(it[0].toString(), it.drop(2).map { v -> (v as Long?) ?: 0L }) })
    }

    /** What [tokens] (input, cache read, cache write, output) of [model] cost at the configuration's prices (`trace/Prices`); 0 without a price. */
    fun cost(model: String, tokens: List<Long>): Double = Prices.of(runtime.settings.get().config).cost(model, tokens[0], tokens[3], tokens[1], tokens[2]) ?: 0.0

    /** Every row of [sql], numbers as `Long` (but doubles), arrays as lists. */
    suspend fun rows(sql: String): List<List<Any?>> {
        runtime.journal.barrier()
        return runtime.journal.read(30_000) { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                (1..rows.metaData.columnCount).map { column ->
                                    when (val value = rows.getObject(column)) {
                                        is java.sql.Array -> (value.array as Array<*>).toList()
                                        is Number -> if (value is Double || value is Float) value.toDouble() else value.toLong()
                                        else -> value
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    suspend fun <T> read(block: (JdbcConnection) -> T): T {
        runtime.journal.barrier()
        return runtime.journal.read(30_000, block)
    }

    // ── Transcripts ─────────────────────────────────────────────────────────────────────────────────

    val conversations: Path get() = runtime.dataDir.resolve("logs").resolve("conversations")

    /** The `.jsonl` transcript of [conversation], if written. */
    fun transcript(conversation: String): Path? = if (!Files.isDirectory(conversations)) null else
        Files.walk(conversations).use { paths -> paths.filter { it.name == "$conversation.jsonl" }.findFirst().orElse(null) }

    /** The records of a transcript: (kind, data). */
    fun records(file: Path): List<Pair<String, JsonObject>> = Files.readAllLines(file).filter { it.isNotBlank() }.map { line ->
        val obj = Json.parseToJsonElement(line).jsonObject
        obj.getValue("kind").jsonPrimitive.content to (obj["data"] as? JsonObject ?: JsonObject(emptyMap()))
    }

    /** The text of the first user message of [conversation], as sent. */
    fun firstUserText(conversation: String): String {
        val file = transcript(conversation) ?: return "(no transcript of $conversation)"
        val sent = records(file).firstOrNull { (kind, data) -> kind == "sent" && (data["role"] as? JsonPrimitive)?.content == "user" }?.second ?: return "(no first message)"
        return when (val content = sent["content"]) {
            is JsonArray -> content.mapNotNull { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull }.joinToString("\n")
            is JsonPrimitive -> content.content
            else -> ""
        }
    }

    // ── The report ──────────────────────────────────────────────────────────────────────────────────

    /** Writes the report; its path. Closes the players and the log listener. */
    suspend fun finish(sections: String): Path? {
        val path = runCatching { writeReport(sections) }.onFailure { log.error("the live report could not be written", it) }.getOrNull()
        log.info("live report: {}", path)
        logLines.close()
        onServer { players.forEach { it.close() } }
        return path
    }

    private suspend fun writeReport(sections: String): Path {
        val out = StringBuilder("# M6 live acceptance: T4, T5, T6 (M6-18)\n\n")
        out.append("Run of ${DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(started))}; prices per million tokens ")
        out.append("(the configuration's `prices`; input / output / cache read / cache write at 1 h): ")
        out.append(runtime.settings.get().config.prices.entries.joinToString(" · ") { (model, p) -> "$model ${p.input} / ${p.output} / ${p.cacheRead} / ${p.cacheWrite} \$" }).append(".\n\n")
        out.append("The whole run: **${spendOf("TRUE")}**.\n\n")
        out.append("## Timeline\n\n").append(timeline).append('\n')
        out.append(sections).append('\n')
        out.append("## Claude calls by role and purpose\n\n| role | purpose | calls | input | cache read | cache write | output | cost |\n|---|---|---|---|---|---|---|---|\n")
        if (live) {
            rows(
                "SELECT role, purpose, model, count(*), coalesce(sum(input_tokens), 0), coalesce(sum(cache_read_tokens), 0), coalesce(sum(cache_write_tokens), 0), " +
                    "coalesce(sum(output_tokens), 0) FROM llm_calls WHERE provider = 'anthropic' GROUP BY role, purpose, model ORDER BY role, purpose",
            ).forEach { r ->
                val tokens = r.subList(4, 8).map { (it as Long?) ?: 0L }
                out.append("| ${r[0]} | ${r[1]} (${r[2]}) | ${r[3]} | ${tokens[0]} | ${tokens[1]} | ${tokens[2]} | ${tokens[3]} | ${dollars(cost(r[2].toString(), tokens))} |\n")
            }
        }
        out.append("\n## Warnings and errors of the mod\n\n")
        logLines.lines().filter { !it.startsWith("DEBUG") }.ifEmpty { listOf("none") }.forEach { out.append("- ").append(it.replace("\n", " ").take(600)).append('\n') }
        out.append("\n## Operator commands at the end\n\n").append(commandAnswers)
        out.append("\nTranscripts: `$conversations`\n")
        val file = Path.of("live-report", "style.md").toAbsolutePath()
        Files.createDirectories(file.parent)
        Files.writeString(file, out)
        return file
    }

    companion object {
        val QUIET_STATES = setOf(DirectorState.IDLE, DirectorState.DISABLED, DirectorState.PAUSED)
    }
}

private fun dollars(value: Double) = "%.3f \$".format(Locale.ROOT, value)

private fun pct(n: Int, of: Int) = if (of == 0) "-" else "%.0f %%".format(Locale.ROOT, 100.0 * n / of)

private fun pct(share: Double) = "%.0f %%".format(Locale.ROOT, share * 100)

private fun num(value: Double) = "%.2f".format(Locale.ROOT, value)

/** Waits until [condition] holds, checking every [everyMs]; whether it held within [timeoutMs]. */
private suspend fun poll(timeoutMs: Long, everyMs: Long = 2_000, condition: suspend () -> Boolean): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (condition()) return true
        delay(everyMs)
    }
    return condition()
}

// ── The acceptance ──────────────────────────────────────────────────────────────────────────────────

/** A commission of T5, as the Director would write it. */
private class Commission(val id: String, val title: String, val brief: String)

private val COMMISSIONS = listOf(
    Commission(
        "return", "Retour après une semaine",
        "Lisette revient ce soir après neuf jours d'absence : le premier soir, elle avait perdu un défi contre Gaspard, puis offert dix diamants " +
            "contre un joker qu'elle n'a jamais joué. Gaspard et Firmin sont en ligne. Je veux marquer son retour : qu'elle se sente attendue sans " +
            "être noyée, et la remettre dans le jeu avec les deux autres, ce soir.",
    ),
    Commission(
        "nether", "Première entrée dans le Nether",
        "Firmin vient d'entrer dans le Nether pour la première fois de sa vie, seul, équipé de fer ; Gaspard, qui y est allé dès le premier soir, " +
            "est resté au désert. Je veux que cette première entrée compte : un moment à sa mesure, ni un piège mortel ni une promenade, ce soir.",
    ),
    Commission(
        "quiet", "Soirée creuse",
        "Soirée creuse : Gaspard et Firmin minent sans but depuis vingt minutes, le chat dit « on s'ennuie » et « il se passe rien ». J'ai déjà " +
            "servi ces derniers jours un défi de collecte, un calcul mental, un concours de sauts et des poules en orbite. Je veux relancer la " +
            "soirée pour eux deux, sans resservir tout ça.",
    ),
)

/** An idea of the storyteller, read from `ideas`. */
private class IdeaRow(val id: Long, val kind: String, val title: String, val pitch: String, val fit: String, val players: List<String>, val register: String?, val notes: String, val module: String?, val brief: String?, val status: String) {
    val ref: String get() = "P-$id"
    fun card(): String = buildString {
        append("$ref $kind « $title » · for ${players.ifEmpty { listOf("whoever is online") }.joinToString()} · register ${register ?: "none"} · its own notes $notes\n")
        append("Pitch: $pitch\nFits: $fit")
        module?.let { append("\nModule: $it") }
        brief?.let { append("\nBrief: $it") }
    }
}

/** T4, T5 and T6 in a row, on the written world; each part measured from what it added to the Journal. */
private class StyleAcceptance(private val run: StyleRun) {
    private val parts = System.getProperty("narrator2.live.parts").orEmpty().split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
    private val verdicts = mutableListOf<Verdict>()
    private val sections = StringBuilder()
    private val judgeKey: String? = System.getenv("NARRATOR_LLMGW_KEY")?.trim()?.ifEmpty { null }
    private lateinit var world: World
    private val moduleTimes = ConcurrentHashMap<String, Double>()

    private fun wanted(part: String) = parts.isEmpty() || part in parts

    suspend fun play(scope: CoroutineScope) {
        try {
            run.start(QUIET_CONFIG)
            world = JSON.decodeFromString(World.serializer(), javaClass.getResourceAsStream(WORLD)!!.use { it.readBytes().decodeToString() })
            val seeded = WorldSeeder(run, world).seed()
            run.note("the world of the acceptance runs written: $seeded")
            run.restart()
            writtenWorld()
            if (wanted("T4") || wanted("T6") || wanted(QUIET)) runCatching { programme() }.onFailure { run.note("T4 stopped by an error of the run: ${it.javaClass.simpleName}: ${it.message}") }
            if (wanted("T5")) runCatching { onDemand() }.onFailure { run.note("T5 stopped by an error of the run: ${it.javaClass.simpleName}: ${it.message}") }
            if (wanted("T6")) runCatching { evening(scope, "T6", ::script) }.onFailure { run.note("T6 stopped by an error of the run: ${it.javaClass.simpleName}: ${it.message}") }
            if (wanted(QUIET)) runCatching { evening(scope, "T6q", ::quietScript) }.onFailure { run.note("T6q stopped by an error of the run: ${it.javaClass.simpleName}: ${it.message}") }
        } finally {
            run.finish(report())
        }
        if (run.live) {
            check(run.logLines.errors().isEmpty()) { "errors in the log: ${run.logLines.errors()}" }
            val failed = verdicts.filter { it.passed == false }
            check(failed.isEmpty()) { "under their threshold: ${failed.joinToString { "${it.id} ${it.title}: ${it.measured}" }}" }
        }
    }

    private fun report(): String {
        val out = StringBuilder("## Verdicts\n\n| # | measure | threshold | measured | |\n|---|---|---|---|---|\n")
        verdicts.forEach { out.append("| ${it.id} | ${it.title} | ${it.threshold} | ${it.measured} | ${when (it.passed) { true -> "pass"; false -> "**FAIL**"; null -> "reported" }} |\n") }
        return out.append('\n').append(sections).toString()
    }

    private fun verdict(id: String, title: String, threshold: String, measured: String, passed: Boolean?) {
        verdicts += Verdict(id, title, threshold, measured, passed)
        run.note("$id $title: $measured${when (passed) { true -> " → pass"; false -> " → **FAIL**"; null -> "" }}")
    }

    /** What the code computed from the written world: the sheets' stats, the canon, the memory's rows (for a reading). */
    private suspend fun writtenWorld() {
        sections.append("## The written world\n\n")
        run.rows("SELECT p.name, s.stat, s.value, CAST(s.detail AS VARCHAR) FROM sheet_stats s JOIN players p ON p.player_uuid = s.player_uuid ORDER BY p.name, s.stat")
            .groupBy { it[0].toString() }
            .forEach { (name, stats) ->
                sections.append("- **$name**: ${stats.filter { it[1] in SHOWN_STATS }.joinToString(" · ") { "${it[1]} ${it[2] ?: "-"} ${it[3].toString().take(160)}" }}\n")
            }
        sections.append("- canon: ${run.rows("SELECT 'C-' || id, kind, status, statement FROM canon ORDER BY id").joinToString { "${it[0]} ${it[1]} ${it[2]}: ${it[3].toString().take(80)}" }}\n")
        sections.append("- memory: ${run.rows("SELECT kind, count(*) FROM memories GROUP BY kind").joinToString { "${it[0]} ${it[1]}" }}; ")
        sections.append("docs ${run.rows("SELECT count(DISTINCT key) FROM memory_docs").single()[0]}; chronicle ${run.rows("SELECT level, count(*) FROM chronicle GROUP BY level").joinToString { "${it[0]} ${it[1]}" }}\n\n")
    }

    // ── T4 ──────────────────────────────────────────────────────────────────────────────────────────

    /** The dream's step 5 alone: resumed from a dream cut after its step 4. */
    private suspend fun programme() {
        val mark = run.marks()
        run.note("**T4: the programme (the dream's step 5)**")
        sections.append("## T4: the programme\n\n")
        if (!run.live) {
            sections.append("Dry run: no programme.\n\n")
            return
        }
        val runtime = run.runtime
        val state = DuckMemoryState(runtime.journal, runtime.clock)
        val cut = runtime.journal.nextId(IdKind.MEMORY_RUN)
        state.setState(MemoryJobs.DREAM_PENDING_KEY, cut.toString())
        state.setState(MemoryJobs.stepKey(cut), "4")
        state.setState(MemoryJobs.DREAM_END_KEY, runtime.clock.millis().toString())
        val runId = runtime.memory.jobs.requestDream(BY).getOrElse {
            verdict("T4", "programme", "5 to 8 ideas, ≥ 80 % feasible and fair, ≥ 3 kinds", "the dream was refused: ${it.message}", false)
            return
        }
        run.note("dream run $runId requested (resuming the cut run $cut at step 5)")
        poll(15 * 60_000L, 3_000) { (run.rows("SELECT status FROM memory_runs WHERE run_id = $runId").firstOrNull()?.get(0) as String?).let { it != null && it != "running" } }
        val status = run.rows("SELECT status, coalesce(report, ''), coalesce(error, '') FROM memory_runs WHERE run_id = $runId").firstOrNull()
        val decision = run.rows("SELECT reason FROM engine_decisions WHERE kind = 'memory' AND decision = 'programme' AND subject = 'run $runId'").firstOrNull()?.get(0)?.toString()
        val conversation = run.rows("SELECT conversation_id FROM conversations WHERE label = 'Dream run $runId, step 5: programme'").firstOrNull()?.get(0)?.toString()
        val spend = conversation?.let { run.spendOf("conversation_id = '$it'") }
        val calls = conversation?.let { run.rows("SELECT latency_ms, coalesce(output_tokens, 0), coalesce(stop_reason, error) FROM llm_calls WHERE conversation_id = '$it' ORDER BY id") }.orEmpty()
        val tools = conversation?.let { toolsOf(it) }.orEmpty()
        val ideas = ideas("run_id = $runId AND source = 'storyteller'")
        val playable = ideas.filter { it.kind != "season" }
        val kinds = playable.map { it.kind }.toSet()
        val context = conversation?.let { run.firstUserText(it) }.orEmpty()
        // The ideas' judge grades T4 only: an evening alone (`QUIET`) needs the programme, not its grades (M6-19's budget).
        val graded = wanted("T4")
        val judged = if (graded) judgeIdeas(context, ideas, T4_BUDGET - (spend?.dollars ?: 0.0) + 0.3) else emptyMap()
        val good = playable.count { judged[it.ref]?.feasibleAndFair == true }
        sections.append("- dream run $runId: ${status?.getOrNull(0)} · ${status?.getOrNull(1)} ${status?.getOrNull(2)}\n- decision: $decision\n")
        sections.append("- the storyteller's conversation `$conversation`: ${spend ?: "-"}; requests ${calls.size}: ${calls.joinToString { "${it[0]} ms, out ${it[1]}, ${it[2]}" }}\n")
        sections.append("- its tool calls: ${tools.joinToString("; ").ifEmpty { "none" }}\n")
        sections.append("- ideas: ${ideas.size} (${playable.size} besides the season), kinds ${kinds.sorted()}\n\n")
        ideasTable(ideas, judged)
        sections.append("\n<details><summary>The storyteller's first message (${context.length} characters)</summary>\n\n```\n$context\n```\n</details>\n\n")
        verdict("T4", "programme: ideas written", "5 to 8 (season apart)", "${playable.size}${if (ideas.size > playable.size) " + season" else ""}", playable.size in 5..8)
        if (graded) {
            verdict("T4", "programme: feasible and fair (judge)", "≥ 80 %", "$good/${playable.size} (${pct(good, playable.size)})", playable.isNotEmpty() && good * 5 >= playable.size * 4)
        } else {
            verdict("T4", "programme: feasible and fair (judge)", "≥ 80 %", "not judged: T4 not asked", null)
        }
        verdict("T4", "programme: kinds of levers", "≥ 3", "${kinds.size} (${kinds.sorted().joinToString()})", kinds.size >= 3)
        verdict("T4", "programme: cost and requests", "reported", "${spend?.calls ?: 0} requests, ${dollars(spend?.dollars ?: 0.0)}, output ${spend?.output ?: 0} tokens", null)
        run.note("T4 cost: ${dollars(run.spendOf("id > ${mark.llmCall}").dollars)}")
    }

    // ── T5 ──────────────────────────────────────────────────────────────────────────────────────────

    /** Three commissions to the storyteller, as the Director writes them; their ideas dropped once measured. */
    private suspend fun onDemand() {
        val mark = run.marks()
        run.note("**T5: three commissions to the storyteller**")
        sections.append("## T5: the storyteller on demand\n\n")
        if (!run.live) {
            sections.append("Dry run: no commission.\n\n")
            return
        }
        var passed = 0
        for (commission in COMMISSIONS) {
            if (!run.affordable(mark, T5_BUDGET)) {
                sections.append("### ${commission.title}\n\nSkipped: the budget is spent.\n\n")
                continue
            }
            if (commission(commission)) passed++
        }
        verdict("T5", "on demand: three ranked, feasible ideas", "3/3", "$passed/${COMMISSIONS.size}", passed == COMMISSIONS.size)
        // The repeats refused (M6-FIX-11: the title of an open idea), and any T5 idea that still took a programme idea's title.
        val refused = run.rows("SELECT coalesce(result, '') FROM tool_calls WHERE role = 'storyteller' AND tool = 'propose_idea' AND status <> 'ok' AND id > ${mark.toolCall}").map { it[0].toString() }
        val programme = ideas("source = 'storyteller' AND run_id IS NOT NULL AND status = 'open'")
        val taken = ideas("source = 'storyteller' AND run_id IS NULL AND created_at >= '${Instant.ofEpochMilli(mark.millis)}'::TIMESTAMPTZ")
            .filter { idea -> programme.any { titleKey(it.title) == titleKey(idea.title) } }
        verdict(
            "T5", "titles of an open idea taken again", "0 (M6-FIX-11)",
            "${taken.size} ${taken.map { "${it.ref} « ${it.title} »" }}; propose_idea refused ${refused.size}: ${refused.count { "same title as" in it }} same title, " +
                "${refused.count { "too close to" in it }} too close, ${refused.count { "same title as" !in it && "too close to" !in it }} other${refused.joinToString("") { " · ${it.take(160)}" }}",
            taken.isEmpty(),
        )
        run.note("T5 cost: ${dollars(run.spendOf("id > ${mark.llmCall}").dollars)}")
    }

    private suspend fun commission(commission: Commission): Boolean {
        val runtime = run.runtime
        val before = Instant.now()
        val id = runtime.workers.submit(Role.STORYTELLER, commission.brief, RUN_CALLER).getOrElse {
            sections.append("### ${commission.title}\n\nRefused: ${it.message}\n\n")
            return false
        }
        run.note("T5 ${commission.id}: T-$id commissioned")
        val limit = (runtime.settings.get().config.workers.timeoutSeconds + 60) * 1_000L
        poll(limit, 3_000) { (run.rows("SELECT status FROM tasks WHERE task_id = $id").firstOrNull()?.get(0) as String?) in ENDED }
        val row = run.rows("SELECT status, coalesce(result, ''), coalesce(error, ''), coalesce(conversation_id, ''), coalesce(epoch_ms(finished_at) - epoch_ms(started_at), 0) FROM tasks WHERE task_id = $id").single()
        val conversation = row[3].toString()
        val spend = run.spendOf("conversation_id = '$conversation'")
        val tools = toolsOf(conversation)
        val ideas = ideas("source = 'storyteller' AND run_id IS NULL AND created_at >= '${before}'::TIMESTAMPTZ AND created_at <= now()")
        val report = row[1].toString()
        // The report ranks its own three ideas; it may cite an idea of the programme on the way (« proche de P-27 »).
        val theirs = ideas.map { it.id }.toSet()
        val cited = IDEA_REF.findAll(report).map { it.groupValues[1].toLong() }.distinct().toList()
        val ranked = cited.filter { it in theirs }
        val rankedOk = ranked.size == 3
        val profiles = run.rows("SELECT coalesce(result, '') FROM tool_calls WHERE conversation_id = '$conversation' AND tool = 'player_profile' ORDER BY id").joinToString("\n\n") { it[0].toString() }
        val judged = judgeIdeas("The Narrator's request:\n${commission.brief}\n\nThe sheets the writer read:\n$profiles", ideas, T5_BUDGET)
        val feasible = ideas.count { judged[it.ref]?.feasible == true && judged[it.ref]?.dark != true }
        val ok = row[0] == "done" && rankedOk && ideas.size == 3 && feasible == 3
        sections.append("### ${commission.title}: T-$id ${row[0]} in ${(row[4] as Long) / 1000} s · ${spend}\n\nBrief: ${commission.brief}\n\n")
        sections.append("- its tool calls: ${tools.joinToString("; ")}\n- ranked in the report: ${ranked.map { "P-$it" }} (${if (rankedOk) "its three ideas" else "**not its three ideas**"}); cited: ${cited.map { "P-$it" }}\n")
        sections.append("- ideas written: ${ideas.size}; feasible (judge): $feasible/${ideas.size}${row[2].toString().takeIf { it.isNotEmpty() }?.let { " · error: $it" }.orEmpty()}\n\nReport:\n\n```\n$report\n```\n\n")
        ideasTable(ideas, judged)
        sections.append("\n")
        for (idea in ideas) {
            runCatching { runtime.memory.store.dropIdea(Ref(RefKind.IDEA, idea.id), "measured by the acceptance (T5)", T5_DROPPER) }
                .onFailure { run.note("${idea.ref} not dropped: ${it.message}") }
        }
        run.note("T5 ${commission.id}: ${row[0]}, ${ideas.size} ideas, ranked ${ranked.size} (cited ${cited.size}), feasible $feasible, ${dollars(spend.dollars)} → ${if (ok) "pass" else "**fail**"}")
        return ok
    }

    /** The storyteller's ideas that [where] picks. */
    private suspend fun ideas(where: String): List<IdeaRow> {
        val names = run.rows("SELECT player_uuid, name FROM players").associate { it[0].toString() to it[1].toString() }
        return run.rows(
            "SELECT id, kind, title, pitch, fit, players, register, novelty, fun, fairness, module, brief, status FROM ideas WHERE $where ORDER BY id",
        ).map { r ->
            IdeaRow(
                r[0] as Long, r[1].toString(), r[2].toString(), r[3].toString(), r[4].toString(), (r[5] as List<*>).map { names[it.toString()] ?: it.toString() },
                r[6]?.toString(), "novelty ${r[7]} fun ${r[8]} fair ${r[9]}", r[10]?.toString(), r[11]?.toString(), r[12].toString(),
            )
        }
    }

    /** The tool calls of [conversation], one line each. */
    private suspend fun toolsOf(conversation: String): List<String> = run.rows(
        "SELECT tool, status, coalesce(result, '') FROM tool_calls WHERE conversation_id = '$conversation' ORDER BY id",
    ).map { "${it[0]} ${it[1]}${if (it[1] != "ok") ": ${it[2].toString().take(160)}" else ""}" }

    private fun judgeIdeas(context: String, ideas: List<IdeaRow>, budget: Double): Map<String, IdeaGrade> {
        val key = judgeKey ?: return emptyMap()
        val judged = IdeaJudge(run.runtime.settings.get().config.gateway.baseUrl, key, LiveBudget(budget.coerceAtLeast(0.2))).use { judge ->
            judge.grade(context, ideas.map { JudgedIdea(it.ref, it.card()) })
        }
        sections.append("- the ideas' judge: ${judged.calls.size} request(s), ${dollars(judged.dollars)}${judged.problems.takeIf { it.isNotEmpty() }?.let { " · problems: ${it.joinToString("; ")}" }.orEmpty()}\n")
        return judged.grades
    }

    private fun ideasTable(ideas: List<IdeaRow>, judged: Map<String, IdeaGrade>) {
        sections.append("\n| idea | kind | for | title | its notes | judge | reason |\n|---|---|---|---|---|---|---|\n")
        ideas.forEach { idea ->
            val grade = judged[idea.ref]
            sections.append("| ${idea.ref} | ${idea.kind} | ${idea.players.joinToString().ifEmpty { "all" }} | ${idea.title.cell()} | ${idea.notes} | ${grade?.summary() ?: "not graded"} | ${grade?.reason.orEmpty().cell()} |\n")
        }
        sections.append("\n")
        ideas.forEach { sections.append("- **${it.ref}** ${it.card().cell()}\n") }
    }

    // ── T6 ──────────────────────────────────────────────────────────────────────────────────────────

    private lateinit var gaspard: StylePlayer
    private lateinit var lisette: StylePlayer
    private lateinit var firmin: StylePlayer
    private lateinit var opaline: StylePlayer
    private var stopped = false

    /**
     * An evening ([id]): production settings, the programme of T4, mock players who play the [script]; then the console
     * closes the chapter, the players leave, and the evening is measured.
     */
    private suspend fun evening(scope: CoroutineScope, id: String, script: suspend (StyleMarks) -> Unit) {
        run.note("**$id: the evening**")
        run.configure(EVENING_CONFIG)
        stopped = false
        val mark = run.marks()
        val start = Instant.now()
        val activity = scope.launch { activity() }
        val watch = scope.launch { watchdog(mark) }
        try {
            script(mark)
        } finally {
            stopped = true
            activity.cancel()
            // They leave first: departures after the close would open a chapter of their own, for nobody (M6-18, run 2).
            for (player in run.players.toList()) runCatching { run.leave(player) }
            runCatching { run.closeChapter() }
            // The nap the close starts belongs to the evening's Chronicler cost (M6-19): it is measured once done.
            if (run.live && !poll(180_000, 3_000) { chronicleRunning() == 0L }) run.note("(a Chronicler job still running after 180 s)")
            watch.cancel()
        }
        val end = Instant.now()
        run.consoleCommands("narrator status", "narrator style", "narrator memory ideas", "narrator usage")
        runCatching { measures(id, mark, start, end) }.onFailure { run.note("**the evening's measures failed**: ${it.javaClass.simpleName}: ${it.message}") }
        run.note("$id cost: ${dollars(run.spendOf("id > ${mark.llmCall}").dollars)}")
    }

    /** The Chronicler's batches and naps still running. */
    private suspend fun chronicleRunning(): Long =
        run.rows("SELECT count(*) FROM memory_runs WHERE kind IN ('batch', 'nap') AND status = 'running'").single()[0] as Long

    /** Past the evening's budget: the tasks are cancelled and the Director put to sleep; the script stops at its next step. */
    private suspend fun watchdog(mark: StyleMarks) {
        while (true) {
            delay(10_000)
            for (view in runCatching { run.runtime.mechanics.modules.list() }.getOrDefault(emptyList())) {
                moduleTimes.merge("${view.ref} ${view.module}", view.msPerSecond, ::maxOf)
            }
            if (run.live && !stopped && !run.affordable(mark, T6_BUDGET)) {
                stopped = true
                run.note("**the evening's budget is spent** (${dollars(run.spendOf("id > ${mark.llmCall}").dollars)}): tasks cancelled, the Director put to sleep")
                for (task in run.runtime.workers.list().filter { !it.status.finished }) run.runtime.workers.cancel(task.taskId)
                run.configure("""{ "dashboard": { "enabled": false }, "director": { "enabled": false } }""")
            }
        }
    }

    /** Every 40 to 70 s, each player online but the operator mines a block or two: the matter of the heartbeats. */
    private suspend fun activity() {
        val random = Random(618)
        val blocks = listOf(Blocks.STONE, Blocks.STONE, Blocks.STONE, Blocks.COAL_ORE, Blocks.IRON_ORE, Blocks.SANDSTONE)
        while (true) {
            delay(40_000L + random.nextLong(30_000))
            for (player in run.players.toList()) {
                if (player.name == "Opaline") continue
                runCatching { player.mine(List(1 + random.nextInt(2)) { blocks[random.nextInt(blocks.size)] }) }
            }
        }
    }

    private suspend fun beat(title: String, settleMs: Long = 150_000, action: suspend () -> Unit) {
        if (stopped) return
        run.step(title, settleMs, action)
    }

    private suspend fun pause(ms: Long) {
        if (!stopped) delay(if (run.live) ms else minOf(ms, 2_000))
    }

    /** The evening, told step by step in real time. */
    private suspend fun script(mark: StyleMarks) {
        beat("Gaspard, Firmin and Opaline join: the chapter opens on an arrival", 180_000) {
            gaspard = run.join("Gaspard", BlockPos(8, 1, 8))
            delay(2_000)
            firmin = run.join("Firmin", BlockPos(20, 1, 8))
            delay(2_000)
            opaline = run.join("Opaline", BlockPos(20, 1, 20), operator = true)
        }
        pause(60_000)
        beat("Firmin and Gaspard chat between themselves", 90_000) {
            firmin.say("Gaspard, tu crois qu'il reste des diamants par ici ?")
            delay(4_000)
            gaspard.say("J'en ai déjà dix, je creuse pour le plaisir.")
        }
        pause(90_000)
        beat("Gaspard mines two diamonds and boasts") {
            gaspard.mine(listOf(Blocks.STONE, Blocks.DIAMOND_ORE, Blocks.DIAMOND_ORE))
            run.onServer { gaspard.player.inventory.add(ItemStack(Items.DIAMOND, 2)) }
            gaspard.say("Encore deux diamants ! Qui c'est le meilleur mineur du serveur ?")
        }
        pause(60_000)
        beat("Lisette comes back after nine days", 180_000) { lisette = run.join("Lisette", BlockPos(8, 1, 20)) }
        pause(45_000)
        beat("Lisette greets everyone") { lisette.say("Coucou tout le monde ! Ça fait une éternité. J'ai raté quoi ?") }
        pause(120_000)
        beat("Firmin dies in lava", 120_000) { firmin.dieInLava() }
        pause(90_000)
        challengeFromAPrayer()
        pause(60_000)
        firstNether()
        pause(30_000)
        beat("A quiet stretch: the players mine and get bored", 90_000) {
            delay(if (run.live) 120_000 else 1_000)
            gaspard.say("Bon, on s'ennuie un peu là.")
            delay(5_000)
            firmin.say("Grave, il se passe rien.")
        }
        pause(150_000)
        beat("Firmin teases the Narrator") { firmin.say("Narrateur, t'es devenu gentil ou quoi ? Ton dernier défi était beaucoup trop facile.") }
        pause(60_000)
        beat("Opaline asks to take Gaspard down a peg") {
            opaline.command("narrator whisper Gaspard se vante de ses diamants depuis le début de la soirée : rabats-lui un peu le caquet, gentiment.")
        }
        pause(60_000)
        beat("Lisette offers three diamonds") {
            run.onServer { lisette.player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.DIAMOND, 3)) }
            lisette.command("offer")
        }
        pause(45_000)
        if (stopped) return
        run.closeChapter()
        for (player in run.players.toList()) run.leave(player)
        pause(30_000)
        beat("Gaspard and Firmin come back: a new chapter opens on an arrival", 180_000) {
            gaspard = run.join("Gaspard", BlockPos(8, 1, 8))
            delay(3_000)
            firmin = run.join("Firmin", BlockPos(20, 1, 8))
        }
        pause(60_000)
        beat("Gaspard asks what comes next") { gaspard.say("Narrateur, c'est quoi le programme pour la suite de la soirée ?") }
        pause(120_000)
        run.note("the evening ends (${(System.currentTimeMillis() - mark.millis) / 60_000} min)")
    }

    /**
     * A quiet evening (`T6q`), for what a busy one never shows: the heartbeats, which come 3 to 8 minutes after the last
     * turn, and the programme's ideas they carry (`Idea: P-…`). Gaspard and Firmin mine (the matter of the heartbeats),
     * chat between themselves twice and get bored, for about 25 minutes.
     */
    private suspend fun quietScript(mark: StyleMarks) {
        beat("Gaspard and Firmin join for a quiet evening", 180_000) {
            gaspard = run.join("Gaspard", BlockPos(8, 1, 8))
            delay(3_000)
            firmin = run.join("Firmin", BlockPos(20, 1, 8))
        }
        pause(8 * 60_000L)
        beat("Gaspard and Firmin chat between themselves", 90_000) {
            gaspard.say("T'as trouvé quoi depuis tout à l'heure ?")
            delay(5_000)
            firmin.say("Du charbon. Encore du charbon.")
        }
        pause(7 * 60_000L)
        beat("They get bored", 90_000) {
            gaspard.say("On s'ennuie un peu là, non ?")
            delay(5_000)
            firmin.say("Grave. Il se passe rien.")
        }
        pause(7 * 60_000L)
        run.note("the quiet evening ends (${(System.currentTimeMillis() - mark.millis) / 60_000} min)")
    }

    /** Lisette prays for a challenge; a participant wins it, as a player would. */
    private suspend fun challengeFromAPrayer() {
        val asked = run.marks()
        beat("Lisette prays for a challenge") { lisette.command("pray Narrateur, lance-nous un défi pour fêter mon retour !") }
        if (stopped || !run.live) return
        poll(120_000, 3_000) { run.directorCalls(asked.toolCall).any { it.tool == "start_challenge" && it.ok } }
        val start = run.directorCalls(asked.toolCall).firstOrNull { it.tool == "start_challenge" && it.ok } ?: return run.note("no challenge after the prayer")
        val ref = REF_IN_RESULT.find(start.result)?.value ?: return
        val view = run.runtime.mechanics.challenges.list().firstOrNull { it.ref.toString() == ref } ?: return
        if (view.spec == null) return
        val winner = listOf(lisette, gaspard, firmin).firstOrNull { p -> view.participants.any { it.name == p.name } } ?: lisette
        // A module that referees the challenge plays it to its end: the run neither plays for the players nor hurries it.
        delay(20_000)
        val refereed = run.runtime.mechanics.modules.list().any { it.challenge == view.ref }
        val done = if (refereed) "a module referees it: left to run to its end" else fulfil(view, winner)
        run.note("challenge $ref: $done")
        val patience = if (refereed) (view.spec!!.minutes + 3) * 60_000L else if (view.spec!!.goal == GoalKind.JUDGED) 240_000L else 90_000L
        poll(patience.coerceAtMost(12 * 60_000L), 2_000) { canonStatus(ref).let { it != null && it != CanonStatus.ACTIVE && it != CanonStatus.PLANNED } }
        run.settle()
        run.note("challenge $ref ended: ${canonStatus(ref)}")
        endIfOpen(ref)
    }

    /** Firmin's first time in the Nether, then back. */
    private suspend fun firstNether() {
        if (stopped) return
        val room = netherRoom()
        beat("Firmin enters the Nether for the first time", 180_000) { firmin.toNether(room) }
        pause(90_000)
        run.onServer { firmin.goHome() }
        run.note("Firmin back in the desert")
    }

    private suspend fun canonStatus(ref: String): CanonStatus? = Ref.parse(ref)?.let { run.runtime.memory.store.canon(it)?.status }

    private suspend fun endIfOpen(ref: String) {
        val view = run.runtime.mechanics.challenges.list().firstOrNull { it.ref.toString() == ref } ?: return
        runCatching { run.runtime.mechanics.challenges.end(view.ref, ChallengeOutcome.CANCELLED, emptyList(), "Annulé par la recette.", SEEDER) }
            .onSuccess { run.note("$ref left open: cancelled by the run") }
    }

    /**
     * Makes [winner] reach [view]'s goal, as a player would (M4-24): the items in their inventory, the creatures killed by
     * their hand, the place reached, the answer said; a `survive` or `judged` challenge has its end brought forward.
     */
    private suspend fun fulfil(view: ChallengeView, winner: StylePlayer): String {
        val spec = view.spec ?: return "not a challenge"
        val server = run.server
        val target = Goals.target(spec, server).getOrElse { return "its goal cannot be resolved: ${it.message}" }
        val done = when (spec.goal) {
            GoalKind.OBTAIN -> {
                val items = target as Target.Items
                val item = BuiltInRegistries.ITEM.stream().filter { it != Items.AIR && items.matches(ItemStack(it)) }.findFirst().orElse(null) ?: return "no item matches ${spec.item}"
                run.onServer {
                    var left = spec.count
                    while (left > 0) {
                        val n = minOf(left, ItemStack(item).maxStackSize)
                        winner.player.inventory.add(ItemStack(item, n))
                        left -= n
                    }
                }
                "${winner.name} got ${spec.count} ${BuiltInRegistries.ITEM.getKey(item)}"
            }
            GoalKind.KILL -> {
                val creatures = target as Target.Creatures
                val id = BuiltInRegistries.ENTITY_TYPE.keySet().map { it.toString() }.sorted().firstOrNull { creatures.matches(it) } ?: return "no creature matches ${spec.entity}"
                val type = BuiltInRegistries.ENTITY_TYPE.getValue(net.minecraft.resources.Identifier.parse(id))
                val count = minOf(spec.count, 12)
                repeat(count) {
                    run.onServer {
                        @Suppress("UNCHECKED_CAST")
                        val entity = run.helper.spawn(type as EntityType<Entity>, Vec3(16.5, 1.0, 16.5))
                        if (entity is LivingEntity) entity.hurtServer(run.helper.level, run.helper.level.damageSources().playerAttack(winner.player), 1_000f) else entity.discard()
                    }
                    delay(300)
                }
                "${winner.name} killed $count $id"
            }
            GoalKind.REACH -> {
                val place = target as Target.Place
                val x = place.x
                val z = place.z
                when {
                    place.dimension == NETHER_ID || (x != null && place.pointDimension == NETHER_ID) -> winner.toNether(netherRoom(x, z))
                    x != null && z != null -> run.onServer { winner.player.teleportTo(x + 0.5, (place.y ?: place.yAbove ?: winner.player.blockY).toDouble(), z + 0.5) }
                    place.yBelow != null || place.yAbove != null -> run.onServer { winner.player.teleportTo(winner.player.x, (place.yBelow ?: place.yAbove!!).toDouble(), winner.player.z) }
                    else -> return "a place the run cannot bring a player to (${spec.place})"
                }
                "${winner.name} brought to ${spec.place}"
            }
            GoalKind.ANSWER -> {
                winner.say(spec.answers.orEmpty().first())
                "${winner.name} said « ${spec.answers.orEmpty().first()} »"
            }
            GoalKind.SURVIVE, GoalKind.JUDGED -> "nothing to do but wait"
        }
        delay(8_000)
        if (run.runtime.mechanics.challenges.list().any { it.ref == view.ref && it.status == ChallengeStatus.RUNNING }) {
            val id = view.ref.id
            run.onServer {
                val until = run.runtime.clock.millis() + 3_000
                StateStore(server).update { state -> state.copy(challenges = state.challenges.map { if (it.id == id && it.endsAt > until) it.copy(endsAt = until) else it }) }
            }
            run.note("${view.ref}'s end brought forward to 3 s from now")
        }
        return done
    }

    /** A closed room in the Nether, its chunks held loaded, near ([x], [z]) when given; its floor's center (M4-24). */
    private suspend fun netherRoom(x: Int? = null, z: Int? = null): BlockPos {
        val nether = run.server.getLevel(Level.NETHER)!!
        val at = if (x != null && z != null) BlockPos(x, NETHER_ROOM_Y, z) else run.helper.absolutePos(BlockPos(16, 0, 16)).atY(NETHER_ROOM_Y)
        fun chunks(): List<ChunkPos> {
            val from = ChunkPos.containing(at.offset(-ROOM_HALF, 0, -ROOM_HALF))
            val to = ChunkPos.containing(at.offset(ROOM_HALF, 0, ROOM_HALF))
            return (from.x..to.x).flatMap { cx -> (from.z..to.z).map { cz -> ChunkPos(cx, cz) } }
        }
        run.onServer {
            for (chunk in chunks()) nether.setChunkForced(chunk.x, chunk.z, true)
            for (dx in -ROOM_HALF..ROOM_HALF) for (dy in -1..4) for (dz in -ROOM_HALF..ROOM_HALF) {
                val shell = dx == -ROOM_HALF || dx == ROOM_HALF || dz == -ROOM_HALF || dz == ROOM_HALF || dy == -1 || dy == 4
                nether.setBlockAndUpdate(at.offset(dx, dy, dz), if (shell) Blocks.NETHERRACK.defaultBlockState() else Blocks.AIR.defaultBlockState())
            }
        }
        awaitCondition("the Nether room loaded") { run.onServer { chunks().all { nether.chunkSource.getChunkNow(it.x, it.z) != null } } }
        return at
    }

    // ── T6: the measures ────────────────────────────────────────────────────────────────────────────

    /** A turn of the evening, from the Journal and its transcript. */
    private class Turn(val id: Long, val conversation: String, val trigger: String, val outcome: String?, val requests: Long, val seconds: Double?) {
        var spoke = false
        var tail: String? = null
        var lastLatencyMs: Long? = null
        var lastOutput: Long? = null
        var opensOnArrival = false
        var first = false

        /** The chapter's first message, as sent (the briefing and what woke it): what opened the chapter (M6-FIX-11). */
        var opening: String? = null
        val opensEmpty: Boolean get() = opening?.contains("No player online.") == true
        val opensOnDeparture: Boolean get() = opening?.let { "] LEAVE " in it && "] JOIN " !in it } == true

        /** The ideas (`P-…`) its thinking or its calls cite: the programme the Director read in its briefing, its heartbeat or `inspire`. */
        val cited = mutableSetOf<Long>()
    }

    private suspend fun measures(id: String, mark: StyleMarks, start: Instant, end: Instant) {
        val minutes = Duration.between(start, end).toSeconds() / 60.0
        val hours = minutes / 60.0
        sections.append("## $id: the evening (${num(minutes)} min)\n\n")
        val calls = run.directorCalls(mark.toolCall)
        val turns = run.rows(
            "SELECT turn_id, conversation_id, trigger, outcome, coalesce(llm_calls, 0), CAST((epoch_ms(ended_at) - epoch_ms(started_at)) / 1000.0 AS DOUBLE) FROM turns " +
                "WHERE turn_id > ${mark.turn} ORDER BY turn_id",
        ).map { Turn(it[0] as Long, it[1].toString(), it[2].toString(), it[3]?.toString(), it[4] as Long, (it[5] as? Double)) }.associateBy { it.id }
        calls.filter { it.ok && it.tool in SPEECH_TOOLS }.forEach { call -> call.turn?.let { turns[it]?.spoke = true } }
        run.rows(
            "SELECT turn_id, latency_ms, coalesce(output_tokens, 0) FROM llm_calls WHERE turn_id > ${mark.turn} AND purpose = 'turn' AND id IN " +
                "(SELECT max(id) FROM llm_calls WHERE turn_id > ${mark.turn} AND purpose = 'turn' GROUP BY turn_id)",
        ).forEach { r -> turns[r[0] as Long]?.let { it.lastLatencyMs = r[1] as Long; it.lastOutput = r[2] as Long } }
        val chapters = turns.values.map { it.conversation }.distinct()
        readTranscripts(chapters, turns)

        // Silence on heartbeats.
        val beats = run.rows("SELECT decision, count(*) FROM engine_decisions WHERE kind = 'heartbeat' AND id > ${mark.decision} GROUP BY decision").associate { it[0].toString() to it[1] as Long }
        val heartbeatTurns = turns.values.filter { it.trigger == "heartbeat" }
        val silentBeats = heartbeatTurns.count { !it.spoke }
        verdict(id, "heartbeats: woke, skipped", "reported", "${beats["woke"] ?: 0} woke the Director, ${beats["skipped"] ?: 0} skipped by the reflexes", null)
        verdict(id, "heartbeat turns without a line", "most of them (M6-16, M6-FIX-9)", "$silentBeats/${heartbeatTurns.size}", if (heartbeatTurns.isEmpty()) null else silentBeats * 2 > heartbeatTurns.size)

        // The end of the turns.
        val ended = turns.values.filter { it.tail != null }
        val fin = ended.count { END_OF_TURN.matches(it.tail!!) }
        val empty = ended.count { it.tail!!.isBlank() }
        val reports = ended.filter { !END_OF_TURN.matches(it.tail!!) && it.tail!!.isNotBlank() }
        val latencies = turns.values.mapNotNull { it.lastLatencyMs }.sorted()
        verdict(
            id, "turns ended by « Fin. » (no report)", "no report",
            "$fin « Fin. », $empty empty, ${reports.size} another text, of ${ended.size} ended${reports.firstOrNull()?.let { " (« ${it.tail!!.take(120)} »)" }.orEmpty()}",
            ended.isEmpty() || reports.isEmpty(),
        )
        verdict(
            id, "the last answer of a turn", "reported",
            "${latencies.size} turns: ${num(latencies.average() / 1000)} s on average (median ${num((latencies.getOrNull(latencies.size / 2) ?: 0) / 1000.0)} s), " +
                "output ${turns.values.mapNotNull { it.lastOutput }.let { if (it.isEmpty()) 0 else it.sum() / it.size }} tokens on average",
            null,
        )

        // The chapter summaries (M6-FIX-9).
        val closed = run.rows("SELECT subject, reason FROM engine_decisions WHERE kind = 'chapter' AND decision = 'closed' AND id > ${mark.decision} ORDER BY id")
        val caught = closed.count { "asked again after an answer of" in it[1].toString() }
        val lost = closed.count { "even asked again" in it[1].toString() || "no summary" in it[1].toString() }
        val warned = run.logLines.lines().count { "is no summary" in it }
        verdict(id, "chapter summaries caught again / lost", "0 lost", "${closed.size} chapters closed: $caught asked again, $lost lost; $warned WARN « is no summary »", lost == 0)

        // Openings on an arrival.
        val openings = turns.values.filter { it.first && it.opensOnArrival }
        verdict(id, "chapter openings on an arrival left mute", "few (M6-FIX-9, D9)", "${openings.count { !it.spoke }}/${openings.size}", if (openings.isEmpty()) null else openings.count { !it.spoke } * 2 <= openings.size)
        // No chapter opened by a departure nor on an empty server (M6-FIX-11).
        val opened = turns.values.filter { it.first }
        val wrongOpenings = opened.filter { it.opensEmpty || it.opensOnDeparture }
        verdict(
            id, "chapters opened by a departure or on an empty server", "0 (M6-FIX-11)",
            "${wrongOpenings.size} of ${opened.size} opened (${opened.joinToString { "${it.conversation} by ${it.trigger}${if (it.opensOnArrival) ", an arrival" else ""}${if (it.opensEmpty) ", **nobody online**" else ""}${if (it.opensOnDeparture) ", **a departure**" else ""}" }})",
            wrongOpenings.isEmpty(),
        )

        // Long lines (more than three sentences, or a line break) and the notes `long` the Director got (M6-FIX-11).
        val said = calls.filter { it.ok && it.tool == "say" }.mapNotNull { it.text("text") }
        val long = said.filter { LineLength.of(it).long }
        val longNotes = run.rows(
            "SELECT strftime(ts, '%H:%M:%S'), coalesce(reason, '') FROM engine_decisions WHERE kind = 'style' AND decision = 'tone_noted' AND subject = 'long' AND id > ${mark.decision} ORDER BY id",
        )
        verdict(
            id, "long lines, and the notes `long` sent to the Director", "reported (M6-FIX-11)",
            "${long.size} of ${said.size} `say` lines long (${long.joinToString { LineLength.of(it).describe() }.ifEmpty { "none" }}); ${longNotes.size} tone_noted long" +
                longNotes.joinToString("") { " · `${it[0]}` ${it[1].toString().take(160)}" },
            null,
        )

        // remember: intentions never said.
        val remembers = calls.filter { it.tool == "remember" }
        val intentions = remembers.filter { call -> call.text("kind") in INTENTIONS && calls.none { it.turn == call.turn && it.ok && it.tool in SPEECH_TOOLS } }
        verdict(id, "`remember` of an intention never said to the players", "0", "${intentions.size} of ${remembers.size} remember calls", intentions.isEmpty())

        // The storyteller commissioned.
        val delegated = calls.filter { it.tool == "delegate" && it.text("role") == "storyteller" }
        val tasks = run.rows("SELECT task_id, status, coalesce(conversation_id, ''), coalesce(result, error, '') FROM tasks WHERE role = 'storyteller' AND requested_by_turn > ${mark.turn} ORDER BY task_id")
        val taskSpend = tasks.map { it[2].toString() }.filter { it.isNotEmpty() }.let { ids -> if (ids.isEmpty()) StyleSpend(0, 0, 0, 0, 0, 0.0) else run.spendOf("conversation_id IN (${ids.joinToString { "'$it'" }})") }
        verdict(id, "commissions to the storyteller", "reported (D11)", "${delegated.size} delegate calls, ${tasks.size} tasks (${tasks.joinToString { "T-${it[0]} ${it[1]}" }}), $taskSpend", null)

        // The programme: dealt, then used.
        val ideaRows = ideas("source = 'storyteller'").associateBy { it.id }
        val dealt = run.rows(
            "SELECT ref_id, strftime(ts, '%H:%M:%S'), channel, epoch_ms(ts), turn_id FROM recalls WHERE ts >= '${start}'::TIMESTAMPTZ AND ref_id IN (SELECT id FROM ideas) ORDER BY ts",
        )
        // Dealt, then followed by a lever of its kind (M6 §18.1, 4): in the turn that dealt it (the deal is recorded once the
        // request is accepted, after its first calls), or within 15 minutes.
        val usedDealt = dealt.filter { r ->
            val idea = ideaRows[r[0] as Long] ?: return@filter false
            val at = r[3] as Long
            calls.any { it.ok && it.tool in leversOf(idea.kind) && (it.turn == r[4] || it.millis in at..(at + 15 * 60_000L)) }
        }.map { it[0] as Long }.distinct()
        // Read in the briefing (shown is not dealt, M6-08): an idea the Director's thinking or calls cite in a turn that then acts
        // or speaks, or a module idea whose module it deploys.
        val programme = ideaRows.filterValues { it.status == "open" || it.status == "expired" }
        val usedCited = turns.values.filter { t -> t.spoke || calls.any { it.turn == t.id && it.ok && it.tool !in READ_TOOLS } }
            .flatMap { t -> t.cited.filter { it in programme } }.distinct()
        val usedModules = programme.values.filter { idea -> idea.kind == "module" && calls.any { it.ok && it.tool == "deploy_module" && it.text("module") == idea.module } }.map { it.id }
        val used = (usedDealt + usedCited + usedModules).distinct()
        verdict(
            id, "programme ideas used", "≥ 1 used",
            "${dealt.size} dealt (${dealt.joinToString { "P-${it[0]} ${it[2]} ${it[1]}" }}), then used ${usedDealt.map { "P-$it" }}; cited by a turn that acts ${usedCited.map { "P-$it" }}; " +
                "module deployed ${usedModules.map { "P-$it" }}: ${used.size} used of ${programme.size} open",
            used.isNotEmpty(),
        )

        // The openers refused.
        val refusals = run.rows("SELECT turn_id, subject, coalesce(CAST(detail->>'text' AS VARCHAR), '') FROM engine_decisions WHERE kind = 'style' AND decision = 'opener_refused' AND id > ${mark.decision} ORDER BY id")
        val reformulated = refusals.count { r -> calls.any { it.turn == r[0] && it.tool == "say" && it.ok } }
        val formulas = refusals.groupingBy { it[1].toString() }.eachCount().filter { (opener, n) -> n > 1 && FORMULA.containsMatchIn(opener) }
        verdict(id, "openers refused per hour, then said another way", "≤ 1 an hour", "${refusals.size} in ${num(hours)} h (${num(refusals.size / hours.coerceAtLeast(0.01))} an hour), $reformulated reformulated: ${refusals.joinToString { it[1].toString() }}", refusals.size <= maxOf(1.0, hours).toInt())
        verdict(id, "a formula of a law or a challenge refused more than once", "none", formulas.keys.joinToString().ifEmpty { "none" }, formulas.isEmpty())

        // The diversity (§3.7), against the acceptance of M4 and the seeded evenings.
        val (now, past, flagged) = run.read { connection ->
            val names = Tics.Names.of(Tics.names(connection))
            val evening = StyleReport.lines(connection, StyleReport.Period(start, end))
            val seeded = StyleReport.lines(connection, StyleReport.Period(start.minus(Duration.ofDays(30)), start.minus(Duration.ofHours(12))))
            Triple(StyleReport.measures(evening, names), StyleReport.measures(seeded, names), evening.filter { line -> line.flags.any { it.first in TONE_FLAGS } })
        }
        val crutch = now.crutches.firstOrNull()
        val crutchShare = crutch?.let { now.crutchShare(it.first) } ?: 0.0
        sections.append("- diversity of the evening: ${now.lines} lines, ${now.reviewed} reviewed · openers distinct ${num(now.openersDistinct)} · top register ${now.topRegister?.id ?: "none"} ${pct(now.topRegisterShare)} · ")
        sections.append("plain ${pct(now.plainShare)} · entropy ${num(now.registerEntropy)} · crutches ${now.crutches.take(5).joinToString { "${it.first} ${it.second}" }.ifEmpty { "none" }} · tics ${now.tics}\n")
        sections.append("  - registers: ${now.registers.entries.sortedByDescending { it.value }.joinToString { "${it.key.id} ${it.value}" }}; the five of D9 (${NEW_REGISTERS.joinToString()}): ")
        sections.append("${now.registers.filterKeys { it.id in NEW_REGISTERS }.entries.joinToString { "${it.key.id} ${it.value}" }.ifEmpty { "none" }}\n")
        sections.append("- the seeded evenings (M4-24 and M5-22, hand-labeled): ${past.lines} lines · openers distinct ${num(past.openersDistinct)} · top register ${past.topRegister?.id} ${pct(past.topRegisterShare)} · « céleste » ${pct(past.crutchShare("céleste"))}\n")
        verdict(id, "openers distinct (§3.7)", "≥ 0.85 (M4: 0.80)", num(now.openersDistinct), now.lines < 5 || now.openersDistinct >= 0.85)
        verdict(id, "top register's share (§3.7)", "≤ 35 %", "${now.topRegister?.id ?: "none"} ${pct(now.topRegisterShare)}", now.topRegisterShare <= 0.35)
        verdict(id, "registers' entropy (§3.7)", "≥ 0.75", num(now.registerEntropy), now.reviewed < 5 || now.registerEntropy >= 0.75)
        verdict(id, "top crutch's share (§3.7)", "≤ 10 % (M4: « céleste » 18 %)", "${crutch?.first ?: "none"} ${pct(crutchShare)}", crutchShare <= 0.10)
        verdict(id, "plain lines, tics", "reported", "plain ${pct(now.plainShare)}, ${now.tics} tics", null)

        // The tone judge over every line of the evening, and the flags of the line review it confirms.
        val spoken = if (!Files.isDirectory(run.conversations)) emptyList() else ReplaySituations.spokenLines(run.conversations).filter { line -> line.conversation in chapters }
        val lines = ReplaySituations.judged(spoken)
        val key = judgeKey
        if (key == null || lines.isEmpty()) {
            sections.append("- tone judge: not run (${if (key == null) "no key" else "no line"})\n")
        } else {
            val budget = LiveBudget(1.2)
            val judged = ToneJudge(run.runtime.settings.get().config.gateway.baseUrl, key, budget).use { it.grade(lines) }
            val grid = GridSummary.of(judged.grades.values)
            grid.missed().let { missed -> verdict(id, "the grid of §4.2 (tone judge)", "dark 0, cruel 0, voice ≥ 95 %, repeats ≤ 15 %, funny ≥ 1.5, legible ≥ 90 %, fair ≥ 85 %", grid.line(), missed.isEmpty()) }
            // The titles apart (M6-FIX-13: the spoken lines and the titles are not funny alike).
            val titles = spoken.indices.filter { spoken[it].tool == "show_title" }.map { lines[it].id }.toSet()
            val withoutTitles = judged.grades.filterKeys { it !in titles }.values
            verdict(
                id, "the grid without the titles", "reported (M6-FIX-13)",
                "${titles.size} titles left out: ${if (withoutTitles.isEmpty()) "no line" else GridSummary.of(withoutTitles).line()}" +
                    if (titles.isEmpty()) "" else "; the titles' funny ${judged.grades.filterKeys { it in titles }.values.joinToString { it.funny.toString() }}",
                null,
            )
            val confirmed = flagged.filter { line -> lines.any { it.text == line.text && judged.grades[it.id]?.let { g -> g.dark || g.cruel || !g.voice } == true } }
            verdict(id, "tone flags of the line review (dark, cruel, out of character ≥ 0.7) confirmed by the judge", "0 confirmed", "${flagged.size} flagged, ${confirmed.size} confirmed", confirmed.isEmpty())
            sections.append("- tone judge: ${judged.calls.size} requests, ${dollars(judged.dollars)}${judged.problems.takeIf { it.isNotEmpty() }?.let { " · problems: ${it.joinToString("; ")}" }.orEmpty()}\n")
            sections.append("\n### Every line of the evening, with its grade\n\n| # | turn | tool | situation | acts | grade | reason | line |\n|---|---|---|---|---|---|---|---|\n")
            lines.forEachIndexed { i, line ->
                val grade = judged.grades[line.id]
                sections.append("| ${line.id} | ${spoken[i].turn.cell()} | ${spoken[i].tool} | ${line.context.take(140).cell()} | ${line.acts.joinToString(" / ").take(200).cell()} | ${grade?.summary() ?: "not graded"} | ${grade?.reason.orEmpty().cell()} | ${line.text.cell()} |\n")
            }
            sections.append("\n")
        }
        sections.append("- flagged by the line review: ${flagged.joinToString { "« ${it.text.take(100)} » ${StyleReport.flagList(it)}" }.ifEmpty { "none" }}\n")

        // The modules of the Forge.
        val modules = run.rows("SELECT subject, decision, reason FROM engine_decisions WHERE kind = 'mechanic' AND id > ${mark.decision} AND decision IN ('started', 'fault', 'module_ended', 'restarted') ORDER BY id")
        val resumed = run.logLines.lines().count { "was given up again" in it }
        val abandoned = run.logLines.lines().count { "abandoned" in it && "was given up again" !in it }
        verdict(
            id, "modules of the Forge: faults, resumes, ms/s", "reported (FORGE-ABANDON review, 4(a))",
            "${modules.count { it[1] == "started" }} deployed, ${modules.count { it[1] == "fault" }} faults; resumed then given up again $resumed, abandoned $abandoned; " +
                "highest ms/s ${moduleTimes.entries.joinToString { "${it.key} ${num(it.value)}" }.ifEmpty { "-" }}",
            null,
        )

        // What the evening cost, the Chronicler apart (M6-FIX-12: one conversation across its jobs).
        val evening = run.spendOf("id > ${mark.llmCall}")
        val chronicler = run.spendOf("role = 'chronicler' AND id > ${mark.llmCall}")
        val chronicleConversations = run.rows("SELECT DISTINCT conversation_id FROM llm_calls WHERE role = 'chronicler' AND id > ${mark.llmCall}").map { it[0].toString() }
        val jobs = run.rows(
            "SELECT run_id, kind, trigger, status, coalesce(array_to_string(conversations, ','), ''), writes, rejected FROM memory_runs " +
                "WHERE kind IN ('batch', 'nap') AND started_at >= '${start}'::TIMESTAMPTZ ORDER BY run_id",
        )
        verdict(
            id, "the Chronicler's cost", "reported (M6-FIX-12)",
            "${chronicleConversations.size} conversation(s) ${chronicleConversations}, $chronicler (${pct(chronicler.dollars / evening.dollars.coerceAtLeast(1e-9))} of the evening); " +
                "jobs: ${jobs.joinToString { "run ${it[0]} ${it[1]} (${it[2]}) ${it[3]}, ${it[5]} writes, ${it[6]} refused, in ${it[4]}" }.ifEmpty { "none" }}",
            null,
        )
        verdict(id, "the evening's cost", "reported", "$evening in ${num(minutes)} min: ${dollars(evening.dollars / hours.coerceAtLeast(0.01))} an hour", null)

        // The rest, for a reading.
        sections.append("\n### The Narrator's lines (as the players read them)\n\n")
        run.narratorLines(mark.chat).forEach { sections.append("- `${it.first}` → ${it.second}: ${it.third}\n") }
        val shown = run.narratorLines(mark.chat).filter { REF_SHOWN.containsMatchIn(it.third) }
        sections.append("\n- references shown to the players (should be none): ${shown.size} ${shown.map { it.third.take(120) }}\n")
        sections.append("\n### Turns\n\n| turn | chapter | trigger | outcome | requests | seconds | spoke | last answer | tail |\n|---|---|---|---|---|---|---|---|---|\n")
        turns.values.forEach { t ->
            sections.append("| ${t.id}${if (t.first) " (opens${if (t.opensOnArrival) " on an arrival" else ""})" else ""} | ${t.conversation} | ${t.trigger} | ${t.outcome} | ${t.requests} | ${t.seconds} | ")
            sections.append("${if (t.spoke) "yes" else "no"} | ${t.lastLatencyMs?.let { "${it} ms, ${t.lastOutput} out" } ?: "-"} | ${t.tail?.take(80)?.cell() ?: "-"} |\n")
        }
        sections.append("\n### The Director's tool calls\n\n")
        calls.forEach { sections.append("- ").append(it.toString().replace("\n", " ")).append('\n') }
        sections.append("\n### remember calls\n\n")
        remembers.forEach { sections.append("- ${if (it in intentions) "**intention, never said** " else ""}$it\n") }
        sections.append("\n### Engine decisions of the evening (chapters, heartbeats, style, memory)\n\n")
        run.rows(
            "SELECT strftime(ts, '%H:%M:%S'), kind, decision, coalesce(subject, ''), coalesce(reason, '') FROM engine_decisions WHERE id > ${mark.decision} " +
                "AND kind IN ('chapter', 'heartbeat', 'style', 'memory', 'refusal', 'budget') ORDER BY id",
        ).forEach { sections.append("- `${it[0]}` ${it[1]} ${it[2]} · ${it[3]} · ${it[4].toString().take(300)}\n") }
        sections.append("\n### Chapters\n\n")
        run.rows(
            "SELECT conversation_id, chapter, end_reason, coalesce(summary, '(no summary)') FROM conversations WHERE role = 'director' AND conversation_id IN (${chapters.joinToString { "'$it'" }.ifEmpty { "''" }}) ORDER BY started_at",
        ).forEach { sections.append("#### Chapter ${it[1]} (${it[0]}, ${it[2] ?: "open"})\n\n```\n${it[3]}\n```\n\n") }
        tasks.forEach { sections.append("#### Storyteller T-${it[0]} ${it[1]}\n\n```\n${it[3]}\n```\n\n") }
    }

    /** The lever tools that play an idea of [kind] (as `ProgrammeInputs` reads « what followed »). */
    private fun leversOf(kind: String): Set<String> = when (kind) {
        "challenge" -> setOf("start_challenge")
        "duel" -> setOf("propose_duel")
        "punishment" -> setOf("punish", "apply_effect", "set_attribute", "spawn", "launch", "teleport", "take_item")
        "favor" -> setOf("favor", "give_item", "give_book", "apply_effect", "set_attribute")
        "law", "plan" -> setOf("create_rule")
        "module" -> setOf("deploy_module")
        "forge" -> setOf("forge_module")
        "speech" -> setOf("give_book", "remember", "say")
        else -> setOf("start_challenge", "propose_duel", "punish", "favor", "give_item", "give_book", "create_rule", "deploy_module", "spawn", "apply_effect")
    }

    /**
     * From the chapters' transcripts: each turn's tail (the last answer, when it calls no tool) and whether a chapter opens
     * on an arrival (a `JOIN` among the events of its first message). The chapter's summary, asked once it closes, is no
     * turn's.
     */
    private fun readTranscripts(chapters: List<String>, turns: Map<Long, Turn>) {
        for (chapter in chapters) {
            val file = run.transcript(chapter) ?: continue
            var turn: Turn? = null
            var firstTurn = true
            var lastText: String? = null
            var lastUsedTools = false
            fun close() {
                val t = turn ?: return
                if (lastText != null && !lastUsedTools) t.tail = lastText
                turn = null
            }
            for ((kind, data) in run.records(file)) {
                when (kind) {
                    "note" -> {
                        val text = (data["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                        if (text.startsWith("chapter ") && " closing " in text) close()
                        val id = TURN_NOTE.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: continue
                        close()
                        turn = turns[id]?.also { it.first = firstTurn }
                        firstTurn = false
                        lastText = null
                        lastUsedTools = false
                    }
                    "sent" -> turn?.takeIf { it.first }?.let { first ->
                        val content = data["content"]
                        if (first.opening == null && content is JsonArray) {
                            first.opening = content.joinToString("\n") { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty() }
                        }
                        if (first.opensOnArrival) return@let
                        if (content is JsonArray && content.any { "] JOIN " in ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull.orEmpty() }) first.opensOnArrival = true
                    }
                    "response" -> turn?.let { current ->
                        val blocks = (data["content"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                        blocks.forEach { block ->
                            val said = ((block["thinking"] as? JsonPrimitive)?.contentOrNull.orEmpty()) + (block["input"]?.toString().orEmpty())
                            IDEA_REF.findAll(said).forEach { current.cited += it.groupValues[1].toLong() }
                        }
                        lastUsedTools = blocks.any { (it["type"] as? JsonPrimitive)?.content == "tool_use" }
                        lastText = blocks.filter { (it["type"] as? JsonPrimitive)?.content == "text" }.joinToString("") { (it["text"] as? JsonPrimitive)?.contentOrNull.orEmpty() }.trim()
                    }
                }
            }
            close()
        }
    }

    private companion object {
        val ENDED = setOf("done", "failed", "cancelled", "timeout")

        /** The sheets' stats the written world is read by (`<players>` of the programme). */
        val SHOWN_STATS = setOf("playtime", "deaths", "mining", "exploration", "gear", "advancements")

        const val NETHER_ID = "minecraft:the_nether"
        const val NETHER_ROOM_Y = 100
        const val ROOM_HALF = 10

        /** What `remember` writes that is an intention when no line of its turn told the players. */
        val INTENTIONS = setOf("promise", "threat", "prophecy", "plan", "challenge")

        /** The flags of the line review the judge confirms (§3.7): dark, cruel, out of character. */
        val TONE_FLAGS = setOf(StyleNoteKind.DARK, StyleNoteKind.CRUEL, StyleNoteKind.OUT_OF_CHARACTER)

        /** The registers D9 added to the voice (M6-16). */
        val NEW_REGISTERS = setOf("movie_trailer", "tour_guide", "horoscope", "user_manual", "auction")

        /** An opener of a law's or a challenge's formula (M6 §18.1, 3). */
        val FORMULA = Regex("""(?i)(décret|défi|loi)""")

        /** `turn 12 · trigger heartbeat · …`, the transcript's note of a turn. */
        val TURN_NOTE = Regex("""^turn (\d+) ·""")

        /** The Director's tools that only read: a turn that calls nothing else did not act. */
        val READ_TOOLS = setOf(
            "recall", "player_profile", "player_stats", "inspect_player", "list_players", "sql", "tasks", "scan_area", "area_info", "locate",
            "command_help", "inspire",
        )
    }
}

private fun String.cell() = replace("|", "/").replace("\n", " ")

/** A title as the repeat rule compares it (`memory/MemoryRows.titleKey`): no accents, no case, no punctuation. */
private fun titleKey(title: String): String = java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "")
    .lowercase(Locale.ROOT).replace("œ", "oe").replace("æ", "ae").replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

// ── The players ─────────────────────────────────────────────────────────────────────────────────────

/**
 * A player of the run, with a fixed name and UUID (those of the written world): joins as a real one does, in survival,
 * at [home], a returning player (their vanilla play time is not zero, or the join would say « first time »); talks in the
 * public chat, types commands through their connection, mines, travels, dies and respawns. Keeps what they read: the
 * chat, the action bar (`[bar] …`) and the titles (`[title] …`). Game-side methods run on the server thread.
 */
private class StylePlayer(private val helper: GameTestHelper, val name: String, private val home: BlockPos, operator: Boolean) : AutoCloseable {
    private val server = helper.level.server
    private val profile = GameProfile(uuidOf(name), name)
    private val connection = StyleConnection()

    val read: List<String> get() = connection.lines.toList()

    init {
        val cookie = CommonListenerCookie.createInitial(profile, false)
        val player = ServerPlayer(server, helper.level, profile, cookie.clientInformation())
        if (player.stats.getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)) == 0) player.stats.setValue(player, Stats.CUSTOM.get(Stats.PLAY_TIME), RETURNING_PLAY_TICKS)
        EmbeddedChannel(connection)
        if (operator) server.playerList.op(NameAndId(profile), Optional.of(LevelBasedPermissionSet.GAMEMASTER), Optional.of(false))
        server.playerList.placeNewPlayer(connection, player, cookie)
        player.setGameMode(GameType.SURVIVAL)
        player.teleportTo(home.x + 0.5, home.y.toDouble(), home.z + 0.5)
        player.connection.handleAcceptPlayerLoad(ServerboundPlayerLoadedPacket())
        player.setOnGround(true)
    }

    val player: ServerPlayer get() = checkNotNull(server.playerList.getPlayer(profile.id)) { "$name is not online" }

    suspend fun say(text: String) = server.onServerThread {
        server.playerList.broadcastChatMessage(PlayerChatMessage.unsigned(player.uuid, text), player, ChatType.bind(ChatType.CHAT, player))
    }

    suspend fun command(line: String) = server.onServerThread { player.connection.handleChatCommand(ServerboundChatCommandPacket(line)) }

    /** Breaks [blocks] set in front of them, one a second, with an iron pickaxe. */
    suspend fun mine(blocks: List<Block>) {
        for ((i, block) in blocks.withIndex()) {
            val pos = BlockPos(home.x + 1 + i % 3, home.y, home.z + 1)
            server.onServerThread {
                helper.level.setBlockAndUpdate(pos, block.defaultBlockState())
                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.IRON_PICKAXE))
                player.gameMode.destroyBlock(pos)
            }
            delay(1_000)
        }
    }

    /** A death in lava, as a player who stepped in (the damage of lava, the death's cause `lava`). */
    suspend fun dieInLava() = server.onServerThread {
        val level = player.level()
        player.hurtServer(level, level.damageSources().lava(), 1_000f)
    }

    /** Back at home, in the overworld. Server thread. */
    fun goHome() = travel(TeleportTransition(helper.level, Vec3(home.x + 0.5, home.y.toDouble(), home.z + 0.5), Vec3.ZERO, 0f, 0f, TeleportTransition.DO_NOTHING))

    /** Into the Nether, on the floor of [room]. */
    suspend fun toNether(room: BlockPos) = server.onServerThread {
        val nether: ServerLevel = server.getLevel(Level.NETHER)!!
        travel(TeleportTransition(nether, Vec3.atBottomCenterOf(room), Vec3.ZERO, 0f, 0f, TeleportTransition.DO_NOTHING))
    }

    /** Through [transition] to a floor, loading the new level as a client does (`handoff/T05.md`). Server thread. */
    private fun travel(transition: TeleportTransition) {
        val changes = transition.newLevel() !== player.level()
        player.teleport(transition)
        if (changes) player.connection.handleAcceptPlayerLoad(ServerboundPlayerLoadedPacket())
        player.setOnGround(true)
    }

    /** Respawns them if they died (a mock player has no client to click "respawn"), at home; whether they had. */
    suspend fun respawnIfDead(): Boolean {
        val dead = server.onServerThread { server.playerList.getPlayer(profile.id)?.isDeadOrDying == true }
        if (!dead) return false
        delay(3_000)
        server.onServerThread {
            val back = server.playerList.respawn(player, false, Entity.RemovalReason.KILLED)
            back.connection.handleAcceptPlayerLoad(ServerboundPlayerLoadedPacket())
            back.teleportTo(home.x + 0.5, home.y.toDouble(), home.z + 0.5)
            back.setOnGround(true)
        }
        return true
    }

    override fun close() {
        server.playerList.getPlayer(profile.id)?.let { server.playerList.remove(it) }
    }
}

/** The vanilla play time of a returning player of the written world: an evening, in ticks. */
private const val RETURNING_PLAY_TICKS = 2 * 60 * 60 * 20

/** A connection that keeps the text of the chat lines, action bar lines and titles it sends, and sends everything on. */
private class StyleConnection : Connection(PacketFlow.SERVERBOUND) {
    val lines = CopyOnWriteArrayList<String>()

    override fun send(packet: Packet<*>, listener: ChannelFutureListener?, flush: Boolean) {
        when (packet) {
            is ClientboundSystemChatPacket -> lines += (if (packet.overlay()) "[bar] " else "") + packet.content().string
            is ClientboundSetTitleTextPacket -> lines += "[title] " + packet.text().string
            is ClientboundSetSubtitleTextPacket -> lines += "[title] " + packet.text().string
            else -> Unit
        }
        super.send(packet, listener, flush)
    }
}

/**
 * The mod's log lines at WARN and above (loggers `narrator2.*`), and the Forge's lines of a call resumed then given up
 * again or abandoned at any level, while open.
 */
private class StyleLog : AutoCloseable {
    private val lines = CopyOnWriteArrayList<Pair<String, String>>()
    private val context = (LogManager.getLogger("narrator2") as Logger).context
    private val appender = object : AbstractAppender("narrator2-live-style-log", null, null, true, Property.EMPTY_ARRAY) {
        override fun append(event: LogEvent) {
            if (!event.loggerName.startsWith("narrator2.")) return
            val text = event.message.formattedMessage
            val forge = "was given up again" in text || "abandoned" in text
            if (!forge && !event.level.isMoreSpecificThan(org.apache.logging.log4j.Level.WARN)) return
            lines += event.level.name() to (text + (event.thrown?.let { " · ${it.javaClass.simpleName}: ${it.message}" } ?: ""))
        }
    }

    init {
        appender.start()
        context.configuration.rootLogger.addAppender(appender, null, null)
        context.updateLoggers()
    }

    fun lines(): List<String> = lines.map { (level, text) -> "$level $text" }

    fun errors(): List<String> = lines.filter { it.first == "ERROR" || it.first == "FATAL" }.map { it.second }

    override fun close() {
        context.configuration.rootLogger.removeAppender(appender.name)
        context.updateLoggers()
        appender.stop()
    }
}
