package io.github.clemsix6.narrator2.tool.command

import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.NarratorTool

/**
 * Command tools (M1_M2 §9.5): `run_command`, `command_help`. `ToolCatalog` builds the family once per
 * conversation, with the services of the running server.
 *
 * Provisional body (T02): no tool yet. T17 writes them.
 */
@Suppress("UNUSED_PARAMETER")
fun commandTools(services: NarratorServices): List<NarratorTool<*>> = emptyList()
