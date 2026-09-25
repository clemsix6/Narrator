package io.github.clemsix6.narrator2.tool.pacing

import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.NarratorTool

/**
 * Pacing tools (M1_M2 §9.5): `remind_me`. `ToolCatalog` builds the family once per conversation, with
 * the services of the running server.
 *
 * Provisional body (T02): no tool yet. T17 writes it.
 */
@Suppress("UNUSED_PARAMETER")
fun pacingTools(services: NarratorServices): List<NarratorTool<*>> = emptyList()
