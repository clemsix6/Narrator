package io.github.clemsix6.narrator2.agent

import io.github.clemsix6.narrator2.runtime.NarratorServices

/**
 * Creates the workers of a server that starts (M1_M2 §13): the Analyst, with the tools of
 * `ToolCatalog.toolSetFor`. Their coroutines run in `services.scope`, which the runtime cancels when
 * the server stops; they take no task once `services.phase` is no longer `RUNNING`.
 */
fun createWorkers(services: NarratorServices): WorkersControl = Workers(services)
