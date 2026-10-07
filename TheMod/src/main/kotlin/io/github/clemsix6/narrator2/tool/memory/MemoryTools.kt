package io.github.clemsix6.narrator2.tool.memory

import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.NarratorTool

/**
 * The memory tools the Director shares with the Chronicler, the Dreamer and the Analyst (M3 §5):
 * `remember`, `resolve`, `note_interaction`, `recall`, `player_profile`. Empty until M3-09 writes them;
 * each will be available only when `ToolEnv.memoryAvailable`.
 */
fun memoryTools(services: NarratorServices): List<NarratorTool<*>> = emptyList()
