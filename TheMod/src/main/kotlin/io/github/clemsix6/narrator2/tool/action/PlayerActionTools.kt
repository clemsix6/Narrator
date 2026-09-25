package io.github.clemsix6.narrator2.tool.action

import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.NarratorTool

/**
 * Actions on players (M1_M2 §9.3): `apply_effect`, `remove_effect`, `set_attribute`,
 * `reset_attribute`, `teleport`, `launch`, `give_item`, `take_item`. `ToolCatalog` builds the family
 * once per conversation, with the services of the running server.
 *
 * Provisional body (T02): no tool yet. T15 writes them.
 */
@Suppress("UNUSED_PARAMETER")
fun playerActionTools(services: NarratorServices): List<NarratorTool<*>> = emptyList()
