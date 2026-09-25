package io.github.clemsix6.narrator2.runtime

import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.config.Prompts
import io.github.clemsix6.narrator2.config.UiTexts
import io.github.clemsix6.narrator2.llm.ClaudeClient
import java.util.concurrent.atomic.AtomicReference

/**
 * Everything `/narrator reload` replaces: configuration, prompts, UI texts and the clients built from
 * them. Immutable; replaced as a whole. Components read [SettingsHolder.get] when they use a setting,
 * never keep a copy (except a Director chapter, whose prompt and tools are frozen).
 *
 * @property claude null when the LLMGW key is missing: the Director stays asleep.
 */
class Settings(
    val config: NarratorConfig,
    val prompts: Prompts,
    val ui: UiTexts,
    val claude: ClaudeClient?,
)

/** The settings in force. Any thread. */
class SettingsHolder(initial: Settings) {
    private val current = AtomicReference(initial)

    fun get(): Settings = current.get()

    /** Installs [next] and returns the settings it replaces, whose clients the caller closes once idle. */
    fun swap(next: Settings): Settings = current.getAndSet(next)
}
