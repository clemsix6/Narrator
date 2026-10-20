package io.github.clemsix6.narrator2.mechanics.module

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.forge.ForgeLimits
import io.github.clemsix6.narrator2.forge.api.Acts
import io.github.clemsix6.narrator2.forge.api.Args
import io.github.clemsix6.narrator2.forge.api.Creatures
import io.github.clemsix6.narrator2.forge.api.Events
import io.github.clemsix6.narrator2.forge.api.ForgeRefusal
import io.github.clemsix6.narrator2.forge.api.Game
import io.github.clemsix6.narrator2.forge.api.Ground
import io.github.clemsix6.narrator2.forge.api.ModuleContext
import io.github.clemsix6.narrator2.forge.api.Storage
import io.github.clemsix6.narrator2.forge.api.Timers
import io.github.clemsix6.narrator2.forge.api.World
import io.github.clemsix6.narrator2.forge.sdk.ActQueue
import io.github.clemsix6.narrator2.forge.sdk.ArgsView
import io.github.clemsix6.narrator2.forge.sdk.CreatureControl
import io.github.clemsix6.narrator2.forge.sdk.FactQueue
import io.github.clemsix6.narrator2.forge.sdk.GroundView
import io.github.clemsix6.narrator2.forge.sdk.Scheduler
import io.github.clemsix6.narrator2.forge.sdk.StorageMap
import io.github.clemsix6.narrator2.forge.sdk.WorldView
import io.github.clemsix6.narrator2.memory.Ref
import java.time.Clock
import java.util.Random

/**
 * What one deployment's module uses (M5 §4.2, §6.5): its [ModuleContext], assembled from the SDK's real pieces — its
 * parameters, facts, timers, reads of the world and storage (M5-06), its acts, creatures, ground and the challenge it
 * referees (M5-07) — plus what the context does itself: its id, its clock, its random numbers, its log lines, its notes
 * to the Director and its end. The host hands it to the module behind its proxies (`ModuleHost.start`), never bare.
 *
 * - **Log** ([log]): the Forge's log, `INFO`, `C-70 orbiting_chickens: …`, cut to [ForgeLimits.NOTE_CHARS];
 *   [ForgeLimits.LOG_LINES_PER_MINUTE] a minute, the others dropped and counted in the next line.
 * - **Notes to the Director** ([tellDirector]): one every [ForgeLimits.DIRECTOR_NOTE_GAP_MS] at most, through [tell];
 *   a note sooner waits for the gap ([second]), the latest one only, the others dropped and counted in it.
 * - **End** ([end]): asked by the module, applied by the engine once the call returns ([endAsked]).
 * - **Stop** ([stopping]): while the module's `stop` runs, it may read (world, storage, its creatures) and write its
 *   storage; its acts are `dropped`, timers and subscriptions refused, an end or a note ignored. Then [close]: every
 *   piece closed, and every method of the context throws [ForgeRefusal] from then on.
 *
 * Server thread only (the host runs each SDK call of the module there).
 *
 * @param tell posts a line `C-73 simon_says says: « … »` to the Director (the engine's, with its decision).
 */
class RunContext(
    val ref: Ref,
    private val label: String,
    private val restarted: Boolean,
    private val endsAt: Long,
    seed: Long,
    private val clock: Clock,
    val args: ArgsView,
    val events: FactQueue,
    val timers: Scheduler,
    val world: World,
    val queue: ActQueue,
    val acts: Acts,
    val creatures: CreatureControl,
    val ground: GroundView,
    val storage: StorageMap,
    val game: Game?,
    private val tell: (String) -> Unit,
) : ModuleContext {
    private val log = Log.forge
    private val random = Random(seed)

    private enum class Phase { RUNNING, STOPPING, CLOSED }

    private var phase = Phase.RUNNING

    /** Why the module asked to end ([end]), once; null while it did not. Read by the engine after each call. */
    var endAsked: String? = null
        private set

    private var logMinute = 0L
    private var logLines = 0
    private var logDropped = 0

    /** When the last note went to the Director; [Long.MIN_VALUE] before the first. */
    private var toldAt = Long.MIN_VALUE

    /** The note waiting for the gap, and the notes it replaced since the last one went. */
    private var waiting: String? = null
    private var replaced = 0

    override fun id(): String = checked { ref.toString() }

    override fun restarted(): Boolean = checked { restarted }

    override fun now(): Long = checked { clock.millis() }

    override fun endsAt(): Long = checked { endsAt }

    override fun random(): Random = checked { random }

    override fun args(): Args = checked { args }

    override fun events(): Events = checked { events }

    override fun timers(): Timers = checked { timers }

    override fun world(): World = checked { world }

    override fun acts(): Acts = checked { acts }

    override fun creatures(): Creatures = checked { creatures }

    override fun ground(): Ground = checked { ground }

    override fun storage(): Storage = checked { storage }

    override fun game(): Game? = checked { game }

    override fun log(line: String?) {
        open()
        if (line == null) throw ForgeRefusal("log needs a line")
        val now = clock.millis()
        if (now - logMinute >= MINUTE_MS || now < logMinute) {
            logMinute = now
            logLines = 0
        }
        if (logLines >= ForgeLimits.LOG_LINES_PER_MINUTE) {
            logDropped++
            return
        }
        logLines++
        val dropped = if (logDropped > 0) " ($logDropped lines dropped before)" else ""
        logDropped = 0
        log.info("$label: ${ModelText.cut(ModelText.escape(line), ForgeLimits.NOTE_CHARS)}$dropped")
    }

    override fun tellDirector(note: String?) {
        open()
        if (note.isNullOrBlank()) throw ForgeRefusal("tellDirector needs a note, French, for the Director")
        if (phase == Phase.STOPPING) {
            log.debug { "$label: a note to the Director while it stops, dropped (its end is told)" }
            return
        }
        val cut = ModelText.cut(note.trim(), ForgeLimits.NOTE_CHARS)
        if (waiting != null) replaced++
        waiting = cut
        second(clock.millis())
    }

    override fun end(why: String?) {
        open()
        if (phase != Phase.RUNNING || endAsked != null) return
        endAsked = why?.trim()?.takeIf { it.isNotEmpty() }?.let { ModelText.cut(it, ForgeLimits.NOTE_CHARS) } ?: ENDED_BY_ITSELF
    }

    /** Once a second (and after a note): the note waiting goes to the Director once the gap since the last one has passed. */
    fun second(now: Long) {
        val note = waiting ?: return
        if (phase != Phase.RUNNING || (toldAt != Long.MIN_VALUE && now - toldAt < ForgeLimits.DIRECTOR_NOTE_GAP_MS)) return
        val dropped = if (replaced > 0) " (${replaced} earlier ${if (replaced == 1) "note" else "notes"} dropped: one every ${ForgeLimits.DIRECTOR_NOTE_GAP_MS / MINUTE_MS} minutes)" else ""
        waiting = null
        replaced = 0
        toldAt = now
        tell("$label says: « ${ModelText.escape(note)} »$dropped")
    }

    /** The module's `stop` is about to run: its acts dropped, no timer nor subscription, no end nor note. */
    fun stopping() {
        if (phase != Phase.RUNNING) return
        phase = Phase.STOPPING
        events.stopping()
        timers.stopping()
        queue.stopping()
    }

    /**
     * The deployment ended: every piece closed (what is queued `dropped`, every timer and subscription cancelled,
     * every SDK object refused), and the context too. Its storage still reads ([StorageMap.snapshot]). Its creatures
     * are the engine's to give back.
     */
    fun close() {
        if (phase == Phase.CLOSED) return
        phase = Phase.CLOSED
        waiting = null
        queue.close()
        events.close()
        timers.close()
        args.close()
        (world as? WorldView)?.close()
        storage.close()
    }

    /** [value], unless the deployment has ended. */
    private inline fun <T> checked(value: () -> T): T {
        open()
        return value()
    }

    private fun open() {
        if (phase == Phase.CLOSED) throw ForgeRefusal("the deployment has ended: its SDK objects no longer work")
    }

    companion object {
        /** The canon's resolution of a module that ended itself without saying why. */
        const val ENDED_BY_ITSELF = "it ended by itself"

        private const val MINUTE_MS = 60_000L
    }
}
