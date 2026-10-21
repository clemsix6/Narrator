package io.github.clemsix6.narrator2.testing

import io.github.clemsix6.narrator2.agent.WorkersControl
import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.config.Prompts
import io.github.clemsix6.narrator2.config.UiTexts
import io.github.clemsix6.narrator2.director.DirectorControl
import io.github.clemsix6.narrator2.event.EventBus
import io.github.clemsix6.narrator2.forge.Forge
import io.github.clemsix6.narrator2.guard.Guards
import io.github.clemsix6.narrator2.journal.DisabledJournal
import io.github.clemsix6.narrator2.journal.Journal
import io.github.clemsix6.narrator2.llm.ClaudeClient
import io.github.clemsix6.narrator2.mechanics.Mechanics
import io.github.clemsix6.narrator2.memory.Memory
import io.github.clemsix6.narrator2.observer.PlayerStates
import io.github.clemsix6.narrator2.reflex.ReflexClient
import io.github.clemsix6.narrator2.reflex.Reflexes
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.runtime.Phase
import io.github.clemsix6.narrator2.runtime.Settings
import io.github.clemsix6.narrator2.runtime.SettingsHolder
import io.github.clemsix6.narrator2.style.StyleWatch
import io.github.clemsix6.narrator2.tool.ToolLimits
import io.github.clemsix6.narrator2.trace.TraceSink
import io.github.clemsix6.narrator2.trace.Transcripts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.minecraft.server.MinecraftServer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock

/**
 * Settings for tests: [config], the jar's prompts and French texts, [claude] and [reflex] (none by default).
 */
fun testSettings(config: NarratorConfig = NarratorConfig(), claude: ClaudeClient? = null, reflex: ReflexClient? = null): Settings =
    Settings(config, Prompts.bundled(), UiTexts.load(config.language), claude, reflex)

/**
 * The services of a runtime, for unit tests: every part is a constructor parameter, with a harmless
 * default (a real bus, a disabled Journal, traces in memory, fakes for the guards, the reflexes, the
 * Director, the workers, the memory, the style watch, the Forge and the mechanics engine). There is no Minecraft
 * server: [server] fails unless one is given.
 * [phase] can be changed to test the stopping server.
 */
class TestServices(
    private val minecraft: MinecraftServer? = null,
    override val clock: Clock = TestClock(),
    override val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    override val dataDir: Path = Files.createTempDirectory("narrator2-test"),
    settings: Settings = testSettings(),
    override val bus: EventBus = EventBus(clock, { 0L }, 1),
    override val journal: Journal = DisabledJournal("unit test", clock),
    override val trace: TraceSink = MemoryTraceSink(),
    override val transcripts: Transcripts = Transcripts(dataDir.resolve("transcripts"), enabled = false),
    override val players: PlayerStates = PlayerStates(),
    override val guards: Guards = FakeGuards(),
    override val reflexes: Reflexes = FakeReflexes(),
    override val director: DirectorControl = FakeDirector(clock),
    override val workers: WorkersControl = FakeWorkers(),
    override val memory: Memory = FakeMemory(clock),
    override val style: StyleWatch = FakeStyle(),
    override val forge: Forge = FakeForge(clock),
    override val mechanics: Mechanics = FakeMechanics(clock),
) : NarratorServices {
    override val server: MinecraftServer get() = minecraft ?: error("no Minecraft server in unit tests")

    @Volatile
    override var phase: Phase = Phase.RUNNING

    override val settings = SettingsHolder(settings)

    override val toolLimits = ToolLimits(clock)

    /** [trace] as the in-memory sink it is by default. */
    val traces: MemoryTraceSink get() = trace as MemoryTraceSink
}
