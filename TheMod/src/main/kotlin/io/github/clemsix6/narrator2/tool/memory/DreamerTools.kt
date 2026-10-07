package io.github.clemsix6.narrator2.tool.memory

import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.NarratorTool

/**
 * The Dreamer's own tools (M3 §8.3): `set_trait`, `retire_trait`, `set_skill`, `set_relation`,
 * `write_sheet`, `dream_hint`, `set_gag`, `write_review`, `write_chronicle`, `write_grimoire`,
 * `merge_memories`. Never offered to the Director. Empty until M3-11 writes them.
 */
fun dreamerTools(services: NarratorServices): List<NarratorTool<*>> = emptyList()
