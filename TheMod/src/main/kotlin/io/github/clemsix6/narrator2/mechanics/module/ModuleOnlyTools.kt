package io.github.clemsix6.narrator2.mechanics.module

import io.github.clemsix6.narrator2.tool.NarratorTool

/**
 * The tools of the modules alone (M5 §4.4, §9.1): `hud` (a game's action bar, once a second per player),
 * `place_blocks` (`Ground.place`, through the `BlockRestorer`, air only) and `creature_attack` (one of a deployment's
 * creatures goes for a player). Like the rules' own tools, no conversation ever sees them (`ToolCatalog` keeps them
 * from every role); only the engine's acts run them (`EngineActions`, wired by task M5-21). Empty until task M5-07
 * fills it.
 */
fun moduleOnlyTools(): List<NarratorTool<*>> = emptyList()
