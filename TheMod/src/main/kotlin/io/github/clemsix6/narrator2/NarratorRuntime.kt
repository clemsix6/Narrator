package io.github.clemsix6.narrator2

import io.github.clemsix6.narrator2.agent.WorkersControl
import io.github.clemsix6.narrator2.agent.createWorkers
import io.github.clemsix6.narrator2.command.AdminCommands
import io.github.clemsix6.narrator2.command.StyleCommands
import io.github.clemsix6.narrator2.command.narratorLine
import io.github.clemsix6.narrator2.config.ConfigException
import io.github.clemsix6.narrator2.config.ConfigStore
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.runOnServerThread
import io.github.clemsix6.narrator2.director.DirectorControl
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.director.createDirector
import io.github.clemsix6.narrator2.event.EventBus
import io.github.clemsix6.narrator2.forge.Forge
import io.github.clemsix6.narrator2.forge.createForge
import io.github.clemsix6.narrator2.forge.forgeState
import io.github.clemsix6.narrator2.guard.Guards
import io.github.clemsix6.narrator2.guard.createGuards
import io.github.clemsix6.narrator2.journal.DuckJournal
import io.github.clemsix6.narrator2.journal.IdKind
import io.github.clemsix6.narrator2.journal.Journal
import io.github.clemsix6.narrator2.journal.openJournalOrDisable
import io.github.clemsix6.narrator2.mechanics.Mechanics
import io.github.clemsix6.narrator2.mechanics.createMechanics
import io.github.clemsix6.narrator2.memory.Memory
import io.github.clemsix6.narrator2.memory.PreparedMemory
import io.github.clemsix6.narrator2.memory.createMemory
import io.github.clemsix6.narrator2.memory.prepareMemory
import io.github.clemsix6.narrator2.observer.ObserverState
import io.github.clemsix6.narrator2.observer.PlayerStates
import io.github.clemsix6.narrator2.observer.installObserver
import io.github.clemsix6.narrator2.reflex.Reflexes
import io.github.clemsix6.narrator2.reflex.createReflexes
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.runtime.Phase
import io.github.clemsix6.narrator2.runtime.RestartReport
import io.github.clemsix6.narrator2.runtime.RestartSequence
import io.github.clemsix6.narrator2.runtime.Restartable
import io.github.clemsix6.narrator2.runtime.Settings
import io.github.clemsix6.narrator2.runtime.SettingsHolder
import io.github.clemsix6.narrator2.runtime.SettingsLoader
import io.github.clemsix6.narrator2.runtime.ShutdownSequence
import io.github.clemsix6.narrator2.runtime.TickClock
import io.github.clemsix6.narrator2.style.StyleWatch
import io.github.clemsix6.narrator2.style.createLineReviewer
import io.github.clemsix6.narrator2.style.createStyle
import io.github.clemsix6.narrator2.tool.ToolLimits
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import io.github.clemsix6.narrator2.trace.TraceSink
import io.github.clemsix6.narrator2.trace.Transcripts
import io.github.clemsix6.narrator2.web.Dashboard
import io.github.clemsix6.narrator2.web.createDashboard
import io.github.clemsix6.narrator2.web.dashboardState
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.ChatFormatting
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Path
import java.time.Clock

/**
 * Everything the Narrator runs while a server is up (M1_M2 §2), from `SERVER_STARTED` to
 * `SERVER_STOPPED`: the bus, the Journal, the player states, the Observer, the guards, the reflexes,
 * the workers, the memory, the style watch and the line review, the Forge, the mechanics engine, the Director and the
 * dashboard. `/narrator reload` only replaces the [settings] (configuration, prompts, texts and clients);
 * `/narrator restart` (M6 §9, [RestartSequence]) replaces the whole runtime by a new one, on an empty server.
 *
 * Components depend on [NarratorServices], never on this class. A runtime starts in two steps (M6 §9). [prepare] opens
 * what may wait: the Journal (three attempts in 10 s at most), the player states, then the memory's reads (30 s at most,
 * [prepareMemory]). [wire] then creates the rest, without waiting, on the server thread, in this order, each factory
 * receiving the runtime with the services created before it: bus, guards, Observer, reflexes, workers, memory, style
 * watch, line review, Forge, mechanics, Director, dashboard. At `SERVER_STARTED` both run on the server thread, before
 * the first tick (ARCHITECTURE §17.8); at a restart, [create] and [prepare] run on the restart's own thread, once the
 * old runtime is closed: an old Journal still closing keeps a new one from opening ([stopAndClose], `RestartSequence`).
 * The Forge comes before the mechanics, whose modules' part builds and runs its modules; it starts off the server thread
 * (its arsenal, the built-in modules compiled) while the rest starts. The dashboard comes last: it reads the others,
 * nothing reads it. At `SERVER_STOPPING` ([ShutdownSequence]) the dashboard stops first, then the mechanics (their
 * modules stop with the server, then the modules' thread ends), then the Forge (its compiler thread ends), then the memory.
 *
 * Data lives in the world folder (`world/narrator2/`), so each world has its own history; configuration
 * and overrides live in `config/narrator2/`.
 */
class NarratorRuntime private constructor(
    override val server: MinecraftServer,
    initial: Settings,
    override val clock: Clock,
) : NarratorServices, Restartable {
    override val dataDir: Path = server.getWorldPath(LevelResource.ROOT).resolve("narrator2").toAbsolutePath().normalize()

    /**
     * Background work: Director, workers, model calls, SQL reads, reloads. A failure in one job never
     * cancels the others. Cancelled as a whole at `SERVER_STOPPING`: work that must outlive that step
     * (recording the players' departures) never runs here.
     */
    override val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName("narrator2") +
            CoroutineExceptionHandler { _, error -> log.error("background work failed", error) },
    )

    @Volatile
    final override var phase: Phase = Phase.STARTING
        private set

    override val settings = SettingsHolder(initial)

    override val transcripts = Transcripts(dataDir.resolve("logs").resolve("conversations"), initial.config.logging.transcripts)

    /** Game time for the bus, readable from any thread. */
    val ticks = TickClock()

    override val toolLimits = ToolLimits(clock)

    final override lateinit var journal: Journal
        private set

    override val trace: TraceSink get() = journal

    final override lateinit var bus: EventBus
        private set

    final override lateinit var players: PlayerStates
        private set

    final override lateinit var guards: Guards
        private set

    /** The Observer's state; hooks reach it through `ObserverState.current()`. */
    lateinit var observer: ObserverState
        private set

    final override lateinit var reflexes: Reflexes
        private set

    final override lateinit var workers: WorkersControl
        private set

    final override lateinit var memory: Memory
        private set

    final override lateinit var style: StyleWatch
        private set

    final override lateinit var forge: Forge
        private set

    final override lateinit var mechanics: Mechanics
        private set

    final override lateinit var director: DirectorControl
        private set

    /** The web dashboard (M6 §8), for `/narrator dashboard`: no component depends on it. */
    lateinit var dashboard: Dashboard
        private set

    /** What [prepare] read of the memory, for [wire]. */
    private lateinit var preparedMemory: PreparedMemory

    private lateinit var shutdown: ShutdownSequence

    /**
     * Opens what the wiring needs and may wait for: the Journal (or a `DisabledJournal` that says why), the player
     * states, then the memory's reads. Bounded (10 s, then 30 s). The server thread at the server's start, the
     * restart's thread at a restart; never throws for an unavailable Journal or memory.
     */
    override fun prepare() {
        val config = settings.get().config
        journal = openJournalOrDisable(dataDir, config.journal, clock, dashboard = config.dashboard.enabled)
        players = PlayerStates()
        preparedMemory = prepareMemory(this)
    }

    /** Creates the server's components with what [prepare] opened, in the order of the class documentation, then runs. Server thread; never waits. */
    private fun wire() {
        ticks.update(server)
        bus = EventBus(clock, ticks::gameTime, journal.nextIdSeed(IdKind.EVENT))
        journal.attach(bus)
        (journal as? DuckJournal)?.tellOperators = ::tellOperatorsOnline
        players.subscribe(bus) { uuid -> server.playerList.getPlayer(uuid) }
        guards = createGuards(this)
        observer = installObserver(this)
        reflexes = createReflexes(this)
        workers = createWorkers(this)
        memory = createMemory(this, preparedMemory)
        style = createStyle(this)
        createLineReviewer(this)
        forge = createForge(this, ::tellOperatorsOnline)
        mechanics = createMechanics(this)
        director = createDirector(this)
        dashboard = createDashboard(this)
        shutdown = ShutdownSequence(
            scope, bus, journal, settings, observer::flushAll, { phase = it }, stopMemory = memory::stop, stopMechanics = mechanics::stop,
            stopForge = forge::stop, stopDashboard = dashboard::stop,
        )
        phase = Phase.RUNNING
    }

    /**
     * A yellow `[Narrator]` line in the chat of the operators online (the Journal's outages, a Forge that fell). Any thread;
     * on the server thread only, so dropped once the server has stopped.
     */
    private fun tellOperatorsOnline(text: String) {
        server.runOnServerThread {
            val line = narratorLine(text, ChatFormatting.YELLOW)
            server.playerList.players.filter { server.playerList.isOp(it.nameAndId()) }.forEach { it.sendSystemMessage(line) }
        }
    }

    /** Undoes a [prepare] or a [wire] that failed halfway, at the server's start. Server thread. */
    private fun abandon() {
        stopInGame()
        runCatching { stopAndClose() }.onFailure { log.error("the Narrator that could not start did not close", it) }
    }

    /** End of a server tick. Server thread. */
    fun onTick(server: MinecraftServer) {
        if (server === this.server) ticks.update(server)
    }

    /**
     * `/narrator reload`: loads the configuration, prompts and texts again and installs them at once
     * (M1_M2 §2.3). The services stay as they are; the replaced clients close once their calls in flight
     * are over, and the Director learns about the change. Reads files: never on the server thread.
     * Throws when loading fails, and the settings in force stay.
     */
    fun reload(): Settings {
        val next = SettingsLoader.load(configDir, environment)
        if (phase != Phase.RUNNING) {
            next.close()
            throw IllegalStateException("the server is stopping")
        }
        val old = settings.swap(next)
        Log.applyLevels(next.config.logging.levels)
        transcripts.enabled = next.config.logging.transcripts
        director.post(DirectorSignal.SettingsChanged(old, next))
        Decisions(trace, clock).record(
            Log.main, DecisionRecord.Kind.RELOAD, "reloaded",
            reason = "new configuration, prompts and texts in force; LLMGW key ${if (next.claude != null) "present" else "missing"}",
        )
        return next
    }

    /** `SERVER_STOPPING`: no new work, the work in flight is cancelled; facts are still recorded. Server thread. */
    fun beginShutdown() = shutdown.begin()

    /** `SERVER_STOPPED`: the Journal is flushed and closed, everything else too. Server thread. */
    fun finishShutdown() {
        shutdown.finish()
        if (current === this) current = null
    }

    /**
     * The half of a restart's stop that never waits (M6 §9): `STOPPING`, the dashboard, the mechanics, the Forge, the
     * Observer's flush; on a runtime whose wiring failed, what it made. Then the hooks no longer reach it. Server thread.
     */
    override fun stopInGame() {
        if (::shutdown.isInitialized) {
            shutdown.stopInGame()
            shutdown.flush()
        } else {
            phase = Phase.STOPPED
            scope.cancel()
            if (::dashboard.isInitialized) dashboard.stop()
            if (::mechanics.isInitialized) mechanics.stop()
            if (::forge.isInitialized) forge.stop()
            if (::bus.isInitialized) bus.close()
        }
        if (current === this) current = null
    }

    /**
     * The half that waits, bounded: the memory, the work in flight, the Director's subscriptions, the bus, the Journal,
     * the clients; on a runtime never wired, what [prepare] opened. Throws while the Journal still holds its database
     * (its writer outlived the close's time): nothing may open the file then. Called again, it waits for that Journal
     * once more. Off the server thread at a restart.
     */
    override fun stopAndClose() {
        if (::shutdown.isInitialized) {
            shutdown.stopWaiting()
            shutdown.close()
        } else {
            phase = Phase.STOPPED
            scope.cancel()
            if (::journal.isInitialized) runCatching { journal.close() }.onFailure { log.error("the Journal failed to close", it) }
            settings.get().close()
        }
        check(!::journal.isInitialized || !journal.holdsDatabase) {
            "its Journal is still closing after ${Journal.DEFAULT_CLOSE_TIMEOUT.toSeconds()} s, its database open until its writer ends"
        }
    }

    /** [wire], then the running Narrator: the hooks reach it. Server thread. */
    override fun wireAndRun() {
        wire()
        install(this)
    }

    override fun summary(): String =
        "Journal ${if (journal.available) "available" else "unavailable"} · " +
            "memory ${if (memory.available) "on" else "off (${memory.unavailableReason})"} · " +
            "mechanics ${if (mechanics.available) "on" else "off (${mechanics.unavailableReason})"} · " +
            "forge ${forgeState(forge, mechanics.modules.halted)}"

    override fun restarted(report: RestartReport) {
        Decisions(trace, clock).record(
            Log.main, DecisionRecord.Kind.OPERATOR, "restarted",
            subject = report.by,
            reason = "${report.text()} (${report.times()})",
            detail = buildJsonObject {
                put("stop_ms", report.stopMs)
                put("prepare_ms", report.prepareMs)
                put("wire_ms", report.wireMs)
                put("gap_ms", report.gapMs)
                put("total_ms", report.totalMs)
                put("journal", journal.available)
                put("memory", memory.available)
                put("mechanics", mechanics.available)
                put("forge", forge.available)
            },
        )
    }

    companion object {
        private val log = Log.main

        val configDir: Path = FabricLoader.getInstance().configDir.resolve("narrator2").toAbsolutePath().normalize()
        val configFile: Path = configDir.resolve(ConfigStore.FILE_NAME)
        val promptsDir: Path = configDir.resolve(SettingsLoader.PROMPTS_DIR)

        /**
         * The Narrator of the running server, from the end of its start to `SERVER_STOPPED`; null
         * otherwise, and during a restart. Hooks registered at mod initialization go through it and do nothing when it is null.
         */
        @Volatile
        var current: NarratorRuntime? = null
            private set

        /** Where the LLMGW key is looked up. In-game tests replace it to inject a key. */
        @Volatile
        var environment: (String) -> String? = System::getenv

        /** The restarts of the JVM, one at a time (M6 §9); the logins wait for the one running (`Narrator2`, `QUERY_START`). */
        val restarts = RestartSequence()

        /**
         * Starts the Narrator for [server] (M1_M2 §2.2): [prepare] then [wire], on the server thread before the first
         * tick. A configuration that cannot be loaded is logged and replaced by the defaults: facts are still recorded,
         * and the Director sleeps until a `/narrator reload` succeeds. A Journal of an earlier Narrator of this process
         * still closing (its writer outlived its close) keeps the new one from opening its file (`DuckDatabase.open`):
         * tried again for 10 s at most, the new Journal is otherwise unavailable, and says why. Server thread.
         */
        fun start(server: MinecraftServer) {
            current?.let { stale ->
                log.warn("a previous Narrator was still running; stopping it first")
                stale.finishShutdown()
            }
            val runtime = create(server)
            try {
                runtime.prepare()
                runtime.wire()
            } catch (e: Exception) {
                log.error("cannot start, the Narrator stays off", e)
                runtime.abandon()
                return
            }
            install(runtime)
        }

        /**
         * `/narrator restart` on [server], asked by [by] (M6 §9): false when a restart already runs; the caller checked
         * [RestartSequence.refusal] first. [done] gets the report on the server thread. [create] makes the new Narrator,
         * which the restart then prepares: in-game tests hold the Journal's file before it. Server thread.
         */
        fun restart(
            server: MinecraftServer,
            by: String,
            done: (RestartReport) -> Unit,
            create: () -> NarratorRuntime = { create(server) },
        ): Boolean = restarts.start(server, by, current, create, stopping = { !server.isRunning }, done)

        /**
         * The runtime of [server] with its settings, not prepared yet: loaded from the files, or the defaults when they
         * cannot be. Reads files: never on the server thread, but at its start.
         */
        fun create(server: MinecraftServer): NarratorRuntime {
            val settings = try {
                SettingsLoader.load(configDir, environment)
            } catch (e: Exception) {
                val reason = e.message ?: e.javaClass.simpleName
                val detail = if (e is ConfigException) null else e
                log.error("cannot load the configuration, running with defaults and no Director until /narrator reload succeeds: $reason", detail)
                SettingsLoader.fallback(reason)
            }
            Log.applyLevels(settings.config.logging.levels)
            return NarratorRuntime(server, settings, Clock.systemDefaultZone())
        }

        /** [runtime] becomes the running Narrator: the hooks reach it; the `ready` line. Server thread. */
        private fun install(runtime: NarratorRuntime) {
            current = runtime
            val settings = runtime.settings.get()
            val config = settings.config
            log.info(
                "ready · gateway ${config.gateway.baseUrl} · LLMGW key ${if (settings.claude != null) "present" else "missing"} · " +
                    "${runtime.summary()} · ${StyleCommands.readyState(runtime)} · storyteller ${AdminCommands.storytellerState(runtime)} · " +
                    "dashboard ${dashboardState(runtime.dashboard)} · data in ${runtime.dataDir}",
            )
            if (settings.claude == null && settings.loadError == null) log.warn("no LLMGW key: ${SettingsLoader.missingKeyHint(config)}")
        }
    }
}
