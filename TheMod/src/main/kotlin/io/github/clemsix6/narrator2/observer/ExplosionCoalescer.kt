package io.github.clemsix6.narrator2.observer

import io.github.clemsix6.narrator2.event.ExplosionHappened
import io.github.clemsix6.narrator2.event.GameEvent

/**
 * Merges the explosions of one tick that go off close together (M1_M2 §7.2): a chain of TNT makes
 * dozens of explosions in the same tick, told as one. An explosion joins the first explosion of the tick
 * in the same dimension whose position is within [ZONE_BLOCKS]; the merged event keeps the first one's
 * position, source and interaction, the largest radius, the sums of blocks hit, every player nearby, the
 * first author (a player rather than a mob, when there is one), and is the Narrator's if any part is.
 *
 * [flush] is called at the end of every server tick and when the server stops. Server thread only.
 */
class ExplosionCoalescer(private val publish: (GameEvent) -> Unit) : Flushable {
    private val pending = ArrayList<ExplosionHappened>()

    fun add(explosion: ExplosionHappened) {
        val index = pending.indexOfFirst { close(it, explosion) }
        if (index < 0) pending += explosion else pending[index] = merge(pending[index], explosion)
    }

    /** Publishes the explosions of the tick, merged. */
    override fun flush() {
        if (pending.isEmpty()) return
        val due = pending.toList()
        pending.clear()
        due.forEach(publish)
    }

    companion object {
        /** Explosions of one tick within this distance of the first one, in blocks, are merged. */
        const val ZONE_BLOCKS = 16

        private fun close(first: ExplosionHappened, other: ExplosionHappened): Boolean {
            val a = first.pos
            val b = other.pos
            if (a.dim != b.dim) return false
            val dx = (a.x - b.x).toLong()
            val dy = (a.y - b.y).toLong()
            val dz = (a.z - b.z).toLong()
            return dx * dx + dy * dy + dz * dz <= ZONE_BLOCKS.toLong() * ZONE_BLOCKS
        }

        fun merge(first: ExplosionHappened, other: ExplosionHappened): ExplosionHappened = first.copy(
            source = first.source ?: other.source,
            author = if (first.author?.player == null && other.author?.player != null) other.author else first.author ?: other.author,
            radius = maxOf(first.radius, other.radius),
            blocksHit = first.blocksHit + other.blocksHit,
            playerBlocksHit = first.playerBlocksHit + other.playerBlocksHit,
            nearby = (first.nearby + other.nearby).distinctBy { it.uuid },
            narrator = first.narrator || other.narrator,
        )
    }
}
