package io.github.clemsix6.narrator2.reflex

import io.github.clemsix6.narrator2.runtime.NarratorServices

/**
 * Creates the reflexes of a server that starts: Jev when the settings have a client (read at each
 * call, `/narrator reload` may replace it), the heuristic otherwise (M1_M2 §11).
 *
 * Provisional body (T02): a neutral verdict, "not addressed to the Narrator, nothing worth a wake-up".
 * T10 replaces it.
 */
@Suppress("UNUSED_PARAMETER")
fun createReflexes(services: NarratorServices): Reflexes = NeutralReflexes

/** Reflexes that find nothing: no chat is addressed to the Narrator, no activity is worth a wake-up. */
private object NeutralReflexes : Reflexes {
    private const val SOURCE = "none"

    override suspend fun triageChat(input: ChatTriageInput): ChatVerdict = ChatVerdict(
        toNarrator = 0.0,
        tone = Tone.NEUTRAL_NO_TARGET,
        toneConfidence = 0.0,
        manipulation = 0.0,
        intent = ChatIntent.OTHER,
        personality = null,
        source = SOURCE,
        latencyMs = 0,
    )

    override suspend fun heartbeat(windowMinutes: Int, summary: List<String>, online: List<String>): HeartbeatVerdict =
        HeartbeatVerdict(worth = 0.0, hook = null, source = SOURCE, latencyMs = 0)
}
