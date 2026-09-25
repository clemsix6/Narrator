package io.github.clemsix6.narrator2.tool.sql

import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.NarratorTool

/**
 * The Journal in SQL (M1_M2 §9.5): `sql`, whose description is the schema generated from the database
 * (§6.5). `ToolCatalog` builds the family once per conversation, with the services of the running
 * server, whose Journal the schema is read from.
 *
 * Provisional body (T02): no tool yet. T17 writes it.
 */
@Suppress("UNUSED_PARAMETER")
fun sqlTools(services: NarratorServices): List<NarratorTool<*>> = emptyList()
