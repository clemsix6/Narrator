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
 * history and memory; configuration and prompts live in `config/narrator2/`.
 */
class NarratorRuntime private constructor(
    val server: MinecraftServer,
    val config: NarratorConfig,
    val prompts: Prompts,
) : AutoCloseable {
    val dataDir: Path = server.getWorldPath(LevelResource.ROOT).resolve("narrator2").toAbsolutePath().normalize()

    /** Background work (model calls, journal flushes). Failures in one job never cancel the others. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("narrator2"))

    val transcripts = Transcripts(dataDir.resolve("logs").resolve("conversations"), config.logging.transcripts)
    val claude = ClaudeClient(config, transcripts)

    /** Cancels background work, including model calls still in flight. */
    override fun close() {
        scope.cancel()
        claude.close()
    }

    companion object {
        val configDir: Path = FabricLoader.getInstance().configDir.resolve("narrator2")
        val configFile: Path = configDir.resolve(ConfigStore.FILE_NAME)

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
         * Starts a new instance from freshly loaded config and prompts, then closes the running one
         * (cancelling its calls in flight). If loading fails, the running instance is kept and the
         * error propagates.
         */
        fun reload(server: MinecraftServer): NarratorRuntime {
            val fresh = create(server)
            current?.close()
            current = fresh
            Log.main.info("configuration and prompts reloaded")
            return fresh
        }

        private fun create(server: MinecraftServer): NarratorRuntime {
            val config = ConfigStore.load(configDir)
            val prompts = Prompts.load(configDir.resolve("prompts"))
            Log.applyLevels(config.logging.levels)
            val runtime = NarratorRuntime(server, config, prompts)
            Log.main.info("ready · gateway ${config.gateway.baseUrl} · data in ${runtime.dataDir}")
            if (!runtime.claude.isConfigured) {
                Log.main.warn(
                    "no LLMGW key: set gateway.apiKey in $configFile " +
                        "or the environment variable ${config.gateway.apiKeyEnv}",
                )
            }
            return runtime
        }
    }
}
