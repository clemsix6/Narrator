package io.github.clemsix6.narrator2.mechanics

import net.minecraft.server.MinecraftServer

/**
 * The engine's live state (M4 §3.2), in the world's persistent global attachment `narrator2:mechanics`
 * ([MechanicsState.TYPE]): the only place that reads or writes it. The world saves it at each of its saves,
 * so a crash brings the state and the world back to the same instant.
 *
 * [get] and [update] run on the server thread, where the parts of the engine run: never a wait, never the
 * database. [snapshot] is the state as the server thread last wrote it, readable from any thread (the
 * tools, the situation). The state is never changed in place: [update] replaces it with a new instance.
 *
 * [inMemory] keeps the state in memory instead, for the tests: each GameTest builds its parts on its own
 * store, so the tests running side by side never share the world's state (M4 §21.2).
 */
class StateStore private constructor(private val server: MinecraftServer?, initial: MechanicsState) {
    /** The state of a store [inMemory]; unused for the world's. */
    private var held: MechanicsState = initial

    /** Reads the world's state. Server thread. */
    constructor(server: MinecraftServer) : this(server, attached(server))

    /** True for the world's state; false for a store [inMemory]. */
    val inWorld: Boolean get() = server != null

    /** The state as the server thread last wrote it (or as it was read when the store was made). Any thread. */
    @Volatile
    var snapshot: MechanicsState = initial
        private set

    /** The state now. Server thread (any thread [inMemory]). */
    @Synchronized
    fun get(): MechanicsState = if (server != null) attached(server) else held

    /**
     * Replaces the state with [change] of it, and returns the new one; the world saves it at its next save.
     * [change] must not have side effects: it runs on the state as it is now. Server thread (any thread
     * [inMemory]).
     */
    @Synchronized
    fun update(change: (MechanicsState) -> MechanicsState): MechanicsState {
        val current = get()
        val next = change(current)
        if (next === current) return current
        if (server != null) server.globalAttachments().setAttached(MechanicsState.TYPE, next) else held = next
        snapshot = next
        return next
    }

    companion object {
        /** A store without world, starting from [initial]: for tests, never shared with the world's state. */
        fun inMemory(initial: MechanicsState = MechanicsState.EMPTY): StateStore = StateStore(null, initial)

        /** The world's state: what is attached, or the empty state for a world that never had one. Server thread. */
        private fun attached(server: MinecraftServer): MechanicsState {
            check(server.isSameThread) { "the mechanics' state is read and written on the server thread only (M4 §3.2)" }
            return server.globalAttachments().getAttachedOrElse(MechanicsState.TYPE, MechanicsState.EMPTY)
        }
    }
}
