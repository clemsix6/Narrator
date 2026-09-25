package io.github.clemsix6.narrator2.tool.speech

import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.NarratorTool

/**
 * Speech and staging tools (M1_M2 §9.1): `say`, `show_title`, `actionbar`, `play_sound`, `give_book`.
 * `ToolCatalog` builds the family once per conversation, with the services of the running server.
 *
 * Provisional body (T02): no tool yet. T14 writes them.
 */
@Suppress("UNUSED_PARAMETER")
fun speechTools(services: NarratorServices): List<NarratorTool<*>> = emptyList()
