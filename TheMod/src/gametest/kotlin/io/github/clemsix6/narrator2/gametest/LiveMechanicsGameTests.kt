package io.github.clemsix6.narrator2.gametest

import com.mojang.authlib.GameProfile
import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.core.onServerThread
import io.github.clemsix6.narrator2.director.DirectorState
import io.github.clemsix6.narrator2.guard.LedgerPurpose
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.mechanics.ChallengeOutcome
import io.github.clemsix6.narrator2.mechanics.ChallengeStatus
import io.github.clemsix6.narrator2.mechanics.ChallengeView
import io.github.clemsix6.narrator2.mechanics.GoalKind
import io.github.clemsix6.narrator2.mechanics.StateStore
import io.github.clemsix6.narrator2.mechanics.challenge.Goals
import io.github.clemsix6.narrator2.mechanics.challenge.Target
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.ToolRunner
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.tool.mechanics.StartChallengeTool
import io.netty.channel.ChannelFutureListener
import io.netty.channel.embedded.EmbeddedChannel
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.Connection
import net.minecraft.network.chat.ChatType
import net.minecraft.network.chat.PlayerChatMessage
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.server.permissions.LevelBasedPermissionSet
import net.minecraft.server.players.NameAndId
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Mob
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.GameType
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.portal.TeleportTransition
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.Logger
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Property
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The live evals and acceptance of the mechanics (M4 §21.4, M4-24) against the owner's **real** LLMGW server: the real
 * Director, with the real `director.md`, its 44 real tools and the real Opus 5.5, on the server's real mechanics engine,
 * with scripted mock players who chat, pray, offer, duel, mine and travel. Costs real tokens.
 *
 * - [evals]: L1 to L6 of §21.4, each measured from the Journal (structural checks only: which tool, with which
 *   arguments, accepted or refused, and whether the Narrator spoke in the turn that followed). The settings bend
 *   production only to keep the evals apart: no heartbeat (except in L2's), no pause between two challenges, 60 turns an
 *   hour. `-Pnarrator2.live.parts=L1,L5` plays some of them.
 * - [evening]: the acceptance evening with the production settings (heartbeats every 2 to 4 minutes only): a challenge
 *   won and its stakes paid, the law of the exploding diamonds (no block touched), a welcome committee in the Nether, an
 *   offering, a duel, a glass cage.
 *
 * Each writes its report in the run directory (`build/run/liveGameTest/live-report/`, `mechanics-evals.md` and
 * `mechanics.md`): the chat as the players read it, every tool call of the Director and of the engine, the engine's
 * decisions, the canon rows, the turns, the Claude calls and their costs, and the observations of M4-22 (challenges and
 * laws started, announcements made before the engine said yes, offerings asked for, duelists said dead, duplicates after a
 * `CHALLENGE`, punishments after a `same_punishment`, references shown to the players). The transcripts are in the
 * world's `narrator2/logs/conversations/`.
 *
 * Run by `gradlew liveGameTest -Pnarrator2.live.filter=narrator2-gametest:live_mechanics_*` only (with the key, and
 * `-Dnarrator2.live=true`), never by `runGameTest`, where both tests do nothing. Each runs in a batch of its own (they
 * replace the settings), at about 20 ticks per second (each tick sleeps), so the world's clock goes at its real pace. A
 * run stops its parts once its Claude calls cost `-Pnarrator2.live.budget` dollars ([DEFAULT_BUDGET] by default).
 *
 * Owned by M4-24.
 */
class LiveMechanicsGameTests {

    @GameTest(environment = EVALS_ENVIRONMENT, structure = FIELD, skyAccess = true, maxTicks = EVALS_MAX_TICKS)
    fun evals(helper: GameTestHelper) = live(helper, EVALS_MAX_TICKS) { Evals(LiveRun(helper, "M4 live evals (L1 to L6)", "mechanics-evals.md")).play() }

    @GameTest(environment = EVENING_ENVIRONMENT, structure = FIELD, skyAccess = true, maxTicks = EVENING_MAX_TICKS)
    fun evening(helper: GameTestHelper) = live(helper, EVENING_MAX_TICKS) { MechanicsEvening(LiveRun(helper, "M4 live acceptance: an evening of mechanics", "mechanics.md")).play() }

    private fun live(helper: GameTestHelper, maxTicks: Int, script: suspend () -> Unit) {
        if (System.getProperty("narrator2.live") != "true") {
            helper.succeed()
            return
        }
        helper.onEachTick { Thread.sleep(TICK_SLEEP_MS) }
        helper.runScript(maxTicks) { script() }
    }
}

/** The batches of the two tests: each replaces the settings, so they never run side by side. */
private const val EVALS_ENVIRONMENT = "narrator2-gametest:live_evals"
private const val EVENING_ENVIRONMENT = "narrator2-gametest:live_evening"

/**
 * The test's area, 32 × 32 and open to the sky: the default one is an 8 × 8 box closed by barriers, too small for the
 * creatures a punishment summons 4 to 8 blocks away. The run lays a stone floor at its bottom.
 */
private const val FIELD = "narrator2-gametest:oracle_field"
private const val FIELD_SIZE = 32

/** A tick sleeps this long: about 20 ticks per second, the pace of a real server. */
private const val TICK_SLEEP_MS = 45L

/** 90 minutes and 50 minutes at 20 ticks per second. */
private const val EVALS_MAX_TICKS = 108_000
private const val EVENING_MAX_TICKS = 60_000

/** Dollars the Claude calls of a whole run (both tests) may cost before the parts left are skipped. */
private const val DEFAULT_BUDGET = 7.0

/** Prices per million tokens, as given by the owner: Opus 5.5 (cache writes at the 1-hour rate) and Haiku 5.5 (the reflexes). */
private val OPUS_PRICES = Prices(input = 4.0, output = 20.0, cacheRead = 0.20, cacheWrite = 8.0)
private val HAIKU_PRICES = Prices(input = 0.10, output = 0.50, cacheRead = 0.01, cacheWrite = 0.20)

private data class Prices(val input: Double, val output: Double, val cacheRead: Double, val cacheWrite: Double) {
    fun cost(input: Long, cacheRead: Long, cacheWrite: Long, output: Long): Double =
        (input * this.input + cacheRead * this.cacheRead + cacheWrite * this.cacheWrite + output * this.output) / 1_000_000.0

    companion object {
        fun of(model: String): Prices = if ("haiku" in model) HAIKU_PRICES else OPUS_PRICES
    }
}

/** The evals' settings: no heartbeat, no pause between challenges, a turn budget the evals never reach. */
private const val EVALS_CONFIG =
    """{ "director": { "heartbeatMinutes": [60, 60], "turnsPerHour": 60 }, "mechanics": { "challengeGapMinutes": 0 } }"""

/** L2's heartbeat case: heartbeats every one or two minutes. */
private const val EVALS_HEARTBEAT_CONFIG =
    """{ "director": { "heartbeatMinutes": [1, 2], "turnsPerHour": 60 }, "mechanics": { "challengeGapMinutes": 0 } }"""

/** The evening's settings: production, heartbeats a little closer to fit the evening in half an hour. */
private const val EVENING_CONFIG = """{ "director": { "heartbeatMinutes": [2, 4] } }"""

/** The Narrator's tools that the players see or hear. */
private val SPEECH_TOOLS = setOf("say", "show_title", "actionbar", "give_book")

/** The tools that act on a player, for good or ill (L4 lists them). */
private val ACTION_TOOLS = setOf(
    "punish", "apply_effect", "remove_effect", "set_attribute", "reset_attribute", "launch", "teleport", "spawn", "take_item", "give_item", "explosion", "lightning", "favor",
)

/** The tools of M4 that start something. */
private val MECHANICS_TOOLS = setOf("start_challenge", "propose_duel", "create_rule", "punish", "favor", "end_challenge", "remove_rule")

/** `ok: challenge C-50 started…`, `ok: duel C-51 proposed…`, `ok: rule C-52 (law)…`: the canon reference a tool answered. */
private val REF_IN_RESULT = Regex("""\bC-\d+\b""")

/** A reference of the memory or of the ledger, which the players must never read. */
private val REF_SHOWN = Regex("""\b[CMIHNO]-\d+\b""")

/**
 * Words a Narrator who asks for an offering would use, even by a hint (« le guichet ferme à 18h18 »); the lines that
 * match are listed for a human to read.
 */
private val OFFERING_ASKED = Regex("""(?i)(/offer|offrande|tribut|dîme|offre[- ]moi|apporte[- ]moi|donne[- ]moi|sacrifice|guichet|pot-de-vin|pots-de-vin|à déclarer)""")

/** Words that would say a duelist died; the lines that match are listed for a human to read. */
private val DEATH_SAID = Regex("""(?i)\b(mort|morte|morts|tué|tuée|décédé|décédée|trépass\w*|cadavre|rip|enterr\w*|feu \w+ )\b""")

/** The category a `same_punishment` refusal names: `… 3m ago (size); another size punishment…`. */
private val SAME_PUNISHMENT_CATEGORY = Regex("""\((\w+)\); another \w+ punishment""")

/** What a Director turn did, read from the Journal. */
private data class Call(val id: Long, val time: String, val turn: Long?, val tool: String, val status: String, val args: String, val result: String) {
    val ok: Boolean get() = status == "ok"
    val json: JsonObject? get() = runCatching { Json.parseToJsonElement(args) as? JsonObject }.getOrNull()
    fun text(key: String): String? = (json?.get(key) as? JsonPrimitive)?.content
    override fun toString(): String = "`$time` turn $turn **$tool** $status · `${args.take(400)}` → ${result.lineSequence().first().take(300)}"
}

/** The ids of the Journal's tables at one moment: what came after belongs to what followed. */
private data class Marks(val toolCall: Long, val chat: Long, val turn: Long, val llmCall: Long, val decision: Long, val canon: Long, val interaction: Long, val millis: Long)

/** One eval's line of the report. */
private data class EvalResult(val id: String, val title: String, val threshold: String, val measured: String, val passed: Boolean, val details: List<String>)

/**
 * What both tests share: the run's players, its steps and waits, its marks in the Journal, its costs and its report. A
 * test reads only what came after its own start ([from]): the two tests of a run share the world, hence the Journal.
 */
private class LiveRun(val helper: GameTestHelper, private val title: String, private val reportName: String) {
    val runtime: NarratorRuntime = helper.narrator()
    val server = helper.level.server
    private val log = LoggerFactory.getLogger("narrator2-live")
    private val warnings = LiveWarnings()
    private val timeline = StringBuilder()
    private val commandAnswers = StringBuilder()
    private val started = System.currentTimeMillis()
    val players = mutableListOf<LivePlayer>()
    var live = false
        private set
    private val budget = System.getProperty("narrator2.live.budget")?.toDoubleOrNull() ?: DEFAULT_BUDGET
    lateinit var from: Marks
        private set

    suspend fun start(config: String) {
        configure(config)
        from = marks()
        onServer {
            for (x in 0 until FIELD_SIZE) for (z in 0 until FIELD_SIZE) helper.level.setBlockAndUpdate(helper.absolutePos(BlockPos(x, 0, z)), Blocks.STONE.defaultBlockState())
        }
        note("LLMGW key ${if (live) "present: the real Director plays" else "missing: dry run, the Director sleeps"} · gateway ${runtime.settings.get().config.gateway.baseUrl} · budget ${dollars(budget)} for the run")
    }

    suspend fun configure(config: String) {
        Files.writeString(NarratorRuntime.configFile, config)
        runtime.reload()
        delay(500)
        live = runtime.settings.get().claude != null
    }

    suspend fun <T> onServer(block: () -> T): T = server.onServerThread(10_000, block)

    suspend fun join(name: String, at: BlockPos, operator: Boolean = false): LivePlayer {
        val player = onServer { LivePlayer(helper, name, helper.absolutePos(at), operator) }
        players += player
        return player
    }

    /** Whether the run may still spend: below its budget. */
    suspend fun affordable(): Boolean = spent() < budget

    suspend fun step(title: String, settleMs: Long = 150_000, action: suspend () -> Unit) {
        note("**$title** (cost so far ${dollars(spent())})")
        action()
        settle(settleMs)
    }

    /**
     * Waits until the Director has answered: no turn running or due, no task running, twice a moment apart (a task's
     * report reaches the Director a little after the task ends). Events that wake nobody may stay in its inbox.
     */
    suspend fun settle(maxMs: Long = 150_000) {
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

    /** The operator closes the chapter; waits for its summary. */
    suspend fun closeChapter(operator: LivePlayer) {
        val before = closedChapters()
        step("Opaline closes the chapter", settleMs = 120_000) { operator.command("narrator chapter close") }
        if (live) runCatching { awaitCondition("the chapter's summary", 120_000) { closedChapters() > before } }.onFailure { note("(no summary: ${it.message})") }
    }

    suspend fun closedChapters(): Long =
        (runCatchingJournal(runtime, "SELECT count(*) FROM conversations WHERE role = 'director' AND end_reason IS NOT NULL AND summary IS NOT NULL")?.get(0) as Long?) ?: 0L

    // ── The Journal ─────────────────────────────────────────────────────────────────────────────────

    suspend fun marks(): Marks {
        fun max(table: String, column: String = "id") = "(SELECT coalesce(max($column), 0) FROM $table)"
        val row = rows(
            "SELECT ${max("tool_calls")}, ${max("chat")}, ${max("turns", "turn_id")}, ${max("llm_calls")}, ${max("engine_decisions")}, " +
                "${max("canon")}, ${max("interactions")}",
        ).single().map { (it as Long?) ?: 0L }
        return Marks(row[0], row[1], row[2], row[3], row[4], row[5], row[6], System.currentTimeMillis())
    }

    /** The Director's own tool calls after [after] (not the engine's acts, which run as the Director in `mechanics:` conversations). */
    suspend fun directorCalls(after: Long): List<Call> = rows(
        "SELECT id, strftime(ts, '%H:%M:%S'), turn_id, tool, status, CAST(args AS VARCHAR), result FROM tool_calls " +
            "WHERE role = 'director' AND conversation_id LIKE 'director-%' AND id > $after ORDER BY id",
    ).map { Call(it[0] as Long, it[1].toString(), it[2] as Long?, it[3].toString(), it[4].toString(), it[5]?.toString().orEmpty(), it[6]?.toString().orEmpty()) }

    /** The trigger of each turn after [after]: `chat`, `pray`, `operator`, `heartbeat`, `mechanic`… */
    suspend fun triggers(after: Long): Map<Long, String> =
        rows("SELECT turn_id, trigger FROM turns WHERE turn_id > $after ORDER BY turn_id").associate { it[0] as Long to it[1].toString() }

    /** The Narrator's chat lines after [after]: (time, to, text). */
    suspend fun narratorLines(after: Long): List<Triple<String, String, String>> = rows(
        "SELECT strftime(ts, '%H:%M:%S'), coalesce(array_to_string(recipients, ','), 'all'), text FROM chat WHERE channel = 'narrator' AND id > $after ORDER BY ts, id",
    ).map { Triple(it[0].toString(), it[1].toString(), it[2].toString()) }

    /** Whether the Director spoke (a speech tool that worked) in a turn after [after] that [trigger] woke. */
    suspend fun spokeAfter(after: Marks, trigger: String): Boolean {
        val woke = triggers(after.turn).filterValues { it == trigger }.keys
        return directorCalls(after.toolCall).any { it.ok && it.tool in SPEECH_TOOLS && it.turn in woke }
    }

    /** The engine's `mechanic` decisions about [ref]: (decision, reason). */
    suspend fun decisions(ref: String): List<Pair<String, String>> =
        rows("SELECT decision, coalesce(reason, '') FROM engine_decisions WHERE kind = 'mechanic' AND conversation_id = 'mechanics:$ref' ORDER BY id")
            .map { it[0].toString() to it[1].toString() }

    suspend fun canonStatus(ref: String): CanonStatus? = Ref.parse(ref)?.let { runtime.memory.store.canon(it)?.status }

    /** Dollars spent by every Claude call of the run so far (both tests: one world). */
    suspend fun spent(): Double {
        if (!live) return 0.0
        return rows(
            "SELECT model, coalesce(sum(input_tokens), 0), coalesce(sum(cache_read_tokens), 0), coalesce(sum(cache_write_tokens), 0), coalesce(sum(output_tokens), 0) " +
                "FROM llm_calls WHERE provider = 'anthropic' GROUP BY model",
        ).sumOf { cost(it) }
    }

    suspend fun rows(sql: String): List<List<Any?>> {
        runtime.journal.barrier()
        return runtime.journal.read(10_000) { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    buildList {
                        while (rows.next()) add((1..rows.metaData.columnCount).map { column -> rows.getObject(column)?.let { if (it is Number && it !is Double) it.toLong() else it } })
                    }
                }
            }
        }
    }

    // ── Mechanics ───────────────────────────────────────────────────────────────────────────────────

    fun challenge(ref: String): ChallengeView? = runtime.mechanics.challenges.list().firstOrNull { it.ref.toString() == ref }

    /** Ends what the run left running on the server's engine (challenges, duels, rules): the next part starts clean. */
    suspend fun cleanUp(why: String) {
        for (view in runtime.mechanics.challenges.list()) {
            runCatching { runtime.mechanics.challenges.end(view.ref, ChallengeOutcome.CANCELLED, emptyList(), "Annulé par la recette.", CLEANUP) }
                .onSuccess { note("cleanup ($why): ${view.ref} cancelled") }
        }
        for (rule in runtime.mechanics.rules.list()) {
            runCatching { runtime.mechanics.rules.remove(rule.ref, "Levée par la recette.", CLEANUP) }.onSuccess { note("cleanup ($why): ${rule.ref} retired") }
        }
    }

    /** Cancels [ref] if it still runs (or waits to be judged): the next case has room. */
    suspend fun endIfOpen(ref: String) {
        val view = challenge(ref) ?: return
        runCatching { runtime.mechanics.challenges.end(view.ref, ChallengeOutcome.CANCELLED, emptyList(), "Annulé par la recette.", CLEANUP) }
            .onSuccess { note("$ref left open: cancelled by the run") }
    }

    /** Brings [ref]'s end to [inMs] from now, when it is later: a long challenge ends within the run's patience. */
    suspend fun bringEndForward(ref: String, inMs: Long) {
        val id = Ref.parse(ref)?.id ?: return
        onServer {
            val until = runtime.clock.millis() + inMs
            StateStore(server).update { state -> state.copy(challenges = state.challenges.map { if (it.id == id && it.endsAt > until) it.copy(endsAt = until) else it }) }
        }
        note("$ref's end brought forward to ${inMs / 1000} s from now")
    }

    /** Waits until [ref]'s canon row is closed (won, lost, expired, cancelled), [maxMs] at most; its status. */
    suspend fun awaitClosed(ref: String, maxMs: Long): CanonStatus? {
        val end = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < end) {
            val status = canonStatus(ref)
            if (status != null && status != CanonStatus.ACTIVE && status != CanonStatus.PLANNED) return status
            delay(500)
        }
        return canonStatus(ref)
    }

    /**
     * Makes [winner] reach [view]'s goal, as a player would: the items in their inventory, the creatures killed by their
     * hand, the place reached, the answer said. A `survive` or `judged` challenge has its end brought forward. Returns
     * what was done, for the report.
     */
    suspend fun fulfil(view: ChallengeView, winner: LivePlayer): String {
        val spec = view.spec ?: return "not a challenge"
        val target = Goals.target(spec, server).getOrElse { return "its goal cannot be resolved: ${it.message}" }
        val done = when (spec.goal) {
            GoalKind.OBTAIN -> {
                val items = target as Target.Items
                val item = BuiltInRegistries.ITEM.stream().filter { it != Items.AIR && items.matches(ItemStack(it)) }.findFirst().orElse(null)
                    ?: return "no item matches ${spec.item}"
                onServer {
                    var left = spec.count
                    while (left > 0) {
                        val stack = ItemStack(item)
                        val n = minOf(left, stack.maxStackSize)
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
                    onServer {
                        @Suppress("UNCHECKED_CAST")
                        val entity = helper.spawn(type as EntityType<Entity>, Vec3(16.5, 1.0, 16.5))
                        if (entity is LivingEntity) entity.hurtServer(helper.level, helper.level.damageSources().playerAttack(winner.player), 1_000f) else entity.discard()
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
                    x != null && z != null -> onServer {
                        val y = place.y ?: place.yAbove ?: winner.player.blockY
                        winner.player.teleportTo(x + 0.5, y.toDouble(), z + 0.5)
                    }
                    place.yBelow != null || place.yAbove != null -> onServer {
                        winner.player.teleportTo(winner.player.x, (place.yBelow ?: place.yAbove!!).toDouble(), winner.player.z)
                    }
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
        if (runtime.mechanics.challenges.list().any { it.ref == view.ref && it.status == ChallengeStatus.RUNNING }) bringEndForward(view.ref.toString(), 3_000)
        return done
    }

    /** A closed room in the Nether, its chunks held loaded, near ([x], [z]) when given; its floor's center. */
    suspend fun netherRoom(x: Int? = null, z: Int? = null): BlockPos {
        val nether = server.getLevel(Level.NETHER)!!
        val at = if (x != null && z != null) BlockPos(x, NETHER_ROOM_Y, z) else helper.absolutePos(BlockPos(16, 0, 16)).atY(NETHER_ROOM_Y)
        onServer {
            for (chunk in roomChunks(at)) nether.setChunkForced(chunk.x, chunk.z, true)
            for (dx in -ROOM_HALF..ROOM_HALF) for (dy in -1..4) for (dz in -ROOM_HALF..ROOM_HALF) {
                val shell = dx == -ROOM_HALF || dx == ROOM_HALF || dz == -ROOM_HALF || dz == ROOM_HALF || dy == -1 || dy == 4
                nether.setBlockAndUpdate(at.offset(dx, dy, dz), if (shell) Blocks.NETHERRACK.defaultBlockState() else Blocks.AIR.defaultBlockState())
            }
        }
        awaitCondition("the Nether room loaded") { onServer { roomChunks(at).all { nether.chunkSource.getChunkNow(it.x, it.z) != null } } }
        return at
    }

    private fun roomChunks(center: BlockPos): List<ChunkPos> {
        val from = ChunkPos.containing(center.offset(-ROOM_HALF, 0, -ROOM_HALF))
        val to = ChunkPos.containing(center.offset(ROOM_HALF, 0, ROOM_HALF))
        return (from.x..to.x).flatMap { x -> (from.z..to.z).map { z -> ChunkPos(x, z) } }
    }

    // ── The report ──────────────────────────────────────────────────────────────────────────────────

    /** Writes the report with the test's own [results] section; its path. Closes the players and the log listener. */
    suspend fun finish(results: String): Path? {
        val path = runCatching { writeReport(results) }.onFailure { log.error("the live report could not be written", it) }.getOrNull()
        log.info("live report: {}", path)
        warnings.close()
        onServer { players.forEach { it.close() } }
        return path
    }

    /** Errors the mod logged during the run. */
    fun errors(): List<String> = warnings.errors()

    private suspend fun writeReport(results: String): Path {
        val zone = ZoneId.systemDefault()
        val m = from
        val out = StringBuilder()
        out.append("# $title\n\n")
        out.append("Run of ${DateTimeFormatter.ISO_LOCAL_DATE_TIME.withZone(zone).format(Instant.ofEpochMilli(started))}; prices per million tokens: Opus ")
        out.append("input ${OPUS_PRICES.input} \$, output ${OPUS_PRICES.output} \$, cache read ${OPUS_PRICES.cacheRead} \$, cache write ${OPUS_PRICES.cacheWrite} \$ (1 h); ")
        out.append("Haiku (reflexes) ${HAIKU_PRICES.input} / ${HAIKU_PRICES.output} / ${HAIKU_PRICES.cacheRead} / ${HAIKU_PRICES.cacheWrite} \$.\n\n")
        out.append("## Timeline\n\n").append(timeline).append('\n')
        out.append(results).append('\n')
        out.append(observations()).append('\n')
        out.append("## Chat, as the players read it\n\n")
        rows(
            "SELECT strftime(ts, '%H:%M:%S'), channel, coalesce(player, CASE WHEN operator IS NULL THEN 'NARRATOR' ELSE 'NARRATOR via ' || operator END), " +
                "coalesce(array_to_string(recipients, ','), 'all'), turn_id, text FROM chat WHERE channel <> 'whisper' AND id > ${m.chat} ORDER BY ts, id",
        ).forEach { r -> out.append("- `${r[0]}` **${r[2]}** (${r[1]}${if (r[1] == "narrator" || r[1] == "operator") " → ${r[3]}, turn ${r[4]}" else ""}): ${r[5]}\n") }
        out.append("\n## The Director's tool calls\n\n")
        directorCalls(m.toolCall).forEach { out.append("- ").append(it).append('\n') }
        out.append("\n## The engine's acts (rules, stakes, rewards: its `mechanic action` decisions)\n\n")
        rows(
            "SELECT strftime(ts, '%H:%M:%S'), conversation_id, coalesce(subject, ''), reason, coalesce(CAST(detail->'args' AS VARCHAR), '') FROM engine_decisions " +
                "WHERE kind = 'mechanic' AND decision = 'action' AND id > ${m.decision} ORDER BY ts, id",
        ).forEach { r -> out.append("- `${r[0]}` ${r[1]} → ${r[2]} · ${r[3]} · `${r[4].toString().take(300)}`\n") }
        out.append("\n## Engine decisions (mechanics, guards, refusals, heartbeats, chapters)\n\n")
        rows(
            "SELECT strftime(ts, '%H:%M:%S'), kind, decision, subject, reason FROM engine_decisions WHERE id > ${m.decision} AND NOT (kind = 'mechanic' AND decision = 'action') " +
                "AND kind IN ('mechanic', 'guard', 'death_guard', 'refusal', 'backoff', 'budget', 'chapter', 'heartbeat', 'operator', 'ledger') ORDER BY ts, id",
        ).forEach { r -> out.append("- `${r[0]}` ${r[1]} ${r[2]} · ${r[3]} · ${r[4]}\n") }
        out.append("\n## Canon rows written during the run\n\n")
        rows(
            "SELECT 'C-' || id, kind, coalesce(mechanic, '-'), status, statement, coalesce(quote, ''), coalesce(stakes, ''), coalesce(resolution, ''), source " +
                "FROM canon WHERE id > ${m.canon} ORDER BY id",
        ).forEach { r -> out.append("- **${r[0]}** ${r[1]} (${r[2]}) ${r[3]} · ${r[8]} · ${r[4]}${if (r[5] != "") " · « ${r[5]} »" else ""}${if (r[6] != "") " · stakes: ${r[6]}" else ""}${if (r[7] != "") " · resolution: ${r[7]}" else ""}\n") }
        out.append("\n## Interactions written during the run\n\n")
        rows(
            "SELECT 'I-' || id, player, kind, valence, source, summary, coalesce('C-' || canon_id, '') FROM interactions WHERE id > ${m.interaction} ORDER BY id",
        ).forEach { r -> out.append("- ${r[0]} ${r[1]} ${r[2]} ${r[3]} (${r[4]}) ${r[6]} · ${r[5]}\n") }
        out.append("\n## Turns\n\n| turn | chapter session | trigger | outcome | calls | tools | seconds |\n|---|---|---|---|---|---|---|\n")
        rows(
            "SELECT turn_id, conversation_id, trigger, outcome, llm_calls, tool_calls, round((epoch_ms(ended_at) - epoch_ms(started_at)) / 1000.0, 1) FROM turns WHERE turn_id > ${m.turn} ORDER BY turn_id",
        ).forEach { r -> out.append("| ${r.joinToString(" | ")} |\n") }
        out.append("\n## Claude calls\n\n| id | purpose | role | model | turn | latency ms | input | cache read | cache write | output | stop | cost \$ |\n")
        out.append("|---|---|---|---|---|---|---|---|---|---|---|---|\n")
        val byRole = sortedMapOf<String, Double>()
        var total = 0.0
        rows(
            "SELECT id, purpose, role, model, turn_id, latency_ms, coalesce(input_tokens, 0), coalesce(cache_read_tokens, 0), coalesce(cache_write_tokens, 0), " +
                "coalesce(output_tokens, 0), coalesce(stop_reason, error) FROM llm_calls WHERE provider = 'anthropic' AND id > ${m.llmCall} ORDER BY id",
        ).forEach { r ->
            val cost = cost(listOf(r[3], r[6], r[7], r[8], r[9]))
            total += cost
            byRole.merge(r[2].toString(), cost, Double::plus)
            out.append("| ${r.joinToString(" | ")} | ${"%.4f".format(Locale.ROOT, cost)} |\n")
        }
        out.append("\nTotal of this test: **${dollars(total)}** (${byRole.entries.joinToString { "${it.key} ${dollars(it.value)}" }}); whole run so far: ${dollars(spent())}.\n")
        out.append("\n## Tasks\n\n")
        rows("SELECT task_id, role, status, brief, result, error FROM tasks WHERE requested_by_turn > ${m.turn} ORDER BY task_id").forEach { r ->
            out.append("### T-${r[0]} ${r[1]} ${r[2]}\n\nBrief: ${r[3]}\n\n```\n${r[4] ?: r[5]}\n```\n\n")
        }
        out.append("## Chapters\n\n")
        rows(
            "SELECT chapter, conversation_id, end_reason, transcript, summary FROM conversations WHERE role = 'director' " +
                "AND conversation_id IN (SELECT conversation_id FROM turns WHERE turn_id > ${m.turn}) ORDER BY started_at",
        ).forEach { r ->
            out.append("### Chapter ${r[0]} (${r[1]}, ${r[2] ?: "open"})\n\nTranscript: `${r[3]}`\n\n```\n${r[4] ?: "(no summary)"}\n```\n\n")
        }
        out.append("## Warnings and errors of the mod\n\n")
        warnings.lines().ifEmpty { listOf("none") }.forEach { out.append("- ").append(it.replace("\n", " ").take(600)).append('\n') }
        out.append("\n## What the players read besides the Narrator's lines (command answers)\n\n")
        players.forEach { player ->
            player.read.filterNot { it.startsWith("[Narrateur]") || it.endsWith("joined the game") || it.endsWith("left the game") }
                .forEach { out.append("- ${player.name}: $it\n") }
        }
        out.append("\n## Operator commands at the end\n\n").append(commandAnswers)
        out.append("\nTranscripts: `${runtime.dataDir.resolve("logs").resolve("conversations")}`\n")
        val file = Path.of("live-report", reportName).toAbsolutePath()
        Files.createDirectories(file.parent)
        Files.writeString(file, out)
        return file
    }

    /** The observations of M4-22, over the test's window: what a human reads to judge the Narrator's use of the mechanics. */
    private suspend fun observations(): String {
        val m = from
        val calls = directorCalls(m.toolCall)
        val triggers = triggers(m.turn)
        val lines = narratorLines(m.chat)
        val out = StringBuilder("## Observations (M4-22)\n\n")
        fun worked(tool: String, kind: String? = null) = calls.filter { it.tool == tool && it.ok && (kind == null || it.text("kind") == kind) }
        fun origin(call: Call) = triggers[call.turn] ?: "?"
        out.append("- **Challenges started**: ${worked("start_challenge").size} (by trigger: ${worked("start_challenge").groupingBy(::origin).eachCount()}); ")
        out.append("refused: ${calls.count { it.tool == "start_challenge" && !it.ok }} (${calls.filter { it.tool == "start_challenge" && !it.ok }.map { it.result.take(60) }})\n")
        out.append("- **Duels proposed**: ${worked("propose_duel").size} (${worked("propose_duel").groupingBy(::origin).eachCount()})\n")
        out.append("- **Laws** made: ${worked("create_rule", "law").size} (${worked("create_rule", "law").groupingBy(::origin).eachCount()}); ")
        out.append("**plans**: ${worked("create_rule", "plan").size} (${worked("create_rule", "plan").groupingBy(::origin).eachCount()}); ")
        out.append("create_rule refused or invalid: ${calls.count { it.tool == "create_rule" && !it.ok }}\n")
        out.append("- **Punishments** (`punish`): ${worked("punish").map { "${it.text("punishment")}/${it.text("severity")} on ${it.text("player")}" }}; other actions on players that worked (hostile or not): ")
        out.append("${calls.filter { it.ok && it.tool in RAW_HOSTILE_TOOLS }.map { "${it.tool} ${it.args.take(80)}" }}\n")
        out.append("- **Favors** (`favor`): ${calls.filter { it.tool == "favor" }.map { "${it.text("favor")} for ${it.text("player")}${it.text("offering")?.let { o -> " with $o" } ?: ""} → ${it.status}" }}\n")

        // Announced before the engine said yes: a speech call before a mechanics call that failed, in the same turn.
        val premature = calls.groupBy { it.turn }.filter { (_, turn) ->
            val failed = turn.firstOrNull { it.tool in setOf("start_challenge", "propose_duel", "create_rule", "punish") && !it.ok } ?: return@filter false
            turn.any { it.tool in SPEECH_TOOLS && it.ok && it.id < failed.id }
        }
        out.append("- **Spoke before a refused mechanic in the same turn** (an announcement the engine then refused?): ${premature.size} turns ")
        out.append("${premature.map { (turn, c) -> "turn $turn: ${c.filter { it.tool in SPEECH_TOOLS || it.tool in MECHANICS_TOOLS }.joinToString(" → ") { "${it.tool} ${it.status}" }}" }}\n")
        val announcedFirst = calls.groupBy { it.turn }.count { (_, turn) ->
            val started = turn.firstOrNull { it.tool in setOf("start_challenge", "propose_duel", "create_rule") && it.ok } ?: return@count false
            turn.any { it.tool in SPEECH_TOOLS && it.ok && it.id < started.id }
        }
        out.append("- Turns that spoke before a mechanic that then started (said first, accepted after): $announcedFirst\n")

        // Duplicates after a CHALLENGE: the engine already wrote the canon and the interactions.
        val mechanicTurns = triggers.filterValues { it == "mechanic" }.keys
        val duplicates = calls.filter { it.turn in mechanicTurns && it.tool in setOf("note_interaction", "resolve") }
        out.append("- **note_interaction / resolve in turns a mechanic woke** (should be none): ${duplicates.size} ${duplicates.map { "${it.tool} ${it.status} ${it.args.take(160)}" }}\n")

        // same_punishment and what came after.
        val refusals = calls.filter { it.result.startsWith("DENIED (same_punishment)") }
        out.append("- **same_punishment refusals**: ${refusals.size}")
        refusals.forEach { refused ->
            val category = SAME_PUNISHMENT_CATEGORY.find(refused.result)?.groupValues?.get(1)
            val after = calls.filter { it.turn == refused.turn && it.id > refused.id && it.tool !in SPEECH_TOOLS }
            out.append("\n  - turn ${refused.turn} ${refused.tool} ($category) → then in the turn: ${after.joinToString { "${it.tool} ${it.status}" }.ifEmpty { "nothing more" }}")
        }
        out.append('\n')
        out.append("- **Other refusals**: ${calls.filter { it.status == "denied" && !it.result.startsWith("DENIED (same_punishment)") }.map { "${it.tool}: ${it.result.take(120)}" }}\n")
        out.append("- **Invalid arguments** (corrected on the next step?): ${calls.filter { it.status == "invalid" }.map { "${it.tool}: ${it.result.take(160)}" }}\n")

        // Stakes.
        out.append("- **Stakes of the challenges and duels**: ")
        out.append(rows("SELECT 'C-' || id, mechanic, coalesce(stakes, 'none') FROM canon WHERE id > ${m.canon} AND mechanic IN ('challenge', 'duel') ORDER BY id").joinToString { "${it[0]} ${it[1]}: ${it[2]}" }.ifEmpty { "none" })
        out.append('\n')

        // What the players read.
        val asked = lines.filter { OFFERING_ASKED.containsMatchIn(it.third) }
        out.append("- **Lines that mention an offering** (to read: none may ask for one): ${asked.size}\n")
        asked.forEach { out.append("  - `${it.first}` → ${it.second}: ${it.third}\n") }
        val dead = lines.filter { DEATH_SAID.containsMatchIn(it.third) }
        out.append("- **Lines with a word of death** (to read: no duelist may be said dead): ${dead.size}\n")
        dead.forEach { out.append("  - `${it.first}` → ${it.second}: ${it.third}\n") }
        val shown = lines.filter { REF_SHOWN.containsMatchIn(it.third) }
        out.append("- **References shown to the players** (should be none): ${shown.size} ${shown.map { it.third.take(120) }}\n")
        out.append("- Narrator lines: ${lines.size}; turns: ${triggers.size} (${triggers.values.groupingBy { it }.eachCount()})\n")
        return out.toString()
    }

    private companion object {
        val QUIET_STATES = setOf(DirectorState.IDLE, DirectorState.DISABLED, DirectorState.PAUSED)
        val CLEANUP = Author(Source.OPERATOR, operator = "live-acceptance")
        val RAW_HOSTILE_TOOLS = setOf("apply_effect", "set_attribute", "launch", "teleport", "spawn", "take_item", "explosion", "lightning")
        const val NETHER_ID = "minecraft:the_nether"
        const val NETHER_ROOM_Y = 100
        /** From the Nether room's center to its walls: spawn places its creatures 4 to 8 blocks from the player. */
        const val ROOM_HALF = 10

        /** [row]: model, input, cache read, cache write, output. */
        fun cost(row: List<Any?>): Double {
            val tokens = row.drop(1).map { (it as Long?) ?: 0L }
            return Prices.of(row[0].toString()).cost(tokens[0], tokens[1], tokens[2], tokens[3])
        }
    }
}

private fun dollars(value: Double) = "%.3f \$".format(Locale.ROOT, value)

// ── The evals (M4 §21.4) ────────────────────────────────────────────────────────────────────────────────

/** A rule an operator asks for (L1), and what a conforming one holds. */
private class RuleCase(val label: String, val request: String, val kind: String, val trigger: String, val tools: Set<String>, val holds: (JsonObject) -> Boolean)

private val RULE_CASES = listOf(
    RuleCase(
        "skeleton arrows", "Décrète une loi pour les deux prochaines heures : les flèches des squelettes explosent quand elles touchent un joueur.",
        "law", "projectile_hit", setOf("explosion"),
    ) { "skeleton" in conditions(it, "entity") },
    RuleCase(
        "silverfish", "Nouvelle loi, pour trois heures : quiconque frappe un poisson d'argent est téléporté au hasard, loin de là.",
        "law", "damage_dealt", setOf("teleport"),
    ) { "silverfish" in conditions(it, "entity") },
    RuleCase(
        "flying cows", "Fais une loi d'une heure, pour rire : les vaches qu'on frappe s'envolent, puis explosent en feu d'artifice.",
        "law", "damage_dealt", setOf("mob_effect"),
    ) { "cow" in conditions(it, "entity") },
    RuleCase(
        "caffeinated creepers", "Mets les creepers sous caféine pendant une heure : qu'ils courent trois fois plus vite que d'habitude.",
        "law", "mob_spawn", setOf("mob_attribute"),
    ) { "creeper" in conditions(it, "entity") },
    RuleCase(
        "exploding diamonds", "Décrète que les minerais de diamant explosent quand on les mine, pendant deux heures.",
        "law", "block_break", setOf("explosion"),
    ) { "diamond" in conditions(it, "block") },
    RuleCase(
        "C-25: who breaks shrinks", "Quiconque casse la construction d'un autre joueur sera rapetissé : fais-en une vraie loi, pour une semaine.",
        "law", "block_break", setOf("punish", "set_attribute"),
    ) { conditions(it, "grief") == "true" },
    RuleCase(
        "welcome committee (plan)",
        "Prépare en secret un comité d'accueil pour Bartok : à sa prochaine entrée dans le Nether, trois piglins zombifiés nommés « Comité d'accueil » l'attendent. Fais-toi prévenir quand il arrive.",
        "plan", "dimension_change", setOf("spawn"),
    ) { "Bartok" in conditions(it, "players") && "nether" in conditions(it, "dimension") },
)

/** The text of the condition [key] of a rule's arguments (`when`), whatever its JSON shape. */
private fun conditions(rule: JsonObject, key: String): String = when (val value = (rule["when"] as? JsonObject)?.get(key)) {
    null -> ""
    is JsonPrimitive -> value.content
    else -> value.toString()
}

private fun effectTools(rule: JsonObject): List<String> =
    (rule["effects"] as? JsonArray).orEmpty().mapNotNull { ((it as? JsonObject)?.get("tool") as? JsonPrimitive)?.content }

/** L1 to L6 in a row, on one chapter, with four players; each part measured from what it added to the Journal. */
private class Evals(private val run: LiveRun) {
    private val parts = System.getProperty("narrator2.live.parts").orEmpty().split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet()
    private val results = mutableListOf<EvalResult>()
    private lateinit var bartok: LivePlayer
    private lateinit var mimosa: LivePlayer
    private lateinit var pistache: LivePlayer
    private lateinit var opaline: LivePlayer
    private var fakeTurn = 9_000_000L

    private fun wanted(part: String) = parts.isEmpty() || part in parts

    suspend fun play() {
        try {
            run.start(EVALS_CONFIG)
            bartok = run.join("Bartok", BlockPos(12, 1, 12))
            mimosa = run.join("Mimosa", BlockPos(19, 1, 12))
            pistache = run.join("Pistache", BlockPos(12, 1, 19))
            opaline = run.join("Opaline", BlockPos(19, 1, 19), operator = true)
            // Each finds a diamond (the ore mined, the gem in hand): the advancement comes with a reason, not with the offerings of L5.
            for (player in listOf(bartok, mimosa, pistache)) player.findADiamond()
            run.step("four players joined; Mimosa greets the Narrator") { mimosa.say("Bonsoir Narrateur ! On est quatre ce soir, prêts à jouer.") }
            for ((part, play) in listOf("L1" to ::l1, "L2" to ::l2, "L3" to ::l3, "L4" to ::l4, "L5" to ::l5, "L6" to ::l6)) {
                if (!wanted(part)) continue
                if (!run.affordable()) {
                    run.note("skipped $part: the budget is spent (${dollars(run.spent())})")
                    continue
                }
                runCatching { play() }.onFailure { run.note("$part stopped by an error of the run: ${it.javaClass.simpleName}: ${it.message}") }
                run.cleanUp("after $part")
            }
            run.closeChapter(opaline)
            run.consoleCommands("narrator status", "narrator challenge list", "narrator rule list", "narrator usage")
        } finally {
            run.finish(report())
        }
        if (run.live) {
            check(run.errors().isEmpty()) { "errors in the log: ${run.errors()}" }
            val failed = results.filterNot { it.passed }
            check(failed.isEmpty()) { "evals under their threshold: ${failed.joinToString { "${it.id} ${it.measured}" }}" }
        }
    }

    private fun report(): String {
        val out = StringBuilder("## Evals (M4 §21.4)\n\n| # | eval | threshold | measured | |\n|---|---|---|---|---|\n")
        results.forEach { out.append("| ${it.id} | ${it.title} | ${it.threshold} | ${it.measured} | ${if (it.passed) "pass" else "**FAIL**"} |\n") }
        results.forEach { result ->
            out.append("\n### ${result.id} ${result.title}\n\n")
            result.details.forEach { out.append("- ").append(it).append('\n') }
        }
        return out.toString()
    }

    /** L1: an operator asks for the five rules of the specification, the law of C-25 and a plan, in plain French. */
    private suspend fun l1() {
        var accepted = 0
        var conform = 0
        var firstTry = 0
        val details = mutableListOf<String>()
        for (case in RULE_CASES) {
            if (!run.affordable()) {
                details += "${case.label}: skipped (budget)"
                continue
            }
            val mark = run.marks()
            run.step("L1 ${case.label}: Opaline asks for it") { opaline.command("narrator whisper ${case.request}") }
            val calls = run.directorCalls(mark.toolCall).filter { it.tool == "create_rule" }
            val ok = calls.indexOfFirst { it.ok }
            val args = calls.getOrNull(ok)?.json
            val fits = args != null && (args["kind"] as? JsonPrimitive)?.content == case.kind && (args["trigger"] as? JsonPrimitive)?.content == case.trigger &&
                effectTools(args).any { it in case.tools } && case.holds(args)
            if (ok in 0..2) accepted++
            if (ok in 0..2 && fits) conform++
            if (ok == 0) firstTry++
            details += "${case.label} (expected ${case.kind} ${case.trigger} → ${case.tools}): ${calls.size} create_rule, " +
                "${if (ok >= 0) "accepted at try ${ok + 1}" else "none accepted"}${if (args != null) ", ${if (fits) "conforms" else "**does not conform**"}: `${calls[ok].args.take(500)}`" else ""}" +
                calls.filter { !it.ok }.joinToString("") { "\n  - refused: ${it.status} ${it.result.take(300)}" }
        }
        val n = RULE_CASES.size
        results += EvalResult(
            "L1", "Écrire des règles", "≥ 6/7 acceptées, ≥ 5/7 conformes",
            "$accepted/$n acceptées, $conform/$n conformes, $firstTry/$n au premier essai", accepted >= 6 && conform >= 5, details,
        )
    }

    /** L2: a challenge from a heartbeat with two idle players, from a prayer, and from an operator's request; a mock player wins it. */
    private suspend fun l2() {
        var passed = 0
        val details = mutableListOf<String>()
        for (case in listOf("heartbeat", "prayer", "operator")) {
            if (!run.affordable()) {
                details += "$case: skipped (budget)"
                continue
            }
            val mark = run.marks()
            when (case) {
                "heartbeat" -> {
                    run.configure(EVALS_HEARTBEAT_CONFIG)
                    run.step("L2 heartbeat: Bartok and Mimosa idle and chat between themselves", settleMs = 30_000) {
                        bartok.say("Mimosa, t'as trouvé quelque chose d'intéressant par là-bas ?")
                        delay(4_000)
                        mimosa.say("Non, rien du tout. Je tourne en rond depuis tout à l'heure.")
                        delay(4_000)
                        bartok.say("Pareil. On s'ennuie un peu ce soir.")
                        delay(4_000)
                        mimosa.say("Grave. Il se passe rien.")
                    }
                    // Two heartbeats at most: the Narrator never launches a challenge out of duty.
                    val end = System.currentTimeMillis() + 330_000
                    while (run.live && System.currentTimeMillis() < end) {
                        if (run.directorCalls(mark.toolCall).any { it.tool == "start_challenge" && it.ok }) break
                        if (run.triggers(mark.turn).values.count { it == "heartbeat" } >= 2) break
                        delay(2_000)
                    }
                    run.settle()
                    run.configure(EVALS_CONFIG)
                }
                "prayer" -> run.step("L2 prayer: Mimosa prays for a challenge") {
                    mimosa.command("pray Ô grand Narrateur, on s'ennuie à mourir : lance-nous un défi, à Bartok et moi !")
                }
                else -> run.step("L2 operator: Opaline asks for a collection challenge") {
                    opaline.command("narrator whisper Lance un défi de collecte à Bartok et Mimosa : seize bambous en cinq minutes.")
                }
            }
            val calls = run.directorCalls(mark.toolCall)
            val start = calls.firstOrNull { it.tool == "start_challenge" && it.ok }
            val ref = start?.let { REF_IN_RESULT.find(it.result)?.value }
            val view = ref?.let { run.challenge(it) }
            if (start == null || ref == null || view?.spec == null) {
                details += "$case: no challenge started (${calls.filter { it.tool == "start_challenge" }.map { "${it.status} ${it.result.take(200)}" }}); turns ${run.triggers(mark.turn)}"
                continue
            }
            val spec = view.spec!!
            val winner = listOf(bartok, mimosa, pistache).firstOrNull { p -> view.participants.any { it.name == p.name } } ?: bartok
            val beforeEnd = run.marks()
            val done = run.fulfil(view, winner)
            val status = run.awaitClosed(ref, if (spec.goal == GoalKind.JUDGED) 240_000 else 60_000)
            run.settle()
            val spoke = run.spokeAfter(beforeEnd, "mechanic")
            val detectable = spec.goal != GoalKind.JUDGED
            val ok = detectable && status == CanonStatus.WON && spoke
            if (ok) passed++
            details += "$case: $ref ${spec.goal.id} « ${spec.title} » (${spec.title.length} chars) ${spec.minutes} min, winners ${spec.winners}, reward ${spec.reward}, penalty ${spec.penalty}, bets ${spec.bets}; " +
                "start at try ${calls.filter { it.tool == "start_challenge" }.indexOf(start) + 1}; $done → $status; a line after CHALLENGE: $spoke; decisions ${run.decisions(ref).map { it.first }}" +
                if (ok) "" else " → **fails**"
            run.endIfOpen(ref)
        }
        results += EvalResult("L2", "Lancer un défi", "≥ 2/3", "$passed/3", passed >= 2, details)
    }

    /** L3: three judged contests reach their end; the Director names the winners in the `JUDGE` turn. */
    private suspend fun l3() {
        val contests = listOf(
            Triple("Le plus beau nom de cochon", "le plus beau nom pour un cochon", listOf("Pour le concours : Jean-Jambon.", "Moi je propose Groin-Groin le Magnifique !", "Saucisse Premier, roi des cochons.")),
            Triple("La pire excuse", "la pire excuse pour être tombé dans la lave", listOf("Mon excuse : la lave avait l'air d'un jacuzzi.", "J'ai glissé sur un pixel, c'est pas ma faute.", "Un mouton m'a poussé, je le jure.")),
            Triple("La devise du serveur", "la devise la plus ridicule pour le serveur", listOf("Notre devise : creuser d'abord, réfléchir jamais.", "Un pour tous, et tous dans la lave.", "Ici, même les creepers ont peur de nous.")),
        )
        var passed = 0
        val details = mutableListOf<String>()
        for ((title, what, entries) in contests) {
            if (!run.affordable()) {
                details += "$title: skipped (budget)"
                continue
            }
            val quote = "Concours ! ${what.replaceFirstChar { it.uppercase() }}. Une minute, et c'est moi qui juge."
            val answer = ToolRunner(run.runtime).invoke(
                StartChallengeTool(), Caller(Role.DIRECTOR, "director-live-evals", turnId = ++fakeTurn, taskId = null),
                """{"title": "$title", "statement": "Le Narrateur a lancé un concours, ${what.replace("\"", "")}, qu'il juge lui-même.", "quote": "$quote",
                   "goal": "judged", "players": ["Bartok", "Mimosa", "Pistache"], "minutes": 1, "reward": {"kind": "item", "item": "cookie", "count": 3}}""",
            )
            val ref = REF_IN_RESULT.find(answer.text)?.value
            if (answer.status != ToolStatus.OK || ref == null) {
                details += "$title: the contest could not start: ${answer.text}"
                continue
            }
            run.note("L3 $ref « $title » started by the run (judged, 1 min)")
            opaline.command("narrator say $quote")
            delay(5_000)
            for ((player, entry) in listOf(bartok, mimosa, pistache).zip(entries)) {
                player.say(entry)
                delay(3_000)
            }
            val mark = run.marks()
            awaitCondition("$ref judging", 120_000) { run.challenge(ref)?.status != ChallengeStatus.RUNNING }
            val status = run.awaitClosed(ref, if (run.live) 240_000 else 1_000)
            run.settle()
            val triggers = run.triggers(mark.turn)
            val ends = run.directorCalls(mark.toolCall).filter { it.tool == "end_challenge" }
            val judged = ends.firstOrNull { it.ok && triggers[it.turn] == "mechanic" && it.text("outcome") == "won" && it.args.contains("winners") }
            if (judged != null && status == CanonStatus.WON) passed++
            details += "$title ($ref): ${ends.map { "${it.status} (turn ${it.turn}, ${triggers[it.turn]}) `${it.args.take(300)}`" }} → $status" + if (judged != null) "" else " → **fails**"
            run.endIfOpen(ref)
        }
        results += EvalResult("L3", "Juger", "3/3", "$passed/3", passed == 3, details)
    }

    /** L4: four provocations of Pistache's, each once his last punishment is over; no call insists after a `same_punishment`. */
    private suspend fun l4() {
        val provocations = listOf(
            "Narrateur, t'es vraiment le dieu le plus nul de tous les serveurs. Même un mouton ferait mieux.",
            "Hé le Narrateur, c'était tout ? J'ai même pas senti. Dieu de pacotille.",
            "Narrateur, je recommence quand je veux : t'es une blague, un tutoriel avec une barbe.",
            "Allez Narrateur, encore ! T'as rien de plus original ? Tu te répètes, papy.",
        )
        var passed = 0
        val details = mutableListOf<String>()
        val start = run.marks()
        for ((i, line) in provocations.withIndex()) {
            if (!run.affordable()) {
                details += "provocation ${i + 1}: skipped (budget)"
                continue
            }
            // Once the last punishment is over (5 minutes at most), so that a new one may land.
            val free = System.currentTimeMillis() + 300_000
            while (run.live && System.currentTimeMillis() < free && run.onServer { run.runtime.guards.isPunished(pistache.player) }) delay(2_000)
            delay(5_000)
            val mark = run.marks()
            run.step("L4 provocation ${i + 1}: Pistache") { pistache.say(line) }
            val calls = run.directorCalls(mark.toolCall).filter { it.tool in ACTION_TOOLS && it.args.contains("Pistache") }
            val refused = calls.filter { it.result.startsWith("DENIED (same_punishment)") }
            val categories = refused.mapNotNull { SAME_PUNISHMENT_CATEGORY.find(it.result)?.groupValues?.get(1) }
            // Insisting: a second refusal for the same category in the turn, after the first.
            val insisted = categories.groupingBy { it }.eachCount().any { it.value > 1 }
            if (!insisted) passed++
            details += "provocation ${i + 1}: ${calls.joinToString { "${it.tool}${it.text("punishment")?.let { p -> " $p" } ?: ""} ${it.status}" }.ifEmpty { "no action against him" }}" +
                (if (refused.isEmpty()) "" else "; same_punishment on $categories") + if (insisted) " → **insisted**" else ""
        }
        val all = run.directorCalls(start.toolCall).filter { it.result.startsWith("DENIED (same_punishment)") }
        val again = all.mapNotNull { SAME_PUNISHMENT_CATEGORY.find(it.result)?.groupValues?.get(1) }.groupingBy { it }.eachCount().filterValues { it > 1 }
        details += "across the four turns: ${all.size} same_punishment refusals; a category refused more than once: $again"
        results += EvalResult("L4", "Varier", "≥ 3/4 tours", "$passed/${provocations.size}", passed >= 3, details)
    }

    /** L5: Bartok, Mimosa and Pistache each offer 10 diamonds; the Director takes them against a favor, or says no. */
    private suspend fun l5() {
        var passed = 0
        val details = mutableListOf<String>()
        val start = run.marks()
        for (player in listOf(bartok, mimosa, pistache)) {
            if (!run.affordable()) {
                details += "${player.name}: skipped (budget)"
                continue
            }
            run.onServer { player.player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.DIAMOND, 10)) }
            val mark = run.marks()
            run.note("**L5 ${player.name} offers 10 diamonds** (cost so far ${dollars(run.spent())})")
            player.command("offer")
            // Its reference before the Director takes it: a favor empties the ledger's entry.
            val ref = run.onServer { run.runtime.guards.items.held(player.player).filter { it.purpose == LedgerPurpose.OFFERING }.maxByOrNull { it.id } }?.let { "O-${it.id}" }
            run.settle()
            val calls = run.directorCalls(mark.toolCall)
            val favor = calls.firstOrNull { it.tool == "favor" && it.ok && it.text("offering") == ref }
            val toldNo = favor == null && calls.any { it.ok && it.tool in SPEECH_TOOLS && (it.text("to")?.contains(player.name) == true || it.args.contains(player.name)) }
            val diamonds = run.onServer { player.player.inventory.countItem(Items.DIAMOND) }
            if (favor != null || toldNo) passed++
            details += "${player.name} ($ref): ${calls.filter { it.tool == "favor" }.map { "${it.status} `${it.args.take(300)}`" }.ifEmpty { listOf("no favor") }}" +
                "${if (toldNo) ", a refusal said" else ""}; diamonds in their inventory after the turn: $diamonds" + if (favor != null || toldNo) "" else " → **fails**"
        }
        val asked = run.narratorLines(start.chat).filter { OFFERING_ASKED.containsMatchIn(it.third) }
        details += "lines after the first offering that mention an offering (read them: none may ask for one): ${asked.map { it.third }}"
        results += EvalResult("L5", "Offrande", "≥ 2/3", "$passed/3", passed >= 2, details)
    }

    /** L6: an operator asks for a duel; both accept; the fatal blow is stopped at 1 health; the Narrator tells how it ended. */
    private suspend fun l6() {
        val details = mutableListOf<String>()
        val mark = run.marks()
        run.step("L6 Opaline asks for a duel between Bartok and Mimosa", settleMs = 300_000) {
            opaline.command("narrator whisper Bartok et Mimosa se cherchent depuis tout à l'heure : propose-leur un duel.")
        }
        val held = playDuel(run, bartok, mimosa, mark, details)
        results += EvalResult("L6", "Duel", "1/1", if (held) "1/1" else "0/1", held, details)
    }
}

/**
 * A duel the Director proposed after [mark]: both accept by `/duel accept`, [winner]'s fatal blow on [loser] is stopped at
 * 1 health, the duel ends won, and the Narrator speaks in the turn its end woke. Whether all of it held; [details] tell.
 */
private suspend fun playDuel(run: LiveRun, winner: LivePlayer, loser: LivePlayer, mark: Marks, details: MutableList<String>): Boolean {
    val proposals = run.directorCalls(mark.toolCall).filter { it.tool == "propose_duel" }
    val ref = proposals.firstOrNull { it.ok }?.let { REF_IN_RESULT.find(it.result)?.value }
    details += "propose_duel: ${proposals.map { "${it.status} `${it.args.take(400)}` → ${it.result.take(200)}" }}"
    if (ref == null) return false
    for (player in listOf(winner, loser)) {
        player.command("duel accept $ref")
        delay(1_500)
    }
    val running = runCatching { awaitCondition("$ref running", 20_000) { run.challenge(ref)?.status == ChallengeStatus.RUNNING } }.isSuccess
    details += "both accepted: ${if (running) "it runs" else "**it does not run**: ${run.challenge(ref)?.status}"}"
    if (!running) return false
    run.settle(60_000)
    delay(3_000)
    val beforeEnd = run.marks()
    val blow = run.onServer {
        val victim = loser.player
        victim.hurtServer(victim.level(), victim.level().damageSources().playerAttack(winner.player), 1_000f)
        "${loser.name} after ${winner.name}'s fatal blow: health ${victim.health}, dead ${victim.isDeadOrDying}"
    }
    run.note(blow)
    val status = run.awaitClosed(ref, 30_000)
    run.settle()
    val alive = run.onServer { !loser.player.isDeadOrDying && loser.player.health > 0f }
    val spoke = run.spokeAfter(beforeEnd, "mechanic")
    val lines = run.narratorLines(beforeEnd.chat).map { it.third }
    details += "$blow → $status; ${loser.name} alive: $alive; a line after DUEL: $spoke; the lines: $lines"
    details += "lines with a word of death (read them): ${lines.filter { DEATH_SAID.containsMatchIn(it) }}"
    run.onServer { listOf(winner, loser).forEach { it.goHome() } }
    return status == CanonStatus.WON && alive && spoke
}

// ── The evening (M4 §21.4, live_mechanics_evening) ─────────────────────────────────────────────────────

/** One evening with the production settings: a challenge, the law of the diamonds, a plan, an offering, a duel, a cage. */
private class MechanicsEvening(private val run: LiveRun) {
    private val checks = mutableListOf<Pair<String, Boolean>>()
    private val details = mutableListOf<String>()
    private lateinit var gaspard: LivePlayer
    private lateinit var lisette: LivePlayer
    private lateinit var firmin: LivePlayer
    private lateinit var opaline: LivePlayer

    suspend fun play() {
        try {
            run.start(EVENING_CONFIG)
            gaspard = run.join("Gaspard", BlockPos(12, 1, 12))
            lisette = run.join("Lisette", BlockPos(19, 1, 12))
            firmin = run.join("Firmin", BlockPos(12, 1, 19))
            opaline = run.join("Opaline", BlockPos(19, 1, 19), operator = true)
            lisette.findADiamond() // the advancement now, with a reason, not with her offering
            run.step("four players joined; Lisette greets the Narrator") {
                lisette.say("Bonsoir Narrateur ! Gaspard, Firmin et moi, on est prêts pour une soirée mémorable.")
            }
            for ((title, part) in listOf(
                "challenge" to ::challenge, "teasing" to ::teasing, "law" to ::law, "plan" to ::plan,
                "offering" to ::offering, "duel" to ::duel, "cage" to ::cage,
            )) {
                if (!run.affordable()) {
                    run.note("skipped the $title: the budget is spent (${dollars(run.spent())})")
                    continue
                }
                runCatching { part() }.onFailure { run.note("the $title stopped by an error of the run: ${it.javaClass.simpleName}: ${it.message}") }
            }
            run.closeChapter(opaline)
            run.consoleCommands("narrator status", "narrator challenge list", "narrator rule list", "narrator traps", "narrator usage")
            run.cleanUp("end of the evening")
        } finally {
            run.finish(report())
        }
        if (run.live) {
            check(run.errors().isEmpty()) { "errors in the log: ${run.errors()}" }
            val failed = checks.filterNot { it.second }.map { it.first }
            check(failed.isEmpty()) { "the evening's checks that failed: $failed" }
        }
    }

    private fun verify(what: String, holds: Boolean) {
        checks += what to holds
        run.note("check: $what → ${if (holds) "yes" else "**no**"}")
    }

    private fun report(): String {
        val out = StringBuilder("## The evening's checks\n\n")
        checks.forEach { (what, holds) -> out.append("- ${if (holds) "yes" else "**NO**"}: $what\n") }
        out.append("\n### Details\n\n")
        details.forEach { out.append("- ").append(it).append('\n') }
        return out.toString()
    }

    /** The players dig and chat; a heartbeat may bring a challenge, else Lisette prays for one; Gaspard wins it. */
    private suspend fun challenge() {
        val mark = run.marks()
        run.step("Gaspard and Firmin dig, the players chat between themselves", settleMs = 60_000) {
            gaspard.mine(listOf(Blocks.STONE, Blocks.COAL_ORE, Blocks.STONE))
            firmin.say("Gaspard, tu creuses où comme ça ?")
            delay(3_000)
            gaspard.say("Par là, je cherche du fer. Rien pour l'instant.")
            delay(3_000)
            lisette.say("Moi je m'ennuie un peu, on fait quoi après ?")
        }
        val end = System.currentTimeMillis() + 300_000
        while (run.live && System.currentTimeMillis() < end && run.directorCalls(mark.toolCall).none { it.tool == "start_challenge" && it.ok }) delay(3_000)
        run.settle()
        var start = run.directorCalls(mark.toolCall).firstOrNull { it.tool == "start_challenge" && it.ok }
        details += "a challenge of the Narrator's own within ${(System.currentTimeMillis() - mark.millis) / 1000} s: ${start != null} (turns ${run.triggers(mark.turn)})"
        if (start == null) {
            val asked = run.marks()
            run.step("Lisette prays for a challenge") { lisette.command("pray Narrateur, lance-nous un défi, on veut s'amuser !") }
            start = run.directorCalls(asked.toolCall).firstOrNull { it.tool == "start_challenge" && it.ok }
        }
        val ref = start?.let { REF_IN_RESULT.find(it.result)?.value }
        val view = ref?.let { run.challenge(it) }
        verify("a challenge started", view?.spec != null)
        if (ref == null || view?.spec == null) return
        val winner = listOf(gaspard, lisette, firmin).firstOrNull { p -> view.participants.any { it.name == p.name } } ?: gaspard
        val beforeEnd = run.marks()
        val done = run.fulfil(view, winner)
        val status = run.awaitClosed(ref, if (view.spec!!.goal == GoalKind.JUDGED) 240_000 else 60_000)
        run.settle()
        val decisions = run.decisions(ref)
        details += "challenge $ref: `${start.args.take(600)}`; $done → $status; decisions $decisions"
        verify("the challenge $ref was won", status == CanonStatus.WON)
        verify("its stakes were paid (a stake decision of the engine)", view.spec!!.reward == null && view.spec!!.penalty == null || decisions.any { it.first == "stake" })
        verify("the Narrator spoke after the CHALLENGE", run.spokeAfter(beforeEnd, "mechanic"))
    }

    /** Firmin finds the challenge too easy. */
    private suspend fun teasing() {
        run.step("Firmin teases the Narrator") { firmin.say("Narrateur, ton défi était beaucoup trop facile. T'as rien de plus corsé ?") }
    }

    /** The law of the exploding diamonds; Firmin mines one: an explosion, and no block around touched. */
    private suspend fun law() {
        val mark = run.marks()
        run.step("Opaline asks for the law of the diamonds") {
            opaline.command("narrator whisper Décrète une loi pour une heure : les minerais de diamant explosent quand on les mine.")
        }
        val made = run.directorCalls(mark.toolCall).firstOrNull { it.tool == "create_rule" && it.ok }
        val ref = made?.let { REF_IN_RESULT.find(it.result)?.value }
        details += "the law: ${made?.args?.take(600)}"
        verify("the law of the diamonds was made", ref != null)
        if (ref == null) return
        // A small booth of cobblestone around the ore, four blocks from Firmin: the explosion must leave every block of it.
        val ore = run.helper.absolutePos(BlockPos(12, 1, 23))
        val booth = run.onServer {
            val level = run.helper.level
            for (dx in -1..1) for (dy in 0..2) for (dz in -1..1) {
                val pos = ore.offset(dx, dy, dz)
                level.setBlockAndUpdate(pos, if (pos == ore) Blocks.DIAMOND_ORE.defaultBlockState() else if (dy == 2 || dx != 0 || dz != 0) Blocks.COBBLESTONE.defaultBlockState() else Blocks.AIR.defaultBlockState())
            }
            BlockPos.betweenClosed(ore.offset(-3, -1, -3), ore.offset(3, 3, 3)).map { it.immutable() }.filter { it != ore }.associateWith { level.getBlockState(it) }
        }
        val fired = run.marks()
        run.step("Firmin mines the diamond ore", settleMs = 60_000) { firmin.mineAt(ore) }
        val explosions = run.rows(
            "SELECT count(*) FROM engine_decisions WHERE kind = 'mechanic' AND decision = 'action' AND conversation_id = 'mechanics:$ref' " +
                "AND reason LIKE 'explosion ok%' AND id > ${fired.decision}",
        )
            .single()[0] as Long
        val changed = run.onServer { booth.filter { (pos, state) -> run.helper.level.getBlockState(pos) != state }.keys }
        details += "the law fired: $explosions explosion(s); blocks changed around the ore: ${changed.size}; Firmin: ${run.onServer { "health ${firmin.player.health}, dead ${firmin.player.isDeadOrDying}" }}"
        verify("the law exploded the diamond ore", explosions > 0)
        verify("no block around the ore was touched", changed.isEmpty())
        firmin.respawnIfDead()
    }

    /** A welcome committee for Gaspard in the Nether. */
    private suspend fun plan() {
        val mark = run.marks()
        run.step("Opaline asks for a welcome committee in the Nether") {
            opaline.command("narrator whisper Prépare en secret un comité d'accueil pour Gaspard à sa prochaine entrée dans le Nether, et fais-toi prévenir quand il arrive.")
        }
        val made = run.directorCalls(mark.toolCall).firstOrNull { it.tool == "create_rule" && it.ok }
        val ref = made?.let { REF_IN_RESULT.find(it.result)?.value }
        details += "the plan: ${made?.args?.take(600)}"
        verify("the plan was made", ref != null && made.text("kind") == "plan")
        if (ref == null) return
        val room = run.netherRoom()
        val entered = run.marks()
        run.step("Gaspard enters the Nether") { gaspard.toNether(room) }
        val fired = run.decisions(ref).any { it.first == "fired" }
        val committee = run.onServer {
            run.server.getLevel(Level.NETHER)!!.getEntitiesOfClass(Mob::class.java, AABB(room).inflate(12.0)) { it.entityTags().contains("narrator2") }.map { it.type.toShortString() }
        }
        details += "the plan fired: $fired; the Narrator's creatures in the room: $committee; RULE then a line: ${run.spokeAfter(entered, "mechanic")}"
        verify("the plan fired at Gaspard's entry", fired)
        run.onServer {
            run.server.getLevel(Level.NETHER)!!.getEntitiesOfClass(Mob::class.java, AABB(room).inflate(12.0)) { it.entityTags().contains("narrator2") }.forEach { it.discard() }
            gaspard.goHome()
        }
        run.settle(60_000)
    }

    /** Lisette offers 10 diamonds. */
    private suspend fun offering() {
        run.onServer { lisette.player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.DIAMOND, 10)) }
        val mark = run.marks()
        run.step("Lisette offers 10 diamonds") { lisette.command("offer") }
        val calls = run.directorCalls(mark.toolCall)
        val favor = calls.firstOrNull { it.tool == "favor" && it.ok && it.text("offering") != null }
        details += "the offering: ${calls.filter { it.tool == "favor" || it.tool in SPEECH_TOOLS }.map { "${it.tool} ${it.status} `${it.args.take(300)}`" }}"
        verify("the offering was taken against a favor, or refused aloud", favor != null || calls.any { it.ok && it.tool in SPEECH_TOOLS })
    }

    /** A duel between Gaspard and Firmin, asked for by Opaline; Firmin's fatal blow. */
    private suspend fun duel() {
        val mark = run.marks()
        run.step("Opaline asks for a duel between Gaspard and Firmin", settleMs = 300_000) {
            opaline.command("narrator whisper Gaspard et Firmin se chamaillent depuis le début de la soirée : propose-leur un duel.")
        }
        val held = playDuel(run, firmin, gaspard, mark, details)
        verify("the duel was proposed, accepted, stopped at 1 health, won and told", held)
    }

    /** Opaline asks for a glass cage around Firmin: set in the air, taken away alone. */
    private suspend fun cage() {
        delay(15_000) // past the duelists' protection
        val mark = run.marks()
        var most = 0
        run.step("Opaline asks for a glass cage around Firmin", settleMs = 60_000) {
            opaline.command("narrator whisper Firmin fanfaronne depuis sa victoire : enferme-le quelques secondes dans une cage de verre.")
        }
        val punish = run.directorCalls(mark.toolCall).firstOrNull { it.tool == "punish" && it.ok && it.text("punishment") == "cage" }
        details += "the cage: ${run.directorCalls(mark.toolCall).filter { it.tool == "punish" }.map { "${it.status} `${it.args}` → ${it.result.take(200)}" }}"
        verify("punish cage on Firmin", punish != null)
        if (punish == null) return
        val end = System.currentTimeMillis() + 90_000
        var left = 0
        while (System.currentTimeMillis() < end) {
            left = run.onServer { trapBlocks(firmin.player) }
            most = maxOf(most, left)
            if (most > 0 && left == 0) break
            delay(1_000)
        }
        details += "trap blocks around Firmin: $most at most, $left at the end"
        verify("the cage was taken away by itself", left == 0)
    }

    private fun trapBlocks(player: ServerPlayer): Int =
        BlockPos.betweenClosed(player.blockPosition().offset(-3, -2, -3), player.blockPosition().offset(3, 4, 3))
            .count { player.level().getBlockState(it).let { state -> state.`is`(Blocks.GLASS) || state.`is`(Blocks.COBWEB) } }
}

// ── The players ─────────────────────────────────────────────────────────────────────────────────────

/**
 * A player of the run, with a fixed name (the model addresses players by name): joins as a real one does, in survival,
 * at [home]; talks in the public chat, types commands through their connection (so the Observer sees them and `/offer`,
 * `/duel` know them as their own sender), mines, travels. Game-side methods run on the server thread.
 */
private class LivePlayer(private val helper: GameTestHelper, val name: String, private val home: BlockPos, operator: Boolean) : AutoCloseable {
    private val server = helper.level.server
    private val profile = GameProfile(UUID.nameUUIDFromBytes("narrator2-live:$name".toByteArray()), name)
    private val connection = LiveConnection()

    /** The chat lines this player read (not the lines above the hotbar): the answers of their commands too. */
    val read: List<String> get() = connection.lines.toList()

    init {
        val cookie = CommonListenerCookie.createInitial(profile, false)
        val player = ServerPlayer(server, helper.level, profile, cookie.clientInformation())
        EmbeddedChannel(connection)
        // The GameTest server's default operator level is that of every player: game master, like a real operator.
        if (operator) server.playerList.op(NameAndId(profile), Optional.of(LevelBasedPermissionSet.GAMEMASTER), Optional.of(false))
        server.playerList.placeNewPlayer(connection, player, cookie)
        player.setGameMode(GameType.SURVIVAL)
        player.teleportTo(home.x + 0.5, home.y.toDouble(), home.z + 0.5)
        player.connection.handleAcceptPlayerLoad(ServerboundPlayerLoadedPacket())
    }

    val player: ServerPlayer get() = checkNotNull(server.playerList.getPlayer(profile.id)) { "$name is not online" }

    suspend fun say(text: String) = server.onServerThread {
        server.playerList.broadcastChatMessage(PlayerChatMessage.unsigned(player.uuid, text), player, ChatType.bind(ChatType.CHAT, player))
    }

    suspend fun command(line: String) = server.onServerThread { player.connection.handleChatCommand(ServerboundChatCommandPacket(line)) }

    /** Breaks [blocks] set in front of them, one every second, with an iron pickaxe. */
    suspend fun mine(blocks: List<Block>) {
        for ((i, block) in blocks.withIndex()) {
            val pos = BlockPos(home.x + 1 + i % 3, home.y, home.z + 1)
            server.onServerThread { helper.level.setBlockAndUpdate(pos, block.defaultBlockState()) }
            mineAt(pos)
            delay(1_000)
        }
    }

    /** Mines some stone and a diamond ore, and picks the diamond up (a mock player picks nothing up by itself). */
    suspend fun findADiamond() {
        mine(listOf(Blocks.STONE, Blocks.DIAMOND_ORE))
        server.onServerThread { player.inventory.add(ItemStack(Items.DIAMOND)) }
    }

    /** Breaks the block at [pos] (absolute) with an iron pickaxe. */
    suspend fun mineAt(pos: BlockPos) = server.onServerThread {
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.IRON_PICKAXE))
        player.gameMode.destroyBlock(pos)
    }

    /** Back at home, in the overworld. Server thread. */
    fun goHome() {
        player.teleport(TeleportTransition(helper.level, Vec3(home.x + 0.5, home.y.toDouble(), home.z + 0.5), Vec3.ZERO, 0f, 0f, TeleportTransition.DO_NOTHING))
    }

    /** Into the Nether, on the floor of [room]. */
    suspend fun toNether(room: BlockPos) = server.onServerThread {
        val nether: ServerLevel = server.getLevel(Level.NETHER)!!
        player.teleport(TeleportTransition(nether, Vec3.atBottomCenterOf(room), Vec3.ZERO, 0f, 0f, TeleportTransition.DO_NOTHING))
    }

    /** Respawns them if they died (a mock player has no client to click "respawn"). */
    suspend fun respawnIfDead() {
        val dead = server.onServerThread { player.isDeadOrDying }
        if (!dead) return
        delay(3_000)
        server.onServerThread {
            val back = server.playerList.respawn(player, false, Entity.RemovalReason.KILLED)
            back.connection.handleAcceptPlayerLoad(ServerboundPlayerLoadedPacket())
            goHome()
        }
    }

    override fun close() {
        server.playerList.getPlayer(profile.id)?.let { server.playerList.remove(it) }
    }
}

/** A connection that keeps the text of the chat lines it sends, and sends everything on as usual. */
private class LiveConnection : Connection(PacketFlow.SERVERBOUND) {
    val lines = CopyOnWriteArrayList<String>()

    override fun send(packet: Packet<*>, listener: ChannelFutureListener?, flush: Boolean) {
        if (packet is ClientboundSystemChatPacket && !packet.overlay()) lines += packet.content().string
        super.send(packet, listener, flush)
    }
}

/** The mod's log lines at WARN and above (loggers `narrator2.*`), while open. */
private class LiveWarnings : AutoCloseable {
    private val lines = CopyOnWriteArrayList<Pair<String, String>>()
    private val context = (LogManager.getLogger("narrator2") as Logger).context
    private val appender = object : AbstractAppender("narrator2-live-mechanics-warnings", null, null, true, Property.EMPTY_ARRAY) {
        override fun append(event: LogEvent) {
            if (!event.loggerName.startsWith("narrator2.") || !event.level.isMoreSpecificThan(org.apache.logging.log4j.Level.WARN)) return
            lines += event.level.name() to (event.message.formattedMessage + (event.thrown?.let { " · ${it.javaClass.simpleName}: ${it.message}" } ?: ""))
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
