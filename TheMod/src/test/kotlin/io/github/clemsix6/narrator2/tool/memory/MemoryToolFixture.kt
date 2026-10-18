package io.github.clemsix6.narrator2.tool.memory

import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.event.Pos
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.memory.DuckMemoryStore
import io.github.clemsix6.narrator2.memory.Memory
import io.github.clemsix6.narrator2.memory.MemoryStore
import io.github.clemsix6.narrator2.memory.PlayerSheets
import io.github.clemsix6.narrator2.memory.Recall
import io.github.clemsix6.narrator2.memory.RunContext
import io.github.clemsix6.narrator2.memory.RunKind
import io.github.clemsix6.narrator2.memory.StoreFixture
import io.github.clemsix6.narrator2.memory.search.HybridRecall
import io.github.clemsix6.narrator2.memory.search.SearchIndex
import io.github.clemsix6.narrator2.memory.stringArray
import io.github.clemsix6.narrator2.observer.LivePlayer
import io.github.clemsix6.narrator2.observer.PlayerStates
import io.github.clemsix6.narrator2.testing.FakeMemory
import io.github.clemsix6.narrator2.testing.TestServices
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.ToolArgs
import io.github.clemsix6.narrator2.tool.ToolContext
import io.github.clemsix6.narrator2.tool.ToolResult
import kotlinx.coroutines.runBlocking
import java.time.Instant

/**
 * The memory tools on the real store, index and recall (DuckDB in memory, [StoreFixture]: Bartok and
 * Mimosa, evening of 2026-10-01 at 19:40 UTC), with fake sheets ([sheets]) until M3-07: what the tools
 * write can be read back from the tables. Memory ids start at 1 000. Tools run as [director] unless told.
 */
internal class MemoryToolFixture : AutoCloseable {
    val base = StoreFixture()
    val clock = base.clock
    val bartok: PlayerRef = base.bartok
    val mimosa: PlayerRef = base.mimosa
    private val index = SearchIndex()
    val store = DuckMemoryStore(base.journal, index, base.playClock, clock)
    val fake = FakeMemory(clock)

    /** The sheets the tools read: [FakeMemory.FakeSheets] by default. */
    var sheets: PlayerSheets = fake.sheets

    val memory: Memory = object : Memory by fake {
        override val store: MemoryStore get() = this@MemoryToolFixture.store
        override val recall: Recall = HybridRecall(base.journal, index, base.playClock, base.clock)
        override val sheets: PlayerSheets get() = this@MemoryToolFixture.sheets
    }
    val players = PlayerStates()
    val services = TestServices(clock = clock, journal = base.journal, players = players, memory = memory)

    val director = Caller(Role.DIRECTOR, "director-c13", turnId = 42, taskId = null)
    val analyst = Caller(Role.ANALYST, "analyst-1a2b3c4d", turnId = null, taskId = 3)

    /** A Chronicler conversation of the batch [runId] over [from]..[to] (its `memory_runs` row and its run context). */
    fun chronicler(runId: Long, from: Instant, to: Instant): Caller {
        val author = base.run(runId, from, to)
        fake.runs[author.conversationId!!] = RunContext(runId, RunKind.BATCH, from, to, "director-c13")
        return Caller(Role.CHRONICLER, author.conversationId!!, turnId = null, taskId = null)
    }

    /** A Dreamer conversation of the dream [runId]. */
    fun dreamer(runId: Long): Caller {
        val conversation = "dreamer-$runId"
        fake.runs[conversation] = RunContext(runId, RunKind.DREAM, null, null, null)
        return Caller(Role.DREAMER, conversation, turnId = null, taskId = null)
    }

    /** [player] online at [pos]. */
    fun online(player: PlayerRef, pos: Pos = Pos("minecraft:overworld", 120, 90, -400)) = players.put(
        LivePlayer(
            ref = player, sessionId = 1, joinedAt = clock.millis() - 3_600_000, isOp = false, gameMode = "survival", pos = pos,
            biome = "minecraft:plains", structure = null, underground = false, health = 20f, maxHealth = 20f, food = 20,
            vehicle = null, lastDeathAt = null, lastMoveAt = clock.millis(), idle = false,
        ),
    )

    /** A `tool_calls` row of the Director's conversation, as the tool runner writes it. */
    fun toolCall(id: Long, tool: String, targets: List<String>, turn: Long = 42, status: String = "ok", effect: String = "world") = base.sql(
        "INSERT INTO tool_calls (id, ts, conversation_id, turn_id, tool_use_id, role, tool, args, status, duration_ms, targets, effect) " +
            "VALUES (?, ?, 'director-c13', ?, 'tu', 'director', ?, '{}'::JSON, ?, 5, ?, ?)",
        id, clock.instant(), turn, tool, status, stringArray(targets), effect,
    )

    /** Decodes [input] as the model wrote it and runs [tool] for [caller], as the tool runner does. */
    fun <A : Any> call(tool: NarratorTool<A>, input: String, caller: Caller = director): ToolResult = runBlocking {
        when (val decoded = ToolArgs.decode(tool.args, input)) {
            is ToolArgs.Decoded.Invalid -> ToolResult.invalid(decoded.problem)
            is ToolArgs.Decoded.Ok -> tool.run(ToolContext(services, caller, clock, tool.timeoutMs), decoded.args)
        }
    }

    fun <T> rows(sql: String, vararg parameters: Any?, row: java.sql.ResultSet.() -> T): List<T> = base.rows(sql, *parameters, row = row)

    override fun close() = base.close()
}
