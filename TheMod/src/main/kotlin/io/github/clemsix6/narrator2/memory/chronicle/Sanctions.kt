package io.github.clemsix6.narrator2.memory.chronicle

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.ModelText
import io.github.clemsix6.narrator2.event.PlayerRef
import io.github.clemsix6.narrator2.memory.Added
import io.github.clemsix6.narrator2.memory.Author
import io.github.clemsix6.narrator2.memory.InteractionDraft
import io.github.clemsix6.narrator2.memory.InteractionKind
import io.github.clemsix6.narrator2.memory.MemoryLimits
import io.github.clemsix6.narrator2.memory.MemoryRejected
import io.github.clemsix6.narrator2.memory.Source
import io.github.clemsix6.narrator2.memory.instant
import io.github.clemsix6.narrator2.memory.longArray
import io.github.clemsix6.narrator2.memory.longOrNull
import io.github.clemsix6.narrator2.memory.longs
import io.github.clemsix6.narrator2.memory.query
import io.github.clemsix6.narrator2.runtime.NarratorServices
import java.sql.Connection
import java.time.Instant
import java.util.UUID

/**
 * Punishments and benefits (M3 §6.5): none escapes the players' history with the Narrator.
 *
 * - A **punishment** is a (turn, player) with at least one `guard allowed` decision: the guards' episode
 *   (T09a). Its calls are the successful world calls of that turn that targeted the player.
 * - A **benefit** is a successful `give_item` (a gift), or `apply_effect`, `set_attribute` or `favor` (a
 *   blessing: a pardon, a grace or a joker, M4 §7.1), on a player the turn did not punish. The calls of one
 *   turn are one benefit per player and kind. A favor that took an offering (M4 §7.2) is still a benefit:
 *   the player's `offering` interaction is their side of the exchange, not the Narrator's.
 *
 * After each batch and nap, [cover] writes an interaction for each one of the window that no interaction
 * links yet (`interactions.tool_calls`; an `offering` that links a favor's call does not count): kind
 * `punishment`, `blessing` or `gift`, valence 0, a summary made of its first call, source [Source.ENGINE].
 * The Chronicler has the first hand, to note them with their reason; the code guarantees the coverage. The
 * exchange log marks their calls `(hostile)` and `(benefit)` ([find]).
 *
 * The engine's acts (a rule's firing, a challenge's stakes) are no `tool_calls`: the engine writes their
 * interactions itself (M4 §5.3, §8.6), and none is found here.
 */
internal class Sanctions(private val services: NarratorServices) {
    private val log = Log.memory

    /** A successful world call of the Director. */
    data class Call(val id: Long, val ts: Instant, val tool: String, val result: String?)

    /** A punishment or a benefit: [kind] is `punishment`, `blessing` or `gift`; [player] its target. */
    data class Sanction(val kind: InteractionKind, val player: PlayerRef, val turnId: Long?, val calls: List<Call>)

    /**
     * Writes an interaction for each punishment and benefit of `]from, to]` that no interaction links yet,
     * as the engine, for the job [runId]; returns how many it wrote. A write the store refuses is logged
     * and skipped.
     */
    suspend fun cover(chapter: String, from: Instant, to: Instant, runId: Long): Int {
        services.journal.barrier()
        val (found, linked) = services.journal.read(READ_TIMEOUT_MS) { connection ->
            val found = find(connection, chapter, from, to)
            val ids = found.flatMap { sanction -> sanction.calls.map { it.id } }
            found to linked(connection, ids)
        }
        val store = services.memory.store
        var written = 0
        for (sanction in found.filter { sanction -> sanction.calls.none { it.id in linked } }) {
            val draft = InteractionDraft(
                player = sanction.player.uuid,
                kind = sanction.kind,
                valence = 0,
                summary = summary(sanction),
                toolCalls = sanction.calls.map { it.id },
            )
            try {
                val added = store.addInteraction(draft, Author(Source.ENGINE, runId = runId))
                if (added is Added.New) written++
            } catch (e: MemoryRejected) {
                log.warn("run $runId: the ${sanction.kind.id} of ${sanction.player.name} (t${sanction.calls.first().id}) is not covered: ${e.message}")
            }
        }
        if (found.isNotEmpty()) log.info("run $runId: ${found.size} punishments and benefits in the window, $written covered by the code")
        return written
    }

    /**
     * The punishments and benefits of the Director's [chapter] (its conversation id) in `]from, to]`, in
     * the order of their first call, read on [connection]. The caller passes a barrier first when the calls
     * are recent.
     */
    fun find(connection: Connection, chapter: String, from: Instant, to: Instant): List<Sanction> {
        val calls = connection.query(CALLS_SQL, chapter, from, to) {
            CallTarget(Call(getLong(1), instant(2), getString(4), getString(5)), longOrNull(3), getString(6))
        }
        if (calls.isEmpty()) return emptyList()
        val allowed = connection.query(ALLOWED_SQL, chapter) { longOrNull(1) to getString(2).lowercase() }.toSet()
        val players = players(connection, calls.map { it.target }.toSet())
        val groups = LinkedHashMap<Triple<InteractionKind, UUID, Long?>, MutableList<Call>>()
        for (call in calls) {
            val player = players[call.target.lowercase()] ?: continue
            val kind = when {
                (call.turnId to call.target.lowercase()) in allowed -> InteractionKind.PUNISHMENT
                call.call.tool == GIVE_ITEM -> InteractionKind.GIFT
                call.call.tool in BLESSING_TOOLS -> InteractionKind.BLESSING
                else -> continue
            }
            groups.getOrPut(Triple(kind, player.uuid, call.turnId)) { mutableListOf() } += call.call
        }
        val refs = players.values.associateBy { it.uuid }
        return groups.map { (key, list) -> Sanction(key.first, refs.getValue(key.second), key.third, list.take(MAX_CALLS)) }
    }

    /**
     * The calls among [ids] that an interaction already links: one that notes the Narrator's act. An
     * `offering` is the player's act, even when it links the favor that took it (M4 §7.2): that favor is
     * still to cover.
     */
    private fun linked(connection: Connection, ids: List<Long>): Set<Long> {
        if (ids.isEmpty()) return emptySet()
        return connection.query(LINKED_SQL, longArray(ids), InteractionKind.OFFERING.id) { longs(1) }.flatten().toSet()
    }

    /** The players named [names] (targets of tool calls), by lowercase name: their current name, else a former one. */
    private fun players(connection: Connection, names: Set<String>): Map<String, PlayerRef> = names.mapNotNull { name ->
        connection.query(PLAYER_SQL, name, name, name) { PlayerRef(UUID.fromString(getString(1)), getString(2)) }.firstOrNull()
            ?.let { name.lowercase() to it }
    }.toMap()

    private class CallTarget(val call: Call, val turnId: Long?, val target: String)

    companion object {
        /**
         * Tools whose success on a player, without a `guard allowed` in the turn, is a blessing: an effect, an
         * attribute, or a favor of the mechanics (a pardon, a grace, a joker; M4 §7.1).
         */
        val BLESSING_TOOLS = setOf("apply_effect", "set_attribute", "favor")

        /** The tool whose success on a player, without a `guard allowed` in the turn, is a gift. */
        const val GIVE_ITEM = "give_item"

        /** Calls linked to one interaction at most (the store's limit of evidence). */
        private const val MAX_CALLS = 20

        private const val READ_TIMEOUT_MS = 10_000L

        /** The Director's successful world calls on players in a window, one row per target. */
        private const val CALLS_SQL = "SELECT id, ts, turn_id, tool, result, unnest(targets) AS target FROM tool_calls " +
            "WHERE conversation_id = ? AND role = 'director' AND status = 'ok' AND effect = 'world' AND ts > ? AND ts <= ? " +
            "ORDER BY ts, id"

        /** The interactions that note the Narrator's act of some of the calls (`list_has_any`), but the players' offerings. */
        private const val LINKED_SQL = "SELECT tool_calls FROM interactions WHERE list_has_any(tool_calls, ?) AND kind <> ?"

        /** The guards' episodes of a chapter: (turn, player name) of each hostile action allowed. */
        private const val ALLOWED_SQL = "SELECT turn_id, subject FROM engine_decisions " +
            "WHERE kind = 'guard' AND decision = 'allowed' AND conversation_id = ? AND subject IS NOT NULL"

        private const val PLAYER_SQL = "SELECT player_uuid::VARCHAR, name FROM players WHERE lower(name) = lower(?) " +
            "OR player_uuid IN (SELECT player_uuid FROM player_names WHERE lower(name) = lower(?)) " +
            "ORDER BY lower(name) = lower(?) DESC, last_seen DESC LIMIT 1"

        /**
         * The engine's summary of an uncovered [sanction], within the store's budget: its first call and what
         * it answered, in quotes (`apply_effect « slowness II on Bartok for 1m » (t88), and 1 more call`).
         * English, as every text of the code; the result stays between « » so that the voice check reads it
         * as quoted words.
         */
        fun summary(sanction: Sanction): String {
            val first = sanction.calls.first()
            val more = sanction.calls.size - 1
            val tail = " (t${first.id})" + if (more > 0) ", and $more more call${if (more > 1) "s" else ""}" else ""
            val said = first.result?.lineSequence()?.firstOrNull()?.removePrefix("ok:")?.trim().orEmpty()
                .replace('«', '"').replace('»', '"').let(ModelText::escape)
            if (said.isEmpty()) return ModelText.cut(first.tool + tail, MemoryLimits.INTERACTION_SUMMARY)
            val room = MemoryLimits.INTERACTION_SUMMARY - first.tool.length - tail.length - 5 // « » and spaces
            return if (room < 10) ModelText.cut(first.tool + tail, MemoryLimits.INTERACTION_SUMMARY) else "${first.tool} « ${ModelText.cut(said, room)} »$tail"
        }
    }
}
