package io.github.clemsix6.narrator2.tool.task

import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.NarratorTool

/**
 * Delegation to the workers (M1_M2 §9.6): `delegate`, `tasks`. `ToolCatalog` builds the family once
 * per conversation, with the services of the running server.
 *
 * Provisional body (T02): no tool yet. T20 writes them.
 */
@Suppress("UNUSED_PARAMETER")
fun taskTools(services: NarratorServices): List<NarratorTool<*>> = emptyList()
