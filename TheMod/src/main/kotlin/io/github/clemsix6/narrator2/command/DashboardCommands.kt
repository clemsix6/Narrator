package io.github.clemsix6.narrator2.command

import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.github.clemsix6.narrator2.core.Log
import io.github.clemsix6.narrator2.trace.DecisionRecord
import io.github.clemsix6.narrator2.trace.Decisions
import io.github.clemsix6.narrator2.web.Dashboard
import io.github.clemsix6.narrator2.web.DisabledDashboard
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import java.net.URI

/**
 * `/narrator dashboard` and `/narrator dashboard reset` (M6 §13): a login link to the dashboard, or why there is none
 * and how to turn it on (it is off by default); a new token, which signs every browser out.
 *
 * Each call draws a new link, whose code is good for one sign-in within 10 minutes ([Dashboard.newLoginUrl]). To a
 * player it comes as a click (`ClickEvent.OpenUrl`) on a text that does not show it: the client's log keeps the text of
 * the chat, not its clicks. A console has no click: it gets the link written out, which Minecraft copies to
 * `logs/latest.log`, where it is dead once used. The mod never logs a code nor the token.
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
     * The reply of `/narrator dashboard`: where the dashboard serves and a new login link, clickable for a player,
     * written out for a console ([player] false); or why there is no dashboard, and how to have one.
     */
    internal fun lines(dashboard: Dashboard, player: Boolean): List<Component> {
        val url = dashboard.url
        val login = dashboard.newLoginUrl()
        if (url == null || login == null) {
            val how = if (dashboard.unavailableReason == DisabledDashboard.SWITCHED_OFF) {
                "The dashboard is optional and off by default. To turn it on, set \"dashboard\": { \"enabled\": true } in " +
                    "$CONFIG_FILE, then run /narrator restart (or restart the server)."
            } else {
                "dashboard.enabled and dashboard.port ($CONFIG_FILE) are read when the Narrator starts: /narrator restart applies a change."
            }
            return listOf(errorLine("no dashboard: ${dashboard.unavailableReason}."), detailLine(how))
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
                add(Component.empty().append(signIn).append(detailLine(" a link for one sign-in, within 10 minutes: keep it to yourself")))
            } else {
                add(detailLine("Sign in: $login"))
                add(detailLine("This link works once, within 10 minutes: run /narrator dashboard again for another."))
            }
            add(detailLine("From another machine: ssh -L $port:localhost:$port to this server, then the link."))
        }
    }

    /** Where an operator turns the dashboard on, from the server's folder. */
    private const val CONFIG_FILE = "config/narrator2/narrator2.json"
}
