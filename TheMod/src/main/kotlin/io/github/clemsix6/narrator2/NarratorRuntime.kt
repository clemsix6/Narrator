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

    override fun close() {
        scope.cancel()
        claude.close()
    }

    companion object {
        val configDir: Path = FabricLoader.getInstance().configDir.resolve("narrator2")

        @Volatile
        var current: NarratorRuntime? = null
            private set

        fun start(server: MinecraftServer) {
            val config = ConfigStore.load(configDir)
            Log.applyLevels(config.logging.levels)
            val prompts = Prompts.load(configDir.resolve("prompts"))
            val runtime = NarratorRuntime(server, config, prompts)
            current = runtime
            Log.main.info("prêt · données dans ${runtime.dataDir}")
            if (!runtime.claude.isConfigured) {
                Log.main.warn(
                    "aucune clé LLMGW : renseigne gateway.apiKey dans ${configDir.resolve("narrator2.json")} " +
                        "ou la variable d'environnement ${config.gateway.apiKeyEnv}",
                )
            }
        }

        fun stop() {
            current?.close()
            current = null
            Log.main.info("arrêté")
        }

        /** Closes the running instance and starts a new one with freshly loaded config and prompts. */
        fun reload(server: MinecraftServer) {
            stop()
            start(server)
        }
    }
}
