package io.github.clemsix6.narrator2

import io.github.clemsix6.narrator2.agent.WorkersControl
import io.github.clemsix6.narrator2.agent.createWorkers
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
import io.github.clemsix6.narrator2.guard.Guards
import io.github.clemsix6.narrator2.guard.createGuards
import io.github.clemsix6.narrator2.journal.DuckJournal
import io.github.clemsix6.narrator2.journal.IdKind
import io.github.clemsix6.narrator2.journal.Journal
import io.github.clemsix6.narrator2.journal.openJournalOrDisable
import io.github.clemsix6.narrator2.mechanics.Mechanics
import io.github.clemsix6.narrator2.mechanics.createMechanics
import io.github.clemsix6.narrator2.memory.Memory
import io.github.clemsix6.narrator2.memory.createMemory
import io.github.clemsix6.narrator2.observer.ObserverState
import io.github.clemsix6.narrator2.observer.PlayerStates
import io.github.clemsix6.narrator2.observer.installObserver
import io.github.clemsix6.narrator2.reflex.Reflexes
import io.github.clemsix6.narrator2.reflex.createReflexes
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.runtime.Phase
import io.github.clemsix6.narrator2.runtime.Settings
import io.github.clemsix6.narrator2.runtime.SettingsHolder
import io.github.clemsix6.narrator2.runtime.SettingsLoader
import io.github.clemsix6.narrator2.runtime.ShutdownSequence
import io.github.clemsix6.narrator2.runtime.TickClock
import io.github.clemsix6.narrator2.tool.ToolLimits
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import io.github.clemsix6.narrator2.trace.TraceSink
import io.github.clemsix6.narrator2.trace.Transcripts
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.ChatFormatting
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Path
import java.time.Clock

/**
 * Everything the Narrator runs while a server is up (M1_M2 §2), from `SERVER_STARTED` to
 * `SERVER_STOPPED`: the bus, the Journal, the player states, the Observer, the guards, the reflexes,
 * the workers, the memory, the Forge, the mechanics engine and the Director. None of them is ever rebuilt while
 * the server runs: `/narrator reload` only replaces the [settings] (configuration, prompts, texts and clients).
 *
 * Components depend on [NarratorServices], never on this class. They are created in this order, each
 * factory receiving the runtime with the services created before it: Journal, bus, player states,
 * guards, Observer, reflexes, workers, memory, Forge, mechanics, Director.
 *
 * Data lives in the world folder (`world/narrator2/`), so each world has its own history; configuration
 * and overrides live in `config/narrator2/`.
 */
class NarratorRuntime private constructor(
    override val server: MinecraftServer,
    initial: Settings,
    override val clock: Clock,
) : NarratorServices {
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

    final override lateinit var forge: Forge
        private set

    final override lateinit var mechanics: Mechanics
        private set

    final override lateinit var director: DirectorControl
        private set

    private lateinit var shutdown: ShutdownSequence

    /** Creates the server's components, in the order of the class documentation, then runs. Server thread. */
    private fun wire() {
        ticks.update(server)
        journal = openJournalOrDisable(dataDir, settings.get().config.journal, clock)
        bus = EventBus(clock, ticks::gameTime, journal.nextIdSeed(IdKind.EVENT))
        journal.attach(bus)
        (journal as? DuckJournal)?.tellOperators = ::tellOperatorsOnline
        players = PlayerStates().also { it.subscribe(bus) { uuid -> server.playerList.getPlayer(uuid) } }
        guards = createGuards(this)
        observer = installObserver(this)
        reflexes = createReflexes(this)
        workers = createWorkers(this)
        memory = createMemory(this)
        forge = createForge(this)
        mechanics = createMechanics(this)
        director = createDirector(this)
        shutdown = ShutdownSequence(
            scope, bus, journal, settings, observer::flushAll, { phase = it }, stopMemory = memory::stop, stopMechanics = mechanics::stop,
            stopForge = forge::stop,
        )
        phase = Phase.RUNNING
    }

    /**
     * A yellow `[Narrator]` line in the chat of the operators online (the Journal's outages). Any thread;
     * on the server thread only, so dropped once the server has stopped.
     */
    private fun tellOperatorsOnline(text: String) {
        server.runOnServerThread {
            val line = narratorLine(text, ChatFormatting.YELLOW)
            server.playerList.players.filter { server.playerList.isOp(it.nameAndId()) }.forEach { it.sendSystemMessage(line) }
        }
    }

    /** Undoes a [wire] that failed halfway. */
    private fun abandon() {
        phase = Phase.STOPPED
        scope.cancel()
        if (::bus.isInitialized) bus.close()
        if (::journal.isInitialized) runCatching { journal.close() }.onFailure { log.error("the Journal failed to close", it) }
        settings.get().close()
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

    companion object {
        private val log = Log.main

        val configDir: Path = FabricLoader.getInstance().configDir.resolve("narrator2").toAbsolutePath().normalize()
        val configFile: Path = configDir.resolve(ConfigStore.FILE_NAME)
        val promptsDir: Path = configDir.resolve(SettingsLoader.PROMPTS_DIR)

        /**
         * The Narrator of the running server, from the end of its start to `SERVER_STOPPED`; null
         * otherwise. Hooks registered at mod initialization go through it and do nothing when it is null.
         */
        @Volatile
        var current: NarratorRuntime? = null
            private set

        /** Where the LLMGW key is looked up. In-game tests replace it to inject a key. */
        @Volatile
        var environment: (String) -> String? = System::getenv

        /**
         * Starts the Narrator for [server] (M1_M2 §2.2). A configuration that cannot be loaded is logged
         * and replaced by the defaults: facts are still recorded, and the Director sleeps until a
         * `/narrator reload` succeeds. Server thread.
         */
        fun start(server: MinecraftServer) {
            current?.let { stale ->
                log.warn("a previous Narrator was still running; stopping it first")
                stale.finishShutdown()
            }
            val settings = try {
                SettingsLoader.load(configDir, environment)
            } catch (e: Exception) {
                val reason = e.message ?: e.javaClass.simpleName
                val detail = if (e is ConfigException) null else e
                log.error("cannot load the configuration, running with defaults and no Director until /narrator reload succeeds: $reason", detail)
                SettingsLoader.fallback(reason)
            }
            Log.applyLevels(settings.config.logging.levels)
            val runtime = NarratorRuntime(server, settings, Clock.systemDefaultZone())
            try {
                runtime.wire()
            } catch (e: Exception) {
                log.error("cannot start, the Narrator stays off", e)
                runtime.abandon()
                return
            }
            current = runtime
            val config = settings.config
            log.info(
                "ready · gateway ${config.gateway.baseUrl} · LLMGW key ${if (settings.claude != null) "present" else "missing"} · " +
                    "Journal ${if (runtime.journal.available) "available" else "unavailable"} · " +
                    "memory ${if (runtime.memory.available) "on" else "off (${runtime.memory.unavailableReason})"} · " +
                    "mechanics ${if (runtime.mechanics.available) "on" else "off (${runtime.mechanics.unavailableReason})"} · " +
                    "forge ${if (runtime.forge.available) "on" else "off (${runtime.forge.unavailableReason})"} · data in ${runtime.dataDir}",
            )
            if (settings.claude == null && settings.loadError == null) log.warn("no LLMGW key: ${SettingsLoader.missingKeyHint(config)}")
        }
    }
}
