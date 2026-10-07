package io.github.clemsix6.narrator2.tool.memory

import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.NarratorTool

/**
 * The Chronicler's own tools (M3 §7.5): `record_episode`, `note_to_director`, `write_chapter`,
 * `since_last`, `use_canon`, `mark_used`. Never offered to the Director. Empty until M3-10 writes them.
 */
fun chroniclerTools(services: NarratorServices): List<NarratorTool<*>> = emptyList()
