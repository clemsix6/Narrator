package io.github.clemsix6.narrator2.testing

import io.github.clemsix6.narrator2.config.OperatorChannelMode
import io.github.clemsix6.narrator2.director.DirectorControl
import io.github.clemsix6.narrator2.director.DirectorSignal
import io.github.clemsix6.narrator2.director.DirectorState
import io.github.clemsix6.narrator2.director.DirectorStatus
import io.github.clemsix6.narrator2.director.PendingReminder
import io.github.clemsix6.narrator2.tool.Caller
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * A Director for tests: it records the signals posted and the reminders scheduled (at most
 * [maxReminders] pending), and answers [activity] for `recentActivity`. Times come from [clock].
 */
class FakeDirector(
    private val clock: Clock = TestClock(),
    private val maxReminders: Int = 5,
) : DirectorControl {
    val signals = CopyOnWriteArrayList<DirectorSignal>()
    val reminders = CopyOnWriteArrayList<PendingReminder>()
    val activity = ConcurrentHashMap<UUID, String>()

    @Volatile
    var state: DirectorState = DirectorState.IDLE

    private val ids = AtomicLong(1)

    override fun post(signal: DirectorSignal) {
        signals += signal
    }

    override fun scheduleReminder(inSeconds: Int, note: String, caller: Caller): Result<Long> {
        if (reminders.size >= maxReminders) return Result.failure(IllegalStateException("at most $maxReminders reminders may wait"))
        val id = ids.getAndIncrement()
        reminders += PendingReminder(id, note, clock.millis() + inSeconds * 1_000L)
        return Result.success(id)
    }

    override fun status() = DirectorStatus(
        state = state,
        until = null,
        reason = null,
        chapter = null,
        conversationId = null,
        contextTokens = 0,
        inbox = 0,
        droppedSignals = 0,
        turnId = null,
        operatorChannel = OperatorChannelMode.SYSTEM_MESSAGE,
        reminders = reminders.toList(),
    )

    override fun recentActivity(player: UUID, minutes: Int): String? = activity[player]
}
