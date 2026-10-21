package io.github.clemsix6.narrator2.gametest

import io.github.clemsix6.narrator2.NarratorRuntime
import io.github.clemsix6.narrator2.core.onServerThread
import io.github.clemsix6.narrator2.director.ReminderState
import io.github.clemsix6.narrator2.director.SavedReminder
import io.github.clemsix6.narrator2.forge.ForgeService
import io.github.clemsix6.narrator2.gametest.GuardPolicyGameTests.Companion.floor
import io.github.clemsix6.narrator2.journal.DisabledJournal
import io.github.clemsix6.narrator2.journal.DuckDatabase
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.mechanics.ChallengeOutcome
import io.github.clemsix6.narrator2.mechanics.ChallengeStatus
import io.github.clemsix6.narrator2.mechanics.StateStore
import io.github.clemsix6.narrator2.mechanics.module.ModuleEngine
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.CanonStatus
import io.github.clemsix6.narrator2.memory.Ref
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.runtime.Phase
import io.github.clemsix6.narrator2.runtime.RestartReport
import io.github.clemsix6.narrator2.tool.Caller
import io.github.clemsix6.narrator2.tool.NarratorTool
import io.github.clemsix6.narrator2.tool.ToolRunner
import io.github.clemsix6.narrator2.tool.ToolStatus
import io.github.clemsix6.narrator2.tool.mechanics.CreateRuleTool
import io.github.clemsix6.narrator2.tool.mechanics.DeployModuleTool
import io.github.clemsix6.narrator2.tool.mechanics.StartChallengeTool
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import net.fabricmc.fabric.api.gametest.v1.GameTest
import io.netty.channel.ChannelFutureListener
import net.fabricmc.fabric.api.networking.v1.LoginPacketSender
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.Connection
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerLoginPacketListenerImpl
import java.io.BufferedReader
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * In-game test of `/narrator restart` (task M6-13, M6 §9, §15.2): the server's own Narrator rebuilt by the real
 * command, on the GameTest server.
 *
 * - With a player online, the restart is refused and nothing stops.
 * - With the server empty, it rebuilds the Narrator: a login meanwhile waits (the hook of `QUERY_START`); the
 *   challenge, the law and the module deployed before are there again (the module `restarted`), as are the memory, the
 *   mechanics and the Director's reminder saved in the world; the decision `operator restarted` is in the new Journal.
 * - With the Journal's file held by a DuckDB client in another process at that moment, the new Narrator runs with a
 *   `DisabledJournal`, memory and mechanics off; once the file is free, a second `/narrator restart` gives them back.
 *
 * It replaces the server's Narrator three times: it runs alone, in a batch of its own ([RESTART_ENVIRONMENT]), and puts
 * back in its `finally` what it changed (the pace of the challenges, the world's reminders, what it deployed, the
 * configuration file, which it holds to the in-game runs' own while it restarts: no dashboard listens). The
 * batches after it run on the last Narrator it made.
 */
class RestartGameTests {

    @GameTest(environment = RESTART_ENVIRONMENT, maxTicks = MAX_TICKS)
    fun aRestartIsRefusedWithAPlayerOnlineThenRebuildsTheNarratorOfTheEmptyServer(helper: GameTestHelper) = helper.runScript(MAX_TICKS) {
        val server = helper.level.server
        val first = helper.narrator()
        check(first.journal.available && first.memory.available && first.mechanics.available) { "the Narrator runs whole: ${first.summary()}" }
        (first.forge as ForgeService).start().join() // the built-in modules, built as the server started
        val (pace, reminders) = server.onServerThread { StateStore(server).get().lastChallengeAt to server.globalAttachments().getAttached(ReminderState.TYPE) }
        val bob = server.onServerThread {
            floor(helper)
            TestPlayer(helper, "rs_").also { it.standAt(BlockPos(3, 1, 3)) }
        }
        var bobOnline = true
        val made = mutableListOf<Ref>()
        // A restart reads the configuration again: the in-game runs' own (no dashboard, M6 §8.2), whatever a test before
        // left in the file, or the dashboard would listen on its port during the tests.
        val config = Files.readString(NarratorRuntime.configFile)
        Files.writeString(NarratorRuntime.configFile, GAMETEST_CONFIG)
        try {
            // What a restart must not lose: a challenge, a law, a module, a reminder.
            first.journal.barrier() // Bob's join row, which the law's players are checked against
            call(
                first, StartChallengeTool(),
                """{"title": "Un œuf", "statement": "Le Narrateur a défié un joueur de tenir un œuf de tortue.", "goal": "obtain",
                   "item": "turtle_egg", "players": ["${bob.name}"], "minutes": 10}""",
            )
            val challenge = first.mechanics.challenges.list().single { view -> view.participants.any { it.name == bob.name } }.ref.also { made += it }
            call(
                first, CreateRuleTool(),
                """{"kind": "law", "statement": "$LAW", "trigger": "chat", "when": {"players": ["${bob.name}"], "words": ["redémarrage"]},
                   "effects": [{"tool": "say", "at": "actor", "args": {"text": "{actor}, la loi a parlé."}}], "minutes": 30}""",
            )
            val law = first.mechanics.rules.list().single { it.spec.statement == LAW }.ref.also { made += it }
            val running = first.mechanics.modules.list().map { it.ref }
            call(
                first, DeployModuleTool(),
                """{"module": "parade", "params": {"player": "${bob.name}", "count": 3}, "kind": "plan", "statement": "Le Narrateur a fait suivre ${bob.name} par une parade de poules.", "minutes": 5}""",
            )
            val module = first.mechanics.modules.list().single { it.ref !in running }.ref.also { made += it }
            // The reminder as a Director saves it in the world (the server's Director is disabled here: no key).
            val reminder = server.onServerThread {
                val saved = reminders ?: ReminderState.EMPTY
                val now = first.clock.millis()
                server.globalAttachments().setAttached(ReminderState.TYPE, ReminderState(saved.nextId + 1, saved.reminders + SavedReminder(saved.nextId, NOTE, now, now + WEEK_MS)))
                saved.nextId
            }

            // A player online: refused, nothing stops.
            val refused = server.onServerThread { command(helper, "narrator restart") }.awaitLines(1)
            check(refused == listOf("[Narrator] cannot restart: 1 player online (${bob.name}); restart when the server is empty, or restart the server.")) { "refused: $refused" }
            check(NarratorRuntime.current === first && first.phase == Phase.RUNNING && !NarratorRuntime.restarts.running) { "nothing stopped: ${first.phase}" }
            check(first.mechanics.modules.list().any { it.ref == module }) { "the module still runs" }

            // The server empty: the restart, a login waiting meanwhile.
            server.onServerThread { bob.close() }
            bobOnline = false
            awaitCondition("the server empty") { server.onServerThread { server.playerList.players.isEmpty() } }
            val logins = Logins()
            val restart = server.onServerThread {
                command(helper, "narrator restart").also {
                    val login = ServerLoginPacketListenerImpl(server, Connection(PacketFlow.SERVERBOUND), false)
                    ServerLoginConnectionEvents.QUERY_START.invoker().onLoginStart(login, server, NoPackets, logins)
                }
            }
            check(logins.waits.size == 1 && !logins.waits.single().isDone) { "a login waits while the Narrator is rebuilt: ${logins.waits}" }
            val restarted = restart.awaitLine(RESTARTED)
            check(restart.lines.first() == "[Narrator] restarting the Narrator; the logins wait until it is done…") { "the command's answer: ${restart.lines}" }
            check(restarted.endsWith(": $WHOLE")) { "restarted: $restarted" }
            check(logins.waits.single().isDone) { "the login goes on" }
            val second = NarratorRuntime.current
            check(second != null && second !== first && second.phase == Phase.RUNNING && first.phase == Phase.STOPPED) { "a new Narrator: $second, the old ${first.phase}" }
            check(!first.journal.available && second.journal.available) { "the old Journal closed, the new one open" }
            noDashboard(second)

            // Nothing lost.
            val view = second.mechanics.challenges.list().singleOrNull { it.ref == challenge }
            check(view?.status == ChallengeStatus.RUNNING) { "the challenge again: $view" }
            check(second.mechanics.rules.list().any { it.ref == law }) { "the law again: ${second.mechanics.rules.list()}" }
            check(second.mechanics.modules.list().any { it.ref == module }) { "the module again: ${second.mechanics.modules.list()}" }
            awaitCondition("$module restarted", 30_000) { decisions(second, ModuleEngine.RESTARTED, module.toString()) == 1L }
            for (ref in listOf(challenge, law, module)) check(second.memory.store.canon(ref)?.status == CanonStatus.ACTIVE) { "$ref in the canon of the new memory" }
            check(second.director.status().reminders.singleOrNull { it.id == reminder }?.note == NOTE) { "the reminder: ${second.director.status().reminders}" }
            check(decisions(second, "restarted", "Server", kind = "operator") == 1L) { "the decision operator restarted" }

            // Nothing left for what follows: ended through the new Narrator, the reminders as found.
            cleanUp(second, made)
            made.clear()
            server.onServerThread { putBack(server, reminders) }

            // The Journal held by another program when the new one opens: the Narrator runs without it.
            val held = JournalHolder(second.dataDir.resolve(DuckDatabase.FILE_NAME))
            try {
                val report = CompletableFuture<RestartReport>()
                val started = server.onServerThread {
                    NarratorRuntime.restart(server, "gametest", { report.complete(it) }) {
                        held.awaitHeld()
                        NarratorRuntime.prepare(server)
                    }
                }
                check(started) { "the second restart starts" }
                val blind = withTimeout(60_000) { report.await() }
                check(blind.ok && blind.summary?.startsWith("Journal unavailable · memory off (the Journal is unavailable: opening failed after 3 attempts") == true) {
                    "without its Journal: ${blind.text()}"
                }
                val third = NarratorRuntime.current!!
                check(third.journal is DisabledJournal && !third.memory.available && !third.mechanics.available) { "Journal, memory, mechanics off: ${third.summary()}" }
                noDashboard(third)
            } finally {
                held.close()
            }

            // The file free again: a second /narrator restart gives them back.
            val again = server.onServerThread { command(helper, "narrator restart") }.awaitLine(RESTARTED)
            check(again.endsWith(": $WHOLE")) { "restarted again: $again" }
            val fourth = NarratorRuntime.current!!
            check(fourth.journal.available && fourth.memory.available && fourth.mechanics.available) { "whole again: ${fourth.summary()}" }
            noDashboard(fourth)
            check(decisions(fourth, "restarted", "Server", kind = "operator") == 2L) { "both restarts by the command recorded" }
        } finally {
            Files.writeString(NarratorRuntime.configFile, config)
            NarratorRuntime.current?.let { cleanUp(it, made) }
            server.onServerThread {
                StateStore(server).update { it.copy(lastChallengeAt = pace) }
                putBack(server, reminders)
                if (bobOnline) bob.close()
            }
        }
    }

    /** The logins that reached `QUERY_START`: the futures they wait for. */
    private class Logins : ServerLoginNetworking.LoginSynchronizer {
        val waits = CopyOnWriteArrayList<Future<*>>()

        override fun waitFor(future: Future<*>) {
            waits += future
        }
    }

    /** The packets of a login of the test: none is sent, the test only looks at what the login is told to wait for. */
    private object NoPackets : LoginPacketSender {
        override fun createPacket(channelName: Identifier, buf: FriendlyByteBuf): Packet<*> = error("a login of the test sends nothing")

        override fun createPacket(payload: CustomPacketPayload): Packet<*> = error("a login of the test sends nothing")

        override fun sendPacket(packet: Packet<*>, callback: ChannelFutureListener?) = error("a login of the test sends nothing")

        override fun disconnect(disconnectReason: Component) = error("a login of the test is never disconnected")
    }

    /** What the server console gets from a command, as it comes (a restart answers once it is over). */
    private class Answer(val output: CommandOutput) {
        val lines: List<String> get() = output.lines.toList()

        suspend fun awaitLines(count: Int): List<String> {
            awaitCondition("$count lines") { output.lines.size >= count }
            return lines
        }

        suspend fun awaitLine(prefix: String): String {
            awaitCondition("a line starting with « $prefix »: ${output.lines}", 60_000) { output.lines.any { it.startsWith(prefix) } }
            return output.lines.first { it.startsWith(prefix) }
        }
    }

    /**
     * A DuckDB client in another JVM that holds the Journal's file once it is free, as the DuckDB command line would:
     * `JournalChild` of the unit tests, in its `hold` mode, on the class path of this server.
     */
    private class JournalHolder(file: Path) : AutoCloseable {
        private val dir: Path = Files.createTempDirectory("narrator2-restart-")
        private val process: Process
        private val output: BufferedReader
        private val seen = StringBuilder()

        init {
            val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
            process = ProcessBuilder(
                java, "--enable-native-access=ALL-UNNAMED", "-Djava.io.tmpdir=${System.getProperty("java.io.tmpdir")}",
                "-cp", classpathJar().toString(), HOLDER, "hold", file.toString(),
            ).directory(dir.toFile()).redirectErrorStream(true).start()
            output = process.inputReader()
        }

        /** Returns once the child holds the file; fails when it ends first. */
        fun awaitHeld() {
            while (true) {
                val line = output.readLine() ?: error("the Journal's holder ended before holding it:\n$seen")
                seen.appendLine(line)
                if (line.startsWith("READY")) return
            }
        }

        /** A jar that only lists this server's class path in its manifest: too long for a Windows command line otherwise. */
        private fun classpathJar(): Path {
            val jar = dir.resolve("classpath.jar")
            val entries = System.getProperty("java.class.path").split(File.pathSeparator).filter { it.isNotBlank() }
                .joinToString(" ") { Path.of(it).toUri().toASCIIString() }
            val manifest = Manifest().apply {
                mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
                mainAttributes[Attributes.Name.CLASS_PATH] = entries
            }
            JarOutputStream(Files.newOutputStream(jar), manifest).close()
            return jar
        }

        override fun close() {
            process.destroyForcibly()
            check(process.waitFor(30, TimeUnit.SECONDS)) { "the Journal's holder did not end" }
            dir.toFile().deleteRecursively()
        }
    }

    private companion object {
        /** The batch of this test alone (`test_environment/restart.json`): it replaces the server's Narrator. */
        const val RESTART_ENVIRONMENT = "narrator2-gametest:restart"

        /** At least two minutes (see [runScript]): three restarts, one of them with the Journal held 6 s. */
        const val MAX_TICKS = 120_000

        const val RESTARTED = "[Narrator] Narrator restarted in "
        /** The configuration of the in-game runs (`seedGameTestConfig` in `build.gradle.kts`): no dashboard. */
        const val GAMETEST_CONFIG = """{ "dashboard": { "enabled": false } }"""

        const val WHOLE = "Journal available · memory on · mechanics on · forge on"
        const val LAW = "Le Narrateur a décrété une loi d'essai sur un redémarrage."
        const val NOTE = "vérifier que le moulin a survécu au redémarrage"

        /** The reminder is due in a week: never during the run. */
        const val WEEK_MS = 7 * 24 * 3_600_000L

        /** The main class of the Journal's holder, from the unit tests' output (on this server's class path). */
        const val HOLDER = "io.github.clemsix6.narrator2.journal.JournalChild"

        val DIRECTOR = Caller(Role.DIRECTOR, "director-gametest-restart", turnId = 6013, taskId = null)

        /** Who ends, in the test, what it deployed. */
        val CLEANUP = Author(Source.OPERATOR, operator = "gametest")

        /** Runs `/narrator …` as the server console. Server thread. */
        fun command(helper: GameTestHelper, command: String): Answer {
            val server = helper.level.server
            val output = CommandOutput()
            server.commands.performPrefixedCommand(server.createCommandSourceStack().withSource(output), command)
            return Answer(output)
        }

        /** [tool] called by a Director's turn with [json], as the model writes it; OK, or the test fails. Off the server thread. */
        suspend fun call(runtime: NarratorRuntime, tool: NarratorTool<*>, json: String) {
            val result = ToolRunner(runtime).invoke(tool, DIRECTOR, json)
            check(result.status == ToolStatus.OK) { "${tool.name}: ${result.text}" }
        }

        /** No dashboard listens: the restart read the in-game runs' configuration. */
        fun noDashboard(runtime: NarratorRuntime) =
            check(runtime.dashboard.url == null && runtime.dashboard.unavailableReason == "dashboard.enabled is false") { "no dashboard: ${runtime.dashboard.url} ${runtime.dashboard.unavailableReason}" }

        /** The number of decisions [decision] about [subject] of [kind] in [runtime]'s Journal. */
        suspend fun decisions(runtime: NarratorRuntime, decision: String, subject: String, kind: String = "mechanic"): Long =
            runCatchingJournal(
                runtime, "SELECT count(*) FROM engine_decisions WHERE kind = ? AND decision = ? AND subject = ?", kind, decision, subject,
            )?.single() as Long

        /** Ends what the test deployed and still runs, through [runtime]'s engine. */
        suspend fun cleanUp(runtime: NarratorRuntime, made: List<Ref>) {
            if (!runtime.mechanics.available) return
            val mechanics = runtime.mechanics
            for (ref in made) {
                runCatching {
                    when {
                        mechanics.challenges.list().any { it.ref == ref } -> mechanics.challenges.end(ref, ChallengeOutcome.CANCELLED, emptyList(), "Annulé à la fin d'un test.", CLEANUP)
                        mechanics.rules.list().any { it.ref == ref } -> mechanics.rules.remove(ref, "La loi d'essai a été levée.", CLEANUP)
                        mechanics.modules.list().any { it.ref == ref } -> mechanics.modules.stop(ref, "Le test est fini.", CLEANUP)
                    }
                }
            }
        }

        /** The world's reminders as the test found them. Server thread. */
        fun putBack(server: MinecraftServer, reminders: ReminderState?) {
            val global = server.globalAttachments()
            if (reminders == null) global.removeAttached(ReminderState.TYPE) else global.setAttached(ReminderState.TYPE, reminders)
        }
    }
}
