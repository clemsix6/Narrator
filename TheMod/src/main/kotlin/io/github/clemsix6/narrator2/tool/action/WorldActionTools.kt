package io.github.clemsix6.narrator2.tool.action

import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.NarratorTool

/**
 * Actions on the world (M1_M2 §9.4): `spawn`, `lightning`, `explosion`, `fireworks`, `particles`,
 * `set_weather`, `set_time`. `ToolCatalog` builds the family once per conversation, with the services
 * of the running server.
 *
 * Provisional body (T02): no tool yet. T16 writes them.
 */
@Suppress("UNUSED_PARAMETER")
fun worldActionTools(services: NarratorServices): List<NarratorTool<*>> = emptyList()
