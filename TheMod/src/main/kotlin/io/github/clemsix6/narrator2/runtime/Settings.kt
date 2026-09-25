package io.github.clemsix6.narrator2.runtime

import io.github.clemsix6.narrator2.config.ConfigStore
import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.config.Prompts
import io.github.clemsix6.narrator2.config.UiTexts
import io.github.clemsix6.narrator2.llm.ClaudeClient
import io.github.clemsix6.narrator2.reflex.JevClient
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/**
 * Everything `/narrator reload` replaces: configuration, prompts, UI texts and the clients built from
 * them. Immutable; replaced as a whole. Components read [SettingsHolder.get] when they use a setting,
 * never keep a copy (except a Director chapter, whose prompt and tools are frozen).
 *
 * @property claude null when the LLMGW key is missing: the Director stays asleep.
 * @property jev null when Jev is disabled or its key is missing: the reflexes use their heuristic.
 * @property loadError why these are the default settings: the configuration could not be loaded when
 *   the server started. Null for settings loaded from the files.
 */
class Settings(
    val config: NarratorConfig,
    val prompts: Prompts,
    val ui: UiTexts,
    val claude: ClaudeClient?,
    val jev: JevClient?,
    val loadError: String? = null,
) {
    /** Closes the clients once their calls in flight are over: these settings were replaced. */
    fun closeWhenIdle() {
        claude?.closeWhenIdle()
        jev?.closeWhenIdle()
    }

    /** Closes the clients now: the server is stopping and their callers were cancelled. */
    fun close() {
        claude?.close()
        jev?.close()
    }
}

/** The settings in force. Any thread. */
class SettingsHolder(initial: Settings) {
    private val current = AtomicReference(initial)

    /** The settings in force; read it at each use, `/narrator reload` may replace them at any time. */
    fun get(): Settings = current.get()

    /**
     * Installs [next] at once and returns the settings it replaces. Their clients close once the calls
     * they already started are over (`closeWhenIdle`): nothing in flight is cut.
     */
    fun swap(next: Settings): Settings {
        val old = current.getAndSet(next)
        if (old !== next) old.closeWhenIdle()
        return old
    }
}

/** Builds [Settings] from the configuration folder (`config/narrator2/`). */
object SettingsLoader {
    /** Prompt overrides, under the configuration folder. */
    const val PROMPTS_DIR = "prompts"

    /** UI text overrides, under the configuration folder. */
    const val UI_DIR = "ui"

    /**
     * Loads and validates the configuration, the prompts and the UI texts, then builds the clients.
     * Keys come from the environment through [env], which tests replace. Reads files: never on the
     * server thread outside startup. Throws when anything is invalid; nothing is left open then.
     */
    fun load(configDir: Path, env: (String) -> String? = System::getenv): Settings {
        val config = ConfigStore.load(configDir)
        val prompts = Prompts.load(configDir.resolve(PROMPTS_DIR))
        val ui = UiTexts.load(config.language, configDir.resolve(UI_DIR))
        val key = env(config.gateway.apiKeyEnv)?.trim()?.ifEmpty { null }
        val claude = key?.let { ClaudeClient(config, it) }
        val jev = try {
            JevClient.create(config.jev, env)
        } catch (e: Exception) {
            claude?.close()
            throw e
        }
        return Settings(config, prompts, ui, claude, jev)
    }

    /**
     * The settings to run with when [load] failed at startup: default configuration, the jar's prompts
     * and texts, and no client, so the Director stays asleep until a `/narrator reload` succeeds while
     * the Journal and the Observer keep recording.
     */
    fun fallback(error: String): Settings {
        val config = NarratorConfig()
        return Settings(config, Prompts.bundled(), UiTexts.load(config.language), claude = null, jev = null, loadError = error)
    }

    /** Where the LLMGW key comes from, for operators (never the key itself). */
    fun keySource(config: NarratorConfig): String = "environment variable ${config.gateway.apiKeyEnv}"

    /** What to do when there is no key. The environment is read when the server process starts. */
    fun missingKeyHint(config: NarratorConfig): String =
        "set the ${keySource(config)} and restart the server (the environment is read at startup)"
}
