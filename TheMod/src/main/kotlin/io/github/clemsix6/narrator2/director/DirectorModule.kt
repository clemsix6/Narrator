package io.github.clemsix6.narrator2.director

import io.github.clemsix6.narrator2.command.narratorLine
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.core.onServerThread
import io.github.clemsix6.narrator2.llm.Role
import io.github.clemsix6.narrator2.runtime.NarratorServices
import io.github.clemsix6.narrator2.tool.ToolCatalog
import net.minecraft.network.chat.Component
import java.util.UUID

/**
 * Creates the Director of a server that starts (M1_M2 §12), last of the server's components: every
 * other service is set when it runs. Its coroutine runs in `services.scope`, which the runtime cancels
 * at `SERVER_STOPPING`; the bus subscriptions that feed it (salience, thinking indicator) use the owner
 * `ShutdownSequence.DIRECTOR_OWNER`, closed at the same moment. `/narrator reload` reaches it as a
 * `DirectorSignal.SettingsChanged`. Each chapter gets the tools wired when it opens
 * (`ToolCatalog.toolSetFor`): the model only sees tools that work.
 */
fun createDirector(services: NarratorServices): DirectorControl {
    val director = Director(services, ServerGameView(services), { env -> ToolCatalog.toolSetFor(Role.DIRECTOR, env, services) })
    director.start()
    return director
}

/** The game as the Director sees it: the running server, reached through its thread only. */
private class ServerGameView(private val services: NarratorServices) : GameView {
    override suspend fun worldTime(): WorldTime {
        val server = services.server
        return server.onServerThread(WORLD_TIMEOUT_MS) {
            val level = server.overworld()
            WorldTime.of(level.defaultClockTime, level.isRaining, level.isThundering)
        }
    }

    override fun actionbar(players: Collection<UUID>, text: String) = overlay(players, Component.literal(text))

    override fun clearActionbar(players: Collection<UUID>) = overlay(players, Component.empty())

    override fun tellOperators(text: String) {
        Log.director.warn("told the operators: $text")
        val operators = services.players.online.filter { it.isOp }.map { it.ref.uuid }
        if (operators.isEmpty()) return
        val server = services.server
        server.execute { operators.forEach { server.playerList.getPlayer(it)?.sendSystemMessage(narratorLine(text)) } }
    }

    private fun overlay(players: Collection<UUID>, message: Component) {
        if (players.isEmpty()) return
        val server = services.server
        val targets = players.toList()
        server.execute { targets.forEach { server.playerList.getPlayer(it)?.sendOverlayMessage(message) } }
    }

    private companion object {
        const val WORLD_TIMEOUT_MS = 1_000L
    }
}
