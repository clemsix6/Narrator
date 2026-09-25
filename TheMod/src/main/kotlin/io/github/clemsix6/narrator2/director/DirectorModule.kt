package io.github.clemsix6.narrator2.director

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.Caller
import java.util.UUID

/**
 * Creates the Director of a server that starts (M1_M2 §12), last of the server's components: every
 * other service is set when it runs. Its coroutine runs in `services.scope`, which the runtime cancels
 * at `SERVER_STOPPING`; bus subscriptions that feed it use the owner `ShutdownSequence.DIRECTOR_OWNER`,
 * closed at the same moment. `/narrator reload` reaches it as a `DirectorSignal.SettingsChanged`.
 *
 * Provisional body (T02): an inert Director, `DISABLED`, that ignores every signal. T18 replaces it.
 */
fun createDirector(services: NarratorServices): DirectorControl = InertDirector(services)

/** A Director that never wakes up. */
private class InertDirector(private val services: NarratorServices) : DirectorControl {
    override fun post(signal: DirectorSignal) {
        Log.director.debug { "ignored (the Director is not written yet): ${signal::class.simpleName}" }
    }

    override fun scheduleReminder(inSeconds: Int, note: String, caller: Caller): Result<Long> =
        Result.failure(IllegalStateException("the Director is disabled"))

    override fun status() = DirectorStatus(
        state = DirectorState.DISABLED,
        until = null,
        reason = "the Director is not written yet",
        chapter = null,
        conversationId = null,
        contextTokens = 0,
        inbox = 0,
        droppedSignals = 0,
        turnId = null,
        operatorChannel = services.settings.get().config.director.channelMode,
        reminders = emptyList(),
    )

    override fun recentActivity(player: UUID, minutes: Int): String? = null
}
