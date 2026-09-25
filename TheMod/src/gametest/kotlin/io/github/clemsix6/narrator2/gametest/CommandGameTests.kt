package io.github.clemsix6.narrator2.gametest

import com.mojang.authlib.GameProfile
import io.github.clemsix6.narrator2.command.TraceCommands
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.director.DirectorState
import io.github.clemsix6.narrator2.event.ChatChannel
import io.github.clemsix6.narrator2.event.ChatMessage
import io.github.clemsix6.narrator2.event.Envelope
import io.github.clemsix6.narrator2.event.GameEvent
import io.github.clemsix6.narrator2.event.NarratorChannel
import io.github.clemsix6.narrator2.event.NarratorSpoke
import io.github.clemsix6.narrator2.journal.IdKind
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.observer.ref
import io.github.clemsix6.narrator2.tool.ToolEffect
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.trace.ConversationRecord
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import io.github.clemsix6.narrator2.trace.LlmCallRecord
import io.github.clemsix6.narrator2.trace.ToolCallRecord
import io.github.clemsix6.narrator2.trace.TurnRecord
import io.netty.channel.ChannelFutureListener
import io.netty.channel.embedded.EmbeddedChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.commands.CommandSourceStack
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.Connection
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.server.level.ClientInformation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.server.permissions.LevelBasedPermissionSet
import java.sql.Connection as SqlConnection
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * In-game tests of the administration commands and `/pray` (M1_M2 §17.3), run through the server's
 * real command dispatcher by players with unique names ([CommandPlayer]), so that the Journal rows of each
 * test are told apart by the operator's or the player's name.
 *
 * Owned by T21. Shared helpers: `GameTestSupport.kt`.
 */
class CommandGameTests {

    /**
     * Without an LLMGW key, the Director is disabled: pause, resume and whispers are refused, and nothing
     * is posted to it (a pause would outlast the missing key, a resume would wake it the moment a key
     * arrives). Only game masters reach `/narrator`. With a key in force (`NarratorGameTests` reloads one
     * for its own fake gateway), this test does nothing: a resume would wake the Director against that
     * gateway. The posting itself is covered by `DirectorCommandsTest`.
     */
    @GameTest
    fun pauseResumeAndWhisperNeedADirectorThatCanHear(helper: GameTestHelper) {
        val runtime = helper.narrator()
        if (runtime.director.status().state != DirectorState.DISABLED) {
            helper.succeed()
            return
        }
        CommandPlayer(helper).use { operator ->
            operator.run("narrator pause", op = true)
            operator.run("narrator resume", op = true)
            operator.run("narrator whisper Be nicer to Bartok.", op = true)
            helper.assertEquals(
                listOf(
                    "[Narrator] the Director is disabled (no LLMGW key): nothing to pause.",
                    "[Narrator] the Director is disabled (no LLMGW key): nothing to resume.",
                    "[Narrator] the Director is disabled (no LLMGW key): nobody would read this instruction.",
                ),
                operator.output.lines.toList(),
                "replies",
            )
            operator.output.lines.clear()
            operator.run("narrator chaos", op = true)
            helper.assertEquals("[Narrator] chaos ${runtime.settings.get().config.chaos} of 5", operator.output.lines.firstOrNull(), "chaos")

            operator.output.lines.clear()
            operator.run("narrator pause", op = false)
            helper.assertTrue(operator.output.lines.none { it.startsWith("[Narrator]") }, "a player who is not a game master: ${operator.output.lines}")
        }
        helper.succeed()
    }

    /** `/narrator say`: everyone reads the Narrator's line; it is journaled as said through this operator. */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun sayIsJournaledAsTheOperatorsLine(helper: GameTestHelper) {
        val runtime = helper.narrator()
        val operator = CommandPlayer(helper)
        val marker = "Silence, mortels. ${UUID.randomUUID().toString().take(8)}"
        val heard = HeardEvents(helper) { it is NarratorSpoke && marker in it.text }
        try {
            operator.run("narrator say §6$marker", op = true)
            val event = heard.envelopes.single().event as NarratorSpoke
            helper.assertEquals(NarratorChannel.OPERATOR, event.channel, "channel")
            helper.assertEquals(operator.name, event.operator, "operator")
            helper.assertEquals(marker, event.text, "the cleaned text")
            helper.assertEquals(null, event.recipients, "everyone")
            helper.assertEquals(null, event.turnId, "no turn")
            helper.assertTrue(operator.chat().any { it == "[Narrateur] $marker" }, "the line as players read it: ${operator.chat()}")
            helper.assertTrue(operator.output.lines.any { it.startsWith("Said as the Narrator to ") }, "reply: ${operator.output.lines}")
        } finally {
            heard.close()
            operator.close()
        }
        val rows = readLater(helper) { connection ->
            listOf(
                rows(connection, "SELECT channel, player, text, turn_id, operator FROM chat WHERE operator = ?", operator.name),
                rows(connection, "SELECT kind, decision, subject FROM engine_decisions WHERE kind = 'operator' AND subject = ?", operator.name),
            )
        }
        helper.awaitAsync {
            helper.assertEquals(
                listOf(listOf(listOf("operator", null, marker, null, operator.name)), listOf(listOf("operator", "said", operator.name))),
                rows.get(),
                "the chat row and the operator decision",
            )
        }
    }

    /** `/pray`: a prayer event nobody else sees, `pray.sent` to its author, then one prayer every 10 s. */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun prayPublishesAPrivatePrayerAndLimitsItsRate(helper: GameTestHelper) {
        val runtime = helper.narrator()
        val ui = runtime.settings.get().ui
        val devout = CommandPlayer(helper)
        val bystander = CommandPlayer(helper)
        val heard = HeardEvents(helper) { it is ChatMessage && it.player.uuid == devout.uuid }
        val prayer = "Ô Narrateur, épargne mes moutons ${UUID.randomUUID().toString().take(6)}"
        val id = try {
            devout.run("pray $prayer")
            val envelope = heard.envelopes.single()
            val event = envelope.event as ChatMessage
            helper.assertEquals(ChatChannel.PRAY, event.channel, "channel")
            helper.assertEquals(prayer, event.text, "text")
            helper.assertEquals(emptyList<String>(), event.recipients, "recipients")
            helper.assertEquals(listOf(ui["pray.sent"]), devout.output.lines.toList(), "what the author reads")
            helper.assertTrue(bystander.chat().none { prayer in it }, "nobody else reads it: ${bystander.chat()}")
            helper.assertTrue(devout.chat().none { prayer in it }, "not even as chat")

            devout.output.lines.clear()
            devout.run("pray encore une fois")
            helper.assertEquals(1, heard.envelopes.size, "a second prayer within 10 s is not published")
            val cooldown = ui["pray.cooldown"].substringBefore("{seconds}")
            helper.assertTrue(devout.output.lines.singleOrNull()?.startsWith(cooldown) == true, "cooldown reply: ${devout.output.lines}")
            envelope.id
        } finally {
            heard.close()
            devout.close()
            bystander.close()
        }
        val row = readLater(helper) { connection -> rows(connection, "SELECT channel, player, text, recipients FROM chat WHERE id = ?", id) }
        helper.awaitAsync {
            helper.assertEquals(listOf(listOf("pray", devout.name, prayer, null)), row.get(), "the chat row of the prayer")
        }
    }

    /** `/narrator protect` protects and unprotects a player, with an operator decision; `/narrator guards` shows it. */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun protectOnAndOffAndTheGuardsShowIt(helper: GameTestHelper) {
        val runtime = helper.narrator()
        val operator = CommandPlayer(helper)
        val target = CommandPlayer(helper)
        try {
            operator.run("narrator protect ${target.name}", op = true)
            helper.assertTrue(runtime.guards.isProtected(target.uuid), "protected")
            helper.assertTrue(operator.output.lines.any { it.startsWith("[Narrator] ${target.name} is protected") }, "reply: ${operator.output.lines}")

            operator.output.lines.clear()
            operator.run("narrator guards ${target.name}", op = true)
            val guards = operator.output.lines.toList()
            helper.assertEquals("[Narrator] guards on ${target.name} · chaos ${runtime.settings.get().config.chaos} of 5", guards.firstOrNull(), "title")
            helper.assertTrue("Protected: yes, the Narrator does nothing hostile to them" in guards, "guards: $guards")
            helper.assertTrue("Nothing of the Narrator's on them." in guards, "guards: $guards")
            helper.assertTrue(guards.any { it.startsWith("Deaths caused by the Narrator in the last played hour: 0 of ") }, "guards: $guards")
            helper.assertTrue("Death guard: not armed" in guards, "guards: $guards")

            operator.output.lines.clear()
            operator.run("narrator guards", op = true)
            helper.assertTrue(operator.output.lines.any { it.startsWith("${target.name}: protected · nothing of the Narrator's · ") }, "overview: ${operator.output.lines}")

            operator.run("narrator protect ${target.name} off", op = true)
            helper.assertFalse(runtime.guards.isProtected(target.uuid), "no longer protected")
        } finally {
            operator.close()
            target.close()
        }
        val rows = readLater(helper) { connection ->
            rows(connection, "SELECT decision, subject FROM engine_decisions WHERE kind = 'operator' AND subject = ? ORDER BY id", target.name)
        }
        helper.awaitAsync {
            helper.assertEquals(listOf(listOf("protected", target.name), listOf("unprotected", target.name)), rows.get(), "operator decisions")
        }
    }

    /**
     * `trace`, `why` and `status` on a turn prepared in the server's Journal: a player's message wakes
     * turn N, whose request thinks and says a line to the player.
     */
    @GameTest(maxTicks = ASYNC_MAX_TICKS)
    fun traceAndWhyReadAPreparedTurn(helper: GameTestHelper) {
        val runtime = helper.narrator()
        val operator = CommandPlayer(helper)
        val speaker = CommandPlayer(helper)
        val trace = runtime.trace
        val clock = runtime.clock
        val conversation = "director-t21-${UUID.randomUUID().toString().take(8)}"
        val thought = "Thinking of ${speaker.name}: a greeting deserves a sneer."
        val turn = trace.nextId(IdKind.TURN)
        try {
            val message = runtime.bus.publish(ChatMessage(speaker.player.ref(), ChatChannel.PUBLIC, "bonjour tout le monde", emptyList()))!!.id
            trace.conversation(ConversationRecord(conversation, Role.DIRECTOR, "Test chapter", null, null, clock.instant(), null, null, null, "logs/$conversation.md"))
            val record = TurnRecord(turn, conversation, clock.instant(), null, TurnRecord.Trigger.CHAT, listOf(message), emptyList(), null, null, null)
            trace.turn(record)
            Decisions(trace, clock).record(
                Log.director, DecisionRecord.Kind.WAKE, "woke", "turn $turn", "chat ${speaker.name}",
                eventId = message, conversationId = conversation, turnId = turn,
            )
            val call = trace.nextId(IdKind.LLM_CALL)
            trace.llmCall(
                LlmCallRecord(
                    id = call, provider = LlmCallRecord.Provider.ANTHROPIC, purpose = LlmCallRecord.Purpose.TURN, conversationId = conversation,
                    turnId = turn, role = Role.DIRECTOR.id, model = "claude-opus-5-5", effort = "medium", startedAt = clock.instant(), endedAt = clock.instant(),
                    latencyMs = 4_200, ttftMs = 4_000, attempts = 1, requestId = null, messageId = null, inputTokens = 900, cacheReadTokens = 40_000,
                    cacheWriteTokens = 0, outputTokens = 120, stopReason = "tool_use", refusalCategory = null, error = null, httpStatus = null, thinking = thought,
                ),
            )
            trace.toolCall(
                ToolCallRecord(
                    trace.nextId(IdKind.TOOL_CALL), clock.instant(), conversation, turn, call, "toolu_t21", Role.DIRECTOR, "say",
                    """{"text":"Bonjour, mortel.","to":["${speaker.name}"]}""", ToolStatus.OK, "ok: whispered to ${speaker.name}", 30, listOf(speaker.name), ToolEffect.SPEECH,
                ),
            )
            trace.turn(record.copy(endedAt = clock.instant().plusMillis(4_500), llmCalls = 1, toolCalls = 1, outcome = TurnRecord.Outcome.ACTED))

            operator.run("narrator trace turn $turn", op = true)
            operator.run("narrator why turn $turn", op = true)
            operator.run("narrator why ${speaker.name}", op = true)
            operator.run("narrator trace ${TraceCommands.MAX_TURNS}", op = true)
            operator.run("narrator status", op = true)
            operator.run("narrator usage", op = true)
        } finally {
            speaker.close()
        }
        val lines = operator.output.lines
        helper.startSequence()
            .thenAwait {
                helper.assertTrue(lines.any { it.startsWith("[Narrator] turn #$turn · ") }, "trace turn: $lines")
                helper.assertTrue(lines.any { it.startsWith("[Narrator] why turn #$turn · ") }, "why turn: $lines")
                helper.assertTrue(lines.any { it.startsWith("[Narrator] why: ${speaker.name}'s last message") }, "why player: $lines")
                helper.assertTrue(lines.any { it.startsWith("#$turn ") && "chat ${speaker.name} → acted · 1 call · say" in it }, "trace: $lines")
                helper.assertTrue(lines.any { it.startsWith("Usage (24 h): Claude ") }, "status usage: $lines")
                helper.assertTrue(lines.any { it == "[Narrator] usage over the last 24 h" }, "usage: $lines")
            }
            .thenExecute {
                helper.assertTrue(lines.any { it.contains("CHAT ${speaker.name}: \"bonjour tout le monde\"") }, "the message that woke it: $lines")
                helper.assertTrue(lines.count { it == "  Thought: $thought" } == 1, "the thinking in trace turn: $lines")
                helper.assertTrue(lines.count { it == "Thought: $thought" } == 2, "the thinking in both whys: $lines")
                helper.assertTrue(lines.count { it == "Did: say → ${speaker.name} (ok): ok: whispered to ${speaker.name}" } == 2, "the action in both whys: $lines")
                helper.assertTrue(lines.any { it.startsWith("Told in turn #$turn at wake-up") }, "why player: $lines")
                helper.assertTrue(lines.any { it == "Transcript: logs/$conversation.md" }, "transcript: $lines")
                helper.assertTrue(lines.any { it.startsWith("Director: ") } && lines.any { it.startsWith("Journal: available · ") }, "status: $lines")
                helper.assertTrue(lines.any { it.startsWith("Guards: chaos ") } && lines.any { it.startsWith("Reflexes: ") }, "status: $lines")
                operator.close()
            }
            .thenSucceed()
    }

    private fun rows(connection: SqlConnection, sql: String, vararg params: Any): List<List<Any?>> =
        connection.prepareStatement(sql).use { statement ->
            params.forEachIndexed { i, value -> statement.setObject(i + 1, value) }
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add((1..rows.metaData.columnCount).map { column -> rows.getObject(column)?.let { if (it is Number) it.toLong() else it.toString() } })
                }
            }
        }

    /** Reads the Journal once what came before is written, off the server thread; the result, or the error as text. */
    private fun <T> readLater(helper: GameTestHelper, query: (SqlConnection) -> T): AtomicReference<Any?> {
        val journal = helper.narrator().journal
        val result = AtomicReference<Any?>()
        CoroutineScope(Dispatchers.IO).launch {
            result.set(
                try {
                    journal.barrier()
                    journal.read(5_000, query)
                } catch (e: Exception) {
                    "read failed: $e"
                },
            )
        }
        return result
    }
}

/**
 * A player with a unique name (mock players are all `test-mock-player`, and commands name players), who
 * joins for one test, runs commands with their own source (or as a game master), and keeps what the
 * commands answer ([output]) and the chat lines the server sends them ([chat]).
 */
private class CommandPlayer(private val helper: GameTestHelper) : AutoCloseable {
    val name = "cm_" + UUID.randomUUID().toString().replace("-", "").take(10)
    val output = CommandOutput()
    private val connection = CommandPlayerConnection()
    val player: ServerPlayer

    init {
        val server = helper.level.server
        val profile = GameProfile(UUID.randomUUID(), name)
        player = ServerPlayer(server, helper.level, profile, ClientInformation.createDefault())
        EmbeddedChannel(connection)
        server.playerList.placeNewPlayer(connection, player, CommonListenerCookie.createInitial(profile, false))
        connection.lines.clear()
    }

    val uuid: UUID get() = player.uuid

    /** Runs [command] as this player; as a game master when [op]. */
    fun run(command: String, op: Boolean = false) = helper.level.server.commands.performPrefixedCommand(source(op), command)

    private fun source(op: Boolean): CommandSourceStack {
        val source = player.createCommandSourceStack().withSource(output)
        return if (op) source.withPermission(LevelBasedPermissionSet.GAMEMASTER) else source
    }

    /** The chat lines this player received (not the lines above the hotbar). */
    fun chat(): List<String> = connection.lines.toList()

    override fun close() {
        val players = helper.level.server.playerList
        players.getPlayer(uuid)?.let { players.remove(it) }
    }
}

/** A connection that keeps the text of the chat lines it sends, and sends everything on as usual. */
private class CommandPlayerConnection : Connection(PacketFlow.SERVERBOUND) {
    val lines = CopyOnWriteArrayList<String>()

    override fun send(packet: Packet<*>, listener: ChannelFutureListener?, flush: Boolean) {
        if (packet is ClientboundSystemChatPacket && !packet.overlay()) lines += packet.content().string
        super.send(packet, listener, flush)
    }
}

/** The events that [accept] picks, published from now until [close]. */
private class HeardEvents(helper: GameTestHelper, accept: (GameEvent) -> Boolean) : AutoCloseable {
    private val bus = helper.narrator().bus
    private val owner = "command-test-${UUID.randomUUID()}"
    val envelopes = CopyOnWriteArrayList<Envelope>()

    init {
        bus.subscribeSync("heard", owner, accept) { envelope -> envelopes += envelope }
    }

    override fun close() = bus.unsubscribeOwner(owner)
}
