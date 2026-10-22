package io.github.clemsix6.narrator2.runtime

import io.github.clemsix6.narrator2.agent.WorkersControl
import io.github.clemsix6.narrator2.director.DirectorControl
import io.github.clemsix6.narrator2.event.EventBus
import io.github.clemsix6.narrator2.forge.Forge
import io.github.clemsix6.narrator2.guard.Guards
import io.github.clemsix6.narrator2.journal.Journal
import io.github.clemsix6.narrator2.mechanics.Mechanics
import io.github.clemsix6.narrator2.memory.Memory
import io.github.clemsix6.narrator2.observer.PlayerStates
import io.github.clemsix6.narrator2.reflex.Reflexes
import io.github.clemsix6.narrator2.relics.RelicsService
import io.github.clemsix6.narrator2.style.StyleWatch
import io.github.clemsix6.narrator2.tool.ToolLimits
import io.github.clemsix6.narrator2.trace.TraceSink
import io.github.clemsix6.narrator2.trace.Transcripts
import kotlinx.coroutines.CoroutineScope
import net.minecraft.server.MinecraftServer
import java.nio.file.Path
import java.time.Clock

/** Life cycle of the Narrator on a server. */
enum class Phase {
    /** Components are being created. */
    STARTING,

    /** Everything runs. */
    RUNNING,

    /** The server is stopping: no new work (no wake-up, no task, no tool call); facts are still journaled. */
    STOPPING,

    /** Everything is closed. */
    STOPPED,
}

/**
 * What the Narrator's components use, from the runtime's start to its stop: the server's, or a
 * `/narrator restart` (M6 §9), which replaces the whole runtime. Implemented by `NarratorRuntime`; unit
 * tests implement it with fakes. No component depends on the runtime class.
 *
 * Everything here lives as long as the runtime, except [server], which outlives it, and [settings],
 * whose content `/narrator reload` replaces.
 */
interface NarratorServices {
    val server: MinecraftServer

    /** Background work (Director, workers, reflexes, reloads). Network calls switch to `Dispatchers.IO`. */
    val scope: CoroutineScope

    val phase: Phase

    /** Wall clock of the engine; a fixed or stepped clock in tests. */
    val clock: Clock

    /** The world's Narrator folder, `world/narrator2/`: Journal, transcripts, small state files. */
    val dataDir: Path

    val settings: SettingsHolder

    val bus: EventBus

    val journal: Journal

    /** Where traces go and where their ids come from: the [journal] in production, an in-memory sink in tests. */
    val trace: TraceSink

    /** Conversation transcripts, under [dataDir]. */
    val transcripts: Transcripts

    /** Live state of the players online. */
    val players: PlayerStates

    val guards: Guards

    val reflexes: Reflexes

    val director: DirectorControl

    val workers: WorkersControl

    /** The Narrator's memory (design M3); `DisabledMemory` when it is off or could not start. */
    val memory: Memory

    /**
     * The watch of the Narrator's style (design M6 §3): the Director's latest lines and the line review's labels,
     * read by `say`, the situation and `inspire`; `DisabledStyle` when it does not run. Created after the memory.
     */
    val style: StyleWatch

    /**
     * The Forge (design M5): the arsenal of modules and the pipeline that makes them; `DisabledForge` when it is off or
     * could not start. Created before the mechanics, which deploy its modules.
     */
    val forge: Forge

    /** The mechanics engine (design M4): challenges, duels, bets, rules, the modules deployed; `DisabledMechanics` when it is off or could not start. */
    val mechanics: Mechanics

    /** The tools' limits that no new conversation resets (speech, gifts, weather and time, commands per turn). */
    val toolLimits: ToolLimits

    /**
     * The armorer's work (M7-RELICS): champions, relics, the chests' reserves. Null without a Journal (where its sheets and
     * reserves are kept), and in unit tests.
     */
    val relics: RelicsService? get() = null
}
