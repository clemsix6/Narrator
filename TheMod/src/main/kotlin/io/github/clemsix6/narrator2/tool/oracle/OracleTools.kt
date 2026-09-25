package io.github.clemsix6.narrator2.tool.oracle

import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.NarratorTool

/**
 * The Oracle's tools, which read the world (M1_M2 §9.2): `list_players`, `inspect_player`,
 * `scan_area`, `locate`, `player_stats`, `area_info`. `ToolCatalog` builds the family once per
 * conversation, with the services of the running server.
 *
 * Provisional body (T02): no tool yet. T13 writes them.
 */
@Suppress("UNUSED_PARAMETER")
fun oracleTools(services: NarratorServices): List<NarratorTool<*>> = emptyList()
