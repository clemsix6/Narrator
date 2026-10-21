package io.github.clemsix6.narrator2.command

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import io.github.clemsix6.narrator2.web.Dashboard
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import java.net.URI

/**
 * `/narrator dashboard` and `/narrator dashboard reset` (M6 §13): the dashboard's login link, or why there is no
 * dashboard; a new token, which signs every browser out.
 *
 * The link carries the token. To a player it comes as a click (`ClickEvent.OpenUrl`) on a text that does not show it:
 * the client's log keeps the text of the chat, not its clicks. A console has no click: it gets the link written out,
 * and Minecraft writes the console's replies to `logs/latest.log`, so the reply advises a `reset` once signed in. The
 * mod never logs the token.
 */
object DashboardCommands {
    private val log = Log.command

    /** Adds `dashboard` and `dashboard reset` under `/narrator`. */
    fun attach(root: LiteralArgumentBuilder<CommandSourceStack>) {
        root.then(
            Commands.literal("dashboard")
                .executes { show(it.source) }
                .then(Commands.literal("reset").executes { reset(it.source) }),
        )
    }

    private fun show(source: CommandSourceStack): Int {
        val runtime = runningNarrator(source) ?: return 0
        log.info("${source.textName}: narrator dashboard")
        lines(runtime.dashboard, source.isPlayer).forEach { line -> source.sendSuccess({ line }, false) }
        return if (runtime.dashboard.url != null) 1 else 0
    }

    private fun reset(source: CommandSourceStack): Int {
        val runtime = runningNarrator(source) ?: return 0
        val dashboard = runtime.dashboard
        if (dashboard.url == null) {
            lines(dashboard, source.isPlayer).forEach { line -> source.sendSuccess({ line }, false) }
            return 0
        }
        dashboard.resetToken()
        Decisions(runtime.trace, runtime.clock).record(
            log, DecisionRecord.Kind.OPERATOR, "dashboard_reset", subject = source.textName,
            reason = "a new token for the dashboard: the browsers signed in are signed out, the links given before no longer work",
        )
        source.sendSuccess({ narratorLine("new dashboard token: every browser is signed out.") }, false)
        lines(dashboard, source.isPlayer).drop(1).forEach { line -> source.sendSuccess({ line }, false) }
        return 1
    }

    /**
     * The reply of `/narrator dashboard`: where the dashboard serves and the login link, clickable for a player, written
     * out for a console ([player] false); or why there is no dashboard.
     */
    internal fun lines(dashboard: Dashboard, player: Boolean): List<Component> {
        val url = dashboard.url
        val login = dashboard.loginUrl()
        if (url == null || login == null) {
            return listOf(
                errorLine("no dashboard: ${dashboard.unavailableReason}."),
                detailLine("dashboard.enabled and dashboard.port are read when the server starts (narrator2.json)."),
            )
        }
        val port = URI.create(url).port
        return buildList {
            add(narratorLine("dashboard on $url (read only, on this machine only)"))
            if (player) {
                val signIn = Component.literal("[Sign in]").withStyle { style ->
                    style.withColor(ChatFormatting.AQUA).withUnderlined(true)
                        .withClickEvent(ClickEvent.OpenUrl(URI.create(login)))
                        .withHoverEvent(HoverEvent.ShowText(Component.literal("Opens the dashboard in your browser, signed in")))
                }
                add(Component.empty().append(signIn).append(detailLine(" the link holds the token: keep it to yourself")))
            } else {
                add(detailLine("Sign in: $login"))
                add(detailLine("The console writes this reply to logs/latest.log: once signed in, /narrator dashboard reset draws a new token."))
            }
            add(detailLine("From another machine: ssh -L $port:localhost:$port to this server, then the same link."))
        }
    }
}
