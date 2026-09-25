package io.github.clemsix6.narrator2.testing

import io.github.clemsix6.narrator2.reflex.ChatIntent
import io.github.clemsix6.narrator2.reflex.ChatTriageInput
import io.github.clemsix6.narrator2.reflex.ChatVerdict
import io.github.clemsix6.narrator2.reflex.HeartbeatVerdict
import io.github.clemsix6.narrator2.reflex.Reflexes
import io.github.clemsix6.narrator2.reflex.Tone
import java.util.concurrent.CopyOnWriteArrayList

/** Reflexes for tests: they answer what [chat] and [heartbeat] say, and record what they were asked. */
class FakeReflexes(
    @Volatile var chat: (ChatTriageInput) -> ChatVerdict = { verdict(toNarrator = 0.0) },
    @Volatile var heartbeat: (List<String>) -> HeartbeatVerdict = { HeartbeatVerdict(worth = 0.0, hook = null, source = "fake", latencyMs = 0) },
) : Reflexes {
    val chatInputs = CopyOnWriteArrayList<ChatTriageInput>()
    val heartbeatSummaries = CopyOnWriteArrayList<List<String>>()

    override suspend fun triageChat(input: ChatTriageInput): ChatVerdict {
        chatInputs += input
        return chat(input)
    }

    override suspend fun heartbeat(windowMinutes: Int, summary: List<String>, online: List<String>): HeartbeatVerdict {
        heartbeatSummaries += summary
        return heartbeat(summary)
    }

    companion object {
        /** A chat verdict with defaults for everything but what the test cares about. */
        fun verdict(
            toNarrator: Double,
            tone: Tone = Tone.NEUTRAL_NO_TARGET,
            intent: ChatIntent = ChatIntent.OTHER,
            manipulation: Double = 0.0,
        ) = ChatVerdict(toNarrator, tone, toneConfidence = 0.9, manipulation, intent, personality = null, source = "fake", latencyMs = 0)
    }
}
