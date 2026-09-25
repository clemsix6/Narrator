package io.github.clemsix6.narrator2

import io.github.clemsix6.narrator2.config.ConfigStore
import io.github.clemsix6.narrator2.config.NarratorConfig
import io.github.clemsix6.narrator2.config.Prompts
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.llm.ClaudeClient
import io.github.clemsix6.narrator2.trace.Transcripts
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Path

/**
 * Everything the Narrator runs while a server is up. Created when the server has started, closed
 * when it stops. Data lives in the world folder (`world/narrator2/`) so each world has its own
 * history and memory; configuration and prompt overrides live in `config/narrator2/`.
 */
class NarratorRuntime private constructor(
    val server: MinecraftServer,
    val config: NarratorConfig,
    val prompts: Prompts,
    apiKey: String?,
) : AutoCloseable {
    val dataDir: Path = server.getWorldPath(LevelResource.ROOT).resolve("narrator2").toAbsolutePath().normalize()

    /** Background work (model calls, journal flushes). Failures in one job never cancel the others. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("narrator2"))

    val transcripts = Transcripts(dataDir.resolve("logs").resolve("conversations"), config.logging.transcripts)
    val claude = ClaudeClient(config, apiKey)

    /** Cancels background work, including model calls still in flight. */
    override fun close() {
        scope.cancel()
        claude.close()
    }

    companion object {
        val configDir: Path = FabricLoader.getInstance().configDir.resolve("narrator2").toAbsolutePath().normalize()
        val configFile: Path = configDir.resolve(ConfigStore.FILE_NAME)
        val promptsDir: Path = configDir.resolve("prompts")

        @Volatile
        var current: NarratorRuntime? = null
            private set

        /**
         * Starts the Narrator for [server]. A broken configuration is logged and leaves the Narrator
         * off: the Minecraft server keeps running, and `/narrator reload` can try again.
         */
        fun start(server: MinecraftServer) {
            current = try {
                create(server)
            } catch (e: Exception) {
                Log.main.error("cannot start, the Narrator stays off: ${e.message}", e)
                null
            }
        }

        fun stop() {
            val runtime = current ?: return
            current = null
            runtime.close()
            Log.main.info("stopped")
        }

        /**
         * Starts a new instance from freshly loaded config and prompts, then closes the running one, if
         * any (cancelling its calls in flight). If loading fails, the running instance is kept and the
         * error propagates.
         */
        fun reload(server: MinecraftServer): NarratorRuntime {
            val fresh = create(server)
            val previous = current
            previous?.close()
            current = fresh
            Log.main.info(if (previous != null) "configuration and prompts reloaded" else "started by /narrator reload")
            return fresh
        }

        /** Where the LLMGW key comes from, for operators (never the key itself). */
        fun keySource(config: NarratorConfig): String = "environment variable ${config.gateway.apiKeyEnv}"

        /** What to do when there is no key. The environment is read when the server process starts. */
        fun missingKeyHint(config: NarratorConfig): String =
            "set the ${keySource(config)} and restart the server (the environment is read at startup)"

        private fun create(server: MinecraftServer): NarratorRuntime {
            val config = ConfigStore.load(configDir)
            Log.applyLevels(config.logging.levels)
            val prompts = Prompts.load(promptsDir)
            val apiKey = System.getenv(config.gateway.apiKeyEnv)?.trim()?.ifEmpty { null }
            val runtime = NarratorRuntime(server, config, prompts, apiKey)
            Log.main.info("ready · gateway ${config.gateway.baseUrl} · data in ${runtime.dataDir}")
            if (apiKey == null) Log.main.warn("no LLMGW key: ${missingKeyHint(config)}")
            return runtime
        }
    }
}
