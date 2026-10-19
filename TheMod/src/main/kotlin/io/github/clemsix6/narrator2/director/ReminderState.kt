package io.github.clemsix6.narrator2.director

import com.mojang.serialization.Codec
import io.github.clemsix6.narrator2.core.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry
import net.fabricmc.fabric.api.attachment.v1.AttachmentType
import net.minecraft.resources.Identifier

/**
 * The Director's reminders (`remind_me`), saved with the world so that a restart keeps them (M4 §15.1): the
 * persistent global attachment `narrator2:reminders`, one JSON text like `MechanicsState`. The Director
 * reads it through a `ReminderStore` of its `GameView` (M4-10), never directly: its tests run without a
 * server.
 *
 * @property nextId the number of the next reminder, `R-<n>`: a restart never reuses one.
 * @property reminders the reminders waiting, soonest first.
 */
@Serializable
data class ReminderState(
    @SerialName("next_id") val nextId: Long = 1,
    val reminders: List<SavedReminder> = emptyList(),
) {
    companion object {
        val EMPTY = ReminderState()

        private val log = Log.director

        private val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

        fun encode(state: ReminderState): String = JSON.encodeToString(serializer(), state)

        /**
         * The state of [text], each reminder decoded on its own; never fails. A reminder that cannot be read is
         * logged and dropped; a text that cannot be read at all gives no reminder, numbered from 1.
         */
        fun decode(text: String): ReminderState {
            val raw = try {
                JSON.decodeFromString(Raw.serializer(), text)
            } catch (e: IllegalArgumentException) { // SerializationException too
                log.error("the saved reminders cannot be read and are dropped: ${firstLine(e)}; saved text: ${text.take(RAW_LOG_MAX)}")
                return EMPTY
            }
            val reminders = raw.reminders.mapNotNull { element ->
                try {
                    JSON.decodeFromJsonElement(SavedReminder.serializer(), element)
                } catch (e: IllegalArgumentException) {
                    log.error("a saved reminder cannot be read and is dropped: ${firstLine(e)}; saved: ${element.toString().take(RAW_LOG_MAX)}")
                    null
                }
            }
            return ReminderState(raw.nextId, reminders)
        }

        private fun firstLine(e: Exception): String = e.message?.lineSequence()?.first()?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName

        private const val RAW_LOG_MAX = 2_000

        /** One JSON string in the world. */
        val CODEC: Codec<ReminderState> = Codec.STRING.xmap(::decode, ::encode)

        /**
         * The persistent global attachment `narrator2:reminders`, saved with the world. Touched by
         * `registerMechanicsHooks`, at mod initialization: a persistent type must exist before the first world loads.
         * Lazy, so that the codec can be used without Fabric's registry (unit tests).
         */
        val TYPE: AttachmentType<ReminderState> by lazy {
            AttachmentRegistry.create(Identifier.fromNamespaceAndPath("narrator2", "reminders")) { builder -> builder.persistent(CODEC) }
        }
    }

    /** The state as saved, its reminders still undecoded. */
    @Serializable
    private class Raw(@SerialName("next_id") val nextId: Long = 1, val reminders: List<JsonElement> = emptyList())
}

/**
 * A reminder waiting (M4 §15.1).
 *
 * @property id its number, `R-<id>`.
 * @property note what the Director wrote for itself.
 * @property setAt when it was set, and [at] when it is due: epoch milliseconds.
 * @property turnId the turn that set it.
 */
@Serializable
data class SavedReminder(
    val id: Long,
    val note: String,
    @SerialName("set_at") val setAt: Long,
    val at: Long,
    @SerialName("turn_id") val turnId: Long? = null,
)
