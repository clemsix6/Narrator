package io.github.clemsix6.narrator2.tool.mechanics

import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.NarratorTool

/**
 * The duels (M4 §5.5, §12): `propose_duel` (arguments `DuelSpec`), a `MechanicsTool` of effect `WORLD`; nobody
 * is ever made to fight, nobody dies in a duel. Filled by M4-19; empty until then.
 */
fun duelTools(services: NarratorServices): List<NarratorTool<*>> = emptyList()
