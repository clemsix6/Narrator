package io.github.clemsix6.narrator2.command

import io.github.clemsix6.narrator2.web.Dashboard
import io.github.clemsix6.narrator2.web.DisabledDashboard
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DashboardCommandsTest {
    private val code = "c".repeat(32)

    private val dashboard = object : Dashboard {
        override val url = "http://127.0.0.1:8770/"
        override val unavailableReason: String? = null
        override fun newLoginUrl() = "${url}login?key=$code"
        override fun resetToken() = Unit
        override fun stop() = Unit
    }

    @Test
    fun `a player gets the login link as a click, the code out of the text`() {
        val lines = DashboardCommands.lines(dashboard, player = true)
        val text = lines.joinToString("\n") { it.string }
        assertFalse(code in text, "the chat's text shows the code: $text")
        assertContains(text, "dashboard on http://127.0.0.1:8770/")
        assertContains(text, "[Sign in]")
        assertContains(text, "one sign-in, within 10 minutes")
        assertContains(text, "ssh -L 8770:localhost:8770")
        assertEquals(listOf(ClickEvent.OpenUrl(URI.create("http://127.0.0.1:8770/login?key=$code"))), lines.flatMap(::clicks))
    }

    @Test
    fun `a console gets the link written out, good once`() {
        val text = DashboardCommands.lines(dashboard, player = false).joinToString("\n") { it.string }
        assertContains(text, "Sign in: http://127.0.0.1:8770/login?key=$code")
        assertContains(text, "This link works once, within 10 minutes")
        assertFalse("reset" in text, "a used link is dead: no advice to reset after a sign-in from the console")
    }

    @Test
    fun `off by the configuration, it says how to turn it on`() {
        val text = DashboardCommands.lines(DisabledDashboard(DisabledDashboard.SWITCHED_OFF), player = true).joinToString("\n") { it.string }
        assertContains(text, "no dashboard: dashboard.enabled is false.")
        assertContains(text, "optional and off by default")
        assertContains(text, "\"dashboard\": { \"enabled\": true } in config/narrator2/narrator2.json")
        assertContains(text, "then run /narrator restart (or restart the server)")
    }

    @Test
    fun `a dashboard that could not start says why`() {
        val text = DashboardCommands.lines(DisabledDashboard("port 8770 of 127.0.0.1 is taken (Address already in use)"), player = false)
            .joinToString("\n") { it.string }
        assertContains(text, "no dashboard: port 8770 of 127.0.0.1 is taken (Address already in use).")
        assertContains(text, "read when the Narrator starts: /narrator restart applies a change")
        assertFalse("off by default" in text)
    }

    private fun clicks(component: Component): List<ClickEvent> = listOfNotNull(component.style.clickEvent) + component.siblings.flatMap(::clicks)
}
