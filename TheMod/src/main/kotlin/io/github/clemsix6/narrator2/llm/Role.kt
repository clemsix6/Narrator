package io.github.clemsix6.narrator2.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Who a conversation with Claude works for. Each role has its own model, effort, token budget and
 * timeouts in the configuration (`roles.<id>`).
 */
@Serializable
enum class Role {
    @SerialName("director") DIRECTOR,
    @SerialName("analyst") ANALYST,
    @SerialName("scout") SCOUT,
    @SerialName("forger") FORGER,
    @SerialName("storyteller") STORYTELLER,
    @SerialName("chronicler") CHRONICLER,
    @SerialName("dreamer") DREAMER,
    ;

    /** Identifier used in the configuration, logs and conversation ids, e.g. `director`. */
    val id: String get() = name.lowercase()

    companion object {
        fun of(id: String): Role? = entries.firstOrNull { it.id == id }
    }
}
