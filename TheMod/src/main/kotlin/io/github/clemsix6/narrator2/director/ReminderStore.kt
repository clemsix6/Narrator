package io.github.clemsix6.narrator2.director

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.runOnServerThread
import net.minecraft.server.MinecraftServer

/**
 * Where the Director keeps its reminders from one run of the server to the next (M4 §15.1). The Director
 * reads it once, when it is created, and saves the whole state each time a reminder is set or given back.
 * It is part of its [GameView]: the world in production ([WorldReminderStore]), memory in the fakes
 * ([InMemoryReminderStore]), so that the Director's tests run without a server.
 */
interface ReminderStore {
    /** The reminders saved, [ReminderState.EMPTY] when there are none. */
    fun load(): ReminderState

    /** Saves [state], which replaces the one saved before. Any thread; never waits. */
    fun save(state: ReminderState)
}

/** A [ReminderStore] in memory: the fakes' own, and what a test hands to a second Director to play a restart. */
class InMemoryReminderStore(initial: ReminderState = ReminderState.EMPTY) : ReminderStore {
    /** The state last saved. */
    @Volatile
    var state: ReminderState = initial
        private set

    override fun load(): ReminderState = state

    override fun save(state: ReminderState) {
        this.state = state
    }
}

/**
 * The reminders in the world: the persistent global attachment `narrator2:reminders` ([ReminderState.TYPE]),
 * saved with the world at each of its saves. [load] runs on the server thread (the Director is created there,
 * while the server starts); [save] hands the state to the server thread without waiting, in the order of the
 * calls, and is dropped once the server has stopped (the world was saved already).
 */
class WorldReminderStore(private val server: MinecraftServer) : ReminderStore {
    override fun load(): ReminderState {
        check(server.isSameThread) { "the reminders are read on the server thread" }
        return server.globalAttachments().getAttachedOrElse(ReminderState.TYPE, ReminderState.EMPTY)
    }

    override fun save(state: ReminderState) {
        val handed = server.runOnServerThread { server.globalAttachments().setAttached(ReminderState.TYPE, state) }
        if (!handed) Log.director.warn("the reminders were not saved: the server has stopped (${state.reminders.size} waiting, next R-${state.nextId})")
    }
}
