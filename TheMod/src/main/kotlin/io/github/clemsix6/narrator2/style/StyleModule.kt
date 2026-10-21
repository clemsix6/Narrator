package io.github.clemsix6.narrator2.style

import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.event.AsyncSubscription
import io.github.clemsix6.narrator2.event.NarratorChannel
import io.github.clemsix6.narrator2.event.NarratorSpoke
import io.github.clemsix6.narrator2.event.PlayerJoined
import io.github.clemsix6.narrator2.memory.stats.Tics
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.runtime.ShutdownSequence
import io.github.clemsix6.narrator2.trace.Decisions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

private val log = Log.style

/** The bus subscriber of the watch, in the bus's stats. */
private const val SUBSCRIBER = "style"

/** Lines and arrivals waiting for the watch; one lost when it is full is counted by the bus, never waited for. */
private const val CAPACITY = 256

/** Longest the start's reading of the Journal may take. */
private const val SEED_TIMEOUT_MS = 10_000L

/** How often the names (the canon's nicknames above all) are read again. */
private const val NAMES_REFRESH_MS = 10 * 60_000L

/**
 * Creates the server's style watch (M6 §3.4), after the memory and before the line review and the Director, on the
 * server thread at `SERVER_STARTED`: a [StyleWatchImpl] fed by the bus (the Director's lines, the players' arrivals) and
 * seeded from the Journal in the coroutine `narrator2-style`, with the tone's monitor ([ToneWatch]) posting to the
 * Director. Without the Journal, [DisabledStyle], which says nothing and refuses nothing. Never waits.
 */
fun createStyle(services: NarratorServices): StyleWatch {
    if (!services.journal.available) {
        log.info("the style watch is off: the Journal is unavailable")
        return DisabledStyle
    }
    val tone = ToneWatch(
        services.clock,
        enabled = { services.settings.get().config.style.toneNotes },
        post = { services.director.post(it) },
        decisions = Decisions(services.trace, services.clock),
    )
    val watch = StyleWatchImpl(tone)
    follow(watch, services)
    return watch
}

/**
 * Subscribes [watch] to the bus first, then seeds it from the Journal and drains the subscription, in [services]'s
 * scope: a line said meanwhile is in both, and the watch takes it once (by its id). The names are read again every
 * [NAMES_REFRESH_MS].
 */
private fun follow(watch: StyleWatchImpl, services: NarratorServices) {
    val subscription = services.bus.subscribeAsync(SUBSCRIBER, ShutdownSequence.DIRECTOR_OWNER, CAPACITY) { event ->
        (event is NarratorSpoke && event.channel == NarratorChannel.NARRATOR && event.turnId != null && event.operator == null) || event is PlayerJoined
    }
    services.scope.launch(CoroutineName("narrator2-style")) {
        try {
            seed(watch, services)
            launch { refreshNames(watch, services) }
            drain(watch, subscription)
        } finally {
            subscription.close()
        }
    }
}

private suspend fun seed(watch: StyleWatchImpl, services: NarratorServices) {
    val start = System.nanoTime()
    try {
        val seed = services.journal.read(SEED_TIMEOUT_MS) { StyleWatchImpl.read(it) }
        watch.seed(seed)
        val ms = (System.nanoTime() - start) / 1_000_000
        log.info("the style watch is on: ${seed.lines.size} lines, ${seed.labels.size} labels and ${seed.names.size} names read in $ms ms")
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("the style watch starts empty: the Journal could not be read (${e.message ?: e.javaClass.simpleName})")
    }
}

private suspend fun drain(watch: StyleWatchImpl, subscription: AsyncSubscription) {
    for (envelope in subscription.events) {
        try {
            when (val event = envelope.event) {
                is NarratorSpoke -> watch.said(envelope.id, Instant.ofEpochMilli(envelope.ts), event.text)
                is PlayerJoined -> watch.named(listOf(event.player.name))
                else -> Unit
            }
        } catch (e: Exception) {
            log.error("style: event ${envelope.id} (${envelope.event.type}) not handled", e)
        }
    }
}

private suspend fun refreshNames(watch: StyleWatchImpl, services: NarratorServices) {
    while (true) {
        delay(NAMES_REFRESH_MS)
        try {
            watch.named(services.journal.read(SEED_TIMEOUT_MS) { Tics.names(it) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("style: the names could not be read again (${e.message ?: e.javaClass.simpleName}); next try in ${NAMES_REFRESH_MS / 60_000} minutes")
        }
    }
}
