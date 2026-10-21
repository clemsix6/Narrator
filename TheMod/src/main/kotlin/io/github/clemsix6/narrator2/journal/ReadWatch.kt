package io.github.clemsix6.narrator2.journal

import kotlin.coroutines.CoroutineContext

/**
 * Follows the Journal reads made in a coroutine, from its context: [DuckJournal.read] asks [start] once the query has
 * its connection and is about to run, and calls [end] once it has ended and the connection is back in the pool. A read
 * that times out runs on after its caller gave up (until its statement's own limit): only [end] says when it really
 * stops. The dashboard keeps its turn of reads with it (`PageContext.inTurn`). A read that never gets a connection (an
 * unavailable Journal, a wait that timed out) calls neither.
 */
interface ReadWatch : CoroutineContext.Element {
    /** Whether the query may run now; false when nobody waits for it any more, and then it never runs. A read thread. */
    fun start(): Boolean

    /** The query [start] let run has ended, its connection given back. A read thread. */
    fun end()

    override val key: CoroutineContext.Key<*> get() = Key

    companion object Key : CoroutineContext.Key<ReadWatch>
}
